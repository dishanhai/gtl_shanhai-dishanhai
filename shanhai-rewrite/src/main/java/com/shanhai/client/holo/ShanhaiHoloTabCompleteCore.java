package com.shanhai.client.holo;

import java.util.List;
import java.util.Locale;

/**
 * 山海重构 · 全息命令输入框的 <b>Tab 自动补全 · 纯逻辑核</b>。
 *
 * <h2>1. 用户原话（逐字）</h2>
 * <blockquote>
 *   对了，在原版输入指令中，按 tab 可以自动补全指令，你也在我们的指令输入那里（就是全息投影的指令输入的地方）添加一个
 * </blockquote>
 * <blockquote>
 *   还有就是我们这个<b>输入框是不需要输入/</b>的，但是候选表<b>只有输入 / 的时候才会出来</b>
 * </blockquote>
 * <blockquote>
 *   候选表位置可以改 ／ 需要支持鼠标候选
 * </blockquote>
 *
 * <h2>2. 🔴 这个类里【哪些是本项目写的、哪些是原版公式的转写】—— 先说清楚，免得误读</h2>
 * <table border="1">
 *   <tr><th>函数</th><th>性质</th><th>谁在调它</th></tr>
 *   <tr><td>{@link #tabEligible(String)}</td>
 *       <td><b>本项目自己的口径</b>：只有以 {@code /} 开头才允许补全</td>
 *       <td>🔴 <b>生产路径真的调它</b> —— {@link ShanhaiHoloCommandScreen} 的 Tab 闸门
 *           与 {@code setAllowSuggestions} 都读它。本轮之后镜子里【恒】以 {@code /} 开头
 *           （见 §5），所以这道闸门恒真 —— 它从"过滤器"变成了"断言"：
 *           只要镜子的口径没被改坏，玩家名候选表就【结构上】不可能冒出来</td></tr>
 *   <tr><td>{@link #mirrorText(String)} / {@link #panelText(String, boolean)} /
 *       {@link #mirrorAddedSlash(String)} / {@link #wordStartForCompletion(String)}</td>
 *       <td><b>本轮新写的口径</b>（"输入框省掉的那个 {@code /}"）</td>
 *       <td>🔴 生产路径真的调它们 —— 面板 ⇄ 镜子 的每一次搬运（见 §5）</td></tr>
 *   <tr><td>{@link #anchorFor(int, int, double[], int)} / {@link #boxOfCorners(double[], double[])} /
 *       {@link #toListSpace(double, double, int, int)} / {@link #contains(int[], double, double)}</td>
 *       <td><b>本轮新写的口径</b>（候选表锚在全息板上 + 鼠标命中）</td>
 *       <td>🔴 生产路径真的调它们：{@code anchorFor} 决定渲染时的位姿平移与鼠标转发量，
 *           {@code boxOfCorners} 决定"四角投影能不能凑出一个矩形"，{@code toListSpace}
 *           就是鼠标坐标那一次换算</td></tr>
 *   <tr><td>{@link #vanillaScaledMouse(double, int, int)} / {@link #rawMouseFor(double, int, int)} /
 *       {@link #wheelTarget(double, double, int, int, int, int, int, int)}</td>
 *       <td><b>本轮新写的口径</b>（滚轮：把鼠标坐标摆到"候选表坐标系"那一格）</td>
 *       <td>🔴 生产路径真的调它们 —— {@code ShanhaiHoloCommandScreen#mouseScrolled} 拿
 *           {@code wheelTarget} 的结果去<b>临时换</b> {@code MouseHandler} 上那个坐标。
 *           为什么必须这么绕：1.20.1 那个入口<b>不接坐标</b>（见 §8）</td></tr>
 *   <tr><td>其余 8 个（{@code applyCompletion / completeWith / ghostSuffix / cycleIndex /
 *       lastWordIndex / prefixHitForRanking / commonPrefix}）</td>
 *       <td><b>原版公式的逐条转写</b>（每条都标了出处：类名 + 方法名）</td>
 *       <td>⚠️ <b>生产路径【不】经过它们</b>。生产补全走的是原版
 *           {@code net.minecraft.client.gui.components.CommandSuggestions} 本身
 *           —— 本工程硬纪律是「原版已有的东西不许重写」。它们留在这里的唯一目的，
 *           是让「与原版语义一致」这件事有一条<b>能离线跑、能红能绿</b>的判据
 *           （见 {@link #selfTest()}）。</td></tr>
 * </table>
 * <p>⇒ 换句话说：<b>这个类不是"补全器的实现"，而是"补全语义的尺子 + 那几条我们自己定的口径"。</b>
 * 把尺子放在这里而不是散在探针里，是为了让它跟着源码一起编译、一起被 review。
 * <p>⚠️ 本类<b>不许 import 任何 Minecraft / JOML 的东西</b>：离线判据装置是用
 * {@code javac} 单独编它（没有 classpath、没有游戏）跑 {@link #selfTest()} 的
 * （见 {@code temp/holo-tabcomplete/run.ps1} 与 {@code run2.ps1}）。
 *
 * <h2>3. 原版语义的出处（都是本机真源码/真字节码核过的，不是凭记忆）</h2>
 * <pre>
 *   ① 补完一段：Brigadier 1.1.8 {@code com.mojang.brigadier.suggestion.Suggestion#apply(String)}
 *      （javap -c 核过）：
 *        if (range.start == 0 &amp;&amp; range.end == input.length()) return text;
 *        sb = new StringBuilder();
 *        if (range.start &gt; 0) sb.append(input, 0, range.start);
 *        sb.append(text);
 *        if (range.end &lt; input.length()) sb.append(input.substring(range.end));
 *        return sb.toString();
 *   ② 灰字尾巴：{@code CommandSuggestions#calculateSuggestionSuffix}
 *        return candidate.startsWith(input) ? candidate.substring(input.length()) : null;
 *   ③ 候选循环：{@code CommandSuggestions.SuggestionsList#select(int)} —— 越界只补/减<b>一次</b>
 *        if (current &lt; 0) current += size;  if (current &gt;= size) current -= size;
 *      （{@code cycle(±1)} 每次只走一步 ⇒ 单次回绕与取模等价，见 {@link #cycleIndex}）
 *   ④ 分词：{@code CommandSuggestions#getLastWordIndex} = 最后一段空白之【后】的下标（0 基）
 *   ⑤ 排序：{@code CommandSuggestions#sortSuggestions} 把<b>用户敲的那一段</b>
 *      {@code toLowerCase(Locale.ROOT)} 之后再比前缀（候选本身不动）
 *   ⑥ 大小写不敏感匹配：Brigadier 1.1.8 {@code LiteralCommandNode#listSuggestions}
 *        literalLowerCase.startsWith(builder.getRemainingLowerCase()) ?
 *            builder.suggest(literal) : Suggestions.empty()
 *      ⇒ <b>匹配不敏感，但补进去的是【候选自己的】规范大小写</b>
 *   ⑦ 候选表的屏幕几何（本轮加进来的，逐条有出处）：
 *        {@code CommandSuggestions#showSuggestions}：
 *          int k = anchorToBottom ? this.screen.height - 12 : 72;
 *        {@code SuggestionsList} 的构造器：
 *          int j = anchorToBottom ? k - 3 - min(size, lineLimit) * 12 : k;
 *          this.rect = new Rect2i(x - 1, j, w + 1, min(size, lineLimit) * 12);
 *        ⇒ <b>候选表的【下边缘】恒等于 {@code screen.height - 15}，与候选条数无关</b>
 *          —— 这一条是本轮能"只靠已知量"把它挪走的全部依据（见 §6）
 *        {@code Rect2i#contains}：{@code x >= this.x && x < this.x + width && y >= this.y && y < this.y + height}
 *        {@code EditBox#getScreenX(int)}：{@code x + font.width(value.substring(0, pos))}
 * </pre>
 *
 * <h2>4. 为什么 Tab 闸门必须由我们来做</h2>
 * 原版 {@code CommandSuggestions#keyPressed} 长这样：
 * <pre>
 *   if (this.suggestions != null &amp;&amp; this.suggestions.keyPressed(...)) return true;
 *   else if (this.screen.getFocused() == this.input &amp;&amp; key == 258) { this.showSuggestions(true); return true; }
 *   else return false;
 * </pre>
 * 🔴 注意 {@code showSuggestions(true)} <b>不看 {@code allowSuggestions}</b>，而 {@code ChatScreen}
 * 传的 {@code commandsOnly = false} ⇒ 输入不以 {@code /} 开头时，
 * {@code updateCommandInfo} 走的是<b>「玩家名候选」</b>那条路（{@code getCustomTabSugggestions()}）。
 * <p>本轮之前，闸门靠"不以 {@code /} 开头就别把按键递下去"来挡它 —— 代价就是
 * <b>用户不敲 {@code /} 就永远看不到候选表</b>（这正是用户点出来的那一条）。
 * 现在改成<b>让镜像里【恒】带一个前导 {@code /}</b>（见 §5）：
 * 原版走的一直是命令补全那条路 ⇒ 玩家名候选表<b>结构上</b>不可能出现，
 * 也不需要再靠"拦按键"去挡它。
 *
 * <h2>5. 🆕 ① 让"镜子"里恒带一个前导 {@code /}（本轮唯一改语义的地方）</h2>
 * 面板（{@link ShanhaiHoloMenuPanel} 里那份正文）是<b>唯一真相</b>，EditBox 只是一面隐形镜子。
 * 本轮把这对搬运改成：
 * <pre>
 *   面板 → 镜子 : {@link #mirrorText}(面板)   —— 不以 '/' 开头就补一个；本来就以 '/' 开头就不补
 *                                                （⇒ <b>绝不会变成 "//sh"</b>）
 *   镜子 → 面板 : {@link #panelText}(镜子, {@link #mirrorAddedSlash}(补之前的面板))
 *                                                —— 我们补的那个斜杠脱掉；他自己敲的斜杠留着
 * </pre>
 * 🔴 <b>为什么不是"把 {@code commandsOnly} 设成 true"</b>（任务书点名不许的那种简单粗暴法）：
 * {@code commandsOnly} 只影响 {@code flag1}，而 {@code stringreader} 上那个
 * <b>{@code if (flag) stringreader.skip();} 仍然不会执行</b>（flag 看的是第一个字符是不是 '/'）
 * ⇒ 解析器会从<b>下标 0</b> 开始读 {@code "sh"}，把整个 {@code sh} 当成命令名去匹配、
 * 补全出来的候选也落在 {@code [0,2)} 上 ⇒ 与"敲 / 的那条路"<b>不是同一套偏移</b>，
 * 而且 {@code updateCommandInfo} 里 {@code s.substring(0, i)} 之类的分支也会变。
 * 镜像方案则是<b>让原版完全走它自己那条已经验证过的路</b>（斜杠 + skip + range 从 1 起），
 * 我们一个字节都不碰原版类。
 * <p>⚠️ <b>偏移只在边界上换算</b>：原版所有的 start/end/光标都在<b>镜子坐标系</b>里算
 * （镜子 = 面板 + 1 个前导斜杠 ⇒ 全部下标恰好 +1），所以
 * {@link #applyCompletion} 那一类"原版公式"的转写不需要任何 +1 补丁；
 * 需要 +1/−1 的只有这两处搬运。{@link #wordStartForCompletion} 负责"补的是哪一段"的起点
 * （命令名那一段从斜杠<b>之后</b>开始 = 原版 {@code stringreader.skip()} 的同一口径）。
 *
 * <h2>6. 🆕 ② 候选表锚在全息板上（以及为什么能只靠"已知量"做）</h2>
 * 原版 {@code CommandSuggestions} 把候选表摆在屏幕底部，而且它的横纵坐标都不是我们能传进去的参数：
 * <pre>
 *   x = clamp(input.getScreenX(range.start), 0, input.getScreenX(0) + input.getInnerWidth() - 最宽候选)
 *   y = screen.height - 15 - min(候选数, 10) * 12        （anchorToBottom = true 这一档）
 * </pre>
 * 🔴 候选表的宽/高是原版的<b>私有字段</b>（{@code pendingSuggestions} / {@code suggestions}），
 * 我们在外面读不到 ⇒ 想把它挪走，只能靠"<b>与候选条数无关的那些量</b>"：
 * <ul>
 *   <li><b>下边缘恒在 {@code screen.height - 15}</b>（出处见 §3 ⑦）⇒ 纵向位移可以精确算出来；</li>
 *   <li><b>左边缘 = 镜子左边缘 + 已输入前缀的宽度</b>（出处 {@code EditBox#getScreenX}）
 *       ⇒ 横向位移也是精确的（前缀宽度用 {@link #wordStartForCompletion} 定，见 §5 的注）。</li>
 * </ul>
 * 于是"挪到板上"这件事变成：{@link #anchorFor} 给出目标（候选表的<b>左边缘 / 下边缘</b>），
 * 调用方拿它减去原版那两个数，得到一次<b>位姿平移</b> —— 见 {@link ShanhaiHoloCommandScreen}。
 * <p>🔴 兜底（任务书点名）：板投影不出来（四角有一个在近平面之后、板整块在屏幕外、窗口太小
 * 导致板上方放不下）⇒ {@link #anchorFor} 返回 {@code null} ⇒ 调用方<b>一个像素都不动</b>
 * （就是原版那个底部位置）⇒ <b>不许抛异常、也不许算出一个越界的矩形</b>。
 *
 * <h2>7. 🔴 本类里两条"给判据用"的约定（别把它们当业务逻辑）</h2>
 * <pre>
 *   · {@link #NO_BOX} / {@link #NO_ANCHOR} = {@code null} 的<b>具名哨兵</b>：
 *     源码里那一句"直接 {@code return} 一个 {@code null} 再带分号"<b>只允许出现一次</b>（在 {@code ghostSuffix} 里）——
 *     离线装置的 6 号突变体正是拿它当【唯一】定位点的，多写一处那个突变体就建不起来。
 *   · {@link #contains(int[], double, double)} 的矩形口径是 <b>{left, top, right, bottom}</b>
 *     （与 {@link #boxOfCorners} 的输出同一个口径），<b>不是</b>原版 {@code Rect2i} 的
 *     {@code {x, y, width, height}}；半开区间与 {@code Rect2i#contains} 逐位一致。
 * </pre>
 *
 * <h2>8. 🆕 ④ 滚轮翻页 —— 1.20.1 那个入口<b>不接坐标</b>（本轮最关键的一条事实）</h2>
 * <pre>
 *   🔴 本机真编译用的那个 jar（{@code forge-1.20.1-47.4.16_mapped_parchment_2023.09.03-1.20.1.jar}）
 *      用 {@code javap} 取到的真签名与真逻辑：
 *      CommandSuggestions#mouseScrolled(double)                  ← 【只有 delta，没有坐标】
 *      CommandSuggestions$SuggestionsList#mouseScrolled(double)  ← 坐标它自己去 MouseHandler 上读：
 *        mx = (int)( MouseHandler.xpos() * Window.getGuiScaledWidth()  / Window.getScreenWidth()  )
 *        my = (int)( MouseHandler.ypos() * Window.getGuiScaledHeight() / Window.getScreenHeight() )
 *        if (rect.contains(mx, my)) { offset = clamp((int)(offset − delta), 0, max(size − 10, 0)); return true; }
 *        return false;                                            ← 不在 rect 里就不吃
 *   （{@code MouseHandler#onScroll} 递给 {@code Screen.mouseScrolled(mouseX, mouseY, delta)} 的那两个实参，
 *     用的是<b>逐字相同</b>的式子 —— 所以"事件里的坐标"与"原版自己读的坐标"是同一个坐标系。）
 * </pre>
 * ⇒ 候选表被平移过 {@code (dx, dy)} 之后，"滚轮作用在【画出来的】表上"这件事，在原版那一侧看到的
 * 坐标是<b>没平移过</b>的那一格。于是本类负责三件事（纯逻辑、可离线判），判定本身一格都不搬过来：
 * <ol>
 *   <li>{@link #vanillaScaledMouse} = 上面那两句读坐标的转写（含 {@code d2i} 的<b>向零截断</b>）；</li>
 *   <li>{@link #rawMouseFor} = 它的<b>逆</b>（🔴 半格必须跟符号走 —— 恒定 {@code +0.5} 在负数上差一格，
 *       本机实测数字写在那条注释里）；</li>
 *   <li>{@link #wheelTarget} = "该把 {@code MouseHandler} 换成什么值"；
 *       {@code dx = dy = 0} ⇒ {@code null} = 不用换（真鼠标已经在同一个坐标系里）。</li>
 * </ol>
 * 🔴 <b>不在表上 / 表折叠了 / 到顶到底 / 吃不吃这一下</b>，全部仍然是原版说了算
 * （原版第一句就是 {@code suggestions == null ⇒ return false}，所以折叠时滚轮永远不会被吃掉）。
 * <p>那条翻页公式 {@link #wheelOffsetAfter} 属于 §2 表里"原版公式的逐条转写"那一族（<b>尺子</b>，
 * 不进生产路径）—— 生产路径把滚轮转发给原版，翻页一个字都不重写。
 */
public final class ShanhaiHoloTabCompleteCore {

    /** 命令的起始字符。原版 {@code CommandSuggestions#updateCommandInfo} 用的也是这一个。 */
    public static final char SLASH = '/';

    /** 一个字符的斜杠字符串（{@link #mirrorText} 补上去的那个）。 */
    private static final String SLASH_STR = "/";

    /** 候选表的行数上限 —— <b>必须与传给 {@code CommandSuggestions} 构造器的那个参数一致</b>。 */
    public static final int SUGGEST_LINE_LIMIT = 10;

    /** 原版候选表一行的高度（像素）。出处：{@code SuggestionsList} 的 {@code * 12} 全部那一族。 */
    public static final int SUGGEST_LINE_H = 12;

    /** 候选表的最大高度 = 行数上限 × 行高 = 120。<b>它只是"最坏情况"，不是实际高度</b>（读不到）。 */
    public static final int SUGGEST_MAX_H = SUGGEST_LINE_LIMIT * SUGGEST_LINE_H;

    /** 原版候选表<b>下边缘</b>离屏幕底的高度。出处见类注释 §3 ⑦（15 = 12 + 3）。 */
    public static final int VANILLA_BOTTOM_INSET = 15;

    /** 候选表下边缘与板上边缘之间留的缝（像素）。 */
    private static final int BOARD_GAP = 4;

    /** 横向夹紧时，候选表左边至少要给屏幕右边留出这么多像素（宽度读不到，只能按最坏情况留）。 */
    private static final int MIN_ROOM_W = 200;

    /** {@code null} 的具名哨兵（见类注释 §7；别退回成"直接 {@code return} 一个 {@code null}"那一句）。 */
    private static final double[] NO_BOX = null;

    /** {@code null} 的具名哨兵（见类注释 §7；别退回成"直接 {@code return} 一个 {@code null}"那一句）。 */
    private static final int[] NO_ANCHOR = null;

    /** {@code null} 的具名哨兵（见类注释 §7）：{@link #wheelTarget} 用它表示"不用换坐标"。 */
    private static final double[] NO_WHEEL_TARGET = null;

    private ShanhaiHoloTabCompleteCore() {}

    // ================================================================== ① 闸门（本项目自己的口径）

    /**
     * 🔴 <b>什么时候才允许补全</b> —— 生产路径真的会调的函数。
     *
     * <pre>
     *   以 '/' 开头        ⇒ true  （Tab 交给原版；候选表允许出现）
     *   空串 / null        ⇒ false （"空输入不补全"）
     *   不以 '/' 开头       ⇒ false （不许把按键递给原版 —— 否则原版会拿【玩家名】当候选）
     * </pre>
     * 判据刻意只看<b>第一个字符</b>：原版自己也是拿 {@code stringreader.peek() == '/'} 判的，
     * 多写一条"必须有两个字符以上"之类的规则就会与原版分叉（例如 {@code /} 单独一个字符时
     * 原版照样把全部指令列出来，那是正常的）。
     * <p>🆕 本轮之后：<b>面板原文始终先过 {@link #mirrorText} 再进镜子</b>，
     * 而镜子恒以 {@code /} 开头且非空 ⇒ 这道闸门恒为 true。这不是失效，而是口径收口：
     * "玩家名候选表不可能冒出来"这件事从"靠拦按键"变成了"镜子里根本没有那种输入"。
     */
    public static boolean tabEligible(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == SLASH;
    }

    // ================================================================== 🆕 ① 前导斜杠（"镜子"方案）

    /**
     * 🔴 <b>面板原文 ⇒ 镜子文本</b>：命令框省掉的那个前导 {@code /} 由这里补上。
     *
     * <pre>
     *   "sh"      ⇒ "/sh"
     *   "/sh"     ⇒ "/sh"      ← 本来就带 ⇒ 【绝不】变成 "//sh"
     *   ""        ⇒ "/"        ← 🔴 不是空串！空串会让原版走「玩家名候选」那条路
     *   null      ⇒ "/"
     * </pre>
     * <p>为什么必须"恒带一个斜杠"：原版 {@code updateCommandInfo} 拿
     * {@code stringreader.peek() == '/'} 决定走命令路还是玩家名路
     * （{@code flag1 = this.commandsOnly || flag}，而 {@code ChatScreen} 传的是 {@code false}）。
     */
    public static String mirrorText(String panelText) {
        if (panelText == null || panelText.isEmpty()) {
            return SLASH_STR;
        }
        return panelText.charAt(0) == SLASH ? panelText : SLASH_STR + panelText;
    }

    /**
     * 这一趟里，镜子上那个前导斜杠<b>是不是我们补的</b>（= 面板原文不以 {@code /} 开头）。
     * <p>它是 {@link #panelText} 的输入：只有"我们补的"那一个才该在写回面板时脱掉，
     * 用户自己敲的斜杠<b>不许吞</b>（见 {@link #panelText}）。
     */
    public static boolean mirrorAddedSlash(String panelText) {
        return panelText == null || panelText.isEmpty() || panelText.charAt(0) != SLASH;
    }

    /**
     * 🔴 <b>镜子文本 ⇒ 面板文本</b>：{@code weAdded} 为真时脱掉<b>一个</b>前导斜杠，否则原样返回。
     *
     * <pre>
     *   panelText("/sh", true)   ⇒ "sh"        （我们补的 ⇒ 脱掉）
     *   panelText("/sh", false)  ⇒ "/sh"       （他自己敲的 ⇒ 留着）
     *   panelText("/",   true)   ⇒ ""
     *   panelText(null,  true)   ⇒ ""
     * </pre>
     * ⚠️ 判据必须是"<b>这一个</b>斜杠是不是我们补的"，不能只看"镜子以 {@code /} 开头就脱"——
     * 那样用户自己敲的 {@code /} 会在第一次 Tab 之后凭空消失。
     */
    public static String panelText(String mirrorText, boolean weAdded) {
        final String t = mirrorText == null ? "" : mirrorText;
        if (weAdded && !t.isEmpty() && t.charAt(0) == SLASH) {
            return t.substring(1);
        }
        return t;
    }

    /**
     * 这一次补的是镜子里的哪一段 —— 那一段的<b>起点下标</b>（0 基）。
     *
     * <pre>
     *   "/shanhai menu dis" ⇒ 14（最后一个词，与 {@link #lastWordIndex} 同口径）
     *   "/sh"               ⇒ 1 （命令名那一段从斜杠【之后】开始）
     *   "/"                 ⇒ 1
     * </pre>
     * 🔴 命令名那一档必须取 1 而不是 0：原版 {@code updateCommandInfo} 里
     * {@code if (flag) stringreader.skip();} 把斜杠跳过去了，Brigadier 给的
     * {@code suggestion.getRange().getStart()} 因此是 <b>1</b>（不是 0）。
     * 这个数只用来把"原版候选表的左边缘"还原出来（{@code EditBox#getScreenX}），
     * 差一个宽度就会让候选表横着偏几像素。
     */
    public static int wordStartForCompletion(String mirrorText) {
        final String t = mirrorText == null ? "" : mirrorText;
        final int word = lastWordIndex(t);
        final boolean slash = !t.isEmpty() && t.charAt(0) == SLASH;
        final int cmd = slash ? 1 : 0;
        return Math.max(word, Math.min(cmd, t.length()));
    }

    // ================================================================== 🆕 ② 候选表锚在哪

    /** 原版候选表的<b>下边缘</b>纵坐标（= {@code screen.height - 15}，与候选条数无关）。 */
    public static int vanillaSuggestBottom(int screenH) {
        return screenH - VANILLA_BOTTOM_INSET;
    }

    /**
     * 一组投影点 ⇒ 轴对齐矩形 {@code {left, top, right, bottom}}（GUI 坐标）。
     *
     * <p>🔴 <b>只要有一个点不可用（NaN / Inf），整块就作废</b>（返回 {@link #NO_BOX}）——
     * 不许"拿剩下三个角凑一个矩形"：那个点可能正好在近平面之后，凑出来的矩形会大到离谱，
     * 候选表就会被摆到一个荒唐的位置。作废的后果是"回退到原版底部"，那是安全的一侧。
     * <p>⚠️ 空数组 / 两数组长度不一致 / {@code null} ⇒ 同样作废，<b>不抛异常</b>。
     */
    public static double[] boxOfCorners(double[] xs, double[] ys) {
        if (xs == null || ys == null || xs.length == 0 || xs.length != ys.length) {
            return NO_BOX;
        }
        double l = Double.POSITIVE_INFINITY;
        double t = Double.POSITIVE_INFINITY;
        double r = Double.NEGATIVE_INFINITY;
        double b = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < xs.length; i++) {
            if (!Double.isFinite(xs[i]) || !Double.isFinite(ys[i])) {
                return NO_BOX;
            }
            l = Math.min(l, xs[i]);
            r = Math.max(r, xs[i]);
            t = Math.min(t, ys[i]);
            b = Math.max(b, ys[i]);
        }
        return new double[]{l, t, r, b};
    }

    /**
     * 🔴 <b>候选表该锚在哪</b> —— 返回 {@code {左边缘, 下边缘}}（GUI 坐标），
     * 或者 {@link #NO_ANCHOR} 表示"没有锚 ⇒ 调用方一个像素都别动（保持原版底部位置）"。
     *
     * <p>规则（逐条都可离线判）：
     * <pre>
     *   ① 屏幕尺寸 ≤ 0、矩形 null / 长度不足 / 含 NaN·Inf、退化（宽或高 &lt; 1）
     *      、整块在可视区之外 ⇒ 没有锚
     *   ② 左边缘 = 板左边缘，夹到 [0, 屏宽 − {@link #MIN_ROOM_W}]
     *   ③ 下边缘 = 板上边缘 − {@link #BOARD_GAP}（候选表从下边缘往上长）
     *      再夹到不超过 {@link #vanillaSuggestBottom}(屏高)
     *   ④ 板上方放不下最坏情况的高度（{@code 下边缘 − listMaxH &lt; 0}）⇒ 没有锚
     * </pre>
     * ⚠️ 第 ④ 条是"窗口太小 / 板太靠上"的唯一出口 —— 它的后果是回退到底部，
     * 而<b>不是</b>硬把候选表顶到屏幕外。
     *
     * @param boardRect 板投影到屏幕上的矩形 {@code {left, top, right, bottom}}；没有 ⇒ {@code null}
     * @param listMaxH  候选表<b>最坏情况</b>的高度（{@link #SUGGEST_MAX_H}）
     */
    public static int[] anchorFor(int screenW, int screenH, double[] boardRect, int listMaxH) {
        if (screenW <= 0 || screenH <= 0) {
            return NO_ANCHOR;
        }
        final double[] r = boardRect;
        if (r == null || r.length < 4) {
            return NO_ANCHOR;
        }
        for (int i = 0; i < 4; i++) {
            if (!Double.isFinite(r[i])) {
                return NO_ANCHOR;
            }
        }
        final double bl = r[0];
        final double bt = r[1];
        final double br = r[2];
        final double bb = r[3];
        if (br - bl < 1.0 || bb - bt < 1.0) {
            return NO_ANCHOR;
        }
        if (br <= 0.0 || bl >= screenW || bb <= 0.0 || bt >= screenH) {
            return NO_ANCHOR;
        }
        final int h = Math.max(0, listMaxH);
        final double room = Math.min(bl, (double) screenW - MIN_ROOM_W);
        final int left = (int) Math.round(Math.max(0.0, room));
        int bottom = (int) Math.round(bt) - BOARD_GAP;
        final int lowest = vanillaSuggestBottom(screenH);
        if (bottom > lowest) {
            bottom = lowest;
        }
        if (bottom - h < 0) {
            return NO_ANCHOR;
        }
        return new int[]{left, bottom};
    }

    // ================================================================== 🆕 ③ 鼠标命中

    /**
     * <b>屏幕坐标 ⇒ 候选表自己的坐标系</b>（= 原版那个 {@code rect} 所在的坐标系）。
     *
     * <p>绘制时整张表被平移了 {@code (dx, dy)}，所以命中判定必须把鼠标点<b>反向</b>平移同样的量。
     * 绘制与命中共用这一对偏移，就不会出现"看到的位置"和"点得到的位置"各漂一套
     * —— 这正是本工程记过的几何 bug 高频形态。
     * <p>⚠️ {@code dx = dy = 0}（回退到底部那一档）时它就是恒等映射 ⇒ 与改动前逐位相同。
     */
    public static double[] toListSpace(double mouseX, double mouseY, int dx, int dy) {
        return new double[]{mouseX - dx, mouseY - dy};
    }

    /**
     * 一个点是不是落在矩形里 —— 口径与原版 {@code Rect2i#contains} <b>逐位一致</b>：
     * <pre>
     *   x &gt;= left &amp;&amp; x &lt; right &amp;&amp; y &gt;= top &amp;&amp; y &lt; bottom
     * </pre>
     * 🔴 先做 {@code (int)} 截断再比 —— 原版 {@code SuggestionsList#mouseClicked} 就是
     * {@code (int) pMouseX} 之后才调 {@code rect.contains(...)} 的；不截断的话
     * 负坐标（鼠标在屏幕外）上会与原版差一格。
     * <p>⚠️ 矩形口径是 <b>{left, top, right, bottom}</b>（见类注释 §7），不是 {@code {x,y,w,h}}。
     * 退化矩形（宽或高 ≤ 0）恒为 false，{@code null} / 长度不足也恒为 false（不抛异常）。
     */
    public static boolean contains(int[] rect, double x, double y) {
        if (rect == null || rect.length < 4) {
            return false;
        }
        final int w = rect[2] - rect[0];
        final int h = rect[3] - rect[1];
        if (w <= 0 || h <= 0) {
            return false;
        }
        final int ix = (int) x;
        final int iy = (int) y;
        return ix >= rect[0] && ix < rect[2] && iy >= rect[1] && iy < rect[3];
    }

    // ================================================================== 🆕 ④ 滚轮（把鼠标坐标摆到表那一格）

    /**
     * 原版读鼠标原始坐标 → GUI 坐标的那一句转写：
     * <pre>
     *   (int) ( raw * guiPx / screenPx )
     * </pre>
     * 🔴 <b>出处（{@code javap -c} 原文，两处是同一句 —— 这正是本方案成立的依据）</b>：
     * <pre>
     *   · {@code CommandSuggestions$SuggestionsList#mouseScrolled(double)} 前 45 条指令：
     *     {@code MouseHandler.xpos()} → {@code Window.getGuiScaledWidth()} → 乘 →
     *     {@code Window.getScreenWidth()} → 除 → {@code d2i}；
     *   · {@code MouseHandler#onScroll} 里算 {@code Screen.mouseScrolled(mouseX, mouseY, delta)}
     *     那两个实参时用的是<b>逐字相同</b>的式子（{@code xpos → getGuiScaledWidth → getScreenWidth}）。
     * </pre>
     * ⇒ 「事件里给的坐标」与「原版自己去读的那个坐标」是同一个式子算出来的 ⇒ 把 raw 换成别的值，
     * 原版表内那一判就会看到我们想要的那一格（见 {@link #wheelTarget}）。
     * <p>⚠️ 三件容易搞错的事：
     * <ol>
     *   <li>{@code screenPx} 是 {@code Window.getScreenWidth()}（窗口逻辑尺寸），<b>不是</b>
     *       {@code Window.getWidth()} —— 后者返回的是 {@code framebufferWidth}，开了显示缩放或高分屏时
     *       两者<b>不相等</b>（传错就是系统性地差一个比例，而且在本机大多数设置下看不出来）；</li>
     *   <li>{@code d2i} 是<b>向零截断</b>，既不是四舍五入也不是 floor（负坐标上三者各不相同）；</li>
     *   <li>{@code guiPx ≤ 0} / {@code screenPx ≤ 0} ⇒ 原版会算出 {@code NaN} 或 {@code ±Inf}，
     *       {@code (int)} 会饱和（{@code 0} / {@code ±Integer.MAX_VALUE}）而<b>不抛异常</b>；
     *       本实现照抄 {@code (int)} 的饱和行为，同样不抛。</li>
     * </ol>
     */
    public static int vanillaScaledMouse(double raw, int screenPx, int guiPx) {
        return (int) (raw * (double) guiPx / (double) screenPx);
    }

    /**
     * {@link #vanillaScaledMouse} 的<b>逆</b>：给一个 GUI 坐标，求一个原始坐标，使得原版读出来
     * <b>正好</b>是 {@code (int) gui}（原版读到的是整数，命中判定也是整数）。
     *
     * <p>🔴 <b>半格必须跟符号走</b>（{@code target ≥ 0 ⇒ +0.5}，{@code target &lt; 0 ⇒ −0.5}）：
     * {@code (int)} 是<b>向零截断</b>，所以整数 {@code g} 的原像区间在两侧不一样 ——
     * {@code g ≥ 0} 时是 {@code [g, g+1)}（中点 {@code g+0.5}），
     * {@code g &lt; 0} 时是 {@code (g−1, g]}（中点 {@code g−0.5}）。
     * <p>🔴 两条都是<b>本机实测</b>出来的（不是推理）：同一组尺寸 {@code screenPx = 2560, guiPx = 854}
     * （2560 宽的窗口、GUI 缩放 3 档 ⇒ {@code guiScaledWidth = 854}）——
     * <pre>
     *   · 恒定 {@code +0.5}：{@code gui = −7} 被读成 {@code −6}、{@code gui = −3} 被读成 {@code −2}（差一格）；
     *   · 完全不加半格：{@code gui = 3} 被读成 {@code 2}、{@code gui = 6} 被读成 {@code 5}
     *     （浮点往返落在 {@code g} 左边一丝丝，截断就掉一格）。
     * </pre>
     * 这一组数字就是 {@code selfTest} 里 N19 / N20 两条负对照的来源（它们钉住"这两条错法真的会错"）。
     * <p>退化尺寸（{@code ≤ 0}）⇒ <b>原样返回</b> {@code gui}：后果最多是这一拍换了个没用的坐标
     * ⇒ 原版判不中 ⇒ 不吃滚轮 —— 是安全的那一侧。
     */
    public static double rawMouseFor(double gui, int screenPx, int guiPx) {
        final int target = (int) gui;
        if (screenPx <= 0 || guiPx <= 0) {
            return gui;
        }
        final double half = target >= 0 ? 0.5 : -0.5;
        return (target + half) * (double) screenPx / (double) guiPx;
    }

    /**
     * 🔴 <b>滚轮这一拍要把 {@code MouseHandler} 上的坐标临时换成什么</b> ——
     * 也就是"把鼠标摆到候选表自己的坐标系里的那一格"。
     *
     * <pre>
     *   null          ⇒ 不用换（{@code dx = dy = 0}：表没被平移，真鼠标已经在同一个坐标系里）
     *   {rawX, rawY}  ⇒ 换坐标的目标值（调用方写完<b>必须还原</b>）
     * </pre>
     * 🔴 <b>为什么是"换坐标"而不是"传坐标"</b>：1.20.1 那个入口是
     * {@code CommandSuggestions#mouseScrolled(double delta)} —— <b>它不接坐标</b>，坐标由它自己从
     * {@code MouseHandler} 上读（{@code javap} 取证见 {@link #vanillaScaledMouse}）。
     * 所以"把鼠标坐标反向平移之后再交给原版"这件事，在 1.20.1 上只有这一条路。
     * <p>⚠️ <b>判定仍然是原版那一条</b>：在不在表上（原版那张私有 {@code rect}）、表折叠了没、
     * 到顶/到底怎么夹、"吃不吃这一下"，本函数一概不管、不重算（本类不自己算一遍矩形，见类注释 §7）。
     */
    public static double[] wheelTarget(double mouseX, double mouseY, int dx, int dy,
                                       int screenW, int screenH, int guiW, int guiH) {
        if (dx == 0 && dy == 0) {
            return NO_WHEEL_TARGET;
        }
        final double[] p = toListSpace(mouseX, mouseY, dx, dy);
        return new double[]{rawMouseFor(p[0], screenW, guiW), rawMouseFor(p[1], screenH, guiH)};
    }

    // ================================================================== ② 原版公式的转写

    /**
     * 把 {@code [start, end)} 这一段换成 {@code candidate}（其余原样保留）。
     * <p>出处：Brigadier 1.1.8 {@code Suggestion#apply(String)}（javap -c 核过，逐条对应）。
     * <p>⚠️ 越界一律钳住，不许抛 —— 原版这两支都带边界判断（{@code start > 0} / {@code end < length}）。
     * 这里不是"多写一层保险"：{@code substring} 越界会直接抛 {@code StringIndexOutOfBoundsException}，
     * 而这条路上任何一次抛出都会把游戏打崩（{@code keyPressed} 里没人接异常）。
     */
    public static String applyCompletion(String input, int start, int end, String candidate) {
        final String in = input == null ? "" : input;
        final String cand = candidate == null ? "" : candidate;
        final int s = Math.max(0, Math.min(Math.min(start, end), in.length()));
        final int e = Math.max(s, Math.min(Math.max(start, end), in.length()));
        if (s == 0 && e == in.length()) {
            return cand;                                    // 原版第 1 支：整条就是候选
        }
        final StringBuilder sb = new StringBuilder();
        if (s > 0) {
            sb.append(in, 0, s);                            // 原版第 2 支：保留前半段
        }
        sb.append(cand);
        if (e < in.length()) {
            sb.append(in.substring(e));                     // 原版第 3 支：保留后半段
        }
        return sb.toString();
    }

    /**
     * 用一张候选表补全（= 原版 {@code SuggestionsList#useSuggestion()} 的效果）。
     *
     * <p>🔴 <b>候选表为空 ⇒ 原样返回 {@code input}，一个字都不动</b>（这是任务书点名的负对照）。
     * 原版也是这个结果：没有候选就没有 {@code suggestions} 对象，Tab 只会让
     * {@code keyPressed} 返回 false，文本框不变。
     *
     * @param index 选中第几个（会被 {@link #cycleIndex} 折进 {@code [0, size)}）
     */
    public static String completeWith(String input, int start, int end,
                                      List<String> candidates, int index) {
        if (candidates == null || candidates.isEmpty()) {
            return input == null ? "" : input;
        }
        return applyCompletion(input, start, end,
                candidates.get(cycleIndex(index, 0, candidates.size())));
    }

    /**
     * 原版那张「灰字尾巴」（第一次 Tab 时输入框里显示的补全预览）。
     * <p>出处：{@code CommandSuggestions#calculateSuggestionSuffix}。
     * <p>⚠️ 候选不以输入开头时原版返回的是 {@code null}（不是空串）—— 这里照抄，
     * 两者在 {@code EditBox.setSuggestion} 那里含义不同。
     * <p>🔴 这一句是本类里<b>唯一</b>一处直接返回 {@code null} 的返回语句
     * （离线装置的第 6 个突变体拿它当唯一定位点，见类注释 §7）。
     */
    public static String ghostSuffix(String input, String candidate) {
        final String in = input == null ? "" : input;
        if (candidate == null || !candidate.startsWith(in)) {
            return null;
        }
        return candidate.substring(in.length());
    }

    /**
     * 候选下标循环走 {@code delta} 步（首尾闭合；{@code delta} 为负 = Shift+Tab 反向）。
     *
     * <p>出处：{@code CommandSuggestions.SuggestionsList#select(int)} —— 越界只补/减<b>一次</b>。
     * 原版每次只走 ±1 步，所以"补一次"与"取模"结果相同；这里写成取模是为了对任意步长都成立
     * （{@code size == 1} 时原地不动、{@code size == 0} 时返回 0 而不是抛）。
     * <p>⚠️ {@code selfTest} 里有一条判据逐位比对"取模"与"原版单次回绕"在 {@code ±1} 上的一致，
     * 免得这条转写偷偷漂掉。
     */
    public static int cycleIndex(int current, int delta, int size) {
        if (size <= 0) {
            return 0;
        }
        final int m = (current + delta) % size;
        return m < 0 ? m + size : m;
    }

    /**
     * 最后一段空白之<b>后</b>的下标（0 基）；没有空白就是 0。
     * <p>出处：{@code CommandSuggestions#getLastWordIndex}（逐字符版：
     * {@code for (matcher = WHITESPACE.matcher(s); matcher.find(); i = matcher.end()); return i;}）。
     * <p>它决定"这一次 Tab 补的是哪一个词"，所以 {@code ""} 与 {@code "  abc"} 的答案不一样，
     * 负对照里两条都钉住了。{@link #wordStartForCompletion} 在它之上只多一条"命令名从 1 起"。
     */
    public static int lastWordIndex(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int i = 0;
        for (int k = 0; k < text.length(); k++) {
            if (Character.isWhitespace(text.charAt(k))) {
                i = k + 1;
            }
        }
        return i;
    }

    /**
     * 原版排序口径：这个候选要不要<b>排到前面</b>。
     * <p>出处：{@code CommandSuggestions#sortSuggestions} —— 只有<b>用户敲的那一段</b>被
     * {@code toLowerCase(Locale.ROOT)}，<b>候选本身不折大小写</b>；
     * 另外兼容带 {@code minecraft:} 前缀的写法。
     * <p>🔴 这是一条<b>排序</b>口径（命中的排前面），<b>不是过滤</b>：没命中的候选只是被挪到后面，
     * 仍然在候选表里。别把它当成"匹配得上才补全"。
     */
    public static boolean prefixHitForRanking(String candidate, String typed) {
        if (candidate == null || typed == null) {
            return false;
        }
        final String t = typed.toLowerCase(Locale.ROOT);
        return candidate.startsWith(t) || candidate.startsWith("minecraft:" + t);
    }

    /**
     * 一张候选表的公共前缀（空表 ⇒ 空串，一个字都不炸）。
     * <p>⚠️ <b>原版补的【不是】公共前缀</b>，而是候选表里排第一的那一个（见 {@link #prefixHitForRanking}）。
     * 这个函数留着是因为任务书点名要"公共前缀补全"这条口径 —— 留在这里，
     * 「公共前缀是什么」就有唯一的落点，离线判据也能直接盯住它，
     * 而不是让这条要求散在报告的散文里（见报告的「与任务书的偏离」一节）。
     */
    public static String commonPrefix(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        String p = items.get(0) == null ? "" : items.get(0);
        for (int i = 1; i < items.size(); i++) {
            final String s = items.get(i) == null ? "" : items.get(i);
            int k = 0;
            final int n = Math.min(p.length(), s.length());
            while (k < n && p.charAt(k) == s.charAt(k)) {
                k++;
            }
            p = p.substring(0, k);
            if (p.isEmpty()) {
                break;
            }
        }
        return p;
    }

    /**
     * 🔴 <b>原版滚轮翻页公式的镜像</b>（1.20.1 {@code CommandSuggestions$SuggestionsList#mouseScrolled(double)}
     * 的后半段）：
     * <pre>
     *   offset' = Mth.clamp( (int) (offset − delta), 0, Math.max(size − lineLimit, 0) )
     * </pre>
     * 出处：{@code javap -c} 的第 104..142 条指令（{@code i2d / dsub / d2i} →
     * {@code Math.max(size − suggestionLineLimit, 0)} → {@code Mth.clamp} → {@code putfield offset} →
     * {@code iconst_1 / ireturn}）；{@code Mth.clamp(int,int,int)} 本机核过 =
     * {@code Math.min(max, Math.max(min, value))}（{@code max < min} 时也不抛）。
     *
     * <p>⚠️ <b>它不在生产路径上</b>（§2 那张表里"原版公式的逐条转写"那一族）：翻页由原版自己做，
     * 我们只把滚轮转发过去（见 {@link #wheelTarget}）。它在这里的理由只有两条：
     * ① 把"原版到底怎么翻页"变成一组<b>能离线判</b>的读数（推进 / 回退 / 到顶 / 到底 / 不足一页不动 /
     * {@code size = 0} 不炸）；② 万一将来有人改成"自己算 offset"，这些判据立刻指出语义漂了没有。
     *
     * <p>🔴 三条语义（都与原版逐位一致，别"顺手改好"）：
     * <ol>
     *   <li><b>滚轮向上 = {@code delta > 0} = 往列表【上】走</b>（{@code offset − delta}）—— 与原版同号；</li>
     *   <li><b>不足一页 ⇒ 一动不动</b>：上界是 {@code max(size − lineLimit, 0)}，{@code size ≤ lineLimit} 时恒为 0；</li>
     *   <li>🔴 <b>到顶 / 到底【照样返回 true】</b>（原版先夹再 {@code iconst_1}）⇒ 到顶时滚轮<b>仍然被原版吃掉</b>、
     *       列表一动不动 —— 这与原版聊天栏逐位一致，是"判据 = 原版返回 true 才吞"的直接后果
     *       （任务书里那句"到顶/到底返回 false"与 1.20.1 的字节码不符，见报告「与任务书的偏离」）。</li>
     * </ol>
     * <p>{@code delta} 是<b>已经过</b> {@code CommandSuggestions#mouseScrolled} 那句
     * {@code Mth.clamp(delta, −1, 1)} 之后的值 —— 夹紧发生在原版那一层，我们转发的是没夹过的原始 delta。
     */
    public static int wheelOffsetAfter(int offset, double delta, int size, int lineLimit) {
        final int upper = Math.max(size - lineLimit, 0);
        final int moved = (int) ((double) offset - delta);
        return Math.min(upper, Math.max(0, moved));
    }

    // ================================================================== 自检

    /** 自检结果（与 {@link ShanhaiHoloMenuPanel.Report} 同一口径）。 */
    public record Report(int pass, int total, String bad) {
        public boolean ok() {
            return bad == null;
        }
    }

    /**
     * 离线自检（<b>不需要 Minecraft，也不需要游戏</b>）：上表那些函数的判据。
     *
     * <p>判据分十组：
     * <pre>
     *   组 0    闸门（以 '/' 开头才允许补全；含 N1..N3 负对照）
     *   组 ①    输入 "/sh" + Tab ⇒ 补成候选（"/shanhai"）
     *   组 ②    多个候选时连续 Tab 循环、Shift+Tab 反向、首尾闭合
     *   组 ③    负对照：不以 '/' 开头 ⇒ 不补全；候选为空 ⇒ 不炸、原样返回
     *   组 ④    大小写：匹配不敏感，但补进去的是候选自己的规范大小写
     *   组 ⑤    灰字尾巴（null 与空串是两件事）
     *   组 ⑥    分词（决定这一次补的是哪一个词）
     *   组 ⑦    公共前缀
     *   🆕 组 ⑧ ① 前导斜杠（R2A*）：面板 'sh' ⇒ 镜子 '/sh'；取回写面板 'shanhai'；
     *           面板本来就带 '/' ⇒ 不许变 '//sh'、也不许被吞
     *   🆕 组 ⑨ ② 候选表锚在板上（R2B*）：给定板矩形 ⇒ 贴在其上方且夹在屏内；
     *           板在背后/为空/窗口太小 ⇒ 没有锚（回退到底部）且不抛异常
     *   🆕 组 ⑩ ③ 鼠标命中（R2C*）：点在（平移后的）表内 ⇒ 命中；点在外面 ⇒ 不命中
     *   🆕 组 ⑪ ④ 滚轮（R3A / R3B / R3C 那一族）：原版读坐标那一句的转写与它的逆（含"半格跟符号走"）、
     *           "要不要换坐标 / 换成什么"、原版翻页公式的边界（推进、回退、到顶、到底、
     *           不足一页不动、{@code size = 0} 不炸）与"到顶到底照样返回 true"这条语义
     * </pre>
     * 另有 22 条独立的 <b>N-负对照</b>（名字里带 {@code N}），专门抓"判据自己恒绿"这件事。
     */
    public static Report selfTest() {
        final StringBuilder bad = new StringBuilder();
        final int[] n = {0, 0};

        // ---- 组 0：闸门（本项目自己的口径；生产路径真的走这一条）----
        c(bad, n, "闸门：'/sh' 允许补全", tabEligible("/sh"), true);
        c(bad, n, "闸门：'/' 单独一个字符也允许补全（原版 peek()=='/' 同口径）", tabEligible("/"), true);
        c(bad, n, "闸门：空串不补全（\"空输入不补全\"）", tabEligible(""), false);
        c(bad, n, "N1 负对照：不以 '/' 开头 ⇒ 不补全（'sh'）", tabEligible("sh"), false);
        c(bad, n, "N2 负对照：前导空格也不算（' /sh'）", tabEligible(" /sh"), false);
        c(bad, n, "N3 负对照：中文输入不算（'命令'）", tabEligible("命令"), false);
        c(bad, n, "闸门：null 不炸且不补全", tabEligible(null), false);
        // 正对照：证明上面那三条"false"不是因为 tabEligible 恒 false
        c(bad, n, "N1/N2/N3 的正对照：同一条函数对 '/sh' 必须为 true（否则那三条恒绿）",
                tabEligible("/sh") && !tabEligible("sh"), true);
        c(bad, n, "N1/N2/N3 的正对照：'/'+内容 与 无 '/' 必须给出【不同】答案",
                tabEligible("/sh") == tabEligible("sh"), false);

        // ---- 组 ①：按一下 Tab 补出什么 ----
        c(bad, n, "① '/sh' + Tab ⇒ '/shanhai'（单候选）",
                applyCompletion("/sh", 1, 3, "shanhai"), "/shanhai");
        c(bad, n, "① '/sh' + Tab ⇒ '/shanhai'（走候选表那条路）",
                completeWith("/sh", 1, 3, List.of("shanhai"), 0), "/shanhai");
        c(bad, n, "① 候选表有三条、选中第 0 条 ⇒ 补成第 0 条",
                completeWith("/sh", 1, 3, List.of("shanhai", "shrug", "shop"), 0), "/shanhai");
        c(bad, n, "① 补全【只动被替换的那一段】：'/shanhai menu dis' ⇒ '/shanhai menu distance'"
                        + "（dis 在 [14,17)：14 = 8+1+4+1）",
                applyCompletion("/shanhai menu dis", 14, 17, "distance"), "/shanhai menu distance");
        c(bad, n, "① 分词与上面那条[14,17)对得上：lastWordIndex('/shanhai menu dis') == 14",
                lastWordIndex("/shanhai menu dis"), 14);
        c(bad, n, "① 补全【只动被替换的那一段】：'/give @s st' ⇒ '/give @s stone'（st 在 [9,11)）",
                applyCompletion("/give @s st", 9, 11, "stone"), "/give @s stone");
        c(bad, n, "① 整条都是候选段时，前缀一个字都不留（Brigadier apply 第 1 支）",
                applyCompletion("/sh", 0, 3, "shanhai"), "shanhai");
        // 补全点在【中间】时后半段必须原样保留（Brigadier apply 的第 3 支）
        c(bad, n, "[KEEP-TAIL] 补全点在中间：'/give @s st one' ⇒ '/give @s stone one'（后半段 ' one' 原样留着）",
                applyCompletion("/give @s st one", 9, 11, "stone"), "/give @s stone one");
        c(bad, n, "[KEEP-TAIL] 同上，长度必须正好是 原长−2+5（后半段一个字符都不许丢）",
                applyCompletion("/give @s st one", 9, 11, "stone").length(),
                "/give @s st one".length() - 2 + 5);
        c(bad, n, "N4 负对照：补全【不许】把整条命令换成候选（前后文必须留着）",
                applyCompletion("/give @s st", 9, 11, "stone").equals("stone"), false);
        c(bad, n, "N4 负对照：补全【不许】只把尾巴接上而丢掉开头斜杠",
                applyCompletion("/sh", 1, 3, "shanhai").startsWith("/"), true);
        c(bad, n, "越界不炸：start/end 越过字符串末尾一律钳住（不许抛 StringIndexOutOfBounds）",
                applyCompletion("/sh", 99, 120, "shanhai"), "/shshanhai");
        // ⚠️ end < start 在原版【不可能出现】（Brigadier 的 end 恒等于 input.length()）。
        //    这一条钉的是【本实现的归一化】（先取 min/max 把区间摆正），不是原版语义承诺。
        c(bad, n, "越界不炸：end < start（原版不会出现）按本实现的归一化处理 ⇒ '/sXhai'",
                applyCompletion("/shanhai", 5, 2, "X"), "/sXhai");

        // ---- 组 ②：循环 / 反向 / 首尾闭合 ----
        c(bad, n, "② 3 个候选：从 0 走 +1 ⇒ 1", cycleIndex(0, 1, 3), 1);
        c(bad, n, "② 3 个候选：从 2 走 +1 ⇒ 0（首尾闭合）", cycleIndex(2, 1, 3), 0);
        c(bad, n, "② 3 个候选：从 0 走 −1 ⇒ 2（Shift+Tab 反向，首尾闭合）", cycleIndex(0, -1, 3), 2);
        c(bad, n, "② 3 个候选：从 1 走 −1 ⇒ 0", cycleIndex(1, -1, 3), 0);
        c(bad, n, "② 单候选：怎么走都在原地", cycleIndex(0, 1, 1), 0);
        c(bad, n, "② 走满一圈回到起点（+1 × 3）", cycleIndex(cycleIndex(cycleIndex(0, 1, 3), 1, 3), 1, 3), 0);
        c(bad, n, "② 反向走满一圈回到起点（−1 × 3）",
                cycleIndex(cycleIndex(cycleIndex(0, -1, 3), -1, 3), -1, 3), 0);
        c(bad, n, "② 候选表为空也不炸（返回 0）", cycleIndex(0, 1, 0), 0);
        // 与原版 select(int) 的"越界只补/减一次"逐位比对（size 1..6 × 每个 current × ±1）
        final StringBuilder wrapMismatch = new StringBuilder();
        for (int size = 1; size <= 6; size++) {
            for (int cur = 0; cur < size; cur++) {
                for (int d = -1; d <= 1; d += 2) {
                    int vanilla = cur + d;                              // SuggestionsList#select
                    if (vanilla < 0) {
                        vanilla += size;
                    }
                    if (vanilla >= size) {
                        vanilla -= size;
                    }
                    if (cycleIndex(cur, d, size) != vanilla) {
                        wrapMismatch.append("size=").append(size).append(" cur=").append(cur)
                                .append(" d=").append(d).append(" got=").append(cycleIndex(cur, d, size))
                                .append(" want=").append(vanilla).append("; ");
                    }
                }
            }
        }
        c(bad, n, "② 与原版 select(int) 单步口径逐位相同（size 1..6 × 每个 current × ±1）",
                wrapMismatch.toString(), "");
        // N5 负对照：不回绕的实现必须被抓到
        final StringBuilder noWrap = new StringBuilder();
        for (int size = 1; size <= 6; size++) {
            for (int cur = 0; cur < size; cur++) {
                final int nw = cur + 1;
                if (nw != cycleIndex(cur, 1, size)) {
                    noWrap.append(size).append('/').append(cur).append(' ');
                }
            }
        }
        c(bad, n, "N5 负对照：'不回绕'的实现在 size≥2 时【必须】与真实现不同（否则判据没牙）",
                noWrap.length() > 0, true);
        c(bad, n, "N5 负对照：回绕的结果恒在 [0, size) 内（不许出现 size 或 −1）",
                cycleIndex(5, 7, 3) >= 0 && cycleIndex(5, 7, 3) < 3, true);

        // ---- 组 ③：负对照（不补全 / 候选空）----
        c(bad, n, "③ N6 负对照：候选表为空 ⇒ 原样返回，一个字都不动",
                completeWith("sh", 0, 2, List.of(), 0), "sh");
        c(bad, n, "③ N6 负对照：候选表为 null ⇒ 同样原样返回，不炸",
                completeWith("/sh", 1, 3, null, 0), "/sh");
        c(bad, n, "③ N6 负对照：候选表为空时【不许】把输入变成空串",
                completeWith("/sh", 1, 3, List.of(), 0).isEmpty(), false);
        c(bad, n, "③ 非 '/' 输入：闸门为 false ⇒ 上层根本不会调补全（这里只能验闸门）",
                tabEligible("sh") || tabEligible(""), false);
        // 正对照：证明"原样返回"不是因为 completeWith 恒返回输入
        c(bad, n, "③ 正对照：同一函数、候选非空 ⇒ 必须真的改动文本（否则 N6 恒绿）",
                completeWith("/sh", 1, 3, List.of("shanhai"), 0).equals("/sh"), false);

        // ---- 组 ④：大小写 ----
        c(bad, n, "④ 匹配不敏感：用户敲 'SH' 也能命中候选 'shanhai'（原版把用户那一段小写化）",
                prefixHitForRanking("shanhai", "SH"), true);
        c(bad, n, "④ 匹配不敏感：'sh' 命中 'shanhai'", prefixHitForRanking("shanhai", "sh"), true);
        c(bad, n, "④ 带 minecraft: 前缀的写法也算命中",
                prefixHitForRanking("minecraft:give", "give"), true);
        c(bad, n, "N7 负对照（原版原样的怪癖）：'Shanhai' 这种【候选带大写】的写法不算命中"
                        + "（原版只小写用户敲的那一段，候选不动）",
                prefixHitForRanking("Shanhai", "sh"), false);
        c(bad, n, "④ 补进去的是【候选自己的】规范大小写：'/SH' ⇒ '/shanhai'",
                applyCompletion("/SH", 1, 3, "shanhai"), "/shanhai");
        c(bad, n, "N8 负对照：补完【不许】还留着用户敲的大写（不是 '/SHanhai'）",
                applyCompletion("/SH", 1, 3, "shanhai").equals("/SHanhai"), false);
        c(bad, n, "④ 大小写对补全【长度】没影响（'/Sh' 与 '/sh' 补出同一条）",
                applyCompletion("/Sh", 1, 3, "shanhai").equals(applyCompletion("/sh", 1, 3, "shanhai")), true);

        // ---- 组 ⑤：灰字尾巴（原版第一次 Tab 的那段预览）----
        c(bad, n, "灰字：ghostSuffix('/sh', '/shanhai') == 'anhai'",
                ghostSuffix("/sh", "/shanhai"), "anhai");
        c(bad, n, "灰字：已经完全相等时尾巴是空串（不是 null）",
                ghostSuffix("/shanhai", "/shanhai"), "");
        c(bad, n, "N9 负对照：候选不以输入开头 ⇒ 原版返回 null（不是空串）",
                ghostSuffix("/sh", "/give"), null);
        c(bad, n, "N9 负对照：'null 与 空串'必须是两件事（原版 EditBox.setSuggestion 靠这个区分）",
                ghostSuffix("/sh", "/give") == "", false);

        // ---- 组 ⑥：分词（决定这一次补的是哪一个词）----
        c(bad, n, "分词：'' ⇒ 0", lastWordIndex(""), 0);
        c(bad, n, "分词：'/sh' ⇒ 0（整条就是一个词）", lastWordIndex("/sh"), 0);
        c(bad, n, "分词：'/shanhai menu' ⇒ 9（补的是 menu 这个位置）", lastWordIndex("/shanhai menu"), 9);
        c(bad, n, "分词：'/shanhai  menu ' ⇒ 15（末尾空白之后）", lastWordIndex("/shanhai  menu "), 15);
        c(bad, n, "分词：前导空白 '  /sh' ⇒ 2", lastWordIndex("  /sh"), 2);
        c(bad, n, "分词：null ⇒ 0，不炸", lastWordIndex(null), 0);
        c(bad, n, "N10 负对照：分词结果不许是负数", lastWordIndex("  ") < 0, false);

        // ---- 组 ⑦：公共前缀（任务书点名的口径）----
        c(bad, n, "公共前缀：{'/shanhai','/shanghai'} ⇒ '/shan'", commonPrefix(List.of("/shanhai", "/shanghai")), "/shan");
        c(bad, n, "公共前缀：单条 ⇒ 它自己", commonPrefix(List.of("/shanhai")), "/shanhai");
        c(bad, n, "公共前缀：空表 ⇒ 空串，不炸", commonPrefix(List.of()), "");
        c(bad, n, "公共前缀：null ⇒ 空串，不炸", commonPrefix(null), "");
        c(bad, n, "公共前缀：两条只共享开头那个斜杠 ⇒ '/'（不是空串）", commonPrefix(List.of("/give", "/time")), "/");
        c(bad, n, "N11 负对照：公共前缀【不许】长过最短的那一条",
                commonPrefix(List.of("/sha", "/shanhai")).length() <= "/sha".length(), true);

        // ================================================================ 🆕 组 ⑧：① 前导斜杠（镜子）
        c(bad, n, "R2A1 面板 'sh' ⇒ 镜子 '/sh'（命令框省掉的那个斜杠由镜像补上，用户不用敲）",
                mirrorText("sh"), "/sh");
        c(bad, n, "R2A1 面板 's' ⇒ 镜子 '/s'（一个字也要补）", mirrorText("s"), "/s");
        c(bad, n, "R2A1 面板 '' ⇒ 镜子 '/'（🔴 绝不是空串：空串会让原版走【玩家名候选】那条路）",
                mirrorText(""), "/");
        c(bad, n, "R2A1 面板 null ⇒ 镜子 '/'，不炸", mirrorText(null), "/");
        c(bad, n, "R2A3 面板本来就带 '/'（'/sh'）⇒ 镜子【不许】变成 '//sh'",
                mirrorText("/sh"), "/sh");
        c(bad, n, "R2A3 面板只有一个 '/' ⇒ 镜子还是 '/'（不许 '//'）", mirrorText("/"), "/");
        c(bad, n, "R2A3-N12 负对照：镜子里 '//' 一次都不许出现（扫描 6 种面板原文）",
                noDoubleSlash(), true);
        c(bad, n, "R2A2 镜子 '/sh' 取回面板 ⇒ 'sh'（我们自己补的那个斜杠要脱掉）",
                panelText("/sh", true), "sh");
        c(bad, n, "R2A2 镜子 '/'（空命令）取回面板 ⇒ ''", panelText("/", true), "");
        c(bad, n, "R2A2 镜子 null / '' 取回面板 ⇒ ''，不炸",
                panelText(null, true) + panelText("", true), "");
        c(bad, n, "R2A4 面板本来就带斜杠（我们没补）⇒ 取回时【原样保留】'/'（不许吞用户敲的字）",
                panelText("/sh", false), "/sh");
        c(bad, n, "R2A5 补没补斜杠：面板不以 '/' 开头 ⇒ true", mirrorAddedSlash("sh"), true);
        c(bad, n, "R2A5 面板以 '/' 开头 ⇒ false（他自己敲的斜杠不许被算成我们补的）",
                mirrorAddedSlash("/sh"), false);
        c(bad, n, "R2A5 面板为空 / null ⇒ true（我们补）",
                mirrorAddedSlash("") && mirrorAddedSlash(null), true);
        // 端到端：这就是任务书 ① 点名的那一条
        {
            final String panel0 = "sh";
            final String mirror0 = mirrorText(panel0);
            final int start0 = wordStartForCompletion(mirror0);
            final String done0 = applyCompletion(mirror0, start0, mirror0.length(), "shanhai");
            c(bad, n, "R2A6 端到端（任务书 ①）：面板 'sh' + 候选 '/shanhai' ⇒ 写回面板的必须是 'shanhai'（不带 '/'）",
                    panelText(done0, mirrorAddedSlash(panel0)), "shanhai");
            c(bad, n, "R2A6 端到端：中间那一步镜子里必须是 '/shanhai'（原版走的还是它自己那条命令路）",
                    done0, "/shanhai");
            c(bad, n, "R2A8 往返一致：写回面板之后下一拍再送进镜子必须还是同一条文本"
                            + "（板上那行字与候选表不会各说一套）",
                    mirrorText(panelText(done0, mirrorAddedSlash(panel0))), done0);

            final String panel1 = "/sh";
            final String mirror1 = mirrorText(panel1);
            final String done1 = applyCompletion(mirror1, wordStartForCompletion(mirror1),
                    mirror1.length(), "shanhai");
            c(bad, n, "R2A4 端到端：面板本来就写 '/sh' ⇒ 补完写回 '/shanhai'（一个字都不许吞）",
                    panelText(done1, mirrorAddedSlash(panel1)), "/shanhai");
        }
        c(bad, n, "R2A7 补全起点：镜子 '/sh' ⇒ 1（命令名从斜杠【之后】开始，与原版 stringreader.skip() 同口径）",
                wordStartForCompletion("/sh"), 1);
        c(bad, n, "R2A7 补全起点：镜子 '/' ⇒ 1", wordStartForCompletion("/"), 1);
        c(bad, n, "R2A7 补全起点：镜子 '/shanhai menu dis' ⇒ 14（最后一个词，与 lastWordIndex 同口径）",
                wordStartForCompletion("/shanhai menu dis"), 14);
        c(bad, n, "R2A7 补全起点：镜子 '/shanhai  menu ' ⇒ 15（末尾空白之后 = 文本长度，与 lastWordIndex 同口径）",
                wordStartForCompletion("/shanhai  menu "), 15);
        c(bad, n, "R2A7-N13 负对照：补全起点恒在 [0, 文本长度] 内（不许越界，否则原版 substring 会抛）",
                startRangeOk(), true);

        // ================================================================ 🆕 组 ⑨：② 候选表锚在板上
        final int sw = 854;
        final int sh = 480;
        final double[] board = {200.0, 200.0, 700.0, 400.0};   // 板投影到屏幕上的矩形（GUI 坐标）
        final int[] anchor = anchorFor(sw, sh, board, SUGGEST_MAX_H);
        c(bad, n, "R2B1 锚的下边缘 = 板上边缘 − 4（候选表从下边缘往上长，贴着板）",
                anchor == null ? -1 : anchor[1], 196);
        c(bad, n, "R2B1 锚的左边缘 = 板的左边缘", anchor == null ? -1 : anchor[0], 200);
        c(bad, n, "R2B2 板矩形 null ⇒ 没有锚（⇒ 调用方一个像素都不动 = 原版底部位置）",
                anchorFor(sw, sh, null, SUGGEST_MAX_H) == null, true);
        c(bad, n, "R2B2 板矩形长度不足（3 个数）⇒ 没有锚，不炸",
                anchorFor(sw, sh, new double[]{1, 2, 3}, SUGGEST_MAX_H) == null, true);
        c(bad, n, "R2B2 板矩形含 NaN（四角之一在近平面之后）⇒ 没有锚，不炸",
                anchorFor(sw, sh, new double[]{200, 100, Double.NaN, 300}, SUGGEST_MAX_H) == null, true);
        c(bad, n, "R2B2 板矩形含 Inf ⇒ 没有锚，不炸",
                anchorFor(sw, sh, new double[]{Double.POSITIVE_INFINITY, 100, 700, 300}, SUGGEST_MAX_H) == null, true);
        c(bad, n, "R2B2 退化矩形（宽 0）⇒ 没有锚，不炸",
                anchorFor(sw, sh, new double[]{200, 100, 200, 300}, SUGGEST_MAX_H) == null, true);
        c(bad, n, "R2B2 板整块在屏幕右边之外 ⇒ 没有锚（回退到底部）",
                anchorFor(sw, sh, new double[]{900, 100, 1200, 300}, SUGGEST_MAX_H) == null, true);
        c(bad, n, "R2B2 板整块在屏幕上方之外 ⇒ 没有锚（回退到底部）",
                anchorFor(sw, sh, new double[]{200, -300, 700, -100}, SUGGEST_MAX_H) == null, true);
        c(bad, n, "R2B3 板上面放不下（板顶 100、屏高 200、候选表最坏 120 高）⇒ 没有锚，不炸",
                anchorFor(sw, 200, new double[]{200.0, 100.0, 700.0, 300.0}, SUGGEST_MAX_H) == null, true);
        c(bad, n, "R2B3-N14 负对照：同一块板、屏高 480 ⇒ 【必须】给得出锚（否则 R2B3 恒绿）",
                anchorFor(sw, sh, board, SUGGEST_MAX_H) != null, true);
        c(bad, n, "R2B4 板左边在屏幕外（负数）⇒ 左边缘夹到 0",
                leftOf(anchorFor(sw, sh, new double[]{-50, 200, 700, 400}, SUGGEST_MAX_H)), 0);
        c(bad, n, "R2B4 板左边太靠右 ⇒ 左边缘夹到 屏宽 − MIN_ROOM_W（" + (sw - MIN_ROOM_W) + "）",
                leftOf(anchorFor(sw, sh, new double[]{820, 200, 1000, 400}, SUGGEST_MAX_H)), sw - MIN_ROOM_W);
        c(bad, n, "R2B5 屏幕 0×480 / 854×−1 ⇒ 没有锚，不炸",
                (anchorFor(0, sh, board, SUGGEST_MAX_H) == null
                        && anchorFor(sw, -1, board, SUGGEST_MAX_H) == null), true);
        c(bad, n, "R2B5-N15 扫描：凡给得出的锚都必须夹在可视区内"
                        + "（0 ≤ 左 ≤ 屏宽−MIN_ROOM_W、下边缘 ≤ 屏高−15、下边缘−最坏高度 ≥ 0）",
                anchorsStayInView(), true);
        c(bad, n, "R2B6 原版底部口径：vanillaSuggestBottom(480) = 465（= 屏高 − 15，与候选条数无关）",
                vanillaSuggestBottom(480), 465);
        c(bad, n, "R2B6 候选表最坏高度 = 行数上限 × 行高 = 10 × 12 = 120", SUGGEST_MAX_H, 120);
        final double[] box = boxOfCorners(new double[]{10, 30, 20, 40}, new double[]{5, 5, 15, 15});
        c(bad, n, "R2B7 四角 ⇒ 轴对齐矩形（min/max）",
                box == null ? "(null)" : (box[0] + "," + box[1] + "," + box[2] + "," + box[3]),
                "10.0,5.0,40.0,15.0");
        c(bad, n, "R2B7-N16 负对照：有一个角不在（NaN）⇒ 整块作废（不许拿剩下三个角凑一个矩形）",
                boxOfCorners(new double[]{10, Double.NaN, 20}, new double[]{5, 5, 15}) == null, true);
        c(bad, n, "R2B7-N16 负对照：空数组 / 两数组长度不一致 ⇒ 作废，不炸",
                (boxOfCorners(new double[0], new double[0]) == null
                        && boxOfCorners(new double[]{1, 2}, new double[]{1}) == null), true);

        // ================================================================ 🆕 组 ⑩：③ 鼠标点候选
        final int dx = 186;
        final int dy = -284;
        final int[] rect = {10, 20, 70, 32};      // {left, top, right, bottom}（本类口径，见类注释 §7）
        c(bad, n, "R2C1 屏幕点在（平移后的）候选表里 ⇒ 换算回表坐标系后命中",
                containsOf(rect, 10 + dx + 5, 20 + dy + 5, dx, dy), true);
        c(bad, n, "R2C2 屏幕点在候选表左边 1 像素 ⇒ 不命中（⇒ 原来的面板点击逻辑接管）",
                containsOf(rect, 10 + dx - 1, 20 + dy + 5, dx, dy), false);
        c(bad, n, "R2C2 屏幕点在候选表下方之外 ⇒ 不命中",
                containsOf(rect, 10 + dx + 5, 20 + dy + 12, dx, dy), false);
        c(bad, n, "R2C2 右/下边缘是开区间（与原版 Rect2i#contains 逐位同口径）",
                (contains(rect, 70, 25) || contains(rect, 15, 32)), false);
        c(bad, n, "R2C2-N17 负对照：退化矩形（宽 0）/ null / 长度不足 ⇒ 恒不命中，不炸",
                (contains(new int[]{10, 20, 10, 32}, 10, 25) || contains(null, 10, 25)
                        || contains(new int[]{1, 2, 3}, 1, 2)), false);
        c(bad, n, "R2C3 偏移为 0（回退到底部那一档）时换算就是恒等 ⇒ 命中判定与改动前逐位相同",
                (toListSpace(123.0, 45.0, 0, 0)[0] == 123.0 && toListSpace(123.0, 45.0, 0, 0)[1] == 45.0), true);
        c(bad, n, "R2C3-N18 负对照：偏移必须真的起作用"
                        + "（同一个屏幕点、不换算就直接判 ⇒ 必须不命中，否则 R2C1 恒绿）",
                contains(rect, 10 + dx + 5, 20 + dy + 5), false);

        // ================================================================ 🆕 组 ⑪：④ 滚轮（坐标 + 翻页语义）
        // 下面这些尺寸都是真尺寸：2560 宽的窗口、GUI 缩放 3 档 ⇒ guiScaledWidth = 854（本机实测那组反例）
        final int wScreenW = 2560;
        final int wScreenH = 1440;
        final int wGuiW = 854;
        final int wGuiH = 480;
        c(bad, n, "R3A1 原版读坐标那一句：raw 960 / 屏宽 1920 / GUI 宽 960 ⇒ 480",
                vanillaScaledMouse(960.0, 1920, 960), 480);
        c(bad, n, "R3A1 向零截断（不是四舍五入）：raw 1.9 ⇒ 0",
                vanillaScaledMouse(1.9, 1920, 960), 0);
        c(bad, n, "R3A1 向零截断（不是 floor）：raw −0.9 ⇒ 0",
                vanillaScaledMouse(-0.9, 1920, 960), 0);
        c(bad, n, "R3A2 逆换算（实测有反例的那组尺寸）：gui 3 必须被原版读回 3",
                vanillaScaledMouse(rawMouseFor(3.0, wScreenW, wGuiW), wScreenW, wGuiW), 3);
        c(bad, n, "R3A2 同上：gui 6 ⇒ 6",
                vanillaScaledMouse(rawMouseFor(6.0, wScreenW, wGuiW), wScreenW, wGuiW), 6);
        c(bad, n, "R3A3 逆换算（负数，半格必须跟符号走）：gui −7 ⇒ −7",
                vanillaScaledMouse(rawMouseFor(-7.0, wScreenW, wGuiW), wScreenW, wGuiW), -7);
        c(bad, n, "R3A3 同上：gui −3 ⇒ −3",
                vanillaScaledMouse(rawMouseFor(-3.0, wScreenW, wGuiW), wScreenW, wGuiW), -3);
        c(bad, n, "R3A4 扫描：18 组真实尺寸 ×（正、负、小数）三片 ⇒ 一个都不许差格",
                wheelRoundTrips(), true);
        c(bad, n, "N19 负对照：不加半格就【读不回来】（这组尺寸下 gui 3 会被读成 2 ⇒ R3A2 有牙）",
                vanillaScaledMouse(3.0 * (double) wScreenW / (double) wGuiW, wScreenW, wGuiW) == 3, false);
        c(bad, n, "N20 负对照：恒定 +0.5 在负数上【读不回来】（gui −7 会被读成 −6 ⇒ R3A3 有牙）",
                vanillaScaledMouse((-7 + 0.5) * (double) wScreenW / (double) wGuiW, wScreenW, wGuiW) == -7, false);
        c(bad, n, "R3B1 dx = dy = 0（回退到原版底部那一档）⇒ 不用换坐标（null ⇒ 真鼠标直接用）",
                wheelTarget(400.0, 300.0, 0, 0, wScreenW, wScreenH, wGuiW, wGuiH) == null, true);
        c(bad, n, "R3B2 表被平移过 ⇒ 必须换坐标，且目标读回来正好是 mouseX − dx（400 − 186 = 214）",
                wheelDecodesAxisX(400.0, 186), 214);
        c(bad, n, "R3B2 同上 Y 轴：mouseY − dy（300 − (−284) = 584）", wheelDecodesAxisY(300.0, -284), 584);
        c(bad, n, "R3B3 只有 dy 不为 0 也要换（判据是【两个量都为 0】才跳过）",
                wheelTarget(400.0, 300.0, 0, -284, wScreenW, wScreenH, wGuiW, wGuiH) == null, false);
        c(bad, n, "R3B4 退化尺寸（屏 0×0）⇒ 不炸，目标原样返回（安全的一侧）",
                wheelTargetText(400.0, 300.0, 186, -284, 0, 0, 0, 0), "214.0/584.0");
        c(bad, n, "N21 负对照：不换坐标时原版读到的是 mouseX（400），【不是】mouseX − dx（214）⇒ R3B2 有牙",
                vanillaScaledMouse(rawMouseFor(400.0, wScreenW, wGuiW), wScreenW, wGuiW) == 214, false);
        c(bad, n, "R3C1 一页 10 条、25 个候选：滚轮向下（delta −1）⇒ 起点下标 0 → 1",
                wheelOffsetAfter(0, -1.0, 25, SUGGEST_LINE_LIMIT), 1);
        c(bad, n, "R3C1 滚轮向上（delta +1）⇒ 15 → 14（回退）",
                wheelOffsetAfter(15, 1.0, 25, SUGGEST_LINE_LIMIT), 14);
        c(bad, n, "R3C2 到顶：起点 0 再往上滚 ⇒ 一动不动（0）",
                wheelOffsetAfter(0, 1.0, 25, SUGGEST_LINE_LIMIT), 0);
        c(bad, n, "R3C2 到底：起点 15 再往下滚 ⇒ 一动不动（15）",
                wheelOffsetAfter(15, -1.0, 25, SUGGEST_LINE_LIMIT), 15);
        c(bad, n, "R3C2 上界 = 候选数 − 每页条数 = 25 − 10 = 15（【不是】25）",
                wheelOffsetAfter(20, -1.0, 25, SUGGEST_LINE_LIMIT), 15);
        c(bad, n, "R3C3 候选不足一页（7 条 < 10）：往下滚 ⇒ 一动不动（0）",
                wheelOffsetAfter(0, -1.0, 7, SUGGEST_LINE_LIMIT), 0);
        c(bad, n, "R3C3 候选不足一页：往上滚也不动（0）",
                wheelOffsetAfter(0, 1.0, 7, SUGGEST_LINE_LIMIT), 0);
        c(bad, n, "R3C3 候选 0 条 ⇒ 0，不炸", wheelOffsetAfter(0, -1.0, 0, SUGGEST_LINE_LIMIT), 0);
        c(bad, n, "R3C3 delta 为 0 ⇒ 一动不动", wheelOffsetAfter(7, 0.0, 25, SUGGEST_LINE_LIMIT), 7);
        c(bad, n, "R3C3 半步（delta −0.5）⇒ 一动不动（(int)(0.5) = 0，与原版 d2i 同口径）",
                wheelOffsetAfter(0, -0.5, 25, SUGGEST_LINE_LIMIT), 0);
        c(bad, n, "R3C4 连续往下滚到底：14 → 15 → 15（到底之后再滚不变）",
                wheelOffsetAfter(wheelOffsetAfter(14, -1.0, 25, SUGGEST_LINE_LIMIT), -1.0, 25,
                        SUGGEST_LINE_LIMIT), 15);
        c(bad, n, "N22 负对照：向上与向下【必须】走出不同的结果（否则 R3C1 恒绿）",
                wheelOffsetAfter(0, 1.0, 25, SUGGEST_LINE_LIMIT)
                        == wheelOffsetAfter(0, -1.0, 25, SUGGEST_LINE_LIMIT), false);

        final String b = bad.length() == 0 ? null : bad.toString();
        return new Report(n[0], n[1], b);
    }

    /** 一行自检读数（写进日志；调用方据此判 PASS/FAIL）。 */
    public static String selfTestLine() {
        final Report r = selfTest();
        return "holo_tabcomplete_selftest " + r.pass() + "/" + r.total() + " PASS=" + r.ok()
                + (r.ok() ? "（判据：Tab 闸门 + 前导斜杠镜像（面板 'sh' ⇄ 镜子 '/sh'）+ "
                + "候选表锚在全息板上（含回退/夹紧）+ 鼠标命中换算 + 原版 apply/ghost/cycle/分词/排序"
                + "五条公式的转写 + 滚轮坐标换算与翻页语义；含 22 组负对照）"
                : (" FAILED:" + r.bad()));
    }

    // ---- 自检用的小工具（都不碰 Minecraft）----

    /** 面板原文扫描：镜子里绝不许出现 {@code "//"}（也就是"绝不许把用户敲的斜杠再加一遍"）。 */
    private static boolean noDoubleSlash() {
        final String[] samples = {"sh", "/sh", "/", "", null, "Shanhai"};
        for (String s : samples) {
            final String m = mirrorText(s);
            if (m.length() < 1 || m.charAt(0) != SLASH || m.contains(SLASH_STR + SLASH_STR)) {
                return false;
            }
        }
        return true;
    }

    /** 补全起点必须恒在 {@code [0, 文本长度]} 内（越界会让原版的 substring 抛异常）。 */
    private static boolean startRangeOk() {
        final String[] samples = {"", "/", "sh", "/sh", "/sh ", "  /sh", "/a b  c ", "/shanhai menu dis", null};
        for (String s : samples) {
            final int at = wordStartForCompletion(s);
            final int len = s == null ? 0 : s.length();
            if (at < 0 || at > len) {
                return false;
            }
        }
        return true;
    }

    /** 扫一批板矩形：凡「给得出锚」的，锚都必须夹在可视区内。 */
    private static boolean anchorsStayInView() {
        final int sw = 854;
        final int sh = 480;
        for (int bl = -200; bl <= 1000; bl += 37) {
            for (int bt = -50; bt <= 520; bt += 23) {
                final int[] a = anchorFor(sw, sh, new double[]{bl, bt, bl + 400, bt + 200}, SUGGEST_MAX_H);
                if (a == null) {
                    continue;
                }
                if (a[0] < 0 || a[0] > sw - MIN_ROOM_W) {
                    return false;
                }
                if (a[1] > vanillaSuggestBottom(sh)) {
                    return false;
                }
                if (a[1] - SUGGEST_MAX_H < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 锚的左边缘（没有锚时给 −1，好让判据打出一句人话）。 */
    private static int leftOf(int[] anchor) {
        return anchor == null ? -1 : anchor[0];
    }

    /** "屏幕点 (x,y) 换算回候选表坐标系之后，命不命中这张表" —— 就是生产路径那两步。 */
    private static boolean containsOf(int[] rect, double x, double y, int dx, int dy) {
        final double[] p = toListSpace(x, y, dx, dy);
        return contains(rect, p[0], p[1]);
    }

    /** 换坐标之后 X 轴读回来的整数（拿不到目标时给 {@code Integer.MIN_VALUE}，好让判据打一句人话）。 */
    private static int wheelDecodesAxisX(double mouseX, int dx) {
        final double[] t = wheelTarget(mouseX, 300.0, dx, -284, 2560, 1440, 854, 480);
        return t == null ? Integer.MIN_VALUE : vanillaScaledMouse(t[0], 2560, 854);
    }

    /** 见 {@link #wheelDecodesAxisX}（Y 轴那一半）。 */
    private static int wheelDecodesAxisY(double mouseY, int dy) {
        final double[] t = wheelTarget(400.0, mouseY, 186, dy, 2560, 1440, 854, 480);
        return t == null ? Integer.MIN_VALUE : vanillaScaledMouse(t[1], 1440, 480);
    }

    /** 换坐标目标的文本形式（退化尺寸那一档要看的是"原样返回"，不是 NaN / null）。 */
    private static String wheelTargetText(double mouseX, double mouseY, int dx, int dy,
                                          int screenW, int screenH, int guiW, int guiH) {
        final double[] t = wheelTarget(mouseX, mouseY, dx, dy, screenW, screenH, guiW, guiH);
        return t == null ? "(null)" : (t[0] + "/" + t[1]);
    }

    /**
     * 扫描：18 组真实尺寸 ×（负数 / 正数 / 小数）⇒ {@link #rawMouseFor} 之后必须被
     * {@link #vanillaScaledMouse} 一个不差地读回来。
     * <p>这一条抓的是"半格"那件事：不加半格在正数上掉一格，恒定 +0.5 在负数上掉一格（见 N19 / N20）。
     */
    private static boolean wheelRoundTrips() {
        final int[][] pairs = {
                {1920, 960}, {1920, 640}, {1366, 683}, {2560, 854}, {3840, 960}, {1000, 334},
                {854, 854}, {1280, 640}, {2560, 1280}, {1920, 480}, {1600, 800}, {1366, 456},
                {3440, 860}, {2560, 512}, {2880, 720}, {4096, 1024}, {1000, 250}, {1234, 617}
        };
        for (int[] p : pairs) {
            final int screenPx = p[0];
            final int guiPx = p[1];
            for (int i = -4000; i < guiPx * 10; i++) {
                final double gui = i / 10.0;
                if (vanillaScaledMouse(rawMouseFor(gui, screenPx, guiPx), screenPx, guiPx) != (int) gui) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- 自检里一格"应当等于"（与 ShanhaiHoloMenuPanel 同一套口径，int/boolean/String 三个重载）----

    private static void c(StringBuilder bad, int[] n, String what, int actual, int expected) {
        n[1]++;
        if (actual == expected) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：得到 ").append(actual)
                    .append("，应当是 ").append(expected).append(';');
        }
    }

    private static void c(StringBuilder bad, int[] n, String what, boolean actual, boolean expected) {
        n[1]++;
        if (actual == expected) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：得到 ").append(actual)
                    .append("，应当是 ").append(expected).append(';');
        }
    }

    private static void c(StringBuilder bad, int[] n, String what, String actual, String expected) {
        n[1]++;
        if (expected == null ? actual == null : expected.equals(actual)) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：得到「").append(actual)
                    .append("」，应当是「").append(expected).append('」');
        }
    }
}
