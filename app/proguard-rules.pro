# Keep XAPK parser
-keep class com.xapkinstaller.** { *; }

# Keep ZIP entry references
-keep class java.util.zip.ZipEntry { *; }
-keep class java.util.zip.ZipFile { *; }
