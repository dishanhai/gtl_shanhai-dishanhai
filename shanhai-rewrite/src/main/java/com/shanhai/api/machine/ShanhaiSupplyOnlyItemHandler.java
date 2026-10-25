package com.shanhai.api.machine;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.trait.IRecipeHandlerTrait;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.lowdragmc.lowdraglib.syncdata.ISubscription;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 「<b>只匹配、绝不抽取</b>」的物品供料 handler —— 给「万能仓室」的
 * <b>通用催化剂槽</b>（`sharedCatalystInventory`）与<b>通用编程电路槽</b>（`sharedCircuitInventory`）用。
 *
 * <h2>一、为什么必须新写（不能直接用上游那两个 handler）</h2>
 * <table border="1">
 *   <tr><th>上游 handler</th><th>它的 `handleRecipeInner` 实际语义</th><th>为什么不能用</th></tr>
 *   <tr><td>{@code com.hepdd.gtmthings...CatalystItemStackHandler}</td>
 *       <td>命中任意"同物品的配方输入"就 <b>{@code return left}</b>（把需求退回）</td>
 *       <td>gtlcore 的 {@code RecipeHandlePart#handleRecipe} 偏移 <b>54–60</b> <b>只认 {@code null} 为"满足"</b>
 *           ⇒ 它<b>永远供不了任何东西</b>（它的作用只是"不许配方吃掉我的催化剂"）</td></tr>
 *   <tr><td>{@code org.gtlcore...NotifiableCircuitItemStackHandler}</td>
 *       <td>偏移 <b>0–6</b>：{@code if (!simulate) return left;} ⇒ 只在"模拟"那一遍按配置匹配，真扣料那遍不认账</td>
 *       <td>塞进普通供料名单会变成「<b>匹配成功 → 启动步失败</b>」（比匹配不上更糟）</td></tr>
 * </table>
 * ⇒ 本类把语义<b>反过来</b>：**能整条满足就返回 {@code null}，并且一件都不抽取**。
 *
 * <h2>二、语义（父代理拍板的三条硬要求）</h2>
 * <ol>
 *   <li>{@link #getContents()} 报<b>真实内容物</b>（让匹配"看得见"）；</li>
 *   <li>{@link #handleRecipeInner}：<b>全部满足 ⇒ {@code null}</b>；<b>不满足 ⇒ 返回<b>入参那个 list 实例</b></b>
 *       （不复制、不删元素、不返回"部分满足"）；</li>
 *   <li>🔴 <b>永不抽取</b>：本类全篇<b>没有</b>任何 {@code extractItem / setStackInSlot / shrink / setCount} 调用，
 *       内容物只通过构造器传入的只读 {@link Supplier} 读出来。
 *       ⇒ 无论 {@code simulate} 是 true 还是 false，库存数量<b>逐字节不变</b>（探针 `E2/E4` 实测）。</li>
 * </ol>
 *
 * <h2>三、为什么"电路"也复用本类</h2>
 * 电路匹配的判据就写在 ingredient 自己身上：{@code IntCircuitIngredient extends StrictNBTIngredient}
 * 的 {@code test(stack)} 就是「物品是编程电路 && 配置值相等」（javap 偏移 0–37）。
 * 本类对每条需求做的正是 {@code ingredient.test(库存里的 stack)} ⇒ **配置对不对由 GT 自己判**，
 * 不需要（也不该）在 sanhai 这边重写一份电路语义。
 *
 * <h2>四、安全边界</h2>
 * <ul>
 *   <li>本 handler <b>只</b>通过标记接口 {@link IShanhaiNormalSupplyPart#shanhaiNormalSupplyHandlers()}
 *       进入「普通供料名单」⇒ 绝不会进 ME 分支（它没有实现 {@code IMEFilterIOPartMachine}）；</li>
 *   <li>它是<b>只读视图</b>，不是 {@code MachineTrait} ⇒ <b>不会</b>被 attach 到机器上、
 *       也<b>不会</b>出现在原版 {@code capabilitiesProxy} 的 trait 列表里（不改变既有的任何表）；</li>
 *   <li>{@link #addChangedListener} 返回一个空订阅：本 handler 的内容变化不影响配方缓存失效
 *       （内容物由"当前库存"现读，重建时自然会重新读）。</li>
 * </ul>
 */
public final class ShanhaiSupplyOnlyItemHandler implements IRecipeHandlerTrait<Ingredient> {

    /** 空订阅（本 handler 是只读视图，不参与变更通知）。 */
    private static final ISubscription NOOP_SUBSCRIPTION = () -> {
    };

    /** 给日志/调试用的名字。 */
    private final String label;

    /** 当前内容物的<b>只读</b>来源（每次调用现读，绝不缓存快照）。 */
    private final Supplier<List<ItemStack>> contents;

    /**
     * @param label    调试名（例如 {@code "sharedCatalystInventory"}）
     * @param contents 只读内容物来源；<b>调用方不得</b>在这个 Supplier 里改动库存
     */
    public ShanhaiSupplyOnlyItemHandler(String label, Supplier<List<ItemStack>> contents) {
        this.label = label;
        this.contents = contents;
    }

    /** 调试名。 */
    public String shanhaiLabel() {
        return this.label;
    }

    @Override
    public IO getHandlerIO() {
        // 与上游那两个槽的 handlerIO 一致（javap：构造器传 IO.IN）
        return IO.IN;
    }

    /** 调试名（也用在 {@link #toString()} 里：日志 / 探针能直接看出是哪一份）。 */
    @Override
    public String toString() {
        return this.label;
    }

    @Override
    public RecipeCapability<Ingredient> getCapability() {
        return ItemRecipeCapability.CAP;
    }

    @Override
    public boolean isProxy() {
        // 不是代理：gtlcore 的 handleNormalPart 偏移 108 会丢弃 isProxy()==true 的 handler
        return false;
    }

    @Override
    public ISubscription addChangedListener(Runnable listener) {
        return NOOP_SUBSCRIPTION;
    }

    @Override
    public List<Object> getContents() {
        final List<ItemStack> now = this.contents.get();
        if (now == null || now.isEmpty()) {
            return List.of();
        }
        final List<Object> out = new ArrayList<>(now.size());
        for (int i = 0; i < now.size(); i++) {
            final ItemStack stack = now.get(i);
            if (stack != null && !stack.isEmpty()) {
                // 复制一份：别把库存里的对象引用交出去（外面拿它做什么都与本机库存无关）
                final ItemStack copy = stack.copy();
                copy.setCount(stack.getCount());
                out.add(copy);
            }
        }
        return out;
    }

    @Override
    public double getTotalContentAmount() {
        final List<ItemStack> now = this.contents.get();
        if (now == null || now.isEmpty()) {
            return 0.0D;
        }
        long total = 0L;
        for (int i = 0; i < now.size(); i++) {
            final ItemStack stack = now.get(i);
            if (stack != null && !stack.isEmpty()) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 🔴 只判定、不消费：全部满足返回 {@code null}；任一不满足就<b>原样返回入参 {@code left}</b>。
     *
     * <p>数量口径与 gtceu 自己的 {@code NotifiableItemStackHandler#handleIngredient} 一致：
     * 需求数量取该 ingredient 的第一个样本栈的 count（{@code ingredient.getItems()[0].getCount()}）。
     * 若需求数量为 0（编程电路就是这种：{@code IntCircuitIngredient}），则<b>只要求库存里存在匹配项</b>。
     *
     * @param left 该 capability 下"还差多少"的需求清单（上游传进来的）
     * @return {@code null} = 本 handler 全部吃下（上游判"满足"）；否则 = 入参本身（上游继续问下一个 handler）
     */
    @Override
    public List<Ingredient> handleRecipeInner(IO io, GTRecipe recipe, List<Ingredient> left,
                                              String slotName, boolean simulate) {
        if (left == null || left.isEmpty()) {
            // 没有需求 ⇒ 视为满足（与 gtceu 各 handler 的口径一致）
            return null;
        }
        // 只读快照：整个判定过程中不再碰库存，更不会改它。
        final List<ItemStack> now = this.contents.get();
        for (int i = 0; i < left.size(); i++) {
            final Ingredient need = left.get(i);
            if (need == null) {
                continue;
            }
            final ItemStack[] samples = need.getItems();
            if (samples == null || samples.length == 0) {
                // 空 ingredient 不构成需求（gtceu 的 handleIngredient 同样跳过）
                continue;
            }
            final long required = samples[0] == null ? 0L : samples[0].getCount();
            final long available = shanhaiCountMatching(now, need);
            if (available <= 0L || (required > 0L && available < required)) {
                // 🔴 不满足：原样返回（同一个 list 实例、长度不变、元素不变）
                return left;
            }
        }
        // 🔴 全部满足：返回 null（gtlcore 只认 null）；一件都没抽取
        return null;
    }

    /** 只读统计：{@code now} 里能被 {@code need} 命中的总量。 */
    private static long shanhaiCountMatching(List<ItemStack> now, Ingredient need) {
        if (now == null || now.isEmpty()) {
            return 0L;
        }
        long available = 0L;
        for (int i = 0; i < now.size(); i++) {
            final ItemStack have = now.get(i);
            if (have == null || have.isEmpty()) {
                continue;
            }
            if (need.test(have)) {
                available += have.getCount();
            }
        }
        return available;
    }
}
