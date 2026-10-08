package com.dishanhai.gt_shanhai.common.recipe.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Voltage names written into the EU/t field.
 * ULV through MAX follow the editor table, and MAX is {@code 2147483647}.
 * MAX-2 through MAX+16 follow the ×4 steps above that point. MAX+16 is {@link Long#MAX_VALUE}.
 */
public final class ShanhaiVoltageTiers {

    public record Tier(String name, long eut) {}

    private static final Tier[] TIERS = build();
    private static final List<String> NAMES = namesOf(TIERS);
    private static final List<String> LABELS = labelsOf(TIERS);

    private ShanhaiVoltageTiers() {}

    public static List<String> names() {
        return NAMES;
    }

    public static List<String> labels() {
        return LABELS;
    }

    public static Long eutOfName(String name) {
        if (name == null) return null;
        for (Tier tier : TIERS) {
            if (tier.name.equals(name)) return tier.eut;
        }
        return null;
    }

    public static Long eutOfLabel(String label) {
        if (label == null) return null;
        int split = label.lastIndexOf(' ');
        if (split < 0 || split + 1 >= label.length()) return null;
        try {
            return Long.parseLong(label.substring(split + 1));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** First tier whose voltage equals the field text. Custom numbers return null. */
    public static String nameFor(String eutText) {
        if (eutText == null || eutText.isBlank()) return null;
        long value;
        try {
            value = Long.parseLong(eutText.trim());
        } catch (NumberFormatException invalid) {
            return null;
        }
        for (Tier tier : TIERS) {
            if (tier.eut == value) return tier.name;
        }
        return null;
    }

    static long extended(int offsetFromMax) {
        if (offsetFromMax == 16) return Long.MAX_VALUE;
        int shift = 31 + 2 * offsetFromMax;
        return 1L << shift;
    }

    private static Tier[] build() {
        String[] names = {
                "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV",
                "UHV", "UEV", "UIV", "UXV", "OpV", "MAX"
        };
        long[] base = {
                8L, 32L, 128L, 512L, 2048L, 8192L, 32768L, 131072L, 524288L,
                2097152L, 8388608L, 33554432L, 134217728L, 536870912L, 2147483647L
        };
        List<Tier> tiers = new ArrayList<>();
        for (int i = 0; i < names.length; i++) tiers.add(new Tier(names[i], base[i]));
        for (int offset = -2; offset <= 16; offset++) {
            if (offset == 0) continue;
            String name = offset < 0 ? "MAX" + offset : "MAX+" + offset;
            tiers.add(new Tier(name, extended(offset)));
        }
        return tiers.toArray(new Tier[0]);
    }

    private static List<String> namesOf(Tier[] tiers) {
        List<String> names = new ArrayList<>();
        for (Tier tier : tiers) names.add(tier.name);
        return List.copyOf(names);
    }

    private static List<String> labelsOf(Tier[] tiers) {
        List<String> labels = new ArrayList<>();
        for (Tier tier : tiers) labels.add(tier.name + "  " + tier.eut);
        return List.copyOf(labels);
    }
}
