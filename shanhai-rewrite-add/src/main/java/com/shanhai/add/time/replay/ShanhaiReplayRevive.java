package com.shanhai.add.time.replay;

import com.shanhai.ShanhaiMod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 山海重构 · <b>「重建出来的生物不许当尸体」的收尾口径</b>（认识 MC，但不改原版行为）。
 *
 * <h2>1. 用户口径（2026-11-14 13:26 录像）</h2>
 * <blockquote>「有些猪出现了<b>假死</b>」「被 kill 的生物<b>血量没回来</b>」</blockquote>
 * <blockquote>「<b>白色烟团是生物死亡的特效</b>，他正常的，但是他不是方块的」</blockquote>
 * ⇒ 两个结论：
 * <ol>
 *   <li>收尾那一瞬的白烟 = <b>有生物在收尾时死了</b>（假死/血量为 0 的连带症状），
 *       不是方块恢复粒子；</li>
 *   <li>{@code particles=0/0} 是真实的：<b>方块恢复一格粒子都没发</b>（那一半按"从零实现"做）。</li>
 * </ol>
 *
 * <h2>2. 本类做两件事</h2>
 * <ul>
 *   <li>{@link #shouldSuppressPoof()}：倒流期间<b>抑制</b>原版死亡烟
 *       （{@code ServerLevel#broadcastEntityEvent(e,(byte)60)}，来源
 *       {@code LivingEntity.java:549} 的 {@code die()}）—— 我们重建/救活的实体不许触发死亡特效；
 *       读数 {@code revive_poof_suppressed=<n>}。</li>
 *   <li>{@link #sendReversedDeathPoof}：<b>窗口内确实死过</b>的生物，死亡烟要出现在
 *       <b>它死的那一拍</b>，而且是倒放的（客户端 {@code ShanhaiReplayClientFx} 会把回放期间的
 *       粒子初速改成"朝格心收"，所以这里只负责"在该拍发一颗"）。</li>
 * </ul>
 */
public final class ShanhaiReplayRevive {

    private static long poofSuppressed;
    private static long reversedPoofs;

    /** 这一拍要不要抑制原版死亡烟（倒流进行中 = 是）。 */
    public static boolean shouldSuppressPoof() {
        return ShanhaiReplayService.isRunning();
    }

    public static void countPoofSuppressed() {
        ++poofSuppressed;
    }

    public static long poofSuppressed() {
        return poofSuppressed;
    }

    public static long reversedPoofs() {
        return reversedPoofs;
    }

    public static void reset() {
        poofSuppressed = 0L;
        reversedPoofs = 0L;
    }

    /**
     * 在重建那一刻补一颗死亡烟（客户端会把它的初速改成朝中心收 ⇒ 观感是"烟往回缩"）。
     *
     * @param entity 刚重建出来的生物（位置已就位）
     */
    public static void sendReversedDeathPoof(ServerLevel level, Entity entity) {
        if (level == null || entity == null) {
            return;
        }
        try {
            ++reversedPoofs;
            level.sendParticles(ParticleTypes.CLOUD,
                    entity.getX(), entity.getY() + 0.5D, entity.getZ(),
                    8, 0.35D, 0.35D, 0.35D, 0.02D);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("[SHANHAI-REPLAY] 反向死亡烟发送失败 uuid={} err={}",
                    entity.getUUID(), t.toString());
        }
    }

    /** 一行读数（{@code done} 行用）。 */
    public static String line() {
        return "revive_poof_suppressed=" + poofSuppressed + " reversed_death_poof=" + reversedPoofs;
    }

    private ShanhaiReplayRevive() {
    }
}
