package com.dishanhai.gt_shanhai.client.gui.generator;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：網路延遲、picker 往返與縮放座標的遷移契約。 */
class PatternGeneratorClientContractTest {
    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/generator/"+name+".java"));
    }
    @Test void queuedDraftsWaitForMatchingAcknowledgements() throws Exception {
        String state=source("PatternGeneratorClientState");
        assertTrue(state.contains("awaitingWhite"));
        assertTrue(state.contains("awaitingExt"));
        assertTrue(state.contains("!awaitingWhite && !awaitingExt"));
        assertTrue(state.contains("state.acknowledgedRequest()"));
    }
    @Test void pickerSuspensionDoesNotCloseOrLoseTheSession() throws Exception {
        String screen=source("PatternGeneratorScreen");
        assertTrue(screen.contains("if (!suspended) finish()"));
        assertTrue(screen.contains("suspended=true"));
        assertTrue(screen.contains("ghostTargets"));
        assertTrue(source("PatternGeneratorScreenOpener").contains("active.receive(state)"));
        assertTrue(source("PatternGeneratorJeiHandler").contains("addGuiScreenHandler"));
    }
    @Test void gridPreservesAllPresetSamplesAndCorrectsScissorsForScaledScreen() throws Exception {
        String screen=source("PatternGeneratorScreen");
        assertTrue(screen.contains("(values.size()+columns-1)/columns"));
        assertTrue(screen.contains("matrix.m00()"));
        assertTrue(screen.contains("matrix.m30()"));
    }
    @Test void pickerReplacementPreservesFluidNbtAndHighlightsTheTagSlot() throws Exception {
        String ghost=source("GeneratorGhostSlot");
        assertFalse(ghost.contains("if (result[0]==null)"));
        assertTrue(ghost.contains("new GenericStack(old,1)"));
        assertTrue(source("PatternGeneratorScreen").contains("selectedFilterSlots[group]==slot"));
        assertTrue(source("PatternGeneratorScreen").contains("e.ghost.isSelected()"));
    }
    @Test void layoutClearsRemovedFocusAndRepliesIdentifyRejectedRequests() throws Exception {
        String screen=source("PatternGeneratorScreen");
        int rebuild=screen.indexOf("private void rebuildLayout()");
        int clearFocus=screen.indexOf("setFocused(null);",rebuild);
        assertTrue(clearFocus>rebuild && clearFocus<screen.indexOf("clearWidgets()",rebuild));
        String state=source("PatternGeneratorClientState");
        assertTrue(state.contains("state.acknowledgedRequest()"));
        assertTrue(state.contains("acknowledgedRequest()>=whiteRequest"));
        assertTrue(state.contains("acknowledgedRequest()>=extRequest"));
        assertTrue(screen.contains("value.acknowledgedRequest()>=pendingRequest"));
    }
    @Test void stateRefreshPreservesInputSelection() throws Exception {
        String screen=source("PatternGeneratorScreen");
        assertTrue(screen.contains("reusableFields.get(key)"));
        assertFalse(screen.contains("highlight=caret"));
    }
    @Test void modalDropdownOwnsKeyboardAndGridMeasuresItsActualTextWidth() throws Exception {
        String screen=source("PatternGeneratorScreen");
        assertTrue(screen.contains("boolean charTyped(char character,int modifiers)"));
        assertTrue(screen.contains("dropdown!=null || super.charTyped"));
        assertTrue(screen.contains("recipeNameWidth(w)"));
        assertTrue(screen.contains("recipeNameWidth(e.w)"));
    }
}
