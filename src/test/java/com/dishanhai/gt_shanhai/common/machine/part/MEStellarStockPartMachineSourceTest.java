package com.dishanhai.gt_shanhai.common.machine.part;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

class MEStellarStockPartMachineSourceTest {

    private static final Path MACHINE = source("MEStellarStockPartMachine.java");
    private static final Path REGISTRY = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "DShanhaiMachines.java");
    private static final Path ZH_LANG = Path.of("src", "main", "resources", "assets",
            "gt_shanhai", "lang", "zh_cn.json");
    private static final Path EN_LANG = Path.of("src", "main", "resources", "assets",
            "gt_shanhai", "lang", "en_us.json");

    @Test
    void machineKeepsParentStockingBehaviorAndUsesPersistedEncodedPatternSlot() throws Exception {
        String source = readMachine();

        assertTrue(source.contains("extends MEDualHatchStockPartMachine"));
        assertTrue(source.contains("super.autoIO();"));
        assertTrue(source.contains("@Persisted"));
        assertTrue(source.contains("PatternDetailsHelper.isEncodedPattern")
                || source.contains("PatternDetailsHelper::isEncodedPattern"));
        assertFalse(source.contains("implements ICraftingRequester"));
        assertFalse(source.contains("pushPattern("));
    }

    @Test
    void pullTargetsComeOnlyFromPatternInputsAndIncludeLongMultipliers() throws Exception {
        String source = readMachine() + Files.readString(source("MEStellarStockTargetPlanner.java"));

        assertTrue(source.contains("detail.getInputs()"));
        assertTrue(source.contains("input.getPossibleInputs()"));
        assertTrue(source.contains("input.getMultiplier()"));
        assertTrue(source.contains("instanceof AEItemKey"));
        assertTrue(source.contains("instanceof AEFluidKey"));
        assertTrue(source.contains("long"));
        assertFalse(source.contains("details.getOutputs()"));
    }

    @Test
    void machineIsRegisteredAndHasBothTranslations() throws Exception {
        String machine = readMachine();
        String registry = Files.readString(REGISTRY);
        String zh = Files.readString(ZH_LANG);
        String en = Files.readString(EN_LANG);

        assertTrue(machine.contains("MEStellarStockPartMachine"));
        assertTrue(registry.contains("ME_STELLAR_STOCK_PART"));
        assertTrue(registry.contains("\"me_stellar_stock_part_machine\""));
        assertTrue(registry.contains("PartAbility.IMPORT_ITEMS.register(0, stellarStockBlock)"));
        assertTrue(registry.contains("PartAbility.IMPORT_FLUIDS.register(0, stellarStockBlock)"));
        assertTrue(zh.contains("\"block.gt_shanhai.me_stellar_stock_part_machine\""));
        assertTrue(en.contains("\"block.gt_shanhai.me_stellar_stock_part_machine\""));
    }

    @Test
    void settingAPatternNeverExtractsOrStoresMaterialsLocally() throws Exception {
        String source = readMachine();

        assertFalse(source.contains("Actionable.MODULATE"));
        assertFalse(source.contains(".extract("));
        assertFalse(source.contains("new NotifiableItemStackHandler"));
        assertFalse(source.contains("new NotifiableFluidTank"));
        assertTrue(source.contains("BASE_PATTERN_SLOTS"));
        assertTrue(source.contains("new ItemStackTransfer(BASE_PATTERN_SLOTS)"));
        assertTrue(source.contains("MEStellarStockTargetPlanner.plan("));
    }

    @Test
    void patternRemovalRestoresConfigurationAndMachineRemovalDropsPattern() throws Exception {
        String source = readMachine();

        assertTrue(source.contains("savedStockConfiguration"));
        assertTrue(source.contains("restoreStockConfiguration()"));
        assertTrue(source.contains("clearInventory(patternInventory)"));
        assertTrue(source.contains("super.onMachineRemoved();"));
        assertTrue(source.contains("super.createUIWidget()"));
    }

    @Test
    void plannerUsesCheckedLongArithmeticAndDoesNotSilentlyTruncate() throws Exception {
        Path planner = source("MEStellarStockTargetPlanner.java");
        assertTrue(Files.isRegularFile(planner), "ME stock target planner has not been implemented");
        String source = Files.readString(planner);

        assertTrue(source.contains("Math.multiplyExact("));
        assertTrue(source.contains("Math.addExact("));
        assertTrue(source.contains("targets.size() > capacity"));
    }

    @Test
    void machineUsesItsOwnStellarCasingAndFrontOverlay() throws Exception {
        String registry = Files.readString(REGISTRY);

        assertTrue(registry.contains("\"block/casings/me_stellar_stock_part_machine_casing\""));
        assertTrue(registry.contains("\"block/machine/part/me_stellar_stock_part_machine\""));
    }

    @Test
    void stellarTexturesAreNonBlank32PixelPngsWithCorrectAlpha() throws Exception {
        Path textures = Path.of("src", "main", "resources", "assets", "gt_shanhai", "textures");
        Path[] paths = {
                textures.resolve("block/casings/me_stellar_stock_part_machine_casing.png"),
                textures.resolve("block/machine/part/me_stellar_stock_part_machine/overlay_front.png"),
                textures.resolve("block/machine/part/me_stellar_stock_part_machine/overlay_front_emissive.png")
        };
        int[] widths = {32, 32, 16};
        int[] heights = {256, 256, 128};
        for (int i = 0; i < paths.length; i++) {
            assertTrue(Files.isRegularFile(paths[i]), "Missing stellar texture: " + paths[i]);
            BufferedImage image = ImageIO.read(paths[i].toFile());
            assertTrue(image != null);
            assertTrue(image.getWidth() == widths[i] && image.getHeight() == heights[i]);
            int visible = 0;
            int transparent = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) >>> 24) == 0) {
                        transparent++;
                    } else {
                        visible++;
                    }
                }
            }
            assertTrue(visible > 0);
            assertTrue(i == 0 ? transparent == 0 : transparent > 0);
        }
    }

    @Test
    void patternInputSlotsAreVerticallyExpandedToFive() throws Exception {
        String source = readMachine();
        assertTrue(source.contains("private static final int BASE_PATTERN_SLOTS = 5"));
        assertTrue(source.contains("for (int i = 0; i < BASE_PATTERN_SLOTS; i++)"));
        assertTrue(source.contains("10 + i * 18"));
    }

    @Test
    void autoPullModeChecksAllPatternSlots() throws Exception {
        String source = readMachine();
        assertTrue(source.contains("if (!hasPattern())"));
        assertTrue(source.contains("private boolean hasPattern()"));
        assertTrue(source.contains("patternInventory.getSlots()"));
        assertFalse(source.contains("patternInventory.getStackInSlot(0).isEmpty()"));
    }

    @Test
    void configurationCapacityUsesSixteenSlotPages() throws Exception {
        String source = readMachine() + Files.readString(source("MEStellarStockTargetPlanner.java"));
        assertTrue(source.contains("BASE_CONFIG_SIZE = 64"));
        assertTrue(source.contains("CONFIG_PAGE_SIZE = 16"));
        assertTrue(source.contains("requiredCapacity"));
        assertTrue(source.contains("aeItemHandler.getSlots()"));
    }

    @Test
    void dynamicResizeKeepsTheParentBoundHandlerInstance() throws Exception {
        String source = readMachine();

        assertTrue(source.contains("replaceBackingInventory(oldItems, newItems)"));
        assertTrue(source.contains("replaceBackingInventory(oldFluids, newFluids)"));
        assertTrue(source.contains("itemTransfer.set(target, null)"));
        assertTrue(source.contains("fluidStorages.set(target, null)"));
        assertTrue(source.contains("target::onContentsChanged"));
        assertFalse(source.contains("aeItemHandler = newItems"));
        assertFalse(source.contains("aeFluidHandler = newFluids"));
    }

    @Test
    void manualStockConfigurationSurvivesAutomaticPatternRefresh() throws Exception {
        String source = readMachine();

        assertTrue(source.contains("manualStockConfiguration"));
        assertTrue(source.contains("automaticStockConfiguration"));
        assertTrue(source.contains("reconcileManualConfiguration()"));
        assertTrue(source.contains("mergeManualAndAutomatic"));
        assertTrue(source.contains("findManualSlot"));
        assertTrue(source.contains("Math.max("));
        assertTrue(source.contains("writeManualConfiguration"));
        assertTrue(source.contains("writeAutomaticConfiguration"));
        assertFalse(source.contains("aeItemHandler.clearInventory("));
        assertFalse(source.contains("aeFluidHandler.clearInventory("));
    }

    @Test
    void patternPullSwitchDefaultsToEnabledAndPersistsDisabledSlots() throws Exception {
        String source = readMachine();

        assertTrue(source.contains("@Persisted\n    private int disabledPatternMask;"),
                "Existing machines must default to all five patterns enabled");
        assertTrue(source.contains("(disabledPatternMask & (1 << slot)) == 0"));
        assertTrue(source.contains("disabledPatternMask &= ~(1 << slot)"));
        assertTrue(source.contains("disabledPatternMask |= 1 << slot"));
    }

    @Test
    void disabledPatternIsExcludedBeforeDecodingAndCacheComparison() throws Exception {
        String source = readMachine();

        assertTrue(source.contains("ItemStack current = isPatternPullEnabled(i)\n"
                + "                    ? patternInventory.getStackInSlot(i) : ItemStack.EMPTY;"),
                "Disabled patterns must never reach the decoder or its cached input list");
        assertTrue(source.indexOf("isPatternPullEnabled(i)")
                < source.indexOf("samePatterns(currentPatterns, decodedPatterns)"));
        assertTrue(source.contains("if (!current.isEmpty())"));
    }

    @Test
    void switchingPatternPullInvalidatesCacheAndRefreshesServerConfiguration() throws Exception {
        String source = readMachine();
        int start = source.indexOf("private void setPatternPullEnabled(");
        assertTrue(start >= 0, "Missing server-side pattern pull setter");
        String setter = source.substring(start, source.indexOf("private static List<", start));

        assertTrue(setter.contains("isRemote()"));
        assertTrue(setter.contains("slot < 0 || slot >= BASE_PATTERN_SLOTS"));
        assertTrue(setter.contains("isPatternPullEnabled(slot) == enabled"));
        assertTrue(setter.contains("decodedPatterns = List.of();"));
        assertTrue(setter.contains("cachedInputs = null;"));
        assertTrue(setter.contains("patternDirty = true;"));
        assertTrue(setter.contains("markDirty();"));
        assertTrue(setter.contains("updatePatternConfiguration();"));
    }

    @Test
    void disablingAllPatternsDoesNotEnableParentAutomaticPulling() throws Exception {
        String source = readMachine();
        int start = source.indexOf("private boolean hasPattern()");
        String hasPattern = source.substring(start,
                source.indexOf("private boolean isPatternPullEnabled(int slot)", start));

        assertTrue(hasPattern.contains("!patternInventory.getStackInSlot(i).isEmpty()"));
        assertFalse(hasPattern.contains("isPatternPullEnabled"),
                "Physical patterns must continue guarding the inherited automatic pull mode");
    }

    @Test
    void everyPatternSlotHasASyncedVerticalRedGreenSwitchInTheRightGap() throws Exception {
        String source = readMachine();

        assertTrue(source.contains("final int slot = i;"));
        assertTrue(source.contains("new SwitchWidget(19, 11 + i * 18, 4, 16,"));
        assertTrue(source.contains("setPatternPullEnabled(slot, enabled)"));
        assertTrue(source.contains(".setSupplier(() -> isPatternPullEnabled(slot))"));
        assertTrue(source.contains(".setPressed(isPatternPullEnabled(slot))"));
        assertTrue(source.contains("new ColorBorderTexture(-1, 0xFFFF3030)"));
        assertTrue(source.contains("new ColorBorderTexture(-1, 0xFF20B24B)"));
    }

    @Test
    void patternPullSwitchHasBothTranslations() throws Exception {
        String key = "\"gt_shanhai.machine.me_stellar_stock_part_machine.pattern_pull\"";
        assertTrue(Files.readString(ZH_LANG).contains(key));
        assertTrue(Files.readString(EN_LANG).contains(key));
    }

    private static Path source(String fileName) {
        return Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "common", "machine", "part", fileName);
    }

    private static String readMachine() throws Exception {
        assertTrue(Files.isRegularFile(MACHINE), "ME stellar stock machine has not been implemented");
        return Files.readString(MACHINE);
    }
}
