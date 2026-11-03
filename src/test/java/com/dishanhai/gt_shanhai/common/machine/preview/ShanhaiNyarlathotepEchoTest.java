package com.dishanhai.gt_shanhai.common.machine.preview;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：回響的獨立配方、真實成本、無強載連結與非方盒結構契約。 */
class ShanhaiNyarlathotepEchoTest {
    private static final Path MAIN = Path.of("src/main/java/com/dishanhai/gt_shanhai");
    @Test void echoHasIndependentRecipeAndLocalPower() throws Exception {
        String machine = Files.readString(MAIN.resolve("common/machine/preview/ShanhaiNyarlathotepEchoMachine.java"));
        assertTrue(machine.contains("extends WorkableElectricMultiblockMachine"));
        assertTrue(machine.contains("VOICE_OF_THE_FACELESS"));
        assertFalse(machine.contains("GRAVITATIONAL_WAVE_CONSUMPTION"));
        assertFalse(machine.contains("WirelessNetworkEnergyHandler"));
        assertTrue(machine.contains("@Persisted @DescSynced"));
        assertTrue(machine.contains("remainingTicks"));
        assertTrue(machine.contains("@Persisted @DescSynced private double paidFactor"), "Paid tier survives reload without losing stored lenses");
        assertTrue(machine.contains("addEchoSource"));
    }
    @Test void costsAreCopiedBeforeInputMatchAndDurationComesFromTheRunningRecipe() throws Exception {
        String machine = Files.readString(MAIN.resolve("common/machine/preview/ShanhaiNyarlathotepEchoMachine.java"));
        String logic = Files.readString(MAIN.resolve("common/machine/preview/ShanhaiNyarlathotepEchoRecipeLogic.java"));
        assertTrue(machine.contains("modifyRecipe(GTRecipe recipe)"));
        assertTrue(machine.contains("ShanhaiNyarlathotepEchoRecipeScale.apply"));
        assertTrue(logic.contains("extends RecipeLogic"));
        assertTrue(logic.contains("handleRecipeWorking()"));
        assertTrue(logic.contains("getProgress()"));
        assertTrue(logic.contains("getDuration()"));
        assertFalse(logic.contains("MultipleRecipesLogic"));
    }
    @Test void linkedTierIsResolvedWithoutForcingChunksAndLensInventoryIsNeverTruncated() throws Exception {
        String machine = Files.readString(MAIN.resolve("common/machine/preview/ShanhaiNyarlathotepEchoMachine.java"));
        String link = Files.readString(MAIN.resolve("common/machine/preview/ShanhaiNyarlathotepEchoLink.java"));
        assertTrue(link.contains("GuixuNyarlathotepLink.machineAt"));
        assertTrue(link.contains("isNyarlathotepLinkLoaded()"));
        assertFalse(link.contains("getChunk("));
        assertTrue(machine.contains("GuixuNyarlathotepLink.resolveHost"));
        assertTrue(machine.contains("return 64;"));
        assertFalse(machine.contains("setCount("));
        assertFalse(machine.contains("shrink("));
    }
    @Test void newVoiceRecipesAreRealRecipesWithNoInheritedBroadcastType() throws Exception {
        for (String name : new String[]{"quantized_reality_item", "quantized_reality_fluid"}) {
            var recipe = JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/gt_shanhai/recipes/voice_of_the_faceless/" + name + ".json"))).getAsJsonObject();
            assertEquals("gtceu:voice_of_the_faceless", recipe.get("type").getAsString());
            assertTrue(recipe.get("duration").getAsInt() > 0);
            assertTrue(recipe.getAsJsonObject("inputs").has("fluid"));
            assertTrue(recipe.getAsJsonObject("tickInputs").has("eu"));
        }
    }
    @Test void structureMatchesExpandedThirteenByTwelveByFifteenMapping() throws Exception {
        Class<?> layout = Class.forName("com.dishanhai.gt_shanhai.common.machine.preview.ShanhaiNyarlathotepEchoLayout");
        String[][] aisles = (String[][]) layout.getMethod("aisles").invoke(null);
        assertEquals(15, aisles.length);
        int controllers = 0, solids = 0, hatches = 0;
        for (String[] rows : aisles) {
            assertEquals(12, rows.length);
            for (String row : rows) {
                assertEquals(13, row.length());
                for (char c : row.toCharArray()) {
                    if (c == 'S') controllers++;
                    if (c == 'C') hatches++;
                    if (c != ' ') solids++;
                }
            }
        }
        assertEquals(1, controllers);
        assertEquals(402, solids);
        assertEquals(14, hatches);
        for (int y = 4; y <= 6; y++) for (int x = 4; x <= 8; x++)
            assertEquals(x == 6 && y == 5 ? 'S' : 'C', aisles[0][11 - y].charAt(x));
        assertEquals('H', aisles[8][5].charAt(6));
        assertEquals(' ', aisles[14][5].charAt(6), "Broadcast mouth remains open");
    }
    @Test void interfaceCabHasFiveRowsWithCenteredControllerAndNoSeaLanterns() throws Exception {
        String[][] aisles = ShanhaiNyarlathotepEchoLayout.aisles();
        for (int z = 0; z <= 3; z++) for (int y = 0; y <= 2; y++)
            for (int x = 0; x < 13; x++) assertEquals(' ', aisles[z][11 - y].charAt(x), "Red-box lower cab must be empty");
        for (int y = 3; y <= 7; y++) for (int x = 3; x <= 9; x++)
            assertNotEquals(' ', aisles[0][11 - y].charAt(x), "Five-row front panel remains continuous");
        for (int x = 3; x <= 9; x++) {
            assertEquals('M', aisles[0][4].charAt(x), "Top frame remains solid casing");
            assertEquals('M', aisles[0][8].charAt(x), "Bottom frame matches the top frame");
        }
        for (int row = 4; row <= 8; row++)
            assertEquals(aisles[0][row], aisles[0][12 - row], "Front panel is vertically centered on the controller row");
        for (String[] rows : aisles) for (String row : rows) for (char symbol : row.toCharArray()) {
            assertNotEquals('L', symbol);
            if (symbol != ' ' && symbol != 'S') assertNotEquals("minecraft:sea_lantern", ShanhaiNyarlathotepEchoLayout.blockId(symbol));
        }
        assertEquals('S', aisles[0][6].charAt(6), "Controller sits at the center of both the five-row cab and the 5x3 interface");
        // 左右鏡像包含同一材料。
        for (String[] rows : aisles) for (String row : rows) for (int x = 0; x < 13; x++)
            assertEquals(row.charAt(x), row.charAt(12 - x));
    }
    @Test void approvedMappingIsPackagedAndLoadedWithoutChangingAnyCell() throws Exception {
        try (var input = getClass().getResourceAsStream("/assets/gt_shanhai/multiblock/nyarlathoteps_echo.mb")) {
            assertNotNull(input, "The actual machine must carry its approved MB in the jar");
            String mb = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            StringBuilder actual = new StringBuilder();
            for (String[] rows : ShanhaiNyarlathotepEchoLayout.aisles())
                actual.append(String.join(",", rows)).append('\n');
            assertEquals(mb, actual.toString());
            assertEquals("c7d198909a635cfeb2ef1c4a5c3f9ab0ef7017ef662d08579e2117ced7939194", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mb.getBytes(StandardCharsets.UTF_8))));
        }
    }
    @Test void allMolecularCasingsAcceptLocalHatchesAndOtherMaterialsDoNot() throws Exception {
        var hatchPolicy = ShanhaiNyarlathotepEchoLayout.class.getMethod("allowsHatch", char.class);
        int hatchPositions = 0;
        for (String[] rows : ShanhaiNyarlathotepEchoLayout.aisles()) for (String row : rows)
            for (char symbol : row.toCharArray()) {
                boolean molecularCasing = symbol != ' ' && symbol != 'S'
                        && ShanhaiNyarlathotepEchoLayout.blockId(symbol).equals("gtlcore:molecular_casing");
                boolean allowsHatch = (boolean) hatchPolicy.invoke(null, symbol);
                assertEquals(molecularCasing, allowsHatch, "Hatch replacement must follow the actual casing material");
                if (allowsHatch) hatchPositions++;
            }
        assertEquals(34, hatchPositions, "Both the fourteen interface casings and twenty frame casings accept hatches");
        var method = ShanhaiNyarlathotepEchoLayout.class.getMethod("blockId", char.class);
        var expected = Map.ofEntries(
                Map.entry('C', "gtlcore:molecular_casing"), Map.entry('M', "gtlcore:molecular_casing"),
                Map.entry('A', "gtlcore:sps_casing"), Map.entry('B', "gtladditions:extreme_density_casing"),
                Map.entry('R', "kubejs:uruium_coil_block"), Map.entry('D', "kubejs:containment_field_generator"),
                Map.entry('T', "kubejs:spacetime_assembly_line_unit"), Map.entry('F', "kubejs:restraint_device"),
                Map.entry('O', "kubejs:hollow_casing"), Map.entry('H', "gtlcore:hyper_core"),
                Map.entry('G', "gtceu:fusion_glass"));
        for (var entry : expected.entrySet()) assertEquals(entry.getValue(), method.invoke(null, entry.getKey()));
        String structure = Files.readString(MAIN.resolve("common/machine/preview/ShanhaiNyarlathotepEchoStructure.java"));
        assertTrue(structure.contains("ShanhaiNyarlathotepEchoLayout.allowsHatch(symbol)"));
        assertEquals(1, structure.split("Predicates.autoAbilities", -1).length - 1);
        assertTrue(structure.contains("state ->"), "KubeJS blocks are resolved lazily at match time");
        assertTrue(structure.contains("ForgeRegistries.BLOCKS.containsKey(id)"), "Missing blocks must not form the machine");
        assertTrue(structure.contains("Predicates.air()"));
        String machine = Files.readString(MAIN.resolve("common/machine/preview/ShanhaiNyarlathotepEchoMachine.java"));
        assertTrue(machine.contains(".appearanceBlock(ShanhaiNyarlathotepEchoStructure::appearanceBlock)"));
        assertTrue(machine.contains("new ResourceLocation(\"gtlcore\", \"block/molecular_casing\")"));
    }
}
