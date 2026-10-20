package com.dishanhai.gt_shanhai.common.machine.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * 校验结构数据从 Java String[] 迁移到 .mb 资源后仍能被 DShanhaiMBParser 正确加载。
 *
 * 该测试只依赖 classpath 上的资源文件，不触碰 Minecraft 注册表，
 * 因此可以在普通 JUnit 环境运行。
 */
class MultiblockDataResourceTest {

    /** DShanhaiMBParser.loadMB 拼出的资源路径规则 */
    private static String resourcePath(String namespace, String path) {
        return "/assets/" + namespace + "/multiblock/" + path.replace("multiblock/", "") + ".mb";
    }

    private static int[] countLayersAndRows(String namespace, String path) throws Exception {
        String file = resourcePath(namespace, path);
        try (InputStream is = MultiblockDataResourceTest.class.getResourceAsStream(file)) {
            assertNotNull(is, "missing multiblock resource: " + file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            int layers = 0;
            int rows = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                layers++;
                rows += line.split(",", -1).length;
            }
            return new int[] { layers, rows };
        }
    }

    @Test
    void harpoonSatelliteMatchesOriginalShape() throws Exception {
        int[] shape = countLayersAndRows("gt_shanhai", "harpoon_satellite");
        assertEquals(82, shape[0], "harpoon_satellite layer count");
        assertEquals(82 * 112, shape[1], "harpoon_satellite row count");
    }

    @Test
    void strStructureMatchesOriginalShape() throws Exception {
        int[] shape = countLayersAndRows("gt_shanhai", "str_structure");
        assertEquals(50, shape[0], "str_structure layer count");
        assertEquals(50 * 56, shape[1], "str_structure row count");
    }

    @Test
    void worldlineCrackingHubMatchesOriginalShape() throws Exception {
        int[] shape = countLayersAndRows("gt_shanhai", "worldline_cracking_hub");
        assertEquals(73, shape[0], "worldline_cracking_hub layer count");
        assertEquals(73 * 68, shape[1], "worldline_cracking_hub row count");
    }

    /** 所有层必须等宽，否则 FactoryBlockPattern.aisle 会抛 IllegalArgumentException */
    @Test
    void everyLayerIsRectangular() throws Exception {
        for (String path : new String[] { "harpoon_satellite", "str_structure", "worldline_cracking_hub" }) {
            String file = resourcePath("gt_shanhai", path);
            try (InputStream is = MultiblockDataResourceTest.class.getResourceAsStream(file)) {
                assertNotNull(is, "missing multiblock resource: " + file);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                int expectedRows = -1;
                int expectedWidth = -1;
                int layer = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    layer++;
                    String[] segs = line.split(",", -1);
                    if (expectedRows < 0) {
                        expectedRows = segs.length;
                        expectedWidth = segs[0].length();
                    }
                    assertEquals(expectedRows, segs.length, path + " layer " + layer + " row count");
                    for (String seg : segs) {
                        assertEquals(expectedWidth, seg.length(), path + " layer " + layer + " row width");
                    }
                }
                assertTrue(layer > 0, path + " must have at least one layer");
            }
        }
    }
}
