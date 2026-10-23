package com.dishanhai.gt_shanhai.common.machine.primordial;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：與稠密參考遍歷比較，空氣格不可被當成任意格省略。 */
class PrimordialPatternIndexTest {
    @Test
    void skipsOnlyAnyAndKeepsAirAndPredicateOrder() {
        String[][][] pattern = {{{"any", "air", "casing"}, {"part", "any", "air"}},
                {{"any", "any", "any"}, {"any", "any", "any"}}};
        int[][] cells = PrimordialPatternIndex.compile(pattern, "any"::equals);
        assertArrayEquals(new int[]{1, 2, 3, 5}, cells[0]);
        assertArrayEquals(new int[0], cells[1]);
        assertEquals("any", pattern[0][0][0], "不得修改原始結構定義");
    }

    @Test
    void randomLayoutsMatchDenseReferenceOrder() {
        Random random = new Random(20261023);
        for (int trial = 0; trial < 200; trial++) {
            int width = 1 + random.nextInt(30);
            int height = 1 + random.nextInt(30);
            Integer[][][] pattern = new Integer[1 + random.nextInt(8)][height][width];
            List<String> expected = new ArrayList<>();
            for (int aisle = 0; aisle < pattern.length; aisle++) {
                for (int row = 0; row < height; row++) {
                    for (int column = 0; column < width; column++) {
                        pattern[aisle][row][column] = random.nextInt(5);
                        if (pattern[aisle][row][column] != 0) {
                            expected.add(aisle + ":" + row + ":" + column);
                        }
                    }
                }
            }
            int[][] cells = PrimordialPatternIndex.compile(pattern, value -> value == 0);
            List<String> actual = new ArrayList<>();
            for (int aisle = 0; aisle < cells.length; aisle++) {
                for (int cell : cells[aisle]) actual.add(aisle + ":" + cell / width + ":" + cell % width);
            }
            assertEquals(expected, actual);
        }
    }

    @Test
    void emptyAndSingleCellPatternsKeepTheirMeaning() {
        assertEquals(0, PrimordialPatternIndex.compile(new String[0][][], "any"::equals).length);
        assertArrayEquals(new int[]{0},
                PrimordialPatternIndex.compile(new String[][][]{{{"air"}}}, "any"::equals)[0]);
    }

    @Test
    void realHostAndModuleResourcesKeepAllRequiredPositions() throws Exception {
        try (ZipFile jar = new ZipFile(Path.of("libs/gtladditions-3.3.1Custom.jar").toFile())) {
            verifyResource(jar, "forge_of_the_antichrist", 688721, 19851);
            verifyResource(jar, "forge_of_the_antichrist_module", 637, 89);
        }
    }

    private static void verifyResource(ZipFile jar, String id, int expectedVolume, int expectedActive)
            throws Exception {
        // 只解碼當前依賴的 GTLASB3 資料，不啟動 Minecraft 或改寫反編譯來源。
        var entry = jar.getEntry("assets/gtladditions/structures/multiblock/" + id + ".bin");
        assertNotNull(entry);
        try (DataInputStream input = new DataInputStream(jar.getInputStream(entry))) {
            assertEquals("GTLASB3", new String(input.readNBytes(7), StandardCharsets.US_ASCII));
            input.readUnsignedByte();
            assertEquals(id, new String(input.readNBytes(input.readUnsignedShort()), StandardCharsets.UTF_8));
            int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
            int[] order = orders[input.readUnsignedByte()];
            int aisles = input.readUnsignedShort();
            int rows = input.readUnsignedShort();
            int width = input.readUnsignedShort();
            int dictionarySize = input.readUnsignedShort();
            String[] dictionary = new String[dictionarySize];
            for (int i = 0; i < dictionarySize; i++) {
                dictionary[i] = new String(input.readNBytes(width), StandardCharsets.US_ASCII);
            }
            String[][] stored = new String[aisles][rows];
            for (int aisle = 0; aisle < aisles; aisle++) {
                for (int row = 0; row < rows; row++) {
                    stored[aisle][row] = dictionary[dictionarySize <= 256
                            ? input.readUnsignedByte() : input.readUnsignedShort()];
                }
            }
            assertEquals(-1, input.read());
            int[] size = new int[3];
            size[order[0]] = aisles;
            size[order[1]] = rows;
            size[order[2]] = width;
            assertEquals(expectedVolume, size[0] * size[1] * size[2]);
            Character[][][] pattern = new Character[size[0]][size[1]][size[2]];
            for (int a = 0; a < size[0]; a++) {
                for (int r = 0; r < size[1]; r++) {
                    for (int c = 0; c < size[2]; c++) {
                        int[] coordinates = {a, r, c};
                        pattern[a][r][c] = stored[coordinates[order[0]]][coordinates[order[1]]]
                                .charAt(coordinates[order[2]]);
                    }
                }
            }
            int[][] cells = PrimordialPatternIndex.compile(pattern, value -> value == ' ');
            int active = 0;
            for (int a = 0; a < size[0]; a++) {
                int index = 0;
                for (int r = 0; r < size[1]; r++) {
                    for (int c = 0; c < size[2]; c++) {
                        if (pattern[a][r][c] != ' ') {
                            assertEquals(r * size[2] + c, cells[a][index++]);
                        }
                    }
                }
                assertEquals(index, cells[a].length);
                active += index;
            }
            assertEquals(expectedActive, active);
        }
    }
}
