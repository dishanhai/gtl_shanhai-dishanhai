package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.ShanhaiAbilityPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real EnhancedCore restrict/classifier bytecode and the real Shanhai HEAD hook.
 * Only Minecraft/registry dependencies are stand-ins; this is not a full Mixin/game launch. */
public class EnhancedCoreNativeHatchesCompatibilityTest {
    private static final String PREFIX = Type.getInternalName(EnhancedCoreNativeHatchesCompatibilityTest.class);
    private static final String GT = "com/gregtechceu/gtceu/api/";
    private static final String EC = "com/gtl/enhancedcore/common/";
    private static final String MIXIN = "com/dishanhai/gt_shanhai/mixin/EnhancedCoreNativeHatchesMixin";
    public record Block(boolean material, boolean connection) {}
    public record BlockState(Block block) { public Block m_60734_() { return block; } }
    public record BlockInfo(BlockState state) { public BlockState getBlockState() { return state; } }
    public static class Ability {
        public static final Ability IMPORT_ITEMS = new Ability(), EXPORT_ITEMS = new Ability(),
                IMPORT_FLUIDS = new Ability(), EXPORT_FLUIDS = new Ability();
        public boolean isApplicable(Block block) { return block.material(); }
    }
    public static class Simple implements ShanhaiAbilityPredicate {
        public Supplier<BlockInfo[]> candidates;
        public int minCount = -1, maxCount = -1, minLayerCount = -1, maxLayerCount = -1;
        private boolean nonMaterial;
        public Simple(boolean marked, Block... blocks) {
            nonMaterial = marked;
            candidates = () -> Arrays.stream(blocks).map(b -> new BlockInfo(new BlockState(b))).toArray(BlockInfo[]::new);
        }
        public boolean gtShanhai$hasNonMaterialAbility() { return nonMaterial; }
        public void gtShanhai$markNonMaterialAbility() { nonMaterial = true; }
    }
    public static class Trace {
        public List<Simple> common = new ArrayList<>(), limited = new ArrayList<>();
        public boolean isController;
        public Trace() {}
        public Trace(Trace source) { common.addAll(source.common); limited.addAll(source.limited); }
        public Trace or(Trace other) { common.addAll(other.common); limited.addAll(other.limited); return this; }
    }
    public static class Ports {
        public static final Simple PORT = new Simple(false, new Block(true, true));
        public static Trace materialPorts(int tier) { Trace p = new Trace(); p.common.add(PORT); return p; }
    }
    public static class Galaxy {
        public static boolean isConnectionPortBlock(Block block) { return block.connection(); }
    }
    static class Loader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
    }
    public static boolean classify(Simple predicate, Class<?> target) throws Exception {
        Class<?> hook = Class.forName(target.getName() + "Hook", true, target.getClassLoader());
        Method method = hook.getDeclaredMethod("gtShanhai$preserveNonMaterialAbilities", Simple.class, CallbackInfoReturnable.class);
        method.setAccessible(true);
        var callback = new CallbackInfoReturnable<Boolean>("materials", true);
        method.invoke(null, predicate, callback);
        if (callback.isCancelled()) return callback.getReturnValueZ();
        Method original = target.getDeclaredMethod("originalMaterials", Simple.class);
        original.setAccessible(true);
        return (boolean) original.invoke(null, predicate);
    }
    private static Class<?> load(String version, boolean patched) throws Exception {
        String original = EC + "recipe/iv/IvNativeHatches";
        String generated = PREFIX + "$Generated" + version.replace('.', '_') + (patched ? "Fixed" : "Baseline");
        Map<String, String> names = new HashMap<>();
        names.put(original, generated);
        names.put(MIXIN, generated + "Hook");
        names.put(GT + "pattern/predicates/SimplePredicate", PREFIX + "$Simple");
        names.put(GT + "pattern/TraceabilityPredicate", PREFIX + "$Trace");
        names.put(GT + "machine/multiblock/PartAbility", PREFIX + "$Ability");
        names.put("net/minecraft/world/level/block/Block", PREFIX + "$Block");
        names.put("net/minecraft/world/level/block/state/BlockState", PREFIX + "$BlockState");
        names.put("com/lowdragmc/lowdraglib/utils/BlockInfo", PREFIX + "$BlockInfo");
        names.put(EC + "machine/galaxy/GalaxyConnectionHatch", PREFIX + "$Galaxy");
        names.put(EC + "recipe/iv/IvBufferRegistry", PREFIX + "$Ports");
        SimpleRemapper remapper = new SimpleRemapper(names);
        Loader loader = new Loader();
        if (patched) {
            try (InputStream in = EnhancedCoreNativeHatchesCompatibilityTest.class.getClassLoader().getResourceAsStream(MIXIN + ".class")) {
                assertNotNull(in);
                ClassWriter hook = new ClassWriter(0);
                new ClassReader(in).accept(new ClassRemapper(hook, remapper), 0);
                loader.define(generated + "Hook", hook.toByteArray());
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String descriptor = "(L" + GT + "pattern/predicates/SimplePredicate;)Z";
        try (ZipFile jar = new ZipFile("libs/GTL-Enhancedcore-" + version + ".jar")) {
            new ClassReader(jar.getInputStream(jar.getEntry(original + ".class"))).accept(
                new ClassVisitor(Opcodes.ASM9, new ClassRemapper(writer, remapper)) {
                    @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                        return super.visitMethod(access, patched && name.equals("materials") && desc.equals(descriptor)
                                ? "originalMaterials" : name, desc, signature, exceptions);
                    }
                }, 0);
        }
        if (patched) {
            MethodVisitor wrapper = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "materials",
                    "(L" + PREFIX + "$Simple;)Z", null, null);
            wrapper.visitCode();
            wrapper.visitVarInsn(Opcodes.ALOAD, 0);
            wrapper.visitLdcInsn(Type.getObjectType(generated));
            wrapper.visitMethodInsn(Opcodes.INVOKESTATIC, PREFIX, "classify",
                    "(L" + PREFIX + "$Simple;Ljava/lang/Class;)Z", false);
            wrapper.visitInsn(Opcodes.IRETURN); wrapper.visitMaxs(2, 1); wrapper.visitEnd();
        }
        writer.visitEnd();
        return loader.define(generated, writer.toByteArray());
    }
    private static Trace restrict(Class<?> type, Trace source) throws Exception {
        return (Trace) type.getMethod("restrict", Trace.class).invoke(type.getConstructor().newInstance(), source);
    }
    @ParameterizedTest @ValueSource(strings = {"3.8.9", "4.1.7", "4.6.5"})
    void retainsNonMaterialRulesAndLimitsWhileReplacingMaterialRules(String version) throws Exception {
        Block ordinary = new Block(false, false), multiAbility = new Block(true, false);
        Simple energy = new Simple(true, ordinary, multiAbility), maintenance = new Simple(true, multiAbility);
        energy.minCount = 1; energy.maxCount = 2; energy.minLayerCount = 1; energy.maxLayerCount = 1;
        maintenance.minCount = 1; maintenance.maxCount = 1;
        Simple input = new Simple(false, multiAbility), output = new Simple(false, multiAbility);
        Trace source = new Trace();
        source.limited.addAll(List.of(energy, maintenance, input)); source.common.add(output);
        Trace baseline = restrict(load(version, false), source);
        assertFalse(baseline.limited.contains(energy)); assertFalse(baseline.limited.contains(maintenance));
        Trace fixed = restrict(load(version, true), source);
        assertEquals(List.of(energy, maintenance), fixed.limited);
        assertEquals(List.of(Ports.PORT), fixed.common);
        assertEquals(1, energy.minCount); assertEquals(2, energy.maxCount);
        assertEquals(1, energy.minLayerCount); assertEquals(1, energy.maxLayerCount);
        assertEquals(1, maintenance.minCount); assertEquals(1, maintenance.maxCount);
        assertEquals(List.of(energy, maintenance, input), source.limited, "Original predicate must stay unchanged");
        assertTrue(((ShanhaiAbilityPredicate) fixed.limited.getFirst()).gtShanhai$hasNonMaterialAbility());
    }
    @ParameterizedTest @ValueSource(strings = {"3.8.9", "4.1.7", "4.6.5"})
    void unknownAndExplicitPredicatesKeepOriginalClassification(String version) throws Exception {
        Class<?> fixed = load(version, true);
        Simple explicitMaterial = new Simple(false, new Block(true, false));
        Simple connection = new Simple(false, new Block(true, true));
        Simple casing = new Simple(false, new Block(false, false));
        Simple noSupplier = new Simple(false); noSupplier.candidates = null;
        Simple noCandidates = new Simple(false); noCandidates.candidates = () -> null;
        Trace source = new Trace(); source.common.addAll(List.of(explicitMaterial, connection, casing, noSupplier, noCandidates));
        Trace result = restrict(fixed, source);
        assertEquals(List.of(connection, casing, noSupplier, noCandidates, Ports.PORT), result.common);
        Trace nonMaterialOnly = new Trace(); nonMaterialOnly.common.add(new Simple(true, new Block(true, false)));
        assertSame(nonMaterialOnly, restrict(fixed, nonMaterialOnly), "No material replacement means original trace identity");
    }
    @Test void gtceuTraceCopyPreservesAbilityMetadataAndLimitsByIdentity() throws Exception {
        String original = GT + "pattern/TraceabilityPredicate", generated = PREFIX + "$RealTraceCopy";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, generated, null, PREFIX + "$Trace", null);
        var remapper = new SimpleRemapper(Map.of(original, generated));
        try (ZipFile jar = new ZipFile("libs/gtceu-1.20.1-1.4.4.jar")) {
            new ClassReader(jar.getInputStream(jar.getEntry(original + ".class"))).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    if (!name.equals("<init>") || !(desc.equals("()V") || desc.equals("(L" + original + ";)V"))) return null;
                    MethodVisitor output = writer.visitMethod(access, name, remapper.mapMethodDesc(desc), null, null);
                    return new org.objectweb.asm.commons.MethodRemapper(output, remapper) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                            // Only substitute the empty superclass constructor. Copy logic remains original GTCEu bytecode.
                            super.visitMethodInsn(opcode, owner.equals("java/lang/Object") && method.equals("<init>")
                                    ? PREFIX + "$Trace" : owner, method, descriptor, itf);
                        }
                    };
                }
            }, 0);
        }
        writer.visitEnd();
        Class<?> type = new Loader().define(generated, writer.toByteArray());
        Trace originalTrace = (Trace) type.getConstructor().newInstance();
        Simple energy = new Simple(true, new Block(true, false)); energy.minCount = 1; energy.maxCount = 2;
        originalTrace.limited.add(energy);
        Trace copy = (Trace) type.getConstructor(type).newInstance(originalTrace);
        assertNotSame(originalTrace.limited, copy.limited);
        assertSame(energy, copy.limited.getFirst());
        assertTrue(copy.limited.getFirst().gtShanhai$hasNonMaterialAbility());
        assertEquals(1, copy.limited.getFirst().minCount); assertEquals(2, copy.limited.getFirst().maxCount);
    }
    @Test void compatibilityMixinLoadsWithoutEnhancedCoreAndIsOptional() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.gtl.enhancedcore.common.recipe.iv.IvNativeHatches", false, loader));
        assertDoesNotThrow(() -> Class.forName(MIXIN.replace('/', '.'), false, loader));
        Set<String> annotations = new HashSet<>();
        try (InputStream in = loader.getResourceAsStream(MIXIN + ".class")) {
            new ClassReader(Objects.requireNonNull(in)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    annotations.add(descriptor); return null;
                }
            }, ClassReader.SKIP_CODE);
        }
        assertTrue(annotations.contains("Lorg/spongepowered/asm/mixin/Pseudo;"));
        String config = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/gt_shanhai.enhancedcore.mixin.json"));
        assertTrue(config.contains("\"required\": false"));
        assertTrue(config.contains("EnhancedCoreNativeHatchesMixin"));
    }

}
