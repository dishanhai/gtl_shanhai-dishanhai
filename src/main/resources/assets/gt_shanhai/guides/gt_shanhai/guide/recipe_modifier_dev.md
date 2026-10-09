---
navigation:
  title: 配方修改器开发者工具
  parent: recipe_control_index.md
  position: 50
categories:
  - gt_shanhai
item_ids:
  - gt_shanhai:_recipe_modifier_dev
---

# 配方修改器开发者工具

<Column gap="15" fullWidth={true}>

<ItemImage id="gt_shanhai:_recipe_modifier_dev" scale="4" />

<Column gap="2" fullWidth={true}>

<ItemLink id="gt_shanhai:_recipe_modifier_dev" /> 是分层配方修改器的物品入口。右键打开的界面和命令 `/山海 配方 修改器` 相同：先找到一条 GT 配方，再改它的输入、输出、耗时和 EU/t，核对差异后提交。提交写成运行时覆盖，并重建该配方类型，JEI 跟着更新。

* 只给开发者用。权限等级不足 2 时，右键只会提示无法打开。
* 物品本身没有合成配方，用来代替反复输入命令。
* 界面分三步：机器 / 配方、图形化编辑、差异审核。未提交的草稿按玩家保存在当前存档里。

</Column>

<Column gap="2" fullWidth={true}>

## 机器 / 配方

把 GT 机器拖进左侧槽位，界面按这台机器映射到的配方类型缩小范围。也可以不放机器，直接搜索。

| 操作 | 作用 |
|---|---|
| 搜索模式：配方 / 原料 / 输出 | 按配方 ID、输入或产出来找 |
| 物品 / 流体 | 原料和输出搜索时区分物品与流体 |
| 查询槽 | 从 JEI 拖入物品或流体，当作搜索目标 |
| 排序 | 按 ID、EU/t、耗时、输入数、输出数排列 |

点开一条配方卡后进入图形化编辑，并读取服务端上的配方快照。

</Column>

<Column gap="2" fullWidth={true}>

## 图形化编辑

这一步改的是当前配方的草稿，还不会写进配方表。

* 配方 ID、耗时（tick）、EU/t 都可以改。耗时和 EU/t 必须是整数，耗时至少为 1。
* 输入和输出分成物品、流体四栏。格子可以改数量；输入设为 0% 表示不消耗，输出百分比表示概率产物。格子不够时用加页扩出空位。
* 撤销修改退回上一步。还原回到打开时的原配方。
* 导出 JS 数组把当前草稿复制到剪贴板，不写文件，也不改游戏内配方。

</Column>

<Column gap="2" fullWidth={true}>

## 差异审核

提交前在这里核对指纹、输入输出和条件。未改动的格子保持原编码。

* 移除原配方：提交后 JEI 和机器里只留下修改后的这一条。这是默认状态。
* 保留原配方：原配方继续存在。配方 ID 必须改成一条还不存在的新 ID，提交后两条同时留下。
* 可以在这里增删配方条件。
* 快速编写为样板：按当前审核稿编码处理样板并上传。需要身上有连上 ME 网络的无线终端，网络里还要有 1 张空白样板。
* 导出配方为 json：默认写到 `kubejs/data/Exported_Recipe/`，按配方类型、命名空间和路径分文件。`gt_shanhai-common.toml` 里 `developerMode = true` 时，直接写到模组源码 `data/gt_shanhai/recipes/`，路径跟配方 id 的路径一致。导出不代替提交。
* 提交并刷新 JEI：服务端重算指纹、写入覆盖并重建该配方类型。配方 ID 使用小写的 `命名空间:路径`。若中途配方已被别人改过，提交会因指纹不符而拒绝。

</Column>

</Column>
