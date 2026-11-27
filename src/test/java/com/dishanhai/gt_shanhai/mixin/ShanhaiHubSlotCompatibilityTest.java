package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.commons.ClassRemapper;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：執行真實 GTCEu/GTLCore 計數及山海注入方法，世界/內部槽位條件以隔離替身提供。 */
public class ShanhaiHubSlotCompatibilityTest {
    private static final String GT = "com/gregtechceu/gtceu/";
    private static final String ROOT = "com/dishanhai/gt_shanhai/";
    private static final String PREFIX = Type.getInternalName(ShanhaiHubSlotCompatibilityTest.class);
    private static Class<?> simpleClass, traceClass;
    private static Class<?> factoryClass;
    private static int sequence;

    public enum IO { BOTH }
    public enum Ability { IMPORT_ITEMS, EXPORT_ITEMS, IMPORT_FLUIDS, EXPORT_FLUIDS,
        INPUT_ENERGY, SUBSTATION_INPUT_ENERGY, INPUT_LASER,
        OUTPUT_ENERGY, SUBSTATION_OUTPUT_ENERGY, OUTPUT_LASER, MAINTENANCE, SPECIAL,
        PARALLEL_HATCH, COMPUTATION_DATA_RECEPTION, COMPUTATION_DATA_TRANSMISSION,
        OPTICAL_DATA_RECEPTION, OPTICAL_DATA_TRANSMISSION, THREAD_MODIFIER;

        public String getName() { return name().toLowerCase(Locale.ROOT); }
    }
    public static class Error {}
    public static class SingleError extends Error {
        public SingleError(SimpleBase predicate, int type) {}
    }
    public static class State {
        public IO io;
        public String block;
        public Error error;
        private final Map<SimpleBase, Integer> global = new IdentityHashMap<>();
        private final Map<SimpleBase, Integer> layer = new IdentityHashMap<>();
        public Map<SimpleBase, Integer> getGlobalCount() { return global; }
        public Map<SimpleBase, Integer> getLayerCount() { return layer; }
        public void setError(Error error) { this.error = error; }
    }
    public static abstract class SimpleBase {
        public int minCount = -1, maxCount = -1, minLayerCount = -1, maxLayerCount = -1;
        public Predicate<State> predicate;
        public boolean innerMatches = true;
        public abstract boolean test(State state);
        public abstract boolean originalTestLimited(State state);
        public abstract boolean testGlobal(State state);
        public abstract boolean testLayer(State state);
        public boolean checkInnerConditions(State state) { return innerMatches; }
        public boolean testLimited(State state) {
            var callback = new CallbackInfoReturnable<Boolean>("testLimited", true);
            invokeHook(this, "gtShanhai$skipHubIoCount", state, callback);
            return callback.isCancelled() ? callback.getReturnValueZ() : originalTestLimited(state);
        }
    }
    public static abstract class TraceBase {
        public List<SimpleBase> limited = new ArrayList<>(), common = new ArrayList<>();
        public abstract boolean originalTest(State state);
        public boolean test(State state) {
            boolean result = originalTest(state);
            var callback = new CallbackInfoReturnable<Boolean>("test", true, result);
            invokeHook(this, "gtShanhai$admitHubWithoutIoCount", state, callback);
            return callback.getReturnValueZ();
        }
    }
    public static class HubWorld {
        public static boolean isHub(State state) { return state != null && "hub".equals(state.block); }
        public static boolean borrowsMaterialSlot(State state) {
            return isHub(state) || isRecipeThreadHatch(state) || "cosmic".equals(state.block)
                    || "overclock".equals(state.block);
        }
        public static boolean isRecipeThreadHatch(State state) { return "recipe-thread".equals(state.block); }
    }
    private static void invokeHook(Object target, String method, State state, CallbackInfoReturnable<Boolean> cir) {
        try {
            Method hook = target.getClass().getMethod(method, State.class, CallbackInfoReturnable.class);
            hook.invoke(target, state, cir);
        } catch (NoSuchMethodException baseline) {
            // 修復前沒有共用 hook，執行原計數流程以觀察紅燈。
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
    static class Loader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }

    @BeforeAll
    static void loadRealMethods() throws Exception {
        Loader loader = new Loader();
        simpleClass = graft(loader, "libs/gtceu-1.20.1-1.4.4.jar",
                GT + "api/pattern/predicates/SimplePredicate", SimpleBase.class,
                Set.of("test", "testLimited", "testGlobal", "testLayer"),
                "ShanhaiHubIoPredicateMixin");
        traceClass = graft(loader, "libs/gtlcore-1.2.3.3-fix4.jar",
                "org/gtlcore/gtlcore/mixin/gtm/api/pattern/TraceabilityPredicateMixin", TraceBase.class,
                Set.of("test"), "ShanhaiHubAdmissionMixin");
        try (InputStream in = ShanhaiHubSlotCompatibilityTest.class.getClassLoader()
                .getResourceAsStream(ROOT + "mixin/ShanhaiHubAbilityPredicateMixin.class")) {
            if (in != null) {
                String generated = PREFIX + "$GeneratedFactory";
                ClassWriter writer = new ClassWriter(0);
                new ClassReader(in).accept(new ClassRemapper(writer, new SimpleRemapper(Map.of(
                        ROOT + "mixin/ShanhaiHubAbilityPredicateMixin", generated,
                        GT + "api/machine/multiblock/PartAbility", PREFIX + "$Ability",
                        GT + "api/pattern/TraceabilityPredicate", PREFIX + "$TraceBase",
                        GT + "api/pattern/predicates/SimplePredicate", PREFIX + "$SimpleBase"))), 0);
                factoryClass = loader.define(generated.replace('/', '.'), writer.toByteArray());
            }
        }
    }

    private static Class<?> graft(Loader loader, String jar, String original, Class<?> parent,
                                   Set<String> methods, String mixinName) throws Exception {
        String generated = PREFIX + "$Generated" + sequence++;
        Map<String, String> mappings = new HashMap<>();
        mappings.put(GT + "api/pattern/predicates/SimplePredicate", PREFIX + "$SimpleBase");
        mappings.put(GT + "api/pattern/TraceabilityPredicate", PREFIX + "$TraceBase");
        mappings.put("org/gtlcore/gtlcore/mixin/gtm/api/pattern/TraceabilityPredicateMixin", PREFIX + "$TraceBase");
        mappings.put(GT + "api/pattern/MultiblockState", PREFIX + "$State");
        mappings.put(GT + "api/pattern/error/PatternError", PREFIX + "$Error");
        mappings.put(GT + "api/pattern/error/SinglePredicateError", PREFIX + "$SingleError");
        mappings.put(GT + "api/capability/recipe/IO", PREFIX + "$IO");
        mappings.put(ROOT + "common/machine/part/ShanhaiHubStructure", PREFIX + "$HubWorld");
        mappings.put(ROOT + "mixin/" + mixinName, generated);
        SimpleRemapper remapper = new SimpleRemapper(mappings);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String marker = ROOT + "api/machine/part/ShanhaiHubIoPredicate";
        boolean hasFix = ShanhaiHubSlotCompatibilityTest.class.getClassLoader()
                .getResource(ROOT + "mixin/" + mixinName + ".class") != null;
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, generated, null, Type.getInternalName(parent),
                hasFix && parent == SimpleBase.class ? new String[]{marker, ROOT + "api/machine/part/ShanhaiAbilityPredicate"} : null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(parent), "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(1, 1); init.visitEnd();
        try (ZipFile zip = new ZipFile(jar)) {
            new ClassReader(zip.getInputStream(zip.getEntry(original + ".class"))).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    if (!methods.contains(name)) return null;
                    String output = name.equals("testLimited") ? "originalTestLimited"
                            : parent == TraceBase.class && name.equals("test") ? "originalTest" : name;
                    return new MethodRemapper(writer.visitMethod(Opcodes.ACC_PUBLIC, output,
                            remapper.mapMethodDesc(desc), null, null), remapper);
                }
            }, 0);
        }
        if (hasFix) {
            try (InputStream in = ShanhaiHubSlotCompatibilityTest.class.getClassLoader()
                    .getResourceAsStream(ROOT + "mixin/" + mixinName + ".class")) {
                new ClassReader(Objects.requireNonNull(in)).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                        if (!name.startsWith("gtShanhai$")) return null;
                        return writer.visitField(Opcodes.ACC_PUBLIC, name, remapper.mapDesc(desc), null, value);
                    }
                    @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                        if (!name.startsWith("gtShanhai$")) return null;
                        return new MethodRemapper(writer.visitMethod(Opcodes.ACC_PUBLIC | (access & Opcodes.ACC_STATIC),
                                name, remapper.mapMethodDesc(desc), null, null), remapper);
                    }
                }, 0);
            }
        }
        writer.visitEnd();
        return loader.define(generated.replace('/', '.'), writer.toByteArray());
    }

    private static SimpleBase slot(String kind, int max, boolean ioAdmission) throws Exception {
        SimpleBase slot = (SimpleBase) simpleClass.getConstructor().newInstance();
        slot.maxCount = max;
        slot.predicate = state -> "hub".equals(state.block) || kind.equals(state.block);
        if (ioAdmission) {
            try { simpleClass.getMethod("gtShanhai$setIoAdmissionOnly", boolean.class).invoke(slot, true); }
            catch (NoSuchMethodException baseline) { /* 修復前走原邏輯。 */ }
        }
        return slot;
    }
    private static boolean match(List<SimpleBase> slots, String... blocks) throws Exception {
        TraceBase trace = (TraceBase) traceClass.getConstructor().newInstance();
        trace.limited.addAll(slots);
        State state = new State();
        for (String block : blocks) {
            state.block = block;
            if (!trace.test(state)) return false;
        }
        return state.global.entrySet().stream().allMatch(e -> e.getValue() >= e.getKey().minCount)
                && state.layer.entrySet().stream().allMatch(e -> e.getValue() >= e.getKey().minLayerCount);
    }
    private static List<SimpleBase> ioSlots() throws Exception {
        return List.of(slot("item-in", 1, true), slot("item-out", 1, true),
                slot("fluid-in", 2, true), slot("fluid-out", 2, true));
    }

    @Test void hubDoesNotStealRealIoQuotaInEitherScanOrder() throws Exception {
        assertTrue(match(ioSlots(), "hub", "item-in", "item-out", "fluid-in", "fluid-out"));
        assertTrue(match(ioSlots(), "item-in", "item-out", "fluid-in", "fluid-out", "hub"));
    }
    @Test void hubFitsWhenAllRealIoQuotasAreFull() throws Exception {
        assertTrue(match(ioSlots(), "hub", "item-in", "item-out", "fluid-in", "fluid-in", "fluid-out", "fluid-out"));
    }
    @Test void ordinaryIoUpperLimitsStillApply() throws Exception {
        assertFalse(match(ioSlots(), "hub", "item-in", "item-in"));
        assertTrue(match(ioSlots(), "item-in", "item-out"));
    }
    @Test void hubCannotSupplyRequiredItemOrFluidIo() throws Exception {
        SimpleBase input = slot("item-in", 1, true); input.minCount = 1;
        assertFalse(match(List.of(input), "hub"));
        assertTrue(match(List.of(input), "hub", "item-in"));
        SimpleBase fluid = slot("fluid-in", 1, true); fluid.minLayerCount = 1;
        assertFalse(match(List.of(fluid), "hub"));
    }
    @Test void maintenanceEnergyAndDirectBlockLimitsAreNotBypassed() throws Exception {
        SimpleBase maintenance = slot("maintenance", 1, false); maintenance.minCount = 1;
        assertTrue(match(List.of(maintenance), "hub"));
        assertFalse(match(List.of(maintenance), "hub", "maintenance"));
        assertFalse(match(List.of(slot("energy", 1, false)), "hub", "energy"));
        assertFalse(match(List.of(slot("explicit-block", 0, false)), "hub"));
    }
    @Test void hubInMaintenanceSlotLeavesOptionalEnergyQuotaForRealHatches() throws Exception {
        for (Ability ability : List.of(Ability.INPUT_ENERGY, Ability.SUBSTATION_INPUT_ENERGY, Ability.INPUT_LASER,
                Ability.OUTPUT_ENERGY, Ability.SUBSTATION_OUTPUT_ENERGY, Ability.OUTPUT_LASER)) {
            assertTrue(match(maintenanceAndEnergySlots(ability, 1, -1), "hub", "energy"));
            assertTrue(match(maintenanceAndEnergySlots(ability, 1, -1), "energy", "hub"));
            assertFalse(match(maintenanceAndEnergySlots(ability, 1, -1), "hub", "energy", "energy"));
            assertTrue(match(maintenanceAndEnergySlots(ability, -1, 1), "hub", "energy"));
        }
    }

    @Test void borrowedMaterialSlotsLeaveQuotaForRealInputs() throws Exception {
        for (String block : List.of("cosmic", "overclock", "recipe-thread")) {
            SimpleBase input = slot("item-in", 1, false);
            input.minCount = 1;
            input.predicate = state -> block.equals(state.block) || "item-in".equals(state.block);
            markAbility(input, Ability.IMPORT_ITEMS);
            assertTrue(match(List.of(input), block, "item-in"), block);
            assertTrue(match(List.of(input), "item-in", block), block);
            assertFalse(match(List.of(input), block), block);
            input.maxCount = 0;
            input.minCount = -1;
            assertFalse(match(List.of(input), block), block);
        }
        for (String block : List.of("cosmic", "overclock")) {
            SimpleBase fluid = slot("fluid-in", -1, false);
            fluid.maxLayerCount = 1;
            fluid.predicate = state -> block.equals(state.block) || "fluid-in".equals(state.block);
            markAbility(fluid, Ability.IMPORT_FLUIDS);
            assertTrue(match(List.of(fluid), block, "fluid-in"), block);
        }
    }

    @Test void realDualInputStillContributesToBothMaterialCounts() throws Exception {
        SimpleBase item = slot("item-in", 1, false);
        SimpleBase fluid = slot("fluid-in", 1, false);
        item.minCount = 1;
        fluid.minCount = 1;
        item.predicate = state -> "dual".equals(state.block) || "item-in".equals(state.block);
        fluid.predicate = state -> "dual".equals(state.block) || "fluid-in".equals(state.block);
        markAbility(item, Ability.IMPORT_ITEMS);
        markAbility(fluid, Ability.IMPORT_FLUIDS);
        assertTrue(match(List.of(item, fluid), "dual"));
        assertFalse(match(List.of(item, fluid), "dual", "item-in"));
    }

    @Test void recipeThreadHatchDoesNotCountAsAParallelHatch() throws Exception {
        SimpleBase parallel = slot("parallel", 1, false);
        parallel.minCount = 1;
        parallel.predicate = state -> "recipe-thread".equals(state.block) || "parallel".equals(state.block);
        markAbility(parallel, Ability.PARALLEL_HATCH);
        assertTrue(match(List.of(parallel), "recipe-thread", "parallel"));
        assertTrue(match(List.of(parallel), "parallel", "recipe-thread"));
        assertFalse(match(List.of(parallel), "recipe-thread"));
    }

    @Test void hubLeavesOptionalUtilityQuotaForRealHatches() throws Exception {
        for (Ability ability : List.of(Ability.PARALLEL_HATCH, Ability.COMPUTATION_DATA_RECEPTION,
                Ability.COMPUTATION_DATA_TRANSMISSION, Ability.OPTICAL_DATA_RECEPTION,
                Ability.OPTICAL_DATA_TRANSMISSION, Ability.THREAD_MODIFIER)) {
            assertTrue(match(maintenanceAndEnergySlots(ability, 1, -1), "hub", "energy"), ability.name());
            assertTrue(match(maintenanceAndEnergySlots(ability, 1, -1), "energy", "hub"), ability.name());
            SimpleBase required = slot("utility", 1, false);
            required.minCount = 1;
            markAbility(required, ability);
            assertTrue(match(List.of(required), "hub"), ability.name());
            assertFalse(match(List.of(required), "hub", "utility"), ability.name());
        }
    }

    @Test void optionalEnergyOnlySlotStillAcceptsHubWithinItsQuota() throws Exception {
        SimpleBase energy = slot("energy", 1, false);
        markAbility(energy, Ability.INPUT_ENERGY);
        assertTrue(match(List.of(energy), "hub"));
        assertFalse(match(List.of(energy), "energy", "hub"));
        assertFalse(match(List.of(energy), "hub", "energy"));

        energy.maxCount = 0;
        assertFalse(match(List.of(energy), "hub"));
        energy.maxCount = 1;
        energy.innerMatches = false;
        assertFalse(match(List.of(energy), "hub"));
    }

    @Test void hubStillContributesToRequiredEnergyCounts() throws Exception {
        SimpleBase global = slot("energy", 1, false);
        markAbility(global, Ability.INPUT_ENERGY);
        global.minCount = 1;
        assertTrue(match(List.of(global), "hub"));
        assertFalse(match(List.of(global), "hub", "energy"));

        SimpleBase layer = slot("energy", -1, false);
        markAbility(layer, Ability.INPUT_LASER);
        layer.minLayerCount = 2;
        layer.maxLayerCount = 2;
        assertFalse(match(List.of(layer), "hub"));
        assertTrue(match(List.of(layer), "hub", "energy"));
    }

    private static List<SimpleBase> maintenanceAndEnergySlots(Ability ability, int globalMax, int layerMax)
            throws Exception {
        SimpleBase maintenance = slot("maintenance", 1, false);
        maintenance.minCount = 1;
        SimpleBase energy = slot("energy", globalMax, false);
        energy.maxLayerCount = layerMax;
        markAbility(energy, ability);
        return List.of(maintenance, energy);
    }

    private static void markAbility(SimpleBase simple, Ability ability) throws Exception {
        TraceBase trace = (TraceBase) traceClass.getConstructor().newInstance();
        trace.common.add(simple);
        Method method = factoryClass.getDeclaredMethod("gtShanhai$markIoAbilities",
                Ability[].class, CallbackInfoReturnable.class);
        method.setAccessible(true);
        method.invoke(null, new Ability[]{ability}, new CallbackInfoReturnable<>("abilities", false, trace));
    }

    @Test void forbiddenOrNonMatchingOrInnerRejectedIoSlotsCannotAdmitHub() throws Exception {
        assertFalse(match(List.of(slot("item-in", 0, true)), "hub"));
        SimpleBase layerZero = slot("fluid-in", 2, true); layerZero.maxLayerCount = 0;
        assertFalse(match(List.of(layerZero), "hub"));
        SimpleBase tierExcluded = slot("item-in", 1, true); tierExcluded.predicate = state -> false;
        assertFalse(match(List.of(tierExcluded), "hub"));
        SimpleBase nbtRejected = slot("item-in", 1, true); nbtRejected.innerMatches = false;
        assertFalse(match(List.of(nbtRejected), "hub"));
    }
    @Test void multipleHubsDoNotPretendToBeMaterialIo() throws Exception {
        assertTrue(match(ioSlots(), "hub", "hub", "item-in", "item-out"));
        assertFalse(match(List.of(slot("maintenance", 1, false)), "hub", "hub"));
    }
    @Test void hubLayerAdmissionDoesNotConsumeTheRealLayerQuota() throws Exception {
        SimpleBase slot = slot("item-in", -1, true); slot.maxLayerCount = 1;
        assertTrue(match(List.of(slot), "hub", "item-in"));
        assertFalse(match(List.of(slot), "hub", "item-in", "item-in"));
    }
    @Test void ioAdmissionCannotHideOtherRealAbilityUpperLimits() throws Exception {
        List<SimpleBase> maintenance = new ArrayList<>(ioSlots());
        maintenance.add(slot("maintenance", 1, false));
        assertFalse(match(maintenance, "hub", "hub"));
        List<SimpleBase> energy = new ArrayList<>(ioSlots());
        energy.add(slot("energy", 1, false));
        assertFalse(match(energy, "energy", "hub"));
        List<SimpleBase> layered = new ArrayList<>(ioSlots());
        SimpleBase parallel = slot("parallel", -1, false); parallel.maxLayerCount = 1;
        layered.add(parallel);
        assertFalse(match(layered, "parallel", "hub"));
        List<SimpleBase> twoRealBranches = new ArrayList<>(ioSlots());
        twoRealBranches.add(slot("maintenance", 1, false));
        twoRealBranches.add(slot("energy", 2, false));
        assertFalse(match(twoRealBranches, "maintenance", "hub"));
    }
    @Test void abilityFactoryMarksOnlyPureMaterialIoAndKeepsTieredFactoryCovered() throws Exception {
        assertNotNull(factoryClass, "缺少能力工厂的入场标记");
        Method abilities = factoryClass.getDeclaredMethod("gtShanhai$markIoAbilities",
                Ability[].class, CallbackInfoReturnable.class);
        abilities.setAccessible(true);
        for (Ability[] group : List.of(new Ability[]{Ability.IMPORT_ITEMS},
                new Ability[]{Ability.EXPORT_ITEMS, Ability.IMPORT_FLUIDS, Ability.EXPORT_FLUIDS})) {
            TraceBase trace = (TraceBase) traceClass.getConstructor().newInstance();
            SimpleBase simple = slot("item-in", 1, false); trace.common.add(simple);
            abilities.invoke(null, group, new CallbackInfoReturnable<>("abilities", false, trace));
            assertEquals(true, simpleClass.getMethod("gtShanhai$isIoAdmissionOnly").invoke(simple));
            assertEquals(false, simpleClass.getMethod("gtShanhai$hasNonMaterialAbility").invoke(simple));
        }
        for (Ability[] group : List.of(new Ability[]{}, new Ability[]{Ability.MAINTENANCE},
                new Ability[]{Ability.IMPORT_ITEMS, Ability.INPUT_ENERGY}, new Ability[]{Ability.SPECIAL})) {
            TraceBase trace = (TraceBase) traceClass.getConstructor().newInstance();
            SimpleBase simple = slot("item-in", 1, false); trace.common.add(simple);
            abilities.invoke(null, group, new CallbackInfoReturnable<>("abilities", false, trace));
            assertEquals(false, simpleClass.getMethod("gtShanhai$isIoAdmissionOnly").invoke(simple));
            assertEquals(group.length > 0, simpleClass.getMethod("gtShanhai$hasNonMaterialAbility").invoke(simple));
        }
        Method tiered = factoryClass.getDeclaredMethod("gtShanhai$markTieredIoAbility",
                Ability.class, int[].class, CallbackInfoReturnable.class);
        tiered.setAccessible(true);
        TraceBase trace = (TraceBase) traceClass.getConstructor().newInstance();
        SimpleBase simple = slot("item-in", 1, false); trace.limited.add(simple);
        tiered.invoke(null, Ability.IMPORT_ITEMS, new int[]{1, 2}, new CallbackInfoReturnable<>("ability", false, trace));
        assertEquals(true, simpleClass.getMethod("gtShanhai$isIoAdmissionOnly").invoke(simple));
        assertEquals(false, simpleClass.getMethod("gtShanhai$hasNonMaterialAbility").invoke(simple));
        for (Ability ability : List.of(Ability.MAINTENANCE, Ability.INPUT_ENERGY, Ability.SPECIAL)) {
            TraceBase nonMaterialTrace = (TraceBase) traceClass.getConstructor().newInstance();
            SimpleBase nonMaterial = slot("non-material", 1, false); nonMaterialTrace.limited.add(nonMaterial);
            tiered.invoke(null, ability, new int[]{1, 2}, new CallbackInfoReturnable<>("ability", false, nonMaterialTrace));
            assertEquals(true, simpleClass.getMethod("gtShanhai$hasNonMaterialAbility").invoke(nonMaterial));
        }
    }
}
