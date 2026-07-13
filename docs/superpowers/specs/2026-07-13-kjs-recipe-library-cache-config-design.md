# KJS 配方库缓存配置隔离设计

> 作者：山海恒长在 / dishanhai

## 目标

为启动期 KJS 配方库磁盘缓存增加独立总开关。配置默认关闭；未明确开启时，完整使用 KubeJS/Rhino 原始配方注册流程和原版配方库，不读取、注入或写入缓存数据包。

本开关只控制 `DShanhaiRecipeCache` 与 `DShanhaiRecipePackFinder` 组成的启动期缓存，不影响机器运行期的 `DShanhaiRuntimeRecipeCache`。

## 配置

在 `config/gt_shanhai/gt_shanhai-common.toml` 中新增：

```toml
[kjs_recipe_library_cache]
enabled = false
```

配置注释必须明确说明：

- 该功能存在风险，一般仅用于开发环境加快游戏进入进程。
- 默认关闭；生产环境或正常游玩不建议开启。
- 修改后需重启游戏或服务端生效。

配置尚未完成加载时按关闭处理，避免早期数据包发现阶段意外启用缓存。

## 统一门控

由 `DShanhaiRecipeCache.isEnabled()` 统一读取配置。所有启动期缓存入口复用该方法，不在 KJS 脚本和多个 Java 类中重复读取配置。

关闭时必须满足：

1. `isCacheValid()` 直接返回 `false`，使 `山海的配方库.js` 正常执行 Rhino 注册。
2. `DShanhaiRecipePackFinder.onAddPackFinders()` 直接返回，不把旧缓存目录注入为 SERVER_DATA 数据包。
3. `exportIfNeeded()` 直接返回，不校验 Hash，不读取、清理或写入缓存目录。
4. `DShanhaiRecipeEngine` 不进入“配方库缓存命中”统计分支。

开启时保持现有缓存命中、数据包注入和失效重建行为不变。

## 磁盘数据处理

关闭配置不会删除现有 `dishanhai_recipe_cache/`。缓存文件只是被隔离；以后重新开启时，仍按现有源文件 Hash 判断复用或重建。

## 错误处理

- 配置未加载或不可读取时采用安全默认值 `false`。
- 缓存开启后的校验、导出异常继续沿用现有日志和回退行为。
- 关闭状态不进行缓存文件系统访问，避免旧缓存损坏影响原始配方注册。

## 验证标准

1. 新生成配置中 `kjs_recipe_library_cache.enabled` 默认为 `false`，且包含风险与开发环境用途说明。
2. 默认关闭时 `isCacheValid()` 为 `false`，KJS 不跳过 Rhino 配方注册。
3. 默认关闭时不注入旧缓存数据包，也不导出新缓存。
4. 默认关闭时不显示“配方库缓存命中”统计。
5. 显式开启后现有缓存行为不回归。
6. `DShanhaiRuntimeRecipeCache` 及其诊断配置不受影响。
7. 清理旧 `build` 后完整 Gradle 构建成功。
