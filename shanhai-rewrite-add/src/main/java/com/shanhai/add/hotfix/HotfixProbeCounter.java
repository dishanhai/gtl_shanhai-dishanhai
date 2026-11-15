package com.shanhai.add.hotfix;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * <b>只计数、不干活的调用探针</b>：给被我们改成"安全默认返回"的方法，在方法体最前面插一条
 * {@code COUNTER.bump("类#方法")}，用来回答一个光看字节永远回答不了的问题 ——
 * <b>这个方法到底跑没跑过（跑的是不是我们那份字节）</b>。
 *
 * <h2>它要解决的两个假阳性</h2>
 * <ol>
 *   <li>{@code patch_probe result=ok} 原先只证明<b>"这 5 秒没人来打"</b>。有了计数器就能分两档：
 *       {@code reason=exercised}（同一 target 在窗口里<b>真被调用过</b>、而且没违规）与
 *       {@code reason=no_violation}（没人来打，但也没证据说它跑过）。</li>
 *   <li><b>存活哨兵</b>（{@code patch_keepalive}）要判"我们的补丁是不是被谁悄悄换回去了"。
 *       字节这一侧<B>观测不到</B>（实测 P1/P2：retransform 交给 transformer 的永远是<b>初始基线</b>，
 *       所以"读回"读到的永远不是当前定义）⇒ 只能靠行为侧：
 *       <b>调用过（有栈证据）＋ 计数器没动</b> ⇒ 那一次跑的不是我们那份字节。</li>
 * </ol>
 *
 * <h2>🔴 三条安全纪律（这个类会往别人家的类里插代码，写错就是把游戏搞崩）</h2>
 * <ol>
 *   <li><b>先判"能不能解析到"</b>：{@link #resolvableFrom(ClassLoader)} 用<b>目标类的加载器</b>
 *       去解析本类；解析不到就<b>不插</b>（否则插进去的那条 {@code INVOKESTATIC} 会在运行时
 *       抛 {@code NoClassDefFoundError}，而且是在别人家的方法体里抛）。</li>
 *   <li><b>只插直线代码</b>：{@code LDC + INVOKESTATIC}，无分支 ⇒ <b>不动 StackMapTable</b>，
 *       只重算 maxStack（{@link ClassWriter#COMPUTE_MAXS}，它不加载任何类）。绝不 {@code COMPUTE_FRAMES}。</li>
 *   <li><b>失败一律退回原字节</b>：本类任何一步抛异常都 {@code return 原样 bytes}，
 *       调用方拿到的字节永远至少等于"不插计数器"那一份。</li>
 * </ol>
 *
 * <p>依赖面：{@code java.*} ＋ ASM（{@code HotfixAutoCounter} 的依赖面本来就含 ASM）。
 */
public final class HotfixProbeCounter {

    /** 本类在字节码里的内部名（插入的 {@code INVOKESTATIC} 用）。 */
    public static final String SELF_INTERNAL = "com/shanhai/add/hotfix/HotfixProbeCounter";

    /** 本类的源级名（解析自证用）。 */
    public static final String SELF_NAME = "com.shanhai.add.hotfix.HotfixProbeCounter";

    /** 计数器（键 = {@code 类#方法}；与日志里的 {@code target=} 逐字一致）。 */
    private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

    private static final AtomicLong INSTRUMENTED = new AtomicLong();
    private static final AtomicLong SKIPPED_UNRESOLVABLE = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();
    private static final AtomicLong BUMPS = new AtomicLong();

    private HotfixProbeCounter() {
        // 纯静态
    }

    // ═══════════════════════════════ 运行期回调（插进别人家的方法体里） ═══════════════════════════════

    /**
     * 计数（每被调用一次 +1）。<b>这个方法绝不许抛</b>：它跑在别人家的方法体第一行。
     *
     * @param key {@code 类#方法}
     */
    public static void bump(String key) {
        if (key != null) {
            final AtomicLong n = counterOf(key);
            n.incrementAndGet();
            BUMPS.incrementAndGet();
        }
    }

    /** 某个方法被调用过几次。 */
    public static long count(String key) {
        if (key == null) {
            return 0L;
        }
        final AtomicLong n = COUNTERS.get(key);
        return n == null ? 0L : n.get();
    }

    /** 清空（离线自检用；生产不调）。 */
    public static void clear() {
        COUNTERS.clear();
    }

    // ═══════════════════════════════ 字节插桩 ═══════════════════════════════

    /**
     * 给 {@code methodName+methodDesc} 这个方法体最前面插一条 {@code bump(key)}。
     *
     * @param bytes      当前字节（通常是"已换成安全默认返回"的那一份）
     * @param methodName 方法名
     * @param methodDesc 精确描述符（由 {@code HotfixMethodBodyPatch} 解析出来的那个）
     * @param key        {@code 类#方法}（计数器键）
     * @param loader     目标类的加载器（{@code null} = bootstrap ⇒ 判不可解析）
     * @return 插好的字节；<b>任何一步不成立都返回原字节</b>（绝不返回半份）
     */
    public static byte[] instrument(byte[] bytes, String methodName, String methodDesc, String key,
                                    ClassLoader loader) {
        if (bytes == null || methodName == null || methodDesc == null || key == null) {
            return bytes;
        }
        if (!resolvableFrom(loader)) {
            SKIPPED_UNRESOLVABLE.incrementAndGet();
            return bytes;
        }
        try {
            final ClassReader cr = new ClassReader(bytes);
            final ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            final String probeKey = key;
            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                                 String[] exceptions) {
                    final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    if (mv == null || !methodName.equals(name) || !methodDesc.equals(desc)) {
                        return mv;
                    }
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        private boolean done;

                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (done) {
                                return;
                            }
                            done = true;
                            // 只插直线代码（LDC + INVOKESTATIC）：无分支 ⇒ 不动 StackMapTable
                            super.visitLdcInsn(probeKey);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF_INTERNAL, "bump",
                                    "(Ljava/lang/String;)V", false);
                        }
                    };
                }
            }, 0);
            final byte[] out = cw.toByteArray();
            if (out == null || out.length == 0) {
                FAILED.incrementAndGet();
                return bytes;
            }
            INSTRUMENTED.incrementAndGet();
            return out;
        } catch (Throwable t) {
            FAILED.incrementAndGet();
            return bytes;
        }
    }

    /**
     * <b>目标类的加载器能不能解析到本类</b>（插桩前的必判项）。
     *
     * <p>为什么用 {@code Class.forName(name, false, loader)}：这就是 JVM 解析那条
     * {@code INVOKESTATIC} 时走的同一条路 —— 它返回得到，插进去的调用就不会在运行期炸。
     * {@code initialize=false} 保证<b>不触发类初始化</b>（不会因为我们探测而提前跑静态块）。
     *
     * @param loader 目标类的加载器；{@code null}（bootstrap）⇒ {@code false}
     * @return {@code true} = 可以安全插桩
     */
    public static boolean resolvableFrom(ClassLoader loader) {
        if (loader == null) {
            return false;      // bootstrap 看不见 mod 类
        }
        try {
            // ⚠️ 只判"解析得到"，**不**要求由这个 loader 亲自定义：JVM 解析 INVOKESTATIC 走的是
            //   loader.loadClass(...) 的委派语义（父加载器定义也算解析得到）。要求"同一个 loader 定义"
            //   会把"目标类的 loader 委托给上层"这种最常见的情形全判成不可插桩（离线自测当场逮到：
            //   skipped_unresolvable=17 / instrumented=0）。
            return Class.forName(SELF_NAME, false, loader) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 插桩自证：{@code resolvableFrom} 对"我们自己这个类的加载器"必须为真（否则生产永远插不上）。 */
    public static boolean selfCheck() {
        return resolvableFrom(HotfixProbeCounter.class.getClassLoader());
    }

    /** 一行读数（{@code statusLine} 用）。 */
    public static String statusLine() {
        return "probe_counter_instrumented=" + INSTRUMENTED.get()
                + " skipped_unresolvable=" + SKIPPED_UNRESOLVABLE.get()
                + " failed=" + FAILED.get()
                + " bumps=" + BUMPS.get()
                + " keys=" + COUNTERS.size();
    }

    /** 一次快照：{@code 类#方法} ⇒ 调用次数（判读/离线自检用；只读副本）。 */
    public static List<String> snapshot() {
        final List<String> out = new ArrayList<>(COUNTERS.size());
        for (Map.Entry<String, AtomicLong> e : COUNTERS.entrySet()) {
            out.add(e.getKey() + "=" + (e.getValue() == null ? 0L : e.getValue().get()));
        }
        return out;
    }

    // ═══════════════════════════════ 内部 ═══════════════════════════════

    private static AtomicLong counterOf(String key) {
        AtomicLong n = COUNTERS.get(key);
        if (n == null) {
            final AtomicLong fresh = new AtomicLong();
            final AtomicLong prev = COUNTERS.putIfAbsent(key, fresh);
            n = prev == null ? fresh : prev;
        }
        return n;
    }

    /** 反射自证用（离线自检可以拿它确认"我们插的方法确实叫 bump(String)"）。 */
    public static Method bumpMethod() throws NoSuchMethodException {
        return HotfixProbeCounter.class.getMethod("bump", String.class);
    }
}
