---
navigation:
  title: 原初铸币工厂
  parent: machine/primordial_index.md
  position: 13
categories:
  - gt_shanhai
  - primordial
item_ids:
  - gt_shanhai:primordial_coin_forge
---

# 原初铸币工厂

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:primordial_coin_forge" />\
> 一台挂在原初引擎模块位上的铸币专用工厂。它把原初物质直接铸成 GT 币，属于原初体系里很明确的终局产出节点。\
> 这台机同样靠并行物质槽决定上限，空槽可以先跑，后面再用山海物质模块抬倍率。

</Column>

<Column gap="2" fullWidth={true}>

### 处理范围

* 专门负责把原初物质铸成 GT 币。
* 它是原初引擎模块，不是独立主机。
* 结构和运行逻辑都围绕铸币流程展开。

</Column>

<Column gap="2" fullWidth={true}>

### 并行槽

> 这台机有一个独立的并行物质槽。\
> 空槽默认并行是 `64`，可以先把铸币链跑起来。\
> 并行槽每 3 tick 扫描一次，换模块后很快刷新。

* 起步档：空槽 `64`，<ItemLink id="dishanhai:wzrm" /> `128`，<ItemLink id="dishanhai:wzjc" /> `256`，<ItemLink id="dishanhai:wzcz1" /> `512`。
* 中期档：<ItemLink id="dishanhai:wzsb" /> `2048`，<ItemLink id="dishanhai:wzcz2" /> `16384`，<ItemLink id="dishanhai:wzqs" /> `65536`。
* 高阶档：<ItemLink id="dishanhai:wzgl" /> `524288`，<ItemLink id="dishanhai:wzsw" /> `2097152`，<ItemLink id="dishanhai:wzdf" /> `536870912`。

</Column>

<Column gap="2" fullWidth={true}>

### 结构要点

* 这台机的定位非常单一，就是铸币。
* 它会显示当前安装物品和铸币并行上限。
* 没有合适模块时，默认并行也能先跑。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 如果你要把原初物质直接转成 GT 币，这台机就是入口。
* 先空槽起步，后面再补高阶山海物质模块。
* 它更像终局货币节点，不是通用处理机。

</Column>
