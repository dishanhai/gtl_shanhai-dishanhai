package com.shanhai.add.mixin;

import com.shanhai.common.guard.ShanhaiGuardValueLayer;

import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 山海重构 · <b>判据层（第一层）：受保护玩家的 {@code LivingEntity#isDeadOrDying()} 一律读成「没死」</b>
 * （2026-11-21）。
 *
 * <h2>目标方法是 javap 取证的，不是猜的</h2>
 * <pre>
 *   javap -p -classpath joined-1.20.1-20230612.114412-srg.jar net.minecraft.world.entity.LivingEntity
 *     public boolean m_21224_();          ← 没有 final（可以被覆写/被 mixin 改），只有这一处声明
 *   javap -p 同上 · Player / ServerPlayer / LocalPlayer
 *     → 三个类【都没有】声明 m_21224_ ⇒ 虚分派落在 LivingEntity 这一个实现上
 *   javap -p -c 同上 · LivingEntity#m_6075_(tick)
 *     … invokevirtual m_21224_ ; ifeq … ; invokevirtual m_6153_(tickDeath)   ← tickDeath 挂在它后面
 * </pre>
 *
 * <h2>🔴 这一层为什么只是「第一层」（别把它当主刀）</h2>
 * <pre>
 *   对手在 getHealth() / isDeadOrDying() 上挂的是 @At("HEAD") + cancellable + setReturnValue，
 *   其中一个 priority = Integer.MAX_VALUE。Mixin 的应用次序是「低 priority 先应用，HEAD 注入
 *   后应用者排在最前」⇒ 对方在 HEAD 上 cancel 并 setReturnValue 之后，**后面（含本类的 RETURN）一条都不跑**。
 *   ⇒ 本层只在「没人 HEAD-cancel」时有效。
 *   ⇒ 真正的救命刀在【消费点】（同包内的 client/* 两个 mixin：Minecraft#m_91152_ / #m_91398_ 的那两次
 *      读取、以及 ClientPacketListener#m_142747_ 的死亡包）—— 它们劫持的是调用点，不是被调方法。
 *   ⇒ 🔴 本类【不】调 priority，也【不】换注入点：与对手正面抢注入点时平局由加载顺序决定，不可控。
 * </pre>
 *
 * <h2>🔴 本类体内零 Minecraft 成员引用（本工程铁律）</h2>
 * <pre>
 *   mixin 类体内的成员引用在生成的 refmap 里没有条目 ⇒ 生产环境会拿开发名去 SRG 命名的类上找。
 *   ⇒ 本类只做三件事：形参 {@code CallbackInfoReturnable}、{@code (Object) this}、
 *     一次 {@link ShanhaiGuardValueLayer#guardIsDeadOrDying(Object, boolean)} 调用。
 *     判定（instanceof Player / isGuarded / 日志）全在普通类里（普通类会被 reobf）。
 * </pre>
 *
 * <h2>为什么 {@code remap = false}</h2>
 * {@code m_21224_} 已经是<b>生产名（SRG）</b>，不需要 refmap 再映射一次；本工程既有的
 * {@code ShanhaiTickDeathGuardMixin}（{@code m_6153_}）就是同一个写法。
 *
 * <h2>为什么 {@code require = 0}</h2>
 * 找不到就只打一行 WARN、整合包照常起 —— 与 {@code shanhai_add.mixin.json} 的
 * {@code "required": false} / {@code defaultRequire: 0} 同一口径。
 * （项目血账：{@code require = 1} 会让整个服务端起不来。）
 *
 * <h2>负向路径（逐条写死，见 {@link ShanhaiGuardValueLayer#guardIsDeadOrDying}）</h2>
 * <pre>
 *   原返回值 false            ⇒ 一个字节都不碰（先判这一条最便宜）
 *   不是 Player（生物/载具/掉落物）⇒ 一个字节都不碰（🔴 硬红线：它们的行为必须逐字不变）
 *   不是受保护玩家            ⇒ 原版结论原样保留
 *   本类/mixin 抛任何 Throwable ⇒ catch 掉 ⇒ 不设返回值（原版读数照走）
 * </pre>
 */
@Mixin(LivingEntity.class)
public abstract class ShanhaiDeadJudgeReturnMixin {

    /**
     * {@code RETURN}：原版已经算完了，这里把"受保护玩家 = 死"这个结论翻成"没死"。
     *
     * <p>⚠️ 说清楚它的边界：一旦有人在 {@code HEAD} 上 cancel 并 setReturnValue，
     * <b>本方法根本不会被调用</b>（实机判据：{@code read_clamp} 仅 1 次 vs {@code read_side_mismatch} 2500 次）。
     *
     * @param cir 回调（只可能 {@code setReturnValue}）
     */
    @Inject(
            method = "m_21224_",
            at = @At("RETURN"),
            cancellable = true,
            remap = false,
            require = 0)
    private void shanhai$refuseDeadOrDying(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (ShanhaiGuardValueLayer.guardIsDeadOrDying((Object) this, cir.getReturnValueZ())) {
                cir.setReturnValue(Boolean.FALSE);
            }
        } catch (Throwable ignored) {
            // 第二道保险：我们坏了，也绝不许改变原版读数（不设返回值）
        }
    }
}
