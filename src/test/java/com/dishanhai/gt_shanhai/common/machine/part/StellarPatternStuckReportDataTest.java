package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：報告資料不依賴 GUI 或原有裁短摘要。 */
class StellarPatternStuckReportDataTest {
    @Test
    void sameAssemblyReportRetainsItsIdentityAsOtherSlotsBecomeBlocked() {
        var first = new StellarPatternStuckReport.Entry(1, "", "a", "in", "out", List.of(), "type", "recipe");
        var second = new StellarPatternStuckReport.Entry(7, "", "b", "in", "out", List.of(), "type", "recipe");
        var dimension = new ResourceLocation("minecraft", "overworld");
        var initial = StellarPatternStuckReport.build(1234L, dimension, BlockPos.ZERO, List.of(first));
        var updated = StellarPatternStuckReport.build(1234L, dimension, BlockPos.ZERO, List.of(first, second));
        assertEquals(initial.id(), updated.id());
        assertEquals(2, updated.lines().size());
        assertArrayEquals(new int[]{2, 0, 2, 0, 0}, updated.counts(), "未能關聯 AE 訂單的紀錄不可計為已解決");
        assertTrue(updated.names().stream().anyMatch(name -> Component.Serializer.toJson(name).contains(" 7 main")));
    }

    @Test
    void structuredDiagnosisKeepsIndependentSectionsAndBothRemoteActionsAcrossThePacket() {
        var diagnosis = new StellarPatternStuckReport.Diagnosis("HOST_STATUS_ONLY", "MINT_GATE_ONLY", "PARALLEL_ONLY");
        var entry = new StellarPatternStuckReport.Entry(203, "", "PRIMARY_ONLY", "INPUT_ONLY", "OUTPUT_ONLY",
                List.of("gtceu:assembler"), "gtceu:assembler", "recipe", StellarPatternStuckReport.Cause.COIN_MINT_LOCKED,
                diagnosis);
        var report = StellarPatternStuckReport.build(new ResourceLocation("minecraft", "overworld"), BlockPos.ZERO, List.of(entry));
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            report.encode(buffer);
            var decoded = com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalReport.decode(buffer);
            String json = Component.Serializer.toJson(decoded.details().getFirst());
            for (String section : new String[]{"section_location", "section_reason", "section_host", "section_mint", "section_parallel", "section_recipe", "section_materials"}) {
                assertTrue(json.contains(section), section);
            }
            for (String value : new String[]{"PRIMARY_ONLY", "HOST_STATUS_ONLY", "MINT_GATE_ONLY", "PARALLEL_ONLY", "INPUT_ONLY", "OUTPUT_ONLY"}) {
                assertTrue(json.contains(value), value);
            }
            String name = Component.Serializer.toJson(decoded.names().getFirst());
            assertTrue(name.contains("/shanhai stellar_slot minecraft:overworld 0 0 0 203 main"));
            assertTrue(name.contains("/shanhai stellar_slot minecraft:overworld 0 0 0 203\""));
            assertEquals("PRIMARY_ONLY", entry.reason());
            assertEquals(diagnosis, entry.diagnosis());
        } finally { buffer.release(); }
    }

    @Test
    void reportCarriesExecutableDimensionAwareLinksAndTranslatedRecipeType() {
        var entry = new StellarPatternStuckReport.Entry(2, "", "reason", "in", "out", List.of(),
                "gt_shanhai:coin_forge", "gt_shanhai:recipe");
        var report = StellarPatternStuckReport.build(new ResourceLocation("minecraft", "the_end"),
                new BlockPos(12, 80, -9), List.of(entry));
        assertEquals("/shanhai stellar_slot minecraft:the_end 12 80 -9 2",
                report.names().getFirst().getStyle().getClickEvent().getValue());
        var args = sectionContent(report.details().getFirst(), "detail_location").getArgs();
        var coordinate = assertInstanceOf(Component.class, args[2]);
        assertEquals("/shanhai stellar_tp minecraft:the_end 12 80 -9",
                coordinate.getStyle().getClickEvent().getValue());
        var type = assertInstanceOf(Component.class, sectionContent(report.details().getFirst(), "detail_recipe").getArgs()[1]);
        assertTrue(type.getString().contains("(coin_forge)"));
        assertEquals("gui.gt_shanhai.ultimate_terminal.report.recipe_type_name.coin_forge",
                ((TranslatableContents) type.getContents()).getKey());
    }

    @Test
    void reportSeparatesConfirmedFailuresFromTimeoutWarningsAndPausedHints() {
        var entries = new ArrayList<StellarPatternStuckReport.Entry>();
        int slot = 0;
        for (var cause : StellarPatternStuckReport.Cause.values()) {
            entries.add(new StellarPatternStuckReport.Entry(slot++, "", "full reason", "in", "out",
                    List.of(), "gtceu:assembler", "recipe", cause));
        }
        var report = StellarPatternStuckReport.build(new ResourceLocation("minecraft", "overworld"),
                BlockPos.ZERO, entries);
        assertEquals(StellarPatternStuckReport.Cause.values().length, report.categories().size());
        assertEquals(0xF07878, StellarPatternStuckReport.Cause.STRONG_MATCH.color());
        assertEquals(0xF0C878, StellarPatternStuckReport.Cause.UNCONSUMED.color());
        assertEquals(0x78C8F0, StellarPatternStuckReport.Cause.PAUSED.color());
    }

    @Test
    void entryRetainsLongDiagnosticsAndCopiesTheHostTypeSnapshot() {
        String inputs = "minecraft:stone x 123; ".repeat(200);
        String reason = "主機狀態與並行評估; ".repeat(200);
        List<String> hosts = new ArrayList<>(List.of("gtceu:assembler", "gtceu:mixer"));
        var entry = new StellarPatternStuckReport.Entry(7, "", reason, inputs, "full output", hosts,
                "gtceu:assembler", "gt_shanhai:assembler/" + "long_id".repeat(100));

        hosts.clear();

        assertEquals(inputs, entry.inputs());
        assertEquals(reason, entry.reason());
        assertTrue(entry.patternRecipeId().length() > 220);
        assertEquals(List.of("gtceu:assembler", "gtceu:mixer"), entry.hostRecipeTypes());
        assertThrows(UnsupportedOperationException.class, () -> entry.hostRecipeTypes().clear());
        assertEquals("8", entry.displaySlot());
    }

    @Test
    void wildcardDisplayRetainsItsExpandedAndMotherSlotIdentity() {
        var entry = new StellarPatternStuckReport.Entry(1003, "通配展开 4 母槽 2", "wait", "input",
                "output", List.of(), "gtceu:assembler", "gt_shanhai:recipe");

        assertEquals("通配展开 4 母槽 2", entry.displaySlot());
        assertEquals(1003, entry.slot());
    }

    @Test
    void reportNamesContainShortReasonsAndDetailsRetainTheFullReason() {
        String reason = "完整主機狀態; ".repeat(300);
        var entry = new StellarPatternStuckReport.Entry(2, "", reason, "input", "output", List.of(),
                "gtceu:assembler", "gt_shanhai:recipe");
        var report = StellarPatternStuckReport.build(new ResourceLocation("minecraft", "overworld"),
                BlockPos.ZERO, List.of(entry));

        var name = assertInstanceOf(TranslatableContents.class, report.names().getFirst().getContents());
        assertEquals("gui.gt_shanhai.ultimate_terminal.report.slot_summary", name.getKey());
        assertEquals("3", name.getArgs()[0]);
        var summary = assertInstanceOf(Component.class, name.getArgs()[1]);
        assertEquals("gui.gt_shanhai.ultimate_terminal.report.reason_unconsumed",
                assertInstanceOf(TranslatableContents.class, summary.getContents()).getKey());
        assertTrue(report.details().getFirst().getString().contains(reason));
    }

    private static TranslatableContents sectionContent(Component details, String key) {
        return details.getSiblings().stream().map(Component::getContents)
                .filter(TranslatableContents.class::isInstance).map(TranslatableContents.class::cast)
                .filter(contents -> contents.getKey().equals("gui.gt_shanhai.ultimate_terminal.report." + key))
                .findFirst().orElseThrow(() -> new AssertionError("Missing section: " + key));
    }
}
