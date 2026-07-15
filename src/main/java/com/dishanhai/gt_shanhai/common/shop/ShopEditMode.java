package com.dishanhai.gt_shanhai.common.shop;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商店「目录编辑」模式的按玩家开关。仅内存态，服务器重启后自动清空（默认关闭，更安全）。
 *
 * <p>由指令 {@code /山海 商店 编辑} 切换 —— 该指令挂在需 permission=2 的 {@code /山海} 下，
 * 与 {@link ShopCheatMode} 同一套结构。有编辑权（{@link ShopEditPermission#canEdit}）的玩家
 * 还须额外开启此开关，才能新增/编辑/删除商品条目（见 {@link ShopEditPermission#canEditCatalog}，
 * 服务端校验见 {@link com.dishanhai.gt_shanhai.network.ShopEditPacket} 的 ADD/EDIT 与
 * {@link com.dishanhai.gt_shanhai.network.ShopActionPacket} 的 DELETE）；商店设置、隐藏切换
 * （见 {@link com.dishanhai.gt_shanhai.network.ShopToggleHiddenPacket}）、排序等非破坏性操作
 * 不受此开关限制，仍只看 canEdit。</p>
 */
public final class ShopEditMode {

    private ShopEditMode() {}

    /** 已开启编辑模式的玩家 UUID 集（内存态）。 */
    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();

    /** 切换指定玩家的编辑模式，返回切换后的状态（true=已开启）。 */
    public static boolean toggle(UUID uuid) {
        if (uuid == null) return false;
        if (ENABLED.remove(uuid)) return false;
        ENABLED.add(uuid);
        return true;
    }

    /** 该玩家是否处于编辑模式。 */
    public static boolean isEnabled(UUID uuid) {
        return uuid != null && ENABLED.contains(uuid);
    }
}
