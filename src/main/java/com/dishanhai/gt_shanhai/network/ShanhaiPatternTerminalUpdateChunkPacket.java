package com.dishanhai.gt_shanhai.network;

import appeng.core.sync.BasePacketHandler.PacketTypes;
import appeng.core.sync.packets.PatternAccessTerminalPacket;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.gui.terminal.ShanhaiPatternManagementScreen;
import io.netty.buffer.Unpooled;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

/** 无线样板管理终端的大包传输。客户端重组后仍使用 AE2 原有的全量/增量处理。 */
public record ShanhaiPatternTerminalUpdateChunkPacket(int menuId, int offset, int totalBytes, byte[] data) {
    public static final int MAX_NATIVE_BYTES = 1024 * 1024;
    private static final int CHUNK_BYTES = MAX_NATIVE_BYTES - 128;
    private static final int MAX_TOTAL_BYTES = 32 * MAX_NATIVE_BYTES;

    public ShanhaiPatternTerminalUpdateChunkPacket {
        if (menuId < 0 || totalBytes <= MAX_NATIVE_BYTES || totalBytes > MAX_TOTAL_BYTES
                || offset < 0 || offset >= totalBytes || offset % CHUNK_BYTES != 0
                || data.length != Math.min(CHUNK_BYTES, totalBytes - offset)) {
            throw new IllegalArgumentException("Invalid pattern terminal update chunk");
        }
    }

    public static void send(ServerPlayer player, int menuId, FriendlyByteBuf payload) {
        if (payload.readableBytes() > MAX_TOTAL_BYTES) {
            GTDishanhaiMod.LOGGER.warn("Pattern terminal update exceeds {} bytes, closing menu {}",
                    MAX_TOTAL_BYTES, menuId);
            player.closeContainer();
            return;
        }
        split(menuId, payload, chunk -> ShanhaiNetwork.CHANNEL.sendTo(
                chunk, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT));
    }

    static void split(int menuId, FriendlyByteBuf payload, Consumer<ShanhaiPatternTerminalUpdateChunkPacket> send) {
        int total = payload.readableBytes();
        for (int offset = 0; offset < total; offset += CHUNK_BYTES) {
            byte[] data = new byte[Math.min(CHUNK_BYTES, total - offset)];
            payload.getBytes(payload.readerIndex() + offset, data);
            send.accept(new ShanhaiPatternTerminalUpdateChunkPacket(menuId, offset, total, data));
        }
    }

    public static void encode(ShanhaiPatternTerminalUpdateChunkPacket chunk, FriendlyByteBuf buffer) {
        buffer.writeVarInt(chunk.menuId);
        buffer.writeVarInt(chunk.offset);
        buffer.writeVarInt(chunk.totalBytes);
        buffer.writeByteArray(chunk.data);
    }

    public static ShanhaiPatternTerminalUpdateChunkPacket decode(FriendlyByteBuf buffer) {
        return new ShanhaiPatternTerminalUpdateChunkPacket(buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readByteArray(CHUNK_BYTES));
    }

    public static void handle(ShanhaiPatternTerminalUpdateChunkPacket chunk,
            Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var connection = context.getNetworkManager();
        context.enqueueWork(() -> applyClient(chunk, connection));
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShanhaiPatternTerminalUpdateChunkPacket chunk, Connection connection) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        var listener = minecraft.getConnection();
        if (player == null || listener == null || listener.getConnection() != connection
                || !(minecraft.screen instanceof ShanhaiPatternManagementScreen screen)
                || player.containerMenu != screen.getMenu() || screen.getMenu().containerId != chunk.menuId) {
            return;
        }
        byte[] complete = screen.acceptPatternUpdateChunk(chunk);
        if (complete == null) return;

        PatternAccessTerminalPacket update;
        try {
            update = decodeUpdate(complete);
        } catch (RuntimeException e) {
            GTDishanhaiMod.LOGGER.warn("Invalid reassembled pattern terminal update, closing menu {}", chunk.menuId, e);
            player.closeContainer();
            return;
        }
        update.clientPacketData(player);
    }

    static PatternAccessTerminalPacket decodeUpdate(byte[] complete) {
        // 单槽可超过默认 2 MiB。重组后的槽位共用一个额度，限制复杂 NBT 的总内存开销。
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(complete)) {
            private final NbtAccounter accounter = new NbtAccounter(2L * MAX_TOTAL_BYTES);

            @Override
            public CompoundTag readNbt() {
                return readNbt(accounter);
            }
        };
        try {
            if (buffer.readInt() != PacketTypes.PATTERN_PROVIDER_UPDATE.getPacketId()) {
                throw new IllegalArgumentException("Invalid reassembled pattern terminal packet type");
            }
            var packet = new PatternAccessTerminalPacket(buffer);
            if (buffer.isReadable()) {
                throw new IllegalArgumentException("Unexpected trailing pattern terminal payload");
            }
            return packet;
        } finally {
            buffer.release();
        }
    }

    /** 单个终端画面只保留一个传输，关闭画面后随画面释放。 */
    public static final class Assembler {
        private byte[] pending;
        private int nextOffset;

        public byte[] accept(ShanhaiPatternTerminalUpdateChunkPacket chunk) {
            if (chunk.offset == 0) {
                pending = new byte[chunk.totalBytes];
                nextOffset = 0;
            }
            if (pending == null || pending.length != chunk.totalBytes || nextOffset != chunk.offset) {
                pending = null;
                return null;
            }
            System.arraycopy(chunk.data, 0, pending, nextOffset, chunk.data.length);
            nextOffset += chunk.data.length;
            if (nextOffset < pending.length) return null;

            byte[] complete = pending;
            pending = null;
            return complete;
        }
    }
}
