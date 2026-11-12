package com.dishanhai.gt_shanhai.client.gui.terminal;

import com.dishanhai.gt_shanhai.client.ShanhaiTerminalReportClient;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalReport;
import com.dishanhai.sh_better_ui.kernel.api.ModuleContext;
import com.dishanhai.sh_better_ui.kernel.api.ModuleDescriptor;
import com.dishanhai.sh_better_ui.kernel.api.ScopeHandle;
import com.dishanhai.sh_better_ui.kernel.api.UIApis;
import com.dishanhai.sh_better_ui.kernel.api.UIModule;
import com.dishanhai.sh_better_ui.kernel.api.UiId;
import com.dishanhai.sh_better_ui.kernel.api.UiVersion;
import com.dishanhai.sh_better_ui.kernel.api.live.ActivityService.Content;
import com.dishanhai.sh_better_ui.kernel.api.live.ActivityService.Control;
import com.dishanhai.sh_better_ui.kernel.api.live.ActivityService.Priority;
import com.dishanhai.sh_better_ui.kernel.api.live.ActivityService.Text;
import com.dishanhai.sh_better_ui.kernel.api.live.NotificationService;
import net.minecraft.client.resources.language.I18n;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER;
import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

/** 山海：通过 SH-Better-UI 活动岛展示当前连接的星律告警。 */
public final class StellarWarningKernelModule implements UIModule {
    private static final UiId MODULE = new UiId(MOD_ID, "stellar_warning");
    private static final UiId SESSION = new UiId(MOD_ID, "stellar_warning/session");
    private static final UiId DETAILS = new UiId(MOD_ID, "stellar_warning/details");
    private static final String REPORT_TEXT = "gui.gt_shanhai.ultimate_terminal.report.";

    private static StellarWarningKernelModule active;
    private ModuleContext module;
    private ScopeHandle session;
    private long nextNotice;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder().id(MODULE).version(UiVersion.parse("1.0.0"))
                .platforms(Set.of(ModuleDescriptor.Platform.FORGE_1_20_1))
                .build();
    }

    @Override
    public void register(ModuleContext context) {}

    @Override
    public void start(ModuleContext context) {
        module = context;
        active = this;
    }

    @Override
    public void stop(ModuleContext context) {
        active = null;
        session = null;
        module = null;
    }

    public static void notify(ShanhaiTerminalReport report) {
        if (active != null && report.type() == ShanhaiTerminalReport.Type.STELLAR_STUCK && !report.lines().isEmpty()) {
            active.publish(report);
        }
    }

    private void publish(ShanhaiTerminalReport report) {
        var notifications = module.engine().find(NotificationService.KEY).orElse(null);
        if (notifications == null) return;

        UiId id = new UiId(MOD_ID, "stellar_warning/notice/" + ++nextNotice);
        UiId machine = new UiId(MOD_ID, "stellar_warning/report/" + Long.toUnsignedString(report.id()));
        String title = I18n.get(REPORT_TEXT + "island_stellar_warning", report.lines().size() + report.omitted());
        String location = report.summary().getString();
        if (location.length() > 512) location = report.target().toShortString();
        var content = new Content(Text.literal(title), Text.literal(location), null, -1, false,
                List.of(new Control(DETAILS, Text.key(REPORT_TEXT + "link_details"))), List.of());
        try {
            if (session == null || !session.isOpen()) {
                session = module.engine().require(UIApis.SCREENS).session(module, SESSION);
            }
            notifications.publish(module, session, new NotificationService.Request(id, content,
                    Priority.HIGH, Duration.ofSeconds(12), machine, NotificationService.Merge.REPLACE,
                    action -> ShanhaiTerminalReportClient.openLatest(report)));
        } catch (IllegalStateException unavailable) {
            // 通知关闭、连接结束或队列满时，仍可通过聊天打开报告。
            LOGGER.debug("Stellar warning activity island unavailable: {}", unavailable.getMessage());
        }
    }
}
