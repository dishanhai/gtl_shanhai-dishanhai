package com.dishanhai.gt_shanhai.client.gui.starlifter;

import com.dishanhai.gt_shanhai.client.gui.starlifter.StellarHudReadout.Label;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/** Shaped and measured once per font, resource reload, and panel width. */
final class StellarBootReadout {
    final Label target, brand, english, division, motto, terminal, display, profile, telemetry, channel,
            device, structure, scan, module, linked, synchronizedData, waiting, subTerminal;
    final Label[] trackedWordmark;

    StellarBootReadout(Font font, int width) {
        int usable = width - 12;
        target = label(font, "target", usable);
        brand = StellarHudReadout.label(font, CuhiIdentity.name(), usable);
        english = label(font, "wordmark", usable);
        division = StellarHudReadout.label(font, CuhiIdentity.division(), usable);
        motto = StellarHudReadout.label(font, CuhiIdentity.motto(), usable);
        terminal = label(font, "terminal", usable);
        display = label(font, "display", usable);
        profile = label(font, "profile", usable);
        telemetry = label(font, "telemetry", usable);
        channel = label(font, "channel", usable);
        device = label(font, "device", usable);
        structure = label(font, "structure", usable);
        scan = label(font, "scan", usable);
        module = label(font, "module", usable);
        linked = label(font, "linked", usable);
        synchronizedData = label(font, "synchronized", usable);
        waiting = label(font, "waiting", usable);
        subTerminal = label(font, "details", usable);
        if (font.width(english.plain()) <= usable) {
            trackedWordmark = new Label[english.plain().length()];
            for (int i = 0; i < trackedWordmark.length; i++) {
                trackedWordmark[i] = StellarHudReadout.label(font, Component.literal(english.plain().substring(i, i + 1)), usable);
            }
        } else trackedWordmark = new Label[0];
    }

    private static Label label(Font font, String key, int width) {
        return StellarHudReadout.label(font, StellarHudReadout.text("boot." + key), width);
    }
}
