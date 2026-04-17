# Using as a GitHub Action

This repository can be used as a GitHub Action in other repositories.

## Basic Usage

Add the following steps to your workflow file:

```yaml
name: Build APK

on:
  push:
    branches: [main]

jobs:
  build-apk:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout your repository
        uses: actions/checkout@v6
      
      - name: Download AutoJs6 APK
        run: |
          wget https://github.com/SuperMonster003/AutoJs6/releases/download/v6.7.0/autojs6-v6.7.0-universal-047ae62e.apk -O autojs6.apk
      
      - name: Build APK with AutoJs6 APK Builder
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

## Inputs

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `autojs` | Yes | - | Path to AutoJs6 APK file |
| `source` | Yes | - | Project directory or .js script file |
| `output` | No | `output.apk` | Output APK path |
| `workspace` | No | `./build_workspace` | Build workspace directory |
| `keystore` | No | - | Custom keystore path |
| `keystore-password` | No | - | Keystore password |
| `key-alias` | No | - | Key alias in keystore |
| `key-password` | No | - | Key password (defaults to keystore password) |

## Using Custom Keystore

```yaml
- name: Build APK
  uses: Steven-Qiang/AutoJs6-ApkBuilder@main
  with:
    autojs: autojs6.apk
    source: ./your-project
    output: my-app.apk
    keystore: ./your-release-key.keystore
    keystore-password: your-keystore-password
    key-alias: your-key-alias
    key-password: your-key-password
```

## Complete Example

```yaml
name: Auto Build and Release APK

on:
  push:
    tags:
      - 'v*'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v6
      
      - name: Download AutoJs6
        run: |
          wget https://github.com/SuperMonster003/AutoJs6/releases/download/v6.7.0/autojs6-v6.7.0-universal-047ae62e.apk -O autojs6.apk
      
      - name: Build APK
        uses: Steven-Qiang/AutoJs6-ApkBuilder@main
        with:
          autojs: autojs6.apk
          source: ./scripts
          output: my-app.apk
          workspace: ./build
      
      - name: Create Release
        uses: softprops/action-gh-release@v3
        if: startsWith(github.ref, 'refs/tags/')
        with:
          files: my-app.apk
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```
