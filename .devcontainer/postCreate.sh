#!/usr/bin/env bash
# Codespaces 初始化脚本：准备 Android SDK，让项目能直接 gradlew assembleRelease
set -e

SDK="${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}"
echo "== Android SDK 目录: $SDK =="

# 1. 确保 cmdline-tools 存在（含 sdkmanager）
CMDLINE="$SDK/cmdline-tools/latest/bin"
if [ ! -x "$CMDLINE/sdkmanager" ]; then
    echo "== 下载并安装 cmdline-tools =="
    mkdir -p "$SDK/cmdline-tools"
    curl -sSL -o /tmp/cmdline-tools.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q /tmp/cmdline-tools.zip -d /tmp/
    rm -rf "$SDK/cmdline-tools/latest"
    mv /tmp/cmdline-tools "$SDK/cmdline-tools/latest"
fi

# 2. 接受 license
echo "== 接受 SDK license =="
yes | "$CMDLINE/sdkmanager" --licenses > /dev/null 2>&1 || true

# 3. 安装编译本项目所需的组件
echo "== 安装 platform-tools / android-35 / build-tools =="
"$CMDLINE/sdkmanager" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# 4. 确认 Java 版本（AGP 8.5.2 需要 JDK 17+）
echo "== Java 版本 =="
java -version 2>&1 | head -1

echo "== Android 环境就绪 =="
echo "接下来可运行：./gradlew assembleRelease"
