---
navigation:
  title: 盒系统中央控制器
  parent: machine/multiblock_index.md
  position: 17
categories:
  - gt_shanhai
  - multiblock
item_ids:
  - gt_shanhai:box_system_central_controller
---

# 盒系统中央控制器

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:box_system_central_controller" />\
> 一台把多配方类型接入“产线封装者”体系的中央控制器。它不是普通加工机，而是一个会捕获当前配方、汇总路由摘要、按锁定状态重建运行时配方的封装中枢。\
> 当前版本已经能记录路由、锁定当前计划、按路由摘要合成运行时配方，并显示最终并行与跨配方线程。

</Column>

<Column gap="2" fullWidth={true}>

### 运行方式

> 机器成型后，会按已选择的配方类型寻找当前可运行配方。\
> 若玩家把当前配方捕获为路由，系统就会保存这条配方的 ID、类型、输入输出摘要与并行。\
> 锁定后，它会把所有路由合成为一个运行时配方；未锁定时只保留路由摘要，方便后续整理。

* 支持捕获当前配方为路由。
* 支持锁定 / 解锁当前计划。
* 支持清空路由，以及并行翻倍 / 减半。

</Column>

<Column gap="2" fullWidth={true}>

### 关键参数

> 机器类型：`产线封装者`\
> 并行上限：`160`\
> 跨配方线程：`16`\
> 结构未成型时，机器不会按封装逻辑运行。

</Column>

<Column gap="2" fullWidth={true}>

### 可接外壳

* 输入总线
* 输出总线
* 输入仓
* 输出仓
* 能源仓替换外壳

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 先让机器成型，再去捕获路由。
* 想长期稳定运行，就先锁定计划，再调整并行。
* 这台机更像 Box++ 行为移植的第一阶段，重点是把“路由摘要 + 统一运行入口”先跑通。

</Column>
