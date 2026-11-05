package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCoreCloudLibraryCompat;
import com.gtl.enhancedcore.common.cloud.store.CloudPatternStore;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** 山海：完整母樣板摘要覆蓋舊版 displaySchema=3 快取。 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.cloud.CloudLibraryService", remap = false)
public class EnhancedCoreCloudLibraryMetadataMixin {
    @Inject(method = "metadata", at = @At("HEAD"), cancellable = true, require = 0)
    private static void gtShanhai$metadata(CloudPatternStore.Entry entry, ServerLevel level, CallbackInfoReturnable<CompoundTag> cir) {
        if (EnhancedCoreCloudLibraryCompat.mother(entry.original()))
            cir.setReturnValue(EnhancedCoreCloudLibraryCompat.metadata(entry, level));
    }

    @Inject(method = "identify", at = @At("HEAD"), cancellable = true, require = 0)
    private static void gtShanhai$types(CloudPatternStore.Entry entry, UUID actor, CallbackInfo ci) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && EnhancedCoreCloudLibraryCompat.mother(entry.original())) {
            entry.setRecipeTypes(actor, EnhancedCoreCloudLibraryCompat.types(entry.original(), server.overworld()));
            ci.cancel();
        }
    }

    @WrapOperation(method = "catalogRow", at = @At(value = "INVOKE",
            target = "Ljava/lang/String;toLowerCase(Ljava/util/Locale;)Ljava/lang/String;"), require = 0)
    private static String gtShanhai$completeSearchIndex(String text, Locale locale, Operation<String> original,
                                                       CloudPatternStore.Entry entry, ServerLevel level) {
        if (EnhancedCoreCloudLibraryCompat.mother(entry.original()))
            text += " " + EnhancedCoreCloudLibraryCompat.searchText(entry, level);
        return original.call(text, locale);
    }

    @WrapOperation(method = "terminalSide", at = @At(value = "INVOKE",
            target = "Ljava/lang/StringBuilder;toString()Ljava/lang/String;"), require = 0)
    private static String gtShanhai$completeTerminalNames(StringBuilder keys, Operation<String> original,
                                                         CompoundTag display, String prefix) {
        return EnhancedCoreCloudLibraryCompat.terminalKeys(original.call(keys), display, prefix);
    }

    @Inject(method = "reloaded", at = @At("HEAD"), require = 0)
    private static void gtShanhai$reload(net.minecraftforge.event.OnDatapackSyncEvent event, CallbackInfo ci) {
        if (event.getPlayer() == null) EnhancedCoreCloudLibraryCompat.clearProbes();
    }

    @Inject(method = "stopped", at = @At("HEAD"), require = 0)
    private static void gtShanhai$clear(CallbackInfo ci) { EnhancedCoreCloudLibraryCompat.clearProbes(); }
}
