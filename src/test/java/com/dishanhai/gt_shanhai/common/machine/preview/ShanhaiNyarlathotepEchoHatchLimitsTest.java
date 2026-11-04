package com.dishanhai.gt_shanhai.common.machine.preview;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：帶全域下限的倉室謂詞必須共用，不能按結構符號重建計數。 */
class ShanhaiNyarlathotepEchoHatchLimitsTest {
    @Test
    void energyMinimumIsCreatedOnceForTheWholeStructureRatherThanOncePerSymbol() throws Exception {
        // 讀取真正編譯的結構工廠，避免初始化普通單元測試無法啟動的 Forge/LDLib 註冊鏈。
        String resource = "com/dishanhai/gt_shanhai/common/machine/preview/ShanhaiNyarlathotepEchoStructure.class";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            var factory = node.methods.stream().filter(method -> method.name.equals("createPattern"))
                    .findFirst().orElseThrow();
            int abilityFactories = 0;
            for (var instruction : factory.instructions) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("com/gregtechceu/gtceu/api/pattern/Predicates")
                        || !call.name.equals("autoAbilities")) continue;
                abilityFactories++;
                int callIndex = factory.instructions.indexOf(call);
                for (var edge : factory.instructions) {
                    if (!(edge instanceof JumpInsnNode jump)) continue;
                    int targetIndex = factory.instructions.indexOf(jump.label);
                    int jumpIndex = factory.instructions.indexOf(jump);
                    assertFalse(targetIndex <= callIndex && callIndex <= jumpIndex,
                            "autoAbilities inside a loop gives C and M separate minimum-energy counts");
                }
            }
            assertEquals(1, abilityFactories, "All 34 casing positions share one local hatch policy");
        }
    }
}
