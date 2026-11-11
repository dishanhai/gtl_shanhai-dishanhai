package com.dishanhai.gt_shanhai.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.CraftingSimulationState;
import com.dishanhai.gt_shanhai.common.item.VirtualProviderSoftLocks;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.ToLongFunction;

@Mixin(value = CraftingCalculation.class, remap = false)
public abstract class CraftingCalculationVirtualSupplyMixin implements VirtualProviderSoftLocks.CalculationAccess {

    @Unique
    private ToLongFunction<AEKey> gtShanhai$supplyPresence;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtShanhai$captureSupplyPresence(Level level, IGrid grid, ICraftingSimulationRequester requester,
            GenericStack output, CalculationStrategy strategy, CallbackInfo ci) {
        this.gtShanhai$supplyPresence = VirtualProviderSoftLocks.snapshotSupplyPresence(grid);
    }

    @Override
    public long gtShanhai$getSupplyPresence(AEKey key) {
        return gtShanhai$supplyPresence.applyAsLong(key);
    }

    @Mixin(value = {CraftingSimulationState.class, CraftingPlan.class}, remap = false)
    public static class Requirements implements VirtualProviderSoftLocks.RequirementsAccess {

        @Unique
        private Object2LongOpenHashMap<AEKey> gtShanhai$supplyRequirements;

        @Override
        public void gtShanhai$requireSupply(AEKey key, long amount) {
            if (gtShanhai$supplyRequirements == null) {
                gtShanhai$supplyRequirements = new Object2LongOpenHashMap<>();
            }
            if (gtShanhai$supplyRequirements.getLong(key) < amount) {
                gtShanhai$supplyRequirements.put(key, amount);
            }
        }

        @Override
        public Object2LongMap<AEKey> gtShanhai$getSupplyRequirements() {
            return gtShanhai$supplyRequirements;
        }
    }

    @Mixin(value = CraftingSimulationState.class, remap = false)
    public static class StatePropagation {

        @Inject(method = "applyDiff", at = @At("RETURN"), remap = false)
        private void gtShanhai$mergeSupplyRequirements(CraftingSimulationState parent, CallbackInfo ci) {
            gtShanhai$copySupplyRequirements(this, parent);
        }

        @Inject(method = "buildCraftingPlan", at = @At("RETURN"), remap = false)
        private static void gtShanhai$saveSupplyRequirements(CraftingSimulationState state,
                CraftingCalculation calculation, long calculatedAmount, CallbackInfoReturnable<CraftingPlan> cir) {
            gtShanhai$copySupplyRequirements(state, cir.getReturnValue());
        }

        @Unique
        private static void gtShanhai$copySupplyRequirements(Object source, Object destination) {
            var from = (VirtualProviderSoftLocks.RequirementsAccess) source;
            var to = (VirtualProviderSoftLocks.RequirementsAccess) destination;
            var requirements = from.gtShanhai$getSupplyRequirements();
            if (requirements == null) return;
            for (Object2LongMap.Entry<AEKey> entry : requirements.object2LongEntrySet()) {
                to.gtShanhai$requireSupply(entry.getKey(), entry.getLongValue());
            }
        }
    }
}
