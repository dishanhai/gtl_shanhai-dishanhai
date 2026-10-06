package com.shanhai.mixin;

import com.shanhai.ShanhaiMod;
import com.shanhai.machine.wildcard.AutoForgeMultiplierHost;
import com.shanhai.machine.wildcard.AutoForgeMultiplierPanel;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 山海重构 · mixin 基建的【冒烟 mixin】。
 *
 * <h2>它做两件事（都只打日志，不改任何行为）</h2>
 * <ol>
 *   <li>在 {@link ShanhaiMod} 的构造器返回处打一行 {@code [SHANHAI-SMOKE] mixin loaded}；</li>
 *   <li><b>2026-10-04 追加</b>：顺便探一次「给上游那台机器加自动倍率」的两个 mixin
 *       <b>到底进没进去</b>，并把它打成一行可判读的日志（见
 *       {@link #shanhai$probeUpstreamAutoMultiplierMixins()}）。</li>
 * </ol>
 *
 * <h2>为什么摘「我们自己的类」</h2>
 * <ul>
 *   <li><b>优先级最高</b>：目标 {@code com.shanhai.ShanhaiMod} 是本 mod 自己的类，<b>不经任何混淆映射</b>
 *       ⇒ {@code remap = false} 成立，<b>不需要 refmap</b>。</li>
 *   <li><b>冒烟必须在 common 侧</b>：这个 mixin 列在 {@code shanhai.mixin.json} 的 {@code mixins} 数组里
 *       （<b>不是</b> {@code client} 数组）。{@code client} 数组的 mixin 专用服务端不会加载，
 *       那样专服冒烟就永远看不到这行日志、也就证不了任何事。</li>
 *   <li><b>必须无害</b>：注入点 {@code @At("RETURN")} <b>不取消、不修改</b>返回值（{@code <init>} 返回 void），
 *       不 {@code @Overwrite}、不 {@code @Redirect}、不 {@code @Shadow}。</li>
 * </ul>
 *
 * <h2>为什么日志里写死字符串而不是取常量</h2>
 * {@code "[SHANHAI-SMOKE] mixin loaded"} 这个字面量在<b>整个工程里只出现在本文件</b>，
 * 因此专服日志里出现它，就唯一地证明「mixin 配置被加载 + 本 mixin 被应用 + 注入点真的执行了」，
 * 而不是「mod 加载了」。这是本次冒烟唯一的判据。
 *
 * <h2>🔴 为什么要探「上游那两个 mixin 进没进去」</h2>
 * 那两个 mixin 目标在 gtladditions（第三方 jar），住在一个 {@code "required": false} 的配置里，
 * 而且<b>冒烟世界里根本没有那台机器</b> ⇒ 不主动探，就<b>永远不知道</b>它是「已经进了」还是
 * 「根本没进」。而「没进」在运行时的表现只是后台一行 WARN —— 玩家侧看起来一模一样。
 * ⇒ 这里在 mod 构造期用 {@code Class.forName(..., false, ...)} <b>只加载不初始化</b>
 * （不跑 {@code <clinit>}、不碰注册表、不造任何对象），就地断言：
 * <ul>
 *   <li>{@code MESuperPatternBufferPartMachine} 实现了 {@link AutoForgeMultiplierHost}
 *       ⇒ 机器那个 mixin 进了；</li>
 *   <li>它身上那三个字段存在且带 {@code @DescSynced}（按名字里含 {@code autoForgeMultiplier} 扫，
 *       <b>不依赖 Mixin 是否给 @Unique 字段加了前缀</b>）⇒ LDLib 会把它登记为托管字段；</li>
 *   <li>{@code FOAPatternConfigurator} 实现了 {@link AutoForgeMultiplierPanel.UpstreamPanelExtender}
 *       ⇒ 面板那个 mixin 进了。</li>
 * </ul>
 * <p>⚠️ 全程 {@code try/catch(Throwable)}：探不到就打印一条 {@code FAIL} 行，
 * <b>绝不让探测本身把服务端启动搞崩</b>（探测只是取证，不是功能）。
 */
@Mixin(value = ShanhaiMod.class, remap = false)
public class ShanhaiSmokeMixin {

    /** 上游那台机器（字符串，不做编译期引用）。 */
    @Unique
    private static final String SHANHAI$UPSTREAM_MACHINE =
            "com.gtladd.gtladditions.common.machine.multiblock.part.MESuperPatternBufferPartMachine";

    /** 上游那台的面板（字符串，不做编译期引用）。 */
    @Unique
    private static final String SHANHAI$UPSTREAM_PANEL =
            "com.gtladd.gtladditions.api.machine.gui.FOAPatternConfigurator";

    /**
     * 在 {@code ShanhaiMod.<init>()} 的 RETURN 处打印冒烟标记，并探一次上游那两个 mixin。
     *
     * <p>{@code require = 1} 是刻意的（默认值也是 1）：注入点找不到目标时<b>让加载期直接失败</b>，
     * 而不是静默放过 —— 冒烟的目的就是「要炸就炸得响」。
     */
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void shanhai$smokeMixinLoaded(CallbackInfo ci) {
        ShanhaiMod.LOGGER.info("[SHANHAI-SMOKE] mixin loaded");
        shanhai$probeUpstreamAutoMultiplierMixins();
    }

    /** 探「给上游那台加自动倍率」的两个 mixin 是否真的进了（详见类注释）。 */
    @Unique
    private static void shanhai$probeUpstreamAutoMultiplierMixins() {
        final ClassLoader loader = ShanhaiSmokeMixin.class.getClassLoader();

        // ── ① 机器那个 mixin：字段在不在（带 @DescSynced）＋ 【接口的每一个方法】在不在 ──
        //    🔴 2026-10-04 第二次返工：旧版只验了字段 ⇒ 漏实现两个接口方法没被发现，
        //       结果「接口挂上了、方法没合并」⇒ 一开面板 AbstractMethodError（**真崩过一次**）。
        //       现在逐条验方法，并额外做一条"故意找不存在的方法名"的负对照。
        try {
            final Class<?> machine = Class.forName(SHANHAI$UPSTREAM_MACHINE, false, loader);

            // 字段：按名字里含 autoForgeMultiplier 扫（不依赖 Mixin 是否给 @Unique 加前缀）
            int fields = 0;
            for (Field field : machine.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!field.getName().contains("autoForgeMultiplier")) {
                    continue;
                }
                if (field.getAnnotation(DescSynced.class) != null) {
                    fields++;
                }
            }

            // 方法：接口声明的每一个都必须【真的在合并后的目标类上】（不是 abstract、不是 static）
            final Method[] required = AutoForgeMultiplierHost.class.getDeclaredMethods();
            final java.util.List<String> missing = new java.util.ArrayList<>();
            int ok = 0;
            for (Method want : required) {
                try {
                    final Method got = machine.getMethod(want.getName(), want.getParameterTypes());
                    final int mod = got.getModifiers();
                    final boolean declaredHere = got.getDeclaringClass() == machine;
                    final boolean concrete = !Modifier.isAbstract(mod) && !Modifier.isStatic(mod);
                    if (declaredHere && concrete) {
                        ok++;
                    } else {
                        missing.add(want.getName() + "(declaredHere=" + declaredHere
                                + ",abstract=" + Modifier.isAbstract(mod)
                                + ",static=" + Modifier.isStatic(mod) + ")");
                    }
                } catch (NoSuchMethodException e) {
                    missing.add(want.getName() + "(缺失)");
                }
            }

            // 🔴 负对照：找一个【确定不存在】的方法名 ⇒ 必须取不到。
            //    取到了说明这套判据（getMethod + 同名同参数）根本没在起作用，那"方法都在"就是假绿。
            boolean control;
            try {
                machine.getMethod("shanhai$thisMethodMustNotExistNegativeControl");
                control = false;     // 竟然取到了 ⇒ 判据坏了
            } catch (NoSuchMethodException expected) {
                control = true;      // 取不到 = 判据有效
            }

            final boolean host = AutoForgeMultiplierHost.class.isAssignableFrom(machine);
            final boolean verdict = host && fields == 3 && missing.isEmpty() && control;

            // 🔴 失败时用 ERROR：这样它会出现在任何"扫 ERROR 行"的回归比对里（INFO 行会被忽略），
            //    而不是躺在一堆启动日志中间等人去翻。
            if (verdict) {
                ShanhaiMod.LOGGER.info("[SHANHAI-SMOKE] upstream-auto-multiplier machine-mixin=APPLIED "
                                + "host-interface={} synced-fields={}/3 interface-methods={}/{} negative-control=OK",
                        host, fields, ok, required.length);
            } else {
                ShanhaiMod.LOGGER.error("[SHANHAI-SMOKE] upstream-auto-multiplier machine-mixin=FAIL "
                                + "host-interface={} synced-fields={}/3 interface-methods={}/{} "
                                + "negative-control={} <= 缺方法：{}（缺方法 ⇒ 一开面板就 AbstractMethodError）",
                        host, fields, ok, required.length, control ? "OK" : "BROKEN", missing);
            }
            // 逐条打印方法签名（= 合并后目标类上的真实签名；declaringClass 必须是那台机器自己）
            for (Method want : required) {
                try {
                    final Method got = machine.getMethod(want.getName(), want.getParameterTypes());
                    ShanhaiMod.LOGGER.info("[SHANHAI-SMOKE] upstream-auto-multiplier   method {}({}) -> {} [declaring={}]",
                            got.getName(), shanhai$describe(got.getParameterTypes()), got.getReturnType().getName(),
                            got.getDeclaringClass().getName());
                } catch (NoSuchMethodException e) {
                    ShanhaiMod.LOGGER.info("[SHANHAI-SMOKE] upstream-auto-multiplier   method {} MISSING",
                            want.getName());
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.info("[SHANHAI-SMOKE] upstream-auto-multiplier machine-mixin=FAIL 类加载异常 {}: {}",
                    t.getClass().getName(), t.toString());
        }

        // ── ② 面板那个 mixin：上游面板类实现不实现我们的标记接口？ ──
        try {
            final Class<?> panel = Class.forName(SHANHAI$UPSTREAM_PANEL, false, loader);
            final boolean applied = AutoForgeMultiplierPanel.UpstreamPanelExtender.class.isAssignableFrom(panel);
            ShanhaiMod.LOGGER.info("[SHANHAI-SMOKE] upstream-auto-multiplier panel-mixin={} extender-interface={}",
                    applied ? "APPLIED" : "FAIL", applied);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.info("[SHANHAI-SMOKE] upstream-auto-multiplier panel-mixin=FAIL 类加载异常 {}: {}",
                    t.getClass().getName(), t.toString());
        }
    }


    /** 把参数类型数组打成可读的一行（探针日志用）。 */
    @Unique
    private static String shanhai$describe(Class<?>[] types) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(types[i].getName());
        }
        return sb.toString();
    }
}