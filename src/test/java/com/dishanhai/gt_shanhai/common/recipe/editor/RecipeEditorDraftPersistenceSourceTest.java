package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeEditorDraftPersistenceSourceTest {

    @Test
    void editorDraftSurvivesUiRecreationThroughWorldSavedData() throws Exception {
        String savedData = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeEditorDraftSavedData.java"));
        String request = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/network/RecipeEditorDraftRequestPacket.java"));
        String response = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/network/RecipeEditorDraftSyncPacket.java"));
        String widget = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeEditorWidget.java"));
        String network = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/network/ShanhaiNetwork.java"));

        assertTrue(savedData.contains("extends SavedData"));
        assertTrue(savedData.contains("server.overworld().getDataStorage().computeIfAbsent"));
        assertTrue(request.contains("ShanhaiRecipeEditorDraftSavedData.get"));
        assertTrue(response.contains("writeNbt"));
        assertTrue(widget.contains("requestDraftLoad"));
        assertTrue(widget.contains("restoreDraft"));
        assertTrue(widget.contains("draftSaveAt"));
        int sameStageGuard = widget.indexOf("if (safe == stage) {");
        int sameStageReset = widget.indexOf("fromStage = safe;", sameStageGuard);
        int panelReset = widget.indexOf("panel.applyStage(false);", sameStageReset);
        int sameStageReturn = widget.indexOf("return;", panelReset);
        int stageMutation = widget.indexOf("fromStage = stage;", sameStageReturn);
        int stageSave = widget.indexOf("flushDraftSave();", stageMutation);
        assertTrue(sameStageGuard >= 0 && sameStageReset > sameStageGuard
                        && panelReset > sameStageReset && sameStageReturn > panelReset
                        && stageMutation > sameStageReturn && stageSave > stageMutation,
                "same-stage selection must reset page motion without marking draft state as edited");
        assertTrue(network.contains("RecipeEditorDraftRequestPacket.class"));
        assertTrue(network.contains("RecipeEditorDraftSyncPacket.class"));
    }
}
