---
enabled: true
version: 4.9.4
title: gt_shanhai 4.9.4 更新公告
title_en: gt_shanhai 4.9.4 Release Notes
link: [[GitHub](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)] - 发布与下载 / Releases and downloads
link: [[GitHub Issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)] - 问题反馈 / Report an issue
link: [[Afdian](https://afdian.com/a/dishanhai)] - 爱发电赞助 / Sponsor on Afdian
link: [[腾讯文档](https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002)] - 问题反馈表格 / Problem Report Form
---

# gt_shanhai 4.9.4

- **@dishanhai** 负责核心代码。
- 🎉 **本期新加入的赞助者**　**Chanrian0607**、**爱发电用户_8k3s**。
- 💖 **再次赞助**　感谢 **爱发电用户_AyGF** 再次支持。
- 感谢每一位提供反馈、报告问题和支持项目的玩家。[爱发电 · 支持 dishanhai](https://afdian.com/a/dishanhai)

本次更新新增 Fumo 玩偶、世界时钟回溯和单人存档编辑，改进全息面板确认、样板搬运与恢复、商店下单和归墟配方。

## 📦 联机兼容与前置

<details>
<summary>🔗 联机升级、依赖与存档</summary>

- 山海主网络协议升至 **22**。联机时请将客户端和服务端一并更新至 **4.9.4**。
- 运行环境为 Minecraft **1.20.1**。
- 最低依赖为 Forge **47.4.16**、SH Common Lib **0.4.1**、SH Better UI **1.0.12**、GTCEu **1.4.4**、GTLCore **1.2.2.9**、GTLAdditions **3.1**、KubeJS **2001.6.5-build.14**、GuideME **20.1.14** 和 ae2wtlib **15.3.3**。KotlinForForge 声明范围为 **[4.12, 5.0)**。
- 升级前备份世界和配置。自定义过商店限额的整合包可核对 `currencySaleLimit`、`currencySalePeriodSeconds` 以及换汇与快速售出的剩余额度。

> ⚠️ **GTL-Extend（`gtl_extend`）已标记弃用。** 当前源码和本地 JAR 的 Forge 元数据仍标记为必装。 [#57](https://github.com/dishanhai/gtl_shanhai-dishanhai/pull/57)

</details>

## 🧸 Fumo 玩偶与全息交互

<details>
<summary>🎭 跳舞玩偶与全息面板</summary>

- 新增 **dishanhai Fomo 玩偶** **dgyjh02804 Fumo 玩偶** **BeiBeiPou Fomo 玩偶**，支持哦耶耶音效、动作和持物姿势。可佩戴在头部饰品栏。
- 全息面板会检查视线遮挡。执行命令时，首次点击显示待执行内容，3 秒内再次确认才会发送，返回或切换操作可取消。

</details>

## 🧰 样板、枢纽与兼容性

<details>
<summary>🔄 样板搬运、生成与溢出恢复</summary>

- **主机样板搬运工具**支持 Shift 右键主机读取并保留来源样板，右键目标主机预览后确认写入，Shift 右键空气查看工具内容。独立电路随样板搬运，写入失败时两者留在工具中。
- 修复旧存档母槽容量变化时的恢复问题，批量放置结束后统一重建通配样板。样板生成器修复 Forge 注册 ID 不连续时输入、输出物品与流体白名单漏项的问题。 [#46](https://github.com/dishanhai/gtl_shanhai-dishanhai/pull/46) · [#48](https://github.com/dishanhai/gtl_shanhai-dishanhai/pull/48) · [#59](https://github.com/dishanhai/gtl_shanhai-dishanhai/pull/59)
- 样板仓溢出产物随存档保存，AE 有空间后继续送出。正常拆除时，剩余产物通过恢复用磁盘阵列交付。
- GTL 合成图缓存尚未预热时也可查找有效样板，查询不消耗材料。世界线信标保留原配方输入概率。EnhancedCore 保留超级冶炼炉等原生机器多能力仓的能源和维护能力。 [#51](https://github.com/dishanhai/gtl_shanhai-dishanhai/pull/51) · [#49](https://github.com/dishanhai/gtl_shanhai-dishanhai/pull/49)

</details>

## 💰 商店与货币

<details>
<summary>🪙 低阶货币限售与 AE 下单</summary>

- 铂金币及以下按玩家、按币种限售。换成星火与快速售出共享额度，只计实际成交枚数，超额留在钱包或 AE 仓储。铜币默认每周期 4,096 枚，每高一档翻倍，铂金币为 65,536 枚。
- 周期默认从首次成交起计 180 现实秒，离线和关服继续计时。商店编辑模式可调整额度与周期，基础限额设为 0 时暂停低阶币出售。
- 商店用同一份 AE 库存快照计算一批下单计划。确认时若目标实际缺口已变化，会提示重新计算。已成功提交的任务会计入结果，旧计划和失败任务不会自动重试。 [#56](https://github.com/dishanhai/gtl_shanhai-dishanhai/pull/56)

</details>

## ⚙️ 归墟与配方编辑

<details>
<summary>🛠️ 主机遥测、配方与恢复</summary>

- 归墟视界枢核新增实际发电速率显示，并调整主机能源数据显示。新增归墟物质解压和范式简化配方，例如范式简化的感光聚合物流体配方单次产出 **16,000 mB**。
- 配方编辑器可在「已删除」筛选中查找并恢复配方，恢复结果可跨重启保留。整合包作者可为配方设置 `sh_disable_primordial_myriad_bonus`，使其免受原初万象增产。

</details>

## ⏳ 世界时钟与存档编辑

<details>
<summary>🕰️ 时间回溯与单人存档工具</summary>

- 有权限的玩家可用 `/shanhai time pause` 和 `/shanhai time resume` 暂停或恢复时钟。回溯前执行 `/shanhai time record start`，用 `/shanhai time anchor set` 设下锚点。之后用 `/shanhai time rewind` 回到锚点，或加秒数回退指定时长。
- 有权限的单人存档所有者可用 `/shanhai saveedit` 检查或编辑玩家背包、实体、进度和统计。

</details>

**发布日期：2026.11.20 · Minecraft 1.20.1 / Forge**

本稿覆盖 4.9.3 公开标签至主线提交 `c59f23e2` 的改动。当前源码版本为 **4.9.4**。

[4.9.3 公开 Release](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/tag/gt-shanhai-v4.9.3) · [源码对比](https://github.com/dishanhai/gtl_shanhai-dishanhai/compare/gt-shanhai-v4.9.3...c59f23e2f3826fdefa9354af8d6e1b46df7d24b7)

<!-- lang:en -->
<details>
<summary>🌐 English release notes</summary>

# gt_shanhai 4.9.4

This update adds Fumo dolls, world-clock rewind, and single-player save editing. It also improves hologram confirmation, pattern handling, shop orders, and Guixu recipes.

## 📦 Multiplayer and Dependencies

<details>
<summary>🔗 Multiplayer upgrades and save compatibility</summary>

- Update both client and server to **4.9.4** for network protocol **22**.
- Use Minecraft **1.20.1**, Forge **47.4.16+**, SH Common Lib **0.4.1+**, and SH Better UI **1.0.12+**. Forge metadata still requires the deprecated GTL-Extend.
- Back up your world and configuration before upgrading.

</details>

## 🧸 Fumo Dolls and Holograms

<details>
<summary>🎭 Dancing dolls and hologram panels</summary>

- Added the dishanhai, dgyjh02804, and BeiBeiPou dolls with sound, dances, held-item poses, and Curios head-slot support.
- Hologram panels check line of sight. Commands require a second click within three seconds.

</details>

## 🧰 Patterns, Hubs, and Compatibility

<details>
<summary>🔄 Pattern transfer, generation, and overflow recovery</summary>

- The host pattern transfer tool previews writes and retains patterns and independent circuits if a write fails.
- Fixed old-save slot recovery and generator allowlists. Pattern storage saves overflow and delivers it when AE has room or through a recovery disk array on dismantling.
- GTL can look up patterns before its crafting cache warms up. Worldline Beacons preserve input chances, and EnhancedCore preserves energy and maintenance capabilities.

</details>

## 💰 Shop and Currency

<details>
<summary>🪙 Low-tier sale limits and AE orders</summary>

- Spark conversion and quick sell share per-player, per-currency allowances. The default 180-second window continues while offline and while the server is stopped.
- Batch orders use one AE inventory snapshot. If the required quantity changes before confirmation, recalculate the plan.

</details>

## ⚙️ Guixu and Recipe Editing

<details>
<summary>🛠️ Machine telemetry, recipes, and restoration</summary>

- Guixu gains actual power-generation telemetry, decompression recipes, and paradigm simplification recipes.
- Restored recipes persist across restarts. Pack authors can use `sh_disable_primordial_myriad_bonus` to disable the Primordial Myriad output bonus per recipe.

</details>

## ⏳ World Clock and Save Editing

<details>
<summary>🕰️ Time rewind and single-player save tools</summary>

- Authorized players can pause or resume the world clock, record history, and rewind to an anchor or by a specified number of seconds.
- Authorized single-player world owners can inspect or edit inventories, entities, advancements, and statistics with `/shanhai saveedit`.

</details>

</details>
