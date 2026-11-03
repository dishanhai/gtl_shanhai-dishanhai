package com.shanhai.common.locate;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import com.gregtechceu.gtceu.api.machine.MetaMachine;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.network.ShanhaiLocateNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 山海重构 · 定位的<b>服务端那一半</b>：收到"我指着这个键"之后，
 * 在 {@link ShanhaiLocateRegistry} 里那几台<b>已加载的</b>自家 part 上找样板、限流、打日志、发结果。
 *
 * <h2>1. 🔴 只读，而且只读一件事</h2>
 * 每台机器只问一句：{@code ICraftingProvider#getAvailablePatterns()}（本工程两台机器的覆写都是
 * <b>读缓存字段</b>，见 {@code StellarSampleAssemblerPartMachine:4852-4870} 与
 * {@code SuperWildcardPatternBufferPartMachine:1657-1659}），然后<b>只</b>读
 * {@code IPatternDetails#getOutputs()}。
 * <pre>
 *   不调 pushPattern（那是"推料"，会改世界）
 *   不调 getInputs（只作输入的键不算"有这个物品的样板"，见 {@link ShanhaiLocateCore} 类注释 ①）
 *   不过滤概率（概率副产在样板里就是产出，见 {@link ShanhaiLocateCore} 类注释 ②）
 *   不改任何字段、不发 requestUpdate、不碰分配器
 * </pre>
 * ⇒ 这一条有专门的断言：离线自证把 {@code find()} 改成"顺手 push 一次"，断言必须变红。
 *
 * <h2>2. 不做的事（用户硬约束④）</h2>
 * <b>不扫维度、不遍历方块</b>：候选只来自注册表（那批"自己报名"的机器），
 * 而且每台还要过 {@link ShanhaiLocateRegistry#isLive(MetaMachine)}（区块没加载 ⇒ 不算）。
 *
 * <h2>3. 限流与日志（用户规格 7 / 8）</h2>
 * <pre>
 *   同一玩家 1 秒最多 1 个请求生效；被限流的那次<b>静默丢弃</b>（连日志都不打，
 *   否则"1 秒 1 行"这条就守不住 —— 连点会刷屏）。
 *   生效的那次只可能打两行之一：
 *     [SHANHAI-LOCATE] req  player=… item=… hits=&lt;n&gt; ms=&lt;耗时&gt;
 *     [SHANHAI-LOCATE] none player=… item=…
 *   ⇒ 每个玩家每秒钟最多一行，且这两行覆盖了"有/没有"的全部情形。
 * </pre>
 *
 * <h2>4. 线程</h2>
 * 只在<b>服务端主线程</b>调（{@code ShanhaiLocateNetwork} 的 {@code ctx.enqueueWork} 里）。
 * 限流表是普通 {@code HashMap}，加锁只为了"万一"（见 §3 的注释）。
 */
public final class ShanhaiLocateSearch {

    public static final String PREFIX = ShanhaiLocateNetwork.PREFIX;

    /** 同一玩家两次生效请求的最小间隔（用户规格：「1 秒最多 1 次请求」）。 */
    public static final long MIN_INTERVAL_MILLIS = 1000L;

    /**
     * 一次请求最多问多少台（硬上限，2026-11-01 第二轮新增）。
     *
     * <p>为什么要有：被动发现会把"同一结构 + 同一 AE 网络"上的样板总成也拉进来，
     * 而每台都要把它的样板产出读一遍（星律那台 1130 格）⇒ 不设上限，一次 shift+右键在
     * 巨型 ME 网络上会变成一个肉眼可见的卡顿。上限只影响"第 96 台之后的候选"，
     * 而注册表里的（我们自己的）永远排在最前面 ⇒ 最坏情况也只是少报几台第三方的。
     */
    public static final int MAX_CANDIDATES = 96;

    /** 玩家 → 上次生效时刻（ms）。主线程读写；外面包一层 synchronized 只为万一。 */
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();

    /** 生效过的请求数 / 被限流丢掉的请求数（只在日志里读，便于排查"点了没反应"）。 */
    private static int acceptedTotal;
    private static int throttledTotal;

    private ShanhaiLocateSearch() {}

    /**
     * 服务端入口：玩家指着 {@code wanted} 问了。
     *
     * @param player 提问的人（服务端自己从连接里取，不读载荷里的身份）
     * @param wanted 目标键（可能为 {@code null} = 客户端那边没解出来，见网络层的 decode）
     */
    public static void onRequest(ServerPlayer player, AEKey wanted) {
        if (player == null) {
            ShanhaiMod.LOGGER.warn("{} locate_req_rejected reason=no_sender", PREFIX);
            return;
        }
        final String who = player.getName().getString();
        if (wanted == null) {
            ShanhaiMod.LOGGER.debug("{} locate_req_skipped reason=no_key player={}", PREFIX, who);
            return;
        }
        final String item = ShanhaiLocateNetwork.keyId(wanted);

        // ---- 限流（见类注释 §3：被丢掉的那次一个字都不打） ----
        final long now = System.currentTimeMillis();
        final UUID id = player.getUUID();
        synchronized (LAST_REQUEST) {
            final Long last = LAST_REQUEST.get(id);
            if (last != null && now - last.longValue() < MIN_INTERVAL_MILLIS) {
                throttledTotal++;
                ShanhaiMod.LOGGER.debug("{} locate_req_skipped reason=throttle player={} item={}",
                        PREFIX, who, item);
                return;
            }
            LAST_REQUEST.put(id, Long.valueOf(now));
            acceptedTotal++;
        }

        final long startedNanos = System.nanoTime();

        // ---- 候选①：只来自注册表（不扫世界），逐台复核是否还活着 ----
        final List<ShanhaiLocateRegistry.Entry> live = ShanhaiLocateRegistry.snapshot();
        final List<ShanhaiLocateCore.Candidate<AEKey>> fromRegistry = new ArrayList<>(live.size());
        int skippedDead = 0;
        for (ShanhaiLocateRegistry.Entry entry : live) {
            final MetaMachine part = entry.machine();
            if (!ShanhaiLocateRegistry.isLive(part)) {
                skippedDead++;
                continue;
            }
            // 分类判据只有一份（ShanhaiLocateCore#kindFor）：注册表里的用它报名时给的 kind。
            final int kind = ShanhaiLocateCore.kindFor(
                    true, entry.kind(), part instanceof ICraftingProvider);
            if (kind == ShanhaiLocateCore.KIND_NONE) {
                continue;
            }
            fromRegistry.add(candidateOf(part, kind, who));
        }

        // ---- 候选②：只读发现（同一结构 / 同一 AE 网格）＋ 就地登记 ----
        //
        //  🔴 为什么需要这一步（2026-11-01 第二轮）：用户的样板装在 gtladditions 的
        //  MESuperPatternBufferPartMachine 里，它不在注册表 ⇒ 旧实现永远报 none。
        //  只在"搜索"这一下跑（用户规格：1 秒最多 1 次请求），没有 tick、没有世界扫描；
        //  摸到的 part 就地登记进同一张弱表 ⇒ 下一次请求直接命中，不必再走发现。
        final List<ShanhaiLocateCore.Candidate<AEKey>> discovered = new ArrayList<>();
        // 同一张 AE 网络只枚举一遍（注册表里同一结构的几台 part 会在同一张网上）。
        final java.util.Set<Object> walkedGrids =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (ShanhaiLocateRegistry.Entry entry : live) {
            final Object gridToken = ShanhaiLocateDiscovery.gridToken(entry.machine());
            if (gridToken != null && !walkedGrids.add(gridToken)) {
                continue;               // 这张网已经走过了 ⇒ 跳过（令牌见 ShanhaiLocateDiscovery#gridToken）
            }
            final List<ShanhaiLocateDiscovery.Found> found;
            try {
                found = ShanhaiLocateDiscovery.around(entry.machine());
            } catch (Throwable t) {
                // 发现层自己已经逐台 try/catch 过了；能走到这里的只有"整条路径"级别的错
                // ⇒ 让这一次请求照旧用注册表那批，绝不把玩家的点击变成一次异常。
                continue;
            }
            for (ShanhaiLocateDiscovery.Found f : found) {
                final int kind = ShanhaiLocateCore.kindFor(
                        false, ShanhaiLocateCore.KIND_NONE, f.part instanceof ICraftingProvider);
                if (kind == ShanhaiLocateCore.KIND_NONE) {
                    continue;
                }
                discovered.add(candidateOf(f.part, kind, who));
                // 被动登记（只在搜索时发生）：下一次请求它就是"注册表里的"，
                // 于是 kind 会从 KIND_UPSTREAM_PATTERN_BUFFER 收敛成它第一次被发现时的分类。
                ShanhaiLocateRegistry.register(f.part, kind);
            }
        }

        // 两张表合成一张（同坐标只留先来的那台：注册表优先，理由见 ShanhaiLocateCore#assemble）。
        final List<ShanhaiLocateCore.Candidate<AEKey>> assembled =
                ShanhaiLocateCore.assemble(fromRegistry, discovered);
        // 硬上限：候选越多，每台都要读一遍样板产出。注册表那批排在前面 ⇒ 截断只砍掉"最后发现"的那几台。
        final List<ShanhaiLocateCore.Candidate<AEKey>> candidates =
                assembled.size() <= MAX_CANDIDATES
                        ? assembled
                        : new ArrayList<>(assembled.subList(0, MAX_CANDIDATES));
        final int discoveredCount = candidates.size() - fromRegistry.size();

        // 「最近的排前面」：于是聊天栏第一行 = 离你最近的那台，前 8 台高亮也是最近的 8 台。
        sortByDistance(candidates, player);

        final ShanhaiLocateCore.Result result =
                ShanhaiLocateCore.find(candidates, wanted, ShanhaiLocateCore.MAX_HIGHLIGHT);
        final int plan = ShanhaiLocateCore.plan(result.totalHits());
        final long ms = (System.nanoTime() - startedNanos) / 1_000_000L;

        // ---- 0 命中：什么都不做（这条是用户规格的原话，也是 plan() 那一条断言） ----
        if (!ShanhaiLocateCore.has(plan, ShanhaiLocateCore.ACTION_REPORT)) {
            ShanhaiMod.LOGGER.info("{} none player={} item={}", PREFIX, who, item);
            return;
        }

        ShanhaiMod.LOGGER.info("{} req player={} item={} hits={} cand={} disc={} ms={}",
                PREFIX, who, item, Integer.valueOf(result.totalHits()),
                Integer.valueOf(candidates.size()), Integer.valueOf(discoveredCount), Long.valueOf(ms));
        ShanhaiMod.LOGGER.debug("{} locate_detail player={} machines={} dead={} shown={}",
                PREFIX, who, Integer.valueOf(live.size()), Integer.valueOf(skippedDead),
                Integer.valueOf(result.highlight().size()));

        // ---- ① 聊天栏一行 ----
        if (ShanhaiLocateCore.has(plan, ShanhaiLocateCore.ACTION_REPORT)) {
            try {
                player.sendSystemMessage(chatLine(player, result));
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.error("{} locate_chat_failed player={} err={}", PREFIX, who, t.toString(), t);
            }
        }
        // ---- ② 世界高亮（发给客户端画） ----
        if (ShanhaiLocateCore.has(plan, ShanhaiLocateCore.ACTION_HIGHLIGHT)) {
            ShanhaiLocateNetwork.sendResult(player, result.highlight());
        }
    }

    /**
     * 一台 part 的只读快照（注册表那批与被动发现那批<b>走同一个构造函数</b>，
     * 免得"两条路各写一套坐标/维度取法"而其中一条写错 —— 那正是本轮要修的形态）。
     *
     * @param part part 机器（服务端活体）
     * @param kind {@link ShanhaiLocateCore#kindFor} 判出来的种类（不会传 {@code KIND_NONE} 进来）
     * @param who  提问的玩家名（只用于日志）
     */
    private static ShanhaiLocateCore.Candidate<AEKey> candidateOf(MetaMachine part, int kind, String who) {
        final Level level = part.getLevel();
        final BlockPos pos = part.getPos();
        return ShanhaiLocateCore.Candidate.of(
                kind,
                level == null ? "?" : level.dimension().location().toString(),
                pos == null ? 0 : pos.getX(),
                pos == null ? 0 : pos.getY(),
                pos == null ? 0 : pos.getZ(),
                producedKeys(part, who));
    }

    /**
     * 一台机器<b>所有样板的全部产出键</b>（只读；不看输入、不过滤概率）。
     *
     * <p>⚠️ 这里会<b>新分配</b>一个 list（样板越多越大）。为什么不"就地比完再返回 boolean"：
     * 那样"找所有者"这件事就从纯逻辑核跑回服务端了，离线就断言不了
     * （用户规格的原话是"把从样板清单里找某个 AEKey 的所有者抽成纯逻辑核"）。
     * 代价可数：本方法是<b>每次请求每台机器一次</b>，而请求被限流在 1 次/秒 ⇒ 可忽略。
     */
    private static List<AEKey> producedKeys(MetaMachine part, String who) {
        if (!(part instanceof ICraftingProvider provider)) {
            return Collections.emptyList();
        }
        final List<IPatternDetails> patterns;
        try {
            patterns = provider.getAvailablePatterns();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} locate_patterns_threw player={} machine={} err={}",
                    PREFIX, who, part.getClass().getSimpleName(), t.toString());
            return Collections.emptyList();
        }
        if (patterns == null || patterns.isEmpty()) {
            return Collections.emptyList();
        }
        final List<AEKey> out = new ArrayList<>(patterns.size());
        for (IPatternDetails pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            final GenericStack[] outputs;
            try {
                outputs = pattern.getOutputs();
            } catch (Throwable t) {
                continue;                       // 一条样板读不出产出 ⇒ 跳过它，不拖垮整次请求
            }
            if (outputs == null) {
                continue;
            }
            for (GenericStack output : outputs) {
                if (output != null && output.what() != null) {
                    out.add(output.what());
                }
            }
        }
        return out;
    }

    /** 按"离玩家多远"排（同维度才算距离；不同维度排最后，用坐标序兜底 ⇒ 结果确定）。 */
    private static void sortByDistance(List<ShanhaiLocateCore.Candidate<AEKey>> candidates,
                                       ServerPlayer player) {
        final String here = player.level().dimension().location().toString();
        final double px = player.getX();
        final double py = player.getY();
        final double pz = player.getZ();
        candidates.sort(new Comparator<ShanhaiLocateCore.Candidate<AEKey>>() {
            @Override
            public int compare(ShanhaiLocateCore.Candidate<AEKey> a,
                               ShanhaiLocateCore.Candidate<AEKey> b) {
                final double da = squaredDistance(a, here, px, py, pz);
                final double db = squaredDistance(b, here, px, py, pz);
                final int byDistance = Double.compare(da, db);
                if (byDistance != 0) {
                    return byDistance;
                }
                // 完全并列 ⇒ 按坐标排，保证"同一局面两次请求顺序一致"
                int cmp = Integer.compare(a.x, b.x);
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Integer.compare(a.y, b.y);
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Integer.compare(a.z, b.z);
                return cmp != 0 ? cmp : Integer.compare(a.kind, b.kind);
            }
        });
    }

    private static double squaredDistance(ShanhaiLocateCore.Candidate<AEKey> candidate, String here,
                                          double px, double py, double pz) {
        if (!here.equals(candidate.dim)) {
            return Double.MAX_VALUE;            // 不同维度 ⇒ 排最后（距离无意义）
        }
        final double dx = px - (candidate.x + 0.5D);
        final double dy = py - (candidate.y + 0.5D);
        final double dz = pz - (candidate.z + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 聊天栏那<b>一行</b>（第一台写全：种类 + 坐标 + 维度 + 距离；其余只报台数）。
     *
     * <p>多台时为什么不把坐标全列出来：用户规格是「聊天栏报一行」+「多台就都高亮」，
     * 坐标的权威呈现是<b>世界里的框</b>；一行里塞 8 组坐标在 1.20.1 的聊天行宽下会被折行，
     * 反而更难读。（这一条写进报告的「待用户确认」，要改只是改 lang 的一句。）
     */
    private static Component chatLine(ServerPlayer player, ShanhaiLocateCore.Result result) {
        final ShanhaiLocateCore.Hit first = result.highlight().get(0);
        final String coords = first.x + ", " + first.y + ", " + first.z;
        final MutableComponent message = Component.empty();
        message.append(Component.translatable(
                ShanhaiLocateCore.LANG_FOUND,
                Component.translatable(ShanhaiLocateCore.kindLangKey(first.kind)),
                Component.literal(coords),
                Component.literal(first.dim),
                Component.literal(distanceText(player, first))));
        final int extra = result.totalHits() - 1;
        if (extra > 0) {
            if (result.truncated()) {
                message.append(Component.translatable(
                        ShanhaiLocateCore.LANG_MORE_CAPPED,
                        Component.literal(String.valueOf(extra)),
                        Component.literal(String.valueOf(result.highlight().size()))));
            } else {
                message.append(Component.translatable(
                        ShanhaiLocateCore.LANG_MORE,
                        Component.literal(String.valueOf(extra))));
            }
        }
        return message;
    }

    /**
     * 距离（方块数，向下取整）。不同维度时给 {@code ?} —— <b>不编一个数</b>
     * （AE 网络不跨维度，正常不会走到这里；走到了就是真有跨维度的情况，如实说"不知道"）。
     */
    private static String distanceText(ServerPlayer player, ShanhaiLocateCore.Hit hit) {
        final String here = player.level().dimension().location().toString();
        if (!here.equals(hit.dim)) {
            return "?";
        }
        final double dx = player.getX() - (hit.x + 0.5D);
        final double dy = player.getY() - (hit.y + 0.5D);
        final double dz = player.getZ() - (hit.z + 0.5D);
        return String.valueOf((int) Math.floor(Math.sqrt(dx * dx + dy * dy + dz * dz)));
    }

    /** 玩家下线时清限流表（防止 UUID 表无限长）。由网络层/事件层顺手调，失败也无所谓。 */
    public static void forget(UUID player) {
        if (player == null) {
            return;
        }
        synchronized (LAST_REQUEST) {
            LAST_REQUEST.remove(player);
        }
    }

    /** 开机/排错用的一行读数。 */
    public static String statsLine() {
        synchronized (LAST_REQUEST) {
            return "locate_search throttle_ms=" + MIN_INTERVAL_MILLIS
                    + " accepted=" + acceptedTotal
                    + " throttled=" + throttledTotal
                    + " tracked_players=" + LAST_REQUEST.size()
                    + " max_highlight=" + ShanhaiLocateCore.MAX_HIGHLIGHT;
        }
    }
}
