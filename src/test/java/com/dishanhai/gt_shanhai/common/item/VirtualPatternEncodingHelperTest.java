package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VirtualPatternEncodingHelperTest {

    @Test
    void preservesSelectedConsumableInputsDuringVirtualProviderRewrite() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        assertFalse(source.contains("List<GenericStack> rewritten = createVirtualInputs(recipe);"));
        assertTrue(source.contains("rewriteInputsPreservingSelections(inputs, recipe)"));
    }

    @Test
    void virtualTargetsRemainReusablePlanningDependencies() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        assertTrue(source.contains("new PresenceInput("),
                "虚拟目标必须进入合成树，缺失时才能由 AE 递归合成补齐");
        assertTrue(source.contains("return template[0].what();"),
                "在场输入必须声明返还自身，规划阶段才能复用而不是按每次合成消耗");
        assertFalse(source.contains("if (analysis.targetFor(input) != null) {\n                    continue;"),
                "不得继续在规划输入中直接删除虚拟目标");
    }

    @Test
    void bulkDispatchDoesNotMultiplyReusableVirtualTargets() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/item/VirtualPatternEncodingHelper.java"));

        assertTrue(source.contains("virtualTargetSink.accept(virtualTarget.what(), Math.max(1L, virtualTarget.amount()));"),
                "不消耗目标只需一份在场数量，不能乘整批下单次数");
        assertFalse(source.contains("virtualTarget.amount()), patternMultiplier"),
                "批量次数不得放大虚拟库存并持久化到样板总成");
    }
}
