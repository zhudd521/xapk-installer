package com.xapkinstaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Split APK 安装结果回调（Activity 实现此接口接收结果）
 */
interface InstallCallback {
    fun onInstallSuccess(packageName: String?)
    fun onInstallError(status: Int, errorCode: Int, message: String)
}

/**
 * 接收 PackageInstaller.Session 的安装结果广播
 * 动态注册在 Activity 中，不在 Manifest 静态声明
 */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InstallResultReceiver"
        const val ACTION_INSTALL_COMPLETE = "com.xapkinstaller.INSTALL_COMPLETE"

        // 单实例回调，commit 前设置
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

        Log.i(TAG, "安装结果广播: status=$status, msg=$statusMessage, pkg=$packageName")

        val cb = pendingCallback
        pendingCallback = null  // 防止泄漏

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
        android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> "安装已中止"
        android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被阻止"
        android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT -> "安装冲突（可能已安装签名不同的同名版本）"
        android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装包与系统不兼容"
        android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效"
        android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
        else -> "未知错误 (code=$status)"
    }
}
