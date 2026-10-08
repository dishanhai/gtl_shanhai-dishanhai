package com.dishanhai.gt_shanhai.mixin;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import appeng.crafting.inv.CraftingSimulationState;

import com.dishanhai.gt_shanhai.common.ae2.FeasiblePatternDemotions;
import com.dishanhai.gt_shanhai.common.ae2.FeasiblePatternOrder;

import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingTreeNode;
import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingTreeProcess;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * priority 必须高于 GTLCore（默认 1000）。{@code gtlcore$runUltraFastTail} 是 GTLCore
 * 后加到 {@link CraftingTreeNode} 上的，本 mixin 得等它合并进类再注入。
 */
@Mixin(value = CraftingTreeNode.class, priority = 1500, remap = false)
public abstract class CraftingTreeNodeFeasiblePatternMixin {

    @Shadow
    @Final
    private CraftingCalculation job;

    @Shadow
    @Final
    private AEKey what;

    @Shadow
    private ArrayList<CraftingTreeProcess> nodes;

    @Inject(method = "gtlcore$runUltraFastTail", at = @At("HEAD"), remap = false)
    private void gtShanhai$demoteCyclePatterns(CraftingSimulationState inv, long totalRequestedItems,
            long requestedAmount, CallbackInfo ci) {
        if (this.job == null || !this.job.isSimulation()) return;
        if (this.nodes == null || this.nodes.size() < 2 || totalRequestedItems <= 0 || this.what == null) return;
        ICraftingService service = ((ICraftingTreeNode) (Object) this).gtlcore$getMaxFastCraftingService();
        if (service == null || inv == null) return;
        boolean demoted = FeasiblePatternOrder.demoteLeadingCycles(this.nodes, this::gtShanhai$patternOf,
                this.what, totalRequestedItems, new StockLookup(service, inv));
        if (demoted) {
            FeasiblePatternDemotions.note(this.what);
        }
    }

    private FeasiblePatternOrder.Pattern gtShanhai$patternOf(CraftingTreeProcess process) {
        if (!(process instanceof ICraftingTreeProcess bridge)) return null;
        return gtShanhai$patternOf(bridge.getDetails(), this.what);
    }

    private static FeasiblePatternOrder.Pattern gtShanhai$patternOf(IPatternDetails details, AEKey product) {
        if (details == null) return null;
        Set<Object> involved = new HashSet<>();
        long output = 0L;
        GenericStack[] outputs = details.getOutputs();
        if (outputs != null) {
            for (int i = 0; i < outputs.length; i++) {
                GenericStack stack = outputs[i];
                if (stack == null || stack.what() == null || stack.amount() <= 0L) continue;
                involved.add(stack.what());
                if (product != null && product.equals(stack.what())) {
                    output = gtShanhai$saturatedAdd(output, stack.amount());
                }
            }
        }
        List<FeasiblePatternOrder.Slot> slots = new ArrayList<>();
        IPatternDetails.IInput[] inputs = details.getInputs();
        if (inputs != null) {
            for (int i = 0; i < inputs.length; i++) {
                FeasiblePatternOrder.Slot slot = gtShanhai$slotOf(inputs[i], involved);
                if (slot == null) return null;
                slots.add(slot);
            }
        }
        return new FeasiblePatternOrder.Pattern(output, slots, involved);
    }

    /** 看不清的输入返回 null，整张样板不当成回环样板。 */
    private static FeasiblePatternOrder.Slot gtShanhai$slotOf(IPatternDetails.IInput input, Set<Object> involved) {
        if (input == null) return null;
        GenericStack[] possible = input.getPossibleInputs();
        if (possible == null || possible.length != 1 || possible[0] == null || possible[0].what() == null) {
            return null;
        }
        long multiplier = input.getMultiplier();
        if (possible[0].amount() <= 0L || multiplier <= 0L) return null;
        if (multiplier > Long.MAX_VALUE / possible[0].amount()) return null;
        involved.add(possible[0].what());
        return new FeasiblePatternOrder.Slot(possible[0].what(), possible[0].amount() * multiplier);
    }

    private static long gtShanhai$saturatedAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static final class StockLookup implements FeasiblePatternOrder.Lookup {
        private final ICraftingService service;
        private final CraftingSimulationState inv;

        private StockLookup(ICraftingService service, CraftingSimulationState inv) {
            this.service = service;
            this.inv = inv;
        }

        @Override
        public boolean canEmit(Object key) {
            return key instanceof AEKey aeKey && service.canEmitFor(aeKey);
        }

        @Override
        public List<FeasiblePatternOrder.Pattern> patternsFor(Object key) {
            if (!(key instanceof AEKey aeKey)) return List.of();
            Collection<IPatternDetails> found = service.getCraftingFor(aeKey);
            if (found.isEmpty()) return List.of();
            List<FeasiblePatternOrder.Pattern> patterns = new ArrayList<>(found.size());
            for (IPatternDetails details : found) {
                // 读不清的样板当成「有一条不回环的来源」，避免把可行样板误判成回环。
                FeasiblePatternOrder.Pattern pattern = gtShanhai$patternOf(details, aeKey);
                if (pattern == null) return List.of(FeasiblePatternOrder.OPEN);
                patterns.add(pattern);
            }
            return patterns;
        }

        @Override
        public long available(Object key) {
            if (!(key instanceof AEKey aeKey)) return 0L;
            return inv.extract(aeKey, Long.MAX_VALUE, Actionable.SIMULATE);
        }
    }
}
