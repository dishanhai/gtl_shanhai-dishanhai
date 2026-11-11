package com.dishanhai.gt_shanhai.mixin;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.menu.me.crafting.CraftingStatusMenu;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingCPU;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingStatusMenu.class, remap = false)
public abstract class QuantumCraftingStatusMenuMixin {

    @Unique
    private static final long gtShanhai$QUANTUM_CPU_LIST_SYNC_INTERVAL = 5L;

    @Unique
    private long gtShanhai$lastQuantumCpuListSyncTick = Long.MIN_VALUE;

    @Inject(method = "setCPU", at = @At("TAIL"))
    private void gtShanhai$rememberSelectedCpu(ICraftingCPU selectedCpu, CallbackInfo ci) {
        CraftingStatusMenuAccessor accessor = (CraftingStatusMenuAccessor) this;
        accessor.gtShanhai$setSelectedCpuRaw(selectedCpu);
        if (selectedCpu == null) {
            accessor.gtShanhai$setSelectedCpuSerialRaw(-1);
        }
        gtShanhai$lastQuantumCpuListSyncTick = Long.MIN_VALUE;
    }

    // 本项目无 refmap：MC 继承方法用 SRG 名 + remap=false。broadcastChanges = m_38946_
    @Inject(method = "m_38946_", at = @At("HEAD"), remap = false, require = 0)
    private void gtShanhai$refreshSelectedQuantumCpuStatus(CallbackInfo ci) {
        CraftingStatusMenuAccessor accessor = (CraftingStatusMenuAccessor) (Object) this;
        ICraftingCPU selectedCpu = accessor.gtShanhai$getSelectedCpuRaw();
        if (selectedCpu instanceof QuantumCraftingCPU quantumCpu && quantumCpu.isBusy()
                && quantumCpu.getGrid() != null && !quantumCpu.getCluster().isDestroyed()) {
            gtShanhai$syncQuantumCpuListIfNeeded(accessor, quantumCpu);
        }
    }

    @Unique
    private void gtShanhai$syncQuantumCpuListIfNeeded(CraftingStatusMenuAccessor accessor, QuantumCraftingCPU cpu) {
        Level level = cpu.getLevel();
        if (level == null) {
            return;
        }
        long tick = level.getGameTime();
        if (gtShanhai$lastQuantumCpuListSyncTick != Long.MIN_VALUE
                && tick - gtShanhai$lastQuantumCpuListSyncTick < gtShanhai$QUANTUM_CPU_LIST_SYNC_INTERVAL) {
            return;
        }
        gtShanhai$lastQuantumCpuListSyncTick = tick;
        accessor.gtShanhai$setCpuListRaw(accessor.gtShanhai$callCreateCpuList());
    }
}
