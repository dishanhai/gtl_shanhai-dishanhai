package com.dishanhai.gt_shanhai.network;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeEditorPacketCodecTest {

    @Test
    void recipeSyncPacketUsesVersionedBoundedPayload() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/network/RecipeSyncPacket.java"));
        assertTrue(source.contains("PAYLOAD_VERSION"));
        assertTrue(source.contains("MAX_ENTRIES"));
        assertTrue(source.contains("writeVarLong"));
        assertTrue(source.contains("readVarLong"));
        assertTrue(source.contains("GTRecipeSerializer.SERIALIZER.toNetwork"));
        assertTrue(source.contains("GTRecipeSerializer.SERIALIZER.fromNetwork"));
        assertTrue(source.contains("removedRecipeIds"));
        assertTrue(source.contains("syncRecipeToAll"));
        assertTrue(source.contains("PAYLOAD_VERSION = 2"));
    }

    @Test
    void editorPacketsCarryFingerprintAndStatus() throws Exception {
        String commit = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/network/RecipeEditorCommitPacket.java"));
        String detail = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/network/RecipeEditorDetailPacket.java"));
        String result = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/network/RecipeEditorResultPacket.java"));
        assertTrue(commit.contains("baseFingerprint"));
        assertTrue(detail.contains("recipeTypeId"));
        assertTrue(detail.contains("\"detail\""));
        assertTrue(result.contains("Status"));
        assertTrue(result.contains("revision"));
    }
}
