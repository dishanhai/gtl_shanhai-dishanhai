package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.CraftingTreeNode;

import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// priority 必须高于 GTLCore 的 CraftingTreeNodeMixin(默认 1000)。
// 原因:adaptiveRequest / fastRequest / ultraFastRequest 这三个方法不是原版 AE2 的,
// 是 GTLCore 用 @Unique 在类加载期"新增"进 CraftingTreeNode 的(原版只有 request)。
// Mixin 按 priority 升序应用(数字小的先应用)。若本 mixin priority 低于 GTLCore(如旧值 900),
// 本 mixin 会在 GTLCore 之前应用——那时这三个新方法还没被合并进 ClassNode,@ModifyVariable
// 对它们的目标解析静默失败(require 默认只需命中 1 个,request 命中即满足,其余被跳过、不报错)。
// 结果:presence(催化剂/可复用)输入的"需求量封顶"只打在原版 request 上,而 GTLCore 默认
// ae2CalculationMode=ADAPTIVE 走的是 adaptiveRequest——封顶失效,催化剂需求被批次倍数 times
// 放大到远超库存,巨量配方必报"残缺的合成计划(材料不足)"。
// 提高到 1500 让本 mixin 在 GTLCore 之后应用,@ModifyVariable 才能命中全部 4 个方法。
// 已核实四个方法签名与首个 long 参数(requestedAmount)语义完全一致,封顶注入对它们都正确。
@Mixin(value = CraftingTreeNode.class, priority = 1500, remap = false)
public abstract class CraftingTreeNodeVirtualPresenceMixin {

    @Shadow @Final IPatternDetails.IInput parentInput;

    @ModifyVariable(
            method = { "request", "adaptiveRequest", "fastRequest", "ultraFastRequest" },
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            remap = false)
    private long gtShanhai$requestPresenceOncePerBatch(long requestedAmount) {
        return VirtualPatternEncodingHelper.isPresenceInput(this.parentInput)
                ? this.parentInput.getMultiplier()
                : requestedAmount;
    }
}
