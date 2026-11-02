package com.dishanhai.gt_shanhai.common.machine.wave;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 引力波广播管理器 — 全局单例
 * <p>
 * 追踪所有活动中的广播源，提供范围查询和全局效果。本类只维护广播源登记表并对外提供
 * 范围/强度查询，自身不订阅任何事件。
 * <p>
 * 效果分发：
 * <ul>
 *   <li><b>回响加速</b>：由 {@link com.dishanhai.gt_shanhai.mixin.BroadcastEffectBuildRecipeMixin} 在目标机器
 *       {@code setupRecipe} 时通过 {@link #getEchoPowerLevel} 查询加速比率</li>
 *   <li><b>产出倍率</b>：同样统一在 {@code BroadcastEffectMixin.setupRecipe} 处理，
 *       通过 {@link #getFixedOutputMultiplier} 查询倍率</li>
 *   <li><b>怪物阻止</b>：由 {@link GravitationalWaveSpawnHandler} 在生物生成时查询 {@link #isInRange}</li>
 * </ul>
 */
public class GravitationalWaveBroadcastManager {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:broadcast_mgr");
    public static final GravitationalWaveBroadcastManager INSTANCE = new GravitationalWaveBroadcastManager();

    private final Map<ResourceKey<Level>, Map<BlockPos, BroadcastSource>> sources = new HashMap<>();

    private GravitationalWaveBroadcastManager() {}

    public static class BroadcastSource {
        public final BlockPos pos;
        public final int radius;
        public final int powerLevel;
        public final int lensCount;
        public final int fixedOutputMultiplier;
        public final boolean echo;

        public BroadcastSource(BlockPos pos, int radius, int powerLevel, int lensCount) {
            this(pos, radius, powerLevel, lensCount, 0);
        }

        public BroadcastSource(BlockPos pos, int radius, int powerLevel, int lensCount, int fixedOutputMultiplier) {
            this(pos, radius, powerLevel, lensCount, fixedOutputMultiplier, false);
        }

        public BroadcastSource(BlockPos pos, int radius, int powerLevel, int lensCount, int fixedOutputMultiplier,
                               boolean echo) {
            this.pos = pos;
            this.radius = radius;
            this.powerLevel = powerLevel;
            this.lensCount = lensCount;
            this.fixedOutputMultiplier = fixedOutputMultiplier;
            this.echo = echo;
        }
    }

    public void addSource(ServerLevel level, BlockPos pos, int radius, int powerLevel) {
        addSource(level, pos, radius, powerLevel, 0);
    }

    public void addSource(ServerLevel level, BlockPos pos, int radius, int powerLevel, int lensCount) {
        addSource(level, pos, radius, powerLevel, lensCount, 0);
    }

    public void addSource(ServerLevel level, BlockPos pos, int radius, int powerLevel, int lensCount, int fixedOutputMultiplier) {
        sources.computeIfAbsent(level.dimension(), k -> new HashMap<>())
               .put(pos, new BroadcastSource(pos, radius, Math.min(100, Math.max(0, powerLevel)), lensCount, fixedOutputMultiplier));
        LOG.info("Source added: dim={}, pos={}, radius={}, power={}, lenses={}, fixedMultiplier={}",
                level.dimension().location(), pos, radius, powerLevel, lensCount, fixedOutputMultiplier);
    }

    /** 山海：回响单独登记，避免新增超频改变原天线行为。广播只覆盖所在维度。 */
    public void addEchoSource(ServerLevel level, BlockPos pos, int radius, int powerLevel, int lensCount,
                              int outputMultiplier) {
        sources.computeIfAbsent(level.dimension(), key -> new HashMap<>()).put(pos.immutable(),
                new BroadcastSource(pos.immutable(), Math.max(1, Math.min(1200, radius)),
                        Math.max(0, Math.min(100, powerLevel)), Math.max(0, Math.min(64, lensCount)),
                        Math.max(2, Math.min(5, outputMultiplier)), true));
    }

    public int getLensCount(ServerLevel level, BlockPos pos) {
        var dimMap = sources.get(level.dimension());
        if (dimMap == null || dimMap.isEmpty()) return 0;
        int maxLenses = 0;
        for (var source : dimMap.values()) {
            if (source.echo && pos.equals(source.pos)) continue;
            long radiusSqr = (long) source.radius * source.radius;
            if (pos.distSqr(source.pos) <= radiusSqr && source.lensCount > maxLenses) {
                maxLenses = source.lensCount;
            }
        }
        return maxLenses;
    }

    public void removeSource(ServerLevel level, BlockPos pos) {
        var dimMap = sources.get(level.dimension());
        if (dimMap != null) {
            dimMap.remove(pos);
            if (dimMap.isEmpty()) sources.remove(level.dimension());
        }
    }

    public boolean isInRange(ServerLevel level, BlockPos pos) {
        var dimMap = sources.get(level.dimension());
        if (dimMap == null || dimMap.isEmpty()) return false;
        for (var source : dimMap.values()) {
            if (pos.distSqr(source.pos) <= (long) (source.radius + 1) * (source.radius + 1)) return true;
        }
        return false;
    }

    public int getPowerLevel(ServerLevel level, BlockPos pos) {
        var dimMap = sources.get(level.dimension());
        if (dimMap == null || dimMap.isEmpty()) return 0;
        int maxPower = 0;
        for (var source : dimMap.values()) {
            double distSqr = pos.distSqr(source.pos);
            long radiusSqr = (long) source.radius * source.radius;
            if (distSqr <= radiusSqr) {
                double dist = Math.sqrt(distSqr);
                double factor = 1.0 - (dist / source.radius);
                int power = (int) (source.powerLevel * factor);
                if (power > maxPower) maxPower = power;
            }
        }
        return maxPower;
    }

    public int getFixedOutputMultiplier(ServerLevel level, BlockPos pos) {
        var dimMap = sources.get(level.dimension());
        if (dimMap == null || dimMap.isEmpty()) return 0;
        return fixedMultiplierAt(dimMap.values(), pos);
    }

    static int fixedMultiplierAt(Iterable<BroadcastSource> candidates, BlockPos pos) {
        int maxMultiplier = 0;
        for (var source : candidates) {
            if (source.fixedOutputMultiplier <= 1) continue;
            if (pos.equals(source.pos)) continue;
            if (pos.distSqr(source.pos) <= (long) source.radius * source.radius) {
                maxMultiplier = Math.max(maxMultiplier, source.fixedOutputMultiplier);
            }
        }
        return maxMultiplier;
    }

    /** 同范围重叠取最强超频，不叠乘；源自身不作为接收端。 */
    public int getEchoPowerLevel(ServerLevel level, BlockPos pos) {
        var dimMap = sources.get(level.dimension());
        return dimMap == null ? 0 : echoPowerAt(dimMap.values(), pos);
    }

    static int echoPowerAt(Iterable<BroadcastSource> candidates, BlockPos pos) {
        int maximum = 0;
        for (var source : candidates) {
            if (!source.echo || source.radius <= 0 || pos.equals(source.pos)) continue;
            double distance = Math.sqrt(pos.distSqr(source.pos));
            if (distance <= source.radius) {
                int power = (int) (source.powerLevel * (1.0 - distance / source.radius));
                maximum = Math.max(maximum, Math.max(0, Math.min(100, power)));
            }
        }
        return maximum;
    }

    public Optional<BroadcastSource> getNearestSource(ServerLevel level, BlockPos pos) {
        var dimMap = sources.get(level.dimension());
        if (dimMap == null || dimMap.isEmpty()) return Optional.empty();
        BroadcastSource nearest = null;
        double nearestDistSqr = Double.MAX_VALUE;
        for (var source : dimMap.values()) {
            double distSqr = pos.distSqr(source.pos);
            long radiusSqr = (long) source.radius * source.radius;
            if (distSqr <= radiusSqr && distSqr < nearestDistSqr) {
                nearest = source;
                nearestDistSqr = distSqr;
            }
        }
        return Optional.ofNullable(nearest);
    }

    public void clearDimension(ResourceKey<Level> dimension) {
        sources.remove(dimension);
    }

    public void clearAll() {
        sources.clear();
    }
}
