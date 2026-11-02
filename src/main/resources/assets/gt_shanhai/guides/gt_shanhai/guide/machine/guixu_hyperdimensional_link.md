---
navigation:
  title: 归墟超维度链接模块
  parent: machine/guixu_index.md
  position: 30
categories:
  - gt_shanhai
  - primordial
  - guixu
item_ids:
  - gt_shanhai:guixu_hyperdimensional_link_module
---

# 归墟超维度链接模块

<ItemLink id="gt_shanhai:guixu_hyperdimensional_link_module" /> 是连接 <ItemLink id="gt_shanhai:guixu_vision_core" /> 与 <ItemLink id="gt_shanhai:primordial_omega_engine" /> 的跨维度桥接道具。通过它绑定后，归墟视界枢核会直接**远程接管**目标原始终焉引擎身上的全部 16 个模块位，并将归墟的全套主机增益共享过去，无需玩家拆除搬迁旧引擎产线。

- **跨维度、无限距离接管**：只要归墟主机与目标原始终焉引擎所在区块均处于已加载状态，即可实时维持接管。
- **扩容至 40 个模块槽**：归墟本机 24 槽（槽位 1～24）加上接管引擎的 16 槽（AR 全息屏槽位 25～40），单座归墟最多统合 **40 台原初模块**。
- **全套增益下放**：被接管的引擎本体及其 16 个模块，完整共享归墟的**材料增产、模块等级复写、主机额外挂载、万象衍生倍率与彭罗斯黑洞遏制场加成**。
- **解锁 10 种高阶原初模块**：默认配置下禁止在普通引擎运行的 10 种高阶模块，在引擎被归墟超维度链接接管后即可直接原地合法运行。

## 绑定与解绑操作流程

### 绑定步骤

1. 确保目标 <ItemLink id="gt_shanhai:guixu_vision_core" /> 与 <ItemLink id="gt_shanhai:primordial_omega_engine" /> 均**已结构成型**，且双方区块已加载。
2. 手持 <ItemLink id="gt_shanhai:guixu_hyperdimensional_link_module" /> **直接右键**归墟视界枢核控制器。
   - 提示「已记录归墟视界枢核: [坐标]」，物品 Tooltip 会显示已记录的维度与坐标。
3. 手持同一枚模块，前往目标 <ItemLink id="gt_shanhai:primordial_omega_engine" /> 控制器并**直接右键**。
   - 提示「已绑定原始终焉引擎 [坐标] 到归墟主机」。
   - **生存模式**下绑定成功会**消耗 1 个**链接模块；创造模式下仅清空物品上的坐标记录。

### 解绑与清除记录

| 操作方式 | 触发效果 |
| --- | --- |
| 手持链接模块 **Shift + 右键** 已绑定的 <ItemLink id="gt_shanhai:guixu_vision_core" /> | **解除超维度链接**（模块立即归还原引擎名下，不返还已消耗的链接模块物品） |
| 手持链接模块 **Shift + 右键** 任意其他方块 | **清除物品上已记录的归墟坐标**（方便重新记录另一台归墟） |

### 绑定约束规则

- **一对一独占**：1 台归墟视界枢核同时**最多绑定 1 台**原始终焉引擎；1 台原始终焉引擎也只能被 1 台归墟主机接管。
- **每秒自动调和**：绑定后服务端每秒检查一次。若目标引擎新装了模块或重新成型，归墟会在下一秒自动把模块接管过来；若任一方区块卸载则暂不动作；若任一方被拆除或结构失效，远端模块会自动释放回原引擎。

## 接管后的增益共享与状态联动

当归墟主机抬头显示「超维度链接：...｜已接管」时，被接管的 <ItemLink id="gt_shanhai:primordial_omega_engine" /> 抬头会新增紫色提示「**已共享归墟增益：配方产出 X×**」，具体共享项如下：

1. **配方产出倍率**：被接管引擎本体（运行 5 类基础工序）及全部接管模块，共享归墟主机的总产出倍率（含归墟基础 `+5` 增产、物质模块专属槽等级、彭罗斯遏制场 `+5` 增产、以及链路内最高万象衍生核心倍率）。
2. **模块等级复写与等效模块数**：归墟主机「模块等级复写槽」的保底等级、全链路汇总的等效物质模块数量（含遏制场 `×2` 堆叠倍率），对远端引擎上的模块完全生效。
3. **主机 3 格额外挂载**：归墟主机左侧 3 格额外挂载槽放入的维护仓、维度碎片、研究组件、满堆线圈与恒星热力容器，同样作为远端引擎模块的合法环境/热力来源。
4. **彭罗斯黑洞遏制场联动**：遏制场提供的 `×4/×8/×16` 并行、`×0.5/×0.25/×0.125` 耗时与 `×0.5` 耗电同步作用于远端引擎与模块；反之，若归墟发生**黑洞坍缩**，远端引擎与模块也会同步停机并显示「链接的归墟黑洞坍缩：原始终焉引擎暂停」。

## 万象衍生核心冲突仲裁机制

当归墟本机槽位（1～24）与被接管引擎槽位（25～40）中安装了多个 <ItemLink id="gt_shanhai:primordial_myriad_proliferation_core" />（原初万象衍生核心）时：
- **不叠加、只取最高**：整条归墟链路最多只有 **1 台**万象核心生效（按候选倍率比较：空闲基础 `10×` < 运行二级晋升 `100×` < 运行一级晋升 `1000×`）。
- **同倍率优先级**：若多台万象核心处于相同倍率档位，优先选用**安装在归墟本机 24 槽上**的核心；若同属本机或同属远端，则按维度与坐标字典序固定选出唯一胜出者。
- **冲突停用保护**：未胜出的其余万象核心会被自动标记为**冲突停用**（倍率视为 `1×`，暂停开启新批次并暂存已投入批次，抬头提示「万象核心已停用」；归墟与引擎抬头同步提示「万象核心冲突」）。拆掉多余核心后即可立即恢复运行。

## 高阶原初模块准入限制（默认 10 种）

在默认配置下，以下 **10 种高阶原初模块**禁止在独立的 <ItemLink id="gt_shanhai:primordial_omega_engine" /> 上直接运行（安装后会停机并报错「此模块需要接入归墟视界枢核；可直接安装，或由归墟超维度链接模块接管所在引擎」）：

| 受限高阶模块 | 对应核心职能 |
| --- | --- |
| [<ItemLink id="gt_shanhai:primordial_causal_weaving_matrix" />](primordial_causal_weaving_matrix.md) | 原初因果编织 |
| [<ItemLink id="gt_shanhai:primordial_singularity_inversion_core" />](primordial_singularity_inversion_core.md) | 奇点反演与高阶凝聚 |
| [<ItemLink id="gt_shanhai:primordial_multidimensional_implosion_core" />](primordial_multidimensional_implosion_core.md) | 电力聚爆与普通聚爆 |
| [<ItemLink id="gt_shanhai:primordial_supercritical_matter_generation_core" />](primordial_supercritical_matter_generation_core.md) | 超临界合成、物质生成与质量发生 |
| [<ItemLink id="gt_shanhai:primordial_tianqiong_assembly_core" />](primordial_tianqiong_assembly_core.md) | 太空组装与超时空装配线 |
| [<ItemLink id="gt_shanhai:primordial_eternal_smelting_furnace" />](primordial_eternal_smelting_furnace.md) | 超维熔炼、终极物质锻造、分子解构与混沌炼金 |
| [<ItemLink id="gt_shanhai:primordial_quantum_distortion_matrix" />](primordial_quantum_distortion_matrix.md) | 量子操纵、奇点压缩、纳米制造与精密材料加工 |
| [<ItemLink id="gt_shanhai:primordial_shaoguang_aggregation_core" />](primordial_shaoguang_aggregation_core.md) | 聚合核心、龙脉结晶与创造聚合 |
| [<ItemLink id="gt_shanhai:primordial_weiyang_reconstruction_module" />](primordial_weiyang_reconstruction_module.md) | 物质异化、梦魇合成、电磁共振、地脉断层与虚空聚流 |
| [<ItemLink id="gt_shanhai:primordial_universe_simulation_core" />](primordial_universe_simulation_core.md) | 宇宙模拟 |

**解锁方式**：
1. 直接将上述模块安装在已成型的 <ItemLink id="gt_shanhai:guixu_vision_core" /> 的 24 个本机槽位上。
2. 或者保持它们安装在 <ItemLink id="gt_shanhai:primordial_omega_engine" /> 上，用 <ItemLink id="gt_shanhai:guixu_hyperdimensional_link_module" /> 将该引擎绑定至已成型的归墟视界枢核。
3. 若整合包作者希望解除此限制，可在配置文件或模组配置界面修改 `primordial_omega_engine` 分组下的 `allowAnyPrimordialModule` 或 `blockedModuleItemIds`（详见[配置文件解析](../config_index.md)）。
