---
navigation:
  title: 物质模块特殊方法
  parent: common_index.md
  position: 10
categories:
  - gt_shanhai
---

# 物质模块特殊方法

这里说明原初终焉引擎模块基类 `PrimordialOmegaEngineModuleBase` 的共通行为。它负责主机连接、模块等级、线程倍率和额外挂载，不是普通机器页能替代的内容。

## 模块槽

`moduleSlot` 只接收物质模块系列。源码里一共映射了 17 个等级，从 `wzrm` 一直到 `create_mk`。

等级主要影响三件事：

* 模块数量换算后的等效等级
* 产物湮灭风险是否被压掉
* 显示里能看到的模块层级

## 线程倍率槽

`threadBoostSlot` 只接收已注册的线程倍率物品。槽位中的物品会按注册表里的倍率乘以堆叠数量。

`universal_parallel_overdriver` 会把并行显示直接推到超限模式，用来做极限合成，不是普通并行件。

## 额外挂载

`extraMountSlots` 有 3 个槽位，当前支持三类挂载物：

* `dark_energy_multiplier`，配方 EU 消耗减半
* `annihilation_core`，配方耗时压缩到 10%，并携带产物湮灭风险
* `bhd_hyper_seed`，在输出端可写时吞掉溢出产物

其中 `annihilation_core` 的风险会在模块等级足够高时被压掉，源码里对应阈值是 15 级。

## 主机与独立运行

模块默认要依附主机。只有当 `workWithoutHost` 开启时，模块才允许脱离主机独立运行配方。

主机连接成功后，模块会自动同步主机里的样板缓存和运行状态，所以模块页里看到的不是孤立逻辑，而是整套主机联动结果。

## 显示与持久化

界面会显示：

* 主机连接状态
* 物质模块名称和等级
* 线程倍率
* 额外挂载效果

这些槽位和状态都会写进持久化数据，重载后会保留。模块页看起来像是“一个模块”，实际是“模块槽、线程槽、挂载槽和主机同步”四件事一起工作。
