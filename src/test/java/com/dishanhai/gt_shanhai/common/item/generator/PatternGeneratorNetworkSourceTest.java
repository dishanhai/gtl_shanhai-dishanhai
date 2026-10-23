package com.dishanhai.gt_shanhai.common.item.generator;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PatternGeneratorNetworkSourceTest {
    private static final Path ROOT = Path.of("src/main/java/com/dishanhai/gt_shanhai");
    @Test void packetsAreDirectionalAndSessionChecksRunOnServerThread() throws Exception {
        String network = Files.readString(ROOT.resolve("network/ShanhaiNetwork.java"));
        assertTrue(network.contains("PROTOCOL_VERSION = \"9\""));
        for (String packet : new String[]{"Open", "Action", "State", "Preset"})
            assertTrue(network.contains("PatternGenerator" + packet + "Packet.class"));
        String action = Files.readString(ROOT.resolve("network/PatternGeneratorActionPacket.java"));
        assertTrue(action.indexOf("enqueueWork") < action.indexOf("PatternGeneratorSessions.get"));
        assertTrue(action.contains("stillHeld()"));
        assertTrue(action.contains("sessionId"));
        assertTrue(action.contains("MAX_PAYLOAD"));
        assertTrue(action.indexOf("if (payload == null)") < action.indexOf("session.acknowledgeRequest"));
        assertTrue(action.indexOf("session.acknowledgeRequest") < action.indexOf("session.handle("));
        String session = Files.readString(ROOT.resolve("common/item/generator/PatternGeneratorSession.java"));
        assertTrue(session.contains("BATCH_PER_TICK = 8"));
        assertTrue(session.contains("tick - lastAvailableCheck >= 20"));
        assertFalse(session.contains("net.minecraft.client"));
        assertTrue(session.contains("originalStack.getOrCreateTag()"));
        String sessions = Files.readString(ROOT.resolve("common/item/generator/PatternGeneratorSessions.java"));
        for (String event : new String[]{"PlayerTickEvent", "PlayerLoggedOutEvent", "PlayerChangedDimensionEvent"})
            assertTrue(sessions.contains(event));
    }

    @Test void oversizedSettingsAreRejectedBeforePersistenceAndCanStillBeRecovered() throws Exception {
        String session = Files.readString(ROOT.resolve("common/item/generator/PatternGeneratorSession.java"));
        String filter = session.substring(session.indexOf("private void setFilter("), session.indexOf("private void rebuild("));
        assertTrue(filter.contains("configurationAccepted(previous)"));
        assertTrue(filter.indexOf("configurationAccepted(previous)") < filter.indexOf("save()"));
        String machine = session.substring(session.indexOf("private void setMachine("), session.indexOf("private void setFilter("));
        assertTrue(machine.contains("configurationAccepted(previous)"));
        assertTrue(machine.indexOf("configurationAccepted(previous)") < machine.indexOf("save()"));
        String save = session.substring(session.indexOf("private void save("), session.indexOf("private void push("));
        assertTrue(save.indexOf("validateConfiguration") < save.indexOf("getOrCreateTag()"));
        assertTrue(session.contains("settings.read(previous)"));
        assertTrue(session.contains("state_too_large"));
        String sessions = Files.readString(ROOT.resolve("common/item/generator/PatternGeneratorSessions.java"));
        assertTrue(sessions.contains("catch (PatternGeneratorCodec.ConfigurationTooLargeException"));
        assertTrue(sessions.contains("state_too_large"));
        String behavior = Files.readString(ROOT.resolve("common/item/generator/PatternGeneratorBehavior.java"));
        assertFalse(behavior.contains("getOrCreateTag().put(PatternGeneratorSettings.TAG"));
    }

    @Test void sameUuidFromAnOldPlayerInstanceCannotCloseTheNewSession() throws Exception {
        String session = Files.readString(ROOT.resolve("common/item/generator/PatternGeneratorSession.java"));
        assertTrue(session.contains("player == candidate"));
        String sessions = Files.readString(ROOT.resolve("common/item/generator/PatternGeneratorSessions.java"));
        assertTrue(sessions.contains("session.ownedBy(player)"));
        String close = sessions.substring(sessions.indexOf("public static void close(ServerPlayer player)"),
                sessions.indexOf("public static void close(ServerPlayer player, UUID"));
        assertTrue(close.contains("get(player)"));
        String open = sessions.substring(sessions.indexOf("public static PatternGeneratorSession open("),
                sessions.indexOf("public static PatternGeneratorSession get("));
        assertTrue(open.contains("SESSIONS.remove(player.getUUID())"));
        assertTrue(sessions.contains("session::owner"));
        assertTrue(sessions.contains("PlayerEvent.Clone"));
        assertTrue(sessions.contains("event.getOriginal() instanceof ServerPlayer"));
    }
}

