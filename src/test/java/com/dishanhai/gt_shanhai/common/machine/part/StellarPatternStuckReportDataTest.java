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
    void reportCarriesExecutableDimensionAwareLinksAndTranslatedRecipeType() {
        var entry = new StellarPatternStuckReport.Entry(2, "", "reason", "in", "out", List.of(),
                "gt_shanhai:coin_forge", "gt_shanhai:recipe");
        var report = StellarPatternStuckReport.build(new ResourceLocation("minecraft", "the_end"),
                new BlockPos(12, 80, -9), List.of(entry));
        assertEquals("/shanhai stellar_slot minecraft:the_end 12 80 -9 2",
                report.names().getFirst().getStyle().getClickEvent().getValue());
        var args = ((TranslatableContents) report.details().getFirst().getContents()).getArgs();
        var coordinate = assertInstanceOf(Component.class, args[2]);
        assertEquals("/shanhai stellar_tp minecraft:the_end 12 80 -9",
                coordinate.getStyle().getClickEvent().getValue());
        var type = assertInstanceOf(Component.class, args[5]);
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
        var details = assertInstanceOf(TranslatableContents.class, report.details().getFirst().getContents());
        assertEquals(reason, details.getArgs()[3]);
    }
}
