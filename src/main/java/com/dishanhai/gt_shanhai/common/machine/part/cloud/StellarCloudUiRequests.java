package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import static com.dishanhai.gt_shanhai.common.machine.part.cloud.StellarCloudUiContract.*;

/** 山海：限本次開啟畫面的單線請求去重，不新增持久化或業務收據格式。 */
final class StellarCloudUiRequests {
    private ActionResult last;

    ActionResult execute(int id, ActionKind action, UUID target, Supplier<String> operation) {
        if (id <= 0 || last != null && (id < last.requestId() || id == last.requestId()
                && (action != last.action() || !Objects.equals(target, last.target()))))
            return new ActionResult(id, action, ResultKind.REJECTED, target, "gt_shanhai.cloud.ui.request_rejected");
        if (last != null && id == last.requestId()) return last;
        String message = operation.get();
        ResultKind kind = switch (message) {
            case "gt_shanhai.cloud.status.uploaded", "gt_shanhai.cloud.status.withdrawn", "gt_shanhai.cloud.status.access_changed",
                 "gui.gt_shanhai.stellar_cloud.settings_applied", "gt_shanhai.cloud.ae_core.status.bound",
                 "gt_shanhai.cloud.ae_core.status.unbound" -> ResultKind.SUCCESS;
            case "gt_shanhai.cloud.status.withdraw_queued" -> ResultKind.WAITING;
            case "gt_shanhai.cloud.status.recovery" -> ResultKind.RECOVERY;
            default -> ResultKind.REJECTED;
        };
        return last = new ActionResult(id, action, kind, target, message);
    }
}
