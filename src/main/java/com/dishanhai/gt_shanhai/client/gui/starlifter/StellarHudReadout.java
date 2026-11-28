package com.dishanhai.gt_shanhai.client.gui.starlifter;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTarget;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTelemetry.Snapshot;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTelemetry.State;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Locale;

final class StellarHudReadout {
    private static final String KEY = "gt_shanhai.star_lifter.jade.";

    record Label(String plain, FormattedCharSequence glyphs, int width) {}
    record Header(Label name, Label section, Label purpose, StarLifterHudTarget role, boolean icon) {}
    record Row(Label label, Label value) {}

    final Label state;
    final Label ownership;
    final Label signature;
    final Label hint;
    final Label brand;
    final Label alert;
    final Label reserve;
    final Label progress;
    final Row[] details;
    final String reserveValue;
    final String progressValue;
    private final String narration;

    StellarHudReadout(Font font, int width, Header header, Snapshot sample, String locale,
                      boolean fullBrand, Component detailKey) {
        var number = NumberFormat.getIntegerInstance(Locale.forLanguageTag(locale.replace('_', '-')));
        boolean member = header.role != StarLifterHudTarget.CORE && sample.formed();
        boolean critical = sample.state() == State.FAULT || sample.state() == State.OFFLINE;
        state = label(font, member && !critical ? text("state.connected") : state(sample), width - 24);
        ownership = label(font, sample.controller() == null ? text("owner.none")
                : text("owner.bound", coordinates(sample.controller())), width - 12);
        signature = label(font, CuhiIdentity.signature(), width - 18);
        hint = label(font, text("details_hint", detailKey), width - 18);
        brand = label(font, CuhiIdentity.wordmark(CuhiIdentity.Scale.MICRO), 32);
        reserve = label(font, text("reserve"), width / 2);
        progress = label(font, text("progress"), width / 2);
        reserveValue = sample.formed() ? percentage(sample.reserve(), sample.capacity()) : "—";
        progressValue = sample.duration() > 0 ? percentage(sample.progress(), sample.duration()) : "—";
        alert = sample.reason() != null ? label(font, sample.reason(), width - 12)
                : sample.invalidAt() != null ? label(font, text("boot.invalid_at", coordinates(sample.invalidAt())), width - 12)
                : member && critical ? label(font, text("state.connected"), width - 12) : null;
        var rows = new ArrayList<Row>();
        if (member) add(rows, font, width, text("controller_state"), state(sample));
        if (sample.formed()) {
            add(rows, font, width, text("ring_effect"), text(sample.visuals() ? "enabled" : "disabled"));
            add(rows, font, width, text("resonance"), text(sample.resonant() ? "linked" : "unlinked"));
            add(rows, font, width, text("capture_budget"), Component.literal(
                    number.format(sample.reserve()) + " / " + number.format(sample.capacity())));
            add(rows, font, width, text("energy"), text("energy_value",
                    number.format(sample.stored()), number.format(sample.energyCapacity())));
            add(rows, font, width, text("transfer"), text("transfer_value",
                    number.format(sample.inputPerSecond()), number.format(sample.outputPerSecond())));
            add(rows, font, width, text("installed_hatches"), Component.literal(number.format(sample.parts())));
            add(rows, font, width, text("batches"), Component.literal(number.format(sample.batches())));
        }
        if (sample.recipe() != null) {
            add(rows, font, width, text("recipe"), Component.literal(sample.recipe().getPath()));
            add(rows, font, width, text("recipe_load"), text("recipe_load_value",
                    number.format(sample.recipeEUt()), number.format(sample.parallels())));
        }
        if (sample.invalidAt() != null) {
            add(rows, font, width, text("invalid_at"), Component.literal(coordinates(sample.invalidAt())));
        }
        if (sample.reason() != null) {
            var reason = font.split(sample.reason(), width - 14);
            for (var line : reason) {
                rows.add(new Row(new Label(sample.reason().getString(), line, font.width(line)), null));
            }
        }
        if (fullBrand) {
            add(rows, font, width, CuhiIdentity.name(), null);
            add(rows, font, width, CuhiIdentity.division(), null);
            add(rows, font, width, CuhiIdentity.text("engineering_platform"), null);
        }
        details = rows.toArray(Row[]::new);
        narration = String.join(". ", header.name.plain, header.purpose.plain, state.plain, signature.plain);
    }

    String narration(boolean detailed, boolean meters, boolean nativeCoreCoordinates) {
        var message = new StringBuilder(narration);
        if (!detailed && alert != null) message.append(". ").append(alert.plain);
        if (!nativeCoreCoordinates) message.append(". ").append(ownership.plain);
        if (meters) message.append(". ").append(reserve.plain).append(' ').append(reserveValue)
                .append(". ").append(progress.plain).append(' ').append(progressValue);
        if (detailed) {
            String previous = null;
            for (var row : details) {
                if (!row.label.plain.equals(previous)) message.append(". ").append(row.label.plain);
                if (row.value != null) message.append(" ").append(row.value.plain);
                previous = row.label.plain;
            }
        }
        return message.toString();
    }

    static Header header(Font font, int width, Component name, StarLifterHudTarget role, boolean icon) {
        int usable = width - (icon ? 41 : 12);
        return new Header(label(font, name, usable), label(font, text("section." + role.icon.name().toLowerCase(Locale.ROOT)),
                width - 49), label(font, text("role." + role.key), usable), role, icon);
    }

    private static Component state(Snapshot sample) {
        return text("state." + sample.state().name().toLowerCase(Locale.ROOT));
    }

    private static String coordinates(net.minecraft.core.BlockPos pos) {
        return pos.getX() + " / " + pos.getY() + " / " + pos.getZ();
    }

    private static String percentage(long value, long capacity) {
        if (capacity <= 0 || value <= 0) return "0%";
        if (value >= capacity) return "100%";
        int tenths = Math.min(999, (int) (value * 1000.0 / capacity));
        if (tenths == 0) return "<0.1%";
        return tenths / 10 + "." + tenths % 10 + "%";
    }

    private static void add(ArrayList<Row> rows, Font font, int width, Component key, Component value) {
        var label = label(font, key, value == null ? width - 12 : width / 2 - 8);
        rows.add(new Row(label, value == null ? null : label(font, value, width - label.width - 21)));
    }

    static Label label(Font font, Component component, int width) {
        String plain = component.getString();
        var glyphs = component.getVisualOrderText();
        int size = font.width(glyphs);
        if (size > width) {
            glyphs = Component.literal(font.plainSubstrByWidth(plain, Math.max(0, width - font.width("…"))) + "…")
                    .getVisualOrderText();
            size = font.width(glyphs);
        }
        return new Label(plain, glyphs, size);
    }

    static Component text(String suffix, Object... arguments) {
        return Component.translatable(KEY + suffix, arguments);
    }
}
