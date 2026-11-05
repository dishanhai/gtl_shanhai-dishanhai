package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCorePrimordialRecipeTypes;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.cloud.CloudPatternAllocator$Scan", remap = false)
public abstract class EnhancedCorePrimordialCloudScanTypesMixin {
    @Shadow @Final private WorkableElectricMultiblockMachine machine;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target =
            "Lcom/gregtechceu/gtceu/api/machine/MultiblockMachineDefinition;getRecipeTypes()[Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;"))
    private GTRecipeType[] shanhai$selectedTypes(MultiblockMachineDefinition definition) {
        return EnhancedCorePrimordialRecipeTypes.selected(machine, definition);
    }
}
