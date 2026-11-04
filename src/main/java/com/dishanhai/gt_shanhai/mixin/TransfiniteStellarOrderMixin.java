package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternCraftingContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.TransfiniteCraftingLogic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 山海：超限 CPU 下發樣板時帶上 AE 訂單，告警才能在發出後繼續判定完成或取消。 */
@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.TransfiniteCraftingLogic",
        priority = 1500, remap = false)
public abstract class TransfiniteStellarOrderMixin {
    @Redirect(method = "executeCrafting", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"),
            remap = false)
    private boolean gtShanhai$pushPatternWithTransfiniteOrder(ICraftingProvider provider, IPatternDetails details,
            KeyCounter[] inputs) {
        TransfiniteCraftingLogic self = (TransfiniteCraftingLogic) (Object) this;
        CraftingLink link = self.getLastLink() instanceof CraftingLink crafting ? crafting : null;
        Integer playerId = null;
        if (self.source() != null) {
            var player = self.source().player().orElse(null);
            if (player instanceof ServerPlayer serverPlayer) playerId = IPlayerRegistry.getPlayerId(serverPlayer);
        }
        String cpu = "Transfinite CPU";
        if (self.cpu() != null) {
            Component name = self.cpu().getName();
            if (name != null && !name.getString().isBlank()) cpu = name.getString();
        }
        StellarPatternCraftingContext.pushOrder(link == null ? null : new StellarPatternCraftingContext.Order(
                link, self.getFinalJobOutput(), () -> 0L, playerId, cpu));
        try {
            return provider.pushPattern(details, inputs);
        } finally {
            StellarPatternCraftingContext.pop();
        }
    }
}
