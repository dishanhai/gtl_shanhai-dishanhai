package com.dishanhai.gt_shanhai.common.machine.preview;

import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：真實GTRecipe複製／成本／時長，能力內容以數值替身隔離未remap的Forge登錄表。 */
class ShanhaiNyarlathotepEchoRecipeScaleTest {
    private GTRecipe apply(GTRecipe recipe, double factor) throws Exception {
        return (GTRecipe) Class.forName("com.dishanhai.gt_shanhai.common.machine.preview.ShanhaiNyarlathotepEchoRecipeScale")
                .getMethod("apply", GTRecipe.class, double.class).invoke(null, recipe, factor);
    }
    private static MarkerContent content(long amount, int chance) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        MarkerContent result = (MarkerContent) ((Unsafe) field.get(null)).allocateInstance(MarkerContent.class);
        result.content = amount; result.chance = chance; result.maxChance = 10000;
        return result;
    }
    private GTRecipe recipe(int duration) throws Exception {
        GTRecipe recipe = new GTRecipe(null, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), new CompoundTag(), duration, false);
        recipe.inputs.put(null, List.of(content(20, 10000), content(32, 0)));
        recipe.tickInputs.put(null, List.of(content(131072, 10000)));
        recipe.outputs.put(null, List.of(content(7, 10000)));
        return recipe;
    }
    @Test void linked64CostsFiveTimesAndBroadcastLastsOneFifthWithoutMutatingTheSource() throws Exception {
        GTRecipe raw = recipe(1000), scaled = apply(raw, 5);
        assertNotSame(raw, scaled);
        assertEquals(100L, scaled.inputs.get(null).get(0).content);
        assertEquals(32L, scaled.inputs.get(null).get(1).content, "Catalysts are not consumed or multiplied");
        assertEquals(655360L, scaled.tickInputs.get(null).get(0).content);
        assertEquals(7L, scaled.outputs.get(null).get(0).content, "Source scaling never changes receiver or output amounts");
        assertEquals(200, scaled.duration);
        assertEquals(20L, raw.inputs.get(null).get(0).content);
        assertEquals(1000, raw.duration);
        assertTrue(raw.data.isEmpty());
    }
    @Test void offlineFactorKeepsOriginalRecipeAndFractionalDurationsRoundUp() throws Exception {
        GTRecipe raw = recipe(1001);
        assertEquals(1001, apply(raw, 1).duration);
        assertEquals(201, apply(raw, 5).duration);
        assertEquals(1, apply(recipe(1), 5).duration);
    }
    private static class MarkerContent extends Content {
        MarkerContent() { super(null, 10000, 10000, 0, null, null); }
        @Override public Content copy(RecipeCapability<?> capability, ContentModifier modifier) {
            try {
                long amount = ((Number) content).longValue();
                return ShanhaiNyarlathotepEchoRecipeScaleTest.content(chance == 0 || modifier == null ? amount
                        : (long) Math.ceil(modifier.apply(amount).doubleValue()), chance);
            } catch (Exception error) { throw new AssertionError(error); }
        }
    }
}
