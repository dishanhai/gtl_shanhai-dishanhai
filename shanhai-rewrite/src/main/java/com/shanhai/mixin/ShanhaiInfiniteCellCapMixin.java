package com.shanhai.mixin;

import com.shanhai.ShanhaiMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>把「ME 无限元件」的单键容量抬到 long 上限</b>（物品 {@code 9E}、流体 {@code 9P}，按 AE 终端的显示口径）。
 *
 * <h2>需求（用户原话，两轮）</h2>
 * <ol>
 *   <li>「这个元件是通过一个 mod 注册的，但是整合包中 ae 的无限元件是 <b>9.22e</b>，
 *       但是这个 mod 没有相应的修改，<b>还是 2.1g</b>，你想个办法把它变成 9.22e」</li>
 *   <li>「我们那个无限元件的流体写少了，应该是 <b>9.2P</b> 而不是 <b>9.2T</b>」← 本文件这一版修的就是它</li>
 * </ol>
 *
 * <h2>🔴 9.2T 的根因：AE 终端显示流体时把存量 <b>除 1000</b>（mB → B）</h2>
 * 证据全部来自 {@code javap}（jar 见下），偏移是 {@code javap -c} 的 bytecode offset：
 * <pre>
 * jar  sha256 = F2998A8812EFDA7FA0FFEC25B70751F67DC0036ECFFF63E74B9402CBAAD6CDBB
 *      (meinfinitycell-1.20.1-5.0.0.jar)
 * AE2  appliedenergistics2-forge-15.4.10.jar  sha256 = FBFEE05C6674CB6B00FE425E945CA4818D41EB34069B60DCB4A36221A0390FCC
 *
 * ① Meinfinitycell#getMax(AEKey)J          ← 全库唯一容量源（static）
 *      0: ldc2_w 2147483647l
 *      3: aload_0
 *      4: invokevirtual AEKey.getAmountPerUnit()I
 *      7: i2l     8: lmul     9: lreturn        ⇒ 返回值 = 2147483647 * perUnit，单位 = AE 内部单位
 *
 * ② AEKey#getAmountPerUnit()I   → 转调 AEKeyType.getAmountPerUnit()I
 *    AEFluidKeys#getAmountPerUnit()I   0: sipush 1000      3: ireturn     ⇒ 流体 = 1000
 *    AEFluidKeys#getUnitSymbol()J      0: ldc "B"          2: areturn     ⇒ 流体显示单位 = B(桶)
 *    AEItemKeys 没有覆写 ⇒ 落到 AEKeyType#getAmountPerUnit()I 0: iconst_1  ⇒ 物品 = 1
 *
 * ③ 终端显示路径（谁除的 1000，就是这里）
 *    MEStorageScreen（amount 列渲染）
 *      195: iload 8 / 200: getstatic AmountFormat.SLOT_LARGE_FONT / 206: getstatic AmountFormat.SLOT
 *      220: invokevirtual AEKey.formatAmount(JLAmountFormat;)Ljava/lang/String;
 *    AEKey#formatAmount     0..6 → getType().formatAmount(...)
 *    AEKeyType#formatAmount 44 → formatFullAmount(J) / 52 → formatShortAmount(JI) with 4 / 61 → 同 with 3
 *    AEKeyType#formatShortAmount(JI)
 *       0..13: if (getAmountPerUnit() <= 1) goto 25      ← 物品直接格式化 long，不除
 *      16: lload_1  17: l2d
 *      18: aload_0  19: invokevirtual getAmountPerUnit()I  22: i2d
 *      23: ddiv                                            ← 流体：amount / 1000.0
 *      24: dstore 4 → ReadableNumberConverter.format(D, I)
 *    AEKeyType#formatFullAmount(J)  8..23: 同样的 ddiv（FULL 口径也除）
 *    Repo（终端内部换算用的 helper） 1..14: (double) getStoredAmount() / (double) getAmountPerUnit()
 *    ⇒ <b>终端看到的数字 = 存量 / 1000（流体）</b>；物品 perUnit=1 ⇒ 不除。
 *
 * ④ 于是：旧实现的流体 cap = Long.MAX_VALUE / 1000 = 9.223372036854775e15 <b>mB</b>
 *          ⇒ 终端显示 9.223372036854e12 <b>B</b> = <b>9.2T</b> ← 与用户实测一模一样 ✔
 *    改成：cap = Long.MAX_VALUE = 9.223372036854775807e18 mB
 *          ⇒ 终端显示 9.223372036854775e15 B = <b>9.2P</b> ✔（用 AE2 自己的格式化器实测输出 "9P"）
 * </pre>
 *
 * <h2>🔴 为什么 perUnit 不再参与除法（本版的核心改动）</h2>
 * 旧实现写的是 {@code cap = Long.MAX_VALUE / max(1, perUnit)}，依据是「下游还会再乘一次 perUnit」。
 * <b>这个前提不成立</b>：{@code getMax} 的 4 个调用点全都把它当<b>最终存量</b>用，谁都不再乘 perUnit：
 * <pre>
 * KeyList#lambda$getAvailableStacks$0  19: invokestatic getMax(...)  22: invokevirtual KeyCounter.add(AEKey,J)V
 * KeyList#lambda$getTooltipImage$1     26: getMax(...)  → new GenericStack(AEKey, J)      （tooltip 存量）
 * InfinityCellInventory#getAvailableStacks  9: getMax(...)  12: KeyCounter.add(AEKey,J)V  （每块元件加一次）
 * InfinityCell#...                     28: getMax(...)  → GenericStack(AEKey, J)          （元件 tooltip）
 * </pre>
 * ⇒ perUnit 只影响「显示时除多少」，不影响「能放多少」。所以除一次就等于<b>白送掉 1000 倍</b>。
 * 现在 perUnit <b>只用于一行日志</b>（说明这次是物品 1 还是流体 1000），见 {@code shanhai$capFor}。
 *
 * <h2>🔴 代价：同一 key 有 ≥2 个来源时会绕回负数（物品和流体<b>现在都有</b>）</h2>
 * AE2 的累加是<b>裸 long 加</b>，没有饱和、没有夹取（逐层 bytecode）：
 * <pre>
 * KeyCounter#add(AEKey,J)          9: invokevirtual getSubIndex(AEKey)VariantCounter
 *                                 14: invokevirtual VariantCounter.add(AEKey,J)V
 * VariantCounter#add(AEKey,J)      0..6: getRecords().addTo(AEKey,J)J
 * AEKey2LongMap$OpenHashMap#addTo  0..3: invokespecial it/unimi/dsi/fastutil/objects/Object2LongOpenHashMap.addTo
 * AEKey2LongMap$AVLTreeMap#addTo   0..3: 同上（fuzzy 子表 Object2LongAVLTreeMap）
 * fastutil Object2LongOpenHashMap#addToValue(II J)
 *                                  8..17: value[pos] = value[pos] + incr，ladd，无任何边界检查
 * </pre>
 * 离线实测（直接调用 AE2 用的那个 fastutil 方法）：
 * <pre>
 * addTo(k, Long.MAX) 第一次 → 存 9223372036854775807
 * addTo(k, Long.MAX) 第二次 → 返回旧值 9223372036854775807，表里变成 <b>-2</b>   ← 绕回，不饱和
 * </pre>
 * ⇒ 两块无限元件（或一块元件 + 网络里同种物品/流体）⇒ {@code 9.22e18 + 9.22e18 = -2}。
 * 而且客户端<b>不会显示负数</b>：{@code ReadableNumberConverter#format(long,int)} 0..13 是
 * {@code Preconditions.checkArgument(amount >= 0)} ⇒ 直接 {@code IllegalArgumentException}
 * （双精度重载 0..13 同样有这条；流体走的正是 double 重载）。所以症状是<b>那一格渲染报错/空白</b>，不是负数。
 *
 * <h3>回退办法（改一个常数即可，别改别的）</h3>
 * <ul>
 *   <li>{@code shanhai$capFor} 里返回 {@code Long.MAX_VALUE / 2}（4.61E）⇒ 2 个来源以内不绕回；</li>
 *   <li>想彻底留余量 ⇒ 返回 {@code 1000000000000000000L}（1.00E）：<b>9 个来源以内都不绕回</b>，</li>
 *   <li>注意这个常数现在是<b>物品和流体共用</b>的一个上限，压低时两档一起变（流体显示 = 常数/1000）。</li>
 * </ul>
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
 */
@Mixin(targets = "net.yxiao233.meinfinitycell.Meinfinitycell", remap = false)
public class ShanhaiInfiniteCellCapMixin {

    /** 原版常量（{@code javap -c} 实证的 {@code ldc2_w #64}）。只用来从返回值反解 perUnit（算日志口径）。 */
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
     * 单键容量。<b>返回值对所有 key 类型都是 {@code Long.MAX_VALUE}</b>（物品 9E / 流体 9P）。
     *
     * <p>{@code perUnit} 在这里<b>只服务日志口径</b>，<b>不参与任何算术</b> ——
     * 因为「AE 显示 = 存量 / perUnit」这条（见类注释 ③），除一次就等于白送 1000 倍；
     * 而 {@code getMax} 的 4 个调用点都把它当最终存量，下游不会再乘回来。
     *
     * @param perUnit 从原返回值反解出来的单位（物品 = 1、流体 = 1000）；只用来打日志，不参与计算
     * @return 恒为 {@code Long.MAX_VALUE}
     */
    @Unique
    private static long shanhai$capFor(final long perUnit) {
        // perUnit 只用于日志口径（1 = 物品、1000 = 流体），返回值与它无关 ——
        // 理由见类注释 ③：「显示 = 存量 / perUnit」⇒ perUnit 只决定显示时除多少，不决定能放多少。
        // 之所以仍保留这个参数：① 日志行要打它；② 以后若要按单位分档（例如只给流体留余量），改这一处就够。
        return Long.MAX_VALUE;
    }

    /**
     * {@code getMax(AEKey)} 的 {@code RETURN} 处改写返回值（见类注释：perUnit 不再除 cap）。
     *
     * <p>{@code cancellable = true}：改写返回值的前提。
     * <p>{@code require = 0}：目标变了就跳过（fail-soft），不把启动打崩。
     * <p>{@code remap = false}：非 MC 目标，见类注释。
     */
    @Inject(method = "getMax", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void shanhai$raiseInfiniteCellCap(CallbackInfoReturnable<Long> cir) {
        final long orig = cir.getReturnValue();                       // = 2147483647L * perUnit
        final long perUnit = orig / SHANHAI$ORIGINAL_CAP;             // item = 1，fluid = 1000
        final long cap = shanhai$capFor(perUnit);                     // = Long.MAX_VALUE（不再除 perUnit）
        if (SHANHAI$loggedPerUnit != perUnit) {
            SHANHAI$loggedPerUnit = perUnit;
            // 判据（实机验收）：grep -F '[SHANHAI-INFCELL] cap_patched' logs/latest.log
            // 期望：perUnit=1 → to=9223372036854775807（终端 9E）；perUnit=1000 → 同一个 to（终端 9P）
            ShanhaiMod.LOGGER.info("[SHANHAI-INFCELL] cap_patched from={} to={} perUnit={} orig={}",
                    SHANHAI$ORIGINAL_CAP, cap, perUnit, orig);
        }
        cir.setReturnValue(Long.valueOf(cap));
    }
}
