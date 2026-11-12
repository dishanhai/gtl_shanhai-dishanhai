---
enabled: true
version: 4.9.2
title: gt_shanhai 4.9.2 更新公告
title_en: gt_shanhai 4.9.2 Release Notes
link: [[GitHub](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)] - 发布与下载 / Releases and downloads
link: [[#37](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/37)] - aiwan-98：原初线程反馈 / aiwan-98: Primordial threading feedback
link: [[#38](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/38)] - lycc193bp：世界载入界面本地化 / lycc193bp: World loading screen localization
link: [[#39](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/39)] - lycc193bp：商店物品图标大小 / lycc193bp: Shop item icon sizing
link: [[#40](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/40)] - @nancheng197：AE2 合成下单原始反馈 / @nancheng197: Original AE2 crafting request report
link: [[GitHub Issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)] - 问题反馈 / Report an issue
link: [[Afdian](https://afdian.com/a/dishanhai)] - 爱发电赞助 / Sponsor on Afdian
---

# gt_shanhai 4.9.2

本次更新改进了山海商店、世界载入界面、AE2 虚拟供应与量子合成，并调整原初线程的并行分配。完整包安装流程可使用 Windows 一键安装脚本。

## 联机兼容与前置

<details>
<summary>协议 18、联机升级与共享库版本</summary>

- 网络协议从 4.9.1 的 **17** 升至 **18**。联机请将客户端和服务端一并更新至 **4.9.2**。
- 运行环境为 Minecraft **1.20.1**、Forge **47.4.16 或更高**。客户端与服务端均需安装 Forge 版 **SH-Better-UI 1.0.6**（含 Unified UI Kernel）和 **SH-Common-Lib 0.2.0**。手动更新时请配齐同版 KubeJS 内容和 GTL 前置。

</details>

## 山海商店与载入界面

<details>
<summary>商品图标、个人隐藏、主题与加载报告</summary>

- 调整商品卡片及图标布局（#39），更新商店主题、工具栏、提示和部分确认界面。
- 普通浏览时可右键商品选择「仅自己隐藏」，再从「我的隐藏」抽屉恢复。个人隐藏按玩家和存档保存。管理员可在编辑模式中查看并恢复全店隐藏的商品。
- 世界载入界面、JEI 加载进度文案和加载报告接入本地化，补齐简体中文与英文文本（#38）。

</details>

## AE2 虚拟供应与量子合成

<details>
<summary>虚拟供应样板、GRAPH 批量派发与兼容性</summary>

- 调整虚拟供应样板的编码、初始物品检查和派发，完善 GTLCore 与量子合成 CPU 的兼容。
- 调整 GRAPH 引擎下含虚拟供应的样板批量派发，覆盖多份合成请求（#40）。真实整合包仍需回归验证，树形引擎的计划不完整问题仍在排查。
- 超级磁盘阵列接入共享预览。

</details>

## 原初线程与配方重建

<details>
<summary>共享并行池分配、原始配方快照与同步</summary>

- 已扣料批次保留原有并行额度，空闲线程在余额不足完整份额时可使用剩余额度。共享并行池按来源占用参与配方优先级排序（#37 相关反馈）。
- 完善配方重建、原始配方快照和同步，调整编辑后配方表与查找结果的更新路径。

</details>

## 一键安装

<details>
<summary>Windows 安装脚本、冲突检查与备份</summary>

- 新增 Windows 脚本「一键安装山海私货.bat」，按包内说明选定已有 GTL 实例后，可检查模组版本冲突、复制安装文件并备份被覆盖的旧文件。

</details>

## 贡献者与赞助鸣谢

<details>
<summary>代码贡献、问题反馈与爱发电赞助</summary>

- **dishanhai** 负责核心代码。
- **aiwan-98** 提供原初线程与独立并行反馈（#37）。
- **lycc193bp** 反馈世界载入界面本地化（#38）和商店物品图标大小（#39）。
- **@nancheng197** 提供 AE2 合成下单问题的原始反馈（#40）。
- 感谢爱发电赞助者 **爱发电用户_CVqE**、**纪念**、**爱发电用户_1e1c5**、**落幕**、**有c**。
- **本期加入的赞助者**　**爱发电用户_AyGF**

</details>

<!-- lang:en -->
<details>
<summary>English release notes</summary>

# gt_shanhai 4.9.2

This update improves the Shanhai Shop, world loading screens, AE2 virtual supply and quantum crafting, and parallel allocation for Primordial threads. A Windows script is available to install the complete package.

## Multiplayer and Dependencies

<details>
<summary>Protocol 18, multiplayer upgrade, and shared libraries</summary>

- The network protocol changes from **17** in 4.9.1 to **18**. Update both the client and server to **4.9.2**.
- Use Minecraft **1.20.1** and Forge **47.4.16 or later**. Install **SH-Better-UI 1.0.6** for Forge with Unified UI Kernel and **SH-Common-Lib 0.2.0** for Forge on both sides. Manual updates also need the matching KubeJS content and GTL prerequisites.

</details>

## Shanhai Shop and Loading Screens

<details>
<summary>Item icons, personal hiding, themes, and loading reports</summary>

- Adjusted item cards and icon sizing (#39) and updated shop themes, toolbar, tooltips, and selected confirmation screens.
- Right-click a product to hide it for yourself and restore it from the personal hidden drawer. The list is saved per player and world. Administrators can view and restore globally hidden products in edit mode.
- Localized the world loading screen, JEI loading progress, and loading reports in Simplified Chinese and English (#38).

</details>

## AE2 Virtual Supply and Quantum Crafting

<details>
<summary>Virtual supply patterns, GRAPH batch dispatch, and compatibility</summary>

- Updated virtual supply pattern encoding, initial item checks, and dispatch, and improved compatibility between GTLCore and the quantum crafting CPU.
- Updated GRAPH dispatch for multiple crafts using virtual supply patterns (#40). The change still needs a live modpack check, and the incomplete plan in the tree engine remains under investigation.
- Connected the Super Disk Array to the shared preview.

</details>

## Primordial Threads and Recipe Rebuilds

<details>
<summary>Shared parallel pools, original recipe snapshots, and sync</summary>

- Batches that have consumed their inputs retain their reservations. Idle threads can use the remaining capacity when it is smaller than a full share. Shared pools now take existing reservations by source into account when prioritizing recipes (related to #37).
- Improved recipe rebuilds, original recipe snapshots, and synchronization of recipe tables and lookups after edits.

</details>

## Windows Installer

<details>
<summary>Installation script, conflict checks, and backups</summary>

- Added a Windows installer script. Follow the package instructions to select an existing GTL instance. The script checks for mod version conflicts, copies the package contents, and backs up overwritten files.

</details>

## Contributors and Sponsors

<details>
<summary>Core development, issue reports, and Afdian supporters</summary>

- **dishanhai** contributed the core code.
- **aiwan-98** reported Primordial threading and parallel pool behavior (#37).
- **lycc193bp** reported world loading screen localization (#38) and shop item icon sizing (#39).
- **@nancheng197** originally reported the AE2 crafting request issue (#40).
- Thanks to Afdian supporters **爱发电用户_CVqE**, **纪念**, **爱发电用户_1e1c5**, **落幕**, and **有c**.
- **New supporter this release** · **爱发电用户_AyGF**

</details>

</details>
