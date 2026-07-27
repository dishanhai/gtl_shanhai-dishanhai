---
navigation:
  title: 高级样板包装箱
  parent: ae/index.md
  position: 89
categories:
  - gt_shanhai
  - ae2
item_ids:
  - gt_shanhai:advanced_pattern_box
---

# 高级样板包装箱

<Column gap="15" fullWidth={true}>

<ItemImage id="gt_shanhai:advanced_pattern_box" scale="4" />

<Column gap="2" fullWidth={true}>

高级样板包装箱是 <ItemLink id="gtlcore:pattern_box" /> 样板包装箱的**超集**：一个随身携带的已编码样板收纳袋，用来在样板供应器/样板总成/装配矩阵之间批量搬样板，省得一张一张手动拖。

* **容量可配置** — 不再是写死的 72 格，默认 9×6×4 = 216 格，可在配置文件里调整。
* **额外支持 ME 样板核心** — 基础版看不到的 <ItemLink id="gtceu:me_craft_pattern_container" /> ME 样板核心，高级版可以直接存取。
* **存档互通** — 和基础版样板包装箱用同一个 NBT 键，两种箱子之间可以互相当中转站用。
* 只接受**已编码样板**，空样板/其他物品会被过滤，塞不进去。

</Column>

<Column gap="2" fullWidth={true}>

## 与基础版样板包装箱的区别

| 对比项 | <ItemLink id="gtlcore:pattern_box" /> 样板包装箱 | 高级样板包装箱 |
|------|------|------|
| 容量 | 固定 72 格 | 默认 216 格，配置文件可调 |
| ME 样板核心 | 不支持（识别不到） | 支持，且走独立的双向搬运逻辑 |
| 样板供应器 / 样板总成 / 装配矩阵 | 支持 | 支持（原样委托给基础版逻辑，行为完全一致） |
| 存档格式 | `PatternInv` NBT | 同一个 `PatternInv` NBT，可与基础版互通 |

</Column>

<Column gap="2" fullWidth={true}>

## 使用方式

### 打开界面

对着空气右键，弹出包装箱自己的分页界面，可以直接在里面整理样板、翻页查看。

### 与方块互动

| 操作 | 目标 | 效果 |
|---|---|---|
| 右键 | 样板供应器 / 样板总成 / 装配矩阵 / <ItemLink id="gtceu:me_craft_pattern_container" /> ME 样板核心 | 把目标里的已编码样板**取出**装进包装箱 |
| 潜行右键 | 同上 | 把包装箱里的已编码样板**放入**目标 |

取出/放入都会在动作栏提示实际搬运数量，比如"已从 ME 样板核心取出 N 个样板"；箱子满了或目标满了会提示失败原因。

### ME 样板核心的特殊之处

ME 样板核心不实现 AE2 标准的样板供应器接口，只支持 GTLCore 自己的分子装配样板槽位，所以：

* 只有**右键直接点在 ME 样板核心本体上**才会触发核心专属逻辑；点多方块的控制器或其他部件仍走普通的样板供应器/样板总成解析链。
* 核心的槽位只收**分子装配机能跑的样板**，其余类型的已编码样板放不进去（潜行右键会提示"箱内样板不是分子装配机样板"）。

### 翻页

界面右上角一对 `<<` / `>>` 按钮按页翻看样板，每页显示 `patternsPerRow × rowsPerPage` 格。

</Column>

<Column gap="2" fullWidth={true}>

## 容量配置

配置文件 `gt_shanhai-common.toml` 的 `advanced_pattern_box` 段：

```
patternsPerRow（默认 9）— 每行样板槽位数
rowsPerPage（默认 6）— 每页行数
maxPages（默认 4）— 最大页数
总容量 = patternsPerRow × rowsPerPage × maxPages
```

调大立即生效，下次打开或使用包装箱就按新容量扩容；**调小不会丢样板**——已经存到高位槽的样板会把这个箱子的实际容量撑在原尺寸，直到那些高位槽被清空，箱子才会缩回配置里的新容量。

</Column>

<Column gap="2" fullWidth={true}>

## 常见问题

### Q: 和普通样板包装箱有什么关系，能混用吗？
**A:** 高级版是基础版的超集，两者存档格式相同，样板可以先倒进普通箱、再倒进高级箱，互相当中转站没问题。

### Q: 为什么普通样板包装箱拿不到 ME 样板核心里的样板？
**A:** ME 样板核心没有实现 AE2 标准的样板供应器接口，基础版包装箱识别不到它，只有高级版认识。

### Q: 潜行右键 ME 样板核心提示"箱内样板不是分子装配机样板"？
**A:** ME 样板核心的槽位只收分子装配机能跑的样板，箱子里其它类型的已编码样板放不进去，换个能被核心接受的样板再试。

### Q: 改了配置文件容量没变化？
**A:** 改完配置需要重启游戏生效；调小容量时，已放到高位槽的样板会把箱子撑在原尺寸，先清空高位槽再改小。

</Column>

</Column>
