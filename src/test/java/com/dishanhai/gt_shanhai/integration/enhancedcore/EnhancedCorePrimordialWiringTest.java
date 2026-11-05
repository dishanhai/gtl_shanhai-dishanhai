package com.dishanhai.gt_shanhai.integration.enhancedcore;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class EnhancedCorePrimordialWiringTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    @Test
    void cloudHasOneExecutionOwnerAndUsesOriginalPrimordialLifecycle() throws Exception {
        String logic = Files.readString(ROOT.resolve("common/machine/primordial/PrimordialThreadedRecipeLogic.java"));
        String bridge = Files.readString(ROOT.resolve("integration/enhancedcore/EnhancedCorePrimordialExecution.java"));
        assertTrue(logic.contains("cloudAdmissionBlocked"));
        assertTrue(logic.contains("prepareCloudBatch"));
        assertTrue(logic.contains("advanceCloudBatch"));
        assertTrue(bridge.contains("prepareForCloudExecution"));
        assertTrue(bridge.contains("IvOutputTransfer.transfer"));
        assertFalse(bridge.contains("iv$engine().tick"));
        assertFalse(bridge.contains("super.serverTick"));
    }

    @Test
    void cloudTypesUseRuntimeSelectionAndPatchEveryDefinitionBasedReader() throws Exception {
        String types = Files.readString(ROOT.resolve("integration/enhancedcore/EnhancedCorePrimordialRecipeTypes.java"));
        assertTrue(types.contains("getSelectedRecipeTypes()"));
        assertFalse(types.contains("getAllSelectableRecipeTypes()"));
        for (String mixin : new String[]{"EnhancedCorePrimordialCloudBufferTypesMixin",
                "EnhancedCorePrimordialCloudScanTypesMixin", "EnhancedCorePrimordialIvBufferTypesMixin"}) {
            assertTrue(Files.readString(ROOT.resolve("mixin/" + mixin + ".java")).contains("@Pseudo"));
        }
    }
}
