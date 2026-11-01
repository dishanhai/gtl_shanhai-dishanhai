package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.client.loading.ShanhaiLoadingClient;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 山海：顯示原生握手細節，不改變連線或取消流程。 */
@Mixin(value = ConnectScreen.class, remap = false)
public abstract class ShanhaiLoadingConnectMixin {
    @Inject(method = {"updateStatus", "m_95717_"}, at = @At("HEAD"), remap = false)
    private void shanhai$connectionStatus(Component status, CallbackInfo ci) {
        ShanhaiLoadingClient.connectionStatus((ConnectScreen) (Object) this, status);
    }
}
