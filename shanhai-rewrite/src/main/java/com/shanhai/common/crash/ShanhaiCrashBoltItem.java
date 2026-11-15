package com.shanhai.common.crash;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 16 件 {@code ex_*}（运行时异常物品）的<b>实现类</b>：持有、按住右键蓄力、松手把
 * {@link ShanhaiCrashBoltEntity} 扔出去 —— 口径逐条照原版三叉戟
 * {@code net/minecraft/world/item/TridentItem.java}。
 *
 * <h2>一、四个钩子（与 {@code TridentItem} 一一对应）</h2>
 * <table border="1">
 *   <caption>三叉戟口径</caption>
 *   <tr><th>环节</th><th>三叉戟（原版）</th><th>本类</th></tr>
 *   <tr><td>开始蓄力</td><td>{@code use()} 末尾 {@code player.startUsingItem(hand); return InteractionResultHolder.consume(stack);}</td>
 *       <td>同（{@link #use}）</td></tr>
 *   <tr><td>蓄力上限</td><td>{@code getUseDuration → 72000}</td><td>同（{@link #CHARGE_TICKS}）</td></tr>
 *   <tr><td>动作动画</td><td>{@code getUseAnimation → UseAnim.SPEAR}</td><td>同（{@link #getUseAnimation}）</td></tr>
 *   <tr><td>释放</td><td>{@code releaseUsing}: {@code i = 72000 - remaining; if (i >= 10) { … shootFromRotation(2.5F) … }}</td>
 *       <td>同（{@link #releaseUsing}；阈值 {@link #THROW_THRESHOLD_TICKS} = 10、力度 {@link #SHOOT_POWER} = 2.5）</td></tr>
 * </table>
 *
 * <h2>二、为什么没有照抄"耐久/附魔/返回"</h2>
 * 三叉戟是"扔出去还要回来"的武器（{@code ThrownTrident} 会被捡回），本物品是<b>一次性的</b>：
 * 扔出去的那一发落地 3 秒后会把游戏崩掉，它没有"回来"的机会。
 * ⇒ 不做 {@code hurtAndBreak}、不做忠诚/激流附魔判断；生存模式扔出去就消耗 1 个
 * （创造模式不消耗，照三叉戟 {@code getAbilities().instabuild} 的判法）。
 * ⚠️ 这是需求没写、由我定的口径（已写进交付报告的"需求外决定"一节）。
 *
 * <h2>三、tooltip 为什么是字面量而不是 lang 键</h2>
 * 需求把这一句<b>逐字</b>给了（含 {@code §c}）：{@link #TOOLTIP_CRASH}。
 * tooltip 是纯客户端渲染（{@code appendHoverText} 只在客户端被调），
 * 写成字面量的好处是：任何语言、任何资源包缺 lang 的情况下，这一句都一字不差地显示出来。
 * 物品<b>名字</b>仍走 lang（{@code item.shanhai.ex_*}），这一句只是"怎么用/会怎样"的说明。
 */
public class ShanhaiCrashBoltItem extends Item {

    /** 蓄力上限（三叉戟 {@code getUseDuration} 就是 72000 = 3600 秒，实际靠松手时机截断）。 */
    public static final int CHARGE_TICKS = 72000;

    /** 最少要蓄多久才算一次投掷（三叉戟 {@code THROW_THRESHOLD_TIME} = 10 tick）。 */
    public static final int THROW_THRESHOLD_TICKS = 10;

    /** 出手速度（三叉戟 {@code SHOOT_POWER} = 2.5F）。 */
    public static final float SHOOT_POWER = 2.5F;

    /** 出手时的散布（三叉戟 {@code shootFromRotation(…, 0.0F, 2.5F, 1.0F)} 的最后一个参数）。 */
    public static final float SHOOT_INACCURACY = 1.0F;

    /** 需求点名的第一行 tooltip（逐字，含 {@code §c}）。 */
    public static final String TOOLTIP_CRASH = "§c落地 3 秒后必崩，捡了也没用。";

    /** 第二行 tooltip：告诉玩家怎么用（需求没点名这一句，是我加的操作提示）。 */
    public static final String TOOLTIP_CHARGE = "§7按住右键蓄力，松手扔出去。";

    /** 异常序号（0..15，与 {@link ShanhaiCrashBoltTable} 的行序一致）。 */
    private final int index;

    public ShanhaiCrashBoltItem(int index, Item.Properties properties) {
        // ⚠️ 不写 stacksTo：阶段 1/2 这 16 件的堆叠上限是默认的 64，本阶段不碰它
        //    （物品属性在注册那一刻定死；改了就是"顺手改了上线物品的属性"，不是本阶段的事）。
        super(properties);
        this.index = Math.max(0, Math.min(index, ShanhaiCrashBoltTable.COUNT - 1));
    }

    /** 异常序号。 */
    public int boltIndex() {
        return this.index;
    }

    /** 注册 id 的 path（{@code ex_nullpointerexception}）。 */
    public String path() {
        return ShanhaiCrashBoltTable.path(this.index);
    }

    // ------------------------------------------------------------------ 开始蓄力

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // 照 TridentItem#use 的末尾两行
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    // ------------------------------------------------------------------ 蓄力口径

    @Override
    public int getUseDuration(ItemStack stack) {
        return CHARGE_TICKS;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.SPEAR;
    }

    // ------------------------------------------------------------------ 松手 = 投出

    /**
     * 松手：蓄够 {@link #THROW_THRESHOLD_TICKS} 就在<b>服务端</b>造一个
     * {@link ShanhaiCrashBoltEntity} 扔出去。
     *
     * <p>⚠️ 只在 {@code !level.isClientSide} 那一支造实体（照 {@code TridentItem#releaseUsing}）：
     * 客户端造实体 = 两端各飞一个，落地后客户端那个永远不会崩游戏，看起来像"投掷物卡住了"。
     */
    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity living, int timeLeft) {
        if (!(living instanceof Player player)) {
            return;
        }
        int charged = this.getUseDuration(stack) - timeLeft;
        if (charged < THROW_THRESHOLD_TICKS) {
            return;   // 没蓄够：什么都不发生（照三叉戟）
        }
        if (level.isClientSide) {
            return;
        }
        ShanhaiCrashBoltEntity bolt = new ShanhaiCrashBoltEntity(level, player, this.index);
        bolt.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, SHOOT_POWER, SHOOT_INACCURACY);
        // 落地那一刻它会被锁住；这里先把"出手动势"清零以外的都交给原版物理。
        level.addFreshEntity(bolt);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0F, 1.0F);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);   // 一次性（见类注释 §二）
        }
    }

    // ------------------------------------------------------------------ tooltip

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.literal(TOOLTIP_CRASH).withStyle(ChatFormatting.RED));
        tooltip.add(Component.literal(TOOLTIP_CHARGE).withStyle(ChatFormatting.GRAY));
    }
}
