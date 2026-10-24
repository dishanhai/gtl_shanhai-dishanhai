package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.item.DShanhaiItems;
import com.dishanhai.gt_shanhai.common.item.ShanhaiUltimateTerminalConfig;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalCraftingManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.util.Lazy;
import net.minecraftforge.network.NetworkEvent;
import org.gtlcore.gtlcore.common.block.BlockMap;
import java.util.function.Supplier;

/** 山海終極終端：玻璃設定介面的單項設定寫回（客戶端至伺服器）。伺服器重新驗證手持物與數值。 */
public record UltimateTerminalConfigPacket(InteractionHand hand, Field field, int value, String text) {
    public enum Field { REPEAT, MIRROR, REPLACE, ABSOLUTE_REPLACE, AE_MODE, ORDER_MODE, NO_CHAMBER, DISMANTLE, MODULE_CHECK, REPLACEMENT }
    private static final int MAX_FAMILY = 32;

    public UltimateTerminalConfigPacket(FriendlyByteBuf buffer) {
        this(buffer.readEnum(InteractionHand.class), buffer.readEnum(Field.class),
                buffer.readVarInt(), buffer.readUtf(MAX_FAMILY));
    }
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(hand); buffer.writeEnum(field); buffer.writeVarInt(value); buffer.writeUtf(text, MAX_FAMILY);
    }
    public static void handle(UltimateTerminalConfigPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> apply(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void apply(UltimateTerminalConfigPacket packet, ServerPlayer player) {
        if (player == null) return;
        ItemStack terminal = player.getItemInHand(packet.hand);
        if (!terminal.is(DShanhaiItems.ULTIMATE_TERMINAL.get())) return;
        boolean on = packet.value != 0;
        switch (packet.field) {
            case REPEAT -> ShanhaiUltimateTerminalConfig.setRepeatCount(terminal, packet.value);
            case MIRROR -> ShanhaiUltimateTerminalConfig.setMirrored(terminal, on);
            case REPLACE -> ShanhaiUltimateTerminalConfig.setReplaceMode(terminal, on);
            case ABSOLUTE_REPLACE -> ShanhaiUltimateTerminalConfig.setAbsoluteReplaceMode(terminal, on);
            case AE_MODE -> {
                ShanhaiUltimateTerminalConfig.setAeMode(terminal, on);
                if (!on) ShanhaiTerminalCraftingManager.clear(terminal);
            }
            case ORDER_MODE -> {
                ShanhaiUltimateTerminalConfig.setOrderMode(terminal, on);
                if (!on) ShanhaiTerminalCraftingManager.clear(terminal);
            }
            case NO_CHAMBER -> ShanhaiUltimateTerminalConfig.setNoChamberMode(terminal, on);
            case DISMANTLE -> ShanhaiUltimateTerminalConfig.setDismantleMode(terminal, on);
            case MODULE_CHECK -> ShanhaiUltimateTerminalConfig.setModuleCheckMode(terminal, on);
            case REPLACEMENT -> {
                Lazy<Block[]> tiers = BlockMap.tierBlockMap.get(packet.text);
                if (tiers == null || packet.value < 0 || packet.value >= tiers.get().length) return;
                ShanhaiUltimateTerminalConfig.setReplacement(terminal, packet.text, packet.value);
            }
        }
    }
}
