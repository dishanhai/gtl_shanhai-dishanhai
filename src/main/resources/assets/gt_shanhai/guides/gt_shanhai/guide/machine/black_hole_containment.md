---
navigation:
  title: 亚稳态黑洞遏制场
  parent: machine/multiblock_index.md
  position: 15
categories:
  - gt_shanhai
  - multiblock
item_ids:
  - gt_shanhai:black_hole_containment
---

# 亚稳态黑洞遏制场

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:black_hole_containment" />\
> 一台会跟踪稳定度、状态和催化倍率的黑洞多方块。它不是单纯的压缩机，而是一台会开洞、维持、失稳、坍缩的状态机。\
> 当前实现里，它会吃黑洞种子开启运行，靠时空流体或催化爆冲维持稳定，并按状态拉高并行。

</Column>

<Column gap="2" fullWidth={true}>

### 当前行为

* 机器状态分四档：关闭、活跃、失稳、超稳态。
* 黑洞种子和超稳态黑洞种子都能启动机器，坍缩器则用于强制关停。
* 活跃时会每秒检查稳定度；稳定度被压到 0 以下会进入失稳。
* 稳定度掉到很低后会自动坍缩并关闭。

</Column>

<Column gap="2" fullWidth={true}>

### 运行特征

> 基础并行为 `8 × tier`。\
> 超稳态时并行会直接放大 4 倍。\
> 普通状态下，稳定度越低，并行也会继续上浮。\
> 开启“催化爆冲”后，并行还会继续乘上当前催化倍率，但需要持续消耗时空流体。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 想稳一点就别开催化爆冲，老老实实补时空流体。
* 想把产能顶上去，可以开爆冲，但要保证输入端一直有时空流体。
* 失稳状态会直接吞掉输出，所以别把它当普通压缩机用。

</Column>
