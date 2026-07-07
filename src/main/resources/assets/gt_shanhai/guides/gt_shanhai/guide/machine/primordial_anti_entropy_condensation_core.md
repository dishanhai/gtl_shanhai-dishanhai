---
navigation:
  title: 原初反熵冷凝核心
  parent: machine/primordial_index.md
  position: 5
categories:
  - gt_shanhai
  - primordial
item_ids:
  - gt_shanhai:primordial_anti_entropy_condensation_core
---

# 原初反熵冷凝核心

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:primordial_anti_entropy_condensation_core" />\
> 一台挂在原初引擎模块位上的反熵处理模块。它把反熵冷凝、真空冷冻和等离子冷凝收进同一套框架里，适合做低温与冷凝方向的原初节点。\
> 这台机和其他原初核心一样，靠并行物质槽决定上限，空槽也能先跑基础并行。

</Column>

<Column gap="2" fullWidth={true}>

### 处理范围

* 反熵冷凝、真空冷冻、等离子冷凝都属于它的工作面。
* 它不是独立主机，而是原初引擎模块位上的功能核心。
* 机器本体会显示当前模块、并行上限和线程倍率。

</Column>

<Column gap="2" fullWidth={true}>

### 并行槽

> 这台机有一个独立的并行物质槽。\
> 空槽默认并行是 `64`，塞入高阶山海物质模块后会快速抬升并行上限。\
> 并行槽每 3 tick 扫描一次，换模块后的刷新很快。

* 起步档：空槽 `64`，<ItemLink id="dishanhai:wzrm" /> `128`，<ItemLink id="dishanhai:wzjc" /> `256`，<ItemLink id="dishanhai:wzcz1" /> `512`。
* 中期档：<ItemLink id="dishanhai:wzsb" /> `2048`，<ItemLink id="dishanhai:wzcz2" /> `16384`，<ItemLink id="dishanhai:wzqs" /> `65536`。
* 高阶档：<ItemLink id="dishanhai:wzgl" /> `524288`，<ItemLink id="dishanhai:wzsw" /> `2097152`，<ItemLink id="dishanhai:wzdf" /> `536870912`。

</Column>

<Column gap="2" fullWidth={true}>

### 结构要点

* 这台机的关键不是额外模式，而是把冷凝类工序统一进原初模块体系。
* 并行值由模块槽里的物品直接决定。
* 没有合适模块时，默认并行可以先顶着跑。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 如果你要做低温、反熵、冷凝链条，这台机比拆成多台更省原初模块位。
* 起步先用空槽，后面再补高阶山海物质模块。
* 它更适合作为冷凝方向的中后段核心。

</Column>
