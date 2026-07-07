---
navigation:
  title: 永恒格雷工坊聚变模块
  parent: machine/eternal_gregtech_workshop.md
  position: 1
categories:
  - gt_shanhai
  - multiblock
item_ids:
  - gt_shanhai:eternal_gregtech_workshop_fusion_module
---

# 永恒格雷工坊聚变模块

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:eternal_gregtech_workshop_fusion_module" />\
> 永恒格雷工坊的聚变模块。当前源码里它先作为普通工坊模块运行，接入主机后只吃主机汇总加成，并把自己的状态上报给工坊主机。\
> 模块本身已经带有默认并行、EU 折扣、时长系数和热量数据。

</Column>

<Column gap="2" fullWidth={true}>

### 当前行为

* 模块类型为 `fusion`。
* 需要连接到永恒格雷工坊主机。
* 当前更像一个可扩展的聚变模块位，而不是独立主机。

</Column>

<Column gap="2" fullWidth={true}>

### 默认参数

> 默认等级 `1`。\
> 默认最大 EUt `42,949,672,940`。\
> 默认 EU 折扣 `0.95`，时长系数 `0.90`，默认并行 `1`，热量 `0`。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 作为工坊模块接入主机后，再看主机汇总后的综合收益。
* 当前版本不要把它理解成独立聚变机，它还是工坊模块。
* 后续如果要扩展聚变链，优先沿这个模块位继续接。

</Column>
