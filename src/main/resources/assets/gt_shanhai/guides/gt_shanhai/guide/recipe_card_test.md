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
  recipeType="gt_shanhai:spacetime_distortion"
  recipeId="gt_shanhai:some_recipe_id"
  title="配方标题"
  showEU={true}
  showDuration={true}
/>
```

属性：
- `recipeType` — 配方类型的 ResourceLocation（必填）
- `recipeId` — 配方 ID 的 ResourceLocation（必填）
- `title` — 卡片标题，默认用 recipeId
- `showEU` — 是否显示 EU/t，默认 true
- `showDuration` — 是否显示耗时，默认 true

---

## 错误处理示例（intentional）

以下卡片使用不存在的配方 ID，验证错误提示是否正常显示：

<RecipeCard
  recipeType="gt_shanhai:spacetime_distortion"
  recipeId="gt_shanhai:nonexistent_recipe"
  title="应显示错误"
/>

---

## 真实配方（游戏内确认 ID 后替换）

将下方 `recipeId` 替换为游戏内实际存在的配方 ID，验证正常渲染：

<RecipeCard
  recipeType="gt_shanhai:spacetime_distortion"
  recipeId="gt_shanhai:replace_with_real_id"
  title="时空扭曲配方示例"
  showEU={true}
  showDuration={true}
/>
