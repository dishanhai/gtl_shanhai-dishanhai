package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：维护能力补齐后会真实执行 modifyRecipe，温度绕过不能让电弧炉丢失必需键。 */
class ShanhaiHubTemperatureContractTest {
    @Test
    void maintenanceModifierPreservesTemperatureKeysAndDoesNotMultiplyDurationAgain() throws Exception {
        boolean[] removesKey = {false};
        boolean[] copiesTag = {false};
        int[] writesTemperature = {0};
        boolean[] multipliesDuration = {false};
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "com/dishanhai/gt_shanhai/common/machine/part/DShanhaiMaintenanceHatchMachine.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    if (!name.equals("modifyRecipe")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                            if (owner.equals("net/minecraft/nbt/CompoundTag")) {
                                if (method.equals("remove")) removesKey[0] = true;
                                if (method.equals("copy")) copiesTag[0] = true;
                                if (method.equals("putInt")) writesTemperature[0]++;
                            }
                            if (method.equals("getDurationMultiplier")) multipliesDuration[0] = true;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG);
        }
        assertFalse(removesKey[0], "ebfOverclock 会拒绝没有 ebf_temp 的配方");
        assertTrue(copiesTag[0], "GTRecipe.copy 共享 data，必须隔离原始注册配方");
        assertEquals(2, writesTemperature[0]);
        assertFalse(multipliesDuration[0], "耗时倍率已有 setupRecipe 入口，不能再次应用");
    }
}
