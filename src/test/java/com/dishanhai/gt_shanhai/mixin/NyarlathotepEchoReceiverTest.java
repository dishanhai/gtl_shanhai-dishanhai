package com.dishanhai.gt_shanhai.mixin;

import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：真實GTRecipe副本的輸入不變、增產／加速與原始內容隔離。 */
class NyarlathotepEchoReceiverTest {
    private GTRecipe recipe() {
        GTRecipe recipe = new GTRecipe(null, new ResourceLocation("gt_shanhai", "echo_receiver_test"),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                List.of(), List.of(), new CompoundTag(), 300, false);
        recipe.inputs.put(null, List.of(NumberContent.create(7)));
        recipe.outputs.put(null, List.of(NumberContent.create(11)));
        recipe.tickInputs.put(null, List.of(NumberContent.create(13)));
        recipe.tickOutputs.put(null, List.of(NumberContent.create(17)));
        recipe.parallels = 8;
        recipe.ocTier = 3;
        return recipe;
    }

    private GTRecipe apply(GTRecipe recipe, int output, int power) throws Exception {
        Method method = BroadcastEffectBuildRecipeMixin.class.getDeclaredMethod(
                "gtShanhai$applyReceiverEffects", GTRecipe.class, int.class, int.class);
        method.setAccessible(true);
        return (GTRecipe) method.invoke(null, recipe, output, power);
    }

    @Test void receiverNeverPaysTheSourcesFivefoldInputCosts() throws Exception {
        GTRecipe original = recipe(), result = apply(original, 5, 100);
        assertNotSame(original, result);
        assertEquals(7L, result.inputs.get(null).getFirst().content);
        assertEquals(13L, result.tickInputs.get(null).getFirst().content);
        assertEquals(55L, result.outputs.get(null).getFirst().content);
        assertEquals(85L, result.tickOutputs.get(null).getFirst().content);
        assertEquals(200, result.duration); // 接收端最多50%加速，不套用廣播源÷5。
        assertEquals(8, result.parallels);
        assertEquals(3, result.ocTier);
        assertEquals(300, original.duration);
        assertEquals(11L, original.outputs.get(null).getFirst().content);
        assertEquals(17L, original.tickOutputs.get(null).getFirst().content);
        assertNotSame(original.data, result.data);
    }

    @Test void receiverHasNoCostOrDurationChangeWithoutAnEchoSpeedSource() throws Exception {
        GTRecipe original = recipe(), result = apply(original, 3, 0);
        assertEquals(300, result.duration);
        assertEquals(7L, result.inputs.get(null).getFirst().content);
        assertEquals(33L, result.outputs.get(null).getFirst().content);
    }

    @Test void setupCopiesTheArgumentAndExcludesTheBroadcastRecipe() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin/BroadcastEffectBuildRecipeMixin.java"));
        assertTrue(source.contains("priority = 1500"), "Must inject after GTLCore setupRecipe overwrite");
        assertTrue(source.contains("@ModifyVariable"));
        assertTrue(source.contains("argsOnly = true"));
        assertTrue(source.contains("voice_of_the_faceless"));
        assertTrue(source.contains("gtShanhai$broadcastBase"), "Repeated setup must recalculate from unmodified input");
    }

    private static final class NumberContent extends Content {
        NumberContent(long amount) { super(amount, 10000, 10000, 0, null, null); }
        static NumberContent create(long amount) {
            try {
                // Content建構會初始化未remap的ChanceLogic／GTRegistries；只隔離該能力內容替身。
                Field field = Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                NumberContent result = (NumberContent) ((Unsafe) field.get(null)).allocateInstance(NumberContent.class);
                result.content = amount;
                result.chance = result.maxChance = 10000;
                return result;
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
        @Override public Content copy(RecipeCapability<?> capability, ContentModifier modifier) {
            long amount = ((Number) content).longValue();
            return create(modifier == null ? amount : modifier.apply(amount).longValue());
        }
    }
}
