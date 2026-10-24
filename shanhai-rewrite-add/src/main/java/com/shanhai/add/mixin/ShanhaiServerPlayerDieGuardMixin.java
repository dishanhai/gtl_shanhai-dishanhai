package com.shanhai.add.mixin;

import com.shanhai.common.guard.ShanhaiGuardValueLayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 值层兜底 ④（<b>真实玩家真正走的那一条</b>）：
 * {@code ServerPlayer#die(DamageSource)} 在 {@code HEAD} 直接拒绝。
 *
 * <h2>🔴 为什么必须有这一条（javap 取证，不是推测）</h2>
 * <pre>
 *   javap -p -c net.minecraft.server.level.ServerPlayer
 *     public void die(DamageSource);
 *        0: aload_0
 *        1: getstatic     // GameEvent.ENTITY_DIE
 *        4: invokevirtual // gameEvent(...)
 *        9: invokestatic  #1146  // ForgeHooks.onLivingDeath(...)   ← 事件层在这里
 *       15: return                                                 ← 事件被取消 ⇒ 返回
 *       16: ...（死亡消息 / 统计 / connection 发包 ……）
 *       ⇒ 全方法体里【没有】任何 invokespecial ...die
 *   javap -p -c net.minecraft.world.entity.player.Player
 *        9: invokespecial #1131  // Method LivingEntity.die:(...)V   ← 只有 Player 才调 super.die
 * </pre>
 * 也就是说：<b>玩家的死亡虚分派落在 {@code ServerPlayer#die}，而它从不调 {@code super.die(...)}</b> ——
 * 只在 {@code LivingEntity#die} 上注入（{@link ShanhaiLivingEntityDieGuardMixin}）对真实玩家<b>永远打不到</b>。
 * 两条都挂：基类那条当兜底，这一条才是真正生效的。
 *
 * <p>⚠️ <b>与原有事件层的关系（必须知道，不是坏了）</b>：本注入点在 {@code HEAD} ⇒ 命中时
 * {@code ForgeHooks.onLivingDeath}（即 {@code LivingDeathEvent}）<b>不会被执行</b>，
 * 所以老的「[SHANHAI-GUARD] 拦截 死亡」那一行<b>在受保护玩家身上不再出现</b>，
 * 改由 {@code [SHANHAI-GUARD] 拦截 拒绝死亡 …} 顶替（更强的一道，死亡流程一步都不开始）。
 *
 * <h2>🔴 只对【受保护玩家】生效</h2>
 * 判据全在 {@link ShanhaiGuardValueLayer#refuseDie(Object, String)}（开关 ＋ 受保护 ＋ {@code ServerPlayer}）：
 * 不命中 ⇒ 不 cancel ⇒ 其他玩家 / 生物的死亡行为逐字不变。
 *
 * <h2>防递归</h2>
 * 复位动作与 ② 完全共用 {@link ShanhaiGuardValueLayer#resetDeathFlow(net.minecraft.world.entity.LivingEntity)}
 * （血补满 ＋ {@code deathTime = 0} ＋ {@code hurtTime = 0}）；复位期间重入闸门是开的 ⇒
 * 回填血不会重入 ① 的 mixin，重入的 {@code die} 只拒绝、不再动手。
 *
 * <h2>为什么 {@code require = 1}</h2>
 * 目标方法在 1.20.1 上必然存在（javap 已证；{@code ServerPlayer} 自己就有 {@code die} 的字节码）。
 * 找不到就当场炸，不许静默。
 */
@Mixin(ServerPlayer.class)
public abstract class ShanhaiServerPlayerDieGuardMixin {

    /**
     * 玩家死亡的最外层入口：受保护玩家 ⇒ 取消。
     *
     * @param source 原版形参（只转交指针，不读任何字段）
     * @param ci     原版回调，本类只可能调 {@code cancel()}
     */
    @Inject(
            method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void shanhai$refuseServerPlayerDie(DamageSource source, CallbackInfo ci) {
        try {
            if (ShanhaiGuardValueLayer.refuseDie((Object) this, "ServerPlayer#die")) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 第二道保险：我们坏了也不许改变原版死亡行为（不 cancel）
        }
    }
}
