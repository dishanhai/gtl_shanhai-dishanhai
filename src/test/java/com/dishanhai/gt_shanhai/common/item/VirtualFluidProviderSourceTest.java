package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Author: dishanhai. Protects the fluid-provider contract across encoding and execution. */
class VirtualFluidProviderSourceTest {

    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/item/" + name + ".java"));
    }

    @Test
    void fluidProviderStoresTheRealGenericAmountInsteadOfAnUnwrappedOneMillibucket() throws Exception {
        String helper = source("VirtualItemProviderHelper");
        String encoding = source("VirtualPatternEncodingHelper");

        assertTrue(helper.contains("createBoundProvider(GenericStack target)"));
        assertTrue(helper.contains("GenericStack.wrapInItemStack(target)"),
                "The fluid key, NBT and long amount must be saved in the provider payload");
        assertTrue(encoding.contains("createVirtualFluidInput(fluidKeyOf(sample), sample.getAmount())"));
        assertFalse(encoding.contains("rewritten.add(new GenericStack(fluidKeyOf(sample), VIRTUAL_FLUID_MARKER_AMOUNT))"));
    }

    @Test
    void providerAnalysisUnwrapsTheGenericTargetWithoutRecipeInference() throws Exception {
        String encoding = source("VirtualPatternEncodingHelper");
        String method = block(encoding, "private static GenericStack getVirtualProviderTarget(");

        assertTrue(method.contains("GenericStack.fromItemStack(target)"));
        assertFalse(method.contains("new GenericStack(AEItemKey.of(target)"),
                "A fluid wrapper is not an actual item dependency");
        assertFalse(method.contains("findMatchingRecipe"));
    }

    @Test
    void fluidReverseLookupChecksWrappedIdentityAndStoredAmount() throws Exception {
        String encoding = source("VirtualPatternEncodingHelper");
        String assignment = block(encoding, "private static boolean assignFluidContents(");

        assertTrue(assignment.contains("getVirtualProviderTarget(input)"));
        assertTrue(assignment.contains("target.amount() != amount"),
                "A provider for 10 mB must not match a 1000 mB catalyst");
        assertTrue(assignment.contains("isNonConsumable(content)"),
                "Fluid providers must not replace ordinary consumable fluids");
        assertTrue(encoding.contains("input.amount() == VIRTUAL_FLUID_MARKER_AMOUNT"),
                "Old bare markers must remain readable");
    }

    @Test
    void manualFluidWrapAndTooltipUseGenericIdentityAndMillibuckets() throws Exception {
        String encoding = source("VirtualPatternEncodingHelper");
        String item = source("VirtualItemProviderItem");

        assertTrue(encoding.contains("VirtualItemProviderHelper.createBoundProvider(raw)"));
        assertTrue(item.contains("GenericStack.fromItemStack(target)"));
        assertTrue(item.contains("instanceof AEFluidKey"));
        assertTrue(item.contains("mB "));
    }

    @Test
    void authoritativeQuickEncoderAlsoWrapsTheFullFluidAmount() throws Exception {
        String encoder = source("ShanhaiPatternEncoder");
        String method = block(encoder, "private static void appendFluidInputs(");

        assertFalse(encoder.contains("VIRTUAL_FLUID_MARKER_AMOUNT"),
                "The authoritative encoder bypasses generic rewriting and must never write a bare marker");
        assertTrue(method.contains("VirtualItemProviderHelper.createBoundProvider(target)"));
        assertTrue(method.contains("new GenericStack(fluidKeyOf(stack), Math.max(1L, stack.getAmount()))"));
        assertTrue(method.contains("new GenericStack(AEItemKey.of(provider), 1L)"));
    }

    @Test
    void aWrappedCatalystDoesNotMakeOrdinaryFluidOfTheSameKeyVirtual() throws Exception {
        String encoding = source("VirtualPatternEncodingHelper");
        String analysis = block(encoding, "private static PatternAnalysis analyzePattern(");
        String lookup = block(encoding, "private static GenericStack getNonConsumableFluidTarget(");

        assertTrue(analysis.contains("plan.indexFor(content)"),
                "Legacy markers must use the assigned recipe slot, not every input of the same fluid key");
        assertTrue(analysis.contains("getVirtualProviderTarget(input)"),
                "Self-describing providers must be resolved before legacy marker inference");
        assertTrue(lookup.contains("stack.amount() != VIRTUAL_FLUID_MARKER_AMOUNT"),
                "Ordinary fluid quantities must never be upgraded to catalyst presence by key alone");
    }

    private static String block(String source, String signature) {
        int start = source.indexOf(signature);
        int opening = source.indexOf('{', start);
        int depth = 0;
        for (int i = opening; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("Missing method: " + signature);
    }
}
