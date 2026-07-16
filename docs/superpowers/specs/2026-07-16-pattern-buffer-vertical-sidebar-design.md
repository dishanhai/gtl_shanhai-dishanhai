# 星律样板总成连续侧栏设计

## 背景

星律样板总成当前通过 `ConfiguratorPanel` 注册库存拉取、通配符、共享输入、返还和样板行为等入口。该面板使用 24 像素按钮加 2 像素间隔，并以可展开浮层覆盖主 UI；入口数量增加后，按钮列过长且容易与其他悬浮控件冲突。

ME 星轨输出矩阵使用 `attachSideTabs(TabsWidget)` 注册 `IFancyUIProvider`，由 GTCEu 的 `VerticalTabsWidget` 绘制连续贴边页签，不产生配置器浮层。

## 目标

- 将星律红框内全部配置入口迁移到星轨同款连续贴边侧栏。
- 主页面不再注册 `ConfiguratorPanel` 悬浮按钮。
- 保留现有库存、通配符、共享输入、样板电路、行为开关、刷新周期和返还功能。
- 不修改配方执行、库存同步、样板缓存、NBT 或网络语义。

## 侧栏结构

星律主页面高度为 144 像素。`VerticalTabsWidget` 每个页签占 24 像素，因此除主页面外固定注册 5 个子页签，恰好连续填满主页面高度：

1. **拉取库存**：复用 `StockInputConfigurator` 的物品/流体库存配置与页码。
2. **通配符样板**：复用 5 个母槽、展开预览、配方类型滚动列表和槽位独立分配行为。
3. **共享输入**：集中共享物品、共享流体和共享编程电路配置。
4. **样板行为**：集中副产物开关、终端可见性以及样板电路批量配置。
5. **ME 操作**：集中库存同步周期与“返还共享库存到 AE2”操作。

所有页签使用原配置器图标或现有山海图标，显示原有悬停说明。侧栏顺序固定，不按状态动态增删，避免按钮位置变化。

## 实现设计

### 页面注册

`RecipeTypePatternBufferPartMachine.attachSideTabs` 先调用父类实现，再按上述顺序注册 5 个 `IFancyUIProvider`。

`RecipeTypePatternBufferPartMachine.attachConfigurators` 不再调用父类，也不再注册山海配置器，从而清空星律页面的悬浮配置按钮。该行为仅作用于星律类，不修改 GTLCore 原版样板总成。

### 配置器适配

新增山海侧栏页面适配器，将现有 `IFancyConfigurator` 控件嵌入 `IFancyUIProvider` 页面。适配器必须转发：

- `writeInitialData` / `readInitialData`
- `detectAndSendChange` / `readUpdateInfo`
- 原配置器标题、图标和悬停说明

这样共享库存、共享流体、编程电路和高级 ME 配置继续使用原控件与同步协议，不复制底层逻辑。

`IFancyConfiguratorButton` 不调用其不支持的 `createConfigurator()`。副产物、终端可见性和返还操作改由对应侧栏页面中的明确按钮触发，仍调用原字段、刷新方法和 `refundAll` 入口。

### 页面尺寸

- 页面内容使用固定宽度并以 `GuiTextures.BACKGROUND_INVERSE` 作为内部工作区背景。
- 页面高度由内容决定；切换到较高页面时沿用 `FancyMachineUIWidget` 的既有动态尺寸逻辑。
- 主页面仍维持 144 像素，5 个子页签不会溢出或进入玩家背包区域。

## 行为边界

- 不保留无法证明必要的悬浮配置入口；当前检查未发现必须留在 `ConfiguratorPanel` 的项目。
- 返还操作保留为显式命令，避免仅打开页签就触发库存变更。
- 切换页面不修改任何机器配置，只有页面内控件点击才提交变化。
- 不更改通配符母样板 NBT、库存拉取槽数据或共享输入存储。

## 验证

- 源码契约测试确认使用 `attachSideTabs` 注册 5 个固定页面。
- 测试确认星律 `attachConfigurators` 不再调用父类或注册任何悬浮入口。
- 测试确认适配器转发完整 `IFancyConfigurator` 同步生命周期。
- 运行星律 UI 定向测试、相关通配符/库存回归测试和完整 `clean build`。
- 部署后实机检查：侧栏按钮连续无间隔、没有旧悬浮按钮、所有 5 页可打开且原功能可操作。
