---
navigation:
  title: 永恒格雷工坊
  parent: machine/multiblock_index.md
  position: 20
categories:
  - gt_shanhai
  - multiblock
item_ids:
  - gt_shanhai:eternal_gregtech_workshop
---

# 永恒格雷工坊

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:eternal_gregtech_workshop" />\
> 一座跨时代的格雷科技工坊主机。它不是单一加工机，而是一个会汇总模块状态、燃料、升级和渲染状态的工坊网络主机。\
> 当前实现里，主机已经能接收模块状态、统计连接数、计算综合并行和综合 EU/时长折扣，并按选定燃料维持运行。

</Column>

<Column gap="2" fullWidth={true}>

### 结构定位

* 这是永恒格雷工坊系列的主机。
* 它会汇总已连接模块的并行、EU 折扣、时长系数和热量。
* 主机还带有升级、燃料和渲染状态管理。

</Column>

<Column gap="2" fullWidth={true}>

### 模块体系

* [<ItemLink id="gt_shanhai:eternal_gregtech_workshop_fusion_module" />](eternal_gregtech_workshop_fusion_module.md)：聚变模块，当前作为普通工坊模块运行。
* [<ItemLink id="gt_shanhai:eternal_gregtech_workshop_eye_of_harmony_module" />](eternal_gregtech_workshop_eye_of_harmony_module.md)：创世之眼模块，当前作为普通工坊模块运行，只吃主机汇总加成。
* [<ItemLink id="gt_shanhai:eternal_gregtech_workshop_extra_module" />](eternal_gregtech_workshop_extra_module.md)：额外模块，作为工坊扩展位使用。

</Column>

<Column gap="2" fullWidth={true}>

### 运行指标

> 主机最多可按 `机器等级 × 4` 统计已连接模块。\
> 基础 EU 折扣按 `0.95^模块等级` 计算，基础时长系数按 `0.90^模块等级` 计算。\
> 机械侧会显示累计配方、累计 EU、燃料统计、引力子碎片和当前升级。

* 连接模块越多，主机汇总出的综合并行和综合加成越强。
* 燃料来自主机或模块周围的能力口，当前支持三类特殊燃料流体。
* 运行时会按周期刷新模块状态并清理过期状态。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 先把主机成型，再逐个连接模块。
* 想看主机实际收益，重点看连接模块数、模块等级和升级状态。
* 这套系统当前已经能跑主机汇总，模块细节后续还可以继续扩展。

</Column>
