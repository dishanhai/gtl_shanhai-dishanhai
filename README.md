# 山海的神人私货 · gt_shanhai

[![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-green.svg)](https://www.minecraft.net/)
[![Forge 47.4.16+](https://img.shields.io/badge/Forge-47.4.16%2B-orange.svg)](https://files.minecraftforge.net/)
[![gt_shanhai 4.9.1](https://img.shields.io/badge/gt__shanhai-4.9.1-blue.svg)](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/tag/gt-shanhai-v4.9.1)

**山海的神人私货**是为 GregTech Leisure（GTL）整合包制作的 GTCEu 附属模组，由 **dishanhai（山海恒长在）**维护。内容覆盖格雷后期多方块与并行产线、AE2 样板自动化、阶段资源和商店。本文以公开发行版 **4.9.1** 为准。

[English](#english) · [主要内容](#主要内容) · [下载与安装](#下载与安装) · [运行环境](#运行环境) · [反馈与支持](#反馈与支持)

## 主要内容

- **终焉、原初与归墟**。终焉创始现实修改矩阵、原始终焉引擎和归墟视界枢核构成后期机器体系。原初模块覆盖物质重组与铸造、质能转换、装配、深空汲取、寰宇模拟、真空零点能等生产路线。配方线程仓和终焉聚合枢纽用于管理配方线程。
- **AE2 样板与供料**。星律样板总成可以按配方自动生产，也能将多条配方合成一张星律通配样板。样板生成工具支持筛选配方并批量编码。星律云端样板库集中存放样板，山海无线样板管理终端用于远程查看与调整。库存输入、输出矩阵、自合成仓、虚拟物品供应和超级磁盘阵列可接入 ME 网络。
- **山海商店与阶段资源**。携带山海钱包即可打开商店，使用星火或无线电网电力结算物品和流体。货币中心支持兑换和转账，银会中心提供会员折扣，银行支持存贷。阶段资源、无限盘和抽奖为整合包进度提供额外的资源获取途径。
- **配方与游戏内指南**。模组加入简化配方、自定义配方和终局组件。携带配方修改器时可从配方页面调整适用配方。查找机器结构、配方规则与操作步骤时，请打开 GuideME 指南书「山海的神人私货引索」。

### 4.9.1 更新

原初模块支持同配方与跨配方批次合并，超限模式为适用模块启用独立配方并行池。AE2 样板编码终端与 JEI 可以将样板直接上传到星律云端样板库。更新还修复了无线样板管理终端的大包断连和专用服务端的玻璃界面崩溃。[游戏内更新公告源文](config/gt_shanhai/changelog/Update-4.9.1.md) · [GitHub Release](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/tag/gt-shanhai-v4.9.1)

## 下载与安装

1. 下载山海完整包。完整包包含模组、配套前置和 KubeJS 脚本，用于安装到已有的 GTL 实例中。单独安装主模组 JAR 缺少完整玩法所需的脚本和资源。[下载 4.9.1 完整包](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/download/gt-shanhai-v4.9.1/shanhai-complete-package-v4.9.1.zip) · [全部版本](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)
2. 按压缩包内说明安装到对应的 GTL 游戏目录，核对实例内的前置版本。
3. 启动游戏后查看更新公告。联机时请将客户端和服务端一并升级至 4.9.1，其网络协议为 **17**。

备用下载入口。[Google Drive](https://drive.google.com/drive/folders/1ko4R7haIh1tx_fAdHfrQbzPpyqoV0eub) · [123 网盘](https://1828543941.share.123pan.cn/123pan/1cwljv-9ZYmd)

## 运行环境

以下为 **4.9.1** 的运行依赖。完整包已随附 SH-Better-UI 和 Kotlin for Forge 运行时，其他整合包依赖请以对应的 GTL 实例为准。[4.9.1 模组依赖声明](https://github.com/dishanhai/gtl_shanhai-dishanhai/blob/gt-shanhai-v4.9.1/src/main/resources/META-INF/mods.toml) · [4.9.1 构建版本](https://github.com/dishanhai/gtl_shanhai-dishanhai/blob/gt-shanhai-v4.9.1/gradle.properties)

| 模组或运行环境 | 4.9.1 要求 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.16 或更高 |
| GTCEu | 1.4.4 或更高 |
| GTLCore | 1.2.2.9 或更高 |
| GTLAdditions | 3.1 或更高 |
| GTL-Extend（`gtl_extend`） | 必装，未声明最低版本 |
| KubeJS | 2001.6.5-build.14 或更高 |
| GuideME | 20.1.14 或更高 |
| AE2 Wireless Terminals（`ae2wtlib`） | 15.3.3 或更高 |
| SH-Better-UI（Forge） | 完整包附带 1.0.3，声明范围为 1.0.3（含）至 2.0.0（不含） |
| Kotlin for Forge | 4.12（含）至 5.0（不含），主模组内置 |

AE2、JEI、GTMThings、FTB Quests、FTB Teams、Curios、ExtendedAE 与 ExtendedAE Plus 用于对应的样板、商店、钱包等功能。整合包兼容性与已知冲突见版本发布页。[4.9.1 兼容性说明](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/tag/gt-shanhai-v4.9.1)

## 反馈与支持

- 提交问题时请附上山海版本、GTL 整合包版本、复现步骤和相关日志。[GitHub Issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues) · [腾讯文档反馈表](https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002)
- 提交玩法建议或支持作者。[新功能意见征集](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13) · [爱发电](https://afdian.com/a/dishanhai)
- 项目许可文件。[LICENSE](LICENSE)

## English

**Shanhai (gt_shanhai)** is a GTCEu addon by **dishanhai** for the GregTech Leisure (GTL) modpack on **Minecraft 1.20.1 and Forge 47.4.16 or later**. This guide covers the public **4.9.1** release.

[简体中文](#山海的神人私货--gt_shanhai) · [Features](#features) · [Installation](#installation) · [Requirements](#requirements) · [Feedback](#feedback)

### Features

- **Late-game multiblocks.** The Terminus and Primordial machine lines and the Guixu core support late-game production. Primordial modules cover matter recombination and casting, mass–energy conversion, assembly, deep-space extraction, universe simulation, and zero-point energy. The Recipe Thread Hatch and Terminus Aggregation Hub manage recipe threads.
- **AE2 automation.** The Stellar Law Pattern Buffer can run recipes automatically or group multiple recipes into a Stellar Wildcard Pattern. Batch tools filter recipes and encode patterns. The Stellar Cloud Pattern Library centralizes pattern storage, and the Shanhai Wireless Pattern Management Terminal lets you manage patterns remotely. Stock inputs, output matrices, self-crafting hatches, virtual item supply, and storage arrays connect to ME networks.
- **Shop and progression.** Carry a Shanhai Wallet to access the shop and trade items and fluids with Spark or wireless-grid power. Currency exchange, membership discounts, banking, progression resources, infinite storage cells, and a lottery offer additional ways to acquire resources.
- **Recipes and in-game guide.** Shanhai adds simplified recipes, custom recipes, and endgame components. The recipe modifier can adjust supported recipes from their recipe pages. Open the Shanhai GuideME book for multiblock structures, recipe rules, and operating instructions.

### What's new in 4.9.1

Primordial modules can merge same-recipe and cross-recipe batches, and supported modules gain independent per-recipe pools in overdrive mode. Upload patterns to the Stellar Cloud Pattern Library from the AE2 Pattern Encoding Terminal or JEI. The release also fixes large-payload disconnects in the wireless terminal and a glass UI crash on dedicated servers. [In-game changelog](config/gt_shanhai/changelog/Update-4.9.1.md) · [GitHub Release](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/tag/gt-shanhai-v4.9.1)

### Installation

1. Download the complete package. It includes the mod, bundled prerequisites, and KubeJS content for an existing GTL instance. The standalone mod JAR requires the matching scripts and resources. [Download the 4.9.1 complete package](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/download/gt-shanhai-v4.9.1/shanhai-complete-package-v4.9.1.zip) · [All releases](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)
2. Follow the package instructions and check the prerequisites in your GTL instance.
3. Start the game and open the in-game changelog. For multiplayer, update the client and server together to 4.9.1, which uses protocol **17**.

Download mirrors. [Google Drive](https://drive.google.com/drive/folders/1ko4R7haIh1tx_fAdHfrQbzPpyqoV0eub) · [123 Cloud Drive](https://1828543941.share.123pan.cn/123pan/1cwljv-9ZYmd)

### Requirements

Use the [4.9.1 requirements table](#运行环境) for exact versions. GTL-Extend is required, the complete package includes SH-Better-UI for Forge 1.0.3, and the mod JAR embeds Kotlin for Forge. Integrations use AE2, JEI, GTMThings, FTB Quests, FTB Teams, Curios, ExtendedAE, and ExtendedAE Plus. [Modpack compatibility and known conflicts](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/tag/gt-shanhai-v4.9.1)

### Feedback

- Include your Shanhai and GTL versions, steps to reproduce, and relevant logs when reporting an issue. [GitHub Issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues) · [Feedback form](https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002)
- Suggest a feature or support the author. [Feature requests](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13) · [Afdian](https://afdian.com/a/dishanhai)
- See the repository's license file. [LICENSE](LICENSE)
