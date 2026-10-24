package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GuixuFunctionsTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai");
    private static final String PACKAGE = "com.dishanhai.gt_shanhai.common.machine.primordial.guixu.";

    @Test
    void dedicatedTypesAndPhysicalIoReplaceTheAssemblerPlaceholder() throws Exception {
        String types = Files.readString(ROOT.resolve("api/recipe/DShanhaiRecipeTypes.java"));
        for (String id : new String[]{"guixu_matter_decompression", "guixu_energy_extraction", "guixu_paradigm_simplification"}) {
            assertTrue(types.contains("register(\"" + id + "\""), id);
        }
        String registration = Files.readString(ROOT.resolve("common/machine/primordial/guixu/GuixuVisionCoreMachines.java"));
        assertFalse(registration.contains("ASSEMBLER_RECIPES"));
        String structure = Files.readString(ROOT.resolve("common/machine/primordial/guixu/GuixuVisionCoreStructure.java"));
        for (String ability : new String[]{"IMPORT_ITEMS", "EXPORT_ITEMS", "IMPORT_FLUIDS", "EXPORT_FLUIDS"}) {
            assertTrue(structure.contains("PartAbility." + ability), ability);
        }
    }

    private long[] plan(BigInteger amount, long... available) throws Exception {
        return (long[]) Class.forName(PACKAGE + "GuixuEnergyTransfer")
                .getMethod("plan", BigInteger.class, long[].class).invoke(null, amount, available);
    }

    @Test
    void energyPlanningRemainsExactAboveLongMaxValue() throws Exception {
        BigInteger amount = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO).add(BigInteger.ONE);
        assertArrayEquals(new long[]{Long.MAX_VALUE, Long.MAX_VALUE, 1}, plan(amount, Long.MAX_VALUE, Long.MAX_VALUE, 7));
        assertArrayEquals(new long[]{5, 3}, plan(BigInteger.valueOf(8), 5, 9));
        assertArrayEquals(new long[]{0, 0}, plan(BigInteger.ZERO, 5, 9));
    }

    @Test
    void insufficientEnergyOrCapacityProducesNoPartialPlan() throws Exception {
        long[] available = {3, 4};
        assertNull(plan(BigInteger.TEN, available));
        assertArrayEquals(new long[]{3, 4}, available);
        assertNull(plan(BigInteger.ONE));
    }

    @Test
    void actualWorkingStateWinsOverOtherBlockedModules() throws Exception {
        var resolve = Class.forName(PACKAGE + "GuixuOperationState")
                .getMethod("resolve", boolean.class, boolean.class, boolean.class);
        assertEquals("IDLE", resolve.invoke(null, false, false, false).toString());
        assertEquals("BLOCKED", resolve.invoke(null, false, false, true).toString());
        assertEquals("PROCESSING", resolve.invoke(null, true, false, true).toString());
        assertEquals("GENERATING", resolve.invoke(null, true, true, true).toString());
    }

    @Test
    void generatorReadsOutputEuAndUsesTheLocalEnergyHandler() throws Exception {
        String machine = Files.readString(ROOT.resolve("common/machine/primordial/guixu/GuixuVisionCoreMachine.java"));
        assertTrue(machine.contains("new GuixuEnergyHandler(this)"));
        String logic = Files.readString(ROOT.resolve("common/machine/primordial/guixu/GuixuVisionCoreRecipeLogic.java"));
        assertTrue(logic.contains("RecipeHelper.getOutputEUt(recipe)"));
    }

    @Test
    void modulePageReadsOnlyLoadedChunksAndUsesTheCurrentGuiPlayer() throws Exception {
        String page = Files.readString(ROOT.resolve("common/machine/primordial/guixu/GuixuControlPanels.java"));
        assertTrue(page.indexOf("!level.hasChunkAt(pos)") < page.indexOf("MetaMachine.getMachine(level, pos)"));
        assertTrue(page.contains("page.getGui().entityPlayer instanceof ServerPlayer player"));
        assertTrue(page.contains(".textSupplier(remote ? null : this::details).setMaxWidthLimit(216)"));
        assertFalse(page.contains(".getChunk("));
    }

    @Test
    void generatorWaitingCannotReplayPreviouslyGeneratedTicks() throws Exception {
        var logic = Class.forName(PACKAGE + "GuixuVisionCoreRecipeLogic");
        assertNotNull(logic.getDeclaredMethod("doDamping"));
        var direction = Class.forName(PACKAGE + "GuixuEnergyTransfer")
                .getMethod("generatesEnergy", BigInteger.class);
        assertEquals(true, direction.invoke(null, BigInteger.ONE));
        assertEquals(false, direction.invoke(null, BigInteger.ONE.negate()));
        assertEquals(false, direction.invoke(null, BigInteger.ZERO));
        String source = Files.readString(ROOT.resolve("common/machine/primordial/guixu/GuixuVisionCoreRecipeLogic.java"));
        int damping = source.indexOf("protected void doDamping()");
        String body = source.substring(damping, source.indexOf("super.doDamping();", damping));
        assertTrue(body.contains("getLastRecipe() instanceof IWirelessGTRecipe"));
        assertTrue(body.contains("generatesEnergy(wireless.getWirelessEuTickInputs())"));
    }
}
