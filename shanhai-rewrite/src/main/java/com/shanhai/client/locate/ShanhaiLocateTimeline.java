package com.shanhai.client.locate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 山海重构 · 「定位到的那几台机器在世界里描 6 秒框」的<b>时序核</b>。
 *
 * <h2>1. 为什么单独一个类、而且<b>一个 MC 类都不 import</b></h2>
 * 「6 秒后消失」是用户点单里唯一带时间的一条判据，而它最容易被写成
 * "画的时候顺手读一下 {@code System.currentTimeMillis()}、六秒靠肉眼"。
 * 那样的代码<b>只能开游戏验</b>，而且"到底是不是 6.0 秒"永远说不清。
 * <p>所以这里把时钟做成<b>参数</b>（每个方法都收 {@code nowMillis}）：
 * 生产端传 {@code System::currentTimeMillis}，离线断言传 0 / 5900 / 6000 / 6100
 * ⇒ 「t=5.9s 仍在、t=6.1s 已清」变成一条纯算术断言（见 {@link #selfTest()}）。
 *
 * <h2>2. 语义（三条，逐条可判）</h2>
 * <pre>
 *   ① 一次响应 = 一批：{@link #begin(long)} 把上一批整体丢掉
 *      ⇒ "又点了一次"是<b>替换</b>，不是叠加（否则连点会把旧的框留在世界里）。
 *   ② 存活判据 = {@code now &lt; expireAt}（严格小于）⇒ 一批活满<b>整整</b> {@link #HIGHLIGHT_MILLIS} 毫秒。
 *   ③ 上限 {@link #MAX_ENTRIES} = 8：只保留前 8 台（服务端也只发 8 台，
 *      这里是第二道闸 —— 客户端<b>不信</b>服务端发来多少就画多少）。
 * </pre>
 *
 * <h2>3. 线程</h2>
 * 只在<b>客户端主线程</b>用：写（{@link #begin}/{@link #add}/{@link #clear}）发生在
 * S2C 包的 {@code ctx.enqueueWork(...)} 里，读（{@link #alive}）发生在渲染帧里
 * —— 两者都是主线程，所以本类<b>不加锁</b>（加了也只是白付一次 CAS）。
 */
public final class ShanhaiLocateTimeline {

    /** 高亮活多久（用户规格：6 秒）。 */
    public static final long HIGHLIGHT_MILLIS = 6000L;

    /** 一批最多几个框（用户规格：最多 8 台）。 */
    public static final int MAX_ENTRIES = 8;

    /** 一个框：坐标 + 维度 + 到期时刻（绝对毫秒）。 */
    public static final class Entry {

        public final String dim;
        public final int x;
        public final int y;
        public final int z;
        /** 到期时刻（{@code now >= expireAt} 即清掉）。 */
        public final long expireAt;

        Entry(String dim, int x, int y, int z, long expireAt) {
            this.dim = dim == null ? "" : dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.expireAt = expireAt;
        }

        @Override
        public String toString() {
            return dim + "@" + x + "," + y + "," + z + "+" + expireAt;
        }
    }

    private final Entry[] entries = new Entry[MAX_ENTRIES];
    private int size;
    /** 这一批的起始时刻（{@code -1} = 从没来过；日志/断言读它）。 */
    private long batchStartMillis = -1L;

    /** 一批开始：丢掉上一批（见类注释 ①）。 */
    public void begin(long nowMillis) {
        this.size = 0;
        this.batchStartMillis = nowMillis;
        for (int i = 0; i < this.entries.length; i++) {
            this.entries[i] = null;
        }
    }

    /** 往当前这一批里加一台；超过 {@link #MAX_ENTRIES} 的<b>静默丢弃</b>（返回 false）。 */
    public boolean add(String dim, int x, int y, int z) {
        if (this.size >= MAX_ENTRIES) {
            return false;
        }
        final long start = this.batchStartMillis < 0L ? 0L : this.batchStartMillis;
        this.entries[this.size++] = new Entry(dim, x, y, z, expireAt(start));
        return true;
    }

    /** 全清（退出世界 / 断线时调，免得下次进世界先闪一下旧坐标）。 */
    public void clear() {
        this.size = 0;
        this.batchStartMillis = -1L;
        for (int i = 0; i < this.entries.length; i++) {
            this.entries[i] = null;
        }
    }

    /** 当前这一批还剩几台（没做过期裁剪，只读计数）。 */
    public int size() {
        return this.size;
    }

    /** 这一批的起始时刻（{@code -1} = 从没来过）。 */
    public long batchStartMillis() {
        return this.batchStartMillis;
    }

    /**
     * 现在<b>还该画</b>的那几台：顺手把过期的剔掉（写回内部数组）⇒ 调用方拿到的一定是可画的。
     *
     * @param nowMillis 当前时刻（生产 = {@code System.currentTimeMillis()}；断言 = 注入值）
     */
    public List<Entry> alive(long nowMillis) {
        if (this.size == 0) {
            return Collections.emptyList();
        }
        int kept = 0;
        for (int i = 0; i < this.size; i++) {
            final Entry entry = this.entries[i];
            if (entry != null && nowMillis < entry.expireAt) {
                this.entries[kept++] = entry;
            }
        }
        for (int i = kept; i < this.size; i++) {
            this.entries[i] = null;
        }
        this.size = kept;
        if (kept == 0) {
            return Collections.emptyList();
        }
        // 交出的是【拷贝】：渲染帧里遍历的时候，主线程上的下一包不会把它换掉。
        final List<Entry> out = new ArrayList<>(kept);
        for (int i = 0; i < kept; i++) {
            out.add(this.entries[i]);
        }
        return Collections.unmodifiableList(out);
    }

    /** 一批的到期时刻：{@code start + 6000}。 */
    public static long expireAt(long startMillis) {
        return startMillis + HIGHLIGHT_MILLIS;
    }

    /**
     * 时序断言（纯算术，离线与开机共用<b>同一个方法</b>）：
     * <pre>
     *   ① t=0 来一批 1 台 ⇒ t=0 就能画
     *   ② t=5.9s <b>仍在</b>（用户规格的正面）
     *   ③ t=6.0s 已清（严格小于 ⇒ 恰好 6.0 秒那一刻起不再画）
     *   ④ t=6.1s 已清（用户规格的负面）
     *   ⑤ 口径：新一批会【替换】旧一批（不是叠加）
     *   ⑥ 上限：塞 12 台只留 8 台
     * </pre>
     */
    public static String selfTest() {
        final int total = 6;
        int pass = 0;
        final StringBuilder bad = new StringBuilder();
        final ShanhaiLocateTimeline line = new ShanhaiLocateTimeline();
        line.begin(0L);
        line.add("minecraft:overworld", 10, 64, 20);
        if (line.alive(0L).size() == 1) {
            pass++;
        } else {
            bad.append(" ①t=0 就不画;");
        }
        if (line.alive(5900L).size() == 1) {
            pass++;
        } else {
            bad.append(" ②t=5.9s 已经没了;");
        }
        if (line.alive(6000L).isEmpty()) {
            pass++;
        } else {
            bad.append(" ③t=6.0s 还在（应当是严格小于）;");
        }
        if (line.alive(6100L).isEmpty()) {
            pass++;
        } else {
            bad.append(" ④t=6.1s 还在;");
        }
        final ShanhaiLocateTimeline replace = new ShanhaiLocateTimeline();
        replace.begin(0L);
        replace.add("minecraft:overworld", 1, 2, 3);
        replace.begin(1000L);
        replace.add("minecraft:overworld", 4, 5, 6);
        final List<Entry> afterReplace = replace.alive(1000L);
        if (afterReplace.size() == 1 && afterReplace.get(0).x == 4) {
            pass++;
        } else {
            bad.append(" ⑤新一批没替换旧的 size=").append(afterReplace.size()).append(';');
        }
        final ShanhaiLocateTimeline capped = new ShanhaiLocateTimeline();
        capped.begin(0L);
        for (int i = 0; i < 12; i++) {
            capped.add("minecraft:overworld", i, 0, 0);
        }
        if (capped.alive(0L).size() == MAX_ENTRIES) {
            pass++;
        } else {
            bad.append(" ⑥12 台塞进去留下 ").append(capped.alive(0L).size()).append(" 台;");
        }
        return "locate_timeline_selftest " + pass + "/" + total + " PASS=" + (pass == total)
                + "（判据：t=5.9s 仍在、t=6.0s/6.1s 已清；新一批替换旧一批；上限 " + MAX_ENTRIES + "）"
                + (bad.length() == 0 ? "" : " FAILED:" + bad);
    }
}
