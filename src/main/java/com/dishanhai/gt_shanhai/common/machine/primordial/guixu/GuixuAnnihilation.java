package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.network.GuixuAnnihilationPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/** The matched block footprint survives a failed pattern check and a save/reload. */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID)
public final class GuixuAnnihilation {
    private static final ThreadLocal<BlockPos> REMOVING = new ThreadLocal<>();
    private static final ResourceLocation BRONZE = new ResourceLocation("gtceu", "bronze_machine_casing");
    private static final ResourceLocation STEAM = new ResourceLocation("gtceu", "industrial_steam_casing");
    private final Long2ObjectOpenHashMap<Block> blocks = new Long2ObjectOpenHashMap<>();

    boolean isEmpty() { return blocks.isEmpty(); }

    boolean capture(MultiblockControllerMachine machine) {
        var lock = machine.getPatternLock();
        lock.lock();
        try {
            var state = machine.getMultiblockState();
            if (!machine.isFormed() || state.hasError() || !state.isPosInCache(machine.getPos())) return false;
            var level = machine.getLevel();
            BlockPos controller = machine.getPos();
            HostAxes axes = machine instanceof GuixuVisionCoreMachine
                    ? HostAxes.of(machine.getFrontFacing(), machine.getUpwardsFacing(), machine.isFlipped()) : null;
            for (long packed : state.cache.toLongArray()) {
                BlockPos pos = BlockPos.of(packed);
                if (!level.hasChunkAt(pos)) continue;
                var blockState = level.getBlockState(pos);
                if (!blockState.isAir() && (axes == null
                        || acceptsHostBlock(axes.symbol(pos.subtract(controller)), blockState.getBlock()))) {
                    blocks.put(packed, blockState.getBlock());
                }
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    private static boolean acceptsHostBlock(char symbol, Block block) {
        return switch (symbol) {
            case ' ', GuixuVisionCoreStructure.MODULE_CAVITY_FILLED -> false;
            case GuixuVisionCoreStructure.CAVITY_MOUTH_FILLED -> block == GuixuVisionCoreStructure.appearanceBlock();
            case GuixuVisionCoreStructure.MODULE_SHELL_BRONZE -> BRONZE.equals(ForgeRegistries.BLOCKS.getKey(block));
            case GuixuVisionCoreStructure.MODULE_SHELL_STEAM -> STEAM.equals(ForgeRegistries.BLOCKS.getKey(block));
            default -> true;
        };
    }

    record HostAxes(Direction aisle, Direction row, Direction col) {
        static HostAxes of(Direction facing, Direction upwards, boolean flipped) {
            return new HostAxes(RelativeDirection.FRONT.getRelativeFacing(facing, upwards, flipped),
                    RelativeDirection.DOWN.getRelativeFacing(facing, upwards, flipped),
                    RelativeDirection.RIGHT.getRelativeFacing(facing, upwards, flipped));
        }

        char symbol(BlockPos relative) {
            int x = project(relative, aisle) + GuixuHostGeometry.CONTROLLER_X - GuixuHostGeometry.TEMPLATE_MIN_X;
            int z = project(relative, col) + GuixuHostGeometry.CONTROLLER_Z - GuixuHostGeometry.TEMPLATE_MIN_Z;
            int y = project(relative, row) + GuixuHostGeometry.TEMPLATE_MIN_Y + GuixuHostGeometry.TEMPLATE_SIZE_Y - 1
                    - GuixuHostGeometry.CONTROLLER_Y;
            return Template.SLICES[z][x].charAt(y);
        }

        private static int project(BlockPos relative, Direction axis) {
            return relative.getX() * axis.getStepX() + relative.getY() * axis.getStepY() + relative.getZ() * axis.getStepZ();
        }
    }

    private static final class Template {
        private static final String[][] SLICES = StructureResourceLoader.INSTANCE.loadShapeInfoSlices(
                GuixuVisionCoreStructure.RESOURCE_PATH, GuixuVisionCoreStructure.RESOURCE_ID,
                (col, aisle, row, symbol) -> symbol);
    }

    CompoundTag save() {
        Map<Block, LongArrayList> byBlock = new HashMap<>();
        blocks.forEach((pos, block) -> byBlock.computeIfAbsent(block, ignored -> new LongArrayList()).add(pos.longValue()));
        var tag = new CompoundTag();
        byBlock.forEach((block, positions) -> tag.putLongArray(ForgeRegistries.BLOCKS.getKey(block).toString(),
                positions.toLongArray()));
        return tag;
    }

    static GuixuAnnihilation load(CompoundTag tag) {
        var footprint = new GuixuAnnihilation();
        for (String name : tag.getAllKeys()) {
            var id = ResourceLocation.tryParse(name);
            if (id == null || !ForgeRegistries.BLOCKS.containsKey(id)) continue;
            Block block = ForgeRegistries.BLOCKS.getValue(id);
            for (long pos : tag.getLongArray(name)) footprint.blocks.put(pos, block);
        }
        return footprint;
    }

    void detonate(ServerLevel level, GuixuAnnihilationPacket burst) {
        ShanhaiNetwork.sendGuixuAnnihilation(level, burst);
        Vec3 origin = Vec3.atCenterOf(burst.center());
        level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 8F, 0.55F);

        // Load only chunks containing recorded structure blocks, then remove controllers before casings.
        var chunks = new LongOpenHashSet();
        var controllers = new LongArrayList();
        remove(level, burst.controller());
        long[] positions = blocks.keySet().toLongArray();
        for (long packed : positions) {
            BlockPos pos = BlockPos.of(packed);
            if (chunks.add(ChunkPos.asLong(pos))) level.getChunkAt(pos);
            var state = level.getBlockState(pos);
            if (state.hasBlockEntity() && state.is(blocks.get(packed))
                    && MetaMachine.getMachine(level, pos) instanceof MultiblockControllerMachine) {
                controllers.add(packed);
            }
        }
        for (long packed : controllers.toLongArray()) remove(level, BlockPos.of(packed));
        for (long packed : positions) remove(level, BlockPos.of(packed));
    }

    private void remove(ServerLevel level, BlockPos pos) {
        Block expected = blocks.get(pos.asLong());
        if (expected == null || !level.getBlockState(pos).is(expected)) return;
        REMOVING.set(pos);
        try {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
        } finally {
            REMOVING.remove();
        }
    }

    /** GT machine removal hooks can emit inventories and covers even with vanilla drops suppressed. */
    @SubscribeEvent
    public static void suppressAnnihilatedDrops(EntityJoinLevelEvent event) {
        BlockPos pos = REMOVING.get();
        if (pos != null && !event.loadedFromDisk() && event.getEntity() instanceof ItemEntity
                && pos.equals(event.getEntity().blockPosition())) event.setCanceled(true);
    }

    /** Mining a critical controller enters the same no-drop path as a supply failure. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void detonateOnControllerBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        MetaMachine machine = MetaMachine.getMachine(level, event.getPos());
        if (machine instanceof GuixuVisionCoreMachine host && (host.isMatrixArmed() || host.hasMatrixFailed())) {
            event.setCanceled(true);
            host.failMatrix();
        } else if (machine instanceof GuixuPenroseContainmentModule anchor
                && anchor.getHost() instanceof GuixuVisionCoreMachine host && host.isMatrixSource(anchor)) {
            event.setCanceled(true);
            host.failMatrix();
        }
    }
}
