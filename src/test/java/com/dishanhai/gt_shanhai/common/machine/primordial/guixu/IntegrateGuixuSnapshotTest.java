package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigInteger;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.matter.PrimordialSupercriticalMatterGenerationCore;
import sun.misc.Unsafe;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：AR 快照接線契約，避免把耗電真值換成受限顯示數字。 */
class IntegrateGuixuSnapshotTest {
    private String source() throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/guixu/GuixuVisionCoreMachine.java"));
    }

    @Test void persistedFxAndServerSnapshotsAreWiredWithoutRemovingExistingControls() throws Exception {
        String source = source();
        for (String field : new String[]{"hostFxEnabled", "hostRingDegPerSec", "hostShipUpDegPerSec",
                "hostShipDownDegPerSec", "hostBlackHoleRadiusTenths", "hostLensEnabled", "hostArMaskEnabled"}) {
            assertTrue(source.matches("(?s).*@Persisted\\s+@DescSynced\\s+private \\w+ " + field + "\\s*=.*"), field);
        }
        assertTrue(source.contains("refreshHostRuntimeSnapshot()"));
        assertTrue(source.contains("refreshProgressTick()"));
        assertTrue(source.contains("return getAllSelectableRecipeTypes();"));
        assertTrue(source.matches("(?s).*public boolean hasSelectedRecipeTypes\\(\\)\\s*\\{\\s*return getAllSelectableRecipeTypes\\(\\).length > 0;\\s*}.*"));
        assertFalse(source.contains("GuixuControlPanels.modes(this)"));
        assertTrue(source.contains("GuixuControlPanels.modules(this)"));
        assertTrue(source.contains("cancelContainment()"));
        assertTrue(source.contains("releaseRemoteModules()"));
    }

    @Test void moduleOutputUsesTheSameInherentGuixuBonus() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialOmegaEngineModuleBase.java"));
        int start = source.indexOf("public int getHostOutputMultiplier()");
        int end = source.indexOf("\n    @Override", start);
        String method = source.substring(start, end);
        assertTrue(method.contains("return currentHost.getRecipeOutputMultiplier();"),
                "主機統一彙總常駐、萬象與遏制場倍率，所有子模組共用");
    }

    @Test void snapshotReadsOnlyLoadedSlotsAndKeepsSignedBigIntegerEnergy() throws Exception {
        String source = source();
        assertTrue(source.contains("snapshotModuleAt(Level level, BlockPos pos)"));
        int start = source.indexOf("snapshotModuleAt(Level level, BlockPos pos)");
        int end = source.indexOf("\n    }", start);
        String lookup = source.substring(start, end);
        assertTrue(lookup.indexOf("hasChunkAt(pos)") < lookup.indexOf("MetaMachine.getMachine(level, pos)"));
        assertTrue(source.contains("snapshotSignedEuPerTick"));
        assertTrue(source.contains("wireless.getWirelessEuTickInputs()"));
        assertTrue(source.contains("private BigInteger[] energyTotals(IO io)"));
        assertFalse(source.contains("Math.max(0L, RecipeHelper.getInputEUt(last))"));
    }

    @Test void displaySaturationAndWindowAveragesDoNotOverflow() {
        assertEquals(Long.MAX_VALUE, GuixuVisionCoreMachine.snapshotLong(BigInteger.ONE.shiftLeft(100)));
        assertEquals(0L, GuixuVisionCoreMachine.snapshotLong(BigInteger.valueOf(-3)));
        assertEquals(Long.MAX_VALUE / 20,
                GuixuVisionCoreMachine.averagePerTick(new long[]{Long.MAX_VALUE, Long.MAX_VALUE}, 2));
        assertEquals(-1L, GuixuVisionCoreMachine.averagePerTick(new long[]{40, -1}, 2));
        assertEquals(2L, GuixuVisionCoreMachine.averagePerTick(new long[]{40, 40}, 2));
        assertEquals(Long.MAX_VALUE / 10, GuixuVisionCoreMachine.averagePerTick(
                new BigInteger[]{BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO)}, 1));
    }

    @Test void progressUsesCeilingSecondsAndAvoidsIntOverflow() {
        assertArrayEquals(new int[]{50, 1}, GuixuVisionCoreMachine.progressOf(10, 20, true));
        assertArrayEquals(new int[]{0, 1}, GuixuVisionCoreMachine.progressOf(0, 1, true));
        assertArrayEquals(new int[]{100, 0}, GuixuVisionCoreMachine.progressOf(Integer.MAX_VALUE, 100, true));
        assertArrayEquals(new int[]{-1, -1}, GuixuVisionCoreMachine.progressOf(0, 0, true));
        assertArrayEquals(new int[]{0, 0}, GuixuVisionCoreMachine.progressOf(0, 0, false));
    }

    @Test void inherentBonusClampsNegativeExtrasAndSaturatesOverflow() {
        assertEquals(6, GuixuVisionCoreMachine.productionMultiplierOf(0, 5));
        assertEquals(7, GuixuVisionCoreMachine.productionMultiplierOf(1, 5));
        assertEquals(23, GuixuVisionCoreMachine.productionMultiplierOf(17, 5));
        assertEquals(18, GuixuVisionCoreMachine.productionMultiplierOf(17, -5));
        assertEquals(6, GuixuVisionCoreMachine.productionMultiplierOf(-1, 5));
        assertEquals(Integer.MAX_VALUE, GuixuVisionCoreMachine.productionMultiplierOf(Integer.MAX_VALUE, 5));
        assertEquals(Integer.MAX_VALUE, GuixuVisionCoreMachine.productionMultiplierOf(17, Integer.MAX_VALUE));
    }

    @Test void consumedPowerReadsWithdrawalAndGeneratedPowerReadsDeposit() throws Exception {
        var method = java.util.Arrays.stream(GuixuVisionCoreMachine.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("snapshotEnergyRatePer20"))
                .findFirst().orElse(null);
        assertNotNull(method, "功率讀取需明確區分倉室充電與主機扣電");
        method.setAccessible(true);
        IEnergyContainer container = (IEnergyContainer) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IEnergyContainer.class},
                (proxy, invoked, args) -> switch (invoked.getName()) {
                    case "getInputPerSec" -> 140L;
                    case "getOutputPerSec" -> 220L;
                    default -> throw new UnsupportedOperationException(invoked.getName());
                });
        assertEquals(220L, method.invoke(null, container, true), "耗能是從輸入倉取走的 EU");
        assertEquals(140L, method.invoke(null, container, false), "發電是存入輸出倉的 EU");
    }

    @Test void moduleSummaryMergesAllKnownRatesAndPreservesUnknown() throws Exception {
        assertTrue(source().contains("public long hostModuleEuPerTick()"));
        var method = java.util.Arrays.stream(GuixuVisionCoreMachine.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("snapshotModuleEuTotal"))
                .findFirst().orElse(null);
        assertNotNull(method);
        method.setAccessible(true);
        assertEquals(0L, method.invoke(null, (Object) new BigInteger[0]));
        assertEquals(170L, method.invoke(null, (Object) new BigInteger[]{BigInteger.valueOf(100), BigInteger.valueOf(70)}));
        assertEquals(100L, method.invoke(null, (Object) new BigInteger[]{BigInteger.valueOf(100), BigInteger.valueOf(-70)}));
        assertEquals(-1L, method.invoke(null, (Object) new BigInteger[]{BigInteger.valueOf(100), null}));
        assertEquals(Long.MAX_VALUE, method.invoke(null, (Object) new BigInteger[]{BigInteger.ONE.shiftLeft(100)}));
    }

    @Test void remoteModuleUsesItsOwnDimensionForLoadedChunkChecks() throws Exception {
        Object hostWorld = new Object();
        Object remoteWorld = new Object();
        java.util.function.BiPredicate<Object, net.minecraft.core.BlockPos> loaded =
                (world, pos) -> world == remoteWorld;
        assertTrue(GuixuVisionCoreMachine.snapshotChunkLoaded(remoteWorld, net.minecraft.core.BlockPos.ZERO, loaded));
        assertFalse(GuixuVisionCoreMachine.snapshotChunkLoaded(hostWorld, net.minecraft.core.BlockPos.ZERO, loaded));
        assertFalse(GuixuVisionCoreMachine.snapshotChunkLoaded(null, net.minecraft.core.BlockPos.ZERO,
                (world, pos) -> { fail("空世界不可查區塊"); return true; }));
        assertTrue(source().contains("snapshotChunkLoaded(module.getLevel(), module.getPos(), Level::hasChunkAt)"));
    }

    @Test void realModuleGetterIncludesGuixuInherentBonusAndMountedMultiplier() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        var host = (BonusHost) unsafe.allocateInstance(BonusHost.class);
        var module = (PrimordialSupercriticalMatterGenerationCore)
                unsafe.allocateInstance(PrimordialSupercriticalMatterGenerationCore.class);
        module.setHost(host);
        host.formed = true;
        host.mounted = 3;
        for (int level : new int[]{0, 1, 17}) {
            host.level = level;
            assertEquals(3 * (6 + level), module.getHostOutputMultiplier());
        }
        host.mounted = Integer.MAX_VALUE;
        assertEquals(Integer.MAX_VALUE, module.getHostOutputMultiplier());
        host.formed = false;
        assertEquals(1, module.getHostOutputMultiplier());
    }

    private static final class BonusHost extends GuixuVisionCoreMachine {
        private int level;
        private int mounted;
        private boolean formed = true;
        private BonusHost() { super(null); }
        @Override public boolean isFormed() { return formed; }
        @Override public int moduleSlotBonus() { return level; }
        @Override public int getMountedOutputMultiplier() { return mounted; }
        @Override public GuixuContainmentBonus getContainmentBonus() { return GuixuContainmentBonus.NONE; }
    }
}
