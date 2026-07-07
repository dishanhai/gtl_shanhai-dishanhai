---
navigation:
  title: 引力波天线发射器
  parent: machine/multiblock_index.md
  position: 15
categories:
  - gt_shanhai
  - multiblock
item_ids:
  - gt_shanhai:gravitational_wave_antenna_transmitter
---

# 引力波天线发射器

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:gravitational_wave_antenna_transmitter" />\
> 一台双模式多方块机器。它既能在生产模式下正常做配方加工，也能在广播模式下发射引力波，为范围内机器提供无损超频、引力透镜复制和怪物生成阻止。\
> 机器本体直接从无线电网取电，核心状态由模式、透镜数量和广播源注册情况共同决定。

</Column>

<Column gap="2" fullWidth={true}>

### 核心结构

<Row gap="20">

<Column gap="2" fullWidth={true}>

* <ItemLink id="gt_shanhai:gravitational_wave_antenna_transmitter" /> 本体是总控方块。
* 透镜槽最多 16 个，只接受 <ItemLink id="dishanhai:gravitational_lens" />。
* 透镜数会影响广播半径、3x 触发概率和广播源强度。

</Column>

<Column gap="2" fullWidth={true}>

* 机器有两种工作模式：生产模式和广播模式。
* 模式切换会重置配方逻辑并刷新广播订阅。
* 广播模式下还会按周期消耗广播燃料。

</Column>

</Row>

</Column>

<Column gap="2" fullWidth={true}>

### 生产模式

> 生产模式对应“引力波宏观干涉”配方类型。\
> 这时它就是一台正常的多方块加工机器，产出物品和流体。\
> 界面会显示生产模式状态，并把当前模式标成“生产模式”。

</Column>

<Column gap="2" fullWidth={true}>

### 广播模式

> 广播模式对应“引力波广域广播”配方类型。\
> 它会把自己注册成引力波广播源，向周围机器提供加成。\
> 界面会显示广播是否激活、广播半径和功率等级。

#### 广播效果

> 范围内机器会获得无损超频、引力透镜复制和怪物生成阻止。\
> 广播源只有在机器真正工作时才会维持。\
> 每 10 秒会消耗一次广播燃料。

</Column>

<Column gap="2" fullWidth={true}>

### 透镜与倍率

> 透镜数会直接影响广播半径。\
> 1 片透镜时，2x 复制就会稳定触发；3x 复制概率会随着透镜数上升，最多到 16 片时满额。\
> 透镜变化后，广播源会重新注册，显示和效果都会同步刷新。

</Column>

<Column gap="2" fullWidth={true}>

### 使用方式

> 先搭好结构，再放入透镜。\
> 想做普通加工就留在生产模式；想给周围机器加广播加成就切到广播模式。\
> 广播模式下如果没有燃料，机器会停在待机状态，不会维持广播源。

</Column>

<Column gap="2" fullWidth={true}>

### 说明

> 这台机不是单纯的“输出口”，而是把生产、广播、透镜倍率和怪物威慑揉在一起的终局节点。\
> 如果你只是要做普通配方加工，直接看生产模式就够；如果要做范围增益和广播场，就重点看广播模式和透镜部分。

</Column>
