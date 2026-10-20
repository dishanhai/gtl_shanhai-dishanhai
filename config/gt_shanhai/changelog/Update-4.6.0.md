---
enabled: true
version: 4.6.0
title: gt_shanhai 4.6.0 更新公告
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai](https://github.com/dishanhai/gtl_shanhai-dishanhai)] - GitHub 仓库
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/](https://github.com/dishanhai/gtl_shanhai-dishanhai/commits/master/)] - 提交历史
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/releases](https://github.com/dishanhai/gtl_shanhai-dishanhai/releases)] - 模组和完整包文件发布页
link: [[https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002](https://docs.qq.com/sheet/DYWFtSEtvTWVodkJn?tab=000002)] - 【腾讯文档】gt_shanhai 模组问题与建议反馈表
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues)] - 杜撰与查看模组问题反馈——如果你有问题可以反馈
link: [[https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13](https://github.com/dishanhai/gtl_shanhai-dishanhai/issues/13)] - 新功能意见征集
link: [[https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link](https://afdian.com/a/dishanhai?utm_source=copylink&utm_medium=link)] - 爱发电赞助链接！感谢您的赞助！
---

# gt_shanhai 4.6.0

发布日期：`2026 年 10 月 20 日`
此更新贡献者：**dishanhai**——`核心代码贡献者`

接在 4.5.1 后面。商店界面有流体动效和局部高斯模糊。多方块可以按配置关掉。物品悬停会标出山海添加或修改。星律的虚拟目标不再套到别的样板总成上。世界碎片收集新增一条创造维度碎片配方。

## 商店界面

- 商店、货币中心、兑换、银会的单次动效时长统一为 150 毫秒。
- 点商品卡片会飞进右侧详情。弹簧默认响应 0.22 秒，阻尼 0.92，落位几乎不超调。卡片离详情越远，响应越慢，范围 0.16 到 0.32 秒。单帧卡顿超过 50 毫秒会截断，再拆成 4 毫秒子步。飞行最后约 7% 时，飞行层和源卡片的磨砂一起淡出，露出下面已经画好的详情。
- 商店内容区做局部高斯模糊，不是整屏。一帧最多两块矩形，共用一条后处理。飞行时糊内容区，并把正在飞的卡片挖空，卡片本身保持清晰；源卡片用第二块矩形。模糊半径是 5 个界面单位，再按界面缩放换算成像素。
- 文字层用密采样高斯。宽间距采样会把背后的商品名打成残影。分组切页只糊网格，落位后强度归零。底部消息横幅不进模糊矩形。采样限制在商店面板内缘，不会糊到外面的世界。
- 打开商店时预热着色器，避免第一次点商品才编译。着色器不可用时退回原来的磨砂和深遮罩。
- 浮层从 0.94 弹到 1。切页只横向平移 32 像素，不再叠一层缩放。消息横幅按槽位插值滑动。
- 按下任意商店按键，点击点会扩出一圈液态玻璃波，持续 240 毫秒。波由三层叠成：波峰径向折射把画面往外推、圆盘内透镜放大、波峰高光带方向。波走独立的后处理槽位，跟卡片飞行、浮层模糊同帧并存——点在商品格上照样看得见。着色器不可用时退回直绘亮环，反馈不会丢。
- 货币中心、兑换、银会的面板和数量框跟同一套 150 毫秒。

## 多方块注册开关

- 新增 `config/gt_shanhai/multiblock_registry.json`。第一次启动没有这个文件时，全部多方块照常启用，注册完成后再扫描并写出配置。
- `mods.<模组id>.enabled` 为 `false` 时，关掉该模组下全部多方块，单项开关不再生效。`mods.<模组id>.multiblocks.<机器id>` 为 `false` 时，只关掉这一台。机器 id 写完整形式，例如 `gt_shanhai:某机器`。
- 关掉的意思是从 GT 机器注册表里移除。JEI 多方块结构预览不再列出它。方块和物品仍由模组注册，已经放在存档里的结构不会因为方块消失而损坏。
- 配置里已经写过的开关会保留。新扫到的机器默认启用。一次都没扫到多方块时，不拿空列表覆盖现有文件。
- 配置写坏时，原文件备份为 `multiblock_registry.json.broken`，这一轮按全部启用处理并重新生成。
- 修改后需要重启游戏或服务端。

## 物品归属

- `gt_shanhai` 命名空间的物品和方块物品，悬停末行写`由 GT-shanhai 添加`。本模组 tooltip 注册过内容的外部物品，写`由 GT-shanhai 修改`。已经有同样一行时不重复追加。
- 文字默认走随机色，逐字流动。显式标记成「修改」的，优先于「添加」。
- KubeJS 可以调用 `DShanhaiAttributionAPI`。`markAdded`、`markModified` 接受单个 id 或数组，也接受 `命名空间:*`。`setStyle('random')` 切换样式，`random`、`rand`、`随机` 是同一个随机色。`setEnabled(false)` 关闭全部自动追加。`setAutoNamespaceAdded`、`setAutoTooltipModified` 分别关掉命名空间自动标记和外部 tooltip 自动标记。

## 星律样板总成

- 虚拟目标、催化剂在场、内部槽虚拟供料，只在星律样板总成上执行。普通样板总成不再套这套逻辑。
- 样板编码终端编码按钮左侧那两颗半尺寸副产物按钮去掉了。一颗是给输出槽样板切换副产物标识，一颗是单独上传一份带标识的样板。
- 标题「样板编码」旁边的开关还在：包裹模式、配方 ID、副产物下单、虚拟供应器。副产物下单只作用于本页编码按钮，快速上传仍走它自己的按钮。样板悬停上的副产物提示还在。JEI 里单独上传带副产物标识的样板还在。

## 配方

- 世界碎片收集新增创造维度碎片配方。不消耗 `dishanhai:world_fragments_creation`、电路 1、`kubejs:eternity_catalyst` ×64，消耗 `kubejs:gelid_cryotheum` 100 mB。耗时 80，每刻 134217728 EU，配方电压档 12（UXV）。配方 id 是 `gtceu:fragment_world_collection/world_creation_1`。默认开启。
- 概率按 10000 为 100%。`dishanhai:world_fragments_creation` ×1 为 50（0.5%）。`gtlcore:mining_crystal` ×1 为 4000（40%），电压每高一档再加 5。`gtlcore:treasures_crystal` ×1 为 4000（40%）。`gtlcore:miracle_crystal` ×1 为 1000（10%）。`gtceu:tiny_magmatter_dust` ×16 为 300（3%）。`gtceu:tiny_magnetohydrodynamicallyconstrainedstarmatter_dust` ×16 为 200（2%）。`gtceu:tiny_eternity_dust` ×16 为 300（3%）。`gtceu:tiny_cosmicneutronium_dust` ×16 为 500（5%）。`gtceu:tiny_cosmic_dust` ×16 为 300（3%）。时空 16000 mB 为 5000（50%）。原始恒星物质等离子体 16000 mB 为 5000（50%）。

## 运行环境

- 模组现在依赖 KotlinForForge，版本范围 `[4.12,5)`。jar 里用 Jar-in-Jar 带上 `kotlinforforge-4.12.0-all.jar`，放在 `META-INF/jarjar`中 所以你不需要自行下载 KotlinForForge。