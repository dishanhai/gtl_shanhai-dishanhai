package com.shanhai.add.time;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.shanhai.ShanhaiMod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * 山海重构 · <b>mixin 的唯一转发点（服务端侧）</b>。
 *
 * <h2>🔴 为什么 mixin 里不许直接调 Minecraft 成员</h2>
 * 本工程的 mixin 注解处理器<b>只把 {@code @Inject} 选中的方法名</b>写进 refmap
 * （实证：{@code build\mixinrefmap\shanhai.refmap.json} 里只有方法映射，没有任何字段/@At 目标条目）。
 * 混入类体内直接调 MC 成员 ⇒ 生产环境拿<b>字面开发名</b>去 SRG 命名的目标类上找 ⇒ 找不到 ⇒ 混入失败或崩。
 * 所以既有约定（见 {@code ShanhaiSetBlockWatchMixin} 类注释 §2）是：
 * <b>混入类只当转发壳，所有 MC 调用下沉到普通类</b>（普通类会被 reobf，SRG 名正常）。
 *
 * <h2>暂停时到底怎么处理 {@code ServerLevel#tick}（三段式，逐段说明）</h2>
 * <ol>
 *   <li><b>区块照常加载/卸载</b>（{@link #tickChunkSourceOnly}）：
 *       {@code getChunkSource().tick(haveTime, false)}。
 *       🔴 第二个实参必须是 <b>{@code false}</b>：{@code true} 会调 {@code ServerChunkCache#tickChunks()}，
 *       那里包含<b>随机 tick / 刷怪 / 冰霜与雪 / 闪电</b> —— 那就"世界没停"了。</li>
 *   <li><b>玩家自己的服务端 tick 照常跑</b>（{@link #tickPlayersOnly}）：
 *       逐个调 {@code ServerLevel#tickNonPassenger(player)}（与 {@code entityManager.tick()} 内部
 *       用的是<b>同一条</b>路径：{@code setOldPosAndRot → ++tickCount → tick() → passengers}；
 *       见 {@code ServerLevel.java:685-700}）。</li>
 *   <li>其余（世界边界/天气/睡觉/skyBrightness/tickTime/blockTicks/fluidTicks/raids/方块事件/
 *       <b>所有非玩家实体</b>）全部跳过 —— 由 mixin 取消整个方法体完成。</li>
 * </ol>
 *
 * <h2>为什么全部判定都读 {@link ShanhaiWorldClock#SERVER}</h2>
 * 服务端权威只有一份。任何在别处另记一个 boolean 的做法都会造出"两头不一致"的半冻状态。
 */
public final class ShanhaiClockHooks {

    /** 上一次记进日志的"被保活的玩家数"（-1 = 还没打过）。 */
    private static int lastKeptPlayers = -1;
    /** 上一次打 kept 日志的墙钟（节流用）。 */
    private static long lastKeptLogMs;

    /** kept 日志心跳窗口：30 s（无变化时的兜底行）。 */
    private static final long KEPT_LOG_INTERVAL_MS = 30_000L;
    /**
     * kept 日志的<b>最小间隔</b>：1000 ms。
     *
     * <p>这是 2026-10-18 父代理要求的"每秒 ≤1 行"。它<b>优先于</b>"玩家数变化就立刻打"——
     * 否则 kept 在 0/1 之间抖动时就是 20 行/秒（刷屏）。折叠掉的次数由 {@code folded=} 报出。
     */
    private static final long KEPT_LOG_MIN_INTERVAL_MS = 1_000L;
    /** 本秒窗口内被折叠掉的 kept 调用次数。 */
    private static int keptLogFolded;
    /** 本秒窗口内 kept 的最小值 / 最大值 / 采样次数（用来证明"抖了多少"）。 */
    private static int keptLogMin;
    private static int keptLogMax;
    private static int keptLogSeen;
    /** 上一次打 WARN 的墙钟。 */
    private static long lastWarnMs;

    private ShanhaiClockHooks() {
    }

    /** 服务端 {@code ServerLevel#tick} 是否应被跳过（= 世界是否冻结）。 */
    public static boolean freezeServerLevelTick() {
        return ShanhaiWorldClock.SERVER.isPaused();
    }

    /**
     * 冻结时对 {@code ServerLevel#tick} 的<b>部分替代</b>：只保留"区块加载 + 玩家自身 tick"。
     *
     * <p>本方法<b>保证不抛异常</b>（内部每段各自 {@code try/catch}）：它一旦抛出去，
     * 调用方就不会取消原版 tick ⇒ 世界照常跑（"暂停失效"比崩更难查，但至少不崩）。
     *
     * @param level    目标世界（{@code (Object) this}，运行期就是 {@link ServerLevel}）
     * @param haveTime 原版形参（"这个 tick 还有没有时间预算"），转交给区块源
     * @return {@code true} ⇒ 调用方应当取消原版 {@code ServerLevel#tick}
     */
    public static boolean freezeServerLevelTick(Object level, BooleanSupplier haveTime) {
        if (!ShanhaiWorldClock.SERVER.isPaused()) {
            return false;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return true; // 类型不认识：只取消，不做替代（保守）
        }
        // 顺序刻意照抄原版：先区块源（ServerLevel.java:303），后实体（:355）
        tickChunkSourceOnly(serverLevel, haveTime);
        tickPlayersOnly(serverLevel);
        return true;
    }

    // ------------------------------------------------------------------ ① 区块照常加载

    /**
     * 只跑 {@code ServerChunkCache#tick(haveTime, false)}：
     * {@code purgeStaleTickets → runDistanceManagerUpdates → chunkMap.tick(haveTime) → clearCache}。
     *
     * <p>跳掉的只有 {@code tickChunks()}（随机 tick/刷怪/天气效果/闪电），因为第二个实参传的是 {@code false}。
     * ⇒ <b>区块照常生成、加载、卸载、发给客户端</b>，但方块与实体不动。
     */
    private static void tickChunkSourceOnly(ServerLevel level, BooleanSupplier haveTime) {
        try {
            level.getChunkSource().tick(haveTime, false);
        } catch (Throwable t) {
            warnThrottled("区块源替代 tick 失败（暂停期间可能看不到新区块）", t);
        }
    }

    // ------------------------------------------------------------------ ② 玩家自身 tick

    /**
     * 逐个把<b>本维度</b>的玩家按原版路径 tick 一次。
     *
     * <p>🔴 三条防重复/防漏的约束：
     * <ol>
     *   <li><b>不可能重复</b>：{@code ServerLevel#tick} 整个方法体被取消，{@code entityManager.tick()}
     *       不会跑；本方法每个外 tick 每个 level 只被调一次 ⇒ 同一个玩家一个外 tick 只 tick 一次。</li>
     *   <li><b>只 tick 本维度的玩家</b>：{@code player.level() != level} 的跳过（玩家只属于一个维度）。</li>
     *   <li><b>不动实体管理器</b>：不 add/remove，只调 tick。</li>
     * </ol>
     *
     * <p>副作用（有意为之）：玩家 tick 里会调 {@code serverLevel().getChunkSource().move(this)}
     * （{@code ServerPlayer.java:445}）⇒ 暂停期间玩家的区块追踪也跟着更新 ⇒ 配合 ① 新区块能正常送达。
     */
    private static void tickPlayersOnly(ServerLevel level) {
        List<ServerPlayer> all;
        try {
            all = level.getServer().getPlayerList().getPlayers();
        } catch (Throwable t) {
            warnThrottled("取玩家列表失败（暂停期间玩家自身状态不会推进）", t);
            return;
        }
        int kept = 0;
        // 拷一份再遍历：玩家 tick 里可能触发换维度/登出，直接遍历实时列表会 ConcurrentModificationException
        ServerPlayer[] snapshot = all.toArray(new ServerPlayer[0]);
        for (ServerPlayer player : snapshot) {
            try {
                if (player == null || player.isRemoved() || player.level() != level) {
                    continue;
                }
                level.tickNonPassenger(player);
                ++kept;
            } catch (Throwable t) {
                warnThrottled("玩家自身 tick 失败（该玩家本轮状态不推进）", t);
            }
        }
        logKeptPlayers(kept);
    }

    /**
     * {@code clock_player_tick} 判据行 —— <b>聚合限流：全局每秒最多 1 行</b>（2026-10-18 父代理要求）。
     *
     * <h2>为什么原来会刷屏</h2>
     * 旧条件是"玩家数变化就立刻打、不变则 30 s 打一次"。而暂停期间 {@code kept} 是<b>逐 tick</b>算的，
     * 只要出现"进/出维度、登录登出、某个维度这个 tick 恰好没有可 tick 的玩家"这类抖动，
     * {@code kept} 就会在 1/0 之间来回 ⇒ <b>每个 tick 一行</b>（20 行/秒），把日志淹掉。
     *
     * <h2>现在的口径（三件事同时成立才算一次"状态变化"）</h2>
     * <ul>
     *   <li>距上一次<b>真打</b>不足 {@link #KEPT_LOG_MIN_INTERVAL_MS}（=1000 ms）⇒ <b>折叠</b>，
     *       只把 {@link #keptLogFolded} 加一（窗口结束时用 {@code folded=} 报出来，<b>不许静默</b>）；</li>
     *   <li>窗口到了：若这 1 秒里发生过任何变化、或本来该打心跳，就打一行，
     *       内容 = <b>当前值</b> + 这 1 秒被折叠掉的次数 + 这 1 秒里出现过的最小/最大 kept；</li>
     *   <li>完全没有变化时不打（心跳仍有 {@link #KEPT_LOG_INTERVAL_MS} 兜底，最长 30 s 一行）。</li>
     * </ul>
     * ⇒ 最密 1 行/秒，最疏 1 行/30 秒，"抖了多少次"在行里可读（{@code folded=}）。
     */
    private static void logKeptPlayers(int kept) {
        long now = System.currentTimeMillis();
        if (kept < keptLogMin || keptLogSeen == 0) {
            keptLogMin = kept;
        }
        if (kept > keptLogMax || keptLogSeen == 0) {
            keptLogMax = kept;
        }
        ++keptLogSeen;

        boolean changed = kept != lastKeptPlayers;
        if (changed) {
            lastKeptPlayers = kept;
        }
        boolean heartbeatDue = now - lastKeptLogMs >= KEPT_LOG_INTERVAL_MS;
        if (now - lastKeptLogMs < KEPT_LOG_MIN_INTERVAL_MS) {
            // 1 秒窗口内：只折叠（不含"心跳"，心跳本来就是 30 s 级）
            ++keptLogFolded;
            return;
        }
        if (!changed && !heartbeatDue) {
            // 没变化、也没到心跳 ⇒ 本秒不用打（折叠计数留着，等下一次真打时一起报）
            return;
        }
        int folded = keptLogFolded;
        int min = keptLogMin;
        int max = keptLogMax;
        int calls = keptLogSeen;
        keptLogFolded = 0;
        keptLogMin = kept;
        keptLogMax = kept;
        keptLogSeen = 0;
        lastKeptLogMs = now;
        // 父代理要求的可 grep 判据行（聚合后：每秒 ≤1 行）
        ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] clock_player_tick kept={} folded={} calls={} min={} max={}"
                        + "（玩家自身状态照常；其余实体仍冻结）", kept, folded, calls, min, max);
    }

    /** 同一个问题最多每 30 s 打一次 WARN（暂停可能持续很久，别刷屏）。 */
    private static void warnThrottled(String message, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs < 30_000L) {
            return;
        }
        lastWarnMs = now;
        ShanhaiMod.LOGGER.warn("[SHANHAI-CLOCK] {}", message, t);
    }

    // ------------------------------------------------------------------ 其它判定

    /**
     * 世界写入硬网（<b>不带 level 的旧口径</b>，只剩 status 显示用）。
     *
     * <p>🔴 真正的拦截判定是 {@link #onLevelSetBlock(Object)}：它<b>按侧分流</b>
     * （服务端拦、客户端放行）。这个方法<b>不要</b>再挂到 mixin 上。
     */
    public static boolean denyWorldWrite() {
        return ShanhaiWorldClock.SERVER.isPaused();
    }

    /**
     * {@code Level#setBlock} 硬网的<b>唯一入口</b>（mixin 只调这一个）。
     *
     * <h2>🔴 为什么要带 level、为什么要按侧分流（2026-10-18 用户实机缺陷）</h2>
     * 旧实现只判 {@code SERVER.isPaused()}、不看这次写发生在哪一侧。而
     * {@code client\compat\GtlAddClientCompat} 的<b>藏环/恢复环是纯客户端写</b>
     * （{@code level.setBlock(pos, AIR, FLAGS)}，服务端从不 hide）⇒ 暂停期间客户的藏环写入
     * 被硬网一并拦掉 ⇒ 环上那一整片实体方块露出来（用户截图：机器的环变成一片方块墙）。
     *
     * <p>口径：<b>客户端写一律放行</b>（它改不了真世界，只是表现层）；服务端写照旧全拦。
     *
     * @param level {@code (Object) this}，运行期是 {@link net.minecraft.world.level.Level}
     * @return {@code true} ⇒ 调用方应当把 {@code setBlock} 的返回值改成 {@code false}
     */
    public static boolean onLevelSetBlock(Object level) {
        if (!ShanhaiWorldClock.SERVER.isPaused()) {
            return false;
        }
        boolean clientSide = level instanceof Level l && l.isClientSide();
        if (ShanhaiClockRules.denyWorldWrite(true, clientSide)) {
            ShanhaiClockGuard.note(ShanhaiClockGuard.Category.WORLD_WRITE, null);
            return true;
        }
        // 放行的是"客户端 cosmetic 写"：计一笔，供 status/日志证明按侧分流真的生效。
        noteClientWriteAllowed();
        return false;
    }

    /** 暂停期间被放行的客户端写累计次数（status 用）。 */
    private static long clientWriteAllowed;

    /** 上一次打"客户端写已放行"日志的墙钟（节流：30 s）。 */
    private static long lastClientWriteLogMs;

    public static long clientWriteAllowedCount() {
        return clientWriteAllowed;
    }

    /**
     * 记一笔"暂停期间放行的客户端写"。
     *
     * <p>为什么要留一行日志：这条正是缺陷 ⑤ 的判据——修好之后它应该在涨，
     * 而不是 {@code blocked=WORLD_WRITE} 在涨（后者=还在拦客户端）。
     */
    private static void noteClientWriteAllowed() {
        long n = ++clientWriteAllowed;
        long now = System.currentTimeMillis();
        if (now - lastClientWriteLogMs < 30_000L) {
            return;
        }
        lastClientWriteLogMs = now;
        // 可 grep 判据行：client_write_allowed 涨 + blocked 里没有 world_write
        ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] client_write_allowed={}（暂停期间的纯客户端表现写已放行：藏环/恢复环）", n);
    }

    /**
     * ① 暂停期间是否拒绝"玩家主动丢出物品"（Q / 拖出界面 / 弃置）。
     *
     * <h2>🔴 为什么必须挂在 {@code drop(boolean)} 的 HEAD，而不是 {@code ItemTossEvent}</h2>
     * {@code ServerPlayer#drop(boolean)}（47.3.0 sources 第 1674-1684 行）的顺序是：
     * <pre>
     *   ItemStack itemstack = inventory.removeFromSelected(p_182295_);   // ← 背包先扣
     *   ...
     *   return ForgeHooks.onPlayerTossEvent(this, itemstack, true) != null;  // ← 这里才发 ItemTossEvent
     * </pre>
     * 所以取消 {@code ItemTossEvent} 只是"不生成物品实体"，<b>扣掉的数量不会回来</b> ⇒
     * 用户看到"按 Q 背包数量减少、地上什么都没有"（凭空消失）。
     * HEAD 拦在扣减之前 ⇒ 数量不变、也没有实体。
     *
     * <p>客户端对应的是 {@code LocalPlayer#drop(boolean)}（{@code Minecraft#handleKeybinds} 第 1943 行调的），
     * 它在<b>本地</b>先 {@code removeFromSelected} 再发 {@code ServerboundPlayerActionPacket} ⇒
     * 只拦服务端的话客户端会先掉一个，所以两端都要挂（{@code ShanhaiClientClock#denyLocalDrop()}）。
     *
     * @param player {@code (Object) this}，运行期是 {@link Player}
     */
    public static boolean denyItemDrop(Object player) {
        if (!ShanhaiClockRules.denyItemDrop(ShanhaiWorldClock.SERVER.isPaused())) {
            return false;
        }
        ShanhaiClockGuard.note(ShanhaiClockGuard.Category.DROP, player instanceof Entity e ? e : null);
        return true;
    }

    /**
     * 容器点击是否拒绝（{@code AbstractContainerMenu#clicked} 那一层，客户端+服务端都会走）。
     *
     * <p>口径见 {@link ShanhaiClockRules#denyContainerClick}：非本人背包一律拒；
     * 本人背包放行"整理"，但 {@code THROW} 与 {@code -999}（丢到地上）照拒 ——
     * 后者在原版里会先 {@code safeTake} 再 {@code player.drop(...)}，被 {@code ItemTossEvent} 一取消
     * 就是<b>又一次凭空消失</b>。
     *
     * <p>⚠️ 这一层<b>盖不住 AE 终端</b>（它覆写了 {@code clicked} 且不调 super），
     * 那一半由 {@link #denyContainerPacketClick} 兜。
     *
     * @param menu      目标菜单（{@code (Object) this}）
     * @param player    操作者（可能为 {@code null}，原版 {@code clicked} 的形参不保证非空）
     * @param clickType {@code ClickType} 枚举（可能为 {@code null}）
     * @param slotId    槽位号（{@code -999} = 点 GUI 外面）
     */
    public static boolean denyContainerClick(Object menu, Object player, Object clickType, int slotId) {
        if (!ShanhaiWorldClock.SERVER.isPaused()) {
            return false;
        }
        if (!(menu instanceof AbstractContainerMenu)) {
            // 不认识的东西：保守放行（宁可漏拦一个第三方菜单，也不要在别人的 click 链里乱返回）
            return false;
        }
        boolean own = menu instanceof InventoryMenu;
        boolean throwClick = clickType instanceof ClickType ct && ct == ClickType.THROW;
        boolean outside = slotId == ShanhaiClockRules.SLOT_CLICKED_OUTSIDE;
        boolean deny = ShanhaiClockRules.denyContainerClick(true, own, throwClick, outside);
        if (deny) {
            ShanhaiClockGuard.note(ShanhaiClockGuard.Category.CONTAINER, player instanceof Entity e ? e : null);
        }
        return deny;
    }

    /**
     * 容器点击包（{@code ServerGamePacketListenerImpl#handleContainerClick}）是否整包拒绝。
     *
     * <h2>为什么必须有这一层（缺陷 ②：AE 能拿不能放）</h2>
     * AE2 的终端菜单<b>覆盖</b>了 {@code AbstractContainerMenu#clicked}：网络槽自己处理（<b>不调 super</b>），
     * 只有普通槽才交给 super ⇒ 我们挂 super HEAD 的拦截<b>只拦到"放回去"</b>，于是"能拿不能放"。
     * 而 {@code handleContainerClick} 是服务端<b>唯一</b>的容器点击漏斗（{@code ServerboundContainerClickPacket}
     * 只能从这里进来），且在菜单分派之前 ⇒ 不管子类怎么覆写都盖得住。
     *
     * <h2>为什么取消后要 {@code sendAllDataToRemote()}</h2>
     * 客户端对容器点击是<b>有本地预判</b>的（{@code AbstractContainerMenu#clicked} 在客户端也跑）。
     * 我们只取消服务端、不同步的话，客户端界面会显示"东西已经动了"而服务端没动 ⇒ 幽灵物品。
     * 整份状态回灌是最便宜的纠正手段（一次性、幂等）。
     */
    public static boolean denyContainerPacketClick(Object listener) {
        if (!ShanhaiWorldClock.SERVER.isPaused()) {
            return false;
        }
        if (!(listener instanceof ServerGamePacketListenerImpl impl)) {
            return false;
        }
        ServerPlayer player = impl.player;
        if (player == null) {
            return false;
        }
        AbstractContainerMenu menu = player.containerMenu;
        boolean own = menu instanceof InventoryMenu;
        if (!ShanhaiClockRules.denyContainerPacket(true, own)) {
            return false;
        }
        ShanhaiClockGuard.note(ShanhaiClockGuard.Category.CONTAINER, player);
        resyncMenu(menu);
        return true;
    }

    // ================================================================== AE 终端（第三方 mod ⇒ 全部 fail-soft）

    /**
     * AE2 的<b>两条 action 包</b>（{@code MEInteractionPacket} / {@code InventoryActionPacket}）
     * 的服务端入口是否<b>整包拒绝</b>。
     *
     * <h2>🔴 为什么上一版拦不住"拿出来"（javap 实证，2026-10-18）</h2>
     * 上一版只拦了原版那三条链（{@code handleContainerClick} / {@code AbstractContainerMenu#clicked} /
     * {@code Player#drop}）。而 AE2 15.4.10 的 ME 终端<b>一个都不走</b>，它用自己的包：
     * <pre>
     *   appeng.core.sync.packets.MEInteractionPacket#serverPacketData(ServerPlayer)
     *     → 校验 containerMenu 是 IMEInteractionHandler、containerId 对得上
     *     → MEStorageMenu#handleInteraction(long, InventoryAction)            ← 取 / 放 真实入口
     *   appeng.core.sync.packets.InventoryActionPacket#serverPacketData(ServerPlayer)
     *     → AEBaseMenu#doAction(ServerPlayer, InventoryAction, int, long)     ← 另一条（配置槽/合成格）
     * </pre>
     * 客户端 {@code MEStorageScreen#slotClicked} 里所有"点网络格子"的分支
     * （拿起 PICKUP_OR_SET_DOWN / 单放 SPLIT_OR_PLACE_SINGLE / Shift 搬整叠 SHIFT_CLICK /
     * 拖区域 MOVE_REGION / 自动合成 AUTO_CRAFT）<b>全部</b>调
     * {@code MEStorageMenu#handleInteraction}，而它客户端第一句就是
     * {@code if (isClientSide()) sendToServer(new MEInteractionPacket(...))} ⇒
     * <b>只挂 {@code InventoryActionPacket} 会照样拿得出来</b>。
     * 所以这一处必须挂在 {@code MEInteractionPacket} 上（{@code InventoryActionPacket} 是第二条、非唯一）。
     *
     * <h2>口径（唯一一格真值）</h2>
     * 暂停 且 {@code player.containerMenu} 不是 {@code InventoryMenu} ⇒ 拒（取与放对称）；
     * 判定本体在 {@link ShanhaiAeGate#deny(boolean, boolean)}，它直接复用
     * {@link ShanhaiClockRules#denyContainerPacket} —— <b>与原版容器点击包同一份代码</b>。
     *
     * <h2>取消后为什么要 {@code sendAllDataToRemote()}</h2>
     * 与原版容器点击同理：客户端对容器槽位有本地预判痕迹（光标/槽位快照），服务端拒绝后不回滚
     * ⇒ 整份状态回灌是最便宜且幂等的纠正手段。返回值失败只打日志（{@link #warnThrottled}）。
     *
     * <p>⚠️ <b>不拦</b>「打开终端 / 看网络里的物品 / 滚动 / 搜索」—— 它们不走这两个包
     * （服务器→客户端是 {@code MEInventoryUpdatePacket}，开界面是 {@code SwitchGuisPacket}）。
     *
     * @param playerObj {@code serverPacketData} 的形参（运行期是 {@link ServerPlayer}）
     * @param source    哪一个包（只进日志：{@code MEInteractionPacket} / {@code InventoryActionPacket}）
     * @return {@code true} ⇒ 调用方应当把这个包整包取消
     */
    public static boolean denyAeActionPacket(Object playerObj, String source) {
        if (!ShanhaiWorldClock.SERVER.isPaused()) {
            return false;
        }
        if (!(playerObj instanceof ServerPlayer player)) {
            return false;
        }
        AbstractContainerMenu menu = player.containerMenu;
        // 已确认在暂停中 ⇒ 第一个实参恒为 true（见方法头：这是 denyContainerPacket 的同一份判定）
        if (!ShanhaiAeGate.deny(true, menu instanceof InventoryMenu)) {
            return false;
        }
        ShanhaiClockGuard.note(ShanhaiClockGuard.Category.CONTAINER, player);
        logAeBlocked(source, player, menu);
        resyncMenu(menu);
        return true;
    }

    /**
     * AE2 的 {@code MEStorageMenu#handleInteraction(long, InventoryAction)} 是否取消（<b>纵深第二道</b>）。
     *
     * <p>为什么在"已经拦了包"之后还要这一层：
     * <ol>
     *   <li>包只有两条，但<b>调用 {@code handleInteraction} 的不止包</b>（AE 内部/加装模组也可能直接调）；</li>
     *   <li>AE2 一改包里的方法名，主钩会静默失效；这一层与它<b>不同类不同方法</b>，
     *       两道都失效才算真失效（{@code ae_hooks_armed} 会逐条报出来）。</li>
     * </ol>
     *
     * <h2>🔴 为什么"定位不到服务端玩家"时【不取消】</h2>
     * 客户端那一份 {@code handleInteraction} 的第一句就是<b>发包</b>。看起来"在这里取消能省一次往返"，
     * 但<b>会吃掉玩家提示</b>：客户端单方面取消 ⇒ 服务端根本不知道有人试过 ⇒
     * 黄字（"时间已暂停：不能容器内物品转移"）永远发不出来（用户要求"尝试时给一句提示"）。
     * 所以判定顺序是：<b>先看这一笔能不能归到某个服务端玩家</b>；归不到（客户端那一份菜单 /
     * 玩家已关界面）⇒ <b>放行</b>，让包照常飞到服务端由主钩拦下并提示。
     *
     * <h2>🔴 为什么不从 AE 的字段里取玩家</h2>
     * 本工程对第三方 mod 的红线：<b>只许碰 public 入口</b>，不许读它的字段/内部类
     * （{@code getPlayerInventory()}、{@code storage} 等一律不碰）。这里用原版 API 反查：
     * {@code MinecraftServer#getPlayerList()} 里谁的 {@code containerMenu} 就是这一个。
     * 并且只在<b>服务端线程</b>做这件事（{@code MinecraftServer#isSameThread()}）：单机下客户端线程
     * 与服务端同 JVM，从客户端线程去遍历服务端玩家表是跨线程读。
     *
     * @param menuObj 目标菜单（{@code (Object) this}，运行期是 {@code MEStorageMenu}，也是 {@link AbstractContainerMenu}）
     * @param source  来源标记（只进日志）
     * @return {@code true} ⇒ 取消这一笔交互
     */
    public static boolean denyAeMenuInteraction(Object menuObj, String source) {
        if (!ShanhaiWorldClock.SERVER.isPaused()) {
            return false;
        }
        if (!(menuObj instanceof AbstractContainerMenu menu)) {
            // 不认识的东西：保守放行（宁可漏拦一个第三方菜单，也不要在别人的交互链里乱返回）
            return false;
        }
        if (!ShanhaiAeGate.deny(true, menu instanceof InventoryMenu)) {
            return false;
        }
        ServerPlayer player = findPlayerByMenu(menu);
        if (player == null) {
            // 见方法头：归不到服务端玩家 ⇒ 不在这里取消（否则玩家的黄字提示会消失）
            return false;
        }
        ShanhaiClockGuard.note(ShanhaiClockGuard.Category.CONTAINER, player);
        logAeBlocked(source, player, menu);
        resyncMenu(menu);
        return true;
    }

    /**
     * 谁的 {@code containerMenu} 正好是这一个（原版 API，不碰 AE 字段）；{@code null} = 找不到。
     *
     * <p>🔴 只在<b>服务端线程</b>返回结果：单机下客户端线程与服务端同 JVM，
     * 从客户端线程遍历服务端玩家表是跨线程读。客户端那一份菜单<b>本来也不该归到任何服务端玩家</b>
     * （两个对象不同），这里用 {@code isSameThread()} 把这条判据显式化。
     */
    private static ServerPlayer findPlayerByMenu(AbstractContainerMenu menu) {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null || !server.isSameThread()) {
                return null; // 客户端（多人）/ 还没起服 / 客户端线程
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player != null && player.containerMenu == menu) {
                    return player;
                }
            }
        } catch (Throwable t) {
            warnThrottled("按菜单反查玩家失败（这一笔只记日志、不给提示）", t);
        }
        return null;
    }

    /**
     * 把整份容器状态回灌给客户端（纠正本地预判）。
     *
     * <p>{@code sendAllDataToRemote()} 内部对 {@code synchronizer} 做了 null 判定（{@code ifnull} 后直接 return），
     * 所以客户端那一份菜单上调它是<b>安全的空操作</b>；失败也只打日志。
     */
    private static void resyncMenu(AbstractContainerMenu menu) {
        if (menu == null) {
            return;
        }
        try {
            menu.sendAllDataToRemote();
        } catch (Throwable t) {
            warnThrottled("容器状态回灌失败（客户端可能显示预判结果）", t);
        }
    }

    /** AE 拦截的日志节流：同一玩家同一来源 5 秒 1 行（与玩家黄字的节流口径一致）。 */
    private static final Map<String, Long> AE_LOG_LAST = new HashMap<>();
    private static final long AE_LOG_INTERVAL_MS = 5_000L;
    /** 服务端启动以来的 AE 拦截累计次数（只进日志，供"到底响没响"判读）。 */
    private static long aeBlockedTotal;

    /**
     * 打一行<b>AE 专用</b>判据日志（可 grep {@code ae_blocked}）。
     *
     * <p>为什么在 {@code blocked=CONTAINER} 之外还要单独一行：
     * {@code blocked=CONTAINER} 是<b>多条链共用</b>的（原版点击包 / {@code clicked} / AE 三条），
     * 只看它分不出"AE 这一层到底响没响"。这一行带 {@code src=}，能直接指到是哪一个钩子拦的。
     *
     * <p>⚠️ {@code flow} 只按<b>光标是否空着</b>推断（空 ⇒ 取出，拿着 ⇒ 放入），仅用于人来判读，
     * <b>不参与任何判定</b>（判定见 {@link ShanhaiAeGate#deny(boolean, boolean, ShanhaiAeGate.Flow)}，
     * 它对方向不敏感）。已知会把方向标错的例外：「拿手持容器把里面的东西灌进网络」（EMPTY_ITEM）
     * 时光标是空的，会被标成 {@code flow=take}。所以要分辨取/放请看包里做了什么，别拿这一格当证据。
     */
    private static void logAeBlocked(String source, ServerPlayer player, AbstractContainerMenu menu) {
        final long total = ++aeBlockedTotal;
        final String cursor = cursorTag(menu);
        final long now = System.currentTimeMillis();
        final String key = (player == null ? "-" : player.getUUID().toString()) + "/" + source;
        final Long last = AE_LOG_LAST.get(key);
        if (last != null && now - last < AE_LOG_INTERVAL_MS) {
            return;
        }
        AE_LOG_LAST.put(key, now);
        ShanhaiMod.LOGGER.info("[SHANHAI-CLOCK] ae_blocked src={} cursor={} flow={} total={} player={}"
                        + "（AE 终端的取/放已在服务端拦下）",
                source, cursor,
                "empty".equals(cursor) ? "take" : ("holding".equals(cursor) ? "put" : "?"),
                total, player == null ? "-" : player.getGameProfile().getName());
    }

    /** 光标状态（只给日志用）：{@code empty} / {@code holding} / {@code unknown}。 */
    private static String cursorTag(AbstractContainerMenu menu) {
        if (menu == null) {
            return "unknown";
        }
        try {
            ItemStack carried = menu.getCarried();
            if (carried == null) {
                return "unknown";
            }
            return carried.isEmpty() ? "empty" : "holding";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    /** 重新开世界/切换存档时清空 AE 拦截的日志节流与计数（避免读到上一局残留）。 */
    public static void resetAeCounters() {
        AE_LOG_LAST.clear();
        aeBlockedTotal = 0L;
    }

    /** 这个实体是不是"玩家"。 */
    public static boolean isPlayer(Object entity) {
        return entity instanceof Player;
    }

    /** 两个实体是否同一个（客户端豁免本地玩家要用）。 */
    public static boolean isSameEntity(Object a, Object b) {
        return a instanceof Entity e && e == b;
    }
}
