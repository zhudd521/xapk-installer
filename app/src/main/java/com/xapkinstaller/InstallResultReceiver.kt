package com.xapkinstaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 接收 PackageInstaller.Session 的安装结果回调
 */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InstallResultReceiver"

        var listener: InstallCallback? = null

        interface InstallCallback {
            fun onInstallSuccess()
            fun onInstallError(status: Int, message: String)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)
        val message = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?: getStatusText(status)
        val packageName = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME)

        Log.i(TAG, "安装结果: status=$status, msg=$message, pkg=$packageName")

        when (status) {
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "安装成功: $packageName")
                listener?.onInstallSuccess()
            }
            else -> {
                Log.e(TAG, "安装失败: $message")
                listener?.onInstallError(status, message)
            }
        }
    }

    private fun getStatusText(status: Int): String {
        return when (status) {
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> "安装成功"
            android.content.pm.PackageInstaller.STATUS_FAILURE -> "安装失败"
            android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> "安装已中止"
            android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被阻止"
            android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT -> "安装冲突"
            android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装包不兼容"
            android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效"
            android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
            else -> "未知错误 (status=$status)"
        }
    }
}
