package com.shanhai.add.hotfix;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * 一个<b>一个字节都不改</b>的 {@link ClassFileTransformer}（永远 {@code return null}）。
 *
 * <h2>它存在的唯一理由：给热更新管道做"空转体检"</h2>
 * 需求验收里那条"对一个与游戏无关的 JDK 类做一次 no-op retransform"需要一个 transformer，
 * 而"能注册上、能被回调、能摘掉"这三件事本身就是整条管道的体检项目 ——
 * 不做任何替换，所以<b>不可能</b>改变 JVM 里的任何行为。
 *
 * <h2>🔴 为什么它是独立的一个类，而不是 {@link HotfixAgent} 里的内部类</h2>
 * agent 侧只带 <b>两个</b> class 进 agent jar（{@code HotfixAgent.class} + 本类，见
 * {@link HotfixAgentJar}）。如果写成匿名内部类 / 内部类，jar 里就要多出
 * {@code HotfixAgent$1.class} 这种编译期名字，而我们的抽 jar 逻辑是"按固定类名清单抽"的
 * ⇒ 少抽一个类，agentmain 就会在运行期 {@code NoClassDefFoundError}（而且是在目标 JVM 里炸，日志难读）。
 * 写成顶层类之后，"要抽哪些类"是一个能一眼核对完的常量清单。
 *
 * <p>约束（与 {@link HotfixAgent} 相同）：<b>只碰 {@code java.*}，不引用任何 MC/Forge/项目类型</b>
 * —— 它被 system class loader 加载，游戏类在 ModLauncher 的 {@code TransformingClassLoader} 上，
 * 一旦引用就是运行期 {@code NoClassDefFoundError}。
 */
public final class HotfixNoopTransformer implements ClassFileTransformer {

    /**
     * JVM 回调入口：永远返回 {@code null}（= "这个类我不管"）。
     *
     * <p>⚠️ 实现的是 {@link ClassFileTransformer} 里那个<b>已废弃的 5 参重载</b>：JDK 9+ 的 6 参
     * 版本默认实现会把调用转给 5 参版本，所以只实现 5 参就够了（这也是 {@code temp/u1-attach}
     * 里已经跑通过的那条路）。
     */
    @Override
    @SuppressWarnings("deprecation")
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        return null;
    }
}
