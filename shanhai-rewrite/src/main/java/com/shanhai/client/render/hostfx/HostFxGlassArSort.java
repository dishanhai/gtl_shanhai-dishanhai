package com.shanhai.client.render.hostfx;

import java.util.Locale;

/**
 * 归墟视界枢核 · 玻璃 AR 面板 ⑦ 模块列表的<b>显示排序</b>（2026-10-25 第三十二改）——
 * <b>零 Minecraft 依赖</b>（只 import {@code java.util.Locale}），可以脱机 {@code javac} + 直接跑探针。
 *
 * <h2>用户这一条要的是什么（原话）</h2>
 * <pre>
 *   「图1这个页面你<b>优先显示正在工作的机器</b>，就是把机器<b>按照最近一次运行时间从上往下排</b>，
 *     因为后面机器多了它肯定写不下」
 * </pre>
 * 图1 = 玻璃 AR 面板左侧那一列模块表（槽号 / 模块 / 进度 / 耗能EU/t / 并行 / 线程 / 输出），
 * 改动前它按<b>固定槽号顺序</b>列模块（{@code HostFxGlassArModel.buildModuleList} 直接遍历
 * {@code State#moduleSlots}），24 个模块只有 7 行 ⇒ 玩家永远只看得见槽号最小的那几台，
 * 真正在干活的那几台可能全在下面。
 *
 * <h2>排序键（三层，逐层比较，见 {@link #before}）</h2>
 * <table border="1">
 *   <tr><th>层</th><th>键</th><th>方向</th><th>平手时</th></tr>
 *   <tr><td>1</td><td><b>现在正在工作</b>（{@code working}）</td><td>在工作的<b>一律最前</b></td>
 *       <td>—</td></tr>
 *   <tr><td>2</td><td><b>本次启动时刻</b>（{@code startTick}）</td>
 *       <td>倒序（<b>后启动的在更上面</b>）</td><td>槽号升序</td></tr>
 *   <tr><td>3</td><td><b>最近一次运行时刻</b>（{@code lastRunTick}）</td>
 *       <td>倒序（最近跑过的在最上面；<b>从没跑过的自然排最后</b>，因为它是 {@link #NOT_RUN}=0）</td>
 *       <td>槽号升序</td></tr>
 * </table>
 * <p>「正在工作的机器必须永远排在最前（即使它比别的机器更晚才启动）」= 第 1 层先比 ⇒ <b>结构上</b>成立，
 * 不是靠时间戳碰巧更大。
 *
 * <h2>🔴 时间戳的口径（这一节是本次改动的核心，别再自己发明第三套）</h2>
 * <pre>
 *   单位 = <b>游戏 tick</b>（{@code Level#getGameTime()}），不是毫秒、不是帧计数。
 *          理由与 {@code HostFxGlassArText#gameTick} 那条注释同源：它就是 tick 计数，
 *          客户端每 tick +1（20 次/秒），与帧率解耦；暂停/未进世界时它冻结 ⇒ 不会"白记一次运行"。
 *
 *   working     = 该槽这一拍【有配方装填且没跑完】：客户端看 {@link #workingOf} 的三条判据。
 *   startTick   = 【上升沿】= 这一拍第一次观测到 working 的那个 tick（"本次启动时刻"）。
 *   lastRunTick = 最后一次观测到"在跑 <b>或刚停</b>"的那个 tick。
 *                 在跑时每拍都刷新 ⇒ 一旦停下，它停在【结束时刻（±1 tick）】上，
 *                 正好就是用户要的"刚结束 ⇒ 记下结束时刻"。
 *   NOT_RUN     = 0 = 从来没跑过（世界第 0 tick 不可能"刚跑完"，所以 0 可以安全地当"从没跑过"）。
 * </pre>
 *
 * <h2>🔴 更新频率：<b>只在"数据推送的那一拍"</b>，绝不每帧（用户硬要求）</h2>
 * 本类没有任何自己的线程/定时器，唯一的喂料口是 {@link Tracker#observe}，
 * 调用点只有 {@code HostFxGlassArText#rebuildReadingsIfStale} 的尾巴 —— 那条路径本身：
 * <ul>
 *   <li>只在<b>面板可见</b>时跑（它由 TESR 的 {@code render()} 调用，看不见的机器根本不进来）；</li>
 *   <li>已有 tick 闸门 {@code REBUILD_EVERY_TICKS = 1} ⇒ <b>同一 tick 内的渲染帧直接跳过</b>
 *       （60 fps 下每 tick 只算一次，见 {@code tickSkips} 计数）。</li>
 * </ul>
 * ⇒ <b>本类一次都不遍历机器</b>：{@link Tracker#observe} 只读 {@code State} 里<b>服务端已经推过来</b>的
 * 三个同步数组（{@code modProgressPct} / {@code modRemainSeconds}，服务端每 tick 刷新，见
 * {@code GuixuVisionCoreMachine#refreshProgressTick}），成本 = 64 次数组读 + 一次 ≤24 元素的插入排序。
 * <p><b>为什么不自己去看机器的 {@code RecipeLogic}</b>（那是更强的信号）：① 那要
 * {@code MetaMachine.getMachine(level, pos)} × 24 = 每 tick 24 次世界查询，而服务端<b>已经</b>
 * 把同一份 {@code logic.getProgress()/getMaxProgress()/isWorking()} 折算成进度数组推过来了
 * （{@code progressOf}）；② 客户端镜像的 {@code RecipeLogic} 字段同步面未取证，拿它当判据 =
 * 拿"没验证过的东西"当结论（本工程纪律）。详见 {@code temp/arsort/报告.md} §1。
 *
 * <h2>"正在工作"到底怎么判（{@link #workingOf}）</h2>
 * <pre>
 *   pct  &lt; 0        ⇒ 未知（服务端那次读不到 / 快照还没到）⇒ 保持上一次的结论，<b>不猜</b>
 *   pct  &gt; 0        ⇒ 在跑（进度已经推进）
 *   pct == 0, rem &gt; 0 ⇒ 在跑（配方刚装填：progress=0 但总时长已知，remain = 总时长/20 > 0；
 *                       长配方 progress=1 时 pct 也会是 0，靠这一条兜住）
 *   pct == 0, rem == 0 ⇒ <b>没在跑</b>（服务端 {@code progressOf} 的口径：没在跑且没有配方 ⇒ {0,0}
 *                       是【真读数】不是占位；见 {@code GuixuVisionCoreMachine#progressOf} 的注释）
 * </pre>
 * ⚠️ 已知边界（写进报告「待你确认」）：GT 的 {@code RecipeLogic} 在 <b>waiting</b>（等电/等料）时
 * {@code isWorking=false} 但 {@code progress} 不归零 ⇒ 本类仍把它算作"在工作"，它会一直排在最前。
 * 这与"最近一次运行时间"的语义一致（它确实最近碰过配方），但如果你要的是"只有真正在推进的才算"，
 * 改 {@link #workingOf} 一处即可。
 *
 * <h2>不变量（面板是只读的）</h2>
 * <ul>
 *   <li><b>只重排，不增删</b>：{@link #order} 返回的是输入数组的一个置换（长度、元素集合完全相同），
 *       {@code HostFxGlassArModel#displayOrder} 还会再验一次集合相等，不等就<b>退回槽号升序</b>；</li>
 *   <li>槽号本身<b>一个都不改</b>（行里印的还是 {@code moduleSlots} 的原值）⇒ 服务端、位图、
 *       名字/读数三份数组的对齐关系全部不变；</li>
 *   <li>不碰任何服务端逻辑、不发包、不写 {@code @DescSynced} 字段。</li>
 * </ul>
 */
public final class HostFxGlassArSort {

    private HostFxGlassArSort() {
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  常量
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * 槽号上限 = <b>64</b>。
     *
     * <p>为什么是 64 而不是服务端那个 {@code GuixuVisionCoreMachine.SNAP_SLOTS = 24}：
     * 槽号是<b>{@code long} 位图里的位下标</b>（{@code hostModuleMask}），所以理论上限就是 64；
     * 本类<b>零 MC 依赖</b>，不能引用机器包那个常量（引用它会把机器类型拖进本类）。
     * 服务端将来把 24 调大，这里不用跟着改（越界槽号在下面的 {@link #flag}/{@link #tick} 里一律当"未知"）。
     */
    public static final int MAX_SLOTS = Long.SIZE;

    /**
     * 「从没跑过」的时间戳 = <b>0</b>（世界第 0 tick）。
     *
     * <p>为什么可以用 0 当哨兵：{@code lastRunTick} 只可能是"观测到在跑/刚停"的那个游戏 tick，
     * 而<b>第 0 tick 之前不可能发生过一次完整的"跑过"</b>；它同时也让负对照变得干净 ——
     * 把所有时间字段置 0 ⇒ 三层键全部平手 ⇒ 输出<b>必定退化成槽号升序</b>。
     * <p>⚠️ 这个哨兵<b>永远不参与减法</b>（历史事故：{@code Long.MIN_VALUE} 参与减法 ⇒ long 溢出 ⇒
     * 闸门恒早退，见 {@code HostFxGlassArText#rebuildReadingsIfStale} 第二十五改那段注释）。
     * 本类对它的用法只有两种：{@code ==} 比较、{@code >} 比较。
     */
    public static final long NOT_RUN = 0L;

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  排序（纯函数：输入 → 输出，无状态）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * 按"正在工作 ⊃ 启动时刻倒序 ⊃ 最近运行时刻倒序 ⊃ 槽号升序"重排槽号。
     *
     * <p>🔴 <b>只重排，不增删</b>：返回数组是 {@code slots} 的一个置换。
     *
     * @param slots       已装模块的槽号（任意顺序；喂料侧给的是槽号升序）
     * @param working     按<b>槽号下标</b>的"这一拍正在工作"标记（{@code null} = 全部不在工作）
     * @param startTick   按<b>槽号下标</b>的"本次启动所在 tick"（{@code null} / 0 = 未知）
     * @param lastRunTick 按<b>槽号下标</b>的"最近一次在（工作或刚停）的 tick"（{@code null} / 0 = 从没跑过）
     * @return 新数组；{@code slots == null} ⇒ 空数组
     */
    public static int[] order(int[] slots, boolean[] working, long[] startTick, long[] lastRunTick) {
        if (slots == null) {
            return new int[0];
        }
        final int[] out = slots.clone();
        // 插入排序：n ≤ 24，比 Arrays.sort(Integer[]) 省（不装箱），而且 before() 是全序 ⇒ 结果唯一。
        for (int i = 1; i < out.length; i++) {
            final int v = out[i];
            int j = i - 1;
            while (j >= 0 && before(v, out[j], working, startTick, lastRunTick)) {
                out[j + 1] = out[j];
                j--;
            }
            out[j + 1] = v;
        }
        return out;
    }

    /**
     * {@code a} 是否必须排在 {@code b} <b>前面</b> —— 三层键，逐层比较（= 用户口径的唯一实现）。
     *
     * <p>包内可见是为了让脱机探针能直接打这一条（{@code temp/arsort/probe}），
     * 而不是只有"整体输出对不对"一个信号。
     */
    static boolean before(int a, int b, boolean[] working, long[] startTick, long[] lastRunTick) {
        final boolean wa = flag(working, a);
        final boolean wb = flag(working, b);
        if (wa != wb) {
            return wa;                             // ← 第 1 层：正在工作的【一律】在最前
        }
        if (wa) {
            final long sa = tick(startTick, a);
            final long sb = tick(startTick, b);
            if (sa != sb) {
                return sa > sb;                    // ← 第 2 层：都在工作 ⇒ 启动时间【倒序】（后启动的在前）
            }
            return a < b;                          // 同时启动 ⇒ 槽号升序（稳定）
        }
        final long la = tick(lastRunTick, a);
        final long lb = tick(lastRunTick, b);
        if (la != lb) {
            return la > lb;                        // ← 第 3 层：最近运行时间【倒序】；从没跑过(0) 自然沉底
        }
        return a < b;                              // 同一时刻 ⇒ 槽号升序（稳定）
    }

    /**
     * 两个数组是不是<b>同一个多重集</b>（长度相同且互为置换）—— "只重排不增删"的校验。
     *
     * <p>{@code HostFxGlassArModel#displayOrder} 用它守最后一道：一旦排序器给了不是置换的东西
     * （将来谁改错了），面板退回 {@code moduleSlots} 原序，<b>绝不出现漏行 / 同一台机器画两行</b>。
     *
     * <p>🔴 <b>零分配</b>（计数式，O(n²)，n ≤ 24）：这个判据跑在<b>每帧</b>的 {@code displayOrder} 里，
     * 不能每帧 clone 两个数组再 {@code Arrays.sort}（那是 120 次/秒的垃圾）。
     * 正确性：长度相同且"每个 {@code a} 里的值在两边出现次数相同" ⇒ {@code b} 里不可能有多余值
     * （总数已被占满）。
     */
    public static boolean sameSet(int[] a, int[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            final int v = a[i];
            if (count(a, v) != count(b, v)) {
                return false;
            }
        }
        return true;
    }

    /** {@code v} 在数组里出现几次（{@link #sameSet} 用；零分配）。 */
    private static int count(int[] arr, int v) {
        int n = 0;
        for (int x : arr) {
            if (x == v) {
                n++;
            }
        }
        return n;
    }

    /**
     * <b>这一拍算不算"正在工作"</b>（判据三条，见类注释）。{@code pct < 0} 由调用方先挡掉。
     *
     * @param pct        该槽的配方进度百分比（0..100）
     * @param remainSec  该槽的配方剩余秒数（≥ 0）
     * @return {@code true} = 在跑
     */
    public static boolean workingOf(int pct, int remainSec) {
        return pct > 0 || remainSec > 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  喂料器（有状态：记住每台机器的"本次启动时刻 / 最近一次运行时刻"）
    // ═══════════════════════════════════════════════════════════════════════════════════

    /**
     * 每 tick 喂一次的<b>时间戳记账簿</b>。
     *
     * <p>它只做三件事：① 记上升沿（{@code startTick}）；② 每拍刷新 {@code lastRunTick}（运行时刻）；
     * ③ 记下降沿（停下那一拍 = 结束时刻）。<b>不排序、不遍历机器、不读世界</b>。
     *
     * <p>线程：只在渲染线程（与 {@code HostFxGlassArText} 的 STATE 同一条路径）。
     */
    public static final class Tracker {

        /** 每个槽这一拍是否在跑（按槽号下标）。 */
        private final boolean[] working = new boolean[MAX_SLOTS];
        /** 每个槽"本次启动"的 tick（{@link #NOT_RUN} = 没在跑/没跑过）。 */
        private final long[] startTick = new long[MAX_SLOTS];
        /** 每个槽"最近一次在跑或刚停"的 tick（{@link #NOT_RUN} = 从没跑过）。 */
        private final long[] lastRunTick = new long[MAX_SLOTS];

        // ── 计数（报告 / 日志 / 判据用；不影响排序）────────────────────────────────
        private long observeCalls;
        private long observeSkipped;
        private long slotUnknown;
        private long edges;
        private long dropped;

        /**
         * 喂一拍（调用方保证：同一 tick 只喂一次；面板不可见的 tick 根本不会喂）。
         *
         * <p>🔴 三条"不许猜"：
         * <ul>
         *   <li>{@code tick < 0}（读不到游戏 tick）或 {@code slots == null}（权威槽位位图还没到）
         *       ⇒ <b>整拍跳过</b>，一个字段都不动（{@link #observeSkipped} 计数）；</li>
         *   <li>某槽 {@code pct < 0}（服务端那次读不到）⇒ <b>只跳过这个槽</b>，保持上一次的结论
         *       （{@link #slotUnknown} 计数）—— 不把"未知"当"没在跑"，否则一次读失败就会把
         *       机器的启动时刻抹掉；</li>
         *   <li>槽不在 {@code slots} 里（模块被拆掉 / 换了另一台）⇒ 该槽状态<b>清空</b>
         *       （{@link #dropped} 计数），免得新装的机器继承上一台的时间戳。</li>
         * </ul>
         *
         * @param tick          当前游戏 tick（{@code Level#getGameTime()}；&lt;0 = 读不到）
         * @param slots         已装模块槽号（{@code null} = 还没有权威位图）
         * @param progressPct   按槽号下标的进度百分比（服务端同步数组；{@code null} = 这一份快照没到）
         * @param remainSeconds 按槽号下标的剩余秒数（同上）
         */
        public void observe(long tick, int[] slots, byte[] progressPct, int[] remainSeconds) {
            if (tick < 0L || slots == null) {
                observeSkipped++;
                return;
            }
            final boolean[] present = new boolean[MAX_SLOTS];
            for (int s : slots) {
                if (s >= 0 && s < MAX_SLOTS) {
                    present[s] = true;
                }
            }
            for (int s = 0; s < MAX_SLOTS; s++) {
                if (!present[s]) {
                    if (working[s] || lastRunTick[s] != NOT_RUN) {
                        dropped++;
                    }
                    working[s] = false;
                    startTick[s] = NOT_RUN;
                    lastRunTick[s] = NOT_RUN;
                    continue;
                }
                final int pct = byteAt(progressPct, s);
                final int rem = intAt(remainSeconds, s);
                if (pct < 0) {
                    slotUnknown++;                 // 这一拍这个槽读不到 ⇒ 不猜，保持上次
                    continue;
                }
                if (workingOf(pct, rem)) {
                    if (!working[s]) {             // 上升沿 = 本次启动
                        working[s] = true;
                        startTick[s] = tick;
                        edges++;
                    }
                    lastRunTick[s] = tick;         // 每拍刷新 ⇒ 停下时它就停在结束时刻上
                } else if (working[s]) {           // 下降沿 = 刚结束
                    working[s] = false;
                    lastRunTick[s] = tick;         // 记下结束时刻（观测到"已停"的这一拍）
                    edges++;
                }
            }
            observeCalls++;
        }

        /** 按当前记账簿算一遍显示顺序（= {@link HostFxGlassArSort#order}）。 */
        public int[] order(int[] slots) {
            return HostFxGlassArSort.order(slots, working, startTick, lastRunTick);
        }

        /** 该槽这一拍是否在跑。 */
        public boolean working(int slot) {
            return slot >= 0 && slot < MAX_SLOTS && working[slot];
        }

        /** 该槽本次启动时刻（{@link #NOT_RUN} = 没在跑）。 */
        public long startTick(int slot) {
            return slot >= 0 && slot < MAX_SLOTS ? startTick[slot] : NOT_RUN;
        }

        /** 该槽最近一次运行时刻（{@link #NOT_RUN} = 从没跑过）。 */
        public long lastRunTick(int slot) {
            return slot >= 0 && slot < MAX_SLOTS ? lastRunTick[slot] : NOT_RUN;
        }

        /** 喂过多少拍（成功记账的）。 */
        public long observeCalls() {
            return observeCalls;
        }

        /** 因为"读不到 tick / 还没有槽位位图"整拍跳过的次数。 */
        public long observeSkipped() {
            return observeSkipped;
        }

        /** 逐槽"读不到进度"的样本数（跳过的槽，不是拍）。 */
        public long slotUnknown() {
            return slotUnknown;
        }

        /** 工作状态切换次数（上升沿 + 下降沿）。 */
        public long edges() {
            return edges;
        }

        /** 因为模块被拆/换而清空状态的槽次数。 */
        public long dropped() {
            return dropped;
        }

        /** 全程复位（换维度 / 复位面板时调；不留跨维度的残渣）。 */
        public void reset() {
            for (int s = 0; s < MAX_SLOTS; s++) {
                working[s] = false;
                startTick[s] = NOT_RUN;
                lastRunTick[s] = NOT_RUN;
            }
            observeCalls = 0L;
            observeSkipped = 0L;
            slotUnknown = 0L;
            edges = 0L;
            dropped = 0L;
        }

        /** 正在工作的槽号（报告 / 日志用；升序）。 */
        public String workingSlots() {
            final StringBuilder sb = new StringBuilder();
            for (int s = 0; s < MAX_SLOTS; s++) {
                if (!working[s]) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(s);
            }
            return sb.length() == 0 ? "无" : sb.toString();
        }

        /**
         * 一行摘要（进 {@code HostFxGlassArText#stats()} 那条每 5 秒的日志）。
         *
         * <p>判据：{@code 在跑=[...]} 里的槽号必须与服务端那一行
         * {@code [SHANHAI-HOSTSNAP] seq=... progress0=...} 对得上；{@code 跳过=} 长期&gt;0 ⇒
         * 断在"读不到 tick / 位图没到"。
         */
        public String stats() {
            return String.format(Locale.ROOT,
                    "AR排序 喂=%d拍 跳过=%d 逐槽未知=%d 工作切换=%d 移除=%d 在跑=[%s]",
                    observeCalls, observeSkipped, slotUnknown, edges, dropped, workingSlots());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    //  内部（越界一律当"未知"，绝不抛异常 —— 本类跑在渲染路径上）
    // ═══════════════════════════════════════════════════════════════════════════════════

    private static boolean flag(boolean[] arr, int slot) {
        return arr != null && slot >= 0 && slot < arr.length && arr[slot];
    }

    private static long tick(long[] arr, int slot) {
        return arr == null || slot < 0 || slot >= arr.length ? NOT_RUN : arr[slot];
    }

    private static int byteAt(byte[] arr, int slot) {
        return arr == null || slot < 0 || slot >= arr.length ? -1 : arr[slot];
    }

    private static int intAt(int[] arr, int slot) {
        return arr == null || slot < 0 || slot >= arr.length ? -1 : arr[slot];
    }
}
