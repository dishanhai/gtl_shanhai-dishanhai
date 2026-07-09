---
navigation:
  title: RecipeCard 测试
  parent: index.md
  position: 98
categories:
  - gt_shanhai
---

# RecipeCard 组件测试

本页测试 `<RecipeCard>` 自定义标签的渲染效果。按 **F3+T** 重载资源后重新打开。

---

## 用法说明

```
<RecipeCard
  recipeType="gtceu:spacetime_distortion"
  title="配方标题"
  showEU={true}
  showDuration={true}
/>
```

属性：
- `recipeType` — 配方类型的 ResourceLocation（**必填**）。山海配方类型实际注册在 `gtceu` 命名空间下（GTCEu 强制），写 `gt_shanhai:xxx` 会自动兜底为 `gtceu:xxx`。
- `recipeId` — 配方 ID 的 ResourceLocation（**可选**）。留空时自动显示该类型的第一个配方，便于测试/展示。
- `title` — 卡片标题，默认用 recipeId
- `showEU` — 是否显示 EU/t，默认 true
- `showDuration` — 是否显示耗时，默认 true

---

## 真实配方（自动显示第一个配方）

只填 `recipeType`、不填 `recipeId`，自动显示该类型的第一个配方。用多个不同类型验证渲染：

### 时空扭曲

<RecipeCard
  recipeType="gtceu:spacetime_distortion"
  title="时空扭曲（首个配方）"
  showEU={true}
  showDuration={true}
/>

### 混沌合成

<RecipeCard
  recipeType="gtceu:chaos_crafting"
  title="混沌合成（首个配方）"
/>

### 太虚熔炼

<RecipeCard
  recipeType="gtceu:taixu_smelting"
  title="太虚熔炼（首个配方）"
/>

---

## 命名空间兜底测试

下方故意写 `gt_shanhai:` 前缀，验证自动兜底到 `gtceu:`：

<RecipeCard
  recipeType="gt_shanhai:spacetime_distortion"
  title="兜底测试（应正常渲染）"
/>

---

## 错误处理示例（intentional）

以下卡片使用不存在的配方类型，验证错误提示是否正常显示：

<RecipeCard
  recipeType="gtceu:totally_fake_type"
  title="应显示错误"
/>
