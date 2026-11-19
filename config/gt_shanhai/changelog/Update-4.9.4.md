---
enabled: true
version: 4.9.4
title: GTSH 4.9.4 更新公告
title_en: GTSH 4.9.4 Release Notes
link: [[GitHub](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)] - 发布与下载 / Releases and downloads
link: [[GitHub Issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)] - 问题反馈 / Report an issue
link: [[Afdian](https://afdian.com/a/dishanhai)] - 爱发电赞助 / Sponsor on Afdian
---

# GTSH 4.9.4

**拟发布稿 · Minecraft 1.20.1 / Forge**  
本稿以 4.9.3 公开标签 `gt-shanhai-v4.9.3`（`4140977`）为起点，止于将模组版本设为 `4.9.4` 的提交 `1e7f9dc`。这份稿件不代表 4.9.4 已在 GitHub 发布。

> ⚠️ **联机升级**　4.9.4 源码候选使用山海主网络协议 **21**，请同时更新客户端和服务端。

## ✨ 玩家可见变化

### 🧰 主机样板搬运工具

<details open>
<summary><quote:transfer> 读取、预览和放置</summary>

- 按住 Shift 右键主机可读取样板，不会清空来源；右键目标主机先预览并确认，再写入目标。Shift 右键空气可查看工具内容。
- 样板可连同母槽的独立电路配置搬运。目标不支持独立电路或写入失败时，该样板和配置留在工具中。带独立配置的样板请直接放入受支持的母槽，不要当作普通样板单独取出。
- 修正各个配方类型编辑入口的独立电路保留逻辑，以及旧存档母槽容量变化时的恢复问题。合并记录：[#46](https://github.com/dishanhai/gtl_shanhai-dishanhai/pull/46)、[#48](https://github.com/dishanhai/gtl_shanhai-dishanhai/pull/48)。

</details>

### 💰 商店与货币

<details>
<summary><quote:trade> 低阶货币限售与快速售出</summary>

- 完善低阶货币周期限售。铂金币及以下按玩家、按币种分别计数；转成星火与快速售出共用额度，只对实际成功卖出的枚数计数。超额部分留在钱包或 AE 仓储；锇币及以上不受本规则限制。
- 默认铜币每周期 4,096 枚，每高一档翻倍，最高至铂金币 65,536 枚。默认周期为自首次成功出售起计的 180 现实秒，离线及关服期间继续计时。管理员可从商店编辑模式调整基础限额与周期，基础限额为 0 时暂停低阶币出售。

</details>

### 🧩 样板、枢纽与机器兼容

<details>
<summary><quote:repair> 虚拟供应、世界线信标与原生机器</summary>

- 改善虚拟供应和 GTL 合成图兼容。原生缓存未预热时也能寻找当前有效的样板；查询本身不抽取可消耗材料。
- 修复世界线信标条件下的配方输入概率丢失，避免枢纽重建配方时丢掉原输入概率。见 [#51](https://github.com/dishanhai/gtl_shanhai-dishanhai/pull/51)。
- 修复 EnhancedCore 原生机器增强将多能力仓误当成纯材料仓的问题，保留超级冶炼炉等原生机器的能源、维护条件；纯材料仓替换仍沿用原逻辑。见 [#49](https://github.com/dishanhai/gtl_shanhai-dishanhai/pull/49)。

</details>

## 📦 整合包与服务端维护

<details>
<summary><quote:settings> 运行环境、依赖与存档升级</summary>

- 客户端和服务器一起更新。该候选的山海主网络协议为 **21**，不与不同协议的客户端混用。
- 依赖：Minecraft **1.20.1**、Forge **47.4.16+**、SH Common Lib **0.4.0+**、SH Better UI **1.0.7+**、GTCEu **1.4.4+**、GTLCore **1.2.2.9+**、gtladditions **3.1+**、KotlinForForge **4.12 至 5.0（不含 5.0）**、KubeJS **2001.6.5-build.14+**、GuideME **20.1.14+**、ae2wtlib **15.3.3+**。
- 上述版本为源码声明的最低兼容范围。上一版公布的 SH Better UI 推荐包 1.0.11 不等于 4.9.4 的最低版本，也不代表 4.9.4 附件已定。公告所用的 SHBUI 内联图标资源已核对至 1.0.11。
- 升级前备份世界及配置。若改过货币和商店设置，在测试实例确认 `currencySaleLimit`、`currencySalePeriodSeconds` 的配置，以及普通换汇与快速出售的剩余额度。大型搬运建议先在复制存档检查目标母槽类型与独立电路。


> ⚠️ **已过时：GTL-Extend（`gtl_extend`）**。不是 4.9.4 的必装前置，新安装不建议添加。

</details>

## 🙏 致谢

<details open>
<summary><quote:acknowledgments> 赞助与社区支持</summary>

- 🎉 **本期新加入的赞助者**　**Chanrian0607**、**爱发电用户_8k3s**。
- 💖 **再次赞助**　感谢 **爱发电用户_AyGF** 再次支持。
- 感谢每一位提供反馈、报告问题和支持项目的玩家。[爱发电 · 支持 dishanhai](https://afdian.com/a/dishanhai)

</details>

## 📋 发行窗口外与验证边界

<details>
<summary>版本范围与发版前核对</summary>

- 商店下单库存变化的重试修复 [#56](https://github.com/dishanhai/gtl_shanhai-dishanhai/pull/56)、世界时钟与回放、存档编辑、样板溢出恢复、配方编辑器后续工作、归墟遥测/配方及全息显示调整均晚于此候选。后续主线把版本改为 `4.9.4-Alpha.3`；除非重新选定并验证正式构建，不列入这份 4.9.4 正式版稿。
- EnhancedCore 兼容测试覆盖增强版 3.8.9、4.1.7、4.6.5 的生产算法。该专项记录显示回归无新增失败，不等于全套通过；不把后续 #56 的打包、专项数据移用到稳定候选。
- 定稿前核对发行 JAR 的版本、构建提交和 SHA-256；本稿不声称已上传附件或发布。

</details>

[4.9.3 公开 Release](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases/tag/gt-shanhai-v4.9.3) · [源码对比](https://github.com/dishanhai/gtl_shanhai-dishanhai/compare/gt-shanhai-v4.9.3...1e7f9dc3b52b0b237ddd205cd1dc4878a5195e28)
