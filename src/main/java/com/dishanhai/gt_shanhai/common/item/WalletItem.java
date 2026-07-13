package com.dishanhai.gt_shanhai.common.item;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;

/**
 * 山海钱包：手持右键打开山海商店界面（ScaledScreen 版）。
 *
 * <p>右键在客户端直接 {@code Minecraft.setScreen(new ShopScreen())} 打开界面，
 * 交易动作由界面发 {@code ShopActionPacket} 到服务端结算。余额存于钱包 NBT
 * （{@link com.dishanhai.gt_shanhai.common.shop.WalletCurrency}）。</p>
 *
 * <p>不再使用 LDLib ModularUI —— 商店界面已改用原版 Screen 体系的 ScaledScreen。</p>
 *
 * <p>实现 {@link top.theillusivec4.curios.api.type.capability.ICurioItem} 使其可装入
 * Curios 专属"wallet"饰品槽（见 {@code data/curios/curios/slots/wallet.json} +
 * {@code data/curios/tags/items/wallet.json}），全部方法用默认实现，无特殊装备逻辑。</p>
 */
public class WalletItem extends Item implements top.theillusivec4.curios.api.type.capability.ICurioItem {

    public WalletItem(Properties properties) {
        super(properties);
    }

    /**
     * 玩家背包（含盔甲栏/副手）或饰品栏任意位置是否携带钱包，供快捷键开店用（不要求手持）。
     * 仅服务端调用。
     */
    public static boolean isCarrying(ServerPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() instanceof WalletItem) return true;
        }
        return top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player)
                .map(handler -> handler.findFirstCurio(stack -> stack.getItem() instanceof WalletItem).isPresent())
                .orElse(false);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // 服务端侧算好编辑权后发包让客户端打开界面（与 /商店 命令统一），
        // 这样被授权的非 OP 玩家钱包右键也能看到编辑按钮。
        if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
            boolean canEdit = com.dishanhai.gt_shanhai.common.shop.ShopEditPermission.canEdit(sp);
            com.dishanhai.gt_shanhai.network.ShanhaiNetwork.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                    new com.dishanhai.gt_shanhai.network.ShopOpenPacket(canEdit,
                            com.dishanhai.gt_shanhai.common.shop.ShopConfig.manifest()));
            // 打开钱包即推账户快照，客户端界面/tooltip 立刻有余额（余额已不在 ItemStack NBT）
            com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI.sync(sp);
        }
        return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§7右键打开 §6山海商店"));
        // 余额已迁到玩家账户（按玩家UUID，钱跟人不跟物），不再存于本物品 NBT
        tooltip.add(Component.literal("§8余额跟随玩家账户 · 右键界面内查看"));
        tooltip.add(Component.literal("§8可装入饰品栏，控制选项绑定快捷键后免手持开店"));
        // 显示当前快捷键绑定：客户端专属（KeyMapping 只存在于客户端），DistExecutor 防止服务端加载客户端类
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                tooltip.add(com.dishanhai.gt_shanhai.client.ShanhaiKeyMappings.getOpenShopKeyTooltip()));
    }
}
