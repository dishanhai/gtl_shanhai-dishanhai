package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuContainmentBonus;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.core.PrimordialMyriadProliferationCore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：保留真實主機增益與仲裁，隔離世界／結構與配方庫存。 */
class GuixuLinkedEngineBonusTest {
    private static final Unsafe UNSAFE = unsafe();

    @Test void linkedEngineUsesTheFullGuixuOutputInsteadOfItsNowEmptyModuleSet() throws Exception {
        Host host = host();
        Core core = core(host, 100, new BlockPos(1, 0, 0));
        modules(host, core);
        Engine engine = engine(host);
        assertEquals(1100, engine.getRecipeOutputMultiplier());
        assertEquals(host.getRecipeOutputMultiplier(), engine.getRecipeOutputMultiplier());
        assertEquals(100, engine.getMountedOutputMultiplier());
        assertSame(host.getOutputMultiplierSourceKey(), engine.getOutputMultiplierSourceKey());
        engine.upstream = null;
        assertEquals(1, engine.getRecipeOutputMultiplier());
    }

    @Test void linkedContainmentAffectsTheEngineAndDisappearsOnUnlink() throws Exception {
        Host host = host();
        Engine engine = engine(host);
        assertEquals(host.bonus, GuixuVisionCoreMachine.containmentBonusOf(engine));
        Logic logic = allocate(Logic.class);
        logic.engine = engine;
        assertEquals(64L, logic.parallelLimit()); // 8 並行 × 2 線程 × 遏制場 4
        engine.upstream = null;
        assertEquals(GuixuContainmentBonus.NONE, GuixuVisionCoreMachine.containmentBonusOf(engine));
        assertEquals(16L, logic.parallelLimit());
    }

    @Test void linkedMatterOverrideAndInvalidationComeFromTheGuixuHost() throws Exception {
        Host host = host();
        host.matterLevel = 17;
        Engine engine = engine(host);
        assertEquals(17, engine.getHostMatterLevelOverride());
        Core core = core(host, 10, new BlockPos(1, 0, 0));
        modules(host, core);
        assertEquals(10, engine.getMountedOutputMultiplier());
        core.multiplier = 1000;
        engine.invalidateMountedOutputMultiplier();
        assertEquals(1000, engine.getMountedOutputMultiplier());
    }

    @Test void onlyTheHighestCoreIsEnabledAcrossBothEnds() throws Exception {
        Host host = host();
        Core local = core(host, 100, new BlockPos(1, 0, 0));
        Core remote = core(host, 1000, new BlockPos(200, 0, 0));
        modules(host, local, remote);
        assertSame(remote, winner(host));
        assertTrue(suppressed(local));
        assertFalse(suppressed(remote));
        assertEquals(1, local.getCurrentOutputMultiplier());
        assertEquals(1000, remote.getCurrentOutputMultiplier());
        assertEquals(11000, host.getRecipeOutputMultiplier());
        assertFalse(local.beforeWorking(null), "停用核心不得開始新批次");
        assertFalse(local.onWorking(), "已有批次停住，不可繼續加工");
    }

    @Test void tiesPreferTheLocalCoreRegardlessOfSetIterationOrder() throws Exception {
        Host host = host();
        Core local = core(host, 100, new BlockPos(1, 0, 0));
        Core remote = core(host, 100, new BlockPos(200, 0, 0));
        modules(host, remote, local);
        assertSame(local, winner(host));
        modules(host, local, remote);
        assertSame(local, winner(host));
    }

    @Test void removingTheWinnerRestoresTheOtherCoreAndWarningsClear() throws Exception {
        Host host = host();
        Core local = core(host, 100, new BlockPos(1, 0, 0));
        Core remote = core(host, 1000, new BlockPos(200, 0, 0));
        modules(host, local, remote);
        assertNotNull(warning(local));
        assertNotNull(hostWarning(host));
        modules(host, local);
        assertFalse(suppressed(local));
        assertNull(warning(local));
        assertNull(hostWarning(host));
        assertEquals(100, local.getCurrentOutputMultiplier());
    }

    @Test void unlinkAndInvalidCandidatesCannotSuppressAHealthyCore() throws Exception {
        Host host = host();
        Core local = core(host, 100, new BlockPos(1, 0, 0));
        Core remote = core(host, 1000, new BlockPos(200, 0, 0));
        modules(host, local, remote);
        remote.available = false;
        assertSame(local, winner(host));
        remote.available = true;
        host.ready = false;
        assertFalse(suppressed(local));
        assertNull(hostWarning(host));
    }

    @Test void separateGuixuLinksDoNotSuppressEachOthersCores() throws Exception {
        Host first = host(), second = host();
        Core low = core(first, 10, new BlockPos(1, 0, 0));
        Core high = core(second, 1000, new BlockPos(1, 0, 0));
        modules(first, low);
        modules(second, high);
        assertFalse(low.isMyriadSuppressed());
        assertFalse(high.isMyriadSuppressed());
        assertEquals(110, engine(first).getRecipeOutputMultiplier());
        assertEquals(11000, engine(second).getRecipeOutputMultiplier());
    }

    @Test void aModuleStillOnTheLinkedEngineUsesTheSameFullAggregate() throws Exception {
        Host host = host();
        modules(host, core(host, 100, new BlockPos(1, 0, 0)));
        Core module = allocate(Core.class);
        module.setHost(engine(host));
        assertEquals(1100, module.getHostOutputMultiplier());
    }

    @Test void anUnloadedEngineCannotUseAStoredReverseReference() throws Exception {
        UnloadedEngine engine = allocate(UnloadedEngine.class);
        initHost(engine);
        engine.setLinkedGuixuHost(host());
        assertNull(engine.getLinkedGuixuHost(), "沒有世界／區塊就不能借用增益");
        engine.setLinkedGuixuHost(null);
        assertNull(engine.getLinkedGuixuHost());
    }

    @Test void reverseLookupAndReconciliationKeepTheLoadedBidirectionalBoundary() throws Exception {
        String root = "src/main/java/com/dishanhai/gt_shanhai/common/machine/primordial/";
        String engine = java.nio.file.Files.readString(java.nio.file.Path.of(root + "PrimordialOmegaEngineMachine.java"));
        String host = java.nio.file.Files.readString(java.nio.file.Path.of(root + "guixu/GuixuVisionCoreMachine.java"));
        assertTrue(engine.contains("ownLevel.hasChunkAt(getPos())"));
        assertTrue(engine.contains("upstreamLevel.hasChunkAt(upstream.getPos())"));
        assertTrue(engine.contains("ownLevel.getServer() != upstreamLevel.getServer()"));
        assertTrue(engine.contains("upstream.isLinkedTo("));
        assertTrue(host.contains("engine.setLinkedGuixuHost(this);"));
        assertTrue(host.contains("resolved.engine().setLinkedGuixuHost(null);"));
    }

    private static Object selection(Host host) throws Exception {
        return GuixuVisionCoreMachine.class.getMethod("getMyriadSelection").invoke(host);
    }
    private static Object winner(Host host) throws Exception {
        Object result = selection(host);
        return result.getClass().getMethod("source").invoke(result);
    }
    private static boolean suppressed(Core core) throws Exception {
        return (boolean) PrimordialMyriadProliferationCore.class.getMethod("isMyriadSuppressed").invoke(core);
    }
    private static Object warning(Core core) throws Exception {
        return PrimordialMyriadProliferationCore.class.getMethod("getMyriadConflictWarning").invoke(core);
    }
    private static Object hostWarning(Host host) throws Exception {
        return GuixuVisionCoreMachine.class.getMethod("getMyriadConflictWarning").invoke(host);
    }

    private static Host host() throws Exception {
        Host host = allocate(Host.class);
        host.ready = true;
        host.bonus = new GuixuContainmentBonus(4, 0.5D, 5, 0.5D, 2, 0D, false);
        initHost(host);
        return host;
    }
    private static Engine engine(Host host) throws Exception {
        Engine engine = allocate(Engine.class);
        engine.upstream = host;
        initHost(engine);
        return engine;
    }
    private static void initHost(PrimordialOmegaEngineMachine engine) throws Exception {
        set(engine, "outputMultiplierCache", new PrimordialOmegaEngineMachine.OutputMultiplierCache());
        set(engine, "moduleSnapshot", Set.of());
    }
    private static Core core(Host host, int multiplier, BlockPos pos) throws Exception {
        Core core = allocate(Core.class);
        core.setHost(host);
        core.multiplier = multiplier;
        core.pos = pos;
        core.available = true;
        return core;
    }
    private static void modules(Host host, Core... cores) throws Exception {
        set(host, "moduleSnapshot", new java.util.LinkedHashSet<>(List.of(cores)));
        host.invalidateMountedOutputMultiplier();
    }
    private static void set(Object instance, String name, Object value) throws Exception {
        Field field = PrimordialOmegaEngineMachine.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static class Host extends GuixuVisionCoreMachine {
        boolean ready;
        int matterLevel;
        GuixuContainmentBonus bonus;
        Host() { super(null); }
        @Override public boolean isFormed() { return true; }
        @Override public Level getLevel() { return null; }
        @Override public boolean isEngineLinkReady() { return ready; }
        @Override public int getHostMatterLevelOverride() { return matterLevel; }
        @Override public int moduleSlotBonus() { return 0; }
        @Override public GuixuContainmentBonus getContainmentBonus() { return bonus; }
        @Override public long getOffsetTimer() { return 1L; }
        @Override public BlockPos getPos() { return BlockPos.ZERO; }
        @Override public BlockPos[] getModuleScanPositions() { return new BlockPos[] {new BlockPos(1, 0, 0)}; }
        @Override public Object getOutputMultiplierSourceKey() { return this; }
    }
    private static class Engine extends PrimordialOmegaEngineMachine {
        Host upstream;
        Engine() { super(null); }
        @Override public GuixuVisionCoreMachine getLinkedGuixuHost() { return upstream; }
        @Override public long getOffsetTimer() { return 1L; }
        @Override public boolean isFormed() { return true; }
        @Override public long getRecipeLogicMaxParallel() { return 8L; }
    }
    private static class UnloadedEngine extends PrimordialOmegaEngineMachine {
        UnloadedEngine() { super(null); }
        @Override public Level getLevel() { return null; }
    }
    private static class Core extends PrimordialMyriadProliferationCore {
        int multiplier;
        BlockPos pos;
        boolean available;
        Core() { super(null); }
        @Override public int getCandidateOutputMultiplier() { return multiplier; }
        @Override public boolean isMyriadSelectionCandidate() { return available; }
        @Override public Level getLevel() { return null; }
        @Override public BlockPos getPos() { return pos; }
    }
    private static class Logic extends PrimordialOmegaEngineRecipeLogic {
        Engine engine;
        Logic() { super(null); }
        @Override public SelectableRecipeTypeSetMachine getMachine() { return engine; }
        @Override protected long getLogicThreadMultiplier() { return 2L; }
        long parallelLimit() { return getTotalParallelLimit(); }
    }
    private static <T> T allocate(Class<T> type) throws Exception { return type.cast(UNSAFE.allocateInstance(type)); }
    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
}
