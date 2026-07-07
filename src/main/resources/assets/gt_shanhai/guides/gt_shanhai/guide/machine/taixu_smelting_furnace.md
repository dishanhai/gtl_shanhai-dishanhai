---
navigation:
  title: 原初太虚宇宙锻炉
  parent: machine/primordial_index.md
  position: 14
categories:
  - gt_shanhai
  - primordial
item_ids:
  - gt_shanhai:taixu_smelting_furnace
---

# 原初太虚宇宙锻炉

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:taixu_smelting_furnace" />\
> 一台挂在原初引擎模块位上的太虚熔炼专用模块。它支持电炉、合金冶炼、电力高炉、合金冶炼炉和太虚熔炼，属于把基础热处理和太虚炉路统一到一台机器里的终局节点。\
> 这台机必须装在运行中的原初引擎上，模块槽也会决定并行上限。

</Column>

<Column gap="2" fullWidth={true}>

### 处理范围

* 支持电炉、合金冶炼、电力高炉、合金冶炼炉和太虚熔炼。
* 它不是独立主机，必须挂在运行中的原初引擎上。
* 这台机更像太虚方向的通用热处理核心。

</Column>

<Column gap="2" fullWidth={true}>

### 并行槽

> 这台机有一个独立的并行物质槽。\
> 空槽默认并行是 `64`，先把炉子跑起来没问题。\
> 并行槽每 3 tick 扫描一次，换模块后刷新很快。

* 起步档：空槽 `64`，<ItemLink id="dishanhai:wzrm" /> `128`，<ItemLink id="dishanhai:wzjc" /> `256`，<ItemLink id="dishanhai:wzcz1" /> `512`。
* 中期档：<ItemLink id="dishanhai:wzsb" /> `2048`，<ItemLink id="dishanhai:wzcz2" /> `16384`，<ItemLink id="dishanhai:wzqs" /> `65536`。
* 高阶档：<ItemLink id="dishanhai:wzgl" /> `524288`，<ItemLink id="dishanhai:wzsw" /> `2097152`，<ItemLink id="dishanhai:wzdf" /> `536870912`。

</Column>

<Column gap="2" fullWidth={true}>

### 结构要点

* 这台机的关键是“必须接入原初引擎后运行”。
* 它会显示当前模块和并行上限。
* 没有合适模块时，默认并行也能先顶着跑。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 如果你要做太虚方向的热处理，这台机就是专用入口。
* 早期先空槽开工，后面再补高阶山海物质模块。
* 它适合基础熔炼和高阶炉类流程，不适合拿来当通用处理核。

</Column>
