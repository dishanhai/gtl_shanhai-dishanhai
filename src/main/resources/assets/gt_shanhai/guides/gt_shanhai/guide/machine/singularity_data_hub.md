---
navigation:
  title: 奇点数据中枢
  parent: machine/multiblock_index.md
  position: 16
categories:
  - gt_shanhai
  - multiblock
item_ids:
  - gt_shanhai:singularity_data_hub
---

# 奇点数据中枢

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:singularity_data_hub" />\
> 一座奇点级 ME 网络存储中枢。它的核心不是加工，而是把超级磁盘阵列、ME 仓室和虚拟磁盘接到同一个网格里。\
> 当前实现支持 1 格磁盘阵列槽、16 个磁盘仓室接入，以及自动挂载虚拟磁盘存储。

</Column>

<Column gap="2" fullWidth={true}>

### 当前行为

* 主体是一个多方块控制器，同时实现了网格连接和存储提供。
* 磁盘阵列槽只有 1 格，放入 `SuperDiskArrayItem` 后会接入网格。
* 结构成型后会重新扫描磁盘仓室，结构失效时会清空缓存。
* 阵列内容变化后会请求网格更新，并把脏数据回写到磁盘阵列里。

</Column>

<Column gap="2" fullWidth={true}>

### 存储特征

> 支持旧式 ME 仓室，也支持新的超级磁盘阵列。\
> 阵列里还能挂虚拟单元，作为额外存储层。\
> 结构成型后靠近 ME 线缆就会自动接入网格。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 先把磁盘阵列放进槽位，再去接仓室和网格。
* 如果你在迁移旧存储系统，这台机器就是把旧仓室和新阵列统一收口的地方。
* 它更像高阶存储主机，不是普通的输入输出仓。

</Column>
