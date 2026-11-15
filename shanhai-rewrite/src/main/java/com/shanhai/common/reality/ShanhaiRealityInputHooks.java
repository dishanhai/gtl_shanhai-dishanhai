package com.shanhai.common.reality;

import com.shanhai.ShanhaiMod;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.CriticalHitEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <b>5 件真接线的 MC 事件</b>：登记过「取消」且目标匹配 ⇒ 该目标的<b>下一次</b>事件被取消一次。
 *
 * <h2>一、为什么只有 5 件（其余 60 件只登记）</h2>
 * 用户点单：{@code mc_livingattackevent} / {@code mc_livinghurtevent} / {@code mc_livingdeathevent} /
 * {@code mc_livingdropsevent} / {@code mc_criticalhitevent} 这 5 件是<b>代表</b>，先把"取消下一次"
 * 真的接上；其余 60 件<b>只做登记</b>，面板会在聊天栏额外回一句「（这件尚未接线，只做登记）」，
 * <b>不许假装有效果</b>（判据 {@link ShanhaiRealityInputStore#isWired}）。
 *
 * <h2>二、🔴 四个能 {@code setCanceled(true)}，一个只能 {@code setResult(DENY)}（字节码实证）</h2>
 * 用 {@code javap -v} 读过 Forge 1.20.1-47.4.16 的这 5 个类：
 * <pre>
 *   LivingAttackEvent   RuntimeVisibleAnnotations: net.minecraftforge.eventbus.api.Cancelable  ⇒ setCanceled(true)
 *   LivingHurtEvent     同上                                                                  ⇒ setCanceled(true)
 *   LivingDeathEvent    同上                                                                  ⇒ setCanceled(true)
 *   LivingDropsEvent    同上（ForgeHooks.onLivingDrops 返回 boolean，取消 = 不掉落）            ⇒ setCanceled(true)
 *   CriticalHitEvent    implements Event$HasResult（<b>没有</b> @Cancelable！）                 ⇒ setResult(DENY)
 * </pre>
 * {@code CriticalHitEvent} 这条判据是 {@code ForgeHooks#getCriticalHit} 的字节码：
 * <pre>
 *   post(event); if (result == ALLOW) return event; if (!vanillaCritical) return null;
 *   if (result == DEFAULT) return event; return null;      // ← DENY 走到这里 ⇒ 返回 null ⇒ 这一下不暴击
 * </pre>
 * ⇒ 所以第 5 件用 {@code setResult(Event.Result.DENY)}；写 {@code setCanceled(true)} 会是
 * {@code UnsupportedOperationException}（{@code Event#setCanceled} 在非 Cancelable 事件上直接抛）。
 *
 * <h2>三、为什么 {@code priority = LOWEST} 且不收已取消的事件</h2>
 * {@code LOWEST} 让别人的处理器先跑；若它们已经把这次事件取消了，我们的方法<b>根本不会被调</b>
 * （{@code receiveCanceled} 默认 false）⇒ <b>一次"本来就不会发生"的事件不会白吃掉我们的一次登记</b>。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiRealityInputHooks {

    /** 5 个物品 id（与 {@link ShanhaiRealityInputStore#wiredIds()} 必须逐字一致）。 */
    public static final String ID_LIVING_ATTACK = ShanhaiRealityInputStore.NAMESPACE + ":mc_livingattackevent";
    public static final String ID_LIVING_HURT = ShanhaiRealityInputStore.NAMESPACE + ":mc_livinghurtevent";
    public static final String ID_LIVING_DEATH = ShanhaiRealityInputStore.NAMESPACE + ":mc_livingdeathevent";
    public static final String ID_LIVING_DROPS = ShanhaiRealityInputStore.NAMESPACE + ":mc_livingdropsevent";
    public static final String ID_CRITICAL_HIT = ShanhaiRealityInputStore.NAMESPACE + ":mc_criticalhitevent";

    private ShanhaiRealityInputHooks() {}

    /** ① 生物被攻击（伤害还没结算）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        if (ShanhaiRealityInputStore.cancelNext(ID_LIVING_ATTACK, event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** ② 生物受伤（伤害已定、还没扣血）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (ShanhaiRealityInputStore.cancelNext(ID_LIVING_HURT, event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** ③ 生物死亡。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (ShanhaiRealityInputStore.cancelNext(ID_LIVING_DEATH, event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** ④ 生物掉落物。取消之外再多做一步：把已经装进集合的掉落<b>清空</b>（判据更硬）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDrops(LivingDropsEvent event) {
        if (ShanhaiRealityInputStore.cancelNext(ID_LIVING_DROPS, event.getEntity())) {
            event.setCanceled(true);
            try {
                // 双保险：万一某个分支不看 isCanceled()，集合空了也掉不出东西
                event.getDrops().clear();
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} drops_clear_failed err={}", ShanhaiRealityInputStore.PREFIX,
                        t.toString());
            }
        }
    }

    /** ⑤ 暴击（{@code HasResult}，不是 Cancelable —— 见类注释 §二）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCriticalHit(CriticalHitEvent event) {
        if (ShanhaiRealityInputStore.cancelNext(ID_CRITICAL_HIT, event.getEntity())) {
            event.setResult(Event.Result.DENY);
        }
    }
}
