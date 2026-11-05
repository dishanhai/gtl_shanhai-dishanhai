package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCoreCloudLibraryCompat;
import com.gtl.enhancedcore.common.cloud.store.CloudPatternStore;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 山海：既存母原件重載後分類也讀完整類型，不能依舊持久化摘要。 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.cloud.store.CloudPatternStore$Entry", remap = false)
public class EnhancedCoreCloudLibraryEntryMixin {
    @Inject(method = "recipeTypes", at = @At("HEAD"), cancellable = true, require = 0)
    private void gtShanhai$allTypes(CallbackInfoReturnable<Set<ResourceLocation>> cir) {
        var entry = (CloudPatternStore.Entry)(Object)this;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && EnhancedCoreCloudLibraryCompat.mother(entry.original()))
            cir.setReturnValue(EnhancedCoreCloudLibraryCompat.types(entry.original(), server.overworld()));
    }
}
