package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TransfiniteSupplyPresenceSourceTest {

    @Test
    void forceSubmittingAMissingTreePlanChecksAndRegistersItsSupplierBeforeExtractingItems() throws IOException {
        String mixin = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin",
                "TransfiniteStellarOrderMixin.java"));
        assertTrue(mixin.contains("@Inject(method = \"trySubmitJob\", at = @At(value = \"INVOKE\""));
        assertTrue(mixin.contains("extractAvailableInitialItems(Lappeng/api/networking/crafting/ICraftingPlan;"));
        assertTrue(mixin.contains("VirtualPatternEncodingHelper.containsPresenceInputs(missingPlan.delegate())"));
        assertTrue(mixin.contains("VirtualCraftingInitialItemExtractor.preparePresence("));
        assertTrue(mixin.contains("missingPlan.delegate(), grid, inventory, source)"));
        assertTrue(mixin.contains("cir.setReturnValue(CraftingSubmitResult.missingIngredient(missing))"));
        assertTrue(mixin.contains("@WrapOperation(method = \"extractAvailableInitialItems\""));
        assertTrue(mixin.contains("VirtualCraftingInitialItemExtractor.realInitialItems(plan, original.call(plan), grid, source)"));
    }

    @Test
    void transfiniteTreePresenceSurvivesSaveAndLoadAndIsClearedOnFinish() throws IOException {
        String mixin = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin",
                "TransfiniteStellarOrderMixin.java"));
        assertTrue(mixin.contains("@Inject(method = \"writeToNbt\", at = @At(\"RETURN\")"));
        assertTrue(mixin.contains("VirtualCraftingPresenceState.writeToNBT(inventory, data)"));
        assertTrue(mixin.contains("@Inject(method = \"readFromNbt\", at = @At(\"RETURN\")"));
        assertTrue(mixin.contains("VirtualCraftingPresenceState.readFromNBT(inventory, data)"));
        assertTrue(mixin.contains("@Inject(method = \"finishJob\", at = @At(\"RETURN\")"));
        assertTrue(mixin.contains("VirtualCraftingPresenceState.clear(inventory)"));
    }
}
