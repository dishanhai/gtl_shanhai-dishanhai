package com.shanhai.common.text;

import com.shanhai.ShanhaiMod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 山海重构 · <b>{@code &$…-} 文本码的解析器</b>（客户端 Font 混入的唯一入口）。
 *
 * <h2>0. 出处</h2>
 * <pre>
 * originals/upstream\gtl_shanhai-dishanhai\src\main\java\com\dishanhai\gt_shanhai\api\TextFormatParser.java
 *   parseFormatting       : 59-89    ← 语法结构（& / $ / - 三个位置关系）
 *   parseFlags            : 147-177  ← 效果字符位 & 样式名提取
 *   isEffectSymbol        : 196-204  ← 11 个效果字符的全表
 *   calcWaveX             : 239-241  ← floatX 的数学式
 *   calcGlitchX / Y       : 255-265  ← glitch 的像素位移
 *   applyGlitchColor      : 294-299  ← glitch 的偶发掺色
 *   mixColor              : 301-314
 * </pre>
 * 一律<b>逐行照抄</b>，只删掉我们用不到的分支（见 §3）。
 *
 * <h2>1. 语法（{@code parseFormatting} 照抄 ＋ 2026-10-31 自加 {@code &r} 还原符）</h2>
 * <pre>
 *   [前缀] &lt;效果字符…&gt; $ &lt;样式名&gt; - &lt;正文&gt; [&amp;r 后缀]
 *   ↑ prefix          ↑ 效果字符可以写在 $ 之前，也可以写在 $ 之后（两种都支持）
 *                                          ↑ 还原符（2026-10-31，<b>上游没有</b>）
 * </pre>
 * 规则逐条（对应上游 63–88 行；第 4 条里的 {@code &r} 是我们新加的）：
 * <ol>
 *   <li>从<b>第一个</b> {@code &} 开始；{@code &} 之前的文本是 {@code prefix}（原样保留、原样绘制）；</li>
 *   <li>{@code &} 之后到<b>第一个 {@code -}</b> 之间的整段 = {@code codePart}；</li>
 *   <li>{@code codePart} 里 {@code $} <b>之后</b>、剥掉紧邻的效果字符，剩下的就是<b>样式名</b>；</li>
 *   <li><b>正文区</b>（{@code -} 之后）<b>到 {@code &r}（或串尾）= 正文 {@code body}</b>；
 *       若正文区里出现 {@code &r}，则 <b>{@code &r} 之后 = 后缀 {@code suffix}</b>
 *       （{@code &r} 这两个字符本身<b>不画</b>）。{@code cleanText = prefix + body + suffix}
 *       （<b>宽度按它算</b>）。</li>
 * </ol>
 *
 * <h3>1.1 {@code &r} 还原符（2026-10-31 新增，用户选的 C 案）</h3>
 * <b>为什么要有它</b>：只有"开始染色"时，染色范围<b>一直到本次 {@code drawInBatch} 收到的整个字符串结尾</b>
 * ⇒ 调用方把"彩色名 + 后面的普通字"拼成一个串时，<b>后面那段普通字也会被染色</b>
 * （用户 2026-10-31 实机报的"特效溢出到外面"，机制见 {@code ShanhaiFontStyleRenderer} 类注释 §4.6）。
 * {@code &r} 让一句里能做到：<b>前置白字 ＋ 中间一段彩虹 ＋ 后面继续白字</b>。
 * <pre>
 *   [prefix] &amp;$[效果字符]&lt;样式名&gt;-[正文]&amp;r[后缀]
 *   例：      前置白字&amp;$neon-跨配方种类并行&amp;r后续白字
 *             ⇒ prefix="前置白字" body="跨配方种类并行" suffix="后续白字"
 *                cleanText="前置白字跨配方种类并行后续白字" style=neon
 * </pre>
 * 判定规则（<b>逐条定死</b>，{@link #parseUncached} 照它实现）：
 * <ol>
 *   <li>只在<b>{@code -} 之后的正文区</b>里找，找的是<b>第一个</b> {@code &r}
 *       （{@code -} 之前的 {@code codePart} 里出现 {@code &r} 不算）；</li>
 *   <li>找到 ⇒ {@code body = &r 之前}、{@code suffix = &r 之后}（{@code &r} 本身丢掉）；</li>
 *   <li>找不到 ⇒ {@code suffix = ""}，{@code body = 正文区全部} ——
 *       <b>此时本类的每一个输出都与 {@code &r} 出现之前逐位相同</b>（回归主线，别改成"顺手也切一刀"）；</li>
 *   <li>{@code &r} 紧跟 {@code -} ⇒ {@code body = ""}、{@code suffix = 其后全部}（合法，不是退化）；</li>
 *   <li>连续两个 {@code &r} ⇒ <b>以第一个为准</b>：{@code body = 第一个之前}、
 *       {@code suffix = 从第一个之后开始的那一串}（于是 suffix 里会<b>原样带上</b>第二个 {@code &r} 的三个字节）。
 *       这与"只找第一个"是同一条规则，<b>不是</b>特例（见 §5 第 7 条）；</li>
 *   <li><b>加粗 {@code #} 与染色一样只作用于 {@code body}</b>；{@code suffix} <b>既不加粗也不染色</b>；</li>
 *   <li>🔴 <b>{@link #parse} 上的 {@code §} 铁律一个字没改</b>：<b>单段入口</b>里同一行出现 {@code §}
 *       ⇒ 整条判「不是我们的码」交回原版。
 *       {@code &r} 只是<b>把"这一行"的判定面从 {@code prefix+body} 扩到 {@code prefix+body+suffix}</b>
 *       （= 整行），并没有放宽那条铁律（suffix 为空时判定面不变 ⇒ 输出不变）。
 *       <p>⚠️ <b>2026-11-01 补充（A 案）</b>：混排行<b>不再</b>由 {@code parse} 负责，而是走新的
 *       <b>分段入口</b> {@link #parseSegments}（见 <b>§7</b>）⇒ {@code parse} 的这条铁律<b>保持不变</b>，
 *       只要调用方拿到的 {@code ours=true}，它就仍然是"不含 §"的那条老路。</li>
 * </ol>
 * ⚠️ <b>边界（刻意写死，不是 bug）</b>：
 * <ul>
 *   <li><b>{@code prefix} 里出现的 {@code &r} 不当作还原符</b> —— prefix 本来就原样绘制。
 *       实践中更常见的结果是<b>整条判非我们的码</b>：本类从<b>第一个</b> {@code &} 开始解析，
 *       一条 {@code 白&r字&$neon-正文} 的第一个 {@code &} 就是那个 {@code &r} 的 {@code &}
 *       ⇒ {@code codePart} 里没有 {@code $} ⇒ {@code ours=false}（这条行为与改前<b>完全一致</b>）；</li>
 *   <li><b>{@code &r} 不可转义</b>：正文里再也写不出字面量 {@code &r}（写了就会被当还原符吃掉）。
 *       我们现有 96 条文本<b>一处 {@code &r} 都没有</b>（取证见 §6），所以现在没有代价。</li>
 * </ul>
 *
 * <h2>2. 🔴 效果字符全表（11 个 + {@code $} 分隔符）—— 逐个交代「做没做」</h2>
 * <table border="1">
 *   <tr><th>字符</th><th>旗标</th><th>语义</th><th>上游公式</th><th>本工程</th></tr>
 *   <tr><td>{@code ~}</td><td>floatY</td><td>上下波浪</td><td>{@code sin(i*0.5 + t*0.002) * 3.0}</td><td><b>没做</b></td></tr>
 *   <tr><td>{@code *}</td><td>floatX</td><td>左右波浪</td><td>{@code cos(i*0.7 + t*0.0015) * 2.0}</td><td><b>✅ 做了</b>（横幅第 2 行用）</td></tr>
 *   <tr><td>{@code %}</td><td>shake</td><td>抖动</td><td>{@code sin(t*0.02 + seed*0.001) * (seed%100) * 0.01}</td><td><b>没做</b></td></tr>
 *   <tr><td>{@code @}</td><td>circle</td><td>圆周</td><td>{@code angle=t*0.003+i*0.5 → (cos*3, sin*3)}</td><td><b>没做</b></td></tr>
 *   <tr><td>{@code !}</td><td>bounce</td><td>弹跳</td><td>{@code |sin(t*0.005 + i*1.2)| * 4.0}</td><td><b>没做</b></td></tr>
 *   <tr><td>{@code ^}</td><td>scan</td><td>扫光</td><td>亮带位置 {@code (t/45)%period - 4}，{@code dist&lt;=3} 混白 {@code *0.75}</td><td><b>没做</b></td></tr>
 *   <tr><td>{@code ?}</td><td>glitch</td><td>闪烁 + 位移</td><td>见 §2.1</td><td><b>✅ 做了</b>（横幅首尾行用）</td></tr>
 *   <tr><td>{@code +}</td><td>breathe</td><td>呼吸明暗</td><td>{@code sin(t*0.003 + i*0.18) → 混白 (w+1)*0.22}</td><td><b>没做</b></td></tr>
 *   <tr><td>{@code &gt;}</td><td>chase</td><td>环形追光</td><td>{@code pos=(t/70)%len}，{@code dist&lt;=2} 混白 {@code *0.9}</td><td><b>没做</b></td></tr>
 *   <tr><td>{@code `}</td><td>obfuscated</td><td>混淆</td><td>{@code Style.withObfuscated(true)}</td><td><b>没做</b></td></tr>
 *   <tr><td>{@code #}</td><td>outline <b>→ 粗体</b></td><td>描边 <b>→ 加粗</b></td><td><b>上游自己就是死旗标</b>（只写不读）</td><td><b>✅ 做了</b>（2026-09-30 改造成「粗体开关」，见 §2.2）</td></tr>
 * </table>
 * <b>为什么只做 3 个</b>：用户裁决「只移植目前我们要用到的」。实测我们的三类文本里
 * <b>只出现了 {@code ?} 与 {@code *}</b>（横幅），物品名<b>一个效果字符都没有</b>
 * （取证：{@code _tools\extract_style_inventory.ps1} 的 {@code FLAGS (distinct=0)}）；
 * {@code #} 是 2026-09-30 用户点单「两块世线板要同时有流动色和粗体」时新启用的（见 §2.2）。<br>
 * <b>没做的怎么退化</b>：见 §3 —— <b>剥离该字符、保留色板颜色</b>，限流打一行 WARN。
 *
 * <h3>2.2 🔴 加粗开关 {@code #}（2026-09-30 新增）</h3>
 * <pre>
 *   写法：  &amp;$#&lt;样式名&gt;-&lt;正文&gt;        例：{@code &$#golden-世线终焉板}
 *   位置：  跟 {@code ?} / {@code *} 一样，写在 {@code $} 之后、样式名之前
 *           （写在 {@code $} 之前也认，因为检测扫的是整个 codePart）
 *   作用域：只让【正文】加粗（{@code -} 之后那一段），与色板染色的作用域完全一致；prefix 不加粗
 * </pre>
 * <b>为什么选 {@code #}</b>（四个候选的取舍，理由逐条）：
 * <ol>
 *   <li><b>{@code #} 是上游自己都没实现的死旗标</b>（{@code isEffectSymbol} 收它，但
 *       {@code parseFlags} 只写进一个从不被读的 {@code outline} 字段）。⇒ 把它改成「粗体」
 *       <b>不可能与上游任何既有文本的观感冲突</b> —— 上游那套渲染里它本来就是个 no-op。
 *       对照组 {@code !}（bounce）：上游是<b>真的有实现</b>的，复用它会在语义上撞车。</li>
 *   <li><b>零新语法概念</b>：{@code #} 本来就在 {@link #EFFECT_SYMBOLS} 里，解析器本来就在
 *       「样式名开头的效果字符」那圈里扫描并剥离它 ⇒ 只需把「扫到了 ⇒ 丢掉」改成「扫到了 ⇒ 置位」，
 *       语法结构一个字不用动（不像 {@code &$bold_xxx-} 那样要新造一个前缀约定）。</li>
 *   <li><b>不占新字符位</b>：11 个效果字符里还有 8 个空着，但用户明确要求「现有的 96 条名字逐字节不变」
 *       ⇒ 新增一个语法符号反而要求所有既有文本重新审一遍有没有撞上。{@code #} 已经过了这一关：
 *       取证 = 全文 {@code zh_cn.json} 里 {@code #} 出现 <b>0 次</b>，6 行横幅的 5 个
 *       {@code PREFIX_*} 常量里也没有。</li>
 *   <li><b>能一眼看出是「修饰」不是「颜色」</b>：它跟在 {@code $} 后、样式名前，与
 *       {@code &$?crimson-} 同一个位置 ⇒ 已有的「效果字符必须写在样式名之前」这条经验直接沿用。</li>
 * </ol>
 * <b>加粗在 MC 里是怎么生效的</b>（全部 {@code javap -c} 实证，见 {@code handoff\outbound\物品名美化-收尾.md}）：
 * <pre>
 *   Font$StringRenderOutput.accept : 60 invokevirtual Style.isBold()Z          ⇒ istore 7
 *                                  : 284 iload 7 → 286 GlyphInfo.getAdvance(Z)F
 *   GlyphInfo.getAdvance(Z)        : getAdvance() + (Z ? getBoldOffset() : 0)，getBoldOffset() 默认返回 1.0f
 *   Font 里 StringSplitter 的 WidthProvider（lambda$new$0）: 17 Style.isBold()Z → 20 GlyphInfo.getAdvance(Z)F
 * </pre>
 * ⇒ <b>「样式带 bold」= 每个字形多推进 1.0 px</b>，而且 <b>宽度测量与绘制走的是同一个 {@code getAdvance(Z)}</b>
 * ⇒ 不需要我们自己造第二条加粗路径，把 {@code Style.withBold(true)} 挂上去就行。
 *
 * <h3>2.1 {@code ?} glitch 的三段（照抄上游）</h3>
 * <pre>
 * 位移  calcGlitchX(i,t) = (t/55 + i*17) % 7 &gt; 1 ? 0 : ((t/55 + i*17) % 3 - 1) * 1.5
 * 位移  calcGlitchY(i,t) = (t/65 + i*23) % 9 &gt; 1 ? 0 : ((t/65 + i*23) % 3 - 1) * 1.0
 * 掺色  applyGlitchColor: (t/60 + i*31) % 11 == 0 → 混 0x55FFFF 65%；% 13 == 0 → 混 0xFF5555 55%
 * </pre>
 * ⚠️ 注意：上游的 {@code ?} <b>既掺色也位移</b>。只做掺色不做位移会少一半观感，
 * 所以 {@code ?} 走「逐字绘制」那条路径（见 {@code ShanhaiFontStyleRenderer}）。
 *
 * <h2>3. 🔴 退化规则（用户裁决 ③：最坏是「没特效」，绝不是「显示乱码」）</h2>
 * <table border="1">
 *   <tr><th>遇到什么</th><th>怎么做</th></tr>
 *   <tr>
 *     <td><b>没移植的样式名</b>（{@code ShanhaiTextPalette.get} 返回 null）</td>
 *     <td><b>剥掉整段码</b>（{@code &…-} 全部不画），按<b>普通文字、无颜色</b>绘制；限流 WARN 记下名字</td>
 *   </tr>
 *   <tr>
 *     <td><b>没实现的效果字符</b>（上表里「没做」的那些）</td>
 *     <td><b>剥掉那个字符本身</b>（它本来就不会被画出来），<b>保留色板颜色</b>，只丢掉那个效果；限流 WARN</td>
 *   </tr>
 *   <tr>
 *     <td><b>{@code §} 与 {@code &$} 混在同一行</b>（§5.3）</td>
 *     <td><b>单段入口 {@link #parse}：</b>整条判<b>「不是我们的码」</b>（{@code ours=false}），原样交回原版 ——
 *         <b>码会显示在名字里</b>，但这是刻意的：交回原版至少能正确显示 {@code §} 语义，自己硬吃反而更难预测。<br>
 *         <b>分段入口 {@link #parseSegments}（A 案，2026-11-01）：</b>切成「原版段 / 我们段」逐段推进，
 *         {@code §} 段交给原版、{@code &$} 段交给我们；<b>段内</b>误写 {@code §} ⇒ 该段退化（丢样式码、按原版段画）</td>
 *   </tr>
 * </table>
 * ⚠️ <b>加粗与颜色共进退</b>：{@code #} 是 codePart 的一部分，样式名不认识时整段码被剥掉
 * ⇒ <b>退化 A 的那条路【不带加粗】</b>（白字、不加粗）。这是刻意的：退化 A 的语义是
 * 「这段码我们不认识 ⇒ 整段当垃圾扔掉」，让其中一半（加粗）活下来会造出一个
 * 「既不是我们的渲染、也不是原版渲染」的第三态。用户裁决原话是「样式名拼错 ⇒ 白字」，
 * 本条**逐字节保持了 {@code #} 出现之前的同一条路径**（{@code drawDegraded} 一个字没改）。
 * 第二条与第一条不同（不当成致命退化）的理由：色板是<b>已经移植好</b>的，
 * 丢的是动效不是颜色 ⇒ 把颜色一起丢掉是<b>无谓的损失</b>；而样式名不在表里时我们<b>根本不知道配什么色</b>，
 * 只能不染。两条都满足用户那句「最坏是没特效，不是显示乱码」。
 *
 * <p>⚠️ <b>WARN 的限流</b>照本工程既有做法（{@code GtlAddClientCompat.HIDE_LOG_MIN_GAP_TICKS}）：
 * <b>每 5000 ms 最多一行</b>，并带上被折叠掉的行数（只限流不报数 = 静默，本工程的血账之一）。
 *
 * <h2>4. 缓存（热路径要求）</h2>
 * 解析是<b>纯函数</b>（只看输入字符串）⇒ 可以用「原始串 → 解析结果」的 LRU 表缓存。
 * 表大小 {@value #CACHE_MAX}，超出按访问顺序淘汰最旧的。
 * 缓存也存<b>否定结果</b>（{@code ours=false}）：聊天栏里含 {@code &} 的普通文本<b>不该每帧重解析一遍</b>。
 * 表本身用 {@code synchronizedMap} 包一层：渲染主要在客户端主线程，但本类<b>不假设</b>只有那一个线程。
 *
 * <h2>5. 已知边界（如实列出，别当成 bug 以外的解释）</h2>
 * <ol>
 *   <li><b>只处理第一个 {@code &}</b>（照抄上游）。一条文本里写两个 {@code &$…-} 码时，
 *       第二个会<b>原样显示</b>。我们的 34 条物品名与 6 行横幅<b>每行只有一个码</b>，所以不构成问题。</li>
 *   <li><b>不实现上游的 {@code parseCompactFormatting}</b>（没有 {@code -} 的那种写法 {@code &$ultimate旋转彩虹}）。
 *       我们的文本<b>一律带 {@code -}</b>；没有 {@code -} 时本类直接判「不是我们的码」⇒ 交回原版渲染，
 *       <b>不会</b>误伤普通文本里的 {@code &}。</li>
 *   <li><b>单段入口不做 § 混合</b>（<b>分段入口做了</b>，见 §7）：上游能在同一行里同时处理 {@code §a} 与 {@code &$…-}
 *       （{@code WobbleFontMixin.java:263-297}）。{@link #parse} <b>仍然不做这一层</b>：
 *       若正文里同时出现 {@code §} 与 {@code &$}，{@code parse} 判「不是我们的码」，交回原版渲染。
 *       理由：<b>单段模型里没有"段"这个概念</b> —— 它把 {@code prefix+body+suffix} 当成<b>一段</b>连续的字，
 *       而 {@code §} 是<b>零宽控制码</b>，我们的渲染器是<b>逐字自己算宽、自己取色</b>的
 *       ⇒ 同一段里混着 {@code §} 会让下标换算（去 § 后重建 per-char 色槽数组）失效。
 *       2026-11-01（A 案）<b>不改这条路</b>，而是另开一条"逐段推进"的路 {@link #parseSegments}（§7）。</li>
 *   <li><b>{@code -} 后是空串</b>时（{@code &$ultimate-}）判「不是我们的码」。
 *       与上游不同（上游会把整条原文当正文绘制 ⇒ <b>反而会把码画出来</b>），我们选更保守的那个。
 *       分段入口对"没有正文的 {@code &$…-}"用<b>同一条</b>判据（见 §7.3）。</li>
 *   <li><b>{@code #} 写在样式名【后面】不算加粗</b>（{@code &$golden#-名}）。
 *       与 {@code ?} / {@code *} 同一条规矩：剥离只发生在样式名开头 ⇒ {@code theme} 变成
 *       {@code "golden#"}，查表查不到 ⇒ <b>退化 A（白字、不加粗）</b>。
 *       <b>这是刻意的、和既有行为一致</b>，不是新坑（{@code &$ultimate?-帝山海} 早在 2026-09-30
 *       上一轮就被实测为「退化 A」，见 {@code handoff\outbound\物品名美化-落地.md} §3.4）。</li>
 *   <li><b>加粗只作用于正文</b>，{@code prefix}（{@code &} 之前那一段）照旧用调用方给的颜色、不加粗。
 *       我们的 96 条物品名与 6 行横幅<b>没有一条带 prefix</b>，所以这条边界目前不生效。</li>
 *   <li><b>（2026-10-31）{@code suffix} 不加粗、不染色</b>：加粗的作用域与染色一致，**到 body 为止**。
 *       连续两个 {@code &r} ⇒ <b>以第一个为准</b>，{@code suffix} 里会原样带上第二个 {@code &r} 的两个字符
 *       （因为规则只有"找第一个"这一条，没有为连续写法开特例）。</li>
 *   <li><b>（2026-10-31）{@code &r} 不可转义</b>：正文里写不出字面量 {@code &r}；
 *       且 {@code prefix} 里的 {@code &r} 不当作还原符（这两条见 §1.1 的"边界"）。</li>
 * </ol>
 *
 * <h2>6. 语料读数（2026-11-01 复测，1052 条 lang 值）</h2>
 * <pre>
 *   assets/shanhai/lang/zh_cn.json  &amp;$ = 253   &amp;r = 253   § = 124
 *   assets/shanhai/lang/en_us.json  &amp;$ =   3   &amp;r =   3   § =  92
 *   逐值分类（zh+en，共 1052 条 string）：
 *     732 条 无 §、无 &amp;$          ⇒ 完全走原版（本类 parse 判 NOT_OURS，一个字都不改）
 *     256 条 无 §、有 &amp;$（&amp;r 全在串尾）⇒ 完全走我们（parse ours=true；&amp;r 之后无正文 ⇒ suffix=""）
 *      64 条 有 §、无 &amp;$          ⇒ 完全走原版（分段入口也只会给出 1 个原版段 ⇒ 行为不变）
 *       0 条 有 §、也有 &amp;$        ⇒ 🔴 <b>真实语料里一个混排行都没有</b>
 * </pre>
 * ⇒ 结论（这条决定验收口径）：<b>混排的 A 案在现有语料上"零触发"</b>，它的自证只能靠<b>合成样例</b>
 * （+ 运行期把 {@code §b模块要求：1× &$electric-…§7（等级 ≥ 17）} 拼出来的那种真实场景）。
 * ⚠️ 早先（2026-10-31）这里记的是「{@code &r} 计数 = 0」，那是<b>lang 尚未加上 {@code &r} 之前</b>的状态；
 * 本轮落地后语料已经是 256 条 {@code &$…&r}，<b>本条已按实测改正</b>（读数见
 * {@code temp\segtext\分段兼容.md} 的「回归三类计数」）。
 *
 * <h2>7. 🔴 分段兼容（A 案，2026-11-01 用户拍板）</h2>
 * <b>要解决的问题</b>：单段模型下「同一行里 {@code §} 段 ＋ {@code &$} 段」只能整行交回原版
 * （§5 第 3 条的"码会显示在名字里"），而用户要的是<b>两段各由各自的渲染器画</b>。
 *
 * <h3>7.1 语法与切分规则（定死）</h3>
 * <pre>
 *   [原版段：可含 § 码]  &amp;$&lt;效果字符&gt;&lt;样式名&gt;-&lt;正文&gt;&amp;r  [原版段：可含 § 码]
 * </pre>
 * <ol>
 *   <li>一行里可以有 <b>0..N 个 {@code &$} 段</b>（顺序扫 {@code &$}，只认这两个字符连写）；</li>
 *   <li>每段 <b>正文</b>到 <b>{@code &r}</b>、<b>下一个 {@code &$}</b>、或<b>串尾</b>为止（三者取最早）；
 *       {@code &r} 作为<b>终止符被吃掉</b>（不进任何段的 text）；</li>
 *   <li>{@code &$} 之前 / 段与段之间的字 = <b>原版段</b>；<b>空的原版段不产出</b>（不画也没宽）；</li>
 *   <li>{@code &$} 段内部<b>不允许</b>出现 {@code §}：出现 ⇒ <b>该段退化</b>（丢掉样式码、正文按原版段画）
 *       ＋ 一行限流 WARN（{@code kind=seg_section_sign}）；</li>
 *   <li>没有 {@code &$} 的行 ⇒ {@link #parseSegments} 只给 1 个原版段；
 *       没有 {@code §} 的行 ⇒ 渲染层<b>根本不走分段</b>（{@link #mixedSegments} 直接返回 null）
 *       ⇒ 与改前<b>逐位一致</b>（这是回归底线，见 §7.4）。</li>
 * </ol>
 *
 * <h3>7.2 两个入口，职责不同</h3>
 * <table border="1">
 *   <tr><th>方法</th><th>对外的语义</th><th>谁用</th></tr>
 *   <tr><td>{@link #parse(String)}</td><td><b>单段</b>：整行一次判定（含 § ⇒ NOT_OURS）</td>
 *       <td>{@code ShanhaiFontStyleRenderer} 的<b>老路</b>（256+ 条现存文本全靠它）</td></tr>
 *   <tr><td>{@link #parseSegments(String)}</td><td><b>纯语法</b>：任何输入都切成段（含 0 个 {@code &$} 的行）</td>
 *       <td>自证 / 诊断 / {@link #mixedSegments} 的内部实现</td></tr>
 *   <tr><td>{@link #mixedSegments(String)}</td><td><b>混排专用</b>：不是混排行、或切成段后没有我们段 ⇒ 返回 {@code null}</td>
 *       <td>渲染层的<b>分段入口</b>（只有它为真才走分段绘制）</td></tr>
 * </table>
 *
 * <h3>7.3 退化规则（分段路径，与 §3 一一对应）</h3>
 * <table border="1">
 *   <tr><th>遇到什么</th><th>怎么切</th></tr>
 *   <tr><td>样式名不在色板里（退化 A）</td><td>仍是<b>我们段</b>，但 {@code style=null}、{@code bold=false}
 *       ⇒ 渲染层剥码后按普通文字画（与单段路径的退化 A 同一句判据）</td></tr>
 *   <tr><td>没实现的效果字符（退化 B）</td><td>字符在 <b>codePart</b> 里、本来就不会被画 ⇒ 切完只剩正文；
 *       {@code style} 照留 ⇒ <b>保留颜色、丢掉那个效果</b>，并限流 WARN</td></tr>
 *   <tr><td><b>段内出现 {@code §}</b></td><td><b>该段退化</b>：丢掉样式码、正文＝原版段（{@code ours=false}），
 *       限流 WARN。<b>不</b>把 {@code §} 交给我们的逐字路径（那是零宽控制码，逐字算宽会算错）</td></tr>
 *   <tr><td>形状不对（没有 {@code -} / 没有样式名 / 正文为空）</td>
 *       <td>那串 {@code &$…} <b>按普通文字留在原版段里</b>（码可见）—— 与单段路径 §5.2 / §5.4 的
 *       "交回原版"同一个结局，<b>不会</b>让字消失</td></tr>
 * </table>
 *
 * <h3>7.4 🔴 回归底线（为什么必须"没有 § 就不走分段"）</h3>
 * 分段会把一行拆成多次 {@code drawInBatch}，而每次调用都各自 {@code Mth.ceil} / 各自 {@code (int)} 截断
 * ⇒ <b>即便字形、颜色、顺序完全一样，x 推进量也可能差 1 px</b>。
 * 不混排的行<b>不需要</b>这个代价 ⇒ 渲染层用 {@link #mixedSegments} 当闸门：
 * <b>没有 § 或没有 {@code &$} 或切完没有我们段 ⇒ 返回 null ⇒ 调用方走改前那条一模一样的路</b>。
 * 这一条<b>不是优化，是正确性</b>：256 条现存 {@code &$…&r} 文本的 x 推进量必须<b>逐位不变</b>。
 */
public final class ShanhaiTextParser {

    private ShanhaiTextParser() {}

    /** 11 个效果字符 —— 照抄上游 {@code isEffectSymbol:196-204}（顺序也照抄）。 */
    private static final String EFFECT_SYMBOLS = "@~#*%!^?+>`";

    /**
     * <b>加粗开关字符</b> —— 2026-09-30 新启用（见类注释 §2.2）。
     *
     * <p>它<b>本来就是</b> {@link #EFFECT_SYMBOLS} 的成员（上游的 {@code outline} 死旗标），
     * 所以样式名开头的剥离循环<b>一个字都不用改</b>就能把 {@code &$#golden-} 里的 {@code #} 剥掉。
     */
    public static final char BOLD_SYMBOL = '#';

    /**
     * <b>还原符</b> —— 2026-10-31 新增（见类注释 §1.1）。
     *
     * <p>语法：{@code …-[正文]&r[后缀]}。{@code &r} 之后的部分用<b>调用方原色</b>绘制、不加粗、不染色，
     * 于是"前置白字 ＋ 中间一段彩虹 ＋ 后面继续白字"可以在<b>一个串</b>里写完。
     *
     * <p>🔴 <b>写成常量是为了让"找它"这一步只有一个可指认的位置</b>
     * （{@link #restoreIndex(String)}）：离线自证的负对照就是把那一行临时改成 {@code return -1}，
     * 其余代码一个字都不用动。
     */
    public static final String RESTORE_MARKER = "&r";

    /**
     * <b>原版的样式码字符</b>（U+00A7）—— 分段路径里<b>唯一</b>让我们"必须交出控制权"的字符。
     *
     * <p>写成常量而不是裸 {@code '\u00A7'} 的理由与 {@link #RESTORE_MARKER} 相同：
     * 让"哪个字符是原版控制码"只有一个可指认的位置（离线自证的负对照就盯着它）。
     */
    public static final char SECTION_SIGN = '\u00A7';

    /**
     * <b>我们那一段的起始标记</b>（{@code &$}）—— 分段扫描<b>只认这两个字符连写</b>。
     *
     * <p>⚠️ 与 {@link #containsCode} 的区别：那边只要一个 {@code &} 就返回 true（那是"要不要去解析"的探针），
     * 这边是"这一行里到底有没有我们的一段"的判据。一条只有 {@code §} 码的行<b>不该</b>进分段路径。
     */
    public static final String SEGMENT_MARK = "&$";

    /** glitch 掺色用的两个目标色（照抄上游 {@code applyGlitchColor}）。 */
    private static final int GLITCH_CYAN = 0x55FFFF;
    private static final int GLITCH_RED = 0xFF5555;

    /** 缓存条数上限。<b>只在构造解析表时用到一次</b>，不是每帧判据；给个够放的数就行。 */
    public static final int CACHE_MAX = 256;

    private static final Map<String, Parsed> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Parsed> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    // ------------------------------------------------------------------ 解析结果

    /**
     * 一条 {@code &$…-} 文本的解析结果。<b>不可变</b>（会被放进缓存、被多个线程读）。
     *
     * @param ours       {@code false} = 这条文本<b>不是我们的码</b>，调用方<b>必须原样交回原版渲染</b>
     * @param cleanText  {@code prefix + body + suffix}，即「该画出来的字」；<b>宽度修正也用它</b>
     *                   （suffix 为空时它逐位等于 {@code prefix + body} ⇒ 与 {@code &r} 出现之前相同）
     * @param prefix     {@code &} 之前的原文（原样保留，原样绘制，用的还是调用方传进来的 color）
     * @param body       {@code -} 之后、{@code &r}（或串尾）之前的正文（色板染色<b>与加粗</b>只作用于这一段）
     * @param suffix     {@code &r} 之后的部分（2026-10-31 新增）：<b>用调用方原色绘制、不加粗、不染色</b>；
     *                   没有 {@code &r} 时恒为 {@code ""}（此时本 record 的其余每个字段都与改动前逐位相同）
     * @param palette    色板；{@code null} = <b>退化模式</b>（没移植的样式名）⇒ 无颜色
     * @param isBody     {@code true} = 正文慢速样式（200 ms/格）
     * @param glitch     是否带 {@code ?}
     * @param floatX     是否带 {@code *}
     * @param bold       是否带 {@code #}（<b>加粗</b>，2026-09-30 新增）。⚠️ 只在
     *                   {@code palette != null} 时可能为 true（退化 A 不带加粗，见类注释 §3）；
     *                   ⚠️ 作用域<b>只有 body</b>（suffix 不加粗）
     * @param styleName  解析出的样式名（诊断用；可能是没移植的名字）
     * @param unsupported 解析时遇到但<b>没实现</b>的效果字符（诊断用，可为空串）
     */
    public record Parsed(
            boolean ours,
            String cleanText,
            String prefix,
            String body,
            String suffix,
            int[] palette,
            boolean isBody,
            boolean glitch,
            boolean floatX,
            boolean bold,
            String styleName,
            String unsupported) {

        /**
         * 有没有「会改变像素位置」的效果 —— 有就得走逐字绘制，没有可以一次画完整行。
         *
         * <p>🔴 <b>{@code bold} 不算 motion</b>（刻意的）：加粗改的是字形的<b>推进量</b>与描摹宽度，
         * 不改「这个字画在哪个坐标」，所以它<b>不需要</b>逐字路径
         * （逐字路径每字一次 {@code drawInBatch}，比单次绘制贵一个 {@code Font$StringRenderOutput}）。
         * 证据：{@code StringRenderOutput.accept} 里 {@code isBold} 只被喂给
         * {@code GlyphInfo.getAdvance(Z)}，绘制坐标 {@code x}/{@code y} 一个字节都没被它改。
         */
        public boolean hasMotion() {
            return glitch || floatX;
        }
    }

    /** 「不是我们的码」的单一实例（缓存里的否定结果也用它，省对象）。 */
    private static final Parsed NOT_OURS = new Parsed(
            false, "", "", "", "", null, false, false, false, false, "", "");

    // ================================================================== 分段（A 案，2026-11-01）

    /**
     * <b>一行里的一个绘制段</b>（分段兼容 A 案的产物，见类注释 §7）。
     *
     * <h2>它只描述"这段由谁画、画什么"，不含坐标</h2>
     * x 推进量由渲染层按段累积（{@code ShanhaiFontStyleRenderer#drawSegments}）——
     * <b>量宽的口径必须和画字的口径一致</b>（{@code Font.drawInBatch} 的返回值 vs {@code Font.width}），
     * 那是渲染层的职责，解析层不掺和。
     *
     * @param ours   {@code true} = <b>我们的一段</b>（{@code &$…-} 切出来、样式码已剥离）；
     *               {@code false} = <b>原版的一段</b>（原样子串，<b>含 {@code §} 码</b>，必须整段交给
     *               {@code Font.drawInBatch(String,…)} 那个会解析 {@code §} 的重载）
     * @param text   {@code ours=true}：<b>去掉样式码后的正文</b>（= {@code parse} 里那个 {@code body}）；
     *               {@code ours=false}：<b>原始子串</b>（逐字节取自入参，一个字符都不改）
     * @param style  色板（{@code ours=false} 或<b>退化 A</b> 时为 {@code null}）
     * @param bold   是否带 {@code #}（<b>只作用于 {@code text}</b>；退化 A 恒为 false，与单段路径同一条判据）
     * @param effect <b>诊断用</b>：本段实际生效的效果字符（按 {@code EFFECT_SYMBOLS} 顺序拼，如 {@code "#?"}）；
     *               原版段 / 退化 A 恒为 {@code ""}。⚠️ <b>不参与绘制判断</b>
     * @param glitch 是否带 {@code ?}（位移 + 掺色 ⇒ 渲染层走逐字路径）
     * @param floatX 是否带 {@code *}（水平波浪 ⇒ 渲染层走逐字路径）
     */
    public record Segment(
            boolean ours,
            String text,
            ShanhaiTextPalette.Style style,
            boolean bold,
            String effect,
            boolean glitch,
            boolean floatX) {

        /** 有位移效果吗（与 {@link Parsed#hasMotion()} 同一条判据：{@code bold} 不算 motion）。 */
        public boolean hasMotion() {
            return glitch || floatX;
        }

        /** 原版段的单一构造（省得每处都写 7 个参数）。<b>私有</b>：段只由本类产出。 */
        private static Segment vanilla(String text) {
            return new Segment(false, text, null, false, "", false, false);
        }
    }

    /** 「空行」的单一实例：一个空的<b>原版段</b> ⇒ 渲染层看到没有我们段就交回原版。 */
    private static final List<Segment> SEG_EMPTY =
            List.of(Segment.vanilla(""));

    /**
     * 分段结果缓存。上限<b>沿用</b> {@link #CACHE_MAX}（刻意不新造一个数字：两张表都是"输入串 → 不可变结果"，
     * 没有任何理由让它们不同）。值与 {@link #CACHE} 一样是<b>不可变</b>的（{@code List.of} / {@code List.copyOf}）。
     */
    private static final Map<String, List<Segment>> SEG_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Segment>> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    // ================================================================== 前缀码剥离

    /**
     * <b>剥掉一段文本里的 {@code &$<样式名>-} 前缀码，只留正文</b>。
     *
     * <h2>🔴 为什么需要它（2026-09-30 用户实机报的 bug）</h2>
     * 用户截图：「量子化现实重构」配方页底部那行显示成
     * <pre>
     *   模块要求：1× &$electric-创始现实修改模块（等级 ≥ 17）      ← &$electric- 原样露出来了
     * </pre>
     * 根因（用<b>真的本类</b>离线跑出来的，不是推断）：那一行是
     * {@code §b模块要求：} ＋ {@code 1× } ＋ 物品显示名 ＋ {@code §7（等级 ≥ 17）} 拼起来的，
     * ⇒ <b>同一行里同时出现 U+00A7 与 {@code &$}</b> ⇒ 命中 {@code parse} 里那条
     * 「{@code §} 与 {@code &$} 混用 ⇒ 交回原版」的规则（见类注释 §5.3）
     * ⇒ {@code ours=false} ⇒ 原版渲染把 {@code &$electric-} 当普通字符画出来。
     * <p>⇒ <b>调用方（拼显示文本的那几处）只要把"名字"这一段先剥码</b>，那一行就不再有 {@code &$}，
     * 于是"交回原版"变成一个**正确**的结局：{@code §b}/{@code §7} 照常生效、也不再露码。
     *
     * <h2>🔴🔴 2026-10-01 更正（理由写错了，修法是对的；本方法的**必要性**反而更强了）</h2>
     * 上面那条"根因 = 命中 {@code parse} 的 §/&amp;$ 混用规则"<b>是错的</b>：
     * 那一行文本由 {@code GTRecipeWidget} 用 {@code condition.getTooltips().getString()} 塞进
     * LDLib 的 {@code LabelWidget(int,int,String)}，而它走
     * {@code Font.drawInBatch(String,…)} —— <b>一条完全不经过本类 {@code parse} 的渲染路径</b>
     * （四段 {@code javap} 原文见 {@code com.shanhai.machine.module.ModuleLevelCondition} 类注释 §7）。
     * ⇒ 那次（以及 2026-10-01 那次 {@code &$ultimateRainbow-} 露码）的<b>同一条真根因</b>是：
     * <b>这条路径上任何 {@code &$…-} 都会被原样画出来，与有没有 {@code §} 无关。</b>
     * <p>⇒ 修法（先剥码再做字面量）<b>依然正确</b>，因为"零 {@code &}"就是那条路径的唯一安全形态；
     * 只是别再指望 {@code parse} 帮你在那条路径上把关 —— <b>它不会被调用</b>。
     *
     * <h2>🔴 它<b>不</b>改 {@code parse} 的规则</h2>
     * 那条"混用交回原版"的全局规则<b>一个字都没动</b>（它保护的是本类无法在不验证的情况下
     * 做的下标换算）。本方法是一个**独立的、给调用方用的**工具函数。
     *
     * <h2>行为</h2>
     * <ul>
     *   <li>以 {@code &$} 开头且后面有 {@code -} ⇒ 返回 {@code -} 之后的部分（含效果字符一起丢）；</li>
     *   <li>（2026-10-31）正文区里有 {@code &r} 时，<b>连 {@code &r} 一起丢掉</b>、前后段拼回去
     *       —— 本方法剥的是"码"，{@code &r} 也是码；不丢的话它会在 String 路径上原样显示。
     *       <b>没有 {@code &r} 时返回值与改动前逐位相同</b>（这条是回归主线）；</li>
     *   <li>其它一切情况（没有 {@code &$} / 没有 {@code -} / {@code -} 后为空 / {@code null}）
     *       ⇒ <b>原样返回</b>，绝不返回 {@code null}、绝不返回空串（除非本来就是空串）。</li>
     *   <li>{@code §} 一个都不动（那是原版的合法色码，剥了反而丢颜色）。</li>
     * </ul>
     */
    public static String stripStyleCode(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? "" : raw;
        }
        if (!raw.startsWith("&$")) {
            return raw;
        }
        final int dash = raw.indexOf('-');
        if (dash < 0 || dash + 1 >= raw.length()) {
            return raw;   // 形状不是 `&$…-正文` ⇒ 不动它（交给原版，与 parse 的判据一致）
        }
        final String rest = raw.substring(dash + 1);
        final int restoreIdx = restoreIndex(rest);
        if (restoreIdx < 0) {
            return rest;   // 🔴 没有 &r ⇒ 与 &r 出现之前的返回值逐位相同
        }
        return rest.substring(0, restoreIdx) + rest.substring(restoreIdx + RESTORE_MARKER.length());
    }

    // ================================================================== 探针

    /**
     * <b>零分配</b>的「这条文本可能含我们的码吗」探针。
     *
     * <p>判据就是「有没有 {@code &}」—— 与上游 {@code containsSpecialFormatting} 同款。
     * <b>绝不</b>在这里做子串/正则：{@code Font.drawInBatch} 是每帧每段文字都走的收口，
     * 这里多分配一次，整个游戏都要付钱。
     *
     * <p>调用方自己在字符回调里做判断，本方法只给常量。
     */
    public static char probeChar() {
        return '&';
    }

    // ================================================================== 解析

    /** 便捷判定：这段文本里有没有 {@code &}（不分配）。 */
    public static boolean containsCode(String raw) {
        return raw != null && !raw.isEmpty() && raw.indexOf('&') >= 0;
    }

    /**
     * 解析一条文本。<b>带缓存</b>，同一个串第二次进来不再解析。
     *
     * @param raw {@code null}/空串会直接返回 {@link #NOT_OURS}
     */
    public static Parsed parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return NOT_OURS;
        }
        Parsed cached = CACHE.get(raw);
        if (cached != null) {
            return cached;
        }
        Parsed fresh = parseUncached(raw);
        CACHE.put(raw, fresh);
        return fresh;
    }

    private static Parsed parseUncached(String raw) {
        int ampIdx = raw.indexOf('&');
        if (ampIdx < 0) {
            return NOT_OURS;
        }
        int cursor = ampIdx + 1;
        int dashIdx = raw.indexOf('-', cursor);
        if (dashIdx < cursor) {
            // 没有 `-`：不是 `&$…-` 这个形状。不学上游的 compact 分支（见类注释 §5.2）。
            return NOT_OURS;
        }
        String codePart = raw.substring(cursor, dashIdx);
        int dollarIdx = codePart.indexOf('$');
        if (dollarIdx < 0) {
            // `&` 后面一段里没有 `$` ⇒ 这就是一条普通含 `&` 的文本（例如 "AT&T - 说明"）。
            return NOT_OURS;
        }

        // ---- 效果字符 + 样式名：与分段入口共用 parseCode（2026-11-01 抽出，行为逐位不变） ----
        // 效果字符照抄 parseFlags:148-159：在 codePart 的全段里找，`$` 前后都认。
        // 加粗与 ? / * 同一套判据（扫整个 codePart ⇒ `$` 前后都认），见类注释 §2.2。
        Code code = parseCode(codePart, dollarIdx);
        boolean glitch = code.glitch();
        boolean floatX = code.floatX();
        boolean bold = code.bold();
        String unsupported = code.unsupported();
        // 样式名照抄 parseFlags:161-174：`$` 之后，剥掉紧邻的效果字符。
        String theme = code.theme();
        if (theme.isEmpty()) {
            return NOT_OURS;
        }

        // ---- 正文区：`-` 之后全都算（含可能出现的 &r） ----
        String rest = raw.substring(dashIdx + 1);
        if (rest.isEmpty()) {
            // 见类注释 §5.4：上游会把整条原文当正文（= 把码画出来），我们判「不是我们的码」。
            // ⚠️ 判据在 `rest` 上、不在拆完的 body 上：`&$neon-&r后缀` 是合法的（body 为空、后缀非空），
            //    而 `&$neon-` 仍然是"没有正文"⇒ 非我们的码（与改前逐位一致：没有 &r 时 rest == body）。
            return NOT_OURS;
        }

        // ---- 还原符 &r（2026-10-31，见类注释 §1.1）----
        //   只在正文区里找、只找【第一个】；找不到 ⇒ body = 正文区全部、suffix = ""（= 改前的行为，逐位相同）。
        final int restoreIdx = restoreIndex(rest);
        final String body;
        final String suffix;
        if (restoreIdx < 0) {
            body = rest;
            suffix = "";
        } else {
            body = rest.substring(0, restoreIdx);
            suffix = rest.substring(restoreIdx + RESTORE_MARKER.length());
        }

        String prefix = raw.substring(0, ampIdx);
        String cleanText = prefix + body + suffix;

        if (prefix.indexOf('\u00A7') >= 0 || body.indexOf('\u00A7') >= 0 || suffix.indexOf('\u00A7') >= 0) {
            // 见类注释 §5.3：§ 与 &$ 混在同一行时不做，交回原版（避免我无法验证的下标换算）。
            // 🔴 &r 不改变这条铁律，只把判定面从 prefix+body 扩到 prefix+body+suffix（= 整行）；
            //    suffix 为空时判定面与改前完全相同 ⇒ 输出逐位不变。
            warnUnsupported("mixed_section_sign", theme);
            return NOT_OURS;
        }

        ShanhaiTextPalette.Style style = ShanhaiTextPalette.get(theme);
        if (style == null) {
            // ---- 退化 A：没移植的样式名 ⇒ 剥码 + 不染色（用户裁决 ③） ----
            // 🔴 这里 bold 恒传 false：样式名不认识 ⇒ 整段码（含 `#`）一起被剥掉，见类注释 §3 的加粗说明。
            warnUnsupported("style", theme);
            return new Parsed(true, cleanText, prefix, body, suffix, null, false,
                    false, false, false, theme, unsupported);
        }

        if (!unsupported.isEmpty()) {
            // ---- 退化 B：没实现的效果字符 ⇒ 丢掉那个效果，保留颜色（用户裁决 ③ 的第二条） ----
            warnUnsupported("effect", unsupported);
        }

        return new Parsed(true, cleanText, prefix, body, suffix, style.rgb(), style.isBody(),
                glitch, floatX, bold, theme, unsupported);
    }

    // ================================================================== codePart 解析（单段 / 分段共用）

    /**
     * {@code codePart} 里剥出来的东西 —— <b>{@code &} 之后、{@code -} 之前那一整段</b>的解读结果。
     *
     * <p>🔴 <b>为什么抽出来</b>（2026-11-01，A 案）：单段入口 {@link #parse} 与分段入口
     * {@link #parseSegments} 必须对"哪些是效果字符、哪个是样式名、哪些没实现"给出<b>完全一样</b>的答案。
     * 写两份 = 迟早有一天两边不一致（而那种不一致只会表现成"同一段文字在某些路径上颜色不对"，
     * 极难定位）。抽成一个方法之后，<b>它们不可能不一致</b>。
     *
     * <p>⚠️ <b>抽出后单段入口的行为逐位不变</b>：方法体就是原来 {@code parseUncached} 里那几行的<b>原样搬运</b>
     * （同样的 {@code EFFECT_SYMBOLS} 扫描顺序、同样的跳过集合、同样的"只剥样式名开头的效果字符"）。
     * 判据 = 对拍：1052 条 lang 值 + 一族边界样例，改前实现 vs 改后实现的 {@code cleanText/body/suffix/palette/
     * bold/glitch/floatX/ours} 全字段逐位相同（读数见 {@code temp\segtext\分段兼容.md}）。
     */
    private record Code(boolean glitch, boolean floatX, boolean bold, String unsupported, String theme) {}

    /**
     * 解析 {@code codePart}（含 {@code $}）。
     *
     * @param codePart {@code &} 之后、{@code -} 之前那一整段（<b>含 {@code $} 自己</b>）
     * @param dollarIdx {@code $} 在 {@code codePart} 里的下标（调用方已经找好，这里不再扫）
     */
    private static Code parseCode(String codePart, int dollarIdx) {
        // 效果字符（照抄 parseFlags:148-159：在 codePart 的全段里找，$ 前后都认）
        boolean glitch = codePart.indexOf('?') >= 0;
        boolean floatX = codePart.indexOf('*') >= 0;
        // 加粗：与上面两个同一套判据（扫整个 codePart ⇒ `$` 前后都认），见类注释 §2.2。
        boolean bold = codePart.indexOf(BOLD_SYMBOL) >= 0;
        StringBuilder unsupported = new StringBuilder();
        for (int i = 0; i < EFFECT_SYMBOLS.length(); i++) {
            char sym = EFFECT_SYMBOLS.charAt(i);
            if (sym == '?' || sym == '*' || sym == BOLD_SYMBOL) {
                continue; // 这三个我们做了
            }
            if (codePart.indexOf(sym) >= 0) {
                unsupported.append(sym);
            }
        }

        // 样式名（照抄 parseFlags:161-174：$ 之后，剥掉紧邻的效果字符）
        String theme = codePart.substring(dollarIdx + 1);
        int strip = 0;
        while (strip < theme.length() && EFFECT_SYMBOLS.indexOf(theme.charAt(strip)) >= 0) {
            strip++;
        }
        return new Code(glitch, floatX, bold, unsupported.toString(), theme.substring(strip));
    }

    /**
     * 把 {@link Code} 里<b>我们真做了</b>的那几个效果字符拼成一个诊断串（顺序照 {@link #EFFECT_SYMBOLS}）。
     *
     * <p>只进 {@link Segment#effect()}，<b>不参与任何绘制判断</b>（渲染只看 {@code glitch/floatX/bold} 三个 boolean）。
     */
    private static String effectOf(Code code) {
        StringBuilder sb = new StringBuilder(3);
        for (int i = 0; i < EFFECT_SYMBOLS.length(); i++) {
            char sym = EFFECT_SYMBOLS.charAt(i);
            boolean on = sym == BOLD_SYMBOL ? code.bold()
                    : sym == '?' ? code.glitch()
                    : sym == '*' && code.floatX();
            if (on) {
                sb.append(sym);
            }
        }
        return sb.toString();
    }

    /**
     * 在<b>正文区</b>（{@code -} 之后那一段）里找<b>第一个</b>还原符 {@code &r}。
     *
     * @return {@code &r} 的下标；没找到返回 {@code -1}
     *
     * <p>🔴 <b>为什么单独抽成一个方法</b>：本任务的负对照要求"把找 {@code &r} 这一步临时关掉
     * ⇒ 新语法与边界两组必须报红"。抽出来后，负对照 = 把本方法方法体换成 {@code return -1;}，
     * <b>其余代码一个字节都不动</b> ⇒ 报红只能归因于这一步，不能归因于我顺手改了别处。
     *
     * <p>⚠️ 它<b>只</b>被 {@code parseUncached} 用、<b>只</b>看正文区：{@code prefix} 段与
     * {@code codePart} 段里的 {@code &r} 都不会进来（类注释 §1.1 的边界第 1 条）。
     */
    private static int restoreIndex(String rest) {
        return rest.indexOf(RESTORE_MARKER);
    }

    // ================================================================== 分段解析（A 案）

    /**
     * <b>把一行切成绘制段</b>（纯语法，<b>任何输入都给结果</b>；规则见类注释 §7.1）。
     *
     * <p>⚠️ <b>它不是"要不要接管"的判据</b> —— 渲染层用的闸门是 {@link #mixedSegments}。
     * 本方法只回答"按语法这一行该切成哪几段"，所以<b>对没有 {@code &$} 的行也会返回一个原版段</b>
     * （而不是 {@code null}/{@code 空表}）：这样自证可以直接把它的输出与 {@code parse} 对拍。
     *
     * @param raw {@code null}/空串 ⇒ 单个空的原版段（不返回 {@code null}，调用方不用判空）
     * @return <b>不可变</b>列表；<b>不产出空的段</b>（空原版段 / 空正文段都不会出现，除非整串为空）
     */
    public static List<Segment> parseSegments(String raw) {
        if (raw == null || raw.isEmpty()) {
            return SEG_EMPTY;
        }
        List<Segment> cached = SEG_CACHE.get(raw);
        if (cached != null) {
            return cached;
        }
        List<Segment> fresh = List.copyOf(segmentUncached(raw));
        SEG_CACHE.put(raw, fresh);
        return fresh;
    }

    /**
     * <b>混排行闸门</b>：这一行该不该走分段绘制？该 ⇒ 返回段列表；不该 ⇒ 返回 {@code null}。
     *
     * <p>🔴 <b>三个"不该"</b>（每一条都对应类注释 §7.4 的一个理由）：
     * <ol>
     *   <li><b>没有 {@code §}</b> ⇒ {@code null}：整行可以一次画完，分段只会白白引入
     *       "每段各自 ceil / 各自截断"的 x 误差 ⇒ <b>回归底线</b>；</li>
     *   <li><b>没有 {@code &$}</b> ⇒ {@code null}：全是原版段 ⇒ 交回原版整行画（与改前完全一样）；</li>
     *   <li><b>切完一段我们的都没有</b>（{@code &$} 全是畸形写法 / 全退化成原版段）⇒ {@code null}：
     *       我们没有要画的东西 ⇒ 干脆让原版整行画，<b>不碰</b> x 推进量。</li>
     * </ol>
     */
    public static List<Segment> mixedSegments(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (raw.indexOf(SECTION_SIGN) < 0) {
            return null;   // 没有 § ⇒ 完全走我们（旧路径）
        }
        if (raw.indexOf(SEGMENT_MARK) < 0) {
            return null;   // 没有 &$ ⇒ 完全走原版（旧路径）
        }
        List<Segment> segs = parseSegments(raw);
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i).ours()) {
                return segs;
            }
        }
        return null;
    }

    /**
     * 分段解析本体（<b>不进缓存</b>；规则逐条对应类注释 §7.1 / §7.3）。
     *
     * <p>扫描是<b>一遍过</b>的：{@code p = raw.indexOf("&$", i)} 找段起点，段与段之间的字<b>原样子串</b>
     * 收成原版段（{@code vanillaFrom} 记住"还没交付的原版区间起点"）。
     */
    private static List<Segment> segmentUncached(String raw) {
        final int n = raw.length();
        final List<Segment> out = new ArrayList<>(4);
        int vanillaFrom = 0;
        int i = 0;
        while (i < n) {
            final int p = raw.indexOf(SEGMENT_MARK, i);
            if (p < 0) {
                break;
            }
            // ---- 1) 找 `-`，但**不许越过下一个 `&$`**（否则会把下一段的 `-` 抢过来） ----
            final int nextSeg = raw.indexOf(SEGMENT_MARK, p + SEGMENT_MARK.length());
            final int limit = nextSeg < 0 ? n : nextSeg;
            final int dash = raw.indexOf('-', p + SEGMENT_MARK.length());
            if (dash < 0 || dash >= limit) {
                // 形状不对（这一段没有 `-`）⇒ 这串 `&$` 当普通文字，留在原版段里（与 parse 的 §5.2 同一条）
                i = p + SEGMENT_MARK.length();
                continue;
            }
            // ---- 2) codePart：与 parseUncached 完全同一个形状（`&` 之后到 `-` 之前，含 `$`）----
            final String codePart = raw.substring(p + 1, dash);
            final int dollarIdx = codePart.indexOf('$');   // 恒为 0：`&$` 的 `$` 就在 p+1
            final Code code = parseCode(codePart, dollarIdx);
            if (code.theme().isEmpty()) {
                // 没有样式名（`&$-正文` / `&$?-正文`）⇒ 形状不对 ⇒ 当普通文字
                i = p + SEGMENT_MARK.length();
                continue;
            }
            // ---- 3) 正文：到 `&r` / 下一个 `&$` / 串尾，三者取最早 ----
            final int bodyFrom = dash + 1;
            final int endR = raw.indexOf(RESTORE_MARKER, bodyFrom);
            final int endS = raw.indexOf(SEGMENT_MARK, bodyFrom);
            final int stop;      // 正文结束下标
            final boolean eatsR; // 结束符是不是 `&r`（是 ⇒ 它被吃掉，不进任何段）
            if (endR < 0 && endS < 0) {
                stop = n;
                eatsR = false;
            } else if (endR >= 0 && (endS < 0 || endR <= endS)) {
                stop = endR;
                eatsR = true;
            } else {
                stop = endS;
                eatsR = false;
            }
            final String body = raw.substring(bodyFrom, stop);
            if (body.isEmpty()) {
                // 见类注释 §5.4 的同一条判据：`&$neon-` 没有正文 ⇒ 不是一段 ⇒ 当普通文字（码可见，字不消失）。
                // ⚠️ 与单段路径的差别只在"判定面"：那边判整条，这边判这一段。
                i = p + SEGMENT_MARK.length();
                continue;
            }
            // ---- 4) 段内 § ⇒ 该段退化（丢样式码、正文交原版）----
            final int sectAt = raw.indexOf(SECTION_SIGN, p);
            if (sectAt >= 0 && sectAt < stop) {
                warnUnsupported("seg_section_sign", code.theme());
                flushVanilla(out, raw, vanillaFrom, p);
                addVanilla(out, body);
                i = eatsR ? stop + RESTORE_MARKER.length() : stop;
                vanillaFrom = i;
                continue;
            }
            // ---- 5) 切出来 ----
            flushVanilla(out, raw, vanillaFrom, p);
            final ShanhaiTextPalette.Style style = ShanhaiTextPalette.get(code.theme());
            if (style == null) {
                // 退化 A：样式名不认识 ⇒ 仍是我们的段，但无颜色、不加粗（与单段路径逐条一致）
                warnUnsupported("style", code.theme());
                out.add(new Segment(true, body, null, false, "", false, false));
            } else {
                if (!code.unsupported().isEmpty()) {
                    // 退化 B：没实现的效果字符本来就在 codePart 里（不会被画）⇒ 保留颜色、丢掉那个效果
                    warnUnsupported("effect", code.unsupported());
                }
                out.add(new Segment(true, body, style, code.bold(), effectOf(code),
                        code.glitch(), code.floatX()));
            }
            i = eatsR ? stop + RESTORE_MARKER.length() : stop;
            vanillaFrom = i;
        }
        // ---- 6) 尾巴上的原版段 ----
        flushVanilla(out, raw, vanillaFrom, n);
        if (out.isEmpty()) {
            return List.of(Segment.vanilla(raw));
        }
        return out;
    }

    /** 把 {@code raw[from,to)} 作为<b>原版段</b>收进去；空区间<b>不产出</b>段（见类注释 §7.1 第 3 条）。 */
    private static void flushVanilla(List<Segment> out, String raw, int from, int to) {
        if (to > from) {
            addVanilla(out, raw.substring(from, to));
        }
    }

    /**
     * 收一个原版段，并<b>与紧邻的前一个原版段合并</b>。
     *
     * <p>为什么要合并：退化段（段内出现 {@code §}）的两侧在原文里<b>不连续</b>（中间那串样式码被丢掉了）
     * ⇒ 会切出「原版段 / 退化段 / 原版段」这种<b>三个连着的原版段</b>。
     * 三个都交给原版画，结果虽然等价，但白白多两次 {@code drawInBatch}、也多两次 x 取整
     * ⇒ 合并成一段。
     * <p>⚠️ 合并只改变"调用几次"，<b>不改变字节</b>：拼接顺序就是原文顺序，
     * 且两段都已经是"不含我们的样式码"的原样子串。
     */
    private static void addVanilla(List<Segment> out, String text) {
        if (text.isEmpty()) {
            return;
        }
        final int last = out.size() - 1;
        if (last >= 0 && !out.get(last).ours()) {
            out.set(last, Segment.vanilla(out.get(last).text() + text));
            return;
        }
        out.add(Segment.vanilla(text));
    }

    // ================================================================== 限流 WARN

    /** 两条 WARN 之间至少隔多少毫秒 —— 照本工程既有做法（{@code HIDE_LOG_MIN_GAP_TICKS} 的精神）。 */
    private static final long WARN_MIN_GAP_MS = 5000L;

    private static final AtomicLong LAST_WARN_MS = new AtomicLong(Long.MIN_VALUE / 2);
    private static final AtomicLong WARN_SUPPRESSED = new AtomicLong();

    /**
     * 限流告警：每 {@value #WARN_MIN_GAP_MS} ms 最多一行，并带上被折叠掉的行数。
     *
     * <p>🔴 <b>只限流不报数 = 静默</b>（本工程的血账）。所以被折叠的那些会累加到 {@code suppressed=} 字段里，
     * 下一次真正打出来的那一行会把它清掉，读日志的人就知道「这中间还发生过 N 次」。
     */
    private static void warnUnsupported(String kind, String name) {
        long now = System.currentTimeMillis();
        long last = LAST_WARN_MS.get();
        if (now - last < WARN_MIN_GAP_MS) {
            WARN_SUPPRESSED.incrementAndGet();
            return;
        }
        if (!LAST_WARN_MS.compareAndSet(last, now)) {
            WARN_SUPPRESSED.incrementAndGet();
            return;
        }
        long suppressed = WARN_SUPPRESSED.getAndSet(0L);
        ShanhaiMod.LOGGER.warn(
                "[SHANHAI-SPEC] font_style_unsupported kind={} name={} suppressed={}",
                kind, name, suppressed);
    }

    // ================================================================== 效果数学（照抄上游）

    /**
     * {@code *} floatX 的水平位移 —— 照抄 {@code TextFormatParser.calcWaveX:239-241}。
     *
     * <pre>cos(charIndex * 0.7 + time * 0.0015) * 2.0</pre>
     */
    public static float waveX(int charIndex, long time) {
        return (float) (Math.cos(charIndex * 0.7 + time * 0.0015) * 2.0);
    }

    /** {@code ?} glitch 的水平位移 —— 照抄 {@code calcGlitchX:255-259}。 */
    public static float glitchX(int charIndex, long time) {
        long phase = (time / 55L) + charIndex * 17L;
        if ((phase % 7L) > 1L) {
            return 0.0f;
        }
        return ((phase % 3L) - 1L) * 1.5f;
    }

    /** {@code ?} glitch 的垂直位移 —— 照抄 {@code calcGlitchY:261-265}。 */
    public static float glitchY(int charIndex, long time) {
        long phase = (time / 65L) + charIndex * 23L;
        if ((phase % 9L) > 1L) {
            return 0.0f;
        }
        return ((phase % 3L) - 1L) * 1.0f;
    }

    /**
     * {@code ?} glitch 的偶发掺色 —— 照抄 {@code applyGlitchColor:294-299}。
     *
     * <pre>
     * phase = (time/60) + charIndex*31
     * phase % 11 == 0  ⇒ 向 0x55FFFF 混 65%   （偏青）
     * phase % 13 == 0  ⇒ 向 0xFF5555 混 55%   （偏红）
     * </pre>
     */
    public static int applyGlitchColor(int color, int charIndex, long time) {
        long phase = (time / 60L) + charIndex * 31L;
        if ((phase % 11L) == 0L) {
            return mixColor(color, GLITCH_CYAN, 0.65f);
        }
        if ((phase % 13L) == 0L) {
            return mixColor(color, GLITCH_RED, 0.55f);
        }
        return color;
    }

    /** 线性混色 —— 照抄 {@code TextFormatParser.mixColor:301-314}（含 {@code amount} 的两个夹取）。 */
    public static int mixColor(int base, int target, float amount) {
        if (amount <= 0.0f) {
            return base;
        }
        if (amount > 1.0f) {
            amount = 1.0f;
        }
        int br = (base >> 16) & 0xFF;
        int bg = (base >> 8) & 0xFF;
        int bb = base & 0xFF;
        int tr = (target >> 16) & 0xFF;
        int tg = (target >> 8) & 0xFF;
        int tb = target & 0xFF;
        int r = (int) (br + (tr - br) * amount);
        int g = (int) (bg + (tg - bg) * amount);
        int b = (int) (bb + (tb - bb) * amount);
        return (r << 16) | (g << 8) | b;
    }

    // ================================================================== 加粗的宽度补偿

    /**
     * 加粗让一段文字<b>多推进</b>多少像素 —— 供
     * {@code ShanhaiFontStyleRenderer#widthOf} 修正宽度用（纯函数，只依赖 {@code java.lang.String}）。
     *
     * <p>🔴 <b>为什么必须补</b>：{@code widthOf} 量的是 {@code self.width(parsed.cleanText())}，
     * 而 {@code Font.width(String)} 内部走的是 {@code Style.EMPTY}（证据：{@code javap -c}
     * {@code StringSplitter.stringWidth(String)} 转调 {@code stringWidth(String, Style.EMPTY)}）
     * ⇒ <b>它量出来的宽度【不含加粗】</b>，而真正画出去的是含 {@code bold} 的 FCS。
     * 不补的话这两块板在居中显示（tooltip / JEI）时每字会偏 1 px。
     *
     * <p><b>数值从哪来</b>（{@code javap -c} 原文，不是推断）：
     * <pre>
     *   com.mojang.blaze3d.font.GlyphInfo.getAdvance(boolean):
     *      0: aload_0
     *      1: invokeinterface getAdvance:()F
     *      6: iload_1
     *      7: ifeq  19
     *     10: invokeinterface getBoldOffset:()F
     *     19: fconst_0
     *     20: fadd
     *     21: freturn
     *   com.mojang.blaze3d.font.GlyphInfo.getBoldOffset():
     *      0: fconst_1
     *      1: freturn                                        → 默认就是 1.0f
     * </pre>
     * ⇒ 每个字形恰好多 <b>1.0 px</b>。
     *
     * <p><b>为什么是「码点数」而不是「字符数/字节数」</b>：加粗是按<b>字形</b>（glyph）算的，
     * 而 {@code Font} 是按<b>码点</b>取字形的 ⇒ 代理对（emoji 等）只算 1 个字形的钱。
     *
     * <p>⚠️ <b>已知边界（如实列出）</b>：
     * <ol>
     *   <li>假设被量的这段文字<b>没有换行符</b>。有 {@code \n} 时 {@code Font.width(String)}
     *       仍是整串累加，而本方法也整串数码点 ⇒ 两者其实仍同口径；但一旦原版改成「按行取最大」，
     *       这条就不成立。我们的 96 条物品名与 6 行横幅<b>一个换行都没有</b>（离线自证里有这条断言）。</li>
     *   <li>假设没有资源包覆写 {@code GlyphInfo.getBoldOffset()}。原版 {@code TrueTypeGlyphProvider}
     *       及其内部类<b>没有</b>覆写它（取证：{@code javap -p} 的该 class 方法表里无 {@code getBoldOffset}）。</li>
     * </ol>
     */
    public static int boldAdvance(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.codePointCount(0, text.length());
    }
}
