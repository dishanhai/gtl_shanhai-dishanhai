package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Demand-driven samples. GT's chunk index and exact pattern cell identify ordinary blocks. */
public final class StarLifterHudTelemetry {
    public enum State {
        OFFLINE, STANDBY, INITIALIZING, PROCESSING, HIGH_LOAD, FAULT, UNFORMED,
        DISCONNECTED, WAITING_FOR_DATA, UNKNOWN, DATA_UNAVAILABLE, EXPIRED
    }

    public record Snapshot(BlockPos controller, long incarnation, State state, boolean visuals,
                           boolean resonant, long reserve, long capacity, long stored, long energyCapacity,
                           long inputPerSecond, long outputPerSecond, int progress, int duration,
                           long recipeEUt, int parallels, long batches, int parts,
                           ResourceLocation recipe, BlockPos invalidAt, Component reason) {
        public boolean formed() { return capacity >= 0; }
        public boolean running() { return state == State.PROCESSING || state == State.HIGH_LOAD; }

        public void write(FriendlyByteBuf buf) {
            buf.writeEnum(state);
            buf.writeNullable(controller, FriendlyByteBuf::writeBlockPos);
            if (controller != null) buf.writeVarLong(incarnation);
            buf.writeNullable(invalidAt, FriendlyByteBuf::writeBlockPos);
            buf.writeNullable(reason, FriendlyByteBuf::writeComponent);
            buf.writeBoolean(formed());
            if (!formed()) return;
            buf.writeBoolean(visuals);
            buf.writeBoolean(resonant);
            buf.writeVarLong(reserve);
            buf.writeVarLong(capacity);
            buf.writeVarLong(stored);
            buf.writeVarLong(energyCapacity);
            buf.writeVarLong(inputPerSecond);
            buf.writeVarLong(outputPerSecond);
            buf.writeVarLong(batches);
            buf.writeVarInt(parts);
            buf.writeNullable(recipe, FriendlyByteBuf::writeResourceLocation);
            if (recipe != null) {
                buf.writeVarInt(progress);
                buf.writeVarInt(duration);
                buf.writeVarLong(recipeEUt);
                buf.writeVarInt(parallels);
            }
        }

        public static Snapshot read(FriendlyByteBuf buf) {
            var state = buf.readEnum(State.class);
            var controller = buf.readNullable(FriendlyByteBuf::readBlockPos);
            long incarnation = controller == null ? 0 : buf.readVarLong();
            var invalidAt = buf.readNullable(FriendlyByteBuf::readBlockPos);
            var reason = buf.readNullable(FriendlyByteBuf::readComponent);
            if (!buf.readBoolean()) return unavailable(controller, incarnation, state, invalidAt, reason);
            boolean visuals = buf.readBoolean();
            boolean resonant = buf.readBoolean();
            long reserve = buf.readVarLong(), capacity = buf.readVarLong();
            long stored = buf.readVarLong(), energyCapacity = buf.readVarLong();
            long input = buf.readVarLong(), output = buf.readVarLong(), batches = buf.readVarLong();
            int parts = buf.readVarInt();
            var recipe = buf.readNullable(FriendlyByteBuf::readResourceLocation);
            return new Snapshot(controller, incarnation, state, visuals, resonant, reserve, capacity, stored, energyCapacity,
                    input, output, recipe == null ? 0 : buf.readVarInt(), recipe == null ? 0 : buf.readVarInt(),
                    recipe == null ? 0 : buf.readVarLong(), recipe == null ? 0 : buf.readVarInt(),
                    batches, parts, recipe, invalidAt, reason);
        }
    }

    private record Sample(long tick, Snapshot snapshot) {}

    private static final AtomicLong NEXT_INCARNATION = new AtomicLong();
    private static final WeakHashMap<StarLifterControllerMachine, Sample> SAMPLES = new WeakHashMap<>();

    private StarLifterHudTelemetry() {}

    public static Snapshot unavailable(State state) {
        return unavailable(null, 0, state, null, null);
    }

    private static Snapshot unavailable(BlockPos pos, long incarnation, State state, BlockPos invalidAt, Component reason) {
        return new Snapshot(pos, incarnation, state, false, false, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                null, invalidAt, reason);
    }

    public record Observation(StarLifterHudTarget role, Snapshot snapshot) {}

    public static Observation observe(ServerLevel level, LevelChunk targetChunk, BlockPos target) {
        var block = targetChunk.getBlockState(target);
        var role = StarLifterHudTarget.forBlock(block.getBlock());
        if (role == StarLifterHudTarget.CORE) {
            var snapshot = MetaMachine.getMachine(targetChunk, target) instanceof StarLifterControllerMachine machine
                    ? sample(level, machine) : unavailable(State.DATA_UNAVAILABLE);
            return new Observation(role, snapshot);
        }
        if (role != null) return new Observation(role, observeMember(level, target, block, null));
        if (!(MetaMachine.getMachine(targetChunk, target) instanceof IMultiPart part)) return null;
        var snapshot = observeMember(level, target, block, part);
        return snapshot == null ? null : new Observation(StarLifterHudTarget.HATCH, snapshot);
    }

    private static Snapshot observeMember(ServerLevel level, BlockPos target, BlockState block, IMultiPart hatch) {
        var index = MultiblockWorldSavedData.getOrCreate(level);
        var candidates = index.chunkPosMapping.get(new ChunkPos(target));
        if (candidates == null) return hatch == null ? unavailable(State.DISCONNECTED) : null;
        StarLifterControllerMachine match = null;
        BlockPos hatchOwner = null;
        boolean unloaded = false;
        for (var pattern : candidates) {
            if (!pattern.isPosInCache(target)) continue;
            BlockPos pos = pattern.controllerPos;
            if (hatch != null) {
                // getControllers() resolves each position through Level and can load its chunk.
                if (!hatch.hasController(pos)) continue;
                if (hatchOwner != null && !hatchOwner.equals(pos)) return null;
                hatchOwner = pos;
            }
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                unloaded = true;
                continue;
            }
            if (!(MetaMachine.getMachine(chunk, pos) instanceof StarLifterControllerMachine controller)) continue;
            char symbol = StarLifterStructure.symbolAt(pos, controller.getFrontFacing(), target);
            if (symbol < 'B' || (hatch != null && symbol != 'K') || !StarLifterStructure.matches(symbol, block)) continue;
            if (match != null) return unavailable(State.UNKNOWN);
            match = controller;
        }
        if (match != null) return sample(level, match);
        if (unloaded) return unavailable(State.DATA_UNAVAILABLE);
        return hatch == null ? unavailable(State.DISCONNECTED) : null;
    }

    private static Snapshot sample(ServerLevel level, StarLifterControllerMachine machine) {
        var pos = machine.getPos();
        boolean formed = machine.isStructureAvailable();
        var previous = SAMPLES.get(machine);
        long incarnation = previous == null ? NEXT_INCARNATION.incrementAndGet() : previous.snapshot.incarnation;
        if (!formed) {
            var error = machine.getMultiblockState().error;
            var failed = machine.failedPosition();
            if (failed == null && error != null && error != MultiblockState.UNINIT_ERROR
                    && error != MultiblockState.UNLOAD_ERROR) failed = machine.getMultiblockState().getPos().immutable();
            var snapshot = unavailable(pos, incarnation, error == MultiblockState.UNLOAD_ERROR || machine.isInvalid()
                    ? State.DATA_UNAVAILABLE : State.UNFORMED, failed, null);
            SAMPLES.put(machine, new Sample(level.getGameTime(), snapshot));
            return snapshot;
        }

        var logic = machine.getRecipeLogic();
        var recipe = !logic.isIdle() ? logic.getLastRecipe() : null;
        boolean visuals = machine.isShowEnabled();
        boolean depleted = machine.captureDepleted();
        long tick = level.getGameTime();
        State state;
        if (!machine.isWorkingEnabled()) state = State.OFFLINE;
        else if (depleted || logic.isWaiting()) state = State.FAULT;
        else if (logic.isWorking()) state = recipe != null && (recipe.parallels > 1
                || RecipeHelper.getInputEUt(recipe) >= GTValues.V[GTValues.UEV])
                ? State.HIGH_LOAD : State.PROCESSING;
        else if (visuals && machine.visualStartTick() >= 0 && tick - machine.visualStartTick() < StarLifterVisualState.STARTUP_TICKS) {
            state = State.INITIALIZING;
        } else state = State.STANDBY;
        if (previous != null && previous.tick == tick && previous.snapshot.state == state
                && previous.snapshot.visuals == visuals) return previous.snapshot;

        Component reason = null;
        if (state == State.FAULT) {
            var hints = logic.getFancyTooltip();
            reason = depleted ? Component.translatable(StarLifterTerminal.TEXT + "capture.depleted")
                    : hints.isEmpty() ? null : hints.get(0);
        }
        var energy = machine.getEnergyContainer();
        var origin = logic.getLastOriginRecipe();
        var links = StellarResonanceLinks.get(level.getServer()).endpoint(level, pos, true);
        var snapshot = new Snapshot(pos, incarnation, state, visuals, links.resonant(), machine.captureRemaining(),
                StellarCaptureRules.current().workTicks(), energy.getEnergyStored(), energy.getEnergyCapacity(),
                energy.getInputPerSec(), energy.getOutputPerSec(), recipe == null ? 0 : logic.getProgress(),
                recipe == null ? 0 : logic.getDuration(), recipe == null ? 0 : RecipeHelper.getInputEUt(recipe),
                recipe == null ? 0 : recipe.parallels, machine.completedBatches(), machine.getParts().size(),
                recipe == null ? null : (origin == null ? recipe : origin).getId(), null, reason);
        SAMPLES.put(machine, new Sample(tick, snapshot));
        return snapshot;
    }
}
