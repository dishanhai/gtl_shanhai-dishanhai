package com.dishanhai.gt_shanhai.common.ae2;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 确认界面要自己判断「回环样板没被用上」。
 * 图引擎的计划不是 {@code CraftingPlan}，树计算上的让位标记到不了格子。
 */
public final class AeCycleSkipLabel {

    private AeCycleSkipLabel() {}

    public static boolean skipped(ICraftingService service, Map<IPatternDetails, Long> usedTimes, AEKey product,
            long total, FeasiblePatternOrder.Lookup lookup) {
        if (service == null || product == null || total <= 0L || lookup == null) return false;
        Collection<IPatternDetails> found = service.getCraftingFor(product);
        if (found.size() < 2) return false;
        List<IPatternDetails> patterns = new ArrayList<>(found);
        Set<AEItemKey> used = definitions(usedTimes);
        return FeasiblePatternOrder.usedSkipsCycle(patterns, details -> view(details, product), product, total,
                lookup, details -> {
                    if (details == null) return false;
                    AEItemKey definition = details.getDefinition();
                    return definition != null && used.contains(definition);
                });
    }

    public static FeasiblePatternOrder.Lookup network(ICraftingService service, MEStorage storage,
            IActionSource source) {
        return new NetworkLookup(service, storage, source);
    }

    /** 看不清的样板返回 null，调用方不当它是回环样板。 */
    public static FeasiblePatternOrder.Pattern view(IPatternDetails details, AEKey product) {
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
                    output = saturatedAdd(output, stack.amount());
                }
            }
        }
        List<FeasiblePatternOrder.Slot> slots = new ArrayList<>();
        IPatternDetails.IInput[] inputs = details.getInputs();
        if (inputs != null) {
            for (int i = 0; i < inputs.length; i++) {
                FeasiblePatternOrder.Slot slot = slotOf(inputs[i], involved);
                if (slot == null) return null;
                slots.add(slot);
            }
        }
        return new FeasiblePatternOrder.Pattern(output, slots, involved);
    }

    private static Set<AEItemKey> definitions(Map<IPatternDetails, Long> usedTimes) {
        Set<AEItemKey> used = new HashSet<>();
        if (usedTimes == null) return used;
        for (Map.Entry<IPatternDetails, Long> entry : usedTimes.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) continue;
            AEItemKey definition = entry.getKey().getDefinition();
            if (definition != null) used.add(definition);
        }
        return used;
    }

    /** 看不清的输入返回 null，整张样板不当成回环样板。 */
    private static FeasiblePatternOrder.Slot slotOf(IPatternDetails.IInput input, Set<Object> involved) {
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

    private static long saturatedAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static final class NetworkLookup implements FeasiblePatternOrder.Lookup {
        private final ICraftingService service;
        private final MEStorage storage;
        private final IActionSource source;

        private NetworkLookup(ICraftingService service, MEStorage storage, IActionSource source) {
            this.service = service;
            this.storage = storage;
            this.source = source;
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
                FeasiblePatternOrder.Pattern pattern = view(details, aeKey);
                if (pattern == null) return List.of(FeasiblePatternOrder.OPEN);
                patterns.add(pattern);
            }
            return patterns;
        }

        @Override
        public long available(Object key) {
            if (!(key instanceof AEKey aeKey) || storage == null) return 0L;
            return storage.extract(aeKey, Long.MAX_VALUE, Actionable.SIMULATE, source);
        }
    }
}
