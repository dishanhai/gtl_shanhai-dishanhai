# 配方修改器重制版 Integrate 移植 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留现有 KubeJS/API/配置兼容性的前提下，把重制版 Integrate 的配方修改器后端、双表重建、编辑器数据层、JEI 同步与持久化链移植到 `gt_shanhai`，并为独立全息 UI 工作流提供稳定接口。

**Architecture:** 以 `DShanhaiRecipeModifierAPI` 为兼容门面，新增不可变原始快照、版本化覆写存储和统一重建服务；所有 strip/replace/delete/toggle 先从原始快照生成 canonical recipe，再同时写回 `RecipeManager.byType` 与 GTCEu lookup。编辑器、命令和全息 UI 都只调用 DTO/服务层，不直接持有 GTCEu 内部索引。

**Tech Stack:** Forge 1.20.1、GTCEu 1.4.4、GTLCore、Java 17/21 编译环境、Mixin 0.8.5、Gson、JEI 15.49、JUnit source-contract tests、Gradle 8.8。

---

## 文件边界

### 现有文件修改

- `src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPI.java`
  - 保留旧公开方法；改为委托 snapshot/rule/rebuild 服务。
- `src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeKJSAPI.java`
  - 保持 KubeJS 参数和返回值不变，统一走新的提交/重建入口。
- `src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRuntimeRecipeCache.java`
  - 增加 recipe revision 与 type revision 的一致性检查。
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/DShanhaiRecipeCache.java`
  - 让缓存导出使用 canonical recipe，而不是修改后的临时对象。
- `src/main/java/com/dishanhai/gt_shanhai/GTDishanhaiMod.java`
  - 接入统一启动、reload、编辑提交后的 rebuild 生命周期。
- `src/main/java/com/dishanhai/gt_shanhai/network/RecipeSyncPacket.java`
  - 增加版本化刷新 payload，兼容空 payload 旧包。
- `src/main/java/com/dishanhai/gt_shanhai/network/ShanhaiNetwork.java`
  - 保持现有注册号，新增编辑器请求/回执包。
- `src/main/java/com/dishanhai/gt_shanhai/mixin/RecipeModifierAPIMixin.java`
  - 把查找入口切换到 canonical lookup recipe。
- `src/main/java/com/dishanhai/gt_shanhai/mixin/GTRecipeTypeModifierMixin.java`
  - 接入 type 级 recipe cache/revision。
- `src/main/java/com/dishanhai/gt_shanhai/mixin/JEIRecipeListMixin.java`
  - 改为使用客户端同步状态和最终展示集合。
- `src/main/resources/gt_shanhai.mixin.json`
  - 只登记新增且已通过 source-contract 的 Mixin。

### 新增后端文件

- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeOriginalSnapshotStore.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildService.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRuleState.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRuleCodec.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeEditResult.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeBase.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeConditions.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeDuration.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeFingerprint.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeIoApply.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeOverrideStore.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeQuery.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeReverseIndex.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeEditorOps.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeMachineNotify.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiJeiBridge.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiJeiSyncPlan.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiVanillaRecipeView.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiVanillaRecipeTable.java`
- `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiVanillaRecipeRebuild.java`
- `src/main/java/com/dishanhai/gt_shanhai/command/ShanhaiRecipeEditorCommand.java`
- `src/main/java/com/dishanhai/gt_shanhai/network/RecipeEditorQueryPacket.java`
- `src/main/java/com/dishanhai/gt_shanhai/network/RecipeEditorCommitPacket.java`
- `src/main/java/com/dishanhai/gt_shanhai/network/RecipeEditorResultPacket.java`

### 新增测试文件

- `src/test/java/com/dishanhai/gt_shanhai/common/recipe/RecipeOriginalSnapshotStoreTest.java`
- `src/test/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildServiceSourceTest.java`
- `src/test/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRuleCodecTest.java`
- `src/test/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeFingerprintTest.java`
- `src/test/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeEditorOpsSourceTest.java`
- `src/test/java/com/dishanhai/gt_shanhai/network/RecipeEditorPacketCodecTest.java`
- `src/test/java/com/dishanhai/gt_shanhai/mixin/RecipeModifierIntegrationSourceTest.java`

### 明确不修改

- `src/main/java/com/dishanhai/gt_shanhai/client/holo/`
- `src/main/java/com/dishanhai/gt_shanhai/common/holo/`
- `src/main/java/com/shanhai/...` 独立重制工程
- `origin-Integrate/shanhai-rewrite/`
- 当前工作区已有与本任务无关的 Primordial、商店、AE 或材质改动。

## Task 1: 锁定旧 API 与原始快照契约

**Files:**
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeOriginalSnapshotStore.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeEditResult.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPI.java:1035-1148`
- Test: `src/test/java/com/dishanhai/gt_shanhai/common/recipe/RecipeOriginalSnapshotStoreTest.java`
- Test: `src/test/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPITest.java`

- [ ] **Step 1: Write the failing snapshot tests**

```java
@Test
void snapshotIsCopiedAndNeverMutatedByLaterRuleApplication() {
    GTRecipe original = fixtureRecipe("dishanhai:test", 100, "minecraft:stone");
    RecipeOriginalSnapshotStore.capture("gtceu:assembler", original);
    GTRecipe copy = RecipeOriginalSnapshotStore.copyOf("gtceu:assembler", "dishanhai:test");

    assertNotSame(original, copy);
    assertEquals(100, RecipeOriginalSnapshotStore
            .copyOf("gtceu:assembler", "dishanhai:test").duration);
}

@Test
void captureDeduplicatesByStableRecipeId() {
    RecipeOriginalSnapshotStore.capture("gtceu:assembler", fixtureRecipe("dishanhai:test", 100, "minecraft:stone"));
    RecipeOriginalSnapshotStore.capture("gtceu:assembler", fixtureRecipe("dishanhai:test", 200, "minecraft:dirt"));

    assertEquals(1, RecipeOriginalSnapshotStore.ids("gtceu:assembler").size());
    assertEquals(100, RecipeOriginalSnapshotStore
            .copyOf("gtceu:assembler", "dishanhai:test").duration);
}
```

- [ ] **Step 2: Run the focused test and verify the expected failure**

Run:

```powershell
.\gradle-install\gradle-8.8\bin\gradle test --no-daemon `
  --tests com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStoreTest
```

Expected: compilation/test failure because `RecipeOriginalSnapshotStore` does not exist.

- [ ] **Step 3: Implement the snapshot store**

Implement these exact operations:

```java
public static void capture(String recipeTypeId, GTRecipe recipe);
public static GTRecipe copyOf(String recipeTypeId, String recipeId);
public static List<GTRecipe> copiesOf(String recipeTypeId);
public static Set<String> ids(String recipeTypeId);
public static void clear(String recipeTypeId);
public static void clearAll();
```

Use a `LinkedHashMap<String, LinkedHashMap<String, GTRecipe>>`, copy the recipe before
storing, ignore a second capture of the same `(type,id)`, and never expose the stored
object directly. `copyOf` and `copiesOf` must return fresh copies.

- [ ] **Step 4: Route existing canonical fields through the store**

Replace the direct writes to `RECIPE_ORIGINALS` in
`DShanhaiRecipeModifierAPI` with `RecipeOriginalSnapshotStore.capture(...)`.
Keep `prepareLookupRecipe`, `prepareJeiRecipe`, `findLookupRecipeById`,
`clearOriginalSnapshot` and `getDisabledRecipeIds` source-compatible.

- [ ] **Step 5: Run the snapshot and existing API tests**

Expected: the new snapshot tests pass and `DShanhaiRecipeModifierAPITest` still passes
without changing any public method signature.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeOriginalSnapshotStore.java `
  src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeEditResult.java `
  src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPI.java `
  src/test/java/com/dishanhai/gt_shanhai/common/recipe/RecipeOriginalSnapshotStoreTest.java `
  src/test/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPITest.java
git commit -m "feat(recipe): 建立原始配方快照层"
```

## Task 2: 统一规则状态、编解码与 canonical rebuild

**Files:**
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRuleState.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRuleCodec.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildService.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPI.java:340-1027,1363-1710`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/common/recipe/DShanhaiRecipeCache.java:177-333`
- Test: `src/test/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRuleCodecTest.java`
- Test: `src/test/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildServiceSourceTest.java`

- [ ] **Step 1: Write rule-order and round-trip tests**

```java
@Test
void codecRoundTripPreservesStripReplaceDeleteAndToggleFields() {
    RecipeRuleState state = RecipeRuleState.builder()
            .addStrip("gtceu:assembler", "minecraft:stone", true, false, "test")
            .addReplace("gtceu:assembler", "minecraft:stone", "minecraft:dirt", false, false, "test")
            .addDelete("gtceu:assembler", "dishanhai:old", "test")
            .setToggle("dishanhai:test", false)
            .build();

    assertEquals(state, RecipeRuleCodec.fromJson(RecipeRuleCodec.toJson(state)));
}

@Test
void rebuildOrderIsStripThenReplaceThenDeleteAndStartsFromOriginals() {
    String source = Files.readString(Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildService.java"));

    assertTrue(source.indexOf("applyStrip") < source.indexOf("applyReplace"));
    assertTrue(source.indexOf("applyReplace") < source.indexOf("isDeleted"));
    assertTrue(source.contains("RecipeOriginalSnapshotStore.copiesOf"));
    assertTrue(source.contains("removeAllRecipes"));
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
.\gradle-install\gradle-8.8\bin\gradle test --no-daemon `
  --tests com.dishanhai.gt_shanhai.common.recipe.RecipeRuleCodecTest `
  --tests com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildServiceSourceTest
```

Expected: failure because the rule state/codec/rebuild service do not exist.

- [ ] **Step 3: Implement `RecipeRuleState` and `RecipeRuleCodec`**

`RecipeRuleState` must hold ordered maps for strip, replace, delete, toggles and active
presets. `RecipeRuleCodec` must read/write the existing files:

```text
config/gt_shanhai/strip_rules.json
config/gt_shanhai/replace_rules.json
config/gt_shanhai/delete_rules.json
config/gt_shanhai/recipe_toggles.json
config/gt_shanhai/active_presets.json
```

The codec must accept the current legacy field names used by
`DShanhaiRecipeModifierAPI` and emit the current names without losing `source`,
`item`, `fluid`, `output`, `matchOutput`, `regex`, or `removeEmpty`.

- [ ] **Step 4: Implement `RecipeRebuildService.rebuildType`**

Expose these methods:

```java
public static RebuildReport rebuildType(String recipeTypeId, RebuildReason reason);
public static RebuildReport rebuildAll(RebuildReason reason);
public static GTRecipe buildCanonical(String recipeTypeId, GTRecipe original);
public static void rebuildVanillaManager(MinecraftServer server, Set<String> typeIds);
```

`buildCanonical` must:

1. start from a fresh original snapshot copy;
2. skip disabled recipe ids;
3. call `DShanhaiRecipeModifierAPI.applyStripByType`;
4. call `DShanhaiRecipeModifierAPI.applyReplaceByType`;
5. skip `isDeletedByRuntimeRule`;
6. deduplicate by stable id;
7. return a fresh object.

`rebuildType` must clear the target `GTRecipeLookup`, add only canonical objects, update
the corresponding `RecipeManager.byType` list through the existing reflection helper,
invalidate pattern caches once, and return counts for captured/kept/dropped/replaced.

- [ ] **Step 5: Replace duplicated rebuild paths**

Make `updateAllLookupRecipes`, `applyAllReplaceRules`, `applyPersistedRecipeToggles`,
`removeAndSync`, and `DShanhaiRecipeCache.exportIfNeeded` delegate to the same service.
No method may call `lookup.removeAllRecipes()` and then reapply rules from an already
modified object.

- [ ] **Step 6: Run tests and inspect the diff**

Run:

```powershell
.\gradle-install\gradle-8.8\bin\gradle test --no-daemon `
  --tests com.dishanhai.gt_shanhai.common.recipe.RecipeRuleCodecTest `
  --tests com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildServiceSourceTest `
  --tests com.dishanhai.gt_shanhai.api.DShanhaiRuntimeRecipeCacheTest
git diff --check
```

Expected: tests pass; `git diff --check` prints no output.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/dishanhai/gt_shanhai/common/recipe `
  src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPI.java `
  src/test/java/com/dishanhai/gt_shanhai/common/recipe
git commit -m "feat(recipe): 统一规则状态与原始配方重建"
```

## Task 3: 接入 revision、运行期缓存和 Mixin 查找边界

**Files:**
- Modify: `src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRuntimeRecipeCache.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeModifierAPI.java:1711-1818`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/mixin/RecipeModifierAPIMixin.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/mixin/GTRecipeTypeModifierMixin.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/mixin/RecipeIteratorStripMixin.java`
- Test: `src/test/java/com/dishanhai/gt_shanhai/mixin/RecipeModifierIntegrationSourceTest.java`
- Test: `src/test/java/com/dishanhai/gt_shanhai/api/DShanhaiRuntimeRecipeCacheTest.java`

- [ ] **Step 1: Add failing source-contract assertions**

```java
@Test
void lookupBoundaryUsesCanonicalRevisionAndDoesNotReapplyModifiedRecipe() {
    String api = read("api/DShanhaiRecipeModifierAPI.java");
    String mixin = read("mixin/RecipeModifierAPIMixin.java");

    assertTrue(api.contains("RecipeRebuildService.rebuildType"));
    assertTrue(api.contains("PATTERN_CACHE_REVISION.incrementAndGet"));
    assertTrue(mixin.contains("prepareLookupRecipe"));
    assertFalse(mixin.contains("applyStripByType(recipe); applyStripByType(recipe)"));
}

@Test
void runtimeCacheKeyContainsPatternRevision() {
    Object key = newKey("gtceu:assembler", "items=a", "fluids=",
            DShanhaiRecipeModifierAPI.getPatternCacheRevision(), "findRecipe");
    assertNotNull(key);
}
```

- [ ] **Step 2: Run the focused tests and confirm missing integration**

Run:

```powershell
.\gradle-install\gradle-8.8\bin\gradle test --no-daemon `
  --tests com.dishanhai.gt_shanhai.mixin.RecipeModifierIntegrationSourceTest `
  --tests com.dishanhai.gt_shanhai.api.DShanhaiRuntimeRecipeCacheTest
```

Expected: the new source assertions fail until the rebuild/revision path is wired.

- [ ] **Step 3: Add explicit type revision and batch invalidation**

Keep the existing global `getPatternCacheRevision()` API. Add:

```java
public static long getRecipeRevision();
public static long getRecipeTypeRevision(String recipeTypeId);
public static void invalidateRecipeCaches(String reason, Set<String> typeIds);
```

Increment the global revision once per outer batch, increment each affected type once,
clear `DShanhaiRuntimeRecipeCache`, then notify registered owners. Nested calls must not
increment twice.

- [ ] **Step 4: Update Mixin boundaries**

`RecipeModifierAPIMixin` must call `prepareLookupRecipe` only for the first canonical
lookup insertion. `GTRecipeTypeModifierMixin` must use the type revision to reject stale
cached recipe lists. `RecipeIteratorStripMixin` must remain a display/compatibility
fallback and must not be the only runtime enforcement path.

- [ ] **Step 5: Verify source contracts**

Run the two focused tests again. Expected: PASS, with no duplicate `removeAllRecipes`
or rule application in the same rebuild path.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/dishanhai/gt_shanhai/api `
  src/main/java/com/dishanhai/gt_shanhai/mixin `
  src/test/java/com/dishanhai/gt_shanhai/mixin `
  src/test/java/com/dishanhai/gt_shanhai/api/DShanhaiRuntimeRecipeCacheTest.java
git commit -m "feat(recipe): 接入配方 revision 与缓存失效边界"
```

## Task 4: 移植编辑器数据层，不引入 UI

**Files:**
- Create the backend files listed under `common/recipe/editor` in the file boundary.
- Create: `src/test/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeFingerprintTest.java`
- Create: `src/test/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeEditorOpsSourceTest.java`
- Reference only: `origin-Integrate/shanhai-rewrite/src/main/java/com/shanhai/common/recipe/editor/*.java`

- [ ] **Step 1: Write DTO/fingerprint tests**

```java
@Test
void fingerprintChangesWhenDurationOrIoChanges() {
    ShanhaiRecipeBase base = fixture("dishanhai:test", 100, "minecraft:stone", 1);
    String first = ShanhaiRecipeFingerprint.of(base);
    String second = ShanhaiRecipeFingerprint.of(base.withDuration(200));
    String third = ShanhaiRecipeFingerprint.of(base.withItemAmount(2));

    assertNotEquals(first, second);
    assertNotEquals(first, third);
}

@Test
void commitRejectsStaleFingerprintWithoutWritingOverride() {
    ShanhaiRecipeOverrideStore store = new ShanhaiRecipeOverrideStore(tempPath());
    ShanhaiRecipeEditorOps ops = new ShanhaiRecipeEditorOps(store);
    ShanhaiRecipeEditorOps.Result result = ops.commit(
            new ShanhaiRecipeEditorOps.Edit(
                    fixture("dishanhai:test", 100, "minecraft:stone", 1),
                    "stale-fingerprint"));

    assertEquals(ShanhaiRecipeEditorOps.Result.Status.CONFLICT, result.status());
    assertTrue(store.find("dishanhai:test").isEmpty());
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\gradle-install\gradle-8.8\bin\gradle test --no-daemon `
  --tests com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeFingerprintTest `
  --tests com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorOpsSourceTest
```

Expected: failure because the backend editor classes do not exist.

- [ ] **Step 3: Port the data-only model**

Implement these methods without any `net.minecraft.client` or `Widget` import:

```java
public record ShanhaiRecipeBase(
        String recipeTypeId,
        String recipeId,
        ShanhaiRecipeIoApply.IoTable io,
        ShanhaiRecipeDuration duration,
        List<ShanhaiRecipeConditions.Value> conditions,
        String fingerprint) {}

public static ShanhaiRecipeBase from(GTRecipe recipe);
public static GTRecipe toGtRecipe(ShanhaiRecipeBase base, GTRecipe original);
public static String of(ShanhaiRecipeBase base);
```

The fingerprint must hash a canonical JSON representation with sorted object keys and
stable slot order. `ShanhaiRecipeIoApply` must validate item/fluid capability, amount > 0,
and reject unknown IO side values.

- [ ] **Step 4: Port query, reverse index and override persistence**

`ShanhaiRecipeQuery` must expose:

```java
public static List<Card> query(String typeFilter, String text, int page, int pageSize);
public static Optional<ShanhaiRecipeBase> get(String typeId, String recipeId);
public static long currentRevision();
```

`ShanhaiRecipeReverseIndex` must index both item/fluid inputs and outputs. Use the current
canonical snapshot as the source; do not scan `GTRecipeLookup` after a rule mutation.

`ShanhaiRecipeOverrideStore` must atomically write a temp file, replace the target file,
keep the last valid document on parse failure, and store `baseFingerprint`, `updatedAt`,
`updatedBy`, and the edited recipe payload.

- [ ] **Step 5: Port editor operations and machine notifications**

`ShanhaiRecipeEditorOps` must implement:

```java
Result preview(Edit edit);
Result commit(Edit edit);
Result rollback(String typeId, String recipeId);
Result undo(String typeId, String recipeId);
```

`commit` must validate fingerprint, update the override store, invoke
`RecipeRebuildService.rebuildType`, call `ShanhaiRecipeMachineNotify`, and return the new
global/type revision. On any failure it must leave the previous override and index intact.

- [ ] **Step 6: Verify UI separation**

Run:

```powershell
rg -n "net\\.minecraft\\.client|Widget|UIFactory|Screen|holo" `
  src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor
```

Expected: no output. This guarantees the independent holographic workflow can consume
the backend without importing client-only classes.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor `
  src/test/java/com/dishanhai/gt_shanhai/common/recipe/editor
git commit -m "feat(recipe): 移植配方编辑器数据层"
```

## Task 5: 统一原版 RecipeManager、GTCEu lookup 和缓存导出

**Files:**
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiVanillaRecipeView.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiVanillaRecipeTable.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiVanillaRecipeRebuild.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/common/misc/RecipeManagerReflectionUtil.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/common/recipe/DShanhaiRecipeCache.java`
- Test: `src/test/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildServiceSourceTest.java`

- [ ] **Step 1: Add the manager/index consistency test**

```java
@Test
void rebuildServiceUpdatesBothManagerTableAndGtLookup() {
    String source = read("common/recipe/RecipeRebuildService.java");
    assertTrue(source.contains("ShanhaiVanillaRecipeRebuild.rebuild"));
    assertTrue(source.contains("recipeType.getLookup().removeAllRecipes()"));
    assertTrue(source.contains("recipeType.getLookup().addRecipe"));
    assertTrue(source.contains("RecipeManagerReflectionUtil"));
}
```

- [ ] **Step 2: Run the test and confirm the current gap**

Run the existing source-contract selector. Expected: failure until the new vanilla table
adapter is connected.

- [ ] **Step 3: Implement the vanilla table adapter**

`ShanhaiVanillaRecipeTable` must expose:

```java
public static List<Recipe<?>> read(MinecraftServer server, String typeId);
public static void replace(MinecraftServer server, String typeId, List<? extends Recipe<?>> recipes);
```

Use the existing reflection utility and preserve map/list identity rules required by
Minecraft 1.20.1. Never mutate the stored original snapshot while replacing the manager
list.

- [ ] **Step 4: Update cache export**

Make `DShanhaiRecipeCache.exportIfNeeded` read from the canonical rebuilt manager table
after `RecipeRebuildService.rebuildAll`. Exported JSON must represent the currently
enabled canonical recipe set and must not contain stale pre-edit objects.

- [ ] **Step 5: Run source checks and JSON sanity checks**

Run:

```powershell
.\gradle-install\gradle-8.8\bin\gradle test --no-daemon `
  --tests com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildServiceSourceTest
git diff --check
```

Expected: PASS and no whitespace errors.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/dishanhai/gt_shanhai/common/misc/RecipeManagerReflectionUtil.java `
  src/main/java/com/dishanhai/gt_shanhai/common/recipe `
  src/test/java/com/dishanhai/gt_shanhai/common/recipe/RecipeRebuildServiceSourceTest.java
git commit -m "feat(recipe): 同步原版配方表与 GTCEu 索引"
```

## Task 6: 版本化 RecipeSyncPacket 与 JEI 刷新

**Files:**
- Create: `src/main/java/com/dishanhai/gt_shanhai/network/RecipeEditorQueryPacket.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/network/RecipeEditorCommitPacket.java`
- Create: `src/main/java/com/dishanhai/gt_shanhai/network/RecipeEditorResultPacket.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/network/RecipeSyncPacket.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/network/ShanhaiNetwork.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/mixin/JEIRecipeListMixin.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/client/ShanhaiJEIPlugin.java`
- Test: `src/test/java/com/dishanhai/gt_shanhai/network/RecipeEditorPacketCodecTest.java`
- Test: `src/test/java/com/dishanhai/gt_shanhai/jei/JeiPatternQuickEncodeIntegrationSourceTest.java`

- [ ] **Step 1: Write codec compatibility tests**

```java
@Test
void recipeSyncPacketRoundTripsAffectedTypesAndIds() {
    RecipeSyncPacket packet = new RecipeSyncPacket(
            7L,
            List.of("gtceu:assembler"),
            List.of("dishanhai:test"),
            false);

    FriendlyByteBuf buf = buffer();
    RecipeSyncPacket.encode(packet, buf);
    RecipeSyncPacket decoded = RecipeSyncPacket.decode(buf);

    assertEquals(packet.revision(), decoded.revision());
    assertEquals(packet.typeIds(), decoded.typeIds());
    assertEquals(packet.recipeIds(), decoded.recipeIds());
}

@Test
void emptyLegacyPayloadStillDecodes() {
    RecipeSyncPacket decoded = RecipeSyncPacket.decode(Unpooled.buffer(0));
    assertNotNull(decoded);
}
```

- [ ] **Step 2: Run codec tests and confirm RED**

Expected: constructor/accessor/codec assertions fail until the packet is versioned.

- [ ] **Step 3: Implement the versioned packet**

Use a payload version byte, revision `long`, `fullRefresh` boolean, bounded type/id list
lengths, and UTF-8 strings. If the buffer is empty, decode an empty legacy packet.
Reject list sizes above 4096 and strings above 512 characters.

- [ ] **Step 4: Add editor request/result packets**

`RecipeEditorQueryPacket` carries `typeFilter`, `text`, `page`, `pageSize`.
`RecipeEditorCommitPacket` carries `typeId`, `recipeId`, `baseFingerprint`, and the
canonical edit JSON.
`RecipeEditorResultPacket` carries status enum, message key, revision, and optional card
JSON. Server handlers must validate sender permission and execute on the server thread.

- [ ] **Step 5: Refresh JEI at the final collection boundary**

`JEIRecipeListMixin` must use `ShanhaiJeiBridge`/`ShanhaiJeiSyncPlan` to remove affected
old recipes before adding rebuilt recipes. Do not rely on `RecipeIteratorStripMixin` as the
only JEI filter. The client must discard stale revisions and request a full refresh when
`fullRefresh=true`.

- [ ] **Step 6: Run packet and JEI source tests**

```powershell
.\gradle-install\gradle-8.8\bin\gradle test --no-daemon `
  --tests com.dishanhai.gt_shanhai.network.RecipeEditorPacketCodecTest `
  --tests com.dishanhai.gt_shanhai.jei.JeiPatternQuickEncodeIntegrationSourceTest
```

Expected: PASS, or an environment-only Gradle loopback failure that must be recorded in
`.learnings/ERRORS.md` without being mistaken for a source failure.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/dishanhai/gt_shanhai/network `
  src/main/java/com/dishanhai/gt_shanhai/mixin/JEIRecipeListMixin.java `
  src/main/java/com/dishanhai/gt_shanhai/client/ShanhaiJEIPlugin.java `
  src/test/java/com/dishanhai/gt_shanhai/network `
  src/test/java/com/dishanhai/gt_shanhai/jei/JeiPatternQuickEncodeIntegrationSourceTest.java
git commit -m "feat(recipe): 版本化配方同步与 JEI 刷新"
```

## Task 7: 接入启动生命周期、命令和 KubeJS

**Files:**
- Create: `src/main/java/com/dishanhai/gt_shanhai/command/ShanhaiRecipeEditorCommand.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/GTDishanhaiMod.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/command/DShanhaiCommands.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/GTDishanhaiKubeJSPlugin.java`
- Modify: `src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeKJSAPI.java`
- Modify: `src/main/resources/gt_shanhai.mixin.json`
- Test: `src/test/java/com/dishanhai/gt_shanhai/common/recipe/RecipeLifecycleSourceTest.java`

- [ ] **Step 1: Add lifecycle source assertions**

```java
@Test
void startupLoadsRulesBeforeTheFirstLookupRebuild() {
    String source = read("GTDishanhaiMod.java");
    assertTrue(source.indexOf("loadStripRules") < source.indexOf("RecipeRebuildService"));
    assertTrue(source.contains("ServerAboutToStartEvent"));
    assertTrue(source.contains("RecipeRebuildService.rebuildAll"));
}

@Test
void kubejsFacadeKeepsLegacyMethodNames() {
    String source = read("api/DShanhaiRecipeKJSAPI.java");
    assertTrue(source.contains("reloadStripRules"));
    assertTrue(source.contains("replaceBatch"));
    assertTrue(source.contains("setRecipeEnabled"));
}
```

- [ ] **Step 2: Implement one shared lifecycle entry**

Add a single method:

```java
public static RecipeRebuildService.RebuildReport rebuildForLifecycle(
        MinecraftServer server, RecipeRebuildService.RebuildReason reason);
```

Use it from `ServerAboutToStartEvent`, `/reload` handling, editor commit and KubeJS
reload methods. Avoid separate calls to `updateAllLookupRecipes` and
`applyAllReplaceRules` that can double-apply rules.

- [ ] **Step 3: Add command surface**

Register:

```text
/山海 配方编辑 查询 <文字>
/山海 配方编辑 查看 <类型> <配方ID>
/山海 配方编辑 重建 [类型]
/山海 配方编辑 回滚 <类型> <配方ID>
/山海 配方编辑 自检
```

Each command must return a translated success/failure component containing revision,
recipe type, recipe id and failure stage when applicable.

- [ ] **Step 4: Expose backend objects to KubeJS**

Add only server-safe classes to `GTDishanhaiKubeJSPlugin`:

```java
event.add("ShanhaiRecipeQuery", ShanhaiRecipeQuery.class);
event.add("ShanhaiRecipeEditorOps", ShanhaiRecipeEditorOps.class);
event.add("ShanhaiRecipeRebuild", RecipeRebuildService.class);
```

Do not expose `Widget`, `Screen`, `ShanhaiHoloMenuRenderer` or any client package.

- [ ] **Step 5: Run lifecycle source checks**

```powershell
.\gradle-install\gradle-8.8\bin\gradle test --no-daemon `
  --tests com.dishanhai.gt_shanhai.common.recipe.RecipeLifecycleSourceTest
```

Expected: PASS; verify `gt_shanhai.mixin.json` contains every new Mixin exactly once.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/dishanhai/gt_shanhai/GTDishanhaiMod.java `
  src/main/java/com/dishanhai/gt_shanhai/GTDishanhaiKubeJSPlugin.java `
  src/main/java/com/dishanhai/gt_shanhai/command `
  src/main/java/com/dishanhai/gt_shanhai/api/DShanhaiRecipeKJSAPI.java `
  src/main/resources/gt_shanhai.mixin.json `
  src/test/java/com/dishanhai/gt_shanhai/common/recipe/RecipeLifecycleSourceTest.java
git commit -m "feat(recipe): 接入编辑命令与统一生命周期"
```

## Task 8: 固定全息 UI 后端契约并交付独立工作流

**Files:**
- Create: `docs/superpowers/specs/2026-10-06-recipe-editor-holo-contract.md`
- Create: `src/test/java/com/dishanhai/gt_shanhai/common/recipe/editor/RecipeEditorBackendContractSourceTest.java`
- Modify: none under `src/main/java/com/dishanhai/gt_shanhai/client/holo/`

- [ ] **Step 1: Write the contract test**

```java
@Test
void backendContractHasQueryPreviewCommitRollbackAndRevision() {
    assertTrue(hasMethod("common.recipe.editor.ShanhaiRecipeQuery", "query"));
    assertTrue(hasMethod("common.recipe.editor.ShanhaiRecipeEditorOps", "preview"));
    assertTrue(hasMethod("common.recipe.editor.ShanhaiRecipeEditorOps", "commit"));
    assertTrue(hasMethod("common.recipe.editor.ShanhaiRecipeEditorOps", "rollback"));
    assertTrue(hasMethod("common.recipe.editor.ShanhaiRecipeQuery", "currentRevision"));
}
```

- [ ] **Step 2: Write the contract document**

Document the exact DTO fields, packet IDs, status values
(`SUCCESS`, `CONFLICT`, `VALIDATION_ERROR`, `REBUILD_FAILED`, `PERMISSION_DENIED`),
revision semantics, and the fact that the UI must not call GTCEu lookup directly.
Reference `docs/superpowers/specs/2026-10-06-holo-ui-preview-design.md` only for visual
states; keep Java rendering out of this plan.

- [ ] **Step 3: Run the contract test**

Expected: PASS after Tasks 1-7. If it fails, fix backend public signatures before handing
the contract to the independent holographic UI workflow.

- [ ] **Step 4: Commit**

```powershell
git add -f docs/superpowers/specs/2026-10-06-recipe-editor-holo-contract.md
git add src/test/java/com/dishanhai/gt_shanhai/common/recipe/editor/RecipeEditorBackendContractSourceTest.java
git commit -m "docs(recipe): 固定全息编辑器后端契约"
```

## Task 9: 全量验证、打包与部署前审查

**Files:**
- Modify only files that fail the focused verification.
- Test: all recipe modifier tests created in Tasks 1-8.

- [ ] **Step 1: Run focused source-contract suite**

```powershell
.\gradle-install\gradle-8.8\bin\gradle test --no-daemon `
  --tests com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPITest `
  --tests com.dishanhai.gt_shanhai.api.DShanhaiRuntimeRecipeCacheTest `
  --tests com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStoreTest `
  --tests com.dishanhai.gt_shanhai.common.recipe.RecipeRuleCodecTest `
  --tests com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildServiceSourceTest `
  --tests com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeFingerprintTest `
  --tests com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorOpsSourceTest `
  --tests com.dishanhai.gt_shanhai.network.RecipeEditorPacketCodecTest `
  --tests com.dishanhai.gt_shanhai.mixin.RecipeModifierIntegrationSourceTest `
  --tests com.dishanhai.gt_shanhai.common.recipe.RecipeLifecycleSourceTest `
  --tests com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorBackendContractSourceTest
```

Expected: all selected tests pass. If Gradle fails before test discovery with
`Unable to establish loopback connection`, record the exact error in
`C:\Users\dishanhai\Desktop\ide专属文件\.learnings\ERRORS.md` and use source-contract
checks only; do not call the test suite passed.

- [ ] **Step 2: Run whitespace and forbidden-import checks**

```powershell
git diff --check
rg -n "com\\.shanhai\\.|client\\.holo|common\\.holo|Widget|UIFactory|Screen" `
  src/main/java/com/dishanhai/gt_shanhai/common/recipe `
  src/main/java/com/dishanhai/gt_shanhai/api `
  src/main/java/com/dishanhai/gt_shanhai/network
```

Expected: no `com.shanhai` imports and no client UI imports in server/editor backend.

- [ ] **Step 3: Run the clean build**

```powershell
.\gradle-install\gradle-8.8\bin\gradle clean build --no-daemon
```

Expected: `BUILD SUCCESSFUL`. If Forge mapped artifacts or loopback prevent execution,
preserve the source and report the build as unverified.

- [ ] **Step 4: Inspect the new jar before deployment**

```powershell
$jar = "build/libs/gt_shanhai.jar"
Get-FileHash -Algorithm SHA256 $jar
jar tf $jar | Select-String "Recipe(Rebuild|Original|Editor|Sync)|gt_shanhai.mixin.json"
```

Expected: the jar contains the new backend classes, packet classes and mixin config.
Do not copy a failed or stale jar into the game instance.

- [ ] **Step 5: Commit only after verification**

```powershell
git status --short
git log -1 --oneline
```

Expected: no unexpected generated files staged; all recipe-modifier commits are present.
Deployment, restart and in-game JEI/machine verification remain a separate post-build
operation and must cite the deployed jar SHA-256.
