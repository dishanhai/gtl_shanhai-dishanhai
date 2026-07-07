---
navigation:
  title: 配方剥离
  parent: recipe_control_index.md
  position: 20
categories:
  - gt_shanhai
---

# 配方剥离

剥离规则是从配方里移除指定输入或输出项。源码里对应 `StripEntry`、`addStripRule`、`removeStripRule`、`removeStripRules` 和 `reloadStripRules`。

<Column gap="2" fullWidth={true}>

### 基础原理

> 此方法直接在配方对象上剥离指定物品 / 流体需求，让配方不再需要该材料。\
> 剥离采用副本机制（`recipe.copy()` → 剥离原本 → 返回副本），原配方永不修改。\
> 规则变更后会自动重建配方查找树，实时生效。恢复命令可将剥离规则全部清除，配方还原。

</Column>

<Column gap="2" fullWidth={true}>

### 持久化与路径

> 这是一个持久化配置文件，路径是 `config/gt_shanhai/strip_rules.json`。\
> 保存后会影响 JEI 暴露的数据，并隐藏原配方。\
> 需要 `F3+T` 重载资源包后，才能在 JEI 中看到修改结果。

</Column>

<Column gap="2" fullWidth={true}>

### 规则字段

> `targetItem`：目标物品或流体 ID\
> `isInput`：`true` 表示输入，`false` 表示输出\
> `isFluid`：`true` 表示流体，`false` 表示物品\
> `recipeId`：可选的配方 ID 过滤

</Column>

<Column gap="2" fullWidth={true}>

### 命令格式

> `/山海 配方 剥离 <type> <物品>`\
> 剥离该类型所有配方中的指定物品，输入和输出都会剥离。\
> `/山海 配方 剥离 <type> <物品> 输入`\
> 只剥离输入侧的物品。\
> `/山海 配方 剥离 <type> <物品> 输出`\
> 只剥离输出侧的物品。\
> `/山海 配方 剥离 <type> <物品> 流体`\
> 剥离该类型所有配方中的指定流体。\
> `/山海 配方 剥离 <type> <物品> 输入 流体`\
> 只剥离输入侧的流体。\
> `/山海 配方 剥离 <type> <物品> 输出 流体 [recipeId]`\
> 剥离指定配方的输出流体。\
> `<recipeId>` 可选填，不填就作用于该类型下的所有配方。\
> 不写 `输入 / 输出` 时，会同时剥离两侧。

</Column>

<Column gap="2" fullWidth={true}>

### 示例

> 剥离物品（全部）：`/山海 配方 剥离 stellar_forge gtceu:copper_ingot`\
> `stellar_forge` 所有配方不再需要铜锭，输入和输出都会被清掉。\
> 剥离物品（仅输入）：`/山海 配方 剥离 stellar_forge gtceu:iron_ingot 输入`\
> 配方仍然产出铁锭，但不再消耗铁锭。\
> 剥离流体：`/山海 配方 剥离 stellar_forge minecraft:water 流体`\
> `stellar_forge` 所有配方不再需要水。\
> 剥离指定配方的输出流体：`/山海 配方 剥离 stellar_forge gtceu:oxygen 输出 流体 my_recipe_id`\
> 仅指定配方不再产出氧气。

</Column>

<Column gap="2" fullWidth={true}>

### 三路径架构

> 运行时路径：机器配方查找时副本剥离，`RecipeIteratorStripMixin` 会在 `next()` 返回前给配方做副本剥离。\
> 模板路径：`RECIPE_ORIGINALS` 缓存原始配方，规则变更后通过 `updateLookupRecipes()` 重建。\
> JEI 路径：`BranchStripMixin` 会对 `Branch.getRecipes()` 返回的配方应用剥离，让 JEI 显示同步到规则结果。

</Column>

<Column gap="2" fullWidth={true}>

### 恢复与重载

> `/山海 配方 恢复`：清除所有剥离和替换规则。\
> `/山海 配方 恢复 <type>`：清除指定类型的剥离和替换规则。\
> `/山海 配方 重载`：从配置文件重新加载剥离和替换规则。\
> `/山海 配方 删除 <type> <recipeId>`：直接删除整个配方，比剥离更彻底。

</Column>

<Column gap="2" fullWidth={true}>

### 警告

> 这是很底层的配方控制，直接覆写 GTCEu 配方层的最终通道。\
> 它能移除一切配方需求，也能抹掉一切配方输出，平衡性影响非常大。\
> 剥离后会永久简化，直到使用恢复加重载命令还原。

</Column>
