package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.gtladd.gtladditions.common.data.ParallelData;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GuixuPowerIntegrationTest {
    private List<String> calls(String resource, String method) throws Exception {
        List<String> calls = new ArrayList<>();
        try (var stream = getClass().getClassLoader().getResourceAsStream(resource + ".class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    if (!name.equals(method)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                              String descriptor, boolean isInterface) {
                            calls.add(owner.substring(owner.lastIndexOf('/') + 1) + "." + name);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return calls;
    }

    @Test void nativeBatchDispatchesToTheGuixuPlannerBeforeConsumingInputs() throws Exception {
        assertNotNull(GuixuVisionCoreRecipeLogic.class.getDeclaredMethod("calculateParallels"));
        var calls = calls(GuixuVisionCoreRecipeLogic.class.getName().replace('.', '/'), "calculateParallels");
        assertTrue(calls.contains("GuixuPowerBudget.allocate"), calls.toString());
        assertTrue(calls.contains("GuixuPowerBudget.duration"), calls.toString());
        assertTrue(calls.contains("GuixuPowerBudget.parallelLimit"), calls.toString());
        assertFalse(calls.stream().anyMatch(s -> s.endsWith(".handleRecipeInput")));
        // 真實依賴資料型別：交回原生建構階段扣料，這裡不預先扣料。
        assertTrue(new ParallelData(List.of(), new long[0], true, null).getShouldProcess());
        var nativeCalls = calls("com/gtladd/gtladditions/api/machine/logic/GTLAddMultipleRecipesLogic",
                "buildFinalWirelessRecipe");
        assertTrue(nativeCalls.stream().anyMatch(s -> s.endsWith(".buildWirelessRecipe")));
    }

    @Test void packagedEnergyUsesThePlannedDurationAndConservativeRounding() throws Exception {
        var calls = calls(GuixuVisionCoreRecipeLogic.class.getName().replace('.', '/'), "buildWirelessRecipe");
        assertTrue(calls.contains("GuixuPowerBudget.roundForTicks"), calls.toString());
        assertTrue(calls.contains("RecipeCalculationHelper.buildWirelessRecipe"), calls.toString());
        assertFalse(calls.stream().anyMatch(s -> s.endsWith(".getLimitedDuration")));
    }

    @Test void sharedInputKeysUseTheNativeTerminalPoolUuid() throws Exception {
        var calls = calls(GuixuEnergyHandler.class.getName().replace('.', '/'), "sharedInputSource");
        assertTrue(calls.contains("NetworkEnergyContainer.getMachine"), calls.toString());
        assertTrue(calls.contains("WirelessEnergyNetworkTerminalPartMachineBase.getUUID"), calls.toString());
    }
}
