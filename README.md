# 码上闪记

[![Build and Test](https://github.com/zixij644-elaborate/pickup-code-app/actions/workflows/ci.yml/badge.svg)](https://github.com/zixij644-elaborate/pickup-code-app/actions/workflows/ci.yml)
[![Downloads](https://img.shields.io/github/downloads/zixij644-elaborate/pickup-code-app/total)](https://github.com/zixij644-elaborate/pickup-code-app/releases)
[![Latest release](https://img.shields.io/github/v/release/zixij644-elaborate/pickup-code-app)](https://github.com/zixij644-elaborate/pickup-code-app/releases/latest)

自动识别截屏或分享图片中的取餐码、取件码和券码，通知提醒 + 一键标记已取。数据全部留在本机。

<div align="center">
  <img src="screenshots/home.jpg" width="30%" alt="主页：取件码/取餐码列表与筛选" />
  <img src="screenshots/detail.jpg" width="30%" alt="详情页：标题栏可一键跳转身份码" />
  <img src="screenshots/identity-code.jpg" width="30%" alt="身份码：淘宝/菜鸟/拼多多一键打开" />
  <img src="screenshots/settings.jpg" width="30%" alt="设置：识别与验证服务逐项可开关" />
</div>

## 功能

### 核心识别
- **六种触发方式**：控制面板磁贴、无障碍自动扫描、分享菜单、短信自动识别、划词识别、手动录入
- **智能 OCR**：ML Kit 中英文混合识别，自动归一化 Unicode 横杠变体（含日文长音符 U+30FC）
- **多格式覆盖**：三段式 (1-2-3456)、四段式 (A1-2-3-45)、字母前缀三段式 (A1-2-3456)、字母-数字 (D-12345)、长数字、带前缀的取件码等
- **上下文感知**：区分快递/餐饮场景，自动过滤干扰数字
- **AI 增强**：可选接入任意 OpenAI 兼容 API（默认 GPT-4o-mini，可改），与正则并行识别，结果去重合并
- **券码（二维码）识别**：检测屏幕/图片中的二维码，解码结果作为码值入库

### 地址识别
- **预存地址优先**：把你常去的驿站/快递柜预先存好（**完整名称 + 关键词**），识别命中任一关键词时，
  直接用你录入的完整名称替换识别结果，而不是 OCR 抄下来的那行。关键词留空则用完整名称匹配。
  支持从历史记录一键导入、停用、删除；删除过的不会再被自动学回来。地址加密保存在本机。

  <div align="center">
    <img src="screenshots/saved-address-dialog.jpg" width="34%" alt="常用取件地址：完整名称 + 关键词" />
  </div>

- **逐码窗口定位**：多条通知同屏时，按码所在的卡片窗口取地址，避免不同驿站之间串台
- **多策略管线**：显式标签 → 「到…取件」句式 → 号柜 → 管道分隔 → 兜底等 11 级策略，自动跨行拼接 OCR 拆断的地址
- **折叠地址补全**：收货/取件地址被 UI 折叠成短串时，自动用同屏更完整的街道地址替换
- **快递100 反向验证**：识别到运单号时调快递100 API 查取件码/地址作为标准答案，对照校验并补全地址

### 身份码
取件时给驿站/柜机核验的身份码由各平台动态生成，第三方拿不到也不该拿，所以做成一键跳转：
- **淘宝身份码 / 菜鸟出库码 / 拼多多身份码**：三个入口分开，各带品牌单色图标
- 入口位置：主页顶栏、详情页标题栏
- **三级降级**：直达深链 → 备用入口 → 打开对应 App 首页，失败会明确提示，不静默失败
- 部分机型首次跳转时系统会询问是否允许，允许后会记住

### 来源识别
- **订单号前缀匹配**：JT→极兔、SF→顺丰、YT→圆通 等，优先于 OCR 文本
- **结构化定位**：品牌+快递/速递/物流后缀识别 + 方括号品牌 + 邻近行匹配
- **品牌图标**：识别到的来源显示对应品牌单色图标（快递、外卖、茶饮、餐饮共 24 个品牌），统一跟随主题色

### 数据管理
- **智能去重**：同码删除时一键清理所有重复记录，批量删除不残留
- **重复通知**：码值再次出现时推送提醒，可跳转整理
- **稍后提醒**：通知栏一键稍后提醒，1 小时后重新推送，取件后自动取消
- **回收站**：标记已取后保留 24 小时，可撤销可恢复
- **地图验证**：提取地址后自动调用地理编码验证真实性（支持高德 API）
- **截图治理**：删除记录时一并回收截图文件；启动时自动清理孤儿截图、超过 30 天的截图，并把截图目录控制在 50 MB 以内
- **回收站清理**：过期记录连同其截图一起删除

### 隐私
- **纯本地**：取件记录、预存地址等数据只存在本机，不上传云端
- **加密存储**：API Key 与常用取件地址经 AndroidKeyStore AES-GCM 加密后落盘
- **身份码页面不采集**：识别到身份码/出库码页面时不读取、不截图、不入库（身份码等于取件授权凭证）
- **网络功能可选**：AI 识别、地图验证、快递100 反查都需要手动配置开启，默认关闭

### 自学习
- **自动模式发现**：从未识别的 OCR 文本中聚类分析，自动生成新正则并应用
- **用户反馈闭环**：详情页确认/标记错误，记录每个模式的准确率
- **统计面板**：总览命中率、模式分布、已学习规则、候选建议
- **识别成绩单**：一键生成统计卡片图片，分享给好友

## 下载

到 [Releases](https://github.com/zixij644-elaborate/pickup-code-app/releases/latest) 下载对应架构的 APK：

| 版本 | 适用设备 | 直链 |
|---|---|---|
| app-arm64-v8a-release.apk | 大部分新手机（含 vivo/小米等 arm64 机型） | [下载](https://github.com/zixij644-elaborate/pickup-code-app/releases/latest/download/app-arm64-v8a-release.apk) |
| app-armeabi-v7a-release.apk | 较老 32 位设备 | [下载](https://github.com/zixij644-elaborate/pickup-code-app/releases/latest/download/app-armeabi-v7a-release.apk) |

不确定选哪个？近几年的手机基本都是 arm64-v8a。每个 Release 都附带 SHA-256 校验文件。

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

建议顺手做的两件事：在「设置 → 常用取件地址」里存上你常去的驿站（识别会更准）；需要时用主页顶栏的身份码按钮。

## 技术栈

| 模块 | 技术 |
|------|------|
| UI | Jetpack Compose + Material3 |
| OCR | ML Kit Text Recognition (Chinese) |
| 截屏 | 无障碍服务 takeScreenshot |
| 外部接收 | Intent Filter (SEND / PROCESS_TEXT) |
| 触发 | Quick Settings Tile + 无障碍自动扫描 |
| 存储 | Room (SQLite) |
| 设置 | DataStore Preferences |
| 加密 | AndroidKeyStore AES-GCM（API Key、常用取件地址） |
| 身份码跳转 | Intent 深链 + Android 11 包可见性声明 (queries) |
| 地图 | Android Geocoder + 高德 API（可选） |
| 快递验证 | 快递100 API（可选） |
| AI | 可选接入任意 OpenAI 兼容 API（默认 GPT-4o-mini，支持修改 API 地址与密钥） |
| 自学习 | 本地聚类分析 + 自动正则生成 |

## 项目结构

```
app/src/main/java/com/pickupcode/app/
├── App.kt                 # Application：全局 scope、通知频道、启动时截图治理
├── MainActivity.kt        # 主页/历史列表/回收站/手动录入 + 导航
├── data/                  # 数据层：Room 实体 + DAO(去重/回收站/归档)
├── extractor/             # 识别核心
│   ├── CodeExtractor.kt        # 取件/取餐码 正则+评分+地址策略管线
│   ├── AddressExtractor.kt     # 地址识别：预存地址 → 逐码窗口 → 全屏多级策略
│   ├── SavedAddressMatcher.kt  # 预存地址匹配（纯函数，可单测）
│   ├── AIExtractor.kt          # OpenAI 兼容 AI 提取
│   └── CouponDetector.kt       # 券码(二维码)检测+解码
├── ocr/OCREngine.kt       # ML Kit 文本识别
├── learner/               # 自学习
│   ├── PatternLearner.kt       # 自动生成正则/统计
│   ├── CommonStationStore.kt   # 自动学习的常用站点（仅作"从历史导入"候选）
│   └── SavedAddressStore.kt    # 用户预存地址（加密存储）
├── util/                  # IdentityCodeLauncher(身份码跳转)、ScreenshotStore(截图治理)、SensitivePageGuard(身份码页面拒采)
├── geocoder/GeocoderVerifier.kt   # 地址地理编码验证
├── kuaidi100/Kuaidi100Verifier.kt # 快递100 运单反查
├── notification/          # 取餐/取件/券码通知 + 已取/忽略广播
├── preferences/           # 设置(DataStore) + SecretCipher(加解密)
├── service/               # 无障碍服务(截屏+识别)、快捷磁贴
├── share/ShareReceiver.kt # 外部分享/拖放
└── ui/                    # Compose UI：theme/components/screens
```

**识别流程**：`截图/分享 → OCR(文字) + 券码检测(二维码) + 正则/AI(取餐/取件码) → 合并去重(券码与食/件码互斥) → 地址识别(预存地址优先) → 存库 + 通知 → 地址验证/快递100反查`

## 取件码格式覆盖

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

## 开发者

想从源码编译、贡献代码或报告问题？请查看：

- **[构建指南](docs/BUILDING.md)** —— 从源码编译（JDK 17 / Gradle 8.9 / Android SDK 要求、常见问题）
- **[贡献指南](CONTRIBUTING.md)** —— 如何提 Issue、提交 PR、代码规范
- **[识别语料回归](docs/CORPUS.md)** —— 用真实截图语料量化识别 precision/recall，如何采集新语料

## 许可证

[GPL-3.0](LICENSE)

界面与品牌图标的来源、署名及各自许可证见 **[ICONS.md](ICONS.md)**。
