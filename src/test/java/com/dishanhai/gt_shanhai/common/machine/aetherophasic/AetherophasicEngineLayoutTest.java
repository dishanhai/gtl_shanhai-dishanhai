package com.dishanhai.gt_shanhai.common.machine.aetherophasic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：验证原 NBT 的尺寸、材料总量、设备墙位置，以及损坏 JSON 的拒绝路径。 */
class AetherophasicEngineLayoutTest {
    @Test
    void layoutPreservesOriginalDimensionsCountsAndEquipmentWall() {
        var layout = AetherophasicEngineLayout.load();
        assertEquals(87, layout.aisles.length);
        Map<Character, Integer> counts = new HashMap<>();
        for (String[] aisle : layout.aisles) {
            assertEquals(91, aisle.length);
            for (int y = 0; y < aisle.length; y++) {
                String row = aisle[y];
                assertEquals(85, row.length());
                if (y < 6) assertEquals(" ".repeat(85), row, "Keep the NBT's empty lower margin");
                for (char symbol : row.toCharArray()) {
                    if (symbol != ' ') counts.merge(symbol, 1, Integer::sum);
                }
            }
        }
        assertEquals(Map.of('A', 46262, 'B', 3014, 'C', 11391, 'D', 648, 'E', 1188,
                'F', 4052, 'G', 644, 'H', 12980, 'I', 6, 'J', 1), counts);
        assertEquals(80186, counts.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals('J', layout.controllerSymbol);
        assertEquals('I', layout.hatchSymbol);
        assertEquals("IIIJIII", layout.aisles[73][42].substring(39, 46));
        assertEquals("gtlcore:opv_hermetic_casing", layout.palette.get(8).get("Name").getAsString());
        assertEquals("gtlcore:spacetimebendingcore", layout.palette.get(9).get("Name").getAsString());
        assertEquals("true", layout.palette.get(9).getAsJsonObject("Properties").get("active").getAsString());
    }

    @Test
    void rejectsRowsThatWouldShiftOrTruncateBlocks() throws Exception {
        var root = resource();
        root.getAsJsonArray("aisles").get(73).getAsJsonArray().set(42,
                new com.google.gson.JsonPrimitive("short"));
        assertThrows(IllegalArgumentException.class, () -> read(root));
    }

    @Test
    void rejectsUnmappedSymbolsInsteadOfIgnoringThem() throws Exception {
        var root = resource();
        var rows = root.getAsJsonArray("aisles").get(0).getAsJsonArray();
        rows.set(0, new com.google.gson.JsonPrimitive("?" + " ".repeat(84)));
        assertThrows(IllegalArgumentException.class, () -> read(root));
    }

    @Test
    void rejectsMovedControllerAndMissingHatchMarkers() throws Exception {
        var moved = resource();
        moved.getAsJsonArray("controller").set(0, new com.google.gson.JsonPrimitive(41));
        assertThrows(IllegalArgumentException.class, () -> read(moved));
        var missing = resource();
        var rows = missing.getAsJsonArray("aisles").get(73).getAsJsonArray();
        rows.set(42, new com.google.gson.JsonPrimitive(rows.get(42).getAsString().replace('I', 'A')));
        assertThrows(IllegalArgumentException.class, () -> read(missing));
    }

    private static AetherophasicEngineLayout read(JsonObject root) {
        return AetherophasicEngineLayout.read(new StringReader(root.toString()));
    }

    private static JsonObject resource() throws Exception {
        try (var input = AetherophasicEngineLayoutTest.class.getResourceAsStream(AetherophasicEngineLayout.RESOURCE)) {
            assertNotNull(input);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
