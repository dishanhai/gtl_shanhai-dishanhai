package com.dishanhai.gt_shanhai.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideRegistrationTest {
    private static final Path CLIENT_SOURCES = Path.of("src/main/java/com/dishanhai/gt_shanhai/client");

    @Test
    void registersBeforeTheFirstClientResourceReload() throws Exception {
        String registration = Files.readString(CLIENT_SOURCES.resolve("GuideRegistration.java"));
        assertTrue(registration.contains("value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD"),
                "Guide registration must subscribe to the client mod event bus");
        assertTrue(registration.matches("(?s).*@SubscribeEvent\\s+public static void \\w+"
                + "\\(RegisterClientReloadListenersEvent \\w+\\).*"),
                "GuideME must see the guide before its first resource reload starts");

        String clientInit = Files.readString(CLIENT_SOURCES.resolve("ClientInit.java"));
        assertFalse(clientInit.contains("GuideRegistration.register("),
                "Client setup runs during the first reload and must not register the guide again");
    }
}
