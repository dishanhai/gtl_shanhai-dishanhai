package com.dishanhai.gt_shanhai.mixin;

// Author: dishanhai
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

/** Checks dependency bytes without initializing Minecraft or machine registries. */
class RecipeThreadTimingMixinCompatibilityTest {
    @Test void timingShadowsExistInTheirActualTargetClasses() throws Exception {
        var missing = new ArrayList<String>();
        for (String name : List.of("RecipeThreadGTLAddLogicMixin", "RecipeThreadMultipleLogicMixin",
                "RecipeThreadMutableLogicMixin")) {
            ClassNode mixin = read("com/dishanhai/gt_shanhai/mixin/" + name);
            AnnotationNode annotation = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
                    .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
            @SuppressWarnings("unchecked") var targets = (List<Type>) value(annotation, "value");
            ClassNode target = read(targets.getFirst().getInternalName());
            for (MethodNode method : mixin.methods) {
                boolean shadow = annotations(method.visibleAnnotations, method.invisibleAnnotations).stream()
                        .anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"));
                if (shadow && target.methods.stream().noneMatch(m -> m.name.equals(method.name) && m.desc.equals(method.desc))) {
                    missing.add(name + ": " + target.name + "." + method.name + method.desc);
                }
            }
        }
        assertTrue(missing.isEmpty(), "Unresolved timing shadows: " + missing);
    }

    @Test void gtlAddEstimateUsesVirtualCallsToTheActualSuperclassMethods() throws Exception {
        var mixin = read("com/dishanhai/gt_shanhai/mixin/RecipeThreadGTLAddLogicMixin");
        var target = read("com/gtladd/gtladditions/api/machine/logic/GTLAddMultipleRecipesLogic");
        assertEquals(target.superName, mixin.superName);
        ClassNode parent = read(target.superName);
        var estimate = mixin.methods.stream().filter(m -> m.name.equals("shanhai$estimateRecipeTicks"))
                .findFirst().orElseThrow();
        for (String name : List.of("getEuMultiplier", "getTotalEuOfRecipe")) {
            var inherited = parent.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
            var calls = new ArrayList<MethodInsnNode>();
            for (var instruction : estimate.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals(name)) calls.add(call);
            }
            assertEquals(1, calls.size(), name);
            assertEquals(inherited.desc, calls.getFirst().desc, name);
            assertEquals(Opcodes.INVOKEVIRTUAL, calls.getFirst().getOpcode(), name);
            assertTrue((inherited.access & Opcodes.ACC_PROTECTED) != 0, name);
        }
    }

    private static ClassNode read(String name) throws Exception {
        try (var input = RecipeThreadTimingMixinCompatibilityTest.class.getClassLoader()
                .getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        var result = new ArrayList<AnnotationNode>();
        if (visible != null) result.addAll(visible);
        if (invisible != null) result.addAll(invisible);
        return result;
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }
}
