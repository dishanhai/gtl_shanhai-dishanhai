package com.shanhai.common.recipe;

/**
 * 横幅新增行「🔷 注册的山海物品: N」的 <b>纯逻辑核</b>。
 *
 * <h2>0. 为什么单独一个类</h2>
 * 本类<b>不 import 任何 Minecraft / Forge / Registrate 的东西</b>（只用 {@code java.lang} 与
 * {@code java.lang.Iterable}）⇒ 它<b>可以被单独 javac 编译、单独 java 执行</b>。
 * 这一点不是洁癖，是本轮判据 ③ 的实现基础：
 * 「拿不到就显示 {@code (不可用)}」这条规则必须能在<b>没有游戏实例</b>的情况下被真跑一次证明，
 * 否则它永远只是一句注释。
 * <p>
 * 工程里已有同款先例：{@link ShanhaiBatchCounters} 就是「纯算术核，不依赖 Minecraft」，
 * 并有自己的 {@code selfTest()}（启动期打进日志）。
 *
 * <h2>1. 那一行的两个口径（都在这里定死）</h2>
 * <ol>
 *   <li><b>口径 A · 注册表现数</b> → {@code registryCount}：调用方把
 *       <b>Forge 物品注册表</b>（{@code ForgeRegistries.ITEMS.getKeys()}）里的 id 逐个转成字符串交给
 *       {@link #countNamespace(Iterable, String)}，本方法<b>数出其中属于 {@code shanhai:} 的条数</b>。
 *       <b>这个数是运行时读出来的，代码里没有任何写死的物品总数。</b></li>
 *   <li><b>口径 B · 交叉核对</b> → {@code ledgerCount}：调用方另外去读
 *       <b>Registrate 自己的物品登记台账</b>（{@code REGISTRATE.getAll(Registries.ITEM).size()}）。
 *       它与口径 A 是两条<b>彼此独立</b>的读取路径（一条读游戏的 Forge 注册表，一条读 Registrate 内存里的
 *       {@code registrations} 表）⇒ 两者相等，说明「台账上说注册了多少」与「注册表里真的有多少」
 *       一致；不相等则说明有注册静默失败（或别的来源往 {@code shanhai:} 里塞了物品）。</li>
 * </ol>
 *
 * <h3>1.1 🔴 为什么不拿「{@code ShanhaiItems} 的 {@code ItemEntry} 字段个数」当第二个口径</h3>
 * 那个数（反射可得，见 {@code ShanhaiRecipeStats#shanhaiItemsDeclaredFields()}）量的是
 * <b>「{@code ShanhaiItems} 这一个类声明了多少个句柄」</b>，而口径 A 量的是
 * <b>「注册表里 {@@code shanhai:} 命名空间下有多少个物品」</b>——<b>两者根本不是同一个集合</b>：
 * 每个流体都会由 Registrate 自动派生一个 {@code shanhai:<id>_bucket} 桶物品
 * （{@code ShanhaiFluids} 的 25 个流体 ⇒ 25 个桶），以及经 {@code REGISTRATE} 注册的机器方块物品。
 * 拿这两个数做「一致/不一致」判定 ⇒ <b>那一行会永远显示「不一致」</b>，
 * 把一条真实的判据变成常亮的噪声。所以字段个数只进<b>启动期日志</b>（{@code shanhaiitems_fields=}），
 * <b>不进横幅</b>。
 *
 * <h2>2. 铁律：拿不到 ⇒ {@code (不可用)}，绝不给数字</h2>
 * {@link #countNamespace(Iterable, String)} 在收到 {@code null}（= 调用方读注册表时抛了异常）
 * 时返回 {@link #UNAVAILABLE}；{@link #valueText(long, long)} 只要发现<b>任一</b>口径是
 * {@link #UNAVAILABLE} 就返回 {@link #UNAVAILABLE_TEXT}（{@code (不可用)}）。
 * 「宁可缺，不可假」——这是本工程的血账（活界面上印假数字骗过三个人一整轮）。
 */
public final class ShanhaiItemCountCore {

    /** 「拿不到」的哨兵值。{@code < 0} 一律按「不可用」处理。 */
    public static final long UNAVAILABLE = -1L;

    /**
     * 取不到时显示的串。与 {@code ShanhaiRecipeStats.VERSION_UNAVAILABLE} <b>逐字相同</b>
     * （那一处是既有常量，本轮不动它；两处的相等关系由启动期日志
     * {@code unavail_text_eq_existing=true} 机器核对，不是靠人眼看）。
     */
    public static final String UNAVAILABLE_TEXT = "(不可用)";

    /** 那一行的中文标签（用户点单：「再统计一下注册的山海物品」）。 */
    public static final String LABEL = "注册的山海物品";

    /** 标签前的菱形（与横幅其余统计行同款）。 */
    public static final String LABEL_ICON = "🔷 ";

    /**
     * 把「注册表读出来的 id 集合」折成一个数。
     *
     * @param registryItemIds 注册表里<b>全部</b>物品 id 的字符串形式；<b>{@code null} 表示读不到</b>
     *                        （调用方读注册表时抛了异常）——此时返回 {@link #UNAVAILABLE}，
     *                        <b>不许回落成 0</b>（0 是「注册表里一个都没有」这个具体事实，不是「读不到」）。
     * @param namespacePrefix 命名空间前缀（本工程传 {@code ShanhaiMod.MOD_ID + ":"}，
     *                        即 {@code "shanhai:"}；<b>不在本类里写死</b>，避免第二个真源）
     * @return 匹配条数；{@code null} 输入 ⇒ {@link #UNAVAILABLE}
     */
    public static long countNamespace(Iterable<String> registryItemIds, String namespacePrefix) {
        if (registryItemIds == null || namespacePrefix == null) {
            return UNAVAILABLE;
        }
        long n = 0L;
        for (String id : registryItemIds) {
            if (id != null && id.startsWith(namespacePrefix)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 那一行的<b>值部分</b>（不含前缀与标签）。
     *
     * <ul>
     *   <li>任一口径 {@code < 0}（拿不到）⇒ {@link #UNAVAILABLE_TEXT}，<b>输出里一个数字都没有</b>；</li>
     *   <li>两口径相等 ⇒ 只打一个数；</li>
     *   <li>两口径不等 ⇒ <b>两个数都打出来并标注「不一致」</b>（这一行自己就能说明哪里对不上）。</li>
     * </ul>
     */
    public static String valueText(long registryCount, long ledgerCount) {
        if (registryCount < 0L || ledgerCount < 0L) {
            return UNAVAILABLE_TEXT;
        }
        if (registryCount == ledgerCount) {
            return Long.toString(registryCount);
        }
        return registryCount + "（交叉核对 " + ledgerCount + "，不一致）";
    }

    /** 整行 = {@code &$<样式>-} 前缀 + 标签 + 值。样式前缀由调用方给（本类不认识样式系统）。 */
    public static String line(String prefix, long registryCount, long ledgerCount) {
        return prefix + LABEL_ICON + LABEL + ": " + valueText(registryCount, ledgerCount);
    }

    /** 数某个字符在串里出现的次数（外部用来机器核对「那行里 {@code ?}/{@code *} 出现 0 次」）。 */
    public static int countChar(String s, char c) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** 串里有没有阿拉伯数字（判据 ③ 的「绝不是任何数字」就靠它）。 */
    public static boolean hasDigit(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                return true;
            }
        }
        return false;
    }

    /**
     * 纯核自检（<b>8 个用例</b>，全是本类自己的函数，不碰游戏）。
     *
     * <p>返回值形如 {@code ok cases=8} 或 {@code FAILED cases=8 failed=1 first=case3}——
     * 启动期打进日志（与 {@code batch_counters_selftest} 同款），因此<b>冒烟日志里就有它</b>。
     *
     * <p>其中 case2 = <b>负对照</b>：注册表读取返回 {@code null} ⇒ {@code countNamespace} 返回
     * {@link #UNAVAILABLE} ⇒ 那一行必须显示 {@link #UNAVAILABLE_TEXT} 且<b>一个数字都没有</b>。
     */
    public static String selfTest() {
        int cases = 0;
        int failed = 0;
        String firstBad = "";

        // case1 正常：两口径一致 ⇒ 只打一个数
        cases++;
        if (!"210".equals(valueText(210L, 210L))) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case1" : firstBad;
        }
        // case2 负对照（判据 ③）：注册表读取拿不到（null ⇒ UNAVAILABLE）⇒ (不可用) 且零数字
        cases++;
        long negRegistry = countNamespace(null, "shanhai:");
        String negLine = line("&$body_golden-", negRegistry, 185L);
        if (negRegistry != UNAVAILABLE
                || negLine.indexOf(UNAVAILABLE_TEXT) < 0
                || hasDigit(negLine)) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case2" : firstBad;
        }
        // case3 负对照：交叉核对那一半拿不到 ⇒ 同样 (不可用)、同样零数字
        cases++;
        String negLedger = line("&$body_golden-", 210L, UNAVAILABLE);
        if (negLedger.indexOf(UNAVAILABLE_TEXT) < 0 || hasDigit(negLedger)) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case3" : firstBad;
        }
        // case4 两口径不一致 ⇒ 两个数都在 + 标注「不一致」
        cases++;
        String mismatch = valueText(210L, 185L);
        if (mismatch.indexOf("210") < 0 || mismatch.indexOf("185") < 0 || mismatch.indexOf("不一致") < 0) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case4" : firstBad;
        }
        // case5 注册表读到了、但里面一个 shanhai 都没有 ⇒ 0（是 0，不是「不可用」）
        cases++;
        if (countNamespace(java.util.List.of(), "shanhai:") != 0L) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case5" : firstBad;
        }
        // case6 前缀过滤真的生效（别的命名空间不算进来）
        cases++;
        if (countNamespace(java.util.List.of("shanhai:a", "gtceu:b", "shanhai:c", "minecraft:d"), "shanhai:") != 2L) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case6" : firstBad;
        }
        // case7 抖动红线：正常那一行里 '?' 与 '*' 必须各 0 次（用户明令「物品名抖动的字看不清」）
        cases++;
        String okLine = line("&$body_golden-", 210L, 210L);
        if (countChar(okLine, '?') != 0 || countChar(okLine, '*') != 0) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case7" : firstBad;
        }
        // case8 负对照的那一行同样不许出现 '?' / '*'（不可用也不能抖）
        cases++;
        if (countChar(negLine, '?') != 0 || countChar(negLine, '*') != 0) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case8" : firstBad;
        }

        return failed == 0
                ? ("ok cases=" + cases)
                : ("FAILED cases=" + cases + " failed=" + failed + " first=" + firstBad);
    }

    private ShanhaiItemCountCore() {}
}
