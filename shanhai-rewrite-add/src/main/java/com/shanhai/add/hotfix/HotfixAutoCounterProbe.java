package com.shanhai.add.hotfix;

/**
 * 主动热改的<b>无害靶子</b>：给 {@link HotfixAutoCounter#selftest()} 用的"我们自己的类"。
 *
 * <h2>为什么要单独一个类，而不是复用 {@link HotfixProbeTarget}</h2>
 * {@link HotfixProbeTarget} 是"常量替换"自检的靶子 —— 它的方法体被钉死成<b>单个字符串常量</b>
 * （{@link HotfixBytePatch} 靠"同长度换 4 个字节"改它）。而本条自检要验的是
 * <b>ASM 结构级换方法体</b>（{@code ICONST_0 IRETURN} 这类），两者的靶子形态要求互相冲突
 * ⇒ 各用各的类，谁也别迁就谁。
 *
 * <h2>这几个方法的用途（改动前先读）</h2>
 * <table border="1">
 *   <tr><th>方法</th><th>用途</th><th>期望的"未改"值</th></tr>
 *   <tr><td>{@link #flag()}</td><td><b>被改的那个</b>（自检把它的方法体换成 {@code return false}）</td>
 *       <td>{@code true}</td></tr>
 *   <tr><td>{@link #number()}</td><td>同一个类里<b>没被要求改</b>的方法 ⇒ 必须一点没动</td>
 *       <td>{@code 42}</td></tr>
 *   <tr><td>{@link #untouched()}</td><td>同上（另一条负对照）</td><td>{@code 7}</td></tr>
 *   <tr><td>{@link #text()}</td><td>引用返回值；也当"别的返回类型没被顺手改坏"的证据</td>
 *       <td>{@link #TEXT}</td></tr>
 *   <tr><td>{@link #big()}</td><td>{@code long} 返回（栈槽 2 的那种），负对照</td><td>{@code 9000000000000L}</td></tr>
 *   <tr><td>{@link #act()}</td><td>{@code void} 返回（换方法体的 void 形态在这里可读）</td><td>无返回值</td></tr>
 * </table>
 *
 * <p>🔴 本类在硬白名单里（{@code com.shanhai.*}）⇒ <b>自动路径永远碰不到它</b>；
 * 只有 {@link HotfixAutoCounter#selftest()} 那条<b>显式绕过白名单</b>的路会改它，而且改完立刻还原。
 * 这正是设计意图：自检要能证明"换方法体"这条链通，而不能因此在游戏里留下任何改动。
 */
public final class HotfixAutoCounterProbe {

    /** {@link #text()} 的期望值（自检比对这个常量，别改字面量而不改这里）。 */
    public static final String TEXT = "SHANHAI-HOTFIX-AC-TEXT";

    /** {@link #number()} 的期望值。 */
    public static final int NUMBER = 42;

    /** {@link #untouched()} 的期望值。 */
    public static final int UNTOUCHED = 7;

    /** {@link #big()} 的期望值。 */
    public static final long BIG = 9000000000000L;

    private HotfixAutoCounterProbe() {
        // 纯静态靶子
    }

    /** 被自检改掉的那个方法：原本恒 {@code true}，改完必须变成 {@code false}。 */
    public static boolean flag() {
        return true;
    }

    /** 负对照：同一个类里没被要求改的方法，必须仍是 {@link #NUMBER}。 */
    public static int number() {
        return NUMBER;
    }

    /** 负对照：另一个没被要求改的方法，必须仍是 {@link #UNTOUCHED}。 */
    public static int untouched() {
        return UNTOUCHED;
    }

    /** 引用返回值（{@code ACONST_NULL ARETURN} 那条规则的对照）。 */
    public static String text() {
        return TEXT;
    }

    /** {@code long} 返回（栈槽 2；换方法体时 {@code maxStack} 要抬到 2）。 */
    public static long big() {
        return BIG;
    }

    /** {@code void} 返回；方法体刻意不是单 {@code RETURN}（否则"改了等于没改"看不出差别）。 */
    public static void act() {
        number();
    }
}
