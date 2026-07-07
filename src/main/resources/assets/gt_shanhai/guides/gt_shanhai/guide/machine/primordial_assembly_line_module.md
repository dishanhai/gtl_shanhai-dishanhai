---
navigation:
  title: 原初装配线模块
  parent: machine/primordial_index.md
  position: 6
categories:
  - gt_shanhai
  - primordial
item_ids:
  - gt_shanhai:primordial_assembly_line_module
---

# 原初装配线模块

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:primordial_assembly_line_module" />\
> 一台挂在原初引擎模块位上的专用产线模块。它把装配线纳入原初引擎的并行体系，适合大批量组装和长链制造。\
> 这台机的重点是把装配线变成原初体系里的可叠倍率节点，而不是单纯放一个普通装配机。

</Column>

<Column gap="2" fullWidth={true}>

### 处理范围

* 这是一台面向装配线的专用模块。
* 适合大批量组装、批处理制造和长链产线。
* 它不是独立主机，必须接在原初引擎上。

</Column>

<Column gap="2" fullWidth={true}>

### 并行槽

> 这台机也有自己的并行物质槽。\
> 空槽默认并行是 `64`，放入高阶山海物质模块后会上到很夸张的档位。\
> 并行槽每 3 tick 扫描一次，换模块后刷新很快。

* 起步档：空槽 `64`，<ItemLink id="dishanhai:wzrm" /> `128`，<ItemLink id="dishanhai:wzjc" /> `256`，<ItemLink id="dishanhai:wzcz1" /> `512`。
* 中期档：<ItemLink id="dishanhai:wzsb" /> `2048`，<ItemLink id="dishanhai:wzcz2" /> `16384`，<ItemLink id="dishanhai:wzqs" /> `65536`。
* 高阶档：<ItemLink id="dishanhai:wzgl" /> `524288`，<ItemLink id="dishanhai:wzsw" /> `2097152`，<ItemLink id="dishanhai:wzdf" /> `536870912`。
* 顶格档：<ItemLink id="dishanhai:wzcz3" /> `4611686018427387903`，<ItemLink id="dishanhai:reality_anchor_module" /> `6917529027641081855`，<ItemLink id="dishanhai:create_mk" /> 直接到无限。

</Column>

<Column gap="2" fullWidth={true}>

### 结构要点

* 这台机的定位是专用产线，不是通用处理核心。
* 它会显示已安装模块、并行上限和线程倍率。
* 没有合适物品时，先按默认并行运行。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 如果你要把装配线并进原初系统，这台机就是专门干这个的。
* 早期用空槽就能开工。
* 真要追极限，再往后换更高阶山海物质模块。

</Column>
