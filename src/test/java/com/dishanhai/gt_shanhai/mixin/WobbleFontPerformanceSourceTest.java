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
    void randomEffectsReachBothComponentAndFcsRenderPaths() throws IOException {
        String source = Files.readString(SOURCE);
        int componentStart = source.indexOf("private void onDrawInBatchComponent(");
        int fcsStart = source.indexOf("private void onDrawInBatchFCS(");
        assertTrue(componentStart >= 0 && fcsStart > componentStart);
        String component = source.substring(componentStart, fcsStart);
        String fcs = source.substring(fcsStart);
        for (String renderPath : new String[]{component, fcs}) {
            assertTrue(renderPath.contains("DShanhaiTextUtil.allTextFrame("), "兩種繪製入口都須取得隨機模式效果");
            assertTrue(renderPath.contains("TextFormatParser.calcAllEffectOffset("), "隨機位移須進入實際繪製");
            assertTrue(renderPath.contains("TextFormatParser.applyAllEffectColor("), "隨機亮度須進入實際繪製");
        }
        assertTrue(fcs.indexOf("if (allFrame != null) hasEffects = true;")
                < fcs.indexOf("if (!hasEffects && !hasWobble)"), "隨機模式不得被純色板快速路徑跳過");
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
    void randomSlowPathDoesNotReadFilteredOutWobbleStyles() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("String wobbleStyle = hasWobble ? DShanhaiTextUtil.WOBBLE_STYLE.get() : null;"),
                "未通過 wobble 過濾的隨機模式，不得被殘留的 wobble 色板覆蓋");
    }

    @Test
    void allWithoutAutomaticEffectsStillPreservesCharacterStyles() throws IOException {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("if (preserveAllStyle) hasEffects = true;"),
                "全模式須保留逐字樣式，不能退回會覆蓋 § 色的快速路徑");
        assertTrue(source.contains("allFrame != null || preserveAllStyle ? legacyStyle : Style.EMPTY"),
                "全模式的非顏色格式須保留");
        assertTrue(source.contains("preserveAllStyle |= DShanhaiTextUtil.isAllStyle(effectiveStyle);"),
                "展開文字以 wobble 指定全色板時，也須保留逐字樣式");
    }
}
