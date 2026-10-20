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

接在 4.5.1 后面。商店界面有流体动效和局部高斯模糊。多方块可以按配置关掉。物品悬停会标出山海添加或修改。星律的虚拟目标不再套到别的样板总成上。世界碎片收集新增一条创造维度碎片配方。配置文件挪进了子目录，旧文件第一次启动时自动迁移。商店加了液态玻璃材质。修了 JEI 搜索框里 Ctrl 快捷键的冲突，以及部分结构预览打不开的问题。

## 配置文件目录

- 商店配置移到 `config/gt_shanhai/shop/`，货币汇率和货币阶段在 `shop/currency/` 里。
- 配方覆写、剥离、替换、删除规则、当前生效预设和预设目录，移到 `config/gt_shanhai/recipe/`。
- 多方块注册开关移到 `config/gt_shanhai/machine/multiblock_registry.json`。
- AE 终端收藏和样板生成器预设移到 `config/gt_shanhai/client/`。
- `gt_shanhai-common.toml`、`changelog/`、`guides/` 位置不变。
- 第一次读取时自动迁移。旧位置有文件、新位置没有，就移过去。两边都有，保留新位置的文件，旧文件改名为 `*.legacy`，不覆盖你在新结构里改过的内容。
- 更新前建议备份 `config/gt_shanhai/`。

## 商店界面

- 商店、货币中心、兑换、银会的单次动效时长统一为 150 毫秒。
- 点商品卡片会飞进右侧详情。弹簧默认响应 0.22 秒，阻尼 0.92，落位几乎不超调。卡片离详情越远，响应越慢，范围 0.16 到 0.32 秒。单帧卡顿超过 50 毫秒会截断，再拆成 4 毫秒子步。飞行最后约 7% 时，飞行层和源卡片的磨砂一起淡出，露出下面已经画好的详情。
- 商店内容区做局部高斯模糊，不是整屏。一帧最多两块矩形，共用一条后处理。飞行时糊内容区，并把正在飞的卡片挖空，卡片本身保持清晰；源卡片用第二块矩形。模糊半径是 5 个界面单位，再按界面缩放换算成像素。
- 文字层用密采样高斯。宽间距采样会把背后的商品名打成残影。分组切页只糊网格，落位后强度归零。底部消息横幅不进模糊矩形。采样限制在商店面板内缘，不会糊到外面的世界。
- 打开商店时预热着色器，避免第一次点商品才编译。着色器不可用时退回原来的磨砂和深遮罩。
- 浮层从 0.94 弹到 1。切页只横向平移 32 像素，不再叠一层缩放。消息横幅按槽位插值滑动。
- 按下任意商店按键，点击点会扩出一圈液态玻璃波，持续 240 毫秒。波由三层叠成：波峰径向折射把画面往外推、圆盘内透镜放大、波峰高光带方向。波走独立的后处理槽位，跟卡片飞行、浮层模糊同帧并存——点在商品格上照样看得见。着色器不可用时退回直绘亮环，反馈不会丢。
- 货币中心、兑换、银会的面板和数量框跟同一套 150 毫秒。
- 加载中的商品格显示占位动画。切换页面时的加载卡顿已修，预加载机制改进，商店页面渲染也做了优化。
- 模糊区加了液态玻璃材质：色调、颗粒、内缘亮边和边缘折射，浮层外缘有柔和阴影。飞行、切页、加载、浮层各用一套参数。切页那套不带亮边，网格边缘不会每次切页都闪一圈。点击波不叠这套材质，只保留自己的折射和高光。

## 多方块注册开关

- 新增 `config/gt_shanhai/multiblock_registry.json`。第一次启动没有这个文件时，全部多方块照常启用，注册完成后再扫描并写出配置。
- `mods.<模组id>.enabled` 为 `false` 时，关掉该模组下全部多方块，单项开关不再生效。`mods.<模组id>.multiblocks.<机器id>` 为 `false` 时，只关掉这一台。机器 id 写完整形式，例如 `gt_shanhai:某机器`。
- 关掉的意思是从 GT 机器注册表里移除。JEI 多方块结构预览不再列出它。方块和物品仍由模组注册，已经放在存档里的结构不会因为方块消失而损坏。
- 配置里已经写过的开关会保留。新扫到的机器默认启用。一次都没扫到多方块时，不拿空列表覆盖现有文件。
- 配置写坏时，原文件备份为 `multiblock_registry.json.broken`，这一轮按全部启用处理并重新生成。
- 修改后需要重启游戏或服务端。

## JEI

- 搜索框有键盘焦点时，JEI 的 Ctrl 快捷键（C 复制名称、D 复制标签、X 复制 ID、L 发到聊天）不再抢输入。打字和 Ctrl 组合键照常。
- 鼠标悬停时 Ctrl 快捷键有时认不出 Ctrl，现在同时检查事件的修饰键和当前按键状态。
- 修复部分多方块在 JEI 里结构预览打不开的问题。这些结构的匹配缓存从未建立，JEI 翻页或关闭预览时会抛出 NPE，现在会先初始化缓存。

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

- 服务器启动流程整理：去掉重复的配方重建调用，持久化配方切换并入同一批规则处理，配方查找表的重新应用也简化了。
- Mekanism 熔炉配方剥离挪到配方重建完成之后执行。
- 玩家登录时补发配方覆写。专用服务器上，客户端以前只能看到剥离和替换的结果，看不到覆写。

## 运行环境

- 模组现在依赖 KotlinForForge，版本范围 `[4.12,5)`。jar 里用 Jar-in-Jar 带上 `kotlinforforge-4.12.0-all.jar`，放在 `META-INF/jarjar`中 所以你不需要自行下载 KotlinForForge。

## 内部调整

- 哈普恩卫星、STR 结构、世界线裂解枢纽三台多方块改为读取 `.mb` 资源文件，`HSData`、`STRData`、`BHCData` 等硬编码数据类已删除。
- `DShanhaiMBParser` 支持用 `ResourceLocation` 解析多方块，并提供带方向参数的文件版解析。
- JEI 复制快捷键的实现移到 Kotlin。