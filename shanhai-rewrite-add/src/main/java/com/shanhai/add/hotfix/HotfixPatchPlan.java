package com.shanhai.add.hotfix;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🆕 2026-11-21（根因①）：<b>按类的累积补丁计划</b> —— "之前写过的 ＋ 这次新补的，一起重写"。
 *
 * <h2>为什么必须有这一层（现场：2026-11-20 22:56 实例 {@code GTL山海9.10test2}）</h2>
 * 同一个类（下称 T 类）打了 3 个方法的补丁：
 * <pre>
 *   T#forceSetHealth       attack=line    result=patched
 *   T#playDamageSounds     attack=method  result=patched
 *   T#runSetHealthMethods  attack=method  result=patched
 *   patch_repair … match=false（第一次 repair 就 false）→ patch_lost 1/2/3 → patch_giveup reason=lost3
 *   retransform_count=7，字节数 31902 → 31360 → 31002（单调变短）
 * </pre>
 * 机制：{@code retransformClasses} 是 <b>整类重写</b> ⇒ 同一类打 N 个方法 = 整类被重写 N 次；
 * 而我们每次都自己现组一份"要写什么"（{@code PATCHED}／{@code CHAT_PATCHED_KEYS} 两处派生），
 * 组出来少一条就是"重写一次、少一个方法"。
 *
 * <h2>口径（用户 2026-11-21 原话）</h2>
 * <blockquote>
 *   「对于重写覆盖的问题，我们记录之前重写过的内容，加上这回要新补的重写的内容，一起重写」
 * </blockquote>
 * ⇒ 每个类一张 {@link ClassPlan}：<b>之前所有已成功写进去的方法</b> ∪ <b>本次新增的</b>；
 * 每次 retransform 把<b>整张计划一次写完</b>。计划是"要写什么"的唯一真相，
 * 基线（{@link HotfixBaseline}）是"从哪份字节出发" —— 两者不冲突。
 *
 * <h2>三条硬纪律</h2>
 * <ol>
 *   <li>{@code rev} 只在<b>整张计划全部写成功</b>之后 +1；任何一条写失败 ⇒ <b>整次不提交</b>
 *       （宁可一次不装，也不要半装），日志 {@code patch_write_abort}。</li>
 *   <li>同一个 {@code methodKey} 再进来 ⇒ <b>以新为准</b>（升级 line→method 走的就是这条），
 *       并记 {@code superseded=1}。</li>
 *   <li>{@code patch_lost}／{@code foreign_write_detected} 之后的恢复，走的是<b>同一张计划</b>
 *       （{@code reason=recover}）—— 不是"从原始字节重建、只装这一次的方法"。</li>
 * </ol>
 *
 * <h2>为什么这个类只碰 {@code java.util}</h2>
 * 它被离线自检直接构造（{@code temp\hotfix\p2-plan\}）：不许引用 ASM／MC／Forge，
 * 否则"改了就废了"（与 {@link HotfixPaths} 同样的理由）。
 */
public final class HotfixPatchPlan {

    /** 第一次给某个类写补丁（计划里原本一条都没有）。 */
    public static final String REASON_FIRST = "first";

    /** 计划里原本有东西，这一次又并进来新的（或顶替了一条）。 */
    public static final String REASON_NEW_PATCH = "new_patch";

    /** 从 {@code patch_lost}／{@code foreign_write_detected} 恢复：整张计划重写一遍。 */
    public static final String REASON_RECOVER = "recover";

    /**
     * 🆕 2026-11-21：这一趟是<b>升级之后的重试</b>（{@code line} 找不到落点 ⇒ 换成强力档）。
     *
     * <p>它进 {@code patch_write … reason=escalate}：从这一行就能判"这条补丁是真的装上去了，
     * 而且它用的是升级后的档"，而不是"又打了一次 line"。
     */
    public static final String REASON_ESCALATE = "escalate";

    /**
     * 🆕 2026-11-21（强力模式 ③）：这一趟是<b>整链抹除</b>（{@code HotfixChainWipe}）写的子计划。
     *
     * <p>为什么要与 {@code first}／{@code new_patch} 分开：整链抹除一次会给同一个类塞进
     * <b>很多条</b>方法（整类或 triad），日志里必须能一眼分出"这是那条更高等级的抹除"，
     * 而不是"又一条普通补丁"。
     */
    public static final String REASON_CHAIN_WIPE = "chain_wipe";

    /**
     * 🆕 2026-11-21（回归修复）：<b>升级之后仍然装不上</b> —— 这才是可以放弃的那一拍。
     *
     * <p>为什么要与原始原因分开：现场要能一眼分出
     * 「line 找不到落点（该升级，不该放弃）」与「升级到最强档也装不上（真的没救）」。
     * 原始原因不丢，跟着 {@code inner=} 一起打出来。
     */
    public static final String REASON_METHOD_FAILED_AFTER_ESCALATION = "method_failed_after_escalation";

    /** 同时维护多少张类计划（防泄漏；超了就不再新建，只记一行）。 */
    public static final int MAX_PLANS = 64;

    // ═══════════════════════ 🆕 2026-11-21（P2）：两条写入路径的**唯一身份账本** ═══════════════════════

    /**
     * 写入者：普通补丁（{@code writePlanLocked} 那条路）。
     *
     * <p><b>为什么写进这张类计划里</b>（用户口径：两条路径并成<b>一份</b>该类的计划）：
     * {@code patch} 与 {@code chain_wipe} 本来就是<b>同一个</b> {@link ClassPlan}（互相覆盖写过的东西），
     * 可"谁写的、第几代、写下去那份 sha 是多少"却只记在一张按方法键的账本
     * （{@code PATCHED}）里 ⇒ 同一个类有两个写入者时，读到的"我们自己上次写下的字节"
     * 是<b>某一条方法补丁</b>的指纹，而不是<b>整类</b>那一份 ⇒ 任何计划变化都会被误判成
     * {@code patch_lost evidence=self_output_unstable}（2026-11-21 现场 134 条）。
     * ⇒ 身份（rev/sha/长度/计划摘要/写入者）必须是<b>类级</b>的，而且只有一处。
     */
    public static final String WRITER_PATCH = "patch";

    /** 写入者：整链抹除（{@code chainWipeWriteClass} 那条路）。 */
    public static final String WRITER_CHAIN_WIPE = "chain_wipe";

    /**
     * 🆕 2026-11-21（P2 追加）：写入者 —— <b>关键类还原</b>（{@code HotfixKeyClassRestore}）。
     *
     * <p>它是第三条<b>独立</b>的 {@code retransformClasses} 路径（清空外部 handler 方法体），
     * <b>不并进计划</b>（那是另一套手术），但与另外两条<b>共用同一把按类的写锁</b>：
     * 同一类的三次写必须串行，否则互相覆盖 ⇒ 现场就会看到 {@code patch_lost}。
     */
    public static final String WRITER_RESTORE = "restore";

    /** 这一类<b>两条路径都写过</b>（日志 {@code writer=both}）。 */
    public static final String WRITER_BOTH = "both";

    private static final int MASK_PATCH = 1;

    private static final int MASK_CHAIN_WIPE = 2;

    private static final int MASK_RESTORE = 4;

    /**
     * 同一个方法键"写失败"几次之后就不再让它挡住整张计划。
     *
     * <p>为什么必须有它：整次不提交的红线会带来一个新形态 —— 某一条<b>永远装不上</b>
     * （方法签名变了／那个方法根本不存在），于是<b>每一次</b>违规都触发一次"尝试 → abort"。
     * 那虽然不是写字节（transformer 返回 null），但也是一个不该无限跑的热路径，
     * 而且这一类永远打不上补丁。⇒ 记到 3 次就把它标成"装不上"，从此<b>不再进计划</b>、
     * 也不再作为目标（日志 {@code give_up=1}）。这条与 {@code repairUsed} 预算是同一个精神：
     * <b>有限次，然后停手</b>。
     */
    public static final int ABORT_GIVEUP = 3;

    /** 一条方法补丁：{@code tier=line} 时还带着"废掉哪几条指令"，{@code tier=method} 时整方法换默认返回。 */
    public static final class MethodPatch {

        /** {@code 类#方法}（与日志里的 {@code target=} 逐字一致）。 */
        public final String key;

        public final String methodName;

        /** 精确描述符；空串 ⇒ 靠 {@link #lineHint} 定位。 */
        public final String desc;

        /** 选靶时用的行号（{@code -1} = 没用它）。 */
        public final int lineHint;

        /** {@link HotfixAttackTier#LINE} 或 {@link HotfixAttackTier#METHOD}。 */
        public final String tier;

        public MethodPatch(String key, String methodName, String desc, int lineHint, String tier) {
            this.key = key == null ? "" : key;
            this.methodName = methodName == null ? "" : methodName;
            this.desc = desc == null ? "" : desc;
            this.lineHint = lineHint;
            this.tier = HotfixAttackTier.orDefault(tier);
        }

        /** 日志里的 {@code detail=[m1:line,m2:method]} 那一段的一条。 */
        public String detail() {
            return (methodName.isEmpty() ? "?" : methodName) + ":" + tier;
        }

        /** 同一个键但换了档／换了描述符 ⇒ 说明这是一次"顶替"（升级）。 */
        public boolean sameAs(MethodPatch other) {
            return other != null && key.equals(other.key)
                    && methodName.equals(other.methodName) && desc.equals(other.desc)
                    && lineHint == other.lineHint && tier.equals(other.tier);
        }
    }

    /**
     * 一个类的<b>累积计划</b>。
     *
     * <p>{@link #committed} 是"已经真的写进 JVM 过、且没被撤回"的那一份 —— 它是唯一的真相来源；
     * 任何要写的东西都从它派生，<b>绝不再从 {@code PATCHED}／{@code CHAT_PATCHED_KEYS} 现组</b>
     * （那两处是"播报去重"与"操作账本"，语义不同，历史半状态就是从那儿漏出来的）。
     */
    public static final class ClassPlan {

        public final String className;

        /** 已提交的那一份（按插入顺序；{@code key ⇒ patch}）。 */
        private final LinkedHashMap<String, MethodPatch> committed = new LinkedHashMap<String, MethodPatch>();

        /** 提交代数：只有<b>整张计划全部写成功</b>才 +1。 */
        private long rev;

        /** 上一次真的写下去是什么时候（诊断用）。 */
        private long lastWriteMillis;

        /** 上一次写下去的那份字节的 sha256 前 16 位（身份章，见 {@link HotfixBaseline}）。 */
        private String lastSha = "";

        /** 🆕 上一次写下去那份字节的<b>长度</b>（{@code patch_lost before_sha=…/<len>} 用）。 */
        private long lastLen = -1L;

        /** 🆕 上一次提交时那份计划的摘要（{@code 方法键:档位} 的 sha16）——计划不变 ⇒ 字节应当不变。 */
        private String lastDigest = "";

        /** 🆕 这一类被哪些写入路径写过（{@link #WRITER_PATCH}／{@link #WRITER_CHAIN_WIPE} 的位掩码）。 */
        private int writersMask;

        /** 🆕 上一次写它的那一方（{@code patch}／{@code chain_wipe}）。 */
        private String lastWriter = "";

        /**
         * 🆕 2026-11-21（P2 追加）：<b>按类名的写锁</b> —— 三条写入路径（patch／chain_wipe／restore）
         * 对同一个类必须串行。
         *
         * <p>⚠️ 加锁顺序固定为 <b>全局 {@code HotfixAutoCounter.LOCK} → 这里的类锁</b>，
         * 绝不反过来（反了就是死锁：patch 持全局锁等类锁、restore 持类锁等全局锁）。
         */
        private final java.util.concurrent.locks.ReentrantLock classLock =
                new java.util.concurrent.locks.ReentrantLock();

        /** 🆕 这个类被<b>串行</b>写了几次（patch／chain_wipe／restore 都算；日志 {@code serial=}）。 */
        private long writeSerial;

        /** 🆕 "正在写"的重入计数（>0 = 有人正在这个类的临界区里）。 */
        private int inWrite;

        /** 🆕 检测到的<b>交错</b>次数（正常恒为 0；非 0 就是锁没生效，必须当 bug 查）。 */
        private int interleaved;

        ClassPlan(String className) {
            this.className = className == null ? "" : className;
        }

        public synchronized long rev() {
            return rev;
        }

        public synchronized long lastWriteMillis() {
            return lastWriteMillis;
        }

        public synchronized String lastSha() {
            return lastSha;
        }

        /** 🆕 上一次写下去那份字节的长度（{@code -1} = 还没写过）。 */
        public synchronized long lastLen() {
            return lastLen;
        }

        /** 🆕 上一次提交的那份计划摘要（计划没变 ⇒ 输出字节应当逐字节相同）。 */
        public synchronized String lastDigest() {
            return lastDigest;
        }

        /** 🆕 上一次写它的那一方（{@code patch}／{@code chain_wipe}；没写过 ⇒ 空串）。 */
        public synchronized String lastWriter() {
            return lastWriter;
        }

        /**
         * 🆕 这一类一共被几条写入路径写过：{@code patch}／{@code chain_wipe}／{@code restore}／{@code both}。
         *
         * <p>它就是 {@code patch_lost} 证据分类里"账本里该类有另一条写入路径的 rev 更新"的判据。
         */
        public synchronized String writerTag() {
            final int bits = (writersMask & MASK_PATCH) != 0 ? 1 : 0;
            final int chain = (writersMask & MASK_CHAIN_WIPE) != 0 ? 1 : 0;
            final int restore = (writersMask & MASK_RESTORE) != 0 ? 1 : 0;
            final int n = bits + chain + restore;
            if (n <= 0) {
                return "";
            }
            if (n > 1) {
                return WRITER_BOTH;
            }
            return bits > 0 ? WRITER_PATCH : (chain > 0 ? WRITER_CHAIN_WIPE : WRITER_RESTORE);
        }

        /** 🆕 这一类被 {@code writer} 之外的另一条路径写过吗。 */
        public synchronized boolean otherWriterSeen(String writer) {
            int selfBit = 0;
            if (WRITER_PATCH.equals(writer)) {
                selfBit = MASK_PATCH;
            } else if (WRITER_CHAIN_WIPE.equals(writer)) {
                selfBit = MASK_CHAIN_WIPE;
            } else if (WRITER_RESTORE.equals(writer)) {
                selfBit = MASK_RESTORE;
            }
            return (writersMask & ~selfBit) != 0;
        }

        // ---- 🆕 2026-11-21（P2 追加）：按类串行（三条写入路径共用） ----

        /** 拿这个类的写锁（调用方必须已持全局 {@code LOCK}；顺序：全局 → 类锁）。 */
        public void lock() {
            classLock.lock();
        }

        public void unlock() {
            classLock.unlock();
        }

        /** 进临界区：返回这一次的序号；若发现上一个还没出临界区 ⇒ 记一次交错（正常 0）。 */
        public synchronized long writeEnter() {
            if (inWrite > 0) {
                interleaved++;
            }
            inWrite++;
            return writeSerial + 1L;
        }

        /** 出临界区：序号 +1。 */
        public synchronized void writeExit() {
            if (inWrite > 0) {
                inWrite--;
            }
            writeSerial++;
        }

        /** 这个类被串行写了几次（三条路径合计）。 */
        public synchronized long writeSerial() {
            return writeSerial;
        }

        /** 检测到的交错次数（正常 0）。 */
        public synchronized int interleaved() {
            return interleaved;
        }

        public synchronized int size() {
            return committed.size();
        }

        public synchronized boolean isEmpty() {
            return committed.isEmpty();
        }

        /** 已提交计划的一份快照（调用方随便改，不影响这里）。 */
        public synchronized List<MethodPatch> snapshot() {
            final List<MethodPatch> out = new ArrayList<MethodPatch>(committed.size());
            for (Iterator<Map.Entry<String, MethodPatch>> it = committed.entrySet().iterator(); it.hasNext(); ) {
                final MethodPatch p = it.next().getValue();
                if (p != null) {
                    out.add(p);
                }
            }
            return out;
        }

        public synchronized MethodPatch get(String key) {
            return key == null ? null : committed.get(key);
        }

        synchronized void commit(List<MethodPatch> candidate, long nowMillis, String sha) {
            commit(candidate, nowMillis, sha, -1L, "", "");
        }

        /**
         * 🆕 2026-11-21：<b>带类级身份</b>的提交（写入者／长度／计划摘要一起落账）。
         *
         * <p>为什么这些都必须在<b>这一个</b>方法里落地：它们是"我们自己上次写下的字节"
         * 的完整身份；散在别处（或按方法键各记一份）就必然出现"同一个类有两份身份"
         * —— 那正是现场 134 条 {@code self_output_unstable} 的来源。
         *
         * @param sha    {@code return} 下去那份字节的 sha16
         * @param len    那份字节的长度（{@code -1} = 不知道）
         * @param digest 这份<b>计划</b>的摘要（{@link #digestOf(List)}）；计划没变 ⇒ 输出应当逐字节相同
         * @param writer {@link #WRITER_PATCH}／{@link #WRITER_CHAIN_WIPE}（空 ⇒ 不改写入者记账）
         */
        synchronized void commit(List<MethodPatch> candidate, long nowMillis, String sha,
                                 long len, String digest, String writer) {
            committed.clear();
            for (int i = 0; i < candidate.size(); i++) {
                final MethodPatch p = candidate.get(i);
                if (p != null && !p.key.isEmpty()) {
                    committed.put(p.key, p);
                }
            }
            rev++;
            lastWriteMillis = nowMillis;
            lastSha = sha == null ? "" : sha;
            lastLen = len;
            lastDigest = digest == null ? "" : digest;
            noteWriterLocked(writer);
        }

        /** 🆕 记一笔"这条路径写过这一类"（{@code writer} 空 ⇒ 什么也不做）。 */
        synchronized void noteWriter(String writer) {
            noteWriterLocked(writer);
        }

        private void noteWriterLocked(String writer) {
            if (WRITER_CHAIN_WIPE.equals(writer)) {
                writersMask |= MASK_CHAIN_WIPE;
                lastWriter = WRITER_CHAIN_WIPE;
            } else if (WRITER_PATCH.equals(writer)) {
                writersMask |= MASK_PATCH;
                lastWriter = WRITER_PATCH;
            } else if (WRITER_RESTORE.equals(writer)) {
                writersMask |= MASK_RESTORE;
                lastWriter = WRITER_RESTORE;
            }
        }

        synchronized void forget(String key) {
            if (key != null) {
                committed.remove(key);
            }
        }

        synchronized void clear() {
            committed.clear();
            rev = 0L;
            lastWriteMillis = 0L;
            lastSha = "";
            lastLen = -1L;
            lastDigest = "";
            writersMask = 0;
            lastWriter = "";
            inWrite = 0;
            interleaved = 0;
            writeSerial = 0L;
        }
    }

    /** 一次合并的<b>候选</b>计划（还没提交；写失败就整个丢掉）。 */
    public static final class Merge {

        public final ClassPlan plan;

        /** 候选全集 = 已提交的 ∪ 本次进来的（同键以新为准）。顺序 = 旧在前、新的在后。 */
        public final List<MethodPatch> candidate;

        /** 本次真的新增了几条。 */
        public final int added;

        /** 本次顶替（同键已存在，以新为准）了几条 —— 升级 line→method 会在这里显形。 */
        public final int superseded;

        /** 这一次写完之后会是第几代。 */
        public final long rev;

        /** {@code first}／{@code new_patch}／{@code recover}。 */
        public final String reason;

        Merge(ClassPlan plan, List<MethodPatch> candidate, int added, int superseded, long rev, String reason) {
            this.plan = plan;
            this.candidate = candidate;
            this.added = added;
            this.superseded = superseded;
            this.rev = rev;
            this.reason = reason;
        }

        public int size() {
            return candidate.size();
        }

        /** {@code detail=[m1:line,m2:method]} 里面那个 {@code [...]}。 */
        public String detail() {
            return detailOf(candidate);
        }
    }

    /** 一张类计划的锁外快照数（{@code status} 用）。 */
    private static final Map<String, ClassPlan> PLANS = new LinkedHashMap<String, ClassPlan>();

    /**
     * "某条补丁写不进去"的记账：{@code 类名 ⇒ (方法键 ⇒ 失败次数)}。
     *
     * <p>到 {@link #ABORT_GIVEUP} 次就不再让它进计划（见那道常量的注释）。
     */
    private static final Map<String, LinkedHashMap<String, Integer>> ABORTS =
            new LinkedHashMap<String, LinkedHashMap<String, Integer>>();

    private HotfixPatchPlan() {
        // 纯静态
    }

    /** 取（必要时建）一个类的计划。超过 {@link #MAX_PLANS} 个类 ⇒ 返回一个不记账的临时计划。 */
    public static ClassPlan plan(String className) {
        final String cn = className == null ? "" : className;
        synchronized (PLANS) {
            ClassPlan p = PLANS.get(cn);
            if (p == null) {
                if (PLANS.size() >= MAX_PLANS) {
                    return new ClassPlan(cn);      // 不记账：宁可这张表不长大，也不许丢异常
                }
                p = new ClassPlan(cn);
                PLANS.put(cn, p);
            }
            return p;
        }
    }

    /** 这个类有一张"已经写成功过"的计划吗（{@code rev>0}）。 */
    public static boolean written(String className) {
        final ClassPlan p;
        synchronized (PLANS) {
            p = PLANS.get(className == null ? "" : className);
        }
        return p != null && p.rev() > 0L;
    }

    /** 当前维护着几张类计划（{@code status} 用）。 */
    public static int planCount() {
        synchronized (PLANS) {
            return PLANS.size();
        }
    }

    /**
     * 🆕 2026-11-21（③ undo 自证）：把<b>某一个类的计划整张清掉</b> —— {@code rev} 归零、身份章作废。
     *
     * <p>为什么需要"整张清"而不是一条一条 {@link #forget}：{@code forget(key)} 只摘一条、
     * {@code rev} 不动 ⇒ 撤完之后 {@link #written(String)} 仍是 true，下一次"整张计划一次写"
     * 会以为这一类还有活干。整类一起撤（"同一个类里 N 条一起 undo"）的语义就是
     * <b>这一类彻底不归我们管了</b> ⇒ 计划必须整张清空。
     *
     * <p>连带清 {@code ABORTS}：这个类都退役了，"装不上"的记账再留着只会让下次重来直接放弃。
     */
    public static void clearPlan(String className) {
        final String cn = className == null ? "" : className;
        final ClassPlan p;
        synchronized (PLANS) {
            p = PLANS.get(cn);
        }
        if (p != null) {
            p.clear();
        }
        synchronized (ABORTS) {
            ABORTS.remove(cn);
        }
    }

    /**
     * <b>把本次要装的补丁并进计划</b>（不提交；提交在写成功之后）。
     *
     * @param plan     这个类的计划
     * @param incoming 本次要装的方法（主目标 ＋ 同类别的兄弟）
     * @param reason   {@code null} ⇒ 自动判（空的计划 = {@code first}，否则 {@code new_patch}）；
     *                 恢复路径显式传 {@link #REASON_RECOVER}
     */
    public static Merge merge(ClassPlan plan, List<MethodPatch> incoming, String reason) {
        final List<MethodPatch> base;
        final long rev;
        synchronized (plan) {
            base = plan.snapshot();
            rev = plan.rev() + 1L;
        }
        final LinkedHashMap<String, MethodPatch> candidate = new LinkedHashMap<String, MethodPatch>();
        for (int i = 0; i < base.size(); i++) {
            final MethodPatch p = base.get(i);
            if (p != null) {
                candidate.put(p.key, p);
            }
        }
        int added = 0;
        int superseded = 0;
        for (int i = 0; incoming != null && i < incoming.size(); i++) {
            final MethodPatch p = incoming.get(i);
            if (p == null || p.key.isEmpty()) {
                continue;
            }
            final MethodPatch old = candidate.get(p.key);
            if (old == null) {
                added++;
                candidate.put(p.key, p);
                continue;
            }
            if (old.sameAs(p)) {
                // 完全一样：仍然覆盖（幂等），但不记 superseded（否则每轮哨兵都会报"顶替"）
                candidate.put(p.key, p);
                continue;
            }
            superseded++;
            // 🆕 2026-11-21（P2）：同键"以新为准"，但**已经知道的描述符／行号不许被空串顶掉**。
            //   现场：整链抹除的键带描述符、普通补丁的键不带 ⇒ 两条写法不同的键指向同一个方法，
            //   计划里出现两条（detail=[instantKill:method,updateSwipe:method,instantKill:method,tick:method]，
            //   只有 3 个方法却 methods=4）。现在两条路径共用不带描述符的<b>同一个键</b>，
            //   描述符只作为字段携带 —— 谁先写进来就把这个事实留给后来者。
            final String desc = p.desc.isEmpty() ? old.desc : p.desc;
            final int lineHint = p.lineHint >= 0 ? p.lineHint : old.lineHint;
            candidate.put(p.key, new MethodPatch(p.key, p.methodName, desc, lineHint, p.tier));
        }
        final String why = reason != null ? reason : (base.isEmpty() ? REASON_FIRST : REASON_NEW_PATCH);
        final List<MethodPatch> out = new ArrayList<MethodPatch>(candidate.size());
        for (Iterator<Map.Entry<String, MethodPatch>> it = candidate.entrySet().iterator(); it.hasNext(); ) {
            out.add(it.next().getValue());
        }
        return new Merge(plan, out, added, superseded, rev, why);
    }

    /**
     * <b>提交</b>：整张计划全写成功之后才调 —— 它是 {@code rev} 唯一的 +1 处。
     *
     * @param sha 这次写下去那份字节的 sha（前 16 位）；拿不到 ⇒ 空串（身份章就只能记"记账型"）
     */
    public static void commit(Merge merge, long nowMillis, String sha) {
        commit(merge, nowMillis, sha, -1L, "", "");
    }

    /**
     * 🆕 2026-11-21（P2）：<b>带类级身份</b>的提交（写的是哪一方、多长、计划摘要）。
     *
     * @param writer {@link #WRITER_PATCH}／{@link #WRITER_CHAIN_WIPE}（进 {@code writer=} 日志与证据分类）
     */
    public static void commit(Merge merge, long nowMillis, String sha, long len, String digest, String writer) {
        if (merge == null || merge.plan == null) {
            return;
        }
        merge.plan.commit(merge.candidate, nowMillis, sha, len, digest, writer);
    }

    /**
     * 🆕 计划摘要：{@code 方法键:档位} 按计划顺序拼起来再 sha16。
     *
     * <p>它的唯一用途：<b>判断"这一次的计划与上一次是不是同一份"</b>。
     * 同一份计划 ⇒ 从同一份基线出发的两次写必须逐字节相同（否则才是真的"输出不稳定"）；
     * 计划变了（新加了一个方法／升了档）⇒ 字节<b>本来就应当</b>不同，那不是"补丁丢了"。
     */
    public static String digestOf(List<MethodPatch> patches) {
        final StringBuilder sb = new StringBuilder(64);
        for (int i = 0; patches != null && i < patches.size(); i++) {
            final MethodPatch p = patches.get(i);
            if (p == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p.key).append(':').append(p.tier).append(':').append(p.desc);
        }
        return HotfixBaseline.sha16(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 显式撤回一条（{@code undo} 成功之后必须调，否则下一次整计划写会把它又装回去）。 */
    public static void forget(String className, String key) {
        final ClassPlan p;
        synchronized (PLANS) {
            p = PLANS.get(className == null ? "" : className);
        }
        if (p != null) {
            p.forget(key);
        }
        forgetAbort(className, key);
    }

    /**
     * 记一次"这一条写不进去"。返回累计次数（到 {@link #ABORT_GIVEUP} ⇒ 从此不进计划）。
     */
    public static int noteAbort(String className, String key) {
        final String cn = className == null ? "" : className;
        final String k = key == null ? "" : key;
        if (k.isEmpty()) {
            return 0;
        }
        synchronized (ABORTS) {
            LinkedHashMap<String, Integer> m = ABORTS.get(cn);
            if (m == null) {
                if (ABORTS.size() >= MAX_PLANS) {
                    return 0;
                }
                m = new LinkedHashMap<String, Integer>();
                ABORTS.put(cn, m);
            }
            final Integer old = m.get(k);
            final int now = (old == null ? 0 : old.intValue()) + 1;
            m.put(k, Integer.valueOf(now));
            return now;
        }
    }

    /** 这一条已经被判"永远装不上"了吗（到了 {@link #ABORT_GIVEUP} 次）。 */
    public static boolean poisoned(String className, String key) {
        final String cn = className == null ? "" : className;
        final String k = key == null ? "" : key;
        if (k.isEmpty()) {
            return false;
        }
        synchronized (ABORTS) {
            final Map<String, Integer> m = ABORTS.get(cn);
            final Integer n = m == null ? null : m.get(k);
            return n != null && n.intValue() >= ABORT_GIVEUP;
        }
    }

    /** 某条补丁累计写失败几次。 */
    public static int abortCount(String className, String key) {
        final String cn = className == null ? "" : className;
        synchronized (ABORTS) {
            final Map<String, Integer> m = ABORTS.get(cn);
            final Integer n = m == null ? null : m.get(key == null ? "" : key);
            return n == null ? 0 : n.intValue();
        }
    }

    private static void forgetAbort(String className, String key) {
        synchronized (ABORTS) {
            final Map<String, Integer> m = ABORTS.get(className == null ? "" : className);
            if (m != null) {
                m.remove(key == null ? "" : key);
            }
        }
    }

    /** 停服／退出世界：整张表清空（下一局从干净态开始）。 */
    public static void reset() {
        synchronized (PLANS) {
            for (Iterator<Map.Entry<String, ClassPlan>> it = PLANS.entrySet().iterator(); it.hasNext(); ) {
                final ClassPlan p = it.next().getValue();
                if (p != null) {
                    p.clear();
                }
            }
            PLANS.clear();
        }
        synchronized (ABORTS) {
            ABORTS.clear();
        }
    }

    /** {@code detail=[a:line,b:method]} 里的 {@code [...]}。 */
    public static String detailOf(List<MethodPatch> patches) {
        final StringBuilder sb = new StringBuilder(48);
        for (int i = 0; patches != null && i < patches.size(); i++) {
            final MethodPatch p = patches.get(i);
            if (p == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p.detail());
        }
        return sb.toString();
    }

    // ═══════════════════════════ 日志行（唯一拼装处；离线自检逐字比对） ═══════════════════════════

    /**
     * {@code patch_plan class=… methods=<N> detail=[m1:line,m2:method,…] retransforms=1}。
     *
     * <p>它是"这一趟<b>计划</b>要写几个方法"的那一行 —— 与 {@link #writeLine} 的区别：
     * {@code patch_plan} 在<b>合并时</b>打（说清"打算一起写几个"），
     * {@code patch_write} 在<b>写成功之后</b>打（说清"真的写下去几个、第几代"）。
     */
    public static String planLine(String className, int methods, String detail) {
        return planLine(className, methods, detail, "");
    }

    /**
     * 🆕 2026-11-21（P2）：带 {@code writer=} 的 {@code patch_plan}。
     *
     * <p>{@code writer} 只可能不写（空串）或写 {@link #WRITER_PATCH}／{@link #WRITER_BOTH}：
     * 整链抹除驱动的那一趟不打这一行（它有自己的 {@code chain_wipe_write}），见
     * {@code HomeCounter.writePlanAttemptLocked} 的分流。
     */
    public static String planLine(String className, int methods, String detail, String writer) {
        return "patch_plan class=" + HotfixAutoCounter.token(className)
                + " methods=" + methods
                + " detail=[" + detail + "]"
                + " retransforms=1"
                + (writer == null || writer.isEmpty() ? "" : " writer=" + HotfixAutoCounter.token(writer));
    }

    /**
     * {@code patch_write class=… rev=<N> methods=<M> detail=[…] reason=first|new_patch|recover retransforms=1}。
     *
     * <p>{@code rev} 是"这个类的第几次整计划写"；{@code methods} 是<b>这一趟一起写下去的全集</b>
     * （含之前就写过、这次只是再套一遍的兄弟）—— 只装一个方法时它也等于 1，所以
     * "之前写过的 ＋ 新补的，一起重写"这件事在日志里是<b>可判</b>的（{@code methods} 单调不降）。
     */
    public static String writeLine(String className, long rev, int methods, String detail, String reason,
                                   int retransforms, int superseded) {
        return writeLine(className, rev, methods, detail, reason, retransforms, superseded, "");
    }

    /**
     * 🆕 2026-11-21（P2）：带 {@code writer=} 的 {@code patch_write}。
     *
     * <p>{@code writer=both} = 这一份计划里既有普通补丁的目标、又有整链抹除的目标
     * （两条路径并成了同一份计划 ⇒ 只可能有一次 {@code retransform}）。
     */
    public static String writeLine(String className, long rev, int methods, String detail, String reason,
                                   int retransforms, int superseded, String writer) {
        return writeLine(className, rev, methods, detail, reason, retransforms, superseded, writer, 0L);
    }

    /**
     * 🆕 2026-11-21（P2 追加）：带 {@code serial=} 的 {@code patch_write}。
     *
     * <p>{@code serial} = 这个类<b>第几次被串行写</b>（patch／chain_wipe／restore 合计）。
     * 三条写入路径（patch／chain_wipe／restore）在同一把按类锁上排队 ⇒ 这个序号必须连续、无跳号；
     * 它就是"三次写真的串行"的可判读证据（离线自证直接断言 1 → 2 → 3）。
     */
    public static String writeLine(String className, long rev, int methods, String detail, String reason,
                                   int retransforms, int superseded, String writer, long serial) {
        return "patch_write class=" + HotfixAutoCounter.token(className)
                + " rev=" + rev
                + " methods=" + methods
                + " detail=[" + detail + "]"
                + " reason=" + HotfixAutoCounter.token(reason)
                + " retransforms=" + retransforms
                + (superseded > 0 ? " superseded=" + superseded : "")
                + (writer == null || writer.isEmpty() ? "" : " writer=" + HotfixAutoCounter.token(writer))
                + (serial > 0L ? " serial=" + serial : "");
    }

    /** {@code patch_write_abort class=… rev=<N> failed=<m2> reason=<…>} —— 整次不提交。 */
    public static String abortLine(String className, long rev, String failedKey, String reason) {
        return "patch_write_abort class=" + HotfixAutoCounter.token(className)
                + " rev=" + rev
                + " failed=" + HotfixAutoCounter.token(failedKey)
                + " reason=" + HotfixAutoCounter.token(reason)
                + " committed=false";
    }

    /**
     * 🆕 2026-11-21（回归修复）：
     * {@code patch_escalate class=… target=<类#方法> from=line to=method why=<原因> escalated=1}。
     *
     * <p>它回答"升级了、成了没"的<b>前半句</b>：这一拍普通攻击（{@code line}）在字节码里找不到落点，
     * 于是把<b>同一个 target</b> 换成强力档（{@code method}）并<b>立刻重试</b>。
     * 后半句是紧随其后的两行之一：
     * <ul>
     *   <li>成了 ⇒ {@code patch_write … reason=escalate}（{@code detail=[m:method]}）；</li>
     *   <li>还是没成 ⇒ {@code patch_write_abort … reason=method_failed_after_escalation committed=false}。</li>
     * </ul>
     *
     * @param why 原始原因（{@code line_no_site} 等；进 {@code why=}）
     */
    public static String escalateLine(String className, String targetKey, String why) {
        return "patch_escalate class=" + HotfixAutoCounter.token(className)
                + " target=" + HotfixAutoCounter.token(targetKey)
                + " from=" + HotfixAttackTier.LINE
                + " to=" + HotfixAttackTier.METHOD
                + " why=" + HotfixAutoCounter.token(why)
                + " escalated=1";
    }
}
