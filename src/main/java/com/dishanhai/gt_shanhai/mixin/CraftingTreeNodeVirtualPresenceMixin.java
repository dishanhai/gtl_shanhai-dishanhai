package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.inv.CraftingSimulationState;

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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    @Shadow @Final IPatternDetails.IInput parentInput;
    @Shadow private java.util.ArrayList<CraftingTreeProcess> nodes;
    @Shadow @Final private boolean canEmit;
    @Shadow private void buildChildPatterns() {
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

    @Inject(method = "gtlcore$tryMaxFastAggregation", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$disablePresenceAggregation(CraftingSimulationState inventory, long requestedAmount,
            MaxFastMetrics metrics, CallbackInfoReturnable<Boolean> cir) {
        if (gtShanhai$containsPresenceInputInSubtree()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "gTLCore$tryMaxFastCycleCandidateGraph", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$disablePresenceCycleCandidateGraph(CraftingSimulationState inventory, long requestedAmount,
            ICraftingTreeProcess process, ICraftingCalculation calculation, CallbackInfoReturnable<Boolean> cir) {
        if (gtShanhai$containsPresenceInputInSubtree()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "gTLCore$tryMaxFastCandidateGraph", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$disablePresenceCandidateGraph(CraftingSimulationState inventory, int candidateIndex,
            ICraftingTreeProcess process, long totalRequestedItems, ICraftingCalculation calculation,
            CallbackInfoReturnable<MaxFastExecutor.CandidateSegmentResult> cir) {
        if (gtShanhai$containsPresenceInputInSubtree()) {
            cir.setReturnValue(MaxFastExecutor.CandidateSegmentResult.STRUCTURAL_FALLBACK);
        }
    }

    @Override
    public boolean gtShanhai$containsPresenceInputInSubtree() {
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
}
