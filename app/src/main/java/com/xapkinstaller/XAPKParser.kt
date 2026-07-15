package com.xapkinstaller

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * XAPK 文件解析器
 * XAPK = ZIP 文件，内含：
 *   - AndroidManifest.xml（或 manifest.json）：描述包名、版本
 *   - *.apk：主安装包
 *   - main.<versionCode>.<packageName>.obb：扩展数据
 *   - patch.<versionCode>.<packageName>.obb：补丁数据
 */
class XAPKParser(private val context: Context) {

    companion object {
        private const val TAG = "XAPKParser"
        const val OBB_DIR = "Android/obb"
    }

    /**
     * 解析 XAPK 文件内容结构（不解压）
     */
    fun parse(xapkFile: File): Result<XAPKContent> = runCatching {
        val zipFile = ZipFile(xapkFile)
        zipFile.use { zip ->
            val entries = zip.entries().asSequence().toList()

            // 查找 manifest.json
            val manifestEntry = entries.find { it.name.equals("manifest.json", ignoreCase = true) }

            // 解析 manifest.json
            var pkgName: String? = null
            var versionCode: Int? = null
            if (manifestEntry != null) {
                val manifestJson = zip.getInputStream(manifestEntry).bufferedReader().readText()
                val json = JSONObject(manifestJson)
                pkgName = json.optString("package_name") ?: json.optString("package")
                versionCode = json.optInt("version_code")
                if (versionCode == 0) versionCode = null
            }

            // 查找 APK 和 OBB 条目
            var apkEntry: ZipEntryInfo? = null
            val obbEntries = mutableListOf<ZipEntryInfo>()

            for (entry in entries) {
                val name = entry.name
                if (name.isBlank()) continue

                val info = ZipEntryInfo(
                    name = name,
                    size = entry.size,
                    compressedSize = entry.compressedSize
                )

                when {
                    name.endsWith(".apk", ignoreCase = true) -> {
                        if (apkEntry == null) apkEntry = info
                    }
                    name.endsWith(".obb", ignoreCase = true) -> {
                        obbEntries.add(info)
                    }
                }
            }

            XAPKContent(
                fileName = xapkFile.name,
                fileSize = xapkFile.length(),
                apkEntry = apkEntry,
                obbEntries = obbEntries,
                hasManifestJson = manifestEntry != null,
                packageName = pkgName,
                versionCode = versionCode
            )
        }
    }

    /**
     * 从 ZIP 提取单个文件到目标路径
     */
    fun extractEntry(zipFile: File, entryName: String, destFile: File): Result<File> = runCatching {
        val zip = ZipFile(zipFile)
        zip.use { z ->
            val entry = z.getEntry(entryName) ?: throw IllegalArgumentException("ZIP 中未找到: $entryName")
            destFile.parentFile?.mkdirs()
            z.getInputStream(entry).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        destFile
    }

    /**
     * 获取 OBB 目标路径
     */
    fun getObbTargetPath(obbName: String, pkgName: String?): File {
        val obbBase = File(context.getExternalFilesDir(null)?.parentFile?.parentFile, OBB_DIR)
        val pkgDir = if (!pkgName.isNullOrBlank()) {
            File(obbBase, pkgName)
        } else {
            obbBase
        }
        pkgDir.mkdirs()
        return File(pkgDir, obbName)
    }

    /**
     * 检查 XAPK 是否已安装（通过包名检测）
     */
    fun isPackageInstalled(pkgName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkgName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 获取已安装包的版本号
     */
    fun getInstalledVersionCode(pkgName: String): Int? {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(pkgName, 0)
            pkgInfo.longVersionCode.toInt()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
