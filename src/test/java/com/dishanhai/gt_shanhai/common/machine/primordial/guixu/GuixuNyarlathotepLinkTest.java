package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：連結獨立於原始終焉引擎，且增益不得穿過未載入或非雙向端點。 */
class GuixuNyarlathotepLinkTest {
    private static final Path JAVA = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    @Test
    void resolverRequiresLoadedFormedReciprocalEndpoints() throws Exception {
        Path file = JAVA.resolve("common/machine/primordial/guixu/GuixuNyarlathotepLink.java");
        assertTrue(Files.exists(file), "Missing Nyarlathotep link resolver");
        String source = Files.readString(file);
        assertTrue(source.contains("hasChunkAt(pos)"));
        assertTrue(source.contains("isLinkedToNyarlathotep("));
        assertTrue(source.contains("isLinkedToGuixu("));
        assertTrue(source.contains("isFormed()"));
        assertFalse(source.contains("getChunk("), "Remote lookup must not load chunks");
    }

    @Test
    void hostKeepsIndependentPersistedAndSyncedNyarlathotepAddress() throws Exception {
        String source = Files.readString(JAVA.resolve("common/machine/primordial/guixu/GuixuVisionCoreMachine.java"));
        assertTrue(source.contains("private String linkedNyarlathotepDim"));
        assertTrue(source.contains("private long linkedNyarlathotepPos"));
        assertTrue(source.contains("private boolean nyarlathotepLinkReady"));
        assertTrue(source.contains("private String linkedEngineDim"), "Engine link must remain independent");
        assertTrue(source.contains("GuixuNyarlathotepLink.reconcileHost(this)"));
        for (String name : new String[]{"linkedNyarlathotepDim", "linkedNyarlathotepPos"}) {
            var field = GuixuVisionCoreMachine.class.getDeclaredField(name);
            assertNotNull(field.getAnnotation(com.lowdragmc.lowdraglib.syncdata.annotation.Persisted.class));
            assertNotNull(field.getAnnotation(com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced.class));
        }
        assertNotNull(GuixuVisionCoreMachine.class.getDeclaredField("nyarlathotepLinkReady")
                .getAnnotation(com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced.class));
    }

    @Test
    void registeredItemHasEarlyInteractionHookAndModel() throws Exception {
        String source = Files.readString(JAVA.resolve("GTDishanhaiMod.java"));
        assertTrue(source.contains("GUIXU_NYARLATHOTEP_LINK_MODULE"));
        assertTrue(source.contains("instanceof GuixuNyarlathotepLinkModuleItem"));
        var model = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/gt_shanhai/models/item/guixu_nyarlathotep_link_module.json"))).getAsJsonObject();
        assertTrue(model.has("textures"));
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            var json = JsonParser.parseString(Files.readString(Path.of(
                    "src/main/resources/assets/gt_shanhai/lang/" + lang + ".json"))).getAsJsonObject();
            assertTrue(json.has("item.gt_shanhai.guixu_nyarlathotep_link_module"));
        }
    }
}
