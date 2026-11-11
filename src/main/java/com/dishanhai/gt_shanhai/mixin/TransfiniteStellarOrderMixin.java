package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.crafting.inv.ListCraftingInventory;
import com.dishanhai.gt_shanhai.common.item.VirtualCraftingInitialItemExtractor;
import com.dishanhai.gt_shanhai.common.item.VirtualCraftingPresenceState;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternCraftingContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.MissingCraftingPlan;
import org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.TransfiniteCraftingLogic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 山海：超限 CPU 下發樣板時帶上 AE 訂單，告警才能在發出後繼續判定完成或取消。 */
@Pseudo
@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.TransfiniteCraftingLogic",
        priority = 1500, remap = false)
public abstract class TransfiniteStellarOrderMixin {
    @Shadow @Final private ListCraftingInventory inventory;

    @Inject(method = "trySubmitJob", at = @At(value = "INVOKE",
            target = "Lorg/gtlcore/gtlcore/integration/ae2/crafting/transfinite/TransfiniteCraftingLogic;extractAvailableInitialItems(Lappeng/api/networking/crafting/ICraftingPlan;Lappeng/api/networking/IGrid;Lappeng/api/networking/security/IActionSource;Lappeng/api/stacks/KeyCounter;)V"),
            cancellable = true, remap = false)
    private void gtShanhai$prepareSupplyForMissingPlan(IGrid grid, ICraftingPlan plan, IActionSource source,
            ICraftingRequester requester, CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        if (!(plan instanceof MissingCraftingPlan missingPlan)
                || !VirtualPatternEncodingHelper.containsPresenceInputs(missingPlan.delegate())) {
            return;
        }

        GenericStack missing = VirtualCraftingInitialItemExtractor.preparePresence(
                missingPlan.delegate(), grid, inventory, source);
        if (missing != null) {
            cir.setReturnValue(CraftingSubmitResult.missingIngredient(missing));
        }
    }

    @WrapOperation(method = "extractAvailableInitialItems", at = @At(value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingPlan;usedItems()Lappeng/api/stacks/KeyCounter;"),
            remap = false)
    private KeyCounter gtShanhai$extractOnlyRealInitialItems(ICraftingPlan plan, Operation<KeyCounter> original,
            @Local(argsOnly = true) IGrid grid, @Local(argsOnly = true) IActionSource source) {
        return VirtualCraftingInitialItemExtractor.realInitialItems(plan, original.call(plan), grid, source);
    }

    @Inject(method = "writeToNbt", at = @At("RETURN"), remap = false)
    private void gtShanhai$writePresence(CompoundTag data, CallbackInfo ci) {
        VirtualCraftingPresenceState.writeToNBT(inventory, data);
    }

    @Inject(method = "readFromNbt", at = @At("RETURN"), remap = false)
    private void gtShanhai$readPresence(CompoundTag data, CallbackInfo ci) {
        VirtualCraftingPresenceState.readFromNBT(inventory, data);
    }

    @Inject(method = "finishJob", at = @At("RETURN"), remap = false)
    private void gtShanhai$clearPresence(boolean canceled, CallbackInfo ci) {
        VirtualCraftingPresenceState.clear(inventory);
    }

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
