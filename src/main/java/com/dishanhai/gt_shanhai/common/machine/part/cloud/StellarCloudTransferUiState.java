package com.dishanhai.gt_shanhai.common.machine.part.cloud;

/** 山海：僅提供呈現停用原因；動作執行時仍走原有 Session 與機器校驗。 */
final class StellarCloudTransferUiState {
    private static final String PREFIX = "gt_shanhai.cloud.";

    static String upload(boolean authorized, boolean recovering, boolean validInput) {
        if (!authorized) return PREFIX + "status.denied";
        return recovering || validInput ? "" : PREFIX + "status.invalid";
    }

    static String withdraw(boolean authorized, boolean selected, boolean outputEmpty, boolean draining) {
        if (!authorized) return PREFIX + "status.denied";
        if (!selected) return PREFIX + "ui.no_selection";
        if (!outputEmpty) return PREFIX + "status.output_full";
        return draining ? PREFIX + "status.withdraw_queued" : "";
    }

    static String access(boolean owner, boolean selectedMember) {
        if (!owner) return PREFIX + "status.denied";
        return selectedMember ? "" : PREFIX + "status.invalid_member";
    }

    private StellarCloudTransferUiState() {}
}
