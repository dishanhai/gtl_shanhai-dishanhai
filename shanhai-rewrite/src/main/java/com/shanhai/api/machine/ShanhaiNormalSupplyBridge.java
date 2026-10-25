package com.shanhai.api.machine;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 「万能仓室」的<b>纯逻辑</b>桥：把「带标记的 ME 仓室」翻译成
 * 「gtlcore 原生 {@code handleNormalPart} 可以照常处理的普通仓室视图」。
 *
 * <h2>🔴 为什么单独抽这一个类（而不是全写在 mixin 里）</h2>
 * <b>为了能离线自证</b>：本类<b>不引用任何 Minecraft / gtceu / gtlcore 类型</b>
 * （只用 {@code java.*}），所以能用一个纯 Java 探针直接跑
 * {@code temp/universal-part/probe/} 里那两个真实分支，<b>不需要启动游戏</b>。
 * 探针跑的是<b>本类的真实字节码</b>，不是"抄一份逻辑"。
 *
 * <h2>二、它到底怎么"接进" gtlcore（不说清楚就没法验收）</h2>
 * gtlcore 的重建只有<b>一个入口</b>（{@code javap -c} 实证）：
 * <pre>
 *   WorkableMultiblockMachineMixin#upDate:()V
 *        8: aload_0
 *       10: getfield traitSubscriptions    → 参数 1
 *       14: invokevirtual getParts:()Ljava/util/List;   ← ★ 我们 @Redirect 的就是这一句（偏移 14）
 *       18: getfield recipeLogic           → 参数 3
 *       22: getfield isDistinct            → 参数 4
 *       25: invokevirtual rebuildRecipeHandleParts:(Ljava/util/List;Ljava/util/List;LRecipeLogic;Z)V
 * </pre>
 * 我们把 {@code getParts()} 的<b>返回值</b>换成一个"每个带标记的 ME 仓室后面多插一个视图"的列表，
 * 于是 {@code rebuildRecipeHandleParts} 的循环里：
 * <ul>
 *   <li><b>原始元素</b> → 仍按 {@code instanceof IMEFilterIOPartMachine} 走
 *       {@code handleMETraitPart}（ME 身份、样板认领、缓冲监听全部照旧）；</li>
 *   <li><b>追加的视图</b> → 不是 {@code IMEFilterIOPartMachine}、也不是 {@code FluidHatchPartMachine}/
 *       {@code IDistinctPart} ⇒ 落进 gtlcore 自己的 {@code handleNormalPart(part, isDistinct, tmp)}。
 *       它的 {@code getRecipeHandlers()} 只返回标记接口声明的那几份 ⇒
 *       <b>gtlcore 原生的 IO 归类、{@code isProxy()} 过滤、共享输入池 {@code sharedInputRecipeHandlePart}、
 *       {@code mergeSharedFluidHandlers} 全部由 gtlcore 自己跑</b>，我们一行都没复制。</li>
 * </ul>
 *
 * <h2>三、安全边界（代码层保证，不是口头承诺）</h2>
 * <ol>
 *   <li>{@link #expandForNormalSupply} 在<b>没有任何标记元素</b>时<b>原样返回同一个 List 实例</b>
 *       （{@code return parts}，不是复制）⇒ 未标记仓室走的是与上游<b>完全相同</b>的那个列表，
 *       行为逐字节不变。</li>
 *   <li>视图只覆盖<b>一个无参方法</b>（{@code getRecipeHandlers()}），其余<b>全部转发</b>给真实仓室；
 *       视图<b>不实现</b> {@code IMEFilterIOPartMachine} ⇒ 不可能被 gtlcore 当 ME 仓室处理。</li>
 *   <li>本类<b>不写</b> gtlcore 的 {@code normalCapabilities} / {@code mePatternRecipeHandleParts}
 *       等任何字段，也不 {@code @Overwrite} 任何方法。</li>
 * </ol>
 */
public final class ShanhaiNormalSupplyBridge {

    /** 只用来「注入第一次被执行时记一行日志」——证明 hook 真的挂上了（与有没有星律无关）。 */
    private static final AtomicBoolean FIRST_HOOK_LOGGED = new AtomicBoolean();

    /** 只用来「第一次真的扩出一个视图时记一行日志」——避免每 tick 重建时刷爆日志。 */
    private static final AtomicBoolean FIRST_EXPANSION_LOGGED = new AtomicBoolean();

    private ShanhaiNormalSupplyBridge() {
    }

    /**
     * 把「parts 列表」扩成「原元素 + 带标记元素后面追加的视图」。
     *
     * @param parts               gtlcore 本来要遍历的那个列表（{@code getParts()} 的返回值）
     * @param isMarkedPart        「这个元素是带标记的 ME 仓室吗」（mixin 里 = 带标记 <b>且</b> 是 ME 仓室）
     * @param toNormalSupplyView  「给它造一个只含声明 handler 的普通仓室视图」（返回 {@code null} 表示跳过）
     * @param <P>                 part 的元素类型（生产里是 {@code IMultiPart}；探针里是桩类型）
     * @return <b>没有任何标记元素时：原样返回入参本身（同一个实例）</b>；
     *         否则返回一个新列表：顺序不变，只是每个带标记元素后面多一个视图
     */
    public static <P> List<P> expandForNormalSupply(List<P> parts,
                                                    Predicate<? super P> isMarkedPart,
                                                    Function<? super P, ? extends P> toNormalSupplyView) {
        if (parts == null || parts.isEmpty()) {
            // 没有仓室 ⇒ 无事可做；原样返回（哪怕是 null），不制造新对象。
            return parts;
        }
        boolean anyMarked = false;
        for (int i = 0, n = parts.size(); i < n; i++) {
            if (isMarkedPart.test(parts.get(i))) {
                anyMarked = true;
                break;
            }
        }
        if (!anyMarked) {
            // 🔴 安全边界 1：未标记 ⇒ 返回<b>同一个</b> List 实例，上游看到的东西与改前完全一致。
            return parts;
        }
        final List<P> expanded = new ArrayList<>(parts.size() + 4);
        for (int i = 0, n = parts.size(); i < n; i++) {
            final P part = parts.get(i);
            expanded.add(part);            // 🔴 原元素一定在前面，一个不少、顺序不变
            if (isMarkedPart.test(part)) {
                final P view = toNormalSupplyView.apply(part);
                if (view != null) {
                    expanded.add(view);    // 追加的只是"视图"，不是替换
                }
            }
        }
        // 带标记的元素存在，但一个视图都没造出来（声明为空 / 全被过滤掉）⇒ 还是原样返回。
        // 这样「没真的新增东西」与「没改」在上游看来完全一致（同一个实例、同样的元素、同样的顺序）。
        if (expanded.size() == parts.size()) {
            return parts;
        }
        return expanded;
    }

    /**
     * 造一个「把<b>指定的几个方法名</b>改成返回给定值、其余全部转发」的视图（JDK 动态代理）。
     *
     * <p>用它而不是手写一个 {@code implements IMultiPart} 的包装类，有两个实在的理由：
     * <ol>
     *   <li>上游 {@code IMultiPart} / {@code IDistinctPart} 未来加方法时，这里<b>不需要跟着改</b>
     *       （不会因接口变长而编译失败）；</li>
     *   <li>本类因此<b>完全不引用</b> {@code IMultiPart} / {@code IRecipeHandlerTrait} 等游戏类型
     *       ⇒ 可以离线探针直接跑（见 {@code temp/universal-part/probe/}）。</li>
     * </ol>
     *
     * <p>覆盖是<b>按方法名</b>匹配的（生产里用到三个）：
     * <ul>
     *   <li>{@code getRecipeHandlers} → 只含声明 handler 的快照（安全边界 2 的落点）；</li>
     *   <li>{@code isDistinct} → {@code false}（让它按「非独立输入仓」的语义并入共享池）；</li>
     *   <li>{@code setDistinct} → {@code null}（no-op；视图是临时的，不该被谁改写状态）。</li>
     * </ul>
     * {@code Object} 的 {@code equals/hashCode/toString} 一律转发给 {@code delegate}
     * （否则日志/调试与任何按对象相等的集合都会看走样）。
     *
     * @param delegate     真实仓室（所有未覆盖的方法都转给它）
     * @param iface        主接口（生产里是 {@code IMultiPart.class}，方法的返回类型）
     * @param extraIfaces  额外要实现的接口（生产里是 {@code {IDistinctPart.class}}，
     *                     因为 gtlcore 的普通分支门栅是 {@code FluidHatchPartMachine || IDistinctPart}）
     * @param overrides    方法名 → 该方法的返回值
     * @param <P>          接口类型
     * @return 转发视图；{@code delegate} 为 {@code null} 时返回 {@code null}
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <P> P viewWithOverrides(P delegate, Class<P> iface,
                                          Class<?>[] extraIfaces, Map<String, Supplier<?>> overrides) {
        if (delegate == null) {
            return null;
        }
        final InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() != Object.class) {
                final Supplier<?> replacement = overrides.get(method.getName());
                if (replacement != null) {
                    return replacement.get();
                }
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                // 别把真实仓室的异常包成 UndeclaredThrowableException —— 原样抛回去。
                throw e.getTargetException();
            } catch (IllegalArgumentException notInvocable) {
                // 真实仓室**没有实现**方法所在的接口时，Method.invoke 会抛这个
                // （生产里只有一种：IDistinctPart 的 default 方法，例如 attachConfigurators ——
                //  真实星律并不是 IDistinctPart）。
                // 此时按「这个接口正常实现」办：走接口默认实现，而不是把异常抛给上游。
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw notInvocable;
            }
        };
        // 合并接口（去重：JDK Proxy 不接受重复接口）
        final List<Class<?>> types = new ArrayList<>();
        types.add(iface);
        if (extraIfaces != null) {
            for (int i = 0; i < extraIfaces.length; i++) {
                final Class<?> extra = extraIfaces[i];
                if (extra != null && !types.contains(extra)) {
                    types.add(extra);
                }
            }
        }
        return iface.cast(Proxy.newProxyInstance(iface.getClassLoader(), types.toArray(new Class<?>[0]), handler));
    }

    /**
     * 「注入第一次真的被执行」时返回 {@code true}（其余时候 {@code false}）。
     *
     * <p>它是 <b>P5 实机验收的第一条判据</b>：只要有一台多方块成型（任何一台都行），
     * {@code upDate()} 就会被调用一次 ⇒ 日志里应当出现
     * {@code [SHANHAI-UNIVERSAL] 注入生效}。没有这行 ⇒ 注入<b>没挂上</b>（先去核 mixin 的
     * {@code method/target} 与 gtlcore 版本，别急着怀疑星律）。
     *
     * @return 本次调用是否是本进程里第一次
     */
    public static boolean claimFirstHookLog() {
        return FIRST_HOOK_LOGGED.compareAndSet(false, true);
    }

    /**
     * 「第一次真的扩出视图」时返回 {@code true}（其余时候 {@code false}）——给 mixin 用来只记一行日志。
     *
     * <p>为什么需要它：{@code upDate()} 会被 ME 缓冲变更监听反复调用，
     * 每次都打日志会把 {@code latest.log} 刷爆；但<b>一行都不打</b>就没法判断注入到底有没有生效。
     *
     * @return 本次调用是否是本进程里第一次
     */
    public static boolean claimFirstExpansionLog() {
        return FIRST_EXPANSION_LOGGED.compareAndSet(false, true);
    }
}
