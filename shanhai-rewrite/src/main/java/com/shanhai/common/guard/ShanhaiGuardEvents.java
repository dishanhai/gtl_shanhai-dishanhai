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
 *   ② 拦死亡      LivingDeathEvent       → setCanceled(true) 当场 setHealth(max)
 *   ③ 每 tick 兜底 TickEvent.ServerTickEvent(END) → 补满 ＋ 复位 deathTime/hurtTime，只处理持有者
 *   ③b 丢出通道   ItemTossEvent          → 记下"他刚把模块丢出去了"（按键丢出＝主动放手）
 *   ④ 补回/放手   每 tick 里：只有【被动失去】才补回（背包没有 ＋ 非丢出通道 ＋ 打开的容器里没有 ＋ AE 里没有）
 *   ⑤ 救回        判出【被外力移除】后**真的动手**：放回位置 → revive() → addFreshEntity()（判返回值）
 *   ⑥ 聊天栏      ★2026-10-10 新增★ 上面每一次拦截都【同时】给持有者本人发一条聊天栏消息
 *                 （前缀 [创始现实修改模块] ＋ 人话；**只发给他一个人**，绝不广播；
 *                  走同一套闸门 ⇒ 同类合并 ＋ 每秒最多一行；**日志那一路照旧保留，不是替换**）
 *   ④′ 死亡界面  ★不在这里★ —— 它在 com.shanhai.client.guard.ShanhaiGuardClientEvents（客户端那半边）
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

    /** 细节日志闸门：与 {@link #LOG} 同一套「同类合并 ＋ 每秒最多一行」，但带原因串（只打每窗口的"末次"）。 */
    private static final ShanhaiGuardCore.LogGate DETAIL = new ShanhaiGuardCore.LogGate();

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
        final float missing = ShanhaiGuardCore.healAmount(true, player.getHealth(), maxHealth);
        if (missing > 0f) {
            player.setHealth(maxHealth);
            verifyHeal(player, maxHealth); // 🔴 补件③：回完血当场读回复核（读回 != 期望 ⇒ 核里记一次失败，不静默）
        }
        log(KIND_DEATH);
        chat(player, KIND_DEATH);   // 🆕 聊天栏（只发给他本人；日志那一行照旧在上面）
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
        }
        // 名单里已经没有的人 ⇒ 连登记一起清掉（避免无限增长；也避免把离线玩家的坐标留着乱拉）
        REMEMBERED.retainAll(present);
        TRACKS.keySet().retainAll(present);
        ME_GATES.keySet().retainAll(present);
        TOSSED_AT.keySet().retainAll(present);
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
            } else {
                // 没救回来（或按规矩不动手）⇒ 一定留一行原因，绝不静默
                logDetail(KIND_REMOVED, removed.reason + " ｜ 救回：" + rescue.reason);
                chat(player, KIND_REMOVED);   // 🆕 聊天栏（只报"你被移除了"这件事，不报救回结果）
            }
        }

        // ── ③ 回血 ＋ 当场读回复核（读回 != 期望 ⇒ 核里记一次失败） ──
        final float maxHealth = player.getMaxHealth();
        final float missing = ShanhaiGuardCore.healAmount(true, player.getHealth(), maxHealth);
        if (missing > 0f) {
            player.setHealth(maxHealth);
            final ShanhaiGuardCore.Decision proof = ShanhaiGuardCore.healRoundTrip(
                    ShanhaiGuardCore.healTarget(maxHealth), player.getHealth());
            if (proof.yes) {
                logDetail(KIND_HEAL_FAIL, proof.reason);
                // 🔴 自证失败（setHealth 可能被 mixin 掉）⇒ **不**发"已补回"（那会是假话），只进日志。
            } else {
                log(KIND_HEAL);
                chat(player, KIND_HEAL);   // 🆕 聊天栏（日志那一行照旧在上面）
            }
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

    /** 回完血当场读回复核（补件③）；读回 != 期望 ⇒ 核里已经记了一次失败，这里只负责把原因打出来。 */
    private static void verifyHeal(Player player, float maxHealth) {
        final ShanhaiGuardCore.Decision proof = ShanhaiGuardCore.healRoundTrip(
                ShanhaiGuardCore.healTarget(maxHealth), player.getHealth());
        if (proof.yes) {
            logDetail(KIND_HEAL_FAIL, proof.reason);
        }
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
