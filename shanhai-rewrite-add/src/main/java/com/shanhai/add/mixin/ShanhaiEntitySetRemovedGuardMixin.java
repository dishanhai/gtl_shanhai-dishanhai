package com.shanhai.add.mixin;

import com.shanhai.add.guard.ShanhaiGuardEntityGuard;

import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · ① 拦实体移除：{@code Entity#setRemoved(RemovalReason)} 的 {@code HEAD}。
 *
 * <h2>为什么挂这个 final 方法（javap 取证，不是推测）</h2>
 * <pre>
 *   javap -p -s net.minecraft.world.entity.Entity
 *     public final void setRemoved(net.minecraft.world.entity.Entity$RemovalReason);
 *       descriptor: (Lnet/minecraft/world/entity/Entity$RemovalReason;)V     ← 【final】，模组改不掉
 *     public void remove(net.minecraft.world.entity.Entity$RemovalReason);   ← 可被模组重写
 *     public final void discard();                                           ← 内部走 remove()
 *   方法体（javap -c）：
 *      0..9   只在 removalReason == null 时写入
 *     12..23  shouldDestroy() ⇒ stopRiding()
 *     26..35  乘客一起 onRemove
 *     40..45  levelCallback.onRemove(reason)   ← 真正把自己从世界的实体账里摘掉的就是这一步
 * </pre>
 * ⇒ 拦在 {@code setRemoved} 上：一行 {@code @Override public void remove(...)} 绕不过去。
 * 反过来只拦 {@code remove()} 会被别人一行重写破防 —— 这是需求点名要的取舍。
 *
 * <h2>🔴 白名单</h2>
 * 换维度 ／ 区块卸载 ／ 玩家确实已断开 ／ 服务器正在关闭 一律<b>放行</b>（判据在
 * {@link com.shanhai.common.guard.ShanhaiGuardRemoval#isNormalLeave}）；
 * 不放行会让换维度与退服卡死。
 *
 * <h2>为什么本类体内零 Minecraft 成员引用</h2>
 * 本工程铁律（见 {@code ShanhaiSetBlockWatchMixin} 类注释 §2）：mixin 类体内的成员引用在生成的
 * refmap 里<b>没有条目</b> ⇒ 生产环境会拿开发名去 SRG 命名的类上找、找不到。
 * 所以本类只做三件事：类型对得上的形参、{@code (Object) this}、一次
 * {@link ShanhaiGuardEntityGuard#refuseSetRemoved(Object, Object)} 调用；
 * 所有 {@code hasDisconnected()} ／ {@code isRunning()} ／ {@code isGuarded()} 都在普通类里做
 * （普通类会被 reobf 成 SRG 名）。
 *
 * <h2>为什么 {@code require = 1}</h2>
 * 目标方法在 1.20.1 上必然存在（javap 已证）。找不到就<b>当场炸</b>，
 * 不许静默变成"看着像拦住了、其实没拦"—— 在这条线上静默失效是最坏的失败形态。
 */
@Mixin(Entity.class)
public abstract class ShanhaiEntitySetRemovedGuardMixin {

    /**
     * @param reason 原版形参（本类<b>只转交指针</b>，不读它的任何成员）
     * @param ci     原版回调，本类只可能调 {@code cancel()}
     */
    @Inject(
            method = "setRemoved(Lnet/minecraft/world/entity/Entity$RemovalReason;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void shanhai$refuseSetRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        try {
            if (ShanhaiGuardEntityGuard.refuseSetRemoved((Object) this, (Object) reason)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 第二道保险：我们坏了，也绝不许改变原版的移除行为（不 cancel）
        }
    }
}
