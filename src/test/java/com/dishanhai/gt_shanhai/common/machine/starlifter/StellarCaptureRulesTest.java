package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StellarCaptureRulesTest {
    @Test
    void readsExactItemCostsAndCapacity() {
        var rule = parse("144000", """
                [{"item":"minecraft:nether_star","count":8},{"item":"minecraft:diamond","count":4}]
                """);
        assertEquals(144_000, rule.workTicks());
        assertEquals(Map.of(Items.NETHER_STAR, 8, Items.DIAMOND, 4), rule.items());
    }

    @Test
    void rejectsInvalidNumbersAndCostsThatCannotBePaid() {
        var validItems = "[{\"item\":\"minecraft:diamond\",\"count\":4}]";
        for (var invalidCapacity : new String[]{"0", "1.5", "4294967297", "\"144000\""}) {
            assertThrows(JsonSyntaxException.class, () -> parse(invalidCapacity, validItems));
        }
        for (var invalidItems : new String[]{
                "[]",
                "[{\"item\":\"minecraft:diamond\",\"count\":4.5}]",
                "[{\"item\":\"minecraft:diamond\",\"count\":1},{\"item\":\"minecraft:diamond\",\"count\":2}]",
                "[{\"item\":\"gt_shanhai:unknown\",\"count\":1}]",
                "[{\"item\":\"minecraft:diamond_sword\",\"count\":37}]"
        }) {
            assertThrows(JsonSyntaxException.class, () -> parse("144000", invalidItems));
        }
    }

    private static StellarCaptureRules.Rules parse(String ticks, String items) {
        return StellarCaptureRules.parse(JsonParser.parseString("""
                {"work_ticks":%s,"reactivation_items":%s}
                """.formatted(ticks, items)).getAsJsonObject());
    }
}
