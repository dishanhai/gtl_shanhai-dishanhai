# 山海的神人私货 · gt_shanhai

[![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-green.svg)](https://www.minecraft.net/)
[![Forge 47.4.16+](https://img.shields.io/badge/Forge-47.4.16%2B-orange.svg)](https://files.minecraftforge.net/)
[![gt_shanhai 4.9.4](https://img.shields.io/badge/gt__shanhai-4.9.4-blue.svg)](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/tag/gt-shanhai-v4.9.4)

**山海的神人私货**是为 GregTech Leisure（GTL）整合包制作的 GTCEu 附属模组，由 **dishanhai（山海恒长在）**维护。内容覆盖格雷后期多方块与并行产线、AE2 样板自动化、阶段资源和商店。本文适用于公开发行的 **4.9.4 修正版（协议 23）**。

[English](#english) · [主要内容](#主要内容) · [下载与安装](#下载与安装) · [运行环境](#运行环境) · [反馈与支持](#反馈与支持)

## 主要内容

- **终焉、原初与归墟**。终焉创始现实修改矩阵、原始终焉引擎和归墟视界枢核构成后期机器体系。原初模块覆盖物质重组与铸造、质能转换、装配、深空汲取、寰宇模拟、真空零点能等生产路线。配方线程仓和终焉聚合枢纽用于管理配方线程。
- **AE2 样板与供料**。星律样板总成可以按配方自动生产，也能将多条配方合成一张星律通配样板。样板生成工具支持筛选配方并批量编码。星律云端样板库集中存放样板，山海无线样板管理终端用于远程查看与调整。库存输入、输出矩阵、自合成仓、虚拟物品供应和超级磁盘阵列可接入 ME 网络。
- **山海商店与阶段资源**。携带山海钱包即可打开商店，使用星火或无线电网电力结算物品和流体。货币中心支持兑换和转账，银会中心提供会员折扣，银行支持存贷。阶段资源、无限盘和抽奖为整合包进度提供额外的资源获取途径。
- **配方与游戏内指南**。模组加入简化配方、自定义配方和终局组件。携带配方修改器时可从配方页面调整适用配方。查找机器结构、配方规则与操作步骤时，请打开 GuideME 指南书「山海的神人私货引索」。

### 4.9.4 更新

- 主机之间可以连同独立电路搬运样板，样板仓的溢出产物支持保存和恢复。修正版补齐样板生成器在 Forge 注册 ID 不连续时遗漏的白名单条目。
- 商店批量下单基于同一份 AE 库存快照。归墟视界枢核显示实际发电速率，配方编辑器可恢复已删除的配方并保存结果。
- 新增 Fumo 玩偶、世界时钟回溯和单人存档编辑。全息面板执行命令前会展示预览，供玩家再次确认。[4.9.4 更新公告](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/tag/gt-shanhai-v4.9.4)

## 下载与安装

- **已有 GTL 实例**。关闭游戏并备份存档与自定义配置，解压山海完整包，按包内说明安装。Windows 玩家也可以运行包内的「一键安装山海私货.bat」。[下载山海完整包](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/download/gt-shanhai-v4.9.4/gt_shanhai-4.9.4-compact-protocol23.zip)
- **新建 GTL 实例**。解压 GTL 3.9.3+ 整合包，用支持 Modrinth 整合包的启动器导入其中的 `modpack.mrpack`。[下载 GTL 3.9.3+ 整合包](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/download/gt-shanhai-v4.9.4/GTL.v3.9.3%2B.v4.9.4-protocol23.zip)
- **手动安装**。在现有 GTL 实例中安装修正版主模组、同版 KubeJS 内容、两款 Forge 共享库和山海工具模组 `shanhai_dev.jar`，并保留实例所需的其他前置。[下载主模组](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/download/gt-shanhai-v4.9.4/gt_shanhai-4.9.4-protocol23.jar) · [配套附件](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/tag/gt-shanhai-v4.9.4)

联机时请在客户端和服务端同时安装 **协议 23** 修正版及配套共享库。协议 **22** 和 **23** 的 4.9.4 构建无法互相连接。[全部版本](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)

使用备用下载入口时，请核对附件名中的 `protocol23`。[Google Drive](https://drive.google.com/drive/folders/1ko4R7haIh1tx_fAdHfrQbzPpyqoV0eub) · [123 网盘](https://1828543941.share.123pan.cn/123pan/1cwljv-9ZYmd)

## 运行环境

以下要求适用于 **4.9.4 协议 23 修正版**。完整包附带两款 Forge 共享库，主模组内置 Kotlin for Forge。其他整合包依赖请以对应的 GTL 实例为准。

| 模组或运行环境 | 4.9.4 修正版要求 |
| --- | --- |
| Minecraft | 1.20.1 |
| Java | 21 |
| Forge | 47.4.16 或更高 |
| SH-Better-UI（Forge） | 1.0.12 或更高 |
| SH-Common-Lib（Forge） | 0.4.1 或更高 |
| GTCEu | 1.4.4 或更高 |
| GTLCore | 1.2.2.9 或更高 |
| GTLAdditions | 3.1 或更高 |
| GTL-Extend（`gtl_extend`） | 已弃用，可选 |
| KubeJS | 2001.6.5-build.14 或更高 |
| GuideME | 20.1.14 或更高 |
| AE2 Wireless Terminals（`ae2wtlib`） | 15.3.3 或更高 |
| Kotlin for Forge | `4.12 ≤ 版本 < 5.0`，主模组内置 4.12.0 |

AE2、JEI、GTMThings、FTB Quests、FTB Teams、Curios、ExtendedAE 与 ExtendedAE Plus 用于对应的样板、商店、钱包等功能。

## 反馈与支持

- 提交问题时请附上山海版本、GTL 整合包版本、复现步骤和相关日志。[GitHub Issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues) · [腾讯文档反馈表](https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002)
- 提交玩法建议或支持作者。[新功能意见征集](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13) · [爱发电](https://afdian.com/a/dishanhai)
- 项目许可文件。[LICENSE](LICENSE)

## English

**Shanhai (gt_shanhai)** is a GTCEu addon by **dishanhai** for the GregTech Leisure (GTL) modpack on **Minecraft 1.20.1 and Forge 47.4.16 or later**. This guide covers the public **4.9.4 corrected build (protocol 23)**.

[简体中文](#山海的神人私货--gt_shanhai) · [Features](#features) · [Installation](#installation) · [Requirements](#requirements) · [Feedback](#feedback)

### Features

- **Late-game multiblocks.** The Terminus and Primordial machine lines and the Guixu core support late-game production. Primordial modules cover matter recombination and casting, mass–energy conversion, assembly, deep-space extraction, universe simulation, and zero-point energy. The Recipe Thread Hatch and Terminus Aggregation Hub manage recipe threads.
- **AE2 automation.** The Stellar Law Pattern Buffer can run recipes automatically or group multiple recipes into a Stellar Wildcard Pattern. Batch tools filter recipes and encode patterns. The Stellar Cloud Pattern Library centralizes pattern storage, and the Shanhai Wireless Pattern Management Terminal lets you manage patterns remotely. Stock inputs, output matrices, self-crafting hatches, virtual item supply, and storage arrays connect to ME networks.
- **Shop and progression.** Carry a Shanhai Wallet to access the shop and trade items and fluids with Spark or wireless-grid power. Currency exchange, membership discounts, banking, progression resources, infinite storage cells, and a lottery offer additional ways to acquire resources.
- **Recipes and in-game guide.** Shanhai adds simplified recipes, custom recipes, and endgame components. The recipe modifier can adjust supported recipes from their recipe pages. Open the Shanhai GuideME book for multiblock structures, recipe rules, and operating instructions.

### What's new in 4.9.4

- Move patterns and their independent circuits between hosts. Pattern storage now saves overflow for recovery. The corrected build fixes missing pattern generator allowlist entries when Forge registry IDs are sparse.
- Batch shop orders share one AE inventory snapshot. The Guixu core shows actual power generation, and restored recipes stay available after a restart.
- The release adds Fumo dolls, world-clock rewind, and single-player save editing. Hologram panels preview commands before asking for confirmation. [4.9.4 release notes](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/tag/gt-shanhai-v4.9.4)

### Installation

- **Existing GTL instance.** Close the game and back up your worlds and custom configuration. Extract the complete Shanhai package and follow the included instructions. Windows players can also use the bundled installer. [Download the complete Shanhai package](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/download/gt-shanhai-v4.9.4/gt_shanhai-4.9.4-compact-protocol23.zip)
- **New GTL instance.** Extract the GTL 3.9.3+ bundle and import `modpack.mrpack` into a launcher that supports Modrinth modpacks. [Download the GTL 3.9.3+ bundle](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/download/gt-shanhai-v4.9.4/GTL.v3.9.3%2B.v4.9.4-protocol23.zip)
- **Manual installation.** Install the corrected mod JAR, matching KubeJS content, both Forge shared libraries, and `shanhai_dev.jar` into your existing GTL instance. Keep the other prerequisites required by your instance. [Download the mod JAR](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/download/gt-shanhai-v4.9.4/gt_shanhai-4.9.4-protocol23.jar) · [Companion downloads](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/tag/gt-shanhai-v4.9.4)

For multiplayer, install the corrected **protocol 23** build and its shared libraries on both client and server. The protocol **22** and **23** builds of 4.9.4 cannot connect to each other. [All releases](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)

When using a mirror, check the package filename for `protocol23`. [Google Drive](https://drive.google.com/drive/folders/1ko4R7haIh1tx_fAdHfrQbzPpyqoV0eub) · [123 Cloud Drive](https://1828543941.share.123pan.cn/123pan/1cwljv-9ZYmd)

### Requirements

The corrected build requires Java 21. The complete package includes SH-Better-UI 1.0.12 and SH-Common-Lib 0.4.1 for Forge, and the mod JAR embeds Kotlin for Forge 4.12.0. GTL-Extend is deprecated and optional. [Full requirements table](#运行环境)

Integrations use AE2, JEI, GTMThings, FTB Quests, FTB Teams, Curios, ExtendedAE, and ExtendedAE Plus.

### Feedback

- Include your Shanhai and GTL versions, steps to reproduce, and relevant logs when reporting an issue. [GitHub Issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues) · [Feedback form](https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002)
- Suggest a feature or support the author. [Feature requests](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13) · [Afdian](https://afdian.com/a/dishanhai)
- See the repository's license file. [LICENSE](LICENSE)
