package com.dishanhai.gt_shanhai.integration.enhancedcore;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;
import com.gtl.enhancedcore.common.cloud.CloudPatternMatching;
import com.gtl.enhancedcore.common.recipe.iv.IvBuffers;
import org.gtlcore.gtlcore.common.data.GTLItems;
import org.gtlcore.gtlcore.common.item.VirtualIngredientBehavior;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 山海：僅由 Enhancedcore 可選相容 Mixin 載入的 AE 型別適配。 */
public final class EnhancedCoreCloudInputsAdapter {

    private EnhancedCoreCloudInputsAdapter() {}

    public static EnhancedCoreCloudInputs.Split<AEKey> split(IPatternDetails pattern) {
        List<EnhancedCoreCloudInputs.Input<AEKey>> entries = new ArrayList<>();
        for (IPatternDetails.IInput input : pattern.getInputs()) {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible == null || possible.length != 1 || possible[0] == null
                    || possible[0].what() == null || input.getMultiplier() < 1) {
                throw new IllegalArgumentException();
            }
            AEKey key = possible[0].what();
            if (VirtualPatternEncodingHelper.isPresenceInput(input)) {
                entries.add(new EnhancedCoreCloudInputs.Input<>(key, input.getMultiplier(), true));
            } else if (key instanceof AEItemKey item && GTLItems.VIRTUAL_INGREDIENT.isIn(item.toStack())) {
                // 保留 GTL 原生 token 的無限供應／電路一份語義，不重新包裝山海有限在場量。
                var wrapper = item.toStack();
                AEKey itemPayload = VirtualIngredientBehavior.payloadItemKey(wrapper);
                AEKey fluidPayload = VirtualIngredientBehavior.payloadFluidKey(wrapper);
                if (itemPayload != null) entries.add(new EnhancedCoreCloudInputs.Input<>(itemPayload,
                        IvBuffers.isCircuit(itemPayload) ? 1L : Long.MAX_VALUE, true));
                if (fluidPayload != null) entries.add(new EnhancedCoreCloudInputs.Input<>(fluidPayload, Long.MAX_VALUE, true));
            } else {
                entries.add(new EnhancedCoreCloudInputs.Input<>(key, input.getMultiplier(), false));
            }
        }
        return EnhancedCoreCloudInputs.split(entries);
    }

    public static CloudPatternMatching.Probe probe(IPatternDetails pattern, Map<AEKey, Long> virtual) {
        try {
            var inputs = split(pattern);
            Map<AEKey, Long> outputs = new LinkedHashMap<>();
            for (GenericStack output : pattern.getOutputs()) {
                outputs.merge(output.what(), output.amount(), Math::addExact);
            }
            return new CloudPatternMatching.Probe(inputs.physical(), inputs.mergePresence(virtual), outputs);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return null;
        }
    }

    public static Map<AEKey, Long> physicalUnits(IPatternDetails pattern) {
        try {
            Map<AEKey, Long> physical = new LinkedHashMap<>(split(pattern).physical());
            // 原生 CPU 對編程電路仍沿用配置輸入而非按操作數抽取的規則。
            physical.keySet().removeIf(IvBuffers::isCircuit);
            return physical;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return null;
        }
    }

    public static IPatternDetails.IInput[] withoutPresence(IPatternDetails.IInput[] inputs) {
        if (!VirtualPatternEncodingHelper.containsPresenceInput(inputs)) return inputs;
        return Arrays.stream(inputs).filter(input -> !VirtualPatternEncodingHelper.isPresenceInput(input))
                .toArray(IPatternDetails.IInput[]::new);
    }

    /** 不依 key 移除實料：同一 key 的消耗料和在場材料可以同時存在。 */
    public static Map<AEKey, Long> mergePresence(IPatternDetails pattern, Map<AEKey, Long> intrinsic) {
        if (!VirtualPatternEncodingHelper.containsVirtualProviderPattern(pattern)) return intrinsic;
        return split(pattern).mergePresence(intrinsic);
    }
}
