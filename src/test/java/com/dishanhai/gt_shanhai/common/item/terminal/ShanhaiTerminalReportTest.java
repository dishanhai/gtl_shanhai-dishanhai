package com.dishanhai.gt_shanhai.common.item.terminal;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：终端报告走封包完整往返；聊天栏只放摘要并以点击连结指向报告界面。 */
class ShanhaiTerminalReportTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai/");

    @Test void builderDeduplicatesTablesAndRoundTrips() {
        var builder = new ShanhaiTerminalReport.Builder(ShanhaiTerminalReport.Type.SCAN, new BlockPos(1, 2, 3), 2, 0, 0, 0, 1);
        builder.add(new BlockPos(0, 64, 0), "PLACE", Component.literal("缺失"), 0x55FF55, Component.literal("机械方块"));
        builder.add(new BlockPos(1, 64, 0), "PLACE", Component.literal("缺失"), 0x55FF55, Component.literal("机械方块"));
        builder.add(new BlockPos(2, 64, 0), "INPUT", Component.literal("输入仓室"), 0xFF3B30, Component.literal("输入总线"));
        var report = builder.build();
        assertEquals(2, report.categories().size());
        assertEquals(2, report.names().size());
        assertEquals(3, report.lines().size());

        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        report.encode(buffer);
        var decoded = ShanhaiTerminalReport.decode(buffer);
        assertEquals(report.id(), decoded.id());
        assertEquals(new BlockPos(1, 2, 3), decoded.target());
        assertArrayEquals(new int[]{2, 0, 0, 0, 1}, decoded.counts());
        assertEquals(report.lines(), decoded.lines());
        assertEquals("输入仓室", decoded.categories().get(1).label().getString());
        assertEquals(0xFF3B30, decoded.categories().get(1).color());
        assertEquals("输入总线", decoded.names().get(1).getString());
        assertEquals(decoded.names().size(), decoded.details().size());
        assertEquals(decoded.names().size(), decoded.icons().size());
    }

    @Test void entriesBeyondTheCapAreCountedNotSilentlyDropped() {
        var builder = new ShanhaiTerminalReport.Builder(ShanhaiTerminalReport.Type.MODULES, BlockPos.ZERO, 0);
        for (int i = 0; i < ShanhaiTerminalReport.MAX_LINES + 5; i++)
            builder.add(new BlockPos(i, 0, 0), "MODULE", Component.literal("m"), 0x00FFFF, Component.literal("n"));
        var report = builder.build();
        assertEquals(ShanhaiTerminalReport.MAX_LINES, report.lines().size());
        assertEquals(5, report.omitted());
        assertEquals(ShanhaiTerminalReport.MAX_LINES + 5, builder.size());
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        report.encode(buffer);
        assertEquals(5, ShanhaiTerminalReport.decode(buffer).omitted());
    }

    @Test void chatShowsOnlyAFewLinesAndLinksToTheReportScreen() throws Exception {
        String behavior = Files.readString(ROOT.resolve("common/item/ShanhaiUltimateTerminalBehavior.java"));
        assertTrue(behavior.contains("MAX_DETAIL_LINES = 3"));
        assertTrue(behavior.contains("ShanhaiTerminalReportPacket.sendTo"));
        // 仓室位置按类型分组列出全部候选，而不是只取第一个候选。
        assertTrue(behavior.contains("ShanhaiChamberClassifier.classifyAll(entry.candidates())"));
        assertTrue(Files.readString(ROOT.resolve("common/item/terminal/ShanhaiChamberClassifier.java")).contains("classifyAll(List<ItemStack> candidates)"));
        assertTrue(behavior.contains("ClickEvent.Action.RUN_COMMAND"));
        assertTrue(behavior.contains("ShanhaiTerminalReport.CHAT_COMMAND"));
        String client = Files.readString(ROOT.resolve("client/ClientInit.java"));
        assertTrue(client.contains("ShanhaiTerminalReportClient.run(clickEvent.getValue())"));
        assertTrue(client.contains("instanceof com.dishanhai.gt_shanhai.client.gui.terminal.TerminalReportScreen"));
        assertTrue(Files.readString(ROOT.resolve("network/ShanhaiNetwork.java")).contains("ShanhaiTerminalReportPacket.class"));
        String screen = Files.readString(ROOT.resolve("client/gui/terminal/TerminalReportScreen.java"));
        for (String forbidden : new String[]{"TextType.HIDE", "LEFT_HIDE", ".substring(", "trimText(", "plainSubstrByWidth("})
            assertFalse(screen.contains(forbidden), forbidden);
        assertTrue(screen.contains("report.omitted()"));
        // 仓室逐个画成带图示的卡片网格，而不是一整段纯文字。
        assertTrue(screen.contains("layoutCells(") && screen.contains("g.renderItem(cell.stack()"));
        assertTrue(behavior.contains("joinNames(stacks), stacks)"));
        // 施工/拆解结果：逐位置明细也走报告界面，聊天栏只留摘要与连结。
        assertTrue(behavior.contains("sendBuildReport(serverPlayer, plan, result)"));
        assertTrue(Files.readString(ROOT.resolve("common/item/terminal/ShanhaiStructureExecutor.java")).contains("details.add(detail(entry, Outcome.SKIPPED))"));
        assertTrue(screen.contains("title_build") && screen.contains("coordLines"));
        // 合成明细：缺口逐项进报告，下单功能在终端主界面有独立开关。
        String crafting = Files.readString(ROOT.resolve("common/item/terminal/ShanhaiTerminalCraftingManager.java"));
        assertTrue(crafting.contains("sendCraftReport(player, session, Stage.CALCULATED"));
        assertTrue(crafting.contains("sendCraftReport(player, session, Stage.SUBMITTED"));
        assertTrue(screen.contains("title_craft") && screen.contains("detailLines"));
        assertTrue(Files.readString(ROOT.resolve("client/gui/terminal/UltimateTerminalScreen.java")).contains("Field.ORDER_MODE"));
        assertTrue(Files.readString(ROOT.resolve("network/UltimateTerminalConfigPacket.java")).contains("case ORDER_MODE"));
        assertTrue(behavior.contains("isOrderMode(terminal)"));
    }
}
