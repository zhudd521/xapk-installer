package com.xapkinstaller

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 接收 PackageInstaller 安装结果的透明 Activity
 *
 * PackageInstaller.commit() 的 PendingIntent 指向这里
 * 系统会在安装完成（成功/失败/需要确认）时启动此 Activity
 */
class InstallConfirmActivity : Activity() {

    companion object {
        private const val TAG = "InstallConfirmActivity"
        const val ACTION_INSTALL_RESULT = "com.xapkinstaller.INSTALL_RESULT"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?: statusText(status)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(EXTRA_PACKAGE_NAME)

        Log.i(TAG, "安装结果: status=$status, msg=$message, pkg=$packageName")

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                // 安装成功
                showResult(true, "安装成功！", "$packageName 已安装成功，可以使用了。", packageName)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 系统需要用户确认 → 获取确认 Intent 并启动
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    Log.i(TAG, "启动系统安装确认界面")
                    startActivityForResult(confirmIntent, 1)
                    // 不 finish，等确认结果
                    return
                } else {
                    showResult(false, "安装失败", "系统要求确认但未提供确认界面", packageName)
                }
            }
            else -> {
                // 其他错误
                showResult(false, "安装失败", "错误码: $status\n$message", packageName)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "确认结果: requestCode=$requestCode, resultCode=$resultCode")

        if (resultCode == RESULT_OK) {
            showResult(true, "安装成功！", "应用已安装成功。", null)
        } else {
            showResult(false, "安装取消", "用户取消了安装或安装失败。", null)
        }
    }

    private fun showResult(success: Boolean, title: String, message: String, packageName: String?) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(if (success) "打开应用" else "好的") { _, _ ->
                if (success && packageName != null) {
                    try {
                        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            startActivity(launchIntent)
                        }
                    } catch (_: Exception) {}
                }
                finish()
            }
            .setNegativeButton("关闭") { _, _ -> finish() }
            .show()
    }

    private fun statusText(status: Int): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "安装成功"
        PackageInstaller.STATUS_PENDING_USER_ACTION -> "等待用户确认"
        PackageInstaller.STATUS_FAILURE -> "安装失败"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "安装已取消"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被阻止"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "安装冲突（签名不同的同名应用）"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "不兼容"
        PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
        else -> "未知错误 ($status)"
    }
}
