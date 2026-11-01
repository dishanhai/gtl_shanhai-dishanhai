package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingClient;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 山海：只有真正讀取世界資料時開始，不把主選單或維度切換當作登入。 */
@Mixin(value = WorldOpenFlows.class, remap = false)
public abstract class ShanhaiLoadingWorldMixin {
    // Forge 的五參數 overload 涵蓋玩家選存檔、備份確認後繼續與資料包重試。
    @Inject(method = "doLoadLevel(Lnet/minecraft/client/gui/screens/Screen;Ljava/lang/String;ZZZ)V", at = @At("HEAD"), remap = false)
    private void shanhai$selectedWorld(CallbackInfo ci) { ShanhaiLoadingClient.beginWorld(); }

    @Inject(method = {"createFreshLevel", "m_233157_"}, at = @At("HEAD"), remap = false)
    private void shanhai$createFreshWorld(CallbackInfo ci) { ShanhaiLoadingClient.beginWorld(); }

    @Inject(method = {"loadWorldDataBlocking", "m_246486_"}, at = @At("HEAD"), remap = false)
    private void shanhai$beginWorld(CallbackInfoReturnable<?> cir) { ShanhaiLoadingClient.loadingWorldData(); }

    @Inject(method = {"loadWorldDataBlocking", "m_246486_"}, at = @At("RETURN"), remap = false)
    private void shanhai$worldDataReady(CallbackInfoReturnable<?> cir) { ShanhaiLoadingClient.dataReady(); }

    @Inject(method = {"createLevelFromExistingSettings", "m_245064_"}, at = @At("HEAD"), remap = false)
    private void shanhai$createPreparedWorld(LevelStorageSource.LevelStorageAccess storage, ReloadableServerResources resources,
            LayeredRegistryAccess<RegistryLayer> registries, WorldData data, CallbackInfo ci) {
        ShanhaiLoadingClient.beginPreparedWorld(resources.getRecipeManager().getRecipes().size());
    }
}
