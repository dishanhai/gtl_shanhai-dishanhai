package com.shanhai.add.hotfix;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * <b>关键类"恢复"：一趟 {@code retransform} 里把外部 handler 的方法体清成空体，同时逐字节保留其余一切。</b>
 *
 * <h2>用户 2026-11-21 的原话（逐字）</h2>
 * <pre>
 *   「就是目前我的思路是这样的，检测到一些关键类被修改，就先溯源到敌人的代码，然后废掉敌人的代码，
 *     再然后把这些类改回来」
 * </pre>
 * <p>本类只做最后一句「把这些类改回来」，<b>不做</b>溯源、不做"废掉敌人的类"（那是
 * {@link HotfixChainWipe} 的活），也不碰任何触发源。
 *
 * <h2>机制（为什么这条路能成立）</h2>
 * <ol>
 *   <li>{@code retransformClasses} 交给 transformer 的 {@code classfileBuffer} 是<b>初始字节</b>
 *       （{@link HotfixPatchReadback} 已实测）：它由 {@code defineClass} 收到，也就是
 *       <b>Mixin / coremod 在启动期处理完之后</b>的那一份
 *       ⇒ 对手的头注入（{@code handler$…}）与我们的启动期注入<b>都在里面</b>。</li>
 *   <li>我们只把<b>外部</b> handler 的方法体清空，<b>不删调用点</b>：
 *       调用点还在，{@code handler(this, cir)} 照常执行，但它什么都不做 ⇒ {@code cir.cancel()}
 *       不会发生 ⇒ 原方法体继续往下跑 ⇒ 挂在我们自己 handler 上的 RETURN 注入重新执行。</li>
 *   <li>对手<b>运行期</b>改的东西（Agent / {@code Unsafe.defineClass}）不在初始字节里
 *       ⇒ 这一趟重建之后自然消失。</li>
 * </ol>
 *
 * <h2>🔴 一趟：读初始字节 → 清空外部 handler → 一次写回</h2>
 * <p>{@link #strip} 只用<b>一个</b> {@link ClassReader} 和一个 {@link ClassWriter}，对所有方法只做一次
 * 遍历；调用方也只调<b>一次</b> {@code retransformClasses}（{@code retransforms=1}）。
 * <b>绝不跑两趟</b>：两趟之间有一个窗口期，对手能在窗口里再改一次，{@code retransform} 就打不完了。
 *
 * <h2>🔴 边界（必须知道，别写成"永久摘除"）</h2>
 * <p>本类<b>不常驻</b> transformer（常驻会让热改引擎的整类指纹对不上，触发 {@code patch_lost}），
 * 所以只能保证两句话：
 * <ol>
 *   <li><b>每一次经热改引擎（{@code HotfixAutoCounter.writePlan*}）写字节之后</b>，该类的
 *       外部 handler 一定不在 —— 因为 {@link #stripForWrite} 已经并进那条写路径；</li>
 *   <li>一次 {@link #restoreForTriage} 之后，<b>那一趟</b>重建的结果里外部 handler 不在。</li>
 * </ol>
 * <p>控制不到的：<b>别的 mod 自己的 transformer</b> 之后若对该类 retransform，仍会从初始字节
 * 把外部 handler 带回来；本类<b>不更新</b> {@link HotfixBaseline}（基线禁改）。
 *
 * <h2>为什么"别的东西逐字节不动"在 ASM 里能做到</h2>
 * <p>{@link ClassWriter#ClassWriter(ClassReader, int)}（第二个参数 {@code 0}）会<b>原样复制</b>
 * 输入的常量池；并且只要 {@code visitMethod} 原样返回它自己造出来的 {@code MethodWriter}，
 * ASM 就会把那个方法的 {@code method_info}（含 Code / StackMapTable / try-catch / 注解）
 * <b>按原始字节整段复制</b>，根本不经过"读事件 → 再写事件"。
 * ⇒ 我们只对<b>被清空的那几个 handler</b>返回一个包装过的 {@link MethodVisitor}（于是只有它们走重写），
 * 其余方法一个字节都不动。离线自证里用手写的 class 文件区间比对核了这一点
 * （常量池区间 / 每个非目标方法的 {@code method_info} 全部逐字节相等）。
 *
 * <h2>清空方法体的实现（只换 Code）</h2>
 * <p>{@link EmptyBody} 把原方法体的事件全部吞掉，在 {@code visitMaxs} 里只发两三条指令：
 * <pre>
 *   void          ⇒ RETURN
 *   基本类型      ⇒ ICONST_0 / LCONST_0 / FCONST_0 / DCONST_0 ＋ xRETURN
 *   引用 / 数组    ⇒ ACONST_NULL ＋ ARETURN
 * </pre>
 * <p>🔴 方法签名 / 描述符 / 访问标志 / 参数 / 注解一律经 {@code mv} 原样转发；{@code maxStack} /
 * {@code maxLocals} 保留原值（清成空体后偏大是合法的，<b>绝不缩小</b>；只有原值小到放不下默认返回的
 * 那 1~2 个栈槽时才抬到最小需要值）。try-catch / LineNumberTable / LocalVariableTable / 局部变量注解
 * 随方法体一起消失（它们指向的 Label 已经不在指令表里）。
 *
 * <h2>认得出"对手的 handler"（判据一条都不放宽）</h2>
 * <ol>
 *   <li>类里存在方法名形如 {@code handler$<hash>$<modid>$<name>} 或
 *       {@code localvar$<hash>$<modid>$<name>}（解析用 {@link HotfixFrameOwner#parse}，
 *       与帧分类【同一份】判据）；</li>
 *   <li>它被<b>被监控的关键方法</b>里的 {@code invokestatic} 调到（owner = 本类）；
 *       没给方法名时扫<b>除注入方法之外的所有方法</b>（见 {@link #plan} 的 {@code methodName}）；</li>
 *   <li>{@code modid == "shanhai"} ⇒ <b>我们自己的，原样保留</b>（按 {@code $shanhai$} 硬判，
 *       <b>不</b>按"声明类是不是 {@code net.minecraft}"判 —— 我们的 handler 也声明在原版类上）；</li>
 *   <li>其余 modid ⇒ 外部 handler，清空方法体。</li>
 * </ol>
 *
 * <h2>日志（逐字；必须能判"摘了几个、留了几个、字节从哪来"）</h2>
 * <pre>
 *   keyclass_restore class=net.minecraft.world.entity.LivingEntity method=m_21224_ stripped=1 \
 *        handlers=[handler$abc000$examplemod$getHealth] ours_kept=2 retransforms=1 bytes=baseline size=31902
 *   keyclass_restore_none class=… reason=no_external_handler stripped=0 ours_kept=2 retransforms=0 \
 *        bytes=baseline size=31902          ← 读过字节、确实没有外部 handler
 *   keyclass_restore_none class=… reason=no_class_bytes stripped=0 ours_kept=0 retransforms=0 bytes=none
 *                                         ← 🔴 三级回退全空：<b>没读到</b>，这个 0 不是结论
 *   keyclass_restore_refused class=… reason=blacklisted|no_instrumentation|class_not_loaded
 *   keyclass_restore_inline class=… stripped=1 handlers=[…] ours_kept=2 trigger=patch_write
 *        ← 并进写字节路径（{@link #stripForWrite}）时由 HotfixAutoCounter 打；没有外部 handler 时【不打】
 * </pre>
 * <p>负对照能力就在 {@code ours_kept=}：见到 {@code handler$xyz$shanhai$guardSetHealth} 时
 * 只计数、<b>绝不动它</b>（离线自证里那两条 ours 的 {@code method_info} 逐字节相等）。
 * <p>🆕 2026-11-21（P2）：{@code bytes=} 是"真 0 / 没读到"的分界线 —— 字节由
 * {@link HotfixBodyProbe#chainBytes(Class, String)} 三级回退取（基线快照 → 磁盘/jar →
 * classloader 资源），与整链抹除<b>同一份实现</b>。{@code ours_kept} 由 {@link #plan} 的
 * ⓪ 那一步独立数出来（{@link HotfixFrameOwner#isSelfName(String)}），<b>不再</b>依赖
 * "有没有调用点 / 有没有外部 handler"。
 * <p>🔴 交接路径 {@link #restoreForTriage} <b>不自己打这一行</b>（它放在 {@link Result#line}，
 * 由交接方打一次）：否则同一次手术会出现两条一模一样的 {@code keyclass_restore}，
 * 现场会误读成"恢复了两次"。{@link #restore} ／ {@link #restoreByName} 自己打。
 * <p>失败路径另外两种取值（诚实优先于"只准三选一"）：{@code reason=class_not_modifiable}、
 * {@code reason=surgery_failed:<detail>}、{@code reason=transformer_not_called}。
 *
 * <h2>准入：黑名单</h2>
 * <p>见 {@link #admit}。两条例外：
 * <ol>
 *   <li>六个已监控的关键类（{@link #KEY_CLASSES}）＋"本次溯源命中的关键方法所属类"
 *       可以穿过 {@code net.minecraft.} 这一条；</li>
 *   <li>{@code CodeSource == null} 的类<b>不能整体重建</b>（只能当 handler owner 被记录：
 *       没有 code source 就没有"恢复成哪一份"的稳定身份）。</li>
 * </ol>
 *
 * <h2>依赖面</h2>
 * {@code java.*} ＋ ASM ＋ 同包纯 JDK 类（{@link HotfixFrameOwner} / {@link HotfixPatchWhitelist} /
 * {@link HotfixMethodBodyPatch} / {@link HotfixForeignScan}）。没有 MC / Forge / log4j 类型
 * ⇒ 离线自检（{@code temp\hotfix\p2-keyclass-restore\}）加载的是<b>同一份</b>字节码。
 */
public final class HotfixKeyClassRestore {

    /** 已监控的六个关键类（顺序 = 日志/报告顺序）。 */
    public static final String[] KEY_CLASSES = {
            "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.server.level.ServerPlayer",
            "net.minecraft.world.entity.Entity",
            "net.minecraft.network.syncher.SynchedEntityData",
            "net.minecraft.client.multiplayer.ClientPacketListener",
            "net.minecraft.client.Minecraft"
    };

    /** 成功路径的日志名（用户点名的逐字行首）。 */
    public static final String LINE_RESTORE = "keyclass_restore";

    /** 无事可做的日志名。 */
    public static final String LINE_NONE = "keyclass_restore_none";

    /** 拒绝路径的日志名。 */
    public static final String LINE_REFUSED = "keyclass_restore_refused";

    /** {@code reason=}：有活干（{@link Plan#actionable()}）且写字节成功。 */
    public static final String REASON_OK = "ok";

    /** {@code reason=}：类里没有可摘的外部 handler（<b>扫过字节</b>之后才敢这么说）。 */
    public static final String REASON_NO_EXTERNAL = "no_external_handler";

    /**
     * 🆕 2026-11-21（P2）：<b>字节根本没拿到</b>（三级回退全空 ⇒ {@code bytes=none}）。
     *
     * <p>为什么必须与 {@link #REASON_NO_EXTERNAL} 分开：这两个 0 长得一模一样
     * （{@code stripped=0 ours_kept=0 retransforms=0}），但一个是"扫过了，确实没有"，
     * 另一个是"压根没读到" —— 把后者当成前者去读，就成了<b>假结论</b>
     * （现场就是这么误判的：{@code LivingEntity} 上明明有 9 个 {@code shanhai$} 方法）。
     *
     * <p>词面与 {@code HotfixChainWipe#REASON_NO_CLASS_BYTES} 相同（同一条链路上的同一个结论）。
     */
    public static final String REASON_NO_CLASS_BYTES = "no_class_bytes";

    /** {@code reason=}：包名黑名单 / 不在恢复目标集合 / 没有 code source。 */
    public static final String REASON_BLACKLISTED = "blacklisted";

    /** {@code reason=}：没拿到 {@link Instrumentation}。 */
    public static final String REASON_NO_INSTRUMENTATION = "no_instrumentation";

    /** {@code reason=}：类还没加载。 */
    public static final String REASON_CLASS_NOT_LOADED = "class_not_loaded";

    /** {@code reason=}：类不可重定义（数组 / 基本类型 / hidden / 加载器不允许）。 */
    public static final String REASON_CLASS_NOT_MODIFIABLE = "class_not_modifiable";

    /** {@code reason=} 前缀：字节写出来了但自证没过（绝不提交）。 */
    public static final String REASON_SURGERY_FAILED = "surgery_failed";

    /** {@code reason=}：{@code retransformClasses} 没有回调 transformer（类不可改 / 平台拒绝）。 */
    public static final String REASON_TRANSFORMER_NOT_CALLED = "transformer_not_called";

    /** 同一次 JVM 内，串行化所有恢复动作（{@code retransformClasses} 本身串行；这里防两个恢复互相踩）。 */
    private static final ReentrantLock LOCK = new ReentrantLock();

    /** 日志出口（游戏里由装配胶水接到统一 logger；离线自检接收集器）。 */
    public interface LineSink {
        void line(String text);
    }

    private static volatile LineSink sink;

    /** 反思缓存：{@code HotfixAutoCounter.chainLog(String)}（找不到 ⇒ {@code null}，永久）。 */
    private static volatile Method logMethod;

    private static volatile boolean logLookedUp;

    /**
     * 交接路径（{@link #restoreForTriage}）自己会打 {@link Result#line}，所以在那一次调用里
     * 抑制本类的内部日志 —— 否则同一行会出现两次（一次本类、一次交接方），
     * 现场会误读成"恢复了两次"。只作用于当前线程的这一次调用。
     */
    private static final ThreadLocal<Boolean> QUIET = new ThreadLocal<Boolean>();

    private HotfixKeyClassRestore() {
        // 纯静态
    }

    /**
     * 接上日志出口（幂等；传 {@code null} = 断开，退回"反射 {@code HotfixAutoCounter.chainLog} → stdout"）。
     *
     * <p>为什么要这个注入口：本类不该为了"打一行日志"把 5000 行的热改引擎拖进编译依赖
     * （那条链还会带上 {@code HotfixTriageTap} 等改动频繁的类）。游戏侧不接也能工作 —— 兜底反射。
     */
    public static void bind(LineSink newSink) {
        sink = newSink;
    }

    // ═══════════════════════════════ ① 认 handler（纯函数） ═══════════════════════════════

    /** 一个被认出来的 handler（方法名里带出来的 owner ＋ 清空方法体需要的读数）。 */
    public static final class Handler {

        /** 方法名（{@code handler$abc000$examplemod$getHealth}）。 */
        public final String name;

        /** 描述符（{@code (Lnet/…/LivingEntity;Lorg/…/CallbackInfoReturnable;)V}）。 */
        public final String desc;

        /** 方法名里的 modid 段（判据的核心）。 */
        public final String modid;

        /** {@code handler}／{@code localvar}（Mixin 的两种注入形态）。 */
        public final String kind;

        /** {@code true} = 我们自己的（{@code $shanhai$}），一个字节都不许动。 */
        public final boolean ours;

        /** 清空后要压栈的操作码；{@code -1} = void，什么都不压。 */
        public final int pushOpcode;

        /** 返回指令。 */
        public final int returnOpcode;

        /** 新方法体需要的栈槽数（0/1/2）。 */
        public final int slots;

        /** 原方法体的 {@code maxStack}（清空后保留，必要时抬到 {@link #slots}）。 */
        public final int maxStack;

        /** 原方法体的 {@code maxLocals}（原样保留）。 */
        public final int maxLocals;

        Handler(String name, String desc, String modid, String kind, boolean ours,
                int pushOpcode, int returnOpcode, int slots, int maxStack, int maxLocals) {
            this.name = name;
            this.desc = desc;
            this.modid = modid;
            this.kind = kind;
            this.ours = ours;
            this.pushOpcode = pushOpcode;
            this.returnOpcode = returnOpcode;
            this.slots = slots;
            this.maxStack = maxStack;
            this.maxLocals = maxLocals;
        }

        /** {@code name+desc}（方法在类里的唯一键）。 */
        public String key() {
            return name + desc;
        }

        @Override
        public String toString() {
            return name + (ours ? "(ours)" : "(" + modid + ")");
        }
    }

    /** 一次"认 handler"的只读计划（不写字节、不碰 JVM）。 */
    public static final class Plan {

        /** 内部名（{@code net/minecraft/…}）。 */
        public final String internalName;

        /** 源级名（{@code net.minecraft.…}）。 */
        public final String className;

        /** {@link #REASON_NO_EXTERNAL} 或 {@code ok}；读不动时是 {@code bad_class_bytes} 等。 */
        public final String reason;

        /** 要清空的外部 handler（按 call site 出现顺序）。 */
        public final List<Handler> external;

        /** 认出来的、属于我们自己的 handler（只计数、不动）。 */
        public final List<Handler> ours;

        /** 方法体已经是空体的外部 handler（上一次恢复留下的；这次不重复改）。 */
        public final List<Handler> alreadyEmpty;

        /** 调用点所在的被监控方法（去重后按出现顺序；日志 {@code method=}）。 */
        public final String method;

        /** 写字节阶段的自证失败原因（{@code null} = 没失败）；只给调用方判读，不直接进日志。 */
        String failure;

        Plan(String internalName, String className, String reason, List<Handler> external,
             List<Handler> ours, List<Handler> alreadyEmpty, String method, String failure) {
            this.internalName = internalName;
            this.className = className;
            this.reason = reason;
            this.external = external;
            this.ours = ours;
            this.alreadyEmpty = alreadyEmpty;
            this.method = method;
            this.failure = failure;
        }

        /** 这一份计划有活干吗（有需要清空的外部 handler）。 */
        public boolean actionable() {
            return !external.isEmpty();
        }

        /** {@code handlers=[a,b]}（只列被摘的；空 ⇒ {@code []}）。 */
        public String handlersList() {
            return joinHandlers(external);
        }

        /** 方法体 {@code name+desc ⇒ Handler}（写字节时按精确描述符命中）。 */
        Map<String, Handler> byKey() {
            final Map<String, Handler> map = new LinkedHashMap<String, Handler>(external.size() * 2);
            for (int i = 0; i < external.size(); i++) {
                final Handler h = external.get(i);
                map.put(h.key(), h);
            }
            return map;
        }
    }

    /**
     * 在 class 字节里认出"被监控的关键方法调到的 mixin handler"（<b>纯只读</b>）。
     *
     * @param classBytes 初始字节（transformer 交下来的那一份）
     * @param methodName 被监控的关键方法名；{@code null}/空 ⇒ 扫<b>除注入方法外的所有方法</b>
     *                   （触发源只给得出"类 + handler 名"、给不出调用者时用这条路；
     *                   判据仍然是"被一个真实方法 invokestatic 调到"）
     * @return 计划；类字节读不动时 {@code reason} 是具体原因、{@code external} 为空
     */
    public static Plan plan(byte[] classBytes, String methodName) {
        final ClassNode cn = HotfixMethodBodyPatch.read(classBytes);
        if (cn == null) {
            return new Plan("?", "?", "class_reader_failed", emptyHandlers(), emptyHandlers(),
                    emptyHandlers(), "", null);
        }
        final String source = cn.name == null ? "?" : cn.name.replace('/', '.');
        // ---- ⓪ 🆕 2026-11-21（P2）：先把"我们自己的方法"独立数出来
        //   🔴 这一步必须与"有没有调用点 / 有没有外部 handler"完全无关 —— 否则
        //     `ours_kept` 又成了别的结论的副产品（旧实现就只在 actionable 时才填这个数，
        //     所以 stripped=0 时它恒为 0，等于没有信息量）。
        final List<Handler> ours = new ArrayList<Handler>(4);
        addSelfMethods(cn, ours);
        // ---- ① 候选：名字符合 Mixin 注入形态的方法
        final Map<String, MethodNode> candidates = new LinkedHashMap<String, MethodNode>(8);
        final Map<String, HotfixFrameOwner.Owner> owners =
                new LinkedHashMap<String, HotfixFrameOwner.Owner>(8);
        for (int i = 0; i < cn.methods.size(); i++) {
            final MethodNode mn = cn.methods.get(i);
            if (mn == null) {
                continue;
            }
            final HotfixFrameOwner.Owner o = HotfixFrameOwner.parse(mn.name);
            if (o == null) {
                continue;
            }
            candidates.put(mn.name + mn.desc, mn);
            owners.put(mn.name + mn.desc, o);
        }
        if (candidates.isEmpty()) {
            return new Plan(cn.name, source, REASON_NO_EXTERNAL, emptyHandlers(), ours,
                    emptyHandlers(), "", null);
        }
        // ---- ② 找调用点：invokestatic 到本类里的候选 handler
        final String wanted = methodName == null ? "" : methodName.trim();
        final Set<String> callerNames = new LinkedHashSet<String>();
        final Map<String, String> called = new LinkedHashMap<String, String>(); // handler key ⇒ 第一个 caller
        for (int i = 0; i < cn.methods.size(); i++) {
            final MethodNode caller = cn.methods.get(i);
            if (caller == null || !inScope(caller, wanted, candidates.containsKey(caller.name + caller.desc))) {
                continue;
            }
            if (caller.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn = caller.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getType() != AbstractInsnNode.METHOD_INSN) {
                    continue;
                }
                final MethodInsnNode mi = (MethodInsnNode) insn;
                if (mi.getOpcode() != Opcodes.INVOKESTATIC
                        || !cn.name.equals(mi.owner)
                        || !candidates.containsKey(mi.name + mi.desc)) {
                    continue;
                }
                final String key = mi.name + mi.desc;
                if (!called.containsKey(key)) {
                    called.put(key, caller.name);
                    callerNames.add(caller.name);
                }
            }
        }
        if (called.isEmpty()) {
            return new Plan(cn.name, source, REASON_NO_EXTERNAL, emptyHandlers(), ours,
                    emptyHandlers(), "", null);
        }
        // ---- ③ 分类：$shanhai$ ⇒ 保留；其余 ⇒ 外部（已空体的单独记账，不重复改）
        //   🔴 我们的那几个已经在 ⓪ 里数过了（判据同一套），这里绝不许再 add 一次。
        final List<Handler> external = new ArrayList<Handler>(4);
        final List<Handler> alreadyEmpty = new ArrayList<Handler>(2);
        for (Map.Entry<String, String> e : called.entrySet()) {
            final MethodNode mn = candidates.get(e.getKey());
            final HotfixFrameOwner.Owner o = owners.get(e.getKey());
            if (mn == null || o == null) {
                continue;
            }
            final int[] dflt = defaultFor(mn.desc);
            if (dflt == null) {
                continue;   // 认不出返回类型 ⇒ 不猜（宁可不动这个 handler）
            }
            final boolean ourHandler = HotfixFrameOwner.SELF_MODID.equals(o.modid);
            final Handler h = new Handler(mn.name, mn.desc, o.modid, o.kind, ourHandler,
                    dflt[0], dflt[1], dflt[2], mn.maxStack, mn.maxLocals);
            if (ourHandler) {
                continue;   // ⓪ 已经计入 ours（只计数，绝不动字节）
            }
            if (!patchable(mn)) {
                continue;   // abstract/native/synchronized/带未知方法属性 ⇒ 不碰
            }
            if (isEmptyBody(mn, dflt[0], dflt[1])) {
                alreadyEmpty.add(h);
                continue;
            }
            external.add(h);
        }
        final String reason = external.isEmpty() ? REASON_NO_EXTERNAL : REASON_OK;
        return new Plan(cn.name, source, reason, external, ours, alreadyEmpty,
                joinSet(callerNames), null);
    }

    /**
     * 🆕 2026-11-21（P2）：把类里<b>名字判定为我们自己的</b>方法全数出来（只读、只计数）。
     *
     * <p>判据见 {@link HotfixFrameOwner#isSelfName(String)}：{@code handler$<hash>$shanhai$<name>}、
     * {@code localvar$<hash>$shanhai$<name>}、{@code shanhai$<name>}（{@code @Unique} 形态），
     * 以及 {@code name} 段后面再挂 {@code $mixin} 之类的后缀变体。
     *
     * <p>为什么不能只数"被 {@code invokestatic} 引到的 handler"（旧实现的第二个坑）：
     * ① {@code @Unique} 形态根本不以 {@code handler$} 开头，{@link HotfixFrameOwner#parse(String)}
     * 对它返回 {@code null} ⇒ 从候选集起就被漏掉；② 没有调用点的 handler（例如对手/我们把调用点
     * 摘掉之后）也会漏。两者都让 {@code ours_kept} 变成"看起来是 0"。
     *
     * @param cn   已读出来的类
     * @param ours 输出（按方法表顺序追加；已有同名同描述符的跳过）
     */
    private static void addSelfMethods(ClassNode cn, List<Handler> ours) {
        if (cn == null || cn.methods == null) {
            return;
        }
        for (int i = 0; i < cn.methods.size(); i++) {
            final MethodNode mn = cn.methods.get(i);
            if (mn == null || mn.name == null || !HotfixFrameOwner.isSelfName(mn.name)) {
                continue;
            }
            final String key = mn.name + mn.desc;
            boolean dup = false;
            for (int k = 0; k < ours.size(); k++) {
                if (ours.get(k).key().equals(key)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }
            final HotfixFrameOwner.Owner o = HotfixFrameOwner.parse(mn.name);
            final int[] dflt = defaultFor(mn.desc);
            ours.add(new Handler(mn.name, mn.desc, HotfixFrameOwner.SELF_MODID,
                    o == null ? HotfixFrameOwner.KIND_UNIQUE : o.kind, true,
                    dflt == null ? -1 : dflt[0],
                    dflt == null ? Opcodes.RETURN : dflt[1],
                    dflt == null ? 0 : dflt[2],
                    mn.maxStack, mn.maxLocals));
        }
    }

    // ═══════════════════════════════ ② 清空方法体（纯函数） ═══════════════════════════════

    /** 写字节的结果。 */
    public static final class StripResult {

        /** 新的 class 字节；{@code null} = 被拒绝 / 没活干（原因见 {@link #reason}）。 */
        public final byte[] bytes;

        /** {@code ok} 或机器可判的失败原因。 */
        public final String reason;

        StripResult(byte[] bytes, String reason) {
            this.bytes = bytes;
            this.reason = reason;
        }

        public boolean ok() {
            return bytes != null;
        }

        @Override
        public String toString() {
            return "ok=" + ok() + " reason=" + reason
                    + (bytes == null ? "" : " len=" + bytes.length);
        }
    }

    /**
     * 一趟把 {@link Plan#external} 的方法体清成空体（<b>同一个 reader / writer，只遍历一次</b>）。
     *
     * @param classBytes 初始字节
     * @param plan       {@link #plan} 的产物
     * @return 成功时是新字节；没有外部 handler ⇒ {@code reason=no_external_handler}；
     *         自证没过 ⇒ {@code reason=<具体项>} 且<b>绝不返回半份字节</b>
     */
    public static StripResult strip(byte[] classBytes, Plan plan) {
        if (classBytes == null || plan == null) {
            return new StripResult(null, "bad_input");
        }
        if (!plan.actionable()) {
            return new StripResult(null, REASON_NO_EXTERNAL);
        }
        final Map<String, Handler> byKey = plan.byKey();
        final byte[] out;
        try {
            final ClassReader cr = new ClassReader(classBytes);
            // (reader, 0)：常量池整段复制；没被替换的方法由 ASM 原样复制 method_info
            final ClassWriter cw = new ClassWriter(cr, 0);
            final ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    if (mv == null) {
                        return null;
                    }
                    final Handler h = byKey.get(name + desc);
                    if (h == null) {
                        return mv;      // ← 原样返回 MethodWriter：ASM 会整段复制这个方法
                    }
                    return new EmptyBody(mv, h);
                }
            };
            cr.accept(cv, 0);
            out = cw.toByteArray();
        } catch (Throwable t) {
            plan.failure = "class_writer_failed:" + t.getClass().getSimpleName();
            return new StripResult(null, plan.failure);
        }
        final String bad;
        try {
            bad = verify(classBytes, out, plan);
        } catch (Throwable t) {
            plan.failure = "verify_exception:" + t.getClass().getSimpleName();
            return new StripResult(null, plan.failure);
        }
        if (bad != null) {
            plan.failure = bad;
            return new StripResult(null, bad);
        }
        return new StripResult(out, REASON_OK);
    }

    /**
     * <b>内联摘除</b>的结果（给我们自己的写字节路径用；{@code null} = 没有可摘的外部 handler）。
     *
     * <p>与 {@link StripResult} 的区别：这里带齐了日志要的三个读数（{@code stripped/handlers/ours_kept}），
     * 调用方不必再拿 {@link Plan}。
     */
    public static final class Inline {

        /** 摘完之后的新字节（可直接作为后续 patch 的起点）。 */
        public final byte[] bytes;

        /** 源级类名（{@code plan.className}）。 */
        public final String className;

        /** 调用点所在的方法（{@code plan.method}）。 */
        public final String method;

        /** 摘掉的外部 handler 个数。 */
        public final int stripped;

        /** 保留的我们自己的 handler 个数。 */
        public final int oursKept;

        /** {@code [name,…]}。 */
        public final String handlers;

        Inline(byte[] bytes, String className, String method, int stripped, int oursKept, String handlers) {
            this.bytes = bytes;
            this.className = className;
            this.method = method;
            this.stripped = stripped;
            this.oursKept = oursKept;
            this.handlers = handlers;
        }
    }

    /**
     * 🆕 <b>内联摘除</b>：在我们自己的 {@code retransformClasses} 写字节路径里，先把外部 handler 清空。
     *
     * <p>为什么要并进写路径（而不是常驻 transformer）：{@code retransform} 的输入永远是初始字节，
     * 一次性的恢复会被<b>我们自己的下一次热改</b>请回来；而常驻 transformer 会让热改引擎的整类
     * 指纹对不上（触发 {@code patch_lost}）。并进写路径 = 每一次写字节都顺手把对手的 handler 摘掉，
     * 不新增 transformer、不动基线语义。
     *
     * <p>🔴 <b>摘不到一律原样放行</b>：没有外部 handler / 被拒绝 / 自证不过 ⇒ {@code null}，
     * 调用方必须继续用原字节打补丁（热改不许因为这一刀失败）。
     *
     * @param classBytes 这一趟写字节的起点（基线快照或 JVM 交下来的初始字节）
     * @return 摘好了 ⇒ {@link Inline}；无事可做/不能做 ⇒ {@code null}
     */
    public static Inline stripForWrite(byte[] classBytes) {
        return stripForWrite(classBytes, null);
    }

    /**
     * 带<b>跳过清单</b>的 {@link #stripForWrite(byte[])}：清单里的方法名是这一趟<b>要打补丁的目标</b>。
     *
     * <p>🔴 为什么必须跳过：如果补丁目标本身就是一个外部 handler（违规栈里那一帧就是它），
     * 先把它摘成空体会让 {@link HotfixMethodBodyPatch#patch} 报 {@code no_code}，
     * 整次热改 abort —— 那就成了"摘对手把我们的补丁也摘掉了"。
     * 跳过之后交给补丁自己把它换成默认返回（更强的处理），日志里 {@code stripped} 也不会计它。
     *
     * @param classBytes      这一趟写字节的起点
     * @param skipMethodNames 这一趟要打补丁的方法名（可空；只按名字跳，不按描述符）
     * @return 摘好了 ⇒ {@link Inline}；无事可做/不能做 ⇒ {@code null}
     */
    public static Inline stripForWrite(byte[] classBytes, java.util.Set<String> skipMethodNames) {
        if (classBytes == null) {
            return null;
        }
        final Plan plan = plan(classBytes, null);
        if (skipMethodNames != null && !skipMethodNames.isEmpty()) {
            for (java.util.Iterator<Handler> it = plan.external.iterator(); it.hasNext(); ) {
                if (skipMethodNames.contains(it.next().name)) {
                    it.remove();
                }
            }
        }
        if (!plan.actionable()) {
            return null;        // 没有外部 handler ⇒ 不打日志、不动字节
        }
        final StripResult stripped = strip(classBytes, plan);
        if (!stripped.ok()) {
            return null;        // 拒绝/自证不过 ⇒ 原样放行（绝不让热改失败）
        }
        return new Inline(stripped.bytes, plan.className, plan.method,
                plan.external.size(), plan.ours.size(), plan.handlersList());
    }

    /**
     * 换方法体的 visitor：<b>吞掉所有 Code 事件</b>，只在 {@code visitMaxs} 里发"默认值 + return"。
     *
     * <p>注解 / 签名 / 参数 / 异常表 / 方法属性一律不覆盖（父类会把它们转发给 {@code mv}）。
     */
    private static final class EmptyBody extends MethodVisitor {

        private final Handler h;

        EmptyBody(MethodVisitor mv, Handler h) {
            super(Opcodes.ASM9, mv);
            this.h = h;
        }

        @Override
        public void visitInsn(int opcode) {
            // 吞掉
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            // 吞掉
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            // 吞掉
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            // 吞掉
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            // 吞掉
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            // 吞掉
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm,
                                           Object... bsmArgs) {
            // 吞掉
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            // 吞掉
        }

        @Override
        public void visitLabel(Label label) {
            // 吞掉
        }

        @Override
        public void visitLdcInsn(Object value) {
            // 吞掉
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            // 吞掉
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            // 吞掉
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            // 吞掉
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            // 吞掉
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath,
                                                                     String descriptor, boolean visible) {
            // 吞掉（挂在已消失的指令上）
            return null;
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            // 吞掉：新方法体没有分支，异常表必须为空
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath,
                                                                         String descriptor, boolean visible) {
            // 吞掉
            return null;
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                                       Label start, Label end, int index) {
            // 吞掉：局部变量表指向的 Label 已经不在指令表里
        }

        @Override
        public org.objectweb.asm.AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath,
                                                                              Label[] start, Label[] end,
                                                                              int[] index, String descriptor,
                                                                              boolean visible) {
            // 吞掉
            return null;
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            // 吞掉（行号表也是指向 Label 的）
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack,
                               Object[] stack) {
            // 吞掉：直线代码没有栈帧
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // 新方法体在这里发：此时 visitCode 已经转发过，指令仍然落在 Code 里、在 visitMaxs 之前
            if (h.pushOpcode >= 0) {
                super.visitInsn(h.pushOpcode);
            }
            super.visitInsn(h.returnOpcode);
            // 🔴 保留原 maxStack（清空后偏大合法）；只有原值放不下默认返回时才抬到最小需要值
            super.visitMaxs(Math.max(maxStack, h.slots), maxLocals);
        }
    }

    // ═══════════════════════════════ ③ 写完之后的自证 ═══════════════════════════════

    /**
     * 输出自证；通过 ⇒ {@code null}，不通过 ⇒ 机器可判的原因。
     *
     * <p>五条：① 输出可读；② 类形状不变；③ 每个被清空的 handler 恰好是"默认值 + return"且
     * try-catch / 局部变量表为空；④ <b>其余每个方法</b>的指令指纹逐字不变；⑤ 方法表条数不变。
     */
    private static String verify(byte[] before, byte[] after, Plan plan) {
        final ClassNode a = HotfixMethodBodyPatch.read(after);
        if (a == null) {
            return "writer_output_unreadable";
        }
        final ClassNode b = HotfixMethodBodyPatch.read(before);
        if (b == null) {
            return "input_unreadable";
        }
        if (!shape(b).equals(shape(a))) {
            return "shape_changed";
        }
        if (b.methods.size() != a.methods.size()) {
            return "method_count_changed";
        }
        final Map<String, MethodNode> afterMethods = new LinkedHashMap<String, MethodNode>(a.methods.size() * 2);
        for (int i = 0; i < a.methods.size(); i++) {
            final MethodNode mn = a.methods.get(i);
            afterMethods.put(mn.name + mn.desc, mn);
        }
        // ③ 被清空的 handler：恰好是默认返回
        for (int i = 0; i < plan.external.size(); i++) {
            final Handler h = plan.external.get(i);
            final MethodNode mn = afterMethods.get(h.key());
            if (mn == null) {
                return "stripped_handler_missing:" + h.name;
            }
            final int expected = h.pushOpcode >= 0 ? 2 : 1;
            if (mn.instructions == null || mn.instructions.size() != expected) {
                return "stripped_body_size:" + h.name;
            }
            if (h.pushOpcode >= 0 && mn.instructions.get(0).getOpcode() != h.pushOpcode) {
                return "stripped_body_push:" + h.name;
            }
            if (mn.instructions.get(expected - 1).getOpcode() != h.returnOpcode) {
                return "stripped_body_return:" + h.name;
            }
            if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) {
                return "stripped_handler_trycatch:" + h.name;
            }
            if (mn.localVariables != null && !mn.localVariables.isEmpty()) {
                return "stripped_handler_localvars:" + h.name;
            }
            if (mn.maxLocals != h.maxLocals) {
                return "stripped_handler_maxlocals:" + h.name;
            }
            if (mn.maxStack < h.slots) {
                return "stripped_handler_maxstack:" + h.name;
            }
            if (mn.maxStack < h.maxStack) {
                return "stripped_handler_maxstack_shrunk:" + h.name;
            }
        }
        // ④ 其余方法：指令 / 异常表 / 局部变量 / maxs 指纹逐字不变
        for (int i = 0; i < b.methods.size(); i++) {
            final MethodNode mn = b.methods.get(i);
            final String key = mn.name + mn.desc;
            if (plan.byKey().containsKey(key)) {
                continue;
            }
            final MethodNode now = afterMethods.get(key);
            if (now == null) {
                return "method_missing:" + key;
            }
            if (!fingerprint(mn).equals(fingerprint(now))) {
                return "other_method_changed:" + key;
            }
        }
        return null;
    }

    /** 类形状（名字 / 版本 / 访问 / 父子 / 接口 / 字段表 / 方法表签名）。 */
    private static String shape(ClassNode cn) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append(cn.name).append('|').append(cn.access).append('|').append(cn.version)
                .append('|').append(cn.superName).append('|').append(cn.interfaces).append('|')
                .append(cn.signature).append('|').append(cn.sourceFile).append("|F[");
        for (int i = 0; i < cn.fields.size(); i++) {
            sb.append(cn.fields.get(i).name).append(':').append(cn.fields.get(i).desc)
                    .append(':').append(cn.fields.get(i).access).append(',');
        }
        sb.append("]M[");
        for (int i = 0; i < cn.methods.size(); i++) {
            final MethodNode mn = cn.methods.get(i);
            sb.append(mn.name).append(mn.desc).append(':').append(mn.access).append(',');
        }
        return sb.append(']').toString();
    }

    /**
     * 一个方法的结构指纹（指令序列 + 操作数 + 跳转目标序 + try-catch + 局部变量 + maxs）。
     *
     * <p>只用来判"这个方法有没有被动过"，不需要覆盖注解（未替换的方法整段按原始字节复制，
     * 离线自证另做了 {@code method_info} 区间比对）。
     */
    private static String fingerprint(MethodNode mn) {
        final StringBuilder sb = new StringBuilder(128 + (mn.instructions == null ? 0 : mn.instructions.size() * 6));
        sb.append(mn.access).append('/').append(mn.maxStack).append('/').append(mn.maxLocals).append('/');
        final Map<LabelNode, Integer> labels = new IdentityHashMap<LabelNode, Integer>();
        if (mn.instructions != null) {
            int n = 0;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getType() == AbstractInsnNode.LABEL) {
                    labels.put((LabelNode) insn, Integer.valueOf(n++));
                }
            }
        }
        if (mn.instructions != null) {
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                switch (insn.getType()) {
                    case AbstractInsnNode.INSN:
                        sb.append(insn.getOpcode()).append(';');
                        break;
                    case AbstractInsnNode.INT_INSN:
                        sb.append(insn.getOpcode()).append('#').append(((IntInsnNode) insn).operand).append(';');
                        break;
                    case AbstractInsnNode.VAR_INSN:
                        sb.append(insn.getOpcode()).append('$').append(((VarInsnNode) insn).var).append(';');
                        break;
                    case AbstractInsnNode.TYPE_INSN:
                        sb.append(insn.getOpcode()).append('^').append(((TypeInsnNode) insn).desc).append(';');
                        break;
                    case AbstractInsnNode.FIELD_INSN: {
                        final FieldInsnNode f = (FieldInsnNode) insn;
                        sb.append(insn.getOpcode()).append('.').append(f.owner).append('/')
                                .append(f.name).append(':').append(f.desc).append(';');
                        break;
                    }
                    case AbstractInsnNode.METHOD_INSN: {
                        final MethodInsnNode m = (MethodInsnNode) insn;
                        sb.append(insn.getOpcode()).append('.').append(m.owner).append('/')
                                .append(m.name).append(m.desc).append('/').append(m.itf).append(';');
                        break;
                    }
                    case AbstractInsnNode.INVOKE_DYNAMIC_INSN: {
                        final InvokeDynamicInsnNode d = (InvokeDynamicInsnNode) insn;
                        sb.append(insn.getOpcode()).append('.').append(d.name).append(d.desc).append('/')
                                .append(d.bsm == null ? "?" : d.bsm.getOwner() + "/" + d.bsm.getName()).append(';');
                        break;
                    }
                    case AbstractInsnNode.JUMP_INSN:
                        sb.append(insn.getOpcode()).append('>')
                                .append(labels.get(((JumpInsnNode) insn).label)).append(';');
                        break;
                    case AbstractInsnNode.LABEL:
                        sb.append('L').append(labels.get(insn)).append(';');
                        break;
                    case AbstractInsnNode.LDC_INSN:
                        sb.append(insn.getOpcode()).append('=')
                                .append(String.valueOf(((LdcInsnNode) insn).cst)).append(';');
                        break;
                    case AbstractInsnNode.IINC_INSN: {
                        final IincInsnNode ii = (IincInsnNode) insn;
                        sb.append(insn.getOpcode()).append('$').append(ii.var).append('+')
                                .append(ii.incr).append(';');
                        break;
                    }
                    case AbstractInsnNode.TABLESWITCH_INSN: {
                        final TableSwitchInsnNode t = (TableSwitchInsnNode) insn;
                        sb.append("TS").append(t.min).append('/').append(t.max).append('>')
                                .append(labels.get(t.dflt)).append(';');
                        break;
                    }
                    case AbstractInsnNode.LOOKUPSWITCH_INSN: {
                        final LookupSwitchInsnNode t = (LookupSwitchInsnNode) insn;
                        sb.append("LS").append('>').append(labels.get(t.dflt)).append(';');
                        break;
                    }
                    case AbstractInsnNode.MULTIANEWARRAY_INSN: {
                        final MultiANewArrayInsnNode m = (MultiANewArrayInsnNode) insn;
                        sb.append(insn.getOpcode()).append('[').append(m.desc).append('/')
                                .append(m.dims).append(';');
                        break;
                    }
                    case AbstractInsnNode.FRAME: {
                        final FrameNode f = (FrameNode) insn;
                        sb.append('F').append(f.type).append('/').append(sizeOf(f.local)).append('/')
                                .append(sizeOf(f.stack)).append(';');
                        break;
                    }
                    case AbstractInsnNode.LINE:
                        sb.append('N').append(((LineNumberNode) insn).line).append(';');
                        break;
                    default:
                        sb.append('?').append(insn.getType()).append(';');
                        break;
                }
            }
        }
        sb.append("TC[").append(mn.tryCatchBlocks == null ? 0 : mn.tryCatchBlocks.size()).append(']');
        if (mn.tryCatchBlocks != null) {
            for (int i = 0; i < mn.tryCatchBlocks.size(); i++) {
                final TryCatchBlockNode t = mn.tryCatchBlocks.get(i);
                sb.append(labels.get(t.start)).append('-').append(labels.get(t.end)).append('-')
                        .append(labels.get(t.handler)).append(':').append(t.type).append(';');
            }
        }
        sb.append("LV[").append(mn.localVariables == null ? 0 : mn.localVariables.size()).append(']');
        if (mn.localVariables != null) {
            for (int i = 0; i < mn.localVariables.size(); i++) {
                final LocalVariableNode v = mn.localVariables.get(i);
                sb.append(v.name).append(':').append(v.desc).append(':').append(v.index).append(';');
            }
        }
        return sb.toString();
    }

    private static int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    // ═══════════════════════════════ ④ 恢复动作（真 retransform，一次） ═══════════════════════════════

    /** 一次恢复的结论（离线自证直接读这些字段）。 */
    public static final class Result {

        /** {@code true} = 真的把"清空后的字节"提交给了 JVM。 */
        public final boolean ok;

        /** 源级类名。 */
        public final String className;

        /** 调用点所在的方法（日志 {@code method=}）。 */
        public final String method;

        /** 摘掉的外部 handler 个数。 */
        public final int stripped;

        /** 保留的我们自己的 handler 个数（负对照：{@code $shanhai$} 一个都不许动）。 */
        public final int oursKept;

        /** 这次真的调了几次 {@code retransformClasses}（成功路径恒为 1）。 */
        public final int retransforms;

        /** {@code ok} 或拒绝/无事可做的原因。 */
        public final String reason;

        /** {@code [name,…]}（成功路径；否则 {@code []}）。 */
        public final String handlers;

        /** 失败细节（自证哪一条没过 / 异常名）；{@code null} = 没有。 */
        public final String detail;

        /**
         * 🆕 2026-11-21（P2）：这一趟的字节从哪来 —— {@code baseline}／{@code disk}／
         * {@code resource}／{@code none}（见 {@link HotfixBodyProbe.Sourced}）。
         */
        public final String bytesSource;

        /** 🆕 这一趟拿到的字节数（{@code 0} = 没拿到）。 */
        public final int bytesSize;

        /** 真正打进日志的那一行（不含模块前缀）。 */
        public final String line;

        Result(boolean ok, String className, String method, int stripped, int oursKept,
               int retransforms, String reason, String handlers, String detail, String line,
               String bytesSource, int bytesSize) {
            this.ok = ok;
            this.className = className;
            this.method = method;
            this.stripped = stripped;
            this.oursKept = oursKept;
            this.retransforms = retransforms;
            this.reason = reason;
            this.handlers = handlers;
            this.detail = detail;
            this.line = line;
            this.bytesSource = bytesSource;
            this.bytesSize = bytesSize;
        }

        /**
         * 日志 {@code bytes=} 后面那一截：{@code baseline size=31902} / {@code none}。
         *
         * <p>🔴 "真 0"与"没读到"的分界线就在这个字段上：{@code size>0} 时
         * {@code ours_kept=0} 才是结论。
         */
        public String bytesTag() {
            return bytesSize > 0 && bytesSource != null && !HotfixBodyProbe.SRC_NONE.equals(bytesSource)
                    ? bytesSource + " size=" + bytesSize : HotfixBodyProbe.SRC_NONE;
        }

        @Override
        public String toString() {
            return "ok=" + ok + " class=" + className + " method=" + method
                    + " stripped=" + stripped + " ours_kept=" + oursKept
                    + " retransforms=" + retransforms + " reason=" + reason
                    + " bytes=" + bytesTag()
                    + (detail == null ? "" : " detail=" + detail);
        }
    }

    /**
     * 恢复一个<b>已加载</b>的关键类（触发源 ①／② 用；调用方必须已在工作线程上）。
     *
     * @param inst   热改引擎的 {@link Instrumentation}
     * @param target 目标类（{@code null} ⇒ {@code class_not_loaded}）
     * @param method 被监控的关键方法名；{@code null}/空 ⇒ 扫所有真实方法（见 {@link #plan}）
     */
    public static Result restore(Instrumentation inst, Class<?> target, String method) {
        return restore0(inst, target, method, false);
    }

    /**
     * 本次<b>溯源命中的关键方法所属类</b>的恢复入口（允许它穿过 {@code net.minecraft.} 黑名单）。
     *
     * <p>只有这条路上 {@code net.minecraft.} 里"不是那六个"的类才可能被整体重建。
     *
     * <p>🔴 <b>本方法不写日志</b>：它把逐字行放在 {@link Result#line}，由交接方（例如
     * {@code HotfixAutoCounter#chainHandoff}）打一次 —— 否则同一行会出现两次。
     */
    public static Result restoreForTriage(Instrumentation inst, Class<?> target, String method) {
        QUIET.set(Boolean.TRUE);
        try {
            return restore0(inst, target, method, true);
        } finally {
            QUIET.remove();
        }
    }

    /**
     * 按类名恢复（手动 {@code /shanhai_add hotfix restore <类名>} 用；只接受那六个关键类）。
     *
     * @param arg 全限定类名，或 {@code 类名#方法名}
     */
    public static Result restoreByName(Instrumentation inst, String arg, String methodHint) {
        final String raw = arg == null ? "" : arg.trim();
        if (raw.isEmpty()) {
            return refuse("?", REASON_CLASS_NOT_LOADED, 0, null);
        }
        final int hash = raw.indexOf('#');
        final String className = hash >= 0 ? raw.substring(0, hash) : raw;
        final String method = hash >= 0 ? raw.substring(hash + 1) : methodHint;
        if (!isKeyClass(className)) {
            // 手动路径不放开 net.minecraft 里其它的类（也不放开第三方类）——黑名单的第一道
            return refuse(className, REASON_BLACKLISTED, 0, "not_a_key_class");
        }
        if (inst == null) {
            return refuse(className, REASON_NO_INSTRUMENTATION, 0, null);
        }
        final Class<?> target = findLoaded(inst, className);
        if (target == null) {
            return refuse(className, REASON_CLASS_NOT_LOADED, 0, null);
        }
        return restore0(inst, target, method, false);
    }

    private static Result restore0(Instrumentation inst, Class<?> target, String method, boolean triageHit) {
        final String className = target == null ? "?" : target.getName();
        // ---- ① 三道初级门（顺序：拿不到工具 > 类没加载 > 黑名单；每条都打逐字日志）
        if (inst == null) {
            return refuse(className, REASON_NO_INSTRUMENTATION, 0, null);
        }
        if (target == null) {
            return refuse(className, REASON_CLASS_NOT_LOADED, 0, null);
        }
        final String codeSource = HotfixForeignScan.codeSourceOf(target);
        if (!admit(className, codeSource, isKeyClass(className) || triageHit)) {
            return refuse(className, REASON_BLACKLISTED, 0,
                    codeSource == null || codeSource.isEmpty() ? "code_source_null" : "not_restore_target");
        }
        try {
            if (!inst.isModifiableClass(target)) {
                return refuse(className, REASON_CLASS_NOT_MODIFIABLE, 0, null);
            }
        } catch (Throwable t) {
            return refuse(className, REASON_CLASS_NOT_MODIFIABLE, 0, t.getClass().getSimpleName());
        }
        // ---- ② 🆕 2026-11-21（P2）：<b>先把字节拿到手</b>（三级回退，与整链抹除同一份实现）
        //   为什么要抢在"看方法名"之前：旧实现在这里只看反射方法名，一个外部 handler 都没有就直接
        //   返回 —— 于是`ours_kept` 恒为 0，而那个 0 是"没读过字节"，不是"类里没有我们的方法"。
        //   现在无 bytes 可读 ⇒ reason=no_class_bytes bytes=none；读到 bytes ⇒ 打 bytes=<来源> size=<N>。
        final HotfixBodyProbe.Sourced src = HotfixBodyProbe.chainBytes(target, className);
        final boolean haveBytes = src != null && src.ok();
        int scannedExternal = 0;
        int scannedOurs = 0;
        if (haveBytes) {
            final Plan scan = plan(src.bytes, method);
            scannedExternal = scan.external.size();
            scannedOurs = scan.ours.size();
        }
        // ---- ③ 再决定要不要 retransform：一个外部 handler 都没有 ⇒ 根本不必调
        //   🔴 这一条是防破坏的：一次"没活干"的 retransform 会把类退回初始字节（P2 实测：全 null = undo），
        //      等于顺手撤掉我们自己的运行期补丁。所以无事可做时【一次都不调】。
        //   判据从"只看反射"扩成"字节扫描 ∪ 反射"：任一条说有，就交给手术
        //   （磁盘/资源那两级是 mixin 之前的字节，只看它们会漏掉真正的外部 handler）。
        final Boolean named = hasExternalHandlerName(target);
        final boolean externalByScan = scannedExternal > 0;
        if (!externalByScan && Boolean.FALSE.equals(named)) {
            return none(className, haveBytes ? REASON_NO_EXTERNAL : REASON_NO_CLASS_BYTES,
                    0, scannedOurs, src, haveBytes ? null : "three_sources_empty");
        }
        // ---- ④ 一趟：注册 → retransform 一次 → 摘 transformer
        final Request req = new Request(target.getName().replace('.', '/'), method);
        final RestoreTransformer tx = new RestoreTransformer(req);
        boolean added = false;
        final long t0 = System.currentTimeMillis();
        LOCK.lock();
        try {
            inst.addTransformer(tx, true);
            added = true;
            HotfixSelfWindow.enter("keyclass_restore");
            try {
                inst.retransformClasses(target);
            } finally {
                HotfixSelfWindow.exit();
            }
        } catch (Throwable t) {
            // 装 transformer 失败 ⇒ 拿不到工具；retransform 自己抛 ⇒ 手术失败（两种都不许返回半份字节）
            final String why = t instanceof UnsupportedOperationException
                    || t instanceof NullPointerException || t instanceof IllegalArgumentException
                    ? REASON_NO_INSTRUMENTATION
                    : REASON_SURGERY_FAILED + ":retransform_" + simple(t);
            return refuse(className, why, tx.calls, "retransform_" + simple(t));
        } finally {
            if (added) {
                try {
                    inst.removeTransformer(tx);
                } catch (Throwable ignored) {
                    // 摘不掉最多让后续 retransform 多走一个"只认注册表"的 transformer，不影响判定
                }
            }
            LOCK.unlock();
        }
        // ---- ⑤ 结论
        if (req.bytes != null) {
            final String line = LINE_RESTORE + " class=" + token(className)
                    + " method=" + token(req.method.length() == 0 ? "?" : req.method)
                    + " stripped=" + req.stripped
                    + " handlers=" + req.handlers
                    + " ours_kept=" + req.oursKept
                    + " retransforms=1"     // 本次动作里 inst.retransformClasses(...) 正好调了一次
                    + " bytes=" + (src == null ? HotfixBodyProbe.SRC_NONE : src.tag());
            log(line);
            return new Result(true, className, req.method, req.stripped, req.oursKept,
                    1, REASON_OK, req.handlers, "elapsed_ms=" + (System.currentTimeMillis() - t0), line,
                    src == null ? HotfixBodyProbe.SRC_NONE : src.source,
                    src == null ? 0 : src.size());
        }
        // 到这里手术没写字节。🔴 字节都没拿到时，不许再报 no_external_handler（那是"扫过、确实没有"）。
        final String noneReason = haveBytes ? REASON_NO_EXTERNAL : REASON_NO_CLASS_BYTES;
        if (tx.calls <= 0) {
            return none(className, REASON_TRANSFORMER_NOT_CALLED, 0, scannedOurs, src, null);
        }
        if (req.failure != null) {
            return none(className, REASON_SURGERY_FAILED + ":" + token(req.failure), 0, scannedOurs, src,
                    req.failure);
        }
        return none(className, noneReason, 0, scannedOurs, src, haveBytes ? null : "three_sources_empty");
    }

    /** 一次恢复的请求 / 结果载体（transformer 与调用线程是同一个线程，无需 volatile）。 */
    private static final class Request {

        final String internalName;
        final String methodArg;
        String method = "";
        String handlers = "[]";
        String failure;
        byte[] bytes;
        int stripped;
        int oursKept;

        Request(String internalName, String methodArg) {
            this.internalName = internalName;
            this.methodArg = methodArg;
        }
    }

    /**
     * 只认"这一次要恢复的那个类"的 transformer：其它类一律 {@code null}；没有活干也 {@code null}
     * （{@code null} = 不改变这个类的字节；JVM 仍会把它定义回初始字节，所以无事可做时调用方根本不
     * 应该走到这里 —— 见 {@link #restore0} 的 ②）。
     */
    private static final class RestoreTransformer implements ClassFileTransformer {

        private final Request req;

        /** JVM 回调了几次（正常 1）。 */
        int calls;

        RestoreTransformer(Request req) {
            this.req = req;
        }

        @Override
        @SuppressWarnings("deprecation")
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className == null || classfileBuffer == null || !className.equals(req.internalName)) {
                return null;
            }
            calls++;
            try {
                final Plan plan = HotfixKeyClassRestore.plan(classfileBuffer, req.methodArg);
                if (!plan.actionable()) {
                    req.failure = null;
                    return null;
                }
                final StripResult stripped = HotfixKeyClassRestore.strip(classfileBuffer, plan);
                if (!stripped.ok()) {
                    req.failure = stripped.reason;
                    return null;
                }
                req.bytes = stripped.bytes;
                req.stripped = plan.external.size();
                req.oursKept = plan.ours.size();
                req.method = plan.method;
                req.handlers = plan.handlersList();
                return stripped.bytes;
            } catch (Throwable t) {
                // 🔴 transformer 里绝不许让异常漏出去（漏出去 = retransformClasses 打不完）
                req.failure = "transformer_exception:" + simple(t);
                return null;
            }
        }
    }

    // ═══════════════════════════════ ⑤ 准入（黑名单） ═══════════════════════════════

    /** 这个类在不在"六个已监控关键类"里（精确匹配，不做包名放宽）。 */
    public static boolean isKeyClass(String className) {
        if (className == null) {
            return false;
        }
        final String name = className.indexOf('/') >= 0 ? className.replace('/', '.') : className;
        for (int i = 0; i < KEY_CLASSES.length; i++) {
            if (KEY_CLASSES[i].equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>黑名单准入</b>（纯函数；离线自证直接喂参数）。
     *
     * <p>规则只有两条：
     * <ol>
     *   <li>{@code CodeSource == null} ⇒ <b>不许整体重建</b>（只能当 handler owner 被记录）；</li>
     *   <li>{@code net.minecraft.} 这一条按 {@code netMinecraftException} 放开（六个关键类 / 本次溯源
     *       命中的类）；其余一律走 {@link HotfixPatchWhitelist#isNeverPatch} 那张黑名单
     *       （JDK / Mojang / Forge / cpw / Mixin / 我们 / 主办方 mod / ASM / log4j / netty …）。</li>
     * </ol>
     *
     * @param className            源级类名（也接受内部名，内部斜杠会被换成点）
     * @param codeSource           {@code getProtectionDomain().getCodeSource().getLocation()} 原样串
     * @param netMinecraftException 这一份是不是"允许动的 net.minecraft 类"（六个关键类或溯源命中）
     * @return {@code true} = 允许整体重建
     */
    public static boolean admit(String className, String codeSource, boolean netMinecraftException) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        if (codeSource == null || codeSource.isEmpty()) {
            return false;   // 没有稳定身份 ⇒ 只能当 handler owner，不能整体重建
        }
        final String name = className.indexOf('/') >= 0 ? className.replace('/', '.') : className;
        if (name.startsWith("net.minecraft.")) {
            return netMinecraftException;
        }
        return !HotfixPatchWhitelist.isNeverPatch(name);
    }

    /**
     * 反射看方法名（只在 {@code retransform} 之前做一次，便宜、只读）。
     *
     * @return {@code TRUE} = 至少有一个 {@code handler$…$<外部 modid>$…}；
     *         {@code FALSE} = 一个都没有；{@code null} = 看不出来（调用方继续走 retransform）
     */
    private static Boolean hasExternalHandlerName(Class<?> target) {
        try {
            final Method[] methods = target.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                final Method m = methods[i];
                if (m == null) {
                    continue;
                }
                final HotfixFrameOwner.Owner o = HotfixFrameOwner.parse(m.getName());
                if (o != null && !HotfixFrameOwner.SELF_MODID.equals(o.modid)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (Throwable t) {
            return null;    // 方法表都读不动 ⇒ 不在这里下结论（交给 retransform 里的 byte 判据）
        }
    }

    // ═══════════════════════════════ 内部小工具 ═══════════════════════════════

    /** 被监控方法 / 候选 handler 的取样口径。 */
    private static boolean inScope(MethodNode mn, String wanted, boolean isCandidateHandler) {
        if (mn == null || isCandidateHandler) {
            return false;
        }
        if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) {
            return false;
        }
        return wanted.isEmpty() || wanted.equals(mn.name);
    }

    /** 方法体能不能换（与 {@link HotfixMethodBodyPatch} 同一条红线）。 */
    private static boolean patchable(MethodNode mn) {
        if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0) {
            return false;
        }
        if (mn.instructions == null || mn.instructions.size() == 0) {
            return false;
        }
        // 未知方法属性可能引用 Code 偏移；换方法体后它们就旧了 ⇒ 不碰
        return mn.attrs == null || mn.attrs.isEmpty();
    }

    /** 方法体是不是已经是空体（默认返回）。 */
    private static boolean isEmptyBody(MethodNode mn, int pushOpcode, int returnOpcode) {
        if (mn.instructions == null) {
            return false;
        }
        final int n = mn.instructions.size();
        if (pushOpcode < 0) {
            return n == 1 && mn.instructions.get(0).getOpcode() == returnOpcode;
        }
        return n == 2
                && mn.instructions.get(0).getOpcode() == pushOpcode
                && mn.instructions.get(1).getOpcode() == returnOpcode;
    }

    /**
     * 按返回类型选默认值：{@code [压栈操作码, 返回操作码, 栈槽数]}；{@code push = -1} 表示 void。
     *
     * @return 认不出（描述符坏了 / 不认识的排序）⇒ {@code null}（调用方跳过，不猜）
     */
    private static int[] defaultFor(String desc) {
        if (desc == null || desc.isEmpty()) {
            return null;
        }
        final Type ret;
        try {
            ret = Type.getReturnType(desc);
        } catch (Throwable t) {
            return null;
        }
        switch (ret.getSort()) {
            case Type.VOID:
                return new int[]{-1, Opcodes.RETURN, 0};
            case Type.BOOLEAN:
            case Type.BYTE:
            case Type.CHAR:
            case Type.SHORT:
            case Type.INT:
                return new int[]{Opcodes.ICONST_0, Opcodes.IRETURN, 1};
            case Type.LONG:
                return new int[]{Opcodes.LCONST_0, Opcodes.LRETURN, 2};
            case Type.FLOAT:
                return new int[]{Opcodes.FCONST_0, Opcodes.FRETURN, 1};
            case Type.DOUBLE:
                return new int[]{Opcodes.DCONST_0, Opcodes.DRETURN, 2};
            case Type.ARRAY:
            case Type.OBJECT:
                return new int[]{Opcodes.ACONST_NULL, Opcodes.ARETURN, 1};
            default:
                return null;
        }
    }

    /** 按源级名找已加载的 {@code Class}（同名多份时返回第一个；找不到 ⇒ {@code null}）。 */
    private static Class<?> findLoaded(Instrumentation inst, String className) {
        try {
            final Class<?>[] all = inst.getAllLoadedClasses();
            if (all == null) {
                return null;
            }
            for (int i = 0; i < all.length; i++) {
                final Class<?> c = all[i];
                if (c != null && className.equals(c.getName())) {
                    return c;
                }
            }
        } catch (Throwable ignored) {
            // 拿不到已加载类表 ⇒ 当作没加载（调用方打 class_not_loaded）
        }
        return null;
    }

    private static Result refuse(String className, String reason, int retransforms, String detail) {
        final String line = LINE_REFUSED + " class=" + token(className) + " reason=" + reason;
        log(line);
        return new Result(false, className, "", 0, 0, retransforms, reason, "[]", detail, line,
                HotfixBodyProbe.SRC_NONE, 0);
    }

    /**
     * "无事可做"的结论 ＋ 一行日志（{@code keyclass_restore_none}）。
     *
     * <p>🆕 2026-11-21（P2）：这一行现在必须带上<b>字节来源与大小</b>
     * （{@code bytes=baseline size=31902} / {@code bytes=none}）和
     * {@code ours_kept}（字节里数到的我们自己的方法数）—— 否则
     * {@code reason=no_external_handler} 与"根本没读到"在日志上长得一模一样。
     *
     * @param oursKept    字节里数到的"我们自己的方法"个数（没读到字节 ⇒ 0）
     * @param src         这一趟的字节来源（{@code null} 视作 none）
     * @param detail      失败细节（{@code null} = 没有）
     */
    private static Result none(String className, String reason, int retransforms, int oursKept,
                               HotfixBodyProbe.Sourced src, String detail) {
        final HotfixBodyProbe.Sourced s = src == null
                ? new HotfixBodyProbe.Sourced(HotfixBodyProbe.SRC_NONE, null) : src;
        final String line = LINE_NONE + " class=" + token(className) + " reason=" + reason
                + " stripped=0 ours_kept=" + oursKept
                + " retransforms=" + retransforms
                + " bytes=" + s.tag();
        log(line);
        return new Result(false, className, "", 0, oursKept, retransforms, reason, "[]", detail, line,
                s.source, s.size());
    }

    /**
     * 交一行到热更新的统一日志出口。
     *
     * <p>三条通道，按"明确接上的 → 同包既有出口 → stdout"降级；任何一条失败都吞掉 ——
     * 日志失败绝不许把动作链带崩。
     */
    private static void log(String line) {
        if (Boolean.TRUE.equals(QUIET.get())) {
            return;
        }
        final LineSink s = sink;
        if (s != null) {
            try {
                s.line(line);
                return;
            } catch (Throwable ignored) {
                // 接上的出口炸了 ⇒ 继续走兜底
            }
        }
        final Method m = logMethod();
        if (m != null) {
            try {
                m.invoke(null, line);
                return;
            } catch (Throwable ignored) {
                // 兜底失败 ⇒ stdout
            }
        }
        try {
            System.out.println("[SHANHAI-HOTFIX] " + line);
        } catch (Throwable ignored) {
            // 连 stdout 都没了：只能放弃这一行
        }
    }

    /** 反射找 {@code HotfixAutoCounter.chainLog(String)}（只找一次；找不到就一直走 stdout）。 */
    private static Method logMethod() {
        if (logLookedUp) {
            return logMethod;
        }
        synchronized (HotfixKeyClassRestore.class) {
            if (!logLookedUp) {
                try {
                    final Class<?> engine = Class.forName("com.shanhai.add.hotfix.HotfixAutoCounter", false,
                            HotfixKeyClassRestore.class.getClassLoader());
                    final Method m = engine.getDeclaredMethod("chainLog", String.class);
                    m.setAccessible(true);
                    logMethod = m;
                } catch (Throwable ignored) {
                    logMethod = null;
                }
                logLookedUp = true;
            }
        }
        return logMethod;
    }

    /** 日志字段里不许出现空格／竖线（与 {@code HotfixAutoCounter.token} 同一个口径）。 */
    private static String token(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "?";
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == ' ' || c == '\t' || c == '|' || c == HotfixTriageVerdict.FULLWIDTH_BAR
                    || c == '\n' || c == '\r') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String simple(Throwable t) {
        return t == null ? "?" : t.getClass().getSimpleName();
    }

    private static List<Handler> emptyHandlers() {
        return new ArrayList<Handler>(0);
    }

    private static String joinHandlers(List<Handler> handlers) {
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < handlers.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(handlers.get(i).name);
        }
        return sb.append(']').toString();
    }

    private static String joinSet(Set<String> values) {
        final StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        return sb.toString();
    }
}
