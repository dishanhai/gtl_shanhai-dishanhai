package com.shanhai.add.mixin;

import com.shanhai.add.guard.gate.ShanhaiDeathGateLayer;

import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 山海重构 · <b>死态闸门 ②「拾取」</b>：{@code Player#m_8107_}(aiStep) 里"拾取闸门"那一次
 * {@code getHealth()}，对受保护玩家把读数抬到 &gt;0（2026-11-21）。
 *
 * <h2>🔴 闸门原文（javap 实证）</h2>
 * <pre>
 *   javap -p -c -classpath &lt;forge-1.20.1-47.4.16-srg.jar&gt; net.minecraft.world.entity.player.Player
 *     public void m_8107_();                        ← aiStep（全方法 454 字节，只有 2 处 getHealth）
 *       200: aload_0
 *       201: invokevirtual #902  // Method m_21223_:()F      ← 🔴 本类劫持的就是这一条（第 2 处）
 *       204: fconst_0 / 205: fcmpl / 206: ifle  382          ← 条件：getHealth() &gt; 0.0F
 *       209: aload_0 / 210: invokevirtual m_5833_()Z         ← 且 !isSpectator()
 *       213: ifne 382                                        ← 旁观者也跳过整段
 *       216..382: getEntities（AABB 略扩）→ 逐实体 playerTouch / 经验球单独收
 *       382: 拾取整段的出口
 * </pre>
 *
 * <h2>🔴 {@code Player#m_8107_} 里全部 2 处 {@code m_21223_} 调用点（javap 逐个列出，不靠印象）</h2>
 * <pre>
 *   ordinal  字节码偏移   上下文                                              本类改不改
 *   ------------------------------------------------------------------------------------------
 *   0        46/47      PEACEFUL ＋ 自然回血规则：getHealth() &lt; getMaxHealth()
 *                       且 tickCount % 20 == 0 ⇒ heal(1.0F)                  ❌ 不改
 *   1        200/201     🔴 拾取闸门：getHealth() &gt; 0.0F &amp;&amp; !isSpectator()    ✅ 改这一条
 *   ------------------------------------------------------------------------------------------
 *   ⚠️ 另有一处常被误认成 getHealth 的读数：偏移 148 是 {@code m_21224_}（isDeadOrDying），
 *      上下文是"走路摆动量"（onGround &amp;&amp; !isDeadOrDying() &amp;&amp; !isSwimming ⇒ bob 上限）；
 *      它<b>不是</b> getHealth，本类不碰；捡经验球那一段（偏移 356~382）也<b>没有</b>第二次 getHealth
 *      —— 它整个被上面这个 {@code if} 包住，所以"经验球捡不到"与"拾取闸门"是同一处。
 *
 *   常量池核对（javap -v）：#902 = Methodref <b>Player</b>.m_21223_:()F
 *     ⇒ 调用点 owner 是 {@code Player}（编译期接收者类型是 this）⇒ 目标串必须写 Player，写 LivingEntity 不命中。
 * </pre>
 *
 * <h2>🔴 为什么只改"这一次读数"，不改字段、不改 {@code isDeadOrDying()}</h2>
 * <pre>
 *   闸门判的是 {@code getHealth() > 0.0F} —— 一次纯读取。
 *   ⇒ 只把<b>这一次</b>返回值抬到 &gt;0 就够：不动同步字段（不改存档/同步态）、
 *     不动 {@code isDeadOrDying()}（别人的注入照旧）、不影响任何别的读取点。
 *   这也是本工程既有的判据层口径：{@code @Redirect} 劫持"这一次读取"，与被读方法上的注入完全无关。
 * </pre>
 *
 * <h2>🔴 负向路径（逐条写死）</h2>
 * <pre>
 *   读数 &gt;= 1.0（正常血量 / +∞）            ⇒ 原读数（处理体里 {@code served == read} ⇒ 直接返回，连 isGuarded 都不调）
 *   未受保护玩家                            ⇒ 原读数（逐位放行）
 *   旁观者                                  ⇒ 本类不改它的血量读数；{@code isSpectator()} 那道门原样还在（旁观仍然不能捡）
 *   任何 Throwable                          ⇒ 原读数（{@code ShanhaiDeathGateLayer#vanillaHealth}）
 * </pre>
 *
 * <h2>🔴 为什么 {@code remap = false} ＋ {@code require = 0} ＋ {@code ordinal = 1}</h2>
 * <pre>
 *   · {@code m_8107_} / {@code m_21223_} 是 SRG 名：生产环境按字面命中，不依赖 refmap（本工程既有做法）。
 *     ⚠ dev-run（官方映射）里不命中 —— 与 {@code ShanhaiHealthReadClampMixin} 逐字相同。
 *   · ordinal=1：同一方法里 {@code m_21223_} 恰好出现 2 次（上表），拾取闸门是<b>第 2 次</b>；
 *     用 ordinal 而不是字节码偏移 ⇒ 别人在前面插几条指令也不会打偏（偏移会漂、顺序不会）。
 *     第 1 次（PEACEFUL 自然回血）<b>故意不动</b>：它不是拾取闸门，改了只是徒增影响面。
 *   · require=0：命中不了的现象是【没有效果也不崩】；判读方式＝日志里永远看不到
 *     {@code death_gate gate=pickup …}（不依赖 Mixin 自己打不打 WARN）。
 * </pre>
 *
 * <h2>两端都要 ⇒ 放公共 {@code mixins} 数组</h2>
 * {@code Player} 在专用服务端也存在、{@code Player#aiStep} 两端都跑 ⇒ 不需要分侧
 * （分侧反而会让服务端那条读数没人管）。客户端才是这条闸门真正生效的一侧，
 * 因为服务端已被 {@code ShanhaiHealthReadClampMixin} 先把 &lt;=0 的读数钳成上限了。
 */
@Mixin(Player.class)
public abstract class ShanhaiPickupDeathGateMixin {

    /**
     * 劫持 {@code Player#m_8107_} 里第 2 次 {@code m_21223_()F}（偏移 200/201，拾取闸门）。
     *
     * @param self 原版 {@code this}（{@code @Redirect} 的接收者，恒为 {@code Player}）
     * @return 这一次 {@code getHealth()} 要用的返回值：受保护玩家 ⇒ {@code max(读数, 1.0F)}；其余 ⇒ 原读数
     */
    @Redirect(
            method = "m_8107_",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;m_21223_()F",
                    ordinal = 1),
            remap = false,
            require = 0)
    private float shanhai$pickupDeathGate(Player self) {
        try {
            // (Object) self ⇒ 本类体内零 MC 成员引用（理由见类注释）。
            return ShanhaiDeathGateLayer.pickupHealth(self);
        } catch (Throwable ignored) {
            // 我们坏了 ⇒ 退回原版读数（这一读就是我们替它读的那一次）
            return ShanhaiDeathGateLayer.vanillaHealth(self);
        }
    }
}
