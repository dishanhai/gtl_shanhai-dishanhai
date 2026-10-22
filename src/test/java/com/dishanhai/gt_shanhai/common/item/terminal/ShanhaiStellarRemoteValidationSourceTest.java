package com.dishanhai.gt_shanhai.common.item.terminal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 山海遠端 UI 的無線副作用快取不得遮蔽目標及網格安全檢查。 */
class ShanhaiStellarRemoteValidationSourceTest {

    @Test
    void cachedWirelessWorkKeepsTargetTerminalAndCurrentGridChecksOutsideTheCache() throws Exception {
        String source = source();
        String invalid = method(source, "public boolean isInvalid()");

        int target = invalid.indexOf("target.isInValid()");
        int level = invalid.indexOf("target.getLevel() == null");
        int terminal = invalid.indexOf("isTerminalStillPresent()");
        int wireless = invalid.indexOf("refreshWirelessState();");
        int grid = invalid.indexOf("node.getGrid() != target.getGrid()");
        assertTrue(target >= 0 && level > target && terminal > level && wireless > terminal && grid > wireless,
                "每次判斷仍應檢查目標、終端存在及當下網格身份");
        assertFalse(invalid.contains("return cached"), "不得快取整個 isInvalid 的結果");
    }

    @Test
    void rangePowerAndNodeResolutionShareOneTickCacheIncludingFailures() throws Exception {
        String refresh = method(source(), "private void refreshWirelessState()");

        int cadence = refresh.indexOf("wirelessCheckCadence.shouldCheck(");
        int range = refresh.indexOf("terminalHost.rangeCheck()");
        int power = refresh.indexOf("terminalHost.drainPower()");
        int node = refresh.indexOf("terminalHost.getActionableNode()");
        assertTrue(cadence >= 0 && range > cadence && power > range && node > power,
                "range、既有扣電及隱含 range 的節點解析都必須共用同 tick 快取");
        assertTrue(refresh.contains("level.getGameTime()"));
        assertTrue(refresh.contains("wirelessFailure = \"terminal_out_of_range\""));
        assertTrue(refresh.contains("wirelessFailure = \"terminal_power_failed\""));
        assertTrue(refresh.contains("wirelessFailure = null"), "下個 tick 成功時應清除舊失敗");
        String invalid = method(source(), "public boolean isInvalid()");
        assertTrue(invalid.contains("if (wirelessFailure != null) return reject(wirelessFailure);"),
                "同 tick 重複判斷必須保留首次失敗結果");
    }

    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/item/terminal/"
                + "ShanhaiStellarRemoteUIHolder.java"));
    }

    private static String method(String source, String signature) {
        int signatureStart = source.indexOf(signature);
        assertTrue(signatureStart >= 0, "缺少方法 " + signature);
        int start = source.indexOf('{', signatureStart);
        int depth = 1;
        int end = start + 1;
        while (end < source.length() && depth > 0) {
            char value = source.charAt(end++);
            if (value == '{') depth++;
            if (value == '}') depth--;
        }
        return source.substring(start, end);
    }
}
