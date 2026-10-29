package com.dishanhai.gt_shanhai.common.machine.steam;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：檢查依賴真正覆寫的入口；一般 JUnit 不會套用 GTLCore 的 Mixin。 */
class ShanhaiSteamGeneratorOutputDispatchTest {
    private static final String HELPER = "org/gtlcore/gtlcore/api/recipe/RecipeRunnerHelper.";

    @Test
    void completionUsesOutputDispatchDespiteGtlMixinRoutingGenericIoToInput() throws Exception {
        List<String> inheritedCalls = calls(
                "org/gtlcore/gtlcore/mixin/gtm/api/machine/RecipeLogicMixin", "handleRecipeIO");
        assertTrue(inheritedCalls.contains(HELPER + "handleRecipeInput"),
                "核對實際 GTLCore jar：一般 IO 入口在 OUT 時也會走輸入");
        assertFalse(inheritedCalls.contains(HELPER + "handleRecipeOutput"));

        List<String> outputCalls = calls(
                "com/dishanhai/gt_shanhai/common/machine/steam/ShanhaiSteamGeneratorMachine$SteamGenerationLogic",
                "tryOutput");
        int preflight = outputCalls.indexOf(HELPER + "matchRecipeOutput");
        int emit = outputCalls.indexOf(HELPER + "handleRecipeOutput");
        assertTrue(preflight >= 0 && emit > preflight,
                "完成批次必須先預檢輸出，再以原生輸出專用 API 寫入倉室／ME 暫存");
        assertEquals(1, outputCalls.stream().filter((HELPER + "handleRecipeOutput")::equals).count());
        assertFalse(outputCalls.stream().anyMatch(call -> call.endsWith(".handleRecipeIO")
                || call.endsWith(".handleRecipeInput")), "完成輸出不可再次扣水");
    }

    private List<String> calls(String type, String method) throws Exception {
        List<String> calls = new ArrayList<>();
        try (var bytes = getClass().getClassLoader().getResourceAsStream(type + ".class")) {
            assertNotNull(bytes, type);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!name.equals(method)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            calls.add(owner + "." + name);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertFalse(calls.isEmpty(), type + "#" + method);
        return calls;
    }
}
