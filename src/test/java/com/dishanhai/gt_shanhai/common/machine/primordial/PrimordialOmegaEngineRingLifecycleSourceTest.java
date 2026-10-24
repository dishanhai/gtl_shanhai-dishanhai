package com.dishanhai.gt_shanhai.common.machine.primordial;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialOmegaEngineRingLifecycleSourceTest {

    private static final Path ENGINE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "PrimordialOmegaEngineMachine.java");

    @Test
    void formedInvalidAndRemovedLifecycleSyncsClientRingStateFromServer() throws IOException {
        String source = Files.readString(ENGINE);

        assertTrue(source.contains("import com.dishanhai.gt_shanhai.network.SHideRingPacket;"));
        assertTrue(source.contains("import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;"));
        assertFalse(source.contains("ClientRingBlockHelper"),
                "common-side machine code must not directly reference the client-only ring helper");

        String formed = extractBlock(source, "public void onStructureFormed() {");
        String invalid = extractBlock(source, "public void onStructureInvalid() {");
        String removed = extractBlock(source, "public void onMachineRemoved() {");
        String sync = extractBlock(source, "protected void syncRingVisibility(boolean hide) {");

        assertTrue(formed.contains("syncRingVisibility(true);"),
                "formed host must push a hide packet even if the renderer has not run yet");
        assertTrue(invalid.contains("syncRingVisibility(false);"),
                "invalidated host must push a restore packet before renderer state can become stranded");
        assertTrue(removed.contains("syncRingVisibility(false);"),
                "removed host must restore rings after the block entity stops rendering");
        assertTrue(removed.contains("super.onMachineRemoved();"),
                "移除時仍須執行父類生命週期，不依賴重複宣告已繼承的介面");
        assertTrue(sync.contains("getLevel() instanceof ServerLevel serverLevel"));
        assertTrue(sync.contains("ShanhaiNetwork.sendHideRingToClients(serverLevel,"));
        assertTrue(sync.contains("new SHideRingPacket(getPos(), getFrontFacing(), hide)"));
    }

    @Test
    void patternFormationDefersWorldMutationToServerThread() throws IOException {
        String source = Files.readString(ENGINE);
        String asyncCheck = extractBlock(source, "public void asyncCheckPattern(long period) {");
        assertTrue(asyncCheck.contains("structureCheck.asyncCheck(this, period)"));
        String checker = Files.readString(ENGINE.resolveSibling("PrimordialStructureCheck.java"));

        assertTrue(checker.contains("getPatternLock().tryLock()"),
                "异步结构扫描必须先用 tryLock，避免与主线程结构变更互相等待");
        assertTrue(checker.contains("level.getServer().execute"),
                "结构成型回调必须回到 Minecraft server thread 执行");
        String formation = checker.substring(checker.indexOf("level.getServer().execute"));
        assertTrue(formation.contains("getPatternLock().lock()"),
                "回到主线程后仍需持有结构锁再读取成型状态");
        assertTrue(formation.indexOf("machine.checkPattern()") < formation.indexOf("machine.onStructureFormed()"),
                "真正成型前必須重新檢測，不能採用等待期間已失效的結果");
        assertTrue(formation.contains("machine.isInValid()") && formation.contains("gate.isCurrent(ticket)"),
                "控制器卸載或任務取消後不得成型");
        assertTrue(formation.contains("finally"),
                "结构锁必须在异常路径释放");
        assertFalse(checker.substring(0, checker.indexOf("level.getServer().execute"))
                .contains("machine.onStructureFormed()"), "不能在異步探測執行緒執行成型回調");
    }

    private static String extractBlock(String source, String declaration) {
        int start = source.indexOf(declaration);
        assertTrue(start >= 0, "missing declaration: " + declaration);
        int openBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(openBrace, i + 1);
                }
            }
        }
        throw new AssertionError("unclosed block: " + declaration);
    }
}
