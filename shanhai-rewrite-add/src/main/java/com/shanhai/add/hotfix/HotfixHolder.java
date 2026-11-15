package com.shanhai.add.hotfix;

import java.lang.instrument.Instrumentation;

/**
 * 游戏侧（本 mod 自己的 class loader 里）的 {@link Instrumentation} <b>持有者</b>。
 *
 * <h2>它为什么必须存在</h2>
 * agent 是被 <b>system class loader</b> 加载的（实测：{@code agent-class-loader} 与
 * {@code system-class-loader} 是同一个 {@code AppClassLoader} 对象），而游戏/我们的类在
 * ModLauncher 的 {@code TransformingClassLoader} 上 ⇒ 两个类加载器里各有一份同名类，
 * agent 里<b>不能</b>写 {@code HotfixHolder.INSTANCE = inst} 这种编译期引用（加载不到，或写到另一份上去）。
 * 唯一可靠的办法是 agent 在 {@code Instrumentation.getAllLoadedClasses()} 里<b>按类名</b>找到
 * 本类，再用反射调 {@link #acceptInstrumentation}。
 *
 * <h2>🔴 三条硬约束（改这个类之前先读）</h2>
 * <ol>
 *   <li><b>只碰 {@code java.*}</b>：本类会被<b>脱离游戏</b>的离线自检直接加载进一个普通 JVM
 *       （{@code temp\hotfix\offline\}），引用任何 MC/Forge/项目类型都会在那一刻
 *       {@code NoClassDefFoundError}。</li>
 *   <li><b>这里不写日志</b>：日志一律在 agent 侧（{@link HotfixAgent}）与游戏侧装配点
 *       （{@link HotfixBootstrap}）打。理由同上：写日志就要 import {@code ShanhaiMod}/{@code slf4j}。</li>
 *   <li><b>方法名是契约</b>：{@code acceptInstrumentation} / {@code publishState} 的<b>名字和签名</b>
 *       被 {@link HotfixAgent} 用字符串 + 反射查找（{@code getMethod}）。改名 = agent 找不到 holder，
 *       而且失败形态只是"预热降级"，不会有编译错误提醒你。</li>
 * </ol>
 *
 * <p>字段全部 {@code volatile}：写入发生在目标 JVM 的 {@code Attach Listener} 线程上，
 * 读取发生在服务端线程 / 命令线程上，跨线程可见性必须显式保证。
 */
public final class HotfixHolder {

    /** agent 找到本类后调用的一次性交接（返回 {@code true} = 接收成功）。 */
    private static volatile Instrumentation instrumentation;

    /** agent 体检结论那一行（含 {@code state=ready|degraded}），由 {@link #publishState} 写入。 */
    private static volatile String stateLine = "";

    /** 交接发生的墙钟毫秒（只用于判读"这次预热是不是本次进程内的事"）。 */
    private static volatile long acceptedAtMillis;

    /** 执行 agentmain 的线程名（实测是 {@code Attach Listener}，留档用于确认"确实在目标 JVM 里"）。 */
    private static volatile String agentThread = "";

    private HotfixHolder() {
    }

    /**
     * 只为让 JVM <b>现在就把本类加载进来</b>（空方法，副作用就是类初始化）。
     *
     * <p>🔴 这是整条链路成立的前提之一：agent 是"按名字在已加载类里找 holder"的，
     * 没加载就找不到。所以游戏侧在拉起 helper <b>之前</b>必须先调一次本方法
     * （见 {@link HotfixBootstrap}）。agent 侧另有有限次重试兜底，但那是第二道保险。
     */
    public static void touch() {
        // 故意的空方法体：调用本身就是目的。
    }

    /**
     * agent 交接入口（反射调用，{@code null} 表示 JVM 没给 Instrumentation ⇒ 拒绝并保持未装状态）。
     *
     * @return {@code true} = 已持有
     */
    public static boolean acceptInstrumentation(Instrumentation inst) {
        if (inst == null) {
            return false;
        }
        instrumentation = inst;
        acceptedAtMillis = System.currentTimeMillis();
        agentThread = Thread.currentThread().getName();
        return true;
    }

    /** agent 体检结论（一行，含 {@code state=...}）。{@code null} 被忽略（不覆盖已有值）。 */
    public static void publishState(String line) {
        if (line != null) {
            stateLine = line;
        }
    }

    /** 已持有的实例；{@code null} = 还没拿到（或预热失败）。 */
    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    /** 是否已拿到 {@link Instrumentation}。 */
    public static boolean installed() {
        return instrumentation != null;
    }

    /** agent 体检结论那一行；空串 = agent 还没回报。 */
    public static String stateLine() {
        return stateLine;
    }

    /** 交接时刻（{@link System#currentTimeMillis()}）；0 = 还没发生。 */
    public static long acceptedAtMillis() {
        return acceptedAtMillis;
    }

    /** 执行 agentmain 的线程名；空串 = 还没发生。 */
    public static String agentThread() {
        return agentThread;
    }

    /** 一行判读用摘要（{@code /shanhai_add hotfix status} 与预热日志共用）。 */
    public static String summary() {
        final Instrumentation inst = instrumentation;
        return "installed=" + (inst != null)
                + " accepted_at_ms=" + acceptedAtMillis
                + " agent_thread=" + (agentThread.isEmpty() ? "?" : agentThread)
                + " state=<" + stateLine + ">";
    }
}
