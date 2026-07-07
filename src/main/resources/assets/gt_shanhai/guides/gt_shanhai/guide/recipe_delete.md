---
navigation:
  title: 配方删除
  parent: recipe_control_index.md
  position: 40
categories:
  - gt_shanhai
---

# 配方删除

删除规则是按配方 ID 正则直接剔除配方。源码里对应 `DeleteEntry`、`addDeleteRule`、`removeDeleteRules` 和 `deleteRecipesById`。

<Column gap="2" fullWidth={true}>

### 基础原理

> 这个命令直接操作配置文件：`config/gt_shanhai/strip_rules.json` 和 `replace_rules.json`。\
> 与 `/山海 配方 恢复` 不同，恢复清除所有类型的全部规则，而删除配置可以精确到单个配方类型和剥离 / 替换分类。\
> 此操作不可逆。与恢复不同，被删除的规则不会自动保存到预设。如果规则很重要，请先用 `预设 保存` 备份。

</Column>

<Column gap="2" fullWidth={true}>

### 命令格式

> `/山海 配方 删除配置 <type>`\
> 删除该配方类型的全部剥离 + 替换规则。\
> `/山海 配方 删除配置 <type> strip`\
> 只删除剥离规则。\
> `/山海 配方 删除配置 <type> replace`\
> 只删除替换规则。\
> `<type>` 支持简写，比如 `nano_forge` 会自动补 `gtceu:` 前缀。\
> 删除后会同时保存到配置文件，`F3+T` 可刷新 JEI 显示。

</Column>

<Column gap="2" fullWidth={true}>

### 示例

> 删除合金冶炼炉的全部规则：`/山海 配方 删除配置 alloy_blast_smelter`\
> `strip_rules.json` 和 `replace_rules.json` 中对应段会被移除。\
> 只删除剥离规则：`/山海 配方 删除配置 alloy_blast_smelter strip`\
> 只移除 `strip_rules.json` 中的对应段，替换规则保留。\
> 只删除替换规则：`/山海 配方 删除配置 nano_forge replace`\
> 只移除 `replace_rules.json` 中的对应段，剥离规则保留。

</Column>

<Column gap="2" fullWidth={true}>

### 相关命令对比

> `恢复`：清除所有类型的全部规则（strip + replace）。\
> `恢复 <type>`：清除指定类型的全部规则，和 `删除配置 <type>` 等价。\
> `删除配置 <type> strip`：只清除剥离规则。\
> `删除配置 <type> replace`：只清除替换规则。\
> `预设 删除 <name>`：删除预设文件，不是删除规则。

</Column>

<Column gap="2" fullWidth={true}>

### 提示

> 删除前建议先用 `/山海 配方 预设 保存 <name>` 备份当前规则。

</Column>
