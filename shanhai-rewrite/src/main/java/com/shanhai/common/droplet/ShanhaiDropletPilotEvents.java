package com.shanhai.common.droplet;

import com.shanhai.ShanhaiMod;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 驾驶期间的<b>免伤</b>（规格：玩家在水滴里获得水滴同款免伤，离开立刻恢复）。
 *
 * <h2>一、为什么是 {@code LivingHurtEvent}，而不是 {@code LivingAttackEvent}</h2>
 * 我把 1.20.1（Forge 47.4.16）的 {@code ForgeHooks} 逐条拉过字节码，结论是：
 * <ul>
 *   <li>{@code LivingEntity#hurt} 调 {@code ForgeHooks.onLivingAttack}，那里面的第一件事就是
 *       {@code if (entity instanceof Player) return true;}（{@code ifne} 跳过 post）
 *       ⇒ <b>这条路玩家不触发 {@code LivingAttackEvent}</b>；</li>
 *   <li><b>但是</b> {@code Player#hurt} 走的是另一个桥 {@code ForgeHooks.onPlayerAttack}，
 *       那里<b>没有</b>玩家判断，会无条件 {@code post(new LivingAttackEvent(...))}；</li>
 *   <li>{@code ForgeHooks.onLivingHurt} 则被 {@code LivingEntity#actuallyHurt} 与
 *       {@code Player#actuallyHurt} <b>两条路都调用</b>，且"取消 ⇒ 返回 0 伤害"。</li>
 * </ul>
 * ⇒ 所以「{@code LivingAttackEvent} 对玩家根本不触发」这句话只对一半（{@code LivingEntity} 那条路），
 * 而<b>取消 {@code LivingHurtEvent} 才是在玩家身上必定生效的那道闸</b>。本类只挂它，不挂 Attack。
 *
 * <h2>二、四道闸（缺一道就有洞）</h2>
 * <ol>
 *   <li>{@link LivingHurtEvent} 取消 —— 所有走 {@code hurt()} 链路的伤害；</li>
 *   <li>{@link LivingDeathEvent} 取消 —— 血量已 ≤0 时兜住原版死亡流程；</li>
 *   <li>血量看门狗（本类 {@link #onPlayerTick}）—— 对手是<b>绕过事件直写血量</b>的伤害。
 *       本模组自己的水滴攻击就是这种：它直接写 {@code DATA_HEALTH_ID}，完全不过任何事件
 *       ⇒ 光挂事件拦不住"别人名下的水滴打骑乘者"；</li>
 *   <li>（接入驾驶逻辑时，实体侧的直写伤害入口也要加一条"目标在骑乘水滴 ⇒ 不写"的早退；
 *       本类不依赖它，但两道一起才是"同款免伤"的体验底线。）</li>
 * </ol>
 *
 * <h2>三、"离开立刻恢复原状"怎么保证</h2>
 * 免伤<b>不写任何持久状态</b>：每次都用 {@code player.getVehicle() instanceof ShanhaiDropletEntity} 现场判。
 * 下车那一刻 {@code getVehicle()} 变 null ⇒ 四道闸同时失效，<b>不存在"忘记恢复"的失败模式</b>。
 * 唯一的可变状态是看门狗表里的"进入时血量快照"，它只在骑乘期间存在，离开即删。
 *
 * <h2>四、已知残余限制（不隐瞒）</h2>
 * 不写 mixin 就拦不住"绕过 {@code hurt()}、绕过事件、并在我们这一 tick 之前就 {@code die()} 生效"的
 * 第三方直写。看门狗覆盖的是"扣了血但还没死"，以及"死了但在下一 tick 之前没被移除"的情形。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiDropletPilotEvents {

    private static final String PREFIX = ShanhaiDropletPilotNetwork.PREFIX;

    /** 骑乘期间的血量快照（UUID → 进入时的血量）。只在骑乘期间存在。 */
    private static final Map<UUID, Float> ENTRY_HEALTH = new ConcurrentHashMap<>();

    private static final AtomicLong HURTS_CANCELED = new AtomicLong();
    private static final AtomicLong DEATHS_CANCELED = new AtomicLong();
    private static final AtomicLong HEALTH_RESTORED = new AtomicLong();

    private ShanhaiDropletPilotEvents() {}

    // ------------------------------------------------------------------ 闸 1：伤害事件

    /**
     * 闸 1：骑乘我们水滴的玩家，一切 {@code hurt()} 伤害取消。
     *
     * <p>为什么不用 {@code event.setAmount(0)} 而用 {@code setCanceled(true)}：
     * {@code ForgeHooks.onLivingHurt} 在事件被取消时<b>直接返回 0</b>，
     * 而 {@code actuallyHurt} 拿到 ≤0 就整段早退（连护甲/吸收/击退都不算）；
     * 只把 amount 设 0 则仍会走完后面的护甲计算与击退。
     */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!isPiloting(player)) {
            return;
        }
        event.setCanceled(true);
        final long n = HURTS_CANCELED.incrementAndGet();
        if (n <= 8L || n % 200L == 0L) {
            final DamageSource source = event.getSource();
            ShanhaiMod.LOGGER.info("{} immunity_hurt_canceled n={} player={} amount={} source={}",
                    PREFIX, Long.valueOf(n), player.getUUID(), Float.valueOf(event.getAmount()),
                    source == null ? "null" : source.toString());
        }
    }

    // ------------------------------------------------------------------ 闸 2：死亡事件

    /** 闸 2：骑乘期间取消死亡流程（兜住"血量已被写到 ≤0"的场合）。 */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!isPiloting(player)) {
            return;
        }
        event.setCanceled(true);
        final long n = DEATHS_CANCELED.incrementAndGet();
        if (n <= 8L || n % 200L == 0L) {
            ShanhaiMod.LOGGER.info("{} immunity_death_canceled n={} player={} source={}",
                    PREFIX, Long.valueOf(n), player.getUUID(),
                    event.getSource() == null ? "null" : event.getSource().toString());
        }
    }

    // ------------------------------------------------------------------ 闸 3：血量看门狗

    /**
     * 闸 3：每 tick 检查骑乘者血量，被"直写数据层"的伤害扣掉就写回去。
     *
     * <p>语义是"进入水滴时多少血，在里面就一直是多少血"（不改上限、不给额外血量）：
     * 只<b>向上</b>恢复，绝不向下写 —— 所以不会把玩家治成超额血量。
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final Player player = event.player;
        if (player == null) {
            return;
        }
        final UUID id = player.getUUID();
        if (!isPiloting(player)) {
            if (ENTRY_HEALTH.remove(id) != null) {
                ShanhaiMod.LOGGER.info("{} immunity_released player={}", PREFIX, id);
            }
            return;
        }
        final Float entry = ENTRY_HEALTH.get(id);
        if (entry == null) {
            ENTRY_HEALTH.put(id, Float.valueOf(player.getHealth()));
            ShanhaiMod.LOGGER.info("{} immunity_armed player={} health={}",
                    PREFIX, id, Float.valueOf(player.getHealth()));
            return;
        }
        if (player.getHealth() < entry.floatValue() - 1.0E-4F) {
            final float restored = Math.min(entry.floatValue(), player.getMaxHealth());
            player.setHealth(restored);
            final long n = HEALTH_RESTORED.incrementAndGet();
            if (n <= 8L || n % 200L == 0L) {
                ShanhaiMod.LOGGER.info("{} immunity_health_restored n={} player={} -> {}",
                        PREFIX, Long.valueOf(n), id, Float.valueOf(restored));
            }
        }
    }

    // ------------------------------------------------------------------ 清理

    /** 下线：清掉血量快照与意图槽（否则表会跟着"来过多少人"一直长）。 */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() == null) {
            return;
        }
        final UUID id = event.getEntity().getUUID();
        final boolean hadHealth = ENTRY_HEALTH.remove(id) != null;
        final boolean hadIntent = ShanhaiDropletPilotNetwork.forget(id);
        ShanhaiMod.LOGGER.info("{} pilot_cleanup_on_logout player={} had_health={} had_intent={}",
                PREFIX, id, Boolean.valueOf(hadHealth), Boolean.valueOf(hadIntent));
    }

    // ------------------------------------------------------------------ 判定 / 读数

    /** 这个玩家是不是正骑在<b>我们的</b>水滴里（免伤的唯一判据）。 */
    public static boolean isPiloting(Player player) {
        return player != null
                && player.isPassenger()
                && player.getVehicle() instanceof ShanhaiDropletEntity;
    }

    /** 诊断读数（命令 / 日志用，机器可判）。 */
    public static String statsLine() {
        return "hurt_canceled=" + HURTS_CANCELED.get()
                + " death_canceled=" + DEATHS_CANCELED.get()
                + " health_restored=" + HEALTH_RESTORED.get()
                + " armed=" + ENTRY_HEALTH.size();
    }
}
