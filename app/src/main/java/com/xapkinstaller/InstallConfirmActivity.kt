package com.xapkinstaller

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.os.Handler
import android.util.Log
import androidx.appcompat.app.AlertDialog

/**
 * 接收 PackageInstaller 安装结果的透明 Activity
 *
 * 关键修复：
 * 1. 收到 PENDING_USER_ACTION 后，先把自己拉到前台
 * 2. confirmIntent 加上 NEW_TASK flag
 * 3. 用 show()/hide() 确保窗口可见
 */
class InstallConfirmActivity : Activity() {

    companion object {
        private const val TAG = "InstallConfirm"
        const val ACTION_INSTALL_RESULT = "com.xapkinstaller.INSTALL_RESULT"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        private const val REQ_CONFIRM = 1001
    }

    private var packageName: String? = null
    private var pendingConfirmIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 关键：强制把 Activity 拉到前台
        val window = this.window
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(EXTRA_PACKAGE_NAME)

        Log.i(TAG, "收到安装结果: status=$status, msg=$message, pkg=$packageName")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    Log.i(TAG, "拿到系统确认 Intent: ${confirmIntent.component}")
                    pendingConfirmIntent = confirmIntent

                    // 延迟 100ms 启动，确保本 Activity 窗口已创建
                    Handler(mainLooper).postDelayed({
                        launchConfirm()
                    }, 100)
                    return
                } else {
                    Log.e(TAG, "PENDING_USER_ACTION 但没有 EXTRA_INTENT")
                    showResult(false, "安装失败", "系统未提供安装确认界面")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                showResult(true, "安装成功", "$packageName 已安装成功！")
            }
            else -> {
                val errText = errorText(status, message)
                Log.e(TAG, "安装失败: $errText")
                showResult(false, "安装失败", errText)
            }
        }
    }

    private fun launchConfirm() {
        val confirmIntent = pendingConfirmIntent ?: run {
            showResult(false, "安装失败", "确认 Intent 丢失")
            return
        }

        try {
            // 加上 NEW_TASK 确保系统确认界面能弹到前台
            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            Log.i(TAG, "启动系统安装确认界面")
            startActivityForResult(confirmIntent, REQ_CONFIRM)
        } catch (e: Exception) {
            Log.e(TAG, "启动确认界面失败", e)
            showResult(false, "安装失败", "无法启动安装确认: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "确认结果: requestCode=$requestCode, resultCode=$resultCode")

        if (requestCode == REQ_CONFIRM) {
            if (resultCode == RESULT_OK) {
                showResult(true, "安装成功", "$packageName 已安装成功！")
            } else {
                showResult(false, "安装取消", "安装已被取消或失败")
            }
        }
    }

    private fun showResult(success: Boolean, title: String, message: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(if (success) "打开应用" else "好的") { _, _ ->
                    if (success && packageName != null) {
                        try {
                            val launch = packageManager.getLaunchIntentForPackage(packageName!!)
                            if (launch != null) startActivity(launch)
                        } catch (_: Exception) {}
                    }
                    finish()
                }
                .setNegativeButton("关闭") { _, _ -> finish() }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示结果对话框失败", e)
            finish()
        }
    }

    private fun errorText(status: Int, message: String): String = when (status) {
        PackageInstaller.STATUS_FAILURE -> "安装失败${if (message.isNotBlank()) ": $message" else ""}"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "安装已取消"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被阻止"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "安装冲突（可能已存在签名不同的同名应用，请先卸载旧版）"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装包与系统不兼容 (status=$status)"
        PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
        else -> "未知错误 (code=$status)${if (message.isNotBlank()) ": $message" else ""}"
    }
}
