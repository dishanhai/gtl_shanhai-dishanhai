package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.testutil.MixinConfigs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StellarPatternStuckWarningSourceTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai");
    private static final Path MACHINE = MAIN.resolve(Path.of("common", "machine", "part",
            "RecipeTypePatternBufferPartMachine.java"));
    private static final Path WATCH = MAIN.resolve(Path.of("common", "machine", "part",
            "StellarPatternStuckWatch.java"));
    private static final Path NOTIFIER = MAIN.resolve(Path.of("common", "machine", "part",
            "StellarPatternStuckNotifier.java"));
    private static final Path CONTEXT = MAIN.resolve(Path.of("common", "machine", "part",
            "StellarPatternCraftingContext.java"));
    private static final Path COMMANDS = MAIN.resolve(Path.of("command", "DShanhaiCommands.java"));
    private static final Path CONFIG = MAIN.resolve(Path.of("config", "DShanhaiConfig.java"));
    private static final Path CRAFTING_CPU_MIXIN = MAIN.resolve(Path.of("mixin",
            "CraftingCpuLogicStellarContextMixin.java"));
    private static final Path QUANTUM_CPU = MAIN.resolve(Path.of("common", "ae2", "quantum",
            "QuantumCraftingCPULogic.java"));

    @Test
    void configExposesBoundedDelayInRecipeTypePatternBufferSection() throws IOException {
        String config = Files.readString(CONFIG);

        assertTrue(config.contains("recipeTypePatternStuckWarningSeconds"));
        assertTrue(config.contains("builder.push(\"recipe_type_pattern_buffer\")"));
        assertTrue(config.contains(".defineInRange(\"stuckWarningSeconds\", 10, 1, 3600)"));
    }

    @Test
    void successfulStellarPushSchedulesDelayedSlotInventoryCheckWithAePlayerContext() throws IOException {
        String machine = Files.readString(MACHINE);

        assertTrue(machine.contains("public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder)"));
        assertTrue(machine.contains("super.pushPattern(patternDetails, inputHolder)"));
        assertTrue(machine.contains("StellarPatternCraftingContext.currentAePlayerId()"));
        assertTrue(machine.contains("gtShanhai$clearPatternSlotRuntimeWarning(slot)"));
        assertTrue(machine.contains("HOST_RECIPE_TYPE_ID_CACHE_TICKS"),
                "推样板热路径不能每次重新收集主机配方类型");
        assertTrue(machine.contains("gtShanhai$isRuntimeTrackableSlot(slotIndex)"),
                "通配展开槽也要排卡死检测，不能只看 maxPatternCount 以内的普通样板槽");
        assertTrue(!machine.contains("slotIndex < maxPatternCount && !isRemote()"),
                "星律通配样板展开后的槽位号大于 maxPatternCount，旧门槛会直接跳过告警");
        assertTrue(machine.contains("StellarPatternStuckWatch.schedule("));
        assertTrue(machine.contains("gtShanhai$getPatternRecipeId(slot)"),
                "卡死告警要带上样板保存的配方 ID，不能只报配方类型");
        assertTrue(machine.contains("gtShanhai$getSlotItemInventory(slot)"));
        assertTrue(machine.contains("gtShanhai$getSlotFluidInventory(slot)"));
    }

    @Test
    void delayedWatchUsesServerTickLoadedChunkAndMarksOnlyStillUnconsumedSlots() throws IOException {
        String watch = Files.readString(WATCH);

        assertTrue(watch.contains("@Mod.EventBusSubscriber"));
        assertTrue(watch.contains("TickEvent.ServerTickEvent"));
        assertTrue(watch.contains("DShanhaiConfig.COMMON.recipeTypePatternStuckWarningSeconds.get()"));
        assertTrue(watch.contains("level.hasChunkAt(watch.pos)"));
        assertTrue(watch.contains("MetaMachine.getMachine(level, watch.pos) instanceof RecipeTypePatternBufferPartMachine"));
        assertTrue(watch.contains("gtShanhai$setPatternSlotStuckWarning(watch.slot, snapshot.items, snapshot.fluids)"));
        assertTrue(watch.contains("gtShanhai$rememberStuckBroadcast"));
        assertTrue(watch.contains("currentHostStatus"));
        assertTrue(watch.contains("gtShanhai$clearPatternSlotRuntimeWarning(watch.slot)"));
        assertTrue(watch.contains("stillContainsAll(snapshot.items"));
        assertTrue(watch.contains("stillContainsAll(snapshot.fluids"));
        assertTrue(watch.contains("appendRecipeLogicState(result, logicMachine)"));
        assertTrue(watch.contains("!logicMachine.isWorkingEnabled()"));
        assertTrue(watch.contains("工作=已暂停工作"));
        assertTrue(watch.contains("module.getModuleConditionError()"),
                "样板卡死必须带上原初模块的额外挂载失败原因");
        assertTrue(watch.contains("appendPlain(result, \"挂载\""));
        assertTrue(watch.contains("collectStuckReason(machine, watch.slot)"),
                "卡死原因必须带上槽位，才能写入该槽的强匹配报错");
        assertTrue(watch.contains("machine.gtShanhai$strongMatchReason(slot)"),
                "强匹配拒绝时，星律卡死告警要带上对应报错，而不是只写主机状态");
    }

    @Test
    void runtimeStuckWarningClearsWhenSlotInputsAreConsumedOrReduced() throws IOException {
        String machine = Files.readString(MACHINE);

        assertTrue(machine.contains("private final BitSet stuckRuntimeWarningSlots"),
                "卡死红框必须单独保存 runtime 快照，不能只靠最终 warning bit");
        assertTrue(machine.contains("gtShanhai$setPatternSlotStuckWarning"));
        assertTrue(machine.contains("stuckRuntimeItemSnapshots.put(slot, items)"));
        assertTrue(machine.contains("stuckRuntimeFluidSnapshots.put(slot, fluids)"));
        assertTrue(machine.contains("stuckRuntimeBroadcasts.remove(slot)"));
        assertTrue(machine.contains("private void gtShanhai$clearResolvedRuntimeWarnings()"));
        assertTrue(machine.contains("protected int[] getActiveSlots()"));
        assertTrue(machine.contains("gtShanhai$clearResolvedRuntimeWarnings();"));
        assertTrue(machine.contains("!gtShanhai$stillContainsAll(itemSnapshot, internalSlot.getItemInventory())"));
        assertTrue(machine.contains("!gtShanhai$stillContainsAll(fluidSnapshot, internalSlot.getFluidInventory())"));
        assertTrue(machine.contains("gtShanhai$refreshPatternSlotWarning(slot)"),
                "清理卡死红框后必须回到错主机配方类型判定，而不是盲目清空红框");
        assertTrue(machine.contains("gtShanhai$isRuntimeTrackableSlot(slot)"),
                "通配展开槽的卡死快照、广播和解除都要跟着内部槽位走");
        assertTrue(machine.contains("gtShanhai$clearWildcardRuntimeWarnings()"),
                "通配母槽重展开后必须清掉旧展开槽的卡死记录");
    }

    @Test
    void notifierBroadcastsNearbyAndAeRequesterWithUuidDedupeAndRunCommandCoordinate() throws IOException {
        String notifier = Files.readString(NOTIFIER);
        String machine = Files.readString(MACHINE);
        String watch = Files.readString(WATCH);

        assertTrue(notifier.contains("NEARBY_RADIUS = 500.0D"));
        assertTrue(notifier.contains("NEARBY_RADIUS_SQUARED = NEARBY_RADIUS * NEARBY_RADIUS"));
        assertTrue(notifier.contains("Set<UUID> sent"));
        assertTrue(notifier.contains("player.distanceToSqr"));
        assertTrue(notifier.contains("IPlayerRegistry.getConnected"));
        assertTrue(notifier.contains("ClickEvent.Action.RUN_COMMAND"));
        assertTrue(notifier.contains("/shanhai stellar_tp "));
        assertTrue(notifier.contains("slot + 1"));
        assertTrue(notifier.contains("displaySlot(slot, slotLabel)"),
                "通配展开槽要用展开序号，普通槽仍显示 slot + 1");
        assertTrue(machine.contains("gtShanhai$describeStuckSlot(int slot)"));
        assertTrue(watch.contains("machine.gtShanhai$describeStuckSlot(slot)"));
        assertTrue(notifier.contains("hostRecipeTypeIds"));
        assertTrue(notifier.contains("patternRecipeTypeId"));
        assertTrue(notifier.contains("patternRecipeId"));
        assertTrue(notifier.contains("样板配方ID"));
        assertTrue(watch.contains("patternRecipeId"));
        assertTrue(notifier.contains("stuckInputs"));
        assertTrue(notifier.contains("stuckOutputs"));
        assertTrue(notifier.contains("broadcastBody"));
    }

    @Test
    void lowPermissionTeleportCommandValidatesLoadedStellarTarget() throws IOException {
        String commands = Files.readString(COMMANDS);

        assertTrue(commands.contains(".then(stellarTeleportCommand())"));
        assertTrue(commands.contains("Commands.literal(\"stellar_tp\")"));
        assertTrue(commands.contains("ResourceLocationArgument.id()"));
        assertTrue(commands.contains(".requires(source -> source.hasPermission(0))"));
        assertTrue(commands.contains("level.hasChunkAt(pos)"));
        assertTrue(commands.contains("MetaMachine.getMachine(level, pos) instanceof RecipeTypePatternBufferPartMachine"));
        assertTrue(commands.contains("player.teleportTo(level"));
    }

    @Test
    void aeAndQuantumCraftingExposeExecutingJobPlayerIdAroundProviderPush() throws IOException {
        String context = Files.readString(CONTEXT);
        String mixin = Files.readString(CRAFTING_CPU_MIXIN);
        String mixinConfig = MixinConfigs.readAll();
        String quantum = Files.readString(QUANTUM_CPU);

        assertTrue(context.contains("ThreadLocal<Integer>"));
        assertTrue(context.contains("currentAePlayerId()"));
        assertTrue(mixin.contains("ExecutingCraftingJob"));
        assertTrue(mixin.contains("@Mixin(value = CraftingCpuLogic.class, priority = 1500, remap = false)"));
        assertTrue(mixin.contains("@Accessor(\"playerId\")"));
        assertTrue(mixin.contains("StellarPatternCraftingContext.push"));
        assertTrue(mixin.contains("StellarPatternCraftingContext.pop"));
        assertTrue(mixinConfig.contains("\"CraftingCpuLogicStellarContextMixin\""));
        assertTrue(quantum.contains("StellarPatternCraftingContext.push(job == null ? null : job.playerId)"));
        assertTrue(quantum.contains("finally"));
        assertTrue(quantum.contains("StellarPatternCraftingContext.pop()"));
    }
}
