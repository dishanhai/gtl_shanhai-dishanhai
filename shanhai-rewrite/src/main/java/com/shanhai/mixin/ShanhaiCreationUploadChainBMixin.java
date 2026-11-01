package com.shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.shanhai.machine.creation.CreationPatternUpload;
import com.shanhai.machine.creation.PrimordialCreationCompilationMatrix;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadMetadata;
import org.gtlcore.gtlcore.integration.ae2.pattern.PatternQuickUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 链 B（gtlcore 的「一键上传」箭头）的接管：<b>我们能收就独占</b>（不弹选择菜单），收不了就完全回到上游行为。
 *
 * <h2>1. 两处注入点（这是它能成立的全部原因）</h2>
 * <pre>
 *   ① 搜候选：PatternQuickUploadService#findTargets(ServerPlayer, IGrid, ItemStack, IPatternDetails, Set)
 *      描述符：(Lnet/minecraft/server/level/ServerPlayer;Lappeng/api/networking/IGrid;Lnet/minecraft/world/item/ItemStack;Lappeng/api/crafting/IPatternDetails;Ljava/util/Set;)Ljava/util/List;
 *      @At("RETURN")、cancellable ⇒ 能收就把返回值换成「只有我们」，收不了【原样不动】
 *   ② 真写：PatternQuickUploadService#insertIntoTargetSlotResult(ServerPlayer, ItemStack, Target)
 *      描述符：(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;Lorg/gtlcore/gtlcore/integration/ae2/pattern/PatternQuickUploadService$Target;)Lorg/gtlcore/gtlcore/integration/ae2/pattern/PatternQuickUploadService$UploadResult;
 *      @At("HEAD")、cancellable ⇒ 目标是我们这台机器时，自己写进 1000 格并给出明确结果码
 * </pre>
 *
 * <h2>2. 🔴 为什么必须由这两处注入「主动加入」，而不是补个接口等上游来认</h2>
 * <ul>
 *   <li>上游搜候选只走两条路：网格路线（入口闸 {@code PatternContainer.class.isAssignableFrom}，
 *       过了闸还要撞 {@code MEPatternBufferPartMachineBase} / {@code MEMolecularAssemblerIOPartMachine} /
 *       {@code TileAssemblerMatrixPattern} 三段硬编码 {@code instanceof}，<b>没有兜底</b>）；
 *       以及多方块路线（{@code collectTargetsFromLoadedMultiblocks} <b>只遍历 {@code controller.getParts()}</b>）。</li>
 *   <li>我们是<b>控制器、不是 part</b>（{@code javap}：{@code PrimordialModuleMachine extends}
 *       {@code WorkableElectricMultiblockMachine}）⇒ 多方块那条路<b>永远够不到我们</b>；
 *       网格那条路又只认那三个具体类 ⇒ 两条路都进不去。所以必须在 ① 里<b>主动把我们的 Target 塞进候选</b>。</li>
 * </ul>
 *
 * <h2>3. 「能收就独占」的口径（用户 2026 拍板）</h2>
 * <pre>
 *   我们的机器在、成型、节点激活、且这批样板它整批收得下（六道闸见 CreationPatternUpload#decide）
 *     ⇒ 候选列表整份换成 [我们]  ⇒ PatternQuickUploadMatch.select 判 UNIQUE
 *     ⇒ gtlcore 不弹选择菜单、直接走 encodeAndUploadPattern ⇒ ② 里我们自己写 ⇒ 结果码 INSERTED
 *   否则 ⇒ 候选列表【一个元素都不动】⇒ 上游原样：NONE / UNIQUE / AMBIGUOUS 菜单全部照旧
 * </pre>
 * <p>⚠️ 搜索期<b>不写</b>机器（只做不落盘的判定）：从搜到写之间隔着一个「玩家点箭头 → 服务端回调」的距离，
 * 中间可能变天（机器散了、格子满了）⇒ 写只在 ② 里发生，且失败必须给结果码。
 *
 * <h2>4. 结果码（不许静默失败）</h2>
 * <table border="1">
 *   <caption>② 里的四种出口</caption>
 *   <tr><th>情况</th><th>返回的 UploadStatus</th><th>玩家看到</th></tr>
 *   <tr><td>写成功</td><td>{@code INSERTED} + 真实槽号</td><td>gtlcore 的「已上传」提示</td></tr>
 *   <tr><td>这 1000 格里已有同一块样板</td><td>{@code DUPLICATE}</td><td>gtlcore 的「重复样板」提示</td></tr>
 *   <tr><td>收不下（容量/成型/节点/类型任一不满足）</td><td>{@code FAILED}</td><td>gtlcore 的「插入失败」提示</td></tr>
 *   <tr><td>目标坐标上不是我们的机器</td><td><b>不 cancel</b>（交回上游）</td><td>上游原样</td></tr>
 * </table>
 *
 * <h2>5. 已知边界（写进交付报告的「待你确认」，此处如实留档）</h2>
 * <ul>
 *   <li><b>撤销（UNDO）对我们是失效的</b>：上游撤销走 {@code removeFromTarget} →
 *       {@code resolvePatternContainer(level,pos)} 反查 {@code PatternContainer}，而<b>我们不实现那个接口</b>
 *       ⇒ 撤销会提示「undo_failed」，样板仍留在机器里（<b>不会丢</b>，只是撤不回来）。
 *       本轮不动第三处注入点（任务书只给两处），要撤回能力再单开。</li>
 *   <li>我们造的 {@code Target} 的 {@code targetMachineId} 传 {@code null}
 *       ⇒ 上游的 {@code collapseEquivalentAssemblyTargetsByGroup} 不会把我们跟别的机器合并
 *       （我们本来就是独占，合并与否都不影响）。</li>
 * </ul>
 */
@Mixin(value = PatternQuickUploadService.class, remap = false)
public abstract class ShanhaiCreationUploadChainBMixin {

    /** 日志前缀（与链 A 同一个串，无头专服里一起 grep）。 */
    @Unique
    private static final String SHANHAI$CHAIN_B_TAG = "[SHANHAI-CREATION-UPLOAD] ";

    /** 本类自己的 logger（与链 A/机器类同款；日志名 {@code shanhai/creation-upload} 便于 grep）。 */
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("shanhai/creation-upload");

    /** 已经打过日志的「不独占原因」（每条原因只打一次，避免刷屏）。 */
    @Unique
    private static final Set<String> SHANHAI$CHAIN_B_LOGGED = ConcurrentHashMap.newKeySet();

    // ═════════════════════════════ ① 搜候选：能收就独占 ═════════════════════════════

    /**
     * 在私有 {@code findTargets}(5 参) 的 RETURN 上把候选换成「只有我们」（能收时）。
     *
     * <p>为什么注在<b>私有</b>那个重载而不是 public 的 3 参：public 版本在拿到候选后还要
     * 过 {@code selectTargets}（collapse + select）才算完，而私有那个是<b>候选产生的唯一出口</b>
     * ⇒ 在这里换掉，后续 collapse/select 自动按上游逻辑处理我们这一条（1 条 ⇒ UNIQUE）。
     *
     * <p>🔴 必须写全描述符：{@code findTargets} 有两个重载（public 3 参 / private 5 参），
     * 只写方法名会让 Mixin 在目标类加载期报歧义（不是静默失效）。
     */
    @Inject(
            method = "findTargets(Lnet/minecraft/server/level/ServerPlayer;Lappeng/api/networking/IGrid;Lnet/minecraft/world/item/ItemStack;Lappeng/api/crafting/IPatternDetails;Ljava/util/Set;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            remap = false)
    private static void shanhai$ownCandidateList(ServerPlayer player,
                                                 IGrid grid,
                                                 ItemStack patternStack,
                                                 IPatternDetails patternDetails,
                                                 Set<ResourceLocation> recipeTypeIds,
                                                 CallbackInfoReturnable<List<PatternQuickUploadService.Target>> cir) {
        try {
            if (grid == null || patternStack == null || patternStack.isEmpty()) {
                return;
            }
            // 上游只在公共 findTargets 里判过一次"这是不是一键上传认的样板"（AEProcessingPattern | MA 支持）
            // ⇒ 这里不再重复那道闸；真正"归不归我们做"由机器侧的 accepts 闸判（只收分子装配能做的三类）。
            final PrimordialCreationCompilationMatrix.UploadChoice choice =
                    PrimordialCreationCompilationMatrix.creationChoiceFor(grid, List.of(patternStack.copy()));
            if (choice == null) {
                shanhai$logNotExclusiveOnce(grid, patternStack);
                return;                     // 🔴 收不下 ⇒ 候选一个元素都不动（= 上游行为）
            }
            final PatternQuickUploadService.Target ours =
                    shanhai$targetFor(choice.machine(), recipeTypeIds);
            if (ours == null) {
                shanhai$logNotExclusiveOnce(grid, patternStack);
                return;
            }
            final List<PatternQuickUploadService.Target> upstream = cir.getReturnValue();
            final int before = (upstream == null) ? 0 : upstream.size();
            cir.setReturnValue(List.of(ours));      // 🔴 能收就独占（不弹菜单）
            LOGGER.info(SHANHAI$CHAIN_B_TAG
                            + "chain=B exclusive machine={} pattern={} upstream_candidates={} ⇒ 只留我们这一条",
                    choice.machine().getPos().toShortString(),
                    shanhai$patternLabel(patternStack), before);
        } catch (Throwable t) {
            // 不 cancel ⇒ 上游候选原样（本 mixin 的任何意外都不许改变上游行为）
            LOGGER.warn(SHANHAI$CHAIN_B_TAG
                    + "chain=B exclusive_failed err={} ⇒ 候选保持上游原样", t.toString());
        }
    }

    // ═════════════════════════════ ② 真写：目标是我们 ⇒ 自己写进 1000 格 ═════════════════════════════

    /**
     * 在 {@code insertIntoTargetSlotResult} 的 HEAD 上接管「写进目标」那一跳。
     *
     * <p>上游那一跳本来会：{@code resolveVisiblePatternContainer} → {@code PatternContainer#getTerminalPatternInventory()}
     * → 逐槽试写。我们<b>不实现 {@code PatternContainer}</b>（本轮口径）⇒ 反查必然为 null ⇒ 上游必回 FAILED
     * ⇒ 所以必须在这里自己写，并把结果码翻成上游认识的 {@code UploadResult}。
     */
    @Inject(
            method = "insertIntoTargetSlotResult(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;Lorg/gtlcore/gtlcore/integration/ae2/pattern/PatternQuickUploadService$Target;)Lorg/gtlcore/gtlcore/integration/ae2/pattern/PatternQuickUploadService$UploadResult;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            remap = false)
    private static void shanhai$writeIntoOurMachine(ServerPlayer player,
                                                    ItemStack patternStack,
                                                    PatternQuickUploadService.Target target,
                                                    CallbackInfoReturnable<PatternQuickUploadService.UploadResult> cir) {
        try {
            if (player == null || target == null || patternStack == null || patternStack.isEmpty()) {
                return;
            }
            final ServerLevel level = player.server.getLevel(target.levelKey());
            if (level == null) {
                return;
            }
            // 目标坐标上是不是我们的控制器（MetaMachine 反查，与上游 resolvePatternContainer 同一句 API）
            final MetaMachine atPos = MetaMachine.getMachine(level, target.bufferPos());
            if (!(atPos instanceof PrimordialCreationCompilationMatrix machine)) {
                return;                     // 不是我们 ⇒ 【不 cancel】，上游原样处理
            }
            final CreationPatternUpload.Decision<ItemStack> decision =
                    machine.creationUploadDecision(List.of(patternStack.copy()));
            if (decision.accepted()) {
                final CreationPatternUpload.Plan<ItemStack> plan = decision.plan();
                boolean written = false;
                try {
                    written = plan.commit();
                } catch (CreationPatternUpload.PartialCommitException partial) {
                    LOGGER.error(SHANHAI$CHAIN_B_TAG
                                    + "chain=B PARTIAL_COMMIT machine={} written={}/{} failedSlot={} pattern={}"
                                    + " ⇒ 按已写入报 INSERTED（库存接口没有原子提交，无法回滚）",
                            machine.getPos().toShortString(), partial.written(), partial.total(),
                            partial.failedSlot(), shanhai$patternLabel(patternStack));
                    written = partial.written() > 0;
                }
                if (written) {
                    LOGGER.info(SHANHAI$CHAIN_B_TAG
                                    + "chain=B insert player={} machine={} slot={} pattern={}",
                            player.getGameProfile().getName(), machine.getPos().toShortString(),
                            plan.firstSlot(), shanhai$patternLabel(patternStack));
                    cir.setReturnValue(new PatternQuickUploadService.UploadResult(
                            PatternQuickUploadService.UploadStatus.INSERTED, target, plan.firstSlot()));
                } else {
                    LOGGER.warn(SHANHAI$CHAIN_B_TAG
                                    + "chain=B insert_refused machine={} pattern={} ⇒ 回 FAILED（不静默）",
                            machine.getPos().toShortString(), shanhai$patternLabel(patternStack));
                    cir.setReturnValue(new PatternQuickUploadService.UploadResult(
                            PatternQuickUploadService.UploadStatus.FAILED, target, -1));
                }
                cir.cancel();
                return;
            }
            // 走到这里说明"搜候选时能收、真要写时不能收"（机器散了/格子满了/已有同一块）
            final boolean duplicate = decision.reject() == CreationPatternUpload.Reject.DUPLICATE;
            LOGGER.warn(SHANHAI$CHAIN_B_TAG
                            + "chain=B insert_refused machine={} reason={} pattern={}",
                    machine.getPos().toShortString(), decision.describe(), shanhai$patternLabel(patternStack));
            cir.setReturnValue(new PatternQuickUploadService.UploadResult(
                    duplicate ? PatternQuickUploadService.UploadStatus.DUPLICATE
                            : PatternQuickUploadService.UploadStatus.FAILED,
                    target, -1));
            cir.cancel();
        } catch (Throwable t) {
            // 不 cancel ⇒ 上游原样（它反查不到 PatternContainer ⇒ 自己会回 FAILED，也不是静默）
            LOGGER.warn(SHANHAI$CHAIN_B_TAG
                    + "chain=B insert_failed err={} ⇒ 不 cancel，交回上游", t.toString());
        }
    }

    // ═════════════════════════════ 内部小工具（全部 @Unique） ═════════════════════════════

    /**
     * 把选中的那台机器翻译成 gtlcore 的 {@link PatternQuickUploadService.Target}。
     *
     * <p>字段口径（逐项都有交代，因为 {@code Target} 的 record 构造器有几个 {@code requireNonNull}）：
     * <ul>
     *   <li>{@code levelKey} = 机器所在维度（写的时候反查要用它）</li>
     *   <li>{@code bufferPos} = <b>控制器方块坐标</b>（我们不实现 PatternContainer，写的那一跳由 ② 自己处理）</li>
     *   <li>{@code targetName} = 方块名（gtlcore 的成功提示直接显示它 ⇒ 玩家看到的就是"原初造物编译矩阵"）</li>
     *   <li>{@code recipeTypeId} = 上游这次算出来的第一个（合成样板 = {@code minecraft:crafting}）；
     *       <b>空集就不造 Target</b>（record 的 {@code requireNonNull} 不接受 null，造了会炸）</li>
     *   <li>{@code recipeTypeName} = 用 gtlcore 自己的 {@code recipeTypeName}（名字口径与上游一致）</li>
     *   <li>{@code targetIcon} = {@code null}（独占路径不弹菜单，用不上图标）</li>
     *   <li>{@code targetMachineId} = {@code null} ⇒ 上游的分组合并不会把我们与别的机器合并</li>
     * </ul>
     */
    @Unique
    private static PatternQuickUploadService.Target shanhai$targetFor(
            PrimordialCreationCompilationMatrix machine, Set<ResourceLocation> recipeTypeIds) {
        final Level level = machine.getLevel();
        if (level == null || recipeTypeIds == null || recipeTypeIds.isEmpty()) {
            return null;
        }
        final ResourceLocation recipeTypeId = recipeTypeIds.iterator().next();
        if (recipeTypeId == null) {
            return null;
        }
        final Component targetName = machine.getBlockState().getBlock().getName();
        final Component recipeTypeName = PatternQuickUploadMetadata.recipeTypeName(recipeTypeId);
        return new PatternQuickUploadService.Target(
                level.dimension(),
                machine.getPos(),
                targetName,
                recipeTypeId,
                recipeTypeName,
                null,
                null,
                List.of(machine.getPos()));
    }

    /** 一行日志用的样板标识。 */
    @Unique
    private static String shanhai$patternLabel(ItemStack stack) {
        try {
            return stack.getHoverName().getString() + " ×" + stack.getCount();
        } catch (Throwable t) {
            return "<unnamed>";
        }
    }

    /** 「这次为什么没独占」只记一次（原因按坐标 + 闸名，去重后最多几十行）。 */
    @Unique
    private static void shanhai$logNotExclusiveOnce(IGrid grid, ItemStack patternStack) {
        final String reason = shanhai$whyNotExclusive(grid, patternStack);
        if (!SHANHAI$CHAIN_B_LOGGED.add(reason)) {
            return;
        }
        LOGGER.info(SHANHAI$CHAIN_B_TAG
                        + "chain=B not_exclusive reason=[{}] pattern={} ⇒ 候选保持上游原样",
                reason, shanhai$patternLabel(patternStack));
    }

    /**
     * 「为什么没能独占」的原因串（只读：只调判定，不写库存）。
     *
     * @return {@code no_machine} 或 {@code pos:REJECT, pos:REJECT}
     */
    @Unique
    private static String shanhai$whyNotExclusive(IGrid grid, ItemStack patternStack) {
        try {
            final List<PrimordialCreationCompilationMatrix> machines =
                    PrimordialCreationCompilationMatrix.creationMachinesOn(grid);
            if (machines.isEmpty()) {
                return "no_machine";
            }
            final StringBuilder sb = new StringBuilder();
            for (PrimordialCreationCompilationMatrix machine : machines) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(machine.getPos().toShortString())
                        .append(':')
                        .append(machine.creationUploadDecision(List.of(patternStack)).reject().name());
            }
            return sb.toString();
        } catch (Throwable t) {
            return "why_failed:" + t;
        }
    }
}
