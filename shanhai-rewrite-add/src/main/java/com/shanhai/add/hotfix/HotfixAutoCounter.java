package com.shanhai.add.hotfix;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 第 3 档：<b>主动出击</b>——分诊判 {@code solved=false} 时，把那条调用栈上第一个"不是白名单、
 * 且来自 {@code mods\}} 的家伙"的方法体换成安全默认返回。<b>默认开（2026-11-20 用户拍板：热改线路默认开）</b>。
 *
 * <h2>三道门槛（缺一不可，任何一道不过就只记日志）</h2>
 * <pre>
 *   ① 开关  auto=true（默认开；关掉走 /shanhai_add hotfix auto off ⇒ 立刻 undoAll）
 *   ② 判据  本体这一拍 solved=false（由 {@link HotfixTriageTap} 从本体日志里读出来）
 *   ③ 白名单 目标类不在 {java.,javax.,jdk.,sun.,com.mojang.,net.minecraft.,net.minecraftforge.,
 *            cpw.,org.spongepowered.,com.shanhai.,org.gtlcore.,com.gtladd.} ＋ 它的 getCodeSource()
 *            必须指到 mods\（判据与理由在 {@link HotfixPatchWhitelist}；每次判定都打一行
 *            {@code whitelist_accepted|refused cls=… raw=… normalized=… reason=… loader=…}）
 * </pre>
 *
 * <h2>它做了什么、绝对没做什么</h2>
 * <ul>
 *   <li><b>只改一个方法的方法体</b>：换成"安全默认返回"（void⇒RETURN、boolean⇒false、数值⇒0、
 *       引用⇒null），签名/访问标志/继承/字段表一个字不动（{@link HotfixMethodBodyPatch} 的
 *       三道自证会验证这一点，不过就整份拒绝）。</li>
 *   <li><b>不碰判定行为</b>：不 cancel 事件、不写血量、不改本体的任何判据。本类不认识 MC 的任何类型。</li>
 *   <li><b>改之前存原字节</b>：{@link Patch#original}，{@link #undo(String)} /
 *       {@link #undoAll(String)} 用它做还原的第二道保险。</li>
 *   <li><b>失败只打日志</b>：整条链每一步都包在 try/catch 里，最坏结果是"这一次没改成"。</li>
 * </ul>
 *
 * <h2>为什么动作在独立线程上跑（而不是在日志回调里）</h2>
 * 触发点是"日志里出现了一行"。log4j 对同一线程的递归 log 会<b>静默丢弃</b>
 * ⇒ 在 appender 里直接 {@code retransformClasses} + 打日志，那几行日志会消失。
 * 所以 {@link #onVerdict} 只入队，真正的活（和它那些 {@code auto_counter …} 行）在
 * daemon 线程 {@code shanhai-hotfix-autocounter} 上干。
 *
 * <h2>日志（可 grep，字段顺序固定；后半段的 {@code match=}/{@code strike_*} 是附加证据）</h2>
 * <pre>
 *   [SHANHAI-HOTFIX] auto_counter target=&lt;类&gt;#&lt;方法&gt; desc=&lt;描述符|?&gt; why=&lt;r3|r4|r2|r1&gt; \
 *        result=patched|refused|failed detail=… orig_bytes=&lt;n&gt; new_bytes=&lt;n&gt; match=… default=… strike_…
 *   [SHANHAI-HOTFIX] patch_apply target=… classes_retransformed=&lt;n&gt; same_class_identity=&lt;true|false&gt; \
 *        loader=… attempt=… same_name_classes=…
 *   [SHANHAI-HOTFIX] patch_probe target=… result=ineffective|ok evidence=violations_after_patch=&lt;n&gt; \
 *        window_ms=5000 …
 *   [SHANHAI-HOTFIX] retaliate target=… attempt=&lt;n&gt; result=repatch|failed|deferred …
 *   [SHANHAI-HOTFIX] retaliate_give_up target=… attempts=5 reason=throttled window_ms=60000
 *   [SHANHAI-HOTFIX] auto_counter_undo target=… result=restored|failed …
 *   [SHANHAI-HOTFIX] auto_counter_undoall trigger=… count=… restored=… failed=…
 * </pre>
 *
 * <h2>🆕 2026-11-08：为什么多了"生效探针 ＋ 反制"</h2>
 * 真实现场（12:41）是：三个目标都 {@code result=patched}，而行为<b>一点没变</b>
 * —— patch 之后仍 {@code setHealth_guard value_was=-57.0} 共 29 条，
 * 且 {@code violation_stack … first_external=…ExampleHelper#doHurt:375}。
 * 也就是说「我改成功了」这件事<b>只有账本自己说</b>。所以补两件事：
 * <ol>
 *   <li><b>生效探针</b>（{@link HotfixPatchProbe} ＋ {@link HotfixPatchWatch}）：patch 之后开一个
 *       5 秒窗口，窗口内只要再来一条<b>抓栈时刻晚于 patch</b> 的违规、且栈里还有这个 target
 *       ⇒ 判 {@code ineffective}（那个方法体若真成了 {@code RETURN}，它根本没法再出现在
 *       调用 {@code setHealth} 的栈上）；窗口关了都没再见 ⇒ {@code ok}。
 *       判据是<b>纯 JDK 函数</b>，离线可自证（{@code temp\hotfix\offline-probe\}）。</li>
 *   <li><b>反制</b>：判死之后重新 {@code undo → 重新判白名单 → 重新 patch}，
 *       带指数退避与"60 秒 5 次"上限；顶上限记一行 {@code retaliate_give_up} 后<b>永久停手</b>
 *       （绝不允许双方打成无限循环）。</li>
 * </ol>
 * ⚠️ 两条纪律：① 重打<b>从不</b>跳过白名单（每次 {@link #apply} 都重新判、每类都判、都留一行）；
 * ② 全部失败路径只打 WARN 级以下的日志、不抛。
 *
 * <h2>🆕 2026-11-08：读回取证（"账本 patched ≠ 类里那份是我们写的"）</h2>
 * 生效探针只能证明"这个方法<b>还在跑</b>"（行为侧，5 秒窗口）；它<b>不能</b>证明"类里那份字节
 * 到底是谁"，也分不出「根本没改进去」和「改进去了、后来又被别人织回去」。
 * 而用户的判断正是后者：<b>它自己带的 agent 又把类织回去了</b>。
 *
 * <p>🔴 <b>按任务书那样"patch 后立刻 retransform 一次读回"是行不通的</b> —— 2026-11-08 13:0x
 * 用真 JVM 量身定做地量过（{@code temp\hotfix\offline-readback\RbPremise.java}）：
 * <ol>
 *   <li>{@code retransform} 交给 transformer 的是<b>初始基线</b>，<b>不是</b>"上次定义后的版本"
 *       ⇒ 读回永远看不到我们写下去的字节；</li>
 *   <li>一次"全 null"的 retransform 会把类<b>退回初始字节</b>（= undo）
 *       ⇒ 在 patch 后做"只读读回"，等于 1 tick 后<b>自己把补丁撤掉</b>
 *       （第一版就这么写的，离线套件 {@code a2.verify_changed}／{@code a4.mods_segment_still_patches}
 *       当场变红）；</li>
 *   <li>同一类里<b>第二个 patch 会把第一个 patch 撤掉</b>（还是因为"从基线出发"）
 *       ⇒ 这是「账本 patched、行为没变」的一条<b>新的、可判</b>的机制。</li>
 * </ol>
 * 所以这里的落法是"只报能观测的事实"：
 * <pre>
 *   [SHANHAI-HOTFIX] patch_readback target=&lt;类#方法&gt; orig=&lt;hash16&gt;/&lt;len&gt; \
 *        patched=&lt;hash16&gt;/&lt;len&gt; live=?/? verdict=unknown verdict_reason=not_observable \
 *        mixin_wrappers=&lt;n&gt; same_class_targets=&lt;n&gt; rescinded=&lt;n&gt; …
 *   [SHANHAI-HOTFIX] patch_reverted target=&lt;被撤掉的旧目标&gt; action=none reason=observed_only
 *   [SHANHAI-HOTFIX] patch_readback … live=&lt;hash16&gt;/&lt;len&gt; verdict=reverted \
 *        verdict_reason=undo_confirmed sample=reset        （只在还原之后取样，见下）
 * </pre>
 * <ul>
 *   <li><b>patch 之后</b>（{@link #readbackAfterPatch}）：<b>一次 retransform 都不做</b>。
 *       落两份指纹 ＋ Mixin 包装数 ＋ "同类互撤"的账；当前字节采不到就写
 *       {@code live=?/? verdict=unknown verdict_reason=not_observable}，
 *       <b>绝不拿基线冒充 live</b>（那会把每个正常补丁报成"被织回去了"）。</li>
 *   <li><b>还原之后</b>（{@link #readbackNow}）：<b>这是唯一安全的取样时刻</b>（"退回基线"正是刚做的事），
 *       sample == 当前状态 ⇒ {@code live==orig} 可以真判 {@code reverted}
 *       （{@code verdict_reason=undo_confirmed}），它证明的是<b>"还原真的落地了"</b>；
 *       这种 reverted <b>不</b>打 {@code patch_reverted}（不是别人织回去的）。</li>
 * </ul>
 * 🔴 <b>判出 reverted 不许自动重打</b>：重打策略在另一条线（退避/上限/白名单重判），
 * 这里只负责"看见了"，写成 {@code action=none reason=observed_only}。
 * <p>⚠️ 与判据一起读的局限（细节在 {@link HotfixPatchReadback} 的类注释）：
 * ① {@code retransformClasses} 会回调<b>所有</b>可重定义的 transformer（含 Mixin 的）；
 * ② 基线样本与磁盘 class 的常量池排布不同 ⇒ 只比哈希、不做任何偏移断言。
 *
 * <h2>🔴 为什么不用"字节偏移比对／替换"</h2>
 * 实测：{@code retransform} 交下来的 {@code classfileBuffer} 常量池被合并过，同一偏移处的字节已经不是
 * 磁盘那份 class 里的东西。所以这里一律走 ASM 结构级改写，并且<b>不做任何跨版本的字节断言</b>
 * （{@code new_bytes != orig_bytes} 是正常的，不是 bug）。
 *
 * <p>本类依赖面：{@code java.*} ＋ ASM ＋ {@link Instrumentation} —— 没有 MC/Forge/日志框架，
 * 所以它能被<b>脱离游戏</b>的离线自检直接跑（{@code temp\hotfix\offline-autocounter\}）。
 */
public final class HotfixAutoCounter {

    /** 本模块统一前缀（与 {@code HotfixBootstrap.PREFIX} 同一个字面量，故意重复：本类不引用那个类）。 */
    public static final String PREFIX = "[SHANHAI-HOTFIX]";

    /** 日志出口（游戏侧接 log4j，离线自检接 stdout；{@code null} = 静默）。 */
    public interface Log {
        void line(String text);

        /**
         * 失败/跳过路径的出口（游戏侧接 WARN，离线自检默认落回 {@code line}）。
         *
         * <p>为什么要有它：读回取证的<b>全部失败路径只许 WARN</b>（它不是玩家行为、也不许
         * 让人以为"热改出事了"）。默认实现落回 {@link #line(String)} ⇒ 老的离线 sink
         * 一个字都不用改就能编过。
         *
         * @param text 一行文本（不含 {@link #PREFIX}）
         */
        default void warn(String text) {
            line(text);
        }

        /**
         * 🆕 2026-11-20：<b>ERROR</b> 级出口（只给 {@code patch_lost} 用）。
         *
         * <p>为什么这一条必须是 ERROR 而不是 WARN：{@code patch_lost} 的含义是
         * "<b>我们写下去的字节被人换掉了，而且重打一次也没用</b>" —— 那一刻这个目标已经
         * <b>不再受我们保护</b>。这不是"这次没读到"（那是 WARN），是防线掉了。
         *
         * <p>默认实现落回 {@link #warn(String)} ⇒ 老的离线 sink 一个字都不用改就能编过
         * （与 {@link #warn} 同一个兼容思路）。
         *
         * @param text 一行文本（不含 {@link #PREFIX}）
         */
        default void error(String text) {
            warn(text);
        }
    }

    // ═══════════════════════════════ 🆕 热改播报（2026-11-08，用户点单） ═══════════════════════════════
    //
    // 用户口径（逐字）：把「主动热改废掉敌方代码」这件事播报到玩家聊天栏，三条样例：
    //   [创始现实修改模块] PATCHED examplemod ExampleHelper#doHurt -> RETURN (health)
    //   [创始现实修改模块] PATCH_REFUSED examplemod ExampleHelper#doHurt (gate_already_patched)
    //   [创始现实修改模块] PATCH_REVERTED examplemod ExampleHelper#doHurt (observed_only)
    //
    // 🔴 分工（为什么这里只出"事实"）：
    //   引擎 = 纯 JDK（离线自检跑同一份字节码）⇒ 这里<b>不许</b> import MC/Forge；
    //   于是"查 modid / 找玩家 / 投聊天栏"全在装配那一侧（{@code HotfixPatchChat}），
    //   正文拼装在纯 JDK 的 {@code HotfixPatchChatText}（离线也能逐字断言）。
    //   **通道本身没有新建**：最终发的是本体守卫的同一条聊天栏通道（同前缀／同闸门／同收件人规则）。
    //
    // 四个落点（与需求一一对应）：
    //   ① {@code result=patched}           ⇒ 每个 applied 方法一条 PATCHED（**只发一次**，见 CHAT_PATCHED_KEYS）
    //   ② {@code result=refused}           ⇒ PATCH_REFUSED … (<detail> 原值)
    //   ③ {@code patch_reverted}           ⇒ PATCH_REVERTED … (<reason> 原值；只报不重打)
    //   ④ 没有玩家上下文（控制台／离线）    ⇒ 一条都不发（日志照旧）

    /**
     * 🔴 <b>热改播报的出口</b>（由装配胶水注入；{@code null} = 没装 ⇒ 不播）。
     *
     * <p>接口只认 {@link String} 与 {@link List}（零 MC 类型），这样本类保持"能被离线自检直接跑"。
     */
    public interface PatchChat {

        /**
         * 把这一批事实播给那个玩家。
         *
         * @param playerName 收件人玩家名（{@code null} ⇒ 没有玩家上下文，一条都不发）
         * @param notes      同一批事实（一次 patch 改了同类多个方法 ⇒ 多条 ⇒ 播成多行）
         * @return 真的交给通道几条（0 = 没发）
         */
        int announce(String playerName, List<PatchNote> notes);

        /**
         * 🆕 2026-11-21（P2 追加）：<b>播一行成品正文</b>（走同一条通道、同一个闸门、同一个收件人）。
         *
         * <p>为什么需要它：{@link PatchNote} 只能表达"动作词 ＋ 一个类 ＋ 一个方法 ＋ 括号里原值"，
         * 而这次两条新播报的正文是<b>聚合事实</b>（{@code WIPED <owner> <N> 类 <M> 方法（整链抹除）}）
         * ／{@code GIVEUP <类名>（…）}），塞不进那个四元组。
         * 默认实现返回 0（老实现不改也不会坏；{@code null} 出口照旧一条都不发）。
         *
         * @param playerName 收件人玩家名（{@code null}/空 ⇒ 不发）
         * @param body       正文（<b>不含</b>前缀；前缀由通道那一侧统一加）
         * @return 真的交给通道几条（0 = 没发）
         */
        default int say(String playerName, String body) {
            return 0;
        }

        /**
         * 🆕 2026-11-21（P2 追加）：<b>一次投多行成品正文</b>（同一批、走同一次通道调用）。
         *
         * <p>为什么需要：合并后的 {@code PATCHED} 是"<b>每类一行</b>"，一波里可能有 3 个类 ⇒
         * 3 行；分 3 次 {@code say} 会让通道的"每秒一行"闸门把它们压成 1 行。
         * 默认实现逐行退化为 {@link #say(String, String)}（老实现不改也不会坏）。
         *
         * @return 真的交给通道几条
         */
        default int sayLines(String playerName, List<String> bodies) {
            int n = 0;
            for (int i = 0; bodies != null && i < bodies.size(); i++) {
                if (say(playerName, bodies.get(i)) > 0) {
                    n++;
                }
            }
            return n;
        }

        /**
         * 🆕 2026-11-21（P2 追加）：这个类属于哪个 mod（播报里的 {@code <owner>}）。
         *
         * <p>引擎是纯 JDK 的，拿不到 {@code ModList} ⇒ 由装配那一侧（{@code HotfixPatchChat}）
         * 实现；默认返回空串 ⇒ 播报里退化成 {@code unknown}（绝不猜）。
         */
        default String ownerOf(String className) {
            return "";
        }
    }

    /** 一条播报事实：动作词 ＋ 目标（类全名／方法名）＋ 括号里那个<b>原值</b>。 */
    public static final class PatchNote {

        /** 动作词（{@code PATCHED}／{@code PATCH_REFUSED}／{@code PATCH_REVERTED}，见 {@link HotfixPatchChatText}）。 */
        public final String word;

        /** 目标类全名（账本键 {@code 类#方法} 的前半段；播报时只取简单类名）。 */
        public final String className;

        /** 方法名（<b>SRG 原样</b>，如 {@code m_8119_}）。 */
        public final String method;

        /** {@code why=}／{@code detail=}／{@code reason=} 的原值（不翻译、不改写）。 */
        public final String arg;

        public PatchNote(String word, String className, String method, String arg) {
            this.word = word;
            this.className = className;
            this.method = method;
            this.arg = arg;
        }

        @Override
        public String toString() {
            return word + " " + className + "#" + method + " (" + arg + ")";
        }
    }

    /** 🔴 播报出口（装配胶水 {@code HotfixPatchChat} 注入；{@code null} = 没装 ⇒ 一条都不播）。 */
    private static volatile PatchChat chatSink;

    /** 🆕 2026-11-08：真的投给播报出口多少条（读数用；判"这套播报到底有没有接上"只看它）。 */
    private static final AtomicLong CHAT_ANNOUNCED = new AtomicLong();

    /**
     * 🔴 <b>wanted 集合（曾经被我们 patch 过、且没有显式 undo 的方法）</b> —— 一张表两用：
     * <ol>
     *   <li><b>播报去重</b>：{@code PATCHED} 每条只发一次（按类累计之后老方法会被反复"再套一遍"，
     *       没有这张表就会每次重算都发）；</li>
     *   <li><b>重建依据</b>（2026-11-08 13:43 复发后的正解）：组 {@code plan} 时<b>不能</b>只看
     *       {@code PATCHED}（"目前还在册"的那几个）—— 被判失效／退役的目标会从账本里消失，
     *       于是同类第二刀又只套自己一个，把第一刀顶掉（P3 复发）。
     *       判据改成："<b>只要曾经 patch 过、且没有显式 undo，就永久留在这里</b>"
     *       ⇒ 退役只影响"是否还在存活判定"，不影响"重建时要不要一起套"。</li>
     * </ol>
     *
     * <p>🔴 与 {@code PATCHED} 的分工：{@code PATCHED} = <b>现在改着谁</b>（undo 的唯一依据）；
     * 本表 = <b>应该改着谁</b>（重建/keepalive 的依据）。两者语义不同，缺一不可。
     *
     * <p>谁写它：{@link #apply} 成功后经 {@link #announcePatched} 落盘（{@code putIfAbsent}，
     * 与"有没有聊天出口"无关）；谁删它：只有<b>显式</b> {@code undo}/{@code undoall}（{@link #undoLocked}
     * 的 {@code retireWatch=true} 那条路）。反制里的"先 undo 再重打"<b>不删</b>。
     */
    private static final Map<String, Want> CHAT_PATCHED_KEYS = new LinkedHashMap<>();

    /**
     * 本次 patch／读回属于哪个玩家（<b>工作线程上的一个"本次上下文"</b>）。
     *
     * <p>为什么用 {@link ThreadLocal}：{@link #handle}／{@link #tick}／{@link #selftest} 都可能走到
     * {@code refuse}／{@code readbackAfterPatch}，而只有 {@code handle} 手上才有"这次是谁被打了"。
     * 用线程局部量就不用给那十几个内部方法都加一个 player 参数（那是几十处签名改动）。
     * 主线只有一条工作线程；命令线程跑的 {@code selftest} 会是"没有玩家上下文" ⇒ 不播（正是想要的）。
     */
    private static final ThreadLocal<String> CHAT_PLAYER = new ThreadLocal<>();

    /**
     * 装播报出口（幂等；由 {@code HotfixAutoCounterGlue} 在进世界时调）。
     *
     * @param sink 出口实现；{@code null} = 卸载（之后一条都不播）
     */
    public static void bindChatSink(PatchChat sink) {
        chatSink = sink;
    }

    /** 播报出口装没装（{@code statusLine()}／日志判读用）。 */
    public static boolean chatSinkBound() {
        return chatSink != null;
    }

    /**
     * 默认值：{@code auto} <b>默认开</b>（2026-11-20 用户拍板：热改线路默认开）。
     *
     * <p>🔴 与 {@code attack_side} <b>完全解耦</b>：{@code attack_side=false} 只关"自动抹除攻击者 ＋
     * 持续压制"，<b>不许</b>顺带把热改也关掉。本类<b>不引用</b> {@code ShanhaiGuardSwitches}
     * （纯 JDK 引擎，认不了那个类），热改只看它自己的 {@link #AUTO}。
     *
     * <p>关掉的入口仍然是 {@code /shanhai_add hotfix auto off}（关的时候立刻 {@code undoAll} 并打一行）。
     */
    public static final boolean DEFAULT_AUTO = true;

    /** 抓栈保鲜期（与 {@link HotfixTriageTap} 同一个值；编译期常量，不会因此加载那个类）。 */
    public static final long STRIKE_TTL_MILLIS = HotfixTriageTap.STRIKE_TTL_MILLIS;

    /**
     * 🆕 违规现场 strike 的保鲜期（{@code 30 000 ms}）：来自 {@link HotfixTriageTap#submitViolation}
     * 的那条路（{@code why=health|die|remove|motion|pos|attack}）。
     *
     * <p>为什么与 {@link #STRIKE_TTL_MILLIS} 不同、为什么是 30 s：依据写在
     * {@link HotfixTriageTap#VIOLATION_STRIKE_TTL_MILLIS} 的 javadoc 里（本体只在它自己计数器
     * 有话说时才 flush {@code solved=false} ⇒ "现场已经发生、结论晚几拍才来"是常态）。
     */
    public static final long VIOLATION_STRIKE_TTL_MILLIS = HotfixTriageTap.VIOLATION_STRIKE_TTL_MILLIS;

    /** 队列上限：日志里一车 {@code solved=false} 时宁可丢，也不许把内存吃光。 */
    public static final int MAX_QUEUED = 64;

    // ═══════════════════════════════ 🆕 读回取证 ═══════════════════════════════

    /**
     * 取样前的稳定延迟：{@value HotfixPatchReadback#DELAY_MILLIS} ms ≈ <b>1 tick</b>（20 TPS）。
     *
     * <p>用在哪：只用在 {@link #readbackNow}（<b>还原之后</b>的那次取样）。
     * patch 之后<b>不取样</b>（实测：那会拿到基线、且会把补丁退回基线），所以那条路上没有延迟可用 ——
     * 任务书要求"1 tick"，这里把它保留在唯一真正发生取样的位置。
     *
     * <p>为什么是 1 tick：{@code retransformClasses} 返回只代表"定义已经提交"，
     * 让这一轮 retransform/deopt 收尾再读，样本才是稳定的那一份；再长就会混进战斗阶段的正常变化。
     */
    public static final long READBACK_DELAY_MILLIS = HotfixPatchReadback.DELAY_MILLIS;

    private static final AtomicBoolean AUTO = new AtomicBoolean(DEFAULT_AUTO);

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final Map<String, Patch> PATCHED = new LinkedHashMap<>();
    private static final LinkedBlockingQueue<Job> JOBS = new LinkedBlockingQueue<>(MAX_QUEUED);
    private static final AtomicInteger QUEUED = new AtomicInteger();

    /** 队列排空等待用的监视器（不拿 {@link AtomicInteger} 本身当锁：JDK 会嫌它"值对象当锁"）。 */
    private static final Object QUEUE_LOCK = new Object();

    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong HANDLED = new AtomicLong();
    private static final AtomicLong PATCH_COUNT = new AtomicLong();
    private static final AtomicLong REFUSE_COUNT = new AtomicLong();
    private static final AtomicLong FAIL_COUNT = new AtomicLong();

    // ---- 🆕 读回取证的读数（全部进 statusLine，判"这套东西到底跑没跑"只用看这几项）
    /** {@code verdict=patched_live} 的次数（只有"取样 == 当前字节"的时刻才可能，见 readback 类注释）。 */
    private static final AtomicLong READBACK_LIVE_COUNT = new AtomicLong();
    /** {@code patch_reverted} 打了几行（= 被**同类的下一个 patch** 撤掉的旧目标数；实测机制 P3）。 */
    private static final AtomicLong READBACK_REVERTED_COUNT = new AtomicLong();
    /** {@code verdict=unknown} 的次数（patch 之后固定落这里：当前字节采不到，{@code not_observable}）。 */
    private static final AtomicLong READBACK_UNKNOWN_COUNT = new AtomicLong();
    /** 还原之后的取样确认"已经回到初始字节"的次数（{@code verdict=reverted reason=undo_confirmed}）。 */
    private static final AtomicLong READBACK_RESTORED_COUNT = new AtomicLong();
    /** 取样本身失败/跳过几次（没拿到字节、transformer 没被回调、渲染线程、类已不在账本里…）。 */
    private static final AtomicLong READBACK_FAIL_COUNT = new AtomicLong();

    /**
     * 读回失败日志的限流闸门（key = {@code 类#方法}）。
     *
     * <p>为什么单独一把小锁：失败路径可能在工作线程的<b>日志回调之后</b>才走到，
     * 拿 {@link #LOCK} 只会把一次"打不出日志"放大成"卡住 retransform"。
     */
    private static final Map<String, ReadbackGate> READBACK_GATES = new LinkedHashMap<>();

    // ═══════════════════════════════ 🆕 生效探针 ＋ 反制 ═══════════════════════════════

    /**
     * 探针 tick（工作线程多久醒一次去推进 {@link HotfixPatchWatch}）。
     *
     * <p>为什么是 250 ms：窗口判据的粒度是 5 000 ms、退避的粒度是 1 000 ms，
     * 250 ms 的抖动对两者都无意义；而 4 次/秒的空转只扫一个很小的 map（通常 ≤3 个目标）。
     * 只有在<b>有 watch</b> 时才按这个周期醒（见 {@link #loop()}）—— 没有 watch 时仍是阻塞 {@code take()}。
     */
    public static final long PROBE_TICK_MILLIS = 250L;

    /** 同时盯多少个目标（防泄漏；超了就不再新开，只留日志）。 */
    public static final int MAX_WATCHES = 32;

    /** 探针账本的锁（<b>独立</b>于 {@link #LOCK}：{@link #onVerdict} 在服务端线程上只碰这把锁）。 */
    private static final Object PROBE_LOCK = new Object();

    /** {@code 类#方法} ⇒ 账本。 */
    private static final Map<String, HotfixPatchWatch> WATCHES = new LinkedHashMap<>();

    /**
     * 🆕 {@code 类#方法} ⇒ <b>上一次 patch 落地那一刻的计数器值</b>。
     *
     * <p>用来把 {@code patch_probe result=ok} 分成两档（{@link #actOn}）：
     * 这 5 秒里计数器动过 ⇒ 我们的方法体真跑了（{@code reason=exercised}）；
     * 没动 ⇒ 只能说"没人来打"（{@code reason=no_violation}），
     * <b>不许</b>把它说成"已验证生效"。
     */
    private static final Map<String, Long> COUNTER_AT_PATCH = new LinkedHashMap<>();

    /** patch 那一刻这个目标的计数器值（没记过 ⇒ 0）。 */
    private static long counterAtPatch(String key) {
        synchronized (COUNTER_AT_PATCH) {
            final Long v = COUNTER_AT_PATCH.get(key);
            return v == null ? 0L : v;
        }
    }

    /** 有 watch 吗（工作线程据此决定 {@code poll(tick)} 还是 {@code take()}）。 */
    private static volatile boolean probeActive;

    /** 🔴 拒绝类日志的 1/s 限流表（{@code key} 里含 {@code target} 与 {@code detail}）。 */
    private static final Map<String, RefuseGate> REFUSE_GATES = new LinkedHashMap<>();

    private static final AtomicLong PROBE_OK_COUNT = new AtomicLong();
    private static final AtomicLong PROBE_BAD_COUNT = new AtomicLong();
    private static final AtomicLong RETALIATE_COUNT = new AtomicLong();
    private static final AtomicLong RETALIATE_FAIL_COUNT = new AtomicLong();
    private static final AtomicLong GIVEUP_COUNT = new AtomicLong();
    private static final AtomicLong SUPPRESSED_LOGS = new AtomicLong();

    /** 🆕 抑制位读数上次打到第几（只在计数变化时打一行，见 {@code logSuppressedSelfOnce}）。 */
    private static final AtomicLong SUPPRESSED_SELF_LOGGED = new AtomicLong(-1L);

    private static volatile Log logSink;
    private static volatile String modsRoot = "";

    // ---- 🆕 2026-11-20：「我们是谁」的三份身份（准入判据要用；由装配胶水注入，不写路径字面量）
    /** 我们自己的 code source 原样串（{@code ShanhaiMod} 那个；{@code null} = 未知）。 */
    private static volatile String selfCodeSource;
    /** 我们自己的类加载器（{@code ShanhaiMod.class.getClassLoader()}）。 */
    private static volatile ClassLoader selfLoader;
    /** 一个原版类的加载器（只给"code source 拿不到时的二级信号 (b)"用）。 */
    private static volatile ClassLoader mcLoader;
    /** {@code foreign_nocode} 那一行只打一次。 */
    private static final AtomicBoolean FOREIGN_LOGGED = new AtomicBoolean();

    private static volatile PatchTransformer transformer;
    private static volatile boolean transformerAdded;
    private static volatile Thread worker;

    private HotfixAutoCounter() {
        // 纯静态
    }

    // ═══════════════════════════════ 装配 ═══════════════════════════════

    /**
     * 接上日志出口与 {@code mods\} 根（游戏侧在进世界时调；离线自检自己调）。
     *
     * @param sink        日志出口；{@code null} = 静默（只算读数）
     * @param modsDir     {@code mods\} 绝对路径；{@code null}/空 = 保持原值（可能是 {@code ""}，
     *                    此时白名单会退化成"路径里必须有一级 mods"这条更严的判据）
     */
    public static void bind(Log sink, String modsDir) {
        logSink = sink;
        if (modsDir != null && !modsDir.isEmpty()) {
            modsRoot = modsDir;
        }
        // 🆕 2026-11-21（①）：`frame_owner …` 那几行也走<b>同一个</b>日志出口
        //   （{@link HotfixTargetResolver} 与 {@link HotfixFrameOwner} 都是纯 JDK 的，
        //   它们不许引用 log4j／MC，只能像这里这样把出口注进去）。
        if (sink == null) {
            HotfixFrameOwner.bind(null);
        } else {
            HotfixFrameOwner.bind(new HotfixFrameOwner.LineSink() {
                @Override
                public void line(String text) {
                    log(text);
                }
            });
        }
    }

    /**
     * 🆕 2026-11-20：把「我们是谁」交给引擎（装配胶水 {@code HotfixAutoCounterGlue} 在进世界时调）。
     *
     * <p>为什么这三样必须由外部注入：引擎是纯 JDK 的（离线自检跑同一份字节码），
     * 它<b>不能</b> {@code import com.shanhai.ShanhaiMod} 或 {@code net.minecraft.*}。
     *
     * <p>它们被用的地方只有一处：{@link HotfixPatchWhitelist#judge(HotfixPatchWhitelist.Probe)}
     * 的三条排除判据（我们自己的 jar ／ JDK 运行时 ／ JDK 三件套加载器）。<b>拿不到时不会放宽</b>：
     * 缺 {@code selfCodeSource} 只意味着"少了这一条排除"，而包名白名单（{@code com.shanhai.}）仍然是第一道。
     *
     * @param codeSource 我们自己那个类的 code source 原样串（可为 {@code null}）
     * @param us         我们自己的加载器（可为 {@code null}）
     * @param mc         一个原版类的加载器（可为 {@code null}）
     */
    public static void bindSelf(String codeSource, ClassLoader us, ClassLoader mc) {
        if (codeSource != null && !codeSource.isEmpty()) {
            selfCodeSource = codeSource;
        }
        if (us != null) {
            selfLoader = us;
        }
        if (mc != null) {
            mcLoader = mc;
        }
    }

    /** 我们自己的 code source（{@code null} = 还没注入）。 */
    public static String selfCodeSource() {
        return selfCodeSource;
    }

    /**
     * 🆕 <b>启动期只读自检那一行</b>（{@code foreign_nocode …}）：数一数"非白名单 ＋ CodeSource 为空"
     * 的已加载类有几个、样例包名是什么 —— 这一行直接回答「场上有没有 agent 注入的东西」。
     *
     * <p>同一 JVM 只算一次、只打一次；{@code Instrumentation} 没拿到就写 {@code unavailable}。
     */
    public static void logForeignScanOnce() {
        if (!FOREIGN_LOGGED.compareAndSet(false, true)) {
            return;
        }
        final Instrumentation inst = HotfixHolder.instrumentation();
        try {
            log(HotfixForeignScan.scanOnce(inst, selfCodeSource, selfLoader, mcLoader));
        } catch (Throwable t) {
            log("foreign_nocode classes=? scanned=0 reason=exception_" + simpleName(t));
        }
    }

    /** 现在开没开。 */
    public static boolean auto() {
        return AUTO.get();
    }

    /**
     * 开关（{@code /shanhai_add hotfix auto on|off}）。
     *
     * <p>只改内存里的一个布尔 —— 落盘由调用方负责（{@code HotfixCommand#autoSet} 会先写
     * {@code config/shanhai_add-common.toml [hotfix] auto} 再调这里；启动时由
     * {@code HotfixAutoCounterGlue#bind} 按同一份配置应用一次）。
     * 默认 {@code true}（2026-11-20 用户拍板：热改线路默认开）。
     *
     * <p>🔴 <b>关掉（{@code off}）时立刻 {@link #undoAll}</b> 并打一行 {@code auto_switch_undoall}：
     * 单机里"退出世界"还在同一个 JVM、下一局会复用同一个类加载器 ⇒ 不许出现
     * "开关已经关了、上一刀却还留在进程里"这种状态。
     *
     * @return 改完之后的值
     */
    public static boolean setAuto(boolean on) {
        final boolean before = AUTO.getAndSet(on);
        log("auto_switch auto=" + on + " before=" + before + " patched=" + patchedCount()
                + " key=hotfix.auto");
        if (!on) {
            final int restored = undoAll("auto_off");
            log("auto_switch_undoall trigger=auto_off auto=false restored=" + restored
                    + " failed_or_count_seen=prev_patched=" + before
                    + " note=关掉即还原（一个方法体都不留改动）");
        }
        return on;
    }

    /**
     * 🆕 2026-11-20：<b>启动时一行自证</b>（同一 JVM 只打一次；不是每 tick）。
     *
     * <p>格式（用户点名）：
     * <pre>
     *   [SHANHAI-HOTFIX] auto_default=on auto=true default=true decoupled_from=attack_side
     * </pre>
     * ⇒ 现场只要 grep {@code auto_default=} 就知道这一版是不是"热改默认开"，
     * 不必再去翻 config 或猜测。
     */
    public static void logAutoDefaultOnce() {
        if (!DEFAULT_LOGGED.compareAndSet(false, true)) {
            return;
        }
        log("auto_default=" + (DEFAULT_AUTO ? "on" : "off") + " auto=" + auto()
                + " default=" + DEFAULT_AUTO + " decoupled_from=attack_side"
                + " switch=/shanhai_add hotfix auto on|off"
                + " note=attack_side 只关自动抹除攻击者与持续压制，不影响热改");
    }

    /** {@link #logAutoDefaultOnce()} 的"只打一次"闩。 */
    private static final AtomicBoolean DEFAULT_LOGGED = new AtomicBoolean();

    // ═══════════════════════════════ 触发入口（日志线程） ═══════════════════════════════

    /**
     * 本体判了 {@code solved=false}：入队，立刻返回。
     *
     * <p>⚠️ 这是<b>日志线程</b>上调用的（{@link HotfixTriageTap} 的 appender 里）——
     * 这里只做两件极便宜的事（计数 ＋ {@code offer}），<b>绝不</b>在这里 retransform、绝不在这里打日志。
     *
     * @param verdict 本体那一拍的结论
     * @param strike  配上的抓栈记录（可能 {@code null}）
     */
    public static void onVerdict(HotfixTriageVerdict verdict, HotfixTriageTap.Strike strike) {
        if (verdict == null) {
            return;
        }
        // 🆕 生效探针：先拿这条违规去比"我们改过的目标还在不在栈上"。
        //   便宜（只扫 ≤MAX_WATCHES 个目标 × ≤16 帧）、不加 LOCK（只碰 PROBE_LOCK）、不 retransform、
        //   不在这里打日志（这里是服务端线程）—— 结论由工作线程的 tick() 打成 patch_probe 行。
        noteViolation(verdict, strike);
        ensureWorker();
        final Job job = new Job(verdict, strike);
        synchronized (QUEUE_LOCK) {
            if (!JOBS.offer(job)) {
                DROPPED.incrementAndGet();
                return;
            }
            QUEUED.incrementAndGet();
        }
    }

    /** 等队列排空（离线自检／命令里用来"等到动作真的做完"）。{@code true} = 已排空。 */
    public static boolean awaitIdle(long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        synchronized (QUEUE_LOCK) {
            while (QUEUED.get() > 0) {
                final long left = deadline - System.currentTimeMillis();
                if (left <= 0L) {
                    return false;
                }
                try {
                    QUEUE_LOCK.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    // ═══════════════════════════════ 撤销 ═══════════════════════════════

    /**
     * 还原一个目标（{@code /shanhai_add hotfix undo <类>#<方法>}）。
     *
     * <p>还原走两条路，第一条不行才走第二条：
     * <ol>
     *   <li><b>让我们的 transformer 这次不认它</b>（返回 {@code null}）再 {@code retransformClasses}
     *       ⇒ JVM 按语义回到<b>初始 class 字节</b>（与 {@link HotfixSelfTest} 第 ⑥ 步同一条路）；</li>
     *   <li>①抛异常时，把<b>改之前存下的原字节</b>显式送回。</li>
     * </ol>
     *
     * @param target {@code <类>#<方法>}（与日志里的 {@code target=} 逐字一致）
     * @return {@code true} = 已还原
     */
    public static boolean undo(String target) {
        LOCK.lock();
        try {
            return undoLocked(target);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 全部还原（{@code /shanhai_add hotfix undoall}、退出世界／停服时自动调）。
     *
     * @param trigger 谁触发的（{@code command}／{@code server_stopping}／{@code server_stopped}）
     * @return 成功还原了几个
     */
    public static int undoAll(String trigger) {
        LOCK.lock();
        try {
            // 🆕 停服/退出世界：探针账本一起退役（下一局从干净态开始，别拿上一局的旧窗口判）
            retireAllWatches();
            if (PATCHED.isEmpty()) {
                log("auto_counter_undoall trigger=" + token(trigger) + " count=0 restored=0 failed=0");
                return 0;
            }
            final List<String> keys = new ArrayList<>(PATCHED.keySet());
            int restored = 0;
            int failed = 0;
            for (int i = 0; i < keys.size(); i++) {
                if (undoLocked(keys.get(i))) {
                    restored++;
                } else {
                    failed++;
                }
            }
            log("auto_counter_undoall trigger=" + token(trigger) + " count=" + keys.size()
                    + " restored=" + restored + " failed=" + failed);
            return restored;
        } catch (Throwable t) {
            log("auto_counter_undoall trigger=" + token(trigger) + " count=? restored=0 failed=? detail="
                    + token("exception_" + simpleName(t)));
            return 0;
        } finally {
            LOCK.unlock();
        }
    }

    // ═══════════════════════════════ 自检 ═══════════════════════════════

    /**
     * 「换方法体 → 验证返回默认值 → 还原 → 验证恢复」闭环（{@code /shanhai_add hotfix selftest} 的第 2 段）。
     *
     * <p>与第 1 段（{@link HotfixSelfTest}，常量替换）互不依赖：这里用的是<b>同一个</b>
     * {@link HotfixMethodBodyPatch} ＋ 同一个 transformer ＋ 同一个 {@code retransformClasses} 调用，
     * 只是靶子换成 {@link HotfixAutoCounterProbe}，并<b>显式绕过白名单</b>（它是 {@code com.shanhai.*}）。
     *
     * @return {@code true} = 每一步都过；最后一行固定 {@code selftest step=auto_counter pass=…}
     */
    public static boolean selftest() {
        final long t0 = System.currentTimeMillis();
        final Steps steps = new Steps();
        final Instrumentation inst = HotfixHolder.instrumentation();
        steps.check("auto_counter_instrumentation", inst != null,
                inst == null ? "instrumentation_null" : "ok");
        if (inst == null) {
            return steps.finish(t0);
        }

        // ---- ① 基线：靶子必须还在原样
        final boolean baseFlag = HotfixAutoCounterProbe.flag();
        final boolean baseOk = baseFlag
                && HotfixAutoCounterProbe.number() == HotfixAutoCounterProbe.NUMBER
                && HotfixAutoCounterProbe.untouched() == HotfixAutoCounterProbe.UNTOUCHED
                && HotfixAutoCounterProbe.BIG == HotfixAutoCounterProbe.big()
                && HotfixAutoCounterProbe.TEXT.equals(HotfixAutoCounterProbe.text());
        steps.check("auto_counter_baseline", baseOk,
                "flag=" + baseFlag + " number=" + HotfixAutoCounterProbe.number()
                        + " untouched=" + HotfixAutoCounterProbe.untouched()
                        + " big=" + HotfixAutoCounterProbe.big()
                        + " text=" + HotfixAutoCounterProbe.text());
        if (!baseOk) {
            return steps.finish(t0);
        }

        // ---- ② 改：走与自动路径【同一个】apply()，只是 checkGates=false（靶子是我们自己）
        final Outcome applied;
        LOCK.lock();
        try {
            applied = apply(inst, new Class<?>[]{HotfixAutoCounterProbe.class},
                    HotfixAutoCounterProbe.class.getName(), "flag", "()Z", -1, false,
                    // 靶子方法体里没有"写受保护状态"的指令 ⇒ 自检必须走强力档（这一点本身也是一条负对照）
                    HotfixAttackTier.METHOD);
        } finally {
            LOCK.unlock();
        }
        steps.check("auto_counter_patch", applied.ok,
                "reason=" + applied.detail + "_match=" + applied.match + "_new_bytes=" + applied.newBytes);
        if (!applied.ok) {
            return steps.finish(t0);
        }

        // ---- ③ 验证"返回值真的变了"，同时确认同类的其它方法一个都没动
        final boolean changed = !HotfixAutoCounterProbe.flag();
        final boolean others = HotfixAutoCounterProbe.number() == HotfixAutoCounterProbe.NUMBER
                && HotfixAutoCounterProbe.untouched() == HotfixAutoCounterProbe.UNTOUCHED
                && HotfixAutoCounterProbe.BIG == HotfixAutoCounterProbe.big()
                && HotfixAutoCounterProbe.TEXT.equals(HotfixAutoCounterProbe.text());
        steps.check("auto_counter_verify_changed", changed && others,
                "flag=" + HotfixAutoCounterProbe.flag() + " number=" + HotfixAutoCounterProbe.number()
                        + " others_unchanged=" + others);

        // ---- ③-b patch 之后的取证：走与自动路径同一个 readbackAfterPatch（零副作用）
        //      期望 unknown/not_observable —— 这是实测出的**边界**，不是"读到了但没对上"
        final String livePatched = readbackAfterPatch(inst, applied.key, applied, "selftest");
        steps.check("auto_counter_readback_limits",
                HotfixPatchReadback.VERDICT_UNKNOWN.equals(livePatched),
                "verdict=" + livePatched + " expect=unknown(not_observable)"
                        + " record_verdict=" + (applied.record == null ? "?" : applied.record.readbackVerdict));

        // ---- ③-c 取证这一步不许碰类：它跑完之后，行为必须还是"被换过"的那个
        final boolean stillPatched = !HotfixAutoCounterProbe.flag()
                && HotfixAutoCounterProbe.number() == HotfixAutoCounterProbe.NUMBER
                && HotfixAutoCounterProbe.untouched() == HotfixAutoCounterProbe.UNTOUCHED;
        steps.check("auto_counter_readback_harmless", stillPatched,
                "flag=" + HotfixAutoCounterProbe.flag() + " number=" + HotfixAutoCounterProbe.number()
                        + " untouched=" + HotfixAutoCounterProbe.untouched());

        // ---- ④ 还原
        final String key = applied.key;
        final boolean restored = undo(key);
        steps.check("auto_counter_undo", restored, "target=" + key);

        // ---- ⑤ 验证恢复
        final boolean back = HotfixAutoCounterProbe.flag()
                && HotfixAutoCounterProbe.number() == HotfixAutoCounterProbe.NUMBER
                && HotfixAutoCounterProbe.untouched() == HotfixAutoCounterProbe.UNTOUCHED;
        steps.check("auto_counter_verify_restored", back,
                "flag=" + HotfixAutoCounterProbe.flag() + " number=" + HotfixAutoCounterProbe.number()
                        + " untouched=" + HotfixAutoCounterProbe.untouched());

        // ---- ⑤-b 还原之后的取样（唯一安全的取样时刻）：类里必须是 orig ⇒ verdict=reverted
        //      reason=undo_confirmed ⇒ 它证明的是"还原真的落地了"，不是"被谁织回去了"
        final String liveOrig = readbackNow(inst, applied.key, applied, "selftest_undo");
        steps.check("auto_counter_readback_after_undo", HotfixPatchReadback.VERDICT_REVERTED.equals(liveOrig),
                "verdict=" + liveOrig + " expect=reverted(undo_confirmed)"
                        + " record_verdict=" + (applied.record == null ? "?" : applied.record.readbackVerdict));

        // ---- ⑥ 门槛负对照：我们自己的类必须被判"白名单命中"
        final HotfixPatchWhitelist.Decision d = HotfixPatchWhitelist.judge(
                HotfixAutoCounterProbe.class.getName(), codeSourceOf(HotfixAutoCounterProbe.class),
                modsRoot);
        steps.check("auto_counter_gate", !d.allowed && d.reason.startsWith("whitelisted:"),
                "judge=" + token(d.reason) + " allowed=" + d.allowed);

        return steps.finish(t0);
    }

    // ═══════════════════════════════ 读数 ═══════════════════════════════

    /** 一行判读摘要。 */
    public static String statusLine() {
        final String targets;
        int lineTier = 0;
        int methodTier = 0;
        LOCK.lock();
        try {
            final StringBuilder sb = new StringBuilder(96);
            for (Map.Entry<String, Patch> e : PATCHED.entrySet()) {
                final Patch p = e.getValue();
                final String t = p == null ? "?" : p.tier;
                if (HotfixAttackTier.METHOD.equals(t)) {
                    methodTier++;
                } else {
                    lineTier++;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(e.getKey()).append('=').append(t);
            }
            targets = sb.length() == 0 ? "-" : sb.toString();
        } finally {
            LOCK.unlock();
        }
        return "auto=" + AUTO.get() + "（默认 " + DEFAULT_AUTO + "，配置键 hotfix.auto，落盘由命令与装配点负责）"
                + " patched=" + patchedCount()
                + " attack_line=" + lineTier
                + " attack_method=" + methodTier
                + " transformer_added=" + transformerAdded
                + " mods_root=" + (modsRoot.isEmpty() ? "?" : modsRoot)
                + " self_code_source=" + (selfCodeSource == null || selfCodeSource.isEmpty() ? "?" : "set")
                + " handled=" + HANDLED.get()
                + " dropped=" + DROPPED.get()
                + " queued=" + QUEUED.get()
                + " refused=" + REFUSE_COUNT.get()
                + " failed=" + FAIL_COUNT.get()
                + " probe_ok=" + PROBE_OK_COUNT.get()
                + " probe_ineffective=" + PROBE_BAD_COUNT.get()
                + " retaliate=" + RETALIATE_COUNT.get()
                + " retaliate_failed=" + RETALIATE_FAIL_COUNT.get()
                + " give_up=" + GIVEUP_COUNT.get()
                + " readback_live=" + READBACK_LIVE_COUNT.get()
                + " readback_reverted=" + READBACK_REVERTED_COUNT.get()
                + " readback_unknown=" + READBACK_UNKNOWN_COUNT.get()
                + " readback_restored=" + READBACK_RESTORED_COUNT.get()
                + " readback_failed=" + READBACK_FAIL_COUNT.get()
                + " wanted=" + wantedCount()
                + " keepalive_same=" + KEEP_SAME.get()
                + " keepalive_reapplied=" + KEEP_REAPPLIED.get()
                + " keepalive_failed=" + KEEP_FAILED.get()
                + " keepalive_give_up=" + KEEP_GIVEUP.get()
                + " log_suppressed=" + SUPPRESSED_LOGS.get()
                + " chat_sink=" + chatSinkBound()
                + " chat_announced=" + CHAT_ANNOUNCED.get()
                + " " + HotfixChainWipe.statusLine()
                + " " + HotfixShield.statusLine()
                + " targets=[" + targets + "]";
    }

    /** 现在被改着的目标（{@code 类#方法}）。 */
    public static List<String> patchedTargets() {
        LOCK.lock();
        try {
            return new ArrayList<>(PATCHED.keySet());
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 🆕 <b>"每个目标当前用的是哪一档"</b>（{@code /shanhai_add hotfix status} 逐行列它）。
     *
     * <pre>
     *   examplemod.util.ExampleHelper#doHurt  attack=line    sites=1 site_list=setHealth:m_21153_ hits=0
     *   examplemod.entity.ExampleEntity#m_8119_    attack=method  sites=0 site_list=-                    hits=3
     * </pre>
     *
     * @return 每个在册目标一行（没有目标 ⇒ 空表）；不带锁、读快照
     */
    public static List<String> patchedTiers() {
        LOCK.lock();
        try {
            final List<String> out = new ArrayList<>(PATCHED.size());
            for (Map.Entry<String, Patch> e : PATCHED.entrySet()) {
                final Patch p = e.getValue();
                if (p == null) {
                    out.add(e.getKey() + " attack=?");
                    continue;
                }
                out.add(e.getKey()
                        + " attack=" + p.tier
                        + " sites=" + p.sites
                        + " site_list=" + (p.siteList.isEmpty() ? "-" : p.siteList)
                        + " hits=" + p.hits
                        + " bytes=" + p.origBytes + "->" + p.newBytes);
            }
            return out;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 🆕 把一条从"<b>现在改着谁</b>"的账本（{@link #PATCHED}）里拿掉，但<b>保留 wanted</b>
     * （{@link #CHAT_PATCHED_KEYS}）。
     *
     * <p>这不是新机制，而是**把已经存在的一种真实状态**做成可复现的入口：
     * 反制里那次"先 undo 再重打"如果重打失败，目标就正好落在这个状态 ——
     * 账本里没有它（所以 {@code gate_already_patched} 不会再挡住它），但它<b>仍然应该改着</b>。
     * 2026-11-08 13:43 的 P3 复发就发生在"账本里没有、plan 又只按账本派生"这个空档里。
     *
     * <p>用途：命令排查、离线自检复现该状态、以及反制失败后的收尾。
     *
     * @param target {@code 类#方法}
     * @return {@code true} = 账本里确实有它并已拿掉
     */
    public static boolean retireFromLedger(String target) {
        LOCK.lock();
        try {
            final Patch removed = PATCHED.remove(target);
            log("auto_counter_retire target=" + token(target)
                    + " result=" + (removed == null ? "not_in_ledger" : "retired")
                    + " wanted_kept=" + (wantedHas(target) ? "true" : "false")
                    + " note=只退出在册账本，重建时仍按wanted一起套");
            return removed != null;
        } finally {
            LOCK.unlock();
        }
    }

    /** wanted 里有没有这一条。 */
    private static boolean wantedHas(String key) {
        if (key == null) {
            return false;
        }
        synchronized (CHAT_PATCHED_KEYS) {
            return CHAT_PATCHED_KEYS.containsKey(key);
        }
    }

    /** 这一整份 plan 里的方法是不是<b>全都</b>还 wanted（哨兵的重打闸门）。 */
    private static boolean stillWantedLocked(List<PlanEntry> plan) {
        if (plan == null || plan.isEmpty()) {
            return false;
        }
        synchronized (CHAT_PATCHED_KEYS) {
            for (int i = 0; i < plan.size(); i++) {
                final PlanEntry e = plan.get(i);
                if (e == null || !CHAT_PATCHED_KEYS.containsKey(e.key)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 当前被改着的个数（{@link #statusLine()}／日志用；不加锁读快照）。 */
    public static int patchedCount() {
        LOCK.lock();
        try {
            return PATCHED.size();
        } finally {
            LOCK.unlock();
        }
    }

    // ═══════════════════════════════ 工作线程 ═══════════════════════════════

    private static void ensureWorker() {
        if (worker != null) {
            return;
        }
        synchronized (HotfixAutoCounter.class) {
            if (worker != null) {
                return;
            }
            final Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "shanhai-hotfix-autocounter");
            t.setDaemon(true);
            try {
                t.start();
                worker = t;
            } catch (Throwable ignored) {
                // 起不来就只意味着"主动热改不可用"，不影响任何别的东西
            }
        }
    }

    private static void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            final Job job;
            try {
                // 🆕 有 watch 在盯（或还有 wanted 要哨兵盯着）时按 PROBE_TICK_MILLIS 醒；
                //   否则仍是"无穷等一件活"（不空转）。
                job = (probeActive || keepaliveActive())
                        ? JOBS.poll(PROBE_TICK_MILLIS, TimeUnit.MILLISECONDS) : JOBS.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (job == null) {
                tick();
                continue;
            }
            try {
                handle(job);
            } catch (Throwable t) {
                FAIL_COUNT.incrementAndGet();
                log(line("?", "?", whyOf(job), "failed", "exception_" + simpleName(t), 0, 0, ""));
            } finally {
                CHAT_PLAYER.remove();   // 🆕 2026-11-08：这一拍的"谁被打了"用完即清（绝不跨 job 串味）
                HANDLED.incrementAndGet();
                synchronized (QUEUE_LOCK) {
                    QUEUED.decrementAndGet();
                    QUEUE_LOCK.notifyAll();
                }
            }
            tick();
        }
    }

    /**
     * 一道一道过门槛（缺一不可），全过才动手。
     *
     * <p>⚠️ 这个方法在<b>工作线程</b>上跑，日志也在这一线程上打（不会撞上 log4j 的递归抑制）。
     */
    private static void handle(Job job) {
        final HotfixTriageVerdict verdict = job.verdict;
        final String why = verdict.why();
        final HotfixTriageTap.Strike strike = job.strike;
        final String strikeInfo = strikeInfo(strike);
        // 🆕 2026-11-08：这一拍"是谁被打了"—— 播报（聊天栏）要用。
        //   没有玩家名（控制台／离线）⇒ 后面一律不播；由 loop() 的 finally 清掉（绝不跨 job 串味）。
        CHAT_PLAYER.set(verdict.player == null || verdict.player.isEmpty() ? null : verdict.player);

        // ① 开关
        if (!AUTO.get()) {
            refuse("?", "?", why, "gate_auto_off", strikeInfo);
            return;
        }
        // ② 判据：进到这里就已经是 solved=false（上层只投递这种）；这里再挡一次"错配进来的 solved=true"
        if (verdict.solved) {
            refuse("?", "?", why, "gate_not_solved_false", strikeInfo);
            return;
        }
        // ③-a 得有一条配得上的、够新的调用栈
        if (strike == null) {
            refuse("?", "?", why, "gate_no_strike_this_tick", strikeInfo);
            return;
        }
        // 🆕 2026-11-20：把这条栈喂给"工具类账本"（学"谁被几个上游用过"）。
        //   放在所有门槛之前：连 motion 的现场也算证据（它不选靶，但它说明"这个类被谁调过"）。
        HotfixChainTriage.observe(strike.frames);
        final long age = strike.ageMillis();
        // 🆕 2026-11-08：两个来源、两个保鲜期。
        //   ① triage（本体 strike_caller）：与 solved=false 基本同拍 ⇒ 15 s 已经极宽；
        //   ② violation（我们自己在原版写入点抓的现场）：本体可能晚好几拍才 flush 结论 ⇒ 30 s。
        //   判据不合并，日志里用 strike_source= 分开记，方便复查"放宽是不是放错了"。
        final long ttl = strike.ttlMillis();
        if (age > ttl || age < 0L) {
            refuse("?", "?", why, "gate_strike_stale", strikeInfo(strike));
            return;
        }
        // 🆕 2026-11-20（规则②）：`motion` 触发源**降级为"证据，不参与选靶"**。
        //   ⇒ 照旧记录（violation_stack where=motion 已经在上一环打过了，strike 也进了探针账本），
        //     但**不调用选靶、不进 patch 名单**（这里直接返回，选靶面一帧都不碰）。
        //   ⚠️ 取证功能一个字没删：违规照记、探针照看、工具类账本照学。
        if (HotfixTrigger.isEvidenceOnly(why)) {
            final long nowMs = System.currentTimeMillis();
            if (HotfixChainTriage.allowEvidenceLog(nowMs)) {
                log(HotfixChainTriage.evidenceOnlyLine(why, strike.frames.size()));
            }
            return;
        }
        // ③-b 🆕 2026-11-20：<b>两条判据</b>——① 帧的类不在包名白名单；② 它的来源（code source ＋ 加载器）
        //   不是我们自己的 jar、不是 JDK 运行时、不是 JDK 三件套加载器。
        //   🔴 判据里【没有】"必须来自 mods\ 下的 jar"：那条会漏掉 agent / Unsafe / defineClass 注入的类
        //   （它们的 CodeSource 往往是 null）。见 HotfixPatchWhitelist 的类注释。
        final Instrumentation inst = HotfixHolder.instrumentation();
        if (inst == null) {
            refuse("?", "?", why, "gate_no_instrumentation", strikeInfo);
            return;
        }
        final HotfixPatchWhitelist.Probe probe = new HotfixPatchWhitelist.Probe();
        probe.modsRoot = modsRoot;
        probe.selfLoader = selfLoader;
        probe.selfCodeSource = selfCodeSource;
        probe.mcLoader = mcLoader;
        // 🆕 2026-11-20：选靶从「从栈顶往下取第一个外部帧」改成
        //   「三段切分 ＋ 两个锚点 ＋ 打分 ＋ 三级证据」（见 HotfixChainTriage 的类注释）。
        //   🔴 白名单与 code source 那两条判据一个字没改：仍走 HotfixTargetResolver#judgeFrame
        //   （resolve() 与这里共用同一份实现，所以不会漂移）。
        final LoadedLookup lookup = new LoadedLookup(inst);
        final long nowMs = System.currentTimeMillis();
        final HotfixChainTriage.Result res = HotfixChainTriage.pick(strike.frames,
                new ProbeGate(probe, lookup), new HotfixBodyProbe(lookup),
                classifierBytes(lookup), why, verdict.player, nowMs);
        // 🆕 2026-11-20：**在写入那一刻**把这条链记进 cause_id 账本（延迟因果的根本解法）。
        //   ⚠️ 只收"能改死"的写作类事件（health/hurt）；后果事件永远不记（它是账本的消费者）。
        //   放在日志之前：这样摘要行里就带着 cause_id，一条链只打一行。
        final long causeId = res.ok()
                ? HotfixChainTriage.noteWrite(verdict.player, why, res.pick, strike.frames, nowMs) : 0L;
        // 🔴 no_origin_candidate 必须罕见（账本空 ＋ 栈全框架）⇒ 同一玩家 10 s 只放一行；
        //    闸门在这里【只过一次】：否则 triage 行吃掉时间窗、auto_counter 那行反而被压掉，
        //    现场看到的就是"只有一行 refusal 却没有对应的 refused 记录"。
        final boolean noOriginAllowed = !res.ok()
                && HotfixChainTriage.DETAIL_NO_ORIGIN.equals(res.reason)
                && HotfixChainTriage.allowNoOriginLog(verdict.player, nowMs);
        // ---- 可判读的日志（都带 evidence=；限流在 HotfixChainTriage 里）
        logTriage(verdict, strike, res, causeId, noOriginAllowed, nowMs);
        // 🆕 2026-11-21（最终版）：<b>攻击链检测播报</b>（链采到之后、写之前）。
        //   {@code CHAIN <owner> <F> 帧 顶端=<简名>#<方法> 写点=<简名>#<方法> → 选靶 <简名>#<方法>}
        //   同一秒多条链 ⇒ 合并一条并 {@code +K}。🔴 只播报：选靶/写入一个字都没动。
        chatChainOf(verdict, strike, why, res);
        // ---- 🆕 2026-11-21（③④）：**整链抹除**（更高等级的攻击手段；默认开）
        //   位置有意放在这里：
        //     ① 在"选靶失败就 return"**之前** —— 单体挑不出目标时，整链抹除仍然要动手
        //        （用户口径：「检测到它的变化之后就往上溯源，废掉对手的代码」）；
        //     ② 在单靶攻击**之前** —— 它已经把整类废掉的那些类，单靶那一步必须跳过，
        //        否则同一个类会被 retransform 两次（违背"一个类只一次"）。
        //   它自己内部才判开关：off ⇒ 只打 chain_wipe_refused、一个字节都不写。
        final HotfixChainWipe.Batch wiped = chainWipeJob(inst, strike);
        if (!res.ok()) {
            final String extra = strikeInfo + " frames_skipped=" + res.framesSkipped
                    + " frames_not_foreign=" + res.framesNotForeign
                    + (res.detail == null || res.detail.isEmpty() ? "" : " frame_detail=" + res.detail)
                    + " evidence=" + HotfixChainTriage.EVIDENCE_NONE;
            if (HotfixChainTriage.DETAIL_NO_ORIGIN.equals(res.reason)) {
                if (noOriginAllowed) {
                    refuse("?", "?", why, res.reason, extra);
                } else {
                    SUPPRESSED_LOGS.incrementAndGet();
                }
            } else {
                refuse("?", "?", why, res.reason, extra);
            }
            return;
        }
        // ---- ③-e 🆕 2026-11-20：<b>模糊相邻对 ⇒ 两帧都当 patch 目标</b>（上限硬编码 2，见
        //   {@link HotfixChainTriage#REASON_AMBIGUOUS_PAIR}）。其余情况 {@code targets()} 只有 1 个，
        //   走的就是原来那一条路 —— 行为与老代码逐字相同（这里只是把"改一个目标"原样搬进了方法）。
        final List<HotfixTargetResolver.Target> targets = res.pick.targets();
        for (int ti = 0; ti < targets.size(); ti++) {
            final HotfixTargetResolver.Target t = targets.get(ti);
            // 🆕 2026-11-21（③）：这个类刚被整链抹除（整类／triad）过了 ⇒ 不再走单靶那一刀，
            //   否则同一个类会被 retransform 第二次（"一个类只一次"就是这么保证的）。
            if (wiped != null && t != null && wiped.covers(t.className)) {
                log("chain_wipe_covered class=" + token(t.className) + " target=" + token(t.brief())
                        + " retransforms_skipped=1");
                continue;
            }
            hotfixOne(inst, t, why, strikeInfo);
        }
    }

    /**
     * 🆕 2026-11-21：给"分发器"判据送 class 字节（只读、失败给 {@code null}）。
     *
     * <p>与 {@link HotfixBodyProbe} 用<b>同一份磁盘来源</b>（{@link HotfixBodyProbe#diskBytes}）：
     * 那边读的是"这个方法自己写了什么"，这边数的是"它的 {@code invoke*} 落在几个不同类型的接收者上"。
     * 缓存与失败退回都在 {@link HotfixChainTriage#receiverKindsOf} 里（拿不到 ⇒ 按名字判）。
     */
    private static HotfixChainTriage.ClassBytes classifierBytes(final HotfixTargetResolver.Lookup lookup) {
        return new HotfixChainTriage.ClassBytes() {
            @Override
            public byte[] bytesOf(String className) {
                if (lookup == null || className == null || className.isEmpty()) {
                    return null;
                }
                final Class<?> cls = lookup.findLoaded(className);
                return cls == null ? null : HotfixBodyProbe.diskBytes(cls, className);
            }
        };
    }

    /**
     * 对<b>一个</b>目标走完那一整套：已改过？→ 找已加载的类 → 选档 → {@code apply} → 播报 → 读回。
     *
     * <p>🔴 这一段是原 {@code handle()} 尾部的<b>原样搬移</b>（一个字没改），所以单目标任务的行为
     * 与搬之前完全相同；`return` 的语义仍然只是"<b>这个目标</b>到此为止"。
     *
     * <p>🆕 为什么现在会被调用两次：选靶判定"模糊相邻对"（分差 &lt; 0.5 ＋ 同 owner ＋ top2 是
     * top1 的直接调用者）时，一次要改两帧 —— 用户口径是"<b>不要赌 0.15 的分差</b>"。
     * 两帧各自独立记账（{@link #PATCHED} 的键是 {@code 类#方法}）、各自独立选档，不共享状态。
     */
    private static void hotfixOne(Instrumentation inst, HotfixTargetResolver.Target target,
                                  String why, String strikeInfo) {
        final String key = target.brief();
        // 🆕 2026-11-21（根因④）：这个类已经被判"改了也没用"（method 档也压不下来）⇒ 一律不再热改。
        //   它是 patch_giveup reason=ineffective 的落地动作；与 Keep.repairUsed 预算是两套独立止损。
        if (HotfixPatchEffect.blocked(target.className)) {
            refuseThrottled(key, "?", why, "gate_giveup_ineffective", strikeInfo);
            return;
        }
        // 🆕 2026-11-21：这一条补丁连试 3 次都写不进去（整次 abort）⇒ 不再作为目标（有限次，然后停手）
        if (HotfixPatchPlan.poisoned(target.className, key)) {
            refuseThrottled(key, "?", why, "gate_write_abort_giveup",
                    strikeInfo + " aborts=" + HotfixPatchPlan.abortCount(target.className, key));
            return;
        }
        // 🆕 2026-11-21（根因④）：**行为判据的写入侧观测点** —— 这一拍 triage 就在栈里看到它了。
        //   与 noteViolation 的观测点同一个键、同一张表；它就是本次窗口的 hits_before 的起点。
        HotfixPatchEffect.noteObserved(key, target.className, target.methodName,
                System.currentTimeMillis());
        // ③-c 已经改过？⇒ 记它被打到第几次；到 K 次就<b>升级成强力档</b>（不重复打同一档）
        boolean escalate = false;
        String currentTier = null;
        int hits = 0;
        LOCK.lock();
        try {
            final Patch existing = PATCHED.get(key);
            if (existing != null) {
                currentTier = existing.tier;
                existing.hits++;
                hits = existing.hits;
                if (HotfixAttackTier.shouldEscalate(existing.tier, hits, null)) {
                    escalate = true;
                } else {
                    // 这条是"已经改过了"的重复拒绝 —— 违规一多它就会刷屏，所以走 1/s 限流。
                    refuseThrottled(key, "?", why, "gate_already_patched",
                            strikeInfo + " attack=" + existing.tier + " hits=" + hits);
                    return;
                }
            }
        } finally {
            LOCK.unlock();
        }
        final Class<?> cls = findLoaded(inst, target.className);
        if (cls == null) {
            refuse(key, "?", why, "gate_class_not_loaded",
                    strikeInfo + " frames_not_foreign=" + target.framesNotForeign
                            + " code_source=" + target.codeSourceToken);
            return;
        }
        if (escalate) {
            // 升级：先把 line 那一档的字节逐字节还原（"从原样重来一次"），再上强力档
            LOCK.lock();
            try {
                undoLocked(key, false);
            } catch (Throwable ignored) {
                // 还原失败也继续：下面 apply 会从基线重新套
            } finally {
                LOCK.unlock();
            }
            log("attack_escalate target=" + token(key)
                    + " from=" + HotfixAttackTier.LINE + " to=" + HotfixAttackTier.METHOD
                    + " why=repeated_hit hits=" + hits + " " + strikeInfo);
            // 🔴 升级到强力档要【额外播报一次】（副作用明显：整方法停摆）
            announceEscalated(key, why);
        }
        // ③-d 选档：默认普通攻击（line）；已判定该升级 ⇒ 强力档
        String tier = escalate ? HotfixAttackTier.METHOD : HotfixAttackTier.LINE;
        Outcome outcome;
        LOCK.lock();
        try {
            // 再判一次（这一小段里可能有别的线程刚改过）
            if (!escalate && PATCHED.containsKey(key)) {
                refuseThrottled(key, "?", why, "gate_already_patched", strikeInfo);
                return;
            }
            outcome = apply(inst, new Class<?>[]{cls}, target.className, target.methodName, null,
                    target.line, true, tier);
            // 🆕 普通攻击这一枪用不出来（找不到写点 / 插不进 / 方法挑不出来）⇒ 自动升级成强力档
            if (!outcome.ok && HotfixAttackTier.LINE.equals(tier)
                    && HotfixAttackTier.lineImpossible(outcome.detail)) {
                final String lineReason = outcome.detail;
                log("attack_escalate target=" + token(key)
                        + " from=" + HotfixAttackTier.LINE + " to=" + HotfixAttackTier.METHOD
                        + " why=" + token(lineReason) + " " + strikeInfo);
                tier = HotfixAttackTier.METHOD;
                // 这一次升级也要播（副作用明显）
                announceEscalated(key, why);
                outcome = apply(inst, new Class<?>[]{cls}, target.className, target.methodName, null,
                        target.line, false, tier);
            }
        } finally {
            LOCK.unlock();
        }
        // 🆕 2026-11-21（回归修复）：{@code attack=} 以【真的落到类里的那一档】为准。
        //   plan 档在写入过程里升级时（line⇒method，见 writePlanLocked），apply 会把 outcome.tier 改成
        //   method —— 日志必须跟着它，否则会出现"日志说 line、实际装的是 method"这种判读陷阱。
        final String effectiveTier = HotfixAttackTier.orDefault(outcome.tier);
        final String attack = " attack=" + effectiveTier;
        if (outcome.gateRefused()) {
            refuse(key, "?", why, outcome.detail, strikeInfo + attack);
            return;
        }
        if (!outcome.ok) {
            FAIL_COUNT.incrementAndGet();
            log(line(key, "?", why, "failed", outcome.detail, outcome.origBytes, outcome.newBytes,
                    "match=" + outcome.match + attack + " " + strikeInfo));
            return;
        }
        PATCH_COUNT.incrementAndGet();
        // 🆕 诊断行（Instrumentation 侧的事实）＋ 探针窗口（"这次到底生效了没有"从这里开始盯）
        logPatchApply(key, outcome, 0);
        notePatched(key, target.className, target.methodName, System.currentTimeMillis());
        // 🆕 2026-11-21（根因④）：**行为判据的写入点** —— 从这一刻起开一个 60 s 窗口，
        //   看这个 target 还会不会出现在违规栈里（判"生效/没生效"用的是行为，不是读不到的字节）。
        //   ⚠️ 档位用真的落下去的那一档：记成 line 会让"改完还挨打"那条判据又按 line 去升级一次。
        HotfixPatchEffect.noteWritten(key, target.className, target.methodName, effectiveTier,
                System.currentTimeMillis());
        log(line(key, outcome.desc, why, "patched", "ok", outcome.origBytes, outcome.newBytes,
                "match=" + outcome.match + " default=" + outcome.defaultKind
                        + " frames_skipped=" + target.framesSkipped
                        + " frames_not_foreign=" + target.framesNotForeign
                        + " code_source=" + target.codeSourceToken
                        + attack + " sites=" + outcome.sites
                        + (outcome.siteList.isEmpty() ? "" : " site_list=" + token(outcome.siteList))
                        + " elapsed_ms=" + outcome.elapsedMillis + " " + strikeInfo));
        // 🆕 2026-11-08：播报成功 —— 每个真的套上去的方法一条 PATCHED（只发一次；见 CHAT_PATCHED_KEYS）
        announcePatched(outcome, why);
        // 🆕 读回取证：账本说 patched 只是一句话；这里去 JVM 里把"类里那份字节"读出来比对。
        //   同一 job、延迟 ~1 tick、只做一次、不常驻 —— 判出 reverted 也只记一行，绝不自动重打。
        readbackAfterPatch(inst, key, outcome, why);
    }

    /**
     * 🆕 按类名在<b>已加载</b>的类里找 {@code Class}（一次 {@code resolve} 只取一份快照）。
     *
     * <p>为什么要有这个：{@link HotfixTargetResolver#resolve} 要逐帧问"这个类的 code source 是什么"，
     * 而 {@code getAllLoadedClasses()} 在大型整合包里是几万个元素 —— 每帧调一次会把一次挑目标
     * 放大成几十毫秒。这里一次 {@code resolve} 只取一份。
     */
    private static final class LoadedLookup implements HotfixTargetResolver.Lookup {

        private final Instrumentation inst;
        private Class<?>[] snapshot;

        LoadedLookup(Instrumentation inst) {
            this.inst = inst;
        }

        @Override
        public Class<?> findLoaded(String className) {
            if (className == null || className.isEmpty()) {
                return null;
            }
            Class<?>[] all = snapshot;
            if (all == null) {
                try {
                    all = inst.getAllLoadedClasses();
                } catch (Throwable t) {
                    all = new Class<?>[0];
                }
                if (all == null) {
                    all = new Class<?>[0];
                }
                snapshot = all;
            }
            for (int i = 0; i < all.length; i++) {
                final Class<?> c = all[i];
                if (c != null && className.equals(c.getName())) {
                    return c;
                }
            }
            return null;
        }
    }


    /**
     * 🆕 2026-11-20：<b>"这一帧算不算外部帧"的判定口</b>（{@link HotfixChainTriage.Gate} 的生产实现）。
     *
     * <p>它只做一件事：把 {@link HotfixTargetResolver#judgeFrame} 的结论翻成
     * {@link HotfixChainTriage.Verdict}。🔴 <b>判据本身一条都没改</b>：仍是
     * "不在包名白名单" ＋ "code source／加载器不是我们自己的、不是 JDK"。
     * 改的只是"从通过了的帧里挑谁"（那件事在 {@link HotfixChainTriage} 里）。
     */
    private static final class ProbeGate implements HotfixChainTriage.Gate {

        private final HotfixPatchWhitelist.Probe template;
        private final HotfixTargetResolver.Lookup lookup;

        ProbeGate(HotfixPatchWhitelist.Probe template, HotfixTargetResolver.Lookup lookup) {
            this.template = template;
            this.lookup = lookup;
        }

        @Override
        public HotfixChainTriage.Verdict judge(HotfixTargetResolver.Frame frame) {
            if (frame == null) {
                return HotfixChainTriage.Verdict.NOT_EXTERNAL;
            }
            // 🆕 2026-11-21（①）：**先拆方法名，再判类名**。
            //   旧写法 `isNeverPatch(frame.className)` 会把"原版类上的注入帧"当内部帧丢掉 ——
            //   而那一帧恰恰是"对手动过这个原版类"的唯一证据（用户点名要的那一类）。
            //   新写法：方法名里 modid 不是我们 ⇒ 算外部帧（owner=modid）；声明类在白名单里时
            //   仍然不许当补丁目标（judgeFrame 会给出 frame_owner_foreign_class_whitelisted）。
            final HotfixFrameOwner.Verdict v = HotfixFrameOwner.classify(frame);
            HotfixFrameOwner.emit(frame, v);
            if (v.self()) {
                return HotfixChainTriage.Verdict.NOT_EXTERNAL;
            }
            if (!v.patchableClass()) {
                return new HotfixChainTriage.Verdict(false, "frame_owner_foreign_class_whitelisted", "none");
            }
            final HotfixPatchWhitelist.Decision d = HotfixTargetResolver.judgeFrame(frame, template, lookup);
            return new HotfixChainTriage.Verdict(d.allowed, d.reason, d.codeSourceToken);
        }
    }

    /**
     * 🆕 2026-11-20：把这次选靶的证据打成可判读的几行（{@code [SHANHAI-TRIAGE]}）。
     *
     * <p>顺序有意：<b>首次见到的整链</b>（只有一条，帮你复盘"这条链长什么样"）→
     * <b>一行摘要</b>（每 5 秒最多一条）→ <b>后果事件</b>那一行（{@code evidence=} 四选一，
     * 靠这一栏判"现场抓的还是账本翻的"）。
     */
    private static void logTriage(HotfixTriageVerdict verdict, HotfixTriageTap.Strike strike,
                                  HotfixChainTriage.Result res, long causeId, boolean noOriginAllowed,
                                  long nowMs) {
        try {
            final String why = verdict == null ? "?" : verdict.why();
            final String player = verdict == null ? null : verdict.player;
            final HotfixChainTriage.Pick pick = res == null ? null : res.pick;
            // 过期账目：不静默（出现频率本身就是"这条假设对不对"的读数）
            if (res != null && res.prunedExpired > 0) {
                log(HotfixChainTriage.expiredLine(res.prunedExpired));
            }
            if (pick != null) {
                final String first = HotfixChainTriage.firstSeenLine(pick, why,
                        strike == null ? null : strike.frames);
                if (first != null) {
                    log(first);
                }
                if (HotfixChainTriage.allowTriageLog(nowMs)) {
                    log(HotfixChainTriage.triageLine(pick, why, causeId));
                }
            }
            if (HotfixTrigger.isConsequence(why)) {
                if (pick != null) {
                    log(HotfixChainTriage.consequenceLine(why, player, pick, pick.evidence,
                            pick.causeId, pick.causeSettled,
                            pick.ageTicks < 0 ? 0 : pick.ageTicks, pick.depth));
                } else if (noOriginAllowed) {
                    log(HotfixChainTriage.noOriginLine(why, player));
                }
            }
        } catch (Throwable ignored) {
            // 日志拼装坏了绝不许影响选靶
        }
    }

    private static String whyOf(Job job) {
        return job == null || job.verdict == null ? "?" : job.verdict.why();
    }

    // ═══════════════════════════════ 核心：判 ＋ 改 ═══════════════════════════════

    /**
     * 过一个目标：白名单／来源／可改性 → 挂 transformer → {@code retransformClasses}。
     *
     * <p>⚠️ 调用方必须持有 {@link #LOCK}（{@link #handle} 里拿、{@link #selftest} 里也拿）——
     * 这里没自己加锁，是为了让 selftest 的"改→验→还原"整段是一个原子动作。
     *
     * <h2>🆕 为什么收的是 {@code Class<?>[]} 而不是一个 {@code Class<?>}</h2>
     * 12:41 那次"账本说 patched、行为没变"有一个很可能的机制：<b>我们 retransform 的
     * {@code Class} 对象不是栈上正在跑的那个</b>（同名类被两个类加载器各加载一份时就会这样；
     * {@code Instrumentation.getAllLoadedClasses()} 里我们看到的是"某一个"，没有任何保证）。
     * ⇒ 反制那一步会把<b>所有同名 Class</b> 一起交进来；每一个都<b>单独</b>判白名单、
     * 单独存原字节（{@link Patch#originals}），这样"改错了其中一个"这件事至少是<b>看得见</b>的。
     *
     * <h2>为什么每个类一次 {@code retransformClasses}</h2>
     * 一次调多个类时 transformer 的 {@code Pending} 会互相覆盖（原字节只有一个槽）；
     * 分开调还能让"某个类失败"不拖累另一个类，并且 {@link Outcome#classesRetransformed} 是真的数。
     */
    private static Outcome apply(Instrumentation inst, Class<?>[] classes, String className, String methodName,
                                 String methodDesc, int lineHint, boolean checkGates, String tier) {
        final Outcome out = new Outcome(className + "#" + methodName);
        out.tier = HotfixAttackTier.orDefault(tier);
        if (inst == null || classes == null || classes.length == 0 || classes[0] == null) {
            out.detail = "gate_no_instrumentation";
            return out;
        }
        // ---- ① 白名单／可改性：**每一个类单独判**（同名不等于同来源）
        final List<Class<?>> accepted = new ArrayList<>(classes.length);
        for (int i = 0; i < classes.length; i++) {
            final Class<?> c = classes[i];
            if (c == null) {
                continue;
            }
            if (checkGates) {
                // 🆕 2026-11-20：黑名单式准入（包名白名单 ＋"不是我们自己／不是 JDK"）。
                //   🔴 判据里没有"必须来自 mods\ 下的 jar"—— 那条会漏掉 agent/Unsafe/defineClass
                //   注入的类（它们的 CodeSource 往往是 null，而它们恰恰是对手也会用的手段）。
                final HotfixPatchWhitelist.Probe probe = new HotfixPatchWhitelist.Probe();
                probe.className = className;
                probe.codeSource = HotfixForeignScan.codeSourceOf(c);
                probe.modsRoot = modsRoot;
                probe.withLoader(c.getClassLoader());   // 置 loaderKnown：null 就是 bootstrap（真实值）
                probe.selfLoader = selfLoader;
                probe.selfCodeSource = selfCodeSource;
                probe.mcLoader = mcLoader;
                final HotfixPatchWhitelist.Decision d = HotfixPatchWhitelist.judge(probe);
                // 🔴 判据必须能自证"这个类为什么被接受/被拒绝"：每次判定都留一行。
                //    raw/normalized 用引号包住、**按原样打**（中文/空格都不动、不做 URL-decode）——
                //    少了原样字符串就没法回答"到底判的是哪条路径"，decode 过的半截路径只会误导排查。
                log("whitelist_" + (d.allowed ? "accepted" : "refused")
                        + " cls=" + token(className)
                        + " raw=\"" + verbatim(d.raw) + "\""
                        + " normalized=\"" + verbatim(d.normalized) + "\""
                        + " reason=" + token(d.reason)
                        + " code_source=" + d.codeSourceToken
                        + " loader=" + token(d.loaderName)
                        + (classes.length > 1 ? " same_name_index=" + i : ""));
                if (!d.allowed) {
                    if (i == 0) {
                        out.detail = "gate_" + d.reason;
                        return out;
                    }
                    continue;               // 同名类里的次要那几个：判不过就跳过，不拖累主目标
                }
            }
            boolean modifiable;
            try {
                modifiable = inst.isModifiableClass(c);
            } catch (Throwable ignored) {
                modifiable = false;
            }
            if (!modifiable) {
                if (i == 0) {
                    out.detail = "gate_not_modifiable";
                    return out;
                }
                continue;
            }
            accepted.add(c);
        }
        if (accepted.isEmpty()) {
            if (out.detail == null || !out.detail.startsWith("gate_")) {
                out.detail = "gate_all_classes_refused";
            }
            return out;
        }
        // ---- ② Instrumentation 侧的事实（诊断行 patch_apply 用；如实打，不修饰）
        out.loaderName = loaderNameOf(accepted.get(0));
        final List<Class<?>> sameName = findLoadedAll(inst, className);
        out.sameNameClasses = sameName.size();
        // "same_class_identity" 的可判形式：已加载类里**没有第二个同名 Class** ⇒ 栈上那个必然就是我们改的那个；
        // 有第二个（甚至更多）⇒ 我们改的<b>可能不是</b>正在跑的那个，如实打 false。
        out.identitySame = out.sameNameClasses <= 1;
        final StringBuilder loaders = new StringBuilder();
        for (int i = 0; i < sameName.size(); i++) {
            if (i > 0) {
                loaders.append(',');
            }
            loaders.append(loaderNameOf(sameName.get(i)));
        }
        out.loaders = loaders.toString();
        ensureTransformer(inst);
        final PatchTransformer tx = transformer;
        if (!transformerAdded || tx == null) {
            out.detail = "gate_transformer_unavailable";
            return out;
        }
        // ---- ③ 🆕 按类累计：把这个类上【应该改着】的方法一起套上（P3 修复）
        //   实测 P3：同类第二次 patch 会静默撤掉第一次（因为 retransform 的输入是基线、我们只 return 一个方法）。
        //   🔴 2026-11-08 13:43 复发：plan 只从 PATCHED（"现在还在册"）派生 ⇒ 13:22 那个被判失效退役的
        //   doHurt 不在账本里 ⇒ 13:43:11 打 playHitFx 时又只套自己一个，把 doHurt 顶掉。
        //   ⇒ 判据改成 wanted 集合（"曾经 patch 过、没显式 undo"就永久在）：这才是"重建时该套的全集"。
        final String mainKey = className + "#" + methodName;
        // 🆕 2026-11-21：这一条已经被判"永远装不上"（连试 3 次都 abort）⇒ 不再拿它挡整张计划。
        //   没有这道闸门，"整次不提交"会退化成"每次违规都 abort 一次"的死循环。
        if (HotfixPatchPlan.poisoned(className, mainKey)) {
            out.detail = "gate_write_abort_giveup";
            return out;
        }
        final List<PlanEntry> plan = new ArrayList<>(3);
        final java.util.Set<String> planned = new java.util.HashSet<>();
        int cumulativeBefore = 0;
        synchronized (CHAT_PATCHED_KEYS) {
            for (Map.Entry<String, Want> e : CHAT_PATCHED_KEYS.entrySet()) {
                final Want w = e.getValue();
                if (w == null || e.getKey() == null || !className.equals(w.className)) {
                    continue;
                }
                if (e.getKey().equals(mainKey)) {
                    continue;                   // 主目标用这一次的描述符/行号
                }
                if (HotfixPatchPlan.poisoned(className, e.getKey())) {
                    continue;                   // 🆕 装不上的那一条不进计划（否则每次写都 abort）
                }
                cumulativeBefore++;
                planned.add(e.getKey());
                plan.add(new PlanEntry(e.getKey(), w.methodName, w.desc, w.lineHint, w.tier));
            }
        }
        // ② 再补"账本里有、wanted 里没有"的（防御：历史半状态也不许漏掉同类的兄弟）
        for (Iterator<Map.Entry<String, Patch>> it = PATCHED.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, Patch> e = it.next();
            final Patch old = e.getValue();
            if (old == null || e.getKey() == null || !className.equals(old.className)) {
                continue;
            }
            if (e.getKey().equals(mainKey) || planned.contains(e.getKey())) {
                continue;
            }
            if (HotfixPatchPlan.poisoned(className, e.getKey())) {
                continue;                       // 🆕 同上：装不上的那一条不挡整张计划
            }
            cumulativeBefore++;
            planned.add(e.getKey());
            plan.add(new PlanEntry(e.getKey(), old.methodName, old.desc, old.lineHint, old.tier));
        }
        // 🆕 主目标这一条带着"这一次选定的档"（line 或 method）；同类的兄弟各带各的档
        plan.add(new PlanEntry(mainKey, methodName, methodDesc, lineHint, out.tier));
        out.className = className;
        out.methodName = methodName;
        out.planMethods = plan.size();
        out.cumulativeBefore = cumulativeBefore;
        out.cumulative = cumulativeBefore > 0;
        out.wantedCount = wantedCount();
        if (cumulativeBefore > 0) {
            // 🔴 这一行就是"同类互撤已被防住"的证据：同类别的 before 个补丁被一起套上，一个都没撤
            log("patch_rescind_guard class=" + token(className)
                    + " before=" + cumulativeBefore
                    + " after=" + (cumulativeBefore + 1)
                    + " avoided=true"
                    + " action=patch this=" + token(methodName)
                    + " mechanism=cumulative_plan");
        }
        // ---- ④ 🆕 2026-11-21（根因①）：**整张计划一次写**
        //   与老实现的三处不同（这就是"根因"与"打补丁"的分界）：
        //     ① 起点是【基线快照】而不是 JVM 交下来的那份（禁止在"当前字节"上叠加）；
        //     ② 同一类的 N 个方法在**同一份字节**上改完才交出去（不再"一次一个方法、整类重写 N 次"）；
        //     ③ 任何一条装不上 ⇒ **整次不提交**（{@code patch_write_abort}），绝不半装。
        final String writeReason = (HotfixPatchPlan.written(className) || cumulativeBefore > 0)
                ? HotfixPatchPlan.REASON_NEW_PATCH : HotfixPatchPlan.REASON_FIRST;
        final PlanWrite w = writePlanLocked(inst, tx, className, accepted, plan, mainKey, writeReason);
        out.elapsedMillis += w.elapsedNanos / 1_000_000L;
        out.origBytes = w.origLen;
        out.newBytes = w.newLen;
        if (!w.ok) {
            // 🆕 2026-11-21（回归修复）：这一次是"升级之后的重试"且仍没装上 ⇒ 动作档位如实记成 method
            //   （否则日志里 attack=line 与 patch_write_abort 的 escalated=1 自相矛盾）。
            if (w.escalated) {
                out.tier = HotfixAttackTier.METHOD;
            }
            out.detail = w.detail;
            return out;
        }
        // 🆕 2026-11-21（回归修复）：档位以【真的写下去的那份计划】为准。
        //   升级过（line⇒method）的 target，账本里必须记成 method —— 否则下一趟重建又按 line 去写，
        //   而"同一 target 每局只升 1 次"的有界性让那一趟没法再升 ⇒ 白丢一次补丁（同一条 line_no_site 复发）。
        final HotfixPatchPlan.MethodPatch landedMain = HotfixPatchPlan.plan(className).get(mainKey);
        if (landedMain != null) {
            out.tier = HotfixAttackTier.orDefault(landedMain.tier);
        }
        out.classesRetransformed = w.retransforms;
        out.match = w.match;
        out.desc = w.desc == null ? "" : w.desc;
        out.defaultKind = w.defaultKind == null ? "" : w.defaultKind;
        out.appliedMethods = w.applied;
        out.sites = w.sites;
        out.siteList = w.siteList;      // 🆕 attack=line 时进日志（site_list=）
        // 主目标自己没套上 ⇒ 这一次不算成功（整计划口径下正常不该发生：装不上就已经 abort 了）
        final String mine = w.perMethod.get(mainKey);
        if (mine == null || !mine.startsWith("ok")) {
            out.detail = "patch_" + (w.failReason == null || w.failReason.isEmpty() ? "not_called" : w.failReason);
            return out;
        }
        for (Iterator<Map.Entry<String, String>> it = w.perMethod.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, String> e = it.next();
            if (e.getValue() == null || !e.getValue().startsWith("ok")) {
                // 整计划口径下这里只可能是 abort 的那一条（本次调用已经提前 return，所以基本拿不到）
                out.planFailedKeys.add(e.getKey());
                continue;
            }
            // 🆕 2026-11-08：真的套上去的那些方法键也要留下 —— 播报要"每个方法一行"
            out.appliedMethodKeys.add(e.getKey());
            // 🆕 wanted 集合要的是"能重建"的信息（类名/内部名/方法/描述符/行号），不只是键
            String desc = null;
            int line = -1;
            for (int k = 0; k < plan.size(); k++) {
                final PlanEntry pe = plan.get(k);
                if (pe != null && pe.key.equals(e.getKey())) {
                    desc = pe.methodDesc;
                    line = pe.lineHint;
                    break;
                }
            }
            if (e.getKey().equals(mainKey) && !out.desc.isEmpty()) {
                desc = out.desc;
            }
            out.appliedWants.add(new Want(e.getKey(), className, internalName(className),
                    methodNameOf(e.getKey()), desc, line));
            // 🆕 每一条 wanted 都带着"它当前用的档"（status 要按目标列档位）
            final Want justAdded = out.appliedWants.get(out.appliedWants.size() - 1);
            // 🆕 2026-11-21（回归修复）：以【已提交的计划】为准 —— 升级过的兄弟方法必须记成 method，
            //   否则下一趟重建又按 line 去写（同一条 line_no_site 反复；而有界性不许它再升一次）。
            final HotfixPatchPlan.MethodPatch landed = HotfixPatchPlan.plan(className).get(e.getKey());
            if (landed != null) {
                justAdded.tier = HotfixAttackTier.orDefault(landed.tier);
            } else if (e.getKey().equals(mainKey)) {
                justAdded.tier = out.tier;
            } else {
                justAdded.tier = tierOfPlanned(plan, e.getKey());
            }
        }
        if (out.classesRetransformed <= 0) {
            out.detail = "patch_not_retransformed";
            return out;
        }
        // 记进"我改过谁"的账本（undo 的唯一依据）。⚠️ 调用方持有 LOCK（见 apply 的注释）。
        //   🆕 原字节取【基线快照】：它就是"这个类第一次被我们看到时的样子"（= undo 要回去的那份）；
        //      拿不到快照时退回 transformer 交上来的那份（行为与老实现一致）。
        final byte[] base = HotfixBaseline.bytes(internalName(className));
        final List<byte[]> originals = new ArrayList<>(accepted.size());
        final List<HotfixPatchReadback.Fingerprint> origFps = new ArrayList<>(accepted.size());
        final List<HotfixPatchReadback.Fingerprint> patchedFps = new ArrayList<>(accepted.size());
        for (int i = 0; i < accepted.size(); i++) {
            originals.add(base);
            origFps.add(w.origFp == null ? HotfixPatchReadback.Fingerprint.NONE : w.origFp);
            patchedFps.add(w.patchedFp == null ? HotfixPatchReadback.Fingerprint.NONE : w.patchedFp);
        }
        final Patch record = new Patch(accepted.toArray(new Class<?>[0]), originals.toArray(new byte[0][]),
                origFps.toArray(new HotfixPatchReadback.Fingerprint[0]),
                patchedFps.toArray(new HotfixPatchReadback.Fingerprint[0]),
                internalName(className), className, methodName, out.desc, lineHint,
                out.origBytes, out.newBytes, out.match, out.defaultKind, System.currentTimeMillis(),
                out.tier, out.sites, out.siteList);
        PATCHED.put(out.key, record);
        out.record = record;
        out.ok = true;
        out.detail = "ok";
        return out;
    }

    /** 还原一个目标（调用方持有 {@link #LOCK}）；同时把它从探针账本里退役。 */
    private static boolean undoLocked(String target) {
        return undoLocked(target, true);
    }

    /**
     * 还原一个目标（调用方持有 {@link #LOCK}）。
     *
     * @param target      {@code 类#方法}
     * @param retireWatch {@code true} = 同时退役探针账本（手动 {@code undo}/停服）；
     *                    {@code false} = <b>保留</b>账本（反制里的"先 undo 再重打"必须走这条，
     *                    否则重打次数会被清空 ⇒ 上限失效 ⇒ 可能打成无限循环）
     */
    private static boolean undoLocked(String target, boolean retireWatch) {
        final Patch record = PATCHED.get(target);
        if (record == null) {
            log("auto_counter_undo target=" + token(target) + " result=failed detail=not_patched");
            return false;
        }
        PATCHED.remove(target);
        // 🆕 2026-11-21（根因①）：**计划表必须同步**。否则下一次"整张计划一次写"会把刚撤掉的
        //   这一条又装回去 —— 那正是"undo 一个、被自己人顶回来"的另一种形态。
        //   ⚠️ 反制走的"先 undo 再重打"（retireWatch=false）例外：那次重打**就是要**把它再装回去，
        //     所以只在显式 undo 时把计划里的这一条摘掉。
        if (retireWatch) {
            HotfixPatchPlan.forget(record.className, target);
            HotfixPatchEffect.forget(target);
        }
        // 🆕 2026-11-08：undo 之后这个目标"不再改着"⇒ 播报去重表里也移除（重打是新的一次 patch，应当再播）。
        //   🔴 但只有**显式 undo**（retireWatch=true）才允许把它从 wanted 里删掉 ——
        //   反制走的"先 undo 再重打"（retireWatch=false）必须保留 wanted，否则 13:43 那种
        //   "同类第二刀把第一刀顶掉"会以另一种形态复发（wanted 一空，plan 就只剩自己）。
        if (retireWatch) {
            try {
                synchronized (CHAT_PATCHED_KEYS) {
                    CHAT_PATCHED_KEYS.remove(target);
                    COUNTER_AT_PATCH.remove(target);
                    final String cls = record.className;
                    boolean left = false;
                    for (Iterator<Want> it = CHAT_PATCHED_KEYS.values().iterator(); it.hasNext(); ) {
                        final Want w = it.next();
                        if (w != null && cls.equals(w.className)) {
                            left = true;
                            break;
                        }
                    }
                    if (!left) {
                        synchronized (KEEPS) {
                            KEEPS.remove(cls);      // 这一类没有 wanted 了 ⇒ 哨兵也退役
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 去重表清理失败绝不影响还原本身
            }
        }
        final Instrumentation inst = HotfixHolder.instrumentation();
        if (inst == null) {
            log("auto_counter_undo target=" + token(target) + " result=failed detail=no_instrumentation");
            return false;
        }
        final PatchTransformer tx = transformer;
        if (tx == null) {
            log("auto_counter_undo target=" + token(target) + " result=failed detail=transformer_unavailable");
            return false;
        }
        final long t0 = System.nanoTime();
        final StringBuilder mode = new StringBuilder("retransform_original");
        int restored = 0;
        int failed = 0;
        // 🆕 撤销**一条**时，必须把"同类剩下的补丁"在**同一次** retransform 里重新套上。
        //   否则一次全 null 的 retransform 会把它们一起退回基线（P2 实测：全 null = undo）——
        //   那正是"undo 一个、误伤另一个"的形态。
        final List<PlanEntry> remaining = new ArrayList<>(2);
        for (Iterator<Map.Entry<String, Patch>> it = PATCHED.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, Patch> e = it.next();
            final Patch keep = e.getValue();
            if (keep == null || e.getKey() == null || !record.className.equals(keep.className)) {
                continue;
            }
            // 🆕 2026-11-21（正确性缺陷）：**兄弟的档必须跟着账本走**。
            //   🔴 4 参构造把档默认成 `method`（见 PlanEntry 的那条委托构造）⇒ 撤销一条 `line` 档补丁时，
            //     同类的兄弟会被悄悄**升级**成"废整个方法"：语义变了却不留痕，读日志的人看不出来。
            //   ⇒ 只补这一个字段（`Patch#tier` 就是当初 apply 写进账本的那个档），"该不该撤销"的判据一个字不动。
            final PlanEntry sib = new PlanEntry(e.getKey(), keep.methodName, keep.desc, keep.lineHint, keep.tier);
            remaining.add(sib);
            // 🔴 自证行：重建出来的那一条，档必须与账本里那条**逐字相同**。
            //   changed=false 才是期望值；changed=true ⇒ 还在丢（这一行就是为了让"丢档"当场可判）。
            final String tierRebuilt = HotfixAttackTier.orDefault(sib.tier);
            final String tierLedger = HotfixAttackTier.orDefault(keep.tier);
            log("patch_undo_rebuild class=" + token(record.className)
                    + " sibling=" + token(keep.methodName)
                    + " tier=" + tierRebuilt
                    + " from=" + tierLedger
                    + " changed=" + !tierRebuilt.equals(tierLedger));
        }
        if (!remaining.isEmpty()) {
            // 🔴 证据行：撤走 1 个之后，同类别剩下的几个仍然会被套上（before=n+1 / after=n）
            log("patch_rescind_guard class=" + token(record.className)
                    + " before=" + (remaining.size() + 1)
                    + " after=" + remaining.size()
                    + " avoided=true"
                    + " action=undo this=" + token(record.methodName)
                    + " mechanism=cumulative_plan");
        }
        for (int i = 0; i < record.classes.length; i++) {
            final Class<?> cls = record.classes[i];
            if (cls == null) {
                continue;
            }
            // ① 首选：这一类还有别的补丁 ⇒ 把剩下的那套重新套上；没有了 ⇒ 交 null（= 退回初始字节）
            tx.pending = remaining.isEmpty()
                    ? null
                    : new Pending(record.internalName, "", null, -1, "", remaining);
            try {
                inst.retransformClasses(cls);
                restored++;
                if (!remaining.isEmpty()) {
                    mode.append(i == 0 ? "" : ",").append("keep_remaining=")
                            .append(remaining.size());
                }
                continue;
            } catch (Throwable first) {
                // ② 回退：把【改之前存下的原字节】显式送回去（只有这一类时才拿得到它）
                //   ⚠️ 但**同类还有别的补丁时绝不许走回退**：那份原字节是"初始基线"，
                //   送回去等于把同类别的方法一起撤掉（P2/P3）—— 宁可这一条报 failed，也不许误伤。
                final byte[] original = record.originalOf(i);
                if (original == null || !remaining.isEmpty()) {
                    failed++;
                    continue;
                }
                final Pending restore = new Pending(record.internalName, "", null, -1);
                restore.restoreBytes = original;
                tx.pending = restore;
                try {
                    inst.retransformClasses(cls);
                    restored++;
                    mode.append(i == 0 ? "" : ",").append("restore_stored_bytes");
                } catch (Throwable second) {
                    failed++;
                    log("auto_counter_undo target=" + token(target) + " result=failed detail="
                            + token("both_restore_paths_failed_first_" + simpleName(first)
                            + "_second_" + simpleName(second)));
                } finally {
                    tx.pending = null;
                }
            } finally {
                tx.pending = null;
            }
        }
        if (remaining.isEmpty() && restored > 0) {
            // 🆕 2026-11-21：这一类已经没有补丁了 ⇒ 身份章作废。
            //   它记的是"我上次写进去的那份 sha"，而那份现在已经不在了 ⇒ 留着只会误导下一次判断。
            HotfixBaseline.unstamp(record.internalName);
        }
        if (retireWatch) {
            retireWatch(target);
        }
        if (restored == 0) {
            log("auto_counter_undo target=" + token(target) + " result=failed detail=no_class_restored"
                    + " failed=" + failed);
            return false;
        }
        log("auto_counter_undo target=" + token(target) + " result=restored mode=" + mode
                + " classes=" + restored + "/" + record.classes.length
                + " orig_bytes=" + record.origBytes + " new_bytes=" + record.newBytes
                + " match=" + record.match
                + " elapsed_ms=" + ((System.nanoTime() - t0) / 1_000_000L));
        // 🆕 还原的字节级自证：**这是唯一安全的取样时刻**（"退回初始字节"正是刚做的事），
        //    sample == 当前状态 ⇒ live==orig 可以真判 reverted。失败只会多一行 WARN，绝不影响还原结果。
        //   🔴 但**同类还有别的补丁时绝不许取样**：取样本身是一次"全 null 的 retransform"（P2 实测
        //   = 退回基线）⇒ 会把刚保住的那些兄弟目标一起撤掉。这一刻只跳过、只记一行，并说明原因。
        if (remaining.isEmpty()) {
            readbackNow(inst, target, record, "undo");
        } else {
            log("auto_counter_undo_readback_skipped target=" + token(target)
                    + " reason=cumulative_keep remaining=" + remaining.size()
                    + " note=全null的retransform会退回基线(P2)，会把同类兄弟一起撤掉");
        }
        return true;
    }

    /** 注册我们的 transformer（必须 {@code canRetransform=true}，否则 retransform 不会回调它）。 */
    private static void ensureTransformer(Instrumentation inst) {
        if (transformerAdded) {
            return;
        }
        try {
            final PatchTransformer tx = new PatchTransformer();
            inst.addTransformer(tx, true);
            transformer = tx;
            transformerAdded = true;
            log("transformer_added=true can_retransform=true");
        } catch (Throwable t) {
            log("transformer_added=false can_retransform=true detail=" + token("exception_" + simpleName(t)));
        }
    }

    // ═══════════════════════════════ 🆕 生效探针 ＋ 反制 ═══════════════════════════════

    /**
     * <b>违规现场 → 探针</b>：这条违规的栈里，还有没有"我们刚改成 RETURN 的那个方法"。
     *
     * <p>这是整套"patch 到底生效了没有"里<b>唯一便宜、且骗不了人</b>的判据：
     * 那个方法体如果真成了 {@code RETURN}，它就<b>不可能</b>再出现在"调用 {@code setHealth} 的栈"上
     * （它连 {@code setHealth} 那一句都不会执行）。12:41 的真实现场正是
     * {@code violation_stack … first_external=…ExampleHelper#doHurt:375} —— 它还在栈上，
     * 就说明 patch 没生效。
     *
     * <p>⚠️ 三个不许：
     * <ul>
     *   <li>不许在这里 {@code retransform}（这里是服务端线程，且可能是 tick 里被调进来的）；</li>
     *   <li>不许在这里打日志（这里是 log4j 的 appender 回调链上）；</li>
     *   <li>不许拿 {@code LOCK}（那把锁在 {@code retransform} 期间被持有 ⇒ 会把整个服卡住）。
     *       只碰 {@link #PROBE_LOCK}。</li>
     * </ul>
     *
     * @param verdict 这一拍的本体/违规结论
     * @param strike  配上的抓栈（{@code strike.atMillis} 是<b>抓栈时刻</b>，不是投递时刻）
     */
    private static void noteViolation(HotfixTriageVerdict verdict, HotfixTriageTap.Strike strike) {
        if (verdict == null || strike == null || !verdict.fromViolation) {
            return;
        }
        // 🆕 2026-11-21（根因③）：**我们自己的 retransform 窗口里一律不观测**
        //   那一刻"类正在变"是我们自己干的；据此判"补丁丢了"就会去重打 ⇒ 自反馈。
        if (HotfixSelfWindow.skip("violation_stack")) {
            return;
        }
        final List<HotfixTargetResolver.Frame> frames = strike.frames;
        if (frames == null || frames.isEmpty()) {
            return;
        }
        try {
            synchronized (PROBE_LOCK) {
                if (WATCHES.isEmpty()) {
                    return;
                }
                for (Iterator<Map.Entry<String, HotfixPatchWatch>> it = WATCHES.entrySet().iterator();
                     it.hasNext(); ) {
                    final Map.Entry<String, HotfixPatchWatch> entry = it.next();
                    final HotfixPatchWatch w = entry.getValue();
                    if (w == null || w.givenUp()) {
                        continue;
                    }
                    if (!containsTarget(frames, w)) {
                        continue;
                    }
                    // ⚠️ 传的是【抓栈时刻】而不是 now：队列里躺着的老现场（patch 之前抓的）不算证据
                    w.noteHit(strike.atMillis, verdict.why());
                    // 🆕 2026-11-21（根因④）：**行为判据的观测点** —— triage 又看到同一个 target 在栈里。
                    //   与 noteWritten 配对成一张小表（键就是 类#方法，与 PATCHED/WATCHES 同一个口径）。
                    HotfixPatchEffect.noteObserved(entry.getKey(), w.className(), w.methodName(), strike.atMillis);
                }
            }
        } catch (Throwable ignored) {
            // 探针坏了只意味着"这次没记上证据"，不许影响任何别的东西
        }
    }

    /** 帧表里有没有这个目标（按类名＋方法名逐字比，不看行号）。 */
    private static boolean containsTarget(List<HotfixTargetResolver.Frame> frames, HotfixPatchWatch w) {
        for (int i = 0; i < frames.size(); i++) {
            final HotfixTargetResolver.Frame f = frames.get(i);
            if (f == null || f.className == null || f.methodName == null) {
                continue;
            }
            if (f.className.equals(w.className()) && f.methodName.equals(w.methodName())) {
                return true;
            }
        }
        return false;
    }

    /** patch 落地 ⇒ 开一个新窗口（反制成功后也走这里，{@code attempts} 计数<b>不清</b>）。 */
    private static void notePatched(String key, String className, String methodName, long nowMillis) {
        synchronized (PROBE_LOCK) {
            HotfixPatchWatch w = WATCHES.get(key);
            if (w == null) {
                if (WATCHES.size() >= MAX_WATCHES) {
                    SUPPRESSED_LOGS.incrementAndGet();
                    return;
                }
                w = new HotfixPatchWatch(className, methodName);
                WATCHES.put(key, w);
            }
            w.onPatched(nowMillis);
            probeActive = true;
        }
        // 🆕 计数器基线：窗口关的时候拿它比"这 5 秒里我们的方法体到底跑没跑"
        //   （跑过 ⇒ ok 可以升格成 reason=exercised；没跑 ⇒ 只能诚实写 reason=no_violation）
        COUNTER_AT_PATCH.put(key, HotfixProbeCounter.count(key));
    }

    /** 反制记账（<b>无论成没成都要调</b>：失败也计数，否则"永远失败的重打"会变成死循环）。 */
    private static void watchRetaliated(String key, long nowMillis, boolean ok, int attemptNo) {
        synchronized (PROBE_LOCK) {
            final HotfixPatchWatch w = WATCHES.get(key);
            if (w == null) {
                return;
            }
            w.onRetaliated(nowMillis, ok, attemptNo);
            probeActive = hasActiveWatchLocked();
        }
    }

    /** 退役一个目标的账本（手动 {@code undo}／停服还原时）。 */
    private static void retireWatch(String key) {
        synchronized (PROBE_LOCK) {
            WATCHES.remove(key);
            probeActive = hasActiveWatchLocked();
        }
    }

    /** 退役全部账本（停服/退出世界）。 */
    private static void retireAllWatches() {
        synchronized (PROBE_LOCK) {
            WATCHES.clear();
            probeActive = false;
        }
        // 🆕 wanted／计数器基线／哨兵状态一起清：下一局从干净态开始（否则会拿上一局的类去重打）
        synchronized (CHAT_PATCHED_KEYS) {
            CHAT_PATCHED_KEYS.clear();
        }
        synchronized (KEEPS) {
            KEEPS.clear();
        }
        synchronized (COUNTER_AT_PATCH) {
            COUNTER_AT_PATCH.clear();
        }
        // 🆕 2026-11-21：计划／基线／身份章／行为判据／抑制位一起清 —— 下一局必须从干净态开始，
        //   否则会拿上一局的"补丁计划"去重写一个已经是原样的类（那等于反着撤销）。
        HotfixPatchPlan.reset();
        HotfixPatchEffect.reset();
        // 🆕 2026-11-21（回归修复）：升级记账也清 —— "每局最多升 1 次"的"一局"就是这里划的界
        clearEscalated();
        // 🔴 基线也要清：下一局那个同名类可能是**另一个加载器里的另一份字节**
        //   （同一 JVM 里"同名不同加载器"是既有事实，{@code same_class_identity} 就是为它存在的）。
        //   留着旧基线 ⇒ 我们会把上一局的字节强行写进新定义，那比"没有基线"糟得多。
        //   清了不会丢东西：下一次 retransform 交给 transformer 的仍是初始基线（P1），那时再抓。
        HotfixBaseline.reset();
        synchronized (BASELINE_LOGGED) {
            BASELINE_LOGGED.clear();
        }
    }

    /** 还剩"需要 tick 推进"的账本吗（已停手的不再需要）。 */
    private static boolean hasActiveWatchLocked() {
        for (Iterator<HotfixPatchWatch> it = WATCHES.values().iterator(); it.hasNext(); ) {
            final HotfixPatchWatch w = it.next();
            if (w != null && !w.givenUp()) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>工作线程上的一格</b>：把每个账本往前推一次，需要动作的立刻做。
     *
     * <p>🔴 锁的纪律：{@link #PROBE_LOCK} 里<b>只判不打日志、只判不 retransform</b>；
     * 动作在锁外做。理由：日志要进 log4j（别的线程可能正持着 log4j 的锁来敲我们这把锁），
     * {@code retransform} 要进 {@link #LOCK} —— 两把锁都不许和 {@link #PROBE_LOCK} 嵌套。
     */
    private static void tick() {
        final long now = System.currentTimeMillis();
        if (probeActive) {
            final List<HotfixPatchWatch.Action> actions = new ArrayList<>(4);
            synchronized (PROBE_LOCK) {
                if (WATCHES.isEmpty()) {
                    probeActive = false;
                } else {
                    for (Iterator<HotfixPatchWatch> it = WATCHES.values().iterator(); it.hasNext(); ) {
                        final HotfixPatchWatch w = it.next();
                        if (w == null) {
                            continue;
                        }
                        final HotfixPatchWatch.Action a;
                        try {
                            a = w.poll(now);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (a != null) {
                            actions.add(a);
                        }
                    }
                    probeActive = hasActiveWatchLocked();
                }
            }
            for (int i = 0; i < actions.size(); i++) {
                try {
                    actOn(actions.get(i));
                } catch (Throwable t) {
                    FAIL_COUNT.incrementAndGet();
                    SUPPRESSED_LOGS.incrementAndGet();
                }
            }
        }
        // 🆕 存活哨兵：每类每 10 秒用 wanted 集合幂等重打一次（判"我们的补丁是不是被悄悄换回去了"）
        try {
            keepaliveTick(now);
        } catch (Throwable ignored) {
            // 哨兵坏了只意味着这一轮没查，不许影响别的东西
        }
        // 🆕 2026-11-21（根因④）：**行为判据的窗口结算**（60 s 到点 / 次数已不可挽回）。
        //   它替代的正是那个"读不到当前字节却硬要判 match"的字节判据。
        try {
            effectTick(now);
        } catch (Throwable ignored) {
            // 判据坏了只意味着这一轮没结算，不许影响别的东西
        }
        logSuppressedSelfOnce();
    }

    /**
     * 🆕 2026-11-21（根因④）：结算到点的行为判据窗口，并按结论<b>只做一次</b>动作。
     *
     * <pre>
     *   patch_effect class=… win=60s hits_before=N hits_after=M verdict=effective|ineffective
     *   verdict=ineffective ＋ line 档 ⇒ 自动升级到 method 档（每个 target 只升一次）
     *   verdict=ineffective ＋ 已是 method 档 ⇒ patch_giveup reason=ineffective ＋ 停止对该类热改
     * </pre>
     */
    private static void effectTick(long nowMillis) {
        final List<HotfixPatchEffect.Row> due = HotfixPatchEffect.due(nowMillis);
        for (int i = 0; i < due.size(); i++) {
            final HotfixPatchEffect.Row row = due.get(i);
            if (row == null) {
                continue;
            }
            final String verdict = HotfixPatchEffect.verdict(row, nowMillis);
            final long before = HotfixPatchEffect.hitsBefore(row);
            final long after = HotfixPatchEffect.hitsAfter(row, nowMillis);
            log(HotfixPatchEffect.line(row, nowMillis));
            if (HotfixPatchEffect.VERDICT_EFFECTIVE.equals(verdict)) {
                HotfixPatchEffect.markReported(row, false);
                continue;
            }
            // ---- ineffective：先把"字节被换过（或压根没进去）"这件事如实记一笔
            noteForeignWrite(row.className, "patch_effect_ineffective");
            final boolean lineTier = HotfixAttackTier.LINE.equals(row.tier());
            if (lineTier && !HotfixPatchEffect.escalated(row)) {
                HotfixPatchEffect.markReported(row, true);
                escalateFromEffect(row, (int) before, (int) after);
                continue;
            }
            // method 档也压不下来（或已经升过一次）⇒ 停手。**不许无限重打**。
            HotfixPatchEffect.markReported(row, true);
            HotfixPatchEffect.block(row.className);
            final Keep keep = keepLocked(row.className);
            log(patchGiveupLine(row.className, "ineffective", keep.retransformCount,
                    "hits_before=" + before + " hits_after=" + after + " 停手不再热改该类"));
            keep.repairOff = true;
        }
    }

    /**
     * 🆕 2026-11-21（根因③）：{@code foreign_write_detected} 的<b>唯一</b>发出口。
     *
     * <p>判据是<b>行为侧</b>（"改了还是挨打"），不是字节对照 —— 我们读不到当前字节
     * ⇒ 日志里如实写 {@code identity=bookkeeping_only}、{@code actual=unreadable}，
     * 并说明"接下来是从基线整计划重建，不是增量叠加"。
     */
    private static void noteForeignWrite(String className, String evidence) {
        final String in = internalName(className);
        final String expected = HotfixBaseline.stampedSha(in);
        if (expected == null || expected.isEmpty()) {
            return;      // 我们还没写过这个类 ⇒ 谈不上"被别人写过"
        }
        log(HotfixBaseline.foreignWriteLine(className, expected, evidence));
    }

    /**
     * 🆕 2026-11-21（根因④）：{@code verdict=ineffective} ＋ 当前 {@code line} 档 ⇒ <b>升级到 method 档</b>。
     *
     * <p>走的是与自动路径完全同一条路（{@code undo → apply}），所以升级之后写下去的还是
     * <b>整张计划</b>（{@code rev+1}、{@code superseded=1}）—— 不是"只装这一个方法"。
     */
    private static void escalateFromEffect(HotfixPatchEffect.Row row, int hitsBefore, int hitsAfter) {
        final String key = row.key;
        final Instrumentation inst = HotfixHolder.instrumentation();
        boolean ok = false;
        String detail = "not_attempted";
        int retransforms = 0;
        LOCK.lock();
        try {
            final Patch record = PATCHED.get(key);
            if (inst == null) {
                detail = "gate_no_instrumentation";
            } else if (record == null) {
                detail = "not_patched";
            } else {
                undoLocked(key, false);
                final List<Class<?>> all = findLoadedAll(inst, record.className);
                final Class<?>[] targets = all.isEmpty() ? record.classes : all.toArray(new Class<?>[0]);
                final Outcome o = apply(inst, targets, record.className, record.methodName,
                        record.desc.isEmpty() ? null : record.desc, record.lineHint, true,
                        HotfixAttackTier.METHOD);
                ok = o.ok;
                detail = o.detail;
                retransforms = o.classesRetransformed;
                if (o.ok) {
                    logPatchApply(key, o, 0);
                    notePatched(key, record.className, record.methodName, System.currentTimeMillis());
                    HotfixPatchEffect.rearm(key, HotfixAttackTier.METHOD, System.currentTimeMillis());
                }
            }
        } catch (Throwable t) {
            detail = "exception_" + simpleName(t);
        } finally {
            LOCK.unlock();
        }
        log("attack_escalate target=" + token(key)
                + " from=" + HotfixAttackTier.LINE + " to=" + HotfixAttackTier.METHOD
                + " why=ineffective"
                + " hits_before=" + hitsBefore + " hits_after=" + hitsAfter
                + " result=" + (ok ? "repatch" : "failed")
                + " detail=" + token(detail)
                + " retransforms=" + retransforms);
        if (ok) {
            announceEscalated(key, "ineffective");
        }
    }

    /** 🆕 抑制位读数（正常应为 0 或极少；出现就说明"我们的写入窗口里有检测逻辑在跑"）。 */
    private static void logSuppressedSelfOnce() {
        final long count = HotfixSelfWindow.count();
        if (count <= 0L) {
            return;
        }
        final long last = SUPPRESSED_SELF_LOGGED.get();
        if (count == last) {
            return;
        }
        SUPPRESSED_SELF_LOGGED.set(count);
        log(HotfixSelfWindow.line() + " last=" + token(HotfixSelfWindow.lastWhat()));
    }

    /** 执行一个探针动作（打那一行 ＋ 该动手时动手）。 */
    private static void actOn(HotfixPatchWatch.Action a) {
        if (a == null || a.step == null) {
            return;
        }
        switch (a.step) {
            case PROBE_OK:
                PROBE_OK_COUNT.incrementAndGet();
                if (a.loggable) {
                    // 🆕 两档，必须诚实：
                    //   exercised   = 窗口里<b>我们的方法体真跑过</b>（只计数探针动了）且没违规 ⇒ 强证据
                    //   no_violation= 没人来打，但也没有"跑过"的证据 ⇒ 只能说"这 5 秒没人来打"
                    final long atPatch = counterAtPatch(a.target);
                    final long counterHits = HotfixProbeCounter.count(a.target) - atPatch;
                    final String reason = counterHits > 0L ? "exercised" : "no_violation";
                    log("patch_probe target=" + token(a.target) + " result=ok"
                            + " reason=" + reason
                            + " counter_hits=" + counterHits
                            + " evidence=violations_after_patch=0"
                            + " window_ms=" + HotfixPatchProbe.WINDOW_MILLIS
                            + " hit=" + a.hits + " attempt=" + a.attempt
                            + (counterHits > 0L ? "" : " note=没人来打也没有跑过的证据，不等于已验证生效"));
                }
                break;
            case PROBE_INEFFECTIVE:
                PROBE_BAD_COUNT.incrementAndGet();
                if (a.loggable) {
                    log("patch_probe target=" + token(a.target) + " result=ineffective"
                            + " evidence=violations_after_patch=" + a.hits
                            + " window_ms=" + HotfixPatchProbe.WINDOW_MILLIS
                            + " since_patch_ms=" + a.sincePatchMillis
                            + " late_hit=" + (a.sincePatchMillis > HotfixPatchProbe.WINDOW_MILLIS)
                            + " where=" + token(a.where)
                            + " counter_hits=" + (HotfixProbeCounter.count(a.target) - counterAtPatch(a.target))
                            + " attempt=" + a.attempt);
                }
                break;
            case DEFER:
                if (a.loggable) {
                    log("retaliate target=" + token(a.target) + " attempt=" + a.nextAttempt
                            + " result=deferred wait_ms=" + a.waitMillis
                            + " reason=backoff backoff_ms=" + a.backoffMillis);
                }
                break;
            case RETALIATE:
                retaliate(a);
                break;
            case GIVE_UP:
                GIVEUP_COUNT.incrementAndGet();
                log("retaliate_give_up target=" + token(a.target) + " attempts=" + a.attempt
                        + " reason=throttled window_ms=" + HotfixPatchProbe.RETALIATE_WINDOW_MILLIS
                        + " note=停手不再重打");
                break;
            default:
                break;
        }
    }

    /**
     * <b>反制一次</b>：{@code undo → 重新判白名单 → 重新 patch}。
     *
     * <p>为什么先 undo：如果上一次的字节还挂着（"账本说改了、其实类没变"的另一种形态），
     * 直接再 patch 会拿"已经改过的字节"去改第二遍；先 undo 让它回到<b>初始 class 字节</b>，
     * 再 patch，才是"从原样重来一次"。
     *
     * <p>为什么重打<b>所有同名 Class</b>：{@code same_class_identity=false} 时我们没有任何证据
     * 说明"当初改的那一个就是正在跑的那一个"；逐个判白名单（{@link #apply} 里每类一行日志）、
     * 逐个改，才有机会真的改中。
     *
     * <p>⚠️ 调用方是工作线程的 {@link #tick()}；这里会拿 {@link #LOCK} 并做 {@code retransform}
     * （几毫秒到几十毫秒），所以那些探针行都在锁外打。
     */
    private static void retaliate(HotfixPatchWatch.Action a) {
        final long now = System.currentTimeMillis();
        final String key = a.target;
        boolean ok = false;
        boolean undone = false;
        int classesDone = 0;
        String detail = "not_attempted";
        Outcome applied = null;
        // 🆕 重打时用哪一档；{@code escalatedFromLine} = 这一趟从普通攻击升到了强力攻击
        String tier = HotfixAttackTier.LINE;
        boolean escalatedFromLine = false;
        final Instrumentation inst = HotfixHolder.instrumentation();
        LOCK.lock();
        try {
            final Patch record = PATCHED.get(key);
            if (inst == null) {
                detail = "gate_no_instrumentation";
            } else if (record == null) {
                detail = "not_patched";
            } else {
                final String cn = record.className;
                final String mn = record.methodName;
                final String desc = record.desc;
                final int lineHint = record.lineHint;
                final Class<?>[] previous = record.classes;
                // 🆕 2026-11-20：被判"改了还在挨打"的那一刀如果是【普通攻击】，直接升到【强力档】——
                //   这就是升级判据里的第三条（行为侧证据），不必再等"连续 3 次"。
                if (HotfixAttackTier.LINE.equals(record.tier)) {
                    tier = HotfixAttackTier.METHOD;
                    escalatedFromLine = true;
                } else {
                    tier = record.tier;
                }
                // 🆕 2026-11-21（根因④）：这个类已经被判"改了也没用"⇒ 不再重打（只记一行，不动作）
                if (HotfixPatchEffect.blocked(cn)) {
                    detail = "gate_giveup_ineffective";
                    watchRetaliated(key, now, false, a.nextAttempt);
                    RETALIATE_FAIL_COUNT.incrementAndGet();
                    log("retaliate target=" + token(key) + " attempt=" + a.nextAttempt
                            + " result=skipped detail=" + token(detail)
                            + " reason=ineffective_stop note=该类已停手不再热改");
                    return;
                }
                // ① 先 undo 回原字节。⚠️ retireWatch=false —— 反制的账本不能被清空（否则上限失效）
                undone = undoLocked(key, false);
                // ② 重新取【所有同名 Class】（应对"当初改的不是正在跑的那个 Class"）
                final List<Class<?>> all = findLoadedAll(inst, cn);
                final Class<?>[] targets = all.isEmpty() ? previous : all.toArray(new Class<?>[0]);
                // ③ 重新判白名单（apply 里逐个判、逐个留行）＋重新 patch（带上（可能已升级的）档）
                final Outcome o = apply(inst, targets, cn, mn, desc.isEmpty() ? null : desc, lineHint, true, tier);
                applied = o;
                ok = o.ok;
                detail = o.detail;
                classesDone = o.classesRetransformed;
                if (o.ok) {
                    logPatchApply(key, o, a.nextAttempt);
                }
            }
        } catch (Throwable t) {
            detail = "exception_" + simpleName(t);
        } finally {
            LOCK.unlock();
        }
        // 记账（成败都记）＋ 一行结果（锁外）
        watchRetaliated(key, now, ok, a.nextAttempt);
        if (ok) {
            RETALIATE_COUNT.incrementAndGet();
        } else {
            RETALIATE_FAIL_COUNT.incrementAndGet();
        }
        log("retaliate target=" + token(key) + " attempt=" + a.nextAttempt
                + " result=" + (ok ? "repatch" : "failed")
                + " detail=" + token(detail)
                + " attack=" + HotfixAttackTier.orDefault(tier)
                + " undone=" + undone
                + " classes_retransformed=" + classesDone
                + " since_probe_ms=" + a.sincePatchMillis);
        if (escalatedFromLine && ok) {
            // 🔴 升级到强力档要【额外播报一次】（副作用明显：整方法停摆）
            log("attack_escalate target=" + token(key)
                    + " from=" + HotfixAttackTier.LINE + " to=" + HotfixAttackTier.METHOD
                    + " why=still_hitting since_probe_ms=" + a.sincePatchMillis
                    + " evidence=violations_after_patch=" + a.hits);
            announceEscalated(key, "still_hitting");
        }
        // 🆕 重打也是一次 patch ⇒ 同样要读回（读回只出 verdict，不触发任何动作，包括不触发重打）
        if (ok) {
            // 🆕 2026-11-21（根因④）：重打也是"被选中并写入"⇒ 行为判据重新开窗
            final Patch rec = PATCHED.get(key);
            if (rec != null) {
                HotfixPatchEffect.noteWritten(key, rec.className, rec.methodName, tier,
                        System.currentTimeMillis());
            }
            readbackAfterPatch(inst, key, applied, "retaliate");
        }
    }

    /**
     * <b>{@code patch_apply} 诊断行</b>：把 {@link Instrumentation} 侧的事实打全。
     *
     * <pre>
     *   patch_apply class=&lt;类名&gt; methods=&lt;n&gt; this=&lt;方法&gt; classes_retransformed=&lt;n&gt; cumulative=&lt;true|false&gt; \
     *        target=&lt;类#方法&gt; same_class_identity=&lt;true|false&gt; loader=… attempt=… same_name_classes=… \
     *        [applied_methods=…] [plan_failed=…] [loaders=…]
     * </pre>
     *
     * <p>{@code methods=} 是这一趟 {@code retransform} 一起套上的方法数（按类累计，P3 修复）；
     * {@code cumulative=} 表示"这一类之前就登记过补丁"（{@code true} 时 {@code methods} 必定 ≥2）。
     * {@code applied_methods=} 是真正套成功的个数 —— 与 {@code methods} 不等就说明有方法没套上，
     * 具体是谁在 {@code plan_failed=} 里（{@code 方法键:原因}）。
     *
     * <p>{@code same_class_identity} 的口径（<b>如实</b>、不修饰）：已加载类里
     * <b>没有第二个同名 {@code Class}</b> ⇒ {@code true}（那"栈上那个"必然就是我们改的这个）；
     * 有 ⇒ {@code false}，并把每个同名类的<b>类加载器</b>列出来（{@code loaders=…}）——
     * 因为"同名不同加载器"正是"我们改了 A、跑的是 B"的唯一可能。
     */
    private static void logPatchApply(String key, Outcome o, int attempt) {
        log("patch_apply class=" + token(o.className)
                + " methods=" + o.planMethods
                + " this=" + token(o.methodName)
                + " classes_retransformed=" + o.classesRetransformed
                + " cumulative=" + o.cumulative
                + " target=" + token(key)
                + " same_class_identity=" + o.identitySame
                + " loader=" + token(o.loaderName)
                + " attempt=" + attempt
                + " same_name_classes=" + o.sameNameClasses
                + " wanted=" + o.wantedCount
                + (o.appliedMethods > 0 ? " applied_methods=" + o.appliedMethods : "")
                + (o.planFailedKeys.isEmpty() ? "" : " plan_failed=" + token(String.join(",", o.planFailedKeys)))
                // 只有 1 个同名类时 loaders= 与 loader= 完全重复，不打（少半段更好读）
                + (o.sameNameClasses <= 1 || o.loaders.isEmpty() ? "" : " loaders=" + token(o.loaders)));
    }

    // ═══════════════════════════════ 🆕 存活哨兵（keepalive） ═══════════════════════════════

    /** 每个类多久用 wanted 集合幂等重打一次。 */
    public static final long KEEPALIVE_INTERVAL_MILLIS = 10_000L;

    /** {@code result=same} 这种"没事"的行，同一个类最快多久一行（10 s 一轮 ⇒ 不刷屏）。 */
    public static final long KEEPALIVE_SAME_LOG_INTERVAL_MILLIS = 60_000L;

    /** 一个类的哨兵状态（{@code className} ⇒ 它）。 */
    private static final Map<String, Keep> KEEPS = new LinkedHashMap<>();

    private static final AtomicLong KEEP_SAME = new AtomicLong();
    private static final AtomicLong KEEP_REAPPLIED = new AtomicLong();
    private static final AtomicLong KEEP_FAILED = new AtomicLong();
    private static final AtomicLong KEEP_GIVEUP = new AtomicLong();

    /**
     * <b>为什么需要它</b>（2026-11-08 13:43 的现场）：
     * 13:22:30 已经 patch 的 {@code doHurt}，到 13:43 还能被 {@code playHitFx} 调起来
     * ⇒ 它<b>早就不是 RETURN 了</b>，而 {@code patch_probe result=ok} 只是因为那 5 秒"没人来打"。
     * 两件事都要治：① 每 10 秒用 wanted 集合<b>重报一次</b>（幂等重打，把被换回去的补丁补回来）；
     * ② 结论要能区分"我们的字节还在"与"被别人换回去了"。
     *
     * <p><b>"被换回去"怎么判</b>（字节这一侧观测不到，见 {@code HotfixPatchReadback} 的 P1/P2 实测：
     * retransform 交给 transformer 的永远是初始基线 ⇒ 读不到"当前定义"）⇒ 只能行为侧交叉：
     * <b>这一轮里有栈证据（违规栈里出现该 target）＋ 计数器没动</b> ⇒ 那一次跑的不是我们那份字节。
     *
     * <p><b>节流</b>：{@code reapplied_after_revert}／{@code failed} 一律记；{@code same} 每类每 60 s 最多一行。
     * <b>退避</b>：失败沿用既有框架（{@link HotfixPatchProbe#judgeRetaliate} 的指数退避 ＋ 60 s 5 次上限），
     * 顶上限记一行 {@code patch_keepalive_give_up} 后这一类不再哨兵（不新开闸门）。
     */
    private static final class Keep {

        /** 上次动手时刻。 */
        long lastAtMillis;

        /** 上次动手时的"计数器总和"；{@code -1} = 还没有基线（第一轮只建基线，不下结论）。 */
        long lastCounterSum = -1L;

        /** 上次动手时的"栈证据总和"（探针账本 hits 之和）。 */
        long lastStrikes = -1L;

        /** 上一次打 {@code result=same} 的时刻。 */
        long lastSameLogMillis;

        /** 失败的退避计数（与反制同一套：{@link HotfixPatchProbe#judgeRetaliate}）。 */
        final long[] attempts = new long[8];
        int attemptCount;

        /** 顶到上限了 ⇒ 这一类永久停手。 */
        boolean givenUp;

        /** 退避要求的下一次最早时刻。 */
        long nextAtMillis;

        // ---- 🆕 2026-11-20（规则③：patch 存活）----

        /** 这个类一共被 {@code retransform} 了多少次（{@code patch_lost} 要报"第几次"）。 */
        int retransformCount;

        /** 连续几次"重打之后仍然不一致"（到 {@link #PATCH_LOST_GIVEUP} 就停手）。 */
        int consecutiveLost;

        /** 放弃即时重打了（{@code patch_giveup reason=lost3}）；10 s 哨兵仍照旧跑。 */
        boolean repairOff;

        /**
         * 🆕 2026-11-21：<b>这个类的重打预算用掉了没有</b>（同一次会话内每类最多重打 <b>1</b> 次）。
         *
         * <p>为什么要预算：实测那三轮 {@code patch_repair} 的字节长度一路变短
         * （31902 → 31360 → 31002）⇒ 每重打一次都在"上一轮的结果"上继续叠加。
         * 所以重打必须是<b>一次性的</b>：用完就只记录（日志 {@code patch_giveup reason=repair_budget_used}）。
         */
        boolean repairUsed;

        /**
         * 🆕 2026-11-21：最近一次 {@code bytes_match=false} 的判定依据
         * （{@code cannot_read_current}／{@code self_output_unstable}／{@code lost_proven}），
         * 进 {@link #keepaliveLine()} 便于复盘"凭什么认为它丢了"。
         */
        String lastLostEvidence = "";
    }

    /** 连续丢几次就停止即时重打（用户口径：3 次；别无限重试）。 */
    public static final int PATCH_LOST_GIVEUP = 3;

    /** 哨兵需要 tick 吗（有 wanted ＋ auto 开着）。 */
    private static boolean keepaliveActive() {
        return AUTO.get() && wantedCount() > 0;
    }

    /** 现在立刻给所有 wanted 类跑一轮哨兵（命令／离线自检用；忽略 10 s 间隔）。 */
    public static int keepaliveNow() {
        if (!AUTO.get()) {
            return 0;
        }
        final long now = System.currentTimeMillis();
        final List<String> classes = wantedClasses();
        int done = 0;
        for (int i = 0; i < classes.size(); i++) {
            if (keepaliveOnce(now, classes.get(i), "manual")) {
                done++;
            }
        }
        return done;
    }

    /** 到点的类跑一轮（工作线程 tick 调；只在 10 s 边界上动手）。 */
    private static void keepaliveTick(long nowMillis) {
        if (!AUTO.get()) {
            return;
        }
        final List<String> classes = wantedClasses();
        for (int i = 0; i < classes.size(); i++) {
            final String className = classes.get(i);
            final Keep keep;
            synchronized (KEEPS) {
                keep = KEEPS.get(className);
            }
            if (keep != null && keep.givenUp) {
                continue;
            }
            final long due = keep == null ? 0L
                    : Math.max(keep.lastAtMillis + KEEPALIVE_INTERVAL_MILLIS, keep.nextAtMillis);
            if (nowMillis < due) {
                continue;
            }
            keepaliveOnce(nowMillis, className, "tick");
        }
    }

    /** wanted 里出现过的类名（去重、稳定顺序）。 */
    private static List<String> wantedClasses() {
        final List<String> out = new ArrayList<>(2);
        synchronized (CHAT_PATCHED_KEYS) {
            for (Iterator<Want> it = CHAT_PATCHED_KEYS.values().iterator(); it.hasNext(); ) {
                final Want w = it.next();
                if (w != null && !w.className.isEmpty() && !out.contains(w.className)) {
                    out.add(w.className);
                }
            }
        }
        return out;
    }

    /** 某一个类的 wanted 集合（组 plan 用）。 */
    private static List<PlanEntry> wantedPlan(String className) {
        final List<PlanEntry> plan = new ArrayList<>(2);
        synchronized (CHAT_PATCHED_KEYS) {
            for (Map.Entry<String, Want> e : CHAT_PATCHED_KEYS.entrySet()) {
                final Want w = e.getValue();
                if (w == null || e.getKey() == null || !className.equals(w.className)) {
                    continue;
                }
                plan.add(new PlanEntry(e.getKey(), w.methodName, w.desc, w.lineHint, w.tier));
            }
        }
        return plan;
    }

    /**
     * 给一个类跑一轮哨兵：<b>只用 wanted 集合</b>重打一次，然后判"被换回去了没有"。
     *
     * @param nowMillis  现在
     * @param className  类名
     * @param why        {@code tick}／{@code manual}（只进日志）
     * @return {@code true} = 真的动手了
     */
    private static boolean keepaliveOnce(long nowMillis, String className, String why) {
        final List<PlanEntry> plan = wantedPlan(className);
        // 🆕 2026-11-21（根因③）：我们自己的写入窗口里一律不查（那一趟"类在变"是我们干的）
        if (HotfixSelfWindow.skip("keepalive")) {
            return false;
        }
        // 🆕 2026-11-21（根因④）：这一类已经被判"改了也没用"⇒ 停手（10 s 哨兵也不再重写）
        if (HotfixPatchEffect.blocked(className)) {
            return false;
        }
        if (plan.isEmpty()) {
            synchronized (KEEPS) {
                KEEPS.remove(className);
            }
            return false;
        }
        final Instrumentation inst = HotfixHolder.instrumentation();
        final PatchTransformer tx = transformer;
        if (inst == null || tx == null || !transformerAdded) {
            recordKeep(className, nowMillis, "failed", 0, 0, 0L, false,
                    inst == null ? "no_instrumentation" : "transformer_unavailable", why);
            return false;
        }
        // ---- 证据快照（动手之前取：计数器只增，栈证据也是累计值）
        long counterSum = 0L;
        for (int i = 0; i < plan.size(); i++) {
            counterSum += HotfixProbeCounter.count(plan.get(i).key);
        }
        long strikes = 0L;
        synchronized (PROBE_LOCK) {
            for (Iterator<HotfixPatchWatch> it = WATCHES.values().iterator(); it.hasNext(); ) {
                final HotfixPatchWatch w = it.next();
                if (w != null && className.equals(w.className())) {
                    strikes += w.hits();
                }
            }
        }
        final Keep keep = keepLocked(className);
        final long lastCounter = keep.lastCounterSum;
        final long lastStrikes = keep.lastStrikes;
        final long counterDelta = lastCounter < 0L ? 0L : counterSum - lastCounter;
        final long strikeDelta = lastStrikes < 0L ? 0L : strikes - lastStrikes;

        // ---- 幂等重打（只用 wanted；同类所有同名 Class 都来一次，与 apply 的口径一致）
        final PlanRun run = runPlan(inst, tx, className, plan,
                findLoadedAll(inst, className).toArray(new Class<?>[0]));
        // "这个类一共被 retransform 了几次"——patch_lost 要报的就是它
        keep.retransformCount += run.attempts;
        if ("wanted_changed".equals(run.detail)) {
            return false;      // 什么都没做：目标已经被显式 undo 掉了
        }
        boolean ok = run.ok;
        boolean bytesMatch = run.bytesMatch;
        String detail = run.detail;

        // ---- 🔴 2026-11-21（规则③收口）：bytes_match=false ⇒ <b>只记录，不再重打</b>
        //
        //   为什么收口（实测日志，两个危险信号）：
        //     patch_repair class=…util.EntityUtil attempt=1 before=ad7174…/31902 after=5df3d8…/31360 match=false retransform_count=3
        //     patch_lost   class=…util.EntityUtil retransform_count=3 consecutive_lost=1
        //     patch_repair … before=5df3d8…/31360 after=56f533…/31002 match=false retransform_count=5
        //   ① <b>第一次 attempt 就是 match=false</b>：这套"检测有没有丢"的判据本来就不可靠
        //      —— 我们读不到 retransform 之后的"当前定义"（{@link HotfixPatchReadback} 已实测：
        //      transformer 拿到的永远是初始基线 ⇒ {@code patch_readback verdict=unknown}）；
        //   ② <b>字节长度一路变短</b>（31902 → 31360 → 31002）：强烈提示每次都在"上一轮的结果"上重打
        //      ⇒ 这不是修复，是<b>叠加/腐蚀</b>。
        //   ⇒ 宁可不修，不许改坏：这一轮起 {@code bytes_match=false} 只落一行 ERROR。
        //     重打降级成"<b>能证明字节确实不在了</b>才打、<b>每个类一次</b>"（见 {@link #repairAllowed}）。
        if (ok && !bytesMatch) {
            // 🆕 2026-11-21（P2）：计数<b>有界</b>（到 3 就停，不再涨到 38）；
            //   它仍然是一个真实的读数：连续几轮"我们写的与账本不一致"。
            if (keep.consecutiveLost < PATCH_LOST_GIVEUP) {
                keep.consecutiveLost++;
            }
            // 🆕 2026-11-21（P2）：证据分类改成"写入者 ＋ 连算两次的稳定性"两项可观测事实。
            //   诊断（只记，不修）：{@code reason=recover} 的那一趟永远修不好"别人换掉我们的字节"
            //   （P1/P2 实测读不到当前定义），重打只会叠加 ⇒ 闸门仍然关着（repairAllowed 只认 lost_proven）。
            final String evidence = lostEvidence(run.beforeFp, run.afterFp, className,
                    HotfixPatchPlan.WRITER_PATCH, Boolean.valueOf(run.stableOutput));
            keep.lastLostEvidence = evidence;
            // 🔴 2026-11-21（P2，用户口径①）：{@code patch_lost} 从 ERROR 降到 <b>WARN</b>
            //   —— 它把真正的错误埋了（这一局 145 条 ERROR 几乎全是它）。
            //   并且<b>有界</b>：同一类最多 {@link #PATCH_LOST_GIVEUP} 条；之后只留一行 giveup，
            //   不再每 10 s 刷一条（现场 consecutive_lost 一路涨到 38，日志里全是它）。
            if (keep.consecutiveLost <= PATCH_LOST_GIVEUP) {
                warn(patchLostLine(className, keep.retransformCount, keep.consecutiveLost, evidence,
                        run.beforeFp, run.afterFp, why, run.writerTag));
            } else {
                SUPPRESSED_LOGS.incrementAndGet();
            }
            if (repairAllowed(evidence, keep.repairUsed, keep.repairOff)) {
                // ---- 只有"证明丢了"才走到这里；且每类一次（Keep#repairUsed 是预算本身）
                keep.repairUsed = true;
                final PlanRun repair = runPlan(inst, tx, className, plan,
                        findLoadedAll(inst, className).toArray(new Class<?>[0]));
                keep.retransformCount += repair.attempts;
                if (!"wanted_changed".equals(repair.detail)) {
                    final boolean match = repair.bytesMatch;
                    warn(patchRepairLine(className, keep.retransformCount, evidence,
                            repair.beforeFp, repair.afterFp, match, why));
                    if (match) {
                        keep.consecutiveLost = 0;      // 救回来了 ⇒ 连续丢失计数清零
                    }
                }
            } else if (LOST_PROVEN.equals(evidence) && keep.repairUsed && !keep.repairOff) {
                // 预算已经用过、又证明丢了第二次 ⇒ 停手（不再叠加）
                keep.repairOff = true;
                warn(patchGiveupLine(className, "repair_budget_used", keep.retransformCount,
                        "每类只允许重打一次；预算用完⇒只记录，10s哨兵照旧"));
            }
            // 连续 lost 到上限 ⇒ 停掉即时重打（10 s 哨兵照旧；这条与老日志逐字同名）
            if (keep.consecutiveLost >= PATCH_LOST_GIVEUP && !keep.repairOff) {
                keep.repairOff = true;
                warn(patchGiveupLine(className, "lost" + PATCH_LOST_GIVEUP, keep.retransformCount,
                        "停止即时重打，10s哨兵照旧"));
                // 🆕 2026-11-21（P2 追加）：把"逐方法补丁被放弃"播到聊天栏（同类只播一次、1 秒 1 条）。
                //   🔴 只加播报：判定与写入一个字节都不动（repairOff / 计数 / 日志都在上面）。
                chatGiveup(className);
            }
        } else if (ok) {
            keep.consecutiveLost = 0;                  // 一致 ⇒ 连续丢失计数清零
        }

        // ---- 判结论（行为侧交叉：有栈证据但计数器没动 ⇒ 跑的不是我们那份字节）
        final String result;
        if (!ok) {
            result = "failed";
        } else if (strikeDelta > 0L && counterDelta <= 0L) {
            result = "reapplied_after_revert";
        } else {
            result = "same";
        }
        recordKeep(className, nowMillis, result, plan.size(), counterDelta, counterDelta, bytesMatch,
                detail, why, strikeDelta);
        return true;
    }

    /**
     * 🆕 2026-11-20：<b>一趟</b>幂等重打的结论（规则③的修复路径要连打两趟，所以抽出来）。
     *
     * <p>抽出来的唯一理由是"重打两次"这件事必须走<b>同一条</b>代码路径：复制一份，
     * 出现的形态就是"修复那次和正常那次行为不一样"，而这种差异只会在现场炸。
     */
    private static final class PlanRun {

        /** 至少有一个方法真的套上去了。 */
        boolean ok;

        /** 我们这次写下去的字节与账本记录的那份一致（{@code bytes_match}）。 */
        boolean bytesMatch;

        /** 失败原因（{@code ok} / {@code class_not_loaded} / {@code wanted_changed} / {@code retransform_*}）。 */
        String detail = "ok";

        /** 账本里记的那一份指纹（{@code before_sha=}）。 */
        HotfixPatchReadback.Fingerprint beforeFp = HotfixPatchReadback.Fingerprint.NONE;

        /** 这一次写下去的那份指纹（{@code after_sha=}）。 */
        HotfixPatchReadback.Fingerprint afterFp = HotfixPatchReadback.Fingerprint.NONE;

        /** 这一次真的调了几次 {@code retransformClasses}。 */
        int attempts;

        /** 🆕 这个类被几条写入路径写过（{@code patch}／{@code chain_wipe}／{@code both}）。 */
        String writerTag = "";

        /** 🆕 同一输入连算两次是否一致（{@code false} 才允许 evidence=self_output_unstable）。 */
        boolean stableOutput = true;

        /** 🆕 这一趟的计划与上一趟是否不同（不同 ⇒ 字节不同是正常的，不算丢）。 */
        boolean planChanged;
    }

    /**
     * 用 wanted 集合把这一个类的所有同名 {@code Class} 各 {@code retransform} 一次。
     *
     * @param inst       Instrumentation
     * @param tx         transformer（{@code pending} 由这里装／卸）
     * @param className  源级类名
     * @param plan       这一轮要套的方法
     * @param targets    这一轮要 retransform 的 {@code Class}（空 ⇒ {@code class_not_loaded}）
     * @return 这一趟的结论（不抛）
     */
    private static PlanRun runPlan(Instrumentation inst, PatchTransformer tx, String className,
                                   List<PlanEntry> plan, Class<?>[] targets) {
        final PlanRun out = new PlanRun();
        if (targets == null || targets.length == 0) {
            out.detail = "class_not_loaded";
            return out;
        }
        LOCK.lock();
        try {
            // 🔴 竞态闸（离线逮到过）：组 plan 之后、拿锁之前，{@code undo}／{@code undoAll}
            //   可能已经把这些目标撤掉（undoAll 还会清空 wanted）。此时若照旧重打，
            //   就会把刚刚还原好的类又改成安全默认值 —— 还原被自己人顶掉。
            //   ⇒ 拿锁后按"当前 wanted"重新核对，只要有一个已经不 wanted 就整轮放弃（静默，不算失败、不退避）。
            if (!stillWantedLocked(plan)) {
                out.detail = "wanted_changed";
                return out;
            }
            // 🆕 2026-11-21（根因①/②）：这条路也是**整张计划一次写**，而且从基线出发。
            //   它的语义是"把这一类应该改着的方法全套一遍"（reason=recover：这是在恢复状态，
            //   不是"又发现了新目标"）—— 用户口径：「之前重写过的内容，加上这回要新补的，一起重写」。
            final List<Class<?>> targetsList = new ArrayList<>(targets.length);
            for (int i = 0; i < targets.length; i++) {
                targetsList.add(targets[i]);
            }
            final String reason = HotfixPatchPlan.written(className)
                    ? HotfixPatchPlan.REASON_RECOVER : HotfixPatchPlan.REASON_FIRST;
            // 🆕 2026-11-21（P2）：**写之前**先把"我们自己上次写下的那份类级身份"采样下来
            //   （rev／sha／长度／计划摘要／写入者）。以前比的是"账本里这个类某一条方法补丁的指纹"
            //   ⇒ 计划里方法一变就必然不等 ⇒ 现场 134 条假的 self_output_unstable。
            final HotfixPatchPlan.ClassPlan cp = HotfixPatchPlan.plan(className);
            final long beforeRev = cp.rev();
            final String beforeSha = cp.lastSha();
            final long beforeLen = cp.lastLen();
            final String beforeDigest = cp.lastDigest();
            final String beforeWriter = cp.lastWriter();
            out.writerTag = cp.writerTag();
            final PlanWrite w = writePlanLocked(inst, tx, className, targetsList, plan, "", reason,
                    HotfixPatchPlan.WRITER_PATCH);
            out.attempts = w.retransforms;
            out.ok = w.ok;
            if (!w.ok) {
                out.detail = w.detail;
                return out;
            }
            // "我们这次写下去的字节"与"我们自己上次写下的字节"是否一致。
            //   🔴 计划变了（新加方法／升档）⇒ 字节本来就应当不同，那**不是**"补丁丢了"
            //   ⇒ 只有"计划一模一样"才允许判 bytes_match=false。
            //   🔴 2026-11-21（P2 追加）：上一次写若是**关键类还原**（另一套手术、不动计划），
            //      字节不同也是**预期**的（它把 handler 清空了）⇒ 同样不算丢：这一趟写下去就把我们的
            //      字节再压回去；下一趟两者都是我们自己写的，才需要真比。
            final boolean planChanged = beforeRev > 0L && !beforeDigest.equals(w.digest);
            final boolean sameSha = w.newSha != null && w.newSha.equals(beforeSha);
            out.bytesMatch = beforeRev <= 0L || planChanged || sameSha
                    || HotfixPatchPlan.WRITER_RESTORE.equals(beforeWriter);
            out.stableOutput = w.stableOutput;
            out.planChanged = planChanged;
            out.beforeFp = new HotfixPatchReadback.Fingerprint(beforeSha, (int) beforeLen);
            out.afterFp = w.patchedFp == null ? HotfixPatchReadback.Fingerprint.NONE : w.patchedFp;
        } finally {
            LOCK.unlock();
        }
        return out;
    }

    /** 记一轮哨兵的结论 ＋ 按规矩打日志（{@code same} 每类 60 s 一行；失败走既有退避/上限）。 */
    private static void recordKeep(String className, long nowMillis, String result, int methods,
                                   long counterDelta, long counterSum, boolean bytesMatch,
                                   String detail, String why) {
        recordKeep(className, nowMillis, result, methods, counterDelta, counterSum, bytesMatch,
                detail, why, 0L);
    }

    private static void recordKeep(String className, long nowMillis, String result, int methods,
                                   long counterDelta, long counterSum, boolean bytesMatch,
                                   String detail, String why, long strikeDelta) {
        final Keep keep = keepLocked(className);
        final long wanted = wantedCount();
        long counterNow = 0L;
        synchronized (CHAT_PATCHED_KEYS) {
            for (Iterator<Want> it = CHAT_PATCHED_KEYS.values().iterator(); it.hasNext(); ) {
                final Want w = it.next();
                if (w != null && className.equals(w.className)) {
                    counterNow += HotfixProbeCounter.count(w.key);
                }
            }
        }
        long strikesNow = 0L;
        synchronized (PROBE_LOCK) {
            for (Iterator<HotfixPatchWatch> it = WATCHES.values().iterator(); it.hasNext(); ) {
                final HotfixPatchWatch w = it.next();
                if (w != null && className.equals(w.className())) {
                    strikesNow += w.hits();
                }
            }
        }
        keep.lastAtMillis = nowMillis;
        keep.lastCounterSum = counterNow;
        keep.lastStrikes = strikesNow;
        keep.nextAtMillis = 0L;

        if ("failed".equals(result)) {
            KEEP_FAILED.incrementAndGet();
            final HotfixPatchProbe.Throttle t = HotfixPatchProbe.judgeRetaliate(
                    keep.attempts, keep.attemptCount, keep.lastAtMillis, nowMillis);
            if (t.atCap()) {
                keep.givenUp = true;
                KEEP_GIVEUP.incrementAndGet();
                log("patch_keepalive_give_up class=" + token(className) + " attempts=" + keep.attemptCount
                        + " reason=throttled window_ms=" + HotfixPatchProbe.RETALIATE_WINDOW_MILLIS
                        + " detail=" + token(detail));
                return;
            }
            keep.attemptCount = recordKeepAttempt(keep, nowMillis);
            keep.nextAtMillis = nowMillis + (t.allowed ? HotfixPatchProbe.backoffMillis(t.attempts)
                    : t.waitMillis);
            log("patch_keepalive class=" + token(className) + " methods=" + methods + " wanted=" + wanted
                    + " result=failed detail=" + token(detail)
                    + " attempts=" + keep.attemptCount
                    + " retry_in_ms=" + keep.nextAtMillis + " why=" + token(why));
            return;
        }
        // 成功：清退避
        keep.attemptCount = 0;
        keep.nextAtMillis = 0L;
        if ("reapplied_after_revert".equals(result)) {
            KEEP_REAPPLIED.incrementAndGet();
            log("patch_keepalive class=" + token(className) + " methods=" + methods + " wanted=" + wanted
                    + " result=reapplied_after_revert"
                    + " stack_hits=" + strikeDelta + " counter_delta=" + counterDelta
                    + " bytes_match=" + bytesMatch
                    + " interval_ms=" + KEEPALIVE_INTERVAL_MILLIS
                    + " why=" + token(why)
                    + " note=有栈证据但计数器没动⇒跑的不是我们那份字节，已按wanted重打");
            return;
        }
        KEEP_SAME.incrementAndGet();
        final boolean logIt = keep.lastSameLogMillis <= 0L
                || nowMillis - keep.lastSameLogMillis >= KEEPALIVE_SAME_LOG_INTERVAL_MILLIS;
        if (logIt) {
            keep.lastSameLogMillis = nowMillis;
            log("patch_keepalive class=" + token(className) + " methods=" + methods + " wanted=" + wanted
                    + " result=same"
                    + " stack_hits=" + strikeDelta + " counter_delta=" + counterDelta
                    + " bytes_match=" + bytesMatch
                    + " interval_ms=" + KEEPALIVE_INTERVAL_MILLIS
                    + " why=" + token(why));
        } else {
            SUPPRESSED_LOGS.incrementAndGet();
        }
    }

    /** 记一次失败的重打时刻（与反制共用同一套滑窗表）。 */
    private static int recordKeepAttempt(Keep keep, long nowMillis) {
        if (keep.attemptCount < keep.attempts.length) {
            keep.attempts[keep.attemptCount++] = nowMillis;
            return keep.attemptCount;
        }
        System.arraycopy(keep.attempts, 1, keep.attempts, 0, keep.attempts.length - 1);
        keep.attempts[keep.attempts.length - 1] = nowMillis;
        return keep.attemptCount;
    }

    /** 取（必要时建）一个类的哨兵状态。 */
    private static Keep keepLocked(String className) {
        synchronized (KEEPS) {
            Keep keep = KEEPS.get(className);
            if (keep == null) {
                keep = new Keep();
                KEEPS.put(className, keep);
            }
            return keep;
        }
    }

    /**
     * 我们这次写下去的字节，与账本里记的那份是否一致（{@code bytes_match}）。
     *
     * <p>⚠️ 这一项只能说明"我们的输出稳定"，<b>不能</b>说明"类当前是不是我们的字节"
     * （P1/P2 实测：读不到当前定义）—— 日志里它是辅证，主证是 {@code stack_hits}/{@code counter_delta}。
     */
    private static boolean bytesMatchLocked(String className, HotfixPatchReadback.Fingerprint nowFp) {
        final HotfixPatchReadback.Fingerprint rec = recordedFpLocked(className);
        final HotfixPatchReadback.Fingerprint now = nowFp;
        if (now == null || rec == null || !now.comparable() || !rec.comparable()) {
            return false;
        }
        return rec.hash.equals(now.hash) && rec.length == now.length;
    }

    /**
     * 账本里记的这个类"当初写下去的字节"指纹（{@code before_sha=} 用）。
     *
     * <p>找不到 ⇒ {@link HotfixPatchReadback.Fingerprint#NONE}（不可比）：
     * 此时 {@code bytes_match=false} 只意味着"没得比"，<b>不许</b>据此判定"字节丢了"
     * （规则③的即时重打也拿它当闸门）。
     */
    private static HotfixPatchReadback.Fingerprint recordedFpLocked(String className) {
        for (Iterator<Map.Entry<String, Patch>> it = PATCHED.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, Patch> e = it.next();
            final Patch p = e.getValue();
            if (p == null || !className.equals(p.className)) {
                continue;
            }
            return p.patchedFpOf(0);
        }
        return HotfixPatchReadback.Fingerprint.NONE;
    }

    /** 某个类的哨兵读数（命令/离线自检用）。 */
    public static String keepaliveLine() {
        final StringBuilder sb = new StringBuilder(192);
        sb.append("keepalive_interval_ms=").append(KEEPALIVE_INTERVAL_MILLIS)
                .append(" same=").append(KEEP_SAME.get())
                .append(" reapplied=").append(KEEP_REAPPLIED.get())
                .append(" failed=").append(KEEP_FAILED.get())
                .append(" give_up=").append(KEEP_GIVEUP.get())
                .append(" classes=").append(KEEPS.size());
        synchronized (KEEPS) {
            for (Map.Entry<String, Keep> e : KEEPS.entrySet()) {
                final Keep k = e.getValue();
                if (k == null) {
                    continue;
                }
                sb.append(" [").append(e.getKey()).append(" last_at_ms=").append(k.lastAtMillis)
                        .append(" counter_sum=").append(k.lastCounterSum)
                        .append(" strikes=").append(k.lastStrikes)
                        .append(" retransforms=").append(k.retransformCount)
                        .append(" lost_streak=").append(k.consecutiveLost)
                        .append(" repair_used=").append(k.repairUsed ? 1 : 0)
                        .append(k.lastLostEvidence.isEmpty() ? "" : " lost_evidence=" + token(k.lastLostEvidence))
                        .append(k.repairOff ? " repair=off" : "")
                        .append(k.givenUp ? " given_up=true" : "")
                        .append(']');
            }
        }
        return sb.toString();
    }

    // ═══════════════════ 🆕 2026-11-21：patch 丢失的证据 / 重打预算（纯函数，可离线断言） ═══════════════════

    /**
     * 证据码：<b>读不到"类当前的定义"</b> ⇒ 无法证明我们的字节不在了。
     *
     * <p>这是既有边界，不是这轮引入的：{@code retransformClasses} 交给 transformer 的永远是<b>初始基线</b>
     * （{@link HotfixPatchReadback} 的类注释有实测记录）⇒ {@code patch_readback verdict=unknown}。
     */
    public static final String LOST_CANNOT_READ = "cannot_read_current";

    /**
     * 证据码：两份指纹都拿得到但<b>不一致</b> —— 它只说明"我们这次产出的字节与账本记的不一样"，
     * <b>不能</b>说明"类里现在不是我们的字节"。
     *
     * <p>🆕 2026-11-21（P2）：这一码<b>降级</b>为"只有同一输入连算两次真的不同才用"
     * （见 {@link #probeStableOutput}）。以前只要"比得上但不一致"就用它，于是现场 134 条
     * 全是它 —— 而真相是<b>两条写入路径抢写</b>＋账本记的是方法级指纹。
     */
    public static final String LOST_SELF_UNSTABLE = "self_output_unstable";

    /**
     * 🆕 2026-11-21（P2）：证据码 —— <b>另一条写入路径</b>动过这个类。
     *
     * <p>判据（两条同时成立）：① 本次输出的字节与"我们自己上次写下的<b>类级</b>身份"不同；
     * ② 这张类计划里<b>有另一条写入路径</b>（普通补丁 ↔ 整链抹除）的记账（{@link HotfixPatchPlan.ClassPlan#otherWriterSeen}）。
     *
     * <p>为什么它能覆盖现场那 134 条：{@code patch} 与 {@code chain_wipe} 对同一个类各写一次时，
     * 后写的那一方拿到的"上次身份"是另一方写下的字节 ⇒ 不一致是<b>必然</b>，那是抢写，不是我们不稳定。
     */
    public static final String LOST_CONCURRENT_WRITER = "concurrent_writer";

    /**
     * 🆕 2026-11-21（P2）：证据码 —— 输出稳定、也没有别的写入者，但<b>计划本身变了</b>
     * （新加了一个方法／升了档 ⇒ 字节本来就该不同）。
     *
     * <p>它也是"不是丢补丁"的一类：以前的实现把它算成 {@code self_output_unstable}，
     * 于是每加一个方法就凭空多一条 ERROR。
     */
    public static final String LOST_PLAN_MOVED = "plan_moved";

    /**
     * 证据码：<b>能证明"类当前的定义确实不是我们写下去的那份"</b>（＝允许重打的唯一依据）。
     *
     * <p>⚠️ 今天<b>拿不到</b>：它要求能读出"当前定义"，而那条通道不可观测（见 {@link #LOST_CANNOT_READ}）。
     * 所以 {@link #lostEvidence} 现在永远不返回它 ⇒ 重打这条路是<b>关着</b>的。等哪天有可观测证据，
     * <b>只改 {@link #lostEvidence} 一处</b>，预算逻辑（{@link #repairAllowed}）已经在位。
     */
    public static final String LOST_PROVEN = "lost_proven";

    /**
     * {@code bytes_match=false} 的<b>判定依据</b>（{@code patch_lost evidence=…} 就是这份文本）。
     *
     * @param before 账本里记的"当初写下去那一条"（{@code before_sha=}）
     * @param after  这一轮我们产出的那一份（{@code after_sha=}）
     * @return {@link #LOST_CANNOT_READ}／{@link #LOST_SELF_UNSTABLE}（今天不会返回 {@link #LOST_PROVEN}）
     */
    static String lostEvidence(HotfixPatchReadback.Fingerprint before, HotfixPatchReadback.Fingerprint after) {
        return lostEvidence(before, after, null, null, null);
    }

    /**
     * 🆕 2026-11-21（P2）：<b>带写入者／稳定性探针</b>的证据分类（纯函数，离线直接断言）。
     *
     * <pre>
     *   比不了（拿不到指纹）                       ⇒ cannot_read_current
     *   同一输入连算两次就不同（stableOutput=false）⇒ self_output_unstable   ← 只有这一种
     *   另一条写入路径写过这个类                    ⇒ concurrent_writer       ← 抢写（现场 134 条的真相）
     *   输出稳定、没有别的写入者，但计划变了        ⇒ plan_moved              ← 不是"丢了"
     * </pre>
     *
     * @param className  源级类名（{@code null} ⇒ 不查类计划）
     * @param thisWriter 这一次是谁写的（{@link HotfixPatchPlan#WRITER_PATCH}／{@code WRITER_CHAIN_WIPE}）
     * @param stable     同一输入连算两次是否一致（{@code null} = 没探过）
     */
    static String lostEvidence(HotfixPatchReadback.Fingerprint before, HotfixPatchReadback.Fingerprint after,
                               String className, String thisWriter, Boolean stable) {
        if (before == null || after == null || !before.comparable() || !after.comparable()) {
            return LOST_CANNOT_READ;      // 没得比 ⇒ 说不出"丢了"，只能写"读不到"
        }
        if (Boolean.FALSE.equals(stable)) {
            // ① 唯一允许说"我们自己输出不稳定"的情形：同一输入连算两次就不一样
            return LOST_SELF_UNSTABLE;
        }
        if (className != null && !className.isEmpty()
                && HotfixPatchPlan.plan(className).otherWriterSeen(thisWriter)) {
            // ② 两条写入路径都写过这个类 ⇒ 身份被另一方推进过，不一致是必然的
            return LOST_CONCURRENT_WRITER;
        }
        // ③ 输出稳定、没有抢写 ⇒ 只可能是计划本身变了（字节本来就该不同），不是"补丁丢了"
        return LOST_PLAN_MOVED;
    }

    /**
     * 允不允许"立刻重打一次"（<b>唯一</b>的闸门）。
     *
     * <p>三条同时成立才放行：① 证据是 {@link #LOST_PROVEN}（能证明丢了）；
     * ② 这个类的预算没用过（{@code repairUsed=false}）；③ 没有被 {@code lost3} 关掉。
     * ⇒ 今天①永远不成立，所以<b>一次重打都不会发生</b>（宁可不修，不许改坏）。
     */
    static boolean repairAllowed(String evidence, boolean repairUsed, boolean repairOff) {
        return LOST_PROVEN.equals(evidence) && !repairUsed && !repairOff;
    }

    /**
     * {@code patch_lost} 的逐字拼法（<b>纯函数</b>：离线自检直接断言这段文本，不改就是游戏里那一行）。
     *
     * <pre>
     *   patch_lost class=… retransform_count=5 consecutive_lost=2 evidence=self_output_unstable \
     *        before_sha=5df3d8f408bbf3f7/31360 after_sha=56f533e20f69b1a6/31002 action=none \
     *        note=only_recorded_no_repair why=tick
     * </pre>
     */
    static String patchLostLine(String className, int retransformCount, int consecutiveLost, String evidence,
                                HotfixPatchReadback.Fingerprint before, HotfixPatchReadback.Fingerprint after,
                                String why) {
        return patchLostLine(className, retransformCount, consecutiveLost, evidence, before, after, why, "");
    }

    /**
     * 🆕 2026-11-21（P2）：带 {@code writer=} 的 {@code patch_lost}。
     *
     * <p>{@code writer} = 这个类<b>被几条写入路径写过</b>（{@code patch}／{@code chain_wipe}／{@code both}）——
     * 一行就能判"这条 lost 是两条路径抢写造成的，还是我们自己算出来的字节真的变了"。
     */
    static String patchLostLine(String className, int retransformCount, int consecutiveLost, String evidence,
                                HotfixPatchReadback.Fingerprint before, HotfixPatchReadback.Fingerprint after,
                                String why, String writer) {
        return "patch_lost class=" + token(className)
                + " retransform_count=" + retransformCount
                + " consecutive_lost=" + consecutiveLost
                + " evidence=" + token(evidence)
                + " before_sha=" + token(before == null ? "?" : before.brief())
                + " after_sha=" + token(after == null ? "?" : after.brief())
                + (writer == null || writer.isEmpty() ? "" : " writer=" + token(writer))
                + " action=none"
                + " note=only_recorded_no_repair"
                + " why=" + token(why);
    }

    /** {@code patch_repair} 的逐字拼法（纯函数；只有在 {@link #repairAllowed} 放行时才会打）。 */
    static String patchRepairLine(String className, int retransformCount, String evidence,
                                  HotfixPatchReadback.Fingerprint before, HotfixPatchReadback.Fingerprint after,
                                  boolean match, String why) {
        return "patch_repair class=" + token(className)
                + " attempt=1 budget=1"
                + " evidence=" + token(evidence)
                + " before_sha=" + token(before == null ? "?" : before.brief())
                + " after_sha=" + token(after == null ? "?" : after.brief())
                + " match=" + match
                + " retransform_count=" + retransformCount
                + " why=" + token(why);
    }

    /** {@code patch_giveup} 的逐字拼法（{@code reason=lost3}／{@code reason=repair_budget_used}）。 */
    static String patchGiveupLine(String className, String reason, int retransformCount, String note) {
        return "patch_giveup class=" + token(className)
                + " reason=" + token(reason)
                + " retransform_count=" + retransformCount
                + " note=" + token(note);
    }

    /** 探针账本的读数（命令与日志共用；一行）。 */
    public static String probeLine() {
        final StringBuilder sb = new StringBuilder(256);
        synchronized (PROBE_LOCK) {
            sb.append("probe_watches=").append(WATCHES.size());
            for (Iterator<HotfixPatchWatch> it = WATCHES.values().iterator(); it.hasNext(); ) {
                final HotfixPatchWatch w = it.next();
                if (w != null) {
                    sb.append(" [").append(w.brief()).append(']');
                }
            }
        }
        return sb.toString();
    }

    // ═══════════════════════════════ 🆕 读回取证 ═══════════════════════════════

    /**
     * <b>patch 之后的取证（零副作用）</b>：把这次 patch 能观测到的事实落成一行
     * {@code patch_readback}，并顺手把<b>被这次 patch 撤掉的同类旧目标</b>报出来。
     *
     * <p>🔴 为什么这里<b>不做</b> retransform（任务书原本要求"立刻做一次只读读回"）：
     * 2026-11-08 13:0x 用真 JVM 量到（{@code temp\hotfix\offline-readback\RbPremise.java}）：
     * <ol>
     *   <li>{@code retransform} 交给 transformer 的是<b>初始基线</b>，不是"上次定义后的版本"；</li>
     *   <li>一次"全 null"的 retransform 会把类<b>退回基线</b>（等价于 undo）。</li>
     * </ol>
     * ⇒ 在 patch 之后做"只读读回"，实际效果是<b>1 tick 后自己把补丁撤掉</b>
     * （第一版就这么写的，离线套件 {@code a2.verify_changed} 当场变红）。
     * 所以这里改成"不碰类"的取证：能观测的（两份指纹、Mixin 包装数、同类互撤）全部落账；
     * 观测不到的（当前字节）明确写 {@code verdict=unknown unknown_reason=not_observable}，
     * <b>不拿基线冒充 live</b> —— 那会把每一个正常补丁都报成"被织回去了"。
     *
     * <p>能观测到的那一条最要紧：<b>同一类的第二个 patch 会把第一个 patch 撤掉</b>（实测 P3）。
     * 每发现一个"同一 {@code Class} 的旧目标"，就替它打一行
     * {@code patch_reverted … action=none reason=observed_only}（只报不重打；重打是另一条线的事）。
     *
     * @param inst    {@link Instrumentation}（这里不碰它，留参数只为调用点统一）
     * @param key     {@code 类#方法}
     * @param outcome 这次 patch 的结论
     * @param why     触发原因（只进日志）
     * @return 这次的 verdict（正常固定 {@link HotfixPatchReadback#VERDICT_UNKNOWN}）
     */
    private static String readbackAfterPatch(Instrumentation inst, String key, Outcome outcome, String why) {
        final Patch record = outcome == null ? null : outcome.record;
        if (record == null) {
            return "?";
        }
        final int mixins;
        final List<String> siblings;
        LOCK.lock();
        try {
            siblings = rescindedTargets(key, record);
            mixins = HotfixPatchReadback.mixinWrappers(
                    record.classes.length == 0 ? null : record.classes[0]);
            record.readbackVerdict = HotfixPatchReadback.VERDICT_UNKNOWN;
            record.readbackMixins = mixins;
            record.readbackAtMillis = System.currentTimeMillis();
        } finally {
            LOCK.unlock();
        }
        READBACK_UNKNOWN_COUNT.incrementAndGet();
        final HotfixPatchReadback.Verdict v =
                HotfixPatchReadback.unknown(HotfixPatchReadback.REASON_NOT_OBSERVABLE);
        // 🔴 按类累计之后，"同类别的方法被这次 patch 撤掉"这件事**不该再发生**（P3 已修）。
        //   所以这里只能报"一起套上的同类型目标数"，真被撤的只认 plan 里失败的那几个
        //   （{@link Outcome#planFailedKeys}）—— **不许把没撤的报成撤了**。
        final List<String> reallyGone = new ArrayList<>(0);
        if (outcome != null) {
            for (int i = 0; i < siblings.size(); i++) {
                if (outcome.planFailedKeys.contains(siblings.get(i))) {
                    reallyGone.add(siblings.get(i));
                }
            }
        }
        final StringBuilder extra = new StringBuilder(224);
        extra.append("desc=").append(record.desc.isEmpty() ? "?" : token(record.desc))
                .append(" why=").append(token(why))
                .append(" sample=none")
                .append(" same_class_targets=").append(siblings.size() + 1)
                .append(" carried=").append(siblings.size() - reallyGone.size())
                .append(" rescinded=").append(reallyGone.size())
                .append(" mechanism=retransform_input_is_baseline")
                .append(" cumulative_plan=").append(outcome == null ? "?" : String.valueOf(outcome.cumulative))
                .append(" cumulative_methods=").append(outcome == null ? "?" : String.valueOf(outcome.planMethods))
                .append(" readback_retransform=skipped")
                .append(" basis=measured:P1P2P3P4");
        if (!reallyGone.isEmpty()) {
            extra.append(" rescinded_targets=").append(token(String.join(",", reallyGone)));
        }
        log(HotfixPatchReadback.line(key, record.origFpOf(0), record.patchedFpOf(0),
                HotfixPatchReadback.Fingerprint.NONE, v, mixins, extra.toString()));
        // 只对"真的没套上"的那些报 reverted（action=none：只报事实、不做动作）
        for (int i = 0; i < reallyGone.size(); i++) {
            READBACK_REVERTED_COUNT.incrementAndGet();
            log("patch_reverted target=" + token(reallyGone.get(i))
                    + " action=none reason=plan_failed");
            // 🆕 2026-11-08：同一条事实也播到聊天栏（PATCH_REVERTED … (<reason> 原值)）。
            //   🔴 这里只发一条消息，一个字节的字节码都不动 —— 「只报不重打」。
            announceReverted(reallyGone.get(i), "plan_failed");
        }
        return HotfixPatchReadback.VERDICT_UNKNOWN;
    }

    /**
     * 这次 patch 撤掉了哪些<b>同类旧目标</b>（调用方持有 {@link #LOCK}）。
     *
     * <p>判据是<b>实测机制</b>而不是猜测：{@code retransform} 的输入是初始基线（P1），
     * 我们这一次 {@code return} 出去的字节里<b>只含这一个方法</b>的改动（P3 实测），
     * 所以同一个 {@code Class} 上先前的 patch 必然一起没了。
     *
     * <p>只认同一个 {@code Class} <b>对象</b>（{@code ==}），不按类名 —— 同名不同加载器
     * 不是同一份字节，不能混为一谈。
     */
    private static List<String> rescindedTargets(String key, Patch record) {
        final List<String> out = new ArrayList<>(2);
        if (record == null || record.classes.length == 0) {
            return out;
        }
        for (Map.Entry<String, Patch> e : PATCHED.entrySet()) {
            if (e.getKey() == null || e.getKey().equals(key) || e.getValue() == null) {
                continue;
            }
            if (sharesClass(record, e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** 两条账本记录改过同一个 {@code Class} 对象吗。 */
    private static boolean sharesClass(Patch a, Patch b) {
        for (int i = 0; i < a.classes.length; i++) {
            final Class<?> x = a.classes[i];
            if (x == null) {
                continue;
            }
            for (int j = 0; j < b.classes.length; j++) {
                if (x == b.classes[j]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * <b>还原之后的取样</b>（唯一安全的取样时刻）：此时"把类退回初始字节"正是意图，
     * 所以这一次 retransform 拿到的样本<b>就是类当前的状态</b>；
     * {@code live == orig} 可以真的判 {@code reverted}，它证明的是
     * <b>"还原真的落地了"</b>（不是"被谁织回去了"，所以这里<b>不</b>打 {@code patch_reverted}）。
     *
     * @param inst    {@link Instrumentation}
     * @param key     {@code 类#方法}
     * @param outcome 原来的 patch 结论（{@link Outcome#record} 里有两份指纹）
     * @param why     为什么取样（{@code selftest_undo}/{@code undo}/{@code undoall}；只进日志）
     * @return 主目标的 verdict；没做 ⇒ {@code "?"}
     */
    private static String readbackNow(Instrumentation inst, String key, Outcome outcome, String why) {
        return readbackNow(inst, key, outcome == null ? null : outcome.record, why);
    }

    /**
     * 还原之后的取样（同 {@link #readbackNow(Instrumentation, String, Outcome, String)}，
     * 但直接收 {@link Patch}：{@code undoLocked} 手里只有账本那一条，没有 Outcome）。
     */
    private static String readbackNow(Instrumentation inst, String key, Patch record, String why) {
        if (record == null) {
            return "?";
        }
        // 🔴 硬要求：渲染线程一律不碰 retransform（哪怕调用点写错了，也只留一行 WARN、不做事）
        if (looksLikeRenderThread()) {
            READBACK_FAIL_COUNT.incrementAndGet();
            warnReadback(key, skippedLine(key, record, why, "render_thread"));
            return "?";
        }
        try {
            Thread.sleep(READBACK_DELAY_MILLIS);   // 与 patch 路径同一个口径：~1 tick 让 JVM 收尾
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            READBACK_FAIL_COUNT.incrementAndGet();
            warnReadback(key, skippedLine(key, record, why, "interrupted"));
            return "?";
        }
        LOCK.lock();
        try {
            return sampleLocked(inst, key, record, why);
        } catch (Throwable t) {
            READBACK_FAIL_COUNT.incrementAndGet();
            warnReadback(key, skippedLine(key, record, why, "exception_" + simpleName(t)));
            return "?";
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 真正取一次样并判定（<b>调用方必须持有 {@link #LOCK}，且只许在"类本来就该是基线"的时刻调</b>）。
     *
     * <p>为什么拿 {@code LOCK}：① 取样本身是一次 {@code retransformClasses}，不能和 patch／undo
     * 同时在飞（会互相覆盖 {@link Pending}）；② 要用的 {@link Patch#classes} 与
     * {@link Patch#origFps} 必须和"这一刻的类"是同一批。
     *
     * <p>同名多份类（{@code same_name_classes>1}）时<b>逐个取样、逐个判</b>，日志里用
     * {@code readback_index=} 分开。
     *
     * @return 主目标（{@code index=0}）的 verdict
     */
    private static String sampleLocked(Instrumentation inst, String key, Patch record, String why) {
        final PatchTransformer tx = transformer;
        // 🆕 2026-11-21（根因③）：**我们自己的写入窗口里绝不取样**。
        //   取样本体就是一次"全 null 的 retransform"（P2 = 退回基线）；如果在我们的写入窗口里跑，
        //   它会把刚写下去的那份撤掉 —— 那正是"补丁莫名其妙丢了"。
        if (HotfixSelfWindow.skip("readback")) {
            READBACK_FAIL_COUNT.incrementAndGet();
            warnReadback(key, skippedLine(key, record, why, "self_window"));
            return "?";
        }
        if (tx != null && tx.pending != null) {
            // 有 patch 正在飞：此刻 retransform 会把"正在写的那份"一起卷进来，宁可不判
            READBACK_FAIL_COUNT.incrementAndGet();
            warnReadback(key, skippedLine(key, record, why, "patch_in_flight"));
            return "?";
        }
        final Class<?>[] classes = record.classes;
        if (inst == null || classes.length == 0) {
            READBACK_FAIL_COUNT.incrementAndGet();
            warnReadback(key, skippedLine(key, record, why,
                    inst == null ? "no_instrumentation" : "no_class"));
            return "?";
        }
        String primary = "?";
        for (int i = 0; i < classes.length; i++) {
            final Class<?> cls = classes[i];
            if (cls == null) {
                continue;
            }
            final HotfixPatchReadback.Probe probe = HotfixPatchReadback.capture(inst, cls);
            final HotfixPatchReadback.Fingerprint origFp = record.origFpOf(i);
            final HotfixPatchReadback.Fingerprint patchedFp = record.patchedFpOf(i);
            HotfixPatchReadback.Verdict v = HotfixPatchReadback.judge(origFp, patchedFp, probe.live);
            if (v.reverted()) {
                // 这次"退回基线"是我们自己干的（还原），所以 reason 要写清楚，别让人以为是别人织回去的
                v = new HotfixPatchReadback.Verdict(HotfixPatchReadback.VERDICT_REVERTED,
                        HotfixPatchReadback.REASON_UNDO_CONFIRMED, v.lenSameAsPatched, v.lenSameAsOrig);
            }
            final int mixins = HotfixPatchReadback.mixinWrappers(cls);
            if (i == 0) {
                primary = v.verdict;
                record.readbackVerdict = v.verdict;
                record.readbackMixins = mixins;
                record.readbackAtMillis = System.currentTimeMillis();
            }
            final StringBuilder extra = new StringBuilder(128);
            extra.append("readback_index=").append(i)
                    .append(" readback_classes=").append(classes.length)
                    .append(" desc=").append(record.desc.isEmpty() ? "?" : token(record.desc))
                    .append(" why=").append(token(why))
                    .append(" sample=reset")
                    .append(' ').append(probe.brief());
            final String text = HotfixPatchReadback.line(key, origFp, patchedFp, probe.live, v, mixins,
                    extra.toString());
            if (probe.ok) {
                if (v.reverted()) {
                    READBACK_RESTORED_COUNT.incrementAndGet();
                } else {
                    countVerdict(v);
                }
                log(text);
            } else {
                READBACK_FAIL_COUNT.incrementAndGet();
                warnReadback(key, text);
            }
        }
        return primary;
    }

    /** 把一次 verdict 记进读数（只记 {@code ok} 的那次读回）。 */
    private static void countVerdict(HotfixPatchReadback.Verdict v) {
        if (v == null) {
            READBACK_UNKNOWN_COUNT.incrementAndGet();
        } else if (v.patchedLive()) {
            READBACK_LIVE_COUNT.incrementAndGet();
        } else if (v.reverted()) {
            READBACK_REVERTED_COUNT.incrementAndGet();
        } else {
            READBACK_UNKNOWN_COUNT.incrementAndGet();
        }
    }

    /**
     * 跳过/失败时的读回行：<b>没有 live 指纹</b> ⇒ verdict 只能是 {@code unknown}
     * （三份哈希照打：{@code orig}/{@code patched} 有值，{@code live=?/?}），
     * 并用 {@code skipped=true skip_reason=…} 说清是哪一种失败 —— 不许猜、不许编一个 verdict。
     */
    private static String skippedLine(String key, Patch record, String why, String detail) {
        final HotfixPatchReadback.Fingerprint origFp = record.origFpOf(0);
        final HotfixPatchReadback.Fingerprint patchedFp = record.patchedFpOf(0);
        final HotfixPatchReadback.Verdict v = HotfixPatchReadback.judge(
                origFp, patchedFp, HotfixPatchReadback.Fingerprint.NONE);
        return HotfixPatchReadback.line(key, origFp, patchedFp, HotfixPatchReadback.Fingerprint.NONE, v,
                record.readbackMixins,
                "skipped=true skip_reason=" + token(detail) + " why=" + token(why));
    }

    /**
     * 读回失败/跳过日志（<b>WARN ＋ 同一个 target 最快 1 s 一行</b>）。
     *
     * <p>为什么限流：失败形态可能是"每次 patch 都读不到"（类不可改/被别的 agent 抢先），
     * 一场战斗几十次 patch 就会刷屏。被压掉的行数记在 {@code suppressed=} 与
     * {@code statusLine()} 的 {@code log_suppressed=} 里 —— 既不刷屏，也不装作没发生。
     */
    private static void warnReadback(String key, String text) {
        final long now = System.currentTimeMillis();
        int suppressed = 0;
        boolean allowed;
        synchronized (READBACK_GATES) {
            ReadbackGate g = READBACK_GATES.get(key);
            if (g == null) {
                if (READBACK_GATES.size() >= MAX_WATCHES * 4) {
                    READBACK_GATES.clear();         // 极端情况下重置；绝不无限涨
                }
                g = new ReadbackGate();
                READBACK_GATES.put(key, g);
            }
            allowed = HotfixPatchReadback.logAllowed(g.lastLogMillis, now);
            if (allowed) {
                suppressed = g.suppressed;
                g.suppressed = 0;
                g.lastLogMillis = now;
            } else {
                g.suppressed++;
                SUPPRESSED_LOGS.incrementAndGet();
            }
        }
        if (!allowed) {
            return;                                 // 日志在锁外打（log4j 可能反过来要我们这把锁）
        }
        warn(suppressed > 0 ? text + " suppressed=" + suppressed : text);
    }

    /** WARN 级出口（失败路径专用；出口没接就静默）。 */
    private static void warn(String text) {
        final Log sink = logSink;
        if (sink == null) {
            return;
        }
        try {
            sink.warn(text);
        } catch (Throwable ignored) {
            // 连 WARN 都打不出去也不能把动作链带崩
        }
    }

    /**
     * 🆕 2026-11-20：<b>ERROR</b> 级出口。
     *
     * <p>⚠️ 2026-11-21（P2）：{@code patch_lost}／{@code patch_giveup} 已经改走 WARN（它们把真错误埋了）；
     * 所以这个出口现在<b>没有任何调用点</b>，保留它只是为了让"ERROR 只有一个口子"这件事不散开。
     */
    private static void error(String text) {
        final Log sink = logSink;
        if (sink == null) {
            return;
        }
        try {
            sink.error(text);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /**
     * 现在这个线程像不像 MC 的渲染线程。
     *
     * <p>🔴 这只是<b>兜底</b>：真正的保证是"读回只在 {@code shanhai-hotfix-autocounter}
     * 这个工作线程（或命令线程的自检）上跑"。名字判据只认 MC 默认的 {@code "Render thread"}，
     * 认不出来时<b>不</b>拦（否则正路也会被误拦）；一旦认出来就一行 WARN、绝不动手。
     */
    private static boolean looksLikeRenderThread() {
        try {
            final String name = Thread.currentThread().getName();
            if (name == null) {
                return false;
            }
            return name.toLowerCase(java.util.Locale.ROOT).contains("render thread");
        } catch (Throwable t) {
            return false;
        }
    }

    // ═══════════════════════════════ 小工具 ═══════════════════════════════

    /** 一行 {@code auto_counter …}（字段顺序＝规格）。 */
    private static String line(String target, String desc, String why, String result, String detail,
                               int origBytes, int newBytes, String extra) {
        final StringBuilder sb = new StringBuilder(192);
        sb.append("auto_counter")
                .append(" target=").append(target == null || target.isEmpty() ? "?" : token(target))
                .append(" desc=").append(desc == null || desc.isEmpty() ? "?" : token(desc))
                .append(" why=").append(why == null || why.isEmpty() ? "?" : token(why))
                .append(" result=").append(result)
                .append(" detail=").append(detail == null || detail.isEmpty() ? "?" : token(detail))
                .append(" orig_bytes=").append(origBytes)
                .append(" new_bytes=").append(newBytes);
        if (extra != null && !extra.isEmpty()) {
            sb.append(' ').append(extra);
        }
        // 🆕 2026-11-20：`strike_source=` 这一项【永远在】（用户要求"可判读的判据行"）。
        //   判读规则：
        //     strike_source=triage    = 来自本体分诊那条真抓栈（能落到第三方帧的那一类）；
        //     strike_source=violation = 来自违规现场抓的栈；
        //     strike_source=absent    = 这次没有抓栈 ⇒ 说明这条违规栈里没有可归因的第三方帧
        //                                （全是白名单帧），所以"改不到东西"是正确行为，不是失败。
        if (extra == null || !extra.contains("strike_source=")) {
            sb.append(" strike_source=absent");
        }
        // 🆕 2026-11-20：`attack=` 这一项【永远在】（用户要求"每次动作的日志必须带 attack=line|method"）。
        //   判读规则：
        //     attack=line   = 普通攻击：只废掉"写受保护状态的那一条指令"，方法其余逻辑照跑；
        //     attack=method = 强力攻击：整方法体换成安全默认返回；
        //     attack=absent = 这一行不是某一次攻击动作（门禁/读回/探针/哨兵自己的行）⇒ 没有档可言。
        if (extra == null || !extra.contains("attack=")) {
            sb.append(" attack=absent");
        }
        return sb.toString();
    }

    private static void refuse(String target, String desc, String why, String detail, String extra) {
        REFUSE_COUNT.incrementAndGet();
        log(line(target, desc, why, "refused", detail, 0, 0, extra));
        // 🆕 2026-11-08：日志照旧，另外【播报】一条给持有者本人（没有玩家上下文时这里什么也不做）
        announceRefused(target, detail);
    }

    /**
     * 与 {@link #refuse} 同款，但<b>同一个 key 最快 1 s 一行</b>（被压掉的行数记在 {@code suppressed=}）。
     *
     * <p>为什么需要它：{@code gate_already_patched} 是"已经改过了"的重复拒绝，
     * 而它会<b>跟着违规走</b> —— 12:41 那种一场战斗 29 条违规的情况下，它会把日志刷成一片，
     * 把真正有用的 {@code patch_probe}/{@code retaliate} 埋在中间（用户口径："不许让日志每 tick 刷屏"）。
     */
    private static void refuseThrottled(String target, String desc, String why, String detail, String extra) {
        final String gate = detail + "|" + target;
        final long now = System.currentTimeMillis();
        int suppressed;
        synchronized (REFUSE_GATES) {
            RefuseGate g = REFUSE_GATES.get(gate);
            if (g == null) {
                if (REFUSE_GATES.size() >= MAX_WATCHES * 4) {
                    REFUSE_GATES.clear();       // 极端情况下重置；绝不无限涨
                }
                g = new RefuseGate();
                REFUSE_GATES.put(gate, g);
            }
            if (!HotfixPatchProbe.logAllowed(g.lastLogMillis, now)) {
                g.suppressed++;
                SUPPRESSED_LOGS.incrementAndGet();
                REFUSE_COUNT.incrementAndGet();
                // 🆕 2026-11-08：日志被限流压掉了，但"又拒了一次"是事实 ⇒ 播报照旧投一次，
                //   让聊天栏那个闸门把它合并成 xN（用户口径：「同一个 target 连续多次
                //   gate_already_patched ⇒ 合并成 xN，不许每 tick 刷屏」）。
                //   ⚠️ 这里只投"事实"，发不发、怎么合并由本体那条现成通道决定。
                announceRefused(target, detail);
                return;
            }
            suppressed = g.suppressed;
            g.suppressed = 0;
            g.lastLogMillis = now;
        }
        refuse(target, desc, why, detail,
                (suppressed > 0 ? "suppressed=" + suppressed + " " : "") + extra);
    }

    // ═══════════════════════════════ 热改播报（2026-11-08，用户点单） ═══════════════════════════════

    /** 这一拍是谁（没有玩家上下文 ⇒ {@code null}）。 */
    private static String chatPlayer() {
        try {
            return CHAT_PLAYER.get();
        } catch (Throwable t) {
            return null;
        }
    }

    // ══════ 🆕 2026-11-21（最终版）：聊天播报的唯一漏斗（逐字相同才折叠；不同内容一条不漏） ══════

    /**
     * 同一条文本<b>重复</b>时补发 {@code xN} 的最小间隔（毫秒）。
     *
     * <p>🔴 它<b>只</b>决定"同一段文字的计数补发节奏"，<b>不</b>用来丢弃不同内容 ——
     * 用户 2026-11-21 口径：「不同的内容不要合并不显示，只有相同的内容要刷屏才做成 *多少 的这种合并显示」
     * 「这个不要加一秒最多一条的限制」。⇒ 不同文本一律立即发，哪怕同一毫秒来 20 条。
     */
    public static final long CHAT_FOLD_REPEAT_MILLIS = 2_000L;

    /** 旧名保留（历史调用点/离线装置可能引用）；语义已经<b>只剩</b>上面那一条。 */
    public static final long WIPE_CHAT_WINDOW_MILLIS = CHAT_FOLD_REPEAT_MILLIS;

    /** {@code GIVEUP} 播报：同类只播一次（类名 ⇒ 已播）。 */
    private static final java.util.Set<String> GIVEUP_CHAT_DONE = new java.util.HashSet<String>();

    // ═════════ 🆕 2026-11-21（最终版）：合并后的处置播报（每类一行）＋ 还原 ＋ 攻击链 ═════════

    /**
     * 合并后的处置播报（<b>每类一行</b>，替代旧的"每方法一条 PATCHED"与 {@code WIPED}）：
     * <pre>
     *   [创始现实修改模块] PATCHED &lt;owner&gt; &lt;类简名&gt; &lt;N&gt; 方法（&lt;m1&gt;, &lt;m2&gt;, …）
     * </pre>
     * 用户口径（2026-11-21 最终版）：
     * <ul>
     *   <li>{@code N} = 该类<b>合并写入</b>的方法数（patch 目标 ∪ 整链目标，按 {@code 类#方法} 去重）；</li>
     *   <li>方法名最多列 <b>4</b> 个，超出写 {@code …+K}；</li>
     *   <li>升档来的那个方法名后加 <b>{@code *}</b>（{@code PATCH_ESCALATED} 的信息并进来，<b>不再单独发</b>）；</li>
     *   <li>被覆盖／放弃那次<b>不播</b>；一波里多个类 ⇒ <b>每类一行</b>，但同一秒窗口内合并投递。</li>
     * </ul>
     *
     * @param owner           目标类所属 mod（拿不到 ⇒ {@code unknown}）
     * @param className       源级类名（播报里只取简名）
     * @param methods         合并后的方法名（顺序即计划顺序）
     * @param escalatedMethod 升档来的那个方法名（可 {@code null}/空 ⇒ 不加 {@code *}）
     */
    static String patchedChatText(String owner, String className, List<String> methods, String escalatedMethod) {
        final String cn = className == null ? "" : className;
        final int dot = cn.lastIndexOf('.');
        final String simple = dot >= 0 && dot < cn.length() - 1 ? cn.substring(dot + 1) : cn;
        final List<String> names = new ArrayList<String>(methods == null ? 0 : methods.size());
        for (int i = 0; methods != null && i < methods.size(); i++) {
            final String m = methods.get(i);
            if (m != null && !m.isEmpty()) {
                names.add(m);
            }
        }
        final int total = names.size();
        final StringBuilder sb = new StringBuilder(64);
        sb.append("PATCHED ")
                .append(owner == null || owner.isEmpty() ? "unknown" : owner)
                .append(' ').append(simple).append(" methods=").append(total);
        final int show = Math.min(4, total);
        for (int i = 0; i < show; i++) {
            sb.append(i == 0 ? " " : ", ");
            final String m = names.get(i);
            sb.append(m);
            if (escalatedMethod != null && !escalatedMethod.isEmpty() && escalatedMethod.equals(m)) {
                sb.append('*');
            }
        }
        if (total > show) {
            sb.append(", …+").append(total - show);
        }
        return sb.toString();
    }

    /**
     * <b>聊天播报的唯一漏斗</b>（四条播报都从这里出去）。
     *
     * <h2>规则（用户 2026-11-21 最终口径）</h2>
     * <ol>
     *   <li><b>内容不同 ⇒ 一条不漏、立即发</b>（哪怕同一毫秒来 20 条不同的）；</li>
     *   <li><b>整行逐字相同 ⇒ 只发第一行</b>，之后不再新增行；计数增长时最多每
     *       {@link #CHAT_FOLD_REPEAT_MILLIS} 补发<b>一次</b>同文本、尾部带 {@code xN}（半角 x，如 {@code x7}）；</li>
     *   <li>没有"每秒一行"之类的丢弃闸门 —— 唯一的 2 秒只用于同一文本的计数补发节奏。</li>
     * </ol>
     *
     * <p>🔴 纯播报：不碰计划／字节／判定。
     */
    private static final Object CHAT_FOLD_LOCK = new Object();

    /** 文本 ⇒ 累计出现次数（只有<b>逐字相同</b>才进这一格）。 */
    private static final Map<String, Integer> CHAT_FOLD_COUNT = new LinkedHashMap<String, Integer>();

    /** 文本 ⇒ 上一次真的发出去的时刻。 */
    private static final Map<String, Long> CHAT_FOLD_LAST_AT = new LinkedHashMap<String, Long>();

    /** 文本 ⇒ 上一次发出去时已经累计到几次（{@code xN} 里的 N 就是当前次数）。 */
    private static final Map<String, Integer> CHAT_FOLD_SENT = new LinkedHashMap<String, Integer>();

    /** 当前收件人（折叠补发要用它；{@code null} = 没有上下文 ⇒ 一条都不发）。 */
    private static String chatFoldPlayer;

    private static boolean chatFoldScheduled;

    /**
     * <b>策略本体</b>（纯函数，离线自证直接断言）：这一条文本现在该不该发。
     *
     * @return 要发的正文（首次 = 原文；重复且已到点 = 原文 + {@code " xN"}）；{@code ""} = 这次不发
     */
    static String chatFoldOffer(String text, long nowMillis) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        synchronized (CHAT_FOLD_LOCK) {
            final Integer old = CHAT_FOLD_COUNT.get(text);
            final int n = old == null ? 1 : old.intValue() + 1;
            if (CHAT_FOLD_COUNT.size() >= 512 && old == null) {
                CHAT_FOLD_COUNT.clear();
                CHAT_FOLD_LAST_AT.clear();
                CHAT_FOLD_SENT.clear();
            }
            CHAT_FOLD_COUNT.put(text, Integer.valueOf(n));
            if (n == 1) {
                CHAT_FOLD_LAST_AT.put(text, Long.valueOf(nowMillis));
                CHAT_FOLD_SENT.put(text, Integer.valueOf(1));
                return text;                        // 首次：立即发原文
            }
            final Long lastAt = CHAT_FOLD_LAST_AT.get(text);
            final Integer sent = CHAT_FOLD_SENT.get(text);
            if (lastAt == null || sent == null) {
                CHAT_FOLD_LAST_AT.put(text, Long.valueOf(nowMillis));
                CHAT_FOLD_SENT.put(text, Integer.valueOf(n));
                return text;
            }
            if (nowMillis - lastAt.longValue() < CHAT_FOLD_REPEAT_MILLIS) {
                return "";                          // 同文本、还没到补发点 ⇒ 只记数
            }
            CHAT_FOLD_LAST_AT.put(text, Long.valueOf(nowMillis));
            CHAT_FOLD_SENT.put(text, Integer.valueOf(n));
            return text + " x" + n;                 // 到点：补发一次，带累计次数
        }
    }

    /** 到点的"同文本计数补发"（一次取走，交给线程去发）。 */
    static List<String> chatFoldDue(long nowMillis) {
        final List<String> out = new ArrayList<String>(4);
        synchronized (CHAT_FOLD_LOCK) {
            for (Map.Entry<String, Integer> e : CHAT_FOLD_COUNT.entrySet()) {
                final String text = e.getKey();
                final int n = e.getValue() == null ? 0 : e.getValue().intValue();
                final Integer sent = CHAT_FOLD_SENT.get(text);
                final Long lastAt = CHAT_FOLD_LAST_AT.get(text);
                if (n <= 1 || sent == null || lastAt == null || n <= sent.intValue()
                        || nowMillis - lastAt.longValue() < CHAT_FOLD_REPEAT_MILLIS) {
                    continue;
                }
                CHAT_FOLD_LAST_AT.put(text, Long.valueOf(nowMillis));
                CHAT_FOLD_SENT.put(text, Integer.valueOf(n));
                out.add(text + " x" + n);
            }
        }
        return out;
    }

    /** 离线自证用：把折叠状态清空。 */
    static void chatFoldReset() {
        synchronized (CHAT_FOLD_LOCK) {
            CHAT_FOLD_COUNT.clear();
            CHAT_FOLD_LAST_AT.clear();
            CHAT_FOLD_SENT.clear();
            chatFoldPlayer = null;
            chatFoldScheduled = false;
        }
    }

    /**
     * 播一条（四条播报的唯一出口）：不同文本立即发；同一文本按 {@link #CHAT_FOLD_REPEAT_MILLIS} 折叠成 {@code xN}。
     *
     * @return {@code true} = 这一次真的投出去了
     */
    private static boolean sendChat(String who, String text) {
        final PatchChat sink = chatSink;
        if (sink == null || who == null || who.isEmpty() || text == null || text.isEmpty()) {
            return false;
        }
        final long now = System.currentTimeMillis();
        boolean scheduled = false;
        synchronized (CHAT_FOLD_LOCK) {
            if (chatFoldPlayer == null) {
                chatFoldPlayer = who;
            }
            if (!chatFoldScheduled) {
                chatFoldScheduled = true;
                scheduled = true;
            }
        }
        if (scheduled) {
            startChatFoldFlusher();
        }
        final String body = chatFoldOffer(text, now);
        if (body.isEmpty()) {
            return false;
        }
        return sayNow(who, body);
    }

    /** 真正投一句（出口炸了也不许影响热改）。 */
    private static boolean sayNow(String who, String body) {
        final PatchChat sink = chatSink;
        if (sink == null) {
            return false;
        }
        try {
            if (sink.say(who, body) > 0) {
                CHAT_ANNOUNCED.incrementAndGet();
                return true;
            }
        } catch (Throwable ignored) {
            // 播报坏了绝不许影响热改本身
        }
        return false;
    }

    /** 起一条短命 daemon：每 {@link #CHAT_FOLD_REPEAT_MILLIS} 检查一次"有没有同文本攒到该补发"。 */
    private static void startChatFoldFlusher() {
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                chatFoldFlushLoop();
            }
        }, "shanhai-hotfix-chat-fold");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable ignored) {
            synchronized (CHAT_FOLD_LOCK) {
                chatFoldScheduled = false;
            }
        }
    }

    private static void chatFoldFlushLoop() {
        while (true) {
            try {
                Thread.sleep(CHAT_FOLD_REPEAT_MILLIS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            final String who;
            synchronized (CHAT_FOLD_LOCK) {
                who = chatFoldPlayer;
            }
            final List<String> due = chatFoldDue(System.currentTimeMillis());
            final PatchChat sink = chatSink;
            if (sink != null && who != null && !who.isEmpty() && !due.isEmpty()) {
                try {
                    CHAT_ANNOUNCED.addAndGet(sink.sayLines(who, due));
                } catch (Throwable ignored) {
                    // 播报坏了绝不许影响热改本身
                }
            }
            final boolean pending;
            synchronized (CHAT_FOLD_LOCK) {
                pending = hasPendingFoldLocked();
                if (!pending) {
                    chatFoldScheduled = false;
                }
            }
            if (!pending) {
                break;
            }
        }
    }

    private static boolean hasPendingFoldLocked() {
        for (Map.Entry<String, Integer> e : CHAT_FOLD_COUNT.entrySet()) {
            final int n = e.getValue() == null ? 0 : e.getValue().intValue();
            final Integer sent = CHAT_FOLD_SENT.get(e.getKey());
            if (n > 1 && (sent == null || n > sent.intValue())) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>一次写成功之后</b>的处置播报（每类一行）。
     *
     * <p>触发点：{@link #writePlanLocked} 里那一趟真的 {@code ok} 之后（patch 与整链抹除共用，
     * 因为两条路径已经合并成同一份计划、同一次写）。
     *
     * @param className 源级类名
     * @param w         这一趟的读数（{@code merge.candidate} 就是"合并后、去重后"的方法全集）
     * @param escalatedKey 升档来的那条 {@code 类#方法}（没升档 ⇒ 空串）
     */
    private static void announceClassPatched(String className, PlanWrite w, String escalatedKey) {
        if (w == null || !w.ok || w.merge == null || w.merge.candidate.isEmpty()) {
            return;                        // 被覆盖／放弃的那次不播；ok=0 不播
        }
        final PatchChat sink = chatSink;
        final String who = chatPlayer();
        if (sink == null || who == null || who.isEmpty()) {
            return;
        }
        final String cn = className == null ? "" : className;
        final List<String> names = new ArrayList<String>(w.merge.candidate.size());
        for (int i = 0; i < w.merge.candidate.size(); i++) {
            final HotfixPatchPlan.MethodPatch p = w.merge.candidate.get(i);
            if (p == null || p.methodName == null || p.methodName.isEmpty()) {
                continue;
            }
            names.add(p.methodName);
        }
        if (names.isEmpty()) {
            return;
        }
        // 🔴 不再做"同类内容没变就不播"的本地去重 —— 那会把"逐字相同的重复"直接丢掉，
        //   拿不到 xN 计数。统一交给漏斗（{@link #sendChat}：不同内容立即发；逐字相同才折叠成 xN）。
        final String escalated = methodNameOf(escalatedKey);
        sendChat(who, patchedChatText(sink.ownerOf(cn), cn, names, escalated));
    }

    // ---- ② 关键类还原（第三条写入路径）一条 ----

    /**
     * <pre>RESTORED-KEY &lt;SimpleClassName&gt; removed=&lt;N&gt; kept=&lt;M&gt; (&lt;handler names, max 2&gt;)</pre>
     *
     * <p>{@code N=0} 也要播 —— 文案换成 {@code removed=0 (not a callback shape)}：
     * "这一类上没有对手的回调"本身就是用户想知道的结论（不是失败）。
     * 类名用<b>简名</b>（与另三条一致）；全名只留在日志里。
     */
    static String restoredKeyChatText(String className, int stripped, int oursKept, String handlers) {
        final String cn = simpleNameOf(className);
        final int n = Math.max(0, stripped);
        final int m = Math.max(0, oursKept);
        if (n == 0) {
            return "RESTORED-KEY " + cn + " removed=0 (not a callback shape)";
        }
        return "RESTORED-KEY " + cn + " removed=" + n + " kept=" + m + " (" + handlerNames(handlers, 2) + ")";
    }

    /** 源级类名 ⇒ 简名（不带包；空 ⇒ 原样）。 */
    static String simpleNameOf(String className) {
        final String cn = className == null ? "" : className;
        final int dot = cn.lastIndexOf('.');
        return dot >= 0 && dot < cn.length() - 1 ? cn.substring(dot + 1) : cn;
    }

    /** {@code [a,b,c]} ⇒ {@code a, b}（最多 {@code max} 个；空 ⇒ {@code -}）。 */
    static String handlerNames(String handlers, int max) {
        final String raw = handlers == null ? "" : handlers.trim();
        final String inner = raw.startsWith("[") && raw.endsWith("]") && raw.length() >= 2
                ? raw.substring(1, raw.length() - 1) : raw;
        final StringBuilder sb = new StringBuilder(48);
        int shown = 0;
        int from = 0;
        while (from <= inner.length() && shown < Math.max(1, max)) {
            int to = inner.indexOf(',', from);
            if (to < 0) {
                to = inner.length();
            }
            final String one = inner.substring(from, to).trim();
            if (!one.isEmpty()) {
                if (shown > 0) {
                    sb.append(", ");
                }
                sb.append(one);
                shown++;
            }
            if (to >= inner.length()) {
                break;
            }
            from = to + 1;
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static void announceRestoredKey(String className, int stripped, int oursKept, String handlers) {
        final String who = chatPlayer();
        if (chatSink == null || who == null || who.isEmpty()) {
            return;
        }
        // 🔴 不再有"1 秒 1 条"的丢弃闸门：不同内容立即发；逐字相同由漏斗折叠成 xN。
        sendChat(who, restoredKeyChatText(className, stripped, oursKept, handlers));
    }

    // ---- ③ 攻击链检测（链采到之后、写之前）一条 ----

    /**
     * <pre>CHAIN &lt;owner&gt; frames=&lt;F&gt; &lt;Cls#m&gt; → &lt;Cls#m&gt; → … → &lt;Cls#m&gt;[write=&lt;kind&gt;] pick=&lt;Cls#m&gt;</pre>
     *
     * <p>🔴 2026-11-21（用户最终口径）：<b>整条外部链全部画出来，中间不省略</b> ——
     * 多少帧就画多少段（{@code frames=20} 就 20 段），也没有 {@code …} 与 {@code (+K)}。
     * 类名用<b>简名</b>；写点后面紧跟 {@code [write=hurt|health|die|remove]}。
     */
    static String chainChatText(String owner, List<String> segments, String writeKind, String picked) {
        final List<String> segs = new ArrayList<String>(segments == null ? 0 : segments.size());
        for (int i = 0; segments != null && i < segments.size(); i++) {
            final String s = segments.get(i);
            if (s != null && !s.isEmpty()) {
                segs.add(s);
            }
        }
        final StringBuilder sb = new StringBuilder(160);
        sb.append("CHAIN ")
                .append(owner == null || owner.isEmpty() ? "unknown" : owner)
                .append(" frames=").append(segs.size()).append(' ');
        for (int i = 0; i < segs.size(); i++) {
            sb.append(segs.get(i));
            if (i < segs.size() - 1) {
                sb.append(" → ");
            }
        }
        sb.append("[write=").append(writeKind == null || writeKind.isEmpty() ? "unknown" : writeKind).append(']');
        sb.append(" pick=").append(picked == null || picked.isEmpty() ? "?" : picked);
        return sb.toString();
    }

    /**
     * 播一条 {@code CHAIN ...}（<b>立即发</b>；不同内容一条不漏，逐字相同才由漏斗折叠成 xN）。
     *
     * <p>触发点：{@code HotfixAutoCounter#handle} 里链采到、选靶完成之后，
     * <b>整链抹除真正写字节之前</b>。
     *
     * @param segments 整条外部链（顶端 → 写点，元素是 {@code Cls#method}）
     * @param kind     写点那一拍的原语（{@code hurt}／{@code health}／{@code die}／{@code remove}）
     * @param picked   选靶结果（{@code Cls#method}）
     */
    static void chatChain(String owner, List<String> segments, String kind, String picked) {
        final String who = chatPlayer();
        if (chatSink == null || who == null || who.isEmpty() || segments == null || segments.isEmpty()) {
            return;
        }
        sendChat(who, chainChatText(owner, segments, kind, picked));
    }
    /** {@code 类#方法} ⇒ {@code 类简名#方法}（播报用；拆不出 ⇒ 原样）。 */
    static String briefTarget(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        final int hash = key.indexOf('#');
        final String cn = hash < 0 ? key : key.substring(0, hash);
        final String method = hash < 0 || hash == key.length() - 1 ? "" : key.substring(hash + 1);
        final int dot = cn.lastIndexOf('.');
        final String simple = dot >= 0 && dot < cn.length() - 1 ? cn.substring(dot + 1) : cn;
        return method.isEmpty() ? simple : simple + "#" + method;
    }

    /**
     * 🆕 {@code patch_giveup reason=lost3} 时的播报：{@code GIVEUP <Cls> reason=patch_overridden covered_by=chain_wipe}。
     *
     * <p>约束：同类<b>只播一次</b>（不是时间闸门 —— 逐字相同的重复由漏斗折叠成 {@code xN}）。
     *
     * <p>🔴 它只播报，<b>不改任何判定／写入</b>。
     */
    static void chatGiveup(String className) {
        final String cn = className == null ? "" : className;
        if (cn.isEmpty()) {
            return;
        }
        final String who = chatPlayer();
        if (chatSink == null || who == null || who.isEmpty()) {
            return;
        }
        synchronized (CHAT_FOLD_LOCK) {
            if (GIVEUP_CHAT_DONE.contains(cn)) {
                return;                    // 同类只播一次
            }
            GIVEUP_CHAT_DONE.add(cn);
            if (GIVEUP_CHAT_DONE.size() > 256) {
                GIVEUP_CHAT_DONE.clear();  // 防涨；清掉后最多重播一次，不会刷屏
            }
        }
        sendChat(who, giveupChatText(cn));
    }

    /** {@code GIVEUP <Cls> reason=patch_overridden covered_by=chain_wipe} —— 纯函数（离线逐字断言）。 */
    static String giveupChatText(String className) {
        return "GIVEUP " + simpleNameOf(className)
                + " reason=patch_overridden covered_by=chain_wipe";
    }

    /**
     * ① <b>成功</b>：{@code result=patched} ⇒ 每个 applied 方法一条 {@code PATCHED}（<b>只发一次</b>）。
     *
     * <p>去重在 {@link #CHAT_PATCHED_KEYS}：按类累计之后，同类的老方法会在每个新 patch 的
     * {@code plan} 里再套一遍（{@code perMethod} 又是 {@code ok}）—— 没有这张表就会"每次重算都发"。
     *
     * @param outcome 这一次的结论（{@link Outcome#appliedMethodKeys} 里是真的套上去的那些方法）
     * @param why     {@code why=} 原值（进括号）
     */
    private static void announcePatched(Outcome outcome, String why) {
        if (outcome == null) {
            return;
        }
        // ① 🔴 记账（**与有没有聊天出口无关**）：曾经 patch 过、且没显式 undo ⇒ 永久留在 wanted 里。
        //   这一步是本轮 P3 复发的正解：plan 从 wanted 派生，而不是从"现在还在册的 PATCHED"派生。
        final List<String> fresh = new ArrayList<>(outcome.appliedWants.size());
        synchronized (CHAT_PATCHED_KEYS) {
            for (int i = 0; i < outcome.appliedWants.size(); i++) {
                final Want w = outcome.appliedWants.get(i);
                if (w == null || w.key.isEmpty()) {
                    continue;
                }
                if (CHAT_PATCHED_KEYS.putIfAbsent(w.key, w) == null) {
                    fresh.add(w.key);
                }
            }
        }
        // ② 播报
        //   🔴 2026-11-21（最终版，用户拍板）：<b>这里不再发聊天</b> —— 粒度改成"每类一行"，
        //      由 {@link #announceClassPatched} 在 {@code writePlanLocked} 写成功之后统一发
        //      （patch 与整链抹除合并成同一份计划 ⇒ 只能由那条路播一次）。
        //      本方法仍然负责①那张账（{@code CHAT_PATCHED_KEYS} 是 keepalive 的唯一输入），一步都不能少。
    }

    /**
     * ② <b>被拒绝</b>：{@code result=refused} ⇒ {@code PATCH_REFUSED … (<detail>)}。
     *
     * <p>同一个 target 反复被同一个门槛拒绝 ⇒ 同一条正文 ⇒ 由聊天栏那个闸门合并成 {@code xN}
     * （不是在这里去重：拒绝是真事，只是不许刷屏）。
     *
     * @param target 目标（{@code 类#方法}；还没解析出目标时是 {@code "?"} ⇒ <b>不播</b>）
     * @param detail {@code detail=} 原值（{@code gate_already_patched} 等）
     */
    private static void announceRefused(String target, String detail) {
        final PatchChat sink = chatSink;
        if (sink == null) {
            return;
        }
        final String who = chatPlayer();
        if (who == null || who.isEmpty()) {
            return;   // 没有玩家上下文 ⇒ 一条都不发
        }
        final String[] split = HotfixPatchChatText.splitTargetKey(target);
        if (split == null) {
            return;   // 目标还没解析出来（target="?"）⇒ 这条播了也没意义
        }
        try {
            if (sink.announce(who, List.of(
                    new PatchNote(HotfixPatchChatText.WORD_REFUSED, split[0], split[1], detail))) > 0) {
                CHAT_ANNOUNCED.incrementAndGet();
            }
        } catch (Throwable ignored) {
            // 同上：播报失败不许影响拒绝本身
        }
    }

    /**
     * ③ <b>被回退／没落地</b>：{@code patch_reverted} ⇒ {@code PATCH_REVERTED … (<reason>)}。
     *
     * <p>🔴 <b>只报不重打</b>：这里只发一条聊天栏消息，一个字节的字节码都不动
     * （重打是探针那条线的事，见 {@code retaliate}）。
     *
     * @param target 被撤掉的那个目标（{@code 类#方法}）
     * @param reason {@code reason=} 原值（{@code observed_only}／{@code plan_failed} 等）
     */
    private static void announceReverted(String target, String reason) {
        final PatchChat sink = chatSink;
        if (sink == null) {
            return;
        }
        final String who = chatPlayer();
        if (who == null || who.isEmpty()) {
            return;   // 没有玩家上下文 ⇒ 一条都不发
        }
        final String[] split = HotfixPatchChatText.splitTargetKey(target);
        if (split == null) {
            return;
        }
        try {
            if (sink.announce(who, List.of(
                    new PatchNote(HotfixPatchChatText.WORD_REVERTED, split[0], split[1], reason))) > 0) {
                CHAT_ANNOUNCED.incrementAndGet();
            }
        } catch (Throwable ignored) {
            // 同上
        }
    }

    /**
     * ④ 🆕 <b>升级到强力档</b>：{@code attack_escalate} ⇒ {@code PATCH_ESCALATED … -> RETURN (<why>)}。
     *
     * <p>为什么升级要单独播一次：普通攻击的副作用只有"那一行不写"（玩家几乎无感），
     * 而强力档会把<b>整个方法</b>停摆 —— 对手实体可能因此不动了、不掉血了、连带的功能一起死。
     * 这种副作用必须<b>当场</b>让持有者本人看见，而不是等他去翻日志。
     *
     * @param target {@code 类#方法}（拆不出两段 ⇒ 不播）
     * @param why    {@code why=} 原值（进括号，与 {@code PATCHED} 那一行同口径）
     */
    private static void announceEscalated(String target, String why) {
        // 🔴 2026-11-21（最终版，用户拍板）：{@code PATCH_ESCALATED} 不再单独发 ——
        //   升档的信息并进"每类一行"的 {@code PATCHED}（方法名后加 {@code *}），
        //   由 {@link #announceClassPatched} 在 {@code writePlanLocked} 里播。
        //   这里保留方法（升级判定本身与日志一字不动），只是不再往聊天栏说话。
    }

    /** 抓栈那一段证据（进日志尾部）。 */
    private static String strikeInfo(HotfixTriageTap.Strike strike) {
        if (strike == null) {
            return "strike=absent";
        }
        return "strike_source=" + strike.sourceToken()
                + " strike_index=" + strike.index
                + " strike_age_ms=" + strike.ageMillis()
                + " strike_ttl_ms=" + strike.ttlMillis()
                + " frames=" + strike.frames.size();
    }

    private static void log(String line) {
        final Log sink = logSink;
        if (sink == null) {
            return;
        }
        try {
            sink.line(line);
        } catch (Throwable ignored) {
            // 日志失败绝不许把动作链带崩
        }
    }

    /**
     * 🆕 给 {@link HotfixShield} 用的出口（包内可见）。
     *
     * <p>为什么单开一个方法而不是把 {@link #log} 改宽：{@code log} 是整条链的唯一落点，
     * 多一个包内入口既保持"只有一处打日志"，又不必动它的可见性。
     *
     * <p>⚠️ 它会在<b>目标方法的方法体里</b>被间接调到（对手 20/s 的循环），所以：
     * ① 只加一个前缀字面量、不做任何格式化；② 异常全吞。
     */
    static void logShield(String line) {
        log(line);
    }

    /** 日志字段里不许出现空格／竖线（否则 grep 会把一行切错）。 */
    static String token(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "?";
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == ' ' || c == '\t' || c == '|' || c == HotfixTriageVerdict.FULLWIDTH_BAR || c == '\n' || c == '\r') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 日志字段里"按原样"打一个值（供 {@code raw=}/{@code normalized=} 这两项：外面已经套了引号）。
     *
     * <p>与 {@link #token(String)} 的区别：<b>不动中文、不动空格</b>，只把 CR/LF 换成空格
     * （否则一行会被切成两行，grep 会错）。也<b>不做</b>任何 URL-decode：解码过的半截路径会误导排查
     * （{@code %E6%97%A5%E5%B8%B8} 解成中文是"好看"，但一旦解错就少一截，原样才可复核）。
     */
    static String verbatim(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "?";
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            sb.append(c == '\n' || c == '\r' ? ' ' : c);
        }
        return sb.toString();
    }

    private static String simpleName(Throwable t) {
        return t == null ? "unknown" : t.getClass().getSimpleName();
    }

    private static String internalName(String className) {
        return className == null ? "" : className.replace('.', '/');
    }

    /** 在<b>已加载</b>的类里按名字找第一个（不主动加载：栈帧上出现过的类必然已加载）。 */
    private static Class<?> findLoaded(Instrumentation inst, String className) {
        final List<Class<?>> all = findLoadedAll(inst, className);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 在<b>已加载</b>的类里按名字找<b>全部</b>同名类（正常是 1 个；同名不同加载器时可能不止）。
     *
     * <p>为什么要"全部"：{@code getAllLoadedClasses()} 里同名类出现多份时，
     * "我们改的那个"和"栈上正在跑的那个"没有任何保证是同一个 —— 这正是
     * 12:41「账本说 patched、行为没变」的候选机制之一（{@code same_class_identity=false}）。
     */
    private static List<Class<?>> findLoadedAll(Instrumentation inst, String className) {
        final List<Class<?>> out = new ArrayList<>(1);
        if (inst == null || className == null || className.isEmpty()) {
            return out;
        }
        try {
            final Class<?>[] all = inst.getAllLoadedClasses();
            if (all == null) {
                return out;
            }
            for (int i = 0; i < all.length; i++) {
                final Class<?> c = all[i];
                if (c != null && className.equals(c.getName())) {
                    out.add(c);
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就等于"这个类不在"
        }
        return out;
    }

    /**
     * {@code getProtectionDomain().getCodeSource().getLocation()} 的<b>原样字符串</b>；拿不到 ⇒ {@code null}。
     *
     * <p>🔴 2026-11-08：这里<b>不再</b>做 {@code Paths.get(url.toURI())}。Forge／ModLauncher 给 mod 类的
     * code source 是 {@code union:/…/mods/x.jar%23326!/}，而 union 那个 {@code FileSystemProvider}
     * 会把这种 URI 解析成 <b>union 文件系统的根</b>（{@code "/"}）—— jar 路径整段丢掉，
     * 于是下游判据只会看到 {@code "/"}，必然判 {@code outside_mods_dir}。见
     * {@link HotfixPatchWhitelist} 的类注释（有真实字符串）。
     *
     * <p>{@code public}：离线自检要拿它来证明"假 mod 类的 code source 真的是 {@code …\mods\x.jar}"。
     */
    public static String codeSourceOf(Class<?> cls) {
        if (cls == null) {
            return null;
        }
        try {
            final ProtectionDomain pd = cls.getProtectionDomain();
            final CodeSource cs = pd == null ? null : pd.getCodeSource();
            final URL url = cs == null ? null : cs.getLocation();
            if (url == null) {
                return null;
            }
            // 原样照抄（file:/… / jar:file:/… / union:/… 都行）：规范化交给 HotfixPatchWhitelist
            return url.toString();
        } catch (Throwable t) {
            // 拿不到就是拿不到；判据那边会按 no_code_source 拒
            return null;
        }
    }

    /**
     * 类加载器的类名（{@code bootstrap} 或拿不到 ⇒ 见下）。
     *
     * <p>只用作白名单的<b>兜底</b>证据（{@code ok_forge_loader}）：路径形态没见过时，能认出
     * "这是 Forge 的 mod 类加载器"也算一条来源证据。<b>大小写敏感</b>地匹配
     * {@code Launch}/{@code SecureJarHandler}/{@code ModLauncher}（理由见
     * {@link HotfixPatchWhitelist#hitForgeLoader}）。
     */
    static String loaderNameOf(Class<?> cls) {
        try {
            if (cls == null) {
                return null;
            }
            final ClassLoader loader = cls.getClassLoader();
            return loader == null ? "bootstrap" : loader.getClass().getName();
        } catch (Throwable t) {
            return null;
        }
    }

    // ═══════════════════════════════ 数据结构 ═══════════════════════════════

    /** 队列里的一件事。 */
    private static final class Job {

        final HotfixTriageVerdict verdict;
        final HotfixTriageTap.Strike strike;

        Job(HotfixTriageVerdict verdict, HotfixTriageTap.Strike strike) {
            this.verdict = verdict;
            this.strike = strike;
        }
    }

    /** 账本里的一条：改过谁、原字节是什么。 */
    private static final class Patch {

        /** 🔴 真正被 {@code retransform} 的那些 {@code Class}（通常只有 1 个；同名多份时更多）。 */
        final Class<?>[] classes;

        /** 与 {@link #classes} 等长的原字节表（拿不到的位置是 {@code null}）。 */
        final byte[][] originals;

        /**
         * 与 {@link #classes} 等长的两份指纹（读回取证的三份里用得上两份）。
         *
         * <p>{@code origFps[i]} = 第 i 个类 patch 前 transformer 收到的字节；
         * {@code patchedFps[i]} = 我们写下去的那份。拿不到 ⇒ {@link HotfixPatchReadback.Fingerprint#NONE}
         * （判 {@code unknown}，绝不猜）。
         */
        final HotfixPatchReadback.Fingerprint[] origFps;
        final HotfixPatchReadback.Fingerprint[] patchedFps;

        final String internalName;
        final String className;
        final String methodName;
        final String desc;

        /** 当初选目标用的行号（反制时复用；{@code -1} = 没用它）。 */
        final int lineHint;

        final String match;
        final String defaultKind;

        final int origBytes;
        final int newBytes;
        final long atMillis;

        // ---- 🆕 2026-11-20：这一刀用的是哪一档（{@code line}=废那一行／{@code method}=整方法 RETURN）
        /** {@link HotfixAttackTier#LINE} 或 {@link HotfixAttackTier#METHOD}（<b>只升不降</b>）。 */
        final String tier;

        /**
         * 这个目标被打到第几次（{@code gate_already_patched} 时 +1）。
         *
         * <p>升到 {@link HotfixAttackTier#ESCALATE_HITS} 次 ⇒ 说明"废了那一行之后它还在作恶" ⇒ 升级。
         * 可变：它是账本里唯一一个"越用越大"的数，写在工作线程上（受 {@link #LOCK} 保护）。
         */
        volatile int hits;

        /** {@code attack=line} 时真的换掉了几条指令（{@code method} 档恒为 0）。 */
        final int sites;

        /** 站点清单（{@code setHealth:m_21153_} 这种；{@code method} 档为空串）。 */
        final String siteList;

        // ---- 🆕 读回取证的结果（由 sampleLocked / readbackAfterPatch 写；status/日志判读用）
        /**
         * 最近一次取证/取样的 verdict；{@code "?"} = 还没读过。
         *
         * <p>口径：patch 之后固定是 {@code unknown}（{@code not_observable}，当前字节采不到）；
         * 还原之后的取样才是 {@code reverted}（{@code undo_confirmed}）。
         */
        volatile String readbackVerdict = "?";

        /** 最近一次读回时类里 {@code handler$}/{@code localvar$} 方法个数（{@code -1} = 数不出来）。 */
        volatile int readbackMixins = HotfixPatchReadback.COUNT_UNKNOWN;

        /** 最近一次读回的时刻。 */
        volatile long readbackAtMillis;

        Patch(Class<?>[] classes, byte[][] originals,
              HotfixPatchReadback.Fingerprint[] origFps, HotfixPatchReadback.Fingerprint[] patchedFps,
              String internalName, String className, String methodName,
              String desc, int lineHint, int origBytes, int newBytes, String match, String defaultKind,
              long atMillis, String tier, int sites, String siteList) {
            this.classes = classes == null ? new Class<?>[0] : classes;
            this.originals = originals == null ? new byte[0][] : originals;
            this.origFps = origFps == null ? new HotfixPatchReadback.Fingerprint[0] : origFps;
            this.patchedFps = patchedFps == null ? new HotfixPatchReadback.Fingerprint[0] : patchedFps;
            this.internalName = internalName;
            this.className = className;
            this.methodName = methodName;
            this.desc = desc == null ? "" : desc;
            this.lineHint = lineHint;
            this.origBytes = origBytes;
            this.newBytes = newBytes;
            this.match = match == null ? "none" : match;
            this.defaultKind = defaultKind == null ? "" : defaultKind;
            this.atMillis = atMillis;
            this.tier = HotfixAttackTier.orDefault(tier);
            this.sites = sites;
            this.siteList = siteList == null ? "" : siteList;
        }

        /** 第 {@code index} 个类的原字节；没有 ⇒ {@code null}。 */
        byte[] originalOf(int index) {
            if (index < 0 || index >= originals.length) {
                return null;
            }
            return originals[index];
        }

        /** 第 {@code index} 个类 patch 前的指纹；没有 ⇒ {@link HotfixPatchReadback.Fingerprint#NONE}。 */
        HotfixPatchReadback.Fingerprint origFpOf(int index) {
            if (index < 0 || index >= origFps.length || origFps[index] == null) {
                return HotfixPatchReadback.Fingerprint.NONE;
            }
            return origFps[index];
        }

        /** 第 {@code index} 个类写下去的指纹；没有 ⇒ {@link HotfixPatchReadback.Fingerprint#NONE}。 */
        HotfixPatchReadback.Fingerprint patchedFpOf(int index) {
            if (index < 0 || index >= patchedFps.length || patchedFps[index] == null) {
                return HotfixPatchReadback.Fingerprint.NONE;
            }
            return patchedFps[index];
        }
    }

    /** 一次改动尝试的结论（给 {@link #handle} 与 {@link #selftest} 共用）。 */
    private static final class Outcome {

        final String key;
        boolean ok;
        String detail = "not_started";
        String desc = "";
        String match = "none";
        String defaultKind = "";
        int origBytes;
        int newBytes;
        long elapsedMillis;

        /** 🆕 这一次动作选的档（{@link HotfixAttackTier#LINE}／{@link HotfixAttackTier#METHOD}）。 */
        String tier = HotfixAttackTier.LINE;

        /** 🆕 attack=line 时真的换掉了几条指令（method 档恒为 0）。 */
        int sites;

        /** 🆕 站点清单（{@code setHealth:m_21153_} 这种；method 档为空串）。 */
        String siteList = "";

        // ---- 🆕 Instrumentation 侧的事实（patch_apply 诊断行）
        /** 真正 {@code retransformClasses} 成功了几次。 */
        int classesRetransformed;
        /** 已加载类里有几个同名 {@code Class}。 */
        int sameNameClasses;
        /** "栈上那个必然就是我们改的这个" ⇒ {@code true}（{@link #sameNameClasses} 只有 1 个）。 */
        boolean identitySame = true;
        /** 我们改的那个类的加载器名。 */
        String loaderName = "?";
        /** 各同名类的加载器名（逗号分隔；判"同名不同加载器"用）。 */
        String loaders = "";

        // ---- 🆕 按类累计（P3 修复）：这一趟 retransform 到底套了几个方法
        /** 源级类名（{@code patch_apply class=}）。 */
        String className = "?";
        /** 源级方法名（{@code patch_apply this=}）。 */
        String methodName = "?";
        /** 这一次 {@code retransform} 一起套上了几个方法（含同类别的目标）。 */
        int planMethods;
        /** 之前这个类上已经登记过几个补丁（{@code >0} ⇒ 这一次是"累计"）。 */
        int cumulativeBefore;
        /** 真的套上去几个。 */
        int appliedMethods;
        /** 这一次一起套、但没套上的方法键（<b>它们才真的被撤了</b>）。 */
        final java.util.List<String> planFailedKeys = new ArrayList<>(0);

        /** 🆕 wanted 集合现在有几条（{@code patch_apply … wanted=<n>}）。 */
        int wantedCount;

        /** 🆕 这一次真的套上去的那些方法（能重建的完整信息；往 wanted 集合落盘用）。 */
        final java.util.List<Want> appliedWants = new ArrayList<>(1);

        /**
         * 🆕 2026-11-08：这一次<b>真的套上去了</b>的方法键（{@code 类#方法}，按 plan 的顺序）。
         *
         * <p>为什么要有它：{@link #appliedMethods} 只是个计数，而播报口径是
         * 「一次 patch 改了同类多个方法 ⇒ <b>每个方法一行</b>」—— 没有方法名就拼不出那一行。
         */
        final List<String> appliedMethodKeys = new ArrayList<>(2);
        /** 是不是"按类累计"的那一次（{@link #cumulativeBefore} {@code >0}）。 */
        boolean cumulative;

        /**
         * 🆕 成功时账本里那一条（{@link #PATCHED} 的同一个对象）。
         *
         * <p>读回取证要它：光有 {@code key} 不够 —— 判"账本里还是不是我改的那一条"必须比
         * <b>对象身份</b>（{@code PATCHED.get(key) == record}），否则"undo 之后又重打"会被
         * 当成同一次 patch。
         */
        Patch record;

        Outcome(String key) {
            this.key = key;
        }

        /** 这次是"被门槛挡住"（而不是"动手了但失败"）——日志要把两者分开。 */
        boolean gateRefused() {
            return !ok && detail != null && detail.startsWith("gate_");
        }
    }

    /** 拒绝类日志的限流闸门（{@code lastLogMillis} ＋ 被压掉的条数）。 */
    private static final class RefuseGate {

        long lastLogMillis;
        int suppressed;
    }

    /** 🆕 读回失败日志的限流闸门（与 {@link RefuseGate} 同款，但按 target 记）。 */
    private static final class ReadbackGate {

        long lastLogMillis;
        int suppressed;
    }

    /**
     * 一次 {@code retransformClasses} 的上下文：告诉 transformer「只改这个类、这个方法」。
     *
     * <p>{@link #restoreBytes} 非 {@code null} 时进"还原模式"：直接把这份字节原样送回去（undo 的第二道保险）。
     *
     * <h2>🆕 {@link #plan}：按类累计（P3 修复）</h2>
     * 实测（读回线 {@code temp\hotfix\读回取证.md}）：
     * <pre>
     *   P1 retransform 交给 transformer 的是【初始基线】，不是上次定义后的版本
     *   P3 🔴 同一个类的【第二个 patch 会静默撤掉第一个】
     *      （patch allowHurt→false 后再 patch 同类 strength ⇒ allowHurt 变回 true、strength=0）
     *   P4 一次 retransform 带同类多个方法的补丁是可行的（两个默认值同时成立）
     * </pre>
     * 12:41 那次"账本 patched 但行为没变"正是 P3：{@code doHurt} 与 {@code playHitFx}
     * <b>是同一个类</b>（{@code examplemod.util.ExampleHelper}），后一次 patch 把前一次撤掉了。
     * ⇒ 现在同一个类再 patch 时，{@code plan} 里装的是<b>该类所有已登记方法</b>，
     * 一次 {@code retransform} 全套上（P4 已证可行）。
     */
    private static final class Pending {

        final String internalName;
        final String methodName;
        final String methodDesc;
        final int lineHint;

        /** 这一趟的主目标键（{@code 类#方法}）；还原用的 keep-pending 里可以是空串。 */
        final String mainKey;

        /** 🆕 这一趟要一起套上的全部方法（按类累计）。空 ⇒ 走老的"只看上面三个字段"的路径。 */
        final List<PlanEntry> plan;

        volatile byte[] restoreBytes;

        // ---- 🆕 2026-11-21（根因②）：这一趟从哪份字节出发
        /**
         * 基线（{@link HotfixBaseline} 里首次见到这个类时抓的那一份）。
         *
         * <p>🔴 非 {@code null} ⇒ 这一趟<b>从原始字节重建</b>（绝不在"上一轮结果"上叠加）；
         * {@code null} ⇒ 基线没抓到，只能拿 JVM 交下来的 {@code classfileBuffer}
         * （那种情况会在 {@code patch_rebuild base=live} 里如实写出来）。
         */
        volatile boolean fromSnapshot;

        /** {@link #fromSnapshot} 那份字节的 sha（{@code patch_rebuild sha_before=}）。 */
        volatile String baseSha = "?";

        /** JVM 交下来的那份的 sha（{@code patch_rebuild} 的对照项）。 */
        volatile String origSha = "?";

        /** 我们 {@code return} 下去那份的 sha（{@code patch_write} 的身份章）。 */
        volatile String newSha = "";

        // ---- 🆕 2026-11-21（根因①）：**整张计划要么全装、要么全不装**
        /** 有一条方法没装上 ⇒ {@code true}（transformer 直接返回 {@code null}，一个都不交）。 */
        volatile boolean aborted;

        /** 没装上的是哪一条（{@code 类#方法}）。 */
        volatile String abortFailedKey = "";

        /** 没装上的原因（来自 ASM 那一层）。 */
        volatile String abortReason = "?";

        // ---- 🆕 2026-11-21（内联摘除）：这一趟写字节之前顺手摘了几个外部 handler
        /** 摘掉的外部 handler 个数（{@code 0} = 没有 ⇒ 不打 {@code keyclass_restore_inline} 行）。 */
        volatile int inlineStripped;

        /** 被摘的 handler 名（{@code [a,b]}；没有 ⇒ {@code []}）。 */
        volatile String inlineHandlers = "[]";

        /** 保留的我们自己的 handler 个数（{@code $shanhai$}）。 */
        volatile int inlineOurs;

        volatile byte[] bytes;
        volatile byte[] original;
        volatile int origBytes;
        volatile int newBytes;
        volatile String reason = "not_called";
        volatile String desc = "";
        volatile String match = "none";
        volatile String defaultKind = "";

        /** 🆕 每个方法的结论（方法键 ⇒ {@code "ok:" + 默认值形态} / {@code "failed:" + 原因}）。 */
        final Map<String, String> perMethod = new LinkedHashMap<>();

        /** 🆕 真的套上去了几个。 */
        volatile int appliedMethods;

        /** 🆕 attack=line 时真的换掉了几条指令（所有 line 档条目之和）。 */
        volatile int sites;

        /** 🆕 站点清单（{@code setHealth:m_21153_},… ；空 = 这一趟没有 line 档条目）。 */
        volatile String siteList = "";

        /** 🆕 没套上的（拿不到方法／描述符对不上），进日志。 */
        final List<String> failedMethods = new ArrayList<>(2);

        // ---- 🆕 读回取证的三份指纹里的两份（第三份 live 由 readback 现算，不留字节）
        /** patch 前 transformer 收到的字节的指纹。 */
        volatile HotfixPatchReadback.Fingerprint origFp;

        /** 我们 {@code return} 下去的那份字节的指纹。 */
        volatile HotfixPatchReadback.Fingerprint patchedFp;

        Pending(String internalName, String methodName, String methodDesc, int lineHint) {
            this(internalName, methodName, methodDesc, lineHint, "", null);
        }

        Pending(String internalName, String methodName, String methodDesc, int lineHint,
                String mainKey, List<PlanEntry> plan) {
            this.internalName = internalName;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
            this.lineHint = lineHint;
            this.mainKey = mainKey == null ? "" : mainKey;
            this.plan = plan == null ? new ArrayList<PlanEntry>(0) : plan;
        }
    }

    /**
     * 🆕 <b>wanted 的一条</b>：一个"应该被我们改成安全默认返回"的方法 ＋ 重建它需要的全部信息。
     *
     * <p>为什么不能只存键（{@code 类#方法}）：重建时要<b>没有账本也能重打</b>
     * （目标可能已经从 {@code PATCHED} 退役），所以描述符与行号必须自己带着走。
     */
    private static final class Want {

        /** {@code 类#方法}（与日志里的 {@code target=} 逐字一致）。 */
        final String key;

        /** 源级类名（重建时按它找 {@code Class}、也按它分组做 keepalive）。 */
        final String className;

        /** 内部名（{@code /} 分隔），transformer 匹配用。 */
        final String internalName;

        final String methodName;

        /** 精确描述符（拿得到时优先用它定位方法）；空 ⇒ 靠 {@link #lineHint}。 */
        final String desc;

        final int lineHint;

        /** 🆕 这一条当前用的档（{@link HotfixAttackTier#LINE}／{@link HotfixAttackTier#METHOD}）。 */
        String tier = HotfixAttackTier.LINE;

        Want(String key, String className, String internalName, String methodName, String desc, int lineHint) {
            this.key = key == null ? "" : key;
            this.className = className == null ? "" : className;
            this.internalName = internalName == null ? "" : internalName;
            this.methodName = methodName == null ? "" : methodName;
            this.desc = desc == null ? "" : desc;
            this.lineHint = lineHint;
        }
    }

    /** 从 {@code 类#方法} 里取方法名（取不到 ⇒ 空串）。 */
    private static String methodNameOf(String key) {
        if (key == null) {
            return "";
        }
        final int hash = key.indexOf('#');
        return hash < 0 || hash == key.length() - 1 ? "" : key.substring(hash + 1);
    }

    /** wanted 里现在有几条（日志 {@code wanted=} 用；不加锁读快照）。 */
    private static int wantedCount() {
        synchronized (CHAT_PATCHED_KEYS) {
            return CHAT_PATCHED_KEYS.size();
        }
    }

    /** 🆕 按类累计里的一个方法（{@code retransform} 时和别的目标一起套上）。 */
    private static final class PlanEntry {

        /** {@code 类#方法}（用于对账/日志）。 */
        final String key;

        final String methodName;

        /** 精确描述符（已登记的账本里那条的 desc）；空 ⇒ 靠 {@link #lineHint} 定位。 */
        final String methodDesc;

        final int lineHint;

        /** 🆕 这个条目要用哪一档（同类里不同方法可以不同档）。 */
        final String tier;

        PlanEntry(String key, String methodName, String methodDesc, int lineHint) {
            this(key, methodName, methodDesc, lineHint, HotfixAttackTier.METHOD);
        }

        PlanEntry(String key, String methodName, String methodDesc, int lineHint, String tier) {
            this.key = key == null ? "" : key;
            this.methodName = methodName == null ? "" : methodName;
            this.methodDesc = methodDesc == null ? "" : methodDesc;
            this.lineHint = lineHint;
            this.tier = HotfixAttackTier.orDefault(tier);
        }
    }

    /** {@code key} 在这份 plan 里用的是哪一档（找不到 ⇒ 默认档）。 */
    private static String tierOfPlanned(List<PlanEntry> plan, String key) {
        if (plan == null || key == null) {
            return HotfixAttackTier.LINE;
        }
        for (int i = 0; i < plan.size(); i++) {
            final PlanEntry e = plan.get(i);
            if (e != null && key.equals(e.key)) {
                return e.tier;
            }
        }
        return HotfixAttackTier.LINE;
    }

    /** 🆕 把一次失败的 plan 条目标成"整次不提交"（transformer 里唯一允许的失败姿势）。 */
    private static void abortPending(Pending p, String failedKey, String reason, String firstReason) {
        if (p == null) {
            return;
        }
        p.aborted = true;
        p.abortFailedKey = failedKey == null ? "" : failedKey;
        p.abortReason = reason == null || reason.isEmpty()
                ? (firstReason == null ? "?" : firstReason) : reason;
        if (failedKey != null && !failedKey.isEmpty()) {
            p.perMethod.put(failedKey, "aborted:" + p.abortReason);
            p.failedMethods.add(failedKey + ":" + p.abortReason);
        }
    }

    /** {@code PlanEntry} → {@link HotfixPatchPlan.MethodPatch}（两张表必须是同一个口径）。 */
    private static List<HotfixPatchPlan.MethodPatch> toMethodPatches(List<PlanEntry> plan) {
        final List<HotfixPatchPlan.MethodPatch> out = new ArrayList<>(plan == null ? 0 : plan.size());
        for (int i = 0; plan != null && i < plan.size(); i++) {
            final PlanEntry e = plan.get(i);
            if (e == null || e.key.isEmpty()) {
                continue;
            }
            out.add(new HotfixPatchPlan.MethodPatch(e.key, e.methodName, e.methodDesc, e.lineHint, e.tier));
        }
        return out;
    }

    /**
     * {@link HotfixPatchPlan.MethodPatch} → {@code PlanEntry}（交给 transformer 的那一份）。
     *
     * <p>🔴 这一步是"整张计划一起重写"的<b>落地处</b>：transformer 收到的必须是
     * <b>合并后的全集</b>（之前写过的 ＋ 这次新补的），而不是本次新补的那几条 ——
     * 少了这一步，{@code rev} 会认"3 个方法都在"，字节里却只有本次那 2 个
     * （离线自证当场抓到这个：{@code batch=732} 而 {@code one+two=690}）。
     */
    private static List<PlanEntry> toPlanEntries(List<HotfixPatchPlan.MethodPatch> patches) {
        final List<PlanEntry> out = new ArrayList<>(patches == null ? 0 : patches.size());
        for (int i = 0; patches != null && i < patches.size(); i++) {
            final HotfixPatchPlan.MethodPatch p = patches.get(i);
            if (p == null || p.key.isEmpty()) {
                continue;
            }
            out.add(new PlanEntry(p.key, p.methodName, p.desc, p.lineHint, p.tier));
        }
        return out;
    }

    /**
     * 🆕 2026-11-21（根因①）：<b>一次整计划写入</b>的结论。
     *
     * <p>它把"这一趟到底写了什么"收敛成一份可判读的事实：第几代（{@code rev}）、写了几个方法
     * （{@code methods}＝全集，含之前就有的兄弟）、真的调了几次 {@code retransformClasses}
     * （{@code retransforms}，同一个 {@code Class} 只调一次）、写下去那份的 sha（身份章）。
     */
    private static final class PlanWrite {

        boolean ok;
        String detail = "not_started";

        /** 这一次写完会是第几代（{@code patch_write rev=}）。 */
        long rev;

        /** {@code first}／{@code new_patch}／{@code recover}。 */
        String reason = HotfixPatchPlan.REASON_FIRST;

        /** 真的调了几次 {@code retransformClasses}（同一份计划、每个同名 Class 一次）。 */
        int retransforms;

        /** 这一趟写下去的方法总数（计划全集）。 */
        int methods;

        /** 真的套上了几个（正常情况下 == {@link #methods}；不等就意味着 abort）。 */
        int applied;

        /** 这一趟顶替了几条（升级 line→method 会在这里显形）。 */
        int superseded;

        /** 整次不提交时：是哪一条挂了、为什么。 */
        String failedKey = "";
        String failReason = "";

        /**
         * 🆕 2026-11-21（回归修复）：这一拍<b>可以靠升级解决</b>（line 档 ＋ 落点找不到那种 reason）。
         *
         * <p>由"一趟"判定、由 {@link #writePlanLocked} 执行：它一置位就说明<b>这一拍还不该打 abort</b>。
         */
        boolean escalatable;

        /** 🆕 升级真的发生了（wrapper 置位；{@code apply} 据此把账本档位跟着升到 method）。 */
        boolean escalated;

        /** 🆕 被升级的是哪一条（{@code 类#方法}）。 */
        String escalatedKey = "";

        /** 🆕 升级的原因（原始 reason，进 {@code patch_escalate … why=}）。 */
        String escalatedWhy = "";

        int origLen;
        int newLen;
        String origSha = "?";
        String baseSha = "?";
        String newSha = "";
        boolean fromSnapshot;

        /** 身份章（记账型）：写之前我们手里记的那一份 sha；{@code null} = 还没写过。 */
        String identityExpected;

        /** 账本里那一份 vs 这次这份（{@code before_sha}／{@code after_sha} 用）。 */
        HotfixPatchReadback.Fingerprint origFp = HotfixPatchReadback.Fingerprint.NONE;
        HotfixPatchReadback.Fingerprint patchedFp = HotfixPatchReadback.Fingerprint.NONE;

        /** 这一趟花在 {@code retransformClasses} 上的时间（纳秒；{@code patch_apply elapsed_ms} 用）。 */
        long elapsedNanos;

        /**
         * 🆕 2026-11-21（P2）：这一类被几条写入路径写过（{@code patch}／{@code chain_wipe}／{@code both}）。
         *
         * <p>它进 {@code patch_write … writer=} 与 {@code chain_wipe_write … writer=}。
         */
        String writer = "";

        /**
         * 🆕 2026-11-21（P2）：<b>同一输入连算两次</b>的输出是否逐字节一致。
         *
         * <p>{@code true} = 我们的输出是稳定的 ⇒ 与账本身份不一致时<b>不许</b>归因于
         * "我们自己输出不稳定"（那是抢写／账本代际变化）。只有 {@code false} 才允许
         * {@code patch_lost evidence=self_output_unstable}。
         */
        boolean stableOutput = true;

        /** 🆕 这一趟合并出来的计划摘要（{@link HotfixPatchPlan#digestOf}）：与上一次相同 ⇒ 输出应当相同。 */
        String digest = "";

        /** 🆕 2026-11-21（P2 追加）：这个类**第几次被串行写**（三条写入路径合计；日志 {@code serial=}）。 */
        long serial;

        final Map<String, String> perMethod = new LinkedHashMap<>();
        int sites;
        String siteList = "";
        String desc = "";
        String match = "none";
        String defaultKind = "";

        /** 这一次合并出来的候选计划（{@code apply} 要拿它回落 {@code appliedWants}）。 */
        HotfixPatchPlan.Merge merge;
    }

    /**
     * 🔴 升级记账：{@code 类#方法 ⇒ 这一局已经为它升过一次档}（见 {@link #writePlanLocked}）。
     *
     * <p>它是有界性<b>唯一</b>的依据：同一个 target 每局最多升 1 次 ⇒ 不可能出现
     * line→method→line→method 的反复（第二次还是"找不到落点"就照旧 abort，交给
     * {@link HotfixPatchPlan#ABORT_GIVEUP} 那道有限次闸门停手）。
     *
     * <p>键与 {@link #PATCHED}／{@link HotfixPatchPlan} 同一个口径（{@code 类#方法}）；
     * 随会话复位（{@link #retireAllWatches()}）一起清空。
     */
    private static final java.util.Set<String> ESCALATED = new java.util.HashSet<>();

    /** 这个 target 这一局已经升过档了吗（是 ⇒ 不许再升）。 */
    private static boolean escalatedAlready(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        synchronized (ESCALATED) {
            return ESCALATED.contains(key);
        }
    }

    /** 记一笔"这个 target 升级过了"；返回 {@code false} = 之前就记过（这一次不许再升）。 */
    private static boolean markEscalated(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        synchronized (ESCALATED) {
            return ESCALATED.add(key);
        }
    }

    /** 清空升级记账（停服/退出世界、离线自检复位）。 */
    private static void clearEscalated() {
        synchronized (ESCALATED) {
            ESCALATED.clear();
        }
    }

    /** 把 plan 里某个 target 的档换成 {@code tier}（返回<b>新表</b>，调用方手里那份不动）。 */
    private static List<PlanEntry> withTier(List<PlanEntry> plan, String key, String tier) {
        final List<PlanEntry> out = new ArrayList<>(plan == null ? 0 : plan.size());
        for (int i = 0; plan != null && i < plan.size(); i++) {
            final PlanEntry e = plan.get(i);
            if (e == null) {
                continue;
            }
            if (key != null && key.equals(e.key)) {
                out.add(new PlanEntry(e.key, e.methodName, e.methodDesc, e.lineHint, tier));
            } else {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * 🆕 2026-11-21（回归修复）：<b>整张计划一次写</b>，并且 <b>{@code line} 找不到落点 ⇒ 自动升级到强力档重试</b>。
     *
     * <h2>为什么必须有这一层（现场 2026-11-21 07:49–07:54 实例 {@code GTL山海9.10test2}）</h2>
     * <pre>
     *   patch_write_abort class=… rev=0 failed=…#instantKill  reason=line_no_site committed=false attempts=1
     *   patch_write_abort class=… rev=0 failed=…#updateSwipe  reason=line_no_site committed=false attempts=1
     *   … 共 12 条，全部 line_no_site，PATCHED 播报 0 条
     * </pre>
     * 机制：选靶修好之后 {@code action_anchor} 落到了真正的动作帧（{@code instantKill}／{@code updateSwipe}），
     * 而<b>这些方法本身不含"写受保护状态"的那条指令</b>（它调用别的函数去写）⇒ line 档找不到 site。
     * 「整次全装或不装」那条是对的，缺的是<b>升级到 method 档再试一次</b>。用户 2026-11-20 口径：
     * <blockquote>
     *   「废整个方法也要保留，只不过作为更强力的攻击，默认使用废那一行作为普通攻击手段」
     * </blockquote>
     * ⇒ 凡 {@link HotfixAttackTier#lineImpossible(String)} 那一族 reason（{@code line_no_site} 属它）
     * 都先把<b>这一个 target</b> 记成 {@link HotfixAttackTier#METHOD}、<b>重新并进当前计划</b>再提交一次
     * （同一拍内完成；{@code reason=escalate}）。
     *
     * <h2>三条红线（都在这一层兜住）</h2>
     * <ol>
     *   <li><b>有界</b>：同一个 target <b>每局最多升 1 次</b>（{@link #ESCALATED} 记账）——
     *       绝不允许 line→method→line 反复；第二次还是找不到落点就照旧 abort（{@code reason=line_no_site}）。</li>
     *   <li><b>升级后也装不上才放弃</b>：那一拍的 abort 用
     *       {@link HotfixPatchPlan#REASON_METHOD_FAILED_AFTER_ESCALATION}（原始原因不丢，跟着 {@code inner=}）。</li>
     *   <li><b>不放宽"整次全装或不装"</b>：升级重试仍然是一份计划、每个 Class 一次 {@code retransform}、
     *       任何一条失败就整次不提交（{@code committed=false}）。</li>
     * </ol>
     *
     * <p>⚠️ 调用方必须持有 {@link #LOCK}（{@code retransform} 期间持锁是既有约定）；
     * 也正因为持锁，这里的"记账 ⇒ 升级 ⇒ 重试"是一个不可分割的动作。
     */
    private static PlanWrite writePlanLocked(Instrumentation inst, PatchTransformer tx, String className,
                                             List<Class<?>> targets, List<PlanEntry> entries, String mainKey,
                                             String reason) {
        return writePlanLocked(inst, tx, className, targets, entries, mainKey, reason,
                HotfixPatchPlan.WRITER_PATCH);
    }

    /**
     * 🆕 2026-11-21（P2，用户口径「两条路径并成一份该类的计划」）：带 {@code writer} 的整计划写。
     *
     * <p>两条写入路径（普通补丁 / 整链抹除）从这一版起<b>只共用这一个入口</b>、共用同一张
     * {@link HotfixPatchPlan.ClassPlan}、共用同一份类级身份（rev／sha／长度／计划摘要／写入者）。
     * {@code writer} 只有两个作用：
     * <ol>
     *   <li>落账（{@code ClassPlan#commit(…, writer)}）⇒ {@code writer=<patch|chain_wipe|both>}；</li>
     *   <li>决定<b>谁打写成功那一行</b>：整链抹除驱动的那一趟<b>不</b>再额外打一行
     *       {@code patch_write}（否则同一个类的一次写会被记成两笔，看起来像"两条路径各写一次"）。</li>
     * </ol>
     */
    private static PlanWrite writePlanLocked(Instrumentation inst, PatchTransformer tx, String className,
                                             List<Class<?>> targets, List<PlanEntry> entries, String mainKey,
                                             String reason, String writer) {
        // 🆕 2026-11-21（P2 ③）：<b>按类 ＋ 目标方法集合 的写冷却</b>（缓解"时间暂停"，不改语义）。
        //   现场：patch_write=144 / chain_wipe_write=8 ⇒ "Can't keep up! 111 ticks behind" × 6、
        //   holo_frame_gap 78 s。同一类、同一批方法在几百毫秒内被反复重写<b>没有任何语义收益</b>，
        //   只是让 JVM 一次次作废已 JIT 的版本 ⇒ 这里只挡【重复写】。
        //   🔴 判据（一个字都不许放宽）：类名 ＋ 目标方法集合（{@code 类#方法#描述符} 排序去重）
        //      都相同、且 200 tick 内已经【写成功】过 2 次 ⇒ 才挡。新增了方法（集合变了）⇒ 键不同 ⇒ 放行。
        final String cooldownKey = targetSetKey(entries);
        final HotfixWriteCooldown.Decision cooldown =
                HotfixWriteCooldown.admit(className, cooldownKey, System.currentTimeMillis());
        if (!cooldown.ok) {
            log(cooldown.line);                      // 逐字：write_cooldown class=… skipped=N
            final PlanWrite skipped = new PlanWrite();
            skipped.ok = false;                      // 没写 ⇒ 下游按失败处理（不会有半装状态）
            skipped.detail = "cooldown_dup_write";
            skipped.reason = reason == null ? skipped.reason : reason;
            skipped.methods = entries == null ? 0 : entries.size();
            // rev 报【这一类当前那一代】（不是 0）：这一趟一个字节都没交，但类本身已经有 rev 了
            // ⇒ 写 0 会让现场误读成"这个类还没被写过"。
            try {
                skipped.rev = HotfixPatchPlan.plan(className == null ? "" : className).rev();
            } catch (Throwable ignored) {
                skipped.rev = 0L;
            }
            return skipped;
        }
        final PlanWrite first = writePlanAttemptLocked(inst, tx, className, targets, entries, mainKey, reason, false,
                writer);
        if (first.ok || !first.escalatable) {
            if (first.ok) {
                HotfixWriteCooldown.recordWritten(className, cooldownKey, System.currentTimeMillis());
                // 🆕 2026-11-21（最终版）：合并后的处置播报（每类一行；patch 与整链抹除共用这一处）
                announceClassPatched(className, first, "");
            }
            return first;
        }
        final String key = first.failedKey;
        final String why = first.failReason;
        if (!markEscalated(key)) {
            // 兜底（正常走不到：{@code escalatable} 已经查过一次；持锁所以也不会有竞态）
            log(HotfixPatchPlan.abortLine(className, first.rev, key, why)
                    + " attempts=" + HotfixPatchPlan.noteAbort(className, key)
                    + " already_escalated=1");
            first.escalatable = false;
            return first;
        }
        // ---- ① 宣告升级（逐字：升级了／为什么／只升这一条）
        log(HotfixPatchPlan.escalateLine(className, key, why));
        // 🔴 升级＝更强力的攻击（整个方法停摆，副作用明显）⇒ 与既有 attack_escalate 同一口径额外播报一次
        announceEscalated(key, why);
        // ---- ② 把这一条记成 METHOD 并重新并进当前计划，立刻再提交一次（reason=escalate）
        final PlanWrite retry = writePlanAttemptLocked(inst, tx, className, targets,
                withTier(entries, key, HotfixAttackTier.METHOD), mainKey,
                HotfixPatchPlan.REASON_ESCALATE, true, writer);
        retry.escalated = true;
        retry.escalatedKey = key;
        retry.escalatedWhy = why;
        if (retry.ok) {
            HotfixWriteCooldown.recordWritten(className, cooldownKey, System.currentTimeMillis());
            // 🆕 2026-11-21（最终版）：升档成功也走同一条"每类一行"（方法名后加 {@code *}）
            announceClassPatched(className, retry, key);
        }
        return retry;
    }

    /**
     * 🆕 2026-11-21（P2 ③）：<b>"同一批目标方法集合"的稳定串</b>（写冷却的键之一）。
     *
     * <p>为什么用 {@code TreeSet}（排序去重）而不是 {@code List.toString()}：两条写入路径
     * （普通补丁 / 整链抹除）加方法的<b>顺序不同</b>，而"同一批方法"与顺序无关 ——
     * 顺序敏感的键会让本该挡掉的重复写全部漏过去。
     *
     * <p>描述符也进键：{@code 类#方法} 相同但描述符不同 = 换了一个重载 ⇒ 目标集合确实变了。
     *
     * @param entries 这一次请求的目标方法（{@code null}/空 ⇒ 空串，仍然照常限流）
     * @return 形如 {@code a#m1()V,b#m2:I}（排序后逗号拼接）
     */
    private static String targetSetKey(List<PlanEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        final java.util.TreeSet<String> set = new java.util.TreeSet<String>();
        for (int i = 0; i < entries.size(); i++) {
            final PlanEntry e = entries.get(i);
            if (e == null || e.key.isEmpty()) {
                continue;
            }
            set.add(e.key + "#" + e.methodDesc);
        }
        final StringBuilder sb = new StringBuilder(set.size() * 32);
        for (String s : set) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
    }


    /**
     * {@link #writePlanLocked} 的<b>一趟</b>：合并 → 写 → 全装才提交。
     *
     * @param escalatedRetry {@code true} = 这一趟是"升级到强力档之后的重试"（abort 的 reason 因此不同）
     */
    private static PlanWrite writePlanAttemptLocked(Instrumentation inst, PatchTransformer tx, String className,
                                                    List<Class<?>> targets, List<PlanEntry> entries, String mainKey,
                                                    String reason, boolean escalatedRetry) {
        return writePlanAttemptLocked(inst, tx, className, targets, entries, mainKey, reason, escalatedRetry,
                HotfixPatchPlan.WRITER_PATCH);
    }

    private static PlanWrite writePlanAttemptLocked(Instrumentation inst, PatchTransformer tx, String className,
                                                    List<Class<?>> targets, List<PlanEntry> entries, String mainKey,
                                                    String reason, boolean escalatedRetry, String writer) {
        // 🆕 2026-11-21（P2 追加）：<b>按类的写锁</b> —— 三条写入路径（patch／chain_wipe／restore）串行。
        //   调用方已持全局 {@link #LOCK}；这里的顺序固定"全局 → 类锁"（绝不反，反了会死锁）。
        final HotfixPatchPlan.ClassPlan gate = HotfixPatchPlan.plan(className == null ? "" : className);
        gate.lock();
        try {
            final long serial = gate.writeEnter();
            try {
                return writePlanAttemptLocked0(inst, tx, className, targets, entries, mainKey, reason,
                        escalatedRetry, writer, gate, serial);
            } finally {
                gate.writeExit();
            }
        } finally {
            gate.unlock();
        }
    }

    private static PlanWrite writePlanAttemptLocked0(Instrumentation inst, PatchTransformer tx, String className,
                                                     List<Class<?>> targets, List<PlanEntry> entries, String mainKey,
                                                     String reason, boolean escalatedRetry, String writer,
                                                     HotfixPatchPlan.ClassPlan plan, long serial) {
        final boolean chainWriter = HotfixPatchPlan.WRITER_CHAIN_WIPE.equals(writer);
        final PlanWrite out = new PlanWrite();
        out.serial = serial;
        if (inst == null || tx == null || targets == null || targets.isEmpty() || entries == null
                || entries.isEmpty()) {
            out.detail = "write_no_target";
            return out;
        }
        final String cn = className == null ? "" : className;
        final String in = internalName(cn);
        // ---- ① 合并（候选；不提交）
        final HotfixPatchPlan.Merge merge = HotfixPatchPlan.merge(plan, toMethodPatches(entries), reason);
        out.merge = merge;
        out.rev = merge.rev;
        out.reason = merge.reason;
        out.methods = merge.size();
        out.superseded = merge.superseded;
        // 🆕 2026-11-21（P2）：整链抹除驱动的那一趟<b>不打</b> patch_plan/patch_write
        //   —— 同一个类的一次写只能有一条写成功行（它自己的 chain_wipe_write 就带着 rev/methods/writer）。
        if (!chainWriter) {
            log(HotfixPatchPlan.planLine(cn, merge.size(), merge.detail(), writer));
        }
        // ---- ② 身份章（记账型：我们读不到"当前定义"，见 HotfixPatchReadback 的 P1/P2）
        out.identityExpected = HotfixBaseline.stampedSha(in);
        // ---- ③ 写（每个 Class 只 retransform 一次；同一份 plan）
        //   🔴 交给 transformer 的是【合并后的全集】：之前写过的 ＋ 这次新补的，一起重写。
        final List<PlanEntry> full = toPlanEntries(merge.candidate);
        boolean first = true;
        /** 🆕 2026-11-21（P2）：JVM 交给 transformer 的那份输入（"同一输入连算两次"的输入）。 */
        byte[] firstInput = null;
        for (int i = 0; i < targets.size(); i++) {
            final Class<?> c = targets.get(i);
            if (c == null) {
                continue;
            }
            final Pending pending = new Pending(in, "", null, -1, mainKey == null ? "" : mainKey, full);
            tx.pending = pending;
            final long t0 = System.nanoTime();
            boolean threw = false;
            HotfixSelfWindow.enter("retransform");
            try {
                inst.retransformClasses(c);
                out.retransforms++;
            } catch (Throwable t) {
                threw = true;
                out.detail = "retransform_" + simpleName(t);
                out.failReason = simpleName(t);
            } finally {
                tx.pending = null;
                HotfixSelfWindow.exit();
            }
            out.elapsedNanos += System.nanoTime() - t0;
            // 🆕 2026-11-21（内联摘除）：这一趟真的写下去了、且顺手摘了外部 handler ⇒ 一行逐字日志。
            //   没有外部 handler 时 inlineStripped=0 ⇒ 不打（否则每条 patch 刷一行）；
            //   补丁 abort/threw 时也不打：那一趟一个字节都没交，说"摘了"会误导。
            if (!threw && !pending.aborted && pending.inlineStripped > 0) {
                log("keyclass_restore_inline class=" + token(cn)
                        + " stripped=" + pending.inlineStripped
                        + " handlers=" + pending.inlineHandlers
                        + " ours_kept=" + pending.inlineOurs
                        + " trigger=patch_write");
            }
            if (pending.aborted || threw) {
                // 🔴 整次不提交（一个字节都不算数）：宁可一次不装，也不要半装
                out.ok = false;
                // ⚠️ rev 报【提交前】的那一代：abort 的定义就是"rev 不动"，报 candidate 会误导
                out.rev = merge.rev - 1L;
                if (!threw) {
                    out.detail = "abort_" + pending.abortReason;
                }
                out.failedKey = pending.abortFailedKey == null || pending.abortFailedKey.isEmpty()
                        ? (first ? mergeKeyOrEmpty(entries, mainKey) : pending.abortFailedKey) : pending.abortFailedKey;
                out.failReason = pending.abortReason;
                if (first) {
                    out.origLen = pending.origBytes;
                    out.origSha = pending.origSha;
                    out.baseSha = pending.baseSha;
                    out.fromSnapshot = pending.fromSnapshot;
                    out.perMethod.putAll(pending.perMethod);
                }
                // 🆕 2026-11-21（回归修复）：line 档"找不到落点"那一族 ⇒ <b>不在这一趟里放弃</b>。
                //   交给 writePlanLocked 把这一个 target 记成 method、重新并进当前计划、立刻再提交一次。
                //   ⚠️ 升级是"更强力的攻击"，不是写失败：所以这一拍<b>不打 patch_write_abort</b>，
                //   也<b>不记 noteAbort</b>（否则升上去成了的那条会白背一次"写失败"，把
                //   ABORT_GIVEUP 的预算提前花光）。
                out.escalatable = !escalatedRetry
                        && HotfixAttackTier.LINE.equals(tierOfPlanned(entries, out.failedKey))
                        && HotfixAttackTier.lineImpossible(out.failReason)
                        && !escalatedAlready(out.failedKey);
                if (out.escalatable) {
                    out.detail = "escalate_" + out.failReason;
                    return out;
                }
                // 记一次"这一条写不进去"：到 ABORT_GIVEUP 次就不再让它挡住整张计划
                final int aborts = HotfixPatchPlan.noteAbort(cn, out.failedKey);
                final boolean giveUp = aborts >= HotfixPatchPlan.ABORT_GIVEUP;
                // 🆕 2026-11-21（回归修复）：升级之后仍然装不上 ⇒ reason 必须与"line 找不到落点"分开
                //   （现场要一眼分出"该升级"与"真的没救"）；原始原因不丢，跟着 inner= 打出来。
                final String abortWhy = escalatedRetry
                        ? HotfixPatchPlan.REASON_METHOD_FAILED_AFTER_ESCALATION
                        : (out.failReason == null || out.failReason.isEmpty() ? "?" : out.failReason);
                if (escalatedRetry) {
                    out.detail = "abort_" + HotfixPatchPlan.REASON_METHOD_FAILED_AFTER_ESCALATION;
                }
                log(HotfixPatchPlan.abortLine(cn, out.rev, out.failedKey, abortWhy)
                        + " attempts=" + aborts
                        + (escalatedRetry ? " escalated=1 inner=" + token(out.failReason) : "")
                        + (giveUp ? " give_up=1" : ""));
                return out;
            }
            if (first) {
                first = false;
                // 🆕 2026-11-21（P2）：把 JVM 交上来的那一份留作"连算两次"的输入
                //   （transformer 里 {@code p.original = classfileBuffer.clone()} 就是它）
                firstInput = pending.original;
                out.origLen = pending.origBytes;
                out.newLen = pending.newBytes;
                out.origSha = pending.origSha;
                out.baseSha = pending.baseSha;
                out.newSha = pending.newSha;
                out.fromSnapshot = pending.fromSnapshot;
                out.origFp = pending.origFp == null ? HotfixPatchReadback.Fingerprint.NONE : pending.origFp;
                out.patchedFp = pending.patchedFp == null ? HotfixPatchReadback.Fingerprint.NONE : pending.patchedFp;
                out.applied = pending.appliedMethods;
                out.sites = pending.sites;
                out.siteList = pending.siteList;
                out.desc = pending.desc == null ? "" : pending.desc;
                out.match = pending.match == null ? "none" : pending.match;
                out.defaultKind = pending.defaultKind == null ? "" : pending.defaultKind;
                out.perMethod.putAll(pending.perMethod);
            }
        }
        if (out.retransforms == 0) {
            out.detail = "write_no_retransform";
            return out;
        }
        // ---- ④ 全部装上才提交（rev 唯一的 +1 处）
        final long now = System.currentTimeMillis();
        final String digest = HotfixPatchPlan.digestOf(merge.candidate);
        out.digest = digest;
        HotfixPatchPlan.commit(merge, now, out.newSha, out.newLen, digest, writer);
        HotfixBaseline.stampSha(in, out.newSha);
        final HotfixBaseline.Snapshot snap = HotfixBaseline.snapshot(in);
        if (snap != null && !baselineLogged(in)) {
            markBaselineLogged(in);
            log(HotfixBaseline.baselineLine(cn, snap));
        }
        // 🆕 2026-11-21（P2）：{@code writer=} 取**这一类被几条路径写过**（both = 这一份计划里既有普通
        //   补丁的目标、又有整链抹除的目标），而不是"谁触发的这一拍"。
        final String writerTag = plan.writerTag();
        out.writer = writerTag;
        // 🆕 2026-11-21（P2）：**同一输入连算两次**的输出是否逐字节一致 —— 只有它不一致，
        //   {@code patch_lost} 才有资格写 evidence=self_output_unstable（见 lostEvidence）。
        out.stableOutput = probeStableOutput(firstInput, merge.candidate, in);
        if (!chainWriter) {
            log(HotfixPatchPlan.writeLine(cn, merge.rev, merge.size(), merge.detail(), merge.reason,
                    out.retransforms, merge.superseded, writerTag, serial));
        }
        log(HotfixBaseline.rebuildLine(cn, out.fromSnapshot, out.baseSha, out.newSha, out.methods));
        out.ok = true;
        out.detail = "ok";
        return out;
    }

    /** 主目标键（拿不到 ⇒ 空串；只给 abort 日志用）。 */
    private static String mergeKeyOrEmpty(List<PlanEntry> entries, String mainKey) {
        if (mainKey != null && !mainKey.isEmpty()) {
            return mainKey;
        }
        return entries == null || entries.isEmpty() || entries.get(entries.size() - 1) == null
                ? "" : entries.get(entries.size() - 1).key;
    }

    // ═══════════════ 🆕 2026-11-21（P2）：同一输入连算两次 ⇒ 输出稳不稳（可离线断言） ═══════════════

    /**
     * <b>同一份输入、同一份计划，连算两次</b> —— 两次输出是否逐字节一致。
     *
     * <p>为什么这条判据是 {@code evidence=self_output_unstable} 的<b>唯一</b>合法来源：
     * 现场 134 条 {@code self_output_unstable} 全是"账本里记的是<b>某一条方法补丁</b>的指纹、
     * 这次比的是<b>整类</b>的输出"（计划里方法一变，字节必然不同），与"我们的算法不稳定"毫无关系。
     * ⇒ 只有<b>同一输入连算两次就不同</b>，才允许说"我们自己输出不稳定"。
     *
     * @param input  JVM 交给 transformer 的那一份（{@code pending.original}）
     * @param plan   这一趟的计划（合并后的全集）
     * @param internalName 内部名
     * @return {@code true} = 两次一致（稳定）；拿不到输入／探针炸了 ⇒ 也按稳定处理（宁可不报）
     */
    static boolean probeStableOutput(byte[] input, List<HotfixPatchPlan.MethodPatch> plan, String internalName) {
        final PatchTransformer tx = transformer;
        if (input == null || plan == null || plan.isEmpty() || tx == null || internalName == null) {
            return true;
        }
        final List<PlanEntry> entries = toPlanEntries(plan);
        final Pending saved = tx.pending;
        try {
            tx.pending = new Pending(internalName, "", null, -1, "", entries);
            final byte[] once = tx.transform(null, internalName, null, null, input.clone());
            tx.pending = new Pending(internalName, "", null, -1, "", entries);
            final byte[] twice = tx.transform(null, internalName, null, null, input.clone());
            return java.util.Arrays.equals(once, twice);
        } catch (Throwable t) {
            return true;
        } finally {
            tx.pending = saved;
        }
    }

    /**
     * 通用版（<b>离线负对照</b>用）：同一个 transformer、同一份输入连调两次，比较两份输出。
     *
     * <p>它只做"连算两次并比较"这一件事，所以离线可以塞一个"第二次故意返回不同字节"的
     * 假 transformer 进来，证明这条判据真的会翻红 —— 而不是永远返回 {@code true}。
     */
    static boolean sameOutputTwice(java.lang.instrument.ClassFileTransformer tx, ClassLoader loader,
                                   String internalName, byte[] input) {
        if (tx == null || input == null) {
            return true;
        }
        try {
            final byte[] once = tx.transform(loader, internalName, null, null, input.clone());
            final byte[] twice = tx.transform(loader, internalName, null, null, input.clone());
            return java.util.Arrays.equals(once, twice);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 已经打过 {@code patch_baseline} 的类（每类一行）。 */
    private static final java.util.Set<String> BASELINE_LOGGED = new java.util.HashSet<>();

    private static boolean baselineLogged(String internalName) {
        synchronized (BASELINE_LOGGED) {
            return BASELINE_LOGGED.contains(internalName);
        }
    }

    private static void markBaselineLogged(String internalName) {
        synchronized (BASELINE_LOGGED) {
            BASELINE_LOGGED.add(internalName);
        }
    }

    // ═══════════════════════════════ 🆕 ③ 整链抹除（更强力的一档） ═══════════════════════════════

    /**
     * ③ 的入口：把"这条违规现场抓到的整链"交给 {@link HotfixChainWipe}。
     *
     * <p>⚠️ 在<b>工作线程</b>上调（{@link #handle} 里），因为下面要拿 {@link #LOCK} 并做
     * {@code retransformClasses}；<b>绝不许</b>在 appender／服务端线程上调。
     *
     * <p>为什么不在这里拼日志、不在这里判模式：那些都在 {@link HotfixChainWipe} 里，
     * 它只依赖 {@code java.*} ＋ ASM ＋ 同包纯 JDK 类，能被离线自检<b>同一份字节码</b>跑。
     *
     * @param inst   {@link Instrumentation}
     * @param strike 这一拍的抓栈记录（{@code chain} 为 64 帧整链；为空时用那 16 帧兜底）
     * @return 这一批的读数（{@code groups/ok/aborted} ＋ 已抹掉的类）；拿不到 ⇒ {@code null}
     */
    static HotfixChainWipe.Batch chainWipeJob(Instrumentation inst, HotfixTriageTap.Strike strike) {
        if (inst == null || strike == null) {
            return null;
        }
        try {
            final HotfixChainCapture.Chain chain;
            if (strike.chain != null && !strike.chain.isEmpty()) {
                // 违规现场那一拍抓的 64 帧（最里层在前）⇒ 组装成整链（顶端→最低端）
                chain = HotfixChainCapture.build(strike.chain, HotfixChainCapture.DEPTH);
            } else {
                // 兜底：只有本体那 16 帧（深度不够，但仍然是"整链"里能看见的所有外部段）
                chain = HotfixChainCapture.build(strike.frames, HotfixChainCapture.DEPTH);
            }
            return HotfixChainWipe.wipe(inst, chain);
        } catch (Throwable t) {
            // 整链抹除炸了绝不许把工作线程带崩（下一拍照旧走普通攻击）
            // 🆕 2026-11-21（P2）：字段顺序与其它 skip 行统一成 {@code class=… reason=…} 相邻。
            log("chain_wipe_skip class=* reason=exception_" + simpleName(t));
            return null;
        }
    }

    /** 整链抹除的日志出口（{@code HotfixChainWipe} 只认这一个方法，不引用 log4j／MC）。 */
    static void chainLog(String line) {
        log(line);
    }

    /**
     * 🆕 这个类**这一组方法**是不是已经在已提交的计划里了（全部 method 档、rev&gt;0）。
     *
     * <p>为什么必须有这道闸门：整链抹除挂在<b>每一个</b>违规现场上。没有它，同一个类会在每一条
     * 违规里被再写一遍（每次一次 {@code retransform}）—— 那不是"更强"，那是"每秒让 JVM 去优化一堆类"，
     * 现场会表现为莫名卡顿。判据用<b>计划</b>（提交过的真相），不用 {@code PATCHED}（那是按方法键、会漏）。
     */
    static boolean chainAlreadyWiped(String className, String[] names, String[] descs) {
        if (className == null || className.isEmpty() || names == null || names.length == 0) {
            return false;
        }
        final HotfixPatchPlan.ClassPlan plan = HotfixPatchPlan.plan(className);
        if (plan.rev() <= 0L) {
            return false;
        }
        for (int i = 0; i < names.length; i++) {
            // 🆕 2026-11-21（P2）：键统一成 {@code 类#方法}（两条路径同一口径，见 chainWipeWriteClass）
            final HotfixPatchPlan.MethodPatch p = plan.get(className + "#" + names[i]);
            if (p == null || !HotfixAttackTier.METHOD.equals(p.tier)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 🆕 2026-11-21（P2）：<b>整链抹除写成功后，把它写进计划的那些方法在 wanted 里也升到同一档</b>。
     *
     * <p>为什么必须做：{@code wanted}（{@code CHAT_PATCHED_KEYS}）是 10 s 哨兵的<b>唯一</b>输入；
     * 它若还记着 {@code line} 档，哨兵那一趟就会拿 {@code line} 去 merge 同一张计划 ⇒ 把刚才
     * 整链抹除写下的 {@code method} 档<b>顶回去</b>（同一个方法，两条路径来回改档）。
     * 那正是"字节每一趟都不一样"的另一个来源，也是 {@code superseded} 一直冒的原因。
     *
     * <p>⚠️ 只<b>升级已有</b>的 wanted（不新建）：整链抹除的目标不需要 10 s 哨兵盯（它有自己的
     * {@code already_wiped} 幂等闸门），凭空给它们加哨兵只会多写字节。
     */
    static void chainMarkWanted(String className, String[] methods, String[] descs) {
        if (className == null || className.isEmpty() || methods == null || methods.length == 0) {
            return;
        }
        int upgraded = 0;
        synchronized (CHAT_PATCHED_KEYS) {
            for (int i = 0; i < methods.length; i++) {
                final Want w = CHAT_PATCHED_KEYS.get(className + "#" + methods[i]);
                if (w != null && !HotfixAttackTier.METHOD.equals(w.tier)) {
                    w.tier = HotfixAttackTier.METHOD;
                    upgraded++;
                }
            }
        }
        if (upgraded > 0) {
            log("chain_wipe_wanted class=" + token(className) + " upgraded=" + upgraded
                    + " to=" + HotfixAttackTier.METHOD
                    + " note=wanted跟着计划升档（否则10s哨兵会把它降级重写）");
        }
    }

    /** 按源级类名找<b>已加载</b>的 {@code Class}（整链抹除用；{@code null} = 没加载）。 */
    static Class<?> chainFindLoaded(Instrumentation inst, String className) {
        return findLoaded(inst, className);
    }

    /**
     * 整链抹除的准入判据（与普通补丁<b>同一份</b> {@link HotfixPatchWhitelist}，不另开一套）。
     *
     * <p>🔴 这里必须再判一次：帧只证明"这个类在作恶的链上"，不证明"它的 code source 是我们的／JDK 的"。
     */
    static HotfixPatchWhitelist.Decision chainGate(Class<?> cls, String className) {
        if (cls == null) {
            return null;
        }
        try {
            final HotfixPatchWhitelist.Probe probe = new HotfixPatchWhitelist.Probe();
            probe.className = className;
            probe.codeSource = HotfixForeignScan.codeSourceOf(cls);
            probe.modsRoot = modsRoot;
            probe.withLoader(cls.getClassLoader());
            probe.selfLoader = selfLoader;
            probe.selfCodeSource = selfCodeSource;
            probe.mcLoader = mcLoader;
            return HotfixPatchWhitelist.judge(probe);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 🆕 2026-11-21（最终版）：<b>攻击链检测的播报输入</b>（在 {@link #handle} 里、整链抹除之前调）。
     *
     * <p>只读：从这一拍的抓栈里组出整链（与 {@code chainWipeJob} 同一份构建），取
     * <b>顶端帧</b>（靠框架那一帧）／<b>写点帧</b>（链上最低端那一帧）／选靶结果三样交给播报；
     * 选靶失败（{@code res.ok=false}）⇒ <b>不播</b>（{@code CHAIN} 那行的"→ 选靶"没有内容，
     * 播出来只会误导）。
     */
    private static void chatChainOf(HotfixTriageVerdict verdict, HotfixTriageTap.Strike strike,
                                    String why, HotfixChainTriage.Result res) {
        try {
            if (strike == null || res == null || !res.ok() || res.pick == null) {
                return;
            }
            final List<HotfixTargetResolver.Target> picked = res.pick.targets();
            if (picked == null || picked.isEmpty() || picked.get(0) == null) {
                return;
            }
            final HotfixChainCapture.Chain chain = strike.chain != null && !strike.chain.isEmpty()
                    ? HotfixChainCapture.build(strike.chain, HotfixChainCapture.DEPTH)
                    : HotfixChainCapture.build(strike.frames, HotfixChainCapture.DEPTH);
            if (chain == null || chain.external == null || chain.external.isEmpty()) {
                return;
            }
            final List<String> segments = new ArrayList<String>(chain.external.size());
            for (int i = 0; i < chain.external.size(); i++) {
                final HotfixTargetResolver.Frame f = chain.external.get(i);
                if (f != null) {
                    segments.add(briefTarget(f.className + "#" + f.methodName));
                }
            }
            chatChain(chain.owners == null || chain.owners.isEmpty() ? "unknown" : chain.owners.get(0),
                    segments, why, briefTarget(picked.get(0).brief()));
        } catch (Throwable ignored) {
            // 播报坏了绝不许影响这一拍的判定与写入
        }
    }

    /**
     * 🆕 2026-11-21（①② 交接）：<b>白名单类上的外部 handler 交给别的线做手术</b>。
     *
     * <p>用户口径（本轮）：原版类上那个 {@code handler$<hash>$<modid>$<name>} 恰恰是真实战场，
     * 但<b>我们绝不在原版类上做任何抹除</b>（整类/整方法 RETURN 会崩服，也是越权）。
     * 手术在 {@link HotfixKeyClassRestore}：只把<b>外部</b> handler 的方法体清空，
     * 我们自己的 handler 与其余字节逐字节保留。
     *
     * <p>🔴 本方法<b>只做交接</b>：不读字节、不写字节、不自己调 {@code retransformClasses}
     * （那一次在它内部、由它自己算）。
     *
     * <p>⚠️ 它依赖另一条线的入口 {@code HotfixKeyClassRestore#restoreForTriage}：
     * 方法名/返回类型一旦改名，这里会<b>编译期</b>报错（而不是运行期静默失效）。
     *
     * @param className 原版类名（{@code net.minecraft.*}）
     * @param handlers  该类上的<b>外部</b> handler 方法名（只是交接内容，本方法不据此动手）
     * @return 一行读数（{@code ok=… handoff=keyclass_restore stripped=… ours_kept=… retransforms=… reason=…}）
     */
    static String chainHandoff(Instrumentation inst, String className, List<String> handlers) {
        if (inst == null) {
            return "ok=false handoff=skipped detail=no_instrumentation";
        }
        try {
            final Class<?> cls = findLoaded(inst, className);
            if (cls == null) {
                // 离线自证里的假类不在 JVM 里 ⇒ 如实写"类没加载"，绝不假装交接成功
                return "ok=false handoff=skipped detail=class_not_loaded handlers="
                        + (handlers == null ? 0 : handlers.size());
            }
            // 🆕 2026-11-21（P2 追加）：走**串行化入口**（与 patch／chain_wipe 同一把按类锁），
            //   绝不直接调 restoreForTriage —— 那是第三条独立 retransform，会与 ①② 交错。
            final HotfixKeyClassRestore.Result r = restoreForTriageSerialized(inst, cls, className);
            if (r == null) {
                return "ok=false handoff=keyclass_restore detail=null_result";
            }
            if (r.line != null && !r.line.isEmpty()) {
                log(r.line);            // 它自己的逐字行（keyclass_restore …）；与本行成对判读
            }
            return "ok=" + r.ok
                    + " handoff=keyclass_restore"
                    + " stripped=" + r.stripped
                    + " ours_kept=" + r.oursKept
                    + " retransforms=" + r.retransforms
                    + " reason=" + token(r.reason);
        } catch (Throwable t) {
            return "ok=false handoff=keyclass_restore detail=exception_" + simpleName(t);
        }
    }

    // ═══════════ 🆕 2026-11-21（P2 追加）：第三条写入路径（关键类还原）的串行化入口 ═══════════

    /**
     * <b>串行化</b>的关键类还原（{@code HotfixKeyClassRestore.restoreForTriage} 的唯一推荐入口）。
     *
     * <h2>为什么必须有这一层（用户 2026-11-21 口径）</h2>
     * <blockquote>
     *   「③ 不要合并进计划（它是另一套手术：清空外部 handler 方法体），但必须与 ①② 串行：
     *    加一个按类名的写锁/队列（同一类在同一个 LOCK 临界区内完成"patch/chain wipe 写"与"restore 写"，
     *    不许交错）」
     * </blockquote>
     * ⇒ 这里按固定顺序拿两把锁：<b>全局 {@link #LOCK} → 该类计划上的类锁</b>，
     * 与 {@code writePlanAttemptLocked} 完全同序；临界区里只做它那一趟 retransform。
     * 还原<b>不改计划</b>（它不是我们写下去的补丁），只记一笔写入者
     * （{@link HotfixPatchPlan#WRITER_RESTORE}）与写序号（{@code serial=}）。
     *
     * <p>⚠️ 它<b>不</b>重新实现手术：识别 handler／清空方法体／保留我们自己的字节，仍然全在
     * {@link HotfixKeyClassRestore} 里（那条线负责）。这里只排队。
     *
     * @return {@link HotfixKeyClassRestore.Result}（{@code null} ⇒ 它自己炸了，没动字节）
     */
    static HotfixKeyClassRestore.Result restoreForTriageSerialized(Instrumentation inst, Class<?> cls,
                                                                  String className) {
        return restoreSerialized(inst, cls, className, null, false);
    }

    /**
     * <b>串行化</b>的按名还原（手动 {@code /shanhai_add hotfix restore <类名>} 用；与上面同一把锁）。
     *
     * @param arg        全限定类名，或 {@code 类名#方法名}
     * @param methodHint 方法名提示（可 {@code null}）
     */
    static HotfixKeyClassRestore.Result restoreByNameSerialized(Instrumentation inst, String arg, String methodHint) {
        final String raw = arg == null ? "" : arg.trim();
        final int hash = raw.indexOf('#');
        final String className = hash >= 0 ? raw.substring(0, hash) : raw;
        // ⚠️ 原样把 arg 交给它自己解析（{@code 类名#方法} 的口径只允许有一处实现）
        return restoreSerialized(inst, null, className, arg, true);
    }

    /**
     * @param method     triage 路径的方法名提示
     * @param byNameArg  按名路径的原始入参（{@code byName=true} 时使用）
     */
    private static HotfixKeyClassRestore.Result restoreSerialized(Instrumentation inst, Class<?> cls,
                                                                 String className, String byNameArg,
                                                                 boolean byName) {
        final String cn = className == null ? "" : className;
        LOCK.lock();
        try {
            final HotfixPatchPlan.ClassPlan gate = HotfixPatchPlan.plan(cn);
            gate.lock();
            try {
                final long serial = gate.writeEnter();
                try {
                    final HotfixKeyClassRestore.Result r = byName
                            ? HotfixKeyClassRestore.restoreByName(inst, byNameArg, null)
                            : HotfixKeyClassRestore.restoreForTriage(inst, cls, byNameArg);
                    if (r == null) {
                        return null;
                    }
                    if (r.retransforms > 0) {
                        // 真的写了字节 ⇒ 记一笔写入者与写序号（计划本身不动：还原不是补丁）
                        gate.noteWriter(HotfixPatchPlan.WRITER_RESTORE);
                    }
                    // 🔴 三条路径共用这一行格式，{@code writer=} 一眼看出这一次是谁写的
                    log("class_write class=" + token(cn)
                            + " writer=" + HotfixPatchPlan.WRITER_RESTORE
                            + " ok=" + r.ok
                            + " retransforms=" + r.retransforms
                            + " stripped=" + r.stripped
                            + " ours_kept=" + r.oursKept
                            + " reason=" + token(r.reason)
                            + " serial=" + serial
                            + " interleaved=" + gate.interleaved()
                            + " by=" + (byName ? "name" : "triage"));
                    // 🆕 2026-11-21（最终版）：关键类还原单独一条播报
                    //   {@code RESTORED-KEY <类名> 摘掉对手 <N> 个回调（我方 <M> 个保留）}；
                    //   N=0 也播（文案换成"对手不是回调形态"）。同类 1 秒最多 1 条在 announce 里。
                    announceRestoredKey(cn, r.stripped, r.oursKept, r.handlers);
                    return r;
                } finally {
                    gate.writeExit();
                }
            } finally {
                gate.unlock();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 整链抹除用的 class 字节：<b>优先基线快照</b>（= 这个类第一次被我们看到时的样子），
     * 拿不到再退回磁盘／jar 里那一份。两个都拿不到 ⇒ {@code null}（那一类就不动，打 {@code no_class_bytes}）。
     *
     * <p>🆕 2026-11-21（P2，关键类还原那条线的同一修）：<b>三级回退已经收进
     * {@link HotfixBodyProbe#chainBytes(Class, String)} 这一份实现</b>里，本方法只是薄封装 ——
     * 整链抹除与关键类还原走的是同一份字节获取，不会出现"一条修好、另一条还在空跑"。
     */
    static byte[] chainClassBytes(Class<?> cls, String className) {
        try {
            final HotfixBodyProbe.Sourced src = HotfixBodyProbe.chainBytes(cls, className);
            return src == null || !src.ok() ? null : src.bytes;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * ③ 的写入动作：<b>一个类 ＋ 一组方法 = 一份子计划 = 一次 {@code retransform}</b>。
     *
     * <p>它就是 {@link #writePlanLocked} 的一层薄封装（复用同一套"整张计划一次写／全装或不装"），
     * 额外做两件事：
     * <ol>
     *   <li><b>方法键带描述符</b>（{@code 类#方法(描述符)}）—— 整类抹除时同名重载才会被当成两条，
     *       否则 {@code foo()V} 与 {@code foo(I)V} 会撞成一个键、只废掉其中一个；</li>
     *   <li>写成功之后把每条都记进 {@link #PATCHED}（{@code undo} 的唯一依据，也是"已抹过就不再抹"的判据）。</li>
     * </ol>
     *
     * @return 读数串 {@code ok=… retransforms=… methods=… rev=… detail=…}（离线自检逐字比对）
     */
    static String chainWipeWriteClass(Instrumentation inst, Class<?> cls, String className,
                                      String[] methods, String[] descs, String mainKey) {
        LOCK.lock();
        try {
            if (inst == null || cls == null || methods == null || methods.length == 0) {
                return "ok=false retransforms=0 methods=0 rev=0 detail=write_no_target";
            }
            boolean modifiable;
            try {
                modifiable = inst.isModifiableClass(cls);
            } catch (Throwable ignored) {
                modifiable = false;
            }
            if (!modifiable) {
                return "ok=false retransforms=0 methods=0 rev=0 detail=gate_not_modifiable";
            }
            ensureTransformer(inst);
            final PatchTransformer tx = transformer;
            if (!transformerAdded || tx == null) {
                return "ok=false retransforms=0 methods=0 rev=0 detail=gate_transformer_unavailable";
            }
            final List<PlanEntry> plan = new ArrayList<>(methods.length);
            for (int i = 0; i < methods.length; i++) {
                final String desc = descs != null && i < descs.length && descs[i] != null ? descs[i] : "";
                // 🆕 2026-11-21（P2）：键与普通补丁<b>同一个口径</b>（{@code 类#方法}，不带描述符）。
                //   现场：两条路径对同一个方法各自造键（一个带描述符、一个不带）⇒ 计划里出现两条
                //   （{@code methods=4} 而实际只有 3 个方法），transformer 还会把同一个方法套两次。
                //   描述符只作为字段携带（谁先写进来就把这个事实留给后来者，见 HotfixPatchPlan#merge）。
                plan.add(new PlanEntry(className + "#" + methods[i], methods[i], desc, -1,
                        HotfixAttackTier.METHOD));
            }
            final List<Class<?>> targets = new ArrayList<>(1);
            targets.add(cls);
            final PlanWrite w = writePlanLocked(inst, tx, className, targets, plan, mainKey,
                    HotfixPatchPlan.REASON_CHAIN_WIPE, HotfixPatchPlan.WRITER_CHAIN_WIPE);
            if (w.ok) {
                // 🆕 2026-11-21（P2）：整链抹除<b>也是</b>这一类的计划里的一等公民 ——
                //   把手上有过的 wanted 条目升到同一档，否则 10 s 哨兵会按旧档（line）把它<b>降级</b>
                //   重写一遍（同一类同一方法，两条路径来回改档 = 永远对不上的字节）。
                chainMarkWanted(className, methods, descs);
                // 记账（undo 的唯一依据）：每条一个 Patch，键与计划里那条逐字相同（带描述符）
                final byte[] base = HotfixBaseline.bytes(internalName(className));
                final HotfixPatchReadback.Fingerprint origFp =
                        w.origFp == null ? HotfixPatchReadback.Fingerprint.NONE : w.origFp;
                final HotfixPatchReadback.Fingerprint patchedFp =
                        w.patchedFp == null ? HotfixPatchReadback.Fingerprint.NONE : w.patchedFp;
                for (int i = 0; i < methods.length; i++) {
                    final String desc = descs != null && i < descs.length && descs[i] != null ? descs[i] : "";
                    final String key = className + "#" + methods[i] + desc;
                    if (PATCHED.containsKey(key)) {
                        continue;               // 之前抹过（幂等）；不要覆盖最早那份记录
                    }
                    PATCHED.put(key, new Patch(
                            new Class<?>[]{cls},
                            new byte[][]{base},
                            new HotfixPatchReadback.Fingerprint[]{origFp},
                            new HotfixPatchReadback.Fingerprint[]{patchedFp},
                            internalName(className), className, methods[i], desc, -1,
                            w.origLen, w.newLen, w.match, w.defaultKind, System.currentTimeMillis(),
                            HotfixAttackTier.METHOD, 0, ""));
                }
            }
            return "ok=" + w.ok + " retransforms=" + w.retransforms + " methods=" + w.methods
                    + " rev=" + w.rev + " detail=" + w.detail
                    // 🆕 2026-11-21（P2）：这个类被几条写入路径写过（both = 这一份计划里既有普通补丁的目标、
                    //   又有整链抹除的目标）—— 一行就能判"这次是不是合并写"
                    + " writer=" + (w.writer == null || w.writer.isEmpty() ? HotfixPatchPlan.WRITER_CHAIN_WIPE : w.writer);
        } catch (Throwable t) {
            return "ok=false retransforms=0 methods=0 rev=0 detail=exception_" + simpleName(t);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 🆕 2026-11-21（③ undo 自证）：<b>同一个类里的 N 条补丁一起撤</b> —— 一次 {@code retransform}。
     *
     * <h2>为什么不能拿 {@link #undo(String)} 连调 N 次</h2>
     * {@code undo} 的语义是"撤一条、把同类剩下的重新套上"（{@code remaining} 非空就再写一遍），
     * 所以 N 条会变成 <b>N 次</b> {@code retransformClasses}（每次还带一次整类重写）。
     * 而"整链抹除"一次给一个类塞 N 条 ⇒ 撤它也必须是一次：一类一份计划，进和出对称。
     *
     * <h2>机制（与 {@code undoLocked} 最后一条相同）</h2>
     * 账本删净 ⇒ {@code PATCHED} 里这一类一条不剩；计划 {@link HotfixPatchPlan#clearPlan} 整张清
     * （{@code rev} 归零）；再<b>一次</b> {@code retransformClasses} —— 这一次 transformer 的
     * {@code pending=null}（全 null）⇒ 类退回<b>初始字节</b>（P2 实测口径）。失败时才退回
     * "把存下的原字节显式送回"（{@code restore_stored_bytes}，次数如实记 2）。
     *
     * @param inst       {@link Instrumentation}（{@code null} ⇒ 拒绝）
     * @param className  源级类名
     * @return 读数 {@code ok=… keys=… rev_before=… rev_after=0 retransforms=… mode=…}
     */
    static String undoClassAll(Instrumentation inst, String className) {
        final String cn = className == null ? "" : className;
        if (inst == null || cn.isEmpty()) {
            return "ok=false keys=0 rev_before=0 rev_after=0 retransforms=0 detail=no_instrumentation";
        }
        LOCK.lock();
        try {
            // ---- ① 账本：这一类在册的键全摘掉（先把类表与原字节备份出来）
            final List<String> keys = new ArrayList<>(4);
            Class<?>[] classes = null;
            byte[] original = null;
            for (Iterator<Map.Entry<String, Patch>> it = PATCHED.entrySet().iterator(); it.hasNext(); ) {
                final Map.Entry<String, Patch> e = it.next();
                final Patch p = e.getValue();
                if (p == null || e.getKey() == null || !cn.equals(p.className)) {
                    continue;
                }
                keys.add(e.getKey());
                if (classes == null) {
                    classes = p.classes;
                    original = p.originalOf(0);
                }
                it.remove();
            }
            if (keys.isEmpty()) {
                return "ok=false keys=0 rev_before=0 rev_after=0 retransforms=0 detail=not_patched";
            }
            for (int i = 0; i < keys.size(); i++) {
                HotfixPatchEffect.forget(keys.get(i));
                retireWatch(keys.get(i));
            }
            synchronized (CHAT_PATCHED_KEYS) {
                for (int i = 0; i < keys.size(); i++) {
                    CHAT_PATCHED_KEYS.remove(keys.get(i));
                    COUNTER_AT_PATCH.remove(keys.get(i));
                }
                boolean left = false;
                for (Iterator<Want> it = CHAT_PATCHED_KEYS.values().iterator(); it.hasNext(); ) {
                    final Want w = it.next();
                    if (w != null && cn.equals(w.className)) {
                        left = true;
                        break;
                    }
                }
                if (!left) {
                    synchronized (KEEPS) {
                        KEEPS.remove(cn);
                    }
                }
            }
            // ---- ② 计划整张清（rev 归零：这是"这一类彻底不归我们管"的唯一真相）
            final long revBefore = HotfixPatchPlan.plan(cn).rev();
            HotfixPatchPlan.clearPlan(cn);
            // ---- ③ 一次 retransform：全 null ⇒ 退回初始字节
            final PatchTransformer tx = transformer;
            if (tx == null) {
                return "ok=false keys=" + keys.size() + " rev_before=" + revBefore
                        + " rev_after=0 retransforms=0 detail=transformer_unavailable";
            }
            int retransforms = 0;
            String mode = "retransform_original";
            boolean ok = false;
            if (classes != null && classes.length > 0) {
                try {
                    tx.pending = null;
                    inst.retransformClasses(classes);
                    retransforms++;
                    ok = true;
                } catch (Throwable first) {
                    // 回退：把当时存下的原字节显式送回去（只有"这一类就剩这一份"时才拿得到）
                    if (original != null) {
                        final Pending restore = new Pending(internalName(cn), "", null, -1);
                        restore.restoreBytes = original;
                        tx.pending = restore;
                        try {
                            inst.retransformClasses(classes);
                            retransforms++;
                            ok = true;
                            mode = "restore_stored_bytes";
                        } catch (Throwable second) {
                            mode = "both_failed_" + simpleName(first) + "_" + simpleName(second);
                        } finally {
                            tx.pending = null;
                        }
                    } else {
                        mode = "retransform_failed_" + simpleName(first);
                    }
                } finally {
                    tx.pending = null;
                }
            }
            if (ok) {
                HotfixBaseline.unstamp(internalName(cn));
            }
            final long revAfter = HotfixPatchPlan.plan(cn).rev();
            log("auto_counter_undo_class class=" + token(cn)
                    + " keys=" + keys.size()
                    + " rev_before=" + revBefore
                    + " rev_after=" + revAfter
                    + " retransforms=" + retransforms
                    + " mode=" + token(mode)
                    + " ok=" + ok);
            return "ok=" + ok + " keys=" + keys.size() + " rev_before=" + revBefore
                    + " rev_after=" + revAfter + " retransforms=" + retransforms + " mode=" + token(mode);
        } catch (Throwable t) {
            return "ok=false keys=0 rev_before=0 rev_after=0 retransforms=0 detail=exception_" + simpleName(t);
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 🆕 2026-11-21：<b>离线自证入口</b>（包内可见；游戏里<b>没有任何调用点</b>）。
     *
     * <p>它只做一件事：用给定的 {@link Instrumentation} 把"同一个类的 N 个方法"当成
     * <b>一份计划</b>写一次，并把读数原样交回来（{@code retransforms=} 真的调了几次
     * {@code retransformClasses}）。{@code temp\hotfix\p2-plan\} 用它证明
     * "改前 3 次 / 改后 1 次"。
     *
     * <p>为什么不把这段逻辑放进自检主流程：自检跑在游戏里（{@code /shanhai_add hotfix selftest}），
     * 而这里要的是"离线、无 MC、可重复、可读读数"—— 两条线不能混。
     */
    static String selftestPlanWrite(Instrumentation inst, Class<?>[] targets, String className,
                                    String[] methods, String[] descs, int[] lineHints, String[] tiers,
                                    String mainKey, String reason) {
        LOCK.lock();
        try {
            ensureTransformer(inst);
            if (transformer == null || !transformerAdded) {
                return "ok=false detail=transformer_unavailable";
            }
            final List<PlanEntry> plan = new ArrayList<>(methods == null ? 0 : methods.length);
            for (int i = 0; methods != null && i < methods.length; i++) {
                plan.add(new PlanEntry(className + "#" + methods[i], methods[i],
                        descs == null ? null : descs[i],
                        lineHints == null ? -1 : lineHints[i],
                        tiers == null ? HotfixAttackTier.METHOD : tiers[i]));
            }
            final List<Class<?>> list = new ArrayList<>(targets == null ? 0 : targets.length);
            for (int i = 0; targets != null && i < targets.length; i++) {
                list.add(targets[i]);
            }
            final PlanWrite w = writePlanLocked(inst, transformer, className, list, plan, mainKey, reason);
            return "ok=" + w.ok + " retransforms=" + w.retransforms + " rev=" + w.rev
                    + " methods=" + w.methods + " new_len=" + w.newLen + " sha=" + w.newSha
                    + " base=" + (w.fromSnapshot ? "orig" : "live") + " detail=" + w.detail
                    // 🆕 2026-11-21（回归修复）：这一趟是不是"升级之后的重试"、结果用的哪一份计划
                    + " reason=" + w.reason + " escalated=" + w.escalated;
        } catch (Throwable t) {
            return "ok=false detail=exception_" + simpleName(t);
        } finally {
            LOCK.unlock();
        }
    }

    /** 离线自检用：把进程内那几张表清干净（游戏里<b>没有任何调用点</b>）。 */
    static void selftestReset() {
        LOCK.lock();
        try {
            PATCHED.clear();
            synchronized (CHAT_PATCHED_KEYS) {
                CHAT_PATCHED_KEYS.clear();
            }
            synchronized (PROBE_LOCK) {
                WATCHES.clear();
            }
            synchronized (KEEPS) {
                KEEPS.clear();
            }
            synchronized (COUNTER_AT_PATCH) {
                COUNTER_AT_PATCH.clear();
            }
            HotfixPatchPlan.reset();
            HotfixPatchEffect.reset();
            clearEscalated();          // 🆕 升级记账跟计划一起复位（离线自检要能重演"第一次升级"）
            HotfixBaseline.reset();
            synchronized (BASELINE_LOGGED) {
                BASELINE_LOGGED.clear();
            }
            HotfixSelfWindow.reset();
            // 🆕 2026-11-21（③④）：整链抹除的读数与 frame_owner 去重表也一起复位
            //   （离线自检要在同一个 JVM 里重演"开关 off / 5 个类各一次"）
            HotfixChainWipe.resetCounters();
            HotfixFrameOwner.resetLogOnce();
            // transformer 保留（离线桩里它只是被回调）；pendeing 必须清空
            final PatchTransformer tx = transformer;
            if (tx != null) {
                tx.pending = null;
            }
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * 🆕 唯一的 transformer：<b>只有当前 {@link Pending} 指到的那个类、那个方法</b>会被改，别的一律 {@code null}。
     *
     * <p>{@code canRetransform=true} 注册（少了它，retransform 不会回调这里）。
     * 失败时返回 {@code null}（＝"这个类我不管"），绝不返回"半信半疑"的字节 ——
     * 那会让 {@code retransformClasses} 抛 {@code UnsupportedOperationException}。
     */
    private static final class PatchTransformer implements ClassFileTransformer {

        volatile Pending pending;

        /** 被回调过几次（判读用：{@code calls=0} 说明 JVM 根本没回调，通常意味着类不可改）。 */
        volatile int calls;

        @Override
        @SuppressWarnings("deprecation")
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            final Pending p = pending;
            if (p == null || classfileBuffer == null) {
                return null;
            }
            if (p.internalName == null || !p.internalName.equals(className)) {
                return null;
            }
            calls++;
            if (p.restoreBytes != null) {
                return p.restoreBytes;   // 还原模式：原样送回
            }
            // 🆕 按类累计：一趟 retransform 里把"这个类所有已登记的方法"一起套上。
            //   为什么必须一起：retransform 的输入是【初始基线】(P1)，我们 return 的字节会**整体替换**
            //   类当前的定义 ⇒ 只套一个方法时，同类别的方法就被静默撤掉了(P3)。
            //   对照实测：同时套两个 ⇒ 两个默认值同时成立(P4)。
            final List<PlanEntry> plan = p.plan;
            if (!plan.isEmpty()) {
                p.origBytes = classfileBuffer.length;
                p.origFp = HotfixPatchReadback.fingerprint(classfileBuffer);
                p.origSha = HotfixBaseline.sha16(classfileBuffer);
                p.original = classfileBuffer.clone();
                // 🆕 2026-11-21（根因②）：**首次见到这个类时抓住原始 bytes**（不覆盖、不更新）。
                //   为什么在这里抓：retransform 交给 transformer 的永远是【初始基线】(P1)，
                //   所以第一次回调收到的 classfileBuffer 就是原样的类字节。
                HotfixBaseline.ensure(p.internalName, classfileBuffer, System.currentTimeMillis());
                final byte[] snap = HotfixBaseline.bytes(p.internalName);
                p.fromSnapshot = snap != null;
                // 🔴 起点：有基线就从基线出发（禁止在"当前字节"上叠加 —— 长度单调变短就是那么来的）
                byte[] current = snap != null ? snap : classfileBuffer;
                // 🔴 baseSha 必须记【摘之前】的那份：{@code patch_rebuild sha_before} 的既有语义是
                //   "重建的起点（基线快照/初始字节）"，与 {@code patch_baseline sha=} 对得上。
                p.baseSha = HotfixBaseline.sha16(current);
                // 🆕 2026-11-21（内联摘除）：套计划【之前】先把外部 handler 的方法体清空。
                //   🔴 必须在套计划之前：识别 handler 要看"关键方法里的 invokestatic"，
                //      而 method 档会把那个方法体换成 RETURN，调用点就没了。
                //   摘不到 ⇒ 原样放行（inline == null），绝不让热改失败。
                //   🔴 跳过"这一趟要打补丁的方法"：目标本身若是外部 handler，先摘空会让
                //      HotfixMethodBodyPatch 报 no_code、整次 abort（边界由 P2InlineRestoreCheck 的 E1 验）。
                final java.util.Set<String> patchTargets = new java.util.LinkedHashSet<String>();
                for (int pi = 0; pi < plan.size(); pi++) {
                    final PlanEntry pe = plan.get(pi);
                    if (pe != null && pe.methodName != null && !pe.methodName.isEmpty()) {
                        patchTargets.add(pe.methodName);
                    }
                }
                final HotfixKeyClassRestore.Inline inline =
                        HotfixKeyClassRestore.stripForWrite(current, patchTargets);
                if (inline != null) {
                    current = inline.bytes;
                    p.inlineStripped = inline.stripped;
                    p.inlineHandlers = inline.handlers;
                    p.inlineOurs = inline.oursKept;
                }
                int applied = 0;
                int sites = 0;
                final StringBuilder siteList = new StringBuilder(48);
                String firstReason = null;
                for (int i = 0; i < plan.size(); i++) {
                    final PlanEntry e = plan.get(i);
                    // 🆕 两级：line = 只废"写受保护状态的那一条指令"；method = 整方法换安全默认返回
                    final boolean lineTier = HotfixAttackTier.LINE.equals(e.tier);
                    final String methodDesc = e.methodDesc.isEmpty() ? null : e.methodDesc;
                    if (lineTier) {
                        final HotfixLinePatch.Result r =
                                HotfixLinePatch.patch(current, e.methodName, methodDesc, e.lineHint, loader);
                        if (!r.ok()) {
                            // 🔴 整张计划要么全装、要么全不装：一条失败 ⇒ 这一个类一个字节都不交
                            abortPending(p, e.key, r.reason, firstReason);
                            return null;
                        }
                        current = r.bytes;      // 链式：上一笔改过的字节是下一笔的输入
                        applied++;
                        sites += r.sites;
                        if (siteList.length() > 0) {
                            siteList.append(',');
                        }
                        siteList.append(r.siteList);
                        p.perMethod.put(e.key, "ok:line:" + r.siteList);
                        if (e.key.equals(p.mainKey)) {
                            p.desc = r.methodDesc == null ? "" : r.methodDesc;
                            p.match = r.match;
                            p.defaultKind = "line:" + r.sites;
                        }
                        // line 档也插只计数探针：方法体没被换掉，但"跑没跑过"仍然是存活哨兵的判据
                        current = HotfixProbeCounter.instrument(current, e.methodName, r.methodDesc, e.key, loader);
                        continue;
                    }
                    final HotfixMethodBodyPatch.Result r = HotfixMethodBodyPatch.patch(
                            current, e.methodName, methodDesc, e.lineHint);
                    if (!r.ok()) {
                        abortPending(p, e.key, r.reason, firstReason);
                        return null;
                    }
                    current = r.bytes;          // 链式：上一笔改过的字节是下一笔的输入
                    applied++;
                    p.perMethod.put(e.key, "ok:" + r.defaultKind);
                    // 🆕 2026-11-08 ③：给"已换成安全默认返回"的方法体最前面插一条**只计数**的探针
                    //   （{@code LDC key; INVOKESTATIC bump(String)V}，无分支、不动 StackMapTable）。
                    //   它的用处：{@code patch_probe result=ok} 才能分出"真被调用过(exercised)"与"没人来打"；
                    //   keepalive 才能判"跑的不是我们那份字节"。插不进去就原样返回（绝不半份、绝不抛）。
                    current = HotfixProbeCounter.instrument(current, e.methodName, r.methodDesc, e.key, loader);
                    if (e.key.equals(p.mainKey)) {
                        p.desc = r.methodDesc == null ? "" : r.methodDesc;
                        p.match = r.match;
                        p.defaultKind = r.defaultKind;
                    }
                }
                p.appliedMethods = applied;
                p.sites = sites;
                p.siteList = siteList.toString();
                p.reason = firstReason == null ? "ok" : firstReason;
                if (applied == 0) {
                    // 一个都没套上 ⇒ 这个类我不管（绝不交半份）
                    abortPending(p, p.mainKey, p.reason, firstReason);
                    return null;
                }
                p.bytes = current;
                p.newBytes = current.length;
                p.newSha = HotfixBaseline.sha16(current);
                p.patchedFp = HotfixPatchReadback.fingerprint(current);
                return current;
            }
            // ---- 老路径（一次只装一个方法；{@code undo} 的"送回原字节"也走它以外的地方）
            //   🆕 2026-11-21：同样从基线出发（口径统一：任何写字节都不许在"当前字节"上叠加）
            p.origSha = HotfixBaseline.sha16(classfileBuffer);
            HotfixBaseline.ensure(p.internalName, classfileBuffer, System.currentTimeMillis());
            final byte[] snapOne = HotfixBaseline.bytes(p.internalName);
            p.fromSnapshot = snapOne != null;
            final byte[] baseOne = snapOne != null ? snapOne : classfileBuffer;
            p.baseSha = HotfixBaseline.sha16(baseOne);
            final HotfixMethodBodyPatch.Result result =
                    HotfixMethodBodyPatch.patch(baseOne, p.methodName, p.methodDesc, p.lineHint);
            p.reason = result.reason;
            p.origBytes = classfileBuffer.length;
            // 🆕 三份指纹里的第一份：**改之前** transformer 收到的字节（= 被织过的原版）
            p.origFp = HotfixPatchReadback.fingerprint(classfileBuffer);
            if (!result.ok()) {
                return null;
            }
            // 🔴 先存原字节再交新字节（undo 的第二道保险就是它）
            p.original = classfileBuffer.clone();
            p.bytes = result.bytes;
            p.newBytes = result.bytes.length;
            p.newSha = HotfixBaseline.sha16(result.bytes);
            // 🆕 第二份指纹：我们即将写下去的那份（回读时拿 live 跟它比）
            p.patchedFp = HotfixPatchReadback.fingerprint(result.bytes);
            p.desc = result.methodDesc == null ? "" : result.methodDesc;
            p.match = result.match;
            p.defaultKind = result.defaultKind;
            return result.bytes;
        }
    }

    /** 自检的步数账本（每步一行 {@code selftest step=… pass=…}）。 */
    private static final class Steps {

        int steps;
        int failed;

        void check(String step, boolean pass, String detail) {
            steps++;
            if (!pass) {
                failed++;
            }
            log("selftest step=" + step + " pass=" + pass + " detail=" + token(detail));
        }

        boolean finish(long t0) {
            final boolean pass = failed == 0 && steps > 0;
            log("selftest step=auto_counter pass=" + pass + " steps=" + steps + " failed=" + failed
                    + " elapsed_ms=" + (System.currentTimeMillis() - t0));
            return pass;
        }
    }
}
