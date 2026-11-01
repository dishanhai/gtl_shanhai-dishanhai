package com.shanhai.machine.creation;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

/**
 * 把一块样板的<b>产物数量</b>按「主机增产倍率」放大后<b>报给 AE</b>。
 *
 * <p>除 {@link #getOutputs()} 与 {@link #supportsPushInputsToExternalInventory()} 之外，
 * <b>其余方法一律委托原样板</b>（输入表 {@link #getInputs()} 尤其必须逐项一致：
 * 本类只改"AE 看到几个产物"和"AE 肯不肯合并派单"，<b>绝不改</b>任何输入的数量）。
 *
 * <h2>为什么必须有这一层（用户原话）</h2>
 * <blockquote>「只吃主机增产，那个倍率是你下单是为了方便 ae 计算用的啊，不然它产出 17 个 ae
 * 下单计算时还以为产出了 1 个，这样就多合成了很多份，这个超级样板总成里面的神锻倍率就是这个意思啊」</blockquote>
 * ⇒ 神锻模式开着时，机器实际一块样板出 {@code 原产物 × 倍率} 个；若报给 AE 的还是"原产物 1 个"，
 * AE 就会<b>多下单</b>（要 17 个却以为要做 17 次）。所以 {@code getAvailablePatterns()} 里必须返回
 * "产物数量已放大"的样板。
 *
 * <h2>🔴 与"实际出货"的一致性（只有一层乘法）</h2>
 * <pre>
 *   报给 AE 的：{@code getOutputs()} = 原产物 × 倍率           ← 本类
 *   实际出货　：产物 = 原产物 × 本次份数 × 倍率                ← CreationCraftExecutor
 *   ⇒ 一块样板 = 一次份数 ⇒ 两边严格相等（"报的数"与"出的数"是同一个倍率）
 * </pre>
 * <b>因此接单时不许再乘第二次</b>：{@code pushPattern} 收到的 {@code IPatternDetails} 很可能就是本类的
 * 实例（AE 会把它从 {@code getAvailablePatterns()} 拿到的那个对象原样递回来）⇒ 执行器必须先用
 * {@link #delegateOf(IPatternDetails)} <b>拆回原样板</b>再取产物，否则会变成"倍率平方"。
 *
 * <h2>🔴 恒包装（P6 起：倍率 = 1 也必须包，<b>没有任何一条裸样板</b>能到 AE 手里）</h2>
 * 本类<b>同时</b>承担第二件事：把 {@link #supportsPushInputsToExternalInventory()} 恒答成 {@code true}
 * （见下）。如果"倍率 = 1 就不包"，那台机器报给 AE 的就是<b>裸的 {@code AECraftingPattern}</b>，
 * 而它对这个问题答 {@code false}（合成样板实现了更具体的
 * {@code IMolecularAssemblerSupportedPattern}，该接口把这个 default 定成 false；
 * JVMS 5.4.3.3 的 maximally-specific 解析 ⇒ 取 false）⇒ <b>倍率 1 的机器永远不合并</b>。
 * ⇒ 所以包装<b>与倍率无关</b>：{@code getAvailablePatterns()} 的每一个元素都是本类实例。
 *
 * <h2>⚠️ 本类不改变的东西（自证里逐项对拍过）</h2>
 * <ul>
 *   <li><b>输入集合 / 输入数量</b>：{@code getInputs()} 原样返回 delegate 的数组（同一个对象）；</li>
 *   <li><b>产物在倍率 = 1 时逐项相等</b>：{@code getOutputs()} 是 delegate 的副本，值与数量逐项相同；</li>
 *   <li><b>份数 / 余量 / 出货</b>：算术仍全在 {@link CreationCraftMath}，本类一个算式都没有。</li>
 * </ul>
 */
public final class CreationScaledPattern implements IPatternDetails {

    private final IPatternDetails delegate;
    private final int multiplier;
    private final GenericStack[] scaledOutputs;

    /**
     * @param delegate   原样板（必须是 AE 解码出来的那个；非空）
     * @param multiplier 主机增产倍率（≥ 1）；<b>1 也照样包</b>（见类注释「恒包装」）
     */
    public CreationScaledPattern(IPatternDetails delegate, int multiplier) {
        this.delegate = delegate;
        this.multiplier = Math.max(1, multiplier);
        this.scaledOutputs = shanhai$scale(delegate.getOutputs(), this.multiplier);
    }

    private static GenericStack[] shanhai$scale(GenericStack[] outputs, int multiplier) {
        if (outputs == null || outputs.length == 0) {
            return new GenericStack[0];
        }
        final GenericStack[] scaled = new GenericStack[outputs.length];
        for (int i = 0; i < outputs.length; i++) {
            final GenericStack stack = outputs[i];
            if (stack == null || stack.what() == null) {
                scaled[i] = null;
                continue;
            }
            // 逐项放大：多产物全部保留（上游只取 [0] 是它的短板，不是我们的口径）。
            scaled[i] = new GenericStack(stack.what(),
                    CreationCraftMath.scaleOutput(stack.amount(), 1L, multiplier));
        }
        return scaled;
    }

    /** 原样板（拆包装用）。 */
    public IPatternDetails delegate() {
        return delegate;
    }

    /** 本包装用的倍率（= 报给 AE 的那个倍率）。 */
    public int multiplier() {
        return multiplier;
    }

    /**
     * 拆包装：拿到<b>真正的</b>样板（可能本身就是真的，那就原样返回）。
     *
     * <p>🔴 {@code pushPattern} / 出货路径<b>必须</b>经过它 —— 否则会把"已经放大过"的产物再放大一次。
     */
    public static IPatternDetails delegateOf(IPatternDetails pattern) {
        return pattern instanceof CreationScaledPattern scaled ? scaled.delegate : pattern;
    }

    /**
     * 取"这块样板当初是按几倍报给 AE 的"；不是本类实例就返回 {@code fallback}（= 现读主机）。
     *
     * <p>为什么优先用包装里的倍率而不是现读主机：AE 决定派这一单时<b>看的就是包装里那个数</b>，
     * 用它才能保证"AE 算的"与"我们出的"逐位一致（主机中途变了也不会让这一单的期望落空）。
     */
    public static int multiplierOf(IPatternDetails pattern, int fallback) {
        return pattern instanceof CreationScaledPattern scaled ? scaled.multiplier : fallback;
    }

    // ═════════════════ IPatternDetails：除 getOutputs / supportsPushInputsToExternalInventory 外全部委托 ═════════════════

    @Override
    public AEItemKey getDefinition() {
        return delegate.getDefinition();
    }

    @Override
    public IPatternDetails.IInput[] getInputs() {
        return delegate.getInputs();
    }

    /**
     * 放大后的产物表（<b>返回副本</b>：AE 侧对数组没有任何"只读"承诺，共享内部数组等于把我们的状态
     * 交给别人改；产物项很少，克隆的代价可以忽略）。
     */
    @Override
    public GenericStack[] getOutputs() {
        return scaledOutputs.clone();
    }

    /**
     * <b>「AE 可不可以把输入推到外部库存」= 恒 {@code true}（🔴 P6 的核心覆写，不再是委托）</b>。
     *
     * <h2>这个回答是给谁看的</h2>
     * AE2 自己只在三处引用它（对 jar 逐个 class 扫常量池得到）：
     * {@code IPatternDetails}、{@code IMolecularAssemblerSupportedPattern}、
     * {@code PatternProviderLogic}（原版样板供应器推料，我们这台机器不经它）。
     * <b>真正读它的是 gtlcore</b>：
     * <pre>
     *   CraftingCpuLogicMixin#executeCrafting
     *     偏移 125: boolean isProcessing = details.supportsPushInputsToExternalInventory();
     *     偏移 202: CraftingPatternAutoExpand.canAutoExpand(isProcessing, provider)
     *   CraftingPatternAutoExpand#canAutoExpand 第 0-5 字节：
     *     0: iload_0 / 1: ifne 6 / 4: iconst_0 / 5: ireturn   ← false 就在这里直接返回 false
     * </pre>
     * ⇒ 答 {@code false} 时，<b>后面"是不是 IAutoExpandSettings"那四支白名单根本轮不到</b>
     * （我们的执行器明明 implements 了它、也明明恒返回 true）⇒ AE 每次只肯派 1 份。
     *
     * <h2>为什么答 {@code true} 是<b>符合事实</b>的（不是骗 AE）</h2>
     * 这个方法在 AE 里的语义是"<b>这块样板能不能不摆合成台/机器方块、由接收方自己把料吃下去做成产物</b>"
     * （原版只有"接在样板供应器上的外部库存"和"样板供应器自己推料"这两种答 true 的形态）。
     * 本机<b>确实</b>是这么干的：料进 {@code pushPattern} 之后由本机自己按 10 tick 周期消化、
     * 产物由本机 {@code MEStorage#insert} 回网络，<b>不需要 AE 去找任何合成台</b>
     * ⇒ 回答 {@code true} 与物理事实一致。
     *
     * <h2>🔴 它只影响"派单形态"，<b>不改变任何数量</b></h2>
     * <ul>
     *   <li>不改输入：{@link #getInputs()} 仍是 delegate 原样；</li>
     *   <li>不改产物：{@link #getOutputs()} 仍只按倍率放大（倍率 = 1 ⇒ 逐项等于原样板）；</li>
     *   <li>不改份数/余量/出货：这些全在 {@code CreationCraftMath} 与执行器里，一行没动；</li>
     *   <li>它<b>不会</b>让 AE 少扣或多扣料 —— 扣料仍是 AE 自己的
     *       {@code extractForProcessingPattern}/{@code extractForCraftPattern}，
     *       我们只按收到的账目算份数（{@code craftsOf}）。</li>
     * </ul>
     *
     * <h2>⚠️ 已知代价（写在这里，不许偷偷带过）</h2>
     * 答 true 之后 gtlcore 会走 {@code AEUtils#extractForProcessingPattern} 那条抽取路径，
     * 而它<b>只认 {@code getPossibleInputs()[0]}</b>。合成样板若某一格是"可替代/标签"输入、
     * 而 ME 里放的是另一个变体 ⇒ 它会抽不到（返回 null，本单停在"等待原料"）。
     * 我们自己的 {@code craftsOf} 是"挑账目里真出现的那个候选"，比它稳，但抽取这一步在 AE 侧、
     * 我们插不进去。⇒ 见 {@code temp/molmanip/P6扩批与显示.md}「待用户确认」。
     */
    @Override
    public boolean supportsPushInputsToExternalInventory() {
        // 🔴 故意【不】委托：delegate 是 AECraftingPattern 时它恒 false（更具体的父接口
        //    IMolecularAssemblerSupportedPattern 把它定成 false）⇒ 委托 = 永远不合并。
        return true;
    }

    /**
     * 真正的"把输入推给外部库存"的动作 —— <b>原样委托</b>（本机不走这条，见上一个方法）。
     *
     * <p>保留委托而不是抛异常：AE2 里只有 {@code PatternProviderLogic} 会调它（原版样板供应器 +
     * 外部库存的组合），我们这台机器不是 {@code PatternProviderLogic} ⇒ 运行期不会被调到；
     * 万一将来有别的模组调，委托给原样板是唯一不撒谎的答案。
     */
    @Override
    public void pushInputsToExternalInventory(KeyCounter[] inputHolder, PatternInputSink inputSink) {
        delegate.pushInputsToExternalInventory(inputHolder, inputSink);
    }

    @Override
    public String toString() {
        final AEItemKey definition = delegate.getDefinition();
        final String name = definition == null ? String.valueOf(delegate) : definition.getDisplayName().getString();
        return "CreationScaledPattern(" + name + " ×" + multiplier + ")";
    }

    /** 只给日志/诊断用：这块样板（或它的委托）当前报出去的产物汇总。 */
    public static String shanhai$describeOutputs(GenericStack[] outputs) {
        if (outputs == null || outputs.length == 0) {
            return "-";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < outputs.length; i++) {
            final GenericStack stack = outputs[i];
            if (stack == null || stack.what() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            final AEKey key = stack.what();
            sb.append(stack.amount()).append("x ").append(key.getId());
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }
}
