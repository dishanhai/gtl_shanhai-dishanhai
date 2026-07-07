---
navigation:
  title: 量子计算机
  parent: ae/index.md
  position: 70
categories:
  - gt_shanhai
  - ae2
item_ids:
  - gt_shanhai:quantum_computer
  - gt_shanhai:quantum_computer_unit
  - gt_shanhai:quantum_crafting_storage
  - gt_shanhai:quantum_structure
  - gt_shanhai:quantum_parallel_processor
---

# 量子计算机

* <ItemLink id="gt_shanhai:quantum_computer" />
* <ItemLink id="gt_shanhai:quantum_computer_unit" />
* <ItemLink id="gt_shanhai:quantum_crafting_storage" />
* <ItemLink id="gt_shanhai:quantum_structure" />
* <ItemLink id="gt_shanhai:quantum_parallel_processor" />

<Row gap="20">
<BlockImage id="gt_shanhai:quantum_computer" scale="4" />
<BlockImage id="gt_shanhai:quantum_computer_unit" scale="4" />
<BlockImage id="gt_shanhai:quantum_parallel_processor" scale="4" />
</Row>
<Row gap="20">
<BlockImage id="gt_shanhai:quantum_crafting_storage" scale="4" />
<BlockImage id="gt_shanhai:quantum_structure" scale="4" />
</Row>

## 说明

<ItemLink id="gt_shanhai:quantum_computer" /> 是独立系统，不算仓室。

<ItemLink id="gt_shanhai:quantum_computer" /> 的核心作用是提供量子合成池和量子 CPU 池，用来处理更高阶的合成并行与结构化计算。它自己有一套成型结构、核心、存储和处理单元，安装时要按整套系统去看，不是普通机器零件拼一拼就完事。

这一组里，<ItemLink id="gt_shanhai:quantum_computer" /> 是主机，<ItemLink id="gt_shanhai:quantum_computer_unit" />、<ItemLink id="gt_shanhai:quantum_crafting_storage" />、<ItemLink id="gt_shanhai:quantum_structure" /> 是支撑它成型和工作的部件。它们组合起来才是完整的量子计算体系。

如果你只是想找一个能接 AE 的仓室，这里不是；如果你要搭终局量子合成池，这里才是入口。

<Column gap="2" fullWidth={true}>

### 五个方块各干什么

> * **<ItemLink id="gt_shanhai:quantum_computer" />**：管理接口，放在量子 CPU 池外部，与量子结构块相邻。\
>   它负责读取池内状态、显示容量和线程、提供 GUI 访问。不参与 CPU 池内部结构，仅作监控和入口。
>
> * **<ItemLink id="gt_shanhai:quantum_computer_unit" />**：核心单元，放在池内部。\
>   提供 `256MB` 存储 + `256` 个处理线程。兼具存储和计算能力，是 CPU 池的基础构成单位。
>
> * **<ItemLink id="gt_shanhai:quantum_parallel_processor" />**：并行处理器单元，放在池内部。\
>   提供 `4096` 个处理线程。适合需要高并发行计算的场景。
>
> * **<ItemLink id="gt_shanhai:quantum_crafting_storage" />**：存储单元，放在池内部。\
>   提供 `Long.MAX_VALUE`（9.22E18）存储，显示为 `∞`。只负责存储，不提供线程。适合需要海量合成存储的场景。
>
> * **<ItemLink id="gt_shanhai:quantum_structure" />**：结构外壳，搭建 7x7x7 以内的外壳包裹层。\
>   自身不提供存储或线程，仅作结构边界。外壳本身可连接 AE 线缆传递频道（单个外壳 `32` 条频道）。

</Column>

<Column gap="2" fullWidth={true}>

### 存储与线程计算

> * **总存储** = 所有核心单元存储 + 所有存储单元存储\
>   空池默认有基础存储，塞一个存储单元后直接跳到 `∞`。
>
> * **总线程** = 所有核心单元线程 + 所有并行处理器线程\
>   举例：3 个核心单元 = `768` 线程，再加 2 个处理器 = 额外 `8192` 线程，总计 `8960` 线程。
>
> * **可用容量** = 总存储 - 当前合成占用\
>   合成任务占用的存储会从可用容量中扣除，任务结束后释放。

</Column>

<Column gap="2" fullWidth={true}>

### 并发机制

> * 单个小 CPU 最多吃 `4096` 线程（对应 1 个并行处理器单元）。
> * 同一时间多个合成任务可以拆分到不同小 CPU 并行执行。
> * 线程多了不是让单个任务跑得更快，而是让多个任务可以同时跑。
> * 堆叠多个并行处理器可以让 CPU 池的并发能力跨数量级增长。

</Column>

<Column gap="2" fullWidth={true}>

### 搭建流程

1. 用 <ItemLink id="gt_shanhai:quantum_structure" /> 搭建最多 `7x7x7` 的外壳。
2. 内部放置 <ItemLink id="gt_shanhai:quantum_computer_unit" />（核心）、<ItemLink id="gt_shanhai:quantum_crafting_storage" />（存储）、处理器单元（如果有）。
3. 把 <ItemLink id="gt_shanhai:quantum_computer" /> 放在量子结构块相邻的外部位置。
4. 通过 AE 线缆连接主网络，系统自动识别 CPU 池。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 如果你只是需要基础合成并发，放 2-3 个核心单元 + 1 个存储单元已经够起步。
* 真正需要超大并发时，再堆叠多个并行处理器单元往上推线程。
* 存储单元的 `∞` 容量适合极端复杂的合成树，普通场景下核心单元自带的 `256MB` 已经够用。
* 外壳本身可以接线缆传频道，所以不必额外占用 ME 接口位。

</Column>
