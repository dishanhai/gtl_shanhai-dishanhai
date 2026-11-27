package com.dishanhai.gt_shanhai.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader;
import com.gtladd.gtladditions.utils.CommonUtils;

import java.util.*;

/**
 * 客户端侧环隐藏助手。
 * 使用 GTL Additions 的环资源与坐标变换，保存实际方块状态以便恢复山海自己的外壳。
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT)
public class ShanhaiRingHelper {

    private static final Map<BlockPos, Map<BlockPos, BlockState>> HIDDEN = new HashMap<>();
    private static final Map<BlockPos, Long> LAST_SCAN = new HashMap<>();
    private static Level ledgerLevel;
    private static String[][][] ringPatterns;

    public static void init() {}

    public static String[][][] ringPatterns() {
        if (ringPatterns == null) {
            ringPatterns = StructureResourceLoader.INSTANCE.loadRings(
                    "rings/forge_of_the_antichrist_rings.bin", "forge_of_the_antichrist_rings");
        }
        return ringPatterns;
    }

    public static void hideRings(Level level, BlockPos machinePos, Direction facing) {
        if (!level.isClientSide) return;
        if (ledgerLevel != level) {
            clearAll();
            ledgerLevel = level;
        }
        long tick = level.getGameTime();
        Long previous = LAST_SCAN.get(machinePos);
        if (previous != null && tick >= previous && tick - previous < 40L) return;
        LAST_SCAN.put(machinePos.immutable(), tick);
        Map<BlockPos, BlockState> states = HIDDEN.computeIfAbsent(machinePos.immutable(), ignored -> new HashMap<>());
        for (String[][] ring : ringPatterns()) {
            for (int x = 0; x < ring.length; x++) {
                for (int y = 0; y < ring[x].length; y++) {
                    for (int z = 0; z < ring[x][y].length(); z++) {
                        if (ring[x][y].charAt(z) == ' ') continue;
                        BlockPos pos = ringPosition(machinePos, facing, ring, x, y, z);
                        if (!level.isLoaded(pos)) continue;
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir()) continue;
                        states.put(pos, state);
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 122);
                    }
                }
            }
        }
    }

    public static void restoreRings(Level level, BlockPos machinePos, Direction facing) {
        if (level != ledgerLevel) return;
        Map<BlockPos, BlockState> states = HIDDEN.remove(machinePos);
        LAST_SCAN.remove(machinePos);
        if (states == null) return;
        states.forEach((pos, state) -> {
            if (level.isLoaded(pos) && level.getBlockState(pos).isAir()) level.setBlock(pos, state, 122);
        });
    }

    public static boolean isBlockProtected(Level level, BlockPos pos) {
        return level == ledgerLevel && HIDDEN.values().stream().anyMatch(states -> states.containsKey(pos));
    }

    public static void clearAll() {
        if (ledgerLevel != null) {
            for (BlockPos controller : List.copyOf(HIDDEN.keySet())) restoreRings(ledgerLevel, controller, Direction.EAST);
        }
        HIDDEN.clear();
        LAST_SCAN.clear();
        ledgerLevel = null;
    }

    static BlockPos ringPosition(BlockPos machinePos, Direction facing, String[][] ring, int x, int y, int z) {
        return machinePos.offset(BlockPos.containing(CommonUtils.INSTANCE.getRotatedRenderPosition(
                Direction.EAST, facing, ring.length == 94 ? -123.0 : -122.0, 0.0, 0.0)))
                .offset(BlockPos.containing(CommonUtils.INSTANCE.getRotatedRenderPosition(
                        Direction.WEST, facing, x - ring.length / 2, ring[0].length / 2 - y,
                        z - ring[0][0].length() / 2)));
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == ledgerLevel) clearAll();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ledgerLevel == null) return;
        if (Minecraft.getInstance().level != ledgerLevel) {
            clearAll();
            return;
        }
        if (ledgerLevel.getGameTime() % 20L != 0L) return;
        for (BlockPos pos : List.copyOf(HIDDEN.keySet())) {
            if (ledgerLevel.isLoaded(pos)
                    && (!(MetaMachine.getMachine(ledgerLevel, pos) instanceof IMultiController controller)
                    || !controller.isFormed())) restoreRings(ledgerLevel, pos, Direction.EAST);
        }
    }
}
