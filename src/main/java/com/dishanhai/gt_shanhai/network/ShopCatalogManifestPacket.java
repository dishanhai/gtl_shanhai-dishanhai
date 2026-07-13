package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** 服务端主动推送的山海商店目录结构清单。 */
public final class ShopCatalogManifestPacket {

    private static final long STALE_MANIFEST_INTERVAL_NANOS = 500_000_000L;
    private static final long CHUNK_WINDOW_NANOS = 1_000_000_000L;
    private static final int MAX_CHUNK_REQUESTS_PER_WINDOW = 128;
    private static final Map<ServerPlayer, RateLimitState> PLAYER_RATE_LIMITS = new WeakHashMap<>();

    /** 纯时间/版本状态；不持有玩家引用，便于 WeakHashMap 回收离线玩家。 */
    public static final class RateLimitState {
        private boolean manifestSeen;
        private long manifestServerRevision;
        private long manifestClientRevision;
        private long lastManifestNanos;
        private boolean chunkWindowStarted;
        private long chunkWindowStartNanos;
        private int chunkRequests;

        public boolean allowManifest(long serverRevision, long clientRevision, long nowNanos) {
            boolean pairChanged = !manifestSeen
                    || manifestServerRevision != serverRevision
                    || manifestClientRevision != clientRevision;
            if (!pairChanged && nowNanos >= lastManifestNanos
                    && nowNanos - lastManifestNanos < STALE_MANIFEST_INTERVAL_NANOS) {
                return false;
            }
            manifestSeen = true;
            manifestServerRevision = serverRevision;
            manifestClientRevision = clientRevision;
            lastManifestNanos = nowNanos;
            return true;
        }

        public boolean allowChunk(long nowNanos) {
            if (!chunkWindowStarted || nowNanos < chunkWindowStartNanos
                    || nowNanos - chunkWindowStartNanos >= CHUNK_WINDOW_NANOS) {
                chunkWindowStarted = true;
                chunkWindowStartNanos = nowNanos;
                chunkRequests = 1;
                return true;
            }
            if (chunkRequests >= MAX_CHUNK_REQUESTS_PER_WINDOW) return false;
            chunkRequests++;
            return true;
        }
    }

    private final ShopCatalogManifest manifest;

    public ShopCatalogManifestPacket(ShopCatalogManifest manifest) {
        this.manifest = manifest == null ? ShopCatalogManifest.empty() : manifest;
    }

    public ShopCatalogManifestPacket(FriendlyByteBuf buf) {
        this(ShopCatalogCodecs.readManifest(buf));
    }

    public void encode(FriendlyByteBuf buf) {
        ShopCatalogCodecs.writeManifest(buf, manifest);
    }

    public ShopCatalogManifest manifest() {
        return manifest;
    }

    public static void handle(ShopCatalogManifestPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> applyClient(packet));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyClient(ShopCatalogManifestPacket packet) {
        boolean changed = com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog
                .applyManifest(packet.manifest);
        if (!changed) return;
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.gui.screens.Screen screen = minecraft.screen;
        if (screen instanceof com.dishanhai.gt_shanhai.client.gui.shop.ShopScreen) {
            screen.resize(minecraft, screen.width, screen.height);
        }
    }

    public static void sendTo(ServerPlayer player, ShopCatalogManifest manifest) {
        if (player == null) return;
        ShanhaiNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new ShopCatalogManifestPacket(manifest));
    }

    /** stale 自愈专用；同一玩家和 revision 对在 500ms 内最多回推一次。 */
    public static boolean sendLatestIfAllowed(ServerPlayer player, ShopCatalogManifest manifest,
                                              long clientRevision) {
        if (player == null || manifest == null) return false;
        RateLimitState state = PLAYER_RATE_LIMITS.computeIfAbsent(player, ignored -> new RateLimitState());
        if (!state.allowManifest(manifest.revision(), clientRevision, System.nanoTime())) return false;
        sendTo(player, manifest);
        return true;
    }

    /** 客户端目录分块请求固定窗口限流；调用方必须位于服务端 enqueueWork 内。 */
    public static boolean allowChunkRequest(ServerPlayer player) {
        if (player == null) return false;
        RateLimitState state = PLAYER_RATE_LIMITS.computeIfAbsent(player, ignored -> new RateLimitState());
        return state.allowChunk(System.nanoTime());
    }

    /** 启动期服务器尚未存在时安全跳过。 */
    public static void broadcast(ShopCatalogManifest manifest) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player, manifest);
        }
    }
}
