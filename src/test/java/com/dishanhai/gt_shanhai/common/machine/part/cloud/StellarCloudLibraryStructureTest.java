package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：主庫結構的尺寸、控制器、留空與方塊映射契約。 */
class StellarCloudLibraryStructureTest {
    private static final Path ROOT = Path.of("src/main");
    private static final Path JAVA = ROOT.resolve(
            "java/com/dishanhai/gt_shanhai/common/machine/part/cloud");

    private static List<String[]> layers() throws Exception {
        return Files.readAllLines(ROOT.resolve(
                        "resources/assets/gt_shanhai/multiblock/cloud_pattern_library.mb"))
                .stream().map(line -> line.split(",", -1)).toList();
    }

    @Test
    void libraryHasFifteenByElevenBySeventeenCellsAndOneCenteredFrontController() throws Exception {
        var layers = layers();
        assertEquals(11, layers.size());
        Map<Character, Integer> counts = new HashMap<>();
        for (String[] layer : layers) {
            assertEquals(17, layer.length);
            for (String row : layer) {
                assertEquals(15, row.length());
                row.chars().forEach(symbol -> counts.merge((char) symbol, 1, Integer::sum));
            }
        }
        assertEquals(1, counts.get('~'));
        assertEquals('~', cell(layers, 7, 3, 0));
        assertEquals(java.util.Set.of('M', 'I', 'N', 'P', 'G', 'F', 'H', 'C', '#', '~', '.'), counts.keySet());
        assertTrue(counts.get('M') >= 32, "兩側前後機架塔必須實際使用分子機械方塊");
    }

    @Test
    void bothRackBanksAreSymmetricAndTheAccessAisleStaysEmpty() throws Exception {
        var layers = layers();
        for (String[] layer : layers) {
            for (String row : layer) assertEquals(row, new StringBuilder(row).reverse().toString());
        }
        // 中央 5×5×5 全留空，容納核心與旋轉環，不用實心殼包住。
        for (int y = 4; y <= 8; y++) {
            for (int z = 6; z <= 10; z++) {
                for (int x = 5; x <= 9; x++) assertEquals('#', cell(layers, x, y, z));
            }
        }
        for (int z = 0; z < 17; z++) assertTrue(".#G".indexOf(cell(layers, 7, 6, z)) >= 0);
        for (int x = 0; x < 15; x++) assertTrue(".#G".indexOf(cell(layers, x, 6, 8)) >= 0);
    }

    @Test
    void everyRequestedMaterialIsMappedExactlyAndRequiredAirIsNotIgnored() throws Exception {
        var palette = assertDoesNotThrow(() -> StellarCloudLibraryStructure.class.getField("MATERIALS").get(null));
        assertEquals(Map.of(
                'M', "gtlcore:molecular_casing",
                'I', "gtlcore:iridium_casing",
                'N', "gtlcore:naquadah_alloy_casing",
                'P', "gtceu:high_power_casing",
                'G', "gtceu:fusion_glass",
                'F', "kubejs:containment_field_generator",
                'H', "kubejs:hollow_casing",
                'C', "kubejs:high_strength_concrete"), palette);
        String structure = Files.readString(JAVA.resolve("StellarCloudLibraryStructure.java"));
        assertTrue(structure.contains("MATERIALS.forEach"));
        assertTrue(structure.contains("Predicates.blocks(requireBlock(id))"));
        assertTrue(structure.contains("palette.put('~', Predicates.controller(Predicates.blocks(definition.getBlock())))"));
        assertTrue(structure.contains("palette.put('#', Predicates.air())"));
        assertTrue(structure.contains("DShanhaiMBParser.parse("));
        assertTrue(structure.contains("RelativeDirection.RIGHT, RelativeDirection.BACK, RelativeDirection.DOWN"),
                "文件由上到下、各層由前到後；不能把高度與深度互換");
        assertTrue(structure.contains("palette.put('.', Predicates.any())"), "外部留空容許環境方塊，核心內部仍嚴格匹配空氣");
        assertFalse(structure.contains("autoAbilities"), "資料庫直接連 ME，不應引入無作用的倉室");
    }

    @Test
    void foundationAndFieldCoreUseTheNewPaletteWithoutMissingBlockFallbacks() throws Exception {
        var layers = layers();
        assertEquals('C', cell(layers, 7, 0, 8));
        assertEquals('F', cell(layers, 7, 3, 8));
        assertEquals('F', cell(layers, 7, 9, 8));
        assertEquals('G', cell(layers, 1, 6, 8));
        assertEquals('G', cell(layers, 13, 6, 8));
        for (int x = 5; x <= 9; x++) {
            for (int z = 6; z <= 10; z++) assertEquals('G', cell(layers, x, 10, z),
                    "核心上方只用聚變玻璃，避免俯視時被實心穹頂遮住");
        }
        assertEquals('.', cell(layers, 0, 10, 0), "穹頂外的空位不能形成實心方盒");
        String structure = Files.readString(JAVA.resolve("StellarCloudLibraryStructure.java"));
        assertTrue(structure.contains("block == null || block == Blocks.AIR"));
        assertFalse(structure.contains("Blocks.BARRIER"));
        assertFalse(structure.contains("DShanhaiBlocks"));
        assertFalse(structure.contains("SEA_LANTERN"));
        String machine = Files.readString(JAVA.resolve("StellarCloudLibraryMachine.java"));
        assertTrue(machine.contains(".appearanceBlock(StellarCloudLibraryStructure::appearanceBlock)"));
        assertTrue(machine.contains("new ResourceLocation(\"gtlcore\", \"block/casings/iridium_casing\")"));
        assertTrue(machine.contains("StellarCloudLibraryRenderer"));
        assertTrue(machine.contains(".hasTESR(true)"));
    }

    private static char cell(List<String[]> layers, int x, int y, int z) {
        return layers.get(10 - y)[z].charAt(x);
    }

    @Test
    void compiledFactoryLoadsThePackagedShapeWithTheCorrectAxes() throws Exception {
        // 山海：JUnit 的 GTCEu 為 SRG 原件，直接初始化方向類會碰到 MC 映射差異。
        // 檢查實際編譯的工廠呼叫及打包資源，不假裝已執行遊戲內結構匹配。
        ClassNode compiled = new ClassNode();
        try (var input = getClass().getResourceAsStream("StellarCloudLibraryStructure.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(compiled, ClassReader.SKIP_DEBUG);
        }
        var factory = compiled.methods.stream().filter(method -> method.name.equals("createPattern"))
                .findFirst().orElseThrow();
        List<String> axes = new ArrayList<>();
        List<String> constants = new ArrayList<>();
        boolean parserCalled = false;
        for (var instruction : factory.instructions) {
            if (instruction instanceof FieldInsnNode field && field.owner.endsWith("/RelativeDirection")) {
                axes.add(field.name);
            }
            if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) constants.add(value);
            if (instruction instanceof MethodInsnNode method && method.owner.endsWith("/DShanhaiMBParser")
                    && method.name.equals("parse")) parserCalled = true;
        }
        assertEquals(List.of("RIGHT", "BACK", "DOWN"), axes);
        assertTrue(parserCalled);
        assertTrue(constants.containsAll(List.of("gt_shanhai", "cloud_pattern_library")));
        try (var input = getClass().getResourceAsStream(
                "/assets/gt_shanhai/multiblock/cloud_pattern_library.mb")) {
            assertNotNull(input);
            String packaged = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(layers().stream().map(layer -> String.join(",", layer)).toList(), packaged.lines().toList());
        }
    }

    @Test
    void registrationAndDiscoveryRequireTheFormedController() throws Exception {
        String machine = Files.readString(JAVA.resolve("StellarCloudLibraryMachine.java"));
        assertTrue(machine.contains("extends MultiblockControllerMachine"));
        assertTrue(machine.contains("IInteractedMachine, IUIMachine"), "轉換控制器後仍須保留原管理介面");
        assertTrue(machine.contains("StellarCloudLibraryMachine.class, MultiblockControllerMachine.MANAGED_FIELD_HOLDER"));
        assertTrue(machine.contains(".multiblock(\"cloud_pattern_library\", StellarCloudLibraryMachine::new)"));
        assertTrue(machine.contains(".pattern(StellarCloudLibraryStructure::createPattern)"));
        assertTrue(machine.contains("RotationState.NON_Y_AXIS"));
        assertTrue(machine.contains("return online && isFormed();"));
        assertTrue(machine.contains("private boolean connectedTo(ServerLevel level, IGrid grid)"));
        assertTrue(machine.contains("if (!isFormed() || getLevel() == null"));
        assertTrue(machine.contains("if (!main.connectedTo(level, grid)) continue;"),
                "遠端上傳與星律供給都應透過同一成形及網路檢查");
        int onLoad = machine.indexOf("@Override public void onLoad()");
        int superLoad = machine.indexOf("super.onLoad()", onLoad);
        assertTrue(machine.substring(onLoad, superLoad).contains("isFormed = false"),
                "重載後必須重新驗證，不能使用存檔中的舊成形旗標");
        assertTrue(machine.contains("PREFIX + \"tooltip.structure\""));
        String ui = Files.readString(JAVA.resolve("StellarCloudLibraryUi.java"));
        assertTrue(ui.contains("\"structure.formed\""));
        assertTrue(ui.contains("\"structure.invalid\""));
    }
}
