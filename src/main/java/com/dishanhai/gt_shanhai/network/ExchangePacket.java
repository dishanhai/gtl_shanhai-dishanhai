package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.WalletItem;
import com.dishanhai.gt_shanhai.common.shop.ExchangeConfig;
import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopAeNetwork;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI;

import appeng.api.stacks.AEFluidKey;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 兑换动作包（C→S）：执行某 {@link ExchangeEntry}，正向/反向 × times。
 *
 * <p>条目两侧对称（星火 + 多物品/流体）。正向：付出 cost、得到 result；反向：付出 result、得到 cost。
 * 付出侧：星火扣数字余额、物品从背包扣、流体从绑定 AE 抽；得到侧：星火加余额、物品走三级投递、流体注 AE。
 * 一次性按可成交次数结算，绝不逐次循环；得到侧流体先 SIMULATE 预检 AE 可注入，防吞。</p>
 */
public class ExchangePacket {

    private final String entryId;
    private final long times;
    private final boolean reverse;
    private final boolean aeMode;   // 得到侧物品投递是否优先注入 AE

    public ExchangePacket(String entryId, long times, boolean reverse, boolean aeMode) {
        this.entryId = entryId == null ? "" : entryId;
        this.times = Math.max(1L, times);
        this.reverse = reverse;
        this.aeMode = aeMode;
    }

    public ExchangePacket(FriendlyByteBuf buf) {
        this.entryId = buf.readUtf();
        this.times = buf.readVarLong();
        this.reverse = buf.readBoolean();
        this.aeMode = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(entryId);
        buf.writeVarLong(times);
        buf.writeBoolean(reverse);
        buf.writeBoolean(aeMode);
    }

    public static void handle(ExchangePacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(ExchangePacket pkt, ServerPlayer player) {
        if (!WalletItem.isCarrying(player)) {
            player.sendSystemMessage(Component.literal("§c[兑换] 需持有山海钱包（背包/饰品栏任意位置都行）"));
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ExchangeEntry entry = ExchangeConfig.byId(pkt.entryId);
        if (entry == null || !entry.isValid()) {
            player.sendSystemMessage(Component.literal("§c[兑换] 条目不存在或无效"));
            return;
        }
        // 正向：付 cost 得 result；反向：付 result 得 cost
        ExchangeEntry.Side pay = pkt.reverse ? entry.getResult() : entry.getCost();
        ExchangeEntry.Side get = pkt.reverse ? entry.getCost() : entry.getResult();
        execute(server, player, entry, pay, get, pkt.times, pkt.aeMode, pkt.reverse);
    }

    private static void execute(MinecraftServer server, ServerPlayer player, ExchangeEntry entry,
                                ExchangeEntry.Side pay, ExchangeEntry.Side get, long times, boolean aeMode, boolean reverse) {
        UUID uuid = player.getUUID();

        // 1) 可成交次数 = min(times, 付出侧各约束)
        long doable = times;
        if (pay.spark.signum() > 0) {
            BigInteger bySpark = WalletAccountAPI.getDigital(server, uuid).divide(pay.spark);
            doable = Math.min(doable, bySpark.bitLength() < 63 ? bySpark.longValue() : Long.MAX_VALUE);
        }
        for (ExchangeEntry.Ingredient in : pay.ingredients) {
            long available;
            if (in.isFluid) {
                Fluid fluid = ForgeRegistries.FLUIDS.getValue(in.id);
                if (fluid == null) { player.sendSystemMessage(Component.literal("§c[兑换] 未知流体 " + in.id)); return; }
                available = ShopAeNetwork.availableForPlayer(player, AEFluidKey.of(fluid));
            } else {
                Item item = ForgeRegistries.ITEMS.getValue(in.id);
                if (item == null) { player.sendSystemMessage(Component.literal("§c[兑换] 未知物品 " + in.id)); return; }
                available = ShopPurchase.countItem(player, item, in.nbt());
            }
            doable = Math.min(doable, available / in.count);
            if (doable <= 0L) break;
        }
        if (doable <= 0L) {
            player.sendSystemMessage(Component.literal("§c[兑换] 付出不足（物品在背包 / 流体需在绑定 AE / 星火不足）"));
            return;
        }

        // 2) 防吞：得到侧要吐流体的，先确认绑定 AE 能全额收下
        for (ExchangeEntry.Ingredient in : get.ingredients) {
            if (!in.isFluid) continue;
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(in.id);
            if (fluid == null) { player.sendSystemMessage(Component.literal("§c[兑换] 未知流体 " + in.id)); return; }
            BigInteger needBig = BigInteger.valueOf(in.count).multiply(BigInteger.valueOf(doable));
            if (needBig.bitLength() >= 63
                    || !ShopAeNetwork.canInjectForPlayer(player, AEFluidKey.of(fluid), needBig.longValue())) {
                player.sendSystemMessage(Component.literal("§c[兑换] AE 网络无法接收流体 " + in.id + "（需绑定在线 AE 网络且容量足够）"));
                return;
            }
        }

        // 3) 扣付出侧（doable 已被付出侧约束，各项必成）
        if (pay.spark.signum() > 0) {
            WalletAccountAPI.tryDeductDigital(server, uuid, pay.spark.multiply(BigInteger.valueOf(doable)));
        }
        for (ExchangeEntry.Ingredient in : pay.ingredients) {
            long need = in.count * doable; // ≤ available，不溢出
            if (in.isFluid) {
                Fluid fluid = ForgeRegistries.FLUIDS.getValue(in.id);
                ShopAeNetwork.extractForPlayer(player, AEFluidKey.of(fluid), need);
            } else {
                Item item = ForgeRegistries.ITEMS.getValue(in.id);
                ShopPurchase.removeItems(player, item, (int) need, in.nbt());
            }
        }

        // 4) 发得到侧
        if (get.spark.signum() > 0) {
            WalletAccountAPI.addDigital(server, uuid, get.spark.multiply(BigInteger.valueOf(doable)));
        }
        for (ExchangeEntry.Ingredient in : get.ingredients) {
            BigInteger totalBig = BigInteger.valueOf(in.count).multiply(BigInteger.valueOf(doable));
            if (in.isFluid) {
                Fluid fluid = ForgeRegistries.FLUIDS.getValue(in.id);
                ShopAeNetwork.injectForPlayer(player, AEFluidKey.of(fluid), totalBig.longValue());
            } else {
                ItemStack unit = in.makeUnitStack();
                if (!unit.isEmpty()) ShopPurchase.deliverItems(player, unit, totalBig, aeMode);
            }
        }

        WalletAccountAPI.sync(player);
        String arrow = reverse ? "§7（反向）" : "";
        player.sendSystemMessage(Component.literal("§b[兑换] §a" + entry.displayName() + " ×"
                + ShopPurchase.formatCount(doable) + " §a成功 " + arrow));
    }
}
