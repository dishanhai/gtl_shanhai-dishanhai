package com.shanhai.common.news;

import com.shanhai.common.text.ShanhaiTextPalette;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏内更新公告的 <b>纯逻辑核</b>。
 *
 * <h2>0. 这个类负责什么</h2>
 * 「公告表 → 要发出去的行」这一段<b>全部纯逻辑</b>都在这里：
 * <ul>
 *   <li><b>公告表</b>（{@link #TABLE}）：本轮只有一条（{@link #NEWS_ID_2026_10_06}），
 *       但结构是<b>表</b>，以后加一条就是往 {@code List.of(…)} 里再插一个 {@link News} —— 一行的事；</li>
 *   <li><b>序号取用</b>（{@link #byOrdinal}）：{@code 1 = 最新}，往前翻就是 2 / 3 / …；</li>
 *   <li><b>投递决议</b>（{@link #resolve}）：要么给出「逐行发出去的那几行」，
 *       要么给出「中文提示」——<b>二者必居其一，不存在第三种结果</b>；</li>
 *   <li><b>目录</b>（{@link #listLines}）：{@code /shanhai 公告 list} 的那几行（序号 + 日期 + 标题）。</li>
 * </ul>
 * 本类<b>不 import 任何 Minecraft / Forge 的东西</b>：它只用 {@code java.util} 与
 * {@link ShanhaiTextPalette}（后者同样没有 MC 依赖，见其类注释 §1 的取证）
 * ⇒ 本类<b>可以被单独 javac 编译、单独 java 执行</b>。
 * 这一点不是洁癖：它是本子系统「离线自证」的<b>实现基础</b>（装置见
 * {@code temp\news-verify-*\offline\NewsVerify.java}），否则那几条判据永远只是一句注释。
 *
 * <h2>1. 🔴 文本从哪来：全部【写进 jar】，本轮不读任何配置文件</h2>
 * 用户 2026-10-06 原话：「老了（的）公告也存着，可以通过打指令显示」＋「<b>可以写进 jar</b>」
 * ⇒ 本轮公告正文就是本类里的<b>编译期常量表</b>。
 * <b>刻意不做</b>「读 config / 读 json / 读 classpath 资源」那条路：
 * 那是以后的事，本轮做它会扩大改动面，而且会把「文案」变成运行期可变的输入
 * （现在它是<b>编译进 jar 的定稿</b>，改一个字就必须重新构建 —— 这正是这一轮要的性质）。
 *
 * <h2>2. 🔴 逐字纪律</h2>
 * {@link News#lines()} 里那 5 行是<b>用户定稿的原文，一个字都不许改</b>
 * （包括行首的 4 个空格、全角 {@code ／}、全角 {@code ＋}、以及 {@code =} 的个数）。
 * 因此本类只做「前缀常量 + 正文」的拼接 {@link #PREFIX_STYLE_MAIN}/{@link #PREFIX_STYLE_SUB}，
 * <b>不做任何 trim / 归一化 / 全半角转换</b> —— 那类"贴心处理"会把定稿改掉。
 * <p>
 * 判据不是靠人眼看：离线装置会把本类产出的 5 行与一份独立写下的参照文件做
 * <b>逐字节比对</b>，并打印每一行的码点与 SHA-256。
 *
 * <h2>3. 🔴 配色：只用 {@code body_golden} / {@code body_silver}，绝不用抖动效果字符</h2>
 * 用户明令：「抖动的字看不清」⇒ 本子系统<b>不出现</b> {@code &$?…-}（glitch）与 {@code &$*…-}（floatX）
 * 这两种带效果字符的前缀。机器判据：整张表里 {@code "&$?"} 与 {@code "&$*"} 的出现次数必须各为 0
 * （{@link #selfTest()} 的 case，离线装置也会再验一遍）。
 * <p>
 * 两个样式名必须<b>真实存在</b>于 {@link ShanhaiTextPalette} 的注册表里，
 * 否则客户端会走「退化绘制」（剥掉码、按普通白字画，见 {@code ShanhaiTextParser}）
 * —— 这也是 {@link #selfTest()} 里一拍：逐个样式名去 {@link ShanhaiTextPalette#get(String)} 查。
 *
 * <h2>4. 🔴 拿不到 / 越界 ⇒ 一行都不发（发空行是错的）</h2>
 * 「表是空的」「序号越界」「这条公告的正文是空的/含 null 行」三种情况
 * <b>一律收敛成 {@link Delivery#lines()} 为空表</b> + 一句中文提示。
 * 理由：{@code sendSystemMessage(Component.literal(""))} 会在聊天框里留一条<b>空白行</b>，
 * 那是一条「看起来像公告、其实什么都没有」的输出 —— 本工程口径是<b>宁可缺，不可假</b>。
 *
 * <h2>5. 日志口径（机器判据就在这里）</h2>
 * 公告<b>真的发出去</b>时打一行（{@link #sentLogLine}），格式固定：
 * <pre>
 * [SHANHAI-NEWS] banner_sent id=&lt;公告id&gt; lines=&lt;行数&gt; total=&lt;表长&gt; by=&lt;player|console&gt; ordinal=&lt;序号&gt;
 * </pre>
 * 例：{@code [SHANHAI-NEWS] banner_sent id=2026-10-06 lines=5 total=1 by=player ordinal=1}
 * <p>
 * ⚠️ {@code by=} / {@code ordinal=} 是<b>追加</b>在 {@code total=} 之后的：
 * 用户给的口径样例 {@code [SHANHAI-NEWS] banner_sent id=2026-10-06 lines=5 total=1}
 * 是这一行的<b>前缀</b>，按子串 grep 一定能命中。
 */
public final class ShanhaiNewsCore {

    private ShanhaiNewsCore() {}

    // ================================================================== 常量

    /** 本子系统所有日志行的前缀（与 {@code [SHANHAI-SPEC]} / {@code [SHANHAI-EDIT]} 同族）。 */
    public static final String LOG_PREFIX = "[SHANHAI-NEWS]";

    /** 公告真的发出去时那一行的键名。grep 这一个词就能定位。 */
    public static final String SENT_LOG_KEY = "banner_sent";

    /** 公告表为空时的中文提示（用户原话「没有公告」）。 */
    public static final String MSG_NO_NEWS = "现在没有任何公告";

    /** 序号小于 1 时的中文提示（{@code IntegerArgumentType} 挡了大部分，这里是纯核自己的兜底）。 */
    public static final String MSG_ORDINAL_TOO_SMALL = "序号要从 1 开始（1 = 最新那条）";

    /** 标题缺失时的兜底显示串（<b>不编一个标题</b>）。 */
    public static final String TITLE_FALLBACK = "（无标题）";

    /** 日期缺失时的兜底显示串。 */
    public static final String DATE_FALLBACK = "（无日期）";

    /**
     * 主句（标题行 / 收尾行）的样式前缀 = {@code &$body_golden-}。
     * <p>与 {@code ShanhaiRecipeStats.PREFIX_STAT} 是<b>同一个串</b>，但此处独立声明：
     * 公告文案与横幅文案是两份各自定稿的东西，绑到对方的常量上会让「改横幅」意外改到公告。
     * 两者相等这件事由离线装置的断言盯着（不是靠约定）。
     */
    public static final String PREFIX_STYLE_MAIN = "&$body_golden-";

    /** 引导行（正文说明）的样式前缀 = {@code &$body_silver-}。 */
    public static final String PREFIX_STYLE_SUB = "&$body_silver-";

    /** 抖动红线：这两个子串在整张表里必须出现 0 次。 */
    public static final String GLITCH_MARK = "&$?";
    public static final String FLOATX_MARK = "&$*";

    /** 本条公告的 id（= 日期，用户指定）。 */
    public static final String NEWS_ID_2026_10_06 = "2026-10-06";

    // ================================================================== 公告表

    /**
     * 一条公告。
     *
     * @param id    唯一 id（本条 = 日期 {@code 2026-10-06}）；进日志的 {@code id=} 字段，也是列表里认它的键
     * @param date  显示用日期（{@code yyyy-MM-dd}）；{@code /shanhai 公告 list} 印它
     * @param title 短标题；<b>只给 {@code list} 用</b>，<b>不</b>出现在发出去的正文里
     *              （正文那 5 行是用户定稿，多一个字都不行）
     * @param lines 正文逐行原文（<b>含行首 {@code &$…-} 样式前缀</b>），发出去时一行一条聊天消息
     */
    public record News(String id, String date, String title, List<String> lines) {}

    /**
     * 🔴 <b>公告表本体（新的在前：下标 0 == 序号 1 == 最新）</b>。
     *
     * <h4>以后「加一条公告」要动哪里</h4>
     * 就在下面这个 {@code List.of(...)} 的<b>最前面</b>再插一个 {@code new News(...)}。
     * 序号会自动跟着变（新插的那条就是 1，老的依次往后挪），{@code /shanhai 公告 list} 与
     * 「序号越界」提示里的条数也都会自动跟着变 —— <b>没有任何第二处需要同步</b>。
     *
     * <h4>本轮为什么只有一条</h4>
     * 用户 2026-10-06 只给了这一条（{@code id=2026-10-06}）。表结构已经按「多条」设计，
     * 但<b>不预置任何占位/示例条目</b>：表里出现的每一条都是真公告，不许有假数据。
     */
    private static final List<News> TABLE = List.of(
            new News(
                    NEWS_ID_2026_10_06,
                    NEWS_ID_2026_10_06,
                    "悬浮全息菜单 · 游戏内配方编辑器",
                    List.of(
                            PREFIX_STYLE_MAIN + "· 悬浮全息菜单：手持「创始现实修改模块」对空气右键，五块板就绕着你转起来",
                            PREFIX_STYLE_SUB + "    · 空手点板子才能操作；朝向 / 排布 / 距离可调，另有一圈环绕特效（数据环 ＋ 200 个光点，四档）",
                            PREFIX_STYLE_MAIN + "· 游戏内配方编辑器：全息菜单里点「配方修改」那一块",
                            PREFIX_STYLE_SUB + "    · 新建 / 修改 / 删除配方；输入输出、时长、电压、条件都能改；物品和流体都能查；改完立刻生效、重启也在",
                            PREFIX_STYLE_MAIN + "=========== 备用指令 /shanhai menu、/shanhai edit ／ 以前的公告 /shanhai 公告 ==========="
                    )
            )
    );

    /** 公告表（<b>只读视图</b>，调用方不许改）。 */
    public static List<News> table() {
        return TABLE;
    }

    /** 表长（= {@code /shanhai 公告 list} 的条数，也是日志里的 {@code total=}）。 */
    public static int total() {
        return TABLE.size();
    }

    // ================================================================== 取用

    /**
     * 按<b>序号</b>取一条公告：{@code 1 = 最新}，{@code 2} 往前翻一条，依此类推。
     *
     * @param table   公告表（新的在前）。{@code null} 当成空表，<b>不抛异常</b>
     * @param ordinal 序号，从 1 开始
     * @return 那一条；<b>空表 / {@code ordinal < 1} / 大于表长 ⇒ {@code null}</b>（调用方必须处理 null，
     *         绝不许直接 {@code table.get(ordinal - 1)} —— 那就是 {@code IndexOutOfBoundsException}）
     */
    public static News byOrdinal(List<News> table, int ordinal) {
        if (table == null || ordinal < 1 || ordinal > table.size()) {
            return null;
        }
        return table.get(ordinal - 1);
    }

    /**
     * 一条公告的<b>投递决议</b>：要么给出去发的那几行，要么给出中文提示。
     *
     * <p>{@link #ok()} 为假时 {@link #lines()} <b>保证是空表</b>（不是 {@code null}，
     * 也不是含空行的表）—— 调用方照空表发就<b>一行都不会发出去</b>，不会留空白聊天行。
     */
    public record Delivery(List<String> lines, String message) {

        /** 有正文可发（= 至少一行，且没有一行是空的）。{@code lines} 为 {@code null} 也按「不可发」处理。 */
        public boolean ok() {
            return lines != null && !lines.isEmpty();
        }
    }

    /**
     * <b>本子系统的唯一投递决议入口</b>（登录那一拍与 {@code /shanhai 公告} 命令共用）。
     *
     * <p>三种失败各给一句中文提示，且三者措辞<b>不同</b>（把"没有这条"与"表是空的"、"这条正文坏了"
     * 分开说 —— 混成一句会让排查时读不出是哪一种）：
     * <ol>
     *   <li>表为空 ⇒ {@link #MSG_NO_NEWS}；</li>
     *   <li>序号越界（表非空）⇒ {@link #notFoundMessage}；</li>
     *   <li>取到了但正文是空的/含 null 行 ⇒ {@link #brokenTextMessage}。</li>
     * </ol>
     *
     * @param table   公告表
     * @param ordinal 序号（{@code 1 = 最新}）
     */
    public static Delivery resolve(List<News> table, int ordinal) {
        final News n = byOrdinal(table, ordinal);
        if (n == null) {
            return new Delivery(List.of(), notFoundMessage(table, ordinal));
        }
        final List<String> lines = sanitize(n.lines());
        if (lines.isEmpty()) {
            return new Delivery(List.of(), brokenTextMessage(ordinal));
        }
        return new Delivery(lines, "");
    }

    /** 最新那一条的投递决议（= {@code resolve(table(), 1)}）。 */
    public static Delivery resolveLatest() {
        return resolve(TABLE, 1);
    }

    /**
     * 逐行清洗：<b>丢掉 null 行；只要出现一行 null 或空串，整条都拒发（返回空表）</b>。
     *
     * <p>为什么不是"跳过坏行、发剩下的"：那会发出一条<b>缺了一行、却看起来正常</b>的公告
     * （本工程的老账：宁可响亮地缺席，也不要残缺地正常）。整条拒发时调用方会拿到
     * {@link #brokenTextMessage}，日志里也看得出来。
     *
     * <p>⚠️ 本方法<b>不做 trim / 不合并空格</b>：正文里有刻意保留的行首缩进（4 个空格）。
     */
    static List<String> sanitize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        final List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s == null || s.isEmpty()) {
                return List.of();
            }
            out.add(s);
        }
        return out;
    }

    /**
     * 取不到时的中文提示。
     *
     * <ul>
     *   <li>表为空 ⇒ {@link #MSG_NO_NEWS}（「现在没有任何公告」）；</li>
     *   <li>{@code ordinal < 1} ⇒ {@link #MSG_ORDINAL_TOO_SMALL}；</li>
     *   <li>越界 ⇒ 「没有第 N 条公告（现在一共 M 条，1 = 最新）」——
     *       <b>把总数也说出来</b>，用户才知道该打几。</li>
     * </ul>
     */
    public static String notFoundMessage(List<News> table, int ordinal) {
        final int t = (table == null) ? 0 : table.size();
        if (t == 0) {
            return MSG_NO_NEWS;
        }
        if (ordinal < 1) {
            return MSG_ORDINAL_TOO_SMALL;
        }
        return "没有第 " + ordinal + " 条公告（现在一共 " + t + " 条，1 = 最新）";
    }

    /** 「这条在表里，但正文是空的 / 有 null 行」的中文提示（与"没有这条"分开说）。 */
    public static String brokenTextMessage(int ordinal) {
        return "第 " + ordinal + " 条公告的正文是空的（数据坏了，不是没有这条）";
    }

    // ================================================================== 目录（list）

    /**
     * {@code /shanhai 公告 list} 的那几行：<b>一条公告一行</b>，形如
     * <pre>1. 2026-10-06  悬浮全息菜单 · 游戏内配方编辑器</pre>
     *
     * <p>🔴 <b>返回值条数恒等于表长</b>（空表 ⇒ 空表）—— 这是可以机器判的：
     * {@code listLines(t).size() == t.size()} 对任何 {@code t} 都成立。
     * 因此这里<b>不加</b>表头行（加了这条恒等式就没了），"表是空的"由调用方用
     * {@link #MSG_NO_NEWS} 单独说。
     *
     * <p>⚠️ 目录行<b>刻意不带</b> {@code &$…-} 样式前缀：它是索引，不是公告正文；
     * 少一处样式名就少一处「样式名拼错 ⇒ 退化绘制」的风险。
     */
    public static List<String> listLines(List<News> table) {
        if (table == null || table.isEmpty()) {
            return List.of();
        }
        final List<String> out = new ArrayList<>(table.size());
        for (int i = 0; i < table.size(); i++) {
            final News n = table.get(i);
            out.add((i + 1) + ". " + dateOf(n) + "  " + titleOf(n));
        }
        return out;
    }

    /** 标题兜底：{@code null}/空白 ⇒ {@link #TITLE_FALLBACK}（<b>绝不编一个标题</b>）。 */
    public static String titleOf(News n) {
        if (n == null || n.title() == null || n.title().isBlank()) {
            return TITLE_FALLBACK;
        }
        return n.title();
    }

    /** 日期兜底：{@code null}/空白 ⇒ {@link #DATE_FALLBACK}。 */
    public static String dateOf(News n) {
        if (n == null || n.date() == null || n.date().isBlank()) {
            return DATE_FALLBACK;
        }
        return n.date();
    }

    // ================================================================== 日志口径

    /**
     * 公告<b>真的发出去</b>时那一行（机器判据）。
     *
     * <pre>
     * [SHANHAI-NEWS] banner_sent id=&lt;id&gt; lines=&lt;行数&gt; total=&lt;表长&gt; by=&lt;player|console&gt; ordinal=&lt;序号&gt;
     * </pre>
     *
     * <p>口径逐条写死（读这行的人不需要再看代码）：
     * <ul>
     *   <li>{@code id} = 发出去的是<b>哪一条</b>公告（本子系统 = 日期）；</li>
     *   <li>{@code lines} = <b>真的发出去的行数</b>（不是表里写了几行）；</li>
     *   <li>{@code total} = 公告表<b>现在一共几条</b>（不是"发了几条"）；</li>
     *   <li>{@code by} = 谁触发的：{@code player}（登录那一拍 / 玩家打命令）或 {@code console}；</li>
     *   <li>{@code ordinal} = 发的是第几条（{@code 1 = 最新}）。</li>
     * </ul>
     * ⚠️ 日志串里<b>不含</b>公告正文：正文只在聊天框里，日志里只放口径
     * （要核对正文的话有 {@code news_preview} 那一族行）。
     */
    public static String sentLogLine(String id, int lines, int total, String by, int ordinal) {
        return LOG_PREFIX + " " + SENT_LOG_KEY
                + " id=" + (id == null ? "" : id)
                + " lines=" + lines
                + " total=" + total
                + " by=" + (by == null ? "" : by)
                + " ordinal=" + ordinal;
    }

    /**
     * 启动期那一行「公告表现在有什么」：
     * <pre>[SHANHAI-NEWS] news_table total=&lt;n&gt; newest=&lt;id|none&gt;</pre>
     * <p>为什么要它：公告只有「玩家登录后 160 tick」才发得出去，
     * 无头专服里<b>永远看不到</b> ⇒ 表的内容必须另有一条启动期读数，冒烟日志才有判据。
     */
    public static String tableLogLine(List<News> table) {
        final int t = (table == null) ? 0 : table.size();
        final News n = byOrdinal(table, 1);
        return LOG_PREFIX + " news_table total=" + t + " newest=" + (n == null ? "none" : n.id());
    }

    // ================================================================== 自检（纯逻辑）

    /**
     * 纯核自检（<b>14 个用例</b>，全是本类自己的函数，不碰游戏）。
     *
     * <p>返回值形如 {@code ok cases=14} 或 {@code FAILED cases=14 failed=1 first=caseX}——
     * 启动期打进日志（与 {@code item_count_selftest} / {@code batch_counters_selftest} 同款），
     * 因此<b>冒烟日志里就有它</b>。
     *
     * <p>⚠️ <b>本自检刻意不逐字复述那 5 行正文</b>：把定稿文本再抄一份进来就是<b>第二个真源</b>，
     * 两份必然会漂移。这里只验<b>结构性质</b>（条数、顺序、前缀、样式名真实存在、抖动字符为 0、
     * 越界不抛）；「逐字节等于定稿」由离线装置拿<b>独立写下的参照文件</b>来验
     * （见 {@code temp\news-verify-*\offline\}）。
     */
    public static String selfTest() {
        int cases = 0;
        int failed = 0;
        String firstBad = "";

        final List<News> t = TABLE;

        // case1 表非空
        cases++;
        if (t.isEmpty()) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case1" : firstBad;
        }

        // case2 序号 1 = 最新那一条（取到的是 id 为 2026-10-06 的那条）
        cases++;
        final News newest = byOrdinal(t, 1);
        if (newest == null || !NEWS_ID_2026_10_06.equals(newest.id())) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case2" : firstBad;
        }

        // case3 最新那条逐行发出去 = 5 行
        cases++;
        final Delivery d1 = resolve(t, 1);
        if (!d1.ok() || d1.lines().size() != 5) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case3" : firstBad;
        }

        // case4 每行都以那两个样式前缀之一开头（顺序无关，纯结构）
        //   🔴 必须先要求 d1.ok()：否则空表时循环一次都不执行、prefixOk 停在 true，
        //      这一拍会【静默通过】——那就是一条永远绿的假断言。
        cases++;
        boolean prefixOk = d1.ok();
        for (String s : d1.lines()) {
            if (!s.startsWith(PREFIX_STYLE_MAIN) && !s.startsWith(PREFIX_STYLE_SUB)) {
                prefixOk = false;
            }
        }
        if (!prefixOk) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case4" : firstBad;
        }

        // case5 首行与末行用主句前缀（标题行 / 收尾行的角色），中间两行是引导行
        //   🔴 先判 size()==5 再 get —— 直接用 get(0)/get(4) 会在表被清空时抛
        //      ArrayIndexOutOfBoundsException，把一次「自检报红」变成一次「自检炸掉」
        //      （负对照实测抓到过这一处，见 temp\news-verify-*\offline\ 的 negctl 段）。
        cases++;
        if (d1.lines().size() != 5
                || !d1.lines().get(0).startsWith(PREFIX_STYLE_MAIN)
                || !d1.lines().get(4).startsWith(PREFIX_STYLE_MAIN)
                || !d1.lines().get(1).startsWith(PREFIX_STYLE_SUB)
                || !d1.lines().get(3).startsWith(PREFIX_STYLE_SUB)) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case5" : firstBad;
        }

        // case6 抖动红线：整张表里 '&$?' 与 '&$*' 各 0 次
        cases++;
        if (countMark(t, GLITCH_MARK) != 0 || countMark(t, FLOATX_MARK) != 0) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case6" : firstBad;
        }

        // case7 样式名真实存在（写错就会走退化绘制 ⇒ 白字）
        cases++;
        boolean styleOk = ShanhaiTextPalette.get("body_golden") != null
                && ShanhaiTextPalette.get("body_silver") != null;
        if (!styleOk) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case7" : firstBad;
        }

        // case8 序号 2 越界 ⇒ 决议为"不可发"，且提示是「没有第 2 条…」（不是抛异常）
        cases++;
        final Delivery d2 = resolve(t, 2);
        if (d2.ok() || !d2.message().contains("没有第 2 条公告")) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case8" : firstBad;
        }

        // case9 序号 0 ⇒ 提示「要从 1 开始」
        cases++;
        if (!MSG_ORDINAL_TOO_SMALL.equals(resolve(t, 0).message())) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case9" : firstBad;
        }

        // case10 负对照：表被 remove 掉（空表）⇒ 「现在没有任何公告」且一行都不发
        cases++;
        final Delivery dEmpty = resolve(List.of(), 1);
        if (dEmpty.ok() || dEmpty.lines().size() != 0 || !MSG_NO_NEWS.equals(dEmpty.message())) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case10" : firstBad;
        }

        // case11 负对照：正文里混进一个 null 行 ⇒ 整条拒发（不许发空行、不许发残缺的半条）
        cases++;
        final List<News> broken = List.of(new News("x", "x", "x", java.util.Arrays.asList("a", null, "c")));
        final Delivery dBroken = resolve(broken, 1);
        if (dBroken.ok() || dBroken.lines().size() != 0 || dBroken.message().isEmpty()) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case11" : firstBad;
        }

        // case12 list 条数恒等于表长；空表 ⇒ 空表
        cases++;
        if (listLines(t).size() != t.size() || !listLines(List.of()).isEmpty()) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case12" : firstBad;
        }

        // case13 日志行口径：样例前缀必须逐字命中（用户给的那一行就是它的前缀）
        cases++;
        final String logLine = sentLogLine(NEWS_ID_2026_10_06, 5, t.size(), "player", 1);
        if (!logLine.startsWith("[SHANHAI-NEWS] banner_sent id=2026-10-06 lines=5 total=1")) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case13" : firstBad;
        }

        // case14 表是只读的：table() 返回的就是 TABLE 本体，改它必须抛（不是悄悄改坏）
        cases++;
        boolean immutable = false;
        try {
            table().add(new News("y", "y", "y", List.of("z")));
        } catch (UnsupportedOperationException e) {
            immutable = true;
        } catch (Throwable ignored) {
            immutable = false;
        }
        if (!immutable) {
            failed++;
            firstBad = firstBad.isEmpty() ? "case14" : firstBad;
        }

        return failed == 0
                ? ("ok cases=" + cases)
                : ("FAILED cases=" + cases + " failed=" + failed + " first=" + firstBad);
    }

    /**
     * 数一个子串在整张表（id/date/title/lines 全部）里出现的总次数。
     *
     * <p>{@code public} 是<b>有意</b>的：抖动红线（{@code "&$?"} / {@code "&$*"} 各 0 次）是
     * 一条<b>要在 jar 外也能重跑</b>的判据 —— 离线装置（{@code temp\news-verify-*\offline\}）
     * 直接调的就是它。（{@code ShanhaiItemCountCore.countChar} 出于同一个理由也是 public。）
     */
    public static int countMark(List<News> table, String mark) {
        if (table == null || mark == null) {
            return 0;
        }
        int n = 0;
        for (News news : table) {
            n += occurrences(news.id(), mark);
            n += occurrences(news.date(), mark);
            n += occurrences(news.title(), mark);
            if (news.lines() != null) {
                for (String s : news.lines()) {
                    n += occurrences(s, mark);
                }
            }
        }
        return n;
    }

    /** 子串出现次数（不用 {@code String.split} —— 它在中文/正则元字符上有坑）。 */
    static int occurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int n = 0;
        int from = 0;
        while (true) {
            final int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + needle.length();
        }
    }
}
