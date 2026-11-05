package com.dishanhai.gt_shanhai.integration.enhancedcore;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EnhancedCoreCloudVirtualWiringTest {

    @Test
    void patchesSharedCpuDemandAndCloudDispatchWithoutReplacingPatternIdentity() throws Exception {
        String cpu = mixin("EnhancedCoreCloudCpuVirtualMixin");
        assertTrue(cpu.contains("physicalUnits"));
        assertTrue(cpu.contains("EnhancedCoreCloudInputsAdapter.physicalUnits"));
        String push = mixin("EnhancedCoreCloudInputsVirtualMixin");
        assertTrue(push.contains("IPatternDetails;getInputs()"));
        assertTrue(push.contains("IvPatternAdmission;circuits("));
        assertTrue(push.contains("EnhancedCoreCloudInputsAdapter.mergePresence"));
        assertTrue(push.contains("CloudPatternBufferMachine"));
        assertFalse(push.contains("NativeVirtualRecipeRunner"));
        assertFalse(push.contains("new IvJob"));
        assertFalse(push.contains("cloudRouteValid"));
        assertFalse(push.contains("@Overwrite"));
    }

    @Test
    void probeAndCpuUseTheSameEntryBasedDecomposition() throws Exception {
        String adapter = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/integration/enhancedcore/EnhancedCoreCloudInputsAdapter.java"));
        assertTrue(adapter.contains("VirtualPatternEncodingHelper.isPresenceInput(input)"));
        assertTrue(adapter.contains("EnhancedCoreCloudInputs.split"));
        assertTrue(mixin("EnhancedCoreCloudProbeVirtualMixin").contains("EnhancedCoreCloudInputsAdapter.probe"));
        assertFalse(adapter.contains("presenceKeys("));
    }

    private static String mixin(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin", name + ".java"));
    }
}
