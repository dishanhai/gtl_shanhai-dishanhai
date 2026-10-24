package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GuixuNativeEnergyContractTest {
    @Test
    void realDependencyFactoryNegatesThePerTickCostBeforeStoringIt() throws Exception {
        List<String> instructions = new ArrayList<>();
        try (var bytes = getClass().getClassLoader().getResourceAsStream(
                "com/gtladd/gtladditions/utils/RecipeCalculationHelper.class")) {
            assertNotNull(bytes);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    if (!name.equals("buildWirelessRecipe") || !descriptor.equals(
                            "(Ljava/util/List;Ljava/util/List;ILjava/math/BigInteger;Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;)Lcom/gtladd/gtladditions/api/recipe/WirelessGTRecipe;")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitVarInsn(int opcode, int variable) {
                            instructions.add(opcode + ":" + variable);
                        }
                        @Override public void visitInsn(int opcode) { instructions.add(Integer.toString(opcode)); }
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                              String descriptor, boolean isInterface) {
                            instructions.add(owner + "." + name);
                        }
                    };
                }
            }, 0);
        }
        String body = String.join(" ", instructions);
        assertTrue(body.contains("25:4 21:3 133 java/math/BigInteger.valueOf java/math/BigInteger.divide java/math/BigInteger.negate 58:6"), body);
        int negate = body.indexOf("java/math/BigInteger.negate");
        assertTrue(negate >= 0 && body.indexOf(".setWirelessEut", negate) > negate);
    }
}
