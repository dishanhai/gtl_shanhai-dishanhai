package com.dishanhai.gt_shanhai.integration.enhancedcore;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 山海：外部 private 入口無法脫離遊戲實例，另保留接線回歸。 */
class EnhancedCoreCloudLibraryContractTest {
    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/" + name + ".java"));
    }

    @Test
    void allThreeUploadValidatorsAreScopedAndDoNotInstallGlobalDecoder() throws Exception {
        String terminal = source("mixin/EnhancedCoreCloudLibraryTerminalMixin");
        String inbox = source("mixin/EnhancedCoreCloudLibraryInboxMixin");
        String server = source("mixin/EnhancedCoreCloudLibraryUploadMixin");
        assertTrue(terminal.contains("processingOriginal"));
        assertTrue(inbox.contains("isItemValid"));
        assertTrue(server.contains("uploadInbox"));
        assertFalse((terminal + inbox + server).contains("registerDecoder"));
    }

    @Test
    void motherMetadataUsesAllChildrenAndInvalidatesSchemaThree() throws Exception {
        String compat = source("integration/enhancedcore/EnhancedCoreCloudLibraryCompat");
        assertTrue(compat.contains("SelectedRecipePattern.expand"));
        assertTrue(compat.contains("SelectedRecipePattern.searchNames"));
        assertTrue(compat.contains("DISPLAY_SCHEMA = 4"));
        assertTrue(compat.contains("inputItemKeys"));
        assertTrue(compat.contains("outputFluidKeys"));
    }

    @Test
    void childrenShareOriginalLeaseAndKeepIndependentRecipeIdentity() throws Exception {
        String buffer = source("mixin/EnhancedCoreCloudBufferLibraryMixin");
        String dispatch = source("mixin/EnhancedCoreCloudBufferDispatchMixin");
        assertTrue(buffer.contains("getAvailablePatterns"));
        assertTrue(buffer.contains("cloudRealPattern"));
        assertTrue(dispatch.contains("@WrapMethod"));
        assertTrue(dispatch.contains("finally"));
        assertTrue(dispatch.contains("CloudPatternMatching;candidates"));
    }
}
