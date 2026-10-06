# 配方修改器重制版 Integrate 移植设计

## 目标

在 `master` 当前代码基础上，完整移植 `origin-Integrate/shanhai-rewrite`
中“配方修改器系统”的后端能力，但不直接复制独立重制工程的包名、mod id
或注册结构。移植范围包括：

- 原始配方快照与可重复重建；
- 剥离、替换、删除、启用/禁用和预设规则；
- GTCEu `GTRecipeLookup` 与原版 `RecipeManager` 的同步；
- 运行期缓存失效、样板缓存 revision 与机器侧通知；
- JEI 客户端重算/刷新与网络同步；
- 配方编辑器的服务端/数据层、命令入口和持久化；
- 必要的 Mixin、KubeJS 暴露与启动生命周期接线。

全息 UI（`com.shanhai.client.holo` 及其直接渲染/输入组件）不在本次实现。
本次只提供稳定的后端查询、编辑、提交、回执和网络契约，供另一个工作流接入。

## 现状与约束

### 当前实现

当前 `master` 已有旧版兼容层：

- `api/DShanhaiRecipeModifierAPI.java`
- `api/DShanhaiRecipeEngine.java`
- `api/DShanhaiRuntimeRecipeCache.java`
- `common/recipe/DShanhaiRecipeCache.java`
- `network/RecipeSyncPacket.java`
- `mixin/RecipeModifierAPIMixin.java`
- `mixin/GTRecipeTypeModifierMixin.java`
- `mixin/JEIRecipeListMixin.java`
- `mixin/RecipeIteratorStripMixin.java`
- `GTDishanhaiMod` 的启动加载和服务端重建链

这些类必须保持对现有 KubeJS、JEI、配方 JSON 条件与旧配置文件的兼容。

### 参考实现

`origin-Integrate/shanhai-rewrite` 是独立工程，使用：

- `com.shanhai` 包名；
- mod id `shanhai`；
- 独立的注册入口和 UI 工厂；
- 只读探针、编辑器和全息 UI 混在同一工程中。

源码可能是不完整的，不能假设其每个类都能直接编译。参考实现只作为
行为和边界依据；以当前 `gt_shanhai` 的依赖版本、已存在 API 与可验证源码
为准续写。

### 硬性边界

- 只修改山海署名的源码、测试、资源和本规格/计划文件；
- 不覆盖当前工作区已有未提交改动；
- Java 代码保持 Java 17/当前 Forge 1.20.1 兼容；
- 不把 `com.shanhai` 独立工程的类名和 mod id 直接引入当前模组；
- 不在本次实现全息 UI、投影渲染、输入热区或客户端全息动画；
- 规则变更必须可回滚，`RECIPE_ORIGINALS` 不能被修改。

## 推荐架构

采用“现有 API 兼容层 + 重制版核心语义”的方案。

### 1. 原始快照层

以稳定 recipe id 为键保存首次加载后的不可变原始配方副本。
所有规则重建都从该副本开始，顺序固定为：

1. 规则开关过滤；
2. strip；
3. replace；
4. delete；
5. 去重并重建索引。

原始快照只在配方重载或首次发现时更新，不接受运行期编辑器直接写入。
这样可避免多次应用规则导致数量、时长或输入逐层累积。

### 2. 规则与覆写层

保留 `DShanhaiRecipeModifierAPI` 作为兼容门面，内部拆分职责：

- 规则读取/写回；
- 预设合并与启停；
- 规则规范化与去重；
- 单 recipe 编辑提交；
- 全量重建与 revision；
- KubeJS 和旧脚本调用适配。

重制版编辑器的数据模型不直接暴露 GTCEu 内部对象，而使用：

- `recipeTypeId`
- `recipeId`
- 输入/输出槽位描述
- 流体槽位描述
- duration / EUt
- 条件列表
- 规则来源与版本

提交时再由适配层生成当前版本可接受的 `GTRecipe` 或覆写记录。

### 3. 双表同步层

任何持久化编辑完成后，必须同时更新：

- 原版 `RecipeManager` 的 `byType` 视图；
- GTCEu `GTRecipeType.getLookup()` 的输入索引；
- 运行中的 recipe cache / pattern cache；
- 客户端 JEI recipe manager。

只改 `GTRecipeLookup` 不算完成，因为 `/reload` 会从原版表重新生成索引。
只改原版表也不算完成，因为机器查找走 GTCEu 输入树。

### 4. 缓存 revision 与机器通知

继续使用全局 recipe revision，并扩展为明确的失效原因：

- `rules-changed`
- `recipe-edited`
- `preset-changed`
- `reload`
- `server-start`

revision 递增后：

1. 清理 `DShanhaiRuntimeRecipeCache`；
2. 通知已登记的样板/机器缓存 owner；
3. 清理本地 recipe cache；
4. 标记 JEI 客户端重算；
5. 记录 revision、规则版本和受影响 recipe type。

正在运行的机器不强制中断当前配方；下一次查找/下一轮运行读取新对象。

### 5. JEI 与网络层

保持现有 `RecipeSyncPacket` 注册号和通道兼容，扩展消息内容时使用版本化
payload，旧客户端收到未知字段仍能安全忽略。

服务端提交成功后发送：

- 新 revision；
- 受影响的 recipe type；
- 受影响的 recipe id；
- 是否需要全量刷新。

客户端不信任服务端传来的可执行对象，使用本地 GTRecipe/规则状态重算展示；
服务端只负责权威编辑结果和刷新信号。

### 6. 编辑器后端

从重制版 `common/recipe/editor` 中只移植不依赖 UI 的部分：

- 查询与反向索引；
- recipe fingerprint；
- IO/duration/condition 编解码；
- 覆写存储；
- 提交、回滚、冲突检测；
- 机器/样板通知；
- JEI bridge 的数据接口；
- 自检命令与诊断日志。

`Widget`、`UIFactory`、`Panel`、全息面板和客户端输入不在本次范围。
后端返回结构要能被普通 GUI 和全息 UI 共用。

### 7. 启动生命周期

沿用 `GTDishanhaiMod` 现有时序：

1. 构造阶段加载规则、预设和缓存元数据；
2. GTCEu recipe type 注册完成后建立类型索引；
3. `ServerAboutToStartEvent` 导出/恢复配方缓存；
4. 在完整 recipe manager 可用后执行一次全量重建；
5. 服务器启动完成后发送初始 revision；
6. `/reload` 和编辑器提交都走同一个重建入口。

## 数据流

### 服务器启动

`RecipeManager` 原始配方
→ 快照层
→ 规则合并
→ 生成可用 recipe
→ 更新 `byType`
→ 重建 `GTRecipeLookup`
→ 清理缓存并递增 revision
→ JEI 初始同步。

### 编辑器提交

编辑器提交 DTO
→ 权限与 recipe fingerprint 校验
→ 生成覆写记录
→ 写入配置/存档
→ 从原始快照重新构建目标 recipe type
→ 更新双表与缓存
→ 发送 `RecipeSyncPacket`
→ 返回成功/冲突/失败回执。

### 全息 UI 接口

全息 UI 只依赖以下后端能力：

- 查询 recipe type 列表；
- 查询 recipe 卡片摘要；
- 查询单 recipe 完整编辑模型；
- 提交编辑；
- 撤销/恢复最近一次编辑；
- 查询 revision 和最近一次失败原因；
- 打开普通编辑器或命令入口。

## 错误处理

- recipe id、recipe type、槽位索引和数量必须在服务端再次校验；
- fingerprint 不一致时拒绝覆盖，返回冲突而不是静默合并；
- 任意单条编辑失败时不写入半成品规则；
- 重建失败时保留上一份可用索引，并记录失败 revision；
- 配置文件损坏时回退到空规则，不删除原始快照；
- 网络包解析失败只拒绝当前请求，不影响服务器 recipe manager；
- 兼容旧配置时保留旧字段，并在写回时使用当前规范格式；
- 所有失败日志包含 recipe type、recipe id、revision 和阶段。

## 测试策略

### 源码契约测试

- 原始快照不可变；
- strip/replace/delete 顺序固定且只应用一次；
- 重建后 `byType` 与 `GTRecipeLookup` 都包含同一批可用对象；
- 规则重复提交不会累积修改；
- recipe fingerprint 冲突会拒绝写入；
- revision 递增和 owner 缓存失效只发生一次；
- 旧 `RecipeSyncPacket` 仍可解码；
- 旧 KubeJS API 方法签名仍可调用；
- 配方开关 `defaultEnabled:false` 和现有 JSON 条件继续生效。

### 构建验证

- `gradle clean build --no-daemon`；
- 检查产物中的 mixin 配置、网络类和新增资源；
- 对新增类运行现有 source-contract tests；
- 不把旧 runtime jar、旧 `bin` 或静态反编译文件当成新实现验证。

### 游戏端验证

只有在新 jar 构建成功并核对 hash 后才部署：

1. 启动服务器并确认初始 revision；
2. 查询、编辑、回滚一条 GTCEu 配方；
3. 检查机器查找、AE 样板查询和 JEI 展示；
4. 执行 `/reload` 确认编辑结果不会被抹掉；
5. 检查全息 UI 尚未接入时后端接口仍可独立工作。

## 不在本次范围

- `com.shanhai.client.holo` 的渲染、动画、输入、布局和投影效果；
- 重制版独立工程的 Primordial 机器/模块全量迁移；
- 与配方修改器无关的材质、物品、机器、商店和 AE 性能改动；
- 直接覆盖当前 `master` 的其他未提交工作。
