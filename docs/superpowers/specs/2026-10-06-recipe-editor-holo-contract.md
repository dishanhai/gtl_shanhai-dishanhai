# 配方编辑器全息 UI 后端契约

## 范围

本契约只描述服务端配方编辑后端。全息 UI 工作流不得直接访问
`GTRecipeLookup`、`RecipeManager` 或规则静态 Map；所有读写都通过以下接口和网络包完成。

## 查询

调用 `ShanhaiRecipeQuery.query(typeFilter, text, page, pageSize)`：

- `typeFilter` 为空表示全部配方类型；
- `text` 同时匹配 recipe id 与 recipe type id；
- `page` 从 0 开始；
- `pageSize` 服务端限制在 1..256；
- 返回 `Result.cards`、`Result.total`、`Result.revision`。

单条详情调用 `ShanhaiRecipeQuery.get(typeId, recipeId)`，不存在时返回空。

## 编辑模型

`ShanhaiRecipeBase` 的 JSON payload 字段固定为：

```text
recipeTypeId
recipeId
duration
eut
inputs
outputs
tickInputs
conditions
```

`inputs`、`outputs`、`tickInputs` 使用 GTCEu `Content.codec` 的原生 JSON 形状。
空对象表示清空该表；未出现在编辑 payload 的字段表示不修改。

## 提交

`RecipeEditorCommitPacket` 必须携带：

- `recipeTypeId`
- `recipeId`
- `baseFingerprint`
- 完整 JSON payload

服务端会重新计算 fingerprint。状态值只有：

- `SUCCESS`
- `CONFLICT`
- `VALIDATION_ERROR`
- `REBUILD_FAILED`
- `PERMISSION_DENIED`

成功提交后，服务端先写原子 override 文件，再从原始快照重建 GTCEu lookup
和 `RecipeManager` 双表，最后递增 revision 并返回 `RecipeEditorResultPacket`。

## 回滚

回滚只删除该 recipe id 的 override 条目，然后重新从原始快照构建。
回滚不会删除 KubeJS 原生 recipe，也不会直接修改全息 UI 状态。

## Revision

- `ShanhaiRecipeQuery.currentRevision()` 返回全局 recipe revision；
- `RecipeSyncPacket` 的 revision 小于客户端缓存 revision 时必须丢弃；
- `fullRefresh=true` 时客户端刷新所有运行期规则类型；
- 否则只刷新 packet 携带的 type id 列表。

## 与全息 UI 的边界

- UI 可以缓存卡片，但必须绑定 revision；
- UI 不得把旧 fingerprint 当成新提交的 fingerprint；
- 服务端拒绝冲突时，UI 应重新查询该 recipe；
- UI 可以把 `RecipeEditorResultPacket.payload` 作为查询结果 JSON，
  但不能执行其中的任意代码；
- 本契约不规定投影布局、动画、面板命中测试或渲染方式。
