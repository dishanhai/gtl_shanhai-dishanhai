---
navigation:
  title: 虚拟物品系统
  parent: ae/index.md
  position: 50
categories:
  - gt_shanhai
  - ae2
item_ids:
  - gt_shanhai:virtual_item_provider
  - gt_shanhai:virtual_item_supply_machine
---

# 虚拟物品系统

* <ItemLink id="gt_shanhai:virtual_item_provider" />
* <ItemLink id="gt_shanhai:virtual_item_supply_machine" />

<Row gap="20">
<ItemImage id="gt_shanhai:virtual_item_provider" scale="4" />
<ItemImage id="gt_shanhai:virtual_item_supply_machine" scale="4" />
</Row>

## 说明

虚拟物品系统处理的是“看起来像在消耗物品，实际上不靠实物库存扣减”的场景。

它主要由两部分组成：<ItemLink id="gt_shanhai:virtual_item_provider" /> 和 <ItemLink id="gt_shanhai:virtual_item_supply_machine" />。

<ItemLink id="gt_shanhai:virtual_item_provider" /> 是一枚可绑定目标的物品。你拿着它时，另一只手放入目标物品就能把目标绑定进去；潜行右键可以清除绑定。绑定之后，这枚提供器不会变成那个目标本体，但在配方、样板和请求流程里会代表那个目标。它适合电路、模具、催化剂、固定模板件这类“需要参与流程，但不想真的消耗掉”的东西。

<ItemLink id="gt_shanhai:virtual_item_supply_machine" /> 是这套系统的接入端。把它接入 AE 网络后，槽位里的真实物品会被当作虚拟物品的来源来校验下单资格。通俗点说，就是让网络接受“这个物品可以被当成那个物品来下单”，但不会把虚拟物品当成普通库存暴露出去，也不会把槽内物品拿去直接当成普通仓储显示。

如果你做的是“占位但不消耗”的自动化，先用 <ItemLink id="gt_shanhai:virtual_item_provider" /> 把目标绑好，再由 <ItemLink id="gt_shanhai:virtual_item_supply_machine" /> 把真实物品挂进网络；如果你只是想做普通仓储，这一组不是给你物项转运用的。

## AE 样板修改

<Column gap="2" fullWidth={true}>

### 样板识别

> AE 样板在编码时会识别 <ItemLink id="gt_shanhai:virtual_item_provider" />。\
> 一旦样板里出现已绑定的提供器，它就不再只是“某个物品本体”，而会被当成那个目标的逻辑占位。\
> 这就是为什么同一个样板看起来像在消耗某个东西，实际却可以让流程保留目标名义。

</Column>

<Column gap="2" fullWidth={true}>

### 编码与执行

> 编码阶段会把可虚拟化的非消耗输入转换成虚拟提供形式。\
> 执行阶段会先检查样板里是否存在虚拟提供器，再把这些虚拟目标交给网络做下单校验。\
> 真正需要消耗的输入仍然按普通输入走，不会被混成虚拟逻辑。

</Column>

<Column gap="2" fullWidth={true}>

### 自动包裹规则

> 非消耗输入会默认尝试转成 <ItemLink id="gt_shanhai:virtual_item_provider" />。\
> 但像编程电路这类被排除的物品，会保留原样，不会强行包成提供器。\
> 这样样板既能保留“占位不消耗”的能力，也能避免把本来就该原样出现的物品改坏。

</Column>

## 工作流

1. 先拿到 <ItemLink id="gt_shanhai:virtual_item_provider" />。
2. 另一只手放入要代表的目标物品，右键完成绑定。
3. 把绑定好的提供器放进需要识别它的配方、样板或输入环节。
4. 再把对应的真实物品放进 <ItemLink id="gt_shanhai:virtual_item_supply_machine" />。
5. 供应机接入 AE 网络后，网络就能把这批真实物品当成虚拟提供条件来校验。

## 适合场景

* 电路、模具、催化剂这类不该被消耗的输入。
* 固定模板件、占位件、样板件这类需要“有它才能下单”的物品。
* 你想让配方逻辑保留一个目标名义，但实际不走真实消耗的自动化。

## 需要注意

* <ItemLink id="gt_shanhai:virtual_item_provider" /> 不是目标物本体，它只是目标的绑定载体。
* <ItemLink id="gt_shanhai:virtual_item_supply_machine" /> 不是普通仓库，它只负责校验和下单来源。
* 这套系统的目的不是替代仓储，而是让某些输入在逻辑上“存在”，在库存上“不扣掉”。
