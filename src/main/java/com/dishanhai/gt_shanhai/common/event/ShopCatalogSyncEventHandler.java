package com.dishanhai.gt_shanhai.common.event;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.network.ShopCatalogManifestPacket;
import com.dishanhai.gt_shanhai.network.ShopQuestLinkSyncPacket;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 玩家登录即下发一次商店目录清单（山海署名）。
 *
 * <p>清单本来只在打开商店（{@code WalletOpenRequestPacket}）和目录变更广播（{@code ShopConfig#publish}）
 * 时才推，于是没打开过商店的玩家客户端目录是空的。FTBQ 任务详情页的「前往商店兑换」按钮要靠这份清单
 * 反查「哪些商品把本任务配成了前置」（见 {@code FtbViewQuestPanelShopButtonMixin}），没有它按钮永远不出现。</p>
 *
 * <p>推的是轻量清单（分类/名称/chunk 归属这些索引字段），不含商品实体——那部分仍按分块惰性加载，
 * 跟原来一样只在玩家真正浏览到时才拉。</p>
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShopCatalogSyncEventHandler {

    private ShopCatalogSyncEventHandler() {}

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ShopCatalogManifestPacket.sendTo(player, ShopConfig.manifest());
        // 「任务 → 商店商品」手动绑定表，同一时机推：任务详情页那条跳转入口靠它决定显不显示
        ShopQuestLinkSyncPacket.sendTo(player);
    }
}
