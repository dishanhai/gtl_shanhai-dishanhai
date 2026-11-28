package com.dishanhai.gt_shanhai.client.gui.starlifter;

import com.dishanhai.sh_better_ui.client.gui.display.BuiltInSvgIcons;
import com.dishanhai.sh_better_ui.kernel.api.UiId;
import com.dishanhai.sh_better_ui.kernel.api.richtext.ImageNode;
import com.dishanhai.sh_better_ui.kernel.api.richtext.RichStyle;
import com.dishanhai.sh_better_ui.kernel.api.richtext.RichText;
import com.dishanhai.sh_better_ui.kernel.api.richtext.RichTextLayout;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StarLifterDossierTest {
    @Test
    void localizedControlsStayInFlowAtBothWidths() throws Exception {
        var metrics = new RichTextLayout.FontMetrics() {
            @Override public int ascent() { return 7; }
            @Override public int descent() { return 3; }
            @Override public int width(String text) { return text.codePointCount(0, text.length()) * 6; }
        };
        for (String locale : List.of("zh_cn", "en_us")) {
            try (var stream = getClass().getResourceAsStream("/assets/gt_shanhai/lang/" + locale + ".json")) {
                assertNotNull(stream);
                var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                String format = json.get(StarLifterDossier.KEY + "pages.engineering").getAsString();
                var builder = RichText.document(new UiId("gt_shanhai", "test/pages"));
                StarLifterDossier.addPageControls(builder, format.formatted(1, 2, '\uE000', '\uE001', '\uE002'),
                        RichStyle.EMPTY);
                var document = builder.build();
                var icons = document.nodes().stream().filter(ImageNode.class::isInstance).map(ImageNode.class::cast).toList();
                assertEquals(List.of("visual_icons/mouse_wheel.svg", "visual_icons/key_page_up.svg",
                        "visual_icons/key_page_down.svg"), icons.stream().map(icon -> icon.texture().path()).toList());
                assertTrue(icons.stream().allMatch(icon -> !icon.altText().isBlank()));
                for (int width : new int[]{48, 300}) {
                    var lines = RichTextLayout.layout(document, width, metrics).lines();
                    assertEquals(width == 300, lines.size() == 1);
                    var fragments = lines.stream().flatMap(line -> line.fragments().stream()).toList();
                    assertEquals(3, fragments.stream().filter(fragment -> fragment.node() instanceof ImageNode).count());
                    assertTrue(fragments.stream().allMatch(fragment -> fragment.bounds().x() + fragment.bounds().width() <= width));
                }
            }
        }
    }

    @Test
    void compositesSharedSvgsIntoSeparateAtlasCells() throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/gt_shanhai/textures/gui/star_lifter/dossier.svg")) {
            assertNotNull(stream);
            var atlas = StarLifterDossierArt.rasterize(stream);
            assertEquals(1536, atlas.getWidth());
            assertEquals(1600, atlas.getHeight());
            for (int i = 0; i < StarLifterDossier.CONTROL_SVGS.size(); i++) {
                String name = StarLifterDossier.CONTROL_SVGS.get(i);
                if (name.equals("mouse_wheel") && !BuiltInSvgIcons.hasSource(name)) name = "mouse";
                assertArrayEquals(BuiltInSvgIcons.argbPixels(name, 64), atlas.getRGB(i * 64, 1536, 64, 64, null, 0, 64));
            }
        }
    }
}
