package com.shanhai.common.droplet;

import com.shanhai.ShanhaiMod;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 水滴的<b>地形</b>处理（规格 7）：撞到非空气方块时的爆炸，以及"这一 tick 的线段先撞到哪个方块"的扫掠。
 *
 * <h2>一、两种模式的语义</h2>
 * <ul>
 *   <li>爆炸开关<b>关</b>（默认）：完全不碰地形 —— 实体 {@code noPhysics = true}，像旁观者一样直接穿过去。</li>
 *   <li>爆炸开关<b>开</b>：<b>自己扫</b>这一 tick 的位移线段（不是只看终点），撞到第一个非空气方块就在
 *       <b>进入点</b>炸开，然后继续朝目标飞（洞已经开了）。</li>
 * </ul>
 *
 * <h2>二、爆炸为什么是「原版表现 + 自己拆方块」两段</h2>
 * ① <b>表现/推/伤</b>：{@code ServerLevel#explode(..., Level.ExplosionInteraction.NONE)}。
 * 取 {@code NONE} 的实证（{@code Level.java} 第 522-546 行）：它映射成 {@code Explosion.BlockInteraction.KEEP}
 * ⇒ {@code finalizeExplosion} 里那段"遍历 toBlow、滚掉落、{@code onBlockExploded}"<b>整段不跑</b>，
 * 但 {@code Explosion#explode()} 里对实体的 <b>hurt + 击退</b>照常跑；{@code ServerLevel#explode}
 * 还会给 4096 平方格内的玩家发 {@code ClientboundExplodePacket} ⇒ 客户端照常放爆炸音效与
 * {@code EXPLOSION_EMITTER} 粒子（客户端重建的那颗 Explosion 的 blockInteraction 是 DESTROY_WITH_DECAY）。
 * ② <b>方块破坏自己扫</b>：以炸点为球心、半径 {@link #BLOCK_RADIUS} 的球体内逐格
 * {@code setBlock(pos, Blocks.AIR, 3)} —— 不看硬度、不看爆炸抗性、不滚掉落：
 * 基岩 / 命令方块 / 屏障 / 水 / 岩浆<b>全部变空气</b>。
 *
 * <h2>三、为什么破坏半径 = 4</h2>
 * 规格：「威力等同 TNT（半径 4）」。原版 TNT 的 {@code Explosion} 半径就是 4.0F，
 * 但原版的实际破坏距离是"射线推进 + 抗性衰减"，<b>不是</b>一个规整的球；规格要的是
 * 「按半径逐格」，所以这里用 <b>半径 4 的球</b>（{@code dx²+dy²+dz² ≤ 16}）逐格清干净，
 * 无视原版的抗性衰减。
 *
 * <h2>四、拥有者豁免（2026-11-22 追加）</h2>
 * 爆炸对<b>拥有者</b>完全免除伤害与击退，对<b>其它玩家 / 生物 / 建造物照旧</b>。做法是：
 * 调 {@code level.explode(...)} <b>之前</b>把"这次爆炸的拥有者 UUID"放进
 * {@link ShanhaiDropletExplosionProtection} 的标记，爆炸内部 {@code ExplosionEvent.Detonate}
 * 把拥有者从受击表里摘掉，<b>finally</b> 里立刻清标记。不是临时无敌、不改原版 {@code Explosion}。
 */
public final class ShanhaiDropletExplosion {

    /** 原版 TNT 的爆炸半径（规格 7）。 */
    public static final float RADIUS = 4.0F;

    /** 方块破坏半径（格）= {@link #RADIUS} 取整；破坏范围就是半径 4 的球。 */
    public static final int BLOCK_RADIUS = 4;

    /** 扫掠用的最小长度（线段短于此视为原地不动）。 */
    private static final double EPS = 1.0E-7D;

    /**
     * 扫这一 tick 的位移线段 {@code from → to}，返回<b>第一个</b>非空气方块的进入点。
     *
     * <h4>为什么不是"看终点那个方块"</h4>
     * 每 tick 位移正好 1.0 格，目标方块可能只有 1 格宽（甚至更薄的非完整方块）⇒ 只看终点会
     * 整块跳过去（隧穿）。这里把线段覆盖到的所有方块（≤ 2×2×2）都做一次
     * {@link AABB#clip(Vec3, Vec3)}（原版的 slab 射线裁剪），按线段参数 t 取<b>最先进入</b>的那个。
     * 起点已经在方块内部时 {@code clip} 返回 empty，用 {@code contains(from)} 补 t = 0。
     *
     * @return 进入点；这一段没有任何非空气方块 ⇒ {@code null}
     */
    public static Vec3 firstTerrainAlong(Level level, Vec3 from, Vec3 to) {
        final double length = from.distanceTo(to);
        if (length < EPS) {
            return null;
        }
        final double minX = Math.min(from.x, to.x) - EPS;
        final double minY = Math.min(from.y, to.y) - EPS;
        final double minZ = Math.min(from.z, to.z) - EPS;
        final double maxX = Math.max(from.x, to.x) + EPS;
        final double maxY = Math.max(from.y, to.y) + EPS;
        final double maxZ = Math.max(from.z, to.z) + EPS;
        final BlockPos min = BlockPos.containing(minX, minY, minZ);
        final BlockPos max = BlockPos.containing(maxX, maxY, maxZ);

        double bestT = Double.MAX_VALUE;
        Vec3 bestPoint = null;
        for (final BlockPos pos : BlockPos.betweenClosed(min, max)) {
            final BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            final AABB box = new AABB(pos);
            final Optional<Vec3> entry = box.clip(from, to);
            double t;
            Vec3 point;
            if (entry.isPresent()) {
                point = entry.get();
                t = from.distanceTo(point) / length;
            } else if (box.contains(from)) {
                point = from;
                t = 0.0D;
            } else {
                continue;
            }
            if (t < bestT) {
                bestT = t;
                bestPoint = point;
            }
        }
        return bestPoint;
    }

    /**
     * 在 {@code at} 炸一次：原版表现（粒子/音效/对实体的推与伤）+ <b>自己扫</b>的方块清除。
     *
     * <p>只在服务端调用（{@code level instanceof ServerLevel} 才真正执行）。
     *
     * @return 被清成空气的方块数（0 = 这一段没有方块；用于日志与自证）
     */
    public static int detonate(Level level, Entity source, Vec3 at) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }
        // ① 原版爆炸：NONE ⇒ BlockInteraction.KEEP ⇒ 原版自己【不】拆方块、不滚掉落；
        //    但实体的 hurt/击退 与客户端音效/粒子照旧（见类注释 §二）。
        //    🔴 拥有者豁免（类注释 §四）：explode 是同步调用 —— 进入前把"这次爆炸的拥有者"放进标记，
        //    ExplosionEvent.Detonate 在爆炸内部把拥有者从受击/击退表里摘掉；
        //    finally 一定清标记 ⇒ 正常返回 / 抛异常 / 事件取消都不会把标记留到下一次爆炸。
        final UUID ownerUuid = source instanceof ShanhaiDropletEntity droplet ? droplet.ownerUuid() : null;
        ShanhaiDropletExplosionProtection.begin(ownerUuid);
        try {
            serverLevel.explode(source, at.x, at.y, at.z, RADIUS, Level.ExplosionInteraction.NONE);
        } finally {
            ShanhaiDropletExplosionProtection.end();
        }

        // ② 方块破坏自己扫：半径 4 的球，逐格 AIR，无视硬度/抗性/掉落（基岩、命令方块、屏障、水/岩浆都清）
        final BlockPos center = BlockPos.containing(at);
        int cleared = 0;
        final int r = BLOCK_RADIUS;
        for (final BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            final double dx = pos.getX() + 0.5D - at.x;
            final double dy = pos.getY() + 0.5D - at.y;
            final double dz = pos.getZ() + 0.5D - at.z;
            if (dx * dx + dy * dy + dz * dz > (double) r * (double) r) {
                continue;
            }
            if (level.getBlockState(pos).isAir()) {
                continue;
            }
            // betweenClosed 给的是可复用的游标：写方块前先固定一份（原版拆方块那段也是这么做的）
            level.setBlock(pos.immutable(), Blocks.AIR.defaultBlockState(), 3);
            cleared++;
        }
        ShanhaiMod.LOGGER.debug("{} explosion at={} radius={} blocks_cleared={}",
                ShanhaiDropletCombat.PREFIX, at, RADIUS, cleared);
        return cleared;
    }

    private ShanhaiDropletExplosion() {}
}
