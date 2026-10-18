package com.shanhai.mixin;

import com.shanhai.common.guard.ShanhaiGuardValueLayer;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · 值层兜底 ④（基类那一条）：{@code LivingEntity#die(DamageSource)} 在 {@code HEAD} 直接拒绝。
 *
 * <h2>为什么还要这一条（javap 取证）</h2>
 * <pre>
 *   javap -p -c net.minecraft.world.entity.LivingEntity
 *     public void die(DamageSource);
 *        0: aload_0
 *        1: aload_1
 *        2: invokestatic  #2223  // Method net/minecraftforge/common/ForgeHooks.onLivingDeath:...
 *        5: ifeq          9
 *        8: return                                        ← 事件被取消 ⇒ 当场返回
 *        9: aload_0
 *       10: invokevirtual #1136  // Method isRemoved:()Z
 *       13: ifne          186                              ← 真正"死透"的动作从这里才开始
 * </pre>
 * 也就是"死亡流程"真正的入口。玩家身上它的派生覆盖是
 * {@code ServerPlayer#die}（<b>从不调 super.die</b>）⇒ 那一条另见
 * {@link ShanhaiServerPlayerDieGuardMixin}；<b>两条都挂</b>才是"谁都不许判死"。
 *
 * <h2>🔴 只对【受保护玩家】生效</h2>
 * 判据（开关 ＋ 受保护 ＋ {@code ServerPlayer}）全在 {@link ShanhaiGuardValueLayer#refuseDie(Object, String)}，
 * 不命中就<b>不 cancel</b> ⇒ 其他玩家 / 生物 / 实体的死亡行为逐字不变。
 *
 * <h2>🔴 防递归 / 防死循环</h2>
 * 拒绝之后顺手复位（血补满 ＋ {@code deathTime = 0} ＋ {@code hurtTime = 0}），
 * 复位期间 {@code ShanhaiGuardValueLayer} 的 ThreadLocal 闸门是开的 ⇒ 回填血不会重入 ① 的 mixin；
 * 万一 {@code die} 在复位过程中被再次调用，则<b>仍然拒绝、但不再动手</b>（不会无限递归）；
 * 血量最后仍有 ② 每 tick 看门狗兜住。
 *
 * <h2>为什么 {@code require = 1}</h2>
 * 目标方法在 1.20.1 上必然存在（javap 已证）。找不到就<b>当场炸</b>，不许静默变成"看着像有保护"。
 */
@Mixin(LivingEntity.class)
public abstract class ShanhaiLivingEntityDieGuardMixin {

    /**
     * {@code die} 的最外层入口：受保护玩家 ⇒ 取消，死亡流程一步都不开始。
     *
     * @param source 原版形参（本类<b>只转交指针</b>，不读它的任何字段 —— 保持 mixin 体内零 MC 成员引用）
     * @param ci     原版回调，本类只可能调 {@code cancel()}
     */
    @Inject(
            method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void shanhai$refuseDie(DamageSource source, CallbackInfo ci) {
        try {
            // (Object) this ⇒ 本类体内零 MC 成员引用（与 ShanhaiSetHealthGuardMixin 同一套写法）
            if (ShanhaiGuardValueLayer.refuseDie((Object) this, "LivingEntity#die")) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 第二道保险：我们坏了也不许改变原版死亡行为（不 cancel）
        }
    }
}
