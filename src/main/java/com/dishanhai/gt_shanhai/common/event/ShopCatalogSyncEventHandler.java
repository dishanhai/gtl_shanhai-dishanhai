package com.dishanhai.gt_shanhai.common.event;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.network.ShopCatalogManifestPacket;
import com.dishanhai.gt_shanhai.network.ShopOpenPacket;
import com.dishanhai.gt_shanhai.network.ShopQuestLinkSyncPacket;
import com.dishanhai.gt_shanhai.network.ShopSubmissionSyncPacket;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PermissionsChangedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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

    private static volatile boolean resourceIndexRefreshPending;
    private static final Set<UUID> EDIT_ACCESS_REFRESH = new HashSet<>();

    static {
        if (net.minecraftforge.fml.ModList.get().isLoaded("ftbquests")) FtbqRefreshListener.register();
    }

    /** 隔离可选依赖；FTBQ 在 readDataFull 开头清缓存，此时还不能读取新奖励表。 */
    private static final class FtbqRefreshListener {
        static void register() {
            dev.ftb.mods.ftbquests.events.ClearFileCacheEvent.EVENT.register(file -> {
                if (file instanceof dev.ftb.mods.ftbquests.quest.ServerQuestFile) resourceIndexRefreshPending = true;
            });
        }
    }

    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        resourceIndexRefreshPending = true;
    }

    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (!EDIT_ACCESS_REFRESH.isEmpty()) {
            for (UUID id : EDIT_ACCESS_REFRESH) {
                ServerPlayer player = event.getServer().getPlayerList().getPlayer(id);
                if (player != null) ShopOpenPacket.syncEditAccess(player);
            }
            EDIT_ACCESS_REFRESH.clear();
        }
        if (!resourceIndexRefreshPending) return;
        resourceIndexRefreshPending = false;
        ShopConfig.refreshResourceIndex();
    }

    @SubscribeEvent
    public static void onOpPermissionChanged(PermissionsChangedEvent event) {
        // Forge 先发可取消事件，再更新 OP 列表；tick 末读取真正生效的权限。
        EDIT_ACCESS_REFRESH.add(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        com.dishanhai.gt_shanhai.common.shop.ShopAvailability.clearPersonal(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        resourceIndexRefreshPending = false;
        EDIT_ACCESS_REFRESH.clear();
        com.dishanhai.gt_shanhai.common.shop.ShopAvailability.clearPersonal();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        com.dishanhai.gt_shanhai.network.ShopAvailabilityPacket.sendTo(player);
        ShopCatalogManifestPacket.sendTo(player, ShopConfig.manifest());
        // 「任务 → 商店商品」手动绑定表，同一时机推：任务详情页那条跳转入口靠它决定显不显示
        ShopQuestLinkSyncPacket.sendTo(player);
        ShopSubmissionSyncPacket.sync(player);
    }
}
