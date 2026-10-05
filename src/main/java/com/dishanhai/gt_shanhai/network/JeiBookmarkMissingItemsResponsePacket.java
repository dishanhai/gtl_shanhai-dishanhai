package com.dishanhai.gt_shanhai.network;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.fml.DistExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** JEI 收藏缺失项结果（服务端 -> 客户端）。 */
public final class JeiBookmarkMissingItemsResponsePacket {

    static final int MAX_RECIPE_ID_LENGTH = 512;
    static final int MAX_ENTRIES = JeiBookmarkMissingItemsRequestPacket.MAX_ENTRIES;

    private final String recipeId;
    private final List<GenericStack> missing;

    public JeiBookmarkMissingItemsResponsePacket(String recipeId, List<GenericStack> missing) {
        this.recipeId = recipeId == null ? "" : recipeId;
        this.missing = missing == null ? List.of() : List.copyOf(missing);
    }

    public JeiBookmarkMissingItemsResponsePacket(FriendlyByteBuf buffer) {
        this.recipeId = buffer.readUtf(MAX_RECIPE_ID_LENGTH);
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new io.netty.handler.codec.DecoderException(
                    "JeiBookmarkMissingItemsResponsePacket entry count out of range: " + count);
        }
        List<GenericStack> decoded = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            GenericStack stack = GenericStack.readBuffer(buffer);
            if (stack != null && stack.what() != null && stack.amount() > 0) {
                decoded.add(stack);
            }
        }
        this.missing = List.copyOf(decoded);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(recipeId, MAX_RECIPE_ID_LENGTH);
        buffer.writeVarInt(Math.min(MAX_ENTRIES, missing.size()));
        for (int i = 0; i < missing.size() && i < MAX_ENTRIES; i++) {
            GenericStack.writeBuffer(missing.get(i), buffer);
        }
    }

    public String recipeId() {
        return recipeId;
    }

    public List<GenericStack> missing() {
        return missing;
    }

    public static void handle(JeiBookmarkMissingItemsResponsePacket packet,
            Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> () -> handleClient(packet)));
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(JeiBookmarkMissingItemsResponsePacket packet) {
        List<net.minecraft.world.item.ItemStack> items = new ArrayList<>();
        List<net.minecraftforge.fluids.FluidStack> fluids = new ArrayList<>();
        for (GenericStack stack : packet.missing) {
            int amount = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, stack.amount()));
            if (stack.what() instanceof AEItemKey itemKey) {
                items.add(itemKey.toStack(amount));
            } else if (stack.what() instanceof AEFluidKey fluidKey) {
                fluids.add(fluidKey.toStack(amount));
            }
        }
        int added = com.dishanhai.gt_shanhai.jei.JeiBookmarkBridge.addItemStacks(items)
                + com.dishanhai.gt_shanhai.jei.JeiBookmarkBridge.addFluidStacks(fluids);
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player != null) {
            String messageKey = packet.missing.isEmpty()
                    ? "message.gt_shanhai.jei.bookmark.no_items"
                    : (added > 0
                            ? "message.gt_shanhai.jei.bookmark.added"
                            : "message.gt_shanhai.jei.bookmark.already_bookmarked");
            minecraft.player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            messageKey,
                            packet.missing.size(), added),
                    false);
        }
        com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER.info(
                "[JEIBookmarkDiag] response recipe={} missing={} added={}",
                packet.recipeId, packet.missing.size(), added);
    }
}
