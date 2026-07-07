---
navigation:
  title: 配方替换
  parent: recipe_control_index.md
  position: 30
categories:
  - gt_shanhai
---

# 配方替换

替换规则是把配方里的某个输入项换成另一个物品、流体或者电路。源码里对应 `ReplaceEntry`、`addReplaceRule`、`removeReplaceRules` 和 `saveReplaceRules`。

<Column gap="2" fullWidth={true}>

### 基础原理

> 此方法直接修改配方查找树中的物品 / 流体映射，将旧需求替换为新需求。\
> 替换后配方永久生效，直到使用恢复加重载命令还原。\
> 替换不影响配方数据源字段，会保留缓存；如需恢复，使用恢复 + 重载命令即可。

</Column>

<Column gap="2" fullWidth={true}>

### 持久化与路径

> 这是一个持久化配置文件，路径是 `config/gt_shanhai/replace_rules.json`。\
> 此方法会向 JEI 暴露变更后的配方数据。\
> 需要 `F3+T` 重载资源包，或者使用重载命令后，才能看到 JEI 中的修改。实时更新 JEI 过于复杂，暂不支持。

</Column>

<Column gap="2" fullWidth={true}>

### 规则字段

> `oldItem`：被替换的原目标\
> `newItem`：替换后的目标\
> `oldIsFluid`：原目标是否是流体\
> `newIsFluid`：新目标是否是流体\
> `recipeId`：可选的配方 ID 过滤\
> `count`：替换成物品时的数量\
> `circuitNumber`：替换成 `gtceu:programmed_circuit` 时的电路号

</Column>

<Column gap="2" fullWidth={true}>

### 命令格式

> `/山海 配方 替换 <type> <旧物品> <新物品>`\
> `/山海 配方 替换 <type> <旧物品> <新物品> 流体`\
> `/山海 配方 替换 <type> <旧物品> 流体 <新物品> 物品`\
> `/山海 配方 替换 <type> <旧物品> 流体 <新物品> 流体`\
> `/山海 配方 替换 <type> <旧物品> 2x <新物品>`\
> `/山海 配方 替换 不消耗 <type> <旧物品> <新物品>`\
> `/山海 配方 替换 消耗 <type> <旧物品> <新物品>`\
> `/山海 配方 替换 电路 <type> <旧物品> <电路号>`

#### 参数说明

> `<recipeId>` 可选填，不填就作用于该配方类型下的所有配方。\
> 第一个 `流体` 标记旧物品为流体，第二个标记新物品为流体。\
> `Nx` 前缀可指定新物品数量，如 `64x minecraft:oak_log`。\
> `电路` 模式会把物品替换为编程电路板，自动设为不消耗。\
> 替换后需要 `F3+T` 重载资源包，或者重载命令后，才能在 JEI 中看到变更。

</Column>

<Column gap="2" fullWidth={true}>

### 示例

> 物品 → 物品：`/山海 配方 替换 stellar_forge gtceu:copper_ingot gtceu:iron_ingot`\
> 将 `stellar_forge` 所有配方中的铜锭替换为铁锭。\
> 物品 → 流体：`/山海 配方 替换 stellar_forge minecraft:water 流体 gtceu:lava 流体`\
> 将 `stellar_forge` 所有配方中的水替换为熔岩。\
> 指定数量：`/山海 配方 替换 stellar_forge dishanhai:god_forge_mod 64x minecraft:oak_log`\
> 替换为 64 个橡木原木。\
> 替换为电路：`/山海 配方 替换 电路 stellar_forge dishanhai:god_forge_mod 2`\
> 替换为 2 号编程电路，自动不消耗。\
> 不消耗模式：`/山海 配方 替换 不消耗 stellar_forge dishanhai:god_forge_mod minecraft:oak_log`\
> 替换后的物品不会在配方中被消耗。

</Column>

<Column gap="2" fullWidth={true}>

### 常见行为

> 物品可以换成物品，也可以换成流体。\
> 流体也可以作为原目标或新目标。\
> 同一类型可以叠加多条替换规则。

</Column>

<Column gap="2" fullWidth={true}>

### 恢复与重载

> `/山海 配方 恢复`：清除所有替换与剥离规则。\
> `/山海 配方 恢复 <type>`：清除指定类型的规则。\
> `/山海 配方 重载`：从配置文件重新加载规则。

</Column>
