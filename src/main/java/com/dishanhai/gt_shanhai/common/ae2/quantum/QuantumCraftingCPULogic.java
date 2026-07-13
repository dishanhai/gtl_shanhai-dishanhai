package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.core.AELog;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.CraftingJobStatusPacket;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.hooks.ticking.TickHandler;
import appeng.me.service.CraftingService;
import com.google.common.base.Preconditions;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftIOPart;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternPartMachine;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public class QuantumCraftingCPULogic {

    final QuantumCraftingCPU cpu;
    private QuantumExecutingCraftingJob job;
    private final ListCraftingInventory inventory = new ListCraftingInventory(this::postChange);
    private final int[] usedOps = new int[3];
    private final Set<Consumer<AEKey>> listeners = new HashSet<>();
    private boolean cantStoreItems;
    private long lastModifiedOnTick = TickHandler.instance().getCurrentTick();
    private boolean markedForDeletion;

    public QuantumCraftingCPULogic(QuantumCraftingCPU cpu) {
        this.cpu = cpu;
    }

    public ICraftingSubmitResult trySubmitJob(IGrid grid, ICraftingPlan plan, IActionSource src,
            @Nullable ICraftingRequester requester) {
        if (job != null) return CraftingSubmitResult.CPU_BUSY;
        if (!cpu.isActive()) return CraftingSubmitResult.CPU_OFFLINE;
        if (cpu.getAvailableStorage() < plan.bytes()) return CraftingSubmitResult.CPU_TOO_SMALL;
        if (!inventory.list.isEmpty()) {
            AELog.warn("Quantum crafting CPU inventory is not empty yet a job was submitted.");
        }

        GenericStack missing = CraftingCpuHelper.tryExtractInitialItems(plan, grid, inventory, src);
        if (missing != null) {
            return CraftingSubmitResult.missingIngredient(missing);
        }

        Integer playerId = src.player().map(player -> {
            if (player instanceof ServerPlayer) {
                return IPlayerRegistry.getPlayerId((ServerPlayer) player);
            }
            return null;
        }).orElse(null);
        UUID craftId = UUID.randomUUID();
        CraftingLink cpuLink = new CraftingLink(CraftingCpuHelper.generateLinkData(craftId, requester == null, false), cpu);
        job = new QuantumExecutingCraftingJob(plan, this::postChange, cpuLink, playerId);
        cpu.updateOutput(plan.finalOutput());
        cpu.markDirty();
        QuantumDiagnostics.hit("logic.trySubmitJob.created",
                "final=" + plan.finalOutput() + " remaining=" + job.remainingAmount
                        + " tasks=" + job.tasks.size() + " waiting=" + job.waitingFor.list.size());
        notifyJobOwner(job, CraftingJobStatusPacket.Status.STARTED);
        if (requester != null) {
            CraftingLink requesterLink = new CraftingLink(CraftingCpuHelper.generateLinkData(craftId, false, true), requester);
            ((CraftingService) grid.getCraftingService()).addLink(cpuLink);
            ((CraftingService) grid.getCraftingService()).addLink(requesterLink);
            return CraftingSubmitResult.successful(requesterLink);
        }
        return CraftingSubmitResult.successful((ICraftingLink) null);
    }

    public void tickCraftingLogic(IEnergyService energyService, CraftingService craftingService) {
        if (!cpu.isActive()) return;
        cantStoreItems = false;
        if (job == null) {
            storeItems();
            if (!inventory.list.isEmpty()) {
                cantStoreItems = true;
                return;
            }
            if (markedForDeletion) {
                cpu.deactivate();
            }
            return;
        }
        if (job.link.isCanceled()) {
            cancel();
            return;
        }
        int remainingOperations = (cpu.getCoProcessors() + 1) - usedOps[0] - usedOps[1] - usedOps[2];
        while (remainingOperations > 0) {
            int pushedPatterns = executeCrafting(remainingOperations, craftingService, energyService, cpu.getLevel());
            if (pushedPatterns <= 0) {
                // 假合成收尾：对齐 gtlcore CraftingCpuLogicMixin.tickCraftingLogic 的原生行为——
                // "自动填写样板"生成的假合成订单，最终产出是一本改名的成书占位物，永远不会有
                // 真实合成把它插回来。样板全部发配完（tasks 空）就该在这里直接判定完成，
                // 而不是让任务悬着、只能靠外部手动取消收场（手动取消会走 finishJob(false)，
                // 已发配的原料不会再退，但也不该本该完成的订单被误判成"取消"）。
                if (job != null && job.tasks.isEmpty() && isFakeCraftingOutput(getFinalJobOutput())) {
                    cpu.updateOutput(null);
                    finishJob(true);
                }
                break;
            }
            usedOps[0] += pushedPatterns;
            remainingOperations -= pushedPatterns;
        }
        usedOps[2] = usedOps[1];
        usedOps[1] = usedOps[0];
        usedOps[0] = 0;
    }

    // gtlcore EncodePatternTransferHandlerMixin.multiBlockOutputImport 里，"自动填写样板"从多方块 JEI
    // 页生成的假合成图纸，最终产出固定编码成一本带 display 标签（改名）的成书占位物（Items.WRITTEN_BOOK），
    // 真实用途是把多方块建材通过 AE2 合成网络发配出去，不会有配方真的产出这本书。
    private static boolean isFakeCraftingOutput(@Nullable GenericStack output) {
        if (output == null) return false;
        AEKey what = output.what();
        if (!(what instanceof AEItemKey itemKey)) return false;
        return itemKey.getItem() == Items.WRITTEN_BOOK && itemKey.hasTag() && itemKey.getTag().contains("display");
    }

    public int executeCrafting(int maxPatterns, CraftingService craftingService, IEnergyService energyService,
            Level level) {
        if (job == null) return 0;
        int pushedPatterns = 0;
        // 复用两个 KeyCounter，避免每个 task 每轮都 new（高并行时分配放大明显）。
        KeyCounter expectedOutputs = new KeyCounter();
        KeyCounter expectedContainerItems = new KeyCounter();
        Iterator<Map.Entry<IPatternDetails, QuantumExecutingCraftingJob.TaskProgress>> iterator = job.tasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<IPatternDetails, QuantumExecutingCraftingJob.TaskProgress> task = iterator.next();
            if (task.getValue().value <= 0) {
                iterator.remove();
                continue;
            }
            IPatternDetails details = task.getKey();
            boolean processingPattern = details instanceof AEProcessingPattern;
            expectedOutputs.reset();
            expectedContainerItems.reset();
            KeyCounter[] craftingContainer = null;
            boolean needsExtraction = true;
            for (ICraftingProvider provider : craftingService.getProviders(details)) {
                boolean gtlCoreBulkProvider = processingPattern
                        && (provider instanceof IMEPatternPartMachine || provider instanceof IMECraftIOPart);
                // 批量样式（gtlCore ME 模式总线）：一次 pushPattern 把整批 task.value 全部发出，
                // 与 GTLcore 原生 executeCrafting 行为一致（extractForProcessingPattern 的 multiplier
                // = 整批份数），这是高发配量的来源。进度可见性由菜单 broadcastChanges 同步通道负责，
                // 不再靠分片推送（分片会把发配量掐成涓流，见 LRN-029）。
                long bulkThisPush = gtlCoreBulkProvider ? task.getValue().value : 0L;
                if (needsExtraction) {
                    craftingContainer = gtlCoreBulkProvider
                            ? AEUtils.extractForProcessingPattern((AEProcessingPattern) details, inventory,
                                    expectedOutputs, bulkThisPush)
                            : CraftingCpuHelper.extractPatternInputs(details, inventory, level,
                                    expectedOutputs, expectedContainerItems);
                    needsExtraction = false;
                }
                if (craftingContainer == null) break;
                if (provider.isBusy()) continue;
                double patternPower = CraftingCpuHelper.calculatePatternPower(craftingContainer);
                if (energyService.extractAEPower(patternPower, Actionable.SIMULATE, PowerMultiplier.CONFIG) < patternPower - 0.01D) {
                    break;
                }
                if (!provider.pushPattern(details, craftingContainer)) continue;

                energyService.extractAEPower(patternPower, Actionable.MODULATE, PowerMultiplier.CONFIG);
                pushedPatterns++;
                for (Object2LongMap.Entry<AEKey> output : expectedOutputs) {
                    job.waitingFor.insert(output.getKey(), output.getLongValue(), Actionable.MODULATE);
                    postChange(output.getKey());
                }
                for (Object2LongMap.Entry<AEKey> containerItem : expectedContainerItems) {
                    job.waitingFor.insert(containerItem.getKey(), containerItem.getLongValue(), Actionable.MODULATE);
                    job.timeTracker.addMaxItems(containerItem.getLongValue(), containerItem.getKey().getType());
                    postChange(containerItem.getKey());
                }
                cpu.markDirty();
                if (gtlCoreBulkProvider) {
                    postTaskOutputsChanged(details);
                    // 整批一次发配完成：本次推送了 bulkThisPush(=整批) 份，task 归零并移除。
                    task.getValue().value -= bulkThisPush;
                    if (task.getValue().value <= 0) {
                        iterator.remove();
                    }
                    break;
                }

                task.getValue().value--;
                postTaskOutputsChanged(details);
                if (task.getValue().value <= 0) {
                    iterator.remove();
                    break;
                }
                if (pushedPatterns == maxPatterns) {
                    break;
                }
                expectedOutputs.reset();
                expectedContainerItems.reset();
                craftingContainer = null;
                needsExtraction = true;
            }
            if (craftingContainer != null) {
                CraftingCpuHelper.reinjectPatternInputs(inventory, craftingContainer);
            }
            if (pushedPatterns == maxPatterns) {
                break;
            }
        }
        return pushedPatterns;
    }

    private void postTaskOutputsChanged(IPatternDetails details) {
        if (details == null) return;
        for (GenericStack output : details.getOutputs()) {
            if (output != null && output.what() != null) {
                postChange(output.what());
            }
        }
    }

    public long insert(AEKey what, long amount, Actionable type) {
        if (what == null || job == null) return 0;
        long waitingFor = job.waitingFor.extract(what, amount, Actionable.SIMULATE);
        if (waitingFor <= 0) {
            QuantumDiagnostics.hit("logic.insert.miss",
                    "what=" + what + " amount=" + amount
                            + " final=" + job.finalOutput
                            + " remaining=" + job.remainingAmount
                            + " waitingKeys=" + job.waitingFor.list.size());
            return 0;
        }
        if (amount > waitingFor) amount = waitingFor;

        if (type == Actionable.MODULATE) {
            job.timeTracker.decrementItems(amount, what.getType());
            job.waitingFor.extract(what, amount, Actionable.MODULATE);
            cpu.markDirty();
        }

        long inserted = amount;
        if (what.matches(job.finalOutput)) {
            inserted = job.link.insert(what, amount, type);
            if (type == Actionable.MODULATE) {
                postChange(what);
                job.remainingAmount = Math.max(0, job.remainingAmount - amount);
                QuantumDiagnostics.hit("logic.insert.finalOutput",
                        "what=" + what + " amount=" + amount + " remaining=" + job.remainingAmount
                                + " type=" + type);
                if (job.remainingAmount <= 0) {
                    finishJob(true);
                    cpu.updateOutput(null);
                } else {
                    cpu.updateOutput(new GenericStack(job.finalOutput.what(), job.remainingAmount));
                }
            }
        } else if (type == Actionable.MODULATE) {
            inventory.insert(what, amount, Actionable.MODULATE);
        }
        return inserted;
    }

    public void cancel() {
        if (job == null) return;
        cpu.updateOutput(null);
        finishJob(false);
    }

    public void storeItems() {
        Preconditions.checkState(job == null, "CPU should not have a job while dumping items");
        IGrid grid = cpu.getGrid();
        if (inventory.list.isEmpty() || grid == null) return;
        MEStorage storage = grid.getStorageService().getInventory();
        for (Object2LongMap.Entry<AEKey> entry : inventory.list) {
            postChange(entry.getKey());
            long inserted = storage.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE, cpu.getSrc());
            entry.setValue(entry.getLongValue() - inserted);
        }
        inventory.list.removeZeros();
        cpu.markDirty();
    }

    public long getLastModifiedOnTick() {
        return lastModifiedOnTick;
    }

    public boolean hasJob() {
        return job != null;
    }

    @Nullable
    public GenericStack getFinalJobOutput() {
        return job == null || job.finalOutput == null ? null : new GenericStack(job.finalOutput.what(), job.remainingAmount);
    }

    public QuantumElapsedTimeTracker getElapsedTimeTracker() {
        return job == null ? new QuantumElapsedTimeTracker() : job.timeTracker;
    }

    public void readFromNBT(CompoundTag data) {
        inventory.readFromNBT(data.getList("inventory", 10));
        if (data.contains("job")) {
            job = new QuantumExecutingCraftingJob(data.getCompound("job"), this::postChange, this);
            if (job.finalOutput == null) {
                finishJob(false);
            } else {
                cpu.updateOutput(new GenericStack(job.finalOutput.what(), job.remainingAmount));
            }
        } else {
            cpu.updateOutput(null);
        }
    }

    public void writeToNBT(CompoundTag data) {
        data.put("inventory", inventory.writeToNBT());
        if (job != null) {
            data.put("job", job.writeToNBT());
        }
    }

    @Nullable
    public ICraftingLink getLastLink() {
        return job == null ? null : job.link;
    }

    public ListCraftingInventory getInventory() {
        return inventory;
    }

    public void addListener(Consumer<AEKey> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<AEKey> listener) {
        listeners.remove(listener);
    }

    public long getStored(AEKey template) {
        return inventory.extract(template, Long.MAX_VALUE, Actionable.SIMULATE);
    }

    public long getWaitingFor(AEKey template) {
        return job == null ? 0 : job.waitingFor.extract(template, Long.MAX_VALUE, Actionable.SIMULATE);
    }

    public void getAllWaitingFor(Set<AEKey> waitingFor) {
        if (job == null) return;
        for (Object2LongMap.Entry<AEKey> entry : job.waitingFor.list) {
            waitingFor.add(entry.getKey());
        }
    }

    public long getPendingOutputs(AEKey template) {
        long count = 0;
        if (job == null) return 0;
        for (Map.Entry<IPatternDetails, QuantumExecutingCraftingJob.TaskProgress> task : job.tasks.entrySet()) {
            for (GenericStack output : task.getKey().getOutputs()) {
                if (template.matches(output)) {
                    count += output.amount() * task.getValue().value;
                }
            }
        }
        return count;
    }

    public void getAllItems(KeyCounter out) {
        out.addAll(inventory.list);
        if (job == null) return;
        out.addAll(job.waitingFor.list);
        for (Map.Entry<IPatternDetails, QuantumExecutingCraftingJob.TaskProgress> task : job.tasks.entrySet()) {
            for (GenericStack output : task.getKey().getOutputs()) {
                out.add(output.what(), output.amount() * task.getValue().value);
            }
        }
    }

    public boolean isCantStoreItems() {
        return cantStoreItems;
    }

    public boolean isMarkedForDeletion() {
        return markedForDeletion;
    }

    public boolean hasStoredItems() {
        return !inventory.list.isEmpty();
    }

    public void markForDeletion() {
        markedForDeletion = true;
    }

    private void finishJob(boolean success) {
        if (job == null) return;
        QuantumDiagnostics.hit("logic.finishJob",
                "success=" + success + " final=" + job.finalOutput + " remaining=" + job.remainingAmount
                        + " tasks=" + job.tasks.size() + " waiting=" + job.waitingFor.list.size()
                        + " inv=" + inventory.list.size());
        if (success) {
            job.link.markDone();
        } else {
            // 取消时不倒退去样板总成里"抠"原料回来：原料一旦 pushPattern 出去就已花费
            // （对齐原生 AE2 CraftingCpuLogic.finishJob），否则会和 gtlcore 建材供应侧的
            // 交付认领重复记账，被刷取消/自动填样板可无限复制材料。
            job.link.cancel();
        }
        job.waitingFor.clear();
        for (Map.Entry<IPatternDetails, QuantumExecutingCraftingJob.TaskProgress> entry : job.tasks.entrySet()) {
            for (GenericStack output : entry.getKey().getOutputs()) {
                postChange(output.what());
            }
        }
        notifyJobOwner(job, success ? CraftingJobStatusPacket.Status.FINISHED : CraftingJobStatusPacket.Status.CANCELLED);
        job = null;
        storeItems();
    }

    private void postChange(AEKey what) {
        lastModifiedOnTick = TickHandler.instance().getCurrentTick();
        for (Consumer<AEKey> listener : listeners) {
            listener.accept(what);
        }
    }

    private void notifyJobOwner(QuantumExecutingCraftingJob job, CraftingJobStatusPacket.Status status) {
        lastModifiedOnTick = TickHandler.instance().getCurrentTick();
        if (job.playerId == null || job.finalOutput == null) return;
        MinecraftServer server = cpu.getLevel().getServer();
        ServerPlayer player = IPlayerRegistry.getConnected(server, job.playerId);
        if (player != null) {
            UUID jobId = job.link.getCraftingID();
            NetworkHandler.instance().sendTo(new CraftingJobStatusPacket(jobId, job.finalOutput.what(),
                    job.finalOutput.amount(), job.remainingAmount, status), player);
        }
    }
}
