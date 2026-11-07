package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuContainmentBonus;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.dishanhai.gt_shanhai.common.machine.thread.RecipeThreadPriority;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gtladd.gtladditions.common.data.ParallelData;
import com.gtladd.gtladditions.api.recipe.IWirelessGTRecipe;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import com.gtladd.gtladditions.api.machine.IWirelessElectricMultiblockMachine;
import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：隔離世界、候選來源與 Mixin 才能用的庫存探測，保留真實分配與線程計算。 */
class PrimordialParallelSupplyTest {
    private static final Unsafe UNSAFE = unsafe();

    @Test void annihilationCopyRetainsWirelessChargeAndNativeHandlerStillDebitsIt() throws Exception {
        Module module = module(0, 0);
        module.annihilation = true;
        GTRecipe original = new WirelessGTRecipe(module.logic.recipes.iterator().next(), BigInteger.valueOf(-37));
        original.parallels = 16;
        original.ocTier = 3;
        original.data.putString("source", "kept");
        var method = PrimordialModuleRecipeLogic.class.getDeclaredMethod("applyExtraMountRecipeEffects", GTRecipe.class);
        method.setAccessible(true);
        GTRecipe changed = (GTRecipe) method.invoke(module.logic, original);
        assertInstanceOf(IWirelessGTRecipe.class, changed, "掛載效果複製不得遺失無線扣電欄位");
        assertEquals(BigInteger.valueOf(-37), ((IWirelessGTRecipe) changed).getWirelessEuTickInputs());
        assertEquals(2, changed.duration);
        assertEquals(20, original.duration);
        assertEquals(16, changed.parallels);
        assertEquals(3, changed.ocTier);
        assertTrue(module.logic.handleWirelessTickInput((IWirelessGTRecipe) changed));
        assertEquals(BigInteger.valueOf(963), module.energy);
        changed.data.putString("source", "changed");
        assertEquals("kept", original.data.getString("source"));
    }

    @Test void defaultCapacityRemains64WithoutAnActiveBatch() throws Exception {
        Module module = module(0, 0L);
        assertEquals(64L, available(module));
        assertEquals(1L, module.getRecipeLogicThreadMultiplier());
        List<Component> lines = new ArrayList<>();
        module.addParallelDisplay(lines);
        assertTrue(lines.getFirst().getString().contains("64"));
    }

    @Test void bothHighMatterModulesSupplyUnlimitedThreadsWithoutAnOverdriver() throws Exception {
        for (int level : List.of(16, 17)) {
            Module module = module(level, 0L);
            assertFalse(module.hasParallelOverdriver());
            assertEquals(Long.MAX_VALUE, module.getRecipeLogicThreadMultiplier(), "物質等級 " + level);
            assertEquals(Integer.MAX_VALUE, module.getAdditionalThread());
            assertEquals(Long.MAX_VALUE, available(module));
        }
        assertEquals(1L, module(15, 0L).getRecipeLogicThreadMultiplier());
        assertEquals(7L, module(15, 7L).getRecipeLogicThreadMultiplier());
    }

    @Test void inheritedHostLevelSuppliesThreadsAndRemovalRestoresTheLocalSupply() throws Exception {
        Module module = module(0, 3L);
        module.hostLevel = 16;
        assertEquals(Long.MAX_VALUE, module.getRecipeLogicThreadMultiplier());
        module.hostLevel = 0;
        assertEquals(3L, module.getRecipeLogicThreadMultiplier());
        assertEquals(192L, available(module));
    }

    @Test void capacityAndAllocationBothIncludeTheLiveContainmentBonus() throws Exception {
        Module module = module(0, 2L);
        Host host = (Host) UNSAFE.allocateInstance(Host.class);
        host.formed = true;
        host.bonus = new GuixuContainmentBonus(4L, 0.5D, 5, 0.5D, 2, 0D, false);
        module.host = host;
        assertEquals(512L, available(module));
        assertArrayEquals(new long[] {256L, 256L}, module.logic.calculateParallels().getParallels());
        host.formed = false;
        assertEquals(128L, available(module));
    }

    @Test void highMatterModulesShareOneLongMaxBudgetAcrossTheBatch() throws Exception {
        for (int level : List.of(16, 17)) {
            Module module = module(level, 0L);
            assertEquals(Long.MAX_VALUE, module.getRecipeLogicThreadMultiplier());
            module.parallel = level == 17 ? Long.MAX_VALUE : 6_917_529_027_641_081_855L;
            ParallelData allocation = module.logic.calculateParallels();
            assertNotNull(allocation);
            assertEquals(3, allocation.getOriginRecipeList().size());
            assertTrue(allocation.getShouldProcess(), "必須保留最終實際扣料路徑");
            long share = Long.MAX_VALUE / 3L;
            assertArrayEquals(new long[] {share + 1L, share, share}, allocation.getParallels());
            assertEquals(3L, module.logic.gtShanhai$getLastAllocatedParallel());
        }
    }

    @Test void ordinaryCrossTypeThreadsStillLimitTheBatchAndRecoverAfterSupplyChanges() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/PrimordialModuleRecipeLogic.java"));
        assertTrue(source.contains("long parallel = getMaxParallel(recipe, recipeLimit);"),
                "分配必須復用公開的可行並行查詢；JUnit 不載入 GTLCore ContentModifier Mixin");
        Module module = module(15, 0L);
        module.logic.recipeLimit = 1L;
        assertEquals(1, module.logic.calculateParallels().getOriginRecipeList().size());
        module.boost = 2L;
        assertEquals(2, module.logic.calculateParallels().getOriginRecipeList().size());
        module.parallelLevel = 17;
        assertEquals(3, module.logic.calculateParallels().getOriginRecipeList().size());
        module.parallelLevel = 15;
        module.boost = 0L;
        assertEquals(1, module.logic.calculateParallels().getOriginRecipeList().size());
    }

    @Test void blockedCandidatesDoNotConsumeThreadsAndNextBatchRecomputesAvailability() throws Exception {
        Module module = module(17, 0L);
        module.logic.recipeLimit = 1L;
        GTRecipe first = module.logic.recipes.iterator().next();
        module.logic.blocked = first;
        ParallelData batch = module.logic.calculateParallels();
        assertEquals(2, batch.getOriginRecipeList().size());
        assertFalse(batch.getOriginRecipeList().contains(first));
        module.logic.blocked = null;
        module.logic.recipeLimit = 7L;
        batch = module.logic.calculateParallels();
        assertEquals(3, batch.getOriginRecipeList().size());
        assertArrayEquals(new long[] {7L, 7L, 7L}, batch.getParallels());
    }

    @Test void aThreadBatchSplitsItsReservationAcrossAllIncludedRecipes() throws Exception {
        Module module = module(17, 0L);
        module.logic.recipeLimit = 30L;
        setThreadAllowance(module.logic, 64L);
        ParallelData batch = module.logic.calculateParallels();
        assertArrayEquals(new long[] {22L, 21L, 21L}, batch.getParallels());
        assertEquals(64L, java.util.Arrays.stream(batch.getParallels()).sum());
    }

    @Test void crossTypeBatchKeepsTheOtherRunnableRecipesWithThreadPriorityPresent() throws Exception {
        Module module = module(17, 0L);
        module.logic.limitedRecipe = module.logic.recipes.iterator().next();
        module.logic.limitedParallel = 4L;
        setThreadAllowance(module.logic, 64L);
        Field priority = PrimordialThreadedRecipeLogic.class.getDeclaredField("priority");
        priority.setAccessible(true);
        priority.set(module.logic, new RecipeThreadPriority<GTRecipe>(recipe -> 20L, recipe -> recipe.duration));

        List<GTRecipe> candidates = List.copyOf(module.logic.recipes);
        ParallelData batch = module.logic.calculateParallels();
        assertEquals(candidates, batch.getOriginRecipeList());
        assertArrayEquals(new long[] {4L, 30L, 30L}, batch.getParallels());
        assertTrue(batch.getShouldProcess());

        ParallelData rotated = module.logic.calculateParallels();
        assertEquals(List.of(candidates.get(1), candidates.get(2), candidates.getFirst()),
                rotated.getOriginRecipeList());
        assertArrayEquals(new long[] {39L, 21L, 4L}, rotated.getParallels());
    }

    @Test void crossTypeBatchesRotateWhenOnlyOneSourceFits() throws Exception {
        Module module = module(15, 0L);
        List<GTRecipe> candidates = List.copyOf(module.logic.recipes);

        for (GTRecipe candidate : candidates) {
            assertEquals(List.of(candidate), module.logic.calculateParallels().getOriginRecipeList());
        }
    }

    @Test void overdriverPlansOneIndependentFullPoolPerThread() throws Exception {
        Module module = module(0, 0L);
        module.overdriver = true;
        ParallelData allocation = module.logic.calculateParallels();
        assertEquals(1, allocation.getOriginRecipeList().size());
        assertArrayEquals(new long[] {Long.MAX_VALUE}, allocation.getParallels());
        assertTrue(allocation.getShouldProcess());
    }

    @Test void overdriverStackKeepsNinetySixTotalSlotsEvenWithAnOldRegistration() {
        String id = "dishanhai:universal_parallel_overdriver";
        PrimordialOmegaEngineModuleBase.registerThreadBoostItem(id, Long.MAX_VALUE);
        assertEquals(0L, PrimordialOmegaEngineModuleBase.getThreadBoostValue(id, 0));
        for (int count : new int[] {1, 2, 64}) {
            long boost = PrimordialOmegaEngineModuleBase.getThreadBoostValue(id, count);
            assertEquals(96L, PrimordialThreadScheduler.threadCount(boost));
        }
    }

    private static void setThreadAllowance(Logic logic, long limit) throws ReflectiveOperationException {
        Field admission = PrimordialThreadedRecipeLogic.class.getDeclaredField("admission");
        admission.setAccessible(true);
        admission.setBoolean(logic, true);
        Field allowance = PrimordialThreadedRecipeLogic.class.getDeclaredField("allowance");
        allowance.setAccessible(true);
        allowance.setLong(logic, limit);
    }

    @Test void uiUsesIntegerThresholdForParallelAndThreadInfinity() throws Exception {
        Module module = module(0, Integer.MAX_VALUE);
        module.parallel = 1L;
        List<Component> lines = new ArrayList<>();
        module.addParallelDisplay(lines);
        assertFalse(lines.getFirst().getString().contains("无限"), "並行必須嚴格超過 int 上限");
        assertTrue(lines.stream().anyMatch(line -> line.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                && text.getKey().equals("gt_shanhai.recipe_threads.cross_recipe_multiplier")
                && ((Component) text.getArgs()[0]).getString().equals("无限")));
        module.parallel = 2L;
        lines.clear();
        module.addParallelDisplay(lines);
        assertTrue(lines.getFirst().getString().contains("无限"));
    }

    private static long available(Module module) throws Exception {
        return (long) PrimordialOmegaEngineModuleBase.class.getMethod("getAvailableRecipeParallel").invoke(module);
    }

    private static Module module(int parallelLevel, long boost) throws Exception {
        Module module = (Module) UNSAFE.allocateInstance(Module.class);
        module.energy = BigInteger.valueOf(1000);
        module.parallel = 64L;
        module.parallelLevel = parallelLevel;
        module.boost = boost;
        Logic logic = (Logic) UNSAFE.allocateInstance(Logic.class);
        module.logic = logic;
        logic.module = module;
        for (String name : List.of("matchableScaledRecipeCache", "parallelHints")) {
            Field field = PrimordialModuleRecipeLogic.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(logic, new IdentityHashMap<>());
        }
        logic.recipes = new LinkedHashSet<>();
        for (int i = 0; i < 3; i++) {
            GTRecipeType type = (GTRecipeType) UNSAFE.allocateInstance(GTRecipeType.class);
            logic.recipes.add(new Recipe(type, "type_" + i));
        }
        return module;
    }

    private static class Module extends PrimordialOmegaEngineModuleBase {
        long parallel, boost;
        int parallelLevel, hostLevel;
        PrimordialOmegaEngineMachine host;
        Logic logic;
        boolean annihilation, overdriver;
        BigInteger energy = BigInteger.valueOf(1000);
        Module() { super(null); }
        @Override public long getCurrentParallel() { return parallel; }
        @Override public long getThreadBoost() { return boost; }
        @Override public int getModuleLevel() { return hostLevel; }
        @Override public int getHostMatterLevelOverride() { return hostLevel; }
        @Override protected int getParallelModuleLevel() { return parallelLevel; }
        @Override public boolean hasParallelOverdriver() { return overdriver; }
        @Override public boolean hasDarkEnergyMultiplierMounted() { return false; }
        @Override public boolean hasAnnihilationCoreMounted() { return annihilation; }
        @Override public double getExtraMountDurationMultiplier() { return annihilation ? 0.1 : 1; }
        @Override public double getAnnihilationOutputLossChance() { return 0; }
        @Override public IWirelessNetworkEnergyHandler getWirelessNetworkEnergyHandler() {
            return (IWirelessNetworkEnergyHandler) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IWirelessNetworkEnergyHandler.class}, (proxy, method, args) -> {
                        if (method.getName().equals("isOnline")) return true;
                        if (method.getName().equals("consumeEnergy")) {
                            energy = energy.add((BigInteger) args[0]);
                            return true;
                        }
                        return null;
                    });
        }
        @Override public boolean hasHyperstableBlackHoleSeedMounted() { return false; }
        @Override public int getHostOutputMultiplier() { return 1; }
        @Override public PrimordialOmegaEngineMachine getHost() { return host; }
        @Override public Logic getRecipeLogic() { return logic; }
        @Override public boolean isCrossTypeParallelActive() { return true; }
        @Override public boolean isRecipeOutputAlwaysMatch(GTRecipe recipe) { return true; }
    }

    private static class Logic extends PrimordialModuleRecipeLogic {
        Module module;
        Set<GTRecipe> recipes;
        GTRecipe blocked, limitedRecipe;
        long recipeLimit, limitedParallel;
        Logic() { super(null); }
        @Override public SelectableRecipeTypeSetMachine getMachine() { return module; }
        @Override public IWirelessElectricMultiblockMachine getWirelessMachine() { return module; }
        @Override protected Set<GTRecipe> lookupRecipeIterator() { return recipes; }
        @Override public long getMaxParallel(GTRecipe recipe, long limit) {
            if (recipe == limitedRecipe) return Math.min(limitedParallel, limit);
            return recipe == blocked ? 0L : recipeLimit > 0L ? Math.min(recipeLimit, limit) : limit;
        }
    }

    private static class Host extends GuixuVisionCoreMachine {
        boolean formed;
        GuixuContainmentBonus bonus;
        Host() { super(null); }
        @Override public boolean isFormed() { return formed; }
        @Override public GuixuContainmentBonus getContainmentBonus() { return bonus; }
    }

    public static class Recipe extends GTRecipe {
        Recipe(GTRecipeType type, String id) {
            super(type, new ResourceLocation("gt_shanhai", id), new HashMap<>(), new HashMap<>(),
                    new HashMap<>(), new HashMap<>(), Map.of(), Map.of(), Map.of(), Map.of(),
                    List.of(), List.of(), new CompoundTag(), 20, false);
        }
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
