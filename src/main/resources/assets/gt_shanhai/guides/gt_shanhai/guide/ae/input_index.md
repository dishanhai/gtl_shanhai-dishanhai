---
navigation:
  title: 输入（含请求）
  parent: ae/index.md
  position: 20
categories:
  - gt_shanhai
  - ae2
item_ids:
  - gt_shanhai:big_tag_filter_stock_bus
  - gt_shanhai:me_requestable_input_bus
  - gt_shanhai:me_requestable_input_hatch
  - gt_shanhai:input_dual_hatch
---

# 输入（含请求）

* <ItemLink id="gt_shanhai:big_tag_filter_stock_bus" />
* <ItemLink id="gt_shanhai:me_requestable_input_bus" />
* <ItemLink id="gt_shanhai:me_requestable_input_hatch" />
* <ItemLink id="gt_shanhai:input_dual_hatch" />
* <ItemLink id="gt_shanhai:recipe_type_pattern_buffer" />

<Row gap="24">
<BlockImage id="gt_shanhai:big_tag_filter_stock_bus" scale="4" />
<BlockImage id="gt_shanhai:me_requestable_input_bus" scale="4" />
<BlockImage id="gt_shanhai:me_requestable_input_hatch" scale="4" />
<BlockImage id="gt_shanhai:input_dual_hatch" scale="4" />
<BlockImage id="gt_shanhai:recipe_type_pattern_buffer" scale="4" />
</Row>

## 说明

这一组负责把东西从 AE 网络送进机器。前者偏物品，后者偏流体，<ItemLink id="gt_shanhai:input_dual_hatch" /> 则是把两种输入合到一个方块里，适合物品和流体都要吃的配方。其中 <ItemLink id="gt_shanhai:recipe_type_pattern_buffer" /> 是**样板输入仓室**，专门装配方。

<ItemLink id="gt_shanhai:me_requestable_input_bus" /> 会按配置去网络里取物品，不够时还能顺着合成链发起请求。<ItemLink id="gt_shanhai:me_requestable_input_hatch" /> 做的是同样的事，但对象是流体。

<ItemLink id="gt_shanhai:input_dual_hatch" /> 则是一个组合型输入点，物品和流体一起管理，省空间，也更适合复杂多方块。它还能通过数据棒复制和粘贴配置，适合重复铺同类机器时直接复用。

<ItemLink id="gt_shanhai:big_tag_filter_stock_bus" /> 不只是普通输入总线，它是按标签批量筛选库存的工具。你想按“矿物”“材料族”“某一类通用副产物”去拉货时，它比逐个物品配置更顺手，尤其适合种类很多的流水线。

## 详细功能

<Column gap="2" fullWidth={true}>

### <ItemLink id="gt_shanhai:me_requestable_input_bus" />

> 负责物品输入。\
> 会先从 AE 网络里抽取目标物品，不够时再顺着合成链发起请求。\
> 适合只吃物品、不吃流体的机器。

</Column>

<Column gap="2" fullWidth={true}>

### <ItemLink id="gt_shanhai:me_requestable_input_hatch" />

> 负责流体输入。\
> 逻辑和输入总线一致，但对象换成了流体。\
> 适合只吃流体、不吃物品的机器。

</Column>

<Column gap="2" fullWidth={true}>

### <ItemLink id="gt_shanhai:input_dual_hatch" />

> 一个方块同时提供物品输入和流体输入。\
> 物品槽位与流体槽位分开管理，常用于同一台机器要同时吃两种资源的情况。\
> 还能用数据棒复制和粘贴配置，减少重复设置。

</Column>

<Column gap="2" fullWidth={true}>

### <ItemLink id="gt_shanhai:big_tag_filter_stock_bus" />

> 按标签批量筛选 AE 库存，而不是一项一项手填。\
> 可以按白名单 / 黑名单过滤，还能配合分页和数量排序查看结果。\
> 更适合材料族、矿物组、通用副产物这类种类很多的拉货场景。

</Column>

<Column gap="2" fullWidth={true}>

### <ItemLink id="gt_shanhai:recipe_type_pattern_buffer" />

> 专门用于装 AE 处理样板的输入仓室。\
> 为每个样板槽位识别并记录配方类型，按类型过滤样板。\
> 用于多配方类型机器需要区分样板的场景。

</Column>

## 使用顺序

1. 先确认你要的是物品输入、流体输入，还是两种一起喂。
2. 只吃物品就选输入总线，只吃流体就选输入仓。
3. 物品和流体都要，就直接上 <ItemLink id="gt_shanhai:input_dual_hatch" />。
4. 如果你是按标签批量拉货，优先看 <ItemLink id="gt_shanhai:big_tag_filter_stock_bus" />。

## 适合场景

* 只吃物品的终端机器。
* 只吃流体的反应器、冷凝器、管线类设备。
* 同时吃物品和流体的复杂多方块。
* 按矿物、材料族、副产物批量拉货的流水线。

## 需要注意

* 输入总线只管物品，输入仓只管流体，不要混用。
* <ItemLink id="gt_shanhai:input_dual_hatch" /> 是组合输入点，不是通用仓库。
* 标签过滤库存总线的重点是“按标签找货”，不是按单个物品手动搬运。
