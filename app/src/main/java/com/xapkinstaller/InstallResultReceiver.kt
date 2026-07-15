package com.xapkinstaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Split APK 安装结果回调
 */
interface InstallCallback {
    fun onInstallSuccess(packageName: String?)
    fun onInstallError(status: Int, errorCode: Int, message: String)
}

/**
 * 接收 PackageInstaller 安装结果广播
 */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InstallResultReceiver"
        const val ACTION_INSTALL_COMPLETE = "com.xapkinstaller.INSTALL_COMPLETE"

        @Volatile
        var pendingCallback: InstallCallback? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            android.content.pm.PackageInstaller.EXTRA_STATUS, -999)
        val statusMessage = intent.getStringExtra(
            android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: statusText(status)
        val packageName = intent.getStringExtra(
            android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME)

        Log.i(TAG, "安装结果: status=$status, msg=$statusMessage, pkg=$packageName")

        val cb = pendingCallback
        pendingCallback = null

        when (status) {
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "安装成功: $packageName")
                cb?.onInstallSuccess(packageName)
            }
            else -> {
                Log.e(TAG, "安装失败: code=$status, $statusMessage")
                cb?.onInstallError(status, status, statusMessage)
            }
        }
    }

    private fun statusText(status: Int): String = when (status) {
        android.content.pm.PackageInstaller.STATUS_SUCCESS -> "安装成功"
        android.content.pm.PackageInstaller.STATUS_FAILURE -> "安装失败"
        android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> "安装已取消"
        android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被阻止"
        android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT -> "安装冲突（可能已存在签名不同的同名应用）"
        android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装包与系统不兼容"
        android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效"
        android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
        else -> "未知错误 (code=$status)"
    }
}
