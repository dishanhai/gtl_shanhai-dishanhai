---
navigation:
  title: 配方集合选择集基类
  parent: common_index.md
  position: 15
categories:
  - gt_shanhai
---

# 配方集合选择集基类

<Column gap="2" fullWidth={true}>

### 是什么

> `SelectableRecipeTypeSetMachine` 是一套把多个 `GTRecipeType` 组织成“可选集合”的公共基类。\
> 它负责统一的配方类型选择页、选中结果持久化、以及选中后重建配方逻辑。\
> 简单说，它让机器在一组配方类型里做“全选 / 仅第一项 / 全空 / 仅此项”的切换，而不是固定死单一类型。

</Column>

<Column gap="2" fullWidth={true}>

### 它做什么

> 这个基类本身不定义具体配方内容，也不注册新的 `GTRecipeType`。\
> 它只负责把“已经存在的配方类型”收拢成一个可选集合，并把选择状态传给配方逻辑。

* 读取机器定义里的所有可选配方类型。
* 在 Fancy UI 里生成“配方集合”页签。
* 把选中状态写入持久化数据，重载后保留。
* 选中集合变化时，重置配方逻辑并清空锁定配方。
* 支持强制搜索单个配方类型，用于轮询和锁定执行。

</Column>

<Column gap="2" fullWidth={true}>

### 核心状态

> `selectedRecipeTypeNames`：当前选中的配方类型名集合。\
> `recipeTypeSelectionInitialized`：是否已经完成初始选择。\
> `selectedRecipeTypesSync`：同步到客户端的换行字符串。\
> `recipeTypeSetWrapper`：当没有明确主选项时，用来占位的包装类型。

</Column>

<Column gap="2" fullWidth={true}>

### UI 行为

> 页签标题固定显示为“配方集合”，图标是比较器。\
> 只有当可选配方类型多于 1 个时，才会显示这个页签。\
> 页面里会显示当前所有可选类型，并提供 `全选`、`仅第一项`、`全空`、`仅此` 四类操作。

</Column>

<Column gap="2" fullWidth={true}>

### 数据流

> 玩家在 UI 里切换选项后，配置器会通过网络包把变更发到服务端。\
> 服务端写入选中集合后，会刷新同步数据、重置配方逻辑，并在必要时清空锁定配方。\
> 配方逻辑侧会优先按选中集合查方；若存在强制搜索类型，则临时只查那一个类型。

</Column>

<Column gap="2" fullWidth={true}>

### 典型使用方

* `ProxyExecutorMachine`
* `BoxSystemCentralControllerMachine`
* `PrimordialOmegaEngineMachine`
* 任何想让玩家自己勾选多个配方类型的机器

</Column>

<Column gap="2" fullWidth={true}>

### 使用建议

* 如果机器本质上只会跑一个类型，不要套这个基类。
* 如果机器要给玩家“按类型分组选择”的控制权，这个基类就是合适的入口。
* 如果只想保留选择页但不要额外 Fancy 侧栏，可以看 `CleanSelectableRecipeTypeSetMachine` 的做法。

</Column>
