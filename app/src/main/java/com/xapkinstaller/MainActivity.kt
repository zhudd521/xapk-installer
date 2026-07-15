package com.xapkinstaller

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.xapkinstaller.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity(), InstallerHelper.InstallListener {

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_INSTALL_RESULT = "com.xapkinstaller.INSTALL_RESULT"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        private const val REQ_SYSTEM_CONFIRM = 8001
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var parser: XAPKParser
    private lateinit var installer: InstallerHelper
    private var isInstalling = false
    private var installingPackageName: String? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { handleXAPKUri(it) } }

    private val installPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        parser = XAPKParser(this)
        installer = InstallerHelper(this)

        setupUI()
        // 处理可能的初始 Intent（从 PENDING_USER_ACTION 启动）
        handleInstallResultIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent: action=${intent.action}")
        handleInstallResultIntent(intent)
    }

    private fun setupUI() {
        binding.btnSelectFile.setOnClickListener {
            if (checkStoragePermission()) openFilePicker()
        }
        binding.btnInstall.setOnClickListener {
            val path = binding.currentFilePath.text.toString()
            if (path.isNotBlank() && !isInstalling) {
                val file = File(path)
                if (file.exists()) startInstall(file)
                else showToast("文件不存在")
            }
        }
        binding.btnSelectFile.performClick()
    }

    // ─── 处理 PackageInstaller 返回的 Intent ───

    private fun handleInstallResultIntent(intent: Intent?) {
        if (intent?.action == ACTION_INSTALL_RESULT) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                ?: intent.getStringExtra(EXTRA_PACKAGE_NAME)
            Log.i(TAG, "安装结果: status=$status, msg=$message, pkg=$pkg")

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // 系统要求用户确认 —— 直接在前台 Activity 启动！
                    val confirmIntent: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    if (confirmIntent != null) {
                        Log.i(TAG, "启动系统安装确认界面（前台）")
                        installingPackageName = pkg
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivityForResult(confirmIntent, REQ_SYSTEM_CONFIRM)
                    } else {
                        Log.e(TAG, "PENDING_USER_ACTION 但无 EXTRA_INTENT")
                        showInstallError("系统未提供安装确认界面")
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    showInstallSuccess(pkg)
                }
                else -> {
                    showInstallError("安装失败 (code=$status): $message")
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "onActivityResult: req=$requestCode, result=$resultCode")

        if (requestCode == REQ_SYSTEM_CONFIRM) {
            if (resultCode == RESULT_OK) {
                showInstallSuccess(installingPackageName)
            } else {
                showInstallError("安装已被取消或失败")
            }
        }
    }

    private fun showInstallSuccess(pkg: String?) {
        isInstalling = false
        binding.btnInstall.isEnabled = true
        binding.btnInstall.text = "安装"
        binding.progressBar.visibility = View.GONE
        binding.statusText.text = "✅ 安装成功！"

        AlertDialog.Builder(this)
            .setTitle("安装成功")
            .setMessage("$pkg 已安装成功！")
            .setPositiveButton("打开应用") { _, _ ->
                if (pkg != null) {
                    try {
                        val launch = packageManager.getLaunchIntentForPackage(pkg)
                        if (launch != null) startActivity(launch)
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showInstallError(msg: String) {
        isInstalling = false
        binding.btnInstall.isEnabled = true
        binding.btnInstall.text = "安装"
        binding.progressBar.visibility = View.GONE
        binding.statusText.text = "❌ $msg"

        AlertDialog.Builder(this)
            .setTitle("安装失败")
            .setMessage(msg)
            .setPositiveButton("好的", null)
            .show()
    }

    // ─── 文件选择 & 解析 ───

    private fun openFilePicker() {
        try { filePickerLauncher.launch(arrayOf("*/*")) }
        catch (_: Exception) { showToast("无法打开文件选择器") }
    }

    private fun handleXAPKUri(uri: Uri) {
        showStatus("正在读取文件...")
        binding.progressBar.visibility = View.VISIBLE
        binding.btnInstall.isEnabled = false

        Thread {
            try {
                val cacheFile = copyUriToCache(uri) ?: run {
                    runOnUiThread { showStatus("无法读取文件"); binding.progressBar.visibility = View.GONE }
                    return@Thread
                }
                val result = parser.parse(cacheFile)
                if (result.isFailure) {
                    runOnUiThread { showStatus("解析失败: ${result.exceptionOrNull()?.message}"); binding.progressBar.visibility = View.GONE }
                    return@Thread
                }
                val content = result.getOrThrow()
                runOnUiThread {
                    displayContent(content, cacheFile.absolutePath)
                    binding.progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                runOnUiThread { showStatus("出错: ${e.message}"); binding.progressBar.visibility = View.GONE }
            }
        }.start()
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val name = getFileName(uri) ?: "package_${System.currentTimeMillis()}.xapk"
            val file = File(cacheDir, name)
            FileOutputStream(file).use { input.copyTo(it) }
            input.close()
            file
        } catch (_: Exception) { null }
    }

    private fun getFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return null
    }

    private fun displayContent(content: XAPKContent, filePath: String) {
        val sb = StringBuilder()
        sb.appendLine("📦 ${content.fileName}")
        sb.appendLine("大小: ${formatSize(content.fileSize)}")
        sb.appendLine()

        if (content.packageName != null) {
            sb.appendLine("📱 包名: ${content.packageName}")
            content.versionCode?.let { sb.appendLine("版本: $it") }
            if (parser.isPackageInstalled(content.packageName)) {
                val v = parser.getInstalledVersionCode(content.packageName)
                sb.append("安装状态: ✅ 已安装")
                v?.let { sb.append(" (v$it)") }
                sb.appendLine()
            } else {
                sb.appendLine("安装状态: ❌ 未安装")
            }
            sb.appendLine()
        }

        if (content.apkEntries.size == 1) {
            sb.appendLine("📱 APK: ${content.apkEntries.first().name} (${formatSize(content.apkEntries.first().size)})")
        } else {
            sb.appendLine("📱 Split APK (${content.apkEntries.size}个):")
            content.apkEntries.forEach { sb.appendLine("  ├ ${it.name} (${formatSize(it.size)})") }
        }

        if (content.obbEntries.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("💾 OBB (${content.obbEntries.size}个):")
            content.obbEntries.forEach { sb.appendLine("  ├ ${it.name} (${formatSize(it.size)})") }
        }

        binding.fileInfoText.text = sb.toString()
        binding.currentFilePath.text = filePath
        binding.btnInstall.isEnabled = !isInstalling
        showStatus("就绪，点击安装按钮开始")
    }

    // ─── 安装 ────────────────────────────────────────────

    private fun startInstall(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(this)
                .setTitle("需要安装权限")
                .setMessage("需要允许安装未知应用才能继续")
                .setPositiveButton("去设置") { _, _ ->
                    installPermLauncher.launch(Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        isInstalling = true
        binding.btnInstall.isEnabled = false
        binding.btnInstall.text = "安装中..."
        binding.progressBar.visibility = View.VISIBLE

        installer.install(file, this, this)
    }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showToast("需要文件管理权限")
                installPermLauncher.launch(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
                return false
            }
        }
        return true
    }

    // ── InstallListener ──

    override fun onProgress(message: String) {
        runOnUiThread { showStatus(message) }
    }

    override fun onComplete(content: XAPKContent) {
        runOnUiThread {
            isInstalling = false
            binding.btnInstall.isEnabled = true
            binding.btnInstall.text = "重新安装"
            binding.progressBar.visibility = View.GONE
            showStatus("系统安装器已打开，请在弹出的界面确认安装")
        }
    }

    override fun onError(message: String) {
        runOnUiThread { showInstallError(message) }
    }

    private fun showStatus(text: String) { binding.statusText.text = text }
    private fun showToast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
