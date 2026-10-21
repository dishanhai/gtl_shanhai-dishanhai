package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海星律 P2：Minecraft/Mixin 執行邊界的源碼契約。 */
class StellarP2RegressionSourceTest {

    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    private static String read(String path) throws IOException {
        return Files.readString(ROOT.resolve(path)).replace("\r\n", "\n");
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, signature);
        return source.substring(start, end);
    }

    @Test
    void wildcardIdentityIsCheckedBeforeTheOrdinarySlotMap() throws IOException {
        String guard = read("api/PatternIdentityGuard.java");
        String body = method(guard, "public static boolean slotRejectsRecipe(");
        int wildcard = body.indexOf("gtShanhai$wildcardSlotRejectsRecipe(trySlot, recipe)");
        int ordinary = body.indexOf("gtShanhai$getSlot2PatternMap()");
        assertTrue(wildcard >= 0 && ordinary > wildcard,
                "通配槽不存在於普通槽 map，必須先比對其已解析公開配方 ID");
        String machine = read("common/machine/part/RecipeTypePatternBufferPartMachine.java");
        String check = method(machine, "public boolean gtShanhai$wildcardSlotRejectsRecipe(");
        assertTrue(check.contains("slot < maxPatternCount"));
        assertTrue(check.contains("PatternSlotScopedRecipe.publicId(recipe)"));
        assertTrue(check.contains("PatternSlotScopedRecipe.publicId(resolved)"));
        assertTrue(check.contains("resolved == null"));
    }

    @Test
    void ordinaryIdentityUsesRequiredAccessorWithoutReflectiveFailOpen() throws IOException {
        String guard = read("api/PatternIdentityGuard.java");
        assertTrue(guard.contains("(MEPatternBufferSlot2PatternAccessor)"));
        assertFalse(guard.contains("java.lang.reflect"));
        assertFalse(guard.contains("ReflectiveOperationException"));
    }

    @Test
    void furnaceResolutionRequiresAnActualSmeltingRecipe() throws IOException {
        String helper = read("common/item/PatternRecipeTypeHelper.java");
        String resolver = method(helper, "public static GTRecipe resolveRecipeForPattern(");
        assertTrue(resolver.contains("resolveVanillaSmeltingRecipe(level, recipeId)"));
        assertTrue(resolver.contains("toElectricFurnaceRecipe(vanilla)"));
        assertTrue(resolver.contains("GTCEU_ELECTRIC_FURNACE_RECIPE_TYPE_ID"));
    }

    @Test
    void scheduleRetainsSnapshotButDefersAllDisplayDescriptions() throws IOException {
        String watch = read("common/machine/part/StellarPatternStuckWatch.java");
        String schedule = method(watch, "public static void schedule(");
        assertTrue(schedule.contains("copyItemSnapshot(itemInventory)"));
        assertTrue(schedule.contains("copyFluidSnapshot(fluidInventory)"));
        assertFalse(schedule.contains("describeStuckInputs"));
        assertFalse(schedule.contains("describePatternOutputs"));
        assertFalse(schedule.contains("describeStuckSlot"));
        assertFalse(schedule.contains("removeIf"));
        assertTrue(schedule.contains("PENDING.put("));
        String process = method(watch, "private static void process(");
        assertTrue(process.indexOf("describePatternOutputs") > process.indexOf("stillContainsAll"));
        assertTrue(process.contains("watch.patternDetails"));
    }

    @Test
    void sparseSlotEnumerationAndTypeChecksAvoidFullNbtHashing() throws IOException {
        String machine = read("common/machine/part/RecipeTypePatternBufferPartMachine.java");
        String active = method(machine, "public int[] gtShanhai$getActiveSlots()");
        assertTrue(active.contains("configuredPatternSlots.nextSetBit("));
        String change = method(machine, "protected void onPatternChange(int index)");
        assertTrue(change.contains("configuredPatternSlots.set(index, hasPatternInSlot(index))"));
        String refresh = method(machine, "private void rebuildConfiguredPatternSlots()");
        assertTrue(refresh.contains("configuredPatternSlots"));
        String load = method(machine, "public void onLoad()");
        assertTrue(load.indexOf("rebuildConfiguredPatternSlots()") > load.indexOf("super.onLoad()"));
        assertTrue(load.indexOf("rebuildConfiguredPatternSlots()", load.indexOf("new TickTask")) >= 0,
                "父類載入排程完成後必須再核對一次，避免將尚未建表的空索引保留下來");
        String type = method(machine, "public String gtShanhai$getPatternRecipeTypeId(int slot)");
        assertTrue(type.contains("!taggedTypeId.equals(patternRecipeTypeIds[slot])"),
                "只有類型經推斷而與 NBT 不同的槽才需要完整內容指紋；避免未標記樣板原地編輯後沿用舊類型");
        assertTrue(type.contains("PatternRecipeTypeHelper.readRecipeTypeId(stack)"),
                "原地改 tag 也要偵測，不能僅依賴 ItemStack/tag 引用相同");
    }
}
