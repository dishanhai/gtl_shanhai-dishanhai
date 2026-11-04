package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternCraftingContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 山海：圖合成 CPU 下發樣板時帶上 AE 訂單，告警才能在發出後繼續判定完成或取消。 */
@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.GtlExecutionAdapter", priority = 1500, remap = false)
public abstract class GtlCoreGraphStellarOrderMixin {
    @Shadow
    private CraftingLink link;
    @Shadow
    private org.gtlcore.gtlcore.integration.ae2.graph.GraphCpuHost host;

    @Redirect(method = "push", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"),
            remap = false)
    private boolean gtShanhai$pushPatternWithGraphOrder(ICraftingProvider provider, IPatternDetails details, KeyCounter[] inputs) {
        Integer playerId = null;
        if (host != null && host.source() != null) {
            var player = host.source().player().orElse(null);
            if (player instanceof ServerPlayer serverPlayer) playerId = IPlayerRegistry.getPlayerId(serverPlayer);
        }
        String cpu = "AE CPU";
        if (host != null && host.cpu() != null) {
            Component name = host.cpu().getName();
            if (name != null && !name.getString().isBlank()) cpu = name.getString();
        }
        StellarPatternCraftingContext.pushOrder(new StellarPatternCraftingContext.Order(
                link, null, () -> 0L, playerId, cpu));
        try {
            return provider.pushPattern(details, inputs);
        } finally {
            StellarPatternCraftingContext.pop();
        }
    }
}
