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
import com.dishanhai.gt_shanhai.common.item.VirtualCraftingPresenceState;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftIOPart;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternPartMachine;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.integration.ae2.crafting.CraftingPatternPower;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

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
            // 抽取容器时锁定的口径：是否按 gtlcore 批量方式抽取、抽取时对应的整批份数。
            // 之后无论这个容器最终被推给哪个 provider，扣任务计数都必须用抽取时锁定的这两个值，
            // 不能用当前遍历到的 provider 重新算的口径——否则同一样板挂了多个 provider（比如
            // 普通 provider 排前面、gtlcore 批量 provider 排后面）时，容器只按前者抽了一份原料，
            // 却会被当作后者的整批份数直接把任务清零，造成"几乎不扣料却整批完成"的复制漏洞。
            boolean extractedAsBulk = false;
            long extractedBulkAmount = 0L;
            String diagnosticTask = QuantumDiagnostics.ENABLED ? Integer.toHexString(details.hashCode()) : null;
            if (QuantumDiagnostics.ENABLED) {
                QuantumDiagnostics.hitEvery("dispatch.task." + diagnosticTask, 1_000L,
                        "remaining=" + task.getValue().value
                                + " processing=" + processingPattern
                                + " inputs=" + describePatternInputs(details)
                                + " outputs=" + describePatternOutputs(details)
                                + " cpuInventory=" + describeCounter(inventory.list));
            }
            for (ICraftingProvider provider : craftingService.getProviders(details)) {
                boolean gtlCoreBulkProvider = processingPattern
                        && (provider instanceof IMEPatternPartMachine || provider instanceof IMECraftIOPart);
                String providerName = provider.getClass().getName();
                if (needsExtraction) {
                    extractedAsBulk = gtlCoreBulkProvider;
                    // 有完整原料时仍一次发完整 task；递归中间料只到了一部分时，先发当前能执行的最大整批。
                    // 这不是固定分片限速，批次只受 CPU 里真实可用原料限制。
                    extractedBulkAmount = gtlCoreBulkProvider
                            ? findAvailableBulkAmount((AEProcessingPattern) details, inventory, task.getValue().value)
                            : 0L;
                    craftingContainer = gtlCoreBulkProvider
                            ? (extractedBulkAmount > 0L
                                    ? AEUtils.extractForProcessingPattern((AEProcessingPattern) details, inventory,
                                            expectedOutputs, extractedBulkAmount)
                                    : null)
                            : CraftingCpuHelper.extractPatternInputs(details, inventory, level,
                                     expectedOutputs, expectedContainerItems);
                    needsExtraction = false;
                    if (QuantumDiagnostics.ENABLED) {
                        QuantumDiagnostics.hitEvery("dispatch.extract." + diagnosticTask, 1_000L,
                                "provider=" + providerName
                                        + " bulk=" + extractedAsBulk
                                        + " bulkAmount=" + extractedBulkAmount
                                        + " result=" + (craftingContainer == null ? "null" : describeCounters(craftingContainer))
                                        + " expectedOutputs=" + describeCounter(expectedOutputs)
                                        + " cpuInventoryAfter=" + describeCounter(inventory.list));
                    }
                }
                if (craftingContainer == null) {
                    if (QuantumDiagnostics.DISPATCH_ENABLED) {
                        String diagnosticKey = "dispatch.blocked.extract.cpu."
                                + Integer.toHexString(System.identityHashCode(cpu));
                        if (QuantumDiagnostics.claimEvery(diagnosticKey, 2_000L)) {
                            QuantumDiagnostics.dispatch("dispatch.blocked.extract",
                                    "cpu=" + Integer.toHexString(System.identityHashCode(cpu))
                                            + " final=" + getFinalJobOutput()
                                            + " task=" + Integer.toHexString(details.hashCode())
                                            + " remaining=" + task.getValue().value
                                            + " bulkAmount=" + extractedBulkAmount
                                            + " bottleneck=" + (processingPattern
                                                    ? describeBulkBottleneck((AEProcessingPattern) details,
                                                            inventory, task.getValue().value)
                                                    : "non-processing")
                                            + " provider=" + providerName
                                            + " inputs=" + describePatternInputs(details));
                        }
                    }
                    if (QuantumDiagnostics.ENABLED) {
                        QuantumDiagnostics.hitEvery("dispatch.blocked.extract." + diagnosticTask, 1_000L,
                                "provider=" + providerName + " bulk=" + extractedAsBulk
                                        + " bulkAmount=" + extractedBulkAmount);
                    }
                    break;
                }
                if (provider.isBusy()) {
                    if (QuantumDiagnostics.ENABLED) {
                        QuantumDiagnostics.hitEvery("dispatch.blocked.busy." + diagnosticTask, 1_000L,
                                "provider=" + providerName);
                    }
                    continue;
                }
                double patternPower = CraftingPatternPower.forCpu(
                        CraftingCpuHelper.calculatePatternPower(craftingContainer),
                        extractedAsBulk, extractedBulkAmount);
                double availablePower = energyService.extractAEPower(
                        patternPower, Actionable.SIMULATE, PowerMultiplier.CONFIG);
                if (availablePower < patternPower - 0.01D) {
                    if (QuantumDiagnostics.ENABLED) {
                        QuantumDiagnostics.hitEvery("dispatch.blocked.power." + diagnosticTask, 1_000L,
                                "provider=" + providerName + " required=" + patternPower
                                        + " available=" + availablePower + " bulkAmount=" + extractedBulkAmount);
                    }
                    break;
                }
                boolean accepted = provider.pushPattern(details, craftingContainer);
                if (QuantumDiagnostics.DISPATCH_ENABLED) {
                    String diagnosticKey = "dispatch.push.cpu."
                            + Integer.toHexString(System.identityHashCode(cpu));
                    if (QuantumDiagnostics.claimEvery(diagnosticKey, 2_000L)) {
                        QuantumDiagnostics.dispatch("dispatch.push",
                                "cpu=" + Integer.toHexString(System.identityHashCode(cpu))
                                        + " final=" + getFinalJobOutput()
                                        + " task=" + Integer.toHexString(details.hashCode())
                                        + " remainingBefore=" + task.getValue().value
                                        + " bulkAmount=" + extractedBulkAmount
                                        + " accepted=" + accepted
                                        + " provider=" + providerName
                                        + " outputs=" + describePatternOutputs(details));
                    }
                }
                if (QuantumDiagnostics.ENABLED) {
                    QuantumDiagnostics.hitEvery("dispatch.push." + diagnosticTask, 1_000L,
                            "provider=" + providerName + " accepted=" + accepted
                                    + " bulk=" + extractedAsBulk + " bulkAmount=" + extractedBulkAmount
                                    + " power=" + patternPower);
                }
                if (!accepted) continue;

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
                // pushPattern 成功后，容器里的原料所有权已经转移给 provider（对应的真实扣料已经在
                // 上面 extractForProcessingPattern/extractPatternInputs 里从 inventory 扣掉了）。
                // 这里必须立刻置空 craftingContainer，否则跳出 for 循环后"craftingContainer != null
                // 就 reinject 把原料退回 inventory"的收尾逻辑会把已经成功发配、provider 已经照单产出的
                // 原料重新塞回去——变成扣了料又把料退回，provider 那边却已经产出了，等于凭空复制一份。
                // 这个坑是从 gtlcore 原生 CraftingCpuLogicMixin.executeCrafting 抄过来的，原生代码同样
                // 只在"同一 task 还要继续推下一份"的分支里置空，success 后其余 break 路径都没置空。
                craftingContainer = null;
                if (extractedAsBulk) {
                    postTaskOutputsChanged(details);
                    // 整批一次发配完成：本次推送了抽取时锁定的 extractedBulkAmount(=整批) 份，与实际扣料量一致。
                    task.getValue().value -= extractedBulkAmount;
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

    private static long findAvailableBulkAmount(AEProcessingPattern details, ListCraftingInventory sourceInv,
            long requestedAmount) {
        long availableBulkAmount = Math.max(0L, requestedAmount);
        KeyCounter requiredPerPattern = new KeyCounter();
        for (IPatternDetails.IInput input : details.getInputs()) {
            if (VirtualPatternEncodingHelper.isPresenceInput(input)) continue;
            GenericStack[] possibleInputs = input.getPossibleInputs();
            if (possibleInputs == null || possibleInputs.length == 0 || possibleInputs[0] == null) return 0L;
            long requiredAmount = input.getMultiplier();
            if (requiredAmount > 0L) {
                requiredPerPattern.add(possibleInputs[0].what(), requiredAmount);
            }
        }
        for (Object2LongMap.Entry<AEKey> input : requiredPerPattern) {
            long availableAmount = sourceInv.extract(input.getKey(), Long.MAX_VALUE, Actionable.SIMULATE);
            availableBulkAmount = limitBulkAmount(
                    availableBulkAmount, availableAmount, input.getLongValue());
            if (availableBulkAmount == 0L) break;
        }
        return availableBulkAmount;
    }

    static long limitBulkAmount(long requestedAmount, long availableAmount, long requiredPerPattern) {
        if (requestedAmount <= 0L) return 0L;
        if (requiredPerPattern <= 0L) return requestedAmount;
        return Math.min(requestedAmount, Math.max(0L, availableAmount) / requiredPerPattern);
    }

    static QuantumCraftingStatus.State classifyCraftingStatus(long runnablePatterns,
            boolean hasProvider, boolean hasFreeProvider, long waitingForInput, long pendingInput) {
        if (runnablePatterns <= 0L) {
            return waitingForInput > 0L || pendingInput > 0L
                    ? QuantumCraftingStatus.State.WAITING_UPSTREAM
                    : QuantumCraftingStatus.State.MISSING_INPUT;
        }
        if (!hasProvider) return QuantumCraftingStatus.State.NO_PROVIDER;
        if (!hasFreeProvider) return QuantumCraftingStatus.State.PROVIDER_BUSY;
        return QuantumCraftingStatus.State.READY_TO_DISPATCH;
    }

    private StatusInputBottleneck inspectStatusBottleneck(IPatternDetails details, long requestedAmount) {
        KeyCounter requiredPerPattern = new KeyCounter();
        for (IPatternDetails.IInput input : details.getInputs()) {
            if (VirtualPatternEncodingHelper.isPresenceInput(input)) continue;
            GenericStack[] possibleInputs = input.getPossibleInputs();
            if (possibleInputs == null || possibleInputs.length == 0 || possibleInputs[0] == null) {
                return StatusInputBottleneck.INVALID;
            }
            if (input.getMultiplier() > 0L) {
                requiredPerPattern.add(possibleInputs[0].what(), input.getMultiplier());
            }
        }

        AEKey bottleneck = null;
        long bottleneckAvailable = 0L;
        long bottleneckRequired = 0L;
        long runnablePatterns = Math.max(0L, requestedAmount);
        for (Object2LongMap.Entry<AEKey> input : requiredPerPattern) {
            long available = inventory.extract(input.getKey(), Long.MAX_VALUE, Actionable.SIMULATE);
            long runnable = limitBulkAmount(requestedAmount, available, input.getLongValue());
            if (bottleneck == null || runnable < runnablePatterns) {
                bottleneck = input.getKey();
                bottleneckAvailable = available;
                bottleneckRequired = input.getLongValue();
                runnablePatterns = runnable;
            }
        }
        return new StatusInputBottleneck(true, bottleneck, bottleneckAvailable,
                bottleneckRequired, runnablePatterns);
    }

    private String describeBulkBottleneck(AEProcessingPattern details, ListCraftingInventory sourceInv,
            long requestedAmount) {
        KeyCounter requiredPerPattern = new KeyCounter();
        int presenceInputs = 0;
        for (IPatternDetails.IInput input : details.getInputs()) {
            if (VirtualPatternEncodingHelper.isPresenceInput(input)) {
                presenceInputs++;
                continue;
            }
            GenericStack[] possibleInputs = input.getPossibleInputs();
            if (possibleInputs == null || possibleInputs.length == 0 || possibleInputs[0] == null) {
                return "invalid-input presenceInputs=" + presenceInputs;
            }
            if (input.getMultiplier() > 0L) {
                requiredPerPattern.add(possibleInputs[0].what(), input.getMultiplier());
            }
        }

        AEKey bottleneck = null;
        long bottleneckAvailable = 0L;
        long bottleneckRequired = 0L;
        long bottleneckBatches = Math.max(0L, requestedAmount);
        for (Object2LongMap.Entry<AEKey> input : requiredPerPattern) {
            long available = sourceInv.extract(input.getKey(), Long.MAX_VALUE, Actionable.SIMULATE);
            long batches = limitBulkAmount(requestedAmount, available, input.getLongValue());
            if (bottleneck == null || batches < bottleneckBatches) {
                bottleneck = input.getKey();
                bottleneckAvailable = available;
                bottleneckRequired = input.getLongValue();
                bottleneckBatches = batches;
            }
        }
        return "key=" + bottleneck
                + " available=" + bottleneckAvailable
                + " perPattern=" + bottleneckRequired
                + " maxBatch=" + bottleneckBatches
                + " waitingFor=" + (bottleneck == null ? 0L : getWaitingFor(bottleneck))
                + " pendingOutputs=" + (bottleneck == null ? 0L : getPendingOutputs(bottleneck))
                + " presenceInputs=" + presenceInputs;
    }

    private static String describePatternInputs(IPatternDetails details) {
        StringBuilder result = new StringBuilder("[");
        IPatternDetails.IInput[] inputs = details.getInputs();
        for (int i = 0; i < inputs.length; i++) {
            if (i > 0) result.append(", ");
            IPatternDetails.IInput input = inputs[i];
            GenericStack[] possible = input.getPossibleInputs();
            result.append(i).append(':').append(input.getMultiplier()).append('x');
            if (possible == null || possible.length == 0 || possible[0] == null) {
                result.append("?");
            } else {
                result.append(possible[0].what()).append('@').append(possible[0].amount());
            }
        }
        return result.append(']').toString();
    }

    private static String describePatternOutputs(IPatternDetails details) {
        StringBuilder result = new StringBuilder("[");
        GenericStack[] outputs = details.getOutputs();
        for (int i = 0; i < outputs.length; i++) {
            if (i > 0) result.append(", ");
            GenericStack output = outputs[i];
            result.append(output == null ? "?" : output.what() + "@" + output.amount());
        }
        return result.append(']').toString();
    }

    private static String describeCounters(KeyCounter[] counters) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < counters.length; i++) {
            if (i > 0) result.append(" | ");
            result.append(i).append(':').append(describeCounter(counters[i]));
        }
        return result.append(']').toString();
    }

    private static String describeCounter(KeyCounter counter) {
        if (counter == null) return "null";
        StringBuilder result = new StringBuilder("{");
        int count = 0;
        for (Object2LongMap.Entry<AEKey> entry : counter) {
            if (count > 0) result.append(", ");
            if (count >= 12) {
                result.append("...");
                break;
            }
            result.append(entry.getKey()).append('=').append(entry.getLongValue());
            count++;
        }
        return result.append('}').toString();
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
                        "what=" + what + " amount=" + amount + " inserted=" + inserted
                                + " remaining=" + job.remainingAmount + " type=" + type);
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
        VirtualCraftingPresenceState.clear(inventory);
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
        VirtualCraftingPresenceState.readFromNBT(inventory, data);
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
        VirtualCraftingPresenceState.writeToNBT(inventory, data);
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

    public QuantumCraftingStatus getCraftingStatus(AEKey output, CraftingService craftingService) {
        long waitingForOutput = getWaitingFor(output);
        long pendingOutput = getPendingOutputs(output);
        if (job == null) {
            QuantumCraftingStatus.State state = waitingForOutput > 0L
                    ? QuantumCraftingStatus.State.WAITING_MACHINE
                    : QuantumCraftingStatus.State.PLANNED;
            return new QuantumCraftingStatus(state, null, 0L, 0L, 0L, 0L,
                    0L, 0L, waitingForOutput, pendingOutput);
        }

        for (Map.Entry<IPatternDetails, QuantumExecutingCraftingJob.TaskProgress> task : job.tasks.entrySet()) {
            long remainingPatterns = task.getValue().value;
            if (remainingPatterns <= 0L || !hasOutput(task.getKey(), output)) continue;

            StatusInputBottleneck bottleneck = inspectStatusBottleneck(task.getKey(), remainingPatterns);
            if (!bottleneck.valid()) {
                return new QuantumCraftingStatus(QuantumCraftingStatus.State.INVALID_PATTERN,
                        null, 0L, 0L, 0L, remainingPatterns,
                        0L, 0L, waitingForOutput, pendingOutput);
            }

            long waitingForInput = bottleneck.key() == null ? 0L : getWaitingFor(bottleneck.key());
            long pendingInput = bottleneck.key() == null ? 0L : getPendingOutputs(bottleneck.key());
            boolean hasProvider = false;
            boolean hasFreeProvider = false;
            for (ICraftingProvider provider : craftingService.getProviders(task.getKey())) {
                hasProvider = true;
                if (!provider.isBusy()) {
                    hasFreeProvider = true;
                    break;
                }
            }
            QuantumCraftingStatus.State state = classifyCraftingStatus(
                    bottleneck.runnablePatterns(), hasProvider, hasFreeProvider,
                    waitingForInput, pendingInput);
            AEKey blockingInput = bottleneck.runnablePatterns() <= 0L ? bottleneck.key() : null;
            return new QuantumCraftingStatus(state, blockingInput,
                    bottleneck.available(), bottleneck.requiredPerPattern(),
                    bottleneck.runnablePatterns(), remainingPatterns,
                    waitingForInput, pendingInput, waitingForOutput, pendingOutput);
        }

        QuantumCraftingStatus.State state = waitingForOutput > 0L
                ? QuantumCraftingStatus.State.WAITING_MACHINE
                : QuantumCraftingStatus.State.PLANNED;
        return new QuantumCraftingStatus(state, null, 0L, 0L, 0L, 0L,
                0L, 0L, waitingForOutput, pendingOutput);
    }

    public RedispatchResult retryRemainingDispatch() {
        if (job == null) return new RedispatchResult(RedispatchState.NO_JOB, 0, 0);
        IGrid grid = cpu.getGrid();
        if (grid == null) return new RedispatchResult(RedispatchState.GRID_OFFLINE, 0, 0);

        Object2LongOpenHashMap<AEKey> requiredInputs = new Object2LongOpenHashMap<>();
        requiredInputs.defaultReturnValue(0L);
        for (Map.Entry<IPatternDetails, QuantumExecutingCraftingJob.TaskProgress> task : job.tasks.entrySet()) {
            long remainingPatterns = Math.max(0L, task.getValue().value);
            if (remainingPatterns <= 0L) continue;
            for (IPatternDetails.IInput input : task.getKey().getInputs()) {
                if (VirtualPatternEncodingHelper.isPresenceInput(input)) continue;
                GenericStack[] possibleInputs = input.getPossibleInputs();
                if (possibleInputs == null || possibleInputs.length == 0 || possibleInputs[0] == null) continue;
                long required = saturatedMultiplyPositive(input.getMultiplier(), remainingPatterns);
                if (required <= 0L) continue;
                AEKey key = possibleInputs[0].what();
                requiredInputs.put(key, saturatedAddPositive(requiredInputs.getLong(key), required));
            }
        }

        MEStorage storage = grid.getStorageService().getInventory();
        int missingKinds = 0;
        int extractedKinds = 0;
        for (Object2LongMap.Entry<AEKey> input : requiredInputs.object2LongEntrySet()) {
            long available = inventory.extract(input.getKey(), Long.MAX_VALUE, Actionable.SIMULATE);
            long missing = Math.max(0L, input.getLongValue() - Math.min(input.getLongValue(), available));
            if (missing <= 0L) continue;
            missingKinds++;
            long extracted = storage.extract(input.getKey(), missing, Actionable.MODULATE, cpu.getSrc());
            if (extracted <= 0L) continue;
            inventory.insert(input.getKey(), extracted, Actionable.MODULATE);
            extractedKinds++;
            postChange(input.getKey());
        }

        java.util.Arrays.fill(usedOps, 0);
        for (Map.Entry<IPatternDetails, QuantumExecutingCraftingJob.TaskProgress> task : job.tasks.entrySet()) {
            postTaskOutputsChanged(task.getKey());
        }
        cpu.markDirty();
        if (missingKinds == 0) {
            return new RedispatchResult(RedispatchState.RETRIGGERED, 0, 0);
        }
        if (extractedKinds == 0) {
            return new RedispatchResult(RedispatchState.MATERIAL_UNAVAILABLE, missingKinds, 0);
        }
        return new RedispatchResult(RedispatchState.REFILLED, missingKinds, extractedKinds);
    }

    private static long saturatedMultiplyPositive(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static long saturatedAddPositive(long left, long right) {
        if (left <= 0L) return Math.max(0L, right);
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public enum RedispatchState {
        NO_JOB,
        GRID_OFFLINE,
        REFILLED,
        MATERIAL_UNAVAILABLE,
        RETRIGGERED
    }

    public record RedispatchResult(RedispatchState state, int missingKinds, int extractedKinds) {}

    private static boolean hasOutput(IPatternDetails details, AEKey output) {
        for (GenericStack patternOutput : details.getOutputs()) {
            if (patternOutput != null && output.matches(patternOutput)) return true;
        }
        return false;
    }

    private record StatusInputBottleneck(boolean valid, @Nullable AEKey key, long available,
                                         long requiredPerPattern, long runnablePatterns) {

        private static final StatusInputBottleneck INVALID =
                new StatusInputBottleneck(false, null, 0L, 0L, 0L);
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
