package com.shanhai.client.pilot;

import com.shanhai.common.droplet.ShanhaiDropletEntity;
import com.shanhai.common.droplet.ShanhaiDropletPilotCore;
import com.shanhai.common.droplet.ShanhaiDropletSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * 驾驶期间<b>目标标记的客户端候选表</b>（绿框 + 距离/名字/血量）。
 *
 * <h2>一、为什么由客户端自己算（而不是服务端下发实体列表）</h2>
 * 硬口径：「客户端自己算（用已同步的模式字段），不要为此新增实体列表同步」。
 * 服务端索敌用的过滤条件一共四条，其中<b>唯一不天然可见</b>的是"攻击模式"，
 * 而它已经在 {@link ShanhaiDropletEntity#attackMode()}（{@code DATA_MODE}）里同步过了。
 * 其余三条（128 格、活着、{@code Enemy} 过滤）客户端本来就能读。所以完全不需要新通道。
 *
 * <h2>二、与服务器<b>同一套</b>过滤（逐条对齐实体里的 {@code isCandidate}）</h2>
 * <pre>
 *   ① 不是骑乘者本人 / 不是水滴拥有者
 *   ② isRemoved / isAlive / isDeadOrDying 三条都过
 *   ③ 与拥有者距离 ≤ 128 格（AABB 是方的 ⇒ 再用真实距离卡一次）
 *   ④ 模式 2 时必须是原版 Enemy；模式 3（不攻击）⇒ 一个都不标
 * </pre>
 * 注：服务端以<b>拥有者</b>为圆心量 128 格；驾驶规格下"能进水滴的只有拥有者"⇒ 客户端用骑乘者当真值即可。
 *
 * <h2>三、成本护栏</h2>
 * 128 格 AABB 覆盖 17×17×17 个区块段，逐帧扫太浪费 ⇒
 * ① 只在客户端 tick 调、且每 {@link ShanhaiDropletPilotCore#TARGET_REFRESH_TICKS} tick 才真扫一次（10 Hz）；
 * ② 结果按距离升序截断到 {@link ShanhaiDropletPilotCore#MAX_MARKS} 个；
 * ③ 标签字符串在<b>重算时</b>就格式化好（每帧只投影 + 画，不做字符串拼接）。
 */
public final class ShanhaiDropletPilotTargets {

    /** 名字最多留几个字符（超出补省略号）。 */
    private static final int MAX_NAME_CHARS = 18;

    private ShanhaiDropletPilotTargets() {}

    /**
     * 一个被标记的目标（不可变，纯数据；不持有实体强引用之外的任何东西）。
     *
     * <p>包围盒存成 6 个 {@code double}（不是 {@code AABB} 对象）：
     * ① 投影核只吃 double；② 每帧画的时候不需要再碰实体（实体可能在这一帧被移除）。
     */
    public static final class Mark {

        /** 实体 id（只用于日志/去重，不参与绘制）。 */
        public final int entityId;

        public final double minX;
        public final double minY;
        public final double minZ;
        public final double maxX;
        public final double maxY;
        public final double maxZ;

        /** 与骑乘者的距离（格）。 */
        public final double distance;

        /** 第一行：`12.3 格 · 僵尸`。 */
        public final String lineFirst;

        /** 第二行：`血量 20.0 / 20.0`。 */
        public final String lineSecond;

        Mark(int entityId,
             double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
             double distance, String lineFirst, String lineSecond) {
            this.entityId = entityId;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.distance = distance;
            this.lineFirst = lineFirst;
            this.lineSecond = lineSecond;
        }

        /** 盒中心（给"是否落进准星圈"用）。 */
        public double centerX() {
            return (this.minX + this.maxX) * 0.5D;
        }

        /** 盒中心 Y。 */
        public double centerY() {
            return (this.minY + this.maxY) * 0.5D;
        }

        /** 盒中心 Z。 */
        public double centerZ() {
            return (this.minZ + this.maxZ) * 0.5D;
        }
    }

    /**
     * 重算候选（只在客户端 tick 调，且由调用方按 {@code TARGET_REFRESH_TICKS} 降频）。
     *
     * @param mc 客户端
     * @param droplet 当前骑乘的水滴
     * @return 不可变列表（按距离升序，最多 {@link ShanhaiDropletPilotCore#MAX_MARKS} 条；不满足条件 ⇒ 空表）
     */
    public static List<Mark> refresh(Minecraft mc, ShanhaiDropletEntity droplet) {
        final LocalPlayer rider = mc == null ? null : mc.player;
        final ClientLevel level = mc == null ? null : mc.level;
        if (rider == null || level == null || droplet == null) {
            return List.of();
        }
        final int mode = droplet.attackMode();
        if (mode == ShanhaiDropletSettings.MODE_NONE) {
            return List.of();                       // 模式 3：不攻击 ⇒ 不标记（与服务器一致）
        }
        final AABB area = rider.getBoundingBox().inflate(ShanhaiDropletEntity.TARGET_RANGE);
        final List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class, area,
                entity -> isCandidate(rider, droplet, mode, entity));

        final List<Mark> marks = new ArrayList<>(Math.min(found.size(), ShanhaiDropletPilotCore.MAX_MARKS));
        for (final LivingEntity entity : found) {
            final AABB box = entity.getBoundingBox();
            final double distance = rider.distanceTo(entity);
            final String name = ShanhaiDropletPilotCore.trimName(entity.getDisplayName().getString(), MAX_NAME_CHARS);
            marks.add(new Mark(entity.getId(),
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                    distance,
                    ShanhaiDropletPilotCore.distanceText(distance) + " · " + name,
                    ShanhaiDropletPilotCore.healthText(entity.getHealth(), entity.getMaxHealth())));
        }
        marks.sort(Comparator.comparingDouble((Mark mark) -> mark.distance));
        if (marks.size() > ShanhaiDropletPilotCore.MAX_MARKS) {
            return List.copyOf(marks.subList(0, ShanhaiDropletPilotCore.MAX_MARKS));
        }
        return List.copyOf(marks);
    }

    /**
     * 与 {@code ShanhaiDropletEntity#isCandidate} 逐条对齐的客户端过滤。
     *
     * <p>刻意<b>不</b>引用实体里的私有方法（那是服务端权威判定，客户端只需要"看起来一样"），
     * 但四条判据的语义完全一致；任何一条将来在服务端改了口径，这里必须同步（PLAN 的风险清单里已列）。
     */
    private static boolean isCandidate(LocalPlayer rider, ShanhaiDropletEntity droplet, int mode,
                                       LivingEntity entity) {
        if (entity == null || entity == rider) {
            return false;
        }
        final java.util.UUID owner = droplet.ownerUuid();
        if (owner != null && owner.equals(entity.getUUID())) {
            return false;
        }
        if (entity.isRemoved() || !entity.isAlive() || entity.isDeadOrDying()) {
            return false;
        }
        if (mode == ShanhaiDropletSettings.MODE_ENEMY && !(entity instanceof Enemy)) {
            return false;
        }
        return rider.distanceTo(entity) <= ShanhaiDropletEntity.TARGET_RANGE;
    }
}
