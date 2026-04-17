# AutoJs6 APK Builder

[![Build and Release](https://github.com/Steven-Qiang/AutoJs6-ApkBuilder/actions/workflows/release.yml/badge.svg)](https://github.com/Steven-Qiang/AutoJs6-ApkBuilder/actions/workflows/release.yml)
[![GitHub release](https://img.shields.io/github/release/Steven-Qiang/AutoJs6-ApkBuilder.svg)](https://GitHub.com/Steven-Qiang/AutoJs6-ApkBuilder/releases/)
[![GitHub license](https://img.shields.io/github/license/Steven-Qiang/AutoJs6-ApkBuilder.svg)](https://github.com/Steven-Qiang/AutoJs6-ApkBuilder/blob/main/LICENSE)

从 [AutoJs6](https://github.com/SuperMonster003/AutoJs6) 提取的独立 APK 打包工具，通过命令行或 GitHub Actions 将 AutoJs6 脚本或项目打包成独立的 Android APK。

## 代码来源

本项目的核心代码完全从官方 AutoJs6 项目提取，主要区别如下：

### 与官方版本的区别

1. **移除插件系统**
   - 官方版本包含完整的插件系统支持，可从已安装的插件 APK 中提取 native 库和 assets 资源
   - 本版本移除了所有插件相关代码（ensureAndExtractPluginLibrariesIfNeeded、selectPluginServiceOrThrow、extractLibrariesFromPluginApkOrThrow、extractAssetsFromPluginApkOrThrow 等方法）

2. **移除 Android 运行时依赖**
   - 官方版本依赖 Android Context、AssetManager、PackageManager 等 Android 框架 API
   - 本版本改为直接从 AutoJs6 APK 文件中提取所需资源（template.apk、assets、lib）

3. **修复 Windows 文件系统兼容性问题**
   - 官方版本使用 ApkPackager 解压模板到文件系统
   - 官方 AutoJs6 的资源文件名存在大小写冲突（如 A1.png 和 a1.png），在 Linux 下是不同文件，但在 Windows 文件系统中会被识别为同一文件导致覆盖
   - 本版本使用 FastZipBuilder 直接在内存中处理 ZIP 文件，避免解压到文件系统，从而解决了 Windows 下文件名大小写冲突的问题

4. **添加命令行接口**
   - 新增 ApkBuilderCli.kt 作为命令行入口
   - 支持通过命令行参数配置打包过程

## 工作原理

本工具的核心代码位于 `ApkBuilder.kt`，其工作流程如下：

### 1. 准备阶段 (prepare)
- 创建构建工作空间和临时目录
- 加载 AutoJs6 APK 作为模板（template.apk）
- 提取模板中的 META-INF/services 目录（用于 Rhino 引擎）

### 2. 配置处理 (withConfig)
- 读取或创建项目配置（project.json）
- 处理启动页资源
- 配置 AndroidManifest.xml（应用名称、版本号、包名等）
- 生成加密密钥：
  - Key = MD5(packageName + versionName + mainScriptFileName)
  - IV = MD5(buildId + name).take(16)
- 根据配置准备 assets 和 native libraries

### 3. 构建阶段 (build)
- 从模板提取 AndroidManifest.xml 和 resources.arsc
- 使用 ManifestEditor 修改 AndroidManifest.xml
- 使用 ARSCDecoder 修改 resources.arsc 中的包名
- 处理应用图标
- 将修改后的文件写回 ZIP

### 4. 脚本加密与打包
- JavaScript 文件使用 AES 加密：
  - 加密算法：AdvancedEncryptionStandard
  - 加密前写入文件头（EncryptedScriptFileHeader）
- 将项目文件或脚本文件复制到 assets/project/ 目录
- 写入更新后的 project.json

### 5. 签名阶段 (sign)
- 使用 FastZipBuilder 构建未签名的 APK
- 复制默认 keystore（default_key_store.bks）或使用自定义 keystore
- 使用 ApkSigner 进行签名，支持 V1/V2/V3/V4 签名方案
- 输出最终的签名 APK

## 环境要求

- Java 17 或更高版本
- AutoJs6 APK 文件（作为打包模板）

## 快速开始

### 1. 下载工具

从 [Releases](https://github.com/Steven-Qiang/AutoJs6-ApkBuilder/releases) 页面下载最新的 `apkbuilder-cli.jar` 文件。

### 2. 下载 AutoJs6 APK

从 [AutoJs6 Releases](https://github.com/SuperMonster003/AutoJs6/releases) 下载最新的 AutoJs6 APK 文件。

### 3. 打包你的脚本

#### 打包单个脚本文件

```bash
java -jar apkbuilder-cli.jar -a autojs6.apk -s main.js -o myapp.apk
```

#### 打包项目目录

```bash
java -jar apkbuilder-cli.jar -a autojs6.apk -s ./my_project -o myapp.apk
```

## 命令行参数

| 参数 | 简写 | 必需 | 说明 |
|------|------|------|------|
| `--autojs` | `-a` | 是 | AutoJs6 APK 文件路径 |
| `--source` | `-s` | 是 | 项目目录或 .js 脚本文件路径 |
| `--output` | `-o` | 否 | 输出 APK 路径（默认: `output.apk`） |
| `--workspace` | `-w` | 否 | 构建工作空间目录（默认: `./build_workspace`） |
| `--keystore` | `-k` | 否 | 自定义 Keystore 路径 |
| `--keystore-password` | `-kp` | 否 | Keystore 密码（默认: `AutoJs6`） |
| `--key-alias` | `-ka` | 否 | Keystore 中的 Key Alias（默认: `AutoJs6`） |
| `--key-password` | `-kpa` | 否 | Key 密码（默认与 Keystore 密码相同） |
| `--help` | `-h` | 否 | 显示帮助信息 |

## 使用示例

### 基础用法

```bash
# 打包单个脚本
java -jar apkbuilder-cli.jar -a autojs6.apk -s main.js -o myapp.apk

# 打包项目目录
java -jar apkbuilder-cli.jar -a autojs6.apk -s ./my_project -o myapp.apk
```

### 使用自定义工作空间

```bash
java -jar apkbuilder-cli.jar -a autojs6.apk -s ./my_project -o myapp.apk -w ./build
```

### 使用自定义签名

```bash
java -jar apkbuilder-cli.jar -a autojs6.apk -s ./my_project -o myapp.apk \
  -k my-release-key.keystore -kp mypassword -ka myalias -kpa mykeypassword
```

## Project Config (project.json)

如果你的项目目录中包含 `project.json` 配置文件，工具会自动读取并应用以下配置：

```json
{
  "name": "我的应用",
  "packageName": "com.example.myapp",
  "versionName": "1.0.0",
  "versionCode": 1,
  "mainScriptFileName": "main.js",
  "iconPath": "./icon.png",
  "permissions": [
    "android.permission.INTERNET",
    "android.permission.WRITE_EXTERNAL_STORAGE"
  ],
  "signatureScheme": "V1 + V2"
}
```

如果没有 `project.json` 文件，工具会自动创建默认配置。

## GitHub Action 使用

本项目也可以作为 GitHub Action 在你的工作流中使用。详细文档请参考 [ACTION_USAGE.md](./ACTION_USAGE.md)。

### 快速示例

```yaml
name: Build APK
on:
  push:
    branches: [main]

jobs:
  build-apk:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v6
      
      - name: Download AutoJs6 APK
        run: |
          wget https://github.com/SuperMonster003/AutoJs6/releases/download/v6.7.0/autojs6-v6.7.0-universal-047ae62e.apk -O autojs6.apk
      
      - name: Build APK
        uses: Steven-Qiang/AutoJs6-ApkBuilder@main
        with:
          autojs: autojs6.apk
          source: ./your-project
          output: my-app.apk
      
      - name: Upload APK
        uses: actions/upload-artifact@v7
        with:
          name: my-app
          path: my-app.apk
```

## 从源码构建

如果你想自己构建这个工具：

```bash
# 克隆仓库
git clone https://github.com/Steven-Qiang/AutoJs6-ApkBuilder.git
cd AutoJs6-ApkBuilder

# 构建
./gradlew build

# 构建好的 JAR 文件位于 build/libs/ 目录
ls -la build/libs/
```

## 核心代码结构

- `ApkBuilderCli.kt` - 命令行入口，参数解析
- `ApkBuilder.kt` - 核心构建逻辑
- `ManifestEditor.java` - AndroidManifest.xml 编辑
- `FastZipBuilder.kt` - ZIP 文件快速构建
- `ApkSigner.kt` - APK 签名
- `TinySign.java` - 轻量级签名实现

## 致谢

- [AutoJs6](https://github.com/SuperMonster003/AutoJs6) - 原始项目

## 许可证

本项目采用 Mozilla Public License Version 2.0 (MPL-2.0)，与 AutoJs6 保持一致。详见 [LICENSE](./LICENSE) 文件。

This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.

## 贡献

欢迎提交 Issue 和 Pull Request。

## 相关链接

- [AutoJs6 GitHub](https://github.com/SuperMonster003/AutoJs6)
- [AutoJs6 文档](https://docs.autojs6.com/)
