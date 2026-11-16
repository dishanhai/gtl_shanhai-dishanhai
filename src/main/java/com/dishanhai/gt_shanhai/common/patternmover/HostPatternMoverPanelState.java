package com.dishanhai.gt_shanhai.common.patternmover;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Arrays;

/**
 * 物品面板的<b>状态包</b>：一次「重新读物品 NBT 并重建列表」的全部结果。
 *
 * <h2>一、它解决的是哪两件事</h2>
 * <ol>
 *   <li><b>C：点完「取出」页面必须自己变</b> —— 动作结束后服务端重新读物品 NBT 造一个新的本类实例，
 *       用 {@code writeUpdateInfo} 推给客户端（客户端 {@code readUpdateInfo} 收下）；
 *       本类<b>自带</b>组数、每组的块数与已用字节，所以回包本身就是"重读之后的样子"。</li>
 *   <li><b>「多义未写」要在面板上看得见</b> —— 通配符自动识别的三项计数
 *       （写入 / 多义未写 / 识别不到）随本类一起走；它们落在<b>物品 tag 根</b>的
 *       {@code shanhai:mover_read} 上（不在 {@code shanhai:mover} 里，见 {@link HostPatternMoverNbt}），
 *       所以面板随时重开都还看得到。</li>
 * </ol>
 *
 * <h2>二、🔴 第五刀：{@code rev} 与「镜像不许盖新值」</h2>
 * 用户实测：「取出后会瞬间刷新一次到正确的位置，但是过零点几秒它又会重新刷新到原本的不正确
 * （和取出之前）的位置」。⇒ 动作回执是对的，是客户端那条<b>周期镜像</b>拿"客户端手上还没同步的物品 NBT"
 * 算出旧状态、把新状态盖了回去。
 * <p>修法：状态包带上物品上那份数据的<b>修订号</b> {@link HostPatternMoverNbt#K_REV}（单调递增），
 * 镜像那份只有 {@code rev} <b>严格更大</b>才许覆盖（{@link #afterMirror}）。
 * <ul>
 *   <li>{@code rev} 由 {@link HostPatternMoverNbt#bumpRevTag} 在<b>唯一写入口</b>
 *       {@link HostPatternMoverReader#storeTo} 里 ＋1 ⇒ 「数据变了 ⇒ 版本必变」是结构性的；</li>
 *   <li>两侧拿到的是<b>同一个数</b>（都从物品 NBT 读）⇒ 客户端"手上那份旧 NBT"天然是旧版本，
 *       不可能盖掉服务端刚推的新版本；</li>
 *   <li>反过来，推送丢了而客户端 NBT 已经同步到新版 ⇒ 镜像的 rev 更大 ⇒ 它仍能兜住
 *       （第三刀那条"点了动作页面不出新"的兜底没被拆掉）。</li>
 * </ul>
 *
 * <h2>三、🔴 绝不带整份 NBT</h2>
 * 一份物品 NBT 最多 1.5 MiB，塞进开界面的包会顶到「单帧 ≤ 2,097,151 B」
 * （取证 §6.2 的两条字节码常量）。本类只有数字与类型 id，几十到几百字节。
 */
public record HostPatternMoverPanelState(int count, int bytes, int limit, String fromHost,
                                         String[] typeIds, int[] typeCounts,
                                         int readResolved, int readAmbiguous, int readUnidentified,
                                         long rev) {

    /** 状态包的来源标签：① 动作回执。 */
    public static final String SRC_ACTION = "action";

    /** 状态包的来源标签：① 打开面板时下发的那一份。 */
    public static final String SRC_OPEN = "open";

    /** 状态包的来源标签：③ 周期镜像（服务端推之前现读 / 客户端每 5 帧自己读）。 */
    public static final String SRC_MIRROR = "mirror";

    public static final int SRC_CODE_ACTION = 0;
    public static final int SRC_CODE_OPEN = 1;
    public static final int SRC_CODE_MIRROR = 2;

    public static final HostPatternMoverPanelState EMPTY = new HostPatternMoverPanelState(
            0, 0, HostPatternMoverData.LIMIT_BYTES, "",
            new String[0], new int[0], 0, 0, 0, 0L);

    public HostPatternMoverPanelState {
        fromHost = fromHost == null ? "" : fromHost;
        typeIds = typeIds == null ? new String[0] : typeIds;
        typeCounts = typeCounts == null ? new int[0] : typeCounts;
        if (count < 0) {
            count = 0;
        }
        if (bytes < 0) {
            bytes = 0;
        }
        if (limit <= 0) {
            limit = HostPatternMoverData.LIMIT_BYTES;
        }
        if (rev < 0L) {
            rev = 0L;
        }
    }

    /** 来源标签 ⇒ 包里的码（认不出 ⇒ {@link #SRC_CODE_MIRROR}，宁可按"镜像"记）。 */
    public static int srcCode(String src) {
        if (SRC_ACTION.equals(src)) {
            return SRC_CODE_ACTION;
        }
        if (SRC_OPEN.equals(src)) {
            return SRC_CODE_OPEN;
        }
        return SRC_CODE_MIRROR;
    }

    /** 包里的码 ⇒ 来源标签（日志用）。 */
    public static String srcName(int code) {
        return switch (code) {
            case SRC_CODE_ACTION -> SRC_ACTION;
            case SRC_CODE_OPEN -> SRC_OPEN;
            default -> SRC_MIRROR;
        };
    }

    // ------------------------------------------------------------------ 造

    /** 从<b>物品 tag 根</b>造（服务端与客户端都能调：这条路径只读 NBT）。 */
    public static HostPatternMoverPanelState of(CompoundTag itemTag) {
        final CompoundTag mover = HostPatternMoverNbt.moverOf(itemTag);
        final HostPatternMoverNbt.GroupCounts groups = HostPatternMoverNbt.groupCountsOf(mover);
        final HostPatternMoverNbt.ReadStats stats = HostPatternMoverNbt.readStats(itemTag);
        return new HostPatternMoverPanelState(
                HostPatternMoverNbt.countOf(mover),
                HostPatternMoverNbt.effectiveBytes(mover),
                HostPatternMoverData.LIMIT_BYTES,
                HostPatternMoverNbt.readFromHost(mover),
                groups.typeIds(), groups.typeCounts(),
                stats.resolved(), stats.ambiguous(), stats.unidentified(),
                HostPatternMoverNbt.readRev(itemTag));
    }

    // ------------------------------------------------------------------ 读

    /** 有几组。 */
    public int groups() {
        return this.typeIds.length;
    }

    /** 总页数（至少 1 页）。 */
    public int pages() {
        return HostPatternMoverPager.pages(groups());
    }

    /** 第 {@code index} 组的类型 id（{@code ""} = 类型未识别）；越界 ⇒ {@code null}。 */
    public String typeAt(int index) {
        return index < 0 || index >= this.typeIds.length ? null : (this.typeIds[index] == null ? "" : this.typeIds[index]);
    }

    /** 第 {@code index} 组的块数；越界 ⇒ 0。 */
    public int countAt(int index) {
        return index < 0 || index >= this.typeCounts.length ? 0 : this.typeCounts[index];
    }

    /** 剩余字节。 */
    public int remaining() {
        return Math.max(0, this.limit - this.bytes);
    }

    /** 这次读取有没有遇到"自身没类型的通配符样板"。 */
    public boolean hasWildcardReads() {
        return this.readResolved > 0 || this.readAmbiguous > 0 || this.readUnidentified > 0;
    }

    /** 逐字段相等（{@code typeIds} / {@code typeCounts} 是数组 ⇒ 自生成的 {@code equals} 不比内容）。
     *
     * <p>⚠️ 它<b>不</b>比 {@code rev}：{@code rev} 是"这份数据是哪一版"，不是数据本身。
     * 需要"连版本都一样"时把 {@code rev} 单独比一下（推送去重就是那么写的）。 */
    public boolean sameAs(HostPatternMoverPanelState other) {
        return other != null
                && this.count == other.count
                && this.bytes == other.bytes
                && this.limit == other.limit
                && this.fromHost.equals(other.fromHost)
                && Arrays.equals(this.typeIds, other.typeIds)
                && Arrays.equals(this.typeCounts, other.typeCounts)
                && this.readResolved == other.readResolved
                && this.readAmbiguous == other.readAmbiguous
                && this.readUnidentified == other.readUnidentified;
    }

    // ------------------------------------------------------------------ 镜像闸（第五刀 ①）

    /**
     * ★<b>镜像那份能不能覆盖当前已显示的那份</b>（<b>唯一的判定点</b>；自检 C28 打的就是这里）。
     *
     * <pre>
     *   动作回执 / 推送（已经显示出来的那份）  ⇒ 优先
     *   镜像算出来的那份                      ⇒ 只有 rev 严格更大才许覆盖
     * </pre>
     * 为什么是 {@code >} 而不是 {@code >=}：{@code rev} 相同 = 同一份数据，覆盖没有意义；
     * 而"客户端 NBT 还没同步"的那一刻，镜像的 rev 恰好比推送的那份<b>小</b>
     * ⇒ 这一句就是用户报的那个"零点几秒后又变回去"的拦网。
     *
     * <p>负对照（自检 C28aNEG）：把这一句换成"照收不误" ⇒ 旧镜像（8 组）会把新状态（10 组）盖回去，
     * 判据必须变红。
     */
    public static boolean acceptsMirror(HostPatternMoverPanelState shown, HostPatternMoverPanelState candidate) {
        if (candidate == null) {
            return false;
        }
        return shown == null || candidate.rev() > shown.rev();
    }

    /**
     * 走过镜像这一步之后<b>界面上应该是哪一份</b>（面板 {@code updateScreen} 直接调它）。
     *
     * <p>面板那边就是一句话：{@code next = afterMirror(this.state(), fresh); if (next != cur) 换掉}。
     * 把这句话抽成纯核，是为了让"旧镜像不许盖新值"这条判据能在<b>裸 JVM</b>里断言
     * （生产与自检跑同一份代码，不是各写一遍）。
     */
    public static HostPatternMoverPanelState afterMirror(HostPatternMoverPanelState shown,
                                                         HostPatternMoverPanelState candidate) {
        return acceptsMirror(shown, candidate) ? candidate : shown;
    }

    /**
     * 一行式读数（日志/断言用，格式稳定）。
     */
    public String describe() {
        return "rev=" + this.rev + " count=" + this.count + " bytes=" + this.bytes + "/" + this.limit
                + " groups=" + groups() + " pages=" + pages()
                + " wildcard(resolved=" + this.readResolved + " ambiguous=" + this.readAmbiguous
                + " unidentified=" + this.readUnidentified + ")"
                + " fromHost=" + (this.fromHost.isEmpty() ? "-" : this.fromHost);
    }

    /**
     * ★<b>实机判读用的那一行</b>（用户点名要的固定形状）：
     * <pre>
     *   [SHANHAI-MOVER] panel_state rev=… groups=… bytes=… src=action|open|mirror side=server|client via=…
     * </pre>
     * 前半段（{@code rev/groups/bytes/src}）是用户点名要的字段与顺序；{@code side} / {@code via} / {@code count}
     * 是附加读数，不影响前半段的固定形状（可 grep {@code panel_state rev=}）。
     *
     * @param side 哪一侧（{@code server} / {@code client}）
     * @param src  这份状态是谁造出来的（{@link #SRC_ACTION} / {@link #SRC_OPEN} / {@link #SRC_MIRROR}）
     * @param via  走的哪条路（{@code recompute} 现读 / {@code push} 收到推送 / {@code mirror} 取镜像）
     */
    public void logState(String side, String src, String via) {
        HostPatternMoverLog.info("{} panel_state rev={} groups={} bytes={} src={} side={} via={} count={}",
                HostPatternMoverLog.PREFIX, Long.valueOf(this.rev), Integer.valueOf(groups()),
                Integer.valueOf(this.bytes), src, side, via, Integer.valueOf(this.count));
    }

    // ------------------------------------------------------------------ 同步

    /** 写进同步包（服务端推 / 开界面时下发）。 */
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(this.count);
        buf.writeVarInt(this.bytes);
        buf.writeVarInt(this.limit);
        buf.writeUtf(this.fromHost, 64);
        final int n = groups();
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeUtf(this.typeIds[i] == null ? "" : this.typeIds[i], 256);
            buf.writeVarInt(countAt(i));
        }
        buf.writeVarInt(this.readResolved);
        buf.writeVarInt(this.readAmbiguous);
        buf.writeVarInt(this.readUnidentified);
        // 🔴 第五刀 ①：修订号必须在包里走（客户端靠它判"镜像那份是不是更新的"）
        buf.writeVarLong(this.rev);
    }

    /** 从同步包读回（客户端）。 */
    public static HostPatternMoverPanelState read(FriendlyByteBuf buf) {
        final int count = buf.readVarInt();
        final int bytes = buf.readVarInt();
        final int limit = buf.readVarInt();
        final String fromHost = buf.readUtf(64);
        final int n = Math.min(Math.max(0, buf.readVarInt()), 4096);
        final String[] ids = new String[n];
        final int[] counts = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = buf.readUtf(256);
            counts[i] = buf.readVarInt();
        }
        final int resolved = buf.readVarInt();
        final int ambiguous = buf.readVarInt();
        final int unidentified = buf.readVarInt();
        final long rev = buf.readVarLong();
        return new HostPatternMoverPanelState(count, bytes, limit, fromHost, ids, counts,
                resolved, ambiguous, unidentified, rev);
    }
}
