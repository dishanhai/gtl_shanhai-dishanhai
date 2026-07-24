package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialRecipeOutputAmplifierTest {

    private static final int FULL_CHANCE = 10_000;
    private static final Unsafe UNSAFE = findUnsafe();
    private static final Path AMPLIFIER_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "PrimordialRecipeOutputAmplifier.java");
    private static final Path LOGIC_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "PrimordialModuleRecipeLogic.java");

    @Test
    void multiplierAtOrBelowOneKeepsTheOriginalRecipeInstance() {
        GTRecipe recipe = emptyRecipe();

        assertSame(recipe, PrimordialRecipeOutputAmplifier.apply(recipe, 1));
        assertSame(recipe, PrimordialRecipeOutputAmplifier.apply(recipe, 0));
        assertSame(recipe, PrimordialRecipeOutputAmplifier.apply(recipe, -10));
        assertNull(PrimordialRecipeOutputAmplifier.apply(null, 1000));
    }

    @Test
    void outputHelperForcesChanceBeforeScalingNormalAndTickContents() {
        for (int multiplier : new int[] { 10, 100, 1000 }) {
            Map<RecipeCapability<?>, List<Content>> normalOutputs = contentMap(
                    rawContent(5L, 2000), rawContent(13L, 0));
            Map<RecipeCapability<?>, List<Content>> tickOutputs = contentMap(
                    rawContent(7L, 2500), rawContent(17L, 0));

            PrimordialRecipeOutputAmplifier.amplifyAndForceFullChance(
                    normalOutputs, FULL_CHANCE, ContentModifier.multiplier(multiplier), this::scaleAfterChanceCheck);
            PrimordialRecipeOutputAmplifier.amplifyAndForceFullChance(
                    tickOutputs, FULL_CHANCE, ContentModifier.multiplier(multiplier), this::scaleAfterChanceCheck);

            assertAmounts(normalOutputs, 5L * multiplier, 13L * multiplier);
            assertAmounts(tickOutputs, 7L * multiplier, 17L * multiplier);
            assertAllGuaranteed(normalOutputs);
            assertAllGuaranteed(tickOutputs);
        }
    }

    @Test
    void zeroChanceOutputRetainsPromotionAndParallelMultipliersWhenPromotionRunsFirst() {
        Map<RecipeCapability<?>, List<Content>> outputs = contentMap(rawContent(13L, 0));

        PrimordialRecipeOutputAmplifier.amplifyAndForceFullChance(
                outputs, FULL_CHANCE, ContentModifier.multiplier(100), this::scaleAfterChanceCheck);
        PrimordialRecipeOutputAmplifier.amplifyAndForceFullChance(
                outputs, FULL_CHANCE, ContentModifier.multiplier(23), this::scaleAfterChanceCheck);

        assertEquals(13L * 100 * 23, amount(outputs, 0));
        assertAllGuaranteed(outputs);
    }

    @Test
    void productionApplyDeepCopiesAndRoutesOnlyNormalAndTickOutputs() throws Exception {
        String source = Files.readString(AMPLIFIER_SOURCE);
        String apply = extractBlock(source, "static GTRecipe apply(GTRecipe recipe, int multiplier) {");

        assertTrue(apply.contains("GTRecipe copy = recipe.copy();"));
        assertTrue(apply.contains("amplifyAndForceFullChance(copy.outputs"));
        assertTrue(apply.contains("amplifyAndForceFullChance(copy.tickOutputs"));
        assertFalse(apply.contains("amplifyAndForceFullChance(copy.inputs"));
        assertFalse(apply.contains("amplifyAndForceFullChance(copy.tickInputs"));
        assertFalse(source.contains("recipe.copy(ContentModifier.multiplier"));
        assertTrue(source.contains("ChanceLogic.getMaxChancedValue()"));
        assertTrue(Pattern.compile("content\\.copy\\(\\s*capability\\s*,\\s*[A-Za-z_$][\\w$]*\\s*\\)")
                .matcher(source).find(), "生产 scaler 必须调用真实 capability 对应的 Content.copy");
        assertTrue(apply.contains("copy.parallels = recipe.parallels;"));
        assertTrue(apply.contains("copy.ocTier = recipe.ocTier;"));
        assertTrue(source.contains("LongIngredient"));
        assertTrue(source.contains("FluidIngredient"));
        assertFalse(source.contains("Ints.saturatedCast"), "不得把 GTLCore 的 long 数量支持错误钳回 int");
        assertFalse(Pattern.compile("Math\\.min\\([^;]*Integer\\.MAX_VALUE").matcher(source).find(),
                "不得把 GTLCore 的 long 数量支持错误钳回 int");
    }

    @Test
    void moduleLogicUsesAmplifiedRecipesForCapacityAndBothWirelessOutputPaths() throws Exception {
        String source = Files.readString(LOGIC_SOURCE);
        String maxParallel = extractBlock(source, "private MatchableScaledRecipe findMaxMatchableScaledRecipe(");
        String wireless = extractBlock(source, "protected WirelessGTRecipe buildFinalWirelessRecipe(");
        String scaledMatch = extractBlock(source, "private MatchableScaledRecipe findMatchableScaledRecipe(");
        String normalizedWireless = wireless.replaceAll("\\s+", " ");

        assertTrue(maxParallel.contains("GTRecipe amplified = amplifyForMountedCore(recipe);"));
        assertTrue(maxParallel.contains("IParallelLogic.getMaxParallel(getMachine(), amplified, limit)"));
        assertTrue(Pattern.compile("IParallelLogic\\.getMinParallel\\(\\s*getMachine\\(\\)\\s*,"
                        + "\\s*amplified\\s*,\\s*[A-Za-z_$][\\w$]*\\s*\\)")
                .matcher(maxParallel).find(), "输出容量必须限制输入可提供的最大并行");

        assertEquals(1, countExact(scaledMatch, "RecipeCalculationHelper.INSTANCE.multipleRecipe("),
                "findMatchableScaledRecipe 只保留防御性兜底重建，二分命中路径由上下文复用 scaledRecipe");
        assertTrue(scaledMatch.contains("context.matchedRecipe"));
        assertTrue(source.contains("private final class ScaledRecipeMatchContext implements LongPredicate"));
        assertTrue(source.contains("matchedRecipe = scaledRecipe"));
        assertTrue(source.contains("matchRecipeInputHandlePartCache(scaledRecipe)"));
        assertTrue(source.contains("RecipeRunnerHelper.matchRecipeOutput(getMachine(), scaledRecipe)"));

        assertTrue(wireless.contains("matchableScaledRecipeCache.remove(recipe)"));
        assertTrue(wireless.contains("matchable = findMatchableScaledRecipe(amplifiedRecipe, parallel)"));
        assertTrue(wireless.contains("IParallelLogic.getRecipeOutputChance(machine, scaledRecipe)"));
        assertTrue(wireless.contains("GTRecipe amplifiedOutputRecipe = amplifyForMountedCore(recipe);"));
        assertTrue(normalizedWireless.contains("multipleRecipe( amplifiedOutputRecipe, parallel)"),
                "必须先把 chance=0 提升为满概率，再做并行缩放");
        assertFalse(wireless.contains("collectOutputs(processedRecipes.get(i)"),
                "已做概率抽取的 processedRecipeList 无法恢复被删除的低概率产出");
        assertEquals(2, countExact(wireless, "calculateRecipeEu(recipe, parallel, euMultiplier)"),
                "两条分支的 EU 都必须继续按未放大的原配方计算");
    }

    private Content scaleAfterChanceCheck(RecipeCapability<?> capability, Content content, ContentModifier modifier) {
        assertEquals(FULL_CHANCE, content.chance, "缩放器收到 Content 前 chance 必须已经满值");
        assertEquals(FULL_CHANCE, content.maxChance, "缩放器收到 Content 前 maxChance 必须已经满值");
        return rawContent(modifier.apply((Number) content.content).longValue(), content.chance);
    }

    @SafeVarargs
    private static Map<RecipeCapability<?>, List<Content>> contentMap(Content... contents) {
        Map<RecipeCapability<?>, List<Content>> map = new HashMap<>();
        map.put(null, new ArrayList<>(List.of(contents)));
        return map;
    }

    private static Content rawContent(long amount, int chance) {
        try {
            Content content = (Content) UNSAFE.allocateInstance(Content.class);
            content.content = amount;
            content.chance = chance;
            content.maxChance = FULL_CHANCE;
            content.tierChanceBoost = 0;
            return content;
        } catch (InstantiationException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertAmounts(Map<RecipeCapability<?>, List<Content>> contents, long... expected) {
        assertEquals(expected.length, contents.get(null).size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], amount(contents, i));
        }
    }

    private static long amount(Map<RecipeCapability<?>, List<Content>> contents, int index) {
        return ((Number) contents.get(null).get(index).content).longValue();
    }

    private static void assertAllGuaranteed(Map<RecipeCapability<?>, List<Content>> contents) {
        for (Content content : contents.get(null)) {
            assertEquals(FULL_CHANCE, content.chance);
            assertEquals(FULL_CHANCE, content.maxChance);
        }
    }

    private static GTRecipe emptyRecipe() {
        return new GTRecipe(null, new ResourceLocation("gt_shanhai", "output_amplifier_test"),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyList(), Collections.emptyList(), new CompoundTag(), 240, false);
    }

    private static Unsafe findUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String extractBlock(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "缺少方法声明: " + declaration);
        int openBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) return source.substring(openBrace, i + 1);
        }
        throw new AssertionError("方法体未闭合: " + declaration);
    }

    private static int countExact(String source, String expected) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(expected, offset)) >= 0) {
            count++;
            offset += expected.length();
        }
        return count;
    }
}
