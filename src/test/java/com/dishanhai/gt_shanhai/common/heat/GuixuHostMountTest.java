package com.dishanhai.gt_shanhai.common.heat;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.common.recipe.condition.DimensionCondition;
import com.gregtechceu.gtceu.common.recipe.condition.ResearchCondition;
import org.gtlcore.gtlcore.common.recipe.condition.GravityCondition;
import net.minecraft.resources.ResourceLocation;
import org.objectweb.asm.*;
import java.io.InputStream;
import sun.misc.Unsafe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.electronwill.nightconfig.core.CommentedConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

public class GuixuHostMountTest {
    @BeforeEach void loadConfig() { DShanhaiConfig.COMMON_SPEC.setConfig(CommentedConfig.inMemory()); }
    @AfterEach void unloadConfig() { DShanhaiConfig.COMMON_SPEC.setConfig(null); }
    private ShanhaiHeatGate.Outcome evaluate(List<ShanhaiHeatGate.Requirement> needs,
            List<ShanhaiHeatGate.SlotContent> local, List<ShanhaiHeatGate.SlotContent> host) throws Exception {
        Class<?> gate = assertDoesNotThrow(() -> Class.forName(
                "com.dishanhai.gt_shanhai.common.heat.ShanhaiExtraMountGate"), "缺少主機掛載共用判定");
        return (ShanhaiHeatGate.Outcome) gate.getMethod("evaluate", List.class, List.class, List.class)
                .invoke(null, needs, local, host);
    }

    @Test void sourcesAreUnitedWithoutWeakeningBetterLocalSlots() throws Exception {
        var hot = new ShanhaiHeatGate.SlotContent(64, 9000, 0, 0, false, false, Set.of());
        var cold = new ShanhaiHeatGate.SlotContent(64, 1800, 0, 0, false, false, Set.of());
        var gravity = new ShanhaiHeatGate.SlotContent(1, 0, 0, 0, true, false, Set.of());
        assertTrue(evaluate(List.of(ShanhaiHeatGate.Requirement.heatTemp(5000),
                ShanhaiHeatGate.Requirement.gravity()), List.of(hot), List.of(cold, gravity)).allowed);
        assertFalse(evaluate(List.of(ShanhaiHeatGate.Requirement.heatTemp(5000)),
                List.of(cold), List.of(gravity)).allowed);
    }

    @Test void sourcesCannotPoolPartialStacksToBypassHeatCount() throws Exception {
        var partial = new ShanhaiHeatGate.SlotContent(32, 9000, 0, 0, false, false, Set.of());
        assertFalse(evaluate(List.of(ShanhaiHeatGate.Requirement.heatTemp(5000)),
                List.of(partial), List.of(partial)).allowed);
    }

    @Test void hostSlotsArePersistedDroppedAndDoNotShrinkExistingModuleSlots() throws Exception {
        assertEquals(6, ShanhaiHeatGate.SLOT_COUNT);
        assertEquals(3, assertDoesNotThrow(() -> GuixuVisionCoreMachine.class
                .getField("EXTRA_MOUNT_SLOT_COUNT")).getInt(null));
        GuixuVisionCoreMachine.assertFieldHolderComplete();
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/guixu/GuixuVisionCoreMachine.java"));
        assertTrue(source.contains("clearInventory(extraMountSlots.storage)"));
        assertTrue(source.contains("snapshot[i] = current.copy()"));
        assertTrue(source.contains("new ExtraMountPageProvider()"));
    }

    @Test void dimensionAlternativesNeedOnlyOneBranchAndReverseCannotUseMountToPass() throws Exception {
        isolatedConditionScenario(0);
    }

    @Test void nativeOrBranchAndUnrestrictedPolicyKeepTheirOriginalMeaning() throws Exception {
        isolatedConditionScenario(1);
    }

    /** 僅去除依賴的 registry codec 初始化；條件解析與 OR/reverse 判定仍執行正式實作。 */
    private static void isolatedConditionScenario(int scenario) throws Exception {
        Class<?> isolated = new RegistryIsolatedLoader(GuixuHostMountTest.class.getClassLoader())
                .loadClass(GuixuHostMountTest.class.getName());
        var run = isolated.getDeclaredMethod("conditionScenario", int.class);
        run.setAccessible(true);
        try { run.invoke(null, scenario); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            throw (Exception) failure.getCause();
        }
    }

    private static void conditionScenario(int scenario) throws Exception {
        if (scenario == 0) {
            var alpha = new Dimension("test:alpha", false, false);
            var beta = new Dimension("test:beta", false, false);
            var slots = List.of(new ShanhaiHeatGate.SlotContent(1, 0, 0, 0, false, false, Set.of("test:beta")));
            GTRecipe recipe = recipe(List.of(alpha, beta));
            var needs = ShanhaiExtraMountGate.conditionRequirements(recipe, null, slots, false, false);
            assertEquals(List.of(ShanhaiHeatGate.Requirement.dimension("test:beta")), needs);
            var outcome = ShanhaiHeatGate.evaluate(needs, slots);
            assertTrue(ShanhaiExtraMountGate.conditionsPass(recipe, null, outcome, false, false));
            recipe = recipe(List.of(new Dimension("test:beta", true, true)));
            assertFalse(ShanhaiExtraMountGate.conditionsPass(recipe, null, outcome, false, false),
                    "反向維度條件原生失敗時，不得由正向掛載救回");
            return;
        }
        GTRecipe recipe = recipe(List.of(new Dimension("test:alpha", true, false),
                new Dimension("test:beta", false, false)));
        assertTrue(ShanhaiExtraMountGate.conditionRequirements(recipe, null, List.of(), false, false).isEmpty());
        assertTrue(ShanhaiExtraMountGate.conditionsPass(recipe, null, null, false, false));
        recipe = recipe(List.of(new Dimension("test:alpha", false, false)));
        assertTrue(ShanhaiExtraMountGate.conditionRequirements(recipe, null, List.of(), true, true).isEmpty());
        assertTrue(ShanhaiExtraMountGate.conditionsPass(recipe, null, null, true, true));
        assertFalse(ShanhaiExtraMountGate.conditionsPass(recipe, null, null, true, false));
        recipe = recipe(List.of(allocate(Research.class)));
        assertTrue(ShanhaiExtraMountGate.conditionRequirements(recipe, null, List.of(), true, false).isEmpty());
        assertTrue(ShanhaiExtraMountGate.conditionsPass(recipe, null, null, true, false), "正常模式仍忽略研究");
        recipe = recipe(List.of(new Gravity()));
        assertFalse(ShanhaiExtraMountGate.conditionRequirements(recipe, null, List.of(), true, true).isEmpty());
        assertFalse(ShanhaiExtraMountGate.conditionsPass(recipe, null, null, true, true), "無限制模式仍要求重力");
    }

    @Test void moduleCacheChangesForHostUpdatesIdentityAndRemoteUnlink() throws Exception {
        Host host = allocate(Host.class);
        host.available = true;
        host.contents = List.of(new ShanhaiHeatGate.SlotContent(63, 9000, 0, 0, false, false, Set.of()));
        Engine engine = allocate(Engine.class);
        engine.upstream = host;
        Module module = allocate(Module.class);
        module.local = List.of(ShanhaiHeatGate.SlotContent.EMPTY);
        module.setHost(engine);
        Logic logic = allocate(Logic.class);
        logic.module = module;
        var method = PrimordialModuleRecipeLogic.class.getDeclaredMethod("currentGateMountContents");
        method.setAccessible(true);
        Object first = method.invoke(logic);
        assertSame(first, method.invoke(logic));
        host.contents = List.of(new ShanhaiHeatGate.SlotContent(64, 9000, 0, 0, false, false, Set.of()));
        var changed = (List<ShanhaiHeatGate.SlotContent>) method.invoke(logic);
        assertNotSame(first, changed);
        assertTrue(ShanhaiHeatGate.evaluate(List.of(ShanhaiHeatGate.Requirement.heatTemp(5000)), changed).allowed);
        Host other = allocate(Host.class);
        other.available = true;
        other.contents = host.contents;
        engine.upstream = other;
        assertNotSame(changed, method.invoke(logic), "相同內容不同歸墟仍要使候選失效");
        engine.upstream = null;
        var unlinked = (List<ShanhaiHeatGate.SlotContent>) method.invoke(logic);
        assertFalse(ShanhaiHeatGate.evaluate(List.of(ShanhaiHeatGate.Requirement.heatTemp(5000)), unlinked).allowed);
        assertNull(ShanhaiHostMountSource.resolve(engine));
        assertSame(host, ShanhaiHostMountSource.resolve(host));
        host.available = false;
        assertNull(ShanhaiHostMountSource.resolve(host), "卸載的直接歸墟不再提供掛載");
        engine.upstream = host;
        assertNull(ShanhaiHostMountSource.resolve(engine), "卸載的遠端歸墟同樣失效");
        host.available = true;
        engine.unformed = true;
        assertNull(ShanhaiHostMountSource.resolve(engine));
        engine.unformed = false;
        engine.invalid = true;
        assertNull(ShanhaiHostMountSource.resolve(engine));
    }

    @Test void productionHostInventorySnapshotDetectsInPlaceCountChanges() throws Exception {
        // 正式 getExtraMountContents 方法逐指令移入 probe，只替換無法在 JUnit 啟動的物品/registry 邊界。
        Class<?> probe = snapshotProbe();
        Object host = probe.getConstructor().newInstance();
        SnapshotStack stack = new SnapshotStack(63);
        probe.getField("stack").set(host, stack);
        var read = probe.getMethod("getExtraMountContents");
        var first = read.invoke(host);
        assertSame(first, read.invoke(host));
        stack.count++;
        var full = read.invoke(host);
        assertNotSame(first, full, "同一 ItemStack 原地63→64也必須重算");
        assertSame(full, read.invoke(host));
        assertFalse(ShanhaiHeatGate.evaluate(List.of(ShanhaiHeatGate.Requirement.heatTemp(5000)),
                (List<ShanhaiHeatGate.SlotContent>) first).allowed);
        assertTrue(ShanhaiHeatGate.evaluate(List.of(ShanhaiHeatGate.Requirement.heatTemp(5000)),
                (List<ShanhaiHeatGate.SlotContent>) full).allowed);
        stack.count--;
        assertNotSame(full, read.invoke(host));
    }

    private static GTRecipe recipe(List<RecipeCondition> conditions) throws Exception {
        GTRecipe recipe = allocate(GTRecipe.class);
        var field = GTRecipe.class.getDeclaredField("conditions");
        field.setAccessible(true);
        field.set(recipe, conditions);
        return recipe;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class Dimension extends DimensionCondition {
        private final boolean pass;
        Dimension(String id, boolean pass, boolean reverse) {
            super(reverse, new ResourceLocation(id));
            this.pass = pass;
        }
        @Override public boolean test(GTRecipe recipe, RecipeLogic logic) { return pass; }
        @Override public RecipeConditionType<?> getType() { return null; }
    }
    private static final class Research extends ResearchCondition {
        @Override public boolean test(GTRecipe recipe, RecipeLogic logic) { return false; }
    }
    private static final class Gravity extends GravityCondition {
        @Override public boolean test(GTRecipe recipe, RecipeLogic logic) { return false; }
    }
    private static final class Host extends GuixuVisionCoreMachine {
        List<ShanhaiHeatGate.SlotContent> contents;
        boolean available;
        private Host() { super(null); }
        @Override public boolean isFormed() { return true; }
        @Override public boolean isInValid() { return false; }
        @Override public List<ShanhaiHeatGate.SlotContent> hostExtraMountContents() { return contents; }
        @Override public boolean canProvideHostMounts() { return available; }
    }
    private static final class Engine extends PrimordialOmegaEngineMachine {
        Host upstream;
        boolean unformed, invalid;
        private Engine() { super(null); }
        @Override public boolean isFormed() { return !unformed; }
        @Override public boolean isInValid() { return invalid; }
        @Override public GuixuVisionCoreMachine getLinkedGuixuHost() { return upstream; }
    }
    private static final class Module extends PrimordialOmegaEngineModuleBase {
        List<ShanhaiHeatGate.SlotContent> local;
        private Module() { super(null); }
        @Override public List<ShanhaiHeatGate.SlotContent> getExtraMountContents() { return local; }
    }
    private static final class Logic extends PrimordialModuleRecipeLogic {
        Module module;
        private Logic() { super(null); }
        @Override public Module getMachine() { return module; }
    }

    private static final class RegistryIsolatedLoader extends ClassLoader {
        RegistryIsolatedLoader(ClassLoader parent) { super(parent); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                boolean dependency = name.equals(RecipeCondition.class.getName())
                        || name.equals(DimensionCondition.class.getName())
                        || name.equals(ResearchCondition.class.getName())
                        || name.equals(GravityCondition.class.getName());
                boolean isolated = dependency || name.startsWith(GuixuHostMountTest.class.getName())
                        || name.equals(ShanhaiExtraMountGate.class.getName());
                if (loaded == null && isolated) {
                    try (InputStream input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        ClassReader reader = new ClassReader(input);
                        ClassWriter writer = new ClassWriter(0);
                        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                    String signature, String[] exceptions) {
                                return dependency && method.equals("<clinit>") ? null
                                        : super.visitMethod(access, method, descriptor, signature, exceptions);
                            }
                        }, 0);
                        byte[] bytes = writer.toByteArray();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
                }
                if (loaded == null) loaded = super.loadClass(name, false);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }

    public static final class SnapshotStack {
        public static final SnapshotStack EMPTY = new SnapshotStack(0);
        int count;
        public SnapshotStack(int count) { this.count = count; }
        public int getCount() { return count; }
        public SnapshotStack copy() { return new SnapshotStack(count); }
        public static boolean isSameItemSameTags(SnapshotStack left, SnapshotStack right) {
            return left != null && right != null;
        }
    }

    public static ShanhaiHeatGate.SlotContent slotContentOf(SnapshotStack stack) {
        return stack.count <= 0 ? ShanhaiHeatGate.SlotContent.EMPTY
                : new ShanhaiHeatGate.SlotContent(stack.count, 9000, 0, 0, false, false, Set.of());
    }

    /** 複製正式方法的控制流與 copy 呼叫，隔離 vanilla ItemStack 的全域 registry 啟動。 */
    private static Class<?> snapshotProbe() throws Exception {
        String original = GuixuVisionCoreMachine.class.getName().replace('.', '/');
        String probe = "com/dishanhai/gt_shanhai/common/heat/GuixuInventorySnapshotProbe";
        String stack = SnapshotStack.class.getName().replace('.', '/');
        String outer = GuixuHostMountTest.class.getName().replace('.', '/');
        String vanilla = "net/minecraft/world/item/ItemStack";
        java.util.function.UnaryOperator<String> descriptor = value -> value == null ? null
                : value.replace("L" + vanilla + ";", "L" + stack + ";");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, probe, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "stack", "L" + stack + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "cachedExtraMountStacks", "[L" + stack + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "cachedExtraMountContents", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor getter = writer.visitMethod(Opcodes.ACC_PUBLIC, "getExtraMountStack", "(I)L" + stack + ";", null, null);
        getter.visitCode();
        getter.visitVarInsn(Opcodes.ILOAD, 1);
        Label empty = new Label();
        getter.visitJumpInsn(Opcodes.IFNE, empty);
        getter.visitVarInsn(Opcodes.ALOAD, 0);
        getter.visitFieldInsn(Opcodes.GETFIELD, probe, "stack", "L" + stack + ";");
        getter.visitInsn(Opcodes.ARETURN);
        getter.visitLabel(empty);
        getter.visitFieldInsn(Opcodes.GETSTATIC, stack, "EMPTY", "L" + stack + ";");
        getter.visitInsn(Opcodes.ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();
        try (InputStream input = GuixuVisionCoreMachine.class.getClassLoader().getResourceAsStream(original + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc,
                        String signature, String[] exceptions) {
                    if (!name.equals("getExtraMountContents")) return null;
                    return new MethodVisitor(Opcodes.ASM9,
                            writer.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, exceptions)) {
                        @Override public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                            super.visitFieldInsn(opcode, owner.equals(original) ? probe : owner, field, descriptor.apply(desc));
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                            String mapped = owner.equals(original) ? probe : owner.equals(vanilla) ? stack
                                    : owner.equals("com/dishanhai/gt_shanhai/common/heat/ShanhaiHeatSources") ? outer : owner;
                            super.visitMethodInsn(opcode, mapped, method, descriptor.apply(desc), itf);
                        }
                        @Override public void visitTypeInsn(int opcode, String type) {
                            super.visitTypeInsn(opcode, type.equals(vanilla) ? stack : type);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        class ProbeLoader extends ClassLoader {
            ProbeLoader() { super(GuixuHostMountTest.class.getClassLoader()); }
            Class<?> define() { return defineClass(probe.replace('/', '.'), bytes, 0, bytes.length); }
        }
        return new ProbeLoader().define();
    }
}
