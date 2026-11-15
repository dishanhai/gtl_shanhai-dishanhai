package com.shanhai.add.guard.radar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 常开栈雷达的<b>纯函数层</b>：白名单判定 ＋ 帧过滤 ＋ 计数聚合 ＋ 一行 {@code top=} 文本。
 *
 * <h2>为什么单独抽一个类（而不是写在采样线程里）</h2>
 * <ol>
 *   <li><b>可离线自证</b>：本类<b>只依赖 {@code java.*}</b>，不 import 任何 MC / Forge / 日志框架
 *       ⇒ 脱开游戏就能用 {@code javac} 编、用自造样本跑
 *       （离线自检在 {@code temp\radar\selfcheck\}，含正/负对照）。</li>
 *   <li><b>成本</b>：采样是每 4 ms 一次的热路径，过滤必须"就地判定、就地截断"，
 *       不许先攒一个 List 再转数组（多数样本会一个字节都不留）。</li>
 * </ol>
 *
 * <h2>白名单口径（🔴 与规格逐字一致，不多不少 10 条前缀）</h2>
 * <pre>
 *   java.*  jdk.*  sun.*  net.minecraft.*  net.minecraftforge.*  cpw.*
 *   org.spongepowered.*  com.gtladd*  org.gtlcore.*  com.shanhai.*
 * </pre>
 * ⚠️ 三个必须知道的副作用（写进 {@code temp\radar\报告.md}，别当 bug 报）：
 * <ul>
 *   <li>{@code javax.} <b>不</b>被 {@code java.} 覆盖（{@code "javax."} 的第五个字符是 {@code x}）——
 *       规格给的清单里没有它，本类就<b>不</b>擅自加；它出现在栈上会被算成一次命中。</li>
 *   <li>{@code com.gregtechceu.*}（GTCEu）、{@code dev.latvian.*}（KubeJS）等<b>正常 mod 不在白名单里</b>
 *       ⇒ 它们平时就会被算成 hits。这是<b>设计使然</b>：白名单只划"宿主 + 我们"，
 *       剩下的全都是"可疑对象"。所以 {@code hits>0} 本身<b>不构成</b>"敌方"证据，
 *       它的用途是回答"违规那一刻主线程正卡在哪一串帧上"。</li>
 *   <li>{@code com.shanhai.*} 把本体与扩展一起挡住 ⇒ 我们自己的 tick 处理
 *       （{@code guard\state\*}、本体仲裁）永远不会出现在 top 里。要查自己就得临时改白名单。</li>
 * </ul>
 *
 * <h2>空样本不分配</h2>
 * 绝大多数采样会"一帧都不剩"。此时返回的是共享常量 {@link #NO_FRAMES}（长度 0 的同一个实例）
 * ⇒ 每个样本省掉一次 {@code new String[0]}（4 ms 一次，一小时 90 万次分配）。调用方<b>只许读</b>，
 * <b>绝不许</b>写这个数组的元素。
 */
public final class ShanhaiStackRadarFilter {

    /**
     * 默认白名单（前缀匹配）。🔴 顺序＝规格顺序，改不动的理由写在类注释里。
     *
     * <p>{@code com.gtladd} 故意不带尾点（规格写的是 {@code com.gtladd*}），
     * 这样 {@code com.gtladditions.…} 与 {@code com.gtladd.gtladditions.…} 两种包名都挡得住
     * （1.20.1 那批 gtladditions 的包名历史上有过两种写法）。
     */
    public static final String[] DEFAULT_WHITELIST = {
            "java.",
            "jdk.",
            "sun.",
            "net.minecraft.",
            "net.minecraftforge.",
            "cpw.",
            "org.spongepowered.",
            "com.gtladd",
            "org.gtlcore.",
            "com.shanhai.",
    };

    /** 「一个都不挡」（负对照用：把白名单清空 ⇒ 白名单帧必须重新出现在 top 里）。 */
    public static final String[] NO_WHITELIST = new String[0];

    /** 空样本（共享常量，<b>只读</b>）。 */
    public static final String[] NO_FRAMES = new String[0];

    /** 每个样本最多留几帧（规格 8）。留那么多不是为了好看：一帧只能证明"在这里"，
     *  相邻几帧才能看出"在哪一层被谁压进来的"（归因要看调用链，不是看最顶那一帧）。 */
    public static final int MAX_FRAMES = 8;

    private ShanhaiStackRadarFilter() {
        // 纯静态
    }

    /**
     * 这条类名在不在白名单里（<b>前缀匹配</b>）。
     *
     * @param className 栈帧的声明类全名（{@code StackTraceElement#getClassName()}）
     * @param whitelist 前缀表；{@code null}/空 = 一个都不挡
     * @return {@code true} = 属于宿主/我们/依赖框架 ⇒ 这个样本<b>不收</b>它
     */
    public static boolean whitelisted(final String className, final String[] whitelist) {
        if (className == null || whitelist == null || whitelist.length == 0) {
            return false;
        }
        for (int i = 0; i < whitelist.length; i++) {
            final String prefix = whitelist[i];
            if (prefix != null && !prefix.isEmpty() && className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一帧的归因键：{@code 类#方法}（与 {@code auto_counter} 的 {@code target=} 同一套写法）。
     *
     * <p>为什么不带行号：同一个方法在采样里出现的行号会发抖（采样打进不同分支），
     * 带上行号 top 会被拆成十几条 1 票的碎片，归因就废了。
     */
    public static String frameKey(final StackTraceElement frame) {
        if (frame == null) {
            return "?";
        }
        final String cls = frame.getClassName();
        final String method = frame.getMethodName();
        return (cls == null ? "?" : cls) + "#" + (method == null ? "?" : method);
    }

    /**
     * 把一个栈样本压成"只留不在白名单里的帧"，最多 {@code maxFrames} 条。
     *
     * <p>三个截断点（顺序就是成本顺序，先便宜后贵）：
     * <ol>
     *   <li>{@code depth}：只看栈顶前 N 帧（配置 {@code stack_radar_depth}，默认 32）——
     *       被红石/区块保存这类深栈拖到几百帧时，后面的帧离"谁在 tick"已经很远了；</li>
     *   <li>白名单：命中即跳过，<b>不</b>分配任何东西；</li>
     *   <li>{@code maxFrames}：攒满就停（一个样本最多 8 条 {@code String}）。</li>
     * </ol>
     *
     * @param frames     {@code Thread#getStackTrace()} 的原样返回
     * @param whitelist  前缀表（传 {@link #NO_WHITELIST} 即"什么都要"，负对照用）
     * @param depth      只看前几帧；{@code <=0} = 不截断
     * @param maxFrames  最多留几帧；{@code <=0} = 用 {@link #MAX_FRAMES}
     * @return 长度 0 时返回共享的 {@link #NO_FRAMES}（<b>只读</b>）
     */
    public static String[] filter(final StackTraceElement[] frames, final String[] whitelist,
                                  final int depth, final int maxFrames) {
        if (frames == null || frames.length == 0) {
            return NO_FRAMES;
        }
        final int limit = (depth <= 0 || depth > frames.length) ? frames.length : depth;
        final int cap = maxFrames <= 0 ? MAX_FRAMES : maxFrames;
        String[] kept = null;   // 拖到"真有第一帧"才分配
        int n = 0;
        for (int i = 0; i < limit && n < cap; i++) {
            final StackTraceElement frame = frames[i];
            if (frame == null) {
                continue;
            }
            final String cls = frame.getClassName();
            if (cls == null || whitelisted(cls, whitelist)) {
                continue;
            }
            if (kept == null) {
                kept = new String[cap];
            }
            kept[n++] = frameKey(frame);
        }
        if (kept == null) {
            return NO_FRAMES;
        }
        if (n == cap) {
            return kept;   // 满额：不必再拷一份
        }
        final String[] out = new String[n];
        System.arraycopy(kept, 0, out, 0, n);
        return out;
    }

    /** 把一个样本的帧累加进计数表（{@code 类#方法 ⇒ 出现次数}）。 */
    public static void countInto(final Map<String, Integer> counts, final String[] sample) {
        if (counts == null || sample == null || sample.length == 0) {
            return;
        }
        for (int i = 0; i < sample.length; i++) {
            final String key = sample[i];
            if (key == null) {
                continue;
            }
            final Integer old = counts.get(key);
            counts.put(key, old == null ? 1 : old + 1);
        }
    }

    /**
     * 聚合结果的 {@code top=} 文本：{@code 类#方法=计数,…}，最多 {@code topN} 条；空 ⇒ {@code none}。
     *
     * <p>排序：计数<b>降序</b>；计数相同时按键<b>升序</b> —— 这一条不是洁癖，
     * 是为了让同一批样本任何时候输出<b>同一个顺序</b>（日志可 diff、自检可断言）。
     */
    public static String topLine(final Map<String, Integer> counts, final int topN) {
        if (counts == null || counts.isEmpty() || topN <= 0) {
            return "none";
        }
        final List<Map.Entry<String, Integer>> all = new ArrayList<>(counts.entrySet());
        all.sort(new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(final Map.Entry<String, Integer> a, final Map.Entry<String, Integer> b) {
                final int byCount = Integer.compare(b.getValue(), a.getValue());
                return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
            }
        });
        final StringBuilder sb = new StringBuilder(96);
        final int n = Math.min(topN, all.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(all.get(i).getKey()).append('=').append(all.get(i).getValue());
        }
        return sb.toString();
    }

    /**
     * 日志字段消毒：空白/竖线/换行/等号一律换成下划线。
     *
     * <p>为什么连 {@code =} 也换：{@code reason=<值>} 后面紧跟 {@code window_ms=…}，
     * 值里再冒出一个等号会让"哪个是键"这件事在 grep 层面失真。
     * ⚠️ <b>只</b>用于 {@code reason=} 这类单值字段 —— {@code top=} 的内容里等号是<b>语法</b>，不许过这一道。
     */
    public static String token(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return "?";
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            // '\uFF5C' = 全角竖线：有人从聊天框复制进来的 reason 会带它，一样要换掉
            if (c == ' ' || c == '\t' || c == '|' || c == '\n' || c == '\r' || c == '=' || c == '\uFF5C') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ═══════════════════════════════🆕 已知宿主集合（deny-by-default 的另一半）═══════════════════════════════

    /**
     * 从一个类名推出它所在 mod 的<b>命名空间前缀</b>（含尾部 {@code .}）。
     *
     * <p>用途：{@code ModList} 里每个已加载 mod 的 {@code @Mod} 实例类 ⇒ 它所在的包 ⇒ "这个 mod 的代码长什么样"。
     * 例：{@code com.gregtechceu.gtceu.GTCEu} ⇒ {@code com.gregtechceu.gtceu.}
     * （GTCEu 的 {@code api}/{@code common} 全在这个前缀下）。
     *
     * <p>🔴 <b>推不出来就返回 {@code null}，绝不猜</b>（默认包、或类名里没有包）。猜错的代价是：
     * 把某个未知 mod 的帧当成"已知宿主"放过 ⇒ 真正的可疑帧被折叠掉，而这个错误在日志里看不出来。
     *
     * @param className 类全名（{@code StackTraceElement#getClassName()} 那个口径）
     * @return 以 {@code .} 结尾的包前缀，或 {@code null} = 映射不出来
     */
    public static String namespaceOfClassName(final String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        final int dot = className.lastIndexOf('.');
        if (dot <= 0) {
            return null;   // 没有包名 / 直接以 . 开头 ⇒ 不是合法的包前缀
        }
        return className.substring(0, dot + 1);
    }

    /**
     * <b>已知宿主判定</b>：帧的类名落在"某个已加载 mod 的命名空间"里 ⇒ known，否则 suspect。
     *
     * <h2>为什么放在这里（而不是 Forge 那侧直接判）</h2>
     * 判定必须是<b>纯 JDK</b> 的：① 采样线程每秒、dump 每次都要调它，不能带 Forge 依赖；
     * ② 离线自检要能直接断言"这个类算 known、那个算 suspect"。
     * "哪些命名空间算已知宿主"由 Forge 那侧从 {@code ModList} 取出来，通过构造参数灌进来。
     *
     * <h2>为什么带缓存</h2>
     * 一个窗口里最多 8 帧 × 几十个样本，但帧键（{@code 类#方法}）高度重复。
     * 没有缓存时每个帧键都要扫一遍命名空间表（几十~几百条）；有缓存后热帧是 O(1)。
     *
     * <h2>线程安全</h2>
     * 采样线程（每秒自保护计数）与 dump 线程都会调 ⇒ 本类自己加锁。
     * 🔴 本锁是<b>叶子锁</b>：持有它的时候绝不许再去拿采样器的缓冲锁（否则可能死锁）。
     */
    public static final class KnownHosts {

        /** 缓存上限（防被"每帧都不一样"的攻击撑爆内存）；满了就整体清掉重建。 */
        public static final int CACHE_MAX = 4096;

        private final String[] namespaces;
        private final java.util.Map<String, Boolean> cache = new java.util.HashMap<>();

        /**
         * @param namespaces 已知宿主包前缀（末尾带 {@code .}）；{@code null}/空 = 一个都不认
         *                   （⇒ 所有非白名单帧都会是 suspect，等价于"还没接上 ModList"）
         */
        public KnownHosts(final String[] namespaces) {
            this.namespaces = namespaces == null ? new String[0] : namespaces.clone();
        }

        /** 命中了几个已知宿主命名空间（日志/自证用）。 */
        public int size() {
            return namespaces.length;
        }

        /** 前缀表原文（日志判读用；调用方不许改）。 */
        public String[] namespaces() {
            return namespaces;
        }

        /**
         * 这个帧键是不是"已知宿主"的代码。
         *
         * <p>⚠️ 传进来的是 {@code 类#方法}（不是类名）：前缀判定仍然成立 ——
         * 包前缀以 {@code .} 结尾、不可能跨越 {@code #}，所以 {@code key.startsWith(ns)} 与
         * "类名 startsWith ns" 等价，且<b>不用先截出类名</b>（省一次分配）。
         *
         * @param frameKey {@code 类#方法}
         * @return {@code true} = known（已知宿主）；{@code false} = suspect
         */
        public boolean isKnown(final String frameKey) {
            if (frameKey == null || frameKey.isEmpty()) {
                return false;
            }
            synchronized (this) {
                final Boolean hit = cache.get(frameKey);
                if (hit != null) {
                    return hit.booleanValue();
                }
                final boolean known = matches(frameKey);
                if (cache.size() >= CACHE_MAX) {
                    cache.clear();   // 简易上限：帧键集合本来就应该很小；真被刷爆时重来一次扫描
                }
                cache.put(frameKey, Boolean.valueOf(known));
                return known;
            }
        }

        /** 缓存的键数（自证用）。 */
        public synchronized int cached() {
            return cache.size();
        }

        private boolean matches(final String frameKey) {
            for (int i = 0; i < namespaces.length; i++) {
                final String ns = namespaces[i];
                if (ns != null && !ns.isEmpty() && frameKey.startsWith(ns)) {
                    return true;
                }
            }
            return false;
        }
    }

    // ═══════════════════════════════🆕 突增判据（ratio）═══════════════════════════════

    /**
     * "建议作为热改候选"的门槛（规格：{@code ratio >= 5}）。
     *
     * <p>{@code topFrames()} 只返回越过这一条线的项 —— 调用方拿到的就是"值得动手"的名单。
     */
    public static final double NOMINATE_MIN_RATIO = 5.0D;

    /**
     * "一律不提名"的噪声底线（规格：{@code ratio < 2}）。
     *
     * <p>日志里的 {@code top=} 也把低于这条线的项剔掉：低于它说明"跟它自己的基线一个量级"，
     * 提名它就等于把背景噪声当犯人。
     */
    public static final double NOISE_MIN_RATIO = 2.0D;

    /**
     * 🔴 <b>突增倍数</b>：窗口内命中数 / 该帧自己的基线在同样窗口长度里"应该"有多少（＋1 平滑）。
     *
     * <h2>为什么分母要乘 {@code windowMs / 1000}</h2>
     * 基线是"<b>每秒</b>命中数的中位数"，而分子是"<b>200 ms</b> 内的命中数"——两边单位不同。
     * 不换算会得到一个假结论：一个稳定 20 次/秒的帧，200 ms 里本来就有 4 次，
     * 直接除 {@code (20+1)} 得 0.19（看起来"很凉"），而它其实<b>完全正常</b>；
     * 反过来，一个 1 次/秒的背景帧出现 30 次也不该被 1 除成 30 —— 它确实该是 30。
     * ⇒ 换算成同单位后：稳定 20 次/秒 ⇒ {@code 4/(4+1)} ≈ 0.8（≈1，不提名）；
     * 背景 1 次/秒里突然 30 次 ⇒ {@code 30/(0.2+1)} = 25（高，提名）。这两组读数就是自检里的正负对照。
     *
     * @param windowHits    最近 200 ms 里这个帧命中的次数
     * @param basePerSecond 它自己的基线（每秒命中数的滚动中位数；没有历史 ⇒ 0）
     * @param windowMs      窗口长度（毫秒）
     */
    public static double ratio(final int windowHits, final int basePerSecond, final int windowMs) {
        final double expected = Math.max(0, basePerSecond) * (Math.max(1, windowMs) / 1000.0D);
        return Math.max(0, windowHits) / (expected + 1.0D);
    }

    /** 一个"突增项"：帧键 ＋ 窗口内命中数 ＋ 基线 ＋ 倍数。 */
    public static final class Ranked {

        public final String key;
        public final int hits;
        public final int base;
        public final double ratio;

        Ranked(final String key, final int hits, final int base, final double ratio) {
            this.key = key;
            this.hits = hits;
            this.base = base;
            this.ratio = ratio;
        }

        @Override
        public String toString() {
            return key + " ratio=" + ratio + "(n=" + hits + " base=" + base + ")";
        }
    }

    /**
     * 🔴 <b>按突增倍数排名</b>（不是按绝对计数）：只保留 {@code ratio >= minRatio} 的项，
     * 按倍数降序；倍数相同按命中数降序；再相同按键升序（输出可复现）。
     *
     * <p>🔴 没有达标项时返回<b>空表</b>，<b>绝不</b>退回"绝对计数最高"那个名单
     * （退回就等于"没有突增也硬指一个犯人"）。
     *
     * @param counts    窗口内 {@code 类#方法 ⇒ 命中数}（只该传 suspect）
     * @param baselines 帧键 ⇒ 基线（每秒命中数的中位数）；{@code null}/缺项按 0 处理
     * @param windowMs  窗口长度
     * @param minRatio  门槛（{@link #NOISE_MIN_RATIO} 或 {@link #NOMINATE_MIN_RATIO}）
     * @return 过滤＋排序后的列表（可能为空）
     */
    public static List<Ranked> rank(final Map<String, Integer> counts, final Map<String, Integer> baselines,
                                    final int windowMs, final double minRatio) {
        final List<Ranked> out = new ArrayList<>();
        if (counts == null || counts.isEmpty()) {
            return out;
        }
        for (final Map.Entry<String, Integer> e : counts.entrySet()) {
            final String key = e.getKey();
            final Integer hitsValue = e.getValue();
            if (key == null || hitsValue == null || hitsValue.intValue() <= 0) {
                continue;
            }
            final Integer baseValue = baselines == null ? null : baselines.get(key);
            final int base = baseValue == null ? 0 : baseValue.intValue();
            final double r = ratio(hitsValue.intValue(), base, windowMs);
            if (r < minRatio) {
                continue;   // 🔴 不达标就不提名（含噪声底线）
            }
            out.add(new Ranked(key, hitsValue.intValue(), base, r));
        }
        out.sort(new Comparator<Ranked>() {
            @Override
            public int compare(final Ranked a, final Ranked b) {
                int c = Double.compare(b.ratio, a.ratio);
                if (c == 0) {
                    c = Integer.compare(b.hits, a.hits);
                }
                return c != 0 ? c : a.key.compareTo(b.key);
            }
        });
        return out;
    }

    /**
     * 把排名拼成日志里的 {@code top=}：
     * {@code 类#方法ratio=<x.x>(n=<n> base=<b>),…}；空 ⇒ {@code none}。
     *
     * <p>⚠️ {@code ratio} 用 {@link java.util.Locale#ROOT} 格式化：跟着系统语言走的话，
     * 某些区域会把小数点写成逗号，而逗号正是本行的分隔符 —— 那会让整行没法解析。
     */
    public static String rankLine(final List<Ranked> ranked, final int topN) {
        if (ranked == null || ranked.isEmpty() || topN <= 0) {
            return "none";
        }
        final StringBuilder sb = new StringBuilder(192);
        final int n = Math.min(topN, ranked.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            final Ranked r = ranked.get(i);
            sb.append(r.key)
                    .append("ratio=")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", Double.valueOf(r.ratio)))
                    .append("(n=").append(r.hits)
                    .append(" base=").append(r.base).append(')');
        }
        return sb.toString();
    }

    /** 取前 {@code topN} 项的帧键（{@code topFrames()} 的返回体；空表 ⇒ 没有达标项）。 */
    public static List<String> keys(final List<Ranked> ranked, final int topN) {
        final List<String> out = new ArrayList<>();
        if (ranked == null || topN <= 0) {
            return out;
        }
        for (int i = 0; i < ranked.size() && out.size() < topN; i++) {
            out.add(ranked.get(i).key);
        }
        return out;
    }

    // ═══════════════════════════════🆕 基线（滚动中位数）═══════════════════════════════

    /**
     * 一个帧的<b>基线</b>：最近 N 秒「每秒命中数」的<b>滚动中位数</b>。
     *
     * <h2>为什么用中位数而不是平均数</h2>
     * 突增本身会把平均数拉起来 —— 用一次 30 次的突发去抬平均，下一次突发就显得"正常"了，
     * 检测器会越用越瞎。中位数对少数离群值不敏感：59 个 1 + 1 个 30 的中位数还是 1。
     *
     * <h2>为什么是 60 秒</h2>
     * 覆盖一个"正常节奏"（GT 机器的 tick 周期、玩家活动周期都在秒级~十秒级），
     * 同时保证一次 10~30 秒的攻击窗口不会把基线永久抬高。
     *
     * <p>线程安全：由采样器的缓冲锁保护（写＝每秒一次，读＝dump 时算中位数）。
     */
    public static final class Baseline {

        private final int[] seconds;
        private int head;
        private int size;

        public Baseline(final int windowSeconds) {
            this.seconds = new int[Math.max(1, windowSeconds)];
        }

        /**
         * 记一秒的命中数（这一秒没出现就记 0 —— <b>不记 0 的话基线永远不会衰减</b>：
         * 一个只在攻击时出现的帧会带着第一次的高基线活到天荒地老）。
         */
        public void push(final int countThisSecond) {
            seconds[head] = Math.max(0, countThisSecond);
            head++;
            if (head == seconds.length) {
                head = 0;
            }
            if (size < seconds.length) {
                size++;
            }
        }

        /** 中位数（偶数个取中间偏上那个；{@code 0} 次历史 ⇒ 0 = "没有基线"）。 */
        public int median() {
            if (size == 0) {
                return 0;
            }
            // 未绕回时有效数据正好排在 [0,size)；绕回后 size==length 也正好是全表
            final int[] copy = new int[size];
            System.arraycopy(seconds, 0, copy, 0, size);
            java.util.Arrays.sort(copy);
            return copy[size / 2];
        }

        /** 已经有几秒历史（自证用）。 */
        public int size() {
            return size;
        }

        /** 窗口长度（秒）。 */
        public int capacity() {
            return seconds.length;
        }
    }
}
