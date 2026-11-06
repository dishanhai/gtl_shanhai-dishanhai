package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：呈現停用原因不能更動租約規則，使用中的原件仍可請求排空。 */
class StellarCloudTransferUiStateTest {
    private String reason(String method, boolean... flags) throws Exception {
        Class<?>[] signature = new Class<?>[flags.length];
        Object[] arguments = new Object[flags.length];
        for (int i = 0; i < flags.length; i++) { signature[i] = boolean.class; arguments[i] = flags[i]; }
        return (String) Class.forName(getClass().getPackageName() + ".StellarCloudTransferUiState")
                .getDeclaredMethod(method, signature).invoke(null, arguments);
    }

    @Test void uploadExplainsPermissionAndInvalidInputButAllowsRecoveryRetry() throws Exception {
        assertEquals("gt_shanhai.cloud.status.denied", reason("upload", false, false, true));
        assertEquals("gt_shanhai.cloud.status.invalid", reason("upload", true, false, false));
        assertEquals("", reason("upload", true, false, true));
        assertEquals("", reason("upload", true, true, false));
    }

    @Test void withdrawalExplainsMissingSelectionAndFullOutput() throws Exception {
        assertEquals("gt_shanhai.cloud.status.denied", reason("withdraw", false, true, true, false));
        assertEquals("gt_shanhai.cloud.ui.no_selection", reason("withdraw", true, false, true, false));
        assertEquals("gt_shanhai.cloud.status.output_full", reason("withdraw", true, true, false, false));
    }

    @Test void leasedOriginalCanRequestDrainWhileAnAlreadyPendingRequestIsDisabled() throws Exception {
        assertEquals("", reason("withdraw", true, true, true, false));
        assertEquals("gt_shanhai.cloud.status.withdraw_queued", reason("withdraw", true, true, true, true));
    }

    @Test void memberActionsExplainOwnerAndSelectionRequirements() throws Exception {
        assertEquals("gt_shanhai.cloud.status.denied", reason("access", false, true));
        assertEquals("gt_shanhai.cloud.status.invalid_member", reason("access", true, false));
        assertEquals("", reason("access", true, true));
    }

    @Test void staleQuickActionInputsReturnAVisibleServerResult() throws Exception {
        String machine = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/machine/part/cloud/StellarCloudLibraryMachine.java"));
        assertTrue(machine.contains("status = PREFIX + \"ui.source_changed\""), "Changed inventory input must explain why no upload happened");
        assertTrue(machine.contains("status = PREFIX + \"ui.list_changed\""), "A stale displayed UUID must explain why nothing was withdrawn");
    }
}
