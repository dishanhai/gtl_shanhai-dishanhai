---
navigation:
  title: 配置文件解析
  parent: index.md
  position: 5
categories:
  - gt_shanhai
---

# 配置文件解析

这里收纳 `DShanhaiConfig` 里已经存在的配置项。每个小节都对应源码中的一个 `builder.push(...)` 分组，前缀、键名和默认值都按源码写。

<Column gap="2" fullWidth={true}>

### tag_filter_bus

> `slotsPerPage` - 默认 `16`\
> UI 每页显示的槽位数，范围 `1` 到 `256`。\
> `maxPages` - 默认 `50`\
> 最大页数，范围 `1` 到 `1000`。\
> `slotsCount` - 默认 `32`\
> 最大拉取种类数，也就是配置槽位总数，范围 `1` 到 `256`。

 </Column>

<Column gap="2" fullWidth={true}>

### maintenance_hatch

> `enabled` - 默认 `true`\
> 山海维护仓是否生效。设为 `false` 后，绕过功能会停用，但维护仓仍可放置。

 </Column>

<Column gap="2" fullWidth={true}>

### parallel_override

> `forceMode` - 默认 `false`\
> 并行覆写模式。`false` 只对已知机器类生效，属于精准覆写；`true` 会尝试对所有机器做全面覆写，并突破纳米核心一类机器的内部并行限制。

 </Column>

<Column gap="2" fullWidth={true}>

### hub_parallel_behavior

> `outputMultiplier` - 默认 `false`\
> 是否让枢纽的并行/线程值作为产出倍率。`false` 时枢纽只提供电压绕过和维护功能；`true` 时沿用原行为，把并行/线程值直接当产出倍率使用。

 </Column>

<Column gap="2" fullWidth={true}>

### super_parallel

> `multiplier` - 默认 `1.0`\
> 配方倍率补偿系数，定义范围为 `1.0` 到 `1.0E15`。源码注释建议使用 `1.0 ~ 1.0E12`。

 </Column>

<Column gap="2" fullWidth={true}>

### nine_industrial

> `fullMode` - 默认 `true`\
> 大明科技全配方模式。`true` 会按大类聚合搜索该大类下全部子配方类型；`false` 只搜当前大类的主配方类型，性能更好。

 </Column>

<Column gap="2" fullWidth={true}>

### module_independence

> `workWithoutHost` - 默认 `false`\
> 模块机器是否允许脱离主机独立运行。`false` 时必须有主机；`true` 时模块可脱离主机后独立运行配方。

 </Column>

<Column gap="2" fullWidth={true}>

### recursive_reverse_array

> `bypassModuleRestrictions` - 默认 `false`\
> 是否破除已连接子模块的内部限制。`false` 保持原版逻辑，催化剂、聚焦材料、温度窗口和运行状态全部正常检查；`true` 只要子模块已成型并接到递归反演阵列，就会把它视作满状态参与增益，但不会让脱离阵列独立运行的模块参与增益。

 </Column>

<Column gap="2" fullWidth={true}>

### me_disk_hatch

> `slots` - 默认 `108`\
> ME 磁盘仓室的槽位数，修改后需要重新放置仓室才会生效。

 </Column>

<Column gap="2" fullWidth={true}>

### virtual_item_provider

> `mode` - 默认 `AE_TARGET_CHECK`\
> 虚拟物品提供器模式。`AE_TARGET_CHECK` 按 AE 下单时的真实目标物检查网络；`SUPPLY_MACHINE` 则检查同网络虚拟物品供应机槽内是否存在目标物。\
> `autoWrapExclusions` - 默认 `gtceu:programmed_circuit`\
> 自动写样板时不包裹成虚拟物品提供器的物品 ID 列表。被排除物品会按原物品写入样板，保留自身 NBT。\
> `forceWrapOmittedNonConsumables` - 默认 `false`\
> 编码器反查到不消耗输入但玩家未放入时，`false` 尊重玩家删除输入的操作，不主动补回虚拟供应器或流体标记；`true` 保留旧行为，自动补回缺失的不消耗输入。

</Column>
