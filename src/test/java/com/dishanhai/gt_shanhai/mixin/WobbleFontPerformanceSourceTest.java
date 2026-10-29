package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WobbleFontPerformanceSourceTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "WobbleFontMixin.java");

    @Test
    void fontRenderAndWidthHotPathsDoNotWriteDiagnosticLogs() throws IOException {
        String source = Files.readString(SOURCE);

        assertFalse(source.contains("LOG.info(\"[Wobble] width"),
                "Font.width 每帧高频调用，不能持续写 INFO 日志");
        assertFalse(source.contains("CURVE_LOG.warn("),
                "Font.drawInBatch 热路径不能保留可能逐帧触发的 WARN 日志");
    }

    @Test
    void allEffectsReachBothComponentAndFcsRenderPaths() throws IOException {
        String source = Files.readString(SOURCE);
        int componentStart = source.indexOf("private void onDrawInBatchComponent(");
        int fcsStart = source.indexOf("private void onDrawInBatchFCS(");
        assertTrue(componentStart >= 0 && fcsStart > componentStart);
        String component = source.substring(componentStart, fcsStart);
        String fcs = source.substring(fcsStart);
        for (String renderPath : new String[]{component, fcs}) {
            assertTrue(renderPath.contains("DShanhaiTextUtil.allTextFrame("), "兩種繪製入口都須取得全模式效果");
            assertTrue(renderPath.contains("TextFormatParser.calcAllEffectOffset("), "隨機位移須進入實際繪製");
            assertTrue(renderPath.contains("TextFormatParser.applyAllEffectColor("), "隨機亮度須進入實際繪製");
        }
        assertTrue(fcs.indexOf("if (allFrame != null) hasEffects = true;")
                < fcs.indexOf("if (!hasEffects && !hasWobble)"), "全模式不得被純色板快速路徑跳過");
    }

    @Test
    void nestedFcsMarkersAreDispatchedBeforeReadingOnlyTheFirstStyle() throws IOException {
        String source = Files.readString(SOURCE);
        int dispatch = source.indexOf("TextFormatParser.hasMixedFcsInsertions(charStyles)");
        int firstStyle = source.indexOf("insertion = rawTextCleanStyles.get(0).getInsertion()");
        assertTrue(dispatch >= 0 && dispatch < firstStyle,
                "前綴＋all 子組件必須依樣式段分派，不能只讀第一字而退化成靜態白字");
    }

    @Test
    void insertionComponentsDelegateBeforeFlatteningTheirStyledChildren() throws IOException {
        String source = Files.readString(SOURCE);
        int componentStart = source.indexOf("private void onDrawInBatchComponent(");
        int fcsStart = source.indexOf("private void onDrawInBatchFCS(");
        String component = source.substring(componentStart, fcsStart);
        assertTrue(component.contains("if (!hasAmp && insertion != null && insertion.startsWith(\"fcs:\")) return;"),
                "帶 insertion 的根組件須交由 FCS 保留各子段的顏色與樣式");
    }

    @Test
    void allSlowPathDoesNotReadFilteredOutWobbleStyles() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("String wobbleStyle = hasWobble ? DShanhaiTextUtil.WOBBLE_STYLE.get() : null;"),
                "未通過 wobble 過濾的全模式，不得被殘留的 wobble 色板覆蓋");
    }
}
