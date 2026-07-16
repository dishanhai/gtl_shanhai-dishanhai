---
navigation:
  title: 原初多维聚爆核心
  parent: machine/primordial_index.md
  position: 15
categories:
  - gt_shanhai
  - primordial
item_ids:
  - gt_shanhai:primordial_multidimensional_implosion_core
---

# 原初多维聚爆核心

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:primordial_multidimensional_implosion_core" />\
> 将电力聚爆、普通聚爆和强引力震爆同时接入原初引擎并行体系的专用核心。\
> 三种工艺保持独立配方类型，普通聚爆用于补全最大兼容性。

</Column>

<Column gap="2" fullWidth={true}>

### 配方类型

* `gtceu:electric_implosion_compressor`：电力聚爆压缩机，普通聚爆的升级版本。
* `gtceu:implosion_compressor`：聚爆压缩机，保留原始爆炸物参与的普通聚爆配方。
* `gtceu:gravitation_shockburst`：强引力震爆。
* 三者是不同的配方类型，选择、缓存和执行时不会互相替代。

</Column>

<Column gap="2" fullWidth={true}>

### 并行与运行

> 空槽默认并行是 `64`。\
> 放入山海物质模块后，基础并行按模块等级提升，并继续叠加同类模块数量倍率。\
> 线程倍率、无线供电和额外挂载效果沿用原初引擎模块体系。

* 必须安装到原初终焉引擎模块位，或在配置允许时独立运行。
* 可通过配方类型选择器分别启用电力聚爆、普通聚爆和强引力震爆。

</Column>
