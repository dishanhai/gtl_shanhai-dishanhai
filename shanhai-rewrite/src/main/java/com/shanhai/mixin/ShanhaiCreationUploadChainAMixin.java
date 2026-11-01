package com.shanhai.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.menu.slot.RestrictedInputSlot;
import com.extendedae_plus.util.uploadPattern.MatrixUploadUtil;
import com.shanhai.machine.creation.CreationPatternUpload;
import com.shanhai.machine.creation.PrimordialCreationCompilationMatrix;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 链 A 的接管：在 {@code extendedae_plus} 的「编码 → 自动上传」入口最上面插一层，
 * <b>我们的原初造物编译矩阵优先</b>；收不下就<b>原样放行</b>给上游（分子操纵者 → AE 装配矩阵）。
 *
 * <h2>1. 注入点（与那个转接插件同一个注入点，我们自己实现、功能合并）</h2>
 * <pre>
 *   目标类：com.extendedae_plus.util.uploadPattern.MatrixUploadUtil
 *   目标方法：uploadFromEncodingMenuToMatrix(ServerPlayer, PatternEncodingTermMenu)   ← public static void
 *   描述符：(Lnet/minecraft/server/level/ServerPlayer;Lappeng/menu/me/items/PatternEncodingTermMenu;)V
 *   注入：@At("HEAD")、cancellable = true、require = 1、remap = false
 * </pre>
 * <p>这个注入点就是<b>唯一</b>一处决定"样板最终进谁"的分岔（取证的字节码偏移：上游在别的跳里才去
 * {@code findAllMatrixPatternInventories} / {@code InternalInventory#addItems}）⇒ 在 HEAD 收下并
 * {@code ci.cancel()} 等价于"这一次上传完全不碰分子操纵者 / AE 装配矩阵"；
 * 不 cancel 则上游那一整段<b>一个字节都不改</b>地照跑。
 *
 * <h2>2. 🔴 判据（按顺序，短路）</h2>
 * <pre>
 *   ① 从终端取「这次编码出来的那块样板」（{@link ShanhaiPatternEncodingTermMenuAccessor} 用的是
 *      {@code PatternEncodingTermMenu#encodedPatternSlot}，与上游 L52-53 同一个槽）
 *   ② 取当前 AE 网格（{@code menu.getNetworkNode().getGrid()}）
 *   ③ 网格上有没有一台我们的机器能<b>整批</b>收下：{@link PrimordialCreationCompilationMatrix#creationChoiceFor}
 *      —— 六道闸（成型 / 节点激活 / 类型 / 查重 / 容量 / 过滤器）全在
 *      {@link CreationPatternUpload#decide}，那段逻辑离线自证跑的就是它
 *   ④ 收得下 ⇒ 真写（全或无）⇒ 扣掉终端里那块样板 ⇒ {@code ci.cancel()}
 *      收不下 ⇒ <b>不 cancel</b> ⇒ 上游原样继续（这就是"我们 &gt; 分子操纵者 &gt; AE 矩阵"的落法）
 * </pre>
 *
 * <h2>3. 与既有插件的先后（优先级）</h2>
 * 转接插件 {@code extendedae_plus_gtladd} 的 {@code MatrixUploadUtilMixin#onUploadStart} 也注在这个
 * HEAD 上（默认 {@code priority = 1000}，且它在 {@code restrictCraftingPatternToMolecular} 默认 true 时
 * 会直接 cancel 并把样板送给分子操纵者）。本 mixin 显式写 {@code priority = 800}
 * —— 按 Sponge Mixin 的惯例（<b>数值小的先应用</b>，{@code javap}/文档口径：priority 越高越晚）我们排在它前面。
 * <p>⚠️ <b>这个方向本轮没有在实机取证</b>（只按惯例 + gtlcore 自己用 {@code priority = 900} 去抢在默认 1000 之前
 * 这一既有先例判断）⇒ 已写进交付报告的「待你确认」；并且本 mixin 在<b>第一次运行时</b>探测那个插件是否装着，
 * 装着就打一行 WARN 告诉玩家"两者都在时请关掉它的 restrictCraftingPatternToMolecular"（判据落在日志里，
 * 不靠人记）。
 *
 * <h2>4. 失败/异常一律「不 cancel」</h2>
 * 本 mixin 的任何一步出问题（网格取不到、解码炸了、写入抛异常）都<b>不</b> cancel ——
 * 也就是退回上游既有行为，绝不会出现"样板被我们吞了但没写进机器"的形态。
 * 唯一例外是 {@link CreationPatternUpload.PartialCommitException}（已经写进去了一部分，
 * 再让上游写一次就会双份）：那一支会 cancel 并打 ERROR。
 */
@Mixin(value = MatrixUploadUtil.class, remap = false, priority = 800)
public abstract class ShanhaiCreationUploadChainAMixin {

    /** 日志前缀（grep 用；无头专服里就认这一串）。 */
    @Unique
    private static final String SHANHAI$CHAIN_A_TAG = "[SHANHAI-CREATION-UPLOAD] ";

    /**
     * 本类自己的 logger（与机器类同款：{@code LoggerFactory.getLogger("shanhai/creation-upload")}）。
     *
     * <p>为什么不复用 {@code ShanhaiMod.LOGGER}：这么写让本 mixin 与 mod 入口类<b>零耦合</b>
     * （离线 javac 自证时不需要把 {@code ShanhaiMod} 连带整棵注册树都编一遍），
     * 而日志名 {@code shanhai/creation-upload} 反而更适合 grep。
     */
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("shanhai/creation-upload");

    /** 已经打过日志的「放行原因」（每条原因只打一次，避免每次点编码都刷屏）。 */
    @Unique
    private static final Set<String> SHANHAI$CHAIN_A_LOGGED = ConcurrentHashMap.newKeySet();

    /** 那个转接插件在不在，只探测一次。 */
    @Unique
    private static boolean shanhai$chainAProbedPlugin;

    /**
     * HEAD 注入：先问我们自己要不要这批样板。
     *
     * @param player 服务端玩家（上游在 {@code server.execute(...)} 里调进来）
     * @param menu   样板编码终端（服务端那一份）
     * @param ci     可 cancel 的回调（cancel 后上游整段不执行）
     */
    @Inject(
            method = "uploadFromEncodingMenuToMatrix(Lnet/minecraft/server/level/ServerPlayer;Lappeng/menu/me/items/PatternEncodingTermMenu;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            remap = false)
    private static void shanhai$takeOverUpload(ServerPlayer player, PatternEncodingTermMenu menu, CallbackInfo ci) {
        try {
            shanhai$chainAProbePlugin();
            if (player == null || menu == null) {
                return;
            }
            // ① 这次要上传的那块样板（与上游 L52-53 同一个槽）
            final ItemStack encoded = shanhai$encodedPattern(menu);
            if (encoded.isEmpty()) {
                return;                     // 没样板 ⇒ 上游也会立刻 return，放行
            }
            // ② 当前网格
            final IGridNode node = menu.getNetworkNode();
            final IGrid grid = node == null ? null : node.getGrid();
            if (grid == null) {
                return;                     // 没连网 ⇒ 上游也做不了，放行
            }
            // ③ 网格上有没有我们能整批收下的机器（六道闸在 CreationPatternUpload#decide）
            final List<ItemStack> batch = List.of(encoded.copy());
            final PrimordialCreationCompilationMatrix.UploadChoice choice =
                    PrimordialCreationCompilationMatrix.creationChoiceFor(grid, batch);
            if (choice == null) {
                // 🔴 收不下 ⇒【不 cancel】⇒ 上游（分子操纵者 → AE 矩阵）原样继续
                shanhai$logPassOnce(grid, batch);
                return;
            }
            // ④ 真写（全或无）
            final int slot = choice.plan().firstSlot();
            try {
                if (!choice.plan().commit()) {
                    // 规划通过了但提交阶段被否（同一 tick 内不可能，除非上游回调有副作用）⇒ 一个字节都没写
                    LOGGER.warn(SHANHAI$CHAIN_A_TAG
                                    + "chain=A commit_refused machine={} pattern={} ⇒ 不 cancel，交给上游",
                            choice.plan().sink().describe(), shanhai$patternLabel(encoded));
                    return;
                }
            } catch (CreationPatternUpload.PartialCommitException partial) {
                // 🔴 已经写进去一部分：再让上游写一次就双份 ⇒ 这一支才 cancel
                LOGGER.error(SHANHAI$CHAIN_A_TAG
                                + "chain=A PARTIAL_COMMIT machine={} written={}/{} failedSlot={} pattern={}"
                                + " ⇒ 已 cancel 防止上游再写一份（请按日志核对）",
                        choice.plan().sink().describe(), partial.written(), partial.total(),
                        partial.failedSlot(), shanhai$patternLabel(encoded));
                shanhai$consumeEncodedSlot(menu, partial.written());
                ci.cancel();
                return;
            }
            // ⑤ 成功：扣掉终端里的那块样板（与上游 completeUploadSuccess 同义）+ 告诉玩家 + cancel
            shanhai$consumeEncodedSlot(menu, choice.plan().size());
            player.displayClientMessage(Component.translatable(
                    "message.shanhai.creation_matrix.uploaded", slot), true);
            LOGGER.info(SHANHAI$CHAIN_A_TAG
                            + "chain=A take_over player={} machine={} slot={} pattern={}",
                    player.getGameProfile().getName(), choice.machine().getPos().toShortString(),
                    slot, shanhai$patternLabel(encoded));
            ci.cancel();
        } catch (Throwable t) {
            // 任何意外都退回上游既有行为（绝不"悄悄吞掉样板"）
            LOGGER.warn(SHANHAI$CHAIN_A_TAG + "chain=A take_over_failed err={} ⇒ 不 cancel，交给上游",
                    t.toString());
        }
    }

    // ═════════════════════════════ 内部小工具（全部 @Unique，不会落到上游类上） ═════════════════════════════

    /** 终端「已编码样板」输出槽里的那块样板（{@code ItemStack.EMPTY} = 还没编码）。 */
    @Unique
    private static ItemStack shanhai$encodedPattern(PatternEncodingTermMenu menu) {
        final RestrictedInputSlot encodedSlot =
                ((ShanhaiPatternEncodingTermMenuAccessor) (Object) menu).shanhai$getEncodedPatternSlot();
        if (encodedSlot == null) {
            return ItemStack.EMPTY;
        }
        final ItemStack stack = encodedSlot.getItem();
        return (stack == null) ? ItemStack.EMPTY : stack;
    }

    /**
     * 从终端里扣掉 <b>n 块</b>刚上传成功的样板（与上游 {@code completeUploadSuccess} 同义）：
     * 先 {@code shrink}，扣空就把槽置空。
     *
     * <p>为什么必须扣：不扣 = 样板既在机器里、又还在终端输出槽里 ⇒ 玩家能再拿一份，等于凭空多一块样板。
     */
    @Unique
    private static void shanhai$consumeEncodedSlot(PatternEncodingTermMenu menu, int n) {
        if (n <= 0) {
            return;
        }
        final RestrictedInputSlot encodedSlot =
                ((ShanhaiPatternEncodingTermMenuAccessor) (Object) menu).shanhai$getEncodedPatternSlot();
        if (encodedSlot == null) {
            return;
        }
        final ItemStack inSlot = encodedSlot.getItem();
        if (inSlot == null || inSlot.isEmpty()) {
            return;
        }
        inSlot.shrink(Math.min(n, inSlot.getCount()));
        if (inSlot.isEmpty()) {
            encodedSlot.set(ItemStack.EMPTY);
        }
    }

    /** 一行日志用的样板标识（名字 × 数量）。 */
    @Unique
    private static String shanhai$patternLabel(ItemStack stack) {
        try {
            return stack.getHoverName().getString() + " ×" + stack.getCount();
        } catch (Throwable t) {
            return "<unnamed>";
        }
    }

    /**
     * 把"这次为什么没接管"记一次日志（<b>按原因去重</b>，不按玩家、不按样板）。
     *
     * <p>为什么需要这一行：无头专服里"我们没接管"和"我们根本没被调用"在行为上完全一样，
     * 只有这条日志能把两者区分开 —— 所以原因要具体到「哪个坐标的机器被哪一道闸否掉」。
     */
    @Unique
    private static void shanhai$logPassOnce(IGrid grid, List<ItemStack> batch) {
        final String reason = shanhai$whyNoMachine(grid, batch);
        if (!SHANHAI$CHAIN_A_LOGGED.add(reason)) {
            return;
        }
        LOGGER.info(SHANHAI$CHAIN_A_TAG
                        + "chain=A pass_through reason=[{}] ⇒ 不 cancel，交给上游（分子操纵者 → AE 矩阵）",
                reason);
    }

    /**
     * 「为什么网格上没有一台能收」的原因串（只读：只调判定、绝不写库存）。
     *
     * @return {@code no_machine}（网格上一台我们的机器都没有）或
     *         {@code pos:REJECT, pos:REJECT}（每台机器各自被哪一道闸否掉）
     */
    @Unique
    private static String shanhai$whyNoMachine(IGrid grid, List<ItemStack> batch) {
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
                        .append(machine.creationUploadDecision(batch).reject().name());
            }
            return sb.toString();
        } catch (Throwable t) {
            return "why_failed:" + t;
        }
    }

    /**
     * 探一次「那个转接插件装没装」并留一行 WARN。
     *
     * <p>为什么要有这一行：我们和它注在<b>同一个 HEAD</b> 上，谁先跑由 mixin priority 决定
     * （我们 800、它默认 1000）。万一实机上一行 {@code chain=A take_over} 从来没有、
     * 而分子操纵者却收到了样板 ⇒ 就是它先 cancel 了 ⇒ 按这行 WARN 的指路关掉它的配置即可。
     */
    @Unique
    private static void shanhai$chainAProbePlugin() {
        if (shanhai$chainAProbedPlugin) {
            return;
        }
        shanhai$chainAProbedPlugin = true;
        try {
            if (net.minecraftforge.fml.ModList.get().isLoaded("extendedae_plus_gtladd")) {
                LOGGER.warn(SHANHAI$CHAIN_A_TAG
                        + "检测到 extendedae_plus_gtladd 仍装着：它和本 mixin 注入同一个 HEAD"
                        + "（我们 priority=800，它默认 1000）。若实机日志里没有 chain=A take_over、"
                        + "样板却进了分子操纵者 ⇒ 请把它配置里的 restrictCraftingPatternToMolecular 关掉。");
            }
        } catch (Throwable ignored) {
            // 探测本身失败不影响接管
        }
    }
}
