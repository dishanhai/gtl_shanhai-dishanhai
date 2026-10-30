package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：完整告警的資料保留、短通知及遠端授權邊界。 */
class StellarPatternStuckReportSourceTest {
    private static final Path MAIN = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    private static String read(String file) throws IOException {
        return Files.readString(MAIN.resolve(file)).replace("\r\n", "\n");
    }

    @Test
    void coinRestrictionsPreventBusyHostsFromDeferringDeniedRecipes() throws IOException {
        String watch = read("common/machine/part/StellarPatternStuckWatch.java");
        assertTrue(watch.contains("forge.getRecipeLogic().describeMintRestriction("));
        assertTrue(watch.contains("host.failed || strongFailure || mintFailure"));
        assertTrue(watch.contains("Cause.COIN_MINT_LOCKED"));
        String logic = read("common/machine/primordial/module/forge/PrimordialCoinForgeLogic.java");
        assertTrue(logic.contains("requiredMintModuleLevel(recipe)"));
        assertTrue(logic.contains("requiredOutputModuleLevel(recipe.outputs)"));
        assertTrue(logic.contains("requiredOutputModuleLevel(recipe.tickOutputs)"));
        assertTrue(logic.contains("CurrencyStageConfig.tierOf("));
    }

    @Test
    void slotLinksRequireAnAvailableTerminalAndTheSameTrackedGrid() throws IOException {
        String commands = read("command/DShanhaiCommands.java");
        assertTrue(commands.contains("Commands.literal(\"stellar_slot\")"));
        int start = commands.indexOf("private static int execStellarSlot(");
        assertTrue(start >= 0);
        String action = commands.substring(start, commands.indexOf("private static int execStellarTeleport(", start));
        assertTrue(action.contains("level.hasChunkAt(pos)"));
        assertTrue(action.contains("ShanhaiPatternTerminalOpenHelper.openFirst(player)"));
        assertTrue(action.contains("menu.stillValid(player)"));
        assertTrue(action.indexOf("menu.resolveStellarContainer(level, pos)") < action.indexOf(".openSlot("));
        assertTrue(action.contains("stellar.isValidRemotePatternSlot(slot)"));
        assertTrue(action.contains("sendAndOpenTo"), "通配展開槽必須開自己的資訊，不能被當成普通槽號");
    }

    @Test
    void fullReportKeepsEverySlotAndAllDiagnosticFieldsOutsideClippedBroadcast() throws IOException {
        Path file = MAIN.resolve("common/machine/part/StellarPatternStuckReport.java");
        assertTrue(Files.exists(file), "完整告警必須保存獨立資料，不能從已裁短廣播還原");
        String source = Files.readString(file);
        assertTrue(source.contains("record Entry("));
        assertTrue(source.contains("List.copyOf(hostRecipeTypes)"));
        assertTrue(source.contains("Type.STELLAR_STUCK"));
        for (String field : new String[]{"entry.reason()", "entry.inputs()", "entry.outputs()",
                "entry.patternRecipeTypeId()", "entry.patternRecipeId()", "entry.hostRecipeTypes()"}) {
            assertTrue(source.contains(field), "缺少完整欄位: " + field);
        }
        assertFalse(source.contains("substring("));
        assertFalse(source.contains("terminalStuckBroadcast("));
    }

    @Test
    void collapsedRowsShowAShortReasonWhileDetailsKeepTheWholeDiagnosis() throws IOException {
        String source = read("common/machine/part/StellarPatternStuckReport.java");
        assertTrue(source.contains("Component name = Component.translatable(PREFIX + \"slot_summary\""),
                "收起列也要顯示槽號與簡短原因");
        assertTrue(source.contains("entry.cause().summary()"), "摘要必須顯示實際原因分類");
        assertTrue(source.contains("coordinates, entry.reason()"), "完整原因仍須原樣放入展開詳情");
        for (String language : new String[]{"zh_cn", "en_us"}) {
            String lang = Files.readString(Path.of("src/main/resources/assets/gt_shanhai/lang/" + language + ".json"));
            assertTrue(lang.contains("gui.gt_shanhai.ultimate_terminal.report.slot_summary"));
            assertTrue(lang.contains("gui.gt_shanhai.ultimate_terminal.report.reason_unconsumed"));
        }
    }

    @Test
    void eachNotificationSendsOneShortMessageAndAStoredReportLink() throws IOException {
        String notifier = read("common/machine/part/StellarPatternStuckNotifier.java");
        String send = notifier.substring(notifier.indexOf("private static void send("),
                notifier.indexOf("public static String broadcastBody("));
        assertEquals(1, send.split("sendSystemMessage\\(", -1).length - 1,
                "每槽聊天只發送一則摘要與詳情連結");
        assertTrue(send.contains("ShanhaiTerminalReportPacket.sendTo"));
        assertTrue(send.contains("ShanhaiTerminalReport.CHAT_COMMAND"));
        assertFalse(send.contains("stuckInputs"));
        assertFalse(send.contains("patternRecipeId"));
    }

    @Test
    void remoteReportResolvesThroughTheCurrentValidMenuBeforeOpening() throws IOException {
        String packet = read("network/ShanhaiPatternRemoteConfigPacket.java");
        assertTrue(packet.contains("OPEN_STUCK_REPORT"));
        int resolve = packet.indexOf("menu.resolveStellarContainer(packet.serverId)");
        int open = packet.indexOf("packet.operation == Operation.OPEN_STUCK_REPORT");
        assertTrue(packet.contains("menu.containerId != packet.menuId || !menu.stillValid(player)"));
        assertTrue(resolve >= 0 && open > resolve, "報告只能讀取目前有權存取的星律容器");
        assertTrue(packet.contains("stellar.gtShanhai$buildStuckReport()"));
        assertTrue(packet.contains("ShanhaiTerminalReportPacket.sendAndOpenTo"));
        assertTrue(read("client/gui/terminal/ShanhaiPatternManagementScreen.java")
                .contains("Operation.OPEN_STUCK_REPORT"));
    }

    @Test
    void sameTickMachineAlertsShareOneReportAndRequesterUnion() throws IOException {
        String notifier = read("common/machine/part/StellarPatternStuckNotifier.java");
        assertTrue(notifier.contains("record BatchKey(ResourceLocation dimension, long pos)"));
        assertTrue(notifier.contains("batch.entries.put(slot, entry)"));
        assertTrue(notifier.contains("batch.aePlayerIds.add(aePlayerId)"));
        assertTrue(notifier.contains("@SubscribeEvent(priority = EventPriority.LOWEST)"));
        assertTrue(notifier.contains("batch.dimension.location(), pos, batch.entries.values()"));
        assertTrue(notifier.contains("onServerStopped(ServerStoppedEvent event)"));
        assertTrue(notifier.contains("PENDING.clear()"));
    }

    @Test
    void reportPacketDefaultsToCacheOnlyAndExplicitlyOpensAfterStorage() throws IOException {
        String packet = read("network/ShanhaiTerminalReportPacket.java");
        assertTrue(packet.contains("this(report, false)"));
        assertTrue(packet.contains("buf.writeBoolean(open)"));
        assertTrue(packet.contains("this.open = buf.readBoolean()"));
        int store = packet.indexOf("ShanhaiTerminalReportClient.store(report)");
        int open = packet.indexOf("if (open) ShanhaiTerminalReportClient.run(");
        assertTrue(store >= 0 && open > store);
    }
}
