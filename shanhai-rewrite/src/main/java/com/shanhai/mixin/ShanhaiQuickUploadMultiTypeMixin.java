package com.shanhai.mixin;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.machine.PrimordialOmegaEngineMachine;
import com.shanhai.machine.module.PrimordialModuleMachine;

import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadService;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 修 gtlcore「快捷上传（pattern quick upload）」里那段<b>从来没生效过的多类型展开</b>，
 * 并给展开加一道<b>「只有真在跨配方种类并行才展开」的门控</b>。
 *
 * <h2>1. 这个 mixin 修的两件事（第一件是上一版做的，第二件是本版补的）</h2>
 * <ol>
 *   <li><b>上游缺陷</b>：{@code getSupportedRecipeTypes} 对任何控制器都只上报
 *       {@code getRecipeType()} 那<b>一个</b>类型（{@code WorkableMultiblockMachine#getRecipeType()}
 *       = {@code recipeTypes[activeRecipeType]}，javap：{@code getfield recipeTypes} →
 *       {@code getfield activeRecipeType} → {@code aaload}）⇒ 声明了多个类型、又真的会跨类型跑的机器，
 *       在快捷上传里只能收到"当前那一个"类型的样板。</li>
 *   <li>🔴 <b>本版修的 bug（用户 2026-10-25 报，原话逐字）</b>：
 *       <blockquote>「<b>一键上传出bug了，它无法识别我们的机器是否开启了跨配方种类并行，
 *       它把样板上传到我们没开启跨配方种类并行的机器上去了</b>（选择的不是那个配方）」</blockquote>
 *       ⇒ 上一版的门控只有 {@code definition.getRecipeTypes().length > 1} 这一条，
 *       <b>只按"声明了几个类型"就展开，没看这台机器是不是真的在跨配方种类并行</b> ⇒
 *       那些"多类型但只跑一个类型"的机器也会声称支持全部类型 ⇒ 上传挑错机器、样板被塞进不跑它的缓冲区。</li>
 * </ol>
 *
 * <h2>2. 门控：三条判据，任一成立才展开；否则<b>一个字节都不动</b></h2>
 * <pre>
 *   ① 我们自己的<b>模块</b>（{@link PrimordialModuleMachine}）
 *        ⇒ {@code crossTypeModeDisplayName() != null}
 *   ② 我们自己的<b>主机</b>（{@link PrimordialOmegaEngineMachine}）
 *        ⇒ {@code crossTypeModeDisplayName() != null}（老主机基类恒 {@code null} ⇒ 永不展开）
 *   ③ <b>上游的跨类型机器</b>（例：伪神之锻炉）
 *        ⇒ {@code getRecipeType()} 为 {@code null}，或<b>不在</b>声明的那几个类型里（= 那个 dummy 类型）
 *   ⇒ 其余一切（{@code getRecipeType()} 是声明列表里的某一个，且不是上面三种）
 *     ⇒ <b>不展开</b>：保持上游结果 = 只有当前那 1 个类型。
 * </pre>
 *
 * <h2>3. 为什么用 {@code crossTypeModeDisplayName()} 而不是自己再拼一遍合取式</h2>
 * 那个方法就是本工程<b>唯一</b>的「跨配方种类并行此刻是否真的在生效」出口，两侧各一份实现：
 * <pre>
 *   模块 PrimordialModuleMachine#crossTypeModeDisplayName（服务端分支）
 *        = isFormed() ∧ !getDefinition().isGenerator() ∧ host != null
 *          ∧ host.grantsCrossTypeParallel() ∧ !isRecipeLockedForDisplay(getRecipeLogic())
 *        —— 与配方逻辑真正用的那个入口 {@code PrimordialModuleRecipeLogic}
 *           （{@code if (shanhai$isCrossTypeEnabled() && !isLock())}）<b>同一个合取式</b>
 *           （{@code shanhai$isCrossTypeEnabled()} = !isGenerator ∧ host != null ∧ host.grants…）；
 *           主机 {@code getHost()} 是<b>纯服务端字段</b>，而快捷上传就发生在服务端
 *           （唯一调用点是 {@code collectTargetsFromBuffer}，入口 {@code findTargets(ServerPlayer, …)}）
 *           ⇒ 这里拿到的是<b>现算的真值</b>，不是客户端那份镜像。
 *   主机 归墟 GuixuVisionCoreMachine#crossTypeModeDisplayName
 *        = isFormed() ∧ grantsCrossTypeParallel() ∧ !isRecipeLockedForDisplay(…)
 *        —— 老主机（{@code PrimordialOmegaEngineMachine} 基类）返回 {@code null}。
 * </pre>
 * 用同一个方法的好处：<b>不产生第三份判据副本</b>（那个合取式已经有两份实现，模块类里明写
 * 「改动任一处必须两处一起改」）⇒ 将来谁改判据，这条门控<b>自动跟着走</b>，不会漂移。
 * <p>同族先例：{@code ShanhaiMachineModeNameMixin#shanhai$modeNameOf} 也是在 mixin 里对
 * 「主机 / 模块」两种机器分别强转、调同一个方法。
 *
 * <h2>4. 三类机器的实测口径（谁声明几个、谁真的跑几个）</h2>
 * <table border="1">
 *   <caption>声明的类型 vs 实际会跑的类型</caption>
 *   <tr><th>机器</th><th>声明</th><th>实际会跑</th><th>本门控</th></tr>
 *   <tr><td>单类型普通机</td><td>1</td><td><b>1</b>（{@code recipeTypes[activeRecipeType]}）</td>
 *       <td>{@code declared.length <= 1} ⇒ 直接跳过，连日志都不打</td></tr>
 *   <tr><td>老主机 {@code shanhai:primordial_omega_engine}</td><td><b>5</b>
 *       （{@code ShanhaiMachines#HOST_RECIPE_TYPES}：FURNACE / ASSEMBLER / ALLOY_SMELTER /
 *        FORGE_HAMMER / MACERATOR）</td>
 *       <td><b>1</b>：{@code PrimordialEngineRecipeLogic extends MutableRecipesLogic}，
 *       而 {@code MutableRecipesLogic#lookupRecipeSet} 只读 {@code getRecipeType().getLookup()}
 *       （javap：偏移 20 / 119 两次 {@code IRecipeLogicMachine.getRecipeType}）⇒ <b>没有跨类型</b></td>
 *       <td>基类 {@code crossTypeModeDisplayName()} = {@code null} ⇒ <b>不展开</b>
 *       （这就是用户报的那台"没开启跨类型"的机器）</td></tr>
 *   <tr><td>我们挂在老主机上的模块</td><td>2…41（{@code ModuleRegistry}，最多 41 个）</td>
 *       <td><b>1</b>（未授予 ⇒ {@code PrimordialModuleRecipeLogic} 走老路径）</td>
 *       <td>{@code getHost().grantsCrossTypeParallel()} = false ⇒ {@code null} ⇒ <b>不展开</b></td></tr>
 *   <tr><td>归墟 + 挂在它上面的模块</td><td>2…41</td>
 *       <td><b>全部类型轮转</b>（{@code getRecipeTypes()} 轮转取候选，凑够线程数即停 ⇒
 *       {@code shanhai$lookupCrossTypeSet}）</td>
 *       <td>{@code crossTypeModeDisplayName() != null} ⇒ <b>展开</b>（仅当模块已连上归墟、
 *       未锁配方、不是发电那台）</td></tr>
 *   <tr><td>归墟主机本体</td><td>3（视界物质解压 / 视界能量抽取 / 原初范式简化）</td>
 *       <td><b>1</b>：主机自己的配方逻辑同样是 {@code PrimordialEngineRecipeLogic}（无跨类型），
 *       且模式选择页已移除 ⇒ 它自己并不跨类型跑</td>
 *       <td>⚠️ 本门控按"它自己上报的模式"判（{@code grantsCrossTypeParallel()} 恒 true）⇒ 会展开。
 *       <b>但这条分支今天不可达</b>：归墟结构的环带只放能源仓 / 动力仓
 *       （{@code GuixuVisionCoreStructure#ringBandPredicate} = {@code Predicates.blocks(RING_BAND)}
 *       {@code .or(abilities(INPUT_ENERGY))} {@code .or(abilities(OUTPUT_ENERGY))}），
 *       <b>收不了样板仓</b> ⇒ 它永远进不了 {@code controllers} 列表。
 *       ⚠️ 若将来给主机加物品仓，这一条必须改成 {@code false}，否则就是"主机声称 3 个类型、
 *       自己只跑 1 个"的新形态同一个 bug（见报告"待确认"一节的取舍）。
 *       <br>🔴 <b>【2026-10-25 收尾 · 上面那句"将来"到了，已改判】</b>：T2 已把物品/流体仓
 *       与 4 个样板总成放进环带（"收不了样板仓"不再是事实）⇒ 归墟主机本体<b>现在真的会进
 *       {@code controllers}</b>。⇒ 已在 {@code shanhai$crossTypeReason} 里<b>判死</b>
 *       （{@code instanceof GuixuVisionCoreMachine ⇒ null}，排在 host 分支之前），
 *       并在这张表下面那一行日志里带 {@code skipped_reason=guixu_host}。
 *       <b>改前</b>：这条分支展开 3 个类型（reason={@code our_host}）；<b>改后</b>：不展开，
 *       只记 {@code [SHANHAI-QUICKUP] multi_type_expand_skipped … skipped_reason=guixu_host …}。
 *       上面那段「不可达 / 将来必须改」的原文按本工程惯例<b>保留不改</b>。</td></tr>
 *   <tr><td>伪神之锻炉 {@code gtladditions:forge_of_the_antichrist}</td><td>3</td>
 *       <td><b>3</b>（{@code GTLAddMultipleTypeRecipesLogic#lookupRecipeIterator} 把候选来源换成
 *       {@code machine.getRecipeTypes()} 的 {@code flatMap}）</td>
 *       <td>{@code getRecipeType()} = {@code dummyRecipeType}（{@code GTLAddRecipesTypes.FORGE_OF_THE_ANTICHRIST}）
 *       <b>不在</b>声明的那 3 个里 ⇒ <b>展开</b></td></tr>
 * </table>
 *
 * <h2>5. 做法：只往返回集合里<b>补加"声明过的"类型</b>（补充式，不做任何删减/替换）</h2>
 * 在 {@code getSupportedRecipeTypes} 的 {@code RETURN} 处注入：
 * <ul>
 *   <li>先攒到局部 {@code extra}，<b>中途任何异常都不会留下半个结果</b>；</li>
 *   <li>再一次性 {@code addAll} 进上游那个 {@code LinkedHashSet}（幂等、只增不减）；</li>
 *   <li>每台控制器<b>单独</b> try/catch：某一台判不了只影响它自己，其它机器照旧。</li>
 * </ul>
 * <p><b>为什么不在 {@code getRecipeType()} 上动手</b>：那是配方逻辑现行读取的入口
 * （{@code MutableRecipesLogic#findAndHandleMultipleRecipe} 每 tick 都读它），动它会连带改变
 * "跑配方"的行为。本条只改"快捷上传能看见哪些类型"，与配方逻辑完全正交。
 *
 * <h2>6. require = 1 + 整体 try/catch</h2>
 * {@code require = 1}：注入点必须<b>正好命中 1 处</b>；上游把 {@code getSupportedRecipeTypes}
 * 改名/改签名/删掉时，Mixin 会在目标类<b>第一次被加载时</b>抛 {@code InjectionError}（响亮的硬失败），
 * 而不是"静默地永远不生效"。外层 {@code catch (Throwable)}：真出了意外就<b>保持原结果</b>并打一行 ERROR。
 *
 * <h2>7. 自证日志（无头专服里就看这两行；都按控制器 {@code Class} 去重，不刷屏）</h2>
 * <pre>
 *   展开：[SHANHAI-QUICKUP] multi_type_expanded controller=… reason=… declared=N result=M
 *   拒开：[SHANHAI-QUICKUP] multi_type_expand_skipped controller=… declared=N active=…
 * </pre>
 * ⇒ <b>现在 expanded 那一行只会在"归墟模块 / 归墟主机 / 伪神"身上出现</b>；
 * 老主机与老主机上的模块应当只出现 <b>skipped</b> 那一行（{@code declared=5 active=…}）。
 */
@Mixin(value = PatternQuickUploadService.class, remap = false)
public abstract class ShanhaiQuickUploadMultiTypeMixin {

    /**
     * 已经打过日志的「控制器类 + 形态」（{@code "E|" + 类名} = 展开、{@code "S|" + 类名} = 拒开）。
     *
     * <p>用 {@link ConcurrentHashMap#newKeySet()} 而不是 {@code HashSet}：快捷上传可能被多个玩家
     * 同时触发（服务端线程不保证只有一个），这个集合只在打日志前读一次、写一次，不需要更强的一致性。
     */
    @Unique
    private static final Set<String> SHANHAI$LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * @param controllers 上游原参数（控制器列表）—— {@code getSupportedRecipeTypes} 是 static，
     *                    所以处理器也必须是 static，并且第一个参数就是方法的第 1 个入参。
     * @param cir         返回值回调用具；{@code getReturnValue()} 就是上游那个 {@code LinkedHashSet}
     *                    （{@code :0-7} 新建、{@code :80-81} 原样返回，所以拿到的永远是同一个可变集合）。
     */
    @Inject(method = "getSupportedRecipeTypes",
            at = @At("RETURN"),
            require = 1,
            cancellable = true,
            remap = false)
    private static void shanhai$expandDeclaredRecipeTypes(Iterable<IMultiController> controllers,
                                                          CallbackInfoReturnable<Set<GTRecipeType>> cir) {
        try {
            if (controllers == null) {
                return;
            }
            final Set<GTRecipeType> upstream = cir.getReturnValue();

            // ① 先把要补的类型全部攒起来（此时一个都不往上游集合里写）
            final Set<GTRecipeType> extra = new LinkedHashSet<>();
            final Map<Class<?>, String> expanded = new LinkedHashMap<>();
            final Map<Class<?>, String> skipped = new LinkedHashMap<>();
            for (IMultiController controller : controllers) {
                if (controller == null) {
                    continue;
                }
                // 与上游 :38-47 同一条判据：没成型的控制器上游压根不看
                if (!controller.isFormed()) {
                    continue;
                }
                try {
                    final MultiblockControllerMachine machine = controller.self();
                    if (machine == null) {
                        continue;
                    }
                    final MultiblockMachineDefinition definition = machine.getDefinition();
                    if (definition == null) {
                        continue;
                    }
                    final GTRecipeType[] declared = definition.getRecipeTypes();
                    // 🔴 第 0 道闸：单类型机器【一个字节都不动】，连日志都不打
                    if (declared == null || declared.length <= 1) {
                        continue;
                    }

                    // 🔴 真正的门控：只有"真在跨配方种类并行"才给 reason
                    final String reason = shanhai$crossTypeReason(machine, declared);
                    if (reason == null) {
                        // 拒开也要留证据：无头专服里"老主机没被展开"的唯一可判据。
                        // 🆕 2026-10-25 收尾：归墟主机本体额外带一个 grep 用的标记
                        //    （`skipped_reason=guixu_host`）—— 它是"归墟进了 controllers 之后仍没被展开"
                        //    的唯一证据行；只是日志文本，**不参与任何判断**。
                        skipped.putIfAbsent(machine.getClass(),
                                (machine instanceof com.shanhai.machine.host.GuixuVisionCoreMachine
                                        ? "skipped_reason=guixu_host " : "")
                                        + "declared=" + declared.length
                                        + " active=" + shanhai$activeTypeId(machine));
                        continue;
                    }
                    boolean contributed = false;
                    for (GTRecipeType type : declared) {
                        if (type == null) {
                            continue;
                        }
                        if (extra.add(type)) {
                            contributed = true;
                        }
                    }
                    if (contributed) {
                        expanded.putIfAbsent(machine.getClass(), reason + " declared=" + declared.length);
                    }
                } catch (Throwable perMachine) {
                    // 单台机器判不了 ⇒ 只放弃这一台，别的控制器照旧（绝不影响上游原结果）
                }
            }

            // ② 一次性并入上游结果集（幂等，只增不减）
            Set<GTRecipeType> applied = upstream;
            if (!extra.isEmpty()) {
                if (applied == null) {
                    // 上游 :0-7 保证非 null，这里只是防御：真为 null 就直接给这份
                    applied = extra;
                    cir.setReturnValue(applied);
                } else {
                    try {
                        applied.addAll(extra);
                    } catch (Throwable immutable) {
                        // 万一上游哪天换成不可变集合：复制一份等价的新集合，语义完全相同
                        final Set<GTRecipeType> copy = new LinkedHashSet<>(applied);
                        copy.addAll(extra);
                        applied = copy;
                        cir.setReturnValue(applied);
                    }
                }
            }

            // ③ 自证日志（每种控制器类 × 每种形态 一次）
            final int resultSize = (applied == null) ? 0 : applied.size();
            for (Map.Entry<Class<?>, String> hit : expanded.entrySet()) {
                if (!SHANHAI$LOGGED.add("E|" + hit.getKey().getName())) {
                    continue;
                }
                ShanhaiMod.LOGGER.info(
                        "[SHANHAI-QUICKUP] multi_type_expanded controller={} reason={} result={}",
                        hit.getKey().getName(), hit.getValue(), resultSize);
            }
            for (Map.Entry<Class<?>, String> hit : skipped.entrySet()) {
                if (!SHANHAI$LOGGED.add("S|" + hit.getKey().getName())) {
                    continue;
                }
                ShanhaiMod.LOGGER.info(
                        "[SHANHAI-QUICKUP] multi_type_expand_skipped controller={} {} "
                                + "⇒ 不是跨配方种类并行，保持上游结果（只有当前那 1 个类型）",
                        hit.getKey().getName(), hit.getValue());
            }
        } catch (Throwable t) {
            // 保持原结果：走到这里说明 ② 还没把 extra 并进上游集合（或已并完才炸），
            // 这两种都不需要再动返回值 ⇒ 上游那个集合原样返回，绝不会出现半个结果。
            ShanhaiMod.LOGGER.error("[SHANHAI-QUICKUP] multi_type_expand_failed err={} -> 保持上游原结果",
                    t.toString());
        }
    }

    /**
     * 🔴 <b>门控本体</b>：这台机器此刻是否<b>真的</b>在跨配方种类并行。
     *
     * @return {@code null} = <b>不是</b>（调用方一个字节都不动，保持上游"只有当前那 1 个类型"）；
     *         非 {@code null} = 展开的<b>理由</b>（只用于日志，不参与任何逻辑）。
     */
    @Unique
    private static String shanhai$crossTypeReason(MultiblockControllerMachine machine,
                                                  GTRecipeType[] declared) {
        // ①② 我们自己的两种机器：直接问机器自己「跨配方种类并行此刻生效吗」
        //     （= 它上报给 Jade / GUI 的同一个值；客户端那份读 @DescSynced 镜像，服务端那份现算）
        //     ⚠️ 模块必须排在主机前面：模块不是 PrimordialOmegaEngineMachine 的子类，
        //        两条 instanceof 互斥，但顺序写清楚更防将来有人给模块换基类。
        if (machine instanceof PrimordialModuleMachine module) {
            return (module.crossTypeModeDisplayName() != null) ? "our_module" : null;
        }
        // 🔴🔴 2026-10-25（收尾）：**归墟主机本体判死** —— 就是本类 §4 那张表格里预告过的那一刀。
        //
        //  <p>表格原文（写于 T2 之前）：「⚠️ 本门控按"它自己上报的模式"判（grantsCrossTypeParallel() 恒 true）
        //  ⇒ 会展开。**但这条分支今天不可达**：归墟结构的环带只放能源仓 / 动力仓 … 收不了样板仓
        //  ⇒ 它永远进不了 controllers 列表。⚠️ **若将来给主机加物品仓，这一条必须改成 false**。」
        //
        //  <p>2026-10-25 的 T2 正好把物品/流体仓与四个样板总成放进了环带
        //  （{@code GuixuVisionCoreStructure#ringBandPredicate}）⇒ "<b>将来</b>"到了 ⇒ 在这里改判：
        //  归墟主机本体<b>自己只跑 1 个类型</b>（{@code GuixuVisionCoreRecipeLogic extends
        //  PrimordialEngineRecipeLogic}，没有跨类型；模式选择页也已移除），
        //  而它的 {@code crossTypeModeDisplayName()} 因 {@code grantsCrossTypeParallel()} 恒 true 而<b>非 null</b>
        //  ⇒ 不判死就会"主机声称 3 个类型、自己只跑 1 个"（与用户 2026-10-25 报的那个 bug 同形）。
        //
        //  <p>🔴 <b>归墟必须排在 {@code PrimordialOmegaEngineMachine} 那条之前</b>（它是那个类的子类，
        //  顺序反了就永远走不到这里）。**只加这一处判断，下面每一行都一个字节没动。**
        if (machine instanceof com.shanhai.machine.host.GuixuVisionCoreMachine) {
            return null;                    // = 不展开；调用方会记一行 skipped_reason=guixu_host
        }
        if (machine instanceof PrimordialOmegaEngineMachine host) {
            return (host.crossTypeModeDisplayName() != null) ? "our_host" : null;
        }

        // ③ 上游的跨类型机器（伪神那一脉）：不靠"单一激活类型"跑
        if (!(machine instanceof IRecipeLogicMachine rlm)) {
            return null;
        }
        // 声明数组里出现 null ⇒ 这台机器本身是坏态（未注册类型被静默挂上），判不了 ⇒ 不展开
        for (GTRecipeType t : declared) {
            if (t == null) {
                return null;
            }
        }
        final GTRecipeType active = rlm.getRecipeType();
        if (active == null) {
            // 没有"当前类型"⇒ 它压根不按单类型跑（伪神那一脉以外，也可能是没接上类型的机器）
            return "upstream_no_active_type";
        }
        // 🔴 核心判据：当前类型【不在】声明的列表里 ⇒ 那个类型是 dummy ⇒ 它靠"全部类型"跑
        for (GTRecipeType t : declared) {
            if (t == active) {
                return null;          // 是声明里的某一个 ⇒ 这台机器只跑这一类 ⇒ 不展开
            }
        }
        // 机器自己的那份数组也过一遍（{@code WorkableMultiblockMachine#getRecipeTypes()}
        // 默认就是 definition 那个数组的同一个引用，但允许子类换掉）—— 只要在任意一份里出现过，
        // 就按"单类型机器"处理（更保守：宁可少展开，不要多展开）
        final GTRecipeType[] machineTypes = rlm.getRecipeTypes();
        if (machineTypes != null) {
            for (GTRecipeType t : machineTypes) {
                if (t == active) {
                    return null;
                }
            }
        }
        return "upstream_cross_type";
    }

    /** 只给日志用：这台机器当前激活的配方类型 id（{@code null} / 无 id 都如实写出来，不美化）。 */
    @Unique
    private static String shanhai$activeTypeId(MultiblockControllerMachine machine) {
        try {
            if (!(machine instanceof IRecipeLogicMachine rlm)) {
                return "n/a(not-IRecipeLogicMachine)";
            }
            final GTRecipeType active = rlm.getRecipeType();
            if (active == null) {
                return "null";
            }
            return (active.registryName == null) ? "null-registryName" : active.registryName.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }
}
