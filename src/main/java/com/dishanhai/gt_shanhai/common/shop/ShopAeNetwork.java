package com.dishanhai.gt_shanhai.common.shop;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionHost;
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

    /**
     * 继承 {@link IActionHost}：两个现有实现（商店终端、FTBQ提交器）本来就通过
     * {@code DShanhaiAENetworkMachine → IGridConnectedMachine → IGridConnectedBlockEntity} 链路
     * 天然满足 {@code getActionableNode()}，这里显式声明只是把这个身份暴露给 {@link #findBoundHost}——
     * 自动合成下单要用真实机器身份构造 {@link IActionSource}，不能用匿名/纯玩家来源（见 ShopAutoCraft 注释，
     * 之前用匿名/无机器来源提交会被本模组自己的虚拟供给改写层判定为"看不见"，样板报自己缺失）。
     */
    public interface Provider extends IActionHost {
        boolean isOnline();
        /** 这台设备当前是否给该玩家提供 AE 网络（提交器按 FTBQ 队伍匹配，商店终端按放置者 UUID 匹配）。 */
        boolean servesPlayer(ServerPlayer player);
        /** 拿这台设备所在 AE 网络的存储；未连接网络返回 null。 */
        MEStorage storage();
        /** 拿这台设备所在 AE 网格（供一键下单缺口用 {@code ICraftingService} 自动合成）；未连接网络返回 null。 */
        IGrid grid();
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    public static void register(Provider provider) {
        if (provider != null) PROVIDERS.add(provider);
    }

    public static void unregister(Provider provider) {
        PROVIDERS.remove(provider);
    }

    /**
     * 该玩家当前是否有绑定的在线 AE 网络（商店终端/FTBQ提交器等 Provider 任一命中）。
     * 供商店 UI 校验「AE模式」开关——没有绑定源时禁止开启，避免玩家误以为交易会走 AE 实际却静默落地到别的交付方式。
     */
    public static boolean hasBoundNetwork(ServerPlayer player) {
        return findBoundStorage(player) != null;
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

    /** 模拟绑定网络还能收下多少（SIMULATE，不改动网络）；供流体商品购买前算「这次最多能买几份」，
     *  比 {@link #canInjectForPlayer} 的全有全无判定更细——网络还有余量但不够全额时，能按余量降量成交。 */
    public static long simulateInjectCapacityForPlayer(ServerPlayer player, AEKey key) {
        if (key == null) return 0L;
        MEStorage storage = findBoundStorage(player);
        if (storage == null) return 0L;
        return storage.insert(key, Long.MAX_VALUE, Actionable.SIMULATE, IActionSource.empty());
    }

    /** 遍历所有已注册的绑定源，返回第一个在线且匹配该玩家的 AE 网格（供 {@link ShopAutoCraft} 提交自动合成）。 */
    public static IGrid findBoundGrid(ServerPlayer player) {
        if (player == null) return null;
        for (Provider provider : PROVIDERS) {
            if (!provider.isOnline()) continue;
            if (!provider.servesPlayer(player)) continue;
            IGrid grid = provider.grid();
            if (grid != null) return grid;
        }
        return null;
    }

    /**
     * 同 {@link #findBoundGrid} 但返回提供网络的那台设备本身（作为 {@link IActionHost}），
     * 供 {@link ShopAutoCraft} 构造带机器身份的 {@link IActionSource#ofPlayer(net.minecraft.world.entity.player.Player, IActionHost)}——
     * 跟 AE2 原生 ME 终端（{@code PlayerSource(player, actionHost)}）用同一套构造方式，不能只给玩家身份不给机器。
     */
    public static IActionHost findBoundHost(ServerPlayer player) {
        if (player == null) return null;
        for (Provider provider : PROVIDERS) {
            if (!provider.isOnline()) continue;
            if (!provider.servesPlayer(player)) continue;
            if (provider.grid() != null) return provider;
        }
        return null;
    }
}
