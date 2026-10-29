package com.dishanhai.gt_shanhai.common.cover;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：AE 直接耗汽的精確需求與模擬／實扣契約。 */
class ShanhaiAeSteamDemandTest {
    @Test
    void coverUsesOnlyExactGtceuSteamAndNeverFillsTheLocalTank() throws Exception {
        Path path = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/cover/ShanhaiAeSteamCover.java");
        assertTrue(Files.exists(path), "缺少蒸汽覆蓋板");
        String source = Files.readString(path);
        assertTrue(source.contains("new ResourceLocation(\"gtceu\", \"steam\")"));
        assertFalse(source.contains("getFluidTag()"));
        assertFalse(source.contains(".fill("));
        assertTrue(source.contains("IActionSource.ofMachine(this)"));
        assertTrue(source.contains("target.hasChunkAt(pos)"));
        assertFalse(source.contains(".getChunk("));
        String binder = Files.readString(path.resolveSibling("ShanhaiAeSteamBinderBehavior.java"));
        assertTrue(binder.contains("instanceof ShanhaiAeSteamBinderMachine"));
        assertTrue(binder.contains("target.putString(\"dimension\""));
        assertTrue(binder.contains("target.putLong(\"pos\""));
        assertTrue(binder.contains("target.putByte(\"side\""));
        assertFalse(source.contains("createManagedNode"), "綁定器方案不額外創建覆蓋板網路節點");
    }

    @Test
    void demandMergesEnergyBeforeRoundingAndKeepsNativeRate() {
        assertEquals(1, ShanhaiAeSteamDemand.requiredSteam(List.of(1L, 1L), 0.5));
        assertEquals(2, ShanhaiAeSteamDemand.requiredSteam(List.of(3L), 0.5));
        assertEquals(81, ShanhaiAeSteamDemand.requiredSteam(List.of(27L), 3.0));
        assertEquals(0, ShanhaiAeSteamDemand.requiredSteam(List.of(), 0.5));
    }

    @Test
    void demandDoesNotOverflowLongOrLoseLargeIntegerPrecision() {
        assertEquals(4611686018427387904L, ShanhaiAeSteamDemand.requiredSteam(List.of(Long.MAX_VALUE), 0.5));
        assertEquals(Long.MAX_VALUE, ShanhaiAeSteamDemand.requiredSteam(List.of(Long.MAX_VALUE, Long.MAX_VALUE), 0.5));
        assertEquals(-1, ShanhaiAeSteamDemand.requiredSteam(List.of(Long.MAX_VALUE), 2));
        assertEquals(-1, ShanhaiAeSteamDemand.requiredSteam(List.of(-1L), 0.5));
        assertEquals(-1, ShanhaiAeSteamDemand.requiredSteam(List.of(1L), Double.NaN));
    }

    @Test
    void availableEnergyMatchesConsumptionRateAndSaturates() {
        assertEquals(2000, ShanhaiAeSteamDemand.availableEnergy(1000, 0.5));
        assertEquals(333, ShanhaiAeSteamDemand.availableEnergy(1000, 3));
        assertEquals(Long.MAX_VALUE, ShanhaiAeSteamDemand.availableEnergy(Long.MAX_VALUE, 0.5));
        assertEquals(0, ShanhaiAeSteamDemand.availableEnergy(1000, 0));
    }

    @Test
    void simulationChecksStockWithoutExtracting() {
        Storage storage = new Storage(1000);
        assertTrue(ShanhaiAeSteamDemand.consume(storage, 16, true));
        assertEquals(1000, storage.amount);
        assertEquals(0, storage.actualCalls);
    }

    @Test
    void shortageNeverStartsExtractionAndSufficientStockPaysExactDemand() {
        Storage storage = new Storage(15);
        assertFalse(ShanhaiAeSteamDemand.consume(storage, 16, false));
        assertEquals(15, storage.amount);
        assertEquals(0, storage.actualCalls);
        storage.amount = 17;
        assertTrue(ShanhaiAeSteamDemand.consume(storage, 16, false));
        assertEquals(1, storage.amount);
        assertEquals(1, storage.actualCalls);
    }

    @Test
    void invalidAndZeroDemandDoNotTouchStorage() {
        Storage storage = new Storage(20);
        assertFalse(ShanhaiAeSteamDemand.consume(storage, -1, false));
        assertTrue(ShanhaiAeSteamDemand.consume(storage, 0, false));
        assertEquals(0, storage.calls);
    }

    @Test
    void partialActualExtractionKeepsPaidSteamAcrossRetryWithoutPayingTwice() {
        var account = new ShanhaiAeSteamDemand.Account(0);
        int[] actualCalls = {0};
        long[] paid = {0};
        ShanhaiAeSteamDemand.SteamStorage unstable = (requested, simulate) -> {
            if (simulate) return requested;
            actualCalls[0]++;
            long amount = actualCalls[0] == 1 ? requested / 2 : requested;
            paid[0] += amount;
            return amount;
        };
        assertFalse(ShanhaiAeSteamDemand.consume((n, s) -> account.extract(unstable, n, s), 16, false));
        assertEquals(8, account.credit());
        assertEquals(8, paid[0]);
        // 重載存檔後仍沿用已付的 8 mB，不再次要求完整 16 mB。
        var restored = new ShanhaiAeSteamDemand.Account(account.credit());
        assertTrue(ShanhaiAeSteamDemand.consume((n, s) -> restored.extract(unstable, n, s), 16, false));
        assertEquals(16, paid[0]);
        assertEquals(0, restored.credit());
    }

    @Test
    void prepaidSteamSimulationNeverSpendsCredit() {
        var account = new ShanhaiAeSteamDemand.Account(16);
        Storage storage = new Storage(0);
        assertTrue(ShanhaiAeSteamDemand.consume((n, s) -> account.extract(storage, n, s), 16, true));
        assertEquals(16, account.credit());
        assertEquals(0, storage.calls);
        assertTrue(ShanhaiAeSteamDemand.consume((n, s) -> account.extract(storage, n, s), 16, false));
        assertEquals(0, account.credit());
        assertEquals(0, storage.calls);
    }

    @Test
    void nativeJarContainsEveryMixinTargetWithExpectedDescriptors() throws Exception {
        Map<String, String> methods = new HashMap<>();
        try (var bytes = getClass().getClassLoader().getResourceAsStream(
                "com/gregtechceu/gtceu/api/machine/steam/SteamEnergyRecipeHandler.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    methods.put(name, descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        }
        assertEquals("(Lcom/gregtechceu/gtceu/api/capability/recipe/IO;Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;Ljava/util/List;Ljava/lang/String;Z)Ljava/util/List;",
                methods.get("handleRecipeInner"));
        assertEquals("()Ljava/util/List;", methods.get("getContents"));
        assertEquals("()D", methods.get("getTotalContentAmount"));
        assertEquals("()J", methods.get("getStored"));
        assertEquals("()J", methods.get("getCapacity"));
        assertTrue(Files.readString(Path.of("src/main/resources/gt_shanhai.gtceu.mixin.json"))
                .contains("ShanhaiAeSteamEnergyHandlerMixin"));
    }

    private static final class Storage implements ShanhaiAeSteamDemand.SteamStorage {
        long amount;
        int calls;
        int actualCalls;
        Storage(long amount) { this.amount = amount; }
        @Override
        public long extract(long requested, boolean simulate) {
            calls++;
            long extracted = Math.min(amount, requested);
            if (!simulate) { actualCalls++; amount -= extracted; }
            return extracted;
        }
    }
}
