---
navigation:
  title: 并行
  parent: ae/index.md
  position: 10
categories:
  - gt_shanhai
  - ae2
item_ids:
  - gt_shanhai:divergence_engine
  - gt_shanhai:super_parallel_core
---

# 并行

* <ItemLink id="gt_shanhai:divergence_engine" />
* <ItemLink id="gt_shanhai:super_parallel_core" />

<Row gap="20">
<ItemImage id="gt_shanhai:divergence_engine" scale="4" />
<ItemImage id="gt_shanhai:super_parallel_core" scale="4" />
</Row>

## 说明

并行不是“配置多台机器”，而是让同一台机器在同一时间处理更多配方。

跨配方线程则是另一层并发。它影响的是机器能同时挂起多少条不同配方路径，不等于单个配方的并行数。简单说，并行管“一个配方能跑多少份”，线程管“能同时跑多少种配方”。

这两件东西都不负责物项转运，也不是仓室。它们只是在终局合成里，把“同一时刻能干多少活”往上抬。

## 并行定义

`并行` 指的是一台机器在同一配方上同时推进的份数。并行越高，同一个配方一次能消耗更多输入、产出更多产物，适合吞吐瓶颈而不是逻辑瓶颈。

在实现上，`并行` 由并行部件往控制器里“报数”决定，控制器把这些值汇总起来，最终作为这台机器的并行上限。它不是库存，也不是缓存，而是一个直接参与配方判定的能力值。

`跨配方线程` 指的是机器可以同时处理多少条不同配方路线。它更像“同时开几个工位”，不是把单个配方做得更快，而是让多个配方一起跑。

在实现上，它也是由部件向控制器提供数值，再由控制器累加成额外线程。线程和并行不是同一个值，玩家看到它们一起出现，是因为终局机器往往同时要解决“能同时跑几份”和“能同时跑几条线”这两个问题。

所以它们不是一回事：

* 并行高，单个配方吞吐更强。
* 线程高，不同配方切换和同时推进更灵活。

## 核心部件

### <ItemLink id="gt_shanhai:divergence_engine" />

<ItemLink id="gt_shanhai:divergence_engine" /> 是可调并行和线程的控制件。它不是仓室，而是一个插在多方块里的并行/线程调参件。

* 槽 0 放 `太初并行子` 时，机器会启用并行调节，每个物品提供 `+32` 并行。
* 槽 1 放 `太初世线之种` 时，机器会启用线程调节，每个物品提供 `+8` 线程。
* 没有对应物品时，对应能力就回到基础值。

它的作用不是替你提高产物倍率，而是把机器的并发能力拆成两个可控方向，让你能单独调“同一配方能跑多少份”和“同时能跑几条配方线”。

在控制器里，它会作为 `IParallelHatch` 和 `IThreadModifierPart` 参与最终汇总，所以它既能影响并行上限，也能影响跨配方线程。

### <ItemLink id="gt_shanhai:super_parallel_core" />

<ItemLink id="gt_shanhai:super_parallel_core" /> 是分子装配机用的超级并行核心，不是通用仓室，也不是普通并行模块。

它的职责更直接：只要分子装配机的控制器里装了它，相关逻辑就会把并行限制放开到 `Long.MAX_VALUE` / `Integer.MAX_VALUE` 级别，并把显示里的上限改成“无限”。换句话说，它不是慢慢加并行，而是直接解除分子装配机的并行上限门槛。

这意味着它的目标不是通用生产，而是专门给分子装配机做极限合成用的。装上它后，机器就不再受普通整数上限的束缚。

<Column gap="2" fullWidth={true}>

> 左边是 <ItemLink id="gt_shanhai:divergence_engine" />，右边是 <ItemLink id="gt_shanhai:super_parallel_core" />。\
> 前者管单配方并行和跨配方线程，后者管分子装配机的无限并行。\
> 你先分清楚“同一配方跑几份”和“同一时间跑几条线”，再决定该看哪一个。

</Column>

## 适合场景

* 你已经碰到并行瓶颈，需要先搞清楚是并行不够还是线程不够。
* 你在做终局合成，希望一个配方能吃更多并行。
* 你在做多配方并发，希望不同配方能同时推进。
* 你需要分子装配机的超级并行核心，而不是普通量子仓室。
