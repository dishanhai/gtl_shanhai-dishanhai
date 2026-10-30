package com.shanhai.common.heat;

import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.machine.multiblock.CleanroomType;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.gregtechceu.gtceu.common.recipe.condition.CleanroomCondition;
import com.gregtechceu.gtceu.common.recipe.condition.DimensionCondition;
import com.gregtechceu.gtceu.common.recipe.condition.ResearchCondition;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.common.recipe.condition.GravityCondition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>「额外挂载槽 ×3」闸门的【唯一实现】</b> —— 模块（{@code PrimordialModuleMachine}）与
 * 归墟视界枢核（{@code GuixuVisionCoreMachine}）<b>共用这一个类</b>。
 *
 * <h2>为什么要单独一个类（2026-10-25）</h2>
 * 用户口径：「配方条件（就是<b>模块上面那 3 个槽</b>）<b>复写</b>…<b>接口 gui 就和模块的一样</b>」。
 * 归墟要的是和模块<b>同语义</b>的三格。若把模块侧那套「条件 → 需求」的转换在主机侧再写一遍，
 * 两份实现必然漂移（本工程最忌讳的形态：改了一边、另一边静默不动）⇒ 所以：
 * <pre>
 *   判定核（纯 java.*）    {@link ShanhaiHeatGate}          ← 不变，仍是唯一判定
 *   条件→需求的转换 ＋ 判定入口 ＋ 拒绝文案   ← 本类（新增，两边都只调这里）
 *   机器侧（槽位存储 / 日志 / 失败原因落点）  ← 各自的 RecipeLogic，各留一份（那是机器各自的壳）
 * </pre>
 *
 * <h2>🔴 语义（逐条与模块侧 2026-10-03 那套一致，不许在这里创新）</h2>
 * <pre>
 *   cleanroom / gravity / dimension ：【原版判定没过】才生成需求
 *       ⇒ 机器真的建在正确维度 / 结构里真塞了洁净维护仓时，原版自己能过，槽不参与（宽松叠加，绝不比原版更严）
 *   research                        ：【一律】生成需求（原版 {@code ResearchCondition.test} 恒 true）
 *   ebf_temp / SCTier               ：>0 各生成一条（热力，必须放满 {@link ShanhaiHeatGate#REQUIRED_COUNT} 个）
 *   反向条件（isReverse）           ：一律交回原版（槽是"提供"语义，给"不提供 X"当不了依据）
 * </pre>
 *
 * <h2>🔴 三格的判定口径（由 {@link ShanhaiHeatGate#evaluate} 承担，本类不重算）</h2>
 * 每格各自算、每格放满 {@link ShanhaiHeatGate#REQUIRED_COUNT} 个才生效、一个条件占一格 ——
 * 这三句的实现在 {@code ShanhaiHeatGate} 里，本类只负责"要什么"与"怎么把结果变成人话"。
 */
public final class ShanhaiExtraMountGate {

    /** 与模块侧同一个前缀（grep 一个词就能把两条线的读数一起捞出来）。 */
    public static final String PREFIX = "[SHANHAI-EXTRAMOUNT]";

    private ShanhaiExtraMountGate() {}

    /**
     * <b>一次判定</b>：把配方的额外条件 + 热力门槛折算成需求，再拿三格现读内容去判。
     *
     * @param recipe        候选配方
     * @param candidateType 候选配方<b>自己的</b>类型（判"这个格子算不算热力"用它，<b>不是</b>机器当前激活的类型：
     *                      跨配方种类并行之后两者可能不同，用后者会让 EBF/SCT 配方绕开温度闸门）
     * @param heatReachable 该类型在热力白名单里 <b>且</b> 这台机器的 3 格允许当热力源
     * @param machine       调用方的 {@code RecipeLogic}（原版条件的 {@code test} 需要它）
     * @param slots         三格现读内容（顺序 = 槽序号；{@code ShanhaiHeatSources#slotContentOf} 的产物）
     */
    @NotNull
    public static ShanhaiHeatGate.Outcome evaluate(@NotNull GTRecipe recipe,
                                                   @Nullable GTRecipeType candidateType,
                                                   boolean heatReachable,
                                                   @NotNull RecipeLogic machine,
                                                   @NotNull List<ShanhaiHeatGate.SlotContent> slots) {
        return evaluate(recipe, candidateType, heatReachable, machine, slots, null).outcome;
    }

    /**
     * <b>一次判定（两个来源）</b> —— 2026-10-25 用户点单的「复写」：
     * <b>模块自己的 3 格 ∪ 挂它的那台主机的 3 格</b>。
     *
     * <h2>用户原话（逐字）</h2>
     * <blockquote>「<b>开</b>，而且我说的是<b>复写</b>，归墟视界枢核的配方虽然没有，
     * <b>但是他模块的配方需要啊</b>」</blockquote>
     *
     * <h2>🔴 为什么"把两个来源拼成一个列表"就等价于"取两者中更满足的那一个"（不是近似，是等价）</h2>
     * 判定核 {@link ShanhaiHeatGate#evaluate} 对每条需求的满足判据是
     * 「<b>存在某一格</b> {@link ShanhaiHeatGate#slotSatisfies 满足} 它」（{@code bestSlotFor(...) >= 0}）
     * ⇒ 把模块的 3 格放前面、主机的 3 格放后面拼起来：
     * <ul>
     *   <li>模块自己就能满足 ⇒ 前 3 格里就命中 ⇒ <b>不会</b>被主机的低级物项压低（"模块更高级就用模块的"）；</li>
     *   <li>模块自己不够 ⇒ 继续扫后 3 格，主机的命中就生效（"模块 0 个、主机 64 个 ⇒ 满足"）；</li>
     *   <li>两边都不够 ⇒ 仍旧不满足（结论与只有模块时一致，只是"实际达到了多少"会取两边的最大值，更贴近现场）。</li>
     * </ul>
     * ⇒ <b>没有第二套判据</b>：`ShanhaiHeatGate.evaluate(...)` 仍然只被本类调用，
     * 本类只是把"槽数组"的来源从一个变成两个（任务书要求的"把槽数组来源抽成参数"）。
     *
     * @param hostSlots 主机供源（{@code null} = 没有，行为与旧版逐位相同）；
     *                  {@link ShanhaiHostMountSource#contentsOf} 的产物
     */
    @NotNull
    public static Result evaluate(@NotNull GTRecipe recipe,
                                  @Nullable GTRecipeType candidateType,
                                  boolean heatReachable,
                                  @NotNull RecipeLogic machine,
                                  @NotNull List<ShanhaiHeatGate.SlotContent> moduleSlots,
                                  @Nullable List<ShanhaiHeatGate.SlotContent> hostSlots) {
        final List<ShanhaiHeatGate.Requirement> needs =
                requirementsOf(recipe, candidateType, heatReachable, machine);
        if (hostSlots == null || hostSlots.isEmpty()) {
            // 没有主机供源 ⇒ 与旧实现逐位相同（连"哪条是主机补的"都不用算）
            return new Result(ShanhaiHeatGate.evaluate(needs, moduleSlots), List.of(), 0);
        }
        final List<ShanhaiHeatGate.SlotContent> union =
                new ArrayList<>(moduleSlots.size() + hostSlots.size());
        union.addAll(moduleSlots);
        union.addAll(hostSlots);
        final ShanhaiHeatGate.Outcome merged = ShanhaiHeatGate.evaluate(needs, union);
        // "是主机补上的" = 这条需求【模块自己的 3 格单独满足不了】、但并集里满足了。
        // 复用同一个逐格判据 slotSatisfies（不重写任何"够不够"的算术）。
        final List<ShanhaiHeatGate.Requirement> hostProvided = new ArrayList<>();
        for (ShanhaiHeatGate.Requirement r : merged.satisfied) {
            boolean moduleAlone = false;
            for (ShanhaiHeatGate.SlotContent s : moduleSlots) {
                if (ShanhaiHeatGate.slotSatisfies(r, s)) {
                    moduleAlone = true;
                    break;
                }
            }
            if (!moduleAlone) {
                hostProvided.add(r);
            }
        }
        return new Result(merged, hostProvided, hostSlots.size());
    }

    /**
     * 一次判定的<b>完整结果</b>：判定本身 ＋ "哪几条是主机补上的"（只给日志/自证用，不参与任何判断）。
     *
     * @param outcome      判定结果（模块 ∪ 主机）
     * @param hostProvided 靠主机才满足的那几条需求（模块自己满足不了的）
     * @param hostSlotCount 主机提供了几格（0 = 没有供源）
     */
    public record Result(@NotNull ShanhaiHeatGate.Outcome outcome,
                         @NotNull List<ShanhaiHeatGate.Requirement> hostProvided,
                         int hostSlotCount) {

        /** 有没有"靠主机才满足"的需求（模块侧打日志的判据）。 */
        public boolean usedHostSource() {
            return outcome.allowed && !hostProvided.isEmpty();
        }
    }

    /**
     * 把一条配方折算成"槽需求"清单（<b>不含</b>机器侧状态；纯函数，便于离线自证）。
     *
     * <p>两条来源（缺一条就会误伤别的配方）：
     * <ol>
     *   <li><b>四类配方条件</b>：{@code cleanroom} / {@code gravity} / {@code dimension} / {@code research}
     *       —— 与配方类型无关，<b>任何</b>配方都算；</li>
     *   <li><b>热力</b>：{@code recipe.data} 的 {@link ShanhaiHeatGate#KEY_EBF_TEMP} /
     *       {@link ShanhaiHeatGate#KEY_SC_TIER} —— 只在 {@link ShanhaiHeatGate#isGated} 的类型
     *       <b>且</b>机器允许当热力源时才算，其余一律无视这个格子。</li>
     * </ol>
     */
    @NotNull
    public static List<ShanhaiHeatGate.Requirement> requirementsOf(@NotNull GTRecipe recipe,
                                                                   @Nullable GTRecipeType candidateType,
                                                                   boolean heatReachable,
                                                                   @NotNull RecipeLogic machine) {
        final List<ShanhaiHeatGate.Requirement> needs = new ArrayList<>();

        // ① 四类配方条件
        for (RecipeCondition condition : recipe.conditions) {
            // 反向条件（isReverse）一律交回原版：槽是"提供"语义，给"不提供 X"这种否定条件当不了依据。
            if (condition.isReverse()) {
                continue;
            }
            final ShanhaiHeatGate.Requirement need = requirementOf(condition);
            if (need == null) {
                continue;       // 不是这四类（module_level / rock_breaker / …）⇒ 原版管
            }
            if (need.kind == ShanhaiHeatGate.Kind.RESEARCH) {
                needs.add(need);                    // 🔴 研究一律要槽（原版恒 true）
                continue;
            }
            if (condition.test(recipe, machine) != condition.isReverse()) {
                continue;                           // 原版已经满足 ⇒ 这条与槽无关
            }
            needs.add(need);
        }

        // ② 热力（只在那 7 个类型 + 允许当热力源的机器上）
        if (heatReachable) {
            final int needTemp = readRecipeInt(recipe.data, ShanhaiHeatGate.KEY_EBF_TEMP);
            final int needTier = readRecipeInt(recipe.data, ShanhaiHeatGate.KEY_SC_TIER);
            if (needTemp > 0) {
                needs.add(ShanhaiHeatGate.Requirement.heatTemp(needTemp));
            }
            if (needTier > 0) {
                needs.add(ShanhaiHeatGate.Requirement.scTier(needTier));
            }
        }
        return needs;
    }

    /**
     * <b>配方条件判定</b> —— 原版 {@code recipe.checkConditions(machine)} 的等价替身，
     * 唯一差别是：<b>由槽满足的那几条条件算过</b>。
     *
     * <h2>两段分流（缺一段就会出事）</h2>
     * <ol>
     *   <li><b>没有任何需求被槽满足</b> ⇒ 原样委托回 {@code recipe.checkConditions(machine)}。
     *       这一条保证「无条件的配方永远能做」与「原版能过的一律照过」，一行行为都不改。</li>
     *   <li><b>有需求被槽满足</b> ⇒ 才走下面那段。它逐字复刻 {@code GTRecipe#checkConditions} 的语义，
     *       只是把"槽已满足"的那几条也当成满足 —— 因为原版判定对清洁度/重力在本机上恒为 false，
     *       不跳过它们就等于槽白放。</li>
     * </ol>
     *
     * <h2>原版语义（{@code javap -c com.gregtechceu.gtceu.api.recipe.GTRecipe} 实读）</h2>
     * <pre>
     *   非 OR 条件：c.test(recipe, logic) == c.isReverse() ⇒ 失败
     *   OR 组    ：按 c.getType() 分组，allMatch(c -&gt; c.test(...) == c.isReverse()) ⇒ 整组失败
     *              ⇒ 组内任意一条满足即整组通过
     * </pre>
     * 🔴 {@code DimensionCondition.isOr()} 实测恒返回 true ⇒ 维度条件天生走 OR 组这条路，
     * 这里必须照抄分组语义，不能按单条判。
     */
    public static boolean conditionsPass(@NotNull GTRecipe recipe,
                                         @NotNull RecipeLogic machine,
                                         @Nullable ShanhaiHeatGate.Outcome outcome) {
        if (outcome == null || outcome.satisfied.isEmpty()) {
            return recipe.checkConditions(machine).isSuccess();
        }
        // 值 = "该 OR 组到目前为止是不是【全组都失败】"（首项取反，后续按与合并）。
        final Map<RecipeConditionType<?>, Boolean> orGroupAllFail = new LinkedHashMap<>();
        for (RecipeCondition condition : recipe.conditions) {
            final boolean ok = isConditionSatisfied(condition, recipe, machine, outcome);
            if (condition.isOr()) {
                orGroupAllFail.merge(condition.getType(), !ok, (a, b) -> a && b);
            } else if (!ok) {
                return false;
            }
        }
        for (Boolean allFail : orGroupAllFail.values()) {
            if (Boolean.TRUE.equals(allFail)) {
                return false;
            }
        }
        return true;
    }

    /** 这条条件过没过 —— 原版满足，或<b>槽提供了对应的物项</b>。 */
    public static boolean isConditionSatisfied(@NotNull RecipeCondition condition, @NotNull GTRecipe recipe,
                                               @NotNull RecipeLogic machine,
                                               @NotNull ShanhaiHeatGate.Outcome outcome) {
        if (condition.test(recipe, machine) != condition.isReverse()) {
            return true;
        }
        final ShanhaiHeatGate.Requirement need = requirementOf(condition);
        return need != null && outcome.satisfied.contains(need);
    }

    /**
     * 把一条原版配方条件翻译成"槽需求"；<b>不是本闸门管的（或认不出来）返回 {@code null}</b>。
     *
     * <p>认不出来的情形必须返回 null（⇒ 交回原版判），而不是"猜一个档位"：
     * 例如将来某个 mod 注册了第 4 种 {@code CleanroomType}，{@link ShanhaiHeatGate#cleanroomTierOfName}
     * 会返回 0，这里就交回原版 —— 后果是"槽帮不上忙"，而不是"槽用错的档位放行"。
     */
    @Nullable
    public static ShanhaiHeatGate.Requirement requirementOf(@NotNull RecipeCondition condition) {
        if (condition instanceof CleanroomCondition cleanroom) {
            final CleanroomType type = cleanroom.getCleanroom();
            final int tier = ShanhaiHeatGate.cleanroomTierOfName(type == null ? null : type.getName());
            return tier == ShanhaiHeatGate.CLEANROOM_NONE ? null : ShanhaiHeatGate.Requirement.cleanroom(tier);
        }
        if (condition instanceof DimensionCondition dimension) {
            final ResourceLocation dim = dimension.getDimension();
            return dim == null ? null : ShanhaiHeatGate.Requirement.dimension(dim.toString());
        }
        if (condition instanceof ResearchCondition) {
            return ShanhaiHeatGate.Requirement.research();
        }
        if (condition instanceof GravityCondition) {
            // ⚠️ gtlcore 的 GravityCondition 里 zero 是 private 且无 getter ⇒ 这里不区分无重力/强重力，
            //    一律"需要重力控制"。依据：带重力的维护仓是可配置的，两种都能给。
            return ShanhaiHeatGate.Requirement.gravity();
        }
        return null;
    }

    /** 读配方 {@code data} 里的一个整数字段；不存在算 0（= 这条配方没有该门槛）。 */
    public static int readRecipeInt(@NotNull CompoundTag data, @NotNull String key) {
        return data.contains(key) ? data.getInt(key) : 0;
    }

    // ═══════════════════════════ 拒绝文案（两边共用，不各写一份） ═══════════════════════════

    /**
     * 把拒绝结果渲成<b>具体</b>文案（走 lang 键，不硬编码中文）。
     *
     * <h2>句式口径（2026-09-30 用户选择题答案逐字「A. 改」定下的）</h2>
     * 一律「<u>先说这个配方需要什么，再说槽里实际是什么</u>」—— 旧句式先否定你放的东西、再说其实要什么，
     * 读起来像"说反了"。
     * <pre>
     *   SLOT_EMPTY     ：这个配方需要【%s】，但 3 个额外挂载槽全是空的
     *   WRONG_ITEM     ：这个配方需要【%s】，但额外挂载槽里放的是【%s】
     *   NOT_FULL       ：这个配方需要放满 %s 个【%s】才生效，槽里只有 %s 个
     *   CLEANROOM_TIER ：这个配方需要【%s】，但槽里的维护仓只提供【%s】
     *   HEAT_TEMP      ：这个配方需要 %sK 炉温，但槽里的线圈只有 %sK
     *   SC_TIER        ：这个配方需要 %s 级恒星热力容器，但槽里的是 %s 级
     * </pre>
     * <p>⚠️ 只给<b>原因</b>，不带「配方失败原因：」前缀 —— 前缀由 Jade 的
     * {@code gtceu.recipe.fail.reason}（"配方失败原因：%s"）加，加了会变成两层。
     *
     * @param o          判定结果
     * @param slotStacks 三格现读物品（<b>只为</b>"槽里实际放的是什么"那一句；可以为 {@code null}）
     * @return 原因；放行 / 认不出时 {@code null}
     */
    @Nullable
    public static Component denyReason(@NotNull ShanhaiHeatGate.Outcome o, @Nullable List<ItemStack> slotStacks) {
        final ShanhaiHeatGate.Requirement need = o.blocked;
        if (need == null) {
            return null;
        }
        final Component what = requirementLabel(need);
        return switch (o.deny) {
            case SLOT_EMPTY -> Component.translatable("shanhai.recipe.fail.extra_slot_empty", what);
            case WRONG_ITEM -> Component.translatable("shanhai.recipe.fail.extra_slot_wrong", what,
                    actualNames(slotStacks));
            case NOT_FULL -> Component.translatable("shanhai.recipe.fail.extra_slot_not_full",
                    ShanhaiHeatGate.REQUIRED_COUNT, what, countOfKind(need.kind, o));
            case CLEANROOM_TIER -> Component.translatable("shanhai.recipe.fail.extra_cleanroom_tier",
                    what, cleanroomLabel(o.have));
            case HEAT_TEMP -> Component.translatable("shanhai.recipe.fail.heat_coil_temp", need.number, o.have);
            case SC_TIER -> Component.translatable("shanhai.recipe.fail.heat_sc_tier", need.number, o.have);
            default -> null;
        };
    }

    /**
     * 一条需求的中文标签（<b>走 lang 键</b>）。
     * <p>超净间那一档用 GTCEu/gtlcore <b>自己的</b> {@code CleanroomType#getTranslationKey()}，
     * 不另写一份中文 —— 上游改名时这里自动跟着走。
     */
    @NotNull
    public static Component requirementLabel(@NotNull ShanhaiHeatGate.Requirement r) {
        return switch (r.kind) {
            case CLEANROOM -> cleanroomLabel(r.number);
            case GRAVITY -> Component.translatable("shanhai.recipe.fail.extra_need_gravity");
            case DIMENSION -> Component.translatable("shanhai.recipe.fail.extra_need_dimension", r.text);
            case RESEARCH -> Component.translatable("shanhai.recipe.fail.extra_need_research");
            case HEAT_TEMP -> Component.translatable("shanhai.recipe.fail.extra_need_coil_temp", r.number);
            case SC_TIER -> Component.translatable("shanhai.recipe.fail.extra_need_sc_tier", r.number);
        };
    }

    /** 超净间档位 → 上游自己的可翻译名；查不到（理论上不会）退化成注册名。 */
    @NotNull
    public static Component cleanroomLabel(int tier) {
        final CleanroomType type = CleanroomType.getByName(ShanhaiHeatGate.cleanroomName(tier));
        return type == null
                ? Component.literal(ShanhaiHeatGate.cleanroomName(tier))
                : Component.translatable(type.getTranslationKey());
    }

    /**
     * <b>额外挂载槽里实际放的是什么</b> —— 用【物品显示名】，🔴 <b>不是 id</b>（玩家看不懂 id）。
     *
     * <h2>🔴 为什么传 {@link Component} 而不是 {@code String}</h2>
     * 这条原因要经 gtlcore 的 {@code RecipeLogicProviderMixin} 送进 Jade 的<b>服务端 NBT</b>
     * （{@code Component$Serializer.toJson(component)} → {@code CompoundTag.putString("reason", …)}），
     * 而<b>专用服务端不加载客户端 lang</b> —— 如果在这里先 {@code getString()} 成 String，
     * 到了客户端就只剩一个键名/英文回退，中文永远出不来。传 Component 则整棵子树被 JSON 序列化过去，
     * <b>客户端才翻译</b> ⇒ 中文正确显示。
     *
     * <h2>退化</h2>
     * 三格全空 / 拿不到显示名 / 任何异常 ⇒ 退化成 lang 键
     * {@code shanhai.recipe.fail.heat_slot_actual.unknown}（「其它物品」），不报错、不留空。
     * <p>吞掉 {@code Throwable} 是有意的：这一句是在<b>报错路径上</b>跑的，
     * 报错路径自己抛异常会把"配方为什么没跑"这条唯一线索也弄没。
     */
    @NotNull
    public static Component actualNames(@Nullable List<ItemStack> slotStacks) {
        final Component unknown = Component.translatable("shanhai.recipe.fail.heat_slot_actual.unknown");
        try {
            if (slotStacks == null) {
                return unknown;
            }
            final MutableComponent out = Component.empty();
            boolean first = true;
            for (ItemStack stack : slotStacks) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                final Component name = stack.getHoverName();
                if (name == null) {
                    continue;
                }
                if (!first) {
                    out.append(Component.translatable("shanhai.recipe.fail.extra_slot_sep"));
                }
                out.append(name);
                first = false;
            }
            return first ? unknown : out;
        } catch (Throwable t) {
            return unknown;
        }
    }

    /** 该需求对应的物项在某一格里最多放了多少（给"没放满"那条文案报读数）。 */
    public static int countOfKind(@NotNull ShanhaiHeatGate.Kind kind, @NotNull ShanhaiHeatGate.Outcome o) {
        int best = 0;
        for (ShanhaiHeatGate.SlotContent s : o.slots) {
            final boolean relevant = switch (kind) {
                case HEAT_TEMP -> s.coilTemperature > 0;
                case SC_TIER -> s.containmentTier > 0;
                default -> true;
            };
            if (relevant) {
                best = Math.max(best, s.count);
            }
        }
        return best;
    }
}
