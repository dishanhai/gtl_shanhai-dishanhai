---
navigation:
  title: 代理执行者
  parent: machine/multiblock_index.md
  position: 18
categories:
  - gt_shanhai
  - multiblock
item_ids:
  - gt_shanhai:proxy_executor
---

# 代理执行者

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:proxy_executor" />\
> 一台高阶代理多方块。它可以把一台 GTCEu 机器接进自己的控制槽，代理执行那台机器的配方类型；同时还能用共鸣核心和世线残片把并行和跨配方线程拉高。\
> 这台机本体更像“代理中枢”，不是单纯的加工机。

</Column>

<Column gap="2" fullWidth={true}>

### 核心槽位

* 控制槽：放入要代理执行的 GTCEu 机器。
* 共鸣槽：放入代理共鸣核心，放大基础并行。
* 线程槽：放入世线残片系列，增加跨配方线程。

</Column>

<Column gap="2" fullWidth={true}>

### 工作方式

> 这台机的目标机器会决定可用配方类型，玩家可以在界面里切换模式。\
> 基础并行大致按“机器数量平方 × 8 × 共鸣核心倍率”计算；结构仓室里特定部件还能覆写并行。\
> 世线残片则负责跨配方线程，不是并行本身。

* 支持单方块机器和多方块控制器。
* 目标机器放进去后，会刷新可代理的配方类型列表。
* `下一模式` 按钮会切换当前代理模式。

</Column>

<Column gap="2" fullWidth={true}>

### 结构要点

* 3x3x3 青铜结构。
* 结构里允许输出总线、输入总线、能源输入和维护相关部件。
* 这台机的思路就是把“目标机 + 代理层 + 共鸣层”合到一起。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 适合拿来接一台你不想单独盯着跑的 GTCEu 机器。
* 如果要提高吞吐，先放目标机，再补共鸣核心和世线残片。
* 它更偏调度和代理，不是新一类通用加工核。

</Column>
