---
enabled: true
version: 4.9.1
title: gt_shanhai 4.9.1 更新公告
title_en: gt_shanhai 4.9.1 Release Notes
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai](https://github.com/dishanhai/gtl_shanhai-dishanhai)] - GitHub 仓库 / GitHub Repository
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/](https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/)] - 提交历史 / Commit History
link: [[gt_shanhai v4.9.1](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/tag/gt-shanhai-v4.9.1)] - 本版本发布说明与完整包 / Release notes and complete package
link: [[https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002](https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002)] - 【腾讯文档】gt_shanhai 模组问题与建议反馈表 / Feedback Sheet
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)] - 提交与查看模组问题反馈 / GitHub Issues
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13)] - 新功能意见征集 / Feature Requests
link: [[https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link](https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link)] - 爱发电赞助链接！感谢您的赞助！ / Sponsor on Afdian
---

# gt_shanhai 4.9.1

发布日期：`2026 年 11 月 8 日`
此更新贡献者：**dishanhai**——`核心代码贡献者`、**AutumnRainLF**——`issues #36` 反馈者、**aiwan-98**——`issues #37` 反馈者
爱发电赞助鸣谢：**爱发电用户_CVqE**、**纪念**、**爱发电用户_1e1c5**、**落幕**、**有c** `（排名不分先后以及大小）`

4.9.1 改进了原初模块的多线程调度，支持同配方与跨配方批次合并。你可以从 AE2 样板编码终端或 JEI 直接上传样板到星律云端样板库。本次也修复了无线样板管理终端同步大量样板时的断连，以及专用服务端构建玻璃界面时的崩溃。

## 联机兼容

- 网络协议从 4.9.0 的 **16** 升至 **17**。联机请将客户端与服务端一并更新至 **4.9.1**。

## 原初多线程批次合并与独立并行池

- **批次合并执行（`PrimordialThreadBatchMerge`）**：原初模块的普通模式与超限模式现支持「同配方合并」及「跨配方合并」。当候选批次的工期一致、进度为 0 且输出已完成确定性概率结算时，每组最多可将 **4 批**合并为一个执行批次共同推进与结算产出，同时保留原线程槽占用与各配方独立的并行预留额度。
- **跨配方线程倍率与超限独立并行池调整（`issues #37`）**：
  - 明确区分「基础并行」「跨配方线程倍率（GTLAdditions）」与「独立配方批次槽」。普通模式下整机总并行池为 `基础并行 × 跨配方线程倍率`；放入「世线残片」（`世线残片·初醒`、`世线残片·共鸣`、`世线残片·跃迁`、`世线残片·超越`、`世线残片·统合`、`世线残片·归一`、`世线残片·裁决`）时，独立批次槽容量按 `1 + 单件额外批次 × 堆叠数量` 计算。
  - 放入「寰宇并行超限器」后，「配方线程仓」、「终焉聚合枢纽」与原初模块的线程槽上限统一固定为 **96 个线程槽**（含基础批次，堆叠或跨来源不叠加）。支持实际并行追踪的原初模块在超限模式下激活「每个配方拥有独立的 Long.MAX_VALUE 并行池」，同一配方的多个批次共用其池，可开工的其他配方会优先取得空闲槽位。
- **分 Tick 配方类型搜索与并行探测优化**：
  - 开启独立并行池且无 ME 样板仓时，配方类型搜索改为按每 tick 最多 **8 种类型**分轮推进（`TypeSearchRound`），保留整轮候选供优先级比较，避免连续样板派单打断导致每 tick 从头重搜。
  - 优化单调并行探测（`findHighestMatchableParallel`）：稳态下优先探测上次可行并行与相邻上界，数量变化时复用已确定边界再倍增与二分定位，大幅降低高上限下的无效探测开销。
- **Jade 原初线程面板与指南更新**：
  - Jade「原初线程运行」面板新增「执行批次 X · 占用线程 Y」，标示 `同配方合并 ×N` 或 `跨配方合并 ×N`，合并批次自动汇总同类物品与流体产出（支持超大数值精确显示），超限模式下逐配方显示「此配方占用并行」与「独立并行池：每个配方上限 Long.MAX_VALUE」。
  - 同步更新 GuideME《配方线程仓》《终焉枢纽专项》《原初引擎与模块》《物质模块特殊方法》中的批次计算与超限规则说明。

## 星律云端样板库快捷上传与贴图

- **编码终端与 JEI 一键上传到星律样板库**：
  - 在 AE2 样板编码终端的快速上传按钮上方新增「上传到星律样板库」按钮（`CloudPatternUploadMenu`），支持将输出槽已编码的「加工样板」或「星律通配样板」直接入库，或在输出槽为空/仅有空白样板时当场编码并上传至当前 ME 网络中唯一在线且已授权的「星律云端样板库核心」。
  - JEI 配方界面新增末影箱图标的「上传到星律样板库」快捷按钮，支持从 JEI 直接编码配方并入库；对无可用在线库、多库冲突、缺少空白样板、重复原件或编码终端输出槽变动等情况提供即时状态提示。
- **云端样板库专属贴图**：为「星律云端样板库核心」补齐六面专属机械贴图与自发光覆层（`cloud_pattern_library`），不再复用「星律样板总成」外观。

## 网络分包与服务端兼容修复

- **样板管理终端大包分片传输（`ShanhaiPatternTerminalUpdateChunkPacket`）**：当「山海无线样板管理终端」的全量或增量更新超过 1 MiB 原生上限时，自动按约 1 MiB 分片下发（最高支持 32 MiB 负载，单画面共享 64 MiB NBT 额度），客户端重组校验后再交由 AE2 处理，并放宽增量同步节流，解决样板极多或含大体积 NBT 时打开终端掉线的问题。
- **专用服务端玻璃 UI 隔离（`StellarGlassTexture`，`issues #36`）**：将星界玻璃与烟色玻璃的客户端纹理与渲染 Lambda 延迟至客户端内部类初始化，在专用服务端（Dedicated Server）环境下安全回退为空纹理，修复服务端构建「星律样板总成」等机器 UI 时触发客户端渲染类加载导致的崩溃。
- **更新公告语言切换**。点击公告底部的语言按钮可在简体中文和英文之间切换。

<!-- lang:en -->
<details>
<summary>🌐 Switch to English / 切换至英文版 (gt_shanhai 4.9.1 Release Notes)</summary>

# gt_shanhai 4.9.1

Release Date: `November 8, 2026`
Contributors: **dishanhai** — `Core Code Contributor`, **AutumnRainLF** — `issues #36` reporter, **aiwan-98** — `issues #37` reporter
Afdian Sponsors Special Thanks: **爱发电用户_CVqE**, **纪念**, **爱发电用户_1e1c5**, **落幕**, **有c** `(Listed in no particular order or tier)`

Version 4.9.1 improves Primordial scheduling and adds same-recipe and cross-recipe batch merging. You can upload patterns directly to the Stellar Cloud Pattern Library from the AE2 Pattern Encoding Terminal or JEI. This release also fixes large-payload disconnects in the wireless pattern terminal and a crash while building glass UIs on dedicated servers.

## Multiplayer Compatibility

- The network protocol changes from **16** in 4.9.0 to **17**. For multiplayer, update both the client and server to **4.9.1**.

## Primordial Thread Batch Merging & Independent Parallel Pools

- **Batch Merging (`PrimordialThreadBatchMerge`)**: Primordial modules in both normal and overdrive modes now support `Same-recipe merge` and `Cross-recipe merge`. Paid, deterministic batches with identical duration and zero progress are merged by up to **4 batches** per execution lane to advance and output together, while preserving their original occupied thread slots and per-recipe parallel reservations.
- **Cross-Recipe Thread Multiplier & Overdrive Independent Pools (`issues #37`)**:
  - Clearly separates Base Parallel, `Cross-recipe thread multiplier` (GTLAdditions), and Independent Batch Slots. In normal mode, the machine-wide parallel pool is `Base Parallel × Cross-Recipe Thread Multiplier`, while inserting Worldline Thread Shards (`dishanhai:thread_shard_1` to `thread_shard_7`) expands independent batch capacity as `1 + extra batches per item × stack count`.
  - Inserting a `Universal Parallel Overdriver` fixes the thread capacity of the `Recipe Thread Hatch`, `Terminus Aggregation Hub`, and Primordial modules at **96 thread slots** (including the base slot; non-stacking across counts or sources). On Primordial modules that track actual parallel, overdrive mode activates `Each recipe has its own Long.MAX_VALUE parallel pool`, allowing other runnable recipes to claim remaining slots even when one recipe's pool is saturated.
- **Per-Tick Recipe Type Search & Parallel Probing Optimization**:
  - When independent recipe pools are active without ME pattern hatches, recipe type lookup advances across ticks at up to **8 recipe types per tick** (`TypeSearchRound`), preserving full-round candidates for priority selection instead of restarting from the first type on continuous notifications.
  - Optimized monotonic parallel probing (`findHighestMatchableParallel`) to test the previous feasible hint and its upper neighbor first in steady state, reusing confirmed bounds before exponential/binary search.
- **Jade Panel & GuideME Updates**:
  - The Jade `Primordial thread activity` panel now displays `Execution batches X · Occupied threads Y`, marks `Same-recipe merge ×N` or `Cross-recipe merge ×N`, aggregates identical item/fluid outputs (with exact formatting for values exceeding `Long.MAX_VALUE`), and shows `Parallel reserved for this recipe` and `Independent pools: Long.MAX_VALUE per recipe` in overdrive mode.
  - Updated GuideME pages (`Recipe Thread Hatch`, `Terminus Hub Special`, `Primordial Engine & Modules`, and `Matter Module Special Methods`) to reflect the new batch capacity and overdrive rules.

## Stellar Cloud Pattern Library Quick Upload & Textures

- **One-Click Upload from Encoding Terminal & JEI**:
  - Added an `Upload to Stellar Pattern Library` button above the quick-upload button in the AE2 Pattern Encoding Terminal (`CloudPatternUploadMenu`). It uploads an encoded processing pattern or `Stellar Wildcard Pattern` directly from the output slot, or encodes on the fly using a blank pattern and uploads to the single online, authorized `Stellar Cloud Pattern Library Core` on the active ME network.
  - Added an Ender Chest `Upload to Stellar Pattern Library` button in JEI recipe views to encode and upload recipes directly to the cloud library, with status feedback for missing/ambiguous libraries, missing blank patterns, duplicate originals, or changed terminal output slots.
- **Dedicated Cloud Library Textures**: Added dedicated 6-sided overlays and emissive textures (`cloud_pattern_library`) for the `Stellar Cloud Pattern Library Core`, replacing the shared `Stellar Law Pattern Buffer` overlay.

## Network Chunking & Dedicated Server Fixes

- **Chunked Pattern Terminal Updates (`ShanhaiPatternTerminalUpdateChunkPacket`)**: Full or incremental updates for the `Shanhai Wireless Pattern Management Terminal` exceeding the 1 MiB native limit are now split into ~1 MiB chunks (up to 32 MiB total payload, with a shared 64 MiB NBT accounter budget on reassembly) and reassembled on the client, preventing disconnections when opening terminals with massive pattern counts or complex NBT.
- **Dedicated Server Glass UI Isolation (`StellarGlassTexture`, `issues #36`)**: Deferred client-only glass textures and rendering lambdas into an inner client class and returned `IGuiTexture.EMPTY` on dedicated servers, fixing a server crash when opening/constructing `Stellar Law Pattern Buffer` and other machine UIs on a dedicated server.
- **Changelog language switch.** Use the language button at the bottom of the changelog to switch between Simplified Chinese and English.

</details>
