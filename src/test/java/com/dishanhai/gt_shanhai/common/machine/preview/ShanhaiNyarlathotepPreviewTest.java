package com.dishanhai.gt_shanhai.common.machine.preview;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：獨立解碼來源 MBS，防止 MB 匯入出現轉置、鏡像或空白丟失。 */
class ShanhaiNyarlathotepPreviewTest {
    private static final Path RESOURCE = Path.of("src/main/resources/assets/gt_shanhai");
    private static final Path SOURCE = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/preview");
    private static final String ID = "nyarlathoteps_tentacle_preview";

    @Test
    void mbMatchesEveryCellOfOriginalMbs() throws Exception {
        Path mb = RESOURCE.resolve("multiblock/nyarlathoteps_tentacle.mb");
        assertTrue(Files.exists(mb), "Missing converted MB structure");
        byte[] original = Files.readAllBytes(Path.of("src/test/resources/structures/nyarlathoteps_tentacle.mbs"));
        byte[] raw;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(original))) {
            raw = gzip.readAllBytes();
        }
        assertArrayEquals(new byte[]{-1, -1, -1, -1, 15, 1, 2, 0, 4, 49, 48},
                java.util.Arrays.copyOf(raw, 11));
        List<String> slices = Files.readAllLines(mb);
        assertEquals(49, slices.size());
        int cursor = 11;
        int solid = 0;
        int controllers = 0;
        int restraints = 0;
        var symbols = new HashSet<Character>();
        for (int z = 0; z < 49; z++) {
            if (z > 0) assertEquals(48, raw[cursor++] & 255);
            String[] rows = slices.get(z).split(",", -1);
            assertEquals(48, rows.length);
            for (int y = 0; y < 48; y++) {
                assertEquals(0, raw[cursor++] & 255);
                assertEquals(165, raw[cursor++] & 255);
                String expected = new String(raw, cursor, 165, StandardCharsets.US_ASCII);
                cursor += 165;
                assertEquals(expected, rows[y], "z=" + z + ", y=" + y);
                for (int x = 0; x < 165; x++) {
                    char symbol = rows[y].charAt(x);
                    if (symbol != ' ') { solid++; symbols.add(symbol); }
                    if (symbol == '~') restraints++;
                    if (symbol == 'a') {
                        controllers++;
                        assertEquals(82, x);
                        assertEquals(24, y);
                        assertEquals(48, z);
                    }
                }
            }
        }
        assertEquals(raw.length, cursor);
        assertEquals(29045, solid);
        assertEquals(33, symbols.size());
        assertEquals(1, controllers);
        assertEquals(34, restraints);
    }

    @Test
    void previewHasNoProductionLogicOrPeriodicStructureScan() throws Exception {
        Path file = SOURCE.resolve("ShanhaiNyarlathotepPreviewMachine.java");
        assertTrue(Files.exists(file), "Missing preview controller");
        String source = Files.readString(file);
        assertTrue(source.contains("extends MultiblockControllerMachine"));
        assertTrue(source.contains("void asyncCheckPattern(long periodID)"));
        assertFalse(source.contains("super.asyncCheckPattern("));
        assertFalse(source.contains("RecipeLogic"));
        assertFalse(source.contains("WorkableElectric"));
        assertTrue(source.contains("GTRecipeTypes.DUMMY_RECIPES"));
        String registration = Files.readString(SOURCE.getParent().resolve("DShanhaiMachines.java"));
        assertTrue(registration.contains("ShanhaiNyarlathotepPreviewMachine.register()"));
    }

    @Test
    void mappingIsCompleteLazyAndUsesOriginalDirections() throws Exception {
        Path file = SOURCE.resolve("ShanhaiNyarlathotepPreviewStructure.java");
        assertTrue(Files.exists(file), "Missing symbol mapping");
        String source = Files.readString(file);
        for (char symbol : "ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_`~a".toCharArray()) {
            assertTrue(source.contains("case '" + symbol + "'"), "Missing symbol " + symbol);
        }
        assertTrue(source.contains("RelativeDirection.LEFT, RelativeDirection.UP, RelativeDirection.FRONT"));
        assertTrue(source.contains("case '~': return lazy(\"kubejs:restraint_device\")"));
        assertTrue(source.contains("case 'Q': return Predicates.heatingCoils()"));
        assertTrue(source.contains("containsKey(id)"), "Unknown ids must not silently become registry air");
        assertFalse(source.contains("static final Block "), "Avoid caching late registries during class initialization");
        assertFalse(source.contains("Predicates.any()"), "Missing solid blocks must remain visible in preview");
    }

    @Test
    void localizedNamesClearlyMarkPreviewOnly() throws Exception {
        for (String lang : List.of("zh_cn", "en_us")) {
            var json = JsonParser.parseString(Files.readString(RESOURCE.resolve("lang/" + lang + ".json"))).getAsJsonObject();
            assertTrue(json.has("block.gt_shanhai." + ID));
            for (int i = 0; i < 3; i++) {
                assertTrue(json.has("gt_shanhai.multiblock." + ID + ".tooltip." + i));
            }
        }
    }
}
