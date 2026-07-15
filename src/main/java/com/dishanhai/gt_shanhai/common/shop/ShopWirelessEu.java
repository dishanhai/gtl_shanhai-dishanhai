package com.dishanhai.gt_shanhai.common.shop;

import com.hepdd.gtmthings.api.misc.WirelessEnergyManager;

import net.minecraft.server.level.ServerPlayer;

import java.math.BigInteger;
import java.util.UUID;

/**
 * 商店 × 无线能源网络（gtladditions/gtmthings 电网系统）集成（山海署名）：把玩家绑定的
 * 无线 EU 网格余额接进商店的第 5 条成本通道（星火/币种/物品/流体之外），支持用电力直接
 * 兑换商品/星火。
 *
 * <p>底层是 gtmthings 的 {@link WirelessEnergyManager}，按 UUID 存取（内部会自动解析成 FTB 队伍
 * 共享池，见其 {@code TeamUtil.getTeamUUID}，本类无需关心）。故意只用 {@code getUserEU}/{@code setUserEU}
 * 这两个纯读写方法，不用 {@code addEUToGlobalEnergyMap}——后者在 gtladditions 的 mixin 覆写版本里
 * 会把传入的 {@code MetaMachine} 参数当 Caffeine 缓存 key，商店交易没有对应的机器方块上下文，传 null
 * 会直接 NPE 崩线程；get/set 两个方法未被覆写，纯 UUID 读写，安全。</p>
 *
 * <p>仅服务端调用；gtladditions 在 mods.toml 声明为强制依赖，它自身又依赖 gtmthings（mixin 进
 * 其内部类），故 gtmthings 传递性地保证随 gtladditions 一起存在，无需额外的软依赖判空守卫
 * （同本模组对 gtladditions 自身的既有引用方式一致）。</p>
 */
public final class ShopWirelessEu {

    private ShopWirelessEu() {}

    /** 读某玩家（或其所在 FTB 队伍）当前无线电网 EU 余额，无则 0。 */
    public static BigInteger getBalance(ServerPlayer player) {
        if (player == null) return BigInteger.ZERO;
        BigInteger v = WirelessEnergyManager.getUserEU(player.getUUID());
        return v == null ? BigInteger.ZERO : v;
    }

    public static BigInteger getBalance(UUID uuid) {
        if (uuid == null) return BigInteger.ZERO;
        BigInteger v = WirelessEnergyManager.getUserEU(uuid);
        return v == null ? BigInteger.ZERO : v;
    }

    /** 尝试扣除指定 EU（余额不足则不扣，返回 false）。 */
    public static boolean tryDeduct(ServerPlayer player, BigInteger cost) {
        if (cost == null || cost.signum() <= 0) return true;
        if (player == null) return false;
        UUID uuid = player.getUUID();
        BigInteger have = getBalance(uuid);
        if (have.compareTo(cost) < 0) return false;
        WirelessEnergyManager.setUserEU(uuid, have.subtract(cost));
        return true;
    }

    /** 增加指定 EU（出售商品换电力）。delta≤0 忽略。 */
    public static void add(ServerPlayer player, BigInteger delta) {
        if (player == null || delta == null || delta.signum() <= 0) return;
        UUID uuid = player.getUUID();
        WirelessEnergyManager.setUserEU(uuid, getBalance(uuid).add(delta));
    }
}
