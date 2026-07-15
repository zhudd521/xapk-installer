package com.xapkinstaller

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * XAPK 安装辅助类
 * 支持 Split APK 批量安装（通过 PackageInstaller.Session）
 */
class InstallerHelper(private val context: Context) {

    companion object {
        private const val TAG = "InstallerHelper"
        private const val INSTALL_DIR = "xapk_extracted"
        private const val SESSION_NAME = "XAPK Installer"
    }

    private val parser = XAPKParser(context)

    /**
     * 安装 XAPK 文件（完整流程）
     * 自动识别 Split APK 并使用 PackageInstaller.Session 批量安装
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

                if (content.apkEntries.isEmpty()) {
                    listener.onError("XAPK 中未找到 APK 文件")
                    return@Thread
                }

                Log.i(TAG, "发现 ${content.apkEntries.size} 个 APK: ${content.apkEntries.joinToString { it.name }}")

                // 创建临时目录
                val installDir = File(context.cacheDir, INSTALL_DIR)
                installDir.deleteRecursively()
                installDir.mkdirs()

                // 提取所有 APK 到临时目录
                val extractedApks = mutableListOf<File>()
                for (apk in content.apkEntries) {
                    listener.onProgress("正在提取: ${apk.name}")
                    val apkFile = File(installDir, apk.name)
                    val extractResult = parser.extractEntry(xapkFile, apk.name, apkFile)
                    if (extractResult.isFailure) {
                        listener.onError("${apk.name} 提取失败: ${extractResult.exceptionOrNull()?.message}")
                        return@Thread
                    }
                    extractedApks.add(apkFile)
                    Log.i(TAG, "已提取: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
                }

                // 根据 APK 数量选择安装方式
                if (extractedApks.size == 1) {
                    // 单个 APK：直接使用系统安装器
                    listener.onProgress("正在启动系统安装器...")
                    installSingleApk(extractedApks.first(), listener)
                } else {
                    // 多个 APK：使用 PackageInstaller.Session 批量安装
                    listener.onProgress("正在准备 Split APK 安装...")
                    installSplitApks(extractedApks, content.packageName, listener)
                }

                // 安装 OBB
                if (content.obbEntries.isNotEmpty()) {
                    listener.onProgress("正在安装 OBB 扩展数据...")
                    for (obb in content.obbEntries) {
                        val obbTarget = parser.getObbTargetPath(obb.name, content.packageName)
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
     * 安装单个 APK（系统安装器）
     */
    private fun installSingleApk(apkFile: File, listener: InstallListener) {
        try {
            // 检查安装未知来源权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(settingsIntent)
                    listener.onProgress("请在设置中允许安装未知应用")
                    return
                }
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动安装器失败", e)
            throw e
        }
    }

    /**
     * 使用 PackageInstaller.Session 批量安装 Split APK
     */
    private fun installSplitApks(
        apkFiles: List<File>,
        packageName: String?,
        listener: InstallListener
    ) {
        val installer = context.packageManager.packageInstaller
        val sessionParams = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )

        // Android 5.0+ 支持
        val sessionId = installer.createSession(sessionParams)
        Log.i(TAG, "创建安装会话: $sessionId, ${apkFiles.size} 个 APK")

        try {
            installer.openSession(sessionId).use { session ->
                // 逐个写入 APK
                for ((index, apkFile) in apkFiles.withIndex()) {
                    val name = apkFile.name
                    listener.onProgress("写入 $name (${index + 1}/${apkFiles.size})...")

                    val sizeBytes = apkFile.length()
                    session.openWrite(name, 0, sizeBytes).use { out ->
                        FileInputStream(apkFile).use { input ->
                            val buffer = ByteArray(65536)
                            var written = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                written += read
                            }
                        }
                    }

                    Log.i(TAG, "已写入: $name (${sizeBytes} bytes)")
                }
            }

            // 提交安装（通过 PendingIntent 回调结果）
            val intentSender = createCommitCallback(listener).intentSender
            installer.openSession(sessionId).use { session ->
                session.commit(intentSender)
            }

            Log.i(TAG, "安装提交成功")
        } catch (e: Exception) {
            Log.e(TAG, "Split APK 安装失败", e)
            // 尝试终止会话
            try { installer.abandonSession(sessionId) } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * 创建安装完成的回调 Intent
     */
    private fun createCommitCallback(listener: InstallListener): android.app.PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java)
        return android.app.PendingIntent.getBroadcast(
            context,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
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
