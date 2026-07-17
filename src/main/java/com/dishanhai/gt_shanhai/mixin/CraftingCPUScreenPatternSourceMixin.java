package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEKey;
import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.menu.me.crafting.CraftingStatus;
import appeng.menu.me.crafting.CraftingStatusEntry;

import com.dishanhai.gt_shanhai.client.PatternSourceClientCache;
import com.dishanhai.gt_shanhai.network.PatternSourceRequestPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 合成状态详情页物品溯源的客户端触发点：收到状态更新时把没缓存过的物品向服务端请求一次
 * （{@link PatternSourceRequestPacket}）。右键传送点击的注入见 {@link AEBaseScreenPatternSourceClickMixin}
 * ——m_6375_（mouseClicked）是 AEBaseScreen 声明的，不是 CraftingCPUScreen 自己声明的，不能挂在这个类上。
 */
@Mixin(value = CraftingCPUScreen.class, remap = false)
public abstract class CraftingCPUScreenPatternSourceMixin {

    // 本项目无 refmap：postUpdate 是 CraftingCPUScreen 自己声明的 AE2 方法，用真实名，remap=false。
    @Inject(method = "postUpdate", at = @At("TAIL"), remap = false)
    private void gtShanhai$requestPatternSources(CraftingStatus status, CallbackInfo ci) {
        List<AEKey> toRequest = null;
        for (CraftingStatusEntry entry : status.getEntries()) {
            AEKey what = entry.getWhat();
            if (what == null || PatternSourceClientCache.isKnownOrPending(what)) continue;
            if (toRequest == null) toRequest = new ArrayList<>();
            toRequest.add(what);
        }
        if (toRequest == null || toRequest.isEmpty()) return;
        PatternSourceClientCache.markPending(toRequest);
        ShanhaiNetwork.CHANNEL.sendToServer(new PatternSourceRequestPacket(toRequest));
    }
}
