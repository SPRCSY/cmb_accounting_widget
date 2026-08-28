# CmbSpendWidget — 招行「当月消费」桌面小组件

监听招行 App（`cmb.pb`）动账通知，在桌面小组件实时显示本月累计消费。消费累加、退款扣减、跨月自动清零、每笔明细对账、自定义排除关键词、前台服务保活。

## 构建

### 生成可安装的 release APK（自签名）

```bash
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

该 APK 用仓库内 `app/keystore/release.jks` 自签名，**本地与 CI 编译出的签名一致**，可直接覆盖安装。

**签名密码不写死在代码里**，通过 gradle property 注入：
- 本地：把下面两行写到 `~/.gradle/gradle.properties`（`CMB_STORE_PASSWORD` / `CMB_KEY_PASSWORD`）
- GitHub Actions：在仓库 Settings → Secrets → Actions 里配同名 Secret，CI 通过 `ORG_GRADLE_PROJECT_*` 环境变量读取

### 安装到手机

```bash
adb connect <手机IP>:<端口>      # 或 USB 连接
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 首次使用

1. 打开 App，授权「通知使用权」（通知监听）
2. 桌面长按 → 添加小组件 → 选择「当月消费」
3. App 内可设置：排除关键词（转账/还款/充值等不计入）、后台保活、电池白名单、自启动指引

## 开发（Codespaces / 本地）

- Codespaces 打开后自动安装 Android SDK（见 `.devcontainer/postCreate.sh`）
- 本地需要 JDK 17+ 与 Android SDK；SDK 路径写到 `local.properties`（已 gitignore）

## 数据与隐私

- 所有数据（累计、明细、规则）仅存于本机 SharedPreferences，不上传任何服务器
- 仓库不含任何银行卡号、账户、token 等敏感信息
