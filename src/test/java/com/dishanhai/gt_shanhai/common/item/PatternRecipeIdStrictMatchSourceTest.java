package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternRecipeIdStrictMatchSourceTest {

    private static final Path ROOT = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common");
    private static final Path HELPER = ROOT.resolve(Path.of("item", "PatternRecipeTypeHelper.java"));
    private static final Path MACHINE =
            ROOT.resolve(Path.of("machine", "part", "RecipeTypePatternBufferPartMachine.java"));
    private static final Path SEARCH_HELPER = ROOT.resolve(Path.of("item", "RecipeTypePatternSearchHelper.java"));

    @Test
    void storedRecipeLookupNarrowsByPatternTypeThenNamespaceThenExactPathSuffix() throws IOException {
        String helper = Files.readString(HELPER);
        int start = helper.indexOf("public static GTRecipe resolveRecipeForPattern(");
        int end = helper.indexOf("\n    private static ResourceLocation parseRecipeId", start);
        assertTrue(start >= 0 && end > start, "應找到按樣板配方類型解析已存配方 ID 的入口");
        String resolver = helper.substring(start, end);

        int typeLookup = resolver.indexOf("resolveRecipeType(recipeTypeId)");
        int pathPrefix = resolver.indexOf("typePathPrefix");
        int typeScopedLookup = resolver.indexOf("recipeType.getRecipe(level.getRecipeManager(), expected)");
        int exactFilter = resolver.indexOf("PatternRecipeIdMatchPolicy.matches(");
        assertTrue(typeLookup >= 0 && typeLookup < pathPrefix && pathPrefix < typeScopedLookup
                        && typeScopedLookup < exactFilter,
                "第一層按樣板類型縮小查找，再以命名空間和精確 ID 後綴過濾");
        String policy = Files.readString(ROOT.resolve(Path.of("item", "PatternRecipeIdMatchPolicy.java")));
        int typeMatch = policy.indexOf("canonicalRecipeTypeId(patternRecipeTypeId)");
        int namespaceMatch = policy.indexOf("getNamespace()");
        int suffixMatch = policy.indexOf("expectedSuffix.equals(candidateSuffix)");
        assertTrue(typeMatch >= 0 && typeMatch < namespaceMatch && namespaceMatch < suffixMatch,
                "過濾順序必須是類型、namespace、類型前綴後的完整路徑");
        assertFalse(resolver.contains("for (GTRecipeType type : GTRegistries.RECIPE_TYPES)"),
                "已存 ID 查找不得退回遍歷全部配方類型");
    }

    @Test
    void strictModeRequiresStoredIdAndExactRecipeTypeWithoutInferenceFallback() throws IOException {
        String machine = Files.readString(MACHINE);
        int start = machine.indexOf(
                "public GTRecipe gtShanhai$getPatternRecipe(int slot, GenericStack[] availableCatalystInputs)");
        int end = machine.indexOf("public GenericStack[] gtShanhai$getPatternInferenceInputs()", start);
        assertTrue(start >= 0 && end > start, "應找到普通樣板槽配方解析入口");
        String lookup = machine.substring(start, end);
        int strictBranch = lookup.indexOf("strongPatternMatchMode");
        int idRead = lookup.indexOf("PatternRecipeTypeHelper.readRecipeId(stack)");
        int typeRead = lookup.indexOf("PatternRecipeTypeHelper.readRecipeTypeId(stack)");
        int strictResolve = lookup.indexOf("PatternRecipeTypeHelper.resolveRecipeForPattern(");
        int strictReject = lookup.indexOf("return null;", strictResolve);
        int fallback = lookup.indexOf("PatternRecipeTypeHelper.peekRecipe(");
        assertTrue(strictBranch >= 0 && idRead < typeRead
                        && typeRead < strictResolve && strictResolve < strictReject
                        && strictReject < fallback,
                "強匹配必須先驗證 ID 與類型，失敗即拒絕且不得走通用推斷");
        assertTrue(lookup.contains("recipeId.isEmpty() || recipeTypeId.isEmpty()"),
                "強匹配不得接受沒有已存配方 ID 或配方類型的樣板");
        assertTrue(machine.contains("PatternRecipeIdMatchPolicy.matches("),
                "扣料前必須再次以完整配方 ID 和配方類型拒絕替補");
        assertTrue(machine.contains("PatternSlotScopedRecipe.publicId(recipe)"),
                "扣料闸必须比对去掉槽位作用域后的配方 ID，不能直接用概率折算副本的 getId()");
        assertTrue(machine.contains("StellarPatternStrongMatchMessages"),
                "强匹配拒绝必须走星律报错文案");
        assertTrue(machine.contains("gtShanhai$patternSlotTooltips"),
                "样板槽悬停必须能看到强匹配报错");
        String zh = Files.readString(Path.of("src", "main", "resources", "assets", "gt_shanhai", "lang", "zh_cn.json"));
        String en = Files.readString(Path.of("src", "main", "resources", "assets", "gt_shanhai", "lang", "en_us.json"));
        assertTrue(zh.contains("gt_shanhai.stellar.strong_match.missing")
                        && zh.contains("gt_shanhai.stellar.strong_match.prefix")
                        && zh.contains("gt_shanhai.stellar.strong_match.unresolved")
                        && zh.contains("gt_shanhai.stellar.strong_match.identity"),
                "强匹配四种拒绝都要有中文报错");
        assertTrue(en.contains("gt_shanhai.stellar.strong_match.missing")
                        && en.contains("gt_shanhai.stellar.strong_match.identity"),
                "强匹配报错要有英文文案");
    }

    @Test
    void strictModeIsPersistedInTheAssemblyUiAndMissingIdUsesTheExistingWarningBorder() throws IOException {
        String machine = Files.readString(MACHINE);
        assertTrue(machine.contains("@DescSynced\n    @Persisted\n    private boolean strongPatternMatchMode;")
                        || machine.contains("@DescSynced\r\n    @Persisted\r\n    private boolean strongPatternMatchMode;"),
                "強匹配狀態必須跨存檔保存並同步到 UI");
        assertTrue(machine.contains("new StrongPatternMatchConfigurator()"));
        assertTrue(machine.contains("recipeId.isEmpty() || recipeTypeId.isEmpty()"),
                "強匹配啟用時無配方 ID 樣板須走既有紅框錯誤顯示");
        assertTrue(machine.contains("PatternRecipeIdMatchPolicy.hasTypePrefix("),
                "單槽內容變更時能以 ID 類型前綴立即標示明顯不匹配");
        assertTrue(machine.contains("gtShanhai$setStrongPatternMatchWarning(slot, true)"),
                "精確解析失敗時須記錄到星律樣板報錯槽");
        assertTrue(machine.contains("gtShanhai$setStrongPatternMatchWarning(index,"),
                "單槽內容變更時應立即標記缺少 ID／類型的樣板");
        assertTrue(machine.contains("slot < gtShanhai$getPatternSlotCount()")
                        && machine.contains("gtShanhai$getPatternStack(slot)"),
                "強匹配扣料閘門也必須覆蓋通配展開後的樣板槽");

        int start = machine.indexOf("public void setStrongPatternMatchMode(");
        int end = machine.indexOf("\n    }", start);
        assertTrue(start >= 0 && end > start, "應找到強匹配切換入口");
        String setter = machine.substring(start, end);
        assertFalse(setter.contains("for (int slot = 0; slot < getPatternInventory().getSlots();"),
                "切換模式不得預先遍歷全部樣板");
        assertTrue(setter.contains("gtShanhai$getActiveSlots()")
                        && setter.contains("removeSlotFromGTRecipeCache(slot)"),
                "切換模式只清除 active 樣板槽快取，不得全量清理所有槽位");

        String search = Files.readString(SEARCH_HELPER);
        assertTrue(search.contains("private static GTRecipe getMarkedRecipeCached(")
                        && search.contains("put(slot, new MarkedRecipeCacheEntry("),
                "解析結果必須沿用現有按活躍樣板槽快取");

        String remoteHolder = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "item", "terminal", "ShanhaiStellarRemoteUIHolder.java"));
        String remotePacket = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "network", "ShanhaiPatternRemoteConfigPacket.java"));
        String terminalScreen = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "client", "gui", "terminal", "ShanhaiPatternManagementScreen.java"));
        assertTrue(remoteHolder.contains("Mode.STRONG_PATTERN_MATCH")
                        && remotePacket.contains("Operation.OPEN_STRONG_PATTERN_MATCH")
                        && terminalScreen.contains("\"gui.gt_shanhai.stellar_remote.strong_match\""),
                "无线终端必须暴露对应的强匹配模式入口");
    }

    @Test
    void startupRecipeTypeRefreshOnlyInvalidatesTheLazyPerSlotCache() throws IOException {
        String machine = Files.readString(MACHINE);
        int start = machine.indexOf("private void refreshPatternRecipeTypes()");
        int end = machine.indexOf("\n    }", start);
        assertTrue(start >= 0 && end > start, "應找到批量配方類型快取失效入口");
        String refresh = machine.substring(start, end);
        assertTrue(refresh.contains("Arrays.fill(patternRecipeTypeIds, \"\")"));
        assertTrue(refresh.contains("Arrays.fill(patternRecipeTypeTags, null)"));
        assertFalse(refresh.contains("for (int slot"),
                "啟動和批量刷新只清空快取標記，不遍歷驗證樣板槽");
        assertFalse(machine.contains("peekRecipeTypeId(stack, getLevel())"),
                "刷新配方類型快取不得推斷或解碼所有樣板");
    }

    @Test
    void wildcardInferenceDoesNotForgeAStoredRecipeId() throws IOException {
        String machine = Files.readString(MACHINE);
        int start = machine.indexOf("private IPatternDetails rememberWildcardPattern(");
        int end = machine.indexOf("private void refreshWildcardPatterns(", start);
        assertTrue(start >= 0 && end > start, "應找到通配展開樣板建立入口");
        String remember = machine.substring(start, end);
        assertTrue(remember.contains("String storedRecipeId = PatternRecipeTypeHelper.readRecipeId(patternStack)"));
        assertTrue(remember.contains("if (!storedRecipeId.isEmpty())"),
                "強匹配不能把通配輸入推斷出的 ID 偽裝成來源樣板已保存的 ID");
        assertFalse(remember.contains("writeRecipeId(patternStack, recipe.id.toString())"));
    }

    @Test
    void visiblePatternWarningsValidateOnlyTheRequestedSlotAndCacheByStackIdentity() throws IOException {
        String machine = Files.readString(MACHINE);
        int start = machine.indexOf("public boolean gtShanhai$isPatternSlotWarning(int slot)");
        int end = machine.indexOf("public int gtShanhai$getWarningSlotCount()", start);
        assertTrue(start >= 0 && end > start, "應找到樣板槽紅框查詢入口");
        String warning = machine.substring(start, end);
        assertTrue(warning.contains("strongPatternMatchMode")
                        && warning.contains("strictRecipeIdWarningStackHashes[slot]")
                        && warning.contains("!stack.isEmpty() && !gtShanhai$hasStrongPatternMetadata(stack)"),
                "強匹配紅框應只在 widget 查詢此槽時檢查 NBT，並按此槽堆疊快取；空槽不得因缺少配方 ID 畫紅框");
        assertFalse(warning.contains("for (int slot"),
                "顯示紅框不得預先驗證全部樣板槽");
        assertFalse(warning.contains("PatternDetailsHelper.decodePattern"),
                "缺 ID 紅框只需讀 NBT 元資料，不得解碼 AE 樣板");
    }
}
