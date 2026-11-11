package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.api.networking.IGrid;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import appeng.crafting.execution.CraftingSubmitResult;
import com.dishanhai.gt_shanhai.mixin.QuantumCraftingServiceMixin;
import net.minecraft.core.BlockPos;
import org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.MissingCraftingPlan;
import org.gtlcore.gtlcore.integration.ae2.graph.AeGraphPlan;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantumGtlCoreCompatibilitySourceTest {

    private static final Path SOURCE = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    @Test
    void switchingToQuantumClearsBothUpstreamCpuBindingsBeforeInstallingItsListener() throws IOException {
        String menu = source("mixin/QuantumCraftingCPUMenuMixin.java");
        String status = source("mixin/QuantumCraftingStatusMenuMixin.java");

        assertTrue(menu.contains("@WrapMethod(method = \"setCPU\")"));
        int upstreamClear = menu.indexOf("original.call((ICraftingCPU) null)");
        int quantumBind = menu.indexOf("quantumCpu.craftingLogic.addListener(cpuChangeListener)");
        assertTrue(upstreamClear >= 0 && quantumBind > upstreamClear);
        assertTrue(status.contains("@Inject(method = \"setCPU\", at = @At(\"TAIL\"))"));
        assertTrue(status.contains("accessor.gtShanhai$setSelectedCpuSerialRaw(-1)"));
        assertFalse(status.contains("findBusyQuantumCpu"));
    }

    @Test
    void cancellationUsesTheBoundCpuWithoutFallingBackToAnotherJob() throws IOException {
        String menu = source("mixin/QuantumCraftingCPUMenuMixin.java");

        assertTrue(menu.contains("@WrapMethod(method = \"cancelCrafting\")"));
        assertTrue(menu.contains("sendClientAction(\"gtShanhaiCancelSelectedCpu\", accessor.gtShanhai$getSelectedCpuSerialRaw())"));
        assertTrue(menu.contains("serial != accessor.gtShanhai$getSelectedCpuSerialRaw()"));
        assertTrue(menu.contains("gtShanhai$resolveExplicitSelectedQuantumCpu()"));
        assertFalse(menu.contains("gtShanhai$resolveSelectedQuantumCpu()"));
        assertFalse(menu.contains("gtShanhai$findBusyQuantumCpu"));
    }

    @Test
    void graphPlansReachQuantumClusterEligibilityChecks() throws ReflectiveOperationException {
        IGrid grid = gridIdentity();
        QuantumCraftingCPUCluster offline = new QuantumCraftingCPUCluster(BlockPos.ZERO, BlockPos.ZERO);
        AeGraphPlan plan = graphPlan(1, BigInteger.ONE);

        assertSame(CraftingSubmitResult.CPU_OFFLINE, offline.submitJob(grid, plan, null, null));
        assertSame(CraftingSubmitResult.CPU_OFFLINE,
                offline.submitJob(grid, new MissingCraftingPlan(plan), null, null));
    }

    @Test
    void graphCpuUsesExactCapacityBeforeAccessingStorage() throws ReflectiveOperationException {
        IGrid grid = gridIdentity();
        QuantumCraftingCPUCluster cluster = new QuantumCraftingCPUCluster(BlockPos.ZERO, BlockPos.ZERO) {
            @Override
            public IGrid getGrid() {
                return grid;
            }

            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public long getAvailableStorage() {
                return Long.MAX_VALUE;
            }
        };
        AeGraphPlan plan = graphPlan(Long.MAX_VALUE, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        MissingCraftingPlan wrapped = new MissingCraftingPlan(plan);

        assertFalse(cluster.canFitPlan(plan));
        assertFalse(cluster.canFitPlan(wrapped));
        assertSame(CraftingSubmitResult.CPU_TOO_SMALL, cluster.submitJob(grid, plan, null, null));
        assertSame(CraftingSubmitResult.CPU_TOO_SMALL, cluster.submitJob(grid, wrapped, null, null));
    }

    @Test
    void oldGridCannotSubmitToAClusterThatHasMoved() {
        IGrid oldGrid = gridIdentity();
        IGrid newGrid = gridIdentity();
        class MovingCluster extends QuantumCraftingCPUCluster {
            private IGrid owner = oldGrid;

            MovingCluster() {
                super(BlockPos.ZERO, BlockPos.ZERO);
            }

            @Override
            public IGrid getGrid() {
                return owner;
            }

            @Override
            public boolean isActive() {
                return true;
            }
        }
        MovingCluster cluster = new MovingCluster();
        assertTrue(cluster.belongsToGrid(oldGrid));

        cluster.owner = newGrid;
        assertFalse(cluster.belongsToGrid(oldGrid));
        assertTrue(cluster.belongsToGrid(newGrid));
        assertSame(CraftingSubmitResult.CPU_OFFLINE, cluster.submitJob(oldGrid, treePlan(), null, null));
    }

    @Test
    void graphOrdersUseTheGtlCoreControllerForTheirEntireLifecycle() throws IOException {
        String service = source("mixin/QuantumCraftingServiceMixin.java");
        String cluster = source("common/ae2/quantum/QuantumCraftingCPUCluster.java");
        String logic = source("common/ae2/quantum/QuantumCraftingCPULogic.java");
        String host = source("common/ae2/quantum/QuantumGraphCpuHost.java");

        assertFalse(service.contains("!QuantumCraftingCPUCluster.supportsPlan(job)"));
        assertTrue(service.contains("cpu.isCapacityView()"));
        assertTrue(service.contains("if (!cluster.canFitPlan(job)) continue"));
        assertTrue(host.contains("new GraphCpuController(this)"));
        int graphSubmit = logic.indexOf("graph.submit(grid, plan, src, requester)");
        assertTrue(graphSubmit >= 0 && graphSubmit < logic.indexOf("CraftingCpuHelper.tryExtractInitialItems"));
        assertTrue(logic.contains("graph.tick(energyService, craftingService)"));
        assertTrue(logic.contains("graph.insert(what, amount, type)"));
        assertTrue(logic.contains("graph.read(data)"));
        assertTrue(logic.contains("graph.write(data)"));
        assertTrue(logic.contains("graph.handoffCancelledInventory()"));
        assertTrue(cluster.contains("retainJobOrRefund(id, cpu)"));
        assertTrue(cluster.contains("if (!cpu.craftingLogic.hasJob())"));
    }

    @Test
    void aggregationWrapsTheGtlCoreResultAndSchedulingVerifiesGridIdentity() throws IOException {
        String service = source("mixin/QuantumCraftingServiceMixin.java");
        String cluster = source("common/ae2/quantum/QuantumCraftingCPUCluster.java");

        assertTrue(service.contains("@WrapMethod(method = \"getCpus\")"));
        assertTrue(service.contains("builder.addAll(original.call())"));
        assertTrue(service.contains("@WrapMethod(method = \"getRequestedAmount\")"));
        assertTrue(service.contains("long requested = original.call(what)"));
        assertTrue(service.contains("if (!cluster.belongsToGrid(grid)) continue;"));
        assertTrue(cluster.contains("grid != null && !isDestroyed() && getGrid() == grid"));
    }

    @Test
    void waitingItemsAreCollectedBeforeAe2NotifiesCraftingWatchers() throws IOException {
        String service = source("mixin/QuantumCraftingServiceMixin.java");
        assertTrue(service.contains("target = \"Lappeng/me/service/CraftingService;lastProcessedCraftingLogicChangeTick:J\""));
        assertTrue(service.contains("target = \"Lappeng/me/service/CraftingService;interests:Lcom/google/common/collect/Multimap;\""));
        assertTrue(service.contains("currentlyCrafting.addAll(gtShanhai$quantumWaiting)"));
        assertTrue(service.contains("name = \"previouslyCrafting\""));
        assertTrue(service.contains("return gtShanhai$lastNotifiedWaiting"));
        assertTrue(service.contains("gtShanhai$lastNotifiedWaiting = Set.copyOf(currentlyCrafting)"));
        assertFalse(service.contains("getAllWaitingFor(currentlyCrafting)"));
    }

    @Test
    void craftingServiceConstructorCallbackInitializesBothWatcherSnapshots() throws Exception {
        String service = source("mixin/QuantumCraftingServiceMixin.java");
        assertTrue(service.contains("@Inject(method = \"<init>\", at = @At(\"RETURN\"))"));
        var mixin = new QuantumCraftingServiceMixin() {
            @Override
            public void addLink(CraftingLink link) {}
        };
        var initialize = QuantumCraftingServiceMixin.class.getDeclaredMethod(
                "gtShanhai$initializeQuantumState", CallbackInfo.class);
        initialize.setAccessible(true);
        initialize.invoke(mixin, new CallbackInfo("<init>", false));

        for (String name : Set.of("gtShanhai$quantumWaiting", "gtShanhai$lastNotifiedWaiting")) {
            Field snapshot = QuantumCraftingServiceMixin.class.getDeclaredField(name);
            snapshot.setAccessible(true);
            assertEquals(Set.of(), snapshot.get(mixin));
        }
    }

    private static String source(String path) throws IOException {
        return Files.readString(SOURCE.resolve(path));
    }

    private static CraftingPlan treePlan() {
        return new CraftingPlan(null, 1, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
    }

    private static AeGraphPlan graphPlan(long bytes, BigInteger exactBytes) throws ReflectiveOperationException {
        // The controller checks capacity before consulting the graph, so no Minecraft key is needed.
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        AeGraphPlan plan = (AeGraphPlan) ((Unsafe) field.get(null)).allocateInstance(AeGraphPlan.class);
        Field capped = AeGraphPlan.class.getDeclaredField("bytes");
        capped.setAccessible(true);
        capped.setLong(plan, bytes);
        Field exact = AeGraphPlan.class.getDeclaredField("exactBytes");
        exact.setAccessible(true);
        exact.set(plan, exactBytes);
        return plan;
    }

    private static IGrid gridIdentity() {
        return (IGrid) Proxy.newProxyInstance(IGrid.class.getClassLoader(), new Class<?>[] { IGrid.class },
                (proxy, method, args) -> { throw new AssertionError(method); });
    }
}
