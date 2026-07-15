package com.xapkinstaller

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

/**
 * XAPK 安装辅助类
 *
 * 策略：
 * - 单 APK → ACTION_VIEW + FileProvider，系统安装器弹窗确认
 * - Split APK → 同样用 ACTION_VIEW 打开 base.apk
 *   Android 5.0+ 系统安装器支持同目录下多 APK 批量安装
 *   （需要用 FileProvider 暴露整个目录）
 *
 * 不再使用 PackageInstaller.Session（普通应用无 INSTALL_PACKAGES 权限，commit 会被静默忽略）
 */
class InstallerHelper(private val context: Context) {

    companion object {
        private const val TAG = "InstallerHelper"
        private const val INSTALL_DIR = "xapk_extracted"
    }

    private val parser = XAPKParser(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun install(
        xapkFile: File,
        listener: InstallListener
    ) {
        Thread {
            try {
                listener.onProgress("正在解析 XAPK...")
                val parseResult = parser.parse(xapkFile)
                if (parseResult.isFailure) {
                    listener.onError("解析失败: ${parseResult.exceptionOrNull()?.message}")
                    return@Thread
                }

                val content = parseResult.getOrThrow()
                if (content.apkEntries.isEmpty()) {
                    listener.onError("未找到 APK 文件")
                    return@Thread
                }

                // 检查安装权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !context.packageManager.canRequestPackageInstalls()
                ) {
                    listener.onError("请先允许安装未知应用")
                    return@Thread
                }

                // 提取所有 APK 到同一目录
                val installDir = File(context.cacheDir, INSTALL_DIR)
                installDir.deleteRecursively()
                installDir.mkdirs()

                val extractedApks = mutableListOf<File>()
                for (apk in content.apkEntries) {
                    listener.onProgress("正在提取: ${apk.name}")
                    val apkFile = File(installDir, apk.name)
                    val r = parser.extractEntry(xapkFile, apk.name, apkFile)
                    if (r.isFailure) {
                        listener.onError("${apk.name} 提取失败")
                        return@Thread
                    }
                    extractedApks.add(apkFile)
                    Log.i(TAG, "已提取: ${apkFile.name} (${apkFile.length()} bytes)")
                }

                // 找到 base.apk（或第一个 APK）
                val baseApk = extractedApks.find { it.name == "base.apk" }
                    ?: extractedApks.first()

                Log.i(TAG, "使用 ${baseApk.name} 作为主 APK，共 ${extractedApks.size} 个 APK")

                // 复制 OBB（在启动安装器之前完成）
                if (content.obbEntries.isNotEmpty()) {
                    listener.onProgress("正在复制 OBB 数据...")
                    for (obb in content.obbEntries) {
                        val target = parser.getObbTargetPath(obb.name, content.packageName)
                        parser.extractEntry(xapkFile, obb.name, target)
                    }
                }

                // 用 ACTION_VIEW 启动系统安装器
                listener.onProgress("正在启动系统安装器...")

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    baseApk
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                }

                // 授予同目录下所有 APK 的读权限（让系统安装器能读到 split APK）
                for (apkFile in extractedApks) {
                    if (apkFile != baseApk) {
                        val splitUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            apkFile
                        )
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        // 给系统安装器授权
                        context.grantUriPermission(
                            "com.android.packageinstaller",
                            splitUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }

                mainHandler.post {
                    context.startActivity(intent)
                    listener.onComplete(content)
                }

            } catch (e: Exception) {
                Log.e(TAG, "安装失败", e)
                listener.onError("安装失败: ${e.message}")
            }
        }.start()
    }

    interface InstallListener {
        fun onProgress(message: String)
        fun onComplete(content: XAPKContent)
        fun onError(message: String)
    }
}
