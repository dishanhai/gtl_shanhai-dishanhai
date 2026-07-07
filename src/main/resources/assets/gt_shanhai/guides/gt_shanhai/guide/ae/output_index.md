---
navigation:
  title: 输出（回网）
  parent: ae/index.md
  position: 30
categories:
  - gt_shanhai
  - ae2
item_ids:
  - gt_shanhai:reliable_me_async_output_buffer
  - gt_shanhai:me_starrail_output_matrix
---

# 输出（回网）

* <ItemLink id="gt_shanhai:reliable_me_async_output_buffer" />
* <ItemLink id="gt_shanhai:me_starrail_output_matrix" />

<Row gap="20">
<BlockImage id="gt_shanhai:reliable_me_async_output_buffer" scale="4" />
<BlockImage id="gt_shanhai:me_starrail_output_matrix" scale="4" />
</Row>

## 说明

这一组是 AE 的回网输出模块，专注于将产物可靠地返送至 AE 网络。两者都是纯回网方案，不涉及任务提交或其他系统交付。

<ItemLink id="gt_shanhai:reliable_me_async_output_buffer" /> 采用稳定的异步输出策略。它先将产物写入本地持久化缓冲，再批量回灌至 AE 网络，着重于可靠性而非峰值速率。适合需要防止缓冲期间物品丢失的生产线。

<ItemLink id="gt_shanhai:me_starrail_output_matrix" /> 提供更高的吞吐能力。同样采用缓冲后批量回写的方案，但使用星轨式并发刷写，增强了处理能力。支持在界面切换输出优先级：均衡、物品优先、流体优先、小堆优先。适合已成型的大型产线，需要精细控制回网顺序和速率的场景。

## 详细功能

<Column gap="2" fullWidth={true}>

### <ItemLink id="gt_shanhai:reliable_me_async_output_buffer" />

> 将产物缓冲至本地持久化存储，再批量回灌 AE 网络。\
> 设计优先考虑可靠性，确保高频生产环境中产物不丢失。\
> 适合产线末端需要稳定回网的场景，尤其是对丢件零容忍的自动化系统。

</Column>

<Column gap="2" fullWidth={true}>

### <ItemLink id="gt_shanhai:me_starrail_output_matrix" />

> 同样采用缓冲后批量回写策略，但使用星轨式并发机制增强处理能力。\
> 支持在界面切换输出优先级：均衡、物品优先、流体优先、小堆优先。\
> 配置在重载后保留，适合大型产线需要精细控制回网顺序和吞吐的场景。

</Column>

<Column gap="2" fullWidth={true}>

## 使用顺序

1. 确认需求是回网输出还是任务自动化交付。
2. 回网输出选择前两个：稳定性优先选相位缓冲，吞吐能力优先选星轨矩阵。
3. 若需要 FTBQ 任务交付，请参阅独立的任务自动化页面。
4. 若仅需普通搬运，请勿将此组件用作通用出货口。

</Column>

## 适合场景

* 高频产线末端的可靠回网。
* 需要精细控制输出优先级的大型自动化系统。
* 对产物丢失零容忍的关键生产环节。
