package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.*;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.core.PrimordialMyriadProliferationCore;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.crafting.PrimordialMolecularAssemblerModule;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.crafting.PrimordialMolecularAssemblerModuleLogic;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.generator.PrimordialDivergenceGenerator;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.generator.PrimordialOmegaVoidInductionArmatureLogic;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.matter.PrimordialSupercriticalMatterGenerationCore;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMolecularAssemblerHandler;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：以真實主機倍率快取與模組入口驗證全體共享，世界與 AE handler 使用替身。 */
class GuixuSharedModuleBonusTest {
    private static final Unsafe UNSAFE = unsafe();

    @Test void everyModuleUsesTheSameCombinedBonusAsItsHost() throws Exception {
        Host host = host();
        Source core = allocate(Source.class);
        core.multiplier = 100;
        core.setHost(host);
        set(PrimordialOmegaEngineMachine.class, host, "moduleSnapshot", Set.of(core));
        for (Class<? extends PrimordialOmegaEngineModuleBase> type : List.of(
                PrimordialSupercriticalMatterGenerationCore.class, PrimordialDivergenceGenerator.class,
                PrimordialMolecularAssemblerModule.class, PrimordialMyriadProliferationCore.class)) {
            var module = allocate(type);
            module.setHost(host);
            assertEquals(1100, host.getRecipeOutputMultiplier()); // (1 + 5 + 遏制場5) × 萬象100
            assertEquals(host.getRecipeOutputMultiplier(), module.getHostOutputMultiplier(), type.getSimpleName());
            assertEquals(1100, module.getOutputMultiplierContribution());
        }
        core.multiplier = 1000;
        host.invalidateMountedOutputMultiplier();
        assertEquals(11000, core.getHostOutputMultiplier());
        host.bonus = GuixuContainmentBonus.NONE;
        assertEquals(6000, core.getHostOutputMultiplier());
        host.formed = false;
        assertEquals(1, core.getHostOutputMultiplier());
        core.setHost(null);
        assertEquals(1, core.getHostOutputMultiplier());
    }

    @Test void anotherHostDoesNotReceiveTheFirstHostsModules() throws Exception {
        Host boosted = host(), plain = host();
        Source core = allocate(Source.class);
        core.multiplier = 100;
        set(PrimordialOmegaEngineMachine.class, boosted, "moduleSnapshot", Set.of(core));
        var module = allocate(PrimordialSupercriticalMatterGenerationCore.class);
        module.setHost(plain);
        assertEquals(11, module.getHostOutputMultiplier());
        module.setHost(boosted);
        assertEquals(1100, module.getHostOutputMultiplier());
    }

    @Test void molecularDirectRecipeReceivesContainmentDurationWithoutMutatingTheAeRecipe() throws Exception {
        Host host = host();
        Fixture fixture = molecular(host);
        GTRecipe result = fixture.logic.getGTRecipe();
        assertEquals(4, result.duration);
        assertNotSame(fixture.raw, result);
        assertFalse(result.outputs.isEmpty());
        assertEquals(8, fixture.raw.duration);
        assertEquals(1, fixture.extractions.get());
        assertEquals(fixture.raw.data, result.data);
    }

    @Test void collapsedHostDoesNotExtractAnAeJob() throws Exception {
        Host host = host();
        host.bonus = GuixuContainmentBonus.LOCKED;
        Fixture fixture = molecular(host);
        assertNull(fixture.logic.getGTRecipe());
        assertEquals(0, fixture.extractions.get());
    }

    @Test void molecularDirectRecipeReceivesUnstableOutputLossWithoutMutatingTheAeRecipe() throws Exception {
        Host host = host();
        host.bonus = GuixuContainmentBonus.of(GuixuContainmentField.Status.UNSTABLE, -900);
        Fixture fixture = molecular(host);
        assertTrue(fixture.logic.getGTRecipe().outputs.isEmpty());
        assertFalse(fixture.raw.outputs.isEmpty());
    }

    @Test void independentGeneratorLookupHonorsTheSharedContainmentLock() throws Exception {
        Host host = host();
        VoidLogic logic = allocate(VoidLogic.class);
        logic.module = allocate(PrimordialDivergenceGenerator.class);
        logic.module.setHost(host);
        logic.recipe = molecular(host).raw;
        assertSame(logic.recipe, logic.getGTRecipe());
        host.bonus = GuixuContainmentBonus.LOCKED;
        assertNull(logic.getGTRecipe());
    }

    private record Fixture(MolecularLogic logic, GTRecipe raw, AtomicInteger extractions) {}

    private static Fixture molecular(Host host) throws Exception {
        GTRecipe raw = new GTRecipe(null, new ResourceLocation("gt_shanhai", "shared_bonus_test"),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), new CompoundTag(), 8, false);
        // 能力複製使用替身，其餘走真實 GTRecipe.copy／配方入口，避免未 remap 的原生登錄表。
        raw.outputs.put(null, List.of(allocate(MarkerContent.class)));
        AtomicInteger extractions = new AtomicInteger();
        Molecular module = allocate(Molecular.class);
        module.setHost(host);
        module.handler = (IMolecularAssemblerHandler) Proxy.newProxyInstance(
                IMolecularAssemblerHandler.class.getClassLoader(), new Class[]{IMolecularAssemblerHandler.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("extractGTRecipe")) throw new AssertionError(method.getName());
                    assertEquals(Long.MAX_VALUE, args[0]);
                    assertEquals(1, args[1]);
                    extractions.incrementAndGet();
                    return raw;
                });
        MolecularLogic logic = allocate(MolecularLogic.class);
        logic.module = module;
        return new Fixture(logic, raw, extractions);
    }

    private static Host host() throws Exception {
        Host host = allocate(Host.class);
        host.formed = true;
        host.bonus = GuixuContainmentBonus.of(GuixuContainmentField.Status.HYPERSTABLE, 100);
        set(PrimordialOmegaEngineMachine.class, host, "outputMultiplierCache", new PrimordialOmegaEngineMachine.OutputMultiplierCache());
        set(PrimordialOmegaEngineMachine.class, host, "moduleSnapshot", Set.of());
        return host;
    }

    private static class Host extends GuixuVisionCoreMachine {
        boolean formed;
        GuixuContainmentBonus bonus;
        Host() { super(null); }
        @Override public boolean isFormed() { return formed; }
        @Override public long getOffsetTimer() { return 1; }
        @Override public int moduleSlotBonus() { return 0; }
        @Override public GuixuContainmentBonus getContainmentBonus() { return bonus; }
    }

    private static class Source extends PrimordialMyriadProliferationCore {
        int multiplier;
        Source() { super(null); }
        @Override public int getCurrentOutputMultiplier() { return multiplier; }
    }

    private static class Molecular extends PrimordialMolecularAssemblerModule {
        IMolecularAssemblerHandler handler;
        Molecular() { super(null); }
        @Override public boolean canRunMolecularAssembly() { return true; }
        // 倍率彙總在上方獨立測試；此處隔離 ChanceLogic 的未 remap 原生登錄表初始化。
        @Override public int getHostOutputMultiplier() { return 1; }
        @Override public long getOffsetTimer() { return 0; }
        @Override public IMolecularAssemblerHandler getMolecularAssemblerHandler() { return handler; }
    }

    private static class MolecularLogic extends PrimordialMolecularAssemblerModuleLogic {
        Molecular module;
        MolecularLogic() { super(null); }
        @Override public Molecular getMachine() { return module; }
    }

    private static class MarkerContent extends Content {
        MarkerContent() { super(3L, 10000, 10000, 0, null, null); }
        @Override public Content copy(RecipeCapability<?> capability, ContentModifier modifier) { return this; }
    }

    private static class VoidLogic extends PrimordialOmegaVoidInductionArmatureLogic {
        PrimordialOmegaEngineModuleBase module;
        GTRecipe recipe;
        VoidLogic() { super(null); }
        @Override public PrimordialOmegaEngineModuleBase getMachine() { return module; }
        @Override protected boolean checkBeforeWorking() { return true; }
        @Override protected Set<GTRecipe> lookupRecipeIterator() { return Set.of(recipe); }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(UNSAFE.allocateInstance(type));
    }

    private static void set(Class<?> owner, Object instance, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
