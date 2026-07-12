package com.dishanhai.gt_shanhai.common.shop;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商店「直接获取（作弊）」模式的按玩家开关。仅内存态，服务器重启后自动清空（默认关闭，更安全）。
 *
 * <p>由指令 {@code /山海 商店 作弊} 切换 —— 该指令挂在需 permission=2 的 {@code /山海} 下，
 * 故仅作弊/管理员可开。开启后该玩家在商店点「确认购买」将<b>不扣任何成本、直接到手</b>
 * （见 {@link ShopPurchase#giveBulk}）。</p>
 */
public final class ShopCheatMode {

    private ShopCheatMode() {}

    /** 已开启直取模式的玩家 UUID 集（内存态）。 */
    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();

    /** 切换指定玩家的直取模式，返回切换后的状态（true=已开启）。 */
    public static boolean toggle(UUID uuid) {
        if (uuid == null) return false;
        if (ENABLED.remove(uuid)) return false;
        ENABLED.add(uuid);
        return true;
    }

    /** 该玩家是否处于直取模式。 */
    public static boolean isEnabled(UUID uuid) {
        return uuid != null && ENABLED.contains(uuid);
    }
}
