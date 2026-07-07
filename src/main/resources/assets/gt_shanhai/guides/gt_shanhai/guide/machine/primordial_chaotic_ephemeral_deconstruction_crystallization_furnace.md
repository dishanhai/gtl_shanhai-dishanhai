---
navigation:
  title: 原初混沌蜉蝣解构结晶炉
  parent: machine/primordial_index.md
  position: 3
categories:
  - gt_shanhai
  - primordial
item_ids:
  - gt_shanhai:primordial_chaotic_ephemeral_deconstruction_crystallization_furnace
---

# 原初混沌蜉蝣解构结晶炉

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:primordial_chaotic_ephemeral_deconstruction_crystallization_furnace" />\
> 一台挂在原初引擎模块位上的高并行矿物处理模块。它把选矿、研磨、采矿、洗矿和熔炼这类偏矿物流程收进同一套框架里，适合做原初体系里的前段处理核心。\
> 这台机的重点不是花哨模式，而是模块槽会读取山海物质模块来决定并行上限，空槽也能先跑基础并行。

</Column>

<Column gap="2" fullWidth={true}>

### 处理范围

* 偏矿物处理和熔炼的流程都能往这里塞。
* 适合把选矿、研磨、采矿和洗矿统一到一台模块机里。
* 结构和运行方式都属于原初引擎模块体系，不是独立主机。

</Column>

<Column gap="2" fullWidth={true}>

### 并行槽

> 这台机有一个独立的并行物质槽，放入山海物质模块后就会改写并行上限。\
> 空槽默认并行是 `64`，不是 `0`，所以没塞倍率物也能先起步。\
> 并行槽每 3 tick 扫描一次，模块换进去后会很快刷新当前并行。

* 起步档：空槽 `64`，<ItemLink id="dishanhai:wzrm" /> `128`，<ItemLink id="dishanhai:wzjc" /> `256`，<ItemLink id="dishanhai:wzcz1" /> `512`。
* 中期档：<ItemLink id="dishanhai:wzsb" /> `2048`，<ItemLink id="dishanhai:wzcz2" /> `16384`，<ItemLink id="dishanhai:wzqs" /> `65536`。
* 高阶档：<ItemLink id="dishanhai:wzgl" /> `524288`，<ItemLink id="dishanhai:wzsw" /> `2097152`，<ItemLink id="dishanhai:wzdf" /> `536870912`。

</Column>

<Column gap="2" fullWidth={true}>

### 结构要点

* 外观结构走原初引擎模块位逻辑，不是单独的普通多方块。
* 机器本体会显示已安装模块、并行上限和线程倍率。
* 如果模块槽里没有合适物品，就按默认并行跑。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 这台机适合放在原初引擎里做前段矿物处理。
* 想要更高并行，就换更高阶的山海物质模块。
* 如果只是早期过渡，空槽默认 `64` 已经够先开工。

</Column>
