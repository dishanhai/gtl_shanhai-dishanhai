package com.shanhai.api.machine;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.trait.IRecipeHandlerTrait;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import com.lowdragmc.lowdraglib.syncdata.ISubscription;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 「<b>只匹配、绝不抽取</b>」的流体供料 handler —— 给「万能仓室」的
 * <b>通用催化剂槽（流体）</b>{@code sharedCatalystTank} 用；与
 * {@link ShanhaiSupplyOnlyItemHandler} 逐条同构，只是 capability 与数量口径换成流体的。
 *
 * <h2>语义（与物品版逐条相同）</h2>
 * <ol>
 *   <li>{@link #getContents()} 报真实内容物；</li>
 *   <li>{@link #handleRecipeInner}：全部满足 ⇒ {@code null}；不满足 ⇒ <b>原样返回入参 {@code left}</b>；</li>
 *   <li>🔴 <b>永不抽取</b>：全篇没有 {@code drain / fill / setFluid} 之类调用，
 *       内容物只通过构造器传入的只读 {@link Supplier} 读出来 ⇒ 无论 {@code simulate} 与否，
 *       库存<b>逐字节不变</b>（探针 `E2/E4` 实测）。</li>
 * </ol>
 *
 * <h2>数量口径</h2>
 * 需求数量取 {@code FluidIngredient#getAmount()}（gtceu 自己也是这个口径，见
 * {@code NotifiableFluidTank#handleIngredient}）；库存侧按 {@code FluidStack#getAmount()} 累加。
 *
 * <h2>安全边界</h2>
 * <ul>
 *   <li>只通过 {@link IShanhaiNormalSupplyPart#shanhaiNormalSupplyHandlers()} 进普通供料名单，
 *       <b>不</b>进 ME 分支；</li>
 *   <li>不是 {@code MachineTrait} ⇒ 不会被 attach、不会污染原版 {@code capabilitiesProxy}；</li>
 *   <li>变更监听为空订阅（只读视图）。</li>
 * </ul>
 */
public final class ShanhaiSupplyOnlyFluidHandler implements IRecipeHandlerTrait<FluidIngredient> {

    /** 空订阅（只读视图，不参与变更通知）。 */
    private static final ISubscription NOOP_SUBSCRIPTION = () -> {
    };

    /** 给日志/调试用的名字。 */
    private final String label;

    /** 当前内容物的<b>只读</b>来源（每次调用现读）。 */
    private final Supplier<List<FluidStack>> contents;

    /**
     * @param label    调试名（例如 {@code "sharedCatalystTank"}）
     * @param contents 只读内容物来源；<b>调用方不得</b>在这个 Supplier 里改动库存
     */
    public ShanhaiSupplyOnlyFluidHandler(String label, Supplier<List<FluidStack>> contents) {
        this.label = label;
        this.contents = contents;
    }

    /** 调试名。 */
    public String shanhaiLabel() {
        return this.label;
    }

    @Override
    public IO getHandlerIO() {
        return IO.IN;
    }

    @Override
    public RecipeCapability<FluidIngredient> getCapability() {
        return FluidRecipeCapability.CAP;
    }

    @Override
    public boolean isProxy() {
        return false;
    }

    /** 调试名（也用在 {@link #toString()} 里：日志 / 探针能直接看出是哪一份）。 */
    @Override
    public String toString() {
        return this.label;
    }

    @Override
    public ISubscription addChangedListener(Runnable listener) {
        return NOOP_SUBSCRIPTION;
    }

    @Override
    public List<Object> getContents() {
        final List<FluidStack> now = this.contents.get();
        if (now == null || now.isEmpty()) {
            return List.of();
        }
        final List<Object> out = new ArrayList<>(now.size());
        for (int i = 0; i < now.size(); i++) {
            final FluidStack stack = now.get(i);
            if (stack != null && stack.getAmount() > 0L) {
                // 复制一份，别把库存里的对象引用交出去
                final FluidStack copy = stack.copy();
                copy.setAmount(stack.getAmount());
                out.add(copy);
            }
        }
        return out;
    }

    @Override
    public double getTotalContentAmount() {
        final List<FluidStack> now = this.contents.get();
        if (now == null || now.isEmpty()) {
            return 0.0D;
        }
        long total = 0L;
        for (int i = 0; i < now.size(); i++) {
            final FluidStack stack = now.get(i);
            if (stack != null && stack.getAmount() > 0L) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * 🔴 只判定、不消费：全部满足返回 {@code null}；任一不满足就<b>原样返回入参 {@code left}</b>。
     *
     * @param left 该 capability 下"还差多少"的需求清单
     * @return {@code null} = 全部吃下（上游判"满足"）；否则 = 入参本身
     */
    @Override
    public List<FluidIngredient> handleRecipeInner(IO io, GTRecipe recipe, List<FluidIngredient> left,
                                                   String slotName, boolean simulate) {
        if (left == null || left.isEmpty()) {
            return null;
        }
        final List<FluidStack> now = this.contents.get();
        for (int i = 0; i < left.size(); i++) {
            final FluidIngredient need = left.get(i);
            if (need == null) {
                continue;
            }
            final long required = need.getAmount();
            final long available = shanhaiCountMatching(now, need);
            if (available <= 0L || (required > 0L && available < required)) {
                // 🔴 不满足：原样返回（同一个 list 实例、长度不变）
                return left;
            }
        }
        // 🔴 全部满足：null；一件都没抽取
        return null;
    }

    /** 只读统计：{@code now} 里能被 {@code need} 命中的总量。 */
    private static long shanhaiCountMatching(List<FluidStack> now, FluidIngredient need) {
        if (now == null || now.isEmpty()) {
            return 0L;
        }
        long available = 0L;
        for (int i = 0; i < now.size(); i++) {
            final FluidStack have = now.get(i);
            if (have == null || have.getAmount() <= 0L) {
                continue;
            }
            if (need.test(have)) {
                available += have.getAmount();
            }
        }
        return available;
    }
}
