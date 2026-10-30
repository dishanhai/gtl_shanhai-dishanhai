package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import net.minecraftforge.common.ForgeConfigSpec;
import sun.misc.Unsafe;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：使用真實工作入口，隔離世界、登錄表與無線電網。 */
class PrimordialGuixuModuleGateTest {
    private static final Unsafe UNSAFE = unsafe();
    private static final List<String> RESTRICTED = List.of(
            "primordial_weiyang_reconstruction_module", "primordial_shaoguang_aggregation_core",
            "primordial_eternal_smelting_furnace", "primordial_tianqiong_assembly_core",
            "primordial_universe_simulation_core", "primordial_quantum_distortion_matrix",
            "primordial_multidimensional_implosion_core", "primordial_supercritical_matter_generation_core",
            "primordial_causal_weaving_matrix", "primordial_singularity_inversion_core");

    private CommentedConfig settings;

    @BeforeEach void loadDefaultConfig() {
        settings = CommentedConfig.inMemory();
        DShanhaiConfig.COMMON_SPEC.setConfig(settings);
    }

    @AfterEach void unloadConfig() {
        DShanhaiConfig.COMMON_SPEC.setConfig(null);
    }

    @Test void configDefaultsPreserveTheOriginalTenRestrictions() throws Exception {
        assertEquals(false, configValue("primordialEngineAllowAnyModule").getDefault());
        assertEquals(RESTRICTED.stream().map(id -> "gt_shanhai:" + id).toList(),
                configValue("primordialEngineBlockedModuleItemIds").getDefault());
    }

    @Test void globalSwitchOverridesTheBlacklistAndCanBeDisabledAgain() throws Exception {
        Module module = module(RESTRICTED.getFirst());
        module.setHost(host(Engine.class, module));
        assertFalse(module.canModuleWork());
        configValue("primordialEngineAllowAnyModule").set(true);
        module.tick++;
        assertTrue(module.canModuleWork());
        configValue("primordialEngineAllowAnyModule").set(false);
        module.tick++;
        assertFalse(module.canModuleWork());
        assertFalse(module.beforeWorking(null));
    }

    @Test void customIdsReplaceDefaultsAndAlsoAcceptOtherNamespaces() throws Exception {
        configValue("primordialEngineBlockedModuleItemIds").set(List.of(
                "gt_shanhai:primordial_critical_processing_module", "other:custom_module"));
        Module removed = module(RESTRICTED.getFirst());
        removed.setHost(host(Engine.class, removed));
        assertTrue(removed.canModuleWork());
        Module added = module("primordial_critical_processing_module");
        added.setHost(host(Engine.class, added));
        assertFalse(added.canModuleWork());
        added.setHost(host(Guixu.class, added));
        assertTrue(added.canModuleWork());
        Module foreign = module("custom_module");
        foreign.definition = MultiblockMachineDefinition.createDefinition(new ResourceLocation("other", "custom_module"));
        foreign.setHost(host(Engine.class, foreign));
        assertFalse(foreign.canModuleWork());
    }

    @Test void emptyBlacklistIsValidAndDisablesAllHostRestrictions() throws Exception {
        configValue("primordialEngineBlockedModuleItemIds").set(List.of());
        assertTrue(DShanhaiConfig.COMMON_SPEC.isCorrect(settings));
        Module module = module(RESTRICTED.getFirst());
        module.setHost(host(Engine.class, module));
        assertTrue(module.canModuleWork());
    }

    @Test void addingAndRemovingABlacklistEntryChangesTheWorkPermission() throws Exception {
        configValue("primordialEngineBlockedModuleItemIds").set(List.of());
        Module module = module(RESTRICTED.getFirst());
        module.setHost(host(Engine.class, module));
        assertTrue(module.canModuleWork());
        configValue("primordialEngineBlockedModuleItemIds").set(List.of("gt_shanhai:" + RESTRICTED.getFirst()));
        assertFalse(module.canModuleWork()); // 新限制不可被同 tick 的成功快取繞過。
        assertFalse(module.beforeWorking(null));
        configValue("primordialEngineBlockedModuleItemIds").set(List.of());
        module.tick++;
        assertTrue(module.canModuleWork());
    }

    @Test void unloadedConfigUsesDefaultRestrictionsInsteadOfStaleOverrides() throws Exception {
        configValue("primordialEngineAllowAnyModule").set(true);
        DShanhaiConfig.COMMON_SPEC.setConfig(null);
        Module module = module(RESTRICTED.getFirst());
        module.setHost(host(Engine.class, module));
        assertFalse(module.canModuleWork());
    }

    @Test void blacklistRejectsMalformedAndUnqualifiedItemIds() {
        ForgeConfigSpec.ValueSpec spec = DShanhaiConfig.COMMON_SPEC.getSpec()
                .get(List.of("primordial_omega_engine", "blockedModuleItemIds"));
        assertNotNull(spec);
        assertTrue(spec.test(List.of("gt_shanhai:primordial_coin_forge")));
        assertTrue(spec.test(List.of()));
        for (Object invalid : List.of("missing_namespace", "gt_shanhai:", "UPPER:invalid", "a:has space", 123)) {
            assertFalse(spec.test(List.of(invalid)), invalid.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static ForgeConfigSpec.ConfigValue<Object> configValue(String name) throws Exception {
        return (ForgeConfigSpec.ConfigValue<Object>) DShanhaiConfig.ConfigValues.class.getField(name)
                .get(DShanhaiConfig.COMMON);
    }

    @Test void allTenModulesRejectOrdinaryEnginesAtEveryWorkEntry() throws Exception {
        for (String id : RESTRICTED) {
            Module module = module(id);
            module.setHost(host(Engine.class, module));
            assertFalse(module.canModuleWork(), id);
            assertFalse(module.beforeWorking(null), id);
            assertFalse(module.onWorking(), id);
            Logic logic = allocate(Logic.class);
            logic.module = module;
            assertNull(logic.getGTRecipe(), id); // 必須在查找／縮放及扣料前返回。
        }
    }

    @Test void directGuixuAndRemoteTakeoverBothUnlockEveryModule() throws Exception {
        for (String id : RESTRICTED) {
            Module module = module(id);
            Guixu guixu = host(Guixu.class, module);
            module.setHost(guixu);
            assertTrue(module.canModuleWork(), id);
            // 超維度調和沿用 connectToHost/setHost，實際工作宿主同樣是歸墟。
            module.setHost(host(Engine.class, module));
            assertFalse(module.canModuleWork(), id + " 解除接管");
            module.setHost(guixu);
            assertTrue(module.canModuleWork(), id + " 重新接管（同 tick）");
        }
    }

    @Test void hyperdimensionalTakeoverAndReleaseUseTheExistingModuleLifecycle() throws Exception {
        Module module = module(RESTRICTED.getFirst());
        Engine engine = host(Engine.class, module);
        Guixu guixu = host(Guixu.class, module);
        module.connectToHost(engine);
        assertFalse(module.canModuleWork());
        module.connectToHost(guixu); // reconcileLinkedEngine 使用的真實連接 API。
        assertSame(guixu, module.getHost());
        assertTrue(module.canModuleWork());
        module.removeFromHost(guixu); // releaseRemoteModules 使用的真實解除 API。
        assertFalse(module.canModuleWork());
        assertFalse(module.beforeWorking(null));
        module.connectToHost(engine);
        assertFalse(module.canModuleWork());
    }

    @Test void unformedGuixuCannotGrantPermissionEvenWithACachedSuccess() throws Exception {
        Module module = module(RESTRICTED.getFirst());
        Guixu guixu = host(Guixu.class, module);
        module.setHost(guixu);
        assertTrue(module.canModuleWork());
        guixu.formed = false;
        assertFalse(module.canModuleWork());
    }

    @Test void independentModeDoesNotBypassGuixuRequirement() throws Exception {
        for (String id : RESTRICTED) {
            Module module = module(id);
            assertTrue(module.canWorkWithoutHost());
            assertFalse(module.canModuleWork(), id);
        }
    }

    @Test void unrelatedModulesKeepTheirExistingHostAndIndependentBehavior() throws Exception {
        for (String id : List.of("primordial_critical_processing_module", "primordial_molecular_assembler_module",
                "primordial_myriad_proliferation_core", "primordial_coin_forge")) {
            Module module = module(id);
            assertTrue(module.canModuleWork(), id + " 獨立運作");
            module.setHost(host(Engine.class, module));
            assertTrue(module.canModuleWork(), id + " 普通引擎");
        }
        Module foreign = module("primordial_weiyang_reconstruction_module");
        foreign.definition = MultiblockMachineDefinition.createDefinition(new ResourceLocation("other", RESTRICTED.getFirst()));
        assertTrue(foreign.canModuleWork());
    }

    @Test void blockedModuleDisplaysAnExplicitRedErrorAndClearsItAfterTakeover() throws Exception {
        Module module = module(RESTRICTED.getFirst());
        module.setHost(host(Engine.class, module));
        List<Component> text = new ArrayList<>();
        module.addHostStatusDisplay(text);
        assertTrue(text.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents content
                && content.getKey().equals("gt_shanhai.machine.primordial_omega_engine.requires_guixu_host")
                && line.getStyle().getColor().getValue() == 0xFF5555));
        module.setHost(host(Guixu.class, module));
        text.clear();
        module.addHostStatusDisplay(text);
        assertFalse(text.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents content
                && content.getKey().equals("gt_shanhai.machine.primordial_omega_engine.requires_guixu_host")));
    }

    private static Module module(String id) throws Exception {
        Module module = allocate(Module.class);
        module.tick = 1L;
        module.definition = MultiblockMachineDefinition.createDefinition(new ResourceLocation("gt_shanhai", id));
        return module;
    }

    private static <T extends PrimordialOmegaEngineMachine> T host(Class<T> type, Module module) throws Exception {
        T host = allocate(type);
        Field snapshot = PrimordialOmegaEngineMachine.class.getDeclaredField("moduleSnapshot");
        snapshot.setAccessible(true);
        snapshot.set(host, Set.of(module));
        if (host instanceof Guixu guixu) guixu.formed = true;
        return host;
    }

    private static class Module extends PrimordialOmegaEngineModuleBase {
        MultiblockMachineDefinition definition;
        long tick;
        Module() { super(null); }
        @Override public MultiblockMachineDefinition getDefinition() { return definition; }
        @Override public long getOffsetTimer() { return tick; }
        @Override public boolean isFormed() { return true; }
        @Override public boolean canWorkWithoutHost() { return true; }
        @Override protected boolean showsModuleAndThreadSlots() { return false; }
    }

    private static class Engine extends PrimordialOmegaEngineMachine {
        Engine() { super(null); }
        @Override public boolean isFormed() { return true; }
        @Override public BlockPos getPos() { return BlockPos.ZERO; }
        @Override public <M extends IModularMachineModule<PrimordialOmegaEngineMachine, M>> void addModule(M module) {}
        @Override public <M extends IModularMachineModule<PrimordialOmegaEngineMachine, M>> void removeModule(M module) {}
    }

    private static class Guixu extends GuixuVisionCoreMachine {
        boolean formed;
        Guixu() { super(null); }
        @Override public boolean isFormed() { return formed; }
        @Override public boolean isContainmentLocked() { return false; }
        @Override public BlockPos getPos() { return BlockPos.ZERO; }
        @Override public <M extends IModularMachineModule<PrimordialOmegaEngineMachine, M>> void addModule(M module) {}
        @Override public <M extends IModularMachineModule<PrimordialOmegaEngineMachine, M>> void removeModule(M module) {}
    }

    private static class Logic extends PrimordialModuleRecipeLogic {
        Module module;
        Logic() { super(null); }
        @Override public Module getMachine() { return module; }
    }

    private static <T> T allocate(Class<T> type) throws InstantiationException {
        return type.cast(UNSAFE.allocateInstance(type));
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
