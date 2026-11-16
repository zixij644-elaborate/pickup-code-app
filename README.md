# 码上闪记

> Android 取件 / 取餐辅助自动化工具：从短信、屏幕或分享图片识别码与地址，自动整理保存、安排到期提醒，并提供一键导航与取件状态管理。取件记录本地保存，AI 增强可选。

[![Build and Test](https://github.com/zixij644-elaborate/pickup-code-app/actions/workflows/ci.yml/badge.svg)](https://github.com/zixij644-elaborate/pickup-code-app/actions/workflows/ci.yml)
[![Downloads](https://img.shields.io/github/downloads/zixij644-elaborate/pickup-code-app/total)](https://github.com/zixij644-elaborate/pickup-code-app/releases)
[![Latest release](https://img.shields.io/github/v/release/zixij644-elaborate/pickup-code-app)](https://github.com/zixij644-elaborate/pickup-code-app/releases/latest)

<div align="center">
  <img src="screenshots/home.jpg" width="30%" alt="主页：取件码/取餐码列表与筛选" />
  <img src="screenshots/detail.jpg" width="30%" alt="详情页：标题栏可一键跳转身份码" />
  <img src="screenshots/identity-code.jpg" width="30%" alt="身份码：淘宝/菜鸟/拼多多一键打开" />
  <img src="screenshots/settings.jpg" width="30%" alt="设置：识别与验证服务逐项可开关" />
</div>

## 下载

到 [Releases](https://github.com/zixij644-elaborate/pickup-code-app/releases/latest) 下载对应架构的 APK：

| 版本 | 适用设备 | 直链 |
|---|---|---|
| app-arm64-v8a-release.apk | 大部分新手机（含 vivo/小米等 arm64 机型） | [下载](https://github.com/zixij644-elaborate/pickup-code-app/releases/latest/download/app-arm64-v8a-release.apk) |
| app-armeabi-v7a-release.apk | 较老 32 位设备 | [下载](https://github.com/zixij644-elaborate/pickup-code-app/releases/latest/download/app-armeabi-v7a-release.apk) |

 > 不确定选哪个？近几年的手机基本都是 arm64-v8a。每个 Release 都附带 SHA-256 校验文件。

## 快速开始

1. 下载 APK 安装
2. 开启无障碍服务（设置 → 无障碍 → 码上闪记）
3. 把磁贴加到控制面板（下拉通知栏 → 编辑 → 找到「码上闪记」）
4. 方式一：打开外卖/快递 App → 点磁贴 → 自动识别
5. 方式二：截图 → 分享菜单 → 选择「码上闪记」
6. 方式三：在软件中分享图片 → 码上闪记 → 自动识别（仅支持分享图片）
7. 方式四：快递取件短信到达时自动识别（需在设置中开启短信权限）
8. 方式五：长按选中文本 → 工具栏选「码上闪记」划词识别
9. 方式六：点右下角加号手动录入
10. 方式七：系统设置 → 无障碍 → 无障碍快捷方式 → 选「码上闪记（音量键快捷方式）」，之后同时按两个音量键直接识别当前屏幕
 > 不开启无障碍权限不影响分享路径与短信路径的识别

## 功能

### 核心识别
- **七种触发方式**：磁贴、双音量键、无障碍自动扫描、分享菜单、短信、划词、手动录入
- **OCR**：ML Kit 中英文识别，归一化横杠变体，并按纠错表修正常见误读（如「免喜」→「兔喜」）
- **格式覆盖**：三段式、四段式、字母前缀、字母-数字、长数字、带前缀，支持自定义正则
- **上下文判断**：区分快递/餐饮、过滤干扰数字；券码二维码直接解码入库
- **AI 增强**（可选）：任意 OpenAI 兼容 API，与正则并行识别后去重合并

### 地址识别
- **预存地址优先**：存好常去驿站的完整名称 + 关键词，命中即用你录入的名字替换 OCR 结果，支持从历史导入，本地加密保存
- **逐码定位**：同屏多条通知时按码所在卡片取地址，不同驿站不串台
- **多级策略**：11 级地址策略自动跨行拼接，折叠地址用同屏更完整的街道名补全
- **快递100 反查**（可选）：识别到运单号时用官方码校验，只有反查结果确实出现才采信

<div align="center">
  <img src="screenshots/saved-address-dialog.jpg" width="34%" alt="常用取件地址：完整名称 + 关键词" />
</div>

### 身份码
- 淘宝身份码 / 菜鸟出库码 / 拼多多身份码三个入口，主页顶栏与详情页各一处，直达失败逐级降级到 App 首页

### 来源识别
- 运单号前缀（JT 极兔、SF 顺丰等）优先于 OCR 文本，配合品牌词与邻近行定位；24 个品牌图标跟随主题色

### 数据管理
- **去重与重复提醒**：同码删除一键清理全部重复；码值再次出现时提醒并可跳转整理
- **改码校验**：格式非法或已有同码待取记录时拒绝写入，改完按新码重建通知与提醒
- **稍后提醒**：通知栏一键延后 1 小时，取件、改码、删除自动取消
- **回收站**：已取记录保留 24 小时，按批次精确撤销，恢复时重排提醒
- **截图治理**：按引用计数回收，孤儿与 30 天前的截图自动清理，两个目录合计限 50 MB
- **地图验证**（可选）：每条记录按自己的地址单独核验

### 到期提醒
- 优先解析文本时限（请于 / 截止 / 有效期至等，含多种日期写法，「今日」按 20:00）
- 没写时限的快递码按 72 小时兜底；餐饮码不提醒
- 改码、取件、删除自动取消，设置里可整体关闭

### 隐私
- 记录与地址只存本机；API Key、地址、学习样本均经 AndroidKeyStore 加密
- 身份码页面不采集、不截图
- AI、地图、快递100 需手动开启，默认关闭

### 自学习
- **自动发现**：从未识别的文本聚类生成新正则，确认/纠错反馈用于统计准确率
- **确认即成规**：确认码值或亲手改写，1 条即建规则（标「已确认」），内置已覆盖的不重复学
- **规则管理**：15 条内置正则可停用、改写、还原，也能自己加；改坏有一键还原，语法错自动回退出厂版本
- **统计**：命中率、模式分布、候选建议，可一键生成成绩单图片

## 自动化工作流

把「找码、记地址、记时限、设提醒」串成连续流程，减少在短信、快递 App 和备忘录之间反复切换。

| 场景 / 触发条件 | 自动执行的处理 | 用户操作与条件 |
|---|---|---|
| 收到取件 / 取餐短信 | 提取码、来源和地址，保存记录并推送通知；快递码按规则安排到期提醒 | 需开启短信识别并授予相应权限 |
| 进入支持自动扫描的 App | 触发屏幕识别，提取信息并接入保存、通知和提醒流程 | 需开启无障碍服务；自动扫描受支持的 App 范围限制 |
| 分享图片 / 划词，或使用磁贴 / 双音量键快捷方式 | 触发后自动完成识别、结果合并去重、信息整理与保存 | 用户分享、选中文字或调用快捷方式；各入口按需配置 |
| 识别出快递取件时限 | 优先解析文本日期；未写时限时以 72 小时作为应用兜底规则，自动安排提醒 | 到期提醒默认开启，可在设置中关闭；实际保管时限以驿站通知为准 |
| 修改码值、标记已取或恢复记录 | 修改码值后更新通知与提醒；标记已取时撤销通知和提醒；恢复未到期记录时重新安排到期提醒 | 用户修改、确认取件或恢复记录，应用同步处理后续状态 |
| 前往取件点 | 使用记录中的地址提供地图入口，减少重新输入地址 | 用户点击导航入口后唤起已安装的地图应用，并确认目的地 |

**例如：** 取件短信到达后，应用自动提取取件码与地址、保存记录、推送通知并安排到期提醒；准备取件时，用户点击地址旁的导航入口；取件后标记已取，应用撤销对应通知和提醒。

识别与整理可在本地完成；AI 增强、地图验证和快递100 反查需单独配置开启，默认关闭。启用相关网络功能时，相应识别或验证信息会发送给所配置的服务。

<details>
<summary>技术栈（模块与技术选型）</summary>

| 模块 | 技术 |
|------|------|
| UI | Jetpack Compose + Material3 |
| OCR | ML Kit Text Recognition (Chinese) |
| 截屏 | 无障碍服务 takeScreenshot |
| 外部接收 | Intent Filter (SEND / PROCESS_TEXT) |
| 触发 | Quick Settings Tile + 无障碍自动扫描 + 系统无障碍快捷方式（双音量键） |
| 短信 | SMS BroadcastReceiver（可选权限） |
| 到期提醒 | AlarmManager + 文本时限解析（快递 72h 兜底，取餐码跳过） |
| 存储 | Room (SQLite) |
| 设置 | DataStore Preferences |
| 加密 | AndroidKeyStore AES-GCM（API Key、常用取件地址、学习样本） |
| 身份码跳转 | Intent 深链 + Android 11 包可见性声明 (queries) |
| 地图 | Android Geocoder + 高德 API（可选） |
| 快递验证 | 快递100 API（可选） |
| AI | 可选接入任意 OpenAI 兼容 API（默认 GPT-4o-mini，支持修改 API 地址与密钥） |
| 自学习 | 本地聚类分析 + 自动正则生成 + 用户确认即成规 |
| 规则管理 | 内置正则表（稳定 id）+ 用户覆盖（停用/改写/一键还原） |

</details>

<details>
<summary>项目结构（目录说明与识别流程）</summary>

```
app/src/main/java/com/pickupcode/app/
├── App.kt                 # Application：全局 scope、通知频道、启动时截图治理与旧数据加密迁移
├── MainActivity.kt        # 主页/历史列表/回收站/手动录入 + 导航
├── data/                  # 数据层：Room 实体 + DAO(去重/回收站/归档)
├── extractor/             # 识别核心
│   ├── CodeExtractor.kt        # 取件/取餐码 正则+评分+地址策略管线
│   ├── AddressExtractor.kt     # 地址识别：预存地址 → 逐码窗口 → 全屏多级策略
│   ├── SavedAddressMatcher.kt  # 预存地址匹配（纯函数，可单测）
│   ├── ExpiryExtractor.kt      # 到期时间提取：文本时限 → 快递 72h 兜底 → 取餐码跳过
│   ├── OcrCorrections.kt       # OCR 常见误读纠错表（免喜→兔喜 等）
│   ├── BrandResolver.kt        # 运单号前缀/品牌词 → 来源品牌
│   ├── AIExtractor.kt          # OpenAI 兼容 AI 提取
│   ├── RecognitionDebugStore.kt # 识别调试快照（仅 DEBUG 构建可见）
│   └── CouponDetector.kt       # 券码(二维码)检测+解码
├── ocr/OCREngine.kt       # ML Kit 文本识别
├── learner/               # 自学习
│   ├── PatternLearner.kt       # 自动生成正则/统计/用户确认即成规
│   ├── DailyStats.kt           # 每日识别统计
│   ├── CommonStationStore.kt   # 自动学习的常用站点（仅作"从历史导入"候选）
│   └── SavedAddressStore.kt    # 用户预存地址（加密存储）
├── util/                  # IdentityCodeLauncher(身份码跳转)、ScreenshotStore(截图治理)、SensitivePageGuard(身份码页面拒采)
├── geocoder/GeocoderVerifier.kt   # 地址地理编码验证
├── kuaidi100/Kuaidi100Verifier.kt # 快递100 运单反查
├── notification/          # 取餐/取件/券码通知 + 已取/忽略广播 + 到期提醒(RemindReceiver)
├── preferences/           # 设置(DataStore) + SecretCipher(加解密)
├── service/               # 无障碍服务(截屏+识别)、快捷磁贴、双音量键快捷方式、
│                          # RecognitionPipeline(单一识别入口)、PostVerifier(地址/快递复核)、
│                          # CodeCompletion(归档/撤销/改码统一入口)
├── share/ShareReceiver.kt # 外部分享/拖放
└── ui/                    # Compose UI：theme/components/screens（含 RulesScreen 规则管理、StatsScreen 统计、RecognitionDebugDialog）
```

**识别流程**：`截图/分享 → OCR(文字) + 券码检测(二维码) + 正则/AI(取餐/取件码) → 合并去重(券码与食/件码互斥) → 地址识别(预存地址优先) → 存库 + 通知 → 地址验证/快递100反查`

</details>

<details>
<summary>取件码格式覆盖（支持自定义）</summary>

| 格式 | 示例 |
|------|------|
|二段式|`1-2345`|
| 三段式 | `1-2-3456`|
| 四段式 | `A1-2-3-45`|
| 字母前缀三段式 | `A1-2-3456`|
| 字母-横杠-数字 | `D-12345`|
| 字母+数字 (无横杠) | `D12345`|
| 长数字 (6-8位) | `123456` |
| 带前缀 | `取件码：123456`|
| 餐饮 | `A12` `123`|

</details>

## 开发者

想从源码编译、贡献代码或报告问题？请查看：

- **[构建指南](docs/BUILDING.md)** —— 从源码编译（JDK 17 / Gradle 8.9 / Android SDK 要求、常见问题）
- **[贡献指南](CONTRIBUTING.md)** —— 如何提 Issue、提交 PR、代码规范
- **[识别语料回归](docs/CORPUS.md)** —— 用真实截图语料量化识别 precision/recall，如何采集新语料

## 许可证

[GPL-3.0](LICENSE)

界面与品牌图标的来源、署名及各自许可证见 **[ICONS.md](ICONS.md)**。
