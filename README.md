# 码上闪记

[![Build and Test](https://github.com/zixij644-elaborate/pickup-code-app/actions/workflows/ci.yml/badge.svg)](https://github.com/zixij644-elaborate/pickup-code-app/actions/workflows/ci.yml)

自动识别截屏或分享图片中的取餐码，取件码和券码，通知提醒 + 一键标记已取。

<div align="center">
  <img src="screenshots/Screenshot_20260813_164743.jpg" width="30%" alt="截图 3" />
  <img src="screenshots/Screenshot_20260813_165152.jpg" width="30%" alt="截图 4" />
  <img src="screenshots/Screenshot_20260813_163603.jpg" width="30%" alt="截图 1" />
  <img src="screenshots/Screenshot_20260813_163606.jpg" width="30%" alt="截图 2" />
  <img src="screenshots/Screenshot_20260813_165244.jpg" width="30%" alt="截图 5" />
  <img src="screenshots/Screenshot_20260813_165248.jpg" width="30%" alt="截图 6" />
  <img src="screenshots/Screenshot_20260813_165252.jpg" width="30%" alt="截图 7" />
</div>

## 功能

### 核心识别
- **六种触发方式**：控制面板磁贴、无障碍自动扫描、分享菜单、短信自动识别、划词识别、手动录入
- **智能 OCR**：ML Kit 中英文混合识别，自动归一化 Unicode 横杠变体（含日文长音符 U+30FC）
- **多格式覆盖**：三段式 (1-2-3456)、四段式 (A1-2-3-45)、字母前缀三段式 (A1-2-3456)、字母-数字 (D-12345)、长数字、带前缀的取件码等
- **上下文感知**：区分快递/餐饮场景，自动过滤干扰数字
- **AI 增强**：可选接入任意 OpenAI 兼容 API（默认为OpenAI的GPT-4o-mini模型，支持修改），与正则并行识别，结果去重合并
- **券码（二维码）识别**：检测屏幕/图片中的**二维码**，解码结果作为码值并入库
### 地址识别
- **多策略管线**：显式标签 → 「到…取件」句式 → 号柜 → 管道分隔 → 兜底等 11 级策略，自动跨行拼接 OCR 拆断的地址
- **折叠地址补全**：收货/取件地址被 UI 折叠成短串时，自动用同屏更完整的街道地址替换
- **快递100 反向验证**：识别到运单号时调快递100 API 查取件码/地址作为标准答案，对照校验并补全地址

### 来源识别
- **订单号前缀匹配**：JT→极兔、SF→顺丰、YT→圆通 等，优先于 OCR 文本
- **结构化定位**：品牌+快递/速递/物流后缀识别 + 【】括号品牌 + 邻近行匹配
- **餐饮品牌覆盖**：瑞幸、星巴克、喜茶、蜜雪冰城、霸王茶姬、林里 等 24 个品牌

### 数据管理
- **智能去重**：同码删除时一键清理所有重复记录，批量删除不残留
- **重复通知**：码值再次出现时推送提醒，可跳转整理
- **稍后提醒**：通知栏一键"稍后提醒"，1 小时后重新推送，取件后自动取消
- **回收站**：标记已取后保留 24 小时，可撤销可恢复
- **地图验证**：提取地址后自动调用地理编码验证真实性（支持高德 API）
- **纯本地**：数据仅存储在本地，不上传云端。AI/地图/快递100等网络验证功能均为可选，需手动配置开启。

### 自学习
- **自动模式发现**：从未识别的 OCR 文本中聚类分析，自动生成新正则并应用
- **用户反馈闭环**：详情页确认/标记错误，记录每个模式的准确率
- **统计面板**：总览命中率、模式分布、已学习规则、候选建议
- **识别成绩单**：一键生成统计卡片图片，分享给好友

## 快速开始

1. 下载 APK 安装
2. 开启无障碍服务（设置 → 无障碍 → 码上闪记）
3. 把磁贴加到控制面板（下拉 → ✏️ → 找到「码上闪记」）
4. 方式一：打开外卖/快递 App → 点磁贴 → 自动识别
5. 方式二：截图 → 分享菜单 → 选择「码上闪记」
6. 方式三：在软件中分享图片 → 码上闪记 → 自动识别（仅支持分享图片）
7. 方式四：快递取件短信到达时自动识别（需在设置中开启短信权限）
8. 方式五：长按选中文本 → 工具栏选「码上闪记」划词识别
9. 方式六：点右下角 ➕ 手动录入

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
| 地图 | Android Geocoder + 高德 API（可选） |
| 快递验证 | 快递100 API（可选） |
| AI | 可选接入任意 OpenAI 兼容 API （默认使用 GPT-4o-mini，支持修改 API 地址与密钥） |
| 自学习 | 本地聚类分析 + 自动正则生成 |

## 项目结构

```
app/src/main/java/com/pickupcode/app/
├── App.kt                 # Application：全局 scope、通知频道
├── MainActivity.kt        # 主页/历史列表/回收站/手动录入 + 导航
├── data/                  # 数据层：Room 实体 + DAO(去重/回收站/归档)
│   ├── CodeHistory.kt
│   └── CodeHistoryDao.kt
├── extractor/             #  识别核心
│   ├── CodeExtractor.kt   #  取件/取餐码 正则+评分+地址S0~S10管线
│   ├── AIExtractor.kt     #  OpenAI 兼容 AI 提取
│   └── CouponDetector.kt  #  券码(二维码)检测+解码
├── ocr/OCREngine.kt       #  ML Kit 文本识别
├── learner/PatternLearner.kt  # 自学习：自动生成正则/统计
├── geocoder/GeocoderVerifier.kt   # 地址地理编码验证
├── kuaidi100/Kuaidi100Verifier.kt # 快递100 运单反查
├── notification/          # 取餐/取件/券码通知 + 已取/忽略广播
├── preferences/AppPreferences.kt  # DataStore 设置
├── service/               # 无障碍服务(截屏+识别)、快捷磁贴
├── share/ShareReceiver.kt # 外部分享/拖放
└── ui/                    # Compose UI：theme/components/screens
```

**识别流程**：`截图/分享 → OCR(文字) + 券码检测(二维码) + 正则/AI(取餐/取件码) → 合并去重(券码与食/件码互斥) → 存库 + 通知 → 地址验证/快递100反查`

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

## 许可证

[GPL-3.0](LICENSE)
