# XAPK 安装器 📦

一个简单的 Android 应用，用于在安卓设备上安装 `.xapk` 格式的安装包。

## 功能

- ✅ 选择并解析 XAPK 文件
- ✅ 显示包名、版本、APK/OBB 详细信息
- ✅ 提取 APK 并启动系统安装器
- ✅ 自动复制 OBB 扩展数据到 `Android/obb/<package>/`
- ✅ 支持 Android 5.0 (API 21) 以上
- ✅ Material 3 设计
- ✅ 浅色/深色主题支持

## 如何获取 APK

### 方式 1：GitHub Actions（推荐，无需本地环境）

1. 将此仓库 fork 或 push 到你的 GitHub 账号
2. 进入 Actions 页面，选择 **Build APK** workflow
3. 点击 **Run workflow** → 等待几分钟
4. 下载生成的 APK 文件

### 方式 2：本地构建

**前置要求：**
- JDK 17+
- Android SDK (命令行工具 + build-tools 35 + platform android-35)

**构建命令：**
```bash
# Linux/macOS
./gradlew assembleRelease

# Windows
gradlew.bat assembleRelease
```

APK 文件位于 `app/build/outputs/apk/release/app-release.apk`

### 方式 3：Android Studio

1. 用 Android Studio 打开此项目文件夹
2. Build → Build Bundle(s) / APK(s) → Build APK(s)
3. 等待构建完成，APK 在 `app/build/outputs/apk/debug/`

## 安装说明

1. 将 APK 传输到安卓手机（微信、QQ、USB 等）
2. 打开文件管理器，点击 APK 安装
3. 首次使用请授予 **文件/媒体访问权限**（用于读取 XAPK 文件）
4. 需在设置中开启 **安装未知来源应用** 的权限

## 使用说明

1. 打开「XAPK 安装器」
2. 点击「选择 XAPK 文件」按钮
3. 在文件管理器中选择 `.xapk` 文件
4. 应用会解析并显示文件内容（APK + OBB 文件信息）
5. 点击「安装」按钮
6. 应用会提取 APK 并启动系统安装器
7. OBB 数据会同时复制到正确的目录
8. 在系统安装器中确认安装

## 关于 XAPK 格式

XAPK 是某些应用商店（如 APKPure）使用的扩展安装包格式。它是一个 ZIP 压缩包，包含：

- **APK 文件**：主安装包
- **OBB 文件**：扩展数据文件（通常命名为 `main.<version>.<package>.obb`）
- **manifest.json**：描述包名、版本等元数据

## 技术栈

- 语言：Kotlin
- UI：Material 3 (Material Design 3)
- 构建工具：Gradle 8.11 + Android Gradle Plugin 8.7
- 最低 SDK：API 21 (Android 5.0)
- 目标 SDK：API 35 (Android 15)

## 项目结构

```
xapk-installer/
├── app/
│   ├── build.gradle.kts     # 模块构建配置
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/xapkinstaller/
│       │   ├── MainActivity.kt      # 主界面
│       │   ├── XAPKParser.kt        # XAPK 解析引擎
│       │   ├── InstallerHelper.kt   # 安装辅助类
│       │   └── XAPKContent.kt       # 数据模型
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/ (strings, colors, themes)
│           └── drawable/ (图标)
├── build.gradle.kts        # 根构建配置
├── settings.gradle.kts     # 项目设置
└── .github/workflows/      # GitHub Actions 构建配置
```

## 许可证

MIT License

## 注意

- 本工具仅用于合法获取的应用安装
- 安装 APK 需要开启「安装未知来源应用」权限
- OBB 文件较大时，复制可能需要一些时间
