package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Author: dishanhai. These contracts do not replace a woven Minecraft runtime test.
class VirtualCatalystMatchingSourceTest {

    private static String itemSource(String name) throws IOException {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/item", name + ".java"));
    }

    private static String mixinSource(String name) throws IOException {
        Path path = Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin", name + ".java");
        return Files.exists(path) ? Files.readString(path) : "";
    }

    @Test
    void cacheFallbackMatchesActualVirtualKeysAndChecksAmounts() throws IOException {
        String source = mixinSource("SlotCacheManagerVirtualProviderMixin");
        assertTrue(source.contains("getBestItemMatchSimulate"),
                "The unconfigured stocking path must match actual virtual item keys.");
        assertTrue(source.contains("key.matches(ingredient)"));
        assertTrue(source.contains("getBestFluidMatchSimulate"));
        assertTrue(source.contains("AEUtils.testFluidIngredient(ingredient, key)"));
        assertTrue(source.contains("VirtualPatternBufferSlotState.findMatchingVirtualTarget"));
        assertTrue(source.contains("VirtualPatternBufferSlotState.getVirtualTargets"),
                "Fallback must be limited to registered virtual targets, not all network stock.");
    }

    @Test
    void configuredStellarStockingAlsoChecksVirtualTargets() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/part",
                "RecipeTypePatternBufferPartMachine.java"));
        assertTrue(source.contains("VirtualPatternBufferSlotState.findMatchingVirtualTarget"),
                "Configured stocking cannot only inspect ingredient samples and configured keys.");
        assertTrue(source.contains("internal instanceof Object2LongOpenHashMap"),
                "Virtual identity must belong to the current slot inventory.");
        assertTrue(source.contains("VirtualPatternBufferSlotState.withoutVirtualCatalystMirrors"),
                "The configured path must not add the same virtual presence twice.");
        assertTrue(source.contains("AEFluidKey.of(stack.getFluid(), stack.getTag())"),
                "Configured fluid samples must retain their NBT.");
    }

    @Test
    void cleanupCannotRunBeforeTheWholeRecipeHasSucceeded() throws IOException {
        String slot = mixinSource("GTLCorePatternInternalSlotVirtualProviderMixin").replace("\r\n", "\n");
        String handler = mixinSource("GTLCoreMEPatternBufferRecipeHandlerVirtualProviderMixin");
        assertFalse(slot.contains("@Inject(method = \"handleItemInternal\""),
                "Item completion cannot clear fluid presence before fluid handling.");
        assertFalse(slot.contains("@Inject(method = \"handleFluidInternal\""),
                "Per-capability completion is not the recipe transaction boundary.");
        assertFalse(handler.contains("access.gtShanhai$stripVirtualTargets()"),
                "Item handler completion cannot clear both capabilities.");
        String whole = mixinSource("MEPatternRecipeHandlePartVirtualProviderMixin");
        assertTrue(whole.contains("simulate || cir.getReturnValueI() < 0"));
        assertTrue(whole.contains("PatternRecipeExecutionGuard.isAuxiliaryIORecipe(recipe)"));
        assertTrue(whole.contains("access.gtShanhai$stripVirtualTargetsInSlot(cir.getReturnValueI())"));
        assertTrue(slot.contains("gtShanhai$stripVirtualFluids();\n        gtShanhai$clearCatalystsIfDepleted();"),
                "Depletion must be checked after both virtual inventories are stripped.");
    }

    @Test
    void activeRecipeContextIsOwnedByTheWholeRecipeBoundary() throws IOException {
        String whole = mixinSource("MEPatternRecipeHandlePartVirtualProviderMixin");
        assertTrue(whole.contains("@At(\"HEAD\")"));
        assertTrue(whole.contains("PatternNotConsumableFilter.setActiveRecipe(recipe)"));
        assertTrue(whole.contains("finally"));
        assertTrue(whole.contains("PatternNotConsumableFilter.clearActiveRecipe()"));
        assertFalse(mixinSource("MEPatternBufferItemRecipeTypeFilterMixin").contains("setActiveRecipe(recipe)"));
        assertFalse(mixinSource("MEPatternBufferFluidRecipeTypeFilterMixin").contains("setActiveRecipe(recipe)"));
    }

    @Test
    void retainedFluidCatalystsPreserveTheirNbt() throws IOException {
        String source = itemSource("PatternNotConsumableFilter");
        assertTrue(source.contains("FluidStack.create(fluidKey.getFluid(), 1L, fluidKey.getTag())"),
                "A tagged fluid catalyst cannot be checked as an untagged fluid.");
        assertTrue(source.contains("ACTIVE_RECIPE.remove()"));
    }

    @Test
    void cacheFallbackMixinIsRegistered() throws IOException {
        String config = Files.readString(Path.of("src/main/resources/gt_shanhai.mixin.json"));
        assertTrue(config.contains("\"SlotCacheManagerVirtualProviderMixin\""));
        assertFalse(config.contains("\"GTLCoreMEPatternBufferRecipeHandlerVirtualProviderMixin\""),
                "The obsolete per-item cleanup hook must not be woven.");
    }
}
