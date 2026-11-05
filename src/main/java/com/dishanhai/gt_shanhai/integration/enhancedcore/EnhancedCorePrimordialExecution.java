package com.dishanhai.gt_shanhai.integration.enhancedcore;

// Author: dishanhai
import appeng.api.networking.security.IActionSource;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.primordial.*;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.crafting.PrimordialMolecularAssemblerModuleLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtl.enhancedcore.common.cloud.CloudPatternBufferMachine;
import com.gtl.enhancedcore.common.recipe.iv.*;
import net.minecraft.nbt.CompoundTag;
import java.math.BigInteger;
import java.util.*;

/**
 * Exclusive cloud execution using the original primordial lifecycle.
 * Only IvJob persists cloud batches; the local scheduler contains no cloud frame.
 */
public final class EnhancedCorePrimordialExecution {
    private static final String FRAME = "sh_primordial_cloud_frame";
    private record Ref(CloudPatternBufferMachine buffer, IvJob job) {}
    private long nextAdmissionTick;

    public boolean tick(PrimordialThreadedRecipeLogic logic) {
        var machine = logic.getMachine();
        if (machine.isRemote()) return false;
        List<Ref> orders = new ArrayList<>();
        for (var part : machine.getParts()) {
            if (!(part instanceof CloudPatternBufferMachine buffer)) continue;
            for (IvJob job : IvBuffers.jobs(buffer, machine)) orders.add(new Ref(buffer, job));
        }
        boolean requested = orders.stream().anyMatch(ref -> ref.job.active() || !ref.job.pending.isEmpty()
                || !ref.job.halted && ref.job.remaining > 0
                && logic.isCloudRecipeSelected(ref.job.recipe));
        boolean localEmpty = logic.prepareForCloudExecution(requested);
        if (EnhancedCorePrimordialPolicy.owner(requested, localEmpty ? 0 : 1)
                != EnhancedCorePrimordialPolicy.Owner.CLOUD) return false;
        // This family extracts an AE crafting-handler protocol, not processing recipes.
        if (logic instanceof PrimordialMolecularAssemblerModuleLogic) return true;
        if (!logic.isCloudExecutionAvailable() || !machine.isRecipeLogicAvailable() || !machine.hasProxies()) return true;

        orders.sort(Comparator.comparing(ref -> ref.job.id));
        for (Ref ref : orders) {
            flush(ref);
            if (ref.job.active() && !ref.job.halted) {
                try { readBatch(ref.job); }
                catch (RuntimeException failure) { halt(ref, failure); }
            }
        }
        orders.removeIf(ref -> {
            if (!ref.job.done()) return false;
            IvBuffers.state(ref.buffer).jobs.remove(ref.job);
            ref.buffer.markDirty();
            return true;
        });

        var scheduler = new PrimordialThreadScheduler<Ref>();
        for (Ref ref : orders) {
            if (ref.job.active() && !ref.job.halted) scheduler.restore(
                    scheduler.lanes().size(), ref.job.nativeCapacity, ref);
        }
        long now = machine.getOffsetTimer();
        scheduler.tick(now, logic.getCloudParallelCapacity(), logic.getRecipeThreadCapacity(),
                new PrimordialThreadScheduler.Runner<>() {
                    @Override public boolean advance(Ref ref) {
                        try (var scope = EnhancedCorePrimordialContext.begin(logic, ref.buffer, ref.job, false)) {
                            var batch = logic.advanceCloudBatch(readBatch(ref.job).snapshot());
                            if (batch == null) {
                                ref.job.completeBatch();
                                ref.job.nativeRecipe = null;
                                ref.job.nativeCapacity = 0;
                                ref.job.nativeWireless = false;
                                ref.job.nativeWirelessEUt = BigInteger.ZERO;
                            } else storeBatch(ref.job, batch);
                            ref.job.validateAccounting();
                            ref.buffer.markDirty();
                            return batch == null;
                        } catch (RuntimeException failure) {
                            halt(ref, failure);
                            return false;
                        }
                    }

                    @Override public Ref start(long share) {
                        if (now < nextAdmissionTick || logic.isCloudDrainRequested()) return null;
                        nextAdmissionTick = now + 5;
                        for (Ref ref : orders) {
                            IvJob job = ref.job;
                            if (job.active() || job.halted || job.cancelled || job.remaining <= 0
                                    || !job.pending.isEmpty() || !logic.isCloudRecipeSelected(job.recipe)
                                    || !IvBuffers.bind(ref.buffer, machine)) continue;
                            try (var scope = EnhancedCorePrimordialContext.begin(logic, ref.buffer, job, true)) {
                                List<GTRecipe> reservedRecipes = orders.stream()
                                        .filter(other -> other.job.active() && !other.job.halted)
                                        .map(other -> readBatch(other.job).recipe()).toList();
                                var batch = logic.prepareCloudBatch(Math.min(share, job.remaining), reservedRecipes);
                                if (batch == null) continue;
                                long parallel = batch.recipe().data.contains(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY)
                                        ? batch.recipe().data.getLong(PrimordialModuleRecipeLogic.ACTUAL_PARALLEL_KEY)
                                        : scope.plannedParallel();
                                if (parallel < 1 || parallel > share || parallel > job.remaining)
                                    throw new IllegalStateException("Primordial cloud batch exceeded its admitted operations");
                                // Resolve and validate all output amounts before the only material commit.
                                var completion = IvRecipeInputs.outputs(batch.recipe().outputs);
                                scope.commitInventory();
                                job.completion.putAll(completion);
                                job.remaining -= parallel;
                                job.parallel = parallel;
                                job.nativeCapacity = share;
                                job.eut = 0;
                                job.energyLeft = job.energyTotal = BigInteger.ZERO;
                                job.nativeWireless = false;
                                job.nativeWirelessEUt = BigInteger.ZERO;
                                storeBatch(job, batch);
                                job.validateAccounting();
                                ref.buffer.markDirty();
                                nextAdmissionTick = now + 1;
                                return ref;
                            } catch (RuntimeException failure) {
                                halt(ref, failure);
                            }
                        }
                        return null;
                    }
                });
        PrimordialThreadedRecipeLogic.CloudBatch display = null;
        int active = 0;
        long reserved = 0;
        List<PrimordialThreadedRecipeLogic.CloudBatch> displays = new ArrayList<>();
        List<Long> reservations = new ArrayList<>();
        for (Ref ref : orders) if (ref.job.active()) {
            active++;
            reserved = reserved > Long.MAX_VALUE - ref.job.nativeCapacity
                    ? Long.MAX_VALUE : reserved + ref.job.nativeCapacity;
            if (!ref.job.halted) {
                var batch = readBatch(ref.job);
                displays.add(batch);
                reservations.add(ref.job.nativeCapacity);
                if (display == null) display = batch;
            }
        }
        logic.projectCloudBatch(display, active, reserved, displays, reservations,
                scheduler.admissionShare(logic.getCloudParallelCapacity(), logic.getRecipeThreadCapacity()) <= 0);
        machine.markDirty();
        return true;
    }

    private static void storeBatch(IvJob job, PrimordialThreadedRecipeLogic.CloudBatch batch) {
        GTRecipe saved = batch.recipe().copy();
        saved.data = batch.recipe().data.copy();
        saved.data.put(FRAME, batch.snapshot());
        job.nativeRecipe = saved;
        job.elapsed = batch.progress();
        job.duration = batch.duration();
        job.error = "";
    }

    private static PrimordialThreadedRecipeLogic.CloudBatch readBatch(IvJob job) {
        if (job.nativeRecipe == null || !job.nativeRecipe.data.contains(FRAME))
            throw new IllegalStateException("Missing primordial cloud execution snapshot");
        CompoundTag snapshot = job.nativeRecipe.data.getCompound(FRAME);
        return PrimordialThreadedRecipeLogic.restoreCloudBatch(snapshot);
    }

    private static void halt(Ref ref, RuntimeException failure) {
        ref.job.halted = true;
        ref.job.error = "gtl_enhancedcore.diagnostic.iv_failure";
        ref.buffer.markDirty();
        GTDishanhaiMod.LOGGER.error("Primordial cloud order {} stopped", ref.job.id, failure);
    }

    private static void flush(Ref ref) {
        IvJob job = ref.job;
        if (!IvBuffers.mayUseNetwork(ref.buffer)) return;
        var grid = ref.buffer.getGrid();
        if (grid == null || !ref.buffer.getMainNode().isActive()) return;
        int budget = 16;
        for (var iterator = job.pending.entrySet().iterator(); iterator.hasNext() && budget-- > 0;) {
            var entry = iterator.next();
            var transfer = IvOutputTransfer.transfer(grid.getEnergyService(), grid.getStorageService().getInventory(),
                    entry.getKey(), entry.getValue(), IActionSource.ofMachine(ref.buffer));
            if (transfer.inserted() == 0) continue;
            long left = entry.getValue() - transfer.inserted();
            if (left == 0) iterator.remove(); else entry.setValue(left);
            ref.buffer.markDirty();
        }
        if (job.pending.isEmpty() && job.pendingOperations > 0) {
            job.deliveredOperations = Math.addExact(job.deliveredOperations, job.pendingOperations);
            job.pendingOperations = 0;
            job.validateAccounting();
            ref.buffer.markDirty();
        }
    }
}
