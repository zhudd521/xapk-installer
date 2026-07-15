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
 * Split APK → PackageInstaller.Session + getActivity PendingIntent
 *
 * 关键：commit() 在主线程执行，确保系统确认界面立即弹出
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
                        // 打开 session（不用 use{}，因为 commit 必须在 session 关闭前执行）
                        val session = pkgInstaller.openSession(sessionId)

                        // 写入所有 APK
                        for ((i, apkFile) in extractedApks.withIndex()) {
                            listener.onProgress("写入 ${i + 1}/${extractedApks.size}: ${apkFile.name}")
                            Log.i(TAG, "写入: ${apkFile.name}")
                            session.openWrite(apkFile.name, 0, apkFile.length()).use { out ->
                                FileInputStream(apkFile).use { it.copyTo(out) }
                            }
                        }

                        // 创建 PendingIntent
                        val resultIntent = Intent(context, InstallConfirmActivity::class.java).apply {
                            action = InstallConfirmActivity.ACTION_INSTALL_RESULT
                            putExtra(InstallConfirmActivity.EXTRA_PACKAGE_NAME, content.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }

                        val pendingIntent = android.app.PendingIntent.getActivity(
                            context,
                            sessionId,
                            resultIntent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                android.app.PendingIntent.FLAG_MUTABLE
                        )

                        // 在主线程执行 commit，确保系统确认界面立即弹出
                        listener.onProgress("提交安装请求...")
                        val intentSender = pendingIntent.intentSender

                        mainHandler.post {
                            try {
                                // commit 后 session 会被系统接管，不需要手动 close
                                session.commit(intentSender)
                                Log.i(TAG, "Session 已 commit")
                            } catch (e: Exception) {
                                Log.e(TAG, "commit 失败", e)
                                try { session.close() } catch (_: Exception) {}
                                try { pkgInstaller.abandonSession(sessionId) } catch (_: Exception) {}
                                listener.onError("提交安装失败: ${e.message}")
                            }
                        }

                        mainHandler.post {
                            listener.onProgress("安装请求已提交，请在弹出的界面确认安装...")
                        }

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
