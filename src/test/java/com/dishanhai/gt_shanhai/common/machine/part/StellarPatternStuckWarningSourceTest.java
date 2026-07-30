package com.dishanhai.gt_shanhai.common.machine.part;

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
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
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
        assertTrue(machine.contains("StellarPatternStuckWatch.schedule("));
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
        assertTrue(watch.contains("gtShanhai$setPatternSlotWarning(watch.slot, true)"));
        assertTrue(watch.contains("stillContainsAll(snapshot.items"));
        assertTrue(watch.contains("stillContainsAll(snapshot.fluids"));
        assertTrue(watch.contains("appendRecipeLogicState(result, logicMachine)"));
        assertTrue(watch.contains("!logicMachine.isWorkingEnabled()"));
        assertTrue(watch.contains("工作=已暂停工作"));
    }

    @Test
    void notifierBroadcastsNearbyAndAeRequesterWithUuidDedupeAndRunCommandCoordinate() throws IOException {
        String notifier = Files.readString(NOTIFIER);

        assertTrue(notifier.contains("NEARBY_RADIUS = 500.0D"));
        assertTrue(notifier.contains("NEARBY_RADIUS_SQUARED = NEARBY_RADIUS * NEARBY_RADIUS"));
        assertTrue(notifier.contains("Set<UUID> sent"));
        assertTrue(notifier.contains("player.distanceToSqr"));
        assertTrue(notifier.contains("IPlayerRegistry.getConnected"));
        assertTrue(notifier.contains("ClickEvent.Action.RUN_COMMAND"));
        assertTrue(notifier.contains("/shanhai stellar_tp "));
        assertTrue(notifier.contains("slot + 1"));
        assertTrue(notifier.contains("hostRecipeTypeIds"));
        assertTrue(notifier.contains("patternRecipeTypeId"));
        assertTrue(notifier.contains("stuckInputs"));
        assertTrue(notifier.contains("stuckOutputs"));
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
        String mixinConfig = Files.readString(MIXIN_CONFIG);
        String quantum = Files.readString(QUANTUM_CPU);

        assertTrue(context.contains("ThreadLocal<Integer>"));
        assertTrue(context.contains("currentAePlayerId()"));
        assertTrue(mixin.contains("ExecutingCraftingJob"));
        assertTrue(mixin.contains("@Accessor(\"playerId\")"));
        assertTrue(mixin.contains("StellarPatternCraftingContext.push"));
        assertTrue(mixin.contains("StellarPatternCraftingContext.pop"));
        assertTrue(mixinConfig.contains("\"CraftingCpuLogicStellarContextMixin\""));
        assertTrue(quantum.contains("StellarPatternCraftingContext.push(job == null ? null : job.playerId)"));
        assertTrue(quantum.contains("finally"));
        assertTrue(quantum.contains("StellarPatternCraftingContext.pop()"));
    }
}
