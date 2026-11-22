package com.shanhai.add.mixin;

import com.shanhai.add.guard.gate.ShanhaiDeathGateLayer;

import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 山海重构 · <b>死态闸门 ①「移动」</b>：{@code LivingEntity#m_8107_}(aiStep) 里那一次
 * {@code isImmobile()}，对<b>受保护玩家</b>只保留"睡觉"那一半（2026-11-21）。
 *
 * <h2>🔴 目标指令（javap 实证，不是猜的）</h2>
 * <pre>
 *   javap -p -c -classpath &lt;forge-1.20.1-47.4.16-srg.jar&gt; net.minecraft.world.entity.LivingEntity
 *     public void m_8107_();                       ← aiStep
 *       356..366: Level.getProfiler().push("ai")
 *       371: aload_0
 *       372: invokevirtual #3741  // Method m_6107_:()Z          ← 🔴 本类劫持的就是这一条
 *       375: ifeq          396
 *       378: aload_0 / 379: iconst_0 / 380: putfield f_20899_   ← jumping = false
 *       383..385: putfield f_20900_ = 0.0F                      ← xxa = 0
 *       388..390: putfield f_20902_ = 0.0F                      ← zza = 0
 *       393: goto          434
 *       396: aload_0 / 397: invokevirtual m_21515_()Z …         ← 只有没被冻住才走的"正常 AI"分支
 *
 *   常量池核对（javap -v）：#3741 = Methodref LivingEntity.m_6107_:()Z
 *     ⇒ 调用点 owner 就是 {@code LivingEntity}（不是在子类里发起）⇒ 目标串写 LivingEntity 才命中。
 *   目标方法体：
 *     LivingEntity#m_6107_ : return isDeadOrDying();                          （1 条 invokevirtual + ireturn）
 *     Player#m_6107_       : return super.isImmobile() || isSleeping();        （invokespecial + m_5803_）
 * </pre>
 *
 * <h2>🔴 为什么是"劫持这一次调用"，而不是去改 {@code isImmobile()} 本身</h2>
 * <pre>
 *   ① {@code isImmobile()} 是原版的判据方法，第三方也可能在上面挂注入 ——
 *      改它 = 与别人的注入抢同一个 RETURN/HEAD，谁后跑谁说了算，且会影响所有调用者（含原版其它逻辑）。
 *   ② 这里劫持的是<b>这一条指令</b>：只影响 aiStep 里这一次判断，
 *      与 {@code isImmobile()} 内部被谁改过<b>完全无关</b>（我们只是拿到它这次的返回值再做减法）。
 *   ③ 睡觉那一半在 {@code Player#m_6107_} 里，我们不动它 ⇒ 睡觉仍然不能动。
 * </pre>
 *
 * <h2>🔴 负向路径（逐条写死；原版行为一个字节都不许变）</h2>
 * <pre>
 *   原值 false（正常活人 / 绝大多数生物）        ⇒ 原样 false（处理体 {@code ShanhaiDeathGateLayer#immobile} 判完原值就返回）
 *   不是 Player（怪物 / 载具 / 其它生物）         ⇒ 原值（含 Ravager / AbstractHorse / Llama / Fox 自己的覆写）
 *   是 Player 但未受保护                          ⇒ 原值
 *   受保护但正在睡觉                              ⇒ 原值（冻结照旧）
 *   我们自己的判定抛任何 Throwable                ⇒ {@code ShanhaiDeathGateLayer#vanillaImmobile}（只取原值）
 * </pre>
 *
 * <h2>🔴 原值怎么取（这里踩过一个坑，写下来省下一轮）</h2>
 * <pre>
 *   {@code isImmobile()} 是 <b>protected</b> ⇒ 普通类直接调编译不过（不在 net.minecraft 包里、也不是它的子类）。
 *   而"用公开 API 重算一遍"是<b>错的</b>：它不只被 Player 覆写 —— 反编译逐类查过，还有
 *     Ravager（吼叫/攻击/眩晕）、AbstractHorse（载具+鞍 / 进食）、Llama、Fox（坐下）
 *   ⇒ 重算会把这几类实体的"僵直"判成"能动"，等于改了非玩家实体的行为。
 *   ⇒ 处理体用<b>反射</b>取原值（开发名 isImmobile / 生产名 m_6107_ 两个都试，取到一次就缓存），
 *     {@code Method.invoke} 走动态分派 ⇒ 子类覆写与第三方注入<b>照原样生效</b>
 *     （与本工程取 {@code DATA_HEALTH_ID} / {@code f_20961_} 同一套写法）。
 * </pre>
 *
 * <h2>🔴 为什么 {@code remap = false} ＋ {@code require = 0}</h2>
 * <pre>
 *   · {@code m_8107_} / {@code m_6107_} 都是 SRG 名：生产环境（运行时就是 SRG）按字面命中，不依赖 refmap
 *     （本工程既有做法：{@code ShanhaiHealthReadClampMixin} 的 m_21223_、{@code ShanhaiTickDeathGuardMixin} 的 m_6153_）。
 *     ⚠ 代价：dev-run（官方映射，方法就叫 aiStep / isImmobile）里<b>不会命中</b> —— 与那两处逐字相同。
 *   · require=0（fail-soft，与 shanhai_add.mixin.json 的 defaultRequire=0 同口径）：
 *     这一道是"死态闸门"的一条腿，命中不了的现象是【没有效果也不崩】；
 *     判读方式：日志里永远看不到 {@code death_gate gate=immobile …} ＝ 这一道没挂上（不依赖 Mixin 自己打不打 WARN）。
 *   · 覆盖范围：{@code LivingEntity#m_8107_} 由 {@code Player#aiStep} 里的 {@code super.m_8107_()} 调到，
 *     客户端（{@code LocalPlayer}）与服务端（{@code ServerPlayer}）<b>走同一条</b> ⇒ 只挂一次，两端都在。
 * </pre>
 *
 * <h2>为什么本类体内零 Minecraft 成员引用</h2>
 * 本工程铁律：mixin 类体不许引 MC 成员（{@code @Shadow} 字段/方法在 refmap 里没有条目）。
 * 本类只做三件事：形参对得上（{@code @Redirect} 的接收者，类型必须是目标方法的 owner）、
 * {@code (Object) self}、一次转发。判据 / 日志 / 兜底全在普通类 {@link ShanhaiDeathGateLayer} 里。
 */
@Mixin(LivingEntity.class)
public abstract class ShanhaiImmobileDeathGateMixin {

    /**
     * 劫持 {@code LivingEntity#m_8107_} 里偏移 372 那条 {@code invokevirtual m_6107_()Z}。
     *
     * @param self 原版 {@code this}（{@code @Redirect} 的接收者，恒为 {@code LivingEntity}）
     * @return 这一次 {@code isImmobile()} 要用的返回值：受保护玩家且没在睡觉 ⇒ {@code false}（解除冻结）；
     *         其余 ⇒ 原值
     */
    @Redirect(
            method = "m_8107_",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;m_6107_()Z"),
            remap = false,
            require = 0)
    private boolean shanhai$immobileDeathGate(LivingEntity self) {
        try {
            // (Object) self ⇒ 本类体内零 MC 成员引用（理由见类注释）。
            return ShanhaiDeathGateLayer.immobile(self);
        } catch (Throwable ignored) {
            // 我们坏了 ⇒ 退回原版读数（此时原值必为 true 或 false，由它自己再读一次）
            return ShanhaiDeathGateLayer.vanillaImmobile(self);
        }
    }
}
