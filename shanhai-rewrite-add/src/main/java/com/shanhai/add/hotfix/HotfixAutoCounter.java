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
 * 且来自 {@code mods\}} 的家伙"的方法体换成安全默认返回。<b>默认关。</b>
 *
 * <h2>三道门槛（缺一不可，任何一道不过就只记日志）</h2>
 * <pre>
 *   ① 开关  auto=false（默认）  ⇒ /shanhai_add hotfix auto on|off
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
 * 且 {@code violation_stack … first_external=…EntityUtil#forceSetHealth:375}。
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
    }

    // ═══════════════════════════════ 🆕 热改播报（2026-11-08，用户点单） ═══════════════════════════════
    //
    // 用户口径（逐字）：把「主动热改废掉敌方代码」这件事播报到玩家聊天栏，三条样例：
    //   [创始现实修改模块] PATCHED examplemod EntityUtil#forceSetHealth -> RETURN (health)
    //   [创始现实修改模块] PATCH_REFUSED examplemod EntityUtil#forceSetHealth (gate_already_patched)
    //   [创始现实修改模块] PATCH_REVERTED examplemod EntityUtil#forceSetHealth (observed_only)
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

    /** 默认值：{@code auto} 默认<b>关</b>（规格）。 */
    public static final boolean DEFAULT_AUTO = false;

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

    private static volatile Log logSink;
    private static volatile String modsRoot = "";
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
    }

    /** 现在开没开。 */
    public static boolean auto() {
        return AUTO.get();
    }

    /**
     * 开关（{@code /shanhai_add hotfix auto on|off}）。
     *
     * <p>只改内存里的一个布尔 —— <b>不落配置</b>（本轮写入范围不含配置类）。默认 {@code false}。
     *
     * @return 改完之后的值
     */
    public static boolean setAuto(boolean on) {
        final boolean before = AUTO.getAndSet(on);
        log("auto_switch auto=" + on + " before=" + before + " patched=" + patchedCount()
                + " note=只改内存不落配置");
        return on;
    }

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
                    HotfixAutoCounterProbe.class.getName(), "flag", "()Z", -1, false);
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
        LOCK.lock();
        try {
            targets = PATCHED.isEmpty() ? "-" : String.join(",", PATCHED.keySet());
        } finally {
            LOCK.unlock();
        }
        return "auto=" + AUTO.get() + "（默认 " + DEFAULT_AUTO + "，运行时开关，不落配置）"
                + " patched=" + patchedCount()
                + " transformer_added=" + transformerAdded
                + " mods_root=" + (modsRoot.isEmpty() ? "?" : modsRoot)
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
        final HotfixTargetResolver.Target target = HotfixTargetResolver.resolve(strike.frames);
        if (target == null) {
            refuse("?", "?", why,
                    strike.frames.isEmpty() ? "gate_no_strike_frame" : "gate_all_frames_whitelisted",
                    strikeInfo);
            return;
        }
        final String key = target.brief();
        // ③-b 白名单 ＋ mods 来源（在 apply() 里判，因为它要 Class 对象）
        final Instrumentation inst = HotfixHolder.instrumentation();
        if (inst == null) {
            refuse(key, "?", why, "gate_no_instrumentation", strikeInfo);
            return;
        }
        LOCK.lock();
        try {
            if (PATCHED.containsKey(key)) {
                // 这条是"已经改过了"的重复拒绝 —— 违规一多它就会刷屏，所以走 1/s 限流。
                refuseThrottled(key, "?", why, "gate_already_patched", strikeInfo);
                return;
            }
        } finally {
            LOCK.unlock();
        }
        final Class<?> cls = findLoaded(inst, target.className);
        if (cls == null) {
            refuse(key, "?", why, "gate_class_not_loaded", strikeInfo);
            return;
        }
        final Outcome outcome;
        LOCK.lock();
        try {
            // 再判一次（这一小段里可能有别的线程刚改过）
            if (PATCHED.containsKey(key)) {
                refuseThrottled(key, "?", why, "gate_already_patched", strikeInfo);
                return;
            }
            outcome = apply(inst, new Class<?>[]{cls}, target.className, target.methodName, null,
                    target.line, true);
        } finally {
            LOCK.unlock();
        }
        if (outcome.gateRefused()) {
            refuse(key, "?", why, outcome.detail, strikeInfo);
            return;
        }
        if (!outcome.ok) {
            FAIL_COUNT.incrementAndGet();
            log(line(key, "?", why, "failed", outcome.detail, outcome.origBytes, outcome.newBytes,
                    "match=" + outcome.match + " " + strikeInfo));
            return;
        }
        PATCH_COUNT.incrementAndGet();
        // 🆕 诊断行（Instrumentation 侧的事实）＋ 探针窗口（"这次到底生效了没有"从这里开始盯）
        logPatchApply(key, outcome, 0);
        notePatched(key, target.className, target.methodName, System.currentTimeMillis());
        log(line(key, outcome.desc, why, "patched", "ok", outcome.origBytes, outcome.newBytes,
                "match=" + outcome.match + " default=" + outcome.defaultKind
                        + " frames_skipped=" + target.framesSkipped
                        + " elapsed_ms=" + outcome.elapsedMillis + " " + strikeInfo));
        // 🆕 2026-11-08：播报成功 —— 每个真的套上去的方法一条 PATCHED（只发一次；见 CHAT_PATCHED_KEYS）
        announcePatched(outcome, why);
        // 🆕 读回取证：账本说 patched 只是一句话；这里去 JVM 里把"类里那份字节"读出来比对。
        //   同一 job、延迟 ~1 tick、只做一次、不常驻 —— 判出 reverted 也只记一行，绝不自动重打。
        readbackAfterPatch(inst, key, outcome, why);
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
                                 String methodDesc, int lineHint, boolean checkGates) {
        final Outcome out = new Outcome(className + "#" + methodName);
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
                final HotfixPatchWhitelist.Decision d = HotfixPatchWhitelist.judge(className,
                        codeSourceOf(c), modsRoot, loaderNameOf(c));
                // 🔴 判据必须能自证"这个类为什么被接受/被拒绝"：每次判定都留一行。
                //    raw/normalized 用引号包住、**按原样打**（中文/空格都不动、不做 URL-decode）——
                //    少了原样字符串就没法回答"到底判的是哪条路径"，decode 过的半截路径只会误导排查。
                log("whitelist_" + (d.allowed ? "accepted" : "refused")
                        + " cls=" + token(className)
                        + " raw=\"" + verbatim(d.raw) + "\""
                        + " normalized=\"" + verbatim(d.normalized) + "\""
                        + " reason=" + token(d.reason)
                        + " loader=" + token(d.loader)
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
        //   forceSetHealth 不在账本里 ⇒ 13:43:11 打 playDamageSounds 时又只套自己一个，把 forceSetHealth 顶掉。
        //   ⇒ 判据改成 wanted 集合（"曾经 patch 过、没显式 undo"就永久在）：这才是"重建时该套的全集"。
        final String mainKey = className + "#" + methodName;
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
                cumulativeBefore++;
                planned.add(e.getKey());
                plan.add(new PlanEntry(e.getKey(), w.methodName, w.desc, w.lineHint));
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
            cumulativeBefore++;
            planned.add(e.getKey());
            plan.add(new PlanEntry(e.getKey(), old.methodName, old.desc, old.lineHint));
        }
        plan.add(new PlanEntry(mainKey, methodName, methodDesc, lineHint));
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
        // ---- ④ 逐个 retransform（主目标失败 ⇒ 整次失败；其余失败只记账）
        final List<Class<?>> done = new ArrayList<>(accepted.size());
        final List<byte[]> originals = new ArrayList<>(accepted.size());
        // 🆕 每个类各自两份指纹（读回取证用；和 done/originals 一一对齐）
        final List<HotfixPatchReadback.Fingerprint> origFps = new ArrayList<>(accepted.size());
        final List<HotfixPatchReadback.Fingerprint> patchedFps = new ArrayList<>(accepted.size());
        Pending primary = null;
        for (int i = 0; i < accepted.size(); i++) {
            final Class<?> c = accepted.get(i);
            final Pending pending = new Pending(internalName(className), methodName, methodDesc, lineHint,
                    mainKey, plan);
            tx.pending = pending;
            final long t0 = System.nanoTime();
            try {
                inst.retransformClasses(c);
            } catch (Throwable t) {
                tx.pending = null;
                if (i == 0) {
                    out.detail = "retransform_" + simpleName(t);
                    out.elapsedMillis += (System.nanoTime() - t0) / 1_000_000L;
                    return out;
                }
                continue;
            } finally {
                tx.pending = null;
            }
            out.elapsedMillis += (System.nanoTime() - t0) / 1_000_000L;
            if (i == 0) {
                primary = pending;
                out.origBytes = pending.origBytes;
                out.newBytes = pending.newBytes;
                out.match = pending.match;
                out.desc = pending.desc == null ? "" : pending.desc;
                out.defaultKind = pending.defaultKind == null ? "" : pending.defaultKind;
                out.appliedMethods = pending.appliedMethods;
                // 主目标自己没套上 ⇒ 这一次不算成功（哪怕同类别的方法套上了）
                final String mine = pending.perMethod.get(mainKey);
                if (pending.bytes == null || mine == null || !mine.startsWith("ok")) {
                    out.detail = "patch_" + (pending.reason == null ? "not_called" : pending.reason);
                    return out;
                }
                // 记下"一起套、但没套上"的那些（它们才真的被撤了；readback 只许报这一个集合）
                for (Iterator<Map.Entry<String, String>> it = pending.perMethod.entrySet().iterator();
                     it.hasNext(); ) {
                    final Map.Entry<String, String> e = it.next();
                    if (e.getValue() != null && e.getValue().startsWith("ok")) {
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
                        continue;
                    }
                    out.planFailedKeys.add(e.getKey());
                }
            }
            done.add(c);
            originals.add(pending.original);
            origFps.add(pending.origFp == null ? HotfixPatchReadback.Fingerprint.NONE : pending.origFp);
            patchedFps.add(pending.patchedFp == null ? HotfixPatchReadback.Fingerprint.NONE : pending.patchedFp);
        }
        out.classesRetransformed = done.size();
        if (out.classesRetransformed == 0) {
            out.detail = "patch_not_retransformed";
            return out;
        }
        // 记进"我改过谁"的账本（undo 的唯一依据）。⚠️ 调用方持有 LOCK（见 apply 的注释）。
        final Patch record = new Patch(done.toArray(new Class<?>[0]), originals.toArray(new byte[0][]),
                origFps.toArray(new HotfixPatchReadback.Fingerprint[0]),
                patchedFps.toArray(new HotfixPatchReadback.Fingerprint[0]),
                internalName(className), className, methodName, out.desc, lineHint,
                out.origBytes, out.newBytes, out.match, out.defaultKind, System.currentTimeMillis());
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
            remaining.add(new PlanEntry(e.getKey(), keep.methodName, keep.desc, keep.lineHint));
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
     * {@code violation_stack … first_external=…EntityUtil#forceSetHealth:375} —— 它还在栈上，
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
                    final HotfixPatchWatch w = it.next().getValue();
                    if (w == null || w.givenUp()) {
                        continue;
                    }
                    if (!containsTarget(frames, w)) {
                        continue;
                    }
                    // ⚠️ 传的是【抓栈时刻】而不是 now：队列里躺着的老现场（patch 之前抓的）不算证据
                    w.noteHit(strike.atMillis, verdict.why());
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
                // ① 先 undo 回原字节。⚠️ retireWatch=false —— 反制的账本不能被清空（否则上限失效）
                undone = undoLocked(key, false);
                // ② 重新取【所有同名 Class】（应对"当初改的不是正在跑的那个 Class"）
                final List<Class<?>> all = findLoadedAll(inst, cn);
                final Class<?>[] targets = all.isEmpty() ? previous : all.toArray(new Class<?>[0]);
                // ③ 重新判白名单（apply 里逐个判、逐个留行）＋重新 patch
                final Outcome o = apply(inst, targets, cn, mn, desc.isEmpty() ? null : desc, lineHint, true);
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
                + " undone=" + undone
                + " classes_retransformed=" + classesDone
                + " since_probe_ms=" + a.sincePatchMillis);
        // 🆕 重打也是一次 patch ⇒ 同样要读回（读回只出 verdict，不触发任何动作，包括不触发重打）
        if (ok) {
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
     * 13:22:30 已经 patch 的 {@code forceSetHealth}，到 13:43 还能被 {@code playDamageSounds} 调起来
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
    }

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
                plan.add(new PlanEntry(e.getKey(), w.methodName, w.desc, w.lineHint));
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
        boolean ok = false;
        boolean bytesMatch = false;
        String detail = "ok";
        final Class<?>[] targets;
        final List<Class<?>> all = findLoadedAll(inst, className);
        if (all.isEmpty()) {
            detail = "class_not_loaded";
            targets = new Class<?>[0];
        } else {
            targets = all.toArray(new Class<?>[0]);
        }
        if (targets.length > 0) {
            LOCK.lock();
            try {
                // 🔴 竞态闸（离线逮到过）：组 plan 之后、拿锁之前，{@code undo}／{@code undoAll}
                //   可能已经把这些目标撤掉（undoAll 还会清空 wanted）。此时若照旧重打，
                //   就会把刚刚还原好的类又改成安全默认值 —— 还原被自己人顶掉。
                //   ⇒ 拿锁后按"当前 wanted"重新核对，只要有一个已经不 wanted 就整轮放弃（静默，不算失败、不退避）。
                if (!stillWantedLocked(plan)) {
                    detail = "wanted_changed";
                } else {
                    for (int i = 0; i < targets.length; i++) {
                        final Pending pending = new Pending(className.replace('.', '/'), "", null, -1, "",
                                plan);
                        tx.pending = pending;
                        try {
                            inst.retransformClasses(targets[i]);
                            ok = pending.appliedMethods > 0 || ok;
                            // "我们这次写下去的字节"与账本记录的那份是否一致（可判：账本里有这个 key 时）
                            if (i == 0 && pending.appliedMethods > 0) {
                                bytesMatch = bytesMatchLocked(className, pending);
                            }
                        } catch (Throwable t) {
                            detail = "retransform_" + simpleName(t);
                        } finally {
                            tx.pending = null;
                        }
                    }
                }
            } finally {
                LOCK.unlock();
            }
        }
        if ("wanted_changed".equals(detail)) {
            return false;      // 什么都没做：目标已经被显式 undo 掉了
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
    private static boolean bytesMatchLocked(String className, Pending pending) {
        for (Iterator<Map.Entry<String, Patch>> it = PATCHED.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, Patch> e = it.next();
            final Patch p = e.getValue();
            if (p == null || !className.equals(p.className)) {
                continue;
            }
            final HotfixPatchReadback.Fingerprint rec = p.patchedFpOf(0);
            final HotfixPatchReadback.Fingerprint now = pending.patchedFp;
            if (now == null || rec == null || !now.comparable() || !rec.comparable()) {
                return false;
            }
            return rec.hash.equals(now.hash) && rec.length == now.length;
        }
        return false;
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
                        .append(k.givenUp ? " given_up=true" : "")
                        .append(']');
            }
        }
        return sb.toString();
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
        // ② 播报（只在有出口 ＋ 有玩家上下文时；没有就只是"不播"，账照记）
        final PatchChat sink = chatSink;
        if (sink == null || fresh.isEmpty() || outcome.appliedMethodKeys.isEmpty()) {
            return;
        }
        final String who = chatPlayer();
        if (who == null || who.isEmpty()) {
            return;   // 没有玩家上下文（控制台／离线自检）⇒ 一条都不发
        }
        final List<PatchNote> notes = new ArrayList<>(fresh.size());
        for (int i = 0; i < fresh.size(); i++) {
            final String key = fresh.get(i);
            final String[] split = HotfixPatchChatText.splitTargetKey(key);
            if (split == null) {
                continue;   // 键不像 类#方法 ⇒ 宁可不说，也不拼一条看不懂的
            }
            notes.add(new PatchNote(HotfixPatchChatText.WORD_PATCHED, split[0], split[1], why));
        }
        if (notes.isEmpty()) {
            return;
        }
        try {
            if (sink.announce(who, notes) > 0) {
                CHAT_ANNOUNCED.addAndGet(notes.size());
            }
        } catch (Throwable ignored) {
            // 播报坏了绝不许影响热改本身（真正的原因由出口那一侧自己 WARN）
        }
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
              long atMillis) {
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
     * 12:41 那次"账本 patched 但行为没变"正是 P3：{@code forceSetHealth} 与 {@code playDamageSounds}
     * <b>是同一个类</b>（{@code examplemod.util.EntityUtil}），后一次 patch 把前一次撤掉了。
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

        PlanEntry(String key, String methodName, String methodDesc, int lineHint) {
            this.key = key == null ? "" : key;
            this.methodName = methodName == null ? "" : methodName;
            this.methodDesc = methodDesc == null ? "" : methodDesc;
            this.lineHint = lineHint;
        }
    }

    /**
     * 唯一的 transformer：<b>只有当前 {@link Pending} 指到的那个类、那个方法</b>会被改，别的一律 {@code null}。
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
                p.original = classfileBuffer.clone();
                byte[] current = classfileBuffer;
                int applied = 0;
                String firstReason = null;
                for (int i = 0; i < plan.size(); i++) {
                    final PlanEntry e = plan.get(i);
                    final HotfixMethodBodyPatch.Result r = HotfixMethodBodyPatch.patch(
                            current, e.methodName, e.methodDesc.isEmpty() ? null : e.methodDesc, e.lineHint);
                    if (!r.ok()) {
                        p.perMethod.put(e.key, "failed:" + r.reason);
                        p.failedMethods.add(e.key + ":" + r.reason);
                        if (firstReason == null) {
                            firstReason = r.reason;
                        }
                        continue;
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
                p.reason = firstReason == null ? "ok" : firstReason;
                if (applied == 0) {
                    return null;                // 一个都没套上 ⇒ 这个类我不管（绝不交半份）
                }
                p.bytes = current;
                p.newBytes = current.length;
                p.patchedFp = HotfixPatchReadback.fingerprint(current);
                return current;
            }
            final HotfixMethodBodyPatch.Result result =
                    HotfixMethodBodyPatch.patch(classfileBuffer, p.methodName, p.methodDesc, p.lineHint);
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
