package com.dishanhai.gt_shanhai.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingBlockEntity;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingCPU;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingCPUCluster;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumDiagnostics;
import com.google.common.collect.ImmutableSet;

import net.minecraft.nbt.CompoundTag;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

@Mixin(value = CraftingService.class, remap = false)
public abstract class QuantumCraftingServiceMixin {

    @Unique
    private static final Comparator<QuantumCraftingCPUCluster> GT_SHANHAI_QUANTUM_FAST_FIRST = Comparator
            .comparingInt(QuantumCraftingCPUCluster::getCoProcessors).reversed()
            .thenComparingLong(QuantumCraftingCPUCluster::getAvailableStorage);

    @Unique
    private Set<QuantumCraftingCPUCluster> gtShanhai$quantumClusters;

    @Unique
    private boolean gtShanhai$quantumClustersDirty = true;

    @Shadow @Final private Set<CraftingCPUCluster> craftingCPUClusters;
    @Shadow @Final private IGrid grid;
    @Shadow @Final private IEnergyService energyGrid;
    @Shadow private boolean updateList;
    @Shadow private long lastProcessedCraftingLogicChangeTick;
    @Shadow @Final private Set<AEKey> currentlyCrafting;

    @Shadow public abstract void addLink(CraftingLink link);

    @Inject(method = "addNode", at = @At("TAIL"))
    private void gtShanhai$onAddNode(IGridNode gridNode, CompoundTag savedData, CallbackInfo ci) {
        if (gridNode.getOwner() instanceof QuantumCraftingBlockEntity) {
            QuantumDiagnostics.hit("service.addNode.quantum", String.valueOf(gridNode.getOwner()));
            gtShanhai$markQuantumClustersDirty();
            updateList = true;
        }
    }

    @Inject(method = "removeNode", at = @At("TAIL"))
    private void gtShanhai$onRemoveNode(IGridNode gridNode, CallbackInfo ci) {
        if (gridNode.getOwner() instanceof QuantumCraftingBlockEntity) {
            QuantumDiagnostics.hit("service.removeNode.quantum", String.valueOf(gridNode.getOwner()));
            gtShanhai$markQuantumClustersDirty();
            updateList = true;
        }
    }

    @Inject(method = "updateCPUClusters", at = @At("TAIL"))
    private void gtShanhai$updateQuantumClusters(CallbackInfo ci) {
        gtShanhai$refreshQuantumClusters("service.updateQuantumClusters");
    }

    @Unique
    private void gtShanhai$refreshQuantumClusters(String reason) {
        if (!gtShanhai$quantumClustersDirty && !"submitJob".equals(reason) && !"getCpus".equals(reason)) {
            return;
        }
        long start = QuantumDiagnostics.start();
        int machines = 0;
        int clusters = 0;
        int active = 0;
        Set<QuantumCraftingCPUCluster> cachedClusters = gtShanhai$getQuantumClusters();
        Set<QuantumCraftingCPUCluster> refreshedClusters = new HashSet<>();
        for (QuantumCraftingBlockEntity blockEntity : grid.getMachines(QuantumCraftingBlockEntity.class)) {
            machines++;
            QuantumCraftingCPUCluster cluster = blockEntity.getCluster();
            if (cluster == null) continue;
            if (!refreshedClusters.add(cluster)) continue;
            clusters++;
            for (QuantumCraftingCPU cpu : cluster.getActiveCPUSnapshot()) {
                active++;
                ICraftingLink link = cpu.craftingLogic.getLastLink();
                if (link instanceof CraftingLink) {
                    addLink((CraftingLink) link);
                }
            }
        }
        if (machines > 0 || cachedClusters.isEmpty()) {
            cachedClusters.clear();
            cachedClusters.addAll(refreshedClusters);
            gtShanhai$quantumClustersDirty = false;
        } else {
            clusters = cachedClusters.size();
            active = 0;
            for (QuantumCraftingCPUCluster cluster : cachedClusters) {
                for (QuantumCraftingCPU ignored : cluster.getActiveCPUSnapshot()) {
                    active++;
                }
            }
        }
        if (QuantumDiagnostics.ENABLED) {
            QuantumDiagnostics.slow("service.refreshQuantumClusters", start,
                    "reason=" + reason + " machines=" + machines + " clusters=" + clusters + " active=" + active);
        }
    }

    @Inject(method = "onServerEndTick", at = @At("TAIL"))
    private void gtShanhai$tickQuantumCpus(CallbackInfo ci) {
        long start = QuantumDiagnostics.start();
        gtShanhai$refreshQuantumClusters("onServerEndTick");
        long latestChange = 0;
        int clusters = 0;
        int active = 0;
        for (QuantumCraftingCPUCluster cluster : gtShanhai$getQuantumClusters()) {
            clusters++;
            cluster.cleanupInactiveCpus();
            for (QuantumCraftingCPU cpu : cluster.getActiveCPUSnapshot()) {
                active++;
                cpu.craftingLogic.tickCraftingLogic(energyGrid, (CraftingService) (Object) this);
                latestChange = Math.max(latestChange, cpu.craftingLogic.getLastModifiedOnTick());
                cpu.craftingLogic.getAllWaitingFor(currentlyCrafting);
            }
        }
        if (latestChange > lastProcessedCraftingLogicChangeTick) {
            lastProcessedCraftingLogicChangeTick = -1L;
        }
        if (QuantumDiagnostics.ENABLED) {
            QuantumDiagnostics.slow("service.onServerEndTick.quantum", start,
                    "clusters=" + clusters + " active=" + active + " latestChange=" + latestChange);
        }
    }

    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true)
    private void gtShanhai$submitQuantumJob(ICraftingPlan job, ICraftingRequester requestingMachine, ICraftingCPU target,
            boolean prioritizePower, IActionSource src, CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        if (job.simulation()) return;
        gtShanhai$refreshQuantumClusters("submitJob");
        if (target instanceof QuantumCraftingCPU) {
            QuantumCraftingCPU cpu = (QuantumCraftingCPU) target;
            gtShanhai$setQuantumSubmitResult(cir, cpu.getCluster().submitJob(grid, job, src, requestingMachine));
            return;
        }
        if (target instanceof QuantumCraftingCPUCluster) {
            gtShanhai$setQuantumSubmitResult(cir, ((QuantumCraftingCPUCluster) target).submitJob(grid, job, src, requestingMachine));
            return;
        }
        if (target == null) {
            QuantumCraftingCPUCluster cluster = gtShanhai$findSuitableQuantumCpu(job, src);
            if (cluster != null) {
                updateList = true;
                gtShanhai$setQuantumSubmitResult(cir, cluster.submitJob(grid, job, src, requestingMachine));
                gtShanhai$markQuantumClustersDirty();
            }
        }
    }

    @Unique
    private void gtShanhai$setQuantumSubmitResult(CallbackInfoReturnable<ICraftingSubmitResult> cir,
            ICraftingSubmitResult result) {
        cir.setReturnValue(result);
    }

    @Overwrite
    public long insertIntoCpus(AEKey what, long amount, Actionable type) {
        long start = QuantumDiagnostics.start();
        long inserted = 0;
        for (CraftingCPUCluster cpu : craftingCPUClusters) {
            inserted += cpu.craftingLogic.insert(what, amount - inserted, type);
        }
        gtShanhai$refreshQuantumClusters("insertIntoCpus");
        Set<QuantumCraftingCPUCluster> quantumClusters = gtShanhai$getQuantumClusters();
        for (QuantumCraftingCPUCluster cluster : quantumClusters) {
            for (QuantumCraftingCPU cpu : cluster.getActiveCPUSnapshot()) {
                inserted += cpu.craftingLogic.insert(what, amount - inserted, type);
            }
        }
        if (QuantumDiagnostics.ENABLED) {
            QuantumDiagnostics.slow("service.insertIntoCpus", start,
                    "amount=" + amount + " inserted=" + inserted + " quantumClusters=" + quantumClusters.size());
        }
        return inserted;
    }

    @Inject(method = "getCpus", at = @At("RETURN"), cancellable = true)
    private void gtShanhai$getQuantumCpus(CallbackInfoReturnable<ImmutableSet<ICraftingCPU>> cir) {
        gtShanhai$refreshQuantumClusters("getCpus");
        ImmutableSet.Builder<ICraftingCPU> builder = ImmutableSet.builder();
        builder.addAll(cir.getReturnValue());
        for (QuantumCraftingCPUCluster cluster : gtShanhai$getQuantumClusters()) {
            for (QuantumCraftingCPU cpu : cluster.getActiveCPUSnapshot()) {
                builder.add(cpu);
            }
            if (cluster.isActive() && !cluster.isDestroyed()) {
                builder.add(cluster.getRemainingCapacityCPU());
            }
        }
        cir.setReturnValue(builder.build());
    }

    @Inject(method = "getRequestedAmount", at = @At("RETURN"), cancellable = true)
    private void gtShanhai$getQuantumRequestedAmount(AEKey what, CallbackInfoReturnable<Long> cir) {
        gtShanhai$refreshQuantumClusters("getRequestedAmount");
        long requested = cir.getReturnValue();
        for (QuantumCraftingCPUCluster cluster : gtShanhai$getQuantumClusters()) {
            for (QuantumCraftingCPU cpu : cluster.getActiveCPUSnapshot()) {
                requested += cpu.craftingLogic.getWaitingFor(what);
            }
        }
        cir.setReturnValue(requested);
    }

    @Inject(method = "hasCpu", at = @At("HEAD"), cancellable = true)
    private void gtShanhai$hasQuantumCpu(ICraftingCPU cpu, CallbackInfoReturnable<Boolean> cir) {
        gtShanhai$refreshQuantumClusters("hasCpu");
        for (QuantumCraftingCPUCluster cluster : gtShanhai$getQuantumClusters()) {
            if (cpu == cluster.getRemainingCapacityCPU()) {
                cir.setReturnValue(true);
                return;
            }
            for (QuantumCraftingCPU activeCpu : cluster.getActiveCPUSnapshot()) {
                if (cpu == activeCpu) {
                    cir.setReturnValue(true);
                    return;
                }
            }
        }
    }

    @Unique
    private Set<QuantumCraftingCPUCluster> gtShanhai$getQuantumClusters() {
        if (gtShanhai$quantumClusters == null) {
            gtShanhai$quantumClusters = new HashSet<>();
        }
        return gtShanhai$quantumClusters;
    }

    @Unique
    private void gtShanhai$markQuantumClustersDirty() {
        gtShanhai$quantumClustersDirty = true;
    }

    @Unique
    private QuantumCraftingCPUCluster gtShanhai$findSuitableQuantumCpu(ICraftingPlan job, IActionSource src) {
        ArrayList<QuantumCraftingCPUCluster> valid = new ArrayList<>();
        for (QuantumCraftingCPUCluster cluster : gtShanhai$getQuantumClusters()) {
            if (!cluster.isActive()) continue;
            if (cluster.getAvailableStorage() < job.bytes()) continue;
            if (!cluster.canBeAutoSelectedFor(src)) continue;
            valid.add(cluster);
        }
        if (valid.isEmpty()) return null;
        valid.sort((a, b) -> {
            boolean firstPreferred = a.isPreferredFor(src);
            boolean secondPreferred = b.isPreferredFor(src);
            if (firstPreferred != secondPreferred) {
                return Boolean.compare(secondPreferred, firstPreferred);
            }
            return GT_SHANHAI_QUANTUM_FAST_FIRST.compare(a, b);
        });
        return valid.get(0);
    }
}
