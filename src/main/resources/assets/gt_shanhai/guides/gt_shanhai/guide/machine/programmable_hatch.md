---
navigation:
  title: 可编程仓
  parent: machine/multiblock_index.md
  position: 16
categories:
  - gt_shanhai
  - multiblock
item_ids:
  - gt_shanhai:ulv_programmable_hatch
  - gt_shanhai:lv_programmable_hatch
  - gt_shanhai:mv_programmable_hatch
  - gt_shanhai:hv_programmable_hatch
  - gt_shanhai:ev_programmable_hatch
  - gt_shanhai:iv_programmable_hatch
  - gt_shanhai:luv_programmable_hatch
  - gt_shanhai:zpm_programmable_hatch
  - gt_shanhai:uv_programmable_hatch
  - gt_shanhai:uhv_programmable_hatch
  - gt_shanhai:uev_programmable_hatch
  - gt_shanhai:uiv_programmable_hatch
  - gt_shanhai:uxv_programmable_hatch
  - gt_shanhai:opv_programmable_hatch
  - gt_shanhai:programmable_hatch
---

# 可编程仓

<Column gap="2" fullWidth={true}>

### 总览

> <ItemLink id="gt_shanhai:programmable_hatch" />\
> 这是一套会跟着等级一起变大的“配方选择仓”。它一边接收输入，一边让你指定多方块当前该搜哪一类配方。\
> 你可以把它当成一把工艺开关：需要的时候只认某一种配方，不需要的时候就回到组合模式。

</Column>

<Column gap="2" fullWidth={true}>

### 它能做什么

> 它最主要的作用，是让一台多方块机器别再一股脑把所有配方都算进去。\
> 你选中的那一项，会变成机器当前优先使用的配方类型。\
> 如果你想让机器保持通用，就切回组合模式。

* 可以把机器固定到某一种工艺上。
* 也可以在几种工艺之间来回切换。
* 同一结构里装了多个可编程仓时，最好选同一个类型。

</Column>

<Column gap="2" fullWidth={true}>

### 输入方式

> 这套系统不只是“选类型”，也还是一个能收材料的仓。\
> 真实原料照常能放，流体也照常能进。\
> 那些不该被消耗的材料，可以留在这里当作识别条件使用。

* 物品仓负责放原料和编程用的物品。
* 流体仓负责放流体和不消耗的流体条件。
* 电路槽会把你塞进去的编程物转成机器能认的目标。

</Column>

<Column gap="2" fullWidth={true}>

### 配置页

> 打开侧边配置页后，会看到这台机器当前能用的配方类型。\
> 选中的那一项，就是这台机器接下来要重点搜索的目标。\
> 列表里没有的类型，说明当前结构或当前连接下还不能用。

* 组合模式：保持通用状态，不锁死单一类型。
* 单一类型：把机器锁到你指定的那一项。
* 多可编程仓：只要选择一致，控制器就会按同一目标运行。

</Column>

<Column gap="2" fullWidth={true}>

### 规模变化

> 这套仓的等级越高，能装的东西越多。\
> 低等级适合小型切换，高等级更适合大结构和长期产线。\
> MAX 版本就是满级版，适合把一整台多方块的工艺切换都收进来。

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 想换工艺时，先把可编程仓选好，再检查原料和流体。
* 如果只是想让机器兼容所有工艺，就保留组合模式。
* 适合放在经常换产线的多方块里，省得反复拆机重配。

</Column>
