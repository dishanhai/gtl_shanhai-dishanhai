package com.shanhai.mixin;

import com.shanhai.common.guard.ShanhaiGuardValueLayer;

import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 山海重构 · 【创始现实修改模块】值层兜底 ①：{@code LivingEntity#setHealth(float)} 的<b>入参</b>改写。
 *
 * <h2>为什么需要它（实机证据）</h2>
 * <pre>
 *   敌人不走 LivingAttackEvent，而是【直接写血量】⇒ 事件层拦不住：
 *   日志仍见「拦截 伤害 / 死亡 / 死亡界面」、补血自证也成功，但玩家进入「死但没死透」（僵住不能动）。
 *   ⇒ 必须在【写血那一步】就把 0/负 挡掉，血根本不许变成 0。
 * </pre>
 *
 * <h2>🔴 目标方法是 javap 查证的，不是猜的</h2>
 * <pre>
 *   javap -p -s -classpath &lt;forge-1.20.1-47.4.16_mapped_parchment_2023.09.03-1.20.1.jar&gt; \
 *         net.minecraft.world.entity.LivingEntity
 *     public void setHealth(float);
 *       descriptor: (F)V                                       ← 只有一个，无重载
 *   ✅ ServerPlayer / Player 都【没有】覆盖 setHealth（javap 逐类查过）⇒ 挂在 LivingEntity 上就能覆盖真实玩家
 *   ✅ TSRG 映射：setHealth (F)V → m_21153_（refmap 的判据见构建产物 build/mixinrefmap/shanhai.refmap.json）
 * </pre>
 *
 * <h2>🔴 本类体内<b>一个字都不许引用 Minecraft 成员</b>（本工程既有铁律）</h2>
 * mixin 类体内的成员引用在生成的 refmap 里<b>没有条目</b>（{@code @Shadow} 字段/方法同样没有，见
 * {@code ShanhaiSetBlockWatchMixin} 类注释 §2 的实测）⇒ 生产环境会拿开发名去 SRG 命名的类上找 ⇒ 找不到。
 * <p>所以本类只做三件事：<b>类型对得上的形参</b>、{@code (Object) this}、一次
 * {@link ShanhaiGuardValueLayer#guardSetHealth(Object, float)} 调用。
 * {@code getMaxHealth()} / {@code isGuarded()} 全部在普通类（会被 reobf）里做。
 *
 * <h2>为什么是 {@code @ModifyVariable(argsOnly = true)} 而不是 {@code @Inject}</h2>
 * 用户 2026-10-17 点名的做法：<b>改入参</b> ⇒ 原方法体一行都不动，返回值/控制流全不变；
 * 不命中时<b>逐字返回原值</b>（对别人的行为一个字节都不改）。{@code index = 1} 显式指定形参槽位
 * （实例方法：0 = this，1 = float 形参）⇒ 不依赖 LVT、不会被"别的 float 局部变量"抢走。
 *
 * <h2>防递归</h2>
 * 见 {@link ShanhaiGuardValueLayer} 类注释的"两层闸门"：① 值 &gt; 0 原样放行 ② ThreadLocal 重入闸门。
 * 我们这个改写返回的是 {@code getMaxHealth()}（&gt; 0）⇒ 就算 {@code setHealth} 内部再调 {@code setHealth}，
 * 那次调用的入参也 &gt; 0 ⇒ 第一道闸门就把它放行了，不会二次改写、不会无限递归。
 *
 * <h2>为什么 {@code require = 1}</h2>
 * 目标方法在 1.20.1 上必然存在（javap 已证）。找不到就该<b>当场炸</b>，
 * 而不是"守卫静默不存在、看起来像已保护"—— 静默失效在这条线上是最坏的失败形态。
 */
@Mixin(LivingEntity.class)
public abstract class ShanhaiSetHealthGuardMixin {

    /**
     * 把 {@code setHealth(float)} 的入参交给值层判一遍。
     *
     * @param value 原入参（{@code HEAD} 处还没写进 {@code this.health}）
     * @return 真正要写进去的值：受保护玩家被写 {@code <= 0} ⇒ 满血上限；否则逐字原样
     */
    @ModifyVariable(
            method = "setHealth(F)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 1,
            require = 1)
    private float shanhai$guardSetHealth(float value) {
        try {
            // (Object) this ⇒ 本类体内零 MC 成员引用（理由见类注释）。
            return ShanhaiGuardValueLayer.guardSetHealth((Object) this, value);
        } catch (Throwable ignored) {
            // 第二道保险：我们坏了，也绝不许改变"写血"这件原版行为
            return value;
        }
    }
}
