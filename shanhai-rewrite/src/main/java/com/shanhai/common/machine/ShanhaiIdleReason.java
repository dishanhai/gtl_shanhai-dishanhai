package com.shanhai.common.machine;

import com.shanhai.common.log.ShanhaiLogThrottle;

/**
 * 「<b>这台机器为什么没跑</b>」这一行 —— 判定 / 存储 / 日志格式的<b>唯一一份</b>（纯 JDK，可离线单跑）。
 *
 * <h2>为什么要有它（用户原话逐字）</h2>
 * <blockquote>「主要是它现在 <b>jade 都没有写出机器不工作的原因</b>」</blockquote>
 * 现有那条链（{@code RecipeResult.of} → {@code IRecipeStatus} → gtlcore 的 Jade 提供器）是
 * <b>上游的临时值</b>：gtlcore {@code RecipeLogicMixin#findAndHandleRecipe} 在<b>方法开头</b>就把
 * {@code recipeStatus} 置 null（字节码 {@code putfield recipeStatus = null}），
 * 而 gtlcore 的 {@code RecipeLogicProviderMixin#addTooltip} 只在
 * {@code getBoolean("Working") == false} 的 else 分支里读 {@code reason}
 * ⇒ 那条行是「每轮重算、说没就没」的，而且和 {@code work_reason} 互斥。
 * 本类提供的是一条<b>我们自己的、稳定的</b>通道：服务端扫描时写字段，Jade 用<b>本工程自己的</b>
 * 服务端数据通道（{@code IServerDataProvider}）送到客户端，客户端只画一行。
 *
 * <h2>🔴 三条硬规则（本项目红线「宁可缺，不可假」的落点）</h2>
 * <ol>
 *   <li><b>没有原因 ⇒ 这一行完全不出现</b>，不是空行、不是占位、不是「无异常」
 *       —— {@link #shown(String)} 是唯一判据，客户端只有它为 true 时才 {@code tooltip.add}；</li>
 *   <li><b>机器重新跑起来（{@code WORKING} / {@code WAITING} / 手上已有配方）⇒ 字段必须清空</b>
 *       —— {@link #next(String, boolean)} 的 {@code running} 分支，<b>同一个扫描周期内</b>生效；</li>
 *   <li><b>日志必须能定位到具体那台机器</b> —— 每行都带 {@code machine=<注册路径>} 与
 *       {@code pos=<维度>@x,y,z}，且<b>每台机器一个闸门实例</b>
 *       ⇒ 同一 tick 多台机器被拦时是<b>多行、逐台可分辨</b>，绝不会因为「原文相同」被合并掉。</li>
 * </ol>
 *
 * <h2>🔴 为什么判定必须住在这个「纯 JDK」类里</h2>
 * 本项目纪律：「凡我自己写的检查脚本报出的结果，采信前必须先证明脚本自己是对的」。
 * 若把判定写在带 Minecraft 依赖的机器类里，离线复现脚本只能<b>照抄一遍</b>判定 ——
 * 那就变成「抄错的是脚本还是生产代码」无法分辨（2026-09-20 同族撞过 7 次，全是检查器自己的假设错了）。
 * ⇒ 本类<b>零 Minecraft / GT / Forge 依赖</b>，{@code javac --release 17} 可单独编译并跑
 * {@link #selfTest()}；<b>游戏里跑的是同一份字节码</b>，不存在第二份判定。
 *
 * <h2>数据形态：为什么存 {@code Component} 的 JSON 而不是已经翻好的中文</h2>
 * 原因文本是 {@code TranslatableComponent}（键如 {@code shanhai.recipe.fail.extra_slot_wrong}）。
 * 服务端 {@code Component#getString()} 只认<b>服务端</b>的语言表 —— 专用服务端不加载本模组的
 * {@code zh_cn.json}，取到的是键名。⇒ 服务端只序列化（{@code Component.Serializer.toJson}），
 * <b>客户端反序列化后再翻译</b>，语言由玩家那边决定。这与 gtlcore 自己写 NBT {@code reason} 的做法同形。
 */
public final class ShanhaiIdleReason {

    /** 本工程自己的 Jade 行文案键（中英各一条，见 {@code assets/shanhai/lang/*.json}）。 */
    public static final String LANG_KEY = "shanhai.jade.not_running_reason";

    /** 「为什么没跑」那行的日志前缀（Jade 侧：算出来了到底显不显示）。 */
    public static final String JADE_LOG_PREFIX = "[SHANHAI-JADE-REASON]";

    /** 旧有那行（写进 gtlcore {@code RecipeResult} → {@code IRecipeStatus}）的日志前缀，保留不动。 */
    public static final String FAIL_LOG_PREFIX = "[SHANHAI-FAIL-REASON]";

    /** {@code shown=} 的取值：这一行不显示。 */
    public static final String NONE = "none";

    /**
     * 日志闸门的最小间隔（毫秒）。实际实现比这更严：<b>同一台机器的同一个形态只打一次</b>
     * （形态 = {@code pos + 原文}），变更即记 ⇒ 天然 ≤ 1 行 / 形态，且不会跨机器合并。
     */
    public static final long LOG_MIN_INTERVAL_MILLIS = 5_000L;

    private ShanhaiIdleReason() {}

    // ═══════════════════════════════ 判定（纯函数，唯一一份） ═══════════════════════════════

    /**
     * <b>该存/该显示什么</b> —— 本类唯一一处「清空逻辑」。
     *
     * <pre>
     *   running == true                      ⇒ ""（清空）
     *   reasonJson == null / 只有空白        ⇒ ""（清空：这一轮没有需要报的原因）
     *   否则                                 ⇒ 原文（trim 后原样）
     * </pre>
     *
     * <p>🔴 {@code running} 分支<b>必须排在前面</b>：{@code checkRecipe} 是<b>逐条候选配方</b>调用的，
     * 同一轮里「配方 A 被闸拦下、配方 B 照跑」是常态 ⇒ 只要机器这一轮真的开起来了，
     * 哪怕手上还攥着 A 的原因，也一个字都不许留（否则就是本工程明令禁止的常驻假数据）。
     *
     * @param reasonJson 服务端序列化后的原因（{@code Component} 的 JSON）；{@code null} = 没有原因
     * @param running    这台机器这一轮是否「有配方在跑或在等」（{@code WORKING} / {@code WAITING} /
     *                   {@code getLastRecipe() != null}）
     */
    public static String next(String reasonJson, boolean running) {
        if (running) {
            return "";
        }
        if (reasonJson == null) {
            return "";
        }
        return reasonJson.trim();
    }

    /** Jade 那一行<b>要不要出现</b>（唯一判据：字段非空）。 */
    public static boolean shown(String stored) {
        return stored != null && !stored.isEmpty();
    }

    /** {@code pos=<维度>@x,y,z} 里的 {@code <维度>@x,y,z} 部分（纯字符串，便于离线断言）。 */
    public static String describePos(String dimension, int x, int y, int z) {
        final String dim = (dimension == null || dimension.isEmpty()) ? "?" : dimension;
        return dim + "@" + x + "," + y + "," + z;
    }

    /**
     * {@code [SHANHAI-JADE-REASON] machine=… pos=… shown=<原文或 none>}。
     *
     * <p>判据用途：<b>这一行出现 = 我们真的把原因发布给了 Jade</b>；
     * {@code shown=none} = 这一拍没有原因（或刚被清空）。两者合起来就能分开
     * 「算出来了但没显示」与「根本没算出来」——只要把本行与 {@code [SHANHAI-FAIL-REASON]} 对照即可。
     */
    public static String jadeLogLine(String machine, String pos, String shownText) {
        return JADE_LOG_PREFIX + " machine=" + (machine == null ? "?" : machine)
                + " pos=" + (pos == null ? "?" : pos)
                + " shown=" + ((shownText == null || shownText.isEmpty()) ? NONE : shownText);
    }

    /**
     * {@code [SHANHAI-FAIL-REASON] machine=… pos=… 原文=《…》} —— 原有的那行，<b>补上坐标与机器 id</b>。
     *
     * <p>🔴 为什么必须补：旧行只有原文一个字段，而同一段会话里不同机器的原因会交替出现
     * （实测 {@code worldline_cracking_hub} 与别的模块各自报过不同原文）⇒ 只凭原文<b>无法判断</b>
     * 是哪一台被拦，用户据此被误导过一次。
     */
    public static String failLogLine(String machine, String pos, String reasonText) {
        return FAIL_LOG_PREFIX + " machine=" + (machine == null ? "?" : machine)
                + " pos=" + (pos == null ? "?" : pos)
                + " 原文=《" + (reasonText == null ? "" : reasonText) + "》"
                + "（链 = RecipeResult.of → IRecipeStatus → Jade 的 reason / 机器 GUI 的红字行）";
    }

    // ═══════════════════════════════ 每台机器一份的状态 ═══════════════════════════════

    /**
     * <b>一台机器一份</b>的「为什么没跑」状态：字段值 ＋ 两个日志闸门。
     *
     * <p>🔴 <b>闸门必须是实例字段、不许是 {@code static}</b> —— 旧实现用
     * {@code private static final Set<String> …按原文去重}，后果是<b>两台机器报同一句原因时只留一行</b>
     * （于是那行被错归给了另一台）。实例化之后「每台机器各自记、各自打」，多台同 tick 被拦 = 多行。
     *
     * <p>本类不写日志（保持纯 JDK、可离线跑 {@link #selfTest()}）：{@link Verdict} 只<b>给出该打的整行</b>，
     * 由机器类交给 {@code ShanhaiMod.LOGGER}。
     */
    public static final class Store {

        private final ShanhaiLogThrottle.Gate jadeGate = new ShanhaiLogThrottle.Gate();
        private final ShanhaiLogThrottle.Gate failGate = new ShanhaiLogThrottle.Gate();

        /** 服务端权威值（{@code ""} = 没有原因 ⇒ Jade 那一行完全不出现）。 */
        private String stored = "";

        /**
         * 发布一次「为什么没跑」。
         *
         * @param reasonJson 序列化后的原因；{@code null} = 这一轮没有原因
         * @param reasonText 给日志用的<b>可读</b>文本（服务端 {@code getString()}，可能是键名）；{@code null} 同上
         * @param running    机器这一轮是否在跑/在等（见 {@link #next(String, boolean)}）
         * @param machine    注册路径（{@code shanhai:worldline_cracking_hub}）
         * @param pos        {@link #describePos} 的结果
         * @param nowMillis  当前毫秒（由调用方喂，便于离线用假时间复现）
         */
        public synchronized Verdict publish(String reasonJson, String reasonText,
                                           boolean running, String machine, String pos, long nowMillis) {
            final String nextValue = next(reasonJson, running);
            final boolean changed = !nextValue.equals(stored);
            stored = nextValue;

            String jadeLog = null;
            // 形态 = pos + 「显示的是什么」⇒ 同一台机器的同一条读数只打一次；变更即记（含"变成 none"）。
            final String shape = "jade|" + pos + "|" + (shown(nextValue) ? reasonText : NONE);
            if (jadeGate.changed(shape, false, nowMillis, LOG_MIN_INTERVAL_MILLIS)) {
                jadeLog = jadeLogLine(machine, pos, shown(nextValue) ? reasonText : null);
            }
            return new Verdict(nextValue, changed, jadeLog);
        }

        /**
         * 记一次「原因已经真的写进 gtlcore 的 {@code RecipeResult}」并给出该打的那行（{@code null} = 打过同形，不打）。
         *
         * <p>与 {@link #publish} 分开：这是<b>另一件事</b>（上游那条链通没通），
         * 判「算出来了但没显示」要靠这两个前缀对照着看。
         */
        public synchronized String failPublished(String machine, String pos, String reasonText, long nowMillis) {
            final String shape = "fail|" + pos + "|" + reasonText;
            if (!failGate.changed(shape, false, nowMillis, LOG_MIN_INTERVAL_MILLIS)) {
                return null;
            }
            return failLogLine(machine, pos, reasonText);
        }

        /** 当前该显示的值（{@code ""} = 这一行不出现）。 */
        public synchronized String stored() {
            return stored;
        }

        /** 清空（结构破坏 / 拆走时用；不写日志 —— 那不是"新原因"，是"这台机器不在了"）。 */
        public synchronized void clear() {
            stored = "";
        }

        /** 一次发布的读数。 */
        public static final class Verdict {

            /** 发布后字段应当持有的值（{@code ""} = 清空）。 */
            public final String stored;
            /** 字段是否真的变了（变了才需要写回 / notifyBlockUpdate）。 */
            public final boolean changed;
            /** 该打的那一行（{@code null} = 同形打过，不再打）。 */
            public final String jadeLog;

            Verdict(String stored, boolean changed, String jadeLog) {
                this.stored = stored;
                this.changed = changed;
                this.jadeLog = jadeLog;
            }

            @Override
            public String toString() {
                return "Verdict{stored=" + (stored.isEmpty() ? "<empty>" : "…")
                        + ", changed=" + changed + ", jadeLog=" + (jadeLog == null ? "null" : "…") + '}';
            }
        }
    }

    // ═══════════════════════════════ 自检（离线可跑） ═══════════════════════════════

    /**
     * 🔴 <b>自检 —— 本项目纪律「检查器先证明自己对」的落点。</b>
     *
     * <p>覆盖用户/任务书要的三条：① 有原因 ⇒ 行出现且文本 = 原因原文；② 无原因 ⇒ 行不出现；
     * ③ 从「被拦」转为「在跑」⇒ <b>字段被清空</b>（并打一行 {@code shown=none}）。
     * 另加两条本轮的硬要求：④ 日志带 {@code machine=} 与 {@code pos=}；
     * ⑤ 两台机器同 tick 报<b>同一句原因</b> ⇒ <b>两行</b>（不许合并）。
     *
     * <p>任一条不成立 ⇒ 抛 {@link IllegalStateException}（宁可当场炸，也不许「看起来修好了」）。
     * <p><b>为什么它是纯的</b>：不碰日志、不碰文件、不碰时钟（时间由参数喂）⇒
     * {@code javac --release 17} 单独编译即可跑，游戏里跑的是<b>同一份字节码</b>。
     *
     * @return 给调用方写日志用的一整行
     */
    public static String selfTest() {
        final String reason = "这个配方需要【超净间】，但额外挂载槽里放的是【重力控制仓】";
        final String posA = describePos("minecraft:overworld", -60, 130, 71);
        final String posB = describePos("minecraft:overworld", 12, 65, -7);
        final String idA = "shanhai:worldline_cracking_hub";
        final String idB = "shanhai:primordial_causal_weaving_matrix";
        int checks = 0;

        // ═══════════ ① 有原因 ⇒ 行出现，文本 = 原因原文 ═══════════
        {
            require(next(reason, false).equals(reason), "① 有原因且没在跑 ⇒ 必须原样返回原因原文");
            require(shown(next(reason, false)), "① 有原因 ⇒ shown() 必须为 true（客户端据此 tooltip.add）");
            require(!shown(next(null, false)), "① 没原因 ⇒ shown() 必须为 false");
            require(!shown(next("   ", false)), "① 只有空白 ⇒ 也必须算「没有原因」");
            final Store s = new Store();
            final Store.Verdict v = s.publish(reason, reason, false, idA, posA, 1_000L);
            require(v.changed, "① 第一次发布 ⇒ changed 必须为 true");
            require(v.stored.equals(reason), "① 字段必须就是原因原文（整行相等断言的前提）");
            require(s.stored().equals(reason), "① store.stored() 必须与 Verdict 一致");
            require(v.jadeLog != null, "① 第一次发布必须给出 [SHANHAI-JADE-REASON] 行");
            require(v.jadeLog.startsWith(JADE_LOG_PREFIX), "① 日志前缀必须是 " + JADE_LOG_PREFIX);
            require(v.jadeLog.contains("machine=" + idA), "① 日志里必须有 machine=" + idA + "（新硬要求①）");
            require(v.jadeLog.contains("pos=" + posA), "① 日志里必须有 pos=" + posA + "（新硬要求①）");
            require(v.jadeLog.endsWith("shown=" + reason), "① 日志必须以 shown=<原因原文> 结尾，实为 " + v.jadeLog);
            checks += 12;
        }

        // ═══════════ ② 无原因 ⇒ 这一行完全不出现（不是空行、不是占位） ═══════════
        {
            final Store s = new Store();
            final Store.Verdict v = s.publish(null, null, false, idA, posA, 1_000L);
            require(!v.changed, "② 从「没有原因」到「没有原因」⇒ changed 必须为 false（不许白白写字段）");
            require(v.stored.isEmpty(), "② 没有原因 ⇒ 字段必须是空串");
            require(!shown(v.stored), "② 没有原因 ⇒ shown() 必须为 false ⇒ 客户端一行都不加");
            require(v.jadeLog != null, "② 首次确认『没有原因』仍要留一行审计（shown=none）");
            require(v.jadeLog.endsWith("shown=" + NONE), "② 那一行必须以 shown=none 结尾，实为 " + v.jadeLog);
            checks += 5;
        }

        // ═══════════ ③ 从「被拦」转为「在跑」⇒ 下一拍字段被清空（本轮核心） ═══════════
        {
            final Store s = new Store();
            final Store.Verdict blocked = s.publish(reason, reason, false, idB, posB, 1_000L);
            require(blocked.stored.equals(reason), "③ 被拦那一拍：字段必须是原因原文");
            // 同一轮扫描里，机器开起来了（running = true）而 pendingFailReason 仍在（多候选配方的常态）
            final Store.Verdict running = s.publish(reason, reason, true, idB, posB, 1_010L);
            require(running.changed, "③ 转为在跑 ⇒ 字段必须真的被改（changed=true），否则就是常驻假数据");
            require(running.stored.isEmpty(), "③ 转为在跑 ⇒ 字段必须被清空（清空逻辑本身）");
            require(!shown(running.stored), "③ 清空之后 ⇒ Jade 那一行必须不再出现");
            require(running.jadeLog != null, "③ 转为在跑要留一行审计");
            require(running.jadeLog.endsWith("shown=" + NONE),
                    "③ 转为在跑那一行必须是 shown=none（否则分不清「还在拦」和「已经跑起来」）");
            // 再拍（仍无原因）⇒ 不再重复打
            final Store.Verdict again = s.publish(null, null, true, idB, posB, 1_020L);
            require(again.jadeLog == null, "③ 同一台机器同一条读数不许重复打（限流）");
            checks += 8;
        }

        // ═══════════ ④ 每台机器各自记、各自打（新硬要求③：不许合并） ═══════════
        {
            final Store a = new Store();
            final Store b = new Store();
            final String la = a.publish(reason, reason, false, idA, posA, 5_000L).jadeLog;
            final String lb = b.publish(reason, reason, false, idB, posB, 5_000L).jadeLog;
            require(la != null && lb != null, "④ 两台机器同 tick 报同一句原因 ⇒ 必须各有一行（不许被去重合并）");
            require(!la.equals(lb), "④ 两行必须不同（至少 machine= / pos= 不同）");
            require(la.contains(idA) && la.contains(posA), "④ A 那行必须指向 A");
            require(lb.contains(idB) && lb.contains(posB), "④ B 那行必须指向 B");
            // 同一台机器的同形重复 ⇒ 只打一次
            require(a.publish(reason, reason, false, idA, posA, 9_000L).jadeLog == null,
                    "④ 同一台机器的同一条读数重复出现 ⇒ 不再打（日志有界）");
            checks += 6;
        }

        // ═══════════ ⑤ [SHANHAI-FAIL-REASON] 同样带坐标、逐台分辨 ═══════════
        {
            final Store a = new Store();
            final Store b = new Store();
            final String fa = a.failPublished(idA, posA, reason, 7_000L);
            final String fb = b.failPublished(idB, posB, reason, 7_000L);
            require(fa != null && fb != null, "⑤ 两台机器同 tick 写入 gtlcore ⇒ 两行（旧实现按原文去重会合成一行）");
            require(fa.startsWith(FAIL_LOG_PREFIX), "⑤ 前缀必须保留 " + FAIL_LOG_PREFIX);
            require(fa.contains("machine=" + idA) && fa.contains("pos=" + posA), "⑤ 必须带 machine= 与 pos=");
            require(fa.contains("原文=《" + reason + "》"), "⑤ 原文格式必须保持《…》");
            require(a.failPublished(idA, posA, reason, 7_010L) == null, "⑤ 同机同文不许重复打");
            checks += 5;
        }

        // ═══════════ ⑥ 坐标格式 ═══════════
        {
            require(posA.equals("minecraft:overworld@-60,130,71"), "⑥ describePos 形状必须是 <维度>@x,y,z");
            require(describePos(null, 0, 0, 0).equals("?@0,0,0"), "⑥ 维度缺失 ⇒ 用 ? 占位（不许伪造维度名）");
            require(describePos("", 1, 2, 3).equals("?@1,2,3"), "⑥ 维度空串 ⇒ 同样用 ? 占位");
            checks += 3;
        }

        return "[SHANHAI-IDLE-REASON] 自检通过（" + checks + " 条断言，纯 JDK、可离线单跑）："
                + "① 有原因⇒行出现且文本=原文（日志带 machine= / pos=）；"
                + "② 无原因⇒行不出现（shown=none，字段空串，客户端不 add）；"
                + "③ 被拦⇒在跑⇒下一拍字段清空并打 shown=none，同形不重复打；"
                + "④/⑤ 两台机器同 tick 同一句原因⇒两行、逐台可分辨（每台一个闸门实例，旧 static 去重已废）；"
                + "⑥ pos=<维度>@x,y,z 形状固定。";
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException("[SHANHAI-IDLE-REASON] 自检失败：" + what
                    + " ⇒ 判定本身是坏的，它在真实机器上说的『没有原因』没有任何信息量。");
        }
    }
}
