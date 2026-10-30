package com.dishanhai.gt_shanhai.client;

import java.util.LinkedHashMap;
import java.util.Map;

import com.dishanhai.gt_shanhai.client.gui.terminal.TerminalReportScreen;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalReport;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 客戶端保存最近幾份終端報告；聊天連結 {@link #COMMAND} 只在客戶端攔截處理，不會發給伺服器。 */
public final class ShanhaiTerminalReportClient {
    public static final String COMMAND = ShanhaiTerminalReport.CHAT_COMMAND;
    private static final int KEEP = 8;
    private static final Map<Long, ShanhaiTerminalReport> REPORTS = new LinkedHashMap<>();

    private ShanhaiTerminalReportClient() {}

    public static void store(ShanhaiTerminalReport report) {
        REPORTS.remove(report.id());
        REPORTS.put(report.id(), report);
        while (REPORTS.size() > KEEP) REPORTS.remove(REPORTS.keySet().iterator().next());
        var minecraft = Minecraft.getInstance();
        if (report.type() == ShanhaiTerminalReport.Type.STELLAR_STUCK
                && minecraft.screen instanceof TerminalReportScreen screen && screen.reportId() == report.id()) {
            minecraft.setScreen(screen.refreshed(report));
        }
    }

    /** @return 該指令屬於本功能（無論成功與否都應攔截）。 */
    public static boolean run(String command) {
        if (command == null || !command.startsWith(COMMAND)) return false;
        Minecraft minecraft = Minecraft.getInstance();
        ShanhaiTerminalReport report = null;
        try {
            report = REPORTS.get(Long.parseLong(command.substring(COMMAND.length()).trim()));
        } catch (NumberFormatException ignored) {
            // 非法 id 與過期同樣處理
        }
        if (report == null) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("message.gt_shanhai.ultimate_terminal.report_expired")
                                .withStyle(ChatFormatting.RED), true);
            }
            return true;
        }
        minecraft.setScreen(new TerminalReportScreen(report));
        return true;
    }
}
