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
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

/**
 * XAPK 安装辅助类
 * - 单 APK：系统安装器 ACTION_VIEW
 * - 多 APK（Split）：PackageInstaller.Session + PendingIntent 广播回调
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
        listener: InstallListener,
        sessionCallback: ((success: Boolean, message: String, packageName: String?) -> Unit)? = null
    ) {
        Thread {
            try {
                listener.onProgress("正在解析 XAPK 文件...")
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

                Log.i(TAG, "发现 ${content.apkEntries.size} 个 APK")

                // 检查安装未知来源权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !context.packageManager.canRequestPackageInstalls()
                ) {
                    mainHandler.post {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                    }
                    listener.onError("请先在设置中允许「安装未知应用」，然后重试")
                    return@Thread
                }

                // 创建临时目录并提取所有 APK
                val installDir = File(context.cacheDir, INSTALL_DIR)
                installDir.deleteRecursively()
                installDir.mkdirs()

                val extractedApks = mutableListOf<File>()
                for (apk in content.apkEntries) {
                    listener.onProgress("正在提取: ${apk.name}")
                    val apkFile = File(installDir, apk.name)
                    val result = parser.extractEntry(xapkFile, apk.name, apkFile)
                    if (result.isFailure) {
                        listener.onError("${apk.name} 提取失败: ${result.exceptionOrNull()?.message}")
                        return@Thread
                    }
                    extractedApks.add(apkFile)
                    Log.i(TAG, "已提取: ${apkFile.name} (${apkFile.length()} bytes)")
                }

                if (extractedApks.size == 1) {
                    // ── 单 APK：直接调系统安装器 ──
                    listener.onProgress("正在启动系统安装器...")
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        extractedApks.first()
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    listener.onComplete(content)
                } else {
                    // ── Split APK：PackageInstaller.Session ──
                    listener.onProgress("正在准备 Split APK 安装（共 ${extractedApks.size} 个包）...")

                    val pm = context.packageManager
                    val installer = pm.packageInstaller
                    val params = PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL
                    )
                    val sessionId = installer.createSession(params)
                    Log.i(TAG, "创建安装会话: sessionId=$sessionId")

                    try {
                        // 写入所有 APK 到 session
                        installer.openSession(sessionId).use { session ->
                            for ((index, apkFile) in extractedApks.withIndex()) {
                                val name = apkFile.name
                                listener.onProgress("写入 $name (${index + 1}/${extractedApks.size})...")
                                Log.i(TAG, "写入 $name (${index + 1}/${extractedApks.size})")

                                session.openWrite(name, 0, apkFile.length()).use { out ->
                                    FileInputStream(apkFile).use { input ->
                                        input.copyTo(out)
                                    }
                                }
                            }

                            // 创建 PendingIntent 接收安装结果
                            val resultIntent = Intent(context, InstallResultReceiver::class.java).apply {
                                action = InstallResultReceiver.ACTION_INSTALL_COMPLETE
                            }

                            // 设置回调
                            InstallResultReceiver.pendingCallback = object : InstallCallback {
                                override fun onInstallSuccess(pkg: String?) {
                                    mainHandler.post {
                                        sessionCallback?.invoke(true, "安装成功", pkg)
                                    }
                                }
                                override fun onInstallError(status: Int, errCode: Int, msg: String) {
                                    mainHandler.post {
                                        sessionCallback?.invoke(false, msg, content.packageName)
                                    }
                                }
                            }

                            val pendingIntent = android.app.PendingIntent.getBroadcast(
                                context,
                                sessionId,
                                resultIntent,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                    android.app.PendingIntent.FLAG_MUTABLE
                            )

                            // commit：提交后系统会处理安装
                            // 注意：普通应用 commit 后系统会弹出安装确认对话框
                            listener.onProgress("已提交安装请求，请在弹出的系统对话框中确认安装...")
                            session.commit(pendingIntent.intentSender)
                        }

                        Log.i(TAG, "Split APK 安装已提交，等待系统处理...")
                        // 不调用 onComplete，等广播回调
                    } catch (e: Exception) {
                        Log.e(TAG, "Split APK 安装失败", e)
                        try { installer.abandonSession(sessionId) } catch (_: Exception) {}
                        listener.onError("安装提交失败: ${e.message}")
                    }
                }

                // 复制 OBB
                if (content.obbEntries.isNotEmpty()) {
                    listener.onProgress("正在复制 OBB 扩展数据...")
                    for (obb in content.obbEntries) {
                        val obbTarget = parser.getObbTargetPath(obb.name, content.packageName)
                        parser.extractEntry(xapkFile, obb.name, obbTarget)
                    }
                }

            } catch (e: Exception) {
                listener.onError("安装失败: ${e.message}")
                Log.e(TAG, "安装失败", e)
            }
        }.apply { start() }
    }

    interface InstallListener {
        fun onProgress(message: String)
        fun onComplete(content: XAPKContent)
        fun onError(message: String)
    }
}
