package com.dishanhai.gt_shanhai.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：無圖形環境驗證告警專用的收合、完整內容與跨維度座標邊界。 */
class StellarStuckReportScreenSourceTest {
    private static final Path MAIN = Path.of("src/main/java/com/dishanhai/gt_shanhai");
    private static final Path LANG = Path.of("src/main/resources/assets/gt_shanhai/lang");

    private static String screen() throws Exception {
        return Files.readString(MAIN.resolve("client/gui/terminal/TerminalReportScreen.java"))
                .replace("\r\n", "\n");
    }

    @Test
    void stellarReportHasItsOwnTitleAndSingleCountSchema() throws Exception {
        String source = screen();
        assertTrue(Files.readString(MAIN.resolve("common/item/terminal/ShanhaiTerminalReport.java"))
                .contains("STELLAR_STUCK"));
        assertTrue(source.contains("case STELLAR_STUCK -> \"title_stellar_stuck\""));
        assertTrue(source.contains("if (isStellarStuck()) return tr(\"counts_stellar_stuck\", c.length > 0 ? c[0] : 0)"));
        assertTrue(source.contains("distinctPositions = isTextReport() ? report.lines().size() : distinct.size()"));
    }

    @Test
    void stellarDetailsStayUnformattedUntilExplicitExpansion() throws Exception {
        String source = screen();
        assertTrue(source.contains("boolean foldable = isStellarStuck() || gridLines > FOLD_LINES"));
        assertTrue(source.contains("detailLines = isStellarStuck() && !open ? List.of()"),
                "收起時不能對所有告警長文字執行字串物化與換行");
        assertTrue(source.contains("font.split(detail, COL_NAME_W)"));
        assertTrue(source.contains("if (!expanded.remove(row.group().key())) expanded.add(row.group().key())"));
        assertTrue(source.contains("isStellarStuck() ? (row.open() ? \"collapse_details\" : \"expand_details\")"));
        assertTrue(source.contains("pumpLayout(LOAD_BUDGET_NS)"));
        assertTrue(source.contains("drawSpinner("));
        for (String forbidden : new String[]{".substring(", "plainSubstrByWidth(", "trimText("}) {
            assertFalse(source.contains(forbidden), forbidden);
        }
    }

    @Test
    void stellarReportsCannotHighlightCoordinatesInThePlayersCurrentDimension() throws Exception {
        String source = screen();
        assertTrue(source.contains("private boolean hasWorldCoordinates() { return !isTextReport(); }"));
        assertTrue(source.contains("if (hasWorldCoordinates() && !positions.isEmpty())"));
        assertTrue(source.contains("boolean boxable = hasWorldCoordinates() && !positions.isEmpty()"));
        assertTrue(source.contains("if (!hasWorldCoordinates()) return;"),
                "即使未來新增按鈕，跨維度告警也不能落入世界座標高亮");
    }

    @Test
    void clickingAStellarRowCopiesCompleteDetailsEvenWhenCollapsed() throws Exception {
        String source = screen();
        assertTrue(source.contains("if (isTextReport()) {\n                String text = report.names()"));
        assertTrue(source.contains("setClipboard(detail.isEmpty() ? text : text + \"\\n\" + detail)"));
        assertTrue(source.contains("isStellarStuck() ? \"copied_stellar_stuck\" : \"copied_craft\""));
    }

    @Test
    void bothSupportedLanguagesProvideStellarReportLabels() throws Exception {
        for (String locale : new String[]{"zh_cn", "en_us"}) {
            String language = Files.readString(LANG.resolve(locale + ".json"));
            for (String key : new String[]{"title_stellar_stuck", "counts_stellar_stuck", "expand_details",
                    "collapse_details", "col_stellar_stuck", "copy_hint_stellar_stuck", "copied_stellar_stuck"}) {
                assertTrue(language.contains("\"gui.gt_shanhai.ultimate_terminal.report." + key + "\":"),
                        locale + ": " + key);
            }
        }
    }
}
