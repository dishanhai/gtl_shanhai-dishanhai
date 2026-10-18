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
 *   ⑦ 拒绝死亡    LivingEntity#die ＋ ServerPlayer#die 的 HEAD（mixin）→ ci.cancel()
 *                 ＋ 同一套复位动作；日志类别 KIND_DIE_REFUSED（走 logDetail/chat 闸门与文案表）
 *   ⑦′ setHealth 入参  LivingEntity#setHealth(float) 的 @ModifyVariable（mixin）→ 受保护玩家被写 &lt;=0 ⇒ 改成满血
 *   ⑧ 对手桥      ★2026-10-17★ 反射调对手自己的 public static（setHealthDelta / obtainHealthDelta /
 *                 checkEntityDead）＋ 清它 ForgeData 里的锁存计数器 ⇒ 日志 omnimobs_bridge=counter_reset / delta_reset；
 *                 ⚠ 这层依赖对手的类名/方法名，换版本就失效（失效只记一行 unavailable，自动退回值层）
 *   ⑨ 反击退      ★2026-10-17★ 每 tick 只对受保护玩家：① {@code hurtMarked} 置 false
 *                 ② 「刚被攻击」窗口（我们最近 20 tick 拦过东西）内 ＋ 水平速度 &gt; 0.6 格/tick
 *                 ⇒ {@code setDeltaMovement} 的 x/z 清零、y 保留（🔴 只刹车，不搬人：没有 setPos / 没有拉回）
 *                 ③ 豁免：鞘翅飞行 / 在载具里 / 旁观者 / 刚传送（2 tick）　④ 日志每秒最多一行、默认不播
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
    // 🔴 2026-10-17 新增一类（⑤ 对手桥维护：清它的 delta / 锁存计数器；诊断类，只进日志）
    private static final String KIND_OMNIMOBS = ShanhaiGuardCore.KIND_OMNIMOBS;
    // 🔴 2026-10-17 新增一类（反击退 / 反挤压；诊断类，只进日志、不进聊天栏）
    private static final String KIND_KNOCKBACK = ShanhaiGuardCore.KIND_KNOCKBACK;

    /** 细节日志闸门：与 {@link #LOG} 同一套「同类合并 ＋ 每秒最多一行」，但带原因串（只打每窗口的"末次"）。 */
    private static final ShanhaiGuardCore.LogGate DETAIL = new ShanhaiGuardCore.LogGate();

    /**
     * 🔴 <b>⑤ 对手桥自己的闸门</b>（2026-10-17 加）：同样"同类合并 ＋ 每秒最多一行"，
     * 但<b>单独一个</b> —— 它的原因串（{@code omnimobs_bridge=counter_reset / delta_reset}）很重要，
     * 不能和同一秒里的别的类别挤在 {@link #DETAIL} 那一行里被"末次"顶掉。
     */
    private static final ShanhaiGuardCore.LogGate OMNI = new ShanhaiGuardCore.LogGate();

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
        if (!ShanhaiGuardCore.blocksDamage(isGuarded(player))) {
            return;
        }
        event.setCanceled(true);
        log(KIND_DAMAGE);
        chat(player, KIND_DAMAGE);   // 🆕 聊天栏（只发给他本人；日志那一行照旧在上面）
        noteIntercept(player);       // 🆕 反击退：这一拍把「刚被攻击」窗口打开了（20 tick）
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
        //    对手的杀人链不过 setHealth、且给 setHealth 挂了单向锁存的 cancel、给 getHealth() 加了偏移，
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
        // 名单里已经没有的人 ⇒ 连登记一起清掉（避免无限增长；也避免把离线玩家的坐标留着乱拉）
        REMEMBERED.retainAll(present);
        TRACKS.keySet().retainAll(present);
        ME_GATES.keySet().retainAll(present);
        TOSSED_AT.keySet().retainAll(present);
        KB_STATES.keySet().retainAll(present);
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
        final ShanhaiGuardCore.Decision removed = ShanhaiGuardCore.removalDecision(
                player.isRemoved(), player.isAddedToWorld(), true, inLevelRoster(player));
        if (removed.yes) {
            // 🔴 2026-10-10：判出来之后**真的动手救**（原来只打一行 warning ⇒ 永远回不来）。
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
            } else {
                // 没救回来（或按规矩不动手）⇒ 一定留一行原因，绝不静默
                logDetail(KIND_REMOVED, removed.reason + " ｜ 救回：" + rescue.reason);
                chat(player, KIND_REMOVED);   // 🆕 聊天栏（只报"你被移除了"这件事，不报救回结果）
            }
        }

        // ── ⑤-1 🔴 2026-10-17 对手桥（补血之前）：清它的「单向锁存计数器」 ──
        //    对手的判据是 `counter >= 5 && 新值 > 当前血 ⇒ cancel setHealth`，而 counter 单向锁存
        //    ⇒ 不清它，我们下面两条腿里的第一条腿【永久无效】（这就是"中招后 setHealth 补血无效"的机制）。
        final String omniCounter = ShanhaiGuardValueLayer.clearOmnimobsCounter(player);
        if (omniCounter != null) {
            logOmni(omniCounter);
        }

        // ── ③ 回血（两条腿）＋ 当场自证（两条腿都失败 ⇒ 核里记一次失败，不静默） ──
        //    🔴 2026-10-17：对手的杀人链不过 setHealth、且给 setHealth 挂了单向锁存的 cancel
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
        //    判据在核里（deathFlowResetDecision，四条任一为真：isDeadOrDying / deathTime>0 /
        //    🔴 同步血量字段真值 <= 0 / 🔴 ⑤ 对手自己的 checkEntityDead 说死）。
        //    复位动作与 ④ die 拒绝共用同一个工具方法（两条腿补血 ＋ deathTime=0 ＋ hurtTime=0）。
        //    ⚠️ 这一条【不】计入"补血成功"的计数：它是另一件事（把死亡流程拉回来），
        //        日志用独立类别（KIND_RESET）＋ 独立闸门（DETAIL，每秒最多一行）。
        final boolean dying = player.isDeadOrDying();
        final int deathTimeBefore = player.deathTime;
        final int hurtTimeBefore = player.hurtTime;
        final float fieldHealthNow = ShanhaiGuardValueLayer.readHealthField(player);   // 复位前【重新读一次】（上面刚补过血）
        final boolean omniSaysDead = ShanhaiGuardValueLayer.omnimobsSaysDead(player);  // ⑤ 它自己的判死
        final ShanhaiGuardCore.Decision resetDecision = ShanhaiGuardCore.deathFlowResetDecision(
                dying, deathTimeBefore, fieldHealthNow, omniSaysDead, ShanhaiGuardCore.enabled);
        if (resetDecision.yes) {
            final ShanhaiGuardValueLayer.HealOutcome reset = ShanhaiGuardValueLayer.resetDeathFlow(player);
            logDetail(KIND_RESET, "isDeadOrDying=" + dying + " ／ deathTime 原值=" + deathTimeBefore
                    + " ／ hurtTime 原值=" + hurtTimeBefore + " ／ 对手说死=" + omniSaysDead
                    + " ／ " + reset.detail
                    + " ／ " + ShanhaiGuardCore.trustedHealthReason(player.getHealth(), fieldHealthNow)
                    + " ｜ " + resetDecision.reason);
            chat(player, KIND_RESET);   // 聊天栏（只给他本人；同一套闸门，每秒最多一行）
            noteIntercept(player);      // 🆕 反击退：把"已经进了死亡流程"的人拉回来也算一次拦截
        }

        // ── ⑤-3 🔴 2026-10-17 对手桥（补血之后，每 tick 兜一次）：清它的 getHealth() 偏移（delta）──
        //    它的 getHealth() = 血量字段 + delta ⇒ delta 归零后读数才是真值，假死/假血一起消失。
        //    清完再跑下面的读血交叉校验，"矛盾"才是真的矛盾（不是被 delta 造出来的）。
        final String omniDelta = ShanhaiGuardValueLayer.clearOmnimobsDelta(player);
        if (omniDelta != null) {
            logOmni(omniDelta);
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
            final boolean ok = giveModule(player);
            ShanhaiGuardCore.noteRestoreResult(ok);
            logDetail(KIND_RESTORE, ok ? "已补回一件（原因：" + restore.reason + "）"
                    : "补回失败（背包满 或 物品未注册），不假装成功");
            if (ok) {
                // 🆕 聊天栏：只有【真放回背包了】才告诉玩家（失败那一条只进日志，不跟玩家说"补回失败"）
                chat(player, KIND_RESTORE);
            }
        }

        // ── 原有兜底：🔴 无条件每 tick 复位（不是「只在血未满时复位」） ──
        player.deathTime = 0;
        player.hurtTime = 0;
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
     * 🔴 <b>「他算不算受保护」的总入口</b>＝ 当前持有 ＋ <b>登记过（曾持有）</b>。
     *
     * <p>后者的必要性：补件④ 的场景是"背包被掏空" ⇒ 那一刻 {@link #holdsGuard} 已是 false；
     * 若四个事件都只认"当前持有"，掏空背包就等于<b>一招破防</b>（伤害/死亡/死亡界面全部放行）。
     */
    public static boolean isGuarded(Player player) {
        if (player == null) {
            return false;
        }
        return holdsGuard(player) || REMEMBERED.contains(player.getUUID());
    }

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
     * @return 真的放进去了才 true；物品没注册 / 背包满 ⇒ false（由核记账，<b>不假装成功</b>）
     */
    private static boolean giveModule(ServerPlayer player) {
        final Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(
                ShanhaiGuardCore.MODULE_NAMESPACE, ShanhaiGuardCore.MODULE_PATH));
        if (item == null || item == Items.AIR) {
            return false; // 物品根本没注册 ⇒ 明确报失败，不无中生有
        }
        final Inventory inventory = player.getInventory();
        if (inventory == null) {
            return false;
        }
        final boolean added = inventory.add(new ItemStack(item));
        if (added) {
            player.inventoryMenu.broadcastChanges(); // 立刻同步给客户端，否则玩家看不见补回来的模块
        }
        return added;
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

    /**
     * 🔴 <b>⑤ 对手桥的行</b>（2026-10-17 加）：带 {@code omnimobs_bridge=counter_reset / delta_reset}，
     * 走它自己的 {@link #OMNI} 闸门 —— 同样"同类合并 ＋ 每秒最多一行"，但不会被同窗口别的类别顶掉。
     *
     * <p>为什么单独一行：用户要求"日志里区分来源"（{@code heal_channel=…} 与
     * {@code omnimobs_bridge=counter_reset|delta_reset}）—— 混在补血那一行里，读日志的人分不清
     * "这次是值层补上的、还是把它的偏移清掉之后才补上的"。
     */
    private static void logOmni(String reason) {
        final String line = OMNI.admit(KIND_OMNIMOBS, System.currentTimeMillis());
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
        if (line == null) {
            return;
        }
        // 🔴 收件人由核给出：**只有他一个人**（第二个参数故意传 null —— 核里那条规则根本不看在线名单）
        for (final ServerPlayer to : ShanhaiGuardCore.chatRecipients(holder, null)) {
            to.sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
        }
    }
}
