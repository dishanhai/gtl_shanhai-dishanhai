package com.shanhai.common.patternmover;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 物品本体：{@code shanhai:host_pattern_mover}（主机样板搬运工具），<b>可堆叠 1</b>。
 *
 * <h2>一、三个入口（用户拍板，逐字）</h2>
 * <pre>
 *   ① Shift+右键主机  = 读取全部样板并入物品（已有则合并）      ← 第一刀（本类 onItemUseFirst）
 *   ② 右键主机        = 放置（第二刀）                          ← 第一刀只占位，回 PASS
 *   ③ Shift+右键空气  = 面板                                    ← 第一刀（Item#use）
 * </pre>
 *
 * <h2>二、为什么挂在 {@code Item#onItemUseFirst} 而不是别的钩子</h2>
 * <ul>
 *   <li><b>它比方块自己的 {@code use}（= 开机器 GUI）早</b> ⇒ 我们能先吃掉右键。取证
 *       （{@code temp/pattern-mover/取证.md} §2.4-2）写明：「用 {@code Item#onItemUseFirst} 最稳
 *       （它比方块 {@code use} 更早，完全绕开 gtladditions 那个
 *       {@code MEPatternBufferPartMachineBase#onUse} 的 {@code @Overwrite}）」；</li>
 *   <li>gtlcore 的两件样板工具（复制 / 剪切）也全走这条路径
 *       （{@code temp/pattern-mover/剪切粘贴工具.md} §②：{@code ComponentItem#onItemUseFirst}
 *       → 首个非 PASS 即返回）。</li>
 * </ul>
 * <p>⚠️ 任务书写的是「{@code @Mod.EventBusSubscriber} 式挂 {@code onItemUseFirst}」——
 * 这里按<b>语义</b>落地：{@code onItemUseFirst} 是 {@code Item} 的实例方法、不是 Forge 事件，
 * 所以正确形态是<b>覆写它</b>。本包确实有一个 {@code @Mod.EventBusSubscriber}
 * （{@link HostPatternMoverBootstrap}），但它负责的是「面板工厂注册 ＋ 自检落日志」。
 *
 * <h2>三、🔴 两侧同判（否则会出现"客户端开了 GUI、服务端没开"的分叉）</h2>
 * {@code onItemUseFirst} 在<b>客户端与服务端都会跑</b>，而真正搬样板只在服务端。
 * 两边都走同一个判据 {@link HostPatternMoverReader#looksLikeTarget}：
 * <ul>
 *   <li>不是宿主 ⇒ <b>两边都</b>回 {@code PASS}（让方块 GUI 正常工作）；</li>
 *   <li>是宿主 ⇒ 两边都回 {@code SUCCESS}（客户端若回 PASS，它会在本地把方块交互再跑一遍）。</li>
 * </ul>
 */
public class HostPatternMoverItem extends Item {

    public HostPatternMoverItem(Item.Properties properties) {
        super(properties);
    }

    // ------------------------------------------------------------------ ① 读取

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        final Player player = context.getPlayer();
        final Level level = context.getLevel();
        if (player == null) {
            return InteractionResult.PASS;
        }
        final BlockPos pos = context.getClickedPos();
        final MetaMachine machine = machineAt(level, pos);
        if (machine == null || !HostPatternMoverReader.looksLikeTarget(machine)) {
            // 不是宿主 ⇒ 两边都 PASS（右键别人的方块仍然正常工作）
            return InteractionResult.PASS;
        }
        // ------------------------------------------------------------------ ② 右键主机 = 放置（第二刀）
        if (!player.isShiftKeyDown()) {
            // 🔴 两侧同判，判据只用两件【两侧都算得出来】的东西：
            //    ① 点的是不是真正的主机（HostPatternMoverReader#looksLikePlaceHost，不用遍历总成）
            //    ② 物品自己 NBT 里有没有样板
            //    ⇒ 不满足就 PASS（让方块 GUI 正常开），满足才吃掉这次右键。
            if (!HostPatternMoverReader.looksLikePlaceHost(machine)) {
                return InteractionResult.PASS;
            }
            if (HostPatternMoverNbt.countOf(HostPatternMoverNbt.moverOf(stack.getTag())) <= 0) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide) {
                return InteractionResult.SUCCESS; // 客户端也回 SUCCESS，否则它会在本地把方块交互再跑一遍
            }
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            try {
                // 只算计划 ＋ 弹确认面板；真正写槽在「确认放置」那一下（取消 ⇒ 一个字节都不写）
                HostPatternMoverActions.preview(serverPlayer, stack, machine, pos);
            } catch (Throwable t) {
                HostPatternMoverLog.warn("{} 打开放置确认面板失败 pos={} err={}",
                        HostPatternMoverReader.PREFIX, pos.toShortString(), t.toString(), t);
            }
            return InteractionResult.SUCCESS;
        }
        // ------------------------------------------------------------------ ① Shift+右键主机 = 读取
        if (level.isClientSide) {
            // 两侧同判：宿主 ⇒ 客户端也回 SUCCESS（真正干活只在服务端）
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        final HostPatternMoverReader.Summary summary;
        try {
            summary = HostPatternMoverReader.readIntoItem(stack, machine, pos);
        } catch (Throwable t) {
            HostPatternMoverLog.warn("{} 读取失败 pos={} err={}", HostPatternMoverReader.PREFIX,
                    pos.toShortString(), t.toString(), t);
            return InteractionResult.SUCCESS;
        }
        if (summary == null) {
            // 认得出是"像宿主"，但一台样板总成都没收到 ⇒ 如实告诉玩家（不静默）
            serverPlayer.displayClientMessage(Component.translatable("shanhai.mover.no_assembly"), false);
            return InteractionResult.SUCCESS;
        }
        serverPlayer.displayClientMessage(HostPatternMoverReader.feedback(summary), false);
        final net.minecraft.network.chat.Component wildcard = HostPatternMoverReader.wildcardFeedback(summary);
        if (wildcard != null) {
            serverPlayer.displayClientMessage(wildcard, false);
        }
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ ③ 面板

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            HostPatternMoverFactory.open(serverPlayer, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    // ------------------------------------------------------------------ tooltip（只读便宜字段）

    /**
     * 提示里只放<b>便宜字段</b>（条数 / 已用字节 / 上限 / 来源主机）。
     *
     * <p>🔴 <b>绝不</b>在这里重建 {@link HostPatternMoverEntry}：重建一条要把它的序列化字节真写一遍，
     * 1000 块的物品每帧刷 tooltip 就是上千次 NBT 序列化 —— 那是会卡的行为。
     * 这里只做 {@code ListTag.size()} 与两个 {@code getInt/getString}。
     */
    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        final CompoundTag mover = HostPatternMoverNbt.moverOf(stack.getTag());
        final int count = HostPatternMoverNbt.countOf(mover);
        final int bytes = Math.max(0, HostPatternMoverNbt.readStoredBytes(mover));
        final String fromHost = HostPatternMoverNbt.readFromHost(mover);
        tooltip.add(Component.translatable("shanhai.mover.tooltip.count", count,
                HostPatternMoverData.LIMIT_BYTES).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("shanhai.mover.tooltip.bytes", bytes,
                HostPatternMoverData.LIMIT_BYTES).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("shanhai.mover.tooltip.host",
                fromHost.isEmpty() ? "?" : fromHost).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("shanhai.mover.tooltip.read").withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.translatable("shanhai.mover.tooltip.place").withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.translatable("shanhai.mover.tooltip.panel").withStyle(ChatFormatting.DARK_AQUA));
    }

    // ------------------------------------------------------------------ 工具

    private static MetaMachine machineAt(Level level, BlockPos pos) {
        try {
            return MetaMachine.getMachine(level, pos);
        } catch (Throwable t) {
            return null;
        }
    }
}
