package com.xapkinstaller

import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.xapkinstaller.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity(), InstallerHelper.InstallListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var parser: XAPKParser
    private lateinit var installer: InstallerHelper
    private var isInstalling = false
    private var installResultReceiver: InstallResultReceiver? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { handleXAPKUri(it) } }

    private val installPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 权限结果 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        parser = XAPKParser(this)
        installer = InstallerHelper(this)

        // 动态注册安装结果广播接收器
        registerInstallReceiver()

        setupUI()
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { installResultReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
    }

    private fun registerInstallReceiver() {
        installResultReceiver = InstallResultReceiver()
        val filter = IntentFilter(InstallResultReceiver.ACTION_INSTALL_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installResultReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(installResultReceiver, filter)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    // ─── UI 设置 ────────────────────────────────────────

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

    // ─── 文件选择与解析 ─────────────────────────────────

    private fun handleIncomingIntent(intent: Intent?) {
        intent?.data?.let { handleXAPKUri(it) }
    }

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

    // ─── 信息展示 ────────────────────────────────────────

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
            val a = content.apkEntries.first()
            sb.appendLine("📱 APK: ${a.name} (${formatSize(a.size)})")
        } else {
            sb.appendLine("📱 APK 分片包 (${content.apkEntries.size}个):")
            content.apkEntries.forEach { sb.appendLine("  ├ ${it.name}") }
        }

        if (content.obbEntries.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("💾 OBB (${content.obbEntries.size}个):")
            content.obbEntries.forEach { sb.appendLine("  ├ ${it.name} (${formatSize(it.size)})") }
        }

        binding.fileInfoText.text = sb.toString()
        binding.currentFilePath.text = filePath
        binding.btnInstall.isEnabled = !isInstalling
        showStatus("就绪，可以安装")
    }

    // ─── 安装流程 ────────────────────────────────────────

    private fun startInstall(file: File) {
        isInstalling = true
        binding.btnInstall.isEnabled = false
        binding.btnInstall.text = "安装中..."

        installer.install(file, this) { status, message, pkgName ->
            // Split APK 安装结果回调（在主线程）
            runOnUiThread {
                isInstalling = false
                binding.btnInstall.isEnabled = true
                binding.btnInstall.text = "重新安装"

                if (status == 0) {
                    showStatus("✅ 安装成功！")
                    AlertDialog.Builder(this)
                        .setTitle("安装完成")
                        .setMessage("${pkgName ?: "应用"} 安装成功！\n可在桌面或应用列表中找到。")
                        .setPositiveButton("打开应用") { _, _ ->
                            try {
                                val launchIntent = packageManager.getLaunchIntentForPackage(pkgName ?: "")
                                if (launchIntent != null) startActivity(launchIntent)
                                else showToast("无法找到启动入口")
                            } catch (_: Exception) { showToast("无法启动应用") }
                        }
                        .setNegativeButton("好的", null)
                        .show()
                } else {
                    showStatus("❌ 安装失败")
                    AlertDialog.Builder(this)
                        .setTitle("安装失败")
                        .setMessage("错误码: $status\n$message")
                        .setPositiveButton("好的", null)
                        .show()
                }
            }
        }
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

    // ─── InstallListener 回调 ────────────────────────────

    override fun onProgress(message: String) {
        runOnUiThread { showStatus(message) }
    }

    override fun onComplete(content: XAPKContent) {
        // 单 APK 路径到此 (系统安装器已弹出)
        // Split APK 路径也有此回调，但真正结果在 sessionCallback 中
    }

    override fun onError(message: String) {
        runOnUiThread {
            isInstalling = false
            binding.btnInstall.isEnabled = true
            binding.btnInstall.text = "安装"
            showStatus("❌ $message")
            showToast(message)
        }
    }

    // ─── 辅助 ────────────────────────────────────────────

    private fun showStatus(text: String) { binding.statusText.text = text }
    private fun showToast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
