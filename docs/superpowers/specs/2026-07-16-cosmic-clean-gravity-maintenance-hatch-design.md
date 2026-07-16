# 寰宇洁净重力维护仓设计

## 背景

终焉聚合枢纽通过 `IMaintenanceBypassPart` 和多组 mixin 提供配方条件、电压、算力、温度、概率、耗时、并行、线程等综合绕过能力。新部件定位为枢纽的低级版本，只提供基础维护与洁净/重力环境，不得继承枢纽的生产强化能力。

GTLCore 已有自动洁净维护仓，可向多方块控制器提供普通洁净室、无菌洁净室和法则洁净室。其重力维护仓只能配置为 0 或 100，无法同时满足无重力与强重力配方，因此双重力兼容需要独立实现。

## 目标

- 新增 `gt_shanhai:cosmic_clean_gravity_maintenance_hatch`，显示名为“寰宇洁净重力维护仓”。
- 提供全自动维护，不产生维护故障。
- 同时满足普通洁净室、无菌洁净室和法则洁净室要求。
- 同时满足 GTLCore 的无重力与强重力配方条件。
- 映射为维护仓、物品输入仓和流体输入仓，以兼容不同多方块结构谓词。
- tooltip 只描述实际功能与边界。

## 非目标

- 不绕过维度、研究或其他配方条件。
- 不绕过电压、EU 消耗、算力、温度、概率或配方时长。
- 不提供并行、线程、产出倍率、模块槽或枢纽 GUI。
- 物品/流体输入仅作结构能力映射，不提供真实物品槽、流体罐或输入容量。
- 本次不新增合成配方。

## 实现设计

### 机器部件

新增 `CosmicCleanGravityMaintenanceHatchMachine`，继承 GTLCore 的 `GTLCleaningMaintenanceHatchPartMachine`，构造时固定传入 `ICleaningRoom.LAW_DUMMY_CLEANROOM`。该洁净提供器包含普通、无菌和法则三种洁净类型，并沿用 GTLCore 的全自动维护行为。

部件实现一个山海专用的窄标记接口 `IUniversalGravityMaintenancePart`。该接口只表达“同时提供两种重力环境”，不继承 `IMaintenanceBypassPart`，避免被枢纽的免电压、免算力和其他 mixin 识别。

### 重力条件

新增针对 GTLCore `GravityCondition` 的 mixin，在条件测试入口检查配方机器是否为已成型多方块，并遍历其部件。存在 `IUniversalGravityMaintenancePart` 时，返回与 `isReverse()` 相反的布尔值，使普通和反转重力条件都通过；不存在时完全保留 GTLCore 原逻辑。

该 mixin 只作用于 `GravityCondition`，不修改 `GTRecipe.checkConditions()`，因此维度、研究和其他条件继续执行。

### 注册与能力映射

在 `DShanhaiMachines` 中新增机器定义和初始化注册：

- ID：`cosmic_clean_gravity_maintenance_hatch`
- 旋转：`RotationState.ALL`
- 渲染：复用 GTCEu 高阶洁净维护仓的 `MaintenanceHatchPartRenderer`
- 物品：标准 `MetaMachineItem`

注册完成后，将同一方块映射到以下 `PartAbility`：

- `MAINTENANCE`
- `IMPORT_ITEMS`
- `IMPORT_FLUIDS`

能力映射只用于结构匹配。部件不注册物品或流体配方处理器，因此不会成为实际输入库存，也不会产生重复输入能力。

### 文案

`zh_cn.json` 和 `en_us.json` 增加方块名称。tooltip 在机器定义上直接写明：

- 全自动维护。
- 提供普通、无菌和法则洁净环境。
- 同时满足无重力与强重力条件。
- 可替代结构中的维护、物品输入、流体输入位置。
- 不提供实际物品/流体容量，不包含终焉聚合枢纽高级功能。

## 数据流

1. 多方块结构匹配时，三个 `PartAbility` 映射允许该部件占据对应仓室位置。
2. 部件加入控制器时，GTLCore 原生逻辑把法则洁净提供器设置到 `ICleanroomReceiver`。
3. 配方检查洁净条件时，GTCEu 原生 `CleanroomCondition` 从控制器读取洁净提供器并正常通过。
4. 配方检查重力条件时，专用 mixin 检测重力标记并仅放行该条件。
5. 其余条件、配方输入、电力与运行逻辑保持原路径。

## 兼容与失败边界

- 只在控制器已成型且部件实际挂载时提供双重力能力。
- 移除部件时沿用父类逻辑清除其洁净提供器。
- 若 GTLCore 未执行目标 `GravityCondition.test`，mixin 应在启动时明确暴露注入失败，不静默扩大到全部条件绕过。
- 多方块若要求真实输入容量，仍需额外安装实际输入仓；本部件只解决结构槽位兼容。

## 验证

- TDD 源码契约测试确认新类继承自动洁净维护仓、使用 `LAW_DUMMY_CLEANROOM`，且未实现 `IMaintenanceBypassPart`。
- 测试确认重力 mixin 只针对 `GravityCondition`，并要求已成型控制器和专用标记。
- 测试确认机器 ID、三个能力映射、mixin 配置和中英文名称均已注册。
- 运行相关定向测试和完整 `clean build`。
- 部署后实机验证：维护正常、三档洁净配方可运行、0G/100G 配方均可运行，维度与研究条件仍会阻止不满足的配方。
