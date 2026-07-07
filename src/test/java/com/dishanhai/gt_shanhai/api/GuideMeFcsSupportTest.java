package com.dishanhai.gt_shanhai.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GuideMeFcsSupportTest {

    @BeforeEach
    void setUp() {
        TextFormatParser.FCS_PREFIX_MAP.clear();
    }

    @Test
    void stripsFcsPrefixAndRemembersRawMapping() {
        var result = GuideMeFcsSupport.prepareLayoutText("&$golden-暗星物质模块");

        assertTrue(result.handled());
        assertEquals("暗星物质模块", result.layoutText());
        assertEquals("&$golden-暗星物质模块", TextFormatParser.restoreRawFcs("暗星物质模块"));
    }

    @Test
    void keepsVisiblePrefixBeforeFcsName() {
        var result = GuideMeFcsSupport.prepareLayoutText("64 &$magic-虚像物质模块");

        assertTrue(result.handled());
        assertEquals("64 虚像物质模块", result.layoutText());
        assertEquals("64 &$magic-虚像物质模块", TextFormatParser.restoreRawFcs("64 虚像物质模块"));
    }

    @Test
    void leavesPlainTextUntouched() {
        var result = GuideMeFcsSupport.prepareLayoutText("物质重组模块");

        assertFalse(result.handled());
        assertEquals("物质重组模块", result.layoutText());
        assertNull(TextFormatParser.restoreRawFcs("物质重组模块"));
    }

    @Test
    void ignoresAmpersandThatIsNotFcsPrefix() {
        var result = GuideMeFcsSupport.prepareLayoutText("R&D 模块");

        assertFalse(result.handled());
        assertEquals("R&D 模块", result.layoutText());
        assertNull(TextFormatParser.restoreRawFcs("R&D 模块"));
    }
}
