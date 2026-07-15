package com.xapkinstaller

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog

/**
 * 接收 PackageInstaller 安装结果的透明 Activity
 *
 * 关键：当系统返回 STATUS_PENDING_USER_ACTION 时，
 * Intent 里会带 EXTRA_INTENT 指向系统安装确认界面。
 * 必须启动它，用户确认后才能真正安装。
 */
class InstallConfirmActivity : Activity() {

    companion object {
        private const val TAG = "InstallConfirm"
        const val ACTION_INSTALL_RESULT = "com.xapkinstaller.INSTALL_RESULT"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        private const val REQ_CONFIRM = 1001
    }

    private var packageName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(EXTRA_PACKAGE_NAME)

        Log.i(TAG, "收到安装结果: status=$status, msg=$message, pkg=$packageName")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 系统要求用户确认 → 获取确认 Intent 并启动
                val confirmIntent: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    Log.i(TAG, "启动系统安装确认界面")
                    startActivityForResult(confirmIntent, REQ_CONFIRM)
                    return  // 不 finish，等确认结果
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
