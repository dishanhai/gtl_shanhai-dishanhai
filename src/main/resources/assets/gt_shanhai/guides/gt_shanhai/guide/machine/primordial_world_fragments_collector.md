---
navigation:
  title: 原初世界碎片采集器
  parent: machine/primordial_index.md
  position: 9
categories:
  - gt_shanhai
  - primordial
item_ids:
  - gt_shanhai:primordial_world_fragments_collector
---

# 原初世界碎片采集器

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:primordial_world_fragments_collector" />\
> 一台挂在原初引擎模块位上的资源采集核心。它专门收集世界碎片，把碎片产出集中到一台机器里统一处理。\
> 这台机同样靠并行物质槽吃山海物质模块，适合做碎片类资源链的汇总节点。

</Column>

<Column gap="2" fullWidth={true}>

### 处理范围

* 专门收集世界碎片。
* 适合把碎片产出统一到一台模块机里。
* 它是原初引擎模块，不是独立主机。

</Column>

<Column gap="2" fullWidth={true}>

### 并行槽

> 这台机有一个独立的并行物质槽。\
> 空槽默认并行是 `64`，可以先跑基础采集。\
> 并行槽每 3 tick 扫描一次，换模块后刷新很快。

* 起步档：空槽 `64`，<ItemLink id="dishanhai:wzrm" /> `128`，<ItemLink id="dishanhai:wzjc" /> `256`，<ItemLink id="dishanhai:wzcz1" /> `512`。
* 中期档：<ItemLink id="dishanhai:wzsb" /> `2048`，<ItemLink id="dishanhai:wzcz2" /> `16384`，<ItemLink id="dishanhai:wzqs" /> `65536`。
* 高阶档：<ItemLink id="dishanhai:wzgl" /> `524288`，<ItemLink id="dishanhai:wzsw" /> `2097152`，<ItemLink id="dishanhai:wzdf" /> `536870912`。

</Column>

<Column gap="2" fullWidth={true}>

### 结构要点

* 它的重点是碎片采集，不是通用处理。
* 并行上限直接看模块槽里的物品。
* 没有合适模块时，默认并行也能先干活。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 如果你在做世界碎片链，这台机很适合单独放一台。
* 早期先空槽起步，后面再加高阶山海物质模块。
* 更像资源汇总终点，而不是前端加工机。

</Column>
