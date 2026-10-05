package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.CraftingSimulationState;

import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingTreeNode;
import org.gtlcore.gtlcore.integration.ae2.crafting.compiled.MaxFastExecutor;
import org.gtlcore.gtlcore.integration.ae2.crafting.compiled.MaxFastMetrics;

import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MaxFastExecutor.class, priority = 1500, remap = false)
public abstract class MaxFastExecutorVirtualPresenceMixin {

    private static final Logger GT_SHANHAI_LOG = LogManager.getLogger("gt_shanhai.max_fast_presence");
    private static final AtomicLong EXECUTION_SEQUENCE = new AtomicLong();

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$runPresenceRootWithoutAggregation(ICraftingTreeNode root,
            CraftingSimulationState inventory, long requestedAmount, KeyCounter containerItems,
            MaxFastMetrics metrics, CallbackInfo ci) throws InterruptedException, appeng.crafting.CraftBranchFailure {
        if (!(root instanceof CraftingTreeNodeVirtualPresenceAccess access)
                || !access.gtShanhai$containsPresenceInputInSubtree()) {
            return;
        }
        long executionId = EXECUTION_SEQUENCE.incrementAndGet();
        long startedNanos = System.nanoTime();
        GT_SHANHAI_LOG.warn(
                "[AE2-MAX_FAST-PRESENCE] root-enter id={} thread={} root={} requested={} containerItems={} diagnostics={}",
                executionId, Thread.currentThread().getName(), root.getClass().getName(), requestedAmount,
                containerItems != null, metrics.isDiagnosticLoggingEnabled());
        try {
            // executeStack 仍會走 gtlcore$runMaxFastPrefix，對 PresenceInput 執行 MODULATE
            // 抽取。退回 AE2 原生 request，才能經過山海的 Presence 專用輸入抽取器。
            root.legacyRequest(inventory, requestedAmount, containerItems);
            ci.cancel();
        } finally {
            GT_SHANHAI_LOG.warn(
                    "[AE2-MAX_FAST-PRESENCE] root-exit id={} elapsed_ms={} thread={}",
                    executionId, (System.nanoTime() - startedNanos) / 1_000_000.0,
                    Thread.currentThread().getName());
        }
    }
}
