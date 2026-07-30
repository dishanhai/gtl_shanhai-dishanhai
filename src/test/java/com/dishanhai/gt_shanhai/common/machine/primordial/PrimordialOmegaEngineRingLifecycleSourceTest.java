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
