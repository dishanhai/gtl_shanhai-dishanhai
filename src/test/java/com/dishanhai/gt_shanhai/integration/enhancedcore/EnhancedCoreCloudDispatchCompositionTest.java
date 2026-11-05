package com.dishanhai.gt_shanhai.integration.enhancedcore;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 山海：CPU 上下文須與 Enhancedcore 自有 pushPattern redirect 共存。 */
class EnhancedCoreCloudDispatchCompositionTest {
    @Test
    void stellarOrderScopeWrapsExecutionWithoutClaimingTheProviderInvocation() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin/CraftingCpuLogicStellarContextMixin.java"));
        assertTrue(source.contains("@WrapMethod(method = \"executeCrafting\""));
        assertFalse(source.contains("@Redirect"));
        assertTrue(source.contains("StellarPatternCraftingContext.pushOrder("));
        assertTrue(source.contains("original.call("));
        assertTrue(source.contains("StellarPatternCraftingContext.pop()"));
    }
}
