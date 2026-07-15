package com.xapkinstaller

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.xapkinstaller.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity(), InstallerHelper.InstallListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var parser: XAPKParser
    private lateinit var installer: InstallerHelper
    private var isInstalling = false

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleXAPKUri(it) }
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 权限授予后自动继续
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        parser = XAPKParser(this)
        installer = InstallerHelper(this)

        setupUI()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun setupUI() {
        binding.btnSelectFile.setOnClickListener {
            if (checkStoragePermission()) {
                openFilePicker()
            }
        }

        binding.btnInstall.setOnClickListener {
            val filePath = binding.currentFilePath.text.toString()
            if (filePath.isNotBlank() && !isInstalling) {
                val file = File(filePath)
                if (file.exists()) {
                    startInstall(file)
                } else {
                    showToast("文件不存在")
                }
            }
        }

        binding.btnSelectFile.performClick()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        handleXAPKUri(uri)
    }

    private fun openFilePicker() {
        try {
            filePickerLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            showToast("无法打开文件选择器")
        }
    }

    private fun handleXAPKUri(uri: Uri) {
        showStatus("正在读取文件...")
        binding.progressBar.visibility = View.VISIBLE
        binding.btnInstall.isEnabled = false

        Thread {
            try {
                val cacheFile = copyUriToCache(uri)
                if (cacheFile == null) {
                    runOnUiThread {
                        showStatus("无法读取文件")
                        binding.progressBar.visibility = View.GONE
                    }
                    return@Thread
                }

                val result = parser.parse(cacheFile)
                if (result.isFailure) {
                    runOnUiThread {
                        showStatus("解析失败: ${result.exceptionOrNull()?.message}")
                        binding.progressBar.visibility = View.GONE
                    }
                    return@Thread
                }

                val content = result.getOrThrow()
                runOnUiThread {
                    displayXAPKContent(content, cacheFile.absolutePath)
                    binding.progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showStatus("处理文件时出错: ${e.message}")
                    binding.progressBar.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val fileName = getFileNameFromUri(uri) ?: "package_${System.currentTimeMillis()}.xapk"
            val cacheFile = File(cacheDir, fileName)
            FileOutputStream(cacheFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            cacheFile
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }

    private fun displayXAPKContent(content: XAPKContent, filePath: String) {
        val info = buildString {
            append("📦 ${content.fileName}\n")
            append("大小: ${formatSize(content.fileSize)}\n\n")

            if (content.packageName != null) {
                append("📱 包名: ${content.packageName}\n")
                if (content.versionCode != null) {
                    append("版本: ${content.versionCode}\n")
                }
                if (parser.isPackageInstalled(content.packageName)) {
                    val installedVer = parser.getInstalledVersionCode(content.packageName)
                    append("安装状态: ✅ 已安装")
                    if (installedVer != null) append(" (v$installedVer)")
                    append("\n")
                    if (content.versionCode != null && installedVer != null) {
                        if (content.versionCode > installedVer) append("⬆️ 有新版本可用!\n")
                        else if (content.versionCode == installedVer) append("ℹ️ 版本相同\n")
                    }
                } else {
                    append("安装状态: ❌ 未安装\n")
                }
                append("\n")
            }

            if (content.apkEntries.isNotEmpty()) {
                if (content.apkEntries.size == 1) {
                    append("📱 APK: ${content.apkEntries.first().name}")
                    append(" (${formatSize(content.apkEntries.first().size)})\n")
                } else {
                    append("📱 APK 分片包 (${content.apkEntries.size}个):\n")
                    for (apk in content.apkEntries) {
                        append("  ├ ${apk.name}\n")
                        append("  └ ${formatSize(apk.size)}\n")
                    }
                }
            }

            if (content.obbEntries.isNotEmpty()) {
                append("\n💾 OBB 扩展数据 (${content.obbEntries.size}个):\n")
                for (obb in content.obbEntries) {
                    append("  ├ ${obb.name}\n")
                    append("  └ 大小: ${formatSize(obb.size)}\n")
                }
            }

            if (content.hasManifestJson) {
                append("\n📋 manifest.json: ✓\n")
            }
        }

        binding.fileInfoText.text = info
        binding.currentFilePath.text = filePath
        binding.btnInstall.isEnabled = !isInstalling
        showStatus("就绪，可以安装")
    }

    private fun startInstall(file: File) {
        isInstalling = true
        binding.btnInstall.isEnabled = false
        binding.btnInstall.text = "安装中..."
        installer.install(file, this)
    }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showToast("需要文件管理权限来浏览文件")
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                installPermissionLauncher.launch(intent)
                return false
            }
        }
        return true
    }

    override fun onProgress(message: String) {
        runOnUiThread { showStatus(message) }
    }

    override fun onComplete(content: XAPKContent) {
        runOnUiThread {
            isInstalling = false
            binding.btnInstall.isEnabled = true
            binding.btnInstall.text = "重新安装"
            showStatus("✅ 安装完成！Split APK 已提交系统安装器，OBB 已复制")
            showToast("安装完成，请在系统安装器中确认")
        }
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

    private fun showStatus(text: String) {
        binding.statusText.text = text
    }

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
