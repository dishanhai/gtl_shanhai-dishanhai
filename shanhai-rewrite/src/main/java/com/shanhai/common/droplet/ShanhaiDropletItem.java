package com.shanhai.common.droplet;

import com.shanhai.ShanhaiMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 强相互作用水滴的<b>物品</b>：右键方块 → 在点击处放一个 {@link ShanhaiDropletEntity}（规格 1）。
 *
 * <h2>一、为什么是"右键方块"而不是"右键空气"</h2>
 * 规格 1 写的是「手持水滴物品<b>右键方块</b> ⇒ 在<b>点击处</b>生成」，所以覆写的是
 * {@link Item#useOn(UseOnContext)}（方块交互），不是 {@code use(Level, Player, InteractionHand)}。
 * 生成坐标用 {@link UseOnContext#getClickLocation()}（准星与方块表面的交点），不是方块中心
 * —— 这样"点在哪个位置就出现在哪个位置"。
 *
 * <h2>二、消耗</h2>
 * 规格 1：「消耗 1 个物品」。实现上<b>创造模式不消耗</b>（与全工程其它"放置型"物品的原版口径一致；
 * 创造模式里每次测试都要重新拿一件会很碍事）。除创造外每次放置 {@code shrink(1)}。
 *
 * <h2>三、放置后</h2>
 * 立刻把三条可点击提示推到聊天框（{@link ShanhaiDropletSettings#sendControlPanel}，
 * {@code ClickEvent.Action.RUN_COMMAND}）。
 */
public class ShanhaiDropletItem extends Item {

    public ShanhaiDropletItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        final Level level = context.getLevel();
        final Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        // 客户端只负责摆手/音效，不改任何状态（实体由服务端生成后同步过来）
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        final Vec3 at = context.getClickLocation();
        final ShanhaiDropletEntity droplet = new ShanhaiDropletEntity(serverLevel, player);
        droplet.setPos(at.x, at.y, at.z);
        if (!serverLevel.addFreshEntity(droplet)) {
            // 生成失败（例如区块拒绝实体）：不扣物品、不装成功
            return InteractionResult.FAIL;
        }
        if (!player.isCreative()) {
            context.getItemInHand().shrink(1);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            ShanhaiDropletSettings.sendControlPanel(serverPlayer);
        }
        ShanhaiMod.LOGGER.info("{} placed owner={} uuid={} pos={} mode={} explosion={}",
                ShanhaiDropletCombat.PREFIX, player.getUUID(), droplet.getUUID(), at,
                ShanhaiDropletSettings.mode(player), ShanhaiDropletSettings.explosion(player));
        return InteractionResult.CONSUME;
    }
}
