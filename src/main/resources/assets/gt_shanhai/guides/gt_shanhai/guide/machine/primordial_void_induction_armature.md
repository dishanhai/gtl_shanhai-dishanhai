---
navigation:
  title: 原初真空零点能发生器
  parent: machine/primordial_index.md
  position: 1
categories:
  - gt_shanhai
  - primordial
item_ids:
  - gt_shanhai:primordial_void_induction_armature
---

# 原初真空零点能发生器

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:primordial_void_induction_armature" />\
> 一台挂在原初引擎模块位上的发电模块。它不靠常规燃料发电，而是读取编程电路编号，把真空量子涨落放大成零点能输出。\
> 这台机的重点不是“普通并行发电”，而是理论数值会随电路编号指数爆炸，所以读法一定要先看规律，再看实际建议。

</Column>

<Column gap="2" fullWidth={true}>

### 电路规律

* 编程电路 `#1` 到 `#20` 决定理论并行和理论发电规模。
* 理论并行遵循：`2^(128 * 2^(n - 1))`。
* 理论发电遵循：`理论并行 * 2^20 EU/t`。
* 界面里真正参与机器并行显示的，是压缩后的工作并行；大数公式主要用于理解它的理论发电上限。

</Column>

<Column gap="2" fullWidth={true}>

### 档位读法

> `#1` 电路起步就是 `2^128` 级并行，已经不是常规机器能拿来横向比较的数量级。\
> 每升 1 号电路，指数本身继续翻倍，所以不是“线性变强”，而是很快进入只有位数还看得懂、本体数值已经没有阅读意义的阶段。\
> 你真要比大小，更适合看“位数”而不是去看完整数字串。

</Column>

<Column gap="2" fullWidth={true}>

### 极限参考

> `#20` 电路的理论并行为 `2^67108864`，理论发电为 `2^67108884 EU/t`。\
> 按位数换算，发电数值约有 `20,201,788` 位。\
> 这个量级已经远超正常产线、无线电网调度和玩家阅读需求，更多只是一个“理论极限刻度”。

</Column>

<Column gap="2" fullWidth={true}>

### 实际建议

* 不建议盲目上太高电路。后面几阶虽然理论数字更大，但对实际游玩几乎没有额外意义。
* 如果没有编程电路，暗能量倍增器可以兜底当作等效 `#1` 电路，让机器至少能起发电流程。
* 这台机必须先满足模块能运行、代理齐全、并且能把电送进目标无线网络，理论值才有落地空间。

</Column>
