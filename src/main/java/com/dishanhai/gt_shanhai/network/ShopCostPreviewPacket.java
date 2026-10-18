package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 花费预览响应（S→C）：{@link ShopCostPreviewRequestPacket} 的回包，把
 * {@link ShopPurchase#previewHave} 算出的只读余量推给客户端，写入 {@code ClientCostPreview} 缓存。
 * items/fluids 顺序对齐服务端 {@code ShopCost#items()}/{@code #fluids()}——客户端渲染花费预览格时按
 * 同一份 ShopCost 同序遍历取值，天然对齐，不用额外传物品 ID。
 * coinOrder/itemOrder/fluidOrder 与余量列表对齐：0 未核对，1 有样板，2 无样板，3 未绑定网络。
 */
public class ShopCostPreviewPacket {

    private static final int MAX_ENTRIES = 256;

    private final long entryKey;
    private final boolean aeMode;
    private final Map<ResourceLocation, BigInteger> coins;
    private final List<Long> items;
    private final List<Long> fluids;
    private final List<Integer> coinOrder;
    private final List<Integer> itemOrder;
    private final List<Integer> fluidOrder;

    public ShopCostPreviewPacket(long entryKey, boolean aeMode, ShopPurchase.CostPreview preview) {
        this.entryKey = entryKey;
        this.aeMode = aeMode;
        this.coins = preview.coins();
        this.items = preview.items();
        this.fluids = preview.fluids();
        this.coinOrder = preview.coinOrder() == null ? List.of() : preview.coinOrder();
        this.itemOrder = preview.itemOrder() == null ? List.of() : preview.itemOrder();
        this.fluidOrder = preview.fluidOrder() == null ? List.of() : preview.fluidOrder();
    }

    public ShopCostPreviewPacket(FriendlyByteBuf buf) {
        this.entryKey = buf.readLong();
        this.aeMode = buf.readBoolean();
        int cn = buf.readVarInt();
        if (cn < 0 || cn > MAX_ENTRIES) throw new DecoderException("Invalid cost preview coins size: " + cn);
        Map<ResourceLocation, BigInteger> coinsMap = new LinkedHashMap<>();
        for (int i = 0; i < cn; i++) {
            ResourceLocation id = buf.readResourceLocation();
            byte[] bytes = buf.readByteArray();
            coinsMap.put(id, bytes.length == 0 ? BigInteger.ZERO : new BigInteger(bytes));
        }
        this.coins = coinsMap;
        this.items = readLongList(buf);
        this.fluids = readLongList(buf);
        this.coinOrder = readStates(buf);
        this.itemOrder = readStates(buf);
        this.fluidOrder = readStates(buf);
    }

    private static List<Long> readLongList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_ENTRIES) throw new DecoderException("Invalid cost preview list size: " + n);
        List<Long> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readVarLong());
        return list;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(entryKey);
        buf.writeBoolean(aeMode);
        buf.writeVarInt(coins.size());
        for (Map.Entry<ResourceLocation, BigInteger> e : coins.entrySet()) {
            buf.writeResourceLocation(e.getKey());
            buf.writeByteArray(e.getValue() == null ? new byte[0] : e.getValue().toByteArray());
        }
        writeLongList(buf, items);
        writeLongList(buf, fluids);
        writeStates(buf, coinOrder);
        writeStates(buf, itemOrder);
        writeStates(buf, fluidOrder);
    }

    private static List<Integer> readStates(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_ENTRIES) throw new DecoderException("Invalid cost preview order size: " + n);
        List<Integer> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(buf.readByte() & 0xFF);
        return list;
    }

    private static void writeStates(FriendlyByteBuf buf, List<Integer> states) {
        buf.writeVarInt(states.size());
        for (Integer state : states) buf.writeByte(state == null ? 0 : state);
    }

    private static void writeLongList(FriendlyByteBuf buf, List<Long> list) {
        buf.writeVarInt(list.size());
        for (Long v : list) buf.writeVarLong(v == null ? 0L : v);
    }

    public static void handle(ShopCostPreviewPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> handleClient(pkt));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(ShopCostPreviewPacket pkt) {
        com.dishanhai.gt_shanhai.client.shop.ClientCostPreview.apply(pkt.entryKey, pkt.aeMode, pkt.coins, pkt.items, pkt.fluids,
                pkt.coinOrder, pkt.itemOrder, pkt.fluidOrder);
    }
}
