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

        assertTrue(source.contains("import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;"));
        assertTrue(source.contains("implements IModularMachineHost<PrimordialOmegaEngineMachine>, IMachineLife"));
        assertTrue(source.contains("import com.dishanhai.gt_shanhai.network.SHideRingPacket;"));
        assertTrue(source.contains("import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;"));
        assertFalse(source.contains("ClientRingBlockHelper"),
                "common-side machine code must not directly reference the client-only ring helper");

        String formed = extractBlock(source, "public void onStructureFormed() {");
        String invalid = extractBlock(source, "public void onStructureInvalid() {");
        String removed = extractBlock(source, "public void onMachineRemoved() {");
        String sync = extractBlock(source, "private void syncRingVisibility(boolean hide) {");

        assertTrue(formed.contains("syncRingVisibility(true);"),
                "formed host must push a hide packet even if the renderer has not run yet");
        assertTrue(invalid.contains("syncRingVisibility(false);"),
                "invalidated host must push a restore packet before renderer state can become stranded");
        assertTrue(removed.contains("syncRingVisibility(false);"),
                "removed host must restore rings after the block entity stops rendering");
        assertTrue(sync.contains("getLevel() instanceof ServerLevel serverLevel"));
        assertTrue(sync.contains("ShanhaiNetwork.sendHideRingToClients(serverLevel,"));
        assertTrue(sync.contains("new SHideRingPacket(getPos(), getFrontFacing(), hide)"));
    }

    @Test
    void patternFormationDefersWorldMutationToServerThread() throws IOException {
        String source = Files.readString(ENGINE);
        String asyncCheck = extractBlock(source, "public void asyncCheckPattern(long period) {");

        assertTrue(asyncCheck.contains("checkPatternWithTryLock()"),
                "异步结构扫描必须先用 tryLock，避免与主线程结构变更互相等待");
        assertTrue(asyncCheck.contains("serverLevel.getServer().execute"),
                "结构成型回调必须回到 Minecraft server thread 执行");
        assertTrue(asyncCheck.contains("getPatternLock().lock()"),
                "回到主线程后仍需持有结构锁再读取成型状态");
        assertTrue(asyncCheck.contains("finally"),
                "结构锁必须在异常路径释放");
        assertFalse(asyncCheck.contains("if (checkPattern())"),
                "不能直接在异步搜索线程执行成型回调");
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
