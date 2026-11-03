package com.shanhai.client.text;

import com.shanhai.ShanhaiMod;
import com.shanhai.common.text.ShanhaiTextPalette;
import com.shanhai.common.text.ShanhaiTextParser;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import org.joml.Matrix4f;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 山海重构 · <b>把 {@code &$…-} 文本画成「随时间流动的色板渐变」的渲染器</b>。
 *
 * <h2>0. 出处</h2>
 * <pre>
 * originals/upstream\gtl_shanhai-dishanhai\src\main\java\com\dishanhai\gt_shanhai\mixin\WobbleFontMixin.java
 *   240-645  onDrawInBatchFCS  ← 本类整体结构照它
 *   34-112   onWidth*         ← 宽度修正（上游用三个 @Inject 分别打三个 width 重载）
 *   442-483  「无位移效果 → 单次绘制」分支
 *   485-638  「有位移效果 → 逐字绘制」分支
 *   628-633  Style + 逐字 drawInBatch + currentX = adv - xOff
 * </pre>
 * 🔴 <b>本类故意与上游不同的三处</b>（每处都写在下面对应位置）：
 * <ol>
 *   <li><b>@Mixin 的注解在 {@link com.shanhai.mixin.ShanhaiFontStyleMixin}，用的是开发名 + refmap</b>，
 *       不是上游的 {@code remap=false}+SRG 名（那条路「只在生产生效、开发期静默失效」）；</li>
 *   <li><b>先探针再分配</b>：上游一进门就 {@code text.accept} 收集成 StringBuilder + ArrayList&lt;Style&gt;
 *       （{@code WobbleFontMixin.java:253-259}）⇒ <b>每段文字都分配</b>。
 *       本类先用<b>零分配</b>探针问「有没有 {@code &}」，没有就直接返回（99%+ 的调用走这条）；</li>
 *   <li><b>§ 混合走「分段绘制」</b>（2026-11-01，A 案，见本类 §4.7）：同一行里 `§` 段交原版、`&$` 段交我们，
 *       逐段推进 x；<b>没有 § 的行完全不碰这条路</b>（回归底线）；
 *       <b>FCS 通道上 § 已经被折成 Style</b> ⇒ 那条路走不到，另有一条「按样式切段」的路（§4.8）。</li>
 * </ol>
 *
 * <h2>1. 🔴 为什么渲染代码在本类（普通类）而不是在 mixin 类里</h2>
 * mixin 类<b>不参与 {@code reobfJar} 重混淆</b>（它的成员引用要靠 refmap 在运行期映射），
 * 而本工程<b>现有两个 mixin 都打自己的类</b>、<b>从来没有走过 refmap 路径</b>。
 * 把 {@code Font.drawInBatch(...)} / {@code Font.width(...)} 这些调用放在<b>普通类</b>里，
 * 它们就走<b>本工程已经验证过的老路</b>（{@code reobfJar} 把 dev 名改成 SRG 名，判据就是 jar 里的
 * {@code m_\\d+_} 计数 &gt; 0）；mixin 类里只留 {@code @Inject} 的<b>方法名字符串</b>这一件事需要 refmap。
 * ⇒ <b>把「新基建」的风险面压到最小</b>：refmap 只需要支持「一个方法名映射」。
 *
 * <h2>2. 两条绘制路径（照上游分支条件）</h2>
 * <table border="1">
 *   <tr><th>条件</th><th>路径</th><th>逐字循环里有没有 {@code new}</th></tr>
 *   <tr>
 *     <td>有<b>位移</b>效果（{@code ?} glitch / {@code *} floatX）</td>
 *     <td><b>逐字绘制</b>：每个字符一次 {@code drawInBatch}，带 x/y 偏移</td>
 *     <td><b>没有</b>（复用 acceptor + {@code Style.EMPTY} + 颜色走 {@code color} 参数）</td>
 *   </tr>
 *   <tr>
 *     <td>只有颜色（{@code body_aurora} / {@code body_moss} / {@code body_silver} / 全部物品名）</td>
 *     <td><b>单次绘制</b>：构造一个「每字一个 Style」的 FCS，一次 {@code drawInBatch} 画完整行</td>
 *     <td><b>没有</b>（{@code Style[]} 在循环<b>外</b>按色板长度预计算，循环里只做数组下标）</td>
 *   </tr>
 * </table>
 * <b>为什么单次绘制不做成「逐字 + 颜色参数」</b>（那样能统一成一条路径）：
 * {@code Font.drawInternal} 每次调用都会 {@code new Font$StringRenderOutput(...)}
 * （取证：{@code javap -c net.minecraft.client.gui.Font} 的 {@code drawInternal(FormattedCharSequence…)}
 * 第 0 条指令就是 {@code new Font$StringRenderOutput}）⇒ 逐字调用 = <b>每字多一个 Font 内部对象</b>，
 * 比「每字两个 Style/TextColor」更贵。所以照上游分两条路。
 *
 * <h2>3. 缓存与分配（逐项交代）</h2>
 * <ol>
 *   <li><b>identity 记忆（最快）</b>：{@link FcsMemo} 记住「上一帧那条 {@code FormattedCharSequence} 实例
 *       解析成了什么」。聊天栏/物品名每帧重画的是<b>同一个实例</b>时，这一步<b>连字符串都不用重建</b>。</li>
 *   <li><b>字符串 LRU</b>：{@link ShanhaiTextParser#parse(String)} 内部 256 条，
 *       存<b>肯定结果与否定结果</b>（含 {@code &} 的普通文本每帧不该重解析）。</li>
 *   <li><b>零分配探针</b>：{@link AmpProbe} 是一个<b>复用</b>的 {@link FormattedCharSink}，
 *       找到 {@code &} 就 {@code return false} 提前中止；<b>不捕获任何变量 ⇒ 不产生 lambda 对象</b>。</li>
 *   <li><b>复用缓冲</b>：{@link Collector}（StringBuilder）/ {@link StyleScratch}（{@code Style[]}）/
 *       {@link CharSeq} / {@link LineSeq} 全部 {@code ThreadLocal} 复用，稳态不再分配。</li>
 *   <li><b>每帧仍会分配的两处</b>（如实列出）：
 *       <ul>
 *         <li>有 {@code &} 的那几行每帧一次 {@code StringBuilder.toString()}（= 1 个 String）——
 *             它是缓存表的 key，去不掉；</li>
 *         <li>单次绘制路径按<b>色板槽位数</b>（≤ 色板长度，最多 40）重建 {@code Style} + {@code TextColor}。
 *             <b>这一步在逐字循环外</b>，且与正文长度无关（长行不会线性变贵）。</li>
 *       </ul>
 *       ⇒ 无 {@code &} 的文本（游戏里 99%+ 的绘制调用）<b>一条分配都没有</b>。</li>
 * </ol>
 *
 * <h2>4. 线程与并发</h2>
 * {@link Font#drawInBatch} 主要跑在客户端主线程（渲染线程），但本类<b>不假设</b>只有那一个线程：
 * 所有可复用状态都是 {@link ThreadLocal}（各自一份，互不干扰），
 * 唯一共享的是 {@link ShanhaiTextParser} 里那个 {@code synchronizedMap} 缓存（值不可变）。
 *
 * <h2>4.5 🔴 加粗（{@code &$#<样式名>-正文}，2026-09-30 新增）</h2>
 * 语法与选 {@code #} 的理由见 {@link ShanhaiTextParser} 类注释 §2.2，这里只说<b>本类怎么接</b>：
 * <ul>
 *   <li><b>单次绘制</b>（{@link #drawWholeLine}）：槽位 {@code Style} 上加一次
 *       {@code .withBold(Boolean.TRUE)} —— 与颜色挂在同一个 {@code Style} 上，
 *       <b>一个额外对象都不多分配</b>（原来就每槽一个 {@code Style}）。</li>
 *   <li><b>逐字绘制</b>（{@link #drawPerChar}）：用<b>类加载期就建好的常量</b> {@link #BOLD_STYLE} ——
 *       {@code Style.EMPTY.withBold(TRUE)} 只在静态初始化时算一次，热路径<b>零分配</b>
 *       （原来这里传的是 {@code Style.EMPTY}，现在只是多了一个可选的常量引用）。</li>
 *   <li><b>宽度</b>（{@link #widthOf}）：{@code Font.width(String)} 走 {@code Style.EMPTY}，
 *       <b>量不到加粗</b> ⇒ 补上 {@link ShanhaiTextParser#boldAdvance}（= 正文字形的码点数）。
 *       不补的话这两块板居中时每字偏 1 px。</li>
 *   <li><b>不动的东西</b>：{@code hasMotion()} 仍然只看 {@code ?}/{@code *}
 *       （加粗不改坐标，不需要逐字路径）；退化 A（样式名拼错）<b>不带加粗</b>；
 *       {@code prefix} <b>不加粗</b>。</li>
 * </ul>
 * <b>MC 侧的实证</b>（全部 {@code javap -c}，非推断）：
 * <pre>
 *   Font$StringRenderOutput.accept :  59 aload_2 / 60 invokevirtual Style.isBold()Z / 63 istore 7
 *                                   : 282 aload 5 / 283 iload 7 / 286 GlyphInfo.getAdvance(Z)F / 291 fstore 13
 *                                   : 445 aload_0 dup / 447 getfield x:F / 450 fload 13 / 452 fadd / 453 putfield x:F
 *   GlyphInfo.getAdvance(Z)        : getAdvance() + (Z ? getBoldOffset() : 0)；getBoldOffset() 默认 fconst_1
 *   Font.<init> 的 WidthProvider   : lambda$new$0 — 16 aload_2 / 17 Style.isBold()Z / 20 GlyphInfo.getAdvance(Z)F
 * </pre>
 * ⇒ <b>同一个 {@code Style.isBold()} 同时驱动「画多宽」和「量多宽」</b>，两条路自动一致，
 * 我们只需要把 {@code Style} 挂对。
 *
 * <h2>4.6 🔴 还原符 {@code &r} 与「染色不越界」（2026-10-31）</h2>
 * <b>用户报的现象</b>（原话）：「这个已经会把渲染特效溢出到外面了」——
 * 一段用了 {@code &$} 的彩色名，<b>后面不属于它的字也被染上了</b>。
 *
 * <h3>4.6.1 溢出的机制：<u>不是</u>渲染器留了状态，是「染色范围 = 到串尾」这条范围规则</h3>
 * 逐条给位置（全部可在本类 / {@link ShanhaiTextParser} 里按行号核）：
 * <ol>
 *   <li>{@link #renderFcs} 把<b>这一次 {@code drawInBatch} 收到的整条 FCS</b> 收成一个字符串
 *       （{@link #extract} → {@link #Collector#accept} 逐码点 append，上限 {@link #MAX_CODEPOINTS}）；
 *       裸字符串那条入口 {@link #renderString} 拿到的更是<b>调用方原封不动的一整串</b>；</li>
 *   <li>{@code ShanhaiTextParser.parseUncached} 里 {@code rest = raw.substring(dashIdx + 1)}
 *       ⇒ <b>正文 = {@code -} 之后一直到最后</b>；</li>
 *   <li>于是"彩色名 + 后面那段普通字"只要在<b>同一个串</b>里（同一次 {@code drawInBatch}，
 *       例如一个 {@code Component} 的多个 sibling、或 LDLib 那种直接传整行 String 的路径），
 *       后面那段就落进了 {@code body} ⇒ 被色板染色。<b>这就是用户看到的溢出</b>。</li>
 * </ol>
 * 🔴 <b>反证：本类没有留下任何"颜色状态"给调用方</b>（这条要能回答"哪一行没还原"）：
 * <ul>
 *   <li><b>代码里零处</b> {@code RenderSystem} / {@code GlStateManager} / {@code setShaderColor}
 *       （读数：本文件 888 行里 <b>代码行 482 行，命中 0 处</b>；{@code grep} 的 3 次命中<b>全部在本注释里</b>，
 *       就是这一句自己。热路径上唯一的外部状态是下面那 9 个 {@code ThreadLocal}）；</li>
 *   <li>颜色是<b>逐字形</b>决定的，来源只有两个：{@code Style.getColor()}、否则该次调用自己的 {@code color} 形参。
 *       {@code javap -c Font$StringRenderOutput.accept} 原文：
 *       <pre>
 *   :72 invokevirtual Style.getColor:()Lnet/minecraft/network/chat/TextColor;
 *   :79 ifnull 149               ⇒ 样式没色 ⇒ 走 aload_0 的 r/g/b/a 字段（= 那次调用传进来的 color）
 *       </pre>
 *       ⇒ {@code Font} 上<b>没有</b>"当前颜色"这种字段会被留下 ⇒ 颜色不可能跨 {@code drawInBatch} 泄漏；</li>
 *   <li>可复用的 <b>6 个 {@code ThreadLocal} 复用件</b>（{@link #PROBE}/{@link #COLLECTOR}/{@link #MEMO}/
 *       {@link #STYLES}/{@link #CHAR_SEQ}/{@link #LINE_SEQ}）每一个都在<b>用之前</b>整份重写：
 *       {@code reset()}（length=0）/ {@code prepare(…)}（覆盖字段）/ {@code ensure(slots)} 后
 *       <b>只读前 {@code slots} 个</b>（循环上界 = {@code slots}）⇒ 上一次的长串不会漏到下一次。
 *       另有 3 个 {@code boolean[1]} 标记（{@link #IN_RENDER}/{@link #IN_WIDTH}/{@link #STARTED}）：
 *       前两个在 {@code finally} 里复位，{@code STARTED} 在每次进入入口时<b>先清零</b>；</li>
 *   <li>静态可变状态只有 {@link #HOOK_LOGGED} 与两个限流计数器（{@code Atomic*}），
 *       <b>只影响日志打不打</b>，不参与任何绘制参数。</li>
 * </ul>
 * <b>结论一句话</b>：溢出 = <b>范围规则</b>（正文到串尾）＋ <b>调用方把多段内容拼进同一个串</b>，
 * 不是渲染器留了状态。<b>{@code Font} 层没有任何"还原点"可还，所以 {@code &r} 必须由语法自己给出来。</b>
 *
 * <h3>4.6.2 修法：染色段结束即还原</h3>
 * <ul>
 *   <li>有 {@code &r} ⇒ 画完 {@code body} 之后，在<b>它真正的末点</b>把 {@code suffix}
 *       用<b>调用方原色</b>、<b>零样式</b>再画一遍（{@link #drawStyled} 末尾那段）；</li>
 *   <li>没有 {@code &r} ⇒ {@code suffix} 为空，{@link #drawStyled} <b>一个多的字形都不画</b>：
 *       染色字形集合恒等于 {@code body} 的字符数（单次绘制 = {@link LineSeq} 只走 {@code body.length()} 个码点，
 *       逐字绘制 = 循环上界 {@code bodyLen}）⇒ <b>染色绝不越过 {@code body} 的串边界</b>；</li>
 *   <li><b>位置口径 = "画多宽"</b>，取的是 {@code Font.drawInBatch} 的<b>返回值</b>
 *       （前缀→正文 的接续本来就用它，同一个口径 ⇒ 行内间距自洽），
 *       <b>不是</b> {@code 字数 × 固定宽度}；也<b>不</b>用 {@code Font.width(body)} 再量一次
 *       （{@code Font.width(String)} 走 {@code Style.EMPTY} ⇒ <b>量不到加粗</b>，见 {@link #widthOf} 的注释）。
 *       {@code javap -c Font.drawInternal(FormattedCharSequence…)} 原文给出这个返回值的精确含义：
 *       <pre>
 *   :70 invokevirtual renderText:(…F)F     ← 真末点（float）
 *   :73 fstore_2  :74 fload_2  :75 f2i     ← 向下取整
 *   :76 iload 5   :78 ifeq 85  :81 iconst_1  ← dropShadow ? +1 : +0
 *   :86 iadd      :87 ireturn
 *       </pre>
 *       ⇒ 返回值 = {@code (int)末点 + (shadow ? 1 : 0)}，与真实末点相差 <b>≤ 1 px</b>。
 *       这与本类既有的"前缀→正文"接续是<b>同一口径</b>，所以行内两段间距保持一致（不单独修正这 1 px）。</li>
 *   <li>🔴 <b>防御</b>：{@code suffix} 非空时按<b>原始字符串整段</b>交给 {@code Font.drawInBatch(String,…)}，
 *       <b>不做任何样式换算</b>（不拆码点、不建 {@code Style}、不加粗）。
 *       {@code suffix} 里若含 {@code §} —— 按 {@link ShanhaiTextParser} 的铁律<b>整条早已被判「不是我们的码」</b>
 *       （解析期就把 {@code §} 的判定面扩到了 suffix），根本走不到这里；这条只是兜底：
 *       <b>真漏进来也不至于把 {@code §} 当普通字符画</b>（String 重载本来就会解析 {@code §}）。</li>
 * </ul>
 *
 * <h2>4.7 🔴 分段兼容（A 案，2026-11-01 用户拍板）：同一行里 {@code §} 段 ＋ {@code &$} 段</h2>
 * <b>要解决的</b>：改前只要一行里出现 {@code §}，整行就被判「不是我们的码」交回原版
 * ⇒ 那行里 {@code &$…-} 的码会<b>原样露出来</b>（用户实机截图：{@code &$electric-创始现实修改模块}）。
 * 用户拍板 A 案：<b>同一行里 {@code §} 段由原版画、{@code &$} 段由我们画，逐段推进</b>。
 * 解析规则见 {@link ShanhaiTextParser} 类注释 §7（那里定死了切分/退化/边界）。
 *
 * <h3>4.7.1 为什么不混在一段里画（技术理由，<u>这条没变</u>）</h3>
 * 我们的染色是<b>逐字自己算宽、自己取色</b>的（{@code LineSeq}/{@code CharSeq} 按码点喂 {@code Style}），
 * 而 {@code §} 是<b>零宽控制码</b>：它和它后面那个字符都<b>不进 glyph 流</b>，却会<b>改变后续字形的样式</b>
 * （{@code StringDecomposer.iterateFormatted} 里 {@code sipush 167 / ChatFormatting.getByCode /
 * Style.applyLegacyFormat}）。⇒ 把它混进"我们自己排好色槽的那条字流"里，
 * 色槽下标（{@code idx = (intPhase + charIndex) % len}）与字形下标就不再一一对应，
 * 而 {@code charIndex} 又直接决定<b>每个字画在哪个 x</b>。**一个字符的错位会污染整行。**
 * ⇒ 唯一干净的做法：<b>让每一段各自是一条干净的字流</b>——原版段交给原版（它自己会算），
 * 我们段交给我们（本来就只有我们的字）。这就是"分段"而不是"混排"的原因。
 *
 * <h3>4.7.2 防递归（{@link #IN_SEGMENTS}，这段是硬要求）</h3>
 * 我们<b>被 {@code Font} 的 hook 调进来</b>，而原版段那一半又必须调
 * {@code Font.drawInBatch(String,…)} ⇒ <b>一定会再进 hook</b>。处理：
 * <pre>
 *   drawSegments 入口 : IN_SEGMENTS = true（finally 里恢复成进入前的值）
 *   renderFcs/renderString : 在【零分配探针之后】检查 IN_SEGMENTS ⇒ true 就 return null（不取消原版）
 * </pre>
 * 🔴 为什么<b>不能只靠 {@link #IN_RENDER}</b>：`IN_RENDER` 在分段绘制期间确实是 true，
 * 也能挡住递归 —— 但那是<b>间接</b>保证（依赖"调用 drawSegments 的人一定先置了 IN_RENDER"）。
 * 一旦以后有人在 `drawSegments` 里提前复位它、或从别处直接调 `drawSegments`，
 * 就会变成<b>分段里再分段</b>：<b>不崩</b>、只是每次绘制多花 N 倍时间，日志上<b>看不出来</b>。
 * ⇒ 给分段绘制一个<b>只由 drawSegments 置位</b>的旗标，内层一律放行。
 * ⚠️ 检查点放探针之后：没有 {@code &} 的文本（99%+ 的 String 绘制）不为它付 ThreadLocal 开销。
 *
 * <h3>4.7.3 x 推进量：每段各自推进，<u>阴影补偿逐段还原</u>（2026-11-01 §9 修正）</h3>
 * <ul>
 *   <li><b>原版段</b>：{@code drawInBatch(段文本, curX, …)} 的<b>返回值</b>；</li>
 *   <li><b>我们段</b>：{@link #drawWholeLine} / {@link #drawPerChar} 的返回值（真实末点 float，
 *       整行结束时统一 {@code Math.round}）；</li>
 *   <li>🔴 <b>阴影补偿 <u>必须逐段还原</u></b>：{@code drawInternal} 的返回值是
 *       {@code (int)末点 + (shadow?1:0)}（见 §4.6.2 的 javap），那个 {@code +1} 是"整行画完该往前挪多少"，
 *       <b>每调用一次就出现一次</b>。分段 = N 次调用 ⇒ 不还原就"每段多吃 1px"，
 *       而且<b>不只是行末变长</b>：后一段的<b>起点</b>也偏移 ⇒ 段间距被撑开。
 *       ⇒ 每次都过 {@link #noShadowBonus}（含我们段与最后一段，理由见该方法的注释）。</li>
 *   <li>✅ 实跑读数（{@code §7白&$neon-彩&r§f后}）：改前 {@code shadow=true} 终点 <b>33/34</b>；
 *       改后 {@code shadow=false} 与 {@code shadow=true} <b>都是 31</b> = {@code font.width("§7白彩§f后")}，
 *       差 <b>0</b>（逐段 9 / 20 / 31）。</li>
 *   <li>⚠️ <b>单段老路不动</b>：{@link #drawStyled} 的 prefix→body→suffix 接续仍是"直接用返回值"的
 *       老口径（{@link #drawPerChar} 传 {@code stripShadowBonus=false}）⇒ 1052 条文本的 x 推进量逐位不变。</li>
 * </ul>
 *
 * <h3>4.7.4 宽度：逐段量再相加（两个口径不同，故意的）</h3>
 * <ul>
 *   <li><b>原版段</b>：{@code self.width(段文本)} —— {@code Font.width(String)} 自己解析 {@code §}
 *       （{@code StringSplitter.stringWidth(String)} → {@code StringDecomposer.iterateFormatted}），
 *       而且 <b>{@code §l} 的加粗也算在里面</b>（{@code WidthProvider.getWidth(int,Style)} →
 *       {@code GlyphInfo.getAdvance(Z)}）⇒ <b>画与量同口径</b>，不用补；</li>
 *   <li><b>我们段</b>：{@code self.width(body) + boldAdvance(body)} —— 与 {@link #widthOf} 老路同一条补法
 *       （{@code width(String)} 走 {@code Style.EMPTY}，不知道我们这一段要加粗，见 §4.5）；</li>
 *   <li>🔴 <b>允许的差值只有一处</b>：{@code Font.width} 是 {@code Mth.ceil}，逐段 ceil 之和与整行 ceil
 *       最多差 <b>段数−1 px</b>（MC 字形推进量都是整数 px ⇒ 实测差 <b>0</b>）；
 *       而"逐段 ceil"是必须的 —— 每一段的末点本来就被各自的 {@code drawInBatch} 取整了。</li>
 * </ul>
 *
 * <h3>4.7.5 闸门（只在三条件同时成立时才分段）</h3>
 * {@link ShanhaiTextParser#mixedSegments} 为 {@code null} 时，本类两个绘制入口<b>立刻返回 null</b>
 * = 行为与改前一模一样：
 * <ol>
 *   <li>行里没有 {@code §} ⇒ 不分段（<b>回归底线</b>：256 条现存文本的 x 推进量必须逐位不变）；</li>
 *   <li>行里没有 {@code &$} ⇒ 不分段（全是原版段，交回原版整行画）；</li>
 *   <li>切完<b>一段我们的都没有</b>（`&$` 全畸形 / 全退化成原版段）⇒ 不分段，交回原版整行画
 *       ⇒ 那种行的屏幕上仍是<b>原文</b>（码可见），与改前一致；<b>不会</b>静默删字。</li>
 * </ol>
 *
 * <h2>4.8 🔴 FCS 通道上的 A 案（2026-11-02）：<b>§ 进不来，就按「样式」切段</b></h2>
 *
 * <h3>4.8.1 改前为什么一定丢色（真 bug，不是推断）</h3>
 * 物品 tooltip 走的是 {@code Font.drawInBatch(FormattedCharSequence,…)}。而 {@code §} 在
 * <b>{@code Component → FCS}</b> 那一步就已经 —— 用上游原文说 ——
 * <b>被转成 {@link Style} 并从文本里剥掉</b>（{@code Language$1.getVisualOrder}
 * → {@code StringDecomposer.iterateFormatted}：{@code sipush 167} / {@code ChatFormatting.getByCode} /
 * {@code Style.applyLegacyFormat}，{@code §} 与它后面那个字符<b>都不进 glyph 流</b>）。
 * ⇒ {@link #extract} 拿到的串<b>永远没有 {@code §}</b> ⇒ {@link #lookup} 之后
 * {@code parsed.ours()} 为真（串里只剩 {@code &$…-}）⇒ 走 {@link #drawStyled}，
 * 而 {@code drawStyled} 里 {@code prefix} / {@code suffix} 是<b>用调用方给的 {@code color} 重画</b>的
 * ⇒ 那些本该由 {@code §} 决定颜色的字<b>全被刷成调用方色（tooltip 里是白）</b>。
 * <p><b>实测（{@code temp\segtext\fcs}，改前实现 = {@code fcsold.OldRenderer}）</b>：
 * <pre>
 *   §4123§r4&amp;$aurora-567&amp;r8   ⇒ 123 的 {@code AA0000} 被涂成 FFFFFF
 *   &amp;$magic-123&amp;r4§3567§r8  ⇒ 4 的 {@code 555555} 与 567 的 {@code 00AAAA} 被涂成 FFFFFF
 *   （字一个没丢，丢的是颜色 —— 所以"看字在不在"的断言抓不到它，必须有"样式色"断言）
 * </pre>
 *
 * <h3>4.8.2 修法：<b>在 FCS 层按样式切段，行数不变</b>（不碰 lore、不碰行切分）</h3>
 * 闸门在 {@link #renderFcs} 里、<b>{@link #lookup} 之前</b>（老路一个字都没动）：
 * <pre>
 *   ① {@link #probeHasAmp} 有 {@code &}（99%+ 的调用在此之前就返回了）
 *   ② 不在 {@link #IN_SEGMENTS} 里（分段/子 FCS 的内层绘制放行给原版）
 *   ③ {@link #collectGlyphs}：一次遍历把 (codepoint, Style) 收成数组 + 拼出纯文本
 *   ④ 有<b>任意一个字形带样式</b>（{@code getColor()!=null} 或 {@code isBold/isItalic/isUnderlined/
 *      isStrikethrough/isObfuscated}）<b>且</b>{@link #planOf} 切出了至少一个我们段
 *      ⇒ 走 {@link #drawFcsSegments} 逐段绘制
 *   （③④ 任一不成立 ⇒ 继续走改前那条老路 ⇒ <b>无 § 样式、或无 {@code &} 的行逐位不变</b>）
 * </pre>
 * 逐段怎么画（三段规则，见 {@link #drawFcsSegments}）：
 * <ol>
 *   <li><b>我们段</b> ⇒ 与 §4.7 同一条路（{@link #drawOursSegment}：色板 + 加粗 + 位移效果）；</li>
 *   <li><b>带样式的原版段</b> ⇒ 🔴 <b>绝不转回 String</b>（一转 String，{@code Style} 就没了，正是本 bug）
 *       ⇒ 用<b>同一批 {@code (codepoint, Style)}</b> 现场做一条<b>子 FCS</b>（{@link RunSeq}：
 *       {@code accept} 里只把下标落在 {@code [from,to)} 的那些 {@code sink.accept(idx, style, cp)}
 *       原样转发出去，{@code Style} <b>引用级不变</b>）⇒ 再调
 *       {@code self.drawInBatch(subFcs, …)}；</li>
 *   <li><b>不带样式的原版段</b> ⇒ 照旧 {@code drawInBatch(String,…)}（与改前逐位相同）。</li>
 * </ol>
 * <b>递归</b>：整段绘制期间 {@link #IN_SEGMENTS} 为 true，内层（{@link #renderFcs} 与
 * {@link #renderString}）在探针之后立刻返回 {@code null} = 放行给原版 ⇒ <b>不会分段里再分段</b>。
 *
 * <h3>4.8.3 切段口径：<b>就是 {@link ShanhaiTextParser#parseSegments}</b>，不另写一套</h3>
 * 切段<b>不做第二份实现</b>：直接拿 {@link ShanhaiTextParser#parseSegments(String)} 的段表
 * （它本来就是"任何输入都给结果"的纯语法函数，对没有 {@code §} 的串同样给
 * 「原版段(前缀) / 我们段(正文) / 原版段(后缀)」）⇒ <b>与 String 通道同一口径</b>，不可能不一致。
 * 唯一多出来的一步是<b>把段映射回 FCS 的码点下标</b>（{@link FcsPlan#build}）：按段表顺序在纯文本上
 * 前进 —— 原版段要求 {@code raw.startsWith(段文本, pos)}，我们段要求
 * {@code raw.startsWith(正文, dash+1)}；<b>任何一条对不上就整体放弃</b>
 * （{@link FcsPlan#usable} = false ⇒ 交回改前行为），<b>不用猜的位置去画</b>。
 * ⚠️ 已知边界（如实记）：{@code &#$…-} 这种「{@code $} 不在 {@code &} 紧后」的写法单段 {@code parse} 认、
 * 但 {@code parseSegments} 只认 {@code &$} ⇒ 那种行<b>保持改前行为</b>（不丢字、颜色仍会被刷白）。
 * 全仓 {@code &#$} 命中 <b>0</b> 处（加粗写法一律 {@code &$#}）。
 *
 * <h3>4.8.4 宽度</h3>
 * {@link #widthFcs} <b>一个字都没改</b>：它量的是 {@code parse(extract).cleanText()}，
 * 而 {@code extract} 里本来就没有 {@code §}（零宽），{@code cleanText} 又已经剥掉了我们的码 ⇒
 * <b>它量的就是"界面上到底占几个像素"</b>。绘制侧的 x 终点由逐段 {@code drawInBatch} 的返回值累加
 * （每段过一次 {@link #noShadowBonus}，与 §4.7.3 同一条口径），实测与它<b>差 0</b>
 * （{@code shadow} 两种模式都差 0，读数在 {@code temp\segtext\fcs\FCS分段.md}）。
 *
 * <h3>4.8.5 分配与开销（如实交代）</h3>
 * 触发条件 = <b>FCS 里既有 {@code &} 又有样式</b>（= 带 {@code §} 的 tooltip 行），这类行本来就每帧
 * 做一次 {@code extract}；本路径把那次遍历换成 {@code collectGlyphs}（多写几个 int/Style 数组，
 * 数组 {@link FcsMemo} 内<b>复用不增长</b>），再加一次 {@code parseSegments} 的<b>缓存命中</b>
 * （{@code SEG_CACHE}，key = 纯文本串）与一次段表→下标的映射（{@link FcsPlan}，按 FCS 实例记忆）。
 * <b>无 {@code &} 的行、以及有 {@code &} 但没有任何样式的行 ⇒ 一条分配都不多</b>（见 §4.8.2 的闸门）。
 *
 * <h2>5. 失败模式（本项目铁律：两种失败必须长得不一样）</h2>
 * <ul>
 *   <li><b>注入失败</b> ⇒ 混入的 {@code require = 1} 让游戏<b>启动即崩</b>（响亮）；</li>
 *   <li><b>算色/绘制抛异常</b> ⇒ 本类 {@code catch (Throwable)} 后<b>安静地不生效</b>
 *       （这次没特效，<b>绝不崩</b>），并打一行 {@code font_style_error} 便于事后定位；</li>
 *   <li><b>注入成功但没生效</b> ⇒ 客户端日志里<b>没有</b>
 *       {@code [SHANHAI-SPEC] font_style_mixin hooked …} 这行 ⇒ 一眼区分「没注入」和「算错了」。</li>
 * </ul>
 *
 * <h2>6. 🔴 两条家族与两个入口（2026-10-01 新增 {@link #renderString}）</h2>
 * {@code Font} 把文字画出去分成两个<b>互不相干</b>的家族（字节码见 mixin 类注释 §2），
 * 本类对应两个入口，<b>不能只做一个</b>：
 * <table border="1">
 *   <tr><th>家族</th><th>收口方法（注入点）</th><th>本类入口</th><th>典型调用方</th></tr>
 *   <tr>
 *     <td>{@code Component} / {@link FormattedCharSequence}</td>
 *     <td>{@code drawInBatch(FormattedCharSequence,…)} = {@code m_272191_}</td>
 *     <td>{@link #renderFcs}</td>
 *     <td>聊天 / 物品名 / tooltip / JEI；{@code GuiGraphics.drawString(Font,Component,…)} 也落到这里</td>
 *   </tr>
 *   <tr>
 *     <td>{@code String}（裸字符串）</td>
 *     <td>{@code drawInBatch(String,…,IIZ)} = {@code m_272078_}</td>
 *     <td>{@link #renderString}</td>
 *     <td>LDLib {@code LabelWidget} 的 String 分支（→ {@code GuiGraphics.m_280056_}）；
 *         AE2 / FTB / KubeJS / Polylib / Sophisticated 等直接调 {@code m_271703_}（10 参，转调到这条）</td>
 *   </tr>
 * </table>
 * 🔴 <b>仍然罩不住</b>：{@code Font.drawInBatch8xOutline}（描边/发光文字）自己 new
 * {@code Font$StringRenderOutput} 逐偏移画，<b>不经过任何 {@code drawInBatch}</b>。
 * 实测调用者 = 原版 {@code SignRenderer} 与 Jade {@code ProgressStyle}（{@code glowText} 时）。
 * 这是<b>改前就存在</b>的缺口，本轮不动 —— 记在这里，免得下一个人以为"注入齐了就全覆盖"。
 */
public final class ShanhaiFontStyleRenderer {

    private ShanhaiFontStyleRenderer() {}

    /**
     * 已经注入成功的方法清单 —— <b>写死是刻意的</b>：它是「编译期事实」的快照，
     * 与 {@link com.shanhai.mixin.ShanhaiFontStyleMixin} 上的 {@code @Inject} 一一对应。
     * 改了混入就必须改这里（否则那行日志会撒谎）。
     */
    public static final String HOOK_METHODS =
            "{drawInBatch:FormattedCharSequence, drawInBatch:String, width:FormattedCharSequence, "
                    + "width:FormattedText, width:String}";

    /**
     * 加粗支持的对外标记 —— 进日志用。
     *
     * <p>🔴 <b>它存在的唯一理由</b>：用户会在客户端日志里找「这版 jar 到底有没有加粗」。
     * 只看 {@code methods=} 那一串<b>分不出来</b>（2026-09-30 那版与它的上一版 {@code HOOK_METHODS} 逐字相同）
     * ⇒ 加一个随功能一起出现的字段，让「日志里有没有 {@code bold=#}」成为判据。
     */
    public static final String BOLD_MARKER = "#";

    /**
     * <b>还原符</b>支持的对外标记 —— 进日志用（2026-10-31，与 {@link #BOLD_MARKER} 同一个理由）。
     *
     * <p>用户判断「这版 jar 有没有 {@code &r}」只能靠客户端日志里那一行
     * （屏幕上看不出"没有还原"和"还没加 {@code &r}"的区别）⇒ 在
     * {@code [SHANHAI-SPEC] font_style_mixin hooked …} 后面加一个 {@code restore=&r} 字段。
     * <p>⚠️ 它<b>必须</b>与 {@link ShanhaiTextParser#RESTORE_MARKER} 是同一个值：这里直接引用，
     * 不另写字面量（写两份就会有一天不一致，然后日志开始撒谎）。
     */
    public static final String RESTORE_MARKER = ShanhaiTextParser.RESTORE_MARKER;

    /** 收集字符串时最多接受多少个码点，超了就当「不是我们的码」。防止病态长文本把热路径拖垮。 */
    private static final int MAX_CODEPOINTS = 1024;

    /**
     * 逐字绘制路径用的<b>加粗样式常量</b> —— 类加载期建一次，热路径复用（零分配）。
     *
     * <p>为什么可以复用：{@code Style} 是<b>不可变</b>的（所有 {@code withXxx} 都返回新实例），
     * 且它<b>不带颜色</b>（{@code getColor()==null}）⇒ {@code StringRenderOutput.accept} 里
     * {@code TextColor textcolor = style.getColor(); int j = textcolor != null ? textcolor.getValue() : color;}
     * 会回落到调用方传进来的 {@code color} 参数 ⇒ <b>加粗与「颜色走参数」这两个机制不冲突</b>
     * （取证：{@code javap -c Font$StringRenderOutput.accept} 的 {@code 71 getColor / 79 ifnull 149}）。
     */
    private static final Style BOLD_STYLE = Style.EMPTY.withBold(Boolean.TRUE);

    private static final AtomicBoolean HOOK_LOGGED = new AtomicBoolean(false);

    // ================================================================== 诊断

    /**
     * 打一行 {@code [SHANHAI-SPEC] font_style_mixin hooked …} —— <b>全工程唯一</b>的「注入成功」判据。
     *
     * <p>🔴 <b>这行是"注入成功"和"算错了"的唯一分界线</b>：屏幕上「没有特效」有两种原因 ——
     * 混入没生效、或者算色算错了。两者肉眼完全一样（本项目血账：两种失败长得一样 ⇒ 那次运行信息量为 0）。
     * 有了这行：<b>日志里有它 = 注入好了（问题在算色）；日志里没它 = 注入没生效</b>。
     *
     * <p>⚠️ 它在<b>第一次真正被调用</b>时才打（不是类加载时）：这样它额外证明了
     * 「注入点真的被执行了」，而不只是「配置被读到了」。
     * ⚠️ <b>专用服务端永远不会打这行</b>：本混入列在 {@code shanhai.mixin.json} 的 {@code client} 数组里，
     * 无头专服不加载 client 混入 ⇒ 这行<b>只能在客户端日志里找</b>。
     */
    private static void logHookedOnce() {
        if (HOOK_LOGGED.compareAndSet(false, true)) {
            ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] font_style_mixin hooked methods={} paletteStyles={} bold={} restore={}",
                    HOOK_METHODS, ShanhaiTextPalette.styleCount(), BOLD_MARKER, RESTORE_MARKER);
        }
    }

    /** 异常只在前两次打全，之后限流（5 秒一行 + suppressed 计数）—— 渲染线程不许被日志拖死。 */
    private static final long ERR_MIN_GAP_MS = 5000L;
    private static final java.util.concurrent.atomic.AtomicLong LAST_ERR_MS =
            new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE / 2);
    private static final java.util.concurrent.atomic.AtomicLong ERR_SUPPRESSED =
            new java.util.concurrent.atomic.AtomicLong();

    private static void logError(String where, Throwable t) {
        long now = System.currentTimeMillis();
        long last = LAST_ERR_MS.get();
        if (now - last < ERR_MIN_GAP_MS || !LAST_ERR_MS.compareAndSet(last, now)) {
            ERR_SUPPRESSED.incrementAndGet();
            return;
        }
        long suppressed = ERR_SUPPRESSED.getAndSet(0L);
        // 🔴 刻意用 warn + 只带异常类名与 message：渲染线程上打整条堆栈会让卡顿变成雪崩。
        ShanhaiMod.LOGGER.warn("[SHANHAI-SPEC] font_style_error where={} ex={} msg={} suppressed={}",
                where, t.getClass().getName(), String.valueOf(t.getMessage()), suppressed);
    }

    // ================================================================== 可复用状态（ThreadLocal）

    /** 渲染重入标记：我们自己发起的逐字 {@code drawInBatch} 会再次进来，必须立刻放行，不能递归。 */
    private static final ThreadLocal<boolean[]> IN_RENDER = ThreadLocal.withInitial(() -> new boolean[1]);

    /**
     * <b>分段绘制重入标记</b>（2026-11-01，A 案）—— 见类注释 §4.7.2。
     *
     * <p>🔴 <b>为什么除了 {@link #IN_RENDER} 还要它</b>：分段绘制里"原版段"那一半<b>必须</b>调
     * {@code self.drawInBatch(String,…)}，而那正是本混入的注入点之一 ⇒ <b>一定</b>会再进
     * {@link #renderString}。{@code IN_RENDER} 本来就能挡（那时它是 true），但那是<b>间接</b>保证：
     * 一旦以后有人在 {@code drawSegments} 里提前复位 {@code IN_RENDER}（或从外面直接调 drawSegments），
     * 就会变成"分段里再分段"的<b>静默递归</b>（不崩、只是每次绘制花 N 倍时间，且日志上看不出来）。
     * 所以这里给分段绘制一个<b>自己的、只由 drawSegments 置位</b>的旗标，
     * 内层一律<b>直接放行给原版</b>（返回 {@code null} = 不取消原版）。
     *
     * <p>⚠️ 检查点放在<b>零分配探针之后</b>：那种"没有 {@code &} 的文本"（游戏里 99%+ 的 String 绘制）
     * 在探针那里就走了，<b>不会</b>为这个旗标付一次 {@code ThreadLocal} 开销。
     */
    private static final ThreadLocal<boolean[]> IN_SEGMENTS = ThreadLocal.withInitial(() -> new boolean[1]);

    /** 宽度重入标记（{@code width(cleanText)} 会再次进来，同理）。 */
    private static final ThreadLocal<boolean[]> IN_WIDTH = ThreadLocal.withInitial(() -> new boolean[1]);

    /** 「这次调用已经画出去东西了吗」—— 异常兜底时决定「取消原渲染」还是「放回原渲染」。 */
    private static final ThreadLocal<boolean[]> STARTED = ThreadLocal.withInitial(() -> new boolean[1]);

    /** 零分配 {@code &} 探针（复用实例，不捕获变量 ⇒ 不是 lambda 对象）。 */
    private static final ThreadLocal<AmpProbe> PROBE = ThreadLocal.withInitial(AmpProbe::new);

    /** 零分配字符串收集器。 */
    private static final ThreadLocal<Collector> COLLECTOR = ThreadLocal.withInitial(Collector::new);

    /** 「上一帧那条 FCS → 解析结果」的记忆。 */
    private static final ThreadLocal<FcsMemo> MEMO = ThreadLocal.withInitial(FcsMemo::new);

    /** 单次绘制路径的 {@code Style[]} 暂存（容量按色板长度增长，稳态不再分配）。 */
    private static final ThreadLocal<StyleScratch> STYLES = ThreadLocal.withInitial(StyleScratch::new);

    /** 逐字绘制路径的复用 acceptor。 */
    private static final ThreadLocal<CharSeq> CHAR_SEQ = ThreadLocal.withInitial(CharSeq::new);

    /** 单次绘制路径的复用 FCS。 */
    private static final ThreadLocal<LineSeq> LINE_SEQ = ThreadLocal.withInitial(LineSeq::new);

    /**
     * <b>子 FCS</b>（FCS 层的原版段用，§4.8）—— 只把 {@code [from,to)} 这段下标的
     * {@code (codepoint, Style)} 原样转发出去。
     *
     * <p>⚠️ 复用是安全的，理由与 {@link CharSeq} 完全相同：{@code Font.drawInBatch} 在<b>同一个调用栈里</b>
     * 把 {@code accept} 走完，不会把 FCS 存下来等下次用；而本类的绘制路径是<b>逐段串行</b>的
     * （{@code drawFcsSegments} 里一段画完才轮下一段），没有嵌套持有。
     */
    private static final ThreadLocal<RunSeq> RUN_SEQ = ThreadLocal.withInitial(RunSeq::new);

    // ================================================================== 入口：绘制

    /**
     * {@code Font.drawInBatch(FormattedCharSequence, …)} 的接管实现。
     *
     * @return {@code null} = <b>我们不管这条</b>（调用方必须原样交回原版渲染）；
     *         非 null = 已画完，值为 x 方向推进量
     */
    public static Integer renderFcs(Font self,
                                    FormattedCharSequence text,
                                    float x, float y, int color, boolean shadow,
                                    Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
                                    int packedLight, int packedOverlay) {
        logHookedOnce();
        if (text == null || self == null) {
            return null;
        }
        boolean[] inRender = IN_RENDER.get();
        if (inRender[0]) {
            return null;
        }
        // 异常兜底的判据必须在【整个 try 之外】清零：否则异常若发生在本方法前段，
        // STARTED 里留着的是上一次调用（可能已经画过字）的陈旧值 ⇒ 会误判成"已经画了"。
        final boolean[] started = STARTED.get();
        started[0] = false;
        try {
            // ---- 第 1 步：零分配探针（99%+ 的调用在这里就返回了） ----
            if (!probeHasAmp(text)) {
                return null;
            }
            // 分段绘制里我们自己发出的 String/FCS 绘制会再进来 —— 直接放行给原版（见 §4.7.2）。
            if (IN_SEGMENTS.get()[0]) {
                return null;
            }

            // ---- 第 1.5 步：FCS 带样式 ⇒ 按 &$…&r 切段、样式原样转发（§4.8）----
            //  § 在 Component→FCS 那一步已被折成 Style 并从文本剥掉 ⇒ 串里没有 § ⇒
            //  "按 § 切段"在这条通道上恒假。这里换成"按 &$…&r 切段"：
            //  原版段用【子 FCS】把每个字形的 Style 原样转发回去，绝不转回 String。
            final GlyphBuffer glyphs = collectGlyphs(text);
            if (glyphs == null) {
                // 码点数超上限（与 extract 同一条判据）：与改前一样交回原版。
                return null;
            }
            if (glyphs.anyStyled) {
                final FcsPlan plan = planOf(text, glyphs);
                if (plan != null) {
                    inRender[0] = true;
                    try {
                        return drawFcsSegments(self, plan, glyphs, x, y, color, shadow, matrix, buffer, mode,
                                packedLight, packedOverlay, started);
                    } finally {
                        inRender[0] = false;
                    }
                }
                // 🔴 带样式但切不出我们的段（例如 `&x$…-` 这种非 `&$` 形态 / 段边界映射不上）
                //    ⇒ 继续走改前那条老路。宁可保留那个已知的"前缀/后缀被刷白"，
                //    也不在这里引入"我们没验证过的切分"。
            }

            // ---- 第 2 步：拿解析结果（先按实例记忆，再按字符串缓存） ----
            //  ⚠️ 上面 collectGlyphs 已经把 memo 填好了 ⇒ 这里恒命中，不会为同一串再走一遍。
            ShanhaiTextParser.Parsed parsed = lookup(text);
            if (parsed == null) {
                return null;
            }
            if (!parsed.ours()) {
                // ---- 混排行（2026-11-01，A 案 §4.7）：§ 段交原版、&$ 段交我们，逐段推进 ----
                List<ShanhaiTextParser.Segment> segs = lookupSegments(text);
                if (segs == null) {
                    return null;
                }
                inRender[0] = true;
                try {
                    return drawSegments(self, segs, x, y, color, shadow, matrix, buffer, mode,
                            packedLight, packedOverlay, started);
                } finally {
                    inRender[0] = false;
                }
            }

            // ---- 第 3 步：画 ----
            inRender[0] = true;
            try {
                if (parsed.palette() == null) {
                    return drawDegraded(self, parsed, x, y, color, shadow, matrix, buffer, mode,
                            packedLight, packedOverlay, started);
                }
                return drawStyled(self, parsed, x, y, color, shadow, matrix, buffer, mode,
                        packedLight, packedOverlay, started);
            } finally {
                inRender[0] = false;
            }
        } catch (Throwable t) {
            logError("drawInBatch", t);
            // 🔴 「安静地不生效」：已经画出去一半就别让原版再画一遍（会重影）；
            //    一个字都还没画就放回去让原版整行照常画（会是白字，但至少不消失、不重影）。
            return STARTED.get()[0] ? Integer.valueOf(Math.round(x)) : null;
        }
    }

    /**
     * {@code Font.drawInBatch(String, …, boolean)}（<b>11 参那个</b>）的接管实现 ——
     * <b>「裸字符串」那一条家族的入口</b>（2026-10-01 新增，见类注释 §6）。
     *
     * <p>为什么必须补这条：一个 {@code Component} 只要被调用方 {@code .getString()} 拍平成 String
     * （实机反例：{@code GTRecipeWidget:660} → {@code LabelWidget.<init>(IILjava/lang/String;)V}），
     * 它就落回这条<b>不走 FCS</b> 的路 ⇒ 只注 FCS 的版本接管不到 ⇒ 码原样画出来（用户图1）。
     *
     * <p>与 {@link #renderFcs} 的三处差异（都是有意的）：
     * <ol>
     *   <li><b>探针按 String 做</b>（{@code indexOf('&')}）—— 这一步不产出任何对象；</li>
     *   <li><b>解析直接走 {@link ShanhaiTextParser#parse(String)}</b> —— String 本来就是缓存表的 key，
     *       不需要 {@link #extract} 那一层；</li>
     *   <li><b>重入标记同一条</b>（{@link #IN_RENDER}）：{@link #drawDegraded} 的退化成字与
     *       {@link #drawStyled} 的 {@code prefix} 都是<b>用 String 重画</b>的，必须立刻放行，
     *       否则 ① 递归 ② 把该由原版解析的 {@code §} 抢掉。</li>
     * </ol>
     *
     * @return {@code null} = <b>我们不管这条</b>（调用方必须原样交回原版渲染）；
     *         非 null = 已画完，值为 x 方向推进量
     */
    public static Integer renderString(Font self,
                                       String text,
                                       float x, float y, int color, boolean shadow,
                                       Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
                                       int packedLight, int packedOverlay) {
        logHookedOnce();
        if (text == null || text.isEmpty() || self == null) {
            return null;
        }
        boolean[] inRender = IN_RENDER.get();
        if (inRender[0]) {
            return null;
        }
        // ---- 第 1 步：零分配探针（99%+ 的调用在这里就返回了） ----
        if (text.indexOf('&') < 0) {
            return null;
        }
        // 分段绘制里我们自己发出的 String 绘制会再进来 —— 直接放行给原版（见 §4.7.2）。
        if (IN_SEGMENTS.get()[0]) {
            return null;
        }
        // 异常兜底的判据与 renderFcs 同口径：必须在整个 try 之外清零。
        final boolean[] started = STARTED.get();
        started[0] = false;
        try {
            // ---- 第 2 步：拿解析结果（String 直接就是缓存 key） ----
            ShanhaiTextParser.Parsed parsed = ShanhaiTextParser.parse(text);
            if (parsed == null) {
                return null;
            }
            if (!parsed.ours()) {
                // ---- 混排行（2026-11-01，A 案 §4.7）：§ 段交原版、&$ 段交我们，逐段推进 ----
                List<ShanhaiTextParser.Segment> segs = ShanhaiTextParser.mixedSegments(text);
                if (segs == null) {
                    return null;
                }
                inRender[0] = true;
                try {
                    return drawSegments(self, segs, x, y, color, shadow, matrix, buffer, mode,
                            packedLight, packedOverlay, started);
                } finally {
                    inRender[0] = false;
                }
            }

            // ---- 第 3 步：画 ----
            inRender[0] = true;
            try {
                if (parsed.palette() == null) {
                    return drawDegraded(self, parsed, x, y, color, shadow, matrix, buffer, mode,
                            packedLight, packedOverlay, started);
                }
                return drawStyled(self, parsed, x, y, color, shadow, matrix, buffer, mode,
                        packedLight, packedOverlay, started);
            } finally {
                inRender[0] = false;
            }
        } catch (Throwable t) {
            logError("drawInBatchString", t);
            // 与 renderFcs 同一条兜底：画了一半就别重复画（重影），一个字没画就交回原版。
            return STARTED.get()[0] ? Integer.valueOf(Math.round(x)) : null;
        }
    }

    // ================================================================== 入口：宽度

    /**
     * {@code Font.width(FormattedCharSequence)} 的修正实现。
     *
     * @return {@code null} = 不管；非 null = 该用的宽度
     */
    public static Integer widthFcs(Font self, FormattedCharSequence text) {
        logHookedOnce();
        if (text == null || self == null) {
            return null;
        }
        if (IN_WIDTH.get()[0]) {
            return null;
        }
        if (!probeHasAmp(text)) {
            return null;
        }
        return widthOf(self, extract(text));
    }

    /** {@code Font.width(FormattedText)} 的修正实现。 */
    public static Integer widthFormattedText(Font self, net.minecraft.network.chat.FormattedText text) {
        logHookedOnce();
        if (text == null || self == null) {
            return null;
        }
        if (IN_WIDTH.get()[0]) {
            return null;
        }
        String raw;
        try {
            raw = text.getString();
        } catch (Throwable t) {
            logError("width.getString", t);
            return null;
        }
        if (!ShanhaiTextParser.containsCode(raw)) {
            return null;
        }
        return widthOf(self, raw);
    }

    /** {@code Font.width(String)} 的修正实现。 */
    public static Integer widthString(Font self, String raw) {
        logHookedOnce();
        if (raw == null || raw.isEmpty() || self == null) {
            return null;
        }
        if (IN_WIDTH.get()[0]) {
            return null;
        }
        if (!ShanhaiTextParser.containsCode(raw)) {
            return null;
        }
        return widthOf(self, raw);
    }

    /**
     * 宽度修正本体。
     *
     * <p>为什么要它：{@code &$?body_golden-} 是 <b>16 个「可见字符」</b>，
     * 不修正的话聊天栏居中 / tooltip 居中 / {@code drawCenteredString} 都会把这一行算「胖」，
     * 文字会明显缩进或错位（方案 md §7.2 验收第 6 条）。
     * 修法照上游：算出 {@code cleanText} 的真实宽度顶回去。
     */
    private static Integer widthOf(Font self, String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            ShanhaiTextParser.Parsed parsed = ShanhaiTextParser.parse(raw);
            if (!parsed.ours()) {
                // ---- 混排行（2026-11-01，A 案）：逐段量宽，见 §4.7.4 ----
                return widthOfSegments(self, raw);
            }
            boolean[] inWidth = IN_WIDTH.get();
            inWidth[0] = true;
            try {
                int w = self.width(parsed.cleanText());
                if (w <= 0) {
                    return null;
                }
                if (parsed.bold()) {
                    // 🔴 Font.width(String) 走 Style.EMPTY ⇒ 量不到加粗，必须自己补（见类注释 §4.5）。
                    //    ceil(a + n) == ceil(a) + n（n 是整数）⇒ 「先取整再加码点数」与「先加再取整」等价，
                    //    所以这里可以直接在 Font 已经 ceil 过的结果上加。
                    // 2026-10-31：只补 body 的码点数 —— 加粗作用域 == 染色作用域 == 到 body 为止（suffix 不加粗）；
                    //    cleanText 已经含 suffix，它的宽度由上面 self.width(cleanText) 直接量到。
                    w += ShanhaiTextParser.boldAdvance(parsed.body());
                }
                return Integer.valueOf(w);
            } finally {
                inWidth[0] = false;
            }
        } catch (Throwable t) {
            logError("width", t);
            return null;
        }
    }

    /**
     * <b>混排行的宽度</b>（2026-11-01，A 案）—— 逐段量，再相加。
     *
     * <p><b>两段的口径不同（故意的）</b>：
     * <ul>
     *   <li><b>原版段</b>：直接 {@code self.width(seg.text())}。{@code Font.width(String)} 自己就解析
     *       {@code §}（{@code javap -c StringSplitter.stringWidth(String)} → {@code StringDecomposer.iterateFormatted}），
     *       而且 <b>{@code §l} 的加粗也算在里面</b>（{@code WidthProvider.getWidth(int, Style)} →
     *       {@code GlyphInfo.getAdvance(Z)}）⇒ <b>量"与"画"完全同口径</b>，不用补；</li>
     *   <li><b>我们段</b>：{@code self.width(body)} <b>再补 {@link ShanhaiTextParser#boldAdvance} 的码点数</b>
     *       —— 因为 {@code width(String)} 走 {@code Style.EMPTY}，它<b>不知道</b>我们这一段要加粗
     *       （与 {@link #widthOf} 老路同一条补法、同一个理由，见类注释 §4.5）。</li>
     * </ul>
     * 🔴 <b>为什么"每段各自 ceil"是允许的</b>（这是本方案唯一一处"数字可能不严格相等"的地方）：
     * {@code Font.width(String)} 返回 {@code Mth.ceil(段宽)}，逐段 ceil 之和 与 整行 ceil 之间可以差
     * <b>≤ 段数-1 px</b>（真实 MC 的字形推进量都是整数 px ⇒ 实际差值恒为 <b>0</b>；
     * 自证读数见 {@code temp\segtext\分段兼容.md} 的「宽度」一节）。
     * 而"逐段 ceil"是<b>必须</b>的：居中要用的是"这一行到底占几个整数像素"，
     * 逐段画出来时每一段的末点也被 {@code drawInBatch} 各自取整了 ⇒ 量的时候逐段取整才是同口径。
     */
    private static Integer widthOfSegments(Font self, String raw) {
        List<ShanhaiTextParser.Segment> segs = ShanhaiTextParser.mixedSegments(raw);
        if (segs == null) {
            return null;
        }
        boolean[] inWidth = IN_WIDTH.get();
        inWidth[0] = true;
        try {
            int w = 0;
            for (int i = 0; i < segs.size(); i++) {
                ShanhaiTextParser.Segment seg = segs.get(i);
                int sw = self.width(seg.text());
                if (seg.ours() && seg.bold()) {
                    sw += ShanhaiTextParser.boldAdvance(seg.text());
                }
                w += sw;
            }
            return w <= 0 ? null : Integer.valueOf(w);
        } finally {
            inWidth[0] = false;
        }
    }

    // ================================================================== 混排分段绘制（A 案）

    /**
     * <b>混排行绘制</b>：<b>顺序画各段、逐段推进 x</b>（2026-11-01，A 案；规则见
     * {@link ShanhaiTextParser} 类注释 §7，理由见本类 §4.7）。
     *
     * <p>🔴 <b>递归防护</b>：本方法一进来就把 {@link #IN_SEGMENTS} 置位、出去时在 {@code finally} 里复位。
     * 于是里面那次 {@code self.drawInBatch(String,…)}（原版段）<b>再进混入</b>时，
     * {@link #renderString} 会在探针之后立刻返回 {@code null} = <b>不取消原版</b> ⇒ 由原版自己画。
     * 没有这个旗标就会「分段里再分段」，而且<b>不会崩</b>（所以只能靠旗标，不能靠崩溃暴露）。
     */
    private static int drawSegments(Font self, List<ShanhaiTextParser.Segment> segs,
                                    float x, float y, int color, boolean shadow,
                                    Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
                                    int packedLight, int packedOverlay, boolean[] started) {
        // 整行一个 now：与 drawStyled 的"同一帧"约定一致（段与段之间不许相位自己抖）。
        final long now = System.currentTimeMillis();
        final boolean[] inSegments = IN_SEGMENTS.get();
        final boolean outer = inSegments[0];
        inSegments[0] = true;
        try {
            float curX = x;
            for (int i = 0; i < segs.size(); i++) {
                ShanhaiTextParser.Segment seg = segs.get(i);
                if (seg.ours()) {
                    curX = drawOursSegment(self, seg, now, curX, y, color, shadow, matrix, buffer, mode,
                            packedLight, packedOverlay, started);
                } else {
                    // 🔴 原版段：<b>必须是会解析 § 的那个 String 重载</b>（10 参那个只转调到 11 参，
                    //    两条都认 §；这里调 10 参 = 生产代码里最常用的那个形状）。
                    //    返回值 = (int)该段末点 + (shadow?1:0) ⇒ 先把阴影补偿还原掉再当推进量（见 §9）。
                    final float adv = self.drawInBatch(seg.text(), curX, y, color, shadow, matrix, buffer,
                            mode, packedLight, packedOverlay);
                    curX = noShadowBonus(adv, shadow);
                    started[0] = true;
                }
            }
            return Math.round(curX);
        } finally {
            inSegments[0] = outer;
        }
    }

    /**
     * <b>把 {@code Font.drawInBatch} 返回值里的阴影补偿还原掉</b>（2026-11-01 §9，段内逐段推进专用）。
     *
     * <h2>为什么必须还原</h2>
     * {@code drawInternal} 的返回值是 {@code (int)末点 + (shadow ? 1 : 0)}（{@code javap -c} 原文见 §4.6.2）。
     * 那个 {@code +1} 是<b>「整行画完之后调用方该往前挪多少」</b>的补偿，<b>每调用一次就出现一次</b>；
     * 而段与段之间要传的是<b>字形的真实末点</b>，不是"再挪 1px 之后的位置"。
     * 分段绘制是 <b>N 次调用</b> ⇒ 不还原就是"每段多吃 1px"：不只行末变长，
     * <b>段间距也会被撑开 1px</b>（后一段的起点就偏移了）。实测同一行 31 → 33。
     *
     * <h2>🔴 为什么连最后一段也还原（与"除最后一段外"的直觉不同，如实交代）</h2>
     * <ul>
     *   <li>验收口径是「<b>两种 shadow 下 x 终点都等于同一串去码后的 {@code font.width}</b>」；</li>
     *   <li>保留最后一段的 {@code +1} ⇒ 终点 <b>32</b>（{@code font.width} = 31）⇒ 差 1，<b>不达标</b>；</li>
     *   <li>"我们自己的段照旧"也不行：{@link #drawWholeLine} 返回的<b>也是</b>
     *       {@code (int)末点 + (shadow?1:0)}（它内部就是一次 {@code drawInBatch}）⇒ 同样 +1，
     *       而且会让<b>后面那一段的起点</b>偏移 1px（实测：只还原原版段 ⇒ <b>33</b>，差 2）；</li>
     *   <li>⇒ 只有<b>每次 {@code drawInBatch} 调用的 {@code +1} 都还原</b>，两种模式才给同一个数
     *       （实测都是 <b>31</b>，逐段 9 / 20 / 31）。</li>
     * </ul>
     * ⚠️ 这条只作用于<b>混排分段</b>这条新路径；单段老路（{@link #drawStyled} 的
     * prefix→body→suffix 接续、以及 {@link #drawPerChar} 的 {@code stripShadowBonus=false} 调用）
     * <b>一个字都没动</b>（回归底线：1052 条文本的 x 推进量必须逐位不变）。
     */
    private static float noShadowBonus(float advance, boolean shadow) {
        return shadow ? advance - 1.0f : advance;
    }

    /**
     * 画<b>我们的一段</b> —— 与 {@link #drawStyled} 同一套算色/绘制，只是"段"取代了"整行"。
     *
     * <p>三处与 {@link #drawStyled} 的差异，都是有意的：
     * <ol>
     *   <li><b>没有 prefix / suffix</b>：分段模型里它们各自是<b>独立的原版段</b>（由 {@code drawSegments} 画）
     *       ⇒ 本方法只负责这一段正文；</li>
     *   <li><b>{@code now} 由外面传进来</b>：整行一个时间戳（见 {@code drawSegments}）；</li>
     *   <li><b>退化段（{@code style == null}）走"剥码后按普通文字画"</b>，与 {@link #drawDegraded} 同一句判据。</li>
     * </ol>
     */
    private static float drawOursSegment(Font self, ShanhaiTextParser.Segment seg, long now,
                                         float curX, float y, int color, boolean shadow,
                                         Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
                                         int packedLight, int packedOverlay, boolean[] started) {
        final String body = seg.text();
        if (body.isEmpty()) {
            return curX;
        }
        final ShanhaiTextPalette.Style style = seg.style();
        if (style == null) {
            // 退化 A：样式名不认识 ⇒ 剥码后按普通文字、用调用方原色画（与 drawDegraded 一致）。
            // 🔴 这一段<b>不含 §</b>（解析期就把含 § 的段退化成原版段了）⇒ 走 String 重载不会丢样式。
            started[0] = true;
            final int adv = self.drawInBatch(body, curX, y, color, shadow, matrix, buffer, mode,
                    packedLight, packedOverlay);
            return noShadowBonus(adv, shadow);
        }
        final int[] palette = style.rgb();
        final int len = palette.length;
        final int bodyLen = body.length();
        final long interval = ShanhaiTextPalette.effectiveInterval(style.isBody(), bodyLen);
        final long masked = ShanhaiTextPalette.maskedPhase(now, interval, len);
        final int intPhase = ShanhaiTextPalette.intPhaseOf(masked, interval);
        final double frac = ShanhaiTextPalette.fracOf(masked, interval);
        if (seg.hasMotion()) {
            // 逐字路径每次 drawInBatch 都带一个 (shadow?1:0) ⇒ 开 stripShadowBonus 让它在段内就还原掉。
            return drawPerChar(self, body, palette, seg.bold(), seg.glitch(), seg.floatX(), true,
                    intPhase, frac, now, curX, y, color, shadow, matrix, buffer, mode,
                    packedLight, packedOverlay, started);
        }
        return noShadowBonus(drawWholeLine(self, body, palette, len, bodyLen, seg.bold(), intPhase, frac,
                curX, y, color, shadow, matrix, buffer, mode, packedLight, packedOverlay, started), shadow);
    }

    // ================================================================== 两条绘制路径

    /**
     * <b>退化绘制</b>：样式名没移植（{@code palette == null}）⇒ 剥掉码、按普通文字画，<b>不染任何颜色</b>。
     *
     * <p>用 {@code Font.drawInBatch(String, …)} 一次画完 —— 它是<b>另一个重载</b>
     * （取证：{@code javap} 显示它转调 {@code drawInBatch(String,…,boolean)}，<b>不经过 FCS 那个重载</b>）。
     * <p>🔴 <b>2026-10-01 更正</b>：这段注释原来接下来写的是"所以不会再次进入本混入" ——
     * <b>那句话现在是错的</b>：String 家族已经补了注入点（{@link #renderString} 走 {@code m_272078_}），
     * 这次调用<b>会</b>再次进到那里。⇒ 由 {@link #IN_RENDER} 重入标记当场放行（返回 {@code null} 不取消原版），
     * <b>交给原版画</b>——这是刻意的：剥完码的正文里可能有 {@code §}，而 {@code §} 只在 String 路径上被原版解析。
     */
    private static int drawDegraded(Font self, ShanhaiTextParser.Parsed parsed,
                                    float x, float y, int color, boolean shadow,
                                    Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
                                    int packedLight, int packedOverlay, boolean[] started) {
        started[0] = true;
        return self.drawInBatch(parsed.cleanText(), x, y, color, shadow, matrix, buffer, mode,
                packedLight, packedOverlay);
    }

    /**
     * <b>正文绘制</b>：{@code prefix} 用调用方给的颜色原样画，{@code body} 用色板逐字流动色画，
     * {@code suffix}（{@code &r} 之后那段，2026-10-31）再用调用方原色接着画。
     *
     * <p>⚠️ 相位（{@code intPhase} / {@code frac}）在<b>循环外算一次</b>，
     * 行内所有字符共用 —— 这是「同一帧」的定义；每个字符各读一次时钟会让相位自己抖。
     *
     * <p>🔴 <b>颜色作用域</b>（本方法的结构性保证，别拆掉）：
     * {@code prefix} → 调用方色；{@code body} → 色板；{@code suffix} → 调用方色。
     * 三段各自 {@code drawInBatch}，<b>没有任何一段会把颜色写回 Font 或全局状态</b>
     * （证据见类注释 §4.6.1）⇒ 染色字形集合恒等于 {@code body} 的字符。
     */
    private static int drawStyled(Font self, ShanhaiTextParser.Parsed parsed,
                                  float x, float y, int color, boolean shadow,
                                  Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
                                  int packedLight, int packedOverlay, boolean[] started) {
        final String body = parsed.body();
        final int[] palette = parsed.palette();
        final int len = palette.length;
        final int bodyLen = body.length();
        final long now = System.currentTimeMillis();

        // 相位：三项之一「时间流动」。照抄 getDynamicColor 第 622-628 行。
        final long interval = ShanhaiTextPalette.effectiveInterval(parsed.isBody(), bodyLen);
        final long masked = ShanhaiTextPalette.maskedPhase(now, interval, len);
        final int intPhase = ShanhaiTextPalette.intPhaseOf(masked, interval);
        final double frac = ShanhaiTextPalette.fracOf(masked, interval);

        // & 之前的文字（我们的 34+6 行里这一般是空的，但机制照上游保留）
        float curX = x;
        if (!parsed.prefix().isEmpty()) {
            curX = self.drawInBatch(parsed.prefix(), curX, y, color, shadow, matrix, buffer, mode,
                    packedLight, packedOverlay);
            started[0] = true;
        }

        // ---- 染色正文：画到 body 的最后一个字形为止（绝不越过 body 的串边界）----
        // 返回值一律是【真实的绘制末点】（float），交给下面的还原段接着画。
        final float endX;
        if (parsed.hasMotion()) {
            // 单段老路：stripShadowBonus=false ⇒ 与改前逐位相同（回归底线），见 §9。
            endX = drawPerChar(self, body, palette, parsed.bold(), parsed.glitch(), parsed.floatX(), false,
                    intPhase, frac, now, curX, y,
                    color, shadow, matrix, buffer, mode, packedLight, packedOverlay, started);
        } else {
            endX = drawWholeLine(self, body, palette, len, bodyLen, parsed.bold(), intPhase, frac, curX, y,
                    color, shadow, matrix, buffer, mode, packedLight, packedOverlay, started);
        }

        // ---- 还原（2026-10-31 &r，见类注释 §4.6）----
        // `&r` 之后 = 调用方原色、零样式。suffix 为空（= 没有 &r）时这一段整体跳过
        // ⇒ 一个多的字形都不画、一次多的 drawInBatch 都不发 ⇒ 输出与 &r 出现之前逐位相同。
        final String suffix = parsed.suffix();
        if (!suffix.isEmpty()) {
            // 🔴 防御：按【原始字符串整段】交给 String 重载 —— 不拆码点、不建 Style、不加粗、不染色。
            //    走 String 重载还有一个附带好处：它本来就会解析 §（若真有 § 从 parse 的铁律那里漏进来，
            //    也不会被当成普通字符画出来）；而正常路径上 suffix 含 § 时整条早已 ours=false。
            final float after = self.drawInBatch(suffix, endX, y, color, shadow, matrix, buffer, mode,
                    packedLight, packedOverlay);
            started[0] = true;
            return Math.round(after);
        }
        return Math.round(endX);
    }

    /**
     * <b>逐字绘制</b>（有位移效果时）。
     *
     * <p>逐字循环里<b>一个 {@code new} 都没有</b>：
     * 复用 {@link CharSeq} 当 FCS、样式恒为<b>两个类加载期常量之一</b>（{@code Style.EMPTY} 或
     * {@link #BOLD_STYLE}）、颜色走 {@code drawInBatch} 的
     * {@code color} 参数 —— 取证：{@code Font$StringRenderOutput.accept} 的字节码里
     * {@code TextColor textcolor = style.getColor(); int j = textcolor != null ? textcolor.getValue() : color;}
     * ⇒ <b>样式没带色时就用参数色</b>，所以「每字一个 Style」不是必需的。
     *
     * <p>{@code currentX = adv - xOff} 照抄上游 {@code WobbleFontMixin.java:633}：
     * {@code drawInBatch} 的返回值已经把 xOff 算进去了，减掉才是「这个字真正的推进量」。
     *
     * <p>⚠️ 加粗会让 {@code drawInBatch} 的返回值每字多 1 px（{@code GlyphInfo.getAdvance(Z)} 实证），
     * 而 {@code xOff} 里<b>没有</b>这一项 ⇒ {@code adv - xOff} 仍然正确
     * （加粗是推进量的一部分，本来就该被算进 curX）。
     */
    private static float drawPerChar(Font self, String body, int[] palette, boolean bold,
                                     boolean glitch, boolean floatX, boolean stripShadowBonus,
                                     int intPhase, double frac, long now,
                                     float curX, float y, int color, boolean shadow,
                                     Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
                                     int packedLight, int packedOverlay, boolean[] started) {
        final CharSeq seq = CHAR_SEQ.get();
        final Style charStyle = bold ? BOLD_STYLE : Style.EMPTY;
        final int bodyLen = body.length();
        int index = 0;
        for (int i = 0; i < bodyLen; ) {
            final int cp = body.codePointAt(i);

            int cc = ShanhaiTextPalette.colorForSlot(palette, index, intPhase, frac);

            float xOff = 0.0f;
            float yOff = 0.0f;
            if (floatX) {
                xOff += ShanhaiTextParser.waveX(index, now);
            }
            if (glitch) {
                xOff += ShanhaiTextParser.glitchX(index, now);
                yOff += ShanhaiTextParser.glitchY(index, now);
                cc = ShanhaiTextParser.applyGlitchColor(cc, index, now);
            }

            seq.prepare(cp, charStyle);
            float adv = self.drawInBatch(seq, curX + xOff, y + yOff, cc, shadow,
                    matrix, buffer, mode, packedLight, packedOverlay);
            // ⚠️ 阴影补偿：逐字路径是【每字一次 drawInBatch】⇒ 每次返回值都含一个 (shadow?1:0)。
            //    stripShadowBonus=true（只在混排分段里）= 把它还原掉，让段末点 == 字形真实末点（见 §9）；
            //    单段老路传 false ⇒ 行为与改前逐位相同（回归底线）。
            curX = adv - xOff - (stripShadowBonus && shadow ? 1.0f : 0.0f);
            started[0] = true;

            index++;
            i += Character.charCount(cp);
        }
        // 返回【float 的真实末点】，由 drawStyled 统一 Math.round（suffix 为空时与改前的
        // `return Math.round(curX)` 逐位相同；有 suffix 时它才是接着画的位置）。
        return curX;
    }

    /**
     * <b>单次绘制</b>（只有颜色、没有位移效果时）。这是 34 条物品名 + 横幅 4 行的路径。
     *
     * <p>🔴「逐字循环零分配」在这里是这样做到的：因为
     * {@code idx1 = (intPhase + charIndex) % len}，所以<b>整行的颜色只有 {@code len} 种可能</b>
     * （{@code len} = 色板长度，最多 40），且与正文长度无关
     * ⇒ 在循环<b>外</b>按「槽位」预计算 {@code Style[]}，循环里只做 {@code styles[k % m]} 下标。
     */
    private static float drawWholeLine(Font self, String body, int[] palette, int len, int bodyLen,
                                       boolean bold, int intPhase, double frac,
                                       float curX, float y, int color, boolean shadow,
                                       Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
                                       int packedLight, int packedOverlay, boolean[] started) {
        if (bodyLen == 0) {
            return curX;
        }
        // 需要的槽位数 = min(正文长度, 色板长度)：正文更长时颜色按色板回绕，更短时用前 bodyLen 个槽。
        final int slots = Math.min(bodyLen, len);
        final Style[] scratch = STYLES.get().ensure(slots);
        for (int k = 0; k < slots; k++) {
            int rgb = ShanhaiTextPalette.colorForSlot(palette, k, intPhase, frac);
            Style s = Style.EMPTY.withColor(TextColor.fromRgb(rgb));
            if (bold) {
                // 加粗与颜色挂在同一个 Style 上 —— 不多一个对象（见类注释 §4.5）。
                s = s.withBold(Boolean.TRUE);
            }
            scratch[k] = s;
        }

        final LineSeq seq = LINE_SEQ.get();
        seq.prepare(body, scratch, slots);
        started[0] = true;
        // drawInBatch 返回 int（= (int)真末点 + (shadow?1:0)，见类注释 §4.6.2 的 javap）；
        // 这里 int→float 是精确的（|值| < 2^24），drawStyled 再 Math.round 回同一个 int
        // ⇒ suffix 为空时返回值与改前 `return self.drawInBatch(…)`（int）逐位相同。
        return self.drawInBatch(seq, curX, y, color, shadow, matrix, buffer, mode, packedLight, packedOverlay);
    }

    // ================================================================== 混排分段的 FCS 版（§4.8）

    /**
     * <b>把这条 FCS 一次遍历收成「码点 / 样式 / 纯文本」</b>，并顺带把 {@link FcsMemo} 填好
     * （于是紧随其后的 {@link #lookup} 一定命中 ⇒ 同一串不会走两遍）。
     *
     * <p>为什么必须收样式：{@code §} 在 {@code Component→FCS} 那一步已经被折成 {@link Style}
     * 并从文本里剥掉（见类注释 §4.8.1）⇒ <b>纯文本里已经没有任何"颜色"信息</b>，
     * 想原样转发就只能把 {@code (codepoint, Style)} 一起留下来。
     *
     * <p>⚠️ 上限判据与 {@link Collector} <b>逐字相同</b>（按 {@code StringBuilder.length()} 判、
     * 到 {@link #MAX_CODEPOINTS} 就中止）—— 两处的"这条太长、不管"必须是同一条线，
     * 否则同一串在两条路上会一个接管、一个不接管。
     *
     * @return {@code null} = 码点数超上限（与 {@link #extract} 返回 {@code null} 的情形一致）
     */
    private static GlyphBuffer collectGlyphs(FormattedCharSequence text) {
        FcsMemo memo = MEMO.get();
        if (memo.seq == text && memo.glyphs != null) {
            // 同一实例：上一帧刚收过（聊天栏横幅/物品名每帧重画的就是同一个实例）。
            return memo.glyphs.overflow ? null : memo.glyphs;
        }
        final GlyphBuffer gb = memo.glyphBuffer();
        gb.reset();
        text.accept(gb);
        memo.seq = text;
        memo.glyphs = gb;
        memo.planResolved = false;
        if (gb.overflow) {
            // 与 lookup() 里 raw==null 那一支逐项对齐（含 segResolved=true ⇒ 「这条不是混排行」）。
            memo.raw = null;
            memo.parsed = null;
            memo.segments = null;
            memo.segResolved = true;
            return null;
        }
        final String raw = gb.sb.toString();
        memo.raw = raw;
        memo.parsed = ShanhaiTextParser.parse(raw);
        memo.segments = null;
        memo.segResolved = false;
        return gb;
    }

    /**
     * 取/建<b>分段计划</b>（按 FCS <b>实例</b>记忆，与 {@link #lookup} 同一套口径）。
     *
     * @return {@code null} = <b>这一行切不出我们的段</b>（或段边界映射不上）⇒ 调用方必须走改前那条老路
     */
    private static FcsPlan planOf(FormattedCharSequence text, GlyphBuffer gb) {
        final FcsMemo memo = MEMO.get();
        if (memo.seq == text && memo.planResolved) {
            return memo.plan != null && memo.plan.usable ? memo.plan : null;
        }
        FcsPlan plan = memo.plan;
        if (plan == null) {
            plan = new FcsPlan();
            memo.plan = plan;
        }
        plan.build(gb, memo.raw);
        memo.planResolved = true;
        return plan.usable ? plan : null;
    }

    /**
     * <b>FCS 层的分段绘制</b>：<b>顺序画各段、逐段推进 x</b>（§4.8；切段规则与 String 通道完全同一套，
     * 见 {@link ShanhaiTextParser#parseSegments}）。
     *
     * <p>三种段三条路（判据见 {@link FcsPlan}）：
     * <ol>
     *   <li><b>我们段</b> ⇒ {@link #drawOursSegment}（色板 / 加粗 / 位移效果，与 §4.7 同一条路）；</li>
     *   <li><b>带样式的原版段</b> ⇒ {@link RunSeq} 子 FCS ⇒ {@code self.drawInBatch(subFcs, …)}；</li>
     *   <li><b>不带样式的原版段</b> ⇒ {@code self.drawInBatch(段文本, …)}（与改前逐位相同）。</li>
     * </ol>
     * 🔴 <b>递归防护</b>：本方法一进来就把 {@link #IN_SEGMENTS} 置位、出去时在 {@code finally} 里复位
     * （与 {@link #drawSegments} 完全同一套语义）⇒ 里面那次子 FCS / String 绘制<b>再进混入</b>时，
     * 两个入口都会在探针之后立刻返回 {@code null} = <b>不取消原版</b>。
     *
     * <p>⚠️ x 推进量与 §4.7.3 <b>同一条口径</b>：每段都过 {@link #noShadowBonus}
     * （{@code drawInBatch} 的返回值是 {@code (int)末点 + (shadow?1:0)}，分段 = N 次调用
     * ⇒ 不还原就"每段多吃 1px"）。
     */
    private static int drawFcsSegments(Font self, FcsPlan plan, GlyphBuffer gb,
                                       float x, float y, int color, boolean shadow,
                                       Matrix4f matrix, MultiBufferSource buffer, Font.DisplayMode mode,
                                       int packedLight, int packedOverlay, boolean[] started) {
        // 整行一个 now：与 drawStyled 的"同一帧"约定一致（段与段之间不许相位自己抖）。
        final long now = System.currentTimeMillis();
        final boolean[] inSegments = IN_SEGMENTS.get();
        final boolean outer = inSegments[0];
        inSegments[0] = true;
        try {
            float curX = x;
            for (int i = 0; i < plan.count; i++) {
                if (plan.oursFlag[i]) {
                    curX = drawOursSegment(self, plan.segs[i], now, curX, y, color, shadow, matrix, buffer, mode,
                            packedLight, packedOverlay, started);
                    continue;
                }
                final float adv;
                if (plan.styledFlag[i]) {
                    // 🔴 样式原样转发：把【同一批 {@code (codepoint, Style)}】做成子 FCS 再喂回去。
                    //    绝不能转成 String —— String 重载里没有 Style，那正是本次要修的 bug（§4.8.1）。
                    final RunSeq run = RUN_SEQ.get();
                    run.prepare(gb, plan.cpFrom[i], plan.cpTo[i]);
                    adv = self.drawInBatch(run, curX, y, color, shadow, matrix, buffer, mode,
                            packedLight, packedOverlay);
                } else {
                    // 这一段<b>一个带样式的字形都没有</b> ⇒ 与改前一样走 String 重载（会解析 §，
                    // 而这条通道上的段文本本来就不含 §）⇒ 逐位相同。
                    adv = self.drawInBatch(plan.segs[i].text(), curX, y, color, shadow, matrix, buffer, mode,
                            packedLight, packedOverlay);
                }
                curX = noShadowBonus(adv, shadow);
                started[0] = true;
            }
            return Math.round(curX);
        } finally {
            inSegments[0] = outer;
        }
    }

    /**
     * 这个 {@link Style} 上<b>有没有任何可见的样式信息</b>（= 有没有东西会在"转成 String"时丢掉）。
     *
     * <p>判据逐项对应 {@code Font$StringRenderOutput.accept} 真正读的那几项
     * （{@code getColor()} / {@code isBold()}；其余四项作用于字形本身）：
     * <b>命中任意一项就必须走子 FCS</b>，否则那一位在 String 路径上会丢。
     * <p>⚠️ 刻意<b>不</b>用 {@code Style.isEmpty()}：{@code Style.EMPTY} 之外的实例（例如
     * {@code withFont(...)} 之类我们这里不判的字段）也会让 {@code isEmpty()} 为 false，
     * 于是"没样式的段"会被无谓地送进子 FCS（多一次 FCS 绘制、结果相同）。
     * 这里只认<b>会改变像素</b>的那几项，宁可多走 String（等价）。
     */
    private static boolean isStyled(Style style) {
        return style.getColor() != null
                || style.isBold() || style.isItalic() || style.isUnderlined()
                || style.isStrikethrough() || style.isObfuscated();
    }

    // ================================================================== 提取 / 记忆 / 探针

    /** 零分配探针：这段 FCS 里有没有 {@code &}？找到就提前中止。 */
    private static boolean probeHasAmp(FormattedCharSequence text) {
        AmpProbe probe = PROBE.get();
        probe.hit = false;
        text.accept(probe);
        return probe.hit;
    }

    /**
     * 按「实例」记忆解析结果；实例对不上时退回按字符串缓存。
     *
     * <p>为什么值得：聊天栏横幅每帧重画的往往是<b>同一个 {@code FormattedCharSequence} 实例</b>
     * ⇒ 命中时连 {@code StringBuilder} 都不用碰，<b>零分配</b>。
     * 实例相同就一定内容相同（{@code FormattedCharSequence} 是纯函数式的 accept 回调），所以这个记忆是安全的。
     */
    private static ShanhaiTextParser.Parsed lookup(FormattedCharSequence text) {
        FcsMemo memo = MEMO.get();
        if (memo.seq == text) {
            return memo.parsed;
        }
        String raw = extract(text);
        if (raw == null) {
            // 超长文本：记住「不管」，免得每帧再来一遍
            memo.seq = text;
            memo.raw = null;
            memo.parsed = null;
            memo.segments = null;
            memo.segResolved = true;   // 「这条不是混排行」—— 已经问过一遍了
            memo.glyphs = null;        // §4.8：字形缓冲与分段计划跟着一起作废（否则会拿旧串的字形去画）
            memo.plan = null;
            memo.planResolved = false;
            return null;
        }
        ShanhaiTextParser.Parsed parsed = ShanhaiTextParser.parse(raw);
        memo.seq = text;
        memo.raw = raw;
        memo.parsed = parsed;
        memo.segments = null;
        memo.segResolved = false;
        // 同上：换了串 ⇒ 旧的字形缓冲/分段计划必须作废。
        // （正常路径上 collectGlyphs 已经把 memo 填好、这里恒命中；这两行是防"以后有人改了调用顺序"。）
        memo.glyphs = null;
        memo.plan = null;
        memo.planResolved = false;
        return parsed;
    }

    /**
     * 混排行用的分段结果（按 FCS <b>实例</b>记忆，与 {@link #lookup} 同一套口径）。
     *
     * @return {@code null} = 这一行<b>不是混排行</b>（没有 {@code §} / 没有 {@code &$} / 切完没有我们段）
     *
     * <p>⚠️ 前置条件：{@link #lookup} 刚刚对<b>同一个实例</b>跑过（{@code renderFcs} 里就是这么排的）。
     * 实例对不上时保守返回 {@code null}（= 交回原版），<b>不会</b>用错的串去画。
     */
    private static List<ShanhaiTextParser.Segment> lookupSegments(FormattedCharSequence text) {
        FcsMemo memo = MEMO.get();
        if (memo.seq != text) {
            return null;
        }
        if (memo.segResolved) {
            return memo.segments;
        }
        memo.segResolved = true;
        memo.segments = memo.raw == null ? null : ShanhaiTextParser.mixedSegments(memo.raw);
        return memo.segments;
    }

    /** 把 FCS 收集成字符串。用复用的 {@link Collector}，只在这里产生 1 个 String。 */
    private static String extract(FormattedCharSequence text) {
        Collector collector = COLLECTOR.get();
        collector.reset();
        text.accept(collector);
        if (collector.overflow) {
            return null;
        }
        return collector.sb.toString();
    }

    // ================================================================== 复用件

    /** 复用探针：不捕获外部变量 ⇒ 不是 lambda 对象，可以长期持有。 */
    private static final class AmpProbe implements FormattedCharSink {
        boolean hit;

        @Override
        public boolean accept(int index, Style style, int codepoint) {
            if (codepoint == '&') {
                hit = true;
                return false; // 提前中止，后面的字不用再走
            }
            return true;
        }
    }

    /** 复用收集器。{@code appendCodePoint} 不分配；只有最后的 {@code toString()} 会。 */
    private static final class Collector implements FormattedCharSink {
        final StringBuilder sb = new StringBuilder(128);
        boolean overflow;

        void reset() {
            sb.setLength(0);
            overflow = false;
        }

        @Override
        public boolean accept(int index, Style style, int codepoint) {
            if (sb.length() >= MAX_CODEPOINTS) {
                overflow = true;
                return false;
            }
            sb.appendCodePoint(codepoint);
            return true;
        }
    }

    /** 「上一帧那条 FCS → 解析结果」的记忆。只存 1 条，不增长。 */
    private static final class FcsMemo {
        FormattedCharSequence seq;
        ShanhaiTextParser.Parsed parsed;

        /**
         * 上一帧那条 FCS 的<b>原始字符串</b>（{@link #extract} 的产物）。
         *
         * <p>为什么留着它：混排行要用<b>同一个串</b>去查分段表。不留就得再 {@code extract} 一次
         * = 每帧多一个 {@code StringBuilder.toString()}（那是热路径上唯一去不掉的分配，别再翻倍）。
         * ⚠️ 它<b>只是缓存</b>：{@code seq == text} ⇒ 内容必然相同，所以引用旧串是安全的。
         */
        String raw;

        /** 上次算出来的分段结果；{@code null} = 「不是混排行」（含"还没算"）。 */
        List<ShanhaiTextParser.Segment> segments;

        /** {@link #segments} 的意思是"算过了"还是"还没算" —— 两者都可能是 {@code null}，必须分开。 */
        boolean segResolved;

        /**
         * 这条 FCS 的<b>字形流</b>（§4.8）—— {@link GlyphBuffer} 每份记忆独占一个、<b>只复用自己的</b>。
         *
         * <p>为什么挂在记忆上而不是再开一个 ThreadLocal：{@code seq == text} 的判据在这里<b>本来就有</b>，
         * 而"这串的字形数组"与"这串的解析结果"是同一份缓存的两个视图（分开两处就得同步两次，
         * 迟早有一天不同步）。{@code null} = 还没收过。
         */
        GlyphBuffer glyphs;

        /** 上次算出来的分段计划（§4.8）；{@code null} = 还没建。 */
        FcsPlan plan;

        /** {@link #plan} 的意思是"建过了"还是"还没建" —— 与 {@link #segResolved} 同一个理由。 */
        boolean planResolved;

        GlyphBuffer glyphBuffer() {
            if (glyphs == null) {
                glyphs = new GlyphBuffer();
            }
            return glyphs;
        }
    }

    /** {@code Style[]} 暂存，按需增长到色板长度。 */
    private static final class StyleScratch {
        Style[] array = new Style[16];

        Style[] ensure(int n) {
            if (array.length < n) {
                array = new Style[Math.max(n, array.length * 2)];
            }
            return array;
        }
    }

    /**
     * 逐字绘制用的复用 FCS。
     *
     * <p>为什么复用是安全的：{@code Font.drawInBatch} → {@code drawInternal} →
     * {@code renderText} 会在<b>同一个调用栈里</b>把 {@code accept(sink)} 走完并 {@code finish()}
     * （取证：{@code javap -c} 的 {@code renderText(FormattedCharSequence…)} 里
     * {@code invokeinterface FormattedCharSequence.accept} 后面紧跟 {@code StringRenderOutput.finish}），
     * <b>不会把这个 FCS 存下来等下次用</b>。
     */
    private static final class CharSeq implements FormattedCharSequence {
        private int codepoint;
        /** 逐字绘制用的样式：{@link Style#EMPTY} 或 {@link #BOLD_STYLE}（加粗时）。 */
        private Style style = Style.EMPTY;

        void prepare(int codepoint, Style style) {
            this.codepoint = codepoint;
            this.style = style;
        }

        @Override
        public boolean accept(FormattedCharSink sink) {
            return sink.accept(0, style, codepoint);
        }
    }

    /** 单次绘制用的复用 FCS：按码点遍历 {@code text}，第 k 个字用 {@code styles[k % slots]}。 */
    private static final class LineSeq implements FormattedCharSequence {
        private String text = "";
        private Style[] styles;
        private int slots = 1;

        void prepare(String text, Style[] styles, int slots) {
            this.text = text;
            this.styles = styles;
            this.slots = Math.max(slots, 1);
        }

        @Override
        public boolean accept(FormattedCharSink sink) {
            int index = 0;
            int n = text.length();
            for (int i = 0; i < n; ) {
                int cp = text.codePointAt(i);
                if (!sink.accept(index, styles[index % slots], cp)) {
                    return false;
                }
                index++;
                i += Character.charCount(cp);
            }
            return true;
        }
    }

    // ================================================================== FCS 分段的复用件（§4.8）

    /**
     * <b>一次遍历收下来的字形流</b>：码点 + {@link Style} + 它在纯文本里的字符下标。
     *
     * <p>三个数组是<b>一一对应</b>的：
     * <pre>
     *   cps[k]    第 k 个码点
     *   styles[k] 它原本的 Style（引用级原样保留 ⇒ 子 FCS 能原样转发）
     *   charAt[k] 它在 {@link #sb} 里的 UTF-16 下标（用来把"段在文本里的字符区间"映射成码点下标）
     * </pre>
     * ⚠️ 上限判据与 {@link Collector} 逐字相同（见 {@link #collectGlyphs} 的注释）。
     */
    private static final class GlyphBuffer implements FormattedCharSink {
        final StringBuilder sb = new StringBuilder(128);
        int[] cps = new int[64];
        Style[] styles = new Style[64];
        int[] charAt = new int[64];
        int count;
        /** 已经收了多少个 UTF-16 字符（= {@link #sb} 的 length）—— 上限判据用它。 */
        int chars;
        boolean overflow;
        /** 这条 FCS 里<b>有没有任何一个字形带样式</b>（§4.8 的闸门）。 */
        boolean anyStyled;

        void reset() {
            sb.setLength(0);
            count = 0;
            chars = 0;
            overflow = false;
            anyStyled = false;
        }

        @Override
        public boolean accept(int index, Style style, int codepoint) {
            if (chars >= MAX_CODEPOINTS) {
                overflow = true;
                return false;
            }
            if (count == cps.length) {
                final int n = count << 1;
                cps = java.util.Arrays.copyOf(cps, n);
                styles = java.util.Arrays.copyOf(styles, n);
                charAt = java.util.Arrays.copyOf(charAt, n);
            }
            cps[count] = codepoint;
            styles[count] = style;
            charAt[count] = chars;
            if (!anyStyled && isStyled(style)) {
                anyStyled = true;
            }
            count++;
            chars += Character.charCount(codepoint);
            sb.appendCodePoint(codepoint);
            return true;
        }

        /**
         * 纯文本里的字符下标 {@code charOffset} 落在第几个码点上（= 第一个 {@code charAt >= charOffset} 的下标）。
         *
         * @return {@code charOffset == chars} 时返回 {@link #count}（串尾）；越界返回 {@code -1}
         */
        int cpIndexAt(int charOffset) {
            if (charOffset < 0) {
                return -1;
            }
            if (charOffset >= chars) {
                return charOffset == chars ? count : -1;
            }
            // 码点数 ≤ MAX_CODEPOINTS（1024）⇒ 线性扫描足够便宜，且不必担心二分写错。
            for (int k = 0; k < count; k++) {
                if (charAt[k] >= charOffset) {
                    return k;
                }
            }
            return -1;
        }

        /** {@code [from,to)} 里有没有带样式的字形。 */
        boolean anyStyledIn(int from, int to) {
            for (int k = from; k < to; k++) {
                if (isStyled(styles[k])) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * <b>子 FCS</b>：只转发 {@code [from,to)} 这段下标的 {@code sink.accept(idx, style, cp)}。
     *
     * <p>🔴 转发的是 {@code styles[k]} <b>这个引用本身</b> ⇒ 颜色/加粗/斜体/下划线/删除线/混淆
     * <b>一位都不改</b>（"样式原样转发"的判据就是：拿到手的 {@code Style} 与原来那个是同一个对象）。
     * <p>{@code index} 也照原样转发（不重编号）：MC 的 {@code StringRenderOutput.accept} 不拿它算坐标，
     * 但重编号没有任何好处，而"原样"让这条子 FCS 与被切下来的那一截<b>逐位等价</b>。
     */
    private static final class RunSeq implements FormattedCharSequence {
        private GlyphBuffer gb;
        private int from;
        private int to;

        void prepare(GlyphBuffer gb, int from, int to) {
            this.gb = gb;
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean accept(FormattedCharSink sink) {
            for (int k = from; k < to; k++) {
                if (!sink.accept(k, gb.styles[k], gb.cps[k])) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * <b>分段计划</b>：段表（谁画、画文本的哪一截）+ 码点区间。按 FCS 实例记忆（在 {@link FcsMemo} 里）。
     *
     * <p>🔴 <b>切段不另写一套</b>：段表直接来自 {@link ShanhaiTextParser#parseSegments(String)}
     * （与 String 通道<b>同一个函数</b>）⇒ 两条通道的切分规则<b>不可能不一致</b>。
     * 本类只多做一件事：<b>把段映射回纯文本的字符区间，再映射成码点下标</b>。
     * <p>映射是<b>带校验</b>的（{@code raw.startsWith(...)} 逐段核对）：
     * 任何一段对不上就 {@link #usable} = false ⇒ 渲染层<b>交回改前行为</b>，
     * 绝不用"猜出来的位置"去画。
     */
    private static final class FcsPlan {
        private boolean usable;
        private int count;

        private boolean[] oursFlag = new boolean[8];
        private boolean[] styledFlag = new boolean[8];
        private int[] cpFrom = new int[8];
        private int[] cpTo = new int[8];
        private ShanhaiTextParser.Segment[] segs = new ShanhaiTextParser.Segment[8];

        void build(GlyphBuffer gb, String raw) {
            usable = false;
            count = 0;
            if (raw == null || gb == null) {
                return;
            }
            // 🔴 前置条件：这条通道上 {@code §} 早就被折成 Style 了（§4.8.1）。
            //    万一真有一条<b>带字面量 §</b> 的 FCS 进来（例如 {@code FormattedCharSequence.forward}，
            //    它<b>不</b>解析 §；那种 FCS 的样式恒为 EMPTY、本来也走不到这里），
            //    也一律<b>放弃分段</b>：`&$…-` 的段边界与 § 的下标换算我们现在没有验证过。
            if (raw.indexOf(ShanhaiTextParser.SECTION_SIGN) >= 0) {
                return;
            }
            final List<ShanhaiTextParser.Segment> table = ShanhaiTextParser.parseSegments(raw);
            final int n = table.size();
            ensure(n);
            int pos = 0;          // 纯文本里"下一段从哪里开始"
            boolean hasOurs = false;
            for (int i = 0; i < n; i++) {
                final ShanhaiTextParser.Segment seg = table.get(i);
                final String t = seg.text();
                final int len = t.length();
                final int from;
                if (seg.ours()) {
                    // 我们段：正文在 `&$…-` 之后；`-` 之前那段样式码<b>不画</b>（跳过）。
                    final int mark = raw.indexOf(ShanhaiTextParser.SEGMENT_MARK, pos);
                    if (mark < 0) {
                        return;
                    }
                    final int dash = raw.indexOf('-', mark + ShanhaiTextParser.SEGMENT_MARK.length());
                    if (dash < 0) {
                        return;
                    }
                    from = dash + 1;
                    if (len == 0 || !raw.startsWith(t, from)) {
                        return;
                    }
                    pos = from + len;
                    if (raw.startsWith(ShanhaiTextParser.RESTORE_MARKER, pos)) {
                        pos += ShanhaiTextParser.RESTORE_MARKER.length();   // `&r` 是码，被吃掉、不画
                    }
                    hasOurs = true;
                } else {
                    // 原版段：原文的<b>原样子串</b>（parser 就是这么切的）⇒ 必须逐字对上。
                    if (len == 0 || !raw.startsWith(t, pos)) {
                        return;
                    }
                    from = pos;
                    pos += len;
                }
                final int cpFrom = gb.cpIndexAt(from);
                final int cpTo = gb.cpIndexAt(pos);
                if (cpFrom < 0 || cpTo <= cpFrom) {
                    return;
                }
                set(i, seg, cpFrom, cpTo, !seg.ours() && gb.anyStyledIn(cpFrom, cpTo));
            }
            count = n;
            usable = hasOurs;
        }

        private void set(int i, ShanhaiTextParser.Segment seg, int from, int to, boolean styled) {
            if (i >= segs.length) {
                ensure(i + 1);
            }
            segs[i] = seg;
            oursFlag[i] = seg.ours();
            cpFrom[i] = from;
            cpTo[i] = to;
            styledFlag[i] = styled;
        }

        private void ensure(int n) {
            if (n <= segs.length) {
                return;
            }
            int cap = Math.max(n, segs.length << 1);
            oursFlag = java.util.Arrays.copyOf(oursFlag, cap);
            styledFlag = java.util.Arrays.copyOf(styledFlag, cap);
            cpFrom = java.util.Arrays.copyOf(cpFrom, cap);
            cpTo = java.util.Arrays.copyOf(cpTo, cap);
            segs = java.util.Arrays.copyOf(segs, cap);
        }
    }
}
