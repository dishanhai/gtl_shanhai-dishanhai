package com.dishanhai.gt_shanhai.common.util;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HubChanceInputPreservationTest {
    @Test
    void beaconUpgradesOutputsWithoutChangingConsumablesOrCatalysts() {
        GTRecipe recipe = recipe();
        var inputs = recipe.inputs;
        var tickInputs = recipe.tickInputs;
        HubMachineHelper.applyFullOutputChance(recipe);
        assertSame(inputs, recipe.inputs);
        assertSame(tickInputs, recipe.tickInputs);
        assertEquals(List.of(10000, 2500, 0), chances(recipe.inputs.get(null)));
        assertEquals(List.of(10000, 2500, 0), chances(recipe.tickInputs.get(null)));
        assertEquals(List.of(10000, 10000, 10000), chances(recipe.outputs.get(null)));
        assertEquals(List.of(10000, 10000, 10000), chances(recipe.tickOutputs.get(null)));
        HubMachineHelper.applyFullOutputChance(recipe);
        assertEquals(List.of(10000, 2500, 0), chances(recipe.inputs.get(null)));
    }

    @Test
    void upstreamRunnerTreatsZeroChanceAsMatchOnlyNotConsumption() throws Exception {
        // Execute the upstream filtering bytecode without bootstrapping Minecraft registries.
        // Only the capability's doMatchInRecipe precondition is stubbed as true; filtering is unchanged.
        Method fill = isolatedRunnerFilter();
        fill.setAccessible(true);
        GTRecipe recipe = recipe();
        var catalyst = new HashMap<>();
        catalyst.put(null, List.of(content(0)));
        Map<?, ?> match = (Map<?, ?>) fill.invoke(null, catalyst, recipe, null, Map.of(), true);
        Map<?, ?> consume = (Map<?, ?>) fill.invoke(null, catalyst, recipe, null, Map.of(), false);
        assertFalse(match.isEmpty(), "zero-chance inputs still participate in matching");
        assertTrue(consume.isEmpty(), "zero-chance inputs must not be consumed");
        HubMachineHelper.applyFullOutputChance(recipe);
        var required = new HashMap<>();
        required.put(null, List.of(recipe.inputs.get(null).getFirst()));
        Map<?, ?> paid = (Map<?, ?>) fill.invoke(null, required, recipe, null, Map.of(), false);
        assertFalse(paid.isEmpty(), "the beacon must not erase required input consumption");
    }

    @Test
    void maintenanceEntryPointDelegatesToOutputOnlyHelper() throws Exception {
        boolean[] callsHelper = {false};
        boolean[] writesChance = {false};
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "com/dishanhai/gt_shanhai/common/machine/part/DShanhaiMaintenanceHatchMachine.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    if (!name.equals("modifyRecipe") && !name.startsWith("lambda$modifyRecipe$")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                            if (owner.equals("com/dishanhai/gt_shanhai/common/util/HubMachineHelper")
                                    && name.equals("applyFullOutputChance")) callsHelper[0] = true;
                        }
                        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            if (opcode == Opcodes.PUTFIELD && name.equals("chance")) writesChance[0] = true;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG);
        }
        assertTrue(callsHelper[0]);
        assertFalse(writesChance[0], "maintenance must not rewrite input probabilities in an inline loop");
    }

    private static Method isolatedRunnerFilter() throws Exception {
        String generated = "com/dishanhai/gt_shanhai/testutil/Issue50RunnerFilter";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, generated, null, "java/lang/Object", null);
        String runnerClass = "org/gtlcore/gtlcore/api/recipe/RecipeRunner.class";
        byte[] runnerBytes;
        String exactJar = System.getProperty("gtShanhai.test.runnerJar");
        if (exactJar != null) {
            try (var jar = new java.util.jar.JarFile(exactJar);
                 InputStream in = jar.getInputStream(jar.getJarEntry(runnerClass))) {
                runnerBytes = in.readAllBytes();
            }
        } else {
            try (InputStream in = HubChanceInputPreservationTest.class.getClassLoader().getResourceAsStream(runnerClass)) {
                assertNotNull(in);
                runnerBytes = in.readAllBytes();
            }
        }
        {
            new ClassReader(runnerBytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc,
                                                           String signature, String[] exceptions) {
                    if (!name.equals("fillContent")) return null;
                    return new MethodVisitor(Opcodes.ASM9,
                            writer.visitMethod(access, name, desc, signature, exceptions)) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            if (owner.equals("com/gregtechceu/gtceu/api/capability/recipe/RecipeCapability")
                                    && name.equals("doMatchInRecipe") && desc.equals("()Z")) {
                                super.visitInsn(Opcodes.POP);
                                super.visitInsn(Opcodes.ICONST_1);
                            } else super.visitMethodInsn(opcode, owner, name, desc, itf);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG);
        }
        writer.visitEnd();
        class FilterLoader extends ClassLoader {
            FilterLoader() { super(HubChanceInputPreservationTest.class.getClassLoader()); }
            Class<?> define(byte[] bytes) { return defineClass(generated.replace('/', '.'), bytes, 0, bytes.length); }
        }
        return Arrays.stream(new FilterLoader().define(writer.toByteArray()).getDeclaredMethods())
                .filter(m -> m.getName().equals("fillContent")).findFirst().orElseThrow();
    }

    private static GTRecipe recipe() {
        GTRecipe result = new GTRecipe(null, new ResourceLocation("gt_shanhai", "issue_50"),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new ArrayList<>(), new ArrayList<>(), new CompoundTag(), 20, false);
        for (var map : List.of(result.inputs, result.tickInputs, result.outputs, result.tickOutputs)) {
            map.put(null, List.of(content(10000), content(2500), content(0)));
        }
        return result;
    }
    private static Content content(int chance) {
        // Content's normal constructor initializes GT registries, unavailable in the plain JVM test harness.
        try {
            var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Content result = (Content) ((sun.misc.Unsafe) field.get(null)).allocateInstance(Content.class);
            result.content = 32L;
            result.chance = chance;
            result.maxChance = 10000;
            return result;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static List<Integer> chances(List<Content> contents) { return contents.stream().map(c -> c.chance).toList(); }
}
