package com.dishanhai.gt_shanhai.common.machine;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 🔴 <b>把「这台机器上装了哪些能源仓」读成总功率 —— 纯计算那一半见 {@link ParallelPowerBudget}。</b>
 *
 * <h2>怎么枚举仓（与 GTCEu 自己【逐字同源】，不是另找一条路）</h2>
 * {@code WorkableElectricMultiblockMachine.getEnergyContainer()} 的实现（javap 偏移 314-385，原文形状）：
 * <pre>
 *   ArrayList list = new ArrayList();
 *   List handlers = capabilitiesProxy.get(IO.IN, EURecipeCapability.CAP);   // ← 输入侧
 *   if (handlers != null) { for (h : handlers) if (h instanceof IEnergyContainer ec) list.add(ec); }
 *   else { … 同一套，改成 IO.OUT … }                                        // ← 没有输入侧才退到输出侧
 *   return new EnergyContainerList(list);
 * </pre>
 * 本类<b>照抄这一条</b>（同一个 {@code getCapabilitiesProxy()} 表、同一个键、同一个
 * {@code instanceof IEnergyContainer} 过滤、同一套"IN 为空才退 OUT"的兜底）
 * ⇒ <b>我们看到的仓集合与机器真正用的 `EnergyContainerList` 是同一个集合</b>。
 * 这是本功能最重要的一条不变式：口径一旦分叉，"算出来的功率"与"机器真能拿到的功率"就会对不上。
 *
 * <h3>⚠️ 为什么不直接读 {@code EnergyContainerList}</h3>
 * 它的字段是 {@code private final List<? extends IEnergyContainer> energyContainerList;}
 * （javap 字段表，原文）——<b>没有公开的迭代入口</b>，只有聚合后的
 * {@code getInputVoltage() / getInputAmperage() / getEnergyCapacity() / getEnergyStored()}。
 * 而那四个量对<b>不同仓型</b>是<b>不对称</b>的（这正是本功能要解决的核心困难）：
 * <pre>
 *   Σ(V×A)       对无线仓 ✅（它就是稳态）、对电网仓 ❌ 少算 16 倍
 *   Σ容量        对电网仓 ✅（它就是稳态）、对无线仓 ❌ 多算 16 倍
 * </pre>
 * ⇒ 必须<b>逐个仓判型</b>再各自取口径，所以只能回到部件/能力表这一层。
 *
 * <h2>🔴 多仓 / 混型</h2>
 * 上面那条链是 {@code for (h : handlers) … list.add(ec)}——<b>无条数上限、无"必须同型号"检查</b>
 * ⇒ 机器上放 2 个、放 2 个不同类型，都会原样进这个 List。
 * 每个元素由 {@link #classify} <b>独立判型</b>，再各自取稳态求和（见
 * {@link ParallelPowerBudget#accumulate}）。
 */
public final class EnergyHatchPower {

    private EnergyHatchPower() {}

    /**
     * <b>本机所有能源仓的稳态总功率（EU/t）</b>。
     *
     * @return {@link ParallelPowerBudget#UNLIMITED}（终端 / 创造仓在机上）或
     *         {@code ≥ 0} 的 EU/t；<b>0 = 一个仓都没有 / 全部认不出来</b>
     *         （调用方按 {@link ParallelPowerBudget#parallelFromPowerMilli} 的约定处理成"不做电力限制"）
     */
    public static long totalSteadyPowerPerTick(IRecipeCapabilityHolder holder) {
        if (holder == null) {
            return 0L;
        }
        long total = 0L;
        final List<IEnergyContainer> containers = containersOf(holder);
        for (IEnergyContainer container : containers) {
            final ParallelPowerBudget.HatchKind kind = classify(container);
            if (kind.isUnlimited()) {
                // 特例：终端 / 创造 —— 用户原话「则直接把并行拉到最大，这个就不需要我们算了」
                return ParallelPowerBudget.UNLIMITED;
            }
            total = ParallelPowerBudget.accumulate(total, kind,
                    container.getInputVoltage(), container.getInputAmperage());
        }
        return total;
    }

    /** 本机能源仓个数（日志用；口径与 {@link #totalSteadyPowerPerTick} 完全同一条链）。 */
    public static int hatchCount(IRecipeCapabilityHolder holder) {
        return holder == null ? 0 : containersOf(holder).size();
    }

    /**
     * 本机能源仓的<b>逐仓型汇总</b>（形如 {@code 电网能源仓×2}、{@code 普通/无线能源仓×1 电网能源仓×1}）。
     *
     * <p>只用于日志与面板读数 —— 用户正是靠它核对"到底认成了哪种仓"。
     */
    public static String kindSummary(IRecipeCapabilityHolder holder) {
        if (holder == null) {
            return "";
        }
        final List<IEnergyContainer> containers = containersOf(holder);
        if (containers.isEmpty()) {
            return "（无能源仓）";
        }
        final StringBuilder sb = new StringBuilder();
        // 档数很少（5 档），用数组计数即可；顺序按判定顺序（从具体到一般）⇒ 读数稳定、可对比。
        final ParallelPowerBudget.HatchKind[] kinds = ParallelPowerBudget.HatchKind.values();
        final int[] counts = new int[kinds.length];
        for (IEnergyContainer container : containers) {
            counts[classify(container).ordinal()]++;
        }
        for (int i = 0; i < kinds.length; i++) {
            if (counts[i] <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(kinds[i].displayName()).append('×').append(counts[i]);
        }
        return sb.toString();
    }

    /**
     * <b>给一个能源仓容器判型</b>——沿超类链从具体走到一般。
     *
     * <pre>
     *   ① 类名认得出来          ⇒ 按 {@link ParallelPowerBudget#knownKindByName} 的那张表
     *   ② 认不出来但容量 = Long.MAX_VALUE ⇒ 当成"无限"（终端 / 创造仓的数值形态）
     *   ③ 都不匹配              ⇒ UNKNOWN（按最保守的 V×A 算）
     * </pre>
     *
     * <p>🔴 第 ② 条是<b>形态兜底</b>，不是主判据：它的存在是为了让"上游换了容器类名"退化成
     * <b>仍然正确</b>（而不是悄悄变成"按 V×A 算"，把终端/创造仓算成一个小数字）。
     * 主判据仍然是 ① —— 类名是从字节码里逐条核出来的（见 {@link ParallelPowerBudget} 的类 javadoc）。
     */
    public static ParallelPowerBudget.HatchKind classify(IEnergyContainer container) {
        if (container == null) {
            return ParallelPowerBudget.HatchKind.UNKNOWN;
        }
        for (Class<?> k = container.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            final ParallelPowerBudget.HatchKind known = ParallelPowerBudget.knownKindByName(k.getName());
            if (known != null) {
                return known;
            }
        }
        try {
            if (container.getEnergyCapacity() == Long.MAX_VALUE) {
                return ParallelPowerBudget.HatchKind.CREATIVE;
            }
        } catch (Throwable ignored) {
            // 认不出来 + 读不到容量 ⇒ 落到 UNKNOWN（保守档）。任何异常都不许打断配方装配。
        }
        return ParallelPowerBudget.HatchKind.UNKNOWN;
    }

    /**
     * 本机的能源仓容器列表 —— <b>与 {@code WorkableElectricMultiblockMachine#getEnergyContainer()}
     * 逐字同源</b>（同一张表、同一个键、同一个过滤、同一套兜底）。
     *
     * <p>每次调用都重新取一遍：能力表在成形/拆仓时会变，缓存下来就会读到过期集合
     * （表现 = "拆了一个仓、功率还是旧的"）。
     */
    public static List<IEnergyContainer> containersOf(IRecipeCapabilityHolder holder) {
        final List<IEnergyContainer> out = new ArrayList<>();
        if (holder == null) {
            return out;
        }
        try {
            List<IRecipeHandler<?>> handlers =
                    holder.getCapabilitiesProxy().get(IO.IN, EURecipeCapability.CAP);
            if (handlers == null) {
                handlers = holder.getCapabilitiesProxy().get(IO.OUT, EURecipeCapability.CAP);
            }
            if (handlers == null) {
                return out;
            }
            for (IRecipeHandler<?> handler : handlers) {
                if (handler instanceof IEnergyContainer energy) {
                    out.add(energy);
                }
            }
        } catch (Throwable ignored) {
            // 纯读：任何异常都退回"没有仓"（⇒ 不做电力限制），绝不打断配方装配。
        }
        return out;
    }
}
