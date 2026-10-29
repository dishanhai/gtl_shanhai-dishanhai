---
navigation:
  title: 蒸汽生成机
  parent: machine/machine_index.md
  position: 5
categories:
  - gt_shanhai
  - multiblock
item_ids:
  - gt_shanhai:steam_generator
---

# 蒸汽生成机

<BlockImage id="gt_shanhai:steam_generator" scale="6" />

<ItemLink id="gt_shanhai:steam_generator" /> 是蒸汽阶段的青铜多方块，直接将水转为蒸汽，供应前期蒸汽产线。

- 水：蒸汽为 **2：1**；每份配方消耗 2000 mB 水，产出 1000 mB 蒸汽。
- 每批耗时 **20 tick（正常游戏速度下为 1 秒）**，无须电力、燃料或蒸汽。
- 控制器内的物质模块槽决定并行上限，采用**原初模块并行 ×4**。
- 实际批量受水量及输出空间限制；完成时若无法输出，会保留当前批次等待。

可以把它当成水转蒸汽的供应站：输入端供水，输出端接蒸汽管道或 AE 回网设备。

## 结构与材料

结构为 **3×3×3**。以下三片由控制器所在的正面往后排列；每片均由上往下阅读。

```text
正面    中间    后面
GGG     GPG     GGG
CSC     IPO     CCC
FFF     FFF     FFF
```

| 符号 | 方块／位置 | 数量 |
| --- | --- | --- |
| S | <ItemLink id="gt_shanhai:steam_generator" />，正面中央 | 1 |
| G | <ItemLink id="gtceu:bronze_gearbox" /> | 8 |
| P | <ItemLink id="gtceu:bronze_pipe_casing" /> | 2 |
| C | <ItemLink id="gtceu:bronze_machine_casing" /> | 5 |
| F | <ItemLink id="gtceu:bronze_firebox_casing" />，底层 | 9 |
| I | 现有的流体输入仓，侧面中央 | 1 |
| O | 现有的流体输出仓，另一侧中央 | 1 |

I、O 使用现有仓室，按各自的流体输入／输出能力匹配。搭建朝向以 JEI 结构预览为准。底座的青铜燃烧室是结构材料，这份水转蒸汽配方不需要投入燃料。

## 物质模块与并行

在控制器界面底部的**物质模块槽**放入物质模块。模块会留在槽内，不作为配方耗材；更换后，下一批使用新的上限，已开始的批次仍按原数量完成。

本机直接沿用原初模块的并行表和堆叠加成，再将结果乘以 4。

| 搭载内容 | 原初基准并行 | 本机并行上限 |
| --- | --- | --- |
| 未搭载 | 64 | 256 |
| 1 个 <ItemLink id="dishanhai:wzrm" /> | 128 | 512 |
| 1 个 <ItemLink id="dishanhai:wzjc" /> | 256 | 1024 |
| 1 个 <ItemLink id="dishanhai:wzcz1" /> | 512 | 2048 |
| 1 个 <ItemLink id="dishanhai:create_mk" /> | Long.MAX_VALUE | Long.MAX_VALUE |

同一槽内的模块堆叠倍率为 `1 + floor(数量 / 16)`：

| 入门模块数量 | 堆叠倍率 | 本机并行上限 |
| --- | --- | --- |
| 1～15 | ×1 | 512 |
| 16～31 | ×2 | 1024 |
| 32～47 | ×3 | 1536 |
| 48～63 | ×4 | 2048 |
| 64 | ×5 | 2560 |

乘法全程使用整数。高阶模块及堆叠加成若超过 `Long.MAX_VALUE`，结果会饱和至该上限，维持有效的正数并行。

界面显示的是**并行上限**。例如放入 1 个入门模块后，上限为 512；水量与输出空间足够时，每批消耗 1,024,000 mB 水并产出 512,000 mB 蒸汽。供料或输出空间较少时，会采用较小的可执行批量。

## 使用方式

1. 制作控制器，按结构预览搭建青铜结构及两个现有流体仓。
2. 向输入仓供应水，为输出仓准备蒸汽去处。
3. 按产能需求，在控制器底部放入物质模块；查看界面的当前并行上限。
4. 开启工作，确认输入水量减少、输出蒸汽增加。
5. 若要集中供应 AE 蒸汽产线，将生成端输出接入 AE 网络，再依[AE 蒸汽供应](../ae/ae_steam_supply.md)设定消耗端。

使用 <ItemLink id="gt_shanhai:me_starrail_output_matrix" /> 等回网设备时，产物先进入它的本地暂存，再送入 AE。网络离线时请分别查看暂存内容和网络连线；回网设备的说明见[输出（回网）](../ae/output_index.md)。

## 排查产量与输出

- **并行低于上限：**检查可用水量和蒸汽输出空间；上限不代表每批必定跑满。
- **完成后等待输出：**清理或疏通输出端。当前批次会保留，恢复后再输出。
- **蒸汽没有进入 AE：**先看输出设备的暂存，再检查其 AE 电力、频道和储存空间。
- **换模块后仍看到旧批量：**已开始的批次会先完成；新上限在下一批采用。
- **存档或拆机：**模块槽会保存；拆除控制器时，槽内模块会掉出。

更多物质模块说明见[原初引擎与模块](primordial_index.md)。
