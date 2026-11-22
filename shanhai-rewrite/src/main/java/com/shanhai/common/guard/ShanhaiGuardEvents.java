package com.shanhai.common.guard;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.items.tools.powered.WirelessTerminalItem;
import com.shanhai.ShanhaiMod;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 🔴 <b>「持有「创始现实修改模块」的玩家不会死」的 Forge 胶水（<span>双侧安全</span>的那一半）。</b>
 *
 * <pre>
 *   ① 挡伤害      LivingAttackEvent      → setCanceled(true)     只对持有者
 *   ② 拦死亡      LivingDeathEvent       → setCanceled(true) 当场补满血（两条腿，见 ③″）
 *   ③ 每 tick 兜底 TickEvent.ServerTickEvent(END) → 补满 ＋ 复位 deathTime/hurtTime，只处理持有者
 *   ③b 丢出通道   ItemTossEvent          → 记下"他刚把模块丢出去了"（按键丢出＝主动放手）
 *   ④ 补回/放手   每 tick 里：只有【被动失去】才补回（背包没有 ＋ 非丢出通道 ＋ 打开的容器里没有 ＋ AE 里没有）
 *   ⑤ 救回        判出【被外力移除】后**真的动手**：放回位置 → revive() → addFreshEntity()（判返回值）
 *   ⑥ 聊天栏      ★2026-10-10 新增★ 上面每一次拦截都【同时】给持有者本人发一条聊天栏消息
 *                 （前缀 [创始现实修改模块] ＋ 人话；**只发给他一个人**，绝不广播；
 *                  走同一套闸门 ⇒ 同类合并 ＋ 每秒最多一行；**日志那一路照旧保留，不是替换**）
 *   ④′ 死亡界面  ★不在这里★ —— 它在 com.shanhai.client.guard.ShanhaiGuardClientEvents（客户端那半边）
 *   ★2026-10-17 值层兜底（对手直接写同步血量 / 直接判死，事件层拦不住）★
 *   ③″ 两条腿补血  setHealth(max) → 自证 → 直写 DATA_HEALTH_ID（第二通道）→ 用【字段真值】再自证；
 *                 日志必带 heal_channel=setHealth / direct / failed（不许只写成功/失败）
 *   ③‴ 死亡流程复位 每 tick：isDeadOrDying ／ deathTime&gt;0 ／ 🔴 同步血量字段真值&lt;=0 任一为真
 *                 ⇒ 两条腿补血 ＋ deathTime=0 ＋ hurtTime=0（独立类别 KIND_RESET，**不计入"补血成功"**）
 *   ⑦ 拒绝死亡    LivingEntity#die ＋ ServerPlayer#die 的 HEAD（mixin — 🔴 已搬进扩展 shanhai_add）→ ci.cancel()
 *                 ＋ 同一套复位动作；日志类别 KIND_DIE_REFUSED（走 logDetail/chat 闸门与文案表）
 *   ⑦′ setHealth 入参  LivingEntity#setHealth(float) 的 @ModifyVariable（mixin — 🔴 已搬进扩展 shanhai_add）
 *                 → 受保护玩家被写 &lt;=0 ⇒ 改成满血
 *                 ⚠ 只装本体时这两道不存在 ⇒ 本体这条路只剩"事件层 ＋ 每 tick 补血（③ ／ ③″）"，
 *                 这也是用户 2026-10-18 拍板的口径（免死只做到事件层和 setHealth，不碰 mixin）。
 *   ⑧ 反击退      ★2026-10-17★ 每 tick 只对受保护玩家：① {@code hurtMarked} 置 false
 *                 ② 「刚被攻击」窗口（我们最近 20 tick 拦过东西）内 ＋ 水平速度 &gt; 0.6 格/tick
 *                 ⇒ {@code setDeltaMovement} 的 x/z 清零、y 保留（🔴 只刹车，不搬人：没有 setPos / 没有拉回）
 *                 ③ 豁免：鞘翅飞行 / 在载具里 / 旁观者 / 刚传送（2 tick）　④ 日志每秒最多一行、默认不播
 *   ⑨ 热改播报    ★2026-11-08★ {@link #chatRawBodies}：把"主动热改改了哪个敌方法"以
 *                 {@code [创始现实修改模块] PATCHED examplemod EntityHelper#forceSetHealth -> RETURN (health)}
 *                 这种形播到聊天栏 —— <b>复用 ⑥ 那条通道</b>（同一个 {@link #CHAT} 闸门、同一个前缀、
 *                 同一个收件人规则），正文纯 ASCII，一律不新建通道
 * </pre>
 *
 * <h2>🔴 为什么死亡界面那一段被搬走了（2026-10-10 专服崩溃）</h2>
 * 本类是自动订阅者 ⇒ <b>专服上也会被加载</b>；而取消死亡界面要用 {@code Minecraft}（{@code player} 字段是
 * {@code LocalPlayer}）／{@code DeathScreen} ／ {@code ScreenEvent} —— 只有客户端才有 ⇒ 专服冒烟当场炸：
 * {@code Attempted to load class net/minecraft/client/player/LocalPlayer for invalid dist DEDICATED_SERVER}。
 * 现在客户端那半边在 {@code client.guard.ShanhaiGuardClientEvents}，挂 {@code value = Dist.CLIENT}。
 * <b>本文件从此不许出现客户端包（{@code javac} 拦不住这个问题，靠 grep ＋ javap 常量池判据）。</b>
 *
 * <h2>为什么 @Mod.EventBusSubscriber 不写 value</h2>
 * {@code Mod.EventBusSubscriber#value()} 的默认值是
 * {@code {Dist.CLIENT, Dist.DEDICATED_SERVER}}（实测 javap：
 * {@code AnnotationDefault: [Lnet/minecraftforge/api/distmarker/Dist;.CLIENT,...DEDICATED_SERVER]}）
 * ⇒ <b>不写 value = 单机与专服都挂</b>。{@code bus()} 默认 {@code Bus.FORGE}，正是上面这几个事件所在的总线。
 *
 * <h2>判定全在纯逻辑核里</h2>
 * 本类<b>只做三件事</b>：把 Minecraft 对象翻译成「持有者 / 血量 / 计数器 / 容器里有没有 / AE 里有没有」几个读数，
 * 问 {@link ShanhaiGuardCore}，然后执行。所有判据住在纯 JDK 的核里 ⇒ 离线判据与游戏内跑的是<b>同一份字节码</b>。
 *
 * <h2>🔴 「持有」按物品 id 判</h2>
 * {@code BuiltInRegistries.ITEM.getKey(stack.getItem())} 取 id，拆成命名空间/路径喂给
 * {@link ShanhaiGuardCore#isGuardItem(String, String)} —— <b>不按类名判</b>（那样改名/换实现就会静默失效）。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID)
public final class ShanhaiGuardEvents {

    private ShanhaiGuardEvents() {}

    /** 日志闸门：同类合并 ＋ 每秒最多一行。 */
    private static final ShanhaiGuardCore.LogGate LOG = new ShanhaiGuardCore.LogGate();

    // 🔴 2026-10-10：这 11 个类别串的【定义】搬到了核里（字符逐字未变，日志行因此逐字未变）——
    //    因为"哪一类该跟玩家说什么人话"那张表住在核里（ShanhaiGuardCore#chatPhrase），
    //    两处各写一份字面量必然漂移；这里改成引用核里的常量。
    private static final String KIND_DAMAGE = ShanhaiGuardCore.KIND_DAMAGE;
    private static final String KIND_DEATH = ShanhaiGuardCore.KIND_DEATH;
    private static final String KIND_HEAL = ShanhaiGuardCore.KIND_HEAL;
    // 补五件新增的四类（走"细节闸门"，见 logDetail）
    private static final String KIND_TELEPORT = ShanhaiGuardCore.KIND_TELEPORT;
    private static final String KIND_REMOVED = ShanhaiGuardCore.KIND_REMOVED;
    private static final String KIND_BLOOD = ShanhaiGuardCore.KIND_BLOOD;
    private static final String KIND_HEAL_FAIL = ShanhaiGuardCore.KIND_HEAL_FAIL;
    private static final String KIND_RESTORE = ShanhaiGuardCore.KIND_RESTORE;
    // 2026-10-10 新增三类（主动放手 ＋ 救回）
    private static final String KIND_DISARM = ShanhaiGuardCore.KIND_DISARM;
    private static final String KIND_ME = ShanhaiGuardCore.KIND_ME;
    private static final String KIND_RESCUED = ShanhaiGuardCore.KIND_RESCUED;
    // 🔴 2026-10-17 新增两类（值层兜底：② 死亡流程复位 ／ ④ 拒绝死亡）
    private static final String KIND_RESET = ShanhaiGuardCore.KIND_RESET;
    private static final String KIND_DIE_REFUSED = ShanhaiGuardCore.KIND_DIE_REFUSED;
    // 🔴 2026-10-17 新增一类（反击退 / 反挤压；诊断类，只进日志、不进聊天栏）
    private static final String KIND_KNOCKBACK = ShanhaiGuardCore.KIND_KNOCKBACK;

    /** 细节日志闸门：与 {@link #LOG} 同一套「同类合并 ＋ 每秒最多一行」，但带原因串（只打每窗口的"末次"）。 */
    private static final ShanhaiGuardCore.LogGate DETAIL = new ShanhaiGuardCore.LogGate();

    /**
     * 🔴 <b>反击退自己的闸门</b>（2026-10-17 加）：同样"同类合并 ＋ 每秒最多一行"，
     * 但<b>单独一个</b> —— 它的读数（{@code cleared=… braked=… ／ 末次：why=…}）很重要，
     * 不能和同一秒里的别的类别挤在 {@link #DETAIL} 那一行里被"末次"顶掉。
     *
     * <p>用户口径：「日志每秒最多 1 行」「默认不播」—— 这一条闸门就是"每秒最多 1 行"的落实。
     */
    private static final ShanhaiGuardCore.LogGate KNOCK = new ShanhaiGuardCore.LogGate();

    /**
     * 🔴 <b>聊天栏闸门</b>（2026-10-10 加）—— 与日志同一套现成实现（{@link ShanhaiGuardCore.LogGate}：
     * 同类合并 ＋ 每秒最多一行），只是去处换成"那个玩家本人的聊天栏"。
     *
     * <p>为什么不复用 {@link #LOG}／{@link #DETAIL}：那两条各自已是"每秒最多一行" ⇒ 同一秒里它们
     * <b>可能各出一行</b>；聊天栏要的是"一口气最多一行"，所以它自己一个闸门、所有类别都进这一个。
     */
    private static final ShanhaiGuardCore.LogGate CHAT = new ShanhaiGuardCore.LogGate();

    // ═══════════════ 补五件用的常驻状态（只在服务端 tick 里读写；键 = 玩家 UUID） ═══════════════

    /**
     * 🔴 <b>登记过的持有者</b>（一旦持有过就记住，在线期间有效）。
     *
     * <p>为什么要"记住"而不是每 tick 重新判：补件④ 的场景是<b>有人把背包掏空</b> ——
     * 那一刻 {@link #holdsGuard} 就是 false，若还按"当前持有"过滤，我们的判据会<b>自己失效</b>
     * （被掏空 ⇒ 不再是持有者 ⇒ 不补回 ⇒ 永远补不回来）。
     */
    private static final Set<UUID> REMEMBERED = new HashSet<>();

    /** 每个持有者一份「上次正常位置」（补件①：有人用 {@code setPos(1e9, 0, 0)} 把你送走）。 */
    private static final Map<UUID, ShanhaiGuardCore.PositionTrack> TRACKS = new HashMap<>();

    /**
     * 🔴 <b>「丢出」通道的登记表</b>（键 = 玩家 UUID，值 = 丢出时的 {@code getGameTime()}）。
     *
     * <p>来源：Forge 的 {@code ItemTossEvent} —— 按键丢出（Q）与<b>从容器里丢出</b>都会走到
     * {@code Player#drop(ItemStack, boolean)} ⇒ 在那里开火。判据在核里（{@link ShanhaiGuardCore#leftViaDropChannel}）：
     * 只有「窗口内 ＋ 背包里已经没有模块」才算【走丢出通道离开】。
     */
    private static final Map<UUID, Long> TOSSED_AT = new HashMap<>();

    /** 🔴 每个持有者一个「只在背包里没有的那一刻查一次 ME」闸门（绝不每 tick 查）。 */
    private static final Map<UUID, ShanhaiGuardCore.MeProbeGate> ME_GATES = new HashMap<>();

    /**
     * 🔴 <b>每个受保护玩家一份「反击退」状态</b>（键 = 玩家 UUID）：窗口记账 ＋ 刚传送豁免 ＋ 计数。
     *
     * <p>判据全在纯核 {@link ShanhaiGuardKnockback} 里（离线可跑同一份字节码）；这里只存状态。
     * <b>不存坐标</b> —— 按用户新口径，本功能只改速度与标志，一个坐标都不记、不搬。
     */
    private static final Map<UUID, ShanhaiGuardKnockback.PlayerState> KB_STATES = new HashMap<>();

    // ═══════════════════════════════ ① 挡伤害 ═══════════════════════════════

    /**
     * 即将受伤 —— 只拦「持有者」。
     *
     * <p>不持有的人走原路（本方法对他<b>一个字节都不改</b>）；持有者直接取消 ⇒
     * {@code ForgeHooks.onLivingAttack} 返回 true ⇒ {@code LivingEntity#hurt} 当场返回，伤害不进血条。
     */
    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (!ShanhaiGuardCore.enabled) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        // 🆕 T 件①③ 的分诊读数【不在这里】—— 见下方 onLivingAttackObserved。
        //    原因（2026-11-07 实测）：本方法没有 receiveCanceled ⇒ 只要**任何别的监听器**先取消了这次攻击，
        //    Forge 就不会把事件发给我们，于是"僵尸被反击抹除了、分诊却一行没打"。
        //    观测必须放在一个 receiveCanceled = true 的独立监听器里，才能保证任何一次攻击都留下读数。
        //    ⚠️ 这里一个字节的判定都没改（不 cancel、不返回）。
        if (!ShanhaiGuardCore.blocksDamage(isGuarded(player))) {
            return;
        }
        event.setCanceled(true);
        log(KIND_DAMAGE);
        chat(player, KIND_DAMAGE);   // 🆕 聊天栏（只发给他本人；日志那一行照旧在上面）
        noteIntercept(player);       // 🆕 反击退：这一拍把「刚被攻击」窗口打开了（20 tick）
    }

    /** 观测行每秒最多一条（防刷；一次挨打本来就很少）。 */
    private static long LAST_ATTACK_SEEN_MS = 0L;

    /**
     * 🔴 <b>攻击事件的【观测】监听器</b>（2026-11-07 加，只记录、绝不改判定）。
     *
     * <p>为什么必须单独一个：{@link #onLivingAttack} 没有 {@code receiveCanceled}，
     * 只要**别的监听器**先取消了这次攻击（实测：扩展层的反击抹除能拿到、本体这一路拿不到），
     * Forge 就不再把事件发给它 ⇒ 分诊的四个读数永远是"没发生"。
     * 本监听器用 {@code receiveCanceled = true, priority = LOWEST} 保证**任何一次攻击都留一行读数**。
     *
     * <p>它只做三件事：① 调分诊的 {@code noteDamage}（R1 ＋ 抓一次栈，每人每局 3 次）；
     * ② 每秒最多打一行 {@code damage_seen canceled=… guarded=… dmg=… src=…}（用来回答"谁先取消的"）；
     * ③ 什么都不取消、不改血量。
     */
    @SubscribeEvent(receiveCanceled = true, priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onLivingAttackObserved(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        try {
            ShanhaiGuardTriageBridge.noteDamage(player);
        } catch (Throwable ignored) {
            // 分诊坏了绝不许影响伤害流程
        }
        final long now = System.currentTimeMillis();
        if (now - LAST_ATTACK_SEEN_MS >= 1000L) {
            LAST_ATTACK_SEEN_MS = now;
            try {
                ShanhaiMod.LOGGER.info("{} damage_seen canceled={} guarded={} dmg={} src={} player={}",
                        ShanhaiGuardTriageBridge.PREFIX, event.isCanceled(), isGuarded(player),
                        event.getAmount(), event.getSource().getMsgId(), player.getName().getString());
            } catch (Throwable ignored) {
                // 观测坏了绝不许影响伤害流程
            }
        }
    }

    // ═══════════════════════════════ ② 拦死亡 ═══════════════════════════════

    /**
     * 即将死亡 —— 只拦「持有者」，并<b>当场把血回满</b>。
     *
     * <p>取消之后 Forge 的 {@code ForgeHooks.onLivingDeath} 返回 true ⇒ {@code LivingEntity#die}
     * 在 {@code this.dead = true} <b>之前</b>就 return ⇒ 死亡动画、掉落、掉经验、死亡界面全部不会开始。
     * 补血是第二道保险：万一血是从别处被清零的，这里立刻补回去。
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!ShanhaiGuardCore.enabled) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        final float maxHealth = player.getMaxHealth();
        if (!ShanhaiGuardCore.blocksDeath(isGuarded(player))) {
            return;
        }
        event.setCanceled(true);
        // 🔴 2026-10-17：补血改走【两条腿】（setHealth → 自证 → 直写同步血量 → 再自证）——
        //    有的第三方 mod 的杀人链不过 setHealth、还会把别人的 setHealth 取消掉、给 getHealth() 加偏移，
        //    ⇒ 只走 setHealth 会把"其实没补上"当成功。日志里带 heal_channel=setHealth/direct/failed。
        //    "缺不缺"也要用【字段真值】判（读不到才退回 getHealth()），不能只信 getHealth()。
        final float missing = ShanhaiGuardCore.healAmount(true,
                ShanhaiGuardCore.trustedHealth(player.getHealth(), ShanhaiGuardValueLayer.readHealthField(player)),
                maxHealth);
        if (missing > 0f) {
            final ShanhaiGuardValueLayer.HealOutcome heal = ShanhaiGuardValueLayer.healTwoLegs(player);
            if (heal.landed()) {
                logDetail(KIND_HEAL, heal.detail);
                chat(player, KIND_HEAL);   // 🆕 聊天栏（日志那一行照旧在上面）
            } else {
                // 两条腿都没落地 ⇒ 明确报失败（不假装成功，也不发"已补回"）
                logDetail(KIND_HEAL_FAIL, heal.detail);
            }
        }
        // 🆕 T 件①②：死亡事件 ⇒ R1 置位；已经拦下（上面 setCanceled）⇒ R2 的"救过"置位。
        //    两行都只读；读数的落盘在 onServerTick 的拍末（那时才取"救完之后"的字段真值血量）。
        ShanhaiGuardTriageBridge.noteDeath(player);
        ShanhaiGuardTriageBridge.noteRescued(player);
        log(KIND_DEATH);
        chat(player, KIND_DEATH);   // 🆕 聊天栏（只发给他本人；日志那一行照旧在上面）
        noteIntercept(player);      // 🆕 反击退：拦到死亡也算"我们确实拦到了东西"
    }

    // ═══════════════════════════════ ③ 每 tick 兜底 ═══════════════════════════════

    /**
     * 服务端每 tick 末 —— <b>只处理持有者</b>，绝不遍历世界。
     *
     * <p>🔴 两条硬约束：
     * <ol>
     *   <li><b>每 tick 复位 {@code deathTime}/{@code hurtTime}</b>（与血量无关）——
     *       {@code LivingEntity#tickDeath} 是 {@code ++deathTime; if (deathTime >= 20) remove(KILLED);}，
     *       有人会每 tick 把 {@code deathTime} 写到满 20 直接处决，那时血条是满的 ⇒
     *       只判「血量 &lt; 满血」会漏掉整条路径。（该判据的负对照见核里的 {@code mutantResetOnlyWhenHurt}。）</li>
     *   <li><b>绝不做批量清零</b>：本方法只遍历<b>玩家表</b>（几十个），拿到持有者就立刻 {@code continue}，
     *       对非持有者<b>一个字段都不碰</b>。</li>
     * </ol>
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        // 1.20.1（Forge 47.4.16）没有 TickEvent.ServerTickEvent.Post 这个子类 —— 实测 javap：
        //   public class TickEvent$ServerTickEvent extends TickEvent { ... haveTime(); getServer(); }
        // 只有 phase 字段，所以「tick 末」= phase == END（与本工程既有探针同一写法）。
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 🆕 T 件④：进世界后【一次】的关键类扫描（换服务器实例 = 新的一局）。
        //    刻意放在下面那个 enabled 闸门【之前】：关掉守卫也该看得见"谁改过 LivingEntity"。
        //    它只做 Class.forName(initialize=false) + getDeclaredMethods()，一个字段都不写。
        ShanhaiGuardTriageBridge.onServerTickHead(event.getServer());
        if (!ShanhaiGuardCore.enabled) {
            return;
        }
        final MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        final List<ServerPlayer> roster = server.getPlayerList().getPlayers();
        final Set<UUID> present = new HashSet<>(Math.max(8, roster.size() * 2));
        for (final ServerPlayer player : roster) {
            final UUID id = player.getUUID();
            present.add(id);
            // 🔴 只处理持有者（含"曾经持有过"的登记）：从没持有过的人在这里就被挡掉，下面一个字段都不碰。
            if (holdsGuard(player)) {
                REMEMBERED.add(id);
            }
            if (!REMEMBERED.contains(id)) {
                continue;
            }
            guardOne(player, id);
            // 🆕 2026-10-17 反击退 / 反挤压：只对【受保护玩家】跑；guardOne 里主动放手（丢了模块）会把他
            //    从 REMEMBERED 里摘掉 ⇒ 这里再看一眼，摘掉的人一个字段都不碰。
            if (REMEMBERED.contains(id)) {
                guardKnockback(player, id);
            }
        }
        // 🆕 T 件②：拍末把这一拍的四个读数判成一句"解没解决"并落盘
        //    （同一 src 每人 5 秒最多一行；只读，绝不影响上面任何一个字段）
        ShanhaiGuardTriageBridge.flushTick(roster);
        // 名单里已经没有的人 ⇒ 连登记一起清掉（避免无限增长；也避免把离线玩家的坐标留着乱拉）
        REMEMBERED.retainAll(present);
        TRACKS.keySet().retainAll(present);
        ME_GATES.keySet().retainAll(present);
        TOSSED_AT.keySet().retainAll(present);
        KB_STATES.keySet().retainAll(present);
        LAST_HELD_TICK.keySet().retainAll(present);   // 🆕 2026-11-20：宽限锚点也随名单回收（防泄漏）
        GROUND_DROP.keySet().retainAll(present);      // 🆕 2026-11-20：地面补件的引用也随名单回收
    }

    /**
     * 🔴 <b>补五件全在这一个方法里</b>（每 tick 只对<b>这一个持有者</b>跑，绝不遍历世界、绝不做批量清零）：
     *
     * <pre>
     *   ① 传送通道   位置离谱（|x|/|z| &gt; 3.0e7 ／ |y| &gt; 4096 ／ NaN）⇒ 拉回自己记的「上次正常位置」
     *   ② 被移除检测 isRemoved() ／ isAddedToWorld()==false ／ 不在 ServerLevel.players() 里
     *   ⑤ 两套名单   PlayerList 与 ServerLevel.players() 不一致 ⇒ 报警（以世界名单为准，判据合在 ② 里）
     *   ③ 回血自证   回完血当场读回复核；读血交叉校验与死亡标志矛盾 ⇒ 核里各记一条
     *   ④ 补回物品   只扫这个人的 背包 ＋ 副手 ＋ 盔甲；少了模块就补回（有人反射直调 dropAllDeathLoot）
     *   ＋ 原有兜底  无条件复位 deathTime / hurtTime
     * </pre>
     */
    private static void guardOne(ServerPlayer player, UUID id) {
        // ── ① 传送通道：位置离谱 ⇒ 拉回上一帧记录的「上次正常位置」 ──
        final ShanhaiGuardCore.PositionTrack track =
                TRACKS.computeIfAbsent(id, k -> new ShanhaiGuardCore.PositionTrack());
        final ShanhaiGuardCore.Decision pos = track.check(player.getX(), player.getY(), player.getZ());
        if (pos.yes) {
            final double backX = track.lastSaneX();
            final double backY = track.lastSaneY();
            final double backZ = track.lastSaneZ();
            player.teleportTo(backX, backY, backZ); // ServerPlayer 版：同时把位置同步给客户端
            player.setDeltaMovement(0.0, 0.0, 0.0); // 别带着"被传送时"的速度继续飞
            logDetail(KIND_TELEPORT, pos.reason);
            chat(player, KIND_TELEPORT);   // 🆕 聊天栏（日志那一行照旧在上面）
        }

        // ── ②⑤ 被移除检测：两套名单（PlayerList vs ServerLevel.players()）＋ isRemoved ＋ isAddedToWorld ──
        //    🔴 2026-11-20：整段抽成【公开方法】{@link #rescueRemovedNow(ServerPlayer)} ——
        //       第二层（扩展 {@code ShanhaiStateAuthority}）在它自己的采样点上也调<b>同一个</b>方法，
        //       按用户口径「把它挪进/复用第二层，别写两套」：判据与三步动作只有这一份实现。
        rescueRemovedNow(player);

        // ── ③ 回血（两条腿）＋ 当场自证（两条腿都失败 ⇒ 核里记一次失败，不静默） ──
        //    🔴 2026-10-17：有的第三方 mod 的杀人链不过 setHealth、还会把别人的 setHealth 取消掉
        //    ⇒ 只走 setHealth 会"以为补上了、其实没补"。这里改走 ShanhaiGuardValueLayer#healTwoLegs：
        //    setHealth → 自证 → 直写同步血量字段（第二通道）→ 用字段真值再自证；日志带 heal_channel=…。
        //    "缺不缺"也要用【字段真值】判（读不到才退回 getHealth()，因为对手给 getHealth() 加了偏移）。
        final float maxHealth = player.getMaxHealth();
        final float fieldHealth = ShanhaiGuardValueLayer.readHealthField(player);
        final float missing = ShanhaiGuardCore.healAmount(true,
                ShanhaiGuardCore.trustedHealth(player.getHealth(), fieldHealth), maxHealth);
        if (missing > 0f) {
            final ShanhaiGuardValueLayer.HealOutcome heal = ShanhaiGuardValueLayer.healTwoLegs(player);
            if (heal.landed()) {
                logDetail(KIND_HEAL, heal.detail);   // 日志里带 heal_channel=setHealth/direct
                chat(player, KIND_HEAL);   // 🆕 聊天栏（日志那一行照旧在上面）
                noteIntercept(player);     // 🆕 反击退：补血也算窗口内的一次拦截
            } else {
                // 🔴 两条腿都没落地（setHealth 被 cancel ＋ 第二通道不可用/被改回）⇒ **不**发"已补回"（那会是假话）
                logDetail(KIND_HEAL_FAIL, heal.detail);
            }
        }

        // ── ③′ 🔴 2026-10-17 新增：值层兜底·第二道 —— 把"已经进入死亡流程"的人拉回来 ──
        //    判据在核里（deathFlowResetDecision，三条任一为真：isDeadOrDying / deathTime>0 /
        //    🔴 同步血量字段真值 <= 0）—— 只看原版读数，不引用任何第三方 mod 的类名/方法名/数据键。
        //    复位动作与 ④ die 拒绝共用同一个工具方法（两条腿补血 ＋ deathTime=0 ＋ hurtTime=0）。
        //    ⚠️ 这一条【不】计入"补血成功"的计数：它是另一件事（把死亡流程拉回来），
        //        日志用独立类别（KIND_RESET）＋ 独立闸门（DETAIL，每秒最多一行）。
        final boolean dying = player.isDeadOrDying();
        final int deathTimeBefore = player.deathTime;
        final int hurtTimeBefore = player.hurtTime;
        final float fieldHealthNow = ShanhaiGuardValueLayer.readHealthField(player);   // 复位前【重新读一次】（上面刚补过血）
        final ShanhaiGuardCore.Decision resetDecision = ShanhaiGuardCore.deathFlowResetDecision(
                dying, deathTimeBefore, fieldHealthNow, ShanhaiGuardCore.enabled);
        if (resetDecision.yes) {
            final ShanhaiGuardValueLayer.HealOutcome reset = ShanhaiGuardValueLayer.resetDeathFlow(player);
            logDetail(KIND_RESET, "isDeadOrDying=" + dying + " ／ deathTime 原值=" + deathTimeBefore
                    + " ／ hurtTime 原值=" + hurtTimeBefore
                    + " ／ " + reset.detail
                    + " ／ " + ShanhaiGuardCore.trustedHealthReason(player.getHealth(), fieldHealthNow)
                    + " ｜ " + resetDecision.reason);
            chat(player, KIND_RESET);   // 聊天栏（只给他本人；同一套闸门，每秒最多一行）
            noteIntercept(player);      // 🆕 反击退：把"已经进了死亡流程"的人拉回来也算一次拦截
        }

        // ── ③b 读血交叉校验：getHealth() 与 isDeadOrDying/deathTime/hurtTime 互相矛盾 ⇒ 记一条 ──
        final ShanhaiGuardCore.Decision blood = ShanhaiGuardCore.crossCheckBlood(
                player.getHealth(), player.isDeadOrDying(), player.deathTime, player.hurtTime);
        if (blood.yes) {
            logDetail(KIND_BLOOD, blood.reason);
        }

        // ── ④ 补回物品：只扫【这一个持有者】的 背包 ＋ 副手 ＋ 盔甲（绝不遍历世界） ──
        //    🔴 2026-10-10 收紧：只有【被动失去】才补回 ——
        //       ① 背包（含副手＋盔甲）里没有 ② 不是通过「丢出」通道离开的 ③ 打开的容器菜单里也没有
        //       ＋ AE/ME 网络里也没有多一份（探针**只在背包里没有的那一刻查一次**，绝不每 tick 查）
        final boolean inInventory = hasModuleInInventory(player);
        final boolean viaDrop = !inInventory && takeTossMarker(id, player.level().getGameTime());
        if (viaDrop) {
            // 🔴 按键丢出 = 主动放手 ⇒ 本局卸下保护、不补回（后面的兜底也不再做）
            final ShanhaiGuardCore.Decision disarm =
                    ShanhaiGuardCore.disarmDecision(true, inInventory, viaDrop);
            if (disarm.yes) {
                REMEMBERED.remove(id);
                TRACKS.remove(id);
                TOSSED_AT.remove(id);
                ME_GATES.remove(id);
                // 🔴 2026-11-20：宽限窗口的锚点也必须一起清 —— 否则"主动丢掉模块"会被宽限窗口复活 5 秒
                LAST_HELD_TICK.remove(id);
                GROUND_DROP.remove(id);   // 主动放手 ⇒ 地面补件的记账也一起清（他不是"被清空"那一类）
                logDetail(KIND_DISARM, disarm.reason);
                chat(player, KIND_DISARM);   // 🆕 聊天栏（用户样例③：你把模块交了出去，保护已卸下）
                return;
            }
        }
        final boolean inOpenContainer = hasModuleInOpenContainer(player);
        final ShanhaiGuardCore.MeProbeGate meGate = ME_GATES.computeIfAbsent(id, k -> new ShanhaiGuardCore.MeProbeGate());
        if (meGate.needsProbe(inInventory)) {
            // 🔴 只在【背包里没有】的那一刻查一次 ME；查不到／接口不可用 ⇒ 当作没有，但留一行日志说明是哪一种
            final ShanhaiGuardCore.MeProbeOutcome outcome = probeMeNetwork(player);
            meGate.record(outcome);
            logDetail(KIND_ME, "AE/ME 探针（只在背包里没有的那一刻查一次）：" + outcome.detail());
        }
        final ShanhaiGuardCore.Decision restore = ShanhaiGuardCore.restoreModuleDecision(
                true, inInventory, viaDrop, inOpenContainer, meGate.outcome());
        if (restore.yes) {
            logDetail(KIND_RESTORE, "要补回了（先给提示）：" + restore.reason); // 🔴 先给一条提示（走同一套日志闸门）
            final String placement = giveModule(player);
            final boolean ok = placement != null;
            ShanhaiGuardCore.noteRestoreResult(ok);
            if (ok) {
                // 🔴 2026-11-20 用户点名的那一行：`module_restored slot=…`
                logDetail(KIND_RESTORE, "module_restored " + placement + " ｜ 原因：" + restore.reason);
                // 🆕 聊天栏：只有【真放回背包/脚下】才告诉玩家（失败那一条只进日志，不跟玩家说"补回失败"）
                chat(player, KIND_RESTORE);
            } else {
                // 🔴 补不回来 = 保护即将失效 ⇒ 这是**一级故障**，用 WARN 打明（不假装成功）
                logDetail(KIND_RESTORE, "module_restore_failed（背包满 ＋ 落地也失败 ／ 物品未注册）"
                        + " ⇒ 保护会在宽限窗口（" + HOLD_GRACE_TICKS + " tick）之后失效 ｜ 原因："
                        + restore.reason);
            }
        }

        // ── 原有兜底：🔴 无条件每 tick 复位（不是「只在血未满时复位」） ──
        player.deathTime = 0;
        player.hurtTime = 0;
    }

    /**
     * 🔴 <b>「见 {@code isRemoved()} 就救回」—— 全工程唯一一份实现</b>（2026-11-20 从 {@link #guardOne} 抽出来）。
     *
     * <h2>为什么必须抽成公开方法（用户口径）</h2>
     * <pre>
     *   「把它挪进/复用第二层，别写两套」
     * </pre>
     * 第二层的采样点（扩展 {@code ShanhaiStateAuthority} 的 {@code ServerTickEvent.START} 与
     * {@code PlayerTickEvent.START}）要判"被移除"这件事 —— 而"被移除的玩家<b>不再 tick</b>"
     * ⇒ 那两条路里只有 {@code ServerTickEvent.START} 还看得到他。
     * 把动作<b>复制</b>一份到扩展里是不允许的（判据/顺序一旦漂移就是两套行为），
     * ⇒ 现在两边调<b>同一个</b>方法，判据与三步动作都还住在这一个方法里。
     *
     * <h2>原来的三条硬约束（一个字都没改，只是搬了位置）</h2>
     * <ol>
     *   <li><b>顺序死</b>：放回位置 → {@code revive()} → {@code addFreshEntity()}（核里 {@code rescueRemovedEntity} 保证）；</li>
     *   <li><b>判返回值</b>：{@code addFreshEntity} 返回 false ⇒ 报警，不当成功；</li>
     *   <li><b>先过滤「正常离开」</b>：换维度／登出／正常死亡／区块卸载一律不碰。</li>
     * </ol>
     *
     * @param player 受保护玩家（调用方负责判）
     * @return true = 这一次<b>真的救回来了</b>（{@code rescue.yes}）；false = 没救回来 / 按规矩不动手
     */
    public static boolean rescueRemovedNow(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        final ShanhaiGuardCore.Decision removed = ShanhaiGuardCore.removalDecision(
                player.isRemoved(), player.isAddedToWorld(), true, inLevelRoster(player));
        if (!removed.yes) {
            return false;   // 三条都正常 ⇒ 一条日志都不打（每 tick 都会走到这里）
        }
        final ShanhaiGuardCore.PositionTrack track =
                TRACKS.computeIfAbsent(player.getUUID(), k -> new ShanhaiGuardCore.PositionTrack());
        // 🔴 判出来之后**真的动手救**（原来只打一行 warning ⇒ 永远回不来）。
        //    三条硬约束全在核里执行：① 顺序 = 放回位置 → revive() → addFreshEntity()
        //    ② 判 addFreshEntity 的返回值（false ⇒ 报警，不当成功）
        //    ③ 先过滤「正常离开」：只有【被外力移除】才动手 —— 换维度／登出／正常死亡／区块卸载一律不碰
        //    ⚠️ 玩家实体本轮**只做实体层**：四套名单（PlayerList／连接／tab／level.players()）的完整重建不做。
        final Entity.RemovalReason rr = player.getRemovalReason();
        final ShanhaiGuardCore.Decision rescue = ShanhaiGuardCore.rescueRemovedEntity(
                ShanhaiGuardCore.enabled,
                rr == null ? null : rr.name(),
                rr != null && rr.shouldDestroy(),
                player.isAddedToWorld(),
                true, // 这就是玩家实体（ServerPlayer）⇒ 原因串里必须写清"只做实体层"
                new ShanhaiGuardCore.RescueOps() {

                    @Override
                    public void restorePosition() {
                        // ① 先把位置放回去（重挂是按"当前位置"挑区块的）；只动实体层坐标，不碰连接
                        if (track.hasLastSane()) {
                            player.setPos(track.lastSaneX(), track.lastSaneY(), track.lastSaneZ());
                        }
                    }

                    @Override
                    public void revive() {
                        player.revive(); // ② 实体层复活（必须在 addFreshEntity 之前）
                    }

                    @Override
                    public boolean addFreshEntity() {
                        final ServerLevel level = player.serverLevel();
                        if (level == null) {
                            return false; // 没有世界 ⇒ 明确报失败，不当成功
                        }
                        return level.addFreshEntity(player); // ③ 返回值照原样上报
                    }
                });
        if (rescue.yes) {
            logDetail(KIND_RESCUED, rescue.reason);
            chat(player, KIND_RESCUED);   // 🆕 聊天栏
            noteIntercept(player);        // 🆕 反击退：拦到"被移除"也算窗口内的一次拦截
            return true;
        }
        // 没救回来（或按规矩不动手）⇒ 一定留一行原因，绝不静默
        logDetail(KIND_REMOVED, removed.reason + " ｜ 救回：" + rescue.reason);
        chat(player, KIND_REMOVED);   // 🆕 聊天栏（只报"你被移除了"这件事，不报救回结果）
        return false;
    }

    // ═══════════════════════════════ ③c 🆕 2026-10-17 反击退 / 反挤压（只刹车，不搬人） ═══════════════════════════════
    //
    // 🔴 用户口径（逐字，两条都是规格）：
    //   「我还需要反击退（包括碰撞箱挤压我），这个猫把我扔来扔去的」
    //   「我不想做拉回，会误伤很多别的 mod 的东西」
    // ⇒ 只改速度与标志：① hurtMarked 置 false ② 窗口内 + 水平速度 > 0.6 ⇒ deltaMovement 的 x/z 清零、y 保留。
    //   🔴 没有位置快照、没有 setPos、没有"卡墙回退"（那是搬人，用户明确否掉）。
    //
    // 🔴 为什么"只改速度"也能挡住击退（读原版源码得到的顺序，不是猜；两处行号在报告里）：
    //   ① ServerLevel#tick 的次序是「实体 tick（伤害/击退在这里发生，会 markHurt() 并把速度加到 deltaMovement）
    //      → entityManager.tick()（ChunkMap#tick → ServerEntity#sendChanges）→ tick 结束」；
    //   ② ServerEntity#sendChanges 里 `if (entity.hurtMarked) { broadcastAndSend(new ClientboundSetEntityMotionPacket(entity));
    //      entity.hurtMarked = false; }` —— 它把实体当前 deltaMovement 当"击退速度"发给客户端；
    //   ③ TickEvent.ServerTickEvent(END) 在 MinecraftServer#tickServer 的【最后一行】才触发 ⇒ 我们拿到手的
    //      是"已经跑过 sendChanges"的那一帧 ⇒ 所以本方法做两件事：清掉标志（下一 tick 不会再被当成击退）
    //      ＋ 把 x/z 清零后**再发一个速度包**（同一个连接上它排在原版那个包之后 ⇒ 客户端最终速度 = 刹住的那个）。
    //   ⚠️ 以上顺序来自读 1.20.1 源码 + javap（见报告），**没有在游戏内实测**；报告里按"推断/未实测"标注。

    /**
     * 🆕 <b>反击退 / 反挤压</b>：每 tick 只对<b>这一个受保护玩家</b>跑（绝不遍历实体、绝不遍历世界）。
     *
     * <pre>
     *   开关关（/shanhai guard knockback off）⇒ 一个字段都不碰
     *   ① Entity#hurtMarked == true ⇒ 置 false（并记一次 cleared）
     *   ② 「刚被攻击」窗口开着（我们最近 20 tick 内确实拦过东西）＋ 四条豁免都不命中
     *      ＋ 水平速度 hypot(dx,dz) &gt; 0.6 ⇒ setDeltaMovement(0, y, 0)（只清 x/z）＋ 给本人补一个速度包
     * </pre>
     *
     * <p>🔴 本方法里<b>不许出现</b> {@code setPos} ／ {@code teleportTo} ／ 位置快照 ／ {@code isInWall}
     * （用户新口径：不搬人）。静态判据：对这两个新文件 grep 这几个词 ⇒ 0 命中（报告里给了读数）。
     */
    private static void guardKnockback(ServerPlayer player, UUID id) {
        // 开关只读一次（读一个 volatile 布尔很便宜，但"读到一半变了"会让日志出现自相矛盾的读数）
        final boolean enabled = ShanhaiGuardSwitches.knockbackGuard();
        if (!enabled) {
            return;   // 关掉 ⇒ 本功能完全不动玩家
        }
        final ShanhaiGuardKnockback.PlayerState state =
                KB_STATES.computeIfAbsent(id, k -> new ShanhaiGuardKnockback.PlayerState());
        final long nowTick = player.level().getGameTime();

        // ── 「刚传送」豁免的记账（换维度 ／ 复活（实体 id 变了）／ ServerPlayer#isChangingDimension()）──
        final String dim = player.level().dimension().location().toString();
        final boolean justTeleported = state.observe(player.getId(), dim, player.isChangingDimension());

        // ── ① 清零击退意图 ──
        final ShanhaiGuardCore.Decision clear = ShanhaiGuardKnockback.hurtMarkedDecision(
                true,          // 调用点已经按 REMEMBERED 过滤（只对受保护玩家跑）
                player.hurtMarked,
                enabled);
        if (clear.yes) {
            player.hurtMarked = false;   // Entity#hurtMarked 是 public 字段（javap 已确认）
            state.noteHurtMarkedCleared();
            ShanhaiGuardKnockback.noteHurtMarkedCleared();
            logKnockback(player, "why=hurt_marked ／ " + clear.reason);
        }

        // ── ② 窗口内刹车：只清 x/z，y 原样保留（不搬人）──
        final Vec3 delta = player.getDeltaMovement();
        final ShanhaiGuardKnockback.Exempt exempt = ShanhaiGuardKnockback.exemptOf(
                player.isSpectator(),          // Player#isSpectator（javap: public abstract，ServerPlayer 实现）
                player.isFallFlying(),         // LivingEntity#isFallFlying（javap: public）
                player.getVehicle() != null,   // Entity#getVehicle（javap: public）
                justTeleported);
        final ShanhaiGuardCore.Decision brake = ShanhaiGuardKnockback.brakeDecision(
                delta.x, delta.z,
                state.windowOpen(nowTick),
                exempt,
                enabled);
        if (!brake.yes) {
            return;
        }
        player.setDeltaMovement(0.0, delta.y, 0.0);   // 🔴 只清 x/z；y 保留（不下沉/不浮空）
        state.noteBraked();
        ShanhaiGuardKnockback.noteBraked();
        // 让"刹车"真的到客户端：同一个连接上这个包排在原版那个击退包之后 ⇒ 客户端最终速度 = 0（x/z）
        // ⚠️ 这是【速度包】（ClientboundSetEntityMotionPacket），不是位置包 —— 不搬人。
        try {
            if (player.connection != null) {
                player.connection.send(new ClientboundSetEntityMotionPacket(player));
            }
        } catch (Throwable t) {
            // 发包失败不影响"服务端已刹住"这件事实，但必须留痕（不静默）
            ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX
                    + "knockback_guard motion_packet_failed player=" + player.getName().getString()
                    + " err=" + t);
        }
        logKnockback(player, "why=brake ／ " + brake.reason + " ／ 拦截于 tick=" + state.lastInterceptTick()
                + " ／ y 保留=" + delta.y);
    }

    /**
     * 🆕 记一次「我们确实拦到了东西」—— 这会打开/续上「刚被攻击」窗口（{@value ShanhaiGuardKnockback#INTERCEPT_WINDOW_TICKS} tick）。
     *
     * <p>只有这几条路会调它（用户口径：「拦到伤害/拒死/补血/拦移除任一都算」）：
     * {@link #onLivingAttack}（拦伤害）／{@link #onLivingDeath}（拦死亡 ＋ 补血）／
     * {@link #guardOne}（补血落地 ／ 死亡流程复位 ／ 救回被移除）／{@link #noteDieRefused}（拒死）。
     * <b>别的 mod 的弹射 / 跳板 / 技能推人不会调它</b> ⇒ 窗口不打开 ⇒ 我们一条都不刹车。
     */
    private static void noteIntercept(Player player) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        KB_STATES.computeIfAbsent(sp.getUUID(), k -> new ShanhaiGuardKnockback.PlayerState())
                .noteIntercept(sp.level().getGameTime());
    }

    /**
     * 🆕 反击退的日志（<b>每秒最多一行</b>，沿用 {@link ShanhaiGuardCore.LogGate} 的"同类合并"口径）
     * ＋（播报开着时）**同一拍**给本人说一句左下角。
     *
     * <pre>
     *   [SHANHAI-GUARD] knockback_guard 本秒 3 次 ｜ 逐次计数 cleared=12 braked=7 ｜ 机制=… ｜ 末次：why=brake ／ …
     * </pre>
     *
     * <p>为什么播报挂在这个方法里、而不是单独的调用点：两者必须<b>同一拍</b>，
     * 否则"日志一行 ＋ 播报一行"就变成两倍刷屏（用户口径：每秒最多 1 行、默认不播）。
     */
    private static void logKnockback(ServerPlayer player, String reason) {
        final ShanhaiGuardCore.LogGate.Batch batch = KNOCK.admitBatch(KIND_KNOCKBACK, System.currentTimeMillis());
        if (batch == null) {
            return;   // 同一秒里已经有过一行 ⇒ 这一次只合并进下一行（不刷屏）
        }
        final long n = batch.merged().getOrDefault(KIND_KNOCKBACK, 0L);
        ShanhaiMod.LOGGER.info(ShanhaiGuardCore.LOG_PREFIX + "knockback_guard 本秒 " + n + " 次 ｜ 逐次计数 cleared="
                + ShanhaiGuardKnockback.hurtMarkedClearedTotal() + " braked="
                + ShanhaiGuardKnockback.brakedTotal() + " ｜ 机制=" + ShanhaiGuardCore.MECH_KNOCKBACK
                + " ｜ 末次：" + reason);
        // ── 左下角播报（默认不播；只有 knockback_broadcast=true 才发，且只发给本人）──
        if (!ShanhaiGuardSwitches.knockbackBroadcast()) {
            return;
        }
        try {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(ShanhaiGuardKnockback.broadcastText(n)), true);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX
                    + "knockback_guard broadcast_failed player=" + player.getName().getString() + " err=" + t);
        }
    }

    // ═══════════════════════════════ ③b 【主动放手】丢出通道 ═══════════════════════════════

    /**
     * 🔴 <b>「丢出」通道</b>：玩家把物品丢出去时开火（按键 Q ／ 从容器里丢出都会走到这里）。
     *
     * <p>为什么必须单独认这个通道：用户原话「我选1，但是放入箱子或者 ae 或者我们的机器槽位中也算主动啊，
     * 不需要补啊」⇒ <b>按键丢出 = 主动放手</b> ⇒ 本局卸下保护、不补回。
     *
     * <p>本方法<b>只登记</b>（记下时刻），判据在核里（窗口 ＋ "背包里已经没有模块"）——
     * 这样"丢的是多出来的那一份"不会被误判成放手。
     */
    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (!ShanhaiGuardCore.enabled) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!isGuardStack(event.getEntity().getItem())) {
            return;
        }
        TOSSED_AT.put(player.getUUID(), player.level().getGameTime());
    }

    // ═══════════════════════════════ ④ 抹掉死亡界面 ═══════════════════════════════
    //
    // 🔴 2026-10-10：这一段**整段搬走**了 —— 它原来住在下面那个位置上，用的是
    //    Minecraft（player 字段类型 = LocalPlayer）／ DeathScreen ／ ScreenEvent，
    //    全是【只有客户端才有的类】；而本类是自动订阅者 ⇒ 专服上也会被加载 ⇒
    //    专服冒烟当场炸：
    //      java.lang.RuntimeException: Attempted to load class net/minecraft/client/player/LocalPlayer
    //                              for invalid dist DEDICATED_SERVER
    //    ⇒ 现在搬到 com.shanhai.client.guard.ShanhaiGuardClientEvents，
    //      并挂 @Mod.EventBusSubscriber(value = Dist.CLIENT)（dist 不匹配 ⇒ Forge 不会加载那个类）。
    //    ⚠️ 本文件从此**不许出现**客户端专属的那一族（Minecraft ／ DeathScreen ／ ScreenEvent），
    //       也不许 import 客户端包 —— 写成"客户端包前缀 + net.minecraft"都会让这条 grep 门失效，
    //       所以这里连注释里都不写那个前缀。用法是：grep 客户端包名 ⇒ 必须 0 命中。
    //       javac 拦不住这个问题（它是运行期 dist 校验），判据是 grep ＋ javap -v 查常量池里的 Class 条目。

    // ═══════════════════════════════ 「持有」判据 ═══════════════════════════════

    /**
     * 背包 ＋ 副手 ＋ 盔甲 ＋ 主手里有没有 {@code shanhai:genesis_reality_modification_module}。
     *
     * <p>按<b>物品 id</b> 判：{@code BuiltInRegistries.ITEM.getKey(stack.getItem())}。
     */
    public static boolean holdsGuard(Player player) {
        if (player == null) {
            return false;
        }
        // 主手 / 副手（需求点名的两处，显式查，不依赖「背包里也有一份」）
        if (isGuardStack(player.getMainHandItem()) || isGuardStack(player.getOffhandItem())) {
            return true;
        }
        final Inventory inventory = player.getInventory();
        if (inventory != null) {
            if (containsGuard(inventory.items)) {
                return true;
            }
            if (containsGuard(inventory.armor)) {
                return true;
            }
            if (containsGuard(inventory.offhand)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsGuard(Iterable<ItemStack> stacks) {
        if (stacks == null) {
            return false;
        }
        for (final ItemStack stack : stacks) {
            if (isGuardStack(stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGuardStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        return ShanhaiGuardCore.isGuardItem(id.getNamespace(), id.getPath());
    }

    // ═══════════════════════════════ 补五件用的取值/执行助手 ═══════════════════════════════

    /**
     * 🔴 <b>「他算不算受保护」的总入口</b>＝ 当前持有 ＋ <b>登记过（曾持有）</b> ＋ <b>宽限窗口</b>。
     *
     * <p>后者的必要性：补件④ 的场景是"背包被掏空" ⇒ 那一刻 {@link #holdsGuard} 已是 false；
     * 若四个事件都只认"当前持有"，掏空背包就等于<b>一招破防</b>（伤害/死亡/死亡界面全部放行）。
     *
     * <h2>🆕 2026-11-20：补一条<b>宽限窗口</b>（用户实测过的破防形态）</h2>
     * <pre>
     *   「创始现实修改模块被清空 ⇒ isGuarded 变 false ⇒ 全套防护失效」
     * </pre>
     * 登记表（{@link #REMEMBERED}）只在两个地方减人：玩家离开名单、以及<b>他自己按键丢出</b>
     * （那是主动放手，必须减）。但"被外力清空一帧"与"他主动丢出"在<b>物品栏读数上长得一模一样</b>，
     * 而这两件事的正确反应完全相反。⇒ 再加一条与登记表<b>无关</b>的、按 tick 计时的宽限：
     * 距 {@link #LAST_HELD_TICK}（最后一次<b>确认</b>持有的 tick）不超过
     * {@link #HOLD_GRACE_TICKS} ⇒ 仍然按受保护对待。
     *
     * <p>⚠️ 宽限<b>不会</b>让"主动丢出"复活：那条路会把 {@link #LAST_HELD_TICK} 一起清掉
     * （见 {@link #guardOne} 的 disarm 分支），所以卸下保护仍然是<b>当场</b>生效。
     *
     * <p>⚠️ 客户端也会调本方法（那边没有 {@code ServerTickEvent} 在维护登记表）
     * ⇒ 拿 {@code player.level().getGameTime()} 当 tick 源，两侧都能算。
     */
    public static boolean isGuarded(Player player) {
        if (player == null) {
            return false;
        }
        if (holdsGuard(player)) {
            noteHeld(player);       // 记下"最后一次确认持有的 tick"（宽限窗口的锚点）
            return true;
        }
        if (REMEMBERED.contains(player.getUUID())) {
            return true;
        }
        return inHoldGrace(player);
    }

    /**
     * 模块丢了之后的宽限窗口（tick）。<b>20 tick = 1 秒；这里取 100 tick = 5 秒。</b>
     *
     * <p>⚠️ 与扩展模块 {@code com.shanhai.add.guard.state.ShanhaiStateRules#HOLD_GRACE_TICKS}
     * <b>必须同值</b>（两个模块不能互相 import 常量）；开机自证那一行会把两个数都打出来 ⇒ 漂了看得见。
     */
    public static final int HOLD_GRACE_TICKS = 100;

    /** 每个玩家「最后一次<b>确认</b>持有模块的 tick」（宽限窗口的锚点；<b>表里没这条</b> = 从没持有过）。 */
    private static final Map<UUID, Long> LAST_HELD_TICK = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 🔴 每个玩家「我们丢在脚下的那一件模块」—— <b>防复制物品</b>的唯一判据（2026-11-20）。
     *
     * <p>{@link #guardOne} 每 tick 跑；背包满的时候它会一直想补回。
     * 只要脚下那一件还 {@code !isRemoved()} 就<b>不再丢第二件</b>
     * （否则就是 20 件/秒的凭空造物 —— 比"模块被清"更糟；项目纪律：
     * "「缺了就补」会造出复制物品，那是原版刷物品漏洞级别"）。
     * 被捡起／消失（{@code isRemoved()} 变 true）之后才允许再补。
     *
     * <p>只在服务端线程读写 ⇒ 与其它几张表同生命周期（{@code ServerTickEvent} 里随名单回收）。
     */
    private static final Map<UUID, ItemEntity> GROUND_DROP = new HashMap<>();

    /** 记一次"确认持有"（只在 {@link #isGuarded} 命中当前持有时调，成本 = 一次 map put）。 */
    private static void noteHeld(Player player) {
        try {
            final long now = player.level() == null ? 0L : player.level().getGameTime();
            LAST_HELD_TICK.put(player.getUUID(), Long.valueOf(now));
        } catch (Throwable ignored) {
            // 记不上只意味着"宽限窗口不生效"，不影响当前持有的判断
        }
    }

    /** 现在还在不在宽限窗口内（模块刚丢但还没超时）。 */
    private static boolean inHoldGrace(Player player) {
        try {
            final Long last = LAST_HELD_TICK.get(player.getUUID());
            if (last == null) {
                return false;
            }
            final long now = player.level() == null ? 0L : player.level().getGameTime();
            final long elapsed = now - last.longValue();
            if (elapsed < 0L || elapsed > (long) HOLD_GRACE_TICKS) {
                return false;
            }
            // 只在【真的靠宽限保住】的时候留一行（限流在 DETAIL 闸门里：同类合并 ＋ 每秒最多一行）
            logDetail(KIND_HOLD_GRACE, "player=" + player.getName().getString()
                    + " since_last_held_ticks=" + elapsed + " grace_ticks=" + HOLD_GRACE_TICKS
                    + " ⇒ 仍按受保护对待（等补件动作把模块放回去）");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 宽限窗口那一条的日志类别（诊断类：只进日志，不进聊天栏）。 */
    private static final String KIND_HOLD_GRACE = "guard_hold_grace";


    /**
     * 🔴 <b>④ 拒绝死亡</b>（2026-10-17 新增）：mixin 在 {@code die(DamageSource)} 的 {@code HEAD} 判出
     * "这是受保护玩家" ⇒ 取消死亡后，由这里<b>走与其它拦截同一套闸门与文案表</b>报出去：
     *
     * <pre>
     *   日志一路：logDetail(KIND_DIE_REFUSED, "命中点=… ／ 两句读数")   ← [SHANHAI-GUARD] 拦截 拒绝死亡 …
     *   聊天一路：chat(player, KIND_DIE_REFUSED)                      ← [创始现实修改模块] REFUSED die (…) xN
     * </pre>
     *
     * <p>⚠️ 只发给<b>他本人</b>（{@link #chat} 里只有收件人一个）；两条路都走"同类合并 ＋ 每秒最多一行"，
     * 所以即便有人每 tick 调一次 {@code die} 也不会刷屏。
     *
     * <p>⚠️ 这个方法由 {@code com.shanhai.common.guard.ShanhaiGuardValueLayer#refuseDie} 调用（mixin → 执行层 → 这里），
     * 所以只能是包内可见（{@code private} 调不到）。
     *
     * @param player   被拒绝死亡的玩家（受保护的那一个）
     * @param callSite 命中点（{@code LivingEntity#die} / {@code ServerPlayer#die}）
     * @param reason   判据与两条腿补血的读数
     */
    static void noteDieRefused(ServerPlayer player, String callSite, String reason) {
        logDetail(KIND_DIE_REFUSED, "命中点=" + callSite + " ／ " + reason);
        chat(player, KIND_DIE_REFUSED);
        noteIntercept(player);   // 🆕 反击退：拒死也算"我们确实拦到了东西"
    }

    /**
     * 🔴 补件⑤：他还在不在<b>世界名单</b>里 —— 以 {@code ServerLevel.players()} 为准
     * （{@code PlayerList} 与它会不一致，两套名单分裂本身就是报警条件）。
     */
    private static boolean inLevelRoster(ServerPlayer player) {
        final ServerLevel level = player.serverLevel();
        if (level == null) {
            return false;
        }
        final List<ServerPlayer> worldPlayers = level.players();
        return worldPlayers != null && worldPlayers.contains(player);
    }

    /** 补件④：<b>只扫这一个玩家的</b>背包 ＋ 副手 ＋ 盔甲（主手在 {@code items} 里，已覆盖）。 */
    private static boolean hasModuleInInventory(Player player) {
        final Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        return containsGuard(inventory.items) || containsGuard(inventory.armor) || containsGuard(inventory.offhand);
    }

    /**
     * 🔴 判据③：<b>当前打开的容器菜单里有没有它</b> —— 用 {@code player.containerMenu}
     * ⇒ 一次覆盖：箱子 ／ 我们的机器槽位 ／ 各种 GUI（它们都是 {@code AbstractContainerMenu}）。
     *
     * <p>注意：玩家自己没开任何容器时 {@code containerMenu} 就是 {@code inventoryMenu}（背包本体），
     * 那时这里读到的就是背包 —— 与 ① 同结论，不会误判成"主动放手"。
     */
    private static boolean hasModuleInOpenContainer(Player player) {
        final AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) {
            return false;
        }
        for (final Slot slot : menu.slots) {
            if (isGuardStack(slot.getItem())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 🔴 <b>AE/ME 探针</b>：他丢出的那一份，是不是被他自己放进 AE 网络了
     * （用户原话：「放入箱子或者 <b>ae</b> 或者我们的机器槽位中也算主动啊」）。
     *
     * <p>用的是本工程既有的那套 ME 接口（AE2 的 {@code IGrid} → {@code IStorageService#getInventory()}
     * → {@code MEStorage#getAvailableStacks()} ＋ {@code AEItemKey}，与
     * {@code SuperWildcardPatternBufferPartMachine} 里 {@code getMainNode().getGrid()} 那条链同一族）。
     *
     * <p>🔴 <b>只在"背包里没有"的那一刻被调用一次</b>（由 {@link ShanhaiGuardCore.MeProbeGate} 保证），
     * 绝不每 tick 查。<b>查不到／接口不可用一律当作"没有"</b>（宁可信其无），但会把是哪种情况写进日志。
     */
    private static ShanhaiGuardCore.MeProbeOutcome probeMeNetwork(ServerPlayer player) {
        try {
            final Item item = moduleItem();
            if (item == null) {
                return ShanhaiGuardCore.MeProbeOutcome.UNAVAILABLE; // 物品没注册 ⇒ 探针不可用，不当"没有"以外的任何结论
            }
            final AEItemKey key = AEItemKey.of(item);
            if (key == null) {
                return ShanhaiGuardCore.MeProbeOutcome.UNAVAILABLE;
            }
            boolean sawLinkedTerminal = false;
            for (final ItemStack stack : carriedStacks(player)) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                if (!(stack.getItem() instanceof WirelessTerminalItem terminal)) {
                    continue; // 只认"能接网"的无线终端
                }
                final IGrid grid = terminal.getLinkedGrid(stack, player.level(), player);
                if (grid == null) {
                    continue; // 这份终端没接网（未绑定 / 不在访问点范围内）⇒ 换下一份
                }
                sawLinkedTerminal = true;
                final MEStorage storage = grid.getStorageService().getInventory();
                if (storage == null) {
                    continue;
                }
                final KeyCounter counter = storage.getAvailableStacks();
                if (counter != null && counter.get(key) > 0L) {
                    return ShanhaiGuardCore.MeProbeOutcome.FOUND;
                }
            }
            // 接上网了但里面没有 ⇒ NOT_FOUND（仍然补回）；一份都没接上 ⇒ UNAVAILABLE（当作没有，日志里说明）
            return sawLinkedTerminal ? ShanhaiGuardCore.MeProbeOutcome.NOT_FOUND
                    : ShanhaiGuardCore.MeProbeOutcome.UNAVAILABLE;
        } catch (Throwable t) {
            // 接口抛异常 ⇒ 当作"没有"（宁可信其无），但记下是哪一种（日志由调用方打）
            ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX + "AE/ME 探针抛异常（当作没有）：" + t);
            return ShanhaiGuardCore.MeProbeOutcome.UNAVAILABLE;
        }
    }

    /** 他随身的全部物品栏（主手/副手/背包/盔甲）—— 探针只在这里找"能接网的无线终端"。 */
    private static Iterable<ItemStack> carriedStacks(Player player) {
        final java.util.List<ItemStack> all = new java.util.ArrayList<>();
        all.add(player.getMainHandItem());
        all.add(player.getOffhandItem());
        final Inventory inventory = player.getInventory();
        if (inventory != null) {
            for (final ItemStack s : inventory.items) {
                all.add(s);
            }
            for (final ItemStack s : inventory.armor) {
                all.add(s);
            }
            for (final ItemStack s : inventory.offhand) {
                all.add(s);
            }
        }
        return all;
    }

    /** 那一件模块的 {@code Item}（没注册 ⇒ null，不无中生有）。 */
    private static Item moduleItem() {
        final Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(
                ShanhaiGuardCore.MODULE_NAMESPACE, ShanhaiGuardCore.MODULE_PATH));
        return (item == null || item == Items.AIR) ? null : item;
    }

    /**
     * 🔴 取一次「丢出」登记（<b>读即消费</b>）：窗口内 ⇒ true；窗口外/没有 ⇒ false。
     *
     * <p>消费语义很重要：一次丢出只影响随后那一次判定，不会留着污染下一次（比如他丢的是多出来的那一份）。
     */
    private static boolean takeTossMarker(UUID id, long nowTick) {
        final Long tossedAt = TOSSED_AT.remove(id);
        if (tossedAt == null) {
            return false;
        }
        return ShanhaiGuardCore.leftViaDropChannel(nowTick, tossedAt, false);
    }

    /**
     * 补件④：补回一件模块（有人反射直调 {@code dropAllDeathLoot} 把背包掏空）。
     *
     * <h2>🔴 2026-11-20：把"模块被清"当成一级故障（用户实测的破防形态）</h2>
     * <pre>
     *   「创始现实修改模块被清空 ⇒ isGuarded 变 false ⇒ 全套防护失效」
     * </pre>
     * 旧版：{@code inventory.add(...)} 返回 false（<b>背包满</b>）就只打一行"补回失败" ⇒
     * 玩家永远拿不回来 ⇒ 保护永久失效。现在改成<b>两级落点</b>：
     * <pre>
     *   ① 背包有空位 → 放回去，日志 slot=&lt;n&gt; placed=inventory
     *   ② 背包满了   → 【丢在脚下】并记一行 WARN，日志 slot=feet placed=ground warn=inventory_full
     * </pre>
     * ⚠️ 落地点<b>刻意不用</b> {@code Player#drop(...)}：那条路会发 {@code ItemTossEvent} ⇒
     * 被本类自己的 {@link #onItemToss} 记成"他按键丢出去了" ⇒ 下一拍判成<b>主动放手</b> ⇒
     * <b>反而把保护卸掉</b>（正是要修的那个 bug 的加强版）。所以这里直建 {@code ItemEntity} 挂进世界。
     *
     * <h2>🔴 防复制物品（实现时自己踩到的坑，写死在这里）</h2>
     * {@code guardOne} 是<b>每 tick</b> 跑的，而"背包满"这件事不会因为丢了一件就变好 ⇒
     * 天真的写法会<b>每 tick 往脚下丢一个模块</b>（20 个/秒的凭空造物，比原 bug 更糟）。
     * ⇒ 判据：<b>同一时刻脚下最多存在一件我们丢的模块</b>（{@link #GROUND_DROP} 持有那个实例，
     * 只要它 {@code !isRemoved()} 就不再丢）；它被捡起/消失之后才允许再补。
     *
     * @return {@code null} = 没补上（物品没注册 ／ 落地也失败）；否则是一段可判读的放置说明
     *         （形如 {@code slot=12 placed=inventory} ／ {@code slot=feet placed=ground warn=inventory_full}）
     */
    private static String giveModule(ServerPlayer player) {
        final Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(
                ShanhaiGuardCore.MODULE_NAMESPACE, ShanhaiGuardCore.MODULE_PATH));
        if (item == null || item == Items.AIR) {
            return null; // 物品根本没注册 ⇒ 明确报失败，不无中生有
        }
        final Inventory inventory = player.getInventory();
        if (inventory == null) {
            return null;
        }
        final int free = inventory.getFreeSlot();
        final ItemStack stack = new ItemStack(item);
        if (inventory.add(stack)) {
            player.inventoryMenu.broadcastChanges(); // 立刻同步给客户端，否则玩家看不见补回来的模块
            GROUND_DROP.remove(player.getUUID());    // 回到背包 ⇒ 这一局的地面补件结束
            // slot = 我们**事先**探测到的空位（-1 表示并进了已有的同类堆叠）
            return "slot=" + free + " placed=inventory";
        }
        // ── 背包满了：丢在脚下（🔴 不走 Player#drop —— 那会发 ItemTossEvent ⇒ 被当成主动放手）──
        final UUID id = player.getUUID();
        final ItemEntity alive = GROUND_DROP.get(id);
        if (alive != null && !alive.isRemoved() && alive.level() == player.level()) {
            // 脚下那一件还在 ⇒ 不再丢第二件（防复制物品；这一条是"每 tick 跑"逼出来的硬约束）
            return "slot=ground placed=ground_alive warn=inventory_full drop_once=true";
        }
        try {
            final ItemEntity dropped = new ItemEntity(
                    player.level(), player.getX(), player.getY() + 0.5D, player.getZ(), new ItemStack(item));
            dropped.setPickUpDelay(0);   // 落地就能捡回来（这是我们的补件，不该像玩家丢的那样延后）
            final boolean ok = player.level().addFreshEntity(dropped);
            if (!ok) {
                return null;             // 挂进世界也失败 ⇒ 如实报失败，不假装成功
            }
            GROUND_DROP.put(id, dropped);
            return "slot=feet placed=ground warn=inventory_full drop_once=true";
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn(ShanhaiGuardCore.LOG_PREFIX
                    + "module_restore_drop_failed player=" + player.getName().getString() + " err=" + t);
            return null;
        }
    }

    // ═══════════════════════════════ 日志 ═══════════════════════════════

    /** 先只打一行 {@code [SHANHAI-GUARD] 拦截 …}（同类合并 ＋ 每秒最多一行）。横幅通道由主代理后接。 */
    private static void log(String kind) {
        final String line = LOG.admit(kind, System.currentTimeMillis());
        if (line != null) {
            ShanhaiMod.LOGGER.info(line);
        }
    }

    /**
     * 🔴 <b>细节行</b>（补件③④⑤ 用）：带原因串，走独立的 {@link #DETAIL} 闸门 —— 同样"每秒最多一行"，
     * 但会把该窗口内<b>最后一条</b>原因串打出来。
     *
     * <p>为什么必须打出来：核里的失败计数（{@code healFailureCount()} / {@code bloodContradictionCount()} /
     * {@code moduleRestoreFailCount()}）如果没人看就等于"静默失效"；这里保证至少每秒有一行可见的原因。
     */
    private static void logDetail(String kind, String reason) {
        final String line = DETAIL.admit(kind, System.currentTimeMillis());
        if (line != null) {
            ShanhaiMod.LOGGER.warn(line + " ｜ 末次：" + reason);
        }
    }

    // ═══════════════════════════════ 聊天栏（2026-10-10 用户点单） ═══════════════════════════════
    //
    // 用户原话（逐字就是规格）：
    //   「还有那个拦截输出的我希望打在聊天栏里面，然后前缀是 [创始现实修改模块]」
    // 🔴 三点口径：① 日志那一路【照旧保留】（不是替换）—— 上面 log/logDetail 一个字都没动；
    //              ② 只发给持有者本人（收件人由核里的 chatRecipients 给出），**绝不广播**；
    //              ③ 走 {@link #CHAT} 同一套闸门（同类合并 ＋ 每秒最多一行）⇒ 不刷屏。

    /**
     * 🔴 <b>把这一次拦截【也】告诉持有者本人</b>（聊天栏那一条路）。
     *
     * <pre>
     *   不是服务端玩家实体（假人／还没同步）  ⇒ 一条都不发（**绝不广播**）
     *   诊断类（读血矛盾／回血自证失败／AE 探针）⇒ 不进聊天栏（也不占那一秒的名额）
     *   同一秒里已经有过了                      ⇒ 合并进下一行（每秒最多一行）
     *   其余                                   ⇒ 给他本人发一条：{@code [创始现实修改模块] …人话…}
     * </pre>
     *
     * <p>投递方式照本工程既有做法（{@code ShanhaiRecipeStats#deliverBanner} ／ {@code ShanhaiNewsCommand}）：
     * 服务端、逐行、{@code ServerPlayer#sendSystemMessage(Component.literal(...))}
     * —— <b>只有收件人这一个玩家</b>，不走任何"广播给所有人"的通道。
     */
    private static void chat(Player player, String kind) {
        if (!(player instanceof ServerPlayer holder)) {
            return;   // 不是服务端玩家实体 ⇒ 一条都不发（绝不广播）
        }
        if (ShanhaiGuardCore.chatPhrase(kind, 1L) == null) {
            return;   // 诊断类 / 不认识的类别：不进聊天栏（也不占"那一秒一个名额"）
        }
        final ShanhaiGuardCore.LogGate.Batch batch = CHAT.admitBatch(kind, System.currentTimeMillis());
        if (batch == null) {
            return;   // 同一秒里已经有过一条 ⇒ 这一次合并进去，不刷屏
        }
        final String line = batch.chatLine();
        if (line != null) {
            sendLine(holder, line);
        }
        // 🔴 2026-11-08：这一拍同时是"热改播报的落盘点" —— 上面那次 admitBatch 会把攒着的
        //    原始正文一起带出来（见核里的 Batch#rawLines）。不发就等于把它们丢了。
        for (final String rawLine : batch.rawLines()) {
            sendLine(holder, rawLine);
        }
    }

    // ═══════════════════════════════ 热改播报（2026-11-08，用户点单） ═══════════════════════════════
    //
    // 用户口径（逐字就是规格）：「把主动热改废掉敌方代码这件事播报到玩家聊天栏」，三条样例：
    //   [创始现实修改模块] PATCHED examplemod EntityHelper#forceSetHealth -> RETURN (health)
    //   [创始现实修改模块] PATCH_REFUSED examplemod EntityHelper#forceSetHealth (gate_already_patched)
    //   [创始现实修改模块] PATCH_REVERTED examplemod EntityHelper#forceSetHealth (observed_only)
    //
    // 🔴 三条硬约束（用户硬要求）：
    //   ① **复用这同一条通道**（这一个类、这一个 {@link #CHAT} 闸门、这一个前缀、这一个收件人规则）
    //      —— 不新建 socket、不开第二条聊天通道；
    //   ② 正文**纯 ASCII**：非 ASCII 一律**拒收并返回 false**（判据在核 {@code isBroadcastableBody}，
    //      自检 ⑱ 带负对照）；
    //   ③ 前缀后面那**一个半角空格**必须保留（{@link ShanhaiGuardCore#CHAT_PREFIX} 自带，这里不另拼）；
    //   ④ 控制台／没有玩家上下文 ⇒ **一条都不发**（那一侧日志照旧，不受这里影响）；
    //   ⑤ 一次 patch 改了几个方法 ⇒ **每个方法一行**（核里 rawLines 是"一条正文一行"，不拼成一行）。

    /**
     * 🔴 <b>通用入口：把一条任意 ASCII 正文以 {@code [创始现实修改模块] } 前缀发给他本人</b>。
     *
     * <pre>
     *   holder 不是服务端玩家 / null        ⇒ false（控制台、无玩家上下文：一条都不发）
     *   他不是受保护玩家（没那件模块）      ⇒ false（收件人规则照旧 = 只发给持有者本人）
     *   正文非 ASCII / 空白 / 带换行        ⇒ false（硬判据，核里就拒收）
     *   正文可播                            ⇒ true（已收下：可能当场发，也可能进下一行合并）
     * </pre>
     *
     * @param holder   收件人（<b>只有他一个人</b>；{@code null} ⇒ 不发）
     * @param asciiBody 正文（<b>不含</b>前缀；必须纯 ASCII）
     * @return 收下了没有（{@code false} = 这条没发、也不会补发）
     */
    public static boolean chatRawBody(ServerPlayer holder, String asciiBody) {
        if (asciiBody == null) {
            return false;
        }
        return chatRawBodies(holder, List.of(asciiBody)) > 0;
    }

    /**
     * 🔴 同 {@link #chatRawBody(ServerPlayer, String)}，但<b>一次投多条</b>（一次 patch 改了同类多个方法）。
     *
     * <p>为什么要"一次投多条"而不是逐条调：这几种正文属于<b>同一个事件</b>，一次投进去它们才落在
     * <b>同一个窗口</b>里 ⇒ 当场就能一起发（"每个方法一行"）。逐条调的话第二条会被"每秒最多一行"
     * 推到下一拍去。
     *
     * <p>本方法可以在<b>任何线程</b>调：不是服务端线程时会把投递排到服务端线程
     * （{@code MinecraftServer#execute}），绝不在工作线程上碰玩家连接。
     *
     * @return 收下了几条（0 = 没发：没有玩家上下文 ／ 不是持有者 ／ 正文全都不可播 ／ 没有服务端）
     */
    public static int chatRawBodies(ServerPlayer holder, List<String> asciiBodies) {
        if (holder == null || asciiBodies == null || asciiBodies.isEmpty()) {
            return 0;   // 控制台 / 没有玩家上下文 ⇒ 一条都不发（那一侧日志照旧）
        }
        if (!isGuarded(holder)) {
            return 0;   // 收件人规则：只有持有「创始现实修改模块」的受保护玩家才收得到
        }
        final List<String> ok = ShanhaiGuardCore.broadcastableBodies(asciiBodies);
        if (ok.isEmpty()) {
            return 0;   // 🔴 非 ASCII / 空白：一条都不发（硬判据；返回 false 的负对照在核的自检 ⑱）
        }
        final MinecraftServer server = holder.getServer();
        if (server == null) {
            return 0;   // 还没挂到服务端（登录中／已断开）⇒ 不发
        }
        final String[] bodies = ok.toArray(new String[0]);
        if (server.isSameThread()) {
            deliverRaw(holder, bodies);
        } else {
            // 热改的工作线程会从这里进来：排队到服务端线程再发（绝不跨线程碰玩家连接）。
            server.execute(new Runnable() {
                @Override
                public void run() {
                    deliverRaw(holder, bodies);
                }
            });
        }
        return bodies.length;
    }

    /**
     * 真的把这一批原始正文交给闸门并发出去（<b>必须在服务端线程</b>）。
     *
     * @return 真的发出了几行（0 = 同一秒里已经有过一行，合并进下一拍）
     */
    private static int deliverRaw(ServerPlayer holder, String[] bodies) {
        final ShanhaiGuardCore.LogGate.Batch batch = CHAT.admitRawBatch(bodies, System.currentTimeMillis());
        if (batch == null) {
            return 0;   // 同一秒里已经有过一行 ⇒ 合并进去（走既有合并，不刷屏）
        }
        int sent = 0;
        for (final String line : batch.rawLines()) {
            sendLine(holder, line);
            sent++;
        }
        return sent;
    }

    /**
     * 逐行投递给<b>他一个人</b>（投递方式与 {@link #chat} 完全相同）。
     *
     * <p>收件人由核 {@code chatRecipients} 给出（第二个参数故意传 {@code null} —— 那条规则根本不看在线名单）。
     */
    private static void sendLine(ServerPlayer holder, String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        for (final ServerPlayer to : ShanhaiGuardCore.chatRecipients(holder, null)) {
            to.sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
        }
    }
}
