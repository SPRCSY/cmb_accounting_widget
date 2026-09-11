# CmbSpendWidget — 招行「当月消费」桌面小组件

监听招行 App（`cmb.pb`）动账通知，在桌面小组件实时显示本月累计消费。消费累加、退款扣减、跨月自动清零、明细表格对账、收入待决台账、自定义排除关键词、前台服务保活。

## 界面

主页面（`MainActivity`）：
- 顶部本月净消费大数 + 监听/保活状态
- 常用操作：授权监听、刷新、排除规则、待决收入、高级 / 保活
- **明细表格**：时间 | 金额（支出 `+`、退款 `−`、收入蓝色标注）| 详细，表头固定、表体自带滚动条，
  详细列自动换行，时间/金额两列按内容智能定宽；底部固定「合计」页脚，
  显示 `支出 − 退款 − 收入抵扣 = 净额`，与顶部大数同源，永远一致。

「高级 / 保活」（`AdvancedActivity`）：保活、电池白名单、自启动指引、**导出明细 CSV**，以及调试用的模拟消费 / 模拟收入 / 清空本月。

「待决收入」（`PendingIncomeActivity`）：逐笔判断收入性质（退款 / 他人AA / 忽略）并指定抵扣金额。

## 数据安全设计（已实测验证）

**台账只追加，永不整体重写**；「本月」由每笔自带的时间戳推导，不依赖任何「当前月份键」。
因此不存在「月份键写错 → 整段明细被覆盖」的可能。

### 重装后还能不能保住数据？

实测结论（2026-09-11，本机 Android 16，用独立包名的同代码探针 App + 真实 App 各验证一轮）：

| 场景 | 结果 |
| --- | --- |
| 覆盖安装（升级，同签名） | ✅ 数据保留 |
| 卸载后重装 | ⚠️ App 内数据被系统清空，但 `/storage/emulated/0/当月消费/台账备份.json` **仍在** |
| 重装后**重新授予「所有文件访问权限」** | ✅ **自动读回，无需手挑文件**（实测 ¥2.00 原样恢复） |
| 未授权时用「从备份导入」手选文件 | ✅ 也可恢复（合并去重，重复导入不会翻倍） |

**推荐用法**：装好后去「高级 / 保活 → 授予「所有文件访问权限」」把权限开了。
之后每次动账都会自动备份；万一卸载重装，**重新开一次这个权限，数据就会自动回来**。

- 备份双写两处：
  - `/storage/emulated/0/当月消费/台账备份.json`（自建目录，**卸载不删，需文件权限**，授权后重装自动恢复）
  - `Download/当月消费/`（MediaStore 兜底，无需权限，但重装后需手动选文件导入）
- 「导出明细 CSV」写出 `内部存储/当月消费/消费明细导出.csv`
- 「从自建目录恢复」= 手动触发一次按路径读取；「从备份导入」= 系统文件选择器手选

> 为什么必须开「所有文件访问权限」才能自动恢复：Android 10+ 分区存储下，
> 重装会换 uid，MediaStore 的公共文件无法被新 uid 直接读回；而 MANAGE_EXTERNAL_STORAGE
> 允许按绝对路径读写，路径固定，所以重装后重新授权即可自动恢复。

> 历史教训：2026-09-11 早期版本曾用「明细月份键」判断换月，去重逻辑先改动了该键，
> 导致首笔写入误判月份、整月明细被覆盖。现设计已移除该键，从根上杜绝。

## 收入待决机制

招行通知里不是每笔进账都等于「退款」：基金赎回、朝朝宝赎回、他人转账、AA 收款都会带「收入/转入/到账」字样。
App 不擅自把它们算作抵扣，而是登记进**待决台账**，由你在「待决收入」页逐笔定性：

- **退款**：电子付款对应的退款，默认按全额从本月消费中扣减
- **他人AA**：可按实际 AA 份额填**部分**抵扣金额
- **忽略**：与消费无关（如理财赎回），不抵扣

本月净消费 = Σ支出 − Σ退款 − Σ已定性的收入抵扣。总额由台账实时推导，
所以顶部大数与表格页脚不会出现对不上的情况；改性质或金额会立即重算，无需手动回滚。

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
3. 主页面可设：排除关键词（转账/还款/充值等不计入）、待决收入定性；保活 / 电池白名单 / 自启动指引在「高级 / 保活」里

## 开发（Codespaces / 本地）

- Codespaces 打开后自动安装 Android SDK（见 `.devcontainer/postCreate.sh`）
- 本地需要 JDK 17+ 与 Android SDK；SDK 路径写到 `local.properties`（已 gitignore）

### 源码结构（纯 Java，`app/src/main/java/com/csy/cmbspend/`）

| 文件 | 职责 |
| --- | --- |
| `CmbNotificationListener` | 只认 `cmb.pb`，解析后分流：支出累加 / 退款扣减 / 收入进待决台账；重连时扫当前通知栏补漏 |
| `CmbNotificationParser` | 文案解析：退款(退款/退货/退费/冲账) → 收入(收入/转入/到账…) → 支出(扣款/支出/消费)；金额存 Long 分 |
| `SpendStore` | 持久化：**只追加**的明细台账与收入台账，「本月」按时间戳过滤；双写备份（公共目录 + 私有）；CSV 导出；`restoreFromJson` 合并式恢复；去重集合 |
| `PublicBackup` | 读写自建目录 `/storage/emulated/0/当月消费/`（需 MANAGE_EXTERNAL_STORAGE；无权限时回退 MediaStore 写 Download/） |
| `SpendWidgetProvider` | 桌面小组件渲染与刷新 |
| `MainActivity` | 主页面：大数 + 明细表格 + 合计 |
| `AdvancedActivity` | 高级 / 保活 / 调试 |
| `PendingIncomeActivity` | 待决收入逐笔定性 |
| `Rules` | 排除关键词 |
| `KeepAliveService` / `AutoStartReceiver` | 前台保活与事件唤醒 |

布局：`activity_main`（表格）、`activity_advanced`、`activity_pending`、`pending_card`、`table_row`、`widget_layout`。

## 数据与隐私

- 所有数据（累计、明细、规则）仅存于本机 SharedPreferences，不上传任何服务器
- 仓库不含任何银行卡号、账户、token 等敏感信息
