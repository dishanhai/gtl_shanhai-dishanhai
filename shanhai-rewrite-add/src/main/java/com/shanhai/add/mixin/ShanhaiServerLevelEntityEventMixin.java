package com.shanhai.add.mixin;

import com.shanhai.add.time.replay.ShanhaiReplayRevive;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 服务端：<b>倒流期间不许广播原版死亡烟（byte 60）</b>。
 *
 * <h2>根因（2026-11-14 录像，用户原话）</h2>
 * <blockquote>「<b>白色烟团是生物死亡的特效</b>，他正常的，但是他不是方块的」</blockquote>
 * ⇒ 收尾那一瞬的白烟 = 有生物在收尾时"死"了。两个来源都被这条挡住：
 * <ol>
 *   <li>重建出来的生物带着 {@code DeathTime>0} 的 NBT，一解冻就走死亡动画
 *       → {@code LivingEntity#die()} → {@code broadcastEntityEvent(this,(byte)60)}
 *       （{@code LivingEntity.java:549}）；</li>
 *   <li>回放写临界区里别的路径（第三方）在倒流期间让实体死亡。</li>
 * </ol>
 *
 * <p>判据：倒流期间 {@code byte 60} 不广播、计数加一（{@code revive_poof_suppressed=}）；
 * <b>窗口内确实死过</b>的生物由 {@code ShanhaiReplayRevive#sendReversedDeathPoof}
 * 在它死的那一拍补发一颗（客户端把初速改成朝中心收 ⇒ 倒放）。
 *
 * <p>⚠️ 只在 {@code ShanhaiReplayService.isRunning()} 时生效：正常游戏/暂停一条都不拦。
 */
@Mixin(ServerLevel.class)
public abstract class ShanhaiServerLevelEntityEventMixin {

    /** {@code LivingEntity#die()} 里那颗死亡烟的事件号（{@code LivingEntity.java:549}）。 */
    private static final byte POOF = 60;

    @Inject(method = "broadcastEntityEvent(Lnet/minecraft/world/entity/Entity;B)V",
            at = @At("HEAD"), require = 1, cancellable = true)
    private void shanhai$suppressReplayPoof(Entity entity, byte event, CallbackInfo ci) {
        try {
            if (event == POOF && ShanhaiReplayRevive.shouldSuppressPoof()) {
                ShanhaiReplayRevive.countPoofSuppressed();
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 判定坏了 ⇒ 照常广播（宁可多一颗烟，也不能吞掉原版特效）
        }
    }
}
