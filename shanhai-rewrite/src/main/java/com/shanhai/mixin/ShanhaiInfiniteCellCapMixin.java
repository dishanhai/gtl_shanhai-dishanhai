package com.shanhai.mixin;

import com.shanhai.ShanhaiMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>把「ME 无限元件」的单键容量从 2.1G 抬到 9.22E</b>（按 {@code amountPerUnit} 换算后封顶）。
 *
 * <h2>需求（用户原话）</h2>
 * 「这个元件是通过一个 mod 注册的，但是整合包中 ae 的无限元件是 <b>9.22e</b>，
 * 但是这个 mod 没有相应的修改，<b>还是 2.1g</b>，你想个办法把它变成 9.22e」
 *
 * <h2>目标与实证（javap，不是猜）</h2>
 * <pre>
 * jar: [ME无限元件] meinfinitycell-1.20.1-5.0.0.jar
 * sha256 = F2998A8812EFDA7FA0FFEC25B70751F67DC0036ECFFF63E74B9402CBAAD6CDBB
 *
 * public static long getMax(appeng.api.stacks.AEKey);      ← 全类库【唯一】容量源，无重载、static
 *   Code:
 *      0: ldc2_w  #64   // long 2147483647l
 *      3: aload_0
 *      4: invokevirtual #71 // AEKey.getAmountPerUnit:()I
 *      7: i2l
 *      8: lmul
 *      9: lreturn
 * </pre>
 * 调用点只有 4 处（{@code KeyList#lambda$getTooltipImage$1}、
 * {@code KeyList#lambda$getAvailableStacks$0}、{@code InfinityCell}、{@code InfinityCellInventory}），
 * 全部是「拿它当一个标称存量」⇒ 改返回值即可。
 *
 * <h2>🔴 口径：不能直接把常量换成 {@code Long.MAX_VALUE}（会溢出），必须按 perUnit 换算</h2>
 * 真身是 {@code 2147483647L * key.getAmountPerUnit()}：<b>物品 = 1、流体 = 1000</b>。
 * 直接返回 {@code 9223372036854775807L} ⇒ 流体侧 {@code ×1000} <b>回绕成负数</b>。
 * 所以这里在 RETURN 处反解出 perUnit，再<b>按 perUnit 封顶</b>：
 * <pre>
 * orig    = cir.getReturnValue()          // = 2147483647L * perUnit
 * perUnit = orig / 2147483647L            // item = 1，fluid = 1000（整除，安全）
 * cap     = Long.MAX_VALUE / max(1, perUnit)
 * </pre>
 * <ul>
 *   <li>物品：{@code cap = 9223372036854775807} ⇒ <b>9.22E</b> ✔</li>
 *   <li>流体：{@code cap = 9223372036854775} ⇒ <b>≈9.22P</b>（{@code cap * 1000 <= Long.MAX_VALUE}，不溢出）✔</li>
 * </ul>
 * 代价：数值不再是一个常数 —— 所以日志里把 {@code perUnit} 与 {@code cap} <b>都</b>打出来（见下）。
 *
 * <h2>🔴 fail-soft：目标 mod 缺席 / 方法改名，都不许把整合包打崩</h2>
 * <ol>
 *   <li>本类住在<b>单独</b>的配置 {@code shanhai.meinfinitycell.mixin.json}，
 *       与跨 mod 先例 {@code shanhai.gtladditions.mixin.json} 同一套：
 *       <b>{@code "required": false} + {@code "injectors": {"defaultRequire": 0}}</b>。</li>
 *   <li>注入点写死 <b>{@code require = 0}</b>（找不到就跳过，不静默崩）。</li>
 *   <li>Mixin 是按<b>目标类加载</b>来应用的：目标 mod 缺装 ⇒ 这个类永远不被加载 ⇒ 连"失败"都不会发生；</li>
 *   <li>目标类在、但 {@code getMax} 改名 ⇒ {@code MixinProcessor} 里实证
 *       {@code config.isRequired() ? ErrorAction.ERROR : ErrorAction.WARN} ⇒ <b>只 WARN</b>。</li>
 * </ol>
 * <p>所以<b>刻意没有</b>塞进 {@code shanhai.mixin.json} —— 那份是 {@code required: true}，
 * 混进去等于「一个可选 mod 缺装/改签名 ⇒ 整个 shanhai 起不来」。
 *
 * <h2>⚠️ 为什么不用反射改</h2>
 * {@code getMax} 是 {@code static}，且方法体里<b>没有任何字段</b>可替换（只有
 * {@code ldc2_w} 常量 + {@code getAmountPerUnit()} + {@code lmul}）⇒ 反射换不到任何东西。
 * 也不能用「Mixin 改常量」以外的写法，所以只能用 {@code @Inject(RETURN)} 改写返回值。
 *
 * <h2>⚠️ remap=false 必须逐处写全（本工程踩过的静默失效坑）</h2>
 * 目标不是 Minecraft 类，写的是它<b>自己的</b>名字 ⇒ {@code @Mixin(remap = false)}
 * 与 {@code @Inject(remap = false)} <b>两处都写</b>。漏写不报错，只是运行期找不到目标、静默不生效。
 *
 * <h2>⚠️ 为什么没有 {@code @Shadow} / 没有把 {@code AEKey} 写进签名</h2>
 * 目标方法是 {@code static} ⇒ 处理器方法也必须是 {@code static}；
 * 这里只需要 {@code @At("RETURN")} 拿到原返回值（{@code long} ⇒ {@code CallbackInfoReturnable<Long>}，Mixin 的装箱口径）
 * ⇒ 本类<b>对目标 mod 零编译期依赖</b>，不往 {@code libs/} 里加 jar、也不引用目标类任何成员。
 * 另：{@code -AdisableTargetValidator=true} 已在 {@code build.gradle} 的 compileJava 里
 * （2026-10-18 就为可选目标打开）⇒ 注解处理器不会因"目标不在 classpath"报错。
 *
 * <h2>🔴 溢出风险（必须知道，别当成"已经验过"）</h2>
 * 本类只改 {@code getMax} 的<b>返回值</b>，不做加法；但 AE2 侧的累加是<b>裸 long 加</b>：
 * <pre>
 * KeyCounter.add(key, amount)
 *   → VariantCounter.add(key, amount)
 *     → AEKey2LongMap.addTo(key, amount)        // fastutil，get(key) + value，溢出绕回负数
 * </pre>
 * 而这个 mod 自己的 {@code KeyList#getAvailableStacks} 正是
 * {@code keyCounter.add(key, getMax(key))}：<b>同一个物品 key 只要有 ≥2 个来源</b>
 * （两块超级无限元件 / 一块元件 + 网络里同种物品）就会 {@code 9.22e18 + 9.22e18 < 0} ⇒ 终端显示负数存量。
 * （流体侧是 9.22e15/键，2 个来源也才 1.8e16，<b>不会</b>溢出。）
 * 真遇到了就往回退：<b>2 个来源并存 → 让物品 cap = 4611686018427387903（4.61E）</b>；
 * 想彻底留余量 → <b>cap = 1000000000000000000（1.00E，9 个来源以内不溢出）</b>。
 * 改法：把下面 {@code Long.MAX_VALUE / Math.max(1L, perUnit)} 里的被除数换掉即可（会同时压低流体）。
 */
@Mixin(targets = "net.yxiao233.meinfinitycell.Meinfinitycell", remap = false)
public class ShanhaiInfiniteCellCapMixin {

    /** 原版常量（{@code javap -c} 实证的 {@code ldc2_w #64}）。用来从返回值反解 perUnit。 */
    @Unique
    private static final long SHANHAI$ORIGINAL_CAP = 2147483647L;

    /**
     * 上一次打日志时的 perUnit；{@code 0} = 还没打过（perUnit 恒 {@code >= 1}）。
     *
     * <p>⚠️ <b>刻意不给字段初始化器</b>：mixin 的静态字段初始化器要借宿主的 {@code <clinit>}，
     * 而 {@code long} 的默认值 {@code 0} 正好当"没打过"的哨兵 ⇒ 不需要初始化器，不赌实现细节。
     *
     * <p>⇒ 物品（perUnit=1）第一次被查询时打一行，流体（perUnit=1000）第一次被查询时再打一行，
     * 之后就安静了（不是每次查询刷屏）。
     */
    @Unique
    private static long SHANHAI$loggedPerUnit;

    /**
     * {@code getMax(AEKey)} 的 {@code RETURN} 处改写返回值（按 perUnit 封顶，见类注释）。
     *
     * <p>{@code cancellable = true}：改写返回值的前提。
     * <p>{@code require = 0}：目标变了就跳过（fail-soft），不把启动打崩。
     * <p>{@code remap = false}：非 MC 目标，见类注释。
     */
    @Inject(method = "getMax", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void shanhai$raiseInfiniteCellCap(CallbackInfoReturnable<Long> cir) {
        final long orig = cir.getReturnValue();                       // = 2147483647L * perUnit
        final long perUnit = orig / SHANHAI$ORIGINAL_CAP;             // item = 1，fluid = 1000
        final long cap = Long.MAX_VALUE / Math.max(1L, perUnit);      // cap * perUnit <= Long.MAX_VALUE
        if (SHANHAI$loggedPerUnit != perUnit) {
            SHANHAI$loggedPerUnit = perUnit;
            // 判据（实机验收）：grep -F '[SHANHAI-INFCELL] cap_patched' logs/latest.log
            ShanhaiMod.LOGGER.info("[SHANHAI-INFCELL] cap_patched from={} to={} perUnit={} orig={}",
                    SHANHAI$ORIGINAL_CAP, cap, perUnit, orig);
        }
        cir.setReturnValue(Long.valueOf(cap));
    }
}
