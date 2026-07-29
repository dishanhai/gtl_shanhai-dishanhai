# Stellar Pattern Warning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** 為星律樣板總成加入 EAEP 錯誤主機二次確認、樣板槽紅框、可配置卡死偵測、GTCEu 錯誤診斷及去重玩家廣播。

**Architecture:** EA2 provider 列表封包攜帶星律標記、主機配方類型與待上傳樣板精確類型，客戶端只負責二次確認。AE2 合成 CPU 在單次 \`pushPattern\` 呼叫期間以 ThreadLocal 暴露權威 player id，星律在服務端維護逐槽原料快照狀態機、同步警告槽集合並組裝診斷訊息。

**Tech Stack:** Java 17、Forge 1.20.1、AE2 15.4.10、EAEP 1.5.3、GTCEu 1.4.4、GTLCore 1.2.3.0-fix9、Sponge Mixin、LDLib、JUnit 5、Gradle 8.8。

**Execution note:** 目前 checkout 有 4 個不相關的使用者修改，且本機 \`libs/\`、\`lib_source/\`、\`gradle-install/\` 未版控。所有提交只精確 stage 本任務檔案，不移動或覆寫既有修改。

---

## File Map

- Create \`src/main/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternWarningPolicy.java\`: 純相容判定與警告槽編碼。
- Create \`src/main/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternStuckMonitor.java\`: 逐槽原料快照狀態機。
- Create \`src/main/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternWarningReporter.java\`: 診斷資料、收件人與聊天元件。
- Create \`src/main/java/com/dishanhai/gt_shanhai/common/ae2/StellarPatternOrderContext.java\`: AE 下單者短生命週期作用域。
- Create \`src/main/java/com/dishanhai/gt_shanhai/mixin/ExecutingCraftingJobPlayerIdAccessor.java\`: 讀取 AE2 工作 player id。
- Create \`src/main/java/com/dishanhai/gt_shanhai/mixin/CraftingCpuLogicStellarOrderContextMixin.java\`: 包裹普通 AE CPU provider 發配。
- Create \`src/main/java/com/dishanhai/gt_shanhai/mixin/EaepProviderUploadPatternAccessor.java\`: 讀取 EAEP Ctrl+Q 待上傳樣板。
- Modify \`src/main/java/com/dishanhai/gt_shanhai/common/compat/eaep/EaepProviderRecipeTypesPacketAccess.java\`: 封包擴充欄位接口。
- Modify \`src/main/java/com/dishanhai/gt_shanhai/common/compat/eaep/EaepProviderRecipeTypeBridge.java\`: provider 元資料快照與精確相容判定。
- Modify \`src/main/java/com/dishanhai/gt_shanhai/mixin/EaepRequestProvidersListRecipeTypesMixin.java\`: 服務端收集元資料。
- Modify \`src/main/java/com/dishanhai/gt_shanhai/mixin/EaepProvidersListRecipeTypesMixin.java\`: 元資料編解碼與客戶端暫存。
- Modify \`src/main/java/com/dishanhai/gt_shanhai/mixin/EaepProviderSelectScreenRecipeTypeMixin.java\`: 錯誤主機確認畫面。
- Modify \`src/main/java/com/dishanhai/gt_shanhai/common/ae2/quantum/QuantumCraftingCPULogic.java\`: 包裹山海量子 CPU provider 發配。
- Modify \`src/main/java/com/dishanhai/gt_shanhai/common/machine/part/RecipeTypePatternBufferPartMachine.java\`: 接單、輪詢、警告同步與清理。
- Modify \`src/main/java/com/dishanhai/gt_shanhai/common/machine/part/CachedPatternPaginationUIManager.java\`: 傳遞逐槽警告查詢。
- Modify \`src/main/java/com/dishanhai/gt_shanhai/common/machine/part/CachedPatternSlotWidget.java\`: 2 px 紅色邊框。
- Modify \`src/main/java/com/dishanhai/gt_shanhai/config/DShanhaiConfig.java\`: 卡死秒數配置。
- Modify \`src/main/java/com/dishanhai/gt_shanhai/command/DShanhaiCommands.java\`: 安全跨維度星律傳送命令。
- Modify \`src/main/resources/gt_shanhai.mixin.json\`: 註冊新增 common mixin。
- Modify \`src/main/resources/assets/gt_shanhai/lang/zh_cn.json\` and \`en_us.json\`: 確認、診斷、傳送文案。
- Create focused tests under \`src/test/java/com/dishanhai/gt_shanhai/common/machine/part\`, \`common/compat/eaep\`, and \`mixin\`.

### Task 1: Host compatibility policy and warning-slot codec

**Files:**
- Create: \`src/main/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternWarningPolicy.java\`
- Test: \`src/test/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternWarningPolicyTest.java\`

- [ ] **Step 1: Write the failing policy tests**

~~~java
@Test
void knownStellarWithoutMatchingTypeIsWrongHost() {
    assertTrue(StellarPatternWarningPolicy.isWrongHost(
            "gtceu:assembler", true, true, List.of("gtceu:alloy_smelter"), (a, b) -> false));
}

@Test
void exactAndSharedTypesAreAcceptedAndUnknownMetadataIsIgnored() {
    assertFalse(StellarPatternWarningPolicy.isWrongHost(
            "gtceu:assembler", true, true, List.of("gtceu:assembler"), (a, b) -> false));
    assertFalse(StellarPatternWarningPolicy.isWrongHost(
            "gtceu:chemical_reactor", true, true, List.of("gtceu:large_chemical_reactor"),
            (a, b) -> a.contains("chemical_reactor") && b.contains("chemical_reactor")));
    assertFalse(StellarPatternWarningPolicy.isWrongHost(
            "gtceu:assembler", false, true, List.of(), (a, b) -> false));
}

@Test
void warningSlotCodecRoundTripsLargeSlotIndices() {
    BitSet slots = new BitSet();
    slots.set(0);
    slots.set(161);
    assertEquals(slots, StellarPatternWarningPolicy.decodeWarningSlots(
            StellarPatternWarningPolicy.encodeWarningSlots(slots)));
}
~~~

- [ ] **Step 2: Run test to verify RED**

Run:

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*StellarPatternWarningPolicyTest' --no-daemon
~~~

Expected: compilation fails because \`StellarPatternWarningPolicy\` does not exist.

- [ ] **Step 3: Implement the minimal policy**

~~~java
public static boolean isWrongHost(String patternTypeId, boolean metadataKnown, boolean stellar,
        Collection<String> hostTypeIds, BiPredicate<String, String> shared) {
    if (!metadataKnown || !stellar || patternTypeId == null || patternTypeId.isBlank()) return false;
    for (String hostTypeId : hostTypeIds) {
        if (patternTypeId.equals(hostTypeId) || shared.test(patternTypeId, hostTypeId)) return false;
    }
    return true;
}

public static String encodeWarningSlots(BitSet slots) {
    return slots.stream().mapToObj(Integer::toString).collect(Collectors.joining(","));
}

public static BitSet decodeWarningSlots(String encoded) {
    BitSet result = new BitSet();
    if (encoded == null || encoded.isBlank()) return result;
    for (String token : encoded.split(",")) result.set(Integer.parseInt(token));
    return result;
}
~~~

- [ ] **Step 4: Run GREEN and commit only policy files**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*StellarPatternWarningPolicyTest' --no-daemon
git add -- src/main/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternWarningPolicy.java src/test/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternWarningPolicyTest.java
git commit -m "feat(pattern): 添加星律主机类型告警策略"
~~~

Expected: focused test passes and the commit contains exactly two files.

### Task 2: EAEP provider metadata packet

**Files:**
- Create: \`src/main/java/com/dishanhai/gt_shanhai/mixin/EaepProviderUploadPatternAccessor.java\`
- Modify: \`src/main/java/com/dishanhai/gt_shanhai/common/compat/eaep/EaepProviderRecipeTypesPacketAccess.java\`
- Modify: \`src/main/java/com/dishanhai/gt_shanhai/common/compat/eaep/EaepProviderRecipeTypeBridge.java\`
- Modify: \`src/main/java/com/dishanhai/gt_shanhai/mixin/EaepRequestProvidersListRecipeTypesMixin.java\`
- Modify: \`src/main/java/com/dishanhai/gt_shanhai/mixin/EaepProvidersListRecipeTypesMixin.java\`
- Modify: \`src/main/resources/gt_shanhai.mixin.json\`
- Test: \`src/test/java/com/dishanhai/gt_shanhai/common/compat/eaep/EaepProviderWarningMetadataSourceTest.java\`

- [ ] **Step 1: Write a failing source contract test**

~~~java
assertTrue(packet.contains("buf.writeBoolean(metadataKnown)"));
assertTrue(packet.contains("buf.writeBoolean(stellarProviders.get(i))"));
assertTrue(packet.contains("buf.writeUtf(uploadRecipeTypeId, 128)"));
assertTrue(request.contains("PatternRecipeTypeHelper.readRecipeTypeId(uploadPattern)"));
assertTrue(request.contains("container instanceof RecipeTypePatternBufferPartMachine"));
assertTrue(config.contains("\"EaepProviderUploadPatternAccessor\""));
~~~

- [ ] **Step 2: Run the test and verify RED**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*EaepProviderWarningMetadataSourceTest' --no-daemon
~~~

Expected: assertions fail because the packet has no stellar flags or upload type id.

- [ ] **Step 3: Add exact packet fields and backward-compatible decode**

Use this packet contract:

~~~java
List<List<String>> gtShanhai$getProviderRecipeTypeIds();
List<Boolean> gtShanhai$getStellarProviders();
String gtShanhai$getUploadRecipeTypeId();
boolean gtShanhai$isWarningMetadataKnown();
~~~

Encode \`metadataKnown\`, provider count with one boolean per provider, then \`uploadRecipeTypeId\`. Decode only when \`buf.readableBytes() > 0\`; absent tail sets \`metadataKnown=false\`. The request mixin obtains Ctrl+Q pending patterns through an \`@Invoker("getPendingCtrlQPattern")\` accessor and encoding-menu patterns through EAEP's existing encoded-slot accessor.

- [ ] **Step 4: Run packet tests and existing EAEP tests**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*EaepProviderWarningMetadataSourceTest' --tests '*EaepProviderRecipeTypeBridge*' --no-daemon
~~~

Expected: all selected EAEP tests pass.

- [ ] **Step 5: Commit the packet slice**

~~~powershell
git add -- src/main/java/com/dishanhai/gt_shanhai/common/compat/eaep src/main/java/com/dishanhai/gt_shanhai/mixin/EaepProviderUploadPatternAccessor.java src/main/java/com/dishanhai/gt_shanhai/mixin/EaepRequestProvidersListRecipeTypesMixin.java src/main/java/com/dishanhai/gt_shanhai/mixin/EaepProvidersListRecipeTypesMixin.java src/main/resources/gt_shanhai.mixin.json src/test/java/com/dishanhai/gt_shanhai/common/compat/eaep/EaepProviderWarningMetadataSourceTest.java
git commit -m "feat(eaep): 同步星律上传告警元数据"
~~~

### Task 3: EAEP second confirmation

**Files:**
- Modify: \`src/main/java/com/dishanhai/gt_shanhai/mixin/EaepProviderSelectScreenRecipeTypeMixin.java\`
- Modify: \`src/main/resources/assets/gt_shanhai/lang/zh_cn.json\`
- Modify: \`src/main/resources/assets/gt_shanhai/lang/en_us.json\`
- Test: \`src/test/java/com/dishanhai/gt_shanhai/client/EaepProviderWrongHostConfirmationSourceTest.java\`

- [ ] **Step 1: Write the failing confirmation contract**

~~~java
assertTrue(screen.contains("@Inject(method = \"onChoose(IZ)V\", at = @At(\"HEAD\"), cancellable = true"));
assertTrue(screen.contains("new ConfirmScreen"));
assertTrue(screen.contains("gtShanhai$bypassWrongHostConfirmation"));
assertTrue(screen.contains("StellarPatternWarningPolicy.isWrongHost"));
assertTrue(screen.contains("gtShanhai$onChoose(index, showStatusMessage)"));
~~~

- [ ] **Step 2: Run RED**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*EaepProviderWrongHostConfirmationSourceTest' --no-daemon
~~~

Expected: confirmation hook assertions fail.

- [ ] **Step 3: Inject the original screen flow**

Shadow \`onChoose(int, boolean)\`. At HEAD, allow one call when bypass is set; otherwise evaluate exact provider metadata. On mismatch cancel, open \`ConfirmScreen\`, and on confirm set bypass then invoke the shadowed method. On cancel restore \`(ProviderSelectScreen)(Object)this\`. Do not close or cancel the EAEP pending pattern before the player decides.

- [ ] **Step 4: Run GREEN plus existing search/layout tests and commit**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*EaepProviderWrongHostConfirmationSourceTest' --tests '*EaepShanhaiPatternSearchSourceTest' --tests '*EaepGtlCoreUploadButtonLayoutSourceTest' --no-daemon
git add -- src/main/java/com/dishanhai/gt_shanhai/mixin/EaepProviderSelectScreenRecipeTypeMixin.java src/main/resources/assets/gt_shanhai/lang/zh_cn.json src/main/resources/assets/gt_shanhai/lang/en_us.json src/test/java/com/dishanhai/gt_shanhai/client/EaepProviderWrongHostConfirmationSourceTest.java
git commit -m "feat(eaep): 错误星律主机上传前二次确认"
~~~

### Task 4: Authoritative AE order-player context

**Files:**
- Create: \`src/main/java/com/dishanhai/gt_shanhai/common/ae2/StellarPatternOrderContext.java\`
- Create: \`src/main/java/com/dishanhai/gt_shanhai/mixin/ExecutingCraftingJobPlayerIdAccessor.java\`
- Create: \`src/main/java/com/dishanhai/gt_shanhai/mixin/CraftingCpuLogicStellarOrderContextMixin.java\`
- Modify: \`src/main/java/com/dishanhai/gt_shanhai/common/ae2/quantum/QuantumCraftingCPULogic.java\`
- Modify: \`src/main/resources/gt_shanhai.mixin.json\`
- Test: \`src/test/java/com/dishanhai/gt_shanhai/common/ae2/StellarPatternOrderContextTest.java\`
- Test: \`src/test/java/com/dishanhai/gt_shanhai/mixin/CraftingCpuOrderContextSourceTest.java\`

- [ ] **Step 1: Write failing nested-scope and cleanup tests**

~~~java
assertNull(StellarPatternOrderContext.currentPlayerId());
assertEquals("ok", StellarPatternOrderContext.withPlayerId(12, () -> {
    assertEquals(12, StellarPatternOrderContext.currentPlayerId());
    return "ok";
}));
assertNull(StellarPatternOrderContext.currentPlayerId());
assertThrows(IllegalStateException.class,
        () -> StellarPatternOrderContext.withPlayerId(13, () -> { throw new IllegalStateException(); }));
assertNull(StellarPatternOrderContext.currentPlayerId());
~~~

- [ ] **Step 2: Run RED, then implement ThreadLocal with try/finally**

~~~java
public static <T> T withPlayerId(Integer playerId, Supplier<T> action) {
    Integer previous = CURRENT_PLAYER_ID.get();
    try {
        if (playerId == null) CURRENT_PLAYER_ID.remove(); else CURRENT_PLAYER_ID.set(playerId);
        return action.get();
    } finally {
        if (previous == null) CURRENT_PLAYER_ID.remove(); else CURRENT_PLAYER_ID.set(previous);
    }
}
~~~

The ordinary CPU mixin redirects the exact \`ICraftingProvider.pushPattern\` invocation in \`executeCrafting\`; the accessor reads \`ExecutingCraftingJob.playerId\`. The quantum CPU wraps its existing provider call with the same context and its existing \`job.playerId\`.

- [ ] **Step 3: Run context and source tests**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*StellarPatternOrderContextTest' --tests '*CraftingCpuOrderContextSourceTest' --no-daemon
~~~

Expected: both tests pass and the source test confirms ordinary and quantum paths use \`withPlayerId\`.

- [ ] **Step 4: Commit the context slice**

~~~powershell
git add -- src/main/java/com/dishanhai/gt_shanhai/common/ae2/StellarPatternOrderContext.java src/main/java/com/dishanhai/gt_shanhai/common/ae2/quantum/QuantumCraftingCPULogic.java src/main/java/com/dishanhai/gt_shanhai/mixin/ExecutingCraftingJobPlayerIdAccessor.java src/main/java/com/dishanhai/gt_shanhai/mixin/CraftingCpuLogicStellarOrderContextMixin.java src/main/resources/gt_shanhai.mixin.json src/test/java/com/dishanhai/gt_shanhai/common/ae2/StellarPatternOrderContextTest.java src/test/java/com/dishanhai/gt_shanhai/mixin/CraftingCpuOrderContextSourceTest.java
git commit -m "feat(ae2): 传递星律样板下单玩家上下文"
~~~

### Task 5: Per-slot stuck state machine

**Files:**
- Create: \`src/main/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternStuckMonitor.java\`
- Test: \`src/test/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternStuckMonitorTest.java\`

- [ ] **Step 1: Write failing transition tests**

Test these exact transitions with string keys: unchanged before timeout returns \`NONE\`; unchanged at timeout returns \`ALERT\` once; any decrease returns \`RESET\`; increase returns \`RESET\`; inactive or empty returns \`CLEAR\`; after RESET a later unchanged timeout can alert again.

~~~java
monitor.onPushed(4, 21, snapshot(Map.of("item:a", 64L)), 100L);
assertEquals(Action.NONE, monitor.check(4, true, snapshot(Map.of("item:a", 64L)), 299L, 200L));
assertEquals(Action.ALERT, monitor.check(4, true, snapshot(Map.of("item:a", 64L)), 300L, 200L));
assertEquals(Action.NONE, monitor.check(4, true, snapshot(Map.of("item:a", 64L)), 500L, 200L));
assertEquals(Action.RESET, monitor.check(4, true, snapshot(Map.of("item:a", 63L)), 501L, 200L));
~~~

- [ ] **Step 2: Run RED**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*StellarPatternStuckMonitorTest' --no-daemon
~~~

Expected: class-not-found compilation failure.

- [ ] **Step 3: Implement slot states without Minecraft dependencies**

Use immutable \`InputSnapshot(Map<Object, Long>)\`, \`SlotState(snapshot, startTick, playerId, alerted)\`, and \`Action { NONE, RESET, CLEAR, ALERT }\`. A snapshot is changed when key sets or values differ; any changed nonempty snapshot resets the timer. \`ALERT\` marks the current state alerted before returning.

- [ ] **Step 4: Run GREEN and commit**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*StellarPatternStuckMonitorTest' --no-daemon
git add -- src/main/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternStuckMonitor.java src/test/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternStuckMonitorTest.java
git commit -m "feat(pattern): 添加星律逐槽卡死状态机"
~~~

### Task 6: Integrate monitoring, diagnostics, recipients, and teleport

**Files:**
- Create: \`src/main/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternWarningReporter.java\`
- Modify: \`src/main/java/com/dishanhai/gt_shanhai/common/machine/part/RecipeTypePatternBufferPartMachine.java\`
- Modify: \`src/main/java/com/dishanhai/gt_shanhai/config/DShanhaiConfig.java\`
- Modify: \`src/main/java/com/dishanhai/gt_shanhai/command/DShanhaiCommands.java\`
- Modify: \`src/main/resources/assets/gt_shanhai/lang/zh_cn.json\`
- Modify: \`src/main/resources/assets/gt_shanhai/lang/en_us.json\`
- Test: \`src/test/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternWarningIntegrationSourceTest.java\`
- Test: \`src/test/java/com/dishanhai/gt_shanhai/command/StellarWarningTeleportSourceTest.java\`

- [ ] **Step 1: Write failing integration contracts**

Assert that the machine overrides \`pushPattern\`, records \`StellarPatternOrderContext.currentPlayerId()\`, subscribes a server tick, checks every 20 ticks, reads \`stellarPatternStuckTimeoutSeconds\`, merges two BitSets, and calls the reporter only on \`Action.ALERT\`. Assert the reporter reads both \`getRecipeStatus()\` and \`getWorkingStatus()\`, uses \`IPlayerRegistry.getConnected\`, uses a 500-block squared radius, and stores recipients in a UUID-keyed map.

Assert the command checks \`level.hasChunkAt(pos)\` before \`getBlockEntity\`, verifies the meta machine is \`RecipeTypePatternBufferPartMachine\`, and only then calls \`ServerPlayer.teleportTo(ServerLevel, ...)\`.

- [ ] **Step 2: Run RED**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*StellarPatternWarningIntegrationSourceTest' --tests '*StellarWarningTeleportSourceTest' --no-daemon
~~~

- [ ] **Step 3: Wire the machine state**

Add:

~~~java
@DescSynced
private String patternWarningSlotsSync = "";
private final BitSet wrongHostWarningSlots = new BitSet();
private final BitSet stuckWarningSlots = new BitSet();
private final StellarPatternStuckMonitor stuckMonitor = new StellarPatternStuckMonitor();
@Nullable private TickableSubscription patternWarningSubscription;
~~~

Override \`pushPattern\` to identify the accepted slot, call \`super\`, and then snapshot its item/fluid maps with the current AE player id. Subscribe on load, unsubscribe on unload, and every 20 ticks update wrong-host state plus monitored active slots. Convert config seconds to ticks with overflow-safe \`Math.min(Integer.MAX_VALUE, seconds * 20L)\`.

- [ ] **Step 4: Implement reporter and secure command**

Reporter builds translated components for slot, inputs, outputs, type IDs, cache mismatch and deduplicated \`IRecipeStatus\` reasons. It adds nearby same-dimension players whose squared distance is at most \`250000.0D\`, then the connected AE job owner, into \`LinkedHashMap<UUID, ServerPlayer>\`.

Coordinate click value:

~~~java
"/shanhai stellar_warning_tp " + dimension.location() + " " + x + " " + y + " " + z
~~~

Command validation order is dimension lookup, \`hasChunkAt\`, block entity lookup, meta-machine type check, then teleport to \`(x + 0.5, y + 1.0, z + 0.5)\`.

- [ ] **Step 5: Run focused tests and commit**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*StellarPatternWarningIntegrationSourceTest' --tests '*StellarWarningTeleportSourceTest' --tests '*StellarPatternStuckMonitorTest' --no-daemon
git add -- src/main/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternWarningReporter.java src/main/java/com/dishanhai/gt_shanhai/common/machine/part/RecipeTypePatternBufferPartMachine.java src/main/java/com/dishanhai/gt_shanhai/config/DShanhaiConfig.java src/main/java/com/dishanhai/gt_shanhai/command/DShanhaiCommands.java src/main/resources/assets/gt_shanhai/lang/zh_cn.json src/main/resources/assets/gt_shanhai/lang/en_us.json src/test/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternWarningIntegrationSourceTest.java src/test/java/com/dishanhai/gt_shanhai/command/StellarWarningTeleportSourceTest.java
git commit -m "feat(pattern): 广播星律卡死诊断与安全传送"
~~~

### Task 7: Red warning border

**Files:**
- Modify: \`src/main/java/com/dishanhai/gt_shanhai/common/machine/part/CachedPatternPaginationUIManager.java\`
- Modify: \`src/main/java/com/dishanhai/gt_shanhai/common/machine/part/CachedPatternSlotWidget.java\`
- Modify: \`src/main/java/com/dishanhai/gt_shanhai/common/machine/part/RecipeTypePatternBufferPartMachine.java\`
- Test: \`src/test/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternWarningBorderSourceTest.java\`
- Test: \`src/test/java/com/dishanhai/gt_shanhai/common/machine/part/RecipeTypePatternBufferRenderPerformanceSourceTest.java\`

- [ ] **Step 1: Write the failing border contract**

~~~java
assertTrue(slot.contains("BooleanSupplier warningSupplier"));
assertTrue(slot.contains("drawWarningBorder(graphics, pos)"));
assertEquals(4, countOccurrences(borderMethod, "DrawerHelper.drawSolidRect"));
assertTrue(pagination.contains("isWarning.test(finalSlot)"));
assertTrue(machine.contains("this::isPatternSlotWarning"));
~~~

- [ ] **Step 2: Run RED**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*StellarPatternWarningBorderSourceTest' --no-daemon
~~~

- [ ] **Step 3: Draw a stable 2 px border**

Pass an \`IntPredicate isWarning\` through the pagination manager into a per-slot \`BooleanSupplier\`. Draw four rectangles at the 18x18 slot bounds after \`drawBackgroundTexture\` and before item/overlay drawing. Use opaque \`0xFFFF2020\`; do not invoke superclass rendering or decode the pattern.

- [ ] **Step 4: Run border and performance tests, then commit**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*StellarPatternWarningBorderSourceTest' --tests '*RecipeTypePatternBufferRenderPerformanceSourceTest' --no-daemon
git add -- src/main/java/com/dishanhai/gt_shanhai/common/machine/part/CachedPatternPaginationUIManager.java src/main/java/com/dishanhai/gt_shanhai/common/machine/part/CachedPatternSlotWidget.java src/main/java/com/dishanhai/gt_shanhai/common/machine/part/RecipeTypePatternBufferPartMachine.java src/test/java/com/dishanhai/gt_shanhai/common/machine/part/StellarPatternWarningBorderSourceTest.java src/test/java/com/dishanhai/gt_shanhai/common/machine/part/RecipeTypePatternBufferRenderPerformanceSourceTest.java
git commit -m "feat(pattern): 星律异常样板槽绘制红色边框"
~~~

### Task 8: Full regression, self-improvement records, and handoff

**Files:**
- Modify: \`C:/Users/dishanhai/Desktop/ide专属文件/.learnings/ERRORS.md\`
- Modify: \`C:/Users/dishanhai/Desktop/ide专属文件/.learnings/LEARNINGS.md\`
- Modify: \`C:/Users/dishanhai/Desktop/ide专属文件/.learnings/TODO.md\`

- [ ] **Step 1: Run all targeted feature tests**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' test --tests '*StellarPattern*' --tests '*EaepProvider*' --tests '*CraftingCpuOrderContextSourceTest' --no-daemon
~~~

Expected: all selected tests pass with zero failures.

- [ ] **Step 2: Run clean full build**

~~~powershell
& '.\gradle-install\gradle-8.8\bin\gradle.bat' clean build --no-daemon
~~~

Expected: \`BUILD SUCCESSFUL\`, jar at \`build/libs/gt_shanhai.jar\`. Do not copy it to the game instance without a deployment request.

- [ ] **Step 3: Inspect exact task diff and requirements**

~~~powershell
git status --short
git diff --check
git log --oneline -8
~~~

Confirm: unrelated terminal/shop files remain untouched; every new mixin is registered; both lang JSON files parse; no temporary diagnostic logging remains; every user requirement maps to a passing test or build check.

- [ ] **Step 4: Record resolved lessons**

Append one consolidated \`ERRORS.md\` entry covering guessed decompile package paths and ignored-doc staging, and one \`LEARNINGS.md\` entry covering the AE2 \`ExecutingCraftingJob.playerId -> executeCrafting -> provider.pushPattern\` owner bridge. Mark or remove the matching unfinished entry in \`TODO.md\`. Each entry follows \`[TYPE-20260729-XXX]\` format and does not exceed the project log token limit.

- [ ] **Step 5: Commit only learning files if the IDE workspace is a Git repository**

~~~powershell
git add -- .learnings/ERRORS.md .learnings/LEARNINGS.md .learnings/TODO.md
git commit -m "docs(learn): 记录星律告警实现经验"
~~~

If the IDE workspace is not a Git repository, leave the required files updated and report that they could not be committed separately.

- [ ] **Step 6: Report build-only result**

Report modified files by location and behavior, exact test/build commands and outcomes, jar path, and the remaining required runtime checks: EAEP confirm/cancel, 10-second unchanged input alert, UUID dedupe, red-border clear, and cross-dimension click teleport.
