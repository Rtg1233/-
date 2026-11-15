package com.example.phonecheck

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var reportView: TextView
    private lateinit var refreshBtn: Button
    private lateinit var clearBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        reportView = findViewById(R.id.reportView)
        refreshBtn = findViewById(R.id.refreshBtn)
        clearBtn = findViewById(R.id.clearBtn)

        refreshBtn.setOnClickListener { runReport() }
        clearBtn.setOnClickListener {
            clearOwnCache()
            runReport()
        }

        runReport()
    }

    private fun runReport() {
        reportView.text = "Аудит выполняется…"
        thread {
            val report = buildReport()
            runOnUiThread { reportView.text = report }
        }
    }

    private fun buildReport(): String {
        val sb = StringBuilder()
        val pm = packageManager
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        sb.appendLine("=== Аудит устройства ===")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()

        // Память
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        sb.appendLine("Память: свободно ${fmt(free)} из ${fmt(total)}")
        sb.appendLine("Кэш этого приложения: ${fmt(dirSize(cacheDir))}")
        sb.appendLine()

        // Установленные приложения
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }

        val userApps = packages.filter {
            it.applicationInfo != null && (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0)
        }
        sb.appendLine("Пользовательских приложений: ${userApps.size}")

        val dangerous = setOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.BODY_SENSORS
        )

        val risky = mutableListOf<String>()
        for (p in userApps) {
            val granted = p.requestedPermissions
                ?.filter { perm ->
                    perm in dangerous &&
                        checkPermission(perm, p.packageName) == PackageManager.PERMISSION_GRANTED
                }
                ?: emptyList()

            if (granted.isNotEmpty()) {
                val label = p.applicationInfo?.loadLabel(pm) ?: p.packageName
                risky.add(
                    "$label (${p.packageName}): " +
                        granted.joinToString { it.substringAfterLast('.') }
                )
            }
        }

        sb.appendLine()
        sb.appendLine("Приложения с выданными опасными разрешениями: ${risky.size}")
        risky.take(20).forEach { sb.appendLine(" • $it") }
        if (risky.size > 20) sb.appendLine(" … и ещё ${risky.size - 20}")

        // Администраторы устройства
        val admins = dpm.activeAdmins?.map { it.packageName } ?: emptyList()
        sb.appendLine()
        sb.appendLine(
            "Администраторы устройства: " +
                if (admins.isEmpty()) "нет" else admins.joinToString()
        )

        // Службы специальных возможностей
        val acc = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val accPkgs = acc.split(':')
            .mapNotNull { it.substringBefore('/').takeIf { s -> s.isNotBlank() } }
            .distinct()

        sb.appendLine(
            "Службы спец. возможностей: " +
                if (accPkgs.isEmpty()) "нет" else accPkgs.joinToString()
        )

        // Поверх других окон и неизвестные источники
        val overlay = mutableListOf<String>()
        val installUnknown = mutableListOf<String>()

        for (p in userApps) {
            val uid = p.applicationInfo?.uid ?: continue
            if (isOpAllowed(appOps, "android:system_alert_window", uid, p.packageName)) {
                overlay.add(p.packageName)
            }
            if (Build.VERSION.SDK_INT >= 26 &&
                isOpAllowed(appOps, "android:request_install_packages", uid, p.packageName)
            ) {
                installUnknown.add(p.packageName)
            }
        }

        sb.appendLine()
        sb.appendLine(
            "Разрешение «поверх других окон»: " +
                if (overlay.isEmpty()) "нет" else overlay.joinToString()
        )
        sb.appendLine(
            "Установка из неизвестных источников: " +
                if (installUnknown.isEmpty()) "нет" else installUnknown.joinToString()
        )

        sb.appendLine()
        sb.appendLine("=== Рекомендации ===")
        if (risky.isNotEmpty()) {
            sb.appendLine("• Проверьте приложения с опасными разрешениями и отзовите лишние.")
        }
        if (admins.isNotEmpty()) {
            sb.appendLine("• Убедитесь, что администраторы устройства вам знакомы.")
        }
        if (accPkgs.isNotEmpty()) {
            sb.appendLine("• Проверьте службы спец. возможностей — это частый вектор вредоносного ПО.")
        }
        if (overlay.isNotEmpty()) {
            sb.appendLine("• Ограничьте «поверх других окон» для незнакомых приложений.")
        }
        if (installUnknown.isNotEmpty()) {
            sb.appendLine("• Отключите установку из неизвестных источников для незнакомых приложений.")
        }
        sb.appendLine("• Обновите систему и приложения. Удалите неиспользуемые приложения.")
        sb.appendLine("• Не устанавливайте «чистильщики» из неизвестных источников.")
        sb.appendLine()
        sb.appendLine("Это демо-аудит, а не антивирус: malware по сигнатурам не определяется.")

        return sb.toString()
    }

    private fun isOpAllowed(
        appOps: AppOpsManager,
        op: String,
        uid: Int,
        pkg: String
    ): Boolean {
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(op, uid, pkg)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun fmt(bytes: Long): String =
        Formatter.formatFileSize(this, bytes)

    private fun clearOwnCache() {
        cacheDir.deleteRecursively()
        externalCacheDir?.deleteRecursively()
    }
}
