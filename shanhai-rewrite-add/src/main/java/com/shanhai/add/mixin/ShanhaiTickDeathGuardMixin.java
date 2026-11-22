package com.shanhai.add.mixin;

import com.shanhai.add.guard.state.ShanhaiStateAuthority;

import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · <b>决定性拦截：受保护玩家的 {@code LivingEntity#tickDeath} 整个不跑</b>（2026-11-20）。
 *
 * <h2>为什么必须是这个方法（javap 取证，不是推测）</h2>
 * <pre>
 *   net.minecraft.world.entity.LivingEntity#tickDeath   (srg: m_6153_)
 *       ++this.deathTime;                                  ← 计时器
 *       if (this.deathTime &gt;= 20 &amp;&amp; !level().isClientSide() &amp;&amp; !isRemoved()) {
 *           level().broadcastEntityEvent(this, (byte)60);   ← 白烟（POOF）
 *           this.remove(Entity.RemovalReason.KILLED);       ← 真正"被移除"的唯一入口
 *       }
 *   调用点：LivingEntity#baseTick 里 `if (isDeadOrDying() && level().shouldTickDeath(this))`
 *          （1.20.1 Forge 47.3.0 sources 实测：baseTick:410-412）
 *   ==== 实机现场（2026-11-20 22:11:23.303，test2）====
 *   where=remove top=Entity#m_142687_:354 ← LivingEntity#m_142687_:664 ← Player#m_142687_:1281
 *        ← LivingEntity#m_6153_:550 ← LivingEntity#m_6075_:411
 *   ⇒ 白烟与本类要防的 {@code remove(KILLED)}<b>都只从这一个方法发出去</b>。
 *
 * <h2>为什么"归零 deathTime"挡不住、而这一刀挡得住</h2>
 * 第二层在 {@code PlayerTickEvent.START} 里把 {@code deathTime} 归零（那一拍<b>早于</b>本方法），
 * 但只要有人把 {@code tickDeath} 里那句 {@code ++deathTime} 改写成"直接写 20"
 * （KEYCLASS 扫描里确有注入 id {@code redirectDeathTimeSet}，见 {@code [SHANHAI-KEYCLASS] class=…LivingEntity}），
 * 那么"清零 → 同一拍内又变 20 → 阈值成立"就会在<b>一次方法调用内</b>完成，归零永远追不上。
 * 在 {@code HEAD} 上 {@code cancel()} 则连 {@code ++deathTime} 都不执行 ⇒ 白烟与 remove 都不发生。
 *
 * <h2>🔴 只对"受保护玩家"生效（负向路径写死）</h2>
 * <pre>
 *   不是 {@code ServerPlayer}      ⇒ 直接 return（怪物 / 掉落物 / 别的实体：一个字段都不碰，
 *                                    而且【连受保护判定都不会调】—— 不给它们增加任何行为）
 *   客户端 / level 为空            ⇒ return（客户端本来就不会走 remove 分支）
 *   {@link ShanhaiStateAuthority#onTickDeathHead(Object)} 返回 false ⇒ 不 cancel ⇒ 原版行为<b>逐字不变</b>
 *   本类抛任何 Throwable           ⇒ catch 掉、不 cancel（我们坏掉时退化成原版）
 * </pre>
 *
 * <h2>为什么 {@code require = 0}</h2>
 * 需求点名：注入失败的正确代价是"白烟仍在，但整合包不崩"（fail-soft，与 {@code shanhai_add.mixin.json}
 * 四份配置的 {@code required:false} 同一口径）。找不到 {@code m_6153_} ⇒ 只打一行 WARN，
 * 其他一切照旧。
 *
 * <h2>为什么本类体内零 Minecraft 成员引用</h2>
 * 本工程铁律（见 {@code ShanhaiEntitySetRemovedGuardMixin} 类注释 §"为什么本类体内零 Minecraft 成员引用"）：
 * mixin 类体内的成员引用在 refmap 里没有条目 ⇒ 生产环境会拿开发名去 SRG 命名的类上找。
 * ⇒ 本类只做三件事：形参 {@code CallbackInfo}、{@code (Object) this}、一次
 * {@link ShanhaiStateAuthority#onTickDeathHead(Object)} 调用；判定 / 归零 / 日志全在普通类里。
 */
@Mixin(LivingEntity.class)
public abstract class ShanhaiTickDeathGuardMixin {

    /**
     * @param ci 原版回调（本类只可能调 {@code cancel()}）
     */
    @Inject(
            method = "m_6153_",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private void shanhai$skipTickDeath(CallbackInfo ci) {
        try {
            if (ShanhaiStateAuthority.onTickDeathHead((Object) this)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 第二道保险：我们坏了，也绝不许改变原版的死亡行为（不 cancel）
        }
    }
}
