package com.dishanhai.gt_shanhai.common.shop;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 山海商店 AE 绑定解析层：任何"能给某玩家提供在线 AE 网络"的机器（FTBQ 提交器、商店终端……）
 * 实现 {@link Provider} 并在加载/卸载时注册/取消注册进来，商店购买/结算只认这一层，
 * 不直接依赖某个具体机器类——新增绑定来源只需要多实现一个 Provider，不用改本类以外的商店代码。
 */
public final class ShopAeNetwork {

    private ShopAeNetwork() {}

    public interface Provider {
        boolean isOnline();
        /** 这台设备当前是否给该玩家提供 AE 网络（提交器按 FTBQ 队伍匹配，商店终端按放置者 UUID 匹配）。 */
        boolean servesPlayer(ServerPlayer player);
        /** 拿这台设备所在 AE 网络的存储；未连接网络返回 null。 */
        MEStorage storage();
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    public static void register(Provider provider) {
        if (provider != null) PROVIDERS.add(provider);
    }

    public static void unregister(Provider provider) {
        PROVIDERS.remove(provider);
    }

    /** 遍历所有已注册的绑定源，返回第一个在线且匹配该玩家的 AE 网络存储（无则 null）。 */
    private static MEStorage findBoundStorage(ServerPlayer player) {
        if (player == null) return null;
        for (Provider provider : PROVIDERS) {
            if (!provider.isOnline()) continue;
            if (!provider.servesPlayer(player)) continue;
            MEStorage storage = provider.storage();
            if (storage != null) return storage;
        }
        return null;
    }

    /** 是否存在能全额收下的绑定在线网络（纯 SIMULATE，不改动网络）。 */
    public static boolean canInjectForPlayer(ServerPlayer player, AEKey key, long amount) {
        if (key == null || amount <= 0L) return false;
        MEStorage storage = findBoundStorage(player);
        if (storage == null) return false;
        return storage.insert(key, amount, Actionable.SIMULATE, IActionSource.empty()) >= amount;
    }

    /**
     * 把物品注入绑定该玩家的在线 AE 网络。
     * 严格 SIMULATE→MODULATE：先模拟能否全额收下，能才真注入，防吞物品。
     * @return 实际注入量（0 = 无可用绑定源 / 网络收不下全部）
     */
    public static long injectForPlayer(ServerPlayer player, AEKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;
        MEStorage storage = findBoundStorage(player);
        if (storage == null) return 0L;
        if (storage.insert(key, amount, Actionable.SIMULATE, IActionSource.empty()) < amount) return 0L;
        return storage.insert(key, amount, Actionable.MODULATE, IActionSource.empty());
    }

    /**
     * 从绑定该玩家的在线 AE 网络抽取物品（供山海商店货币 ATM 复用）。
     * extract 天然不超抽，直接 MODULATE 取返回值即实抽量（可部分成交，网络不足则抽多少算多少）。
     * @return 实际抽出量（0 = 无可用绑定源 / 网络无此物）
     */
    public static long extractForPlayer(ServerPlayer player, AEKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;
        MEStorage storage = findBoundStorage(player);
        if (storage == null) return 0L;
        return storage.extract(key, amount, Actionable.MODULATE, IActionSource.empty());
    }

    /** 模拟绑定网络可抽取量（SIMULATE，不改动网络）；供兑换前算可成交量（尤其流体，无背包容器）。 */
    public static long availableForPlayer(ServerPlayer player, AEKey key) {
        if (key == null) return 0L;
        MEStorage storage = findBoundStorage(player);
        if (storage == null) return 0L;
        return storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, IActionSource.empty());
    }
}
