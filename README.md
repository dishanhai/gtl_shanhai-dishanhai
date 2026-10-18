# 山海的神人私货（gt_shanhai）

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green.svg)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.4.16-orange.svg)](https://files.minecraftforge.net/)
[![内容版本](https://img.shields.io/badge/changelog-4.5.1-blue.svg)](docs/release/gt_shanhai-v4.5.1.md)

GTL 整合包用的 GTCEu 附属模组。模组 ID 是 `gt_shanhai`，创造模式物品栏显示名是 `dishanhai`，简介是「山海的神人私货 — 终焉超时空波动矩阵」。作者：山海恒长在（dishanhai）。

单独把 `gt_shanhai.jar` 丢进 `mods` 不能玩。安装用发布页里的完整包。

仓库：<https://github.com/dishanhai/gtl_shanhai-dishanhai>

---

## 版本

| 位置 | 当前值 |
| --- | --- |
| 游戏内更新日志、`config/gt_shanhai/changelog/`、`docs/release/` | **4.5.1**（2026-10-18） |
| `gradle.properties` 的 `mod_version`，以及打出来的 jar `Implementation-Version` | **4.4.0** |

4.5.0、4.5.1 的发布说明已经写进仓库，Gradle 版本号还没有跟着改。看玩家公告以 4.5.1 为准，看 jar 元数据以 `gradle.properties` 为准。

逐步变更写在：

- 游戏内全屏更新日志
- [`config/gt_shanhai/changelog/`](config/gt_shanhai/changelog/)
- [`docs/release/gt_shanhai-v4.5.1.md`](docs/release/gt_shanhai-v4.5.1.md)
- [`docs/release/gt_shanhai-v4.5.0.md`](docs/release/gt_shanhai-v4.5.0.md)

---

## 运行环境

Minecraft **1.20.1**，Forge **47.4.16**，编译用 **Java 21**。

`src/main/resources/META-INF/mods.toml` 里标成必选的依赖：

| 模组 | 声明范围 | 本仓库 `libs/` 里用来编译的文件 |
| --- | --- | --- |
| GTCEu | 1.4.4+ | `gtceu-1.20.1-1.4.4.jar` |
| GTLCore | 1.2.2.9+ | `gtlcore-1.2.3.2-fix4.jar` |
| gtladditions | 3.1+ | `gtladditions-3.3.1Custom.jar` |
| gtl_extend | 必选，无版本下限 | `libs/` 里没有同名 jar，由整合包提供 |
| KubeJS | 2001.6.5-build.14+ | `kubejs-forge-2001.6.5-build.16.jar` |
| GuideME | 20.1.14+ | `guideme-20.1.14.jar` |
| ae2wtlib | 15.3.3+ | 编译期引用游戏目录里的 `ae2wtlib-15.3.3-forge.jar` |

功能代码还会直接用到这些，整合包里需要在场：

- Applied Energistics 2（`libs` 为 15.4.10）
- JEI 15.49
- GTMThings 1.3.5.b：商店的 EU 成本走它的无线电网账本
- LDLib 1.0.33.b
- FTB Quests、FTB Teams、FTB Library
- Curios：钱包槽
- ExtendedAE、ExtendedAE Plus：星律样板界面的一部分
- cloth-config：模组列表里的配置界面，只在编译期引用

`lib_source/` 是反编译参考。ExtendedAE、FTB Quests、Jade、gtmthings 的源码版本已经落后于 `libs/`，查类成员以 `libs/` 里的 jar 为准。

---

## 安装

1. 从 [Releases](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases) 拿对应版本的完整包，例如 `shanhai-complete-package-v4.5.1.zip`。
2. 按完整包说明放进 GTL 整合包。发布说明写明：单独的 `gt_shanhai-v4.5.1.jar` 不能独立安装。
3. 进游戏后看全屏更新日志。指南书物品是「山海的神人私货引索」。

中文名以 `src/main/resources/assets/gt_shanhai/lang/zh_cn.json` 为准。

---

## 机器

注册入口是 `DShanhaiMachines`。下面的名字都来自当前语言文件。

### 主机

- **终焉创始现实修改矩阵**
- **原始终焉引擎**：原初模块的宿主

### 原初多方块

原始真空零点能发生器、原初质能核心、原初薪火裂解窑、原初渊薮炼化塔、原初寰宇探源核心、原初生物核心、原初混沌蜉蝣解构结晶炉、原初物质重组核心、原初因果编织矩阵、原初奇点反演核心、原初世界碎片采集器、原初装配线模块、原初临界加工模块、原初多维聚爆核心、原初超临界物质生成核心、原初宇宙反应炉、原初分子裂隙核心、原初天琼组装核心、原初永恒熔炼炉、原初世线穿刺矩阵、原初量子扭曲矩阵、原初韶光聚合核心、原初六源统御核心、原初深空汲取核心、原初万象衍生核心、原初寰宇模拟核心、原初未央重构模块、原初世线蚀刻核心、原初分子操纵模块、原初铸币工厂、原初分歧发生器、原初物质铸造机、原初反熵冷凝核心、原初太虚宇宙锻炉。

`gtl_extend` 在场时，原初六源统御核心会多带大型虚空泵配方类型。

4.5.1 里和原初配方直接相关的两点：原初生物演化协议改成 1 格物品输入、72 格物品输出；原初永恒熔炼炉增加配方类型「原初逆熵构成」。原初渊薮炼化塔带「太素衍化」，同时兼容 gtladditions 3.2 和 3.3.1。

### 其他多方块

世线剥离震荡发生器、引力波天线发射器、天界星云零点虹吸枢纽、零点光子转换器、世线裂解枢纽、亚稳态黑洞遏制场、物质定增、进阶质量发生器、大明科技、空间缩放仪、集成装配矩阵、综合组装车间、盒系统中央控制器、代理执行者、奇点数据中枢、绝对量子完美净化单元、天界领航塔。

永恒格雷工坊另有聚变模块、创世之眼模块、额外模块。

### 仓室与总线

星律样板总成、星律样板代理、星空可请求标签库存输入总成、ME 可请求输入总线、ME 可请求输入仓、可请求输入总成、ME 相位输出矩阵、ME 星轨输出矩阵、ME 磁盘仓室、自合成仓、虚拟物品供应机、山海特大号标签过滤库存总线、山海商店终端、FTBQ AE 自动提交器。

可编程仓、超频仓、逻辑算力仓、太初分歧引擎、超级并行核心、终焉聚合枢纽、七十二变。

---

## 样板与 AE

- **星律样板总成**按配方类型挂样板，可跨宿主已有的配方类型执行。通配符槽按页配置。打开「样板强匹配」后，只认配方类型和完整配方 ID；拒绝原因写在格子悬停、强匹配页、Jade 和卡死告警上。
- **星律通配样板**按记录的配方 ID 展开，放进通配符槽。分「自动生成」和「手动更改」，手动页 70 格。一张最多 4096 条配方。
- **山海样板生成工具**按机器、配方类型、电路、物品、流体和文字条件筛选，再批量生成样板或收成通配样板。预设在 `config/gt_shanhai/pattern-generator-presets`。
- **山海无线样板管理终端**从星律分组的「星」按钮进入，可看状态、传送，并改样板、库存、倍率和通配符。
- **自合成仓**放入处理样板后由仓室自己完成 AE 合成，不需要多方块主机。
- **星空可请求标签库存输入总成**按样板输入做标签匹配，耗料时从 ME 抽取；侧栏可提交合成请求。
- **虚拟物品提供器**给不消耗输入编码。样板编码可以选是否写入配方 ID、是否写入虚拟供应器。
- **超级磁盘阵列**不再嵌套已经有内容的磁盘、另一张超级磁盘阵列或虚拟盘。
- **山海终极终端**在 AE 模式里先算缺口再确认下单。

---

## 商店、货币、配方工具

- **山海钱包**放进 Curios 钱包槽后，默认按 `S` 打开商店。悬停货币中心里的币，按住商店快捷键约半秒，会校验钱包并定位到该币。
- 商品可以是物品、流体，也可以设成免费。五条成本都空、又没点免费时，保存会被拦住。免费商品可以买，不能卖。
- 成本通道包括星火数字余额，以及 GTMThings 无线电网里的 EU。高阶币兑星火走阶梯价。
- **银会中心**负责会员折扣。商店银行可以存款、贷款，利率和欠款上限在模组配置里。
- 阶段未解锁时，可以用 AE 下单补齐阶段前置。关闭商店再打开会记住上次的商品和搜索词。
- **配方修改器开发者工具**需要背包里有这个物品，并且权限等级达到 2。JEI 配方页可以「编辑此配方」。支持标签匹配、温度、线圈、备注、按类型导出，以及默认关闭某条配方。
- 模组列表里的配置界面覆盖样板总成、商店、商店银行、缓存诊断和开发模式。开发模式关掉时，配方导出到游戏目录 `kubejs/data/Exported_Recipe/`。

终局物品里，**创始演算器**、**万象原核**、**寰宇模拟单元**、**寰宇电路重组单元**会作为不消耗输入写进对应配方。创始演算器本身没有机器逻辑。

---

## 构建

仓库自带 Gradle 8.8：

```powershell
.\gradle-install\gradle-8.8\bin\gradle build --no-daemon
```

产物在 `build/libs/gt_shanhai.jar`，源码 jar 一并生成。

`build_mod.bat` 会清掉 `build/`、跳过测试、再把 jar 拷到作者本机的 GTL 九周目 `mods` 目录。`build.gradle` 里 FTB Quests、FTB Teams、cloth-config、ae2wtlib 也指向这台机器的游戏目录。换电脑要先改这些路径。

---

## 反馈

- [问题与建议反馈表](https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002)
- [Issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)
- [新功能意见征集](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13)
- [爱发电](https://afdian.com/a/dishanhai)

---

## 许可

仓库根目录的 [`LICENSE`](LICENSE) 是 **GNU LGPLv3**。

`mods.toml` 的 `license` 字段目前写成 `All Rights Reserved`，和 `LICENSE` 文件不一致。

---

## English

**gt_shanhai** is the GTCEu addon used by the GTL modpack (Minecraft 1.20.1, Forge 47.4.16). Install the complete package from Releases. A lone jar does not load the pack content.

The in-game changelog and `docs/release/` are at **4.5.1** (2026-10-18). `gradle.properties` still sets `mod_version` to **4.4.0**, so the built jar's implementation version is 4.4.0.

Mandatory mods are declared in `src/main/resources/META-INF/mods.toml`: GTCEu, GTLCore, gtladditions, gtl_extend, KubeJS, GuideME, and ae2wtlib, plus Forge and Minecraft. AE2, JEI, GTMThings, FTB, Curios, and ExtendedAE are used by shop, pattern, and wireless-EU features.

Player-facing systems, using the current Chinese names: the Primordial Omega Engine and its primordial multiblocks, the Stellar Pattern Buffer, wildcard patterns with strict recipe-id matching, the pattern generator, the shop and Spark currency, the recipe modifier, and GuideME. Machine and item names live in `src/main/resources/assets/gt_shanhai/lang/zh_cn.json`.

Build with Java 21:

```powershell
.\gradle-install\gradle-8.8\bin\gradle build --no-daemon
```

The root `LICENSE` file is LGPLv3. The `license` field in `mods.toml` currently says `All Rights Reserved`.

Last checked against the repository on 2026-10-18.
