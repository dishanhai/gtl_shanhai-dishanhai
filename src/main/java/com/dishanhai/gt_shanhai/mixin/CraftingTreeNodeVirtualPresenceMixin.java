package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.inv.CraftingSimulationState;

import com.dishanhai.gt_shanhai.common.item.VirtualCraftingPresenceState;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;

import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingCalculation;
import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingTreeProcess;
import org.gtlcore.gtlcore.integration.ae2.crafting.compiled.MaxFastExecutor;
import org.gtlcore.gtlcore.integration.ae2.crafting.compiled.MaxFastMetrics;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// priority 必须高于 GTLCore 的 CraftingTreeNodeMixin(默认 1000)。
// 原因:adaptiveRequest / fastRequest / ultraFastRequest / maxFastRequest /
// gtlcore$runMaxFastPrefix 这些方法不是原版 AE2 的,是 GTLCore 用 @Unique
// 在类加载期"新增"进 CraftingTreeNode 的(原版只有 request)。
// Mixin 按 priority 升序应用(数字小的先应用)。若本 mixin priority 低于 GTLCore(如旧值 900),
// 本 mixin 会在 GTLCore 之前应用——那时这些新方法还没被合并进 ClassNode,@ModifyVariable
// 对它们的目标解析静默失败(require 默认只需命中 1 个,request 命中即满足,其余被跳过、不报错)。
// 结果:presence(催化剂/可复用)输入的"需求量封顶"只打在原版 request 上,而 GTLCore
// 的快速路径会绕过它,封顶失效,催化剂需求被批次倍数 times 放大到远超库存,
// 巨量配方必报"残缺的合成计划(材料不足)"。
// 提高到 1500 让本 mixin 在 GTLCore 之后应用,@ModifyVariable 才能命中其新增方法。
// maxFastRequest 与 gtlcore$runMaxFastPrefix 都必须封顶;后者是 MAX_FAST 实际抽取入口。
@Mixin(value = CraftingTreeNode.class, priority = 1500, remap = false)
public abstract class CraftingTreeNodeVirtualPresenceMixin implements CraftingTreeNodeVirtualPresenceAccess {

    private static final Logger GT_SHANHAI_LOG = LogManager.getLogger("gt_shanhai.max_fast_presence");
    private static final AtomicLong PRESENCE_SCAN_SEQUENCE = new AtomicLong();
    private static final AtomicLong AGGREGATION_GUARD_CALLS = new AtomicLong();
    private static final AtomicLong CYCLE_GUARD_CALLS = new AtomicLong();
    private static final AtomicLong CANDIDATE_GUARD_CALLS = new AtomicLong();
    private static final AtomicLong REQUEST_ENTRY_CALLS = new AtomicLong();
    private static final ThreadLocal<Integer> PRESENCE_SCAN_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> PRESENCE_SCAN_VISITS = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Long> PRESENCE_SCAN_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> PRESENCE_SCAN_STARTED_NANOS = new ThreadLocal<>();

    @Shadow @Final IPatternDetails.IInput parentInput;
    @Shadow private java.util.ArrayList<CraftingTreeProcess> nodes;
    @Shadow @Final private boolean canEmit;
    @Shadow private void buildChildPatterns() {
        throw new AssertionError();
    }
    @Shadow
    void request(CraftingSimulationState inventory, long requestedAmount, KeyCounter containerItems)
            throws InterruptedException, CraftBranchFailure {
        throw new AssertionError();
    }

    @ModifyVariable(
            method = { "request", "adaptiveRequest", "fastRequest", "ultraFastRequest",
                    "maxFastRequest", "gtlcore$runMaxFastPrefix" },
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            remap = false)
    private long gtShanhai$requestPresenceOncePerBatch(long requestedAmount) {
        return VirtualPatternEncodingHelper.isPresenceInput(this.parentInput)
                ? this.parentInput.getMultiplier()
                : requestedAmount;
    }

    @Inject(method = "request", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$traceLegacyRequest(CraftingSimulationState inventory, long requestedAmount,
            KeyCounter containerItems, CallbackInfo ci) {
        logRequestEntry("legacy", requestedAmount, containerItems);
        if (gtShanhai$hasPresenceWithoutTransfer(inventory)) {
            ci.cancel();
        }
    }

    @Inject(method = "fastRequest", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$traceFastRequest(CraftingSimulationState inventory, long requestedAmount,
            KeyCounter containerItems, CallbackInfo ci) throws InterruptedException, CraftBranchFailure {
        logRequestEntry("fast", requestedAmount, containerItems);
        gtShanhai$fallbackPresenceSubtreeToLegacy(inventory, requestedAmount, containerItems, ci);
    }

    @Inject(method = "ultraFastRequest", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$traceUltraFastRequest(CraftingSimulationState inventory, long requestedAmount,
            KeyCounter containerItems, CallbackInfo ci) throws InterruptedException, CraftBranchFailure {
        logRequestEntry("ultra_fast", requestedAmount, containerItems);
        gtShanhai$fallbackPresenceSubtreeToLegacy(inventory, requestedAmount, containerItems, ci);
    }

    @Inject(method = "maxFastRequest", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$traceMaxFastRequest(CraftingSimulationState inventory, long requestedAmount,
            KeyCounter containerItems, CallbackInfo ci) throws InterruptedException, CraftBranchFailure {
        logRequestEntry("max_fast", requestedAmount, containerItems);
        gtShanhai$fallbackPresenceSubtreeToLegacy(inventory, requestedAmount, containerItems, ci);
    }

    @Inject(method = "gtlcore$tryMaxFastAggregation", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$disablePresenceAggregation(CraftingSimulationState inventory, long requestedAmount,
        MaxFastMetrics metrics, CallbackInfoReturnable<Boolean> cir) {
        if (gtShanhai$containsPresenceInputInSubtree()) {
            logGuard("aggregation", AGGREGATION_GUARD_CALLS);
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "gTLCore$tryMaxFastCycleCandidateGraph", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$disablePresenceCycleCandidateGraph(CraftingSimulationState inventory, long requestedAmount,
        ICraftingTreeProcess process, ICraftingCalculation calculation, CallbackInfoReturnable<Boolean> cir) {
        if (gtShanhai$containsPresenceInputInSubtree()) {
            logGuard("cycle-candidate", CYCLE_GUARD_CALLS);
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "gTLCore$tryMaxFastCandidateGraph", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$disablePresenceCandidateGraph(CraftingSimulationState inventory, int candidateIndex,
            ICraftingTreeProcess process, long totalRequestedItems, ICraftingCalculation calculation,
            CallbackInfoReturnable<MaxFastExecutor.CandidateSegmentResult> cir) {
        if (gtShanhai$containsPresenceInputInSubtree()) {
            logGuard("candidate", CANDIDATE_GUARD_CALLS);
            cir.setReturnValue(MaxFastExecutor.CandidateSegmentResult.STRUCTURAL_FALLBACK);
        }
    }

    @Override
    public boolean gtShanhai$containsPresenceInputInSubtree() {
        int depth = PRESENCE_SCAN_DEPTH.get();
        boolean rootScan = depth == 0;
        if (rootScan) {
            long scanId = PRESENCE_SCAN_SEQUENCE.incrementAndGet();
            PRESENCE_SCAN_ID.set(scanId);
            PRESENCE_SCAN_VISITS.set(0);
            PRESENCE_SCAN_STARTED_NANOS.set(System.nanoTime());
            GT_SHANHAI_LOG.warn("[AE2-MAX_FAST-PRESENCE] presence-scan begin id={} thread={} node={}",
                    scanId, Thread.currentThread().getName(), System.identityHashCode(this));
        }
        PRESENCE_SCAN_DEPTH.set(depth + 1);
        int visits = PRESENCE_SCAN_VISITS.get() + 1;
        PRESENCE_SCAN_VISITS.set(visits);
        if (rootScan || visits % 10000 == 0) {
            GT_SHANHAI_LOG.warn(
                    "[AE2-MAX_FAST-PRESENCE] presence-scan heartbeat id={} depth={} visits={} thread={}",
                    PRESENCE_SCAN_ID.get(), depth + 1, visits, Thread.currentThread().getName());
        }
        try {
            boolean result = gtShanhai$scanPresenceInputInSubtree();
            if (rootScan) {
                GT_SHANHAI_LOG.warn(
                        "[AE2-MAX_FAST-PRESENCE] presence-scan end id={} result={} elapsed_ms={} visits={} thread={}",
                        PRESENCE_SCAN_ID.get(), result,
                        (System.nanoTime() - PRESENCE_SCAN_STARTED_NANOS.get()) / 1_000_000.0,
                        PRESENCE_SCAN_VISITS.get(), Thread.currentThread().getName());
            }
            return result;
        } catch (RuntimeException | Error throwable) {
            if (rootScan) {
                GT_SHANHAI_LOG.error(
                        "[AE2-MAX_FAST-PRESENCE] presence-scan abort id={} elapsed_ms={} visits={} thread={}",
                        PRESENCE_SCAN_ID.get(),
                        (System.nanoTime() - PRESENCE_SCAN_STARTED_NANOS.get()) / 1_000_000.0,
                        PRESENCE_SCAN_VISITS.get(), Thread.currentThread().getName(), throwable);
            }
            throw throwable;
        } finally {
            PRESENCE_SCAN_DEPTH.set(depth);
            if (rootScan) {
                PRESENCE_SCAN_ID.remove();
                PRESENCE_SCAN_VISITS.remove();
                PRESENCE_SCAN_STARTED_NANOS.remove();
            }
        }
    }

    private boolean gtShanhai$scanPresenceInputInSubtree() {
        if (VirtualPatternEncodingHelper.isPresenceInput(this.parentInput)) {
            return true;
        }
        if (this.nodes == null) {
            if (this.canEmit) {
                return false;
            }
            buildChildPatterns();
        }
        for (CraftingTreeProcess process : this.nodes) {
            if (process == null) {
                continue;
            }
            ICraftingTreeProcess bridge = (ICraftingTreeProcess) process;
            if (VirtualPatternEncodingHelper.containsVirtualProviderPattern(bridge.getDetails())) {
                return true;
            }
            CraftingTreeNode[] children = bridge.gtlcore$getChildNodes();
            if (children == null) {
                continue;
            }
            for (CraftingTreeNode child : children) {
                if (child instanceof CraftingTreeNodeVirtualPresenceAccess access
                        && access.gtShanhai$containsPresenceInputInSubtree()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void logGuard(String guard, AtomicLong counter) {
        long count = counter.incrementAndGet();
        if (count == 1 || count % 1000 == 0) {
            GT_SHANHAI_LOG.warn("[AE2-MAX_FAST-PRESENCE] graph-guard={} calls={}", guard, count);
        }
    }

    private boolean gtShanhai$hasPresenceWithoutTransfer(CraftingSimulationState inventory) {
        if (!VirtualPatternEncodingHelper.isPresenceInput(this.parentInput)) {
            return false;
        }
        long needed = Math.max(1L, this.parentInput.getMultiplier());
        GenericStack[] possibleInputs = this.parentInput.getPossibleInputs();
        if (possibleInputs == null) {
            return false;
        }
        for (GenericStack possibleInput : possibleInputs) {
            if (possibleInput != null && VirtualCraftingPresenceState.hasPresence(
                    inventory, possibleInput.what(), needed)) {
                return true;
            }
        }
        return false;
    }

    private void gtShanhai$fallbackPresenceSubtreeToLegacy(CraftingSimulationState inventory,
            long requestedAmount, KeyCounter containerItems, CallbackInfo ci)
            throws InterruptedException, CraftBranchFailure {
        if (!gtShanhai$containsPresenceInputInSubtree()) {
            return;
        }
        request(inventory, requestedAmount, containerItems);
        ci.cancel();
    }

    private void logRequestEntry(String path, long requestedAmount, KeyCounter containerItems) {
        long count = REQUEST_ENTRY_CALLS.incrementAndGet();
        if (count <= 10 || count % 1000 == 0) {
            GT_SHANHAI_LOG.warn(
                    "[AE2-MAX_FAST-PRESENCE] request-enter path={} calls={} presence={} requested={} containerItems={} node={} thread={}",
                    path, count, VirtualPatternEncodingHelper.isPresenceInput(this.parentInput), requestedAmount,
                    containerItems != null, System.identityHashCode(this), Thread.currentThread().getName());
        }
    }
}
