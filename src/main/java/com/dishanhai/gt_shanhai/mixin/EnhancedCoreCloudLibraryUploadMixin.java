package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCoreCloudLibraryCompat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** 山海：伺服器再次驗證後，完整保留 inbox WAL/扣除/恢復流程。 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.cloud.CloudPatternLibraryMachine", remap = false)
public class EnhancedCoreCloudLibraryUploadMixin {
    @Redirect(method = "uploadInbox", at = @At(value = "INVOKE", target = "Lappeng/api/crafting/PatternDetailsHelper;decodePattern(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;)Lappeng/api/crafting/IPatternDetails;"), require = 0)
    private IPatternDetails gtShanhai$validate(ItemStack stack, Level level) {
        return EnhancedCoreCloudLibraryCompat.validationDetails(stack, level);
    }
}
