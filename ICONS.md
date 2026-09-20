# 图标来源与署名（ICONS）

本 App 界面里的图标分三类。**品牌图标仅为「识别来源」的指代用途（nominative use），商标归各品牌所有。**

## 1. 界面线性图标（`res/drawable/ic_*.xml`）

| 来源 | 许可证 | 用途 |
|---|---|---|
| [Lucide](https://lucide.dev/) | ISC | 界面通用图标（地址、包裹、设置、统计、回收站等 33 个） |
| 手写 VectorDrawable | 随本项目 GPL-3.0 | 少量几何图形（`ic_action_*` 等由 Lucide 图形转换而来） |

## 2. 品牌图标 — 矢量（`res/drawable/ic_brand_*.xml`）

| 品牌 | 图标集 | 许可证 |
|---|---|---|
| 淘宝 / 菜鸟 / 拼多多 | [Arcticons](https://github.com/Arcticons-Team/Arcticons) | CC BY-SA 4.0 |
| 必胜客 / 达美乐 | [Arcticons](https://github.com/Arcticons-Team/Arcticons) | CC BY-SA 4.0 |
| 肯德基 / 麦当劳 / 星巴克 / 美团 | [Simple Icons](https://simpleicons.org/) | CC0-1.0 |

## 3. 品牌图标 — 位图（`res/drawable-nodpi/brand_*_mono.png`）

15 个中文品牌的单色图标，**由本仓库自行制作**（非取自第三方图标库）：

- **描摹**：顺丰、韵达、圆通、申通、极兔、京东、德邦、瑞幸、蜜雪冰城、海底捞、绝味、周黑鸭、饿了么
  —— 从各品牌公开标识描摹为 128×128 单色轮廓（纯黑 + alpha，运行时按主题 tint 上色）。
- **亮度抠图重制**：中通、好利来 —— 圆盘底色 + 内部白色字形的标识，按亮度分离主体与白字，
  避免只取 alpha 时退化成实心圆。

> 这些图形是品牌标识的简化轮廓，仅用于在本 App 内标注「这条记录来自哪个品牌」，
> 不构成对任何品牌的授权、合作或背书主张。

## 图标集许可以及与本项目 GPL-3.0 的关系

- **Simple Icons（CC0-1.0）**：公有领域，无附加义务。
- **Lucide（ISC）**：宽松许可，保留版权声明即可。
- **Arcticons（CC BY-SA 4.0）**：要求**署名**；其 **ShareAlike** 仅作用于图标本身，
  本项目以「独立资源文件 + 本署名文件」的形式分发，源码整体仍为 GPL-3.0。

如某个品牌方要求移除对应图标，请在 Issue 中提出，我们会替换为该品牌的文字标签或通用类型图标。
