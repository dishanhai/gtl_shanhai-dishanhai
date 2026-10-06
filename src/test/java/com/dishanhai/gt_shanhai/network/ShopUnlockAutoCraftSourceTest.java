package com.dishanhai.gt_shanhai.network;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopUnlockAutoCraftSourceTest {

    @Test
    void stageRequirementsCanStartTheSharedAeCraftPlan() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        assertTrue(text.contains("beginStagePlan("));
        assertTrue(text.contains("ShopStageConfig.get(stagePath)"));
        assertTrue(text.contains("\"stage:\" + stagePath"));
        assertTrue(text.contains("请先開啟「AE模式」"));
    }

    @Test
    void entrySubmissionRequirementsCanStartTheSharedAeCraftPlan() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        assertTrue(text.contains("beginEntrySubmissionPlan("));
        assertTrue(text.contains("entry.getSubmissionItems()"));
        assertTrue(text.contains("\"entry:\" + entry.getStableId()"));
    }

    @Test
    void stageAndEntryPlansAreValidatedAgainOnConfirm() throws Exception {
        String text = source("common/shop/ShopAutoCraft.java");
        assertTrue(text.contains("case STAGE"));
        assertTrue(text.contains("case ENTRY_SUBMISSION"));
        assertTrue(text.contains("session.aeMode"));
        assertTrue(text.contains("ShopStageConfig.get(session.stagePath)"));
        assertTrue(text.contains("hasSubmissionRequirement()"));
    }

    @Test
    void requestPacketCarriesTheUnlockTargetKind() throws Exception {
        String packet = source("network/ShopAutoCraftRequestPacket.java");
        assertTrue(packet.contains("enum Target"));
        assertTrue(packet.contains("COST"));
        assertTrue(packet.contains("STAGE"));
        assertTrue(packet.contains("ENTRY_SUBMISSION"));
        assertTrue(packet.contains("buf.readEnum(Target.class)"));
        assertTrue(packet.contains("buf.writeEnum(target)"));
    }

    @Test
    void screenOffersAutoCraftButtonsForBothPrerequisiteBlocks() throws Exception {
        String screen = source("client/gui/shop/ShopScreen.java");
        assertTrue(screen.contains("stageLockAutoCraftX"));
        assertTrue(screen.contains("submissionAutoCraftX"));
        assertTrue(screen.contains("ShopAutoCraftRequestPacket.Target.STAGE"));
        assertTrue(screen.contains("ShopAutoCraftRequestPacket.Target.ENTRY_SUBMISSION"));
    }

    @Test
    void stageRequirementsStaySeparateFromEntryPrerequisites() throws Exception {
        String config = source("common/shop/ShopStageConfig.java");
        assertTrue(config.contains("requirementsForCategory"));
        String submit = source("network/ShopSubmitPacket.java");
        assertTrue(submit.contains("\"entry:\" + entry.getStableId()"));
        assertTrue(submit.contains("\"stage:\" + packet.stagePath"));
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/" + relative));
    }
}
