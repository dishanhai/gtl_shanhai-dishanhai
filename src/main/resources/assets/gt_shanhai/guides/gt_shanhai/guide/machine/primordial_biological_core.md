---
navigation:
  title: 原初生物核心
  parent: machine/primordial_index.md
  position: 2
categories:
  - gt_shanhai
  - primordial
item_ids:
  - gt_shanhai:primordial_biological_core
  - dishanhai:wzrm
  - dishanhai:wzjc
  - dishanhai:wzcz1
  - dishanhai:wzxc
  - dishanhai:wzsb
  - dishanhai:wzax
  - dishanhai:wzcz2
  - dishanhai:wzqs
  - dishanhai:wzgl
  - dishanhai:wzhy
  - dishanhai:wzsw
  - dishanhai:wzcx
  - dishanhai:wzdf
  - dishanhai:wzyh
  - dishanhai:wzcz3
  - dishanhai:reality_anchor_module
  - dishanhai:create_mk
---

# 原初生物核心

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:primordial_biological_core" />\
> 一台挂在原初引擎模块位上的生命系综合模块。它把原初生物演化协议、生物模拟、温室、培养缸、浮游选矿这 5 类配方收进同一套模块框架里。\
> 这台机的重点不是单纯“能跑五种配方”，而是把生命、生长、培养和浮游资源处理合并成一台统一吃模块倍率的生命产线核心。

</Column>

<Column gap="2" fullWidth={true}>

### 五合一配方组

* 原初生物演化协议：原初体系自己的生命演化主协议。
* 生物模拟：把生命过程当作可重复计算的工序来跑。
* 温室：偏作物、菌类和持续生长类产物。
* 培养缸：偏生物培养、增殖和定向繁育。
* 浮游选矿：把生命系处理链延伸到浮游资源筛选。

</Column>

<Column gap="2" fullWidth={true}>

### 并行来源

* 这台机有一个独立的并行物质槽，读取山海物质模块系列来决定并行上限。
* 空槽默认并行是 `64`，不是 `0`，所以就算没放倍率物也能先跑基础流程。
* 线程倍率槽负责跨配方线程，和并行物质槽是两套东西，不要混着看。
* 模块本体仍然受主机连接、独立运行配置、线程倍率物和额外挂载物共同影响。

</Column>

<Column gap="2" fullWidth={true}>

### 并行档位

> 这台机的并行不是看“配方类型”，而是看你塞进并行物质槽的那块山海物质模块。\
> 越往后并行跳得越夸张，所以更适合把它当作几段档位，而不是一个个死记数字。

* 起步档：空槽 `64`，<ItemLink id="dishanhai:wzrm" /> `128`，<ItemLink id="dishanhai:wzjc" /> `256`，<ItemLink id="dishanhai:wzcz1" /> `512`。
* 早中期档：<ItemLink id="dishanhai:wzxc" /> `1024`，<ItemLink id="dishanhai:wzsb" /> `2048`，<ItemLink id="dishanhai:wzax" /> `4096`，<ItemLink id="dishanhai:wzcz2" /> `16384`，<ItemLink id="dishanhai:wzqs" /> `65536`。
* 高阶档：<ItemLink id="dishanhai:wzgl" /> `524288`，<ItemLink id="dishanhai:wzhy" /> `1048576`，<ItemLink id="dishanhai:wzsw" /> `2097152`。
* 终局档：<ItemLink id="dishanhai:wzcx" /> `268435456`，<ItemLink id="dishanhai:wzdf" /> `536870912`，<ItemLink id="dishanhai:wzyh" /> `2147483647`。
* 顶格档：<ItemLink id="dishanhai:wzcz3" /> `4611686018427387903`，<ItemLink id="dishanhai:reality_anchor_module" /> `6917529027641081855`，<ItemLink id="dishanhai:create_mk" /> 直接到无限。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 如果你要的是一台统一处理生命链的大模块，这台机比把温室、培养缸和浮游选矿拆开更省主机位。
* 如果你只是临时跑少量生命类工序，空槽默认 `64` 并行已经够起步，不必一上来就塞最高档模块。
* 真正需要高并行时，再考虑往后换更高阶的山海物质模块；否则很多档位只是数字很大，实际收益未必成比例。
* 它本质上还是原初模块，先保证接上原初引擎，再谈并行和线程倍率。

</Column>
