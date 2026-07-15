package com.xapkinstaller

/**
 * XAPK 文件解析结果
 */
data class XAPKContent(
    val fileName: String,
    val fileSize: Long,
    val apkEntry: ZipEntryInfo?,
    val obbEntries: List<ZipEntryInfo>,
    val hasManifestJson: Boolean,
    val packageName: String?,
    val versionCode: Int?
)

data class ZipEntryInfo(
    val name: String,
    val size: Long,
    val compressedSize: Long
)
