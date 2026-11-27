# 以太相引擎结构接入

作者：dishanhai。版本：4.9.5.Alpha.1。日期：2026-11-26。

## 入口

在山海创造栏或 JEI 搜索「以太相引擎」，注册 ID 为
`gt_shanhai:aetherophasic_engine_preview`。控制器成型后可运行「以太相跃迁」，并提供 JEI 结构预览和山海终端建造入口。控制器和八种专属结构件均使用超时空装配配方制作。

原 NBT 的 `gtlcore:spacetimebendingcore[active=true]` 是主机标记，在运行图案中替换为此控制器。六个 `gtlcore:opv_hermetic_casing` 保留原位，也接受标准物品、流体及能源输入／输出仓。仓室在此版本仅参与结构匹配。

## JSON 与坐标

正式资源：`src/main/resources/assets/gt_shanhai/multiblock/aetherophasic_engine.json`。
这是项目使用的调色板／切片 JSON，不是原版世界生成结构 JSON。`format_version` 为 1，
`palette` 保留原 NBT 的方块 ID 和状态；字符从 `A` 起按调色板索引编号。
`aisles[z][y][x]` 保留原始坐标，未记录的位置用空格表示。空格沿用大型结构的任意方块语义，不要求清空。

- 原始尺寸：85 × 91 × 87。占用范围：X=0…84、Y=6…90、Z=0…86；底部六层空白保留。
- 主机：`(42,42,73)`，符号 `J`。
- 仓位：`(39,42,73)`、`(40,42,73)`、`(41,42,73)`、`(43,42,73)`、`(44,42,73)`、`(45,42,73)`，符号 `I`。
- 图案轴：`LEFT / UP / FRONT`。控制器朝南时与 NBT 的世界轴一致，其他水平方向整体旋转。
- 原始 `DataVersion=3465`、`BuilderMCP` 和来源 SHA-256 同存于 JSON；原文件没有实体或方块实体。

| 符号 | 方块 ID | 数量 |
| --- | --- | ---: |
| A | gt_shanhai:aetherophasic_casing | 46,262 |
| B | gt_shanhai:aetherophasic_structural_rib | 3,014 |
| C | gt_shanhai:aetherophasic_conduit | 11,391 |
| D | gt_shanhai:aetherophasic_optical_coupler | 648 |
| E | gt_shanhai:aetherophasic_fastener | 1,188 |
| F | gt_shanhai:aetherophasic_aperture | 4,052 |
| G | gt_shanhai:aetherophasic_heat_vent | 644 |
| H | gt_shanhai:aetherophasic_stellar_surface | 12,980 |
| I | gtlcore:opv_hermetic_casing | 6 |
| J | gtlcore:spacetimebendingcore[active=true]（主机标记） | 1 |

`AetherophasicEngineLayout` 读取并校验 JSON，`AetherophasicEngineStructure` 将切片交给
已有 `DShanhaiMBParser.parseArray`。没有新增通用结构框架，也不修改其他机器的加载规则。
GTCEu 的多方块注册表自动向 JEI 提供结构；山海终端读取同一个 `BlockPattern`，不需要另设机器白名单。

## 超时空装配配方

资源目录：`src/main/resources/data/gt_shanhai/recipes/suprachronal_assembly_line/aetherophasic/`。
九份配方统一使用 `gtceu:suprachronal_assembly_line`；`euTier=13`，耗电为 `GTValues.VA[OpV]`，即 503,316,480 EU/t。配方 ID 前缀与类型一致，以支持山海样板强匹配；旧装配机配方的样板需重新编码。
八种结构件每批均产出 1 个；装甲外壳、能量导管、紧固件、结构肋、散热口和光学耦合器耗时 100 tick，光阑与恒星表层耗时 120 tick。每条结构件配方使用 5～6 种物品及两种流体。

2026-11-27 按部件用途重新选材：OPV 只约束制作电压，材料可跨科技阶段。八种结构件的非电路物品由 15 种扩展为 36 种，流体由 3 种扩展为 13 种；仅光学布线复用普通光导管。电路必须使用 `gtceu:circuits/opv` 和 `gtceu:circuits/max` 标签，并作为消耗品投入，不固定具体电路物品。

| 产物 | 物品输入 | 流体输入（mB） |
| --- | --- | --- |
| 装甲外壳 ×1 | 基础恒星热力容器 ×8、双层三钛板 ×16、双层中子素板 ×8、高速钢 G 框架 ×8、石墨烯板 ×8、OPV 电路标签 ×2 | 原始恒星混合物等离子体 10、聚苯并咪唑 2,304 |
| 能量导管 ×1 | 装甲外壳 ×8、十六倍宇宙中子素导线 ×16、普通时空流体管道 ×8、普通光导管 ×8、时空静滞装置 ×8、MAX 电路标签 ×2 | 星辉等离子体 4,000、`gtceu:trinium` 2,304 |
| 装甲紧固件 ×1 | 中子素环 ×8、山铜螺丝 ×16、三钛螺栓 ×8、超时空金属小齿轮 ×8、OPV 电路标签 ×1 | 铋铅合金-140 2,304、润滑油 1,000 |
| 结构肋 ×1 | 无尽框架 ×8、超时空金属长杆 ×8、双层艾德曼合金板 ×8、山铜齿轮 ×8、OPV 电路标签 ×2 | 熔融无尽 2,304、原始恒星混合物等离子体 5 |
| 散热口 ×1 | 中子素框架 ×8、热能超导体 ×8、银板 ×8、相对论级热容 ×4、OPV 电路标签 ×2 | 液氦 4,000、钠钾合金 4,000 |
| 光阑 ×1 | 山铜环 ×4、铼强化聚能玻璃 ×8、装配线光栅 ×8、量子之眼 ×4、UXV 发射器 ×4、OPV 电路标签 ×2 | 原始恒星混合物等离子体 10、量子点 2,000 |
| 光学耦合器 ×1 | 能量导管 ×8、龙素涂覆的掺星辉晶圆 ×8、时空透镜 ×8、时空 SoC ×4、普通光导管 ×8、MAX 电路标签 ×2 | 超突变活性焊料 2,304、时空 288 |
| 恒星表层 ×1 | 进阶恒星热力容器 ×8、觉醒龙板 ×16、星辉箔 ×8、𬭶板 ×8、宇宙中子素弹簧 ×8、MAX 电路标签 ×2 | 原始恒星混合物等离子体 20、觉醒龙等离子体 4,000 |

板材和框架用于承力，螺丝与螺栓用于紧固，导线与时空管道用于导能，热能超导体、相对论级热容和冷却液用于散热，光栅、透镜与 SoC 用于光学控制。材料含 OPV 之前与之后的生产链，不保证进入 OPV 时即可获得全部材料。表中的无命名空间材料 ID 均属 `gtceu`；完整 ID 以配方 JSON 为准。

主机配方用时 12,000 tick（600 秒），产出 1 个以太相引擎控制器；用满超时空装配配方类型的 16 种物品及 4 种流体输入：

- OPV 机器外壳 ×1,806；以太相装甲外壳 ×30,347、能量导管 ×26,214、装甲紧固件 ×13,480、结构肋 ×15,785。
- 以太相光阑 ×32,401、光学耦合器 ×31,342、散热口 ×30,506、恒星表层 ×13,309。
- OPV 力场发生器 ×4,190、引力引擎单元 ×3,599、中央引力子流量调节器 ×7,923。
- `gtceu:circuits/opv` 电路 ×6,064、`gtceu:circuits/max` 电路 ×2,169；两种电路各占一个输入槽，均按标签匹配。
- 时空扭曲核心方块 ×830、相对论级热容 ×1,600。
- 原始恒星混合物等离子体 6,911,413 mB、星辉等离子体 18,597,326 mB、星辉 12,163,694 mB、超突变活性焊料 36,174,061 mB。

主机物品及其他流体数量由随机取值确定，原始恒星混合物等离子体随后调低；所有数量均为固定消耗，不会在每次合成时重新随机。物品及流体需通过足够容量的输入仓或网络供应。

按结构中的方块数量及一台主机、无额外损耗计算，整套共需制造 2,424,809 个装甲外壳，消耗 32,143,808 mB 原始恒星混合物等离子体、5,043,154 个 OPV 标签电路及 705,777 个 MAX 标签电路。该数字包含中间结构件的投入，未包含仓位材料和其他输入的上游生产。递归统计见 `tmp/aetherophasic-variety-bom-20261127.json`。

设备墙的六个密封机械方块 XIII 继续使用 GTLCore 已有配方。

物品与流体 ID 对照目标实例的运行时注册日志；两种电路标签对照当前 GTCEu JAR 的 `CustomTags` 定义。实际游戏内的标签成员、配方加载、合成和材料获取仍需在目标整合包验收。

本次配方调整的资源检查与 `build -x test` 已通过，未重跑无改动的结构测试，也未部署或实测游戏合成。核验报告：`tmp/aetherophasic-variety-check-20261127.json`；构建日志：`tmp/aetherophasic-variety-build-20261127.log`。

## 重现转换与验证

转换器需要 Python 的 `nbtlib`，运行时游戏不依赖 Python。项目根目录执行：

```bash
python src/test/python/convert_aetherophasic_structure.py \
  'C:/Users/dishanhai/Downloads/以太相引擎 · 完成态.nbt' \
  src/main/resources/assets/gt_shanhai/multiblock/aetherophasic_engine.json
```

来源 SHA-256：`58b1cbf30590b9d58f9aa0e60f4632b31df348e1d9a450590937add1db2ef339`。
独立解码原 NBT 与 JSON 后，全部 80,186 个占用坐标及状态一致，尺寸、调色板和元数据一致。
转换器遇到重复／越界坐标、额外根字段、实体或方块实体时会拒绝，避免静默丢失。

本次以 JDK 21、Gradle 8.8 执行：

```bash
gradle test --tests '*AetherophasicEngineLayoutTest' \
  --tests '*MultiblockDataResourceTest' --tests '*ShanhaiTerminalStructureSourceTest' --no-daemon
gradle build -x test --no-daemon
```

16 项定向检查全部通过，构建通过。未运行全套测试。日志：
`tmp/aetherophasic-structure-tests.log`、`tmp/aetherophasic-structure-build.log`。
逐格转换核验：`tmp/aetherophasic-structure-conversion-check.json`。

游戏内 JEI 显示、四向成型、仓室替换与终端建造尚未实测，验收条目见根目录 `TODO.md`。未部署到游戏实例。

### 后续运行时核验（2026-11-26）

用户更新并重新启动实例后，`D:/pcl/.minecraft/versions/GregTech Leisure-增强版/logs/debug.log`
在 15:10:18 明确记录 `gt_shanhai:aetherophasic_engine_preview` 已进入方块、物品和方块实体三项注册表。
核验时实例的 `mods` 目录仅有包含新控制器的 `gt_shanhai-4.9.5.Alpha.1.jar`。
证据保存在 `tmp/aetherophasic-runtime-registration-check.json`。
这证明运行时注册成功；创造栏／JEI 的可见性和结构交互仍需游戏内验收。
拥有命令权限时，可用 `/give @s gt_shanhai:aetherophasic_engine_preview` 直接获取控制器。
