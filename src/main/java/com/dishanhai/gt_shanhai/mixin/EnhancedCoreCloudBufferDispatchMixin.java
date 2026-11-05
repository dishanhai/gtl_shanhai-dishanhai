package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import com.dishanhai.gt_shanhai.common.item.SelectedRecipePattern;
import appeng.api.stacks.*;
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCoreCloudLibraryCompat;
import com.gregtechceu.gtceu.api.recipe.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** 山海：精確子 ID 只在同步派單範圍生效；finally 保證拒單及異常皆清理。 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.recipe.iv.IvBuffers", remap = false)
public class EnhancedCoreCloudBufferDispatchMixin {
    @WrapMethod(method = "push(Lorg/gtlcore/gtlcore/common/machine/multiblock/part/ae/MEPatternBufferPartMachine;Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z")
    private static boolean gtShanhai$exactDispatch(MEPatternBufferPartMachine buffer, IPatternDetails pattern, KeyCounter[] inputs,
                                                   Operation<Boolean> original) {
        String previous = EnhancedCoreCloudLibraryCompat.enterDispatch(pattern);
        try { return original.call(buffer, pattern, inputs); }
        finally { EnhancedCoreCloudLibraryCompat.leaveDispatch(previous); }
    }

    @WrapOperation(method = "push(Lorg/gtlcore/gtlcore/common/machine/multiblock/part/ae/MEPatternBufferPartMachine;Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z",
            at = @At(value = "INVOKE", target = "Lcom/gtl/enhancedcore/common/cloud/CloudPatternMatching;candidates(Lnet/minecraft/world/level/Level;Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;Ljava/util/Map;)Ljava/util/List;"), require = 0)
    private static List<GTRecipe> gtShanhai$pinCandidate(Level level, GTRecipeType type, Map<AEKey, Long> outputs,
                                                        Operation<List<GTRecipe>> original) {
        var recipes = original.call(level, type, outputs); String id = EnhancedCoreCloudLibraryCompat.dispatchRecipe();
        return id == null ? recipes : recipes.stream().filter(recipe -> recipe.id.toString().equals(id)).toList();
    }

    @WrapOperation(method = "declaredCircuits", at = @At(value = "INVOKE", target = "Lappeng/api/crafting/PatternDetailsHelper;decodePattern(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;)Lappeng/api/crafting/IPatternDetails;"), require = 0)
    private static IPatternDetails gtShanhai$childCircuits(ItemStack stack, Level level, Operation<IPatternDetails> original) {
        String id = EnhancedCoreCloudLibraryCompat.dispatchRecipe();
        if (id != null && EnhancedCoreCloudLibraryCompat.mother(stack)) {
            var child = EnhancedCoreCloudLibraryCompat.child(stack, level, id);
            return child == null ? null : child.pattern();
        }
        return original.call(stack, level);
    }
}
