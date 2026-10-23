package com.dishanhai.gt_shanhai.common.item.generator;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.network.PatternGeneratorOpenPacket;
import com.dishanhai.gt_shanhai.network.PatternGeneratorStatePacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 山海生成器會話登記表。只在伺服器主執行緒存取。 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID)
public final class PatternGeneratorSessions {
    private static final Map<UUID, PatternGeneratorSession> SESSIONS = new HashMap<>();
    private PatternGeneratorSessions() {}

    public static PatternGeneratorSession open(ServerPlayer player, InteractionHand hand) {
        PatternGeneratorSession previous = SESSIONS.remove(player.getUUID());
        if (previous != null) closeSession(previous);
        PatternGeneratorSession session;
        try {
            session = new PatternGeneratorSession(player, hand);
        } catch (PatternGeneratorCodec.ConfigurationTooLargeException oversized) {
            // 舊版已寫入的大 NBT 工具保持原樣，只明確拒絕開啟，避免無法編碼後鎖住畫面。
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "gt_shanhai.gui.pattern_generator.state_too_large"), true);
            return null;
        }
        SESSIONS.put(player.getUUID(), session);
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PatternGeneratorOpenPacket(hand, session.state()));
        return session;
    }

    public static PatternGeneratorSession get(ServerPlayer player) {
        PatternGeneratorSession session = SESSIONS.get(player.getUUID());
        return session != null && session.ownedBy(player) ? session : null;
    }

    public static void close(ServerPlayer player) {
        PatternGeneratorSession session = get(player);
        if (session == null) return;
        SESSIONS.remove(player.getUUID(), session);
        closeSession(session);
    }

    private static void closeSession(PatternGeneratorSession session) {
        session.close();
        ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(session::owner),
                new PatternGeneratorStatePacket(session.sessionId()));
    }

    public static void close(ServerPlayer player, UUID sessionId) {
        PatternGeneratorSession session = get(player);
        if (session != null && session.sessionId().equals(sessionId)) close(player);
    }

    @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        PatternGeneratorSession session = get(player);
        if (session != null) session.tick();
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) close(player);
    }

    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) close(player);
    }

    @SubscribeEvent public static void cloned(PlayerEvent.Clone event) {
        // 死亡重生與終界返回會換成新 ServerPlayer；舊 owner 此後不再收到 tick/logout。
        if (event.getOriginal() instanceof ServerPlayer player) close(player);
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        var iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            PatternGeneratorSession session = iterator.next().getValue();
            if (session.owner().server != event.getServer()) continue;
            iterator.remove();
            session.close();
        }
    }
}

