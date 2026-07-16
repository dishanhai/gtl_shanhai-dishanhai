package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.WalletItem;
import com.dishanhai.gt_shanhai.common.shop.CurrencyRateConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.common.shop.WalletAccountAPI;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.math.BigInteger;
import java.util.function.Supplier;

/**
 * 货币 ATM 操作包（C→S）：单币提交 / 币种兑换 / AE 抽取。
 *
 * <p>服务端校验发包玩家背包/饰品栏任意位置是否携带钱包 {@link WalletItem#isCarrying}，
 * 按 {@link Op} 派发到 {@link ShopPurchase} 的对应结算方法，账户余额走玩家 UUID 账本。</p>
 */
public class CurrencyActionPacket {

    public enum Op { DEPOSIT, EXCHANGE, AE_EXTRACT, TO_DIGITAL, FROM_DIGITAL, WITHDRAW }

    private final Op op;
    private final ResourceLocation currency;   // 源/操作币种
    private final ResourceLocation target;     // EXCHANGE 目标币种（其余忽略）
    private final long amount;                 // EXCHANGE/AE_EXTRACT 数量（DEPOSIT 忽略）

    public CurrencyActionPacket(Op op, ResourceLocation currency, ResourceLocation target, long amount) {
        this.op = op;
        this.currency = currency == null ? new ResourceLocation("minecraft:air") : currency;
        this.target = target == null ? new ResourceLocation("minecraft:air") : target;
        this.amount = Math.max(0L, amount);
    }

    public CurrencyActionPacket(FriendlyByteBuf buf) {
        this.op = buf.readEnum(Op.class);
        this.currency = buf.readResourceLocation();
        this.target = buf.readResourceLocation();
        this.amount = buf.readVarLong();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(op);
        buf.writeResourceLocation(currency);
        buf.writeResourceLocation(target);
        buf.writeVarLong(amount);
    }

    public static void handle(CurrencyActionPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            apply(pkt, player);
        });
        context.setPacketHandled(true);
    }

    private static void apply(CurrencyActionPacket pkt, ServerPlayer player) {
        if (!WalletItem.isCarrying(player)) {
            player.sendSystemMessage(Component.literal("§c[货币中心] 需持有山海钱包（背包/饰品栏任意位置都行）"));
            return;
        }
        switch (pkt.op) {
            case DEPOSIT -> {
                long got = ShopPurchase.depositOne(player, pkt.currency);
                player.sendSystemMessage(got > 0L
                        ? Component.literal("§b[货币中心] §a已提交 §f" + ShopPurchase.formatCount(got) + " §a枚 "
                            + ShopPurchase.coinName(pkt.currency))
                        : Component.literal("§c[货币中心] 背包里没有 " + ShopPurchase.coinName(pkt.currency)));
            }
            case EXCHANGE -> {
                ShopPurchase.ExchangeResult r = ShopPurchase.exchange(player, pkt.currency, pkt.target, pkt.amount);
                player.sendSystemMessage(r.gained() > 0L
                        ? Component.literal("§b[货币中心] §a已兑换 §f" + ShopPurchase.formatCount(r.consumed()) + " "
                            + ShopPurchase.coinName(pkt.currency) + " §7→ §f" + ShopPurchase.formatCount(r.gained()) + " "
                            + ShopPurchase.coinName(pkt.target))
                        : Component.literal("§c[货币中心] 兑换失败（余额不足 / 数量太小 / 币值未配置）"));
            }
            case AE_EXTRACT -> {
                long got = ShopPurchase.aeExtractCoin(player, pkt.currency, pkt.amount);
                player.sendSystemMessage(got > 0L
                        ? Component.literal("§b[货币中心] §a已从 AE 抽取 §f" + ShopPurchase.formatCount(got) + " §a枚 "
                            + ShopPurchase.coinName(pkt.currency) + " §7入钱包")
                        : Component.literal("§c[货币中心] AE 抽取失败（无绑定在线 AE 网络 / 网络无此币）"));
            }
            case TO_DIGITAL -> {
                // pkt.amount 语义 = 想要转出的源币数量，实际可能因余额不足被 toDigital 内部封顶；
                // 消耗量直接拿转换前后的账户余额差（BigInteger 精确，不靠 gained÷币值反推——
                // gained 是显示用 long，数额巨大到截断 Long.MAX 时反推除法会算错真实消耗量）
                BigInteger before = WalletAccountAPI.getCurrency(player.getServer(), player.getUUID(), pkt.currency);
                long gained = ShopPurchase.toDigital(player, pkt.currency, pkt.amount);
                if (gained > 0L) {
                    BigInteger after = WalletAccountAPI.getCurrency(player.getServer(), player.getUUID(), pkt.currency);
                    BigInteger consumedBig = before.subtract(after);
                    long consumed = consumedBig.bitLength() < 63 ? consumedBig.longValue() : Long.MAX_VALUE;
                    player.sendSystemMessage(Component.literal("§b[货币中心] §a已把 §f" + ShopPurchase.formatCount(consumed) + " "
                            + ShopPurchase.coinName(pkt.currency) + " §a转成 §e" + ShopPurchase.formatCount(gained) + " 星火"));
                } else {
                    player.sendSystemMessage(Component.literal("§c[货币中心] 转星火失败（余额不足 / 币值未配置）"));
                }
            }
            case FROM_DIGITAL -> {
                // pkt.amount 语义 = 想要的目标币数量（非花的星火数），花费 = amount × 币值
                long coins = ShopPurchase.fromDigital(player, pkt.currency, pkt.amount);
                if (coins > 0L) {
                    long value = CurrencyRateConfig.getValue(pkt.currency);
                    BigInteger spentBig = BigInteger.valueOf(coins).multiply(BigInteger.valueOf(value));
                    long spent = spentBig.bitLength() < 63 ? spentBig.longValue() : Long.MAX_VALUE;
                    player.sendSystemMessage(Component.literal("§b[货币中心] §a已花 §e" + ShopPurchase.formatCount(spent) + " 星火 §a换得 §f"
                            + ShopPurchase.formatCount(coins) + " " + ShopPurchase.coinName(pkt.currency)));
                } else {
                    player.sendSystemMessage(Component.literal("§c[货币中心] 星火转出失败（星火余额不足 / 币值未配置）"));
                }
            }
            case WITHDRAW -> {
                long got = ShopPurchase.withdraw(player, pkt.currency, pkt.amount);
                player.sendSystemMessage(got > 0L
                        ? Component.literal("§b[货币中心] §a已提取 §f" + ShopPurchase.formatCount(got) + " §a枚 "
                            + ShopPurchase.coinName(pkt.currency) + " §7到背包")
                        : Component.literal("§c[货币中心] 提取失败（钱包余额不足）"));
            }
        }
        WalletAccountAPI.sync(player); // 回推账户快照，客户端界面即时刷新
    }
}
