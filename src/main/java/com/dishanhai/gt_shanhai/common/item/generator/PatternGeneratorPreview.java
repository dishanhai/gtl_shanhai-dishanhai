package com.dishanhai.gt_shanhai.common.item.generator;

import appeng.api.stacks.GenericStack;
import java.util.List;

/** 山海生成器的不可變預覽快照。 */
public record PatternGeneratorPreview(String id, int circuit, long eut, int duration, String tier,
                                      List<GenericStack> inputs, List<GenericStack> outputs,
                                      boolean checked, boolean overflow) {
    public PatternGeneratorPreview {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }
}

