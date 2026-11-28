package com.dishanhai.gt_shanhai.client.gui.starlifter;

import com.dishanhai.gt_shanhai.common.machine.DShanhaiMachines;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterGeometry;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterStructure;
import com.dishanhai.sh_better_ui.client.lyrics.render.GradientPainter;
import com.dishanhai.sh_better_ui.client.visual.RichTextComponents;
import com.dishanhai.sh_better_ui.kernel.api.UiId;
import com.dishanhai.sh_better_ui.kernel.api.richtext.ImageNode;
import com.dishanhai.sh_better_ui.kernel.api.richtext.RichStyle;
import com.dishanhai.sh_better_ui.kernel.api.richtext.RichText;
import com.dishanhai.sh_better_ui.kernel.api.richtext.RichTextLayout;
import com.dishanhai.sh_better_ui.kernel.api.richtext.TextRun;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class StarLifterDossier {
    static final String KEY = "gt_shanhai.star_lifter.dossier.";
    static final int CORE = 0xFFF3D6, GOLD = 0xFFD080, ORANGE = 0xE9A044, VIOLET = 0xB99AFA,
            BLUE = 0x87C8FF, BODY = 0xBBC5D4, MUTED = 0x8291A8;
    static final int ART = 1, RING = 2, GLOW = 3, CORNER = 4, RULE = 5,
            STAR = 6, ARRAY = 7, EXTRACT = 8, ROUTE = 9, BUILD = 10, POWER = 11,
            SHIFT = 12, CTRL = 13, ARCHIVE = 14, PAGE = 15;
    static final int CONTROL_TILE = 16;
    static final int KEYCAP_SIZE = 13;
    static final List<String> CONTROL_SVGS = List.of("key_page_up", "key_page_down", "mouse_wheel");
    private static final List<UiId> CONTROL_IMAGES = CONTROL_SVGS.stream()
            .map(name -> new UiId("sh_better_ui", "visual_icons/" + name + ".svg")).toList();
    private static final char FIRST_CONTROL = '\uE000', LAST_CONTROL = '\uE002';
    static final int HEADER = 48, PAD = 10;
    static final int OVERVIEW = 0, ENGINEERING = 1, PROJECT = 2;

    record Span(int x, int y, int icon, FormattedCharSequence text, int color, boolean heading) {}
    record Page(Span[] spans, Span[] footer, int footerY, int height) {}
    private record Row(String text, int icon, int color, boolean heading, int gap) {}
    private record Line(List<Span> spans, int height) {}
    record Glyph(String text, float start, float end) {}

    final Font font;
    final Language language = Language.getInstance();
    final String locale;
    final int width, viewportHeight;
    final Page[][] pages = new Page[3][];
    final Glyph[] title;
    final float titleWidth;
    final FormattedCharSequence subtitle;
    final int subtitleWidth;
    private final RichTextLayout.FontMetrics metrics;
    private final NumberFormat numbers;

    StarLifterDossier(Font font, int width, int height, String locale) {
        this.font = font;
        this.width = width;
        viewportHeight = height;
        this.locale = locale;
        numbers = NumberFormat.getIntegerInstance(Locale.forLanguageTag(locale.replace('_', '-')));
        metrics = new RichTextLayout.FontMetrics() {
            @Override public int ascent() { return font.lineHeight - 2; }
            @Override public int descent() { return 3; }
            @Override public int width(String value) { return font.width(value); }
            @Override public int width(TextRun run, String value) {
                return font.width(Component.literal(value).setStyle(RichTextComponents.nativeStyle(run.style())));
            }
        };
        var name = Component.translatable("block.gt_shanhai.star_lifter").getVisualOrderText();
        var glyphs = new ArrayList<Glyph>();
        float[] advance = {0};
        name.accept((index, style, codePoint) -> {
            var glyph = Character.toString(codePoint);
            float start = advance[0];
            advance[0] += GradientPainter.advance(font, glyph);
            glyphs.add(new Glyph(glyph, start, advance[0]));
            return true;
        });
        title = glyphs.toArray(Glyph[]::new);
        titleWidth = advance[0];
        subtitle = Component.translatable(KEY + "eyebrow").getVisualOrderText();
        subtitleWidth = font.width(subtitle);

        pages[OVERVIEW] = paginate(List.of(
                row("class", STAR, GOLD, true, 4), row("motto", 0, CORE, false, 6),
                row("intro", STAR, BODY, false, 5), row("purpose", EXTRACT, BODY, false, 6),
                row("quote", 0, GOLD, false, 1)), OVERVIEW);

        var engineering = new ArrayList<Row>();
        engineering.add(row("engineering", ARCHIVE, BLUE, true, 1));
        engineering.add(row("fiction", 0, MUTED, false, 5));
        section(engineering, "definitions", "def", BUILD, new int[]{ARCHIVE, STAR, EXTRACT, ARRAY, STAR, ROUTE});
        section(engineering, "systems", "sys", ARRAY,
                new int[]{ARRAY, EXTRACT, RING, ROUTE, ROUTE, EXTRACT, POWER, BUILD, ARCHIVE});
        section(engineering, "sequence", "seq", ROUTE,
                new int[]{BUILD, ARCHIVE, ARRAY, EXTRACT, RING, POWER, EXTRACT, ROUTE});
        var spec = StarLifterStructure.specifications();
        engineering.add(row("specs", BUILD, GOLD, true, 2));
        engineering.add(row("dimensions", BUILD, BODY, false, 0,
                n(spec.front()), n(spec.up()), n(spec.right())));
        engineering.add(row("blocks", BUILD, BODY, false, 0, n(spec.blocks())));
        engineering.add(row("rings", ARRAY, BODY, false, 0, n(StarLifterGeometry.RINGS.size())));
        engineering.add(row("ports", ROUTE, BODY, false, 0, n(spec.interfaces())));
        for (var type : DShanhaiMachines.STAR_LIFTER.getRecipeTypes()) {
            engineering.add(row("recipe", EXTRACT, BODY, false, 0,
                    Component.translatable("gtceu.recipe_type." + type.registryName.getPath())));
        }
        engineering.add(row("formation", BUILD, BODY, false, 2));
        engineering.add(row("recipe.details", POWER, MUTED, false, 6));
        engineering.add(row("engineering.quote", 0, GOLD, false, 0));
        pages[ENGINEERING] = paginate(engineering, ENGINEERING);

        pages[PROJECT] = paginate(List.of(row("project", STAR, GOLD, true, 1),
                row("archive", ARCHIVE, VIOLET, false, 6), row("project.1", STAR, BODY, false, 5),
                row("project.2", EXTRACT, BODY, false, 5), row("project.3", ARRAY, BODY, false, 5),
                row("project.4", 0, CORE, false, 5), row("project.quote", 0, GOLD, false, 0)), PROJECT);
    }

    private String n(int value) { return numbers.format(value); }

    private static Row row(String key, int icon, int color, boolean heading, int gap, Object... args) {
        return new Row(Component.translatable(KEY + key, args).getString(), icon, color, heading, gap);
    }

    private static void section(List<Row> rows, String heading, String prefix, int icon, int[] icons) {
        rows.add(row(heading, icon, BLUE, true, 2));
        for (int i = 0; i < icons.length; i++) {
            rows.add(row(prefix + "." + i, icons[i], BODY, false, i == icons.length - 1 ? 6 : 0));
        }
    }

    private List<Line> wrap(Row row) {
        var doc = RichText.document(new UiId("gt_shanhai", "star_lifter/dossier"));
        if (row.icon != 0) doc.image(new UiId("gt_shanhai", "star_lifter/icon_" + row.icon), 12, 9, "");
        var style = new RichStyle(null, row.heading, false, false, false, false, null);
        if (row.icon == PAGE) addPageControls(doc, row.text, style);
        else doc.text(row.text, style);
        int hangingIndent = row.icon == 0 ? 0 : 12;
        var boxes = RichTextLayout.layout(doc.build(), width - 2 * PAD - hangingIndent, metrics).lines();
        var lines = new ArrayList<Line>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            var box = boxes.get(i);
            var spans = new ArrayList<Span>(box.fragments().size());
            for (var fragment : box.fragments()) {
                int x = PAD + fragment.bounds().x() + (i == 0 ? 0 : hangingIndent);
                if (fragment.node() instanceof ImageNode image) {
                    int control = CONTROL_IMAGES.indexOf(image.texture());
                    int tile = control < 0 ? row.icon : CONTROL_TILE + control;
                    spans.add(new Span(x, fragment.bounds().y() - box.y(), tile, null, row.color, false));
                } else if (fragment.node() instanceof TextRun run) {
                    var text = Component.literal(fragment.text()).setStyle(RichTextComponents.nativeStyle(run.style()));
                    spans.add(new Span(x, box.baseline() - metrics.ascent() - box.y(),
                            0, text.getVisualOrderText(), row.color, row.heading && i == 0));
                }
            }
            int spacing = i == boxes.size() - 1 ? row.gap + (row.heading ? 3 : 0) : 0;
            lines.add(new Line(spans, box.height() + spacing));
        }
        return lines;
    }

    // Translation placeholders can place control images anywhere in the sentence.
    static void addPageControls(RichText.Builder doc, String text, RichStyle style) {
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char code = text.charAt(i);
            if (code < FIRST_CONTROL || code > LAST_CONTROL) continue;
            if (start < i) doc.text(text.substring(start, i), style);
            String alt = Component.translatable(switch (code - FIRST_CONTROL) {
                case 0 -> "key.keyboard.page.up";
                case 1 -> "key.keyboard.page.down";
                default -> KEY + "pages.wheel";
            }).getString();
            doc.image(CONTROL_IMAGES.get(code - FIRST_CONTROL), KEYCAP_SIZE, KEYCAP_SIZE, alt);
            start = i + 1;
        }
        if (start < text.length()) doc.text(text.substring(start), style);
    }

    private static Row pageRow(String key, String current, String total) {
        return row(key, PAGE, MUTED, false, 0, current, total, FIRST_CONTROL,
                (char) (FIRST_CONTROL + 1), LAST_CONTROL);
    }

    private Page[] paginate(List<Row> rows, int mode) {
        var hints = new ArrayList<Row>();
        hints.add(new Row(CuhiIdentity.signature().getString(), 0, MUTED, false, 0));
        if (mode == OVERVIEW) {
            hints.add(row("shift", SHIFT, MUTED, false, 0));
            hints.add(row("ctrl", CTRL, MUTED, false, 0));
        }
        String pageHint = mode == ENGINEERING ? "pages.engineering" : "pages";
        int hintHeight = 0;
        for (var hint : hints) for (var line : wrap(hint)) hintHeight += line.height;
        int available = viewportHeight - HEADER - hintHeight - 2 * PAD;
        int pageHintHeight = wrap(pageRow(pageHint, "00", "00")).stream()
                .mapToInt(Line::height).sum();
        var sections = new ArrayList<List<Line>>();
        var section = new ArrayList<Line>();
        int totalHeight = 0;
        for (var row : rows) {
            if (row.heading && !section.isEmpty()) {
                sections.add(section);
                section = new ArrayList<>();
            }
            var lines = wrap(row);
            section.addAll(lines);
            totalHeight += lines.stream().mapToInt(Line::height).sum();
        }
        sections.add(section);
        boolean multiple = totalHeight > available;
        int capacity = Math.max(font.lineHeight + 1, available - (multiple ? pageHintHeight : 0));
        var chunks = new ArrayList<List<Line>>();
        var current = new ArrayList<Line>();
        int used = 0;
        for (var group : sections) {
            int sectionHeight = group.stream().mapToInt(Line::height).sum();
            if (!current.isEmpty() && sectionHeight <= capacity && used + sectionHeight > capacity) {
                chunks.add(current);
                current = new ArrayList<>();
                used = 0;
            }
            for (var line : group) {
                if (!current.isEmpty() && used + line.height > capacity) {
                    chunks.add(current);
                    current = new ArrayList<>();
                    used = 0;
                }
                current.add(line);
                used += line.height;
            }
        }
        chunks.add(current);
        Page[] result = new Page[chunks.size()];
        for (int i = 0; i < result.length; i++) {
            var spans = new ArrayList<Span>();
            int y = append(spans, chunks.get(i), HEADER);
            var footer = new ArrayList<Span>();
            int footerY = y + 5;
            y = footerY;
            for (var hint : hints) y = append(footer, wrap(hint), y);
            if (result.length > 1) {
                y = append(footer, wrap(pageRow(pageHint, n(i + 1), n(result.length))), y);
            }
            result[i] = new Page(spans.toArray(Span[]::new), footer.toArray(Span[]::new),
                    footerY, y + PAD);
        }
        return result;
    }

    private static int append(List<Span> out, List<Line> lines, int y) {
        for (var line : lines) {
            for (var span : line.spans) {
                out.add(new Span(span.x, y + span.y, span.icon, span.text, span.color, span.heading));
            }
            y += line.height;
        }
        return y;
    }
}
