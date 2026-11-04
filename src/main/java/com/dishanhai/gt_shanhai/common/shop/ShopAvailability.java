package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 山海商店全局禁用入口；个人禁用由客户端自己的 config 保存。 */
public final class ShopAvailability {
    private static final Set<UUID> PERSONAL_DISABLED = ConcurrentHashMap.newKeySet();
    private ShopAvailability() {}

    public static boolean isDisabled() {
        return DShanhaiConfig.COMMON.shopDisabled.get();
    }

    public static boolean isDisabled(boolean globalDisabled, boolean personalDisabled) {
        return globalDisabled || personalDisabled;
    }

    public static boolean isPersonallyDisabled(UUID playerId) { return PERSONAL_DISABLED.contains(playerId); }

    public static void setPersonalDisabled(UUID playerId, boolean disabled) {
        if (disabled) PERSONAL_DISABLED.add(playerId);
        else PERSONAL_DISABLED.remove(playerId);
    }

    public static void clearPersonal(UUID playerId) { PERSONAL_DISABLED.remove(playerId); }
    public static void clearPersonal() { PERSONAL_DISABLED.clear(); }

    public static boolean isDisabled(ServerPlayer player) {
        return isDisabled() || player != null && isPersonallyDisabled(player.getUUID());
    }

    public static boolean requireEnabled(ServerPlayer player) {
        if (!isDisabled(player)) return true;
        if (player != null) player.sendSystemMessage(Component.literal(isDisabled()
                ? "§c[山海商店] 商店已被 OP 全局禁用，请在商店设置中恢复"
                : "§c[山海商店] 你已禁用个人商店，请在商店设置中恢复"));
        return false;
    }
}
