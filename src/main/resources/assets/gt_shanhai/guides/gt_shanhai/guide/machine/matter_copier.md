---
navigation:
  title: 物质定增
  parent: machine/multiblock_index.md
  position: 19
categories:
  - gt_shanhai
  - multiblock
item_ids:
  - gt_shanhai:matter_copier
---

# 物质定增

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:matter_copier" />\
> 一台高阶物质复制多方块。它能把原型槽里的物品或流体按设定数量持续复制到输出口，适合做定量补货和定量增产。\
> 这台机不是普通仓储，而是按原型和数量执行复制的专用装置。

</Column>

<Column gap="2" fullWidth={true}>

### 复制方式

* 支持物品模式和流体模式。
* 原型槽里放物品时走物品复制；切到流体模式后会尽量从容器或输入流体里读原型。
* 每 20 tick 尝试复制一次。

</Column>

<Column gap="2" fullWidth={true}>

### 能量与输入

> 这台机用无线电网供能，不需要传统能源仓。\
> 每复制 1 个单位大约消耗 1024 EU。\
> 如果输出口已满，状态会直接提示输出已满。

* 原型槽只放 1 个原型。
* 复制数量可手动输入，界面里也有 `×10` 便捷按钮。
* 物品和流体都会走输出能力口，不是直接塞满机器内部库存。

</Column>

<Column gap="2" fullWidth={true}>

### 结构要点

* 3x3x3 UV 机器外壳。
* 可替换输入/输出总线与输入/输出仓。
* 本体强调的是“原型 + 数量 + 无线供能”，不是堆复杂模式。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 想补货就放一个原型，再设数量。
* 物品和流体分开看，不要混模式。
* 它更适合做定量复制，不适合拿来当通用处理核。

</Column>
