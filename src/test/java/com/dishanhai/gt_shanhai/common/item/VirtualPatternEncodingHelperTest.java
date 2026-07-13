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
}
