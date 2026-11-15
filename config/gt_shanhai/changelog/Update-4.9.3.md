---
enabled: true
version: 4.9.3
title: gt_shanhai 4.9.3 更新公告
title_en: gt_shanhai 4.9.3 Release Notes
link: [[GitHub](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)] - 发布与下载 / Releases and downloads
link: [[GitHub Issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)] - 问题反馈 / Report an issue
link: [[Afdian](https://afdian.com/a/dishanhai)] - 爱发电赞助 / Sponsor on Afdian
---

# gt_shanhai 4.9.3

- **@dishanhai** 负责核心代码。
- 感谢所有提交问题、提供反馈和支持项目的玩家。
- 感谢爱发电赞助者 **爱发电用户_CVqE**、**纪念**、**爱发电用户_1e1c5**、**落幕**、**有c**、 **爱发电用户_AyGF**。[爱发电，支持 dishanhai](https://afdian.com/a/dishanhai)
- 🎉 **本期加入的赞助者**　**fkqingshan**

本次更新完善了原初机器的并行计算、AE2 样板编码与自动合成，新增低阶货币周期限售，并改进星律卡单提示和合成任务重派发功能。

## 📦 联机兼容与前置

<details>
<summary>🔗 联机升级与共享库版本</summary>

- 网络协议从 **18** 升至 **20**。联机时请将客户端和服务端一并更新至 **4.9.3**。
- 运行环境为 Minecraft **1.20.1**、Forge **47.4.16 或更高**。
- 客户端与服务端均需安装 Forge 版 **SH-Better-UI 1.0.7** 和 **SH-Common-Lib 0.2.0**。
- 手动更新时请配齐同版 KubeJS 内容和 GTL 前置。

</details>

## ⚙️ 原初机器与配方线程

<details>
<summary>🧵 并行计算、线程额度与信息显示</summary>

- 调整原初模块的并行计算和配方线程额度分配。
- 修复部分情况下独立并行、跨配方线程与共享并行池计算不符合预期的问题。
- 改进维护仓及 Jade 信息显示。
- 补充部分原初机器与奈亚拉托提普之触结构预览机的合成配方。

</details>

## 🧩 AE2 样板与自动合成

<details>
<summary>📝 样板编码与虚拟供应</summary>

- 改进虚拟供应样板的编码和包装逻辑，减少配方类型判断错误与不必要的包装。
- 优化编码终端与 JEI 快速编码按钮，并恢复星律样板上传入口。

</details>

<details>
<summary>🔄 合成任务重派发与副产物</summary>

- 改进合成任务的重派发功能：GRAPH 任务可重新规划尚未发配的部分，普通量子合成任务可尝试补取实际材料。
- 完善含副产物的合成订单处理。
- 修复部分情况下分子装配室产出倍率影响任务派发的问题。
- 商店自动合成规划按 AE 网络的实际可提取库存校验，并扣除前序方案已预留的材料。

</details>

## 💰 山海商店与货币中心

<details>
<summary>🪙 低阶货币周期限售</summary>

- 铂金币及以下货币兑换星火时，每位玩家的每个币种拥有独立额度；单币兑换和快速售出共用额度，超出部分保留在钱包或 AE 网络中。锇币及以上不受此限。
- 每升一档翻倍。默认铜币 4,096 枚、白铜币 8,192 枚、银币 16,384 枚、金币 32,768 枚、铂金币 65,536 枚，从首次成功出售起经过 **180 现实秒**刷新；存档关闭时暂停计时。
- 管理员可在商店编辑模式中调整限额和刷新周期，限额设为 0 可暂停低阶货币出售。界面会显示剩余额度、刷新时间及快速售出结果。

</details>

## 🌟 星律提示与界面

<details>
<summary>🚨 卡单提示与更新公告</summary>

- 改进星律样板卡单提示，接入活动岛展示详细信息，提示持续 30 秒。
- 接入共享更新公告界面，统一更新信息的显示方式。
- 修正物质复制机和部分指南中的闪存操作说明。

</details>

<!-- lang:en -->
<details>
<summary>🌐 English release notes</summary>

# gt_shanhai 4.9.3

This update improves Primordial machine parallel calculation, AE2 pattern encoding and crafting, adds periodic low-tier currency sale limits, and refines Stellar stuck-order warnings and crafting redispatch.

## 📦 Multiplayer and Dependencies

<details>
<summary>🔗 Multiplayer upgrades and shared libraries</summary>

- The network protocol changes from **18** to **20**. Update both the client and server to **4.9.3** when playing multiplayer.
- Use Minecraft **1.20.1** and Forge **47.4.16 or later**.
- Install the Forge versions of **SH-Better-UI 1.0.7** and **SH-Common-Lib 0.2.0** on both sides.
- Manual updates also require matching KubeJS content and GTL prerequisites.

</details>

## ⚙️ Primordial Machines and Recipe Threads

<details>
<summary>🧵 Parallel calculation, thread capacity, and information</summary>

- Adjusted parallel calculation and recipe-thread capacity allocation for Primordial modules.
- Fixed cases where independent parallelism, cross-recipe threads, and shared parallel pools were calculated incorrectly.
- Improved maintenance hatch and Jade information displays.
- Added crafting recipes for several Primordial machines and the Nyarlathotep's Tentacle structure preview machine.

</details>

## 🧩 AE2 Patterns and Crafting

<details>
<summary>📝 Pattern encoding and virtual supply</summary>

- Improved virtual supply pattern encoding and wrapping to reduce incorrect recipe-type decisions and unnecessary wrapping.
- Refined the encoding terminal and JEI quick-encoding buttons, and restored the Stellar pattern upload control.

</details>

<details>
<summary>🔄 Crafting redispatch and byproducts</summary>

- Improved crafting redispatch: GRAPH jobs can replan work that has not yet been dispatched, while ordinary quantum crafting jobs can retry acquiring physical materials.
- Improved handling of crafting orders involving byproducts.
- Fixed cases where Molecular Assembler output multipliers could interfere with task dispatch.
- Shop autocrafting plans check actually extractable AE network stock and subtract materials reserved by earlier plans.

</details>

## 💰 Shanhai Shop and Currency Center

<details>
<summary>🪙 Periodic limits for low-tier currency sales</summary>

- Each player has a separate sale allowance for each currency up to and including platinum coins when converting them to Spark. Single-currency conversion and quick sell share the allowance; excess currency stays in the wallet or AE network. Osmium coins and higher tiers are not subject to this limit.
- By default, up to **4,096 coins** of each low-tier currency can be sold per period. The allowance refreshes **3,600 game seconds** after the first successful sale; the timer pauses while the world is closed.
- Administrators can adjust the allowance and refresh period in shop edit mode. Setting the allowance to 0 disables low-tier currency sales. The interface shows remaining allowances, refresh times, and quick-sell results.

</details>

## 🌟 Stellar Warnings and Interface

<details>
<summary>🚨 Stuck-order warnings and update announcements</summary>

- Improved Stellar pattern stuck-order warnings, added detailed information to the activity island, and extended the notification to 30 seconds.
- Integrated the shared update announcement interface for a consistent presentation.
- Corrected flash drive instructions for the Matter Copier and in several guide pages.

</details>

</details>
