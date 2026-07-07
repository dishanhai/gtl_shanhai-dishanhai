package com.dishanhai.gt_shanhai.mixin;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.menu.me.crafting.CraftingStatusMenu;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingCPU;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumDiagnostics;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

    @Shadow
    protected abstract void setCPU(ICraftingCPU cpu);

    // 本项目无 refmap：MC 继承方法用 SRG 名 + remap=false。broadcastChanges = m_38946_
    @Inject(method = "m_38946_", at = @At("HEAD"), remap = false, require = 0)
    private void gtShanhai$bindBusyQuantumCpuBeforeStatusUpdate(CallbackInfo ci) {
        CraftingStatusMenuAccessor accessor = (CraftingStatusMenuAccessor) (Object) this;
        ICraftingCPU selectedCpu = accessor.gtShanhai$getSelectedCpuRaw();
        if (selectedCpu instanceof QuantumCraftingCPU && selectedCpu.isBusy()) {
            gtShanhai$syncQuantumCpuListIfNeeded(accessor, (QuantumCraftingCPU) selectedCpu);
            return;
        }

        QuantumCraftingCPU busyCpu = gtShanhai$findBusyQuantumCpu(accessor);
        if (busyCpu == null || busyCpu == selectedCpu) {
            return;
        }

        setCPU(busyCpu);
        gtShanhai$lastQuantumCpuListSyncTick = Long.MIN_VALUE;
        gtShanhai$syncQuantumCpuListIfNeeded(accessor, busyCpu);
        QuantumDiagnostics.hit("menu.statusBindQuantum",
                "serial=" + accessor.gtShanhai$getSelectedCpuSerialRaw()
                        + " lastCpuSet=" + accessor.gtShanhai$getLastCpuSetRaw().size());
    }

    @Unique
    private QuantumCraftingCPU gtShanhai$findBusyQuantumCpu(CraftingStatusMenuAccessor accessor) {
        for (ICraftingCPU cpu : accessor.gtShanhai$getLastCpuSetRaw()) {
            if (cpu instanceof QuantumCraftingCPU) {
                QuantumCraftingCPU quantumCpu = (QuantumCraftingCPU) cpu;
                if (quantumCpu.isBusy()) {
                    return quantumCpu;
                }
            }
        }
        return null;
    }

    @Unique
    private void gtShanhai$syncQuantumCpuListIfNeeded(CraftingStatusMenuAccessor accessor, QuantumCraftingCPU cpu) {
        Level level = cpu.getLevel();
        if (level == null) {
            return;
        }
        long tick = level.getGameTime();
        if (tick - gtShanhai$lastQuantumCpuListSyncTick < gtShanhai$QUANTUM_CPU_LIST_SYNC_INTERVAL) {
            return;
        }
        gtShanhai$lastQuantumCpuListSyncTick = tick;
        accessor.gtShanhai$setCpuListRaw(accessor.gtShanhai$callCreateCpuList());
    }
}
