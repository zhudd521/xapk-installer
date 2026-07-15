package com.xapkinstaller

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * XAPK 安装辅助类
 */
class InstallerHelper(private val context: Context) {

    companion object {
        private const val TAG = "InstallerHelper"
        private const val NOTIFICATION_CHANNEL_ID = "xapk_install"
        private const val NOTIFICATION_ID = 1001
        private const val INSTALL_DIR = "xapk_extracted"
    }

    private val parser = XAPKParser(context)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "XAPK 安装进度",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示 XAPK 安装状态"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 安装 XAPK 文件（完整流程）
     */
    fun install(xapkFile: File, listener: InstallListener) {
        Thread {
            try {
                listener.onProgress("正在解析 XAPK 文件...")

                // 解析 XAPK
                val parseResult = parser.parse(xapkFile)
                if (parseResult.isFailure) {
                    listener.onError("解析失败: ${parseResult.exceptionOrNull()?.message}")
                    return@Thread
                }

                val content = parseResult.getOrThrow()

                if (content.apkEntry == null) {
                    listener.onError("XAPK 中未找到 APK 文件")
                    return@Thread
                }

                listener.onProgress("正在提取 APK...")

                // 创建临时目录
                val installDir = File(context.cacheDir, INSTALL_DIR)
                installDir.mkdirs()

                // 提取 APK
                val apkFile = File(installDir, content.apkEntry.name)
                val extractResult = parser.extractEntry(xapkFile, content.apkEntry.name, apkFile)
                if (extractResult.isFailure) {
                    listener.onError("APK 提取失败: ${extractResult.exceptionOrNull()?.message}")
                    return@Thread
                }

                // 安装 APK（启动系统安装器）
                listener.onProgress("正在启动系统安装器...")
                installApk(apkFile)

                // 安装 OBB
                if (content.obbEntries.isNotEmpty()) {
                    listener.onProgress("正在安装 OBB 扩展数据...")
                    for (obb in content.obbEntries) {
                        val obbTarget = parser.getObbTargetPath(
                            obb.name,
                            content.packageName
                        )
                        val obbResult = parser.extractEntry(xapkFile, obb.name, obbTarget)
                        if (obbResult.isSuccess) {
                            Log.i(TAG, "OBB 已复制: ${obbTarget.absolutePath}")
                        } else {
                            Log.w(TAG, "OBB 复制失败: ${obb.name}")
                        }
                    }
                }

                listener.onComplete(content)
            } catch (e: Exception) {
                listener.onError("安装失败: ${e.message}")
                Log.e(TAG, "安装失败", e)
            }
        }.apply { start() }
    }

    /**
     * 启动系统 APK 安装器
     */
    private fun installApk(apkFile: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }

            // 有些设备需要额外的来源设置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                }
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动安装器失败", e)
            // 备用方案：直接尝试
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            apkFile
                        ),
                        "application/vnd.android.package-archive"
                    )
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "备用安装方案也失败", e2)
            }
        }
    }

    /**
     * 请求必要权限
     */
    fun requestPermissionsIfNeeded(activity: androidx.fragment.app.FragmentActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                activity.startActivity(intent)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = mutableListOf<String>()
            if (context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (permissions.isNotEmpty()) {
                activity.requestPermissions(permissions.toTypedArray(), 100)
            }
        }
    }

    interface InstallListener {
        fun onProgress(message: String)
        fun onComplete(content: XAPKContent)
        fun onError(message: String)
    }
}
