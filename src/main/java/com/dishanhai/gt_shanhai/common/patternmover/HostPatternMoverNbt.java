package com.dishanhai.gt_shanhai.common.patternmover;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 物品 NBT 那一层的<b>读 / 写</b>（只碰 {@code CompoundTag} ⇒ 裸 JVM 可跑，见 {@link HostPatternMoverLog} 的类注释）。
 *
 * <h2>一、结构（用户拍板的形状，逐字）</h2>
 * <pre>
 *   itemStack.tag["shanhai:mover"] = {
 *     patterns: [ { pattern: &lt;ItemStack.serializeNBT()&gt;, type: "&lt;gtceu:xxx&gt;"|"" }, ... ],
 *     bytes:    &lt;int&gt;,        // 实算：把 bytes 自身摘掉之后这个复合标签的序列化字节数
 *     fromHost: "primordial_omega_engine" | "guixu_vision_core"   // 只在认出主机时写
 *   }
 * </pre>
 * <ul>
 *   <li>🔴 <b>{@code bytes} 不计它自己</b>（否则自指）：它是 {@code HostPatternMoverBytes.totalBytes}
 *       的结果；读的时候<b>一律重算、不信任存档值</b>。</li>
 *   <li>{@code fromHost} 是第二刀「自动分配」要用的线索：老主机（原始终焉引擎／
 *       {@code primordial_omega_engine}）与新主机（归墟视界枢核／{@code guixu_vision_core}）的样板类型来源
 *       不同，本轮只<b>记下来</b>、不改判定。</li>
 * </ul>
 *
 * <h2>二、🔴 写之前必须再做一次「实算」校验</h2>
 * {@link #toTag} 里的顺序是：
 * <pre>
 *   ① 算术口径算一遍（闸已经过一次了）
 *   ② 【真的】NbtIo.write 整份写一遍，拿实数
 *   ③ 两者不等 ⇒ WARN（说明字节算术坏了），且以【实数】为准
 *   ④ 实数仍 ≤ 上限才返回标签；否则返回 null（调用方不许写）
 * </pre>
 * ⇒ 即使算术哪天被改坏，写进物品的那一份也<b>不会</b>超限（最坏只是提前拒绝）。
 *
 * <h2>三、为什么这份不压缩存（与取证建议的差别，属刻意）</h2>
 * {@code temp/pattern-mover/取证.md} §6.3 建议「整表 deflate 成 {@code byte[]}（判据仍按未压缩）」，
 * 能把物品本体从 ~900 B/块压到 ~69–436 B/块。本轮<b>不做</b>，理由：
 * <ol>
 *   <li>用户拍板的 NBT 结构就是 {@code {patterns, bytes, fromHost}}，压缩要另加 {@code blob} 键 ⇒ 结构变了；</li>
 *   <li>第一刀要能拿 SNBT 直读排查（日志 / 手改 / 面板），压缩形态做不到；</li>
 *   <li>真正被压缩解决的痛点是「1.5 MiB 物品随容器同步爆帧」（取证 §6.2 推的两条字节码常量：
 *       单帧 ≤ 2,097,151 B、网络读 NBT 配额 2,097,152 B）。这一轮先在<b>面板</b>这侧把它彻底堵掉 ——
 *       面板<b>不</b>同步整份 NBT，只同步「类型 → 条数 ＋ 总字节」几百字节的摘要
 *       （见 {@code HostPatternMoverFactory.Holder}）。</li>
 * </ol>
 * 若第二刀要支持「几千块样板」，再按取证 §6.3 方案 B 加 {@code blob}（判据口径不变）——
 * 那是<b>显式设计变更</b>，必须同时改本节文档。
 */
public final class HostPatternMoverNbt {

    /** 物品 tag 上的根键（用户拍板：{@code shanhai:mover}）。 */
    public static final String ROOT_KEY = "shanhai:mover";

    /** 条目列表键。 */
    public static final String K_PATTERNS = "patterns";

    /** 已用字节数键（缓存读数，读时一律重算）。 */
    public static final String K_BYTES = "bytes";

    /** 来源主机键。 */
    public static final String K_FROM_HOST = "fromHost";

    /**
     * <b>上一次读取</b>里「通配符样板自动识别」的三项计数 —— 🔴 它挂在物品 tag 的根上，
     * <b>不在</b> {@code shanhai:mover} 里面（与旧版那个坐标键同一个理由）。
     *
     * <p>为什么必须放在外面：{@link HostPatternMoverBytes#totalBytes} 的算术口径是
     * {@code 20 + Σ(listElementBytes) + (fromHost ? 13 + writeUTF 字节 : 0)}，
     * 自检第 6 组要求它与「真的 {@code NbtIo.write} 一遍」<b>逐字节相等</b>。
     * 往 {@code shanhai:mover} 里加任何一个键都会让两者不等（除非同时改那个公式）
     * ⇒ 这三项不是样板数据、<b>不参与 1.5 MiB 闸</b> ⇒ 放在根上，既看得见，又不动已验收的字节口径。
     *
     * <p>它给面板用：用户点名「多义未写 N 块，日志与面板都要能看到」。
     * 面板是<b>之后</b>才打开的（Shift+右键空气），现场早已没有那次读取 ⇒ 必须落盘。
     */
    public static final String K_READ_STATS = "shanhai:mover_read";

    /**
     * 物品上那份样板数据的<b>修订号</b>（单调递增的整数，第五刀加的）。
     *
     * <h2>为什么需要它</h2>
     * 用户实测（2026-11 第五刀）：「取出后会瞬间刷新一次到正确的位置，但是过零点几秒它又会重新刷新到
     * 原本的不正确（和取出之前）的位置」。
     * 根因：动作回执（服务端推出的新状态）是对的，但客户端那条<b>周期镜像</b>读的是<b>客户端自己手上那份
     * 物品 NBT</b> —— 那份在服务端改完之后还没来得及同步 ⇒ 镜像拿"旧数据"算出旧状态，把新状态盖回去了。
     * 判据只能是<b>版本</b>：镜像算出来的那份必须比"当前已显示的那一份"<b>更新</b>才许覆盖。
     *
     * <h2>🔴 为什么戳落在<b>物品 tag 根</b>上（不在 {@code shanhai:mover} 里面）</h2>
     * 与 {@link #K_READ_STATS} 同一个理由：{@link HostPatternMoverBytes#totalBytes} 的算术口径是
     * {@code 20 + Σ(listElementBytes) + (fromHost ? 13 + writeUTF 字节 : 0)}，自检第 6 组要求它与
     * 「真的 {@code NbtIo.write} 一遍」<b>逐字节相等</b>。往 {@code shanhai:mover} 里加任何一个键
     * 都会让两者不等（除非同时改那个公式）⇒ 修订号不是样板数据、<b>不参与 1.5 MiB 闸</b> ⇒ 放根上。
     *
     * <h2>谁来维护</h2>
     * 唯一落笔处 {@link #bumpRevTag} 由 {@link HostPatternMoverReader#storeTo} 调用
     * （那是本包<b>唯一</b>写 {@code shanhai:mover} 的地方）⇒ 「数据变了 ⇒ 版本必变」是结构性的，
     * 不靠调用方记得。读取方：{@link #readRev}（{@code HostPatternMoverPanelState#of} 用它）。
     */
    public static final String K_REV = "shanhai:mover_rev";

    /**
     * 一次读取的三项通配符计数（「多义未写」是用户点名要的那一条）。
     *
     * @param resolved     自动识别后<b>写进类型</b>的块数
     * @param ambiguous    来源给出 ≥2 个类型 ⇒ <b>多义未写</b>的块数（不猜）
     * @param unidentified 两个来源都没给出类型 ⇒ 不写的块数
     */
    public record ReadStats(int resolved, int ambiguous, int unidentified) {

        public static final ReadStats EMPTY = new ReadStats(0, 0, 0);

        public CompoundTag toTag() {
            final CompoundTag tag = new CompoundTag();
            tag.putInt("resolved", this.resolved);
            tag.putInt("ambiguous", this.ambiguous);
            tag.putInt("unidentified", this.unidentified);
            return tag;
        }

        /** 读不到（没有 / 坏 / 负数）⇒ 全 0（<b>不</b>编数）。 */
        public static ReadStats fromTag(CompoundTag tag) {
            if (tag == null) {
                return EMPTY;
            }
            return new ReadStats(Math.max(0, tag.getInt("resolved")),
                    Math.max(0, tag.getInt("ambiguous")),
                    Math.max(0, tag.getInt("unidentified")));
        }

        public boolean isEmpty() {
            return this.resolved == 0 && this.ambiguous == 0 && this.unidentified == 0;
        }

        public String describe() {
            return "resolved=" + this.resolved + " ambiguous=" + this.ambiguous
                    + " unidentified=" + this.unidentified;
        }
    }

    private HostPatternMoverNbt() {}

    // ------------------------------------------------------------------ 读

    /**
     * 从物品 tag 根上解出 {@code shanhai:mover} 复合标签；没有 ⇒ 空标签（<b>不创建</b>）。
     *
     * <p>⚠️ 只读：{@code contains} + {@code getCompound} 不会在物品上新建任何东西
     * （{@code getOrCreateTag()} 才会）。
     */
    public static CompoundTag moverOf(CompoundTag itemTag) {
        if (itemTag == null || !itemTag.contains(ROOT_KEY, Tag.TAG_COMPOUND)) {
            return new CompoundTag();
        }
        return itemTag.getCompound(ROOT_KEY);
    }

    /** 读出全部条目（坏条目跳过；顺序 = 存档顺序）。 */
    public static List<HostPatternMoverEntry> readEntries(CompoundTag mover) {
        if (mover == null || !mover.contains(K_PATTERNS, Tag.TAG_LIST)) {
            return List.of();
        }
        final ListTag list = mover.getList(K_PATTERNS, Tag.TAG_COMPOUND);
        final List<HostPatternMoverEntry> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag entryTag = list.getCompound(i);
            if (entryTag.isEmpty()) {
                continue;
            }
            out.add(HostPatternMoverEntry.fromTag(entryTag));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 用存档里的条目建一份数据。
     *
     * <p>过字节闸：存档若超限（只可能来自手改 NBT 或上限被调小过），<b>整份不收</b>
     * ⇒ 返回空数据并 WARN（不带着超限数据跑，也不悄悄截断）。
     */
    public static HostPatternMoverData<HostPatternMoverEntry> readData(CompoundTag mover, String fromHost,
                                                                      int limitBytes) {
        final HostPatternMoverData<HostPatternMoverEntry> data =
                new HostPatternMoverData<>(new HostPatternMoverCodec(fromHost), limitBytes);
        final List<HostPatternMoverEntry> entries = readEntries(mover);
        if (!data.resetTo(entries)) {
            HostPatternMoverLog.warn("{} 存档读数超限，整份不收 patterns={} bytes={}/{}",
                    HostPatternMoverLog.PREFIX, entries.size(),
                    HostPatternMoverCodec.totalBytes(entries, fromHost), limitBytes);
            data.resetTo(List.of());
            return data;
        }
        final int real = HostPatternMoverCodec.realTotalBytes(entries, fromHost);
        if (real != data.bytes()) {
            HostPatternMoverLog.warn("{} 字节算术与实写不一致（读档）arith={} real={} patterns={}",
                    HostPatternMoverLog.PREFIX, data.bytes(), real, entries.size());
        }
        return data;
    }

    /** 存档里的 {@code fromHost}（没有 ⇒ {@code ""}）。 */
    public static String readFromHost(CompoundTag mover) {
        if (mover == null || !mover.contains(K_FROM_HOST, Tag.TAG_STRING)) {
            return "";
        }
        final String v = mover.getString(K_FROM_HOST);
        return v == null ? "" : v;
    }

    /** 物品上记的「上一次读取的通配符识别计数」；没有 ⇒ {@link ReadStats#EMPTY}（见 {@link #K_READ_STATS}）。 */
    public static ReadStats readStats(CompoundTag itemTag) {
        if (itemTag == null || !itemTag.contains(K_READ_STATS, Tag.TAG_COMPOUND)) {
            return ReadStats.EMPTY;
        }
        try {
            return ReadStats.fromTag(itemTag.getCompound(K_READ_STATS));
        } catch (Throwable t) {
            return ReadStats.EMPTY;
        }
    }

    /**
     * 把三项计数记到物品上（服务端读完之后调）。
     *
     * <p>⚠️ 这是写路径，会 {@code getOrCreateTag()}；但它写的是<b>根上的一个小复合标签</b>，
     * 与 {@code shanhai:mover} 那份字节口径<b>完全无关</b>（见 {@link #K_READ_STATS}）。
     */
    public static void writeStats(net.minecraft.world.item.ItemStack stack, ReadStats stats) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final ReadStats s = stats == null ? ReadStats.EMPTY : stats;
        if (s.isEmpty()) {
            // 三项全零 ⇒ 不留垃圾键（老物品上也不会凭空多一个复合标签）
            if (stack.getTag() != null) {
                stack.getTag().remove(K_READ_STATS);
            }
            return;
        }
        stack.getOrCreateTag().put(K_READ_STATS, s.toTag());
    }

    /** 存档里的 {@code bytes}（只用来打「存档值与实算值一致吗」的读数，<b>不参与判断</b>）。 */
    public static int readStoredBytes(CompoundTag mover) {
        return mover == null || !mover.contains(K_BYTES, Tag.TAG_INT) ? -1 : mover.getInt(K_BYTES);
    }

    // ------------------------------------------------------------------ 修订号（第五刀 ①）

    /**
     * 读物品上那份数据的修订号；没有（老物品 / 从没写过）⇒ {@code 0}。
     *
     * <p>⚠️ 只读：{@code contains} + {@code getLong} 不会在物品上新建任何东西。
     */
    public static long readRev(CompoundTag itemTag) {
        if (itemTag == null || !itemTag.contains(K_REV, Tag.TAG_LONG)) {
            return 0L;
        }
        return itemTag.getLong(K_REV);
    }

    /**
     * 物品 tag <b>根</b>上的修订号 ＋1（单调递增；返回新值）。
     *
     * <p>纯 NBT（收 {@code CompoundTag} 而不是 {@code ItemStack}）⇒ 裸 JVM 能直接断言
     * 「两次写 ⇒ 1 然后 2」（自检 C28d）。生产由 {@link HostPatternMoverReader#storeTo} 调。
     *
     * <p>🔴 只增不减：即使存档里出现负数 / 被手改成很大，也只往"更大"走
     * （{@code Math.max(now + 1, prev + 1)}）；拿不到合法值就从 1 开始。
     */
    public static long bumpRevTag(CompoundTag itemTag) {
        if (itemTag == null) {
            return 0L;
        }
        final long now = System.currentTimeMillis();
        final long prev = readRev(itemTag);
        final long next = Math.max(now, Math.max(prev + 1, 1L));
        itemTag.putLong(K_REV, next);
        return next;
    }

    /**
     * 里面有几块样板 —— <b>只读 {@code ListTag.size()}</b> 的便宜口径（tooltip 每帧都会问它）。
     *
     * <p>🔴 不要用 {@link #readEntries} 来数：那会把每一条都重建一遍（每条两次真 NBT 写），
     * 1000 块的物品每帧刷 tooltip 就是上千次序列化。
     */
    public static int countOf(CompoundTag mover) {
        if (mover == null || !mover.contains(K_PATTERNS, Tag.TAG_LIST)) {
            return 0;
        }
        return mover.getList(K_PATTERNS, Tag.TAG_COMPOUND).size();
    }

    /**
     * 面板要的「按类型分组计数」—— 直接读每条元素里的 {@code type} 字符串。
     *
     * <p>🔴 便宜口径（不进 {@link #readEntries}）：只做 {@code getString}，<b>不做任何序列化</b>。
     * 组顺序 = 首次出现顺序（与 {@link HostPatternMoverData#groupsByType()} 同一条口径）。
     *
     * @return {@code [0]} = 去重后的类型 id（空串 = 类型未识别），{@code [1]} = 对应条数
     */
    public static GroupCounts groupCountsOf(CompoundTag mover) {
        if (mover == null || !mover.contains(K_PATTERNS, Tag.TAG_LIST)) {
            return GroupCounts.EMPTY;
        }
        final ListTag list = mover.getList(K_PATTERNS, Tag.TAG_COMPOUND);
        final List<String> ids = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            final String type = list.getCompound(i).getString(HostPatternMoverEntry.K_TYPE);
            final String key = type == null ? "" : type.trim();
            final int at = ids.indexOf(key);
            if (at >= 0) {
                counts.set(at, Integer.valueOf(counts.get(at).intValue() + 1));
            } else {
                ids.add(key);
                counts.add(Integer.valueOf(1));
            }
        }
        final int[] arr = new int[counts.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = counts.get(i).intValue();
        }
        return new GroupCounts(ids.toArray(new String[0]), arr);
    }

    /** 分组计数的二元组（{@code typeIds.length == typeCounts.length}）。 */
    public record GroupCounts(String[] typeIds, int[] typeCounts) {

        public static final GroupCounts EMPTY = new GroupCounts(new String[0], new int[0]);

        public int groups() {
            return this.typeIds.length;
        }
    }

    /**
     * 「这份数据到底占多少字节」—— 优先用存档里的 {@code bytes}（我们自己写的），
     * 没有（旧存档 / 手改过）就<b>现量</b>：把 {@code bytes} 摘掉后真写一遍。
     *
     * <p>为什么不返回 0 或 -1：面板上显示「0 B」是<b>假数据</b>（本工程明令禁止）。
     */
    public static int effectiveBytes(CompoundTag mover) {
        final int stored = readStoredBytes(mover);
        if (stored >= 0) {
            return stored;
        }
        if (mover == null) {
            return 0;
        }
        final CompoundTag copy = mover.copy();
        copy.remove(K_BYTES);
        return HostPatternMoverBytes.writeSize(copy);
    }

    // ------------------------------------------------------------------ 写

    /**
     * 把一份条目录成物品 NBT 用的复合标签；<b>超限 ⇒ 返回 {@code null}</b>（调用方不许写）。
     *
     * <p>四步见类注释 §二。
     */
    public static CompoundTag toTag(List<HostPatternMoverEntry> entries, String fromHost, int limitBytes) {
        final int arith = HostPatternMoverCodec.totalBytes(entries, fromHost);
        final int real = HostPatternMoverCodec.realTotalBytes(entries, fromHost);
        if (arith != real) {
            HostPatternMoverLog.warn("{} 字节算术与实写不一致 arith={} real={} patterns={}（以实写为准）",
                    HostPatternMoverLog.PREFIX, arith, real, entries == null ? 0 : entries.size());
        }
        if (real > limitBytes) {
            return null;
        }
        final CompoundTag tag = HostPatternMoverCodec.rawTag(entries, fromHost, false);
        tag.putInt(K_BYTES, real);
        return tag;
    }
}
