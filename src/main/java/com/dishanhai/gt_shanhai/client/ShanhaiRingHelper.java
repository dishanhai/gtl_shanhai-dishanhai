package com.dishanhai.gt_shanhai.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 客户端侧环隐藏助手。
 * 委托给 FOTC 的 ClientRingBlockHelper 执行环隐藏/恢复，
 * 确保位置计算与 FOTC 渲染完全一致。
 */
public class ShanhaiRingHelper {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:ring");

    private static final Map<net.minecraft.resources.ResourceKey<Level>, Set<Long>> PROTECTED_POSITIONS = new HashMap<>();

    public static void init() {}

    /**
     * 委托给 FOTC ClientRingBlockHelper.hideRingsAtPosition，
     * 确保位置计算与 FOTC 渲染完全一致。
     */
    public static void hideRings(Level level, BlockPos machinePos, Direction facing) {
        LOG.info("hideRings: pos={} facing={}", machinePos, facing);
        var helper = com.gtladd.gtladditions.utils.antichrist.ClientRingBlockHelper.INSTANCE;
        helper.hideRingsAtPosition(level, machinePos.asLong(), facing);
    }

    public static void restoreRings(Level level, BlockPos machinePos, Direction facing) {
        var helper = com.gtladd.gtladditions.utils.antichrist.ClientRingBlockHelper.INSTANCE;
        helper.restoreRingsAtPosition(level, machinePos.asLong(), facing);
    }

    public static boolean isBlockProtected(Level level, BlockPos pos) {
        var set = PROTECTED_POSITIONS.get(level.dimension());
        return set != null && set.contains(pos.asLong());
    }

    public static void clearAll() { PROTECTED_POSITIONS.clear(); }
}
