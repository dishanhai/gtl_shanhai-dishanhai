---
navigation:
  title: 世线剥离震荡发生器
  parent: machine/worldline_index.md
  position: 2
categories:
  - gt_shanhai
  - worldline
item_ids:
  - gt_shanhai:world_line_stripping_oscillation_generator
---

# 世线剥离震荡发生器

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:world_line_stripping_oscillation_generator" />\
> 一台把宇宙模拟、星核剥离和相关扩展配方集中到一起处理的世线机器。它的核心卖点是超高并行和无线配方处理。\
> 当前实现会在界面里显示无限并行，并把运行模式文本直接提示给玩家。

</Column>

<Column gap="2" fullWidth={true}>

### 当前行为

* 机器类型为 `world_line_stripping_oscillation_generator`。
* 默认最大并行为无限上限。
* 支持锁定配方，也支持全量枚举多个配方类型。
* 会优先搜索宇宙模拟、星核剥离，以及装载扩展后的额外配方类型。

</Column>

<Column gap="2" fullWidth={true}>

### 运行特征

> 并行显示为“无限”。\
> 机器会显示世线震荡剥离中的状态。\
> 这个机器更偏向跨配方批量处理，而不是单一专用生产线。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 适合拿来吃多种世线类配方。
* 如果你需要看它到底吃哪些配方，优先看实际注册的配方类型列表。
* 它不是单功能机，理解成“世线配方汇聚器”更接近实际。

</Column>
