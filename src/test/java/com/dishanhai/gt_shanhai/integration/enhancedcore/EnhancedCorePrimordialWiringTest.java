package com.dishanhai.gt_shanhai.integration.enhancedcore;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class EnhancedCorePrimordialWiringTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    @Test
    void cloudHasOneExecutionOwnerAndUsesOriginalPrimordialLifecycle() throws Exception {
        String logic = Files.readString(ROOT.resolve("common/machine/primordial/PrimordialThreadedRecipeLogic.java"));
        String bridge = Files.readString(ROOT.resolve("integration/enhancedcore/EnhancedCorePrimordialExecution.java"));
        assertTrue(logic.contains("cloudAdmissionBlocked"));
        assertTrue(logic.contains("prepareCloudBatch"));
        assertTrue(logic.contains("advanceCloudBatch"));
        assertTrue(bridge.contains("prepareForCloudExecution"));
        assertTrue(bridge.contains("IvOutputTransfer.transfer"));
        assertTrue(bridge.contains("!logic.isCloudRecipeSelected(job.recipe)"));
        assertFalse(bridge.contains("iv$engine().tick"));
        assertFalse(bridge.contains("super.serverTick"));
        String context = Files.readString(ROOT.resolve("integration/enhancedcore/EnhancedCorePrimordialContext.java"));
        assertTrue(context.contains("IParallelLogic.getRecipeOutputChance(logic.getMachine(), inputRoll).outputs"));
        assertTrue(context.contains("IvRecipeInputs.plan(rolled, inventory, Map.of(), 1)"));
        assertTrue(context.contains("if (!simulate) replaceCompletion(job.completion, IvRecipeInputs.outputs(contents))"));
    }

    @Test
    void cloudTypesUseRuntimeSelectionAndPatchEveryDefinitionBasedReader() throws Exception {
        String types = Files.readString(ROOT.resolve("integration/enhancedcore/EnhancedCorePrimordialRecipeTypes.java"));
        assertTrue(types.contains("getSelectedRecipeTypes()"));
        assertFalse(types.contains("getAllSelectableRecipeTypes()"));
        for (String mixin : new String[]{"EnhancedCorePrimordialCloudBufferTypesMixin",
                "EnhancedCorePrimordialCloudScanTypesMixin", "EnhancedCorePrimordialIvBufferTypesMixin"}) {
            assertTrue(Files.readString(ROOT.resolve("mixin/" + mixin + ".java")).contains("@Pseudo"));
        }
    }

    @Test
    void everyDefinitionTypeReaderInThe417JarHasADeclaredCloudHook() throws Exception {
        Map<String, Set<String>> readers = Map.of(
                "com/gtl/enhancedcore/common/cloud/CloudPatternBufferMachine", Set.of(
                        "graphOperationsPerPattern", "claim", "snapshot", "switchMode"),
                "com/gtl/enhancedcore/common/cloud/CloudPatternAllocator$Scan", Set.of("<init>"),
                "com/gtl/enhancedcore/common/recipe/iv/IvBuffers", Set.of(
                        "push", "lambda$push$21", "lambda$cloudControllers$6"));
        try (var jar = new JarFile("libs/GTL-Enhancedcore-4.1.7.jar")) {
            for (var expected : readers.entrySet()) {
                ClassNode node = new ClassNode();
                try (var input = jar.getInputStream(jar.getJarEntry(expected.getKey() + ".class"))) {
                    new ClassReader(input).accept(node, 0);
                }
                Set<String> actual = new HashSet<>();
                for (var method : node.methods) for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode invoke
                            && invoke.owner.equals("com/gregtechceu/gtceu/api/machine/MultiblockMachineDefinition")
                            && invoke.name.equals("getRecipeTypes")) actual.add(method.name);
                }
                assertEquals(expected.getValue(), actual, expected.getKey());
            }
        }
    }
}
