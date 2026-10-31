package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;

/**
 * <b>【通配符段的"扣料前联合预检"】—— 纯逻辑核（零 Minecraft / GT 依赖 ⇒ 可离线 java 单跑探针）。</b>
 *
 * <h2>一、它要堵的那条已证实的丢料路径</h2>
 * {@code org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart#handleRecipe}
 * （反编译 {@code temp\wildbug\dec\src-out6\...\MEPatternRecipeHandlePart.java:132-142}）里
 * <b>物品与流体是分开判、且用 {@code &&} 短路</b>：
 * <pre>
 *   for (int slot : this.itemHandler.getActiveSlots()) {
 *      if (!cachedSlots.contains(slot)
 *          &amp;&amp; (!hasItemx  || this.itemHandler.meHandleRecipe(recipe, simulate, slot))   // ← 先扣物品
 *          &amp;&amp; (!hasFluid  || this.fluidHandler.meHandleRecipe(recipe, simulate, slot)))  // ← 再扣流体
 *      { return slot; }
 *   }
 *   return -1;
 * </pre>
 * ⇒ 当 {@code simulate == false}、<b>物品扣成功而流体返回 false</b> 时，这个槽的料已经被扣走，
 * 循环却继续去试下一个槽，最后可能 {@code return -1}：
 * <ul>
 *   <li>{@code RecipeRunner.handleInputDistinct} 返回 false ⇒ 配方不成立；</li>
 *   <li>退料口只有 GUI 的 {@code refundAll}（{@code RecipeLogic#interruptRecipe/inValid} 都<b>不退</b>，
 *       见 {@code temp\wildbug\报告.md} §4）⇒ <b>料凭空消失</b>。</li>
 * </ul>
 *
 * <h2>二、做法（报告 §8 里风险最低的那一档：不改上游、只加一道"更早的否决"）</h2>
 * 物品侧<b>还没扣</b>之前（同一次 {@code meHandleRecipeInner} 的 HEAD）先问一句：
 * 「这条配方的流体侧，在这个槽上以 <b>simulate=true</b> 走一遍能过吗？」
 * 过不了 ⇒ 这一侧<b>直接返回 false</b>（<b>一个物品都不扣</b>），上游的 {@code &&} 短路会自然去试下一个槽。
 *
 * <h2>三、边界（一条都不能少，少一条就会把能跑的配方拦掉）</h2>
 * <ol>
 *   <li><b>只在真扣遍生效</b>（{@code simulate == false}）：模拟遍本来就不动库存，没必要迟滞；</li>
 *   <li><b>只在物品侧生效</b>：流体侧被问到时物品已经扣过了，再预检没有意义（也拦不住已发生的扣料）；</li>
 *   <li><b>只对通配符段（槽号 ≥ 1080）生效</b>：普通槽（0..1079）逐位保持上游行为 —— 这是回归红线；</li>
 *   <li><b>只在配方确实有流体输入时生效</b>：没有流体能力 ⇒ 上游那条 {@code &&} 的右半边恒真、不存在半扣，
 *       此时<b>绝不能</b>去问流体 handler（它手里那份 {@code preparedMEHandleContents} 可能是上一条配方留下的），
 *       问它就是在拿别的配方的流体判这一条；</li>
 *   <li><b>可一键关</b>：{@link #DISABLE_ENV}（{@code 1/true/yes/on}）⇒ 本类一律不否决
 *       ⇒ 行为回到"没有这道预检"的原样（负对照探针就靠它，见 {@code temp\wildfix\probe}）。</li>
 * </ol>
 *
 * <h2>四、明确<b>不做</b>的事</h2>
 * <b>绝不</b>「预检不过就静默放行」—— 那等于把这次扣料放回原来的半扣路径，等于重新引入串配方/丢料。
 * 预检不过的唯一后果是<b>这个槽拒掉这条配方</b>（上游换下一个槽，与类型闸拒掉的语义完全相同）。
 *
 * <h2>五、残余风险（写在代码里，别只写在报告里）</h2>
 * 流体侧的 {@code simulate=true} 与 {@code simulate=false} <b>不是同一份判据</b>：
 * {@code InternalSlot.handleFluidInternal} 的模拟遍用 {@code getBestFluidMatchSimulate(.., getFluidCatalystInventory(), ..)}
 * （<b>多看一份催化剂</b>），真扣遍用 {@code getBestFluidMatch(..)}。
 * ⇒ 理论上存在「模拟遍通过、真扣遍失败」与「模拟遍失败、真扣遍本可通过」两种不对称：
 * 前者本预检<b>拦不住</b>（残余丢料），后者会<b>多拒一个槽</b>（见 {@code MATGUARD} 日志行，可据此关掉本闸）。
 */
public final class ShanhaiWildcardMaterialGuard {

    /** 回退位：{@code SHANHAI_DISABLE_MATERIAL_GUARD=1} ⇒ 本预检整体不生效（行为回到上游原样）。 */
    public static final String DISABLE_ENV = "SHANHAI_DISABLE_MATERIAL_GUARD";

    /**
     * 预检重入标记。
     *
     * <p>为什么需要：预检要<b>反调流体 handler</b>，而那个 handler 正是本 mixin 的另一个目标类 ⇒
     * 不做保护会再进一遍闸（多打判定行、也多做一次无意义的判定）。
     * 放在<b>普通类</b>里（不是 mixin 里）是有意的：mixin 的静态字段是"每个目标类各一份"，
     * 两个目标类会各拿各的，重入根本没挡住。
     */
    private static final ThreadLocal<Boolean> IN_GUARD = new ThreadLocal<>();

    private ShanhaiWildcardMaterialGuard() {}

    /** 回退位是否打开（每次现读，<b>不缓存</b> ⇒ 运行期也能改；与类型闸的 {@code ShanhaiSlotTypeGate.disabled()} 同风格）。 */
    public static boolean disabled() {
        try {
            if (isTruthy(System.getProperty(DISABLE_ENV))) {
                return true;
            }
            return isTruthy(System.getenv(DISABLE_ENV));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTruthy(String raw) {
        if (raw == null) {
            return false;
        }
        final String v = raw.trim();
        return v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on");
    }

    /** 当前线程是否正在做这次预检（= 这次 {@code meHandleRecipeInner} 是预检反调进来的，闸应当整段跳过）。 */
    public static boolean inGuard() {
        return Boolean.TRUE.equals(IN_GUARD.get());
    }

    /**
     * 进入预检作用域。
     *
     * @return {@code true} = 进去了（调用方必须在 finally 里 {@link #exitGuard()} 一次）；
     *         {@code false} = 已经在预检里（重入）⇒ 调用方<b>别再进</b>
     */
    public static boolean enterGuard() {
        if (inGuard()) {
            return false;
        }
        IN_GUARD.set(Boolean.TRUE);
        return true;
    }

    /** 退出预检作用域（幂等；永远不抛）。 */
    public static void exitGuard() {
        try {
            IN_GUARD.remove();
        } catch (Throwable ignored) {
            // ThreadLocal 清理失败不影响任何判定
        }
    }

    /**
     * <b>唯一判定点</b>（纯逻辑；探针实跑的就是这一个方法）。
     *
     * @param simulate            这一次 {@code meHandleRecipeInner} 是模拟遍还是真扣遍
     * @param itemHandler         被问的是不是<b>物品</b>侧 handler
     * @param wildcardSlot        槽号是否落在通配符段（{@code ≥ ShanhaiWildcardLayout.PATTERN_SLOTS}）
     * @param recipeHasFluidInputs 这条配方有没有流体输入（{@code getInputContents(FluidRecipeCapability.CAP)} 非空）
     * @param fluidSimulateOk     流体侧以 {@code simulate=true} 在<b>同一个槽</b>上跑通的结论
     * @return {@code true} = <b>否决这一次物品侧真扣</b>（不扣任何料）
     */
    public static boolean vetoItemPass(boolean simulate, boolean itemHandler, boolean wildcardSlot,
                                       boolean recipeHasFluidInputs, boolean fluidSimulateOk) {
        if (disabled()) {
            return false;
        }
        if (simulate) {
            return false;
        }
        if (!itemHandler) {
            return false;
        }
        if (!wildcardSlot) {
            return false;
        }
        if (!recipeHasFluidInputs) {
            return false;
        }
        // 到这里：真扣遍、物品侧、通配符段、配方确实要流体 ⇒ 流体过不了就不许扣物品。
        return !fluidSimulateOk;
    }

    /** 一行式判据串（日志用；格式稳定便于 grep）。 */
    public static @NotNull String describe(boolean veto, boolean fluidSimulateOk) {
        return "matguard veto=" + veto + " fluidSim=" + (fluidSimulateOk ? "ok" : "blocked")
                + " env=" + DISABLE_ENV + (disabled() ? "=1(GUARD_OFF)" : "=unset/off");
    }
}
