package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCoreCloudLibraryCompat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** 山海：只替換 inbox validator 的解碼；權限、重複檢查仍走原方法。 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.cloud.CloudLibraryInventories$2", remap = false)
public class EnhancedCoreCloudLibraryInboxMixin {
    @Redirect(method = "isItemValid", at = @At(value = "INVOKE", target = "Lappeng/api/crafting/PatternDetailsHelper;decodePattern(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;)Lappeng/api/crafting/IPatternDetails;"), require = 0)
    private IPatternDetails gtShanhai$validate(ItemStack stack, Level level) {
        return EnhancedCoreCloudLibraryCompat.validationDetails(stack, level);
    }
}
