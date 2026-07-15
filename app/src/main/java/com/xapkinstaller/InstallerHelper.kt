package com.xapkinstaller

import android.app.Activity
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
 * Split APK → PackageInstaller.Session
 *   commit 后系统返回 PENDING_USER_ACTION，
 *   直接用 Activity.startIntentSenderForResult 启动系统确认界面
 *   （不经过中间 Activity，避免 HyperOS/MIUI 后台启动限制）
 *
 * 关键：commit 的回调不通过 PendingIntent Activity，
 * 而是通过 Activity.startIntentSenderForResult 直接在前台接收
 */
class InstallerHelper(private val context: Context) {

    companion object {
        private const val TAG = "InstallerHelper"
        private const val INSTALL_DIR = "xapk_extracted"
        const val REQUEST_INSTALL = 9001
    }

    private val parser = XAPKParser(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * @param activity 用于 startIntentSenderForResult 的 Activity 引用
     * @param onInstallResult 安装结果回调（在 onActivityResult 中调用）
     */
    fun install(
        xapkFile: File,
        activity: Activity,
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

                // 提取所有 APK
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

                // 复制 OBB
                if (content.obbEntries.isNotEmpty()) {
                    listener.onProgress("正在复制 OBB 数据...")
                    for (obb in content.obbEntries) {
                        val target = parser.getObbTargetPath(obb.name, content.packageName)
                        parser.extractEntry(xapkFile, obb.name, target)
                    }
                }

                if (extractedApks.size == 1) {
                    // ── 单 APK：ACTION_VIEW ──
                    listener.onProgress("正在启动系统安装器...")
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", extractedApks.first()
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    mainHandler.post {
                        context.startActivity(intent)
                        listener.onComplete(content)
                    }
                } else {
                    // ── Split APK：PackageInstaller.Session ──
                    listener.onProgress("准备安装 ${extractedApks.size} 个 Split APK...")

                    val pkgInstaller = context.packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL
                    )
                    val sessionId = pkgInstaller.createSession(params)
                    Log.i(TAG, "创建 session: $sessionId")

                    try {
                        val session = pkgInstaller.openSession(sessionId)

                        // 写入所有 APK
                        for ((i, apkFile) in extractedApks.withIndex()) {
                            listener.onProgress("写入 ${i + 1}/${extractedApks.size}: ${apkFile.name}")
                            session.openWrite(apkFile.name, 0, apkFile.length()).use { out ->
                                FileInputStream(apkFile).use { it.copyTo(out) }
                            }
                        }

                        // 创建 PendingIntent —— 指向 MainActivity
                        // 不用 InstallConfirmActivity，直接回 MainActivity
                        val resultIntent = Intent(context, MainActivity::class.java)
                        resultIntent.action = MainActivity.ACTION_INSTALL_RESULT
                        resultIntent.putExtra(MainActivity.EXTRA_PACKAGE_NAME, content.packageName)
                        resultIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

                        val pendingIntent = android.app.PendingIntent.getActivity(
                            context,
                            sessionId,
                            resultIntent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                android.app.PendingIntent.FLAG_MUTABLE
                        )

                        // commit 后系统会触发 PENDING_USER_ACTION
                        // 系统会通过 pendingIntent 启动 MainActivity（singleTop），
                        // 在 onNewIntent 中收到 PENDING_USER_ACTION + EXTRA_INTENT
                        listener.onProgress("提交安装请求...")
                        session.commit(pendingIntent.intentSender)
                        Log.i(TAG, "Session 已 commit")

                        listener.onProgress("正在等待系统安装确认...")

                    } catch (e: Exception) {
                        Log.e(TAG, "Session 失败", e)
                        try { pkgInstaller.abandonSession(sessionId) } catch (_: Exception) {}
                        listener.onError("安装失败: ${e.message}")
                    }
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
