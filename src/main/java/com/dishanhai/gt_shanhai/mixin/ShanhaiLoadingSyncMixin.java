package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 山海：僅採用目前連線在主執行緒套用的配方包，晚到舊封包不能污染新會話。 */
@Mixin(value = ClientPacketListener.class, remap = false)
public abstract class ShanhaiLoadingSyncMixin {
    @Inject(method = {"handleUpdateRecipes", "m_6327_"}, at = @At("HEAD"), remap = false)
    private void shanhai$syncBegin(ClientboundUpdateRecipesPacket packet, CallbackInfo ci) {
        ShanhaiLoadingClient.syncRecipes((ClientPacketListener) (Object) this, packet.getRecipes().size(), false);
    }

    @Inject(method = {"handleUpdateRecipes", "m_6327_"}, at = @At("RETURN"), remap = false)
    private void shanhai$syncEnd(ClientboundUpdateRecipesPacket packet, CallbackInfo ci) {
        ShanhaiLoadingClient.syncRecipes((ClientPacketListener) (Object) this, packet.getRecipes().size(), true);
    }
}
