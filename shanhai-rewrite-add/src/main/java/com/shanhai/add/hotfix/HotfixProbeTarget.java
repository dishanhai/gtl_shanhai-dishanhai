package com.shanhai.add.hotfix;

/**
 * 自检用的<b>无害靶子</b>：一个返回固定字符串的方法，被本 mod 自己的自检
 * （{@code /shanhai_add hotfix selftest}）当作"改方法体 → 验证变了 → 还原 → 再验证"的对象。
 *
 * <h2>为什么不能拿游戏类当靶子</h2>
 * 硬约束：<b>不许碰 {@code minecraft}/{@code forge}/上游类</b>。在活世界里 retransform
 * 一个被 Mixin 处理过的 MC 类，失败形态多种多样（Mixin 的 stateless 变换、方法体已有注入、
 * 结构性变更被 JVM 拒绝），而这些风险对"验证管道通不通"这件事<b>毫无必要</b>。
 * 用我们自己的一个"返回常量"的方法，同样证明整条链：
 * transformer 被回调 → 新字节被 JVM 接受 → 新方法体真的被调用到 → 还原也有效。
 *
 * <h2>🔴 改这个方法之前必须知道的两件事</h2>
 * <ol>
 *   <li><b>方法体必须保持"单个字符串常量"这一形态</b>：{@link HotfixBytePatch} 是靠
 *       "在常量池里找唯一一个 {@code SHANHAI-HOTFIX-STAMP-xxxx} 并原地换 4 个字节"来改它的。
 *       如果把方法改成 {@code return PREFIX + token;}（拼接）、加了分支、或让同一个字符串
 *       在类里出现两次（例如再加一个 {@code private static final String} 字段），
 *       自检会以 {@code reason=stamp_not_found} / {@code reason=stamp_appears_2_times} 失败
 *       —— 那是<b>故意</b>的拒绝，不是 bug：那种情况下"只改一份"会让类内部不一致。</li>
 *   <li><b>字面量必须与 {@link HotfixBytePatch#ORIGINAL_VALUE} 逐字一致</b>。两处漂了的话，
 *       自检会在 {@code step=baseline} 就红，并打出两边的值（一眼能看出是谁漂了）。</li>
 * </ol>
 *
 * <p>它被 {@link HotfixBytePatch} 在类注释里引用（解释靶子形态），所以必须在同一个包里。
 */
public final class HotfixProbeTarget {

    private HotfixProbeTarget() {
    }

    /**
     * 自检靶子方法：返回一个编译期字符串常量（javac 编译成 {@code ldc} + {@code areturn}）。
     *
     * @return 恒为 {@link HotfixBytePatch#ORIGINAL_VALUE}，除非自检正把 transformer 挂着
     */
    public static String stamp() {
        return "SHANHAI-HOTFIX-STAMP-0000";
    }
}
