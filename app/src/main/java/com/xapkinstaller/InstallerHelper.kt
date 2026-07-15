package com.xapkinstaller

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
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
 * 单 APK → ACTION_VIEW + FileProvider
 * Split APK → PackageInstaller.Session，commit 后系统自动处理
 */
class InstallerHelper(private val context: Context) {

    companion object {
        private const val TAG = "InstallerHelper"
        private const val INSTALL_DIR = "xapk_extracted"
    }

    private val parser = XAPKParser(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 安装 XAPK
     *
     * @param onSessionReady 当 Split APK session commit 完成后回调
     *        Activity 需要在此回调中调 startIntentSenderForResult 来触发系统安装确认界面
     */
    fun install(
        xapkFile: File,
        listener: InstallListener,
        onSessionCommitted: ((intentSender: IntentSender, packageName: String?) -> Unit)? = null,
        onInstallResult: ((success: Boolean, message: String, packageName: String?) -> Unit)? = null
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

                // 检查权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !context.packageManager.canRequestPackageInstalls()
                ) {
                    listener.onError("请先允许安装未知应用")
                    return@Thread
                }

                // 提取 APK
                val installDir = File(context.cacheDir, INSTALL_DIR)
                installDir.deleteRecursively()
                installDir.mkdirs()

                val extractedApks = mutableListOf<File>()
                for (apk in content.apkEntries) {
                    listener.onProgress("提取中: ${apk.name}")
                    val apkFile = File(installDir, apk.name)
                    val r = parser.extractEntry(xapkFile, apk.name, apkFile)
                    if (r.isFailure) {
                        listener.onError("${apk.name} 提取失败")
                        return@Thread
                    }
                    extractedApks.add(apkFile)
                }

                if (extractedApks.size == 1) {
                    // 单 APK → 系统安装器
                    listener.onProgress("启动系统安装器...")
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", extractedApks.first()
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    listener.onComplete(content)
                } else {
                    // Split APK → PackageInstaller.Session
                    listener.onProgress("准备安装 ${extractedApks.size} 个 Split APK...")

                    val pkgInstaller = context.packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL
                    )
                    val sessionId = pkgInstaller.createSession(params)

                    try {
                        // 写入所有 APK
                        pkgInstaller.openSession(sessionId).use { session ->
                            for ((i, apkFile) in extractedApks.withIndex()) {
                                listener.onProgress("写入 ${i + 1}/${extractedApks.size}: ${apkFile.name}")
                                session.openWrite(apkFile.name, 0, apkFile.length()).use { out ->
                                    FileInputStream(apkFile).use { it.copyTo(out) }
                                }
                            }

                            // 创建 PendingIntent：用 getActivity 而非 getBroadcast
                            // 这样系统会直接启动确认界面 Activity
                            val resultIntent = Intent(context, InstallConfirmActivity::class.java).apply {
                                action = InstallConfirmActivity.ACTION_INSTALL_RESULT
                                putExtra(InstallConfirmActivity.EXTRA_PACKAGE_NAME, content.packageName)
                            }

                            val pendingIntent = android.app.PendingIntent.getActivity(
                                context,
                                sessionId,
                                resultIntent,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                    android.app.PendingIntent.FLAG_MUTABLE
                            )

                            // commit：系统收到后会处理安装
                            // 某些设备会直接安装，某些会弹确认框
                            session.commit(pendingIntent.intentSender)

                            Log.i(TAG, "Session 已 commit")
                        }

                        // 通知 Activity：session 已提交，等待结果
                        mainHandler.post {
                            onInstallResult?.let { cb ->
                                // 不在这里调 cb，等 InstallConfirmActivity 回传结果
                            }
                            listener.onProgress("安装请求已提交，请查看系统通知或弹窗...")
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Session 失败", e)
                        try { pkgInstaller.abandonSession(sessionId) } catch (_: Exception) {}
                        listener.onError("安装失败: ${e.message}")
                    }
                }

                // OBB
                if (content.obbEntries.isNotEmpty()) {
                    listener.onProgress("复制 OBB 数据...")
                    for (obb in content.obbEntries) {
                        val target = parser.getObbTargetPath(obb.name, content.packageName)
                        parser.extractEntry(xapkFile, obb.name, target)
                    }
                }

            } catch (e: Exception) {
                listener.onError("安装失败: ${e.message}")
                Log.e(TAG, "安装失败", e)
            }
        }.start()
    }

    interface InstallListener {
        fun onProgress(message: String)
        fun onComplete(content: XAPKContent)
        fun onError(message: String)
    }
}
