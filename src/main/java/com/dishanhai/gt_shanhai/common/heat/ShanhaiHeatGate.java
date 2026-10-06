package com.dishanhai.gt_shanhai.common.heat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原初模块额外挂载槽判定核心。
 *
 * <p>本类只依赖 {@code java.*}，物品如何提供能力由 {@link ShanhaiHeatSources} 负责。
 */
public final class ShanhaiHeatGate {

    private ShanhaiHeatGate() {}

    public static final int REQUIRED_COUNT = 64;
    public static final int SLOT_COUNT = 3;

    public static final int CLEANROOM_NONE = 0;
    public static final int CLEANROOM_PLAIN = 1;
    public static final int CLEANROOM_STERILE = 2;
    public static final int CLEANROOM_LAW = 3;

    public static final String KEY_EBF_TEMP = "ebf_temp";
    public static final String KEY_SC_TIER = "SCTier";

    public static final Set<String> GATED_TYPE_IDS = Set.of(
            "gtceu:alloy_blast_smelter",
            "gtceu:electric_blast_furnace",
            "gtceu:dimensionally_transcendent_plasma_forge",
            "gtceu:chaotic_alchemy",
            "gtceu:stellar_lgnition",
            "gtceu:stellar_forge",
            "gtceu:distort");

    public static final Set<String> HEAT_SLOT_MACHINE_IDS = Set.of(
            "gt_shanhai:taixu_smelting_furnace",
            "gt_shanhai:primordial_eternal_smelting_furnace",
            "gt_shanhai:primordial_molecular_rift_core");

    public static boolean isGated(String typeId) {
        return typeId != null && GATED_TYPE_IDS.contains(typeId);
    }

    public static boolean hasHeatSlot(String machineId) {
        return machineId != null && HEAT_SLOT_MACHINE_IDS.contains(machineId);
    }

    public static String verifyMachineIds(List<String> registeredMachineIds) {
        if (registeredMachineIds == null || registeredMachineIds.isEmpty()) {
            return "额外挂载热力白名单自检失败：已注册机器列表为空";
        }
        StringBuilder missing = new StringBuilder();
        for (String wanted : HEAT_SLOT_MACHINE_IDS) {
            if (!registeredMachineIds.contains(wanted)) {
                if (missing.length() > 0) {
                    missing.append(" / ");
                }
                missing.append(wanted);
            }
        }
        return missing.length() == 0
                ? null
                : "额外挂载热力白名单缺少机器：" + missing;
    }

    public enum Kind {
        CLEANROOM,
        GRAVITY,
        DIMENSION,
        RESEARCH,
        HEAT_TEMP,
        SC_TIER
    }

    /**
     * 原初模块的条件策略。
     *
     * <p>正常模式忽略研究/数据访问，其余额外挂载条件照常生效；无限制模式只保留重力条件。
     */
    public static boolean isRequirementEnforced(Kind kind, boolean unrestrictedMode) {
        if (kind == null || kind == Kind.RESEARCH) {
            return false;
        }
        return !unrestrictedMode || kind == Kind.GRAVITY;
    }

    public static boolean isRequirementIgnored(Kind kind, boolean unrestrictedMode) {
        return !isRequirementEnforced(kind, unrestrictedMode);
    }

    public static final class Requirement {
        public final Kind kind;
        public final int number;
        public final String text;

        private Requirement(Kind kind, int number, String text) {
            this.kind = kind;
            this.number = number;
            this.text = text;
        }

        public static Requirement cleanroom(int tier) {
            return new Requirement(Kind.CLEANROOM, tier, null);
        }

        public static Requirement gravity() {
            return new Requirement(Kind.GRAVITY, 0, null);
        }

        public static Requirement dimension(String dimensionId) {
            return new Requirement(Kind.DIMENSION, 0, dimensionId);
        }

        public static Requirement research() {
            return new Requirement(Kind.RESEARCH, 0, null);
        }

        public static Requirement heatTemp(int kelvin) {
            return new Requirement(Kind.HEAT_TEMP, kelvin, null);
        }

        public static Requirement scTier(int tier) {
            return new Requirement(Kind.SC_TIER, tier, null);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Requirement requirement)) {
                return false;
            }
            return kind == requirement.kind
                    && number == requirement.number
                    && (text == null ? requirement.text == null : text.equals(requirement.text));
        }

        @Override
        public int hashCode() {
            return (kind.ordinal() * 31 + number) * 31 + (text == null ? 0 : text.hashCode());
        }

        public String describe() {
            return switch (kind) {
                case CLEANROOM -> "超净间：" + cleanroomName(number);
                case GRAVITY -> "重力控制";
                case DIMENSION -> "维度：" + text;
                case RESEARCH -> "研究";
                case HEAT_TEMP -> "线圈炉温：" + number + "K";
                case SC_TIER -> "恒星热力容器等级：" + number;
            };
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    public static String cleanroomName(int tier) {
        return switch (tier) {
            case CLEANROOM_PLAIN -> "cleanroom";
            case CLEANROOM_STERILE -> "sterile_cleanroom";
            case CLEANROOM_LAW -> "law_cleanroom";
            default -> "unknown";
        };
    }

    public static int cleanroomTierOfName(String name) {
        if (name == null) {
            return CLEANROOM_NONE;
        }
        return switch (name) {
            case "cleanroom" -> CLEANROOM_PLAIN;
            case "sterile_cleanroom" -> CLEANROOM_STERILE;
            case "law_cleanroom" -> CLEANROOM_LAW;
            default -> CLEANROOM_NONE;
        };
    }

    public static final class SlotContent {
        public static final SlotContent EMPTY = new SlotContent(
                0, 0, 0, 0, false, false, Set.of());

        public final int count;
        public final int coilTemperature;
        public final int containmentTier;
        public final int cleanroomTier;
        public final boolean gravity;
        public final boolean research;
        public final Set<String> dimensions;

        public SlotContent(int count, int coilTemperature, int containmentTier,
                           int cleanroomTier, boolean gravity, boolean research,
                           Set<String> dimensions) {
            this.count = count;
            this.coilTemperature = coilTemperature;
            this.containmentTier = containmentTier;
            this.cleanroomTier = cleanroomTier;
            this.gravity = gravity;
            this.research = research;
            this.dimensions = dimensions == null ? Set.of() : Set.copyOf(dimensions);
        }

        public boolean isBlank() {
            return coilTemperature <= 0 && containmentTier <= 0 && cleanroomTier <= 0
                    && !gravity && !research && dimensions.isEmpty();
        }

        public String describe() {
            StringBuilder result = new StringBuilder("×").append(count);
            if (coilTemperature > 0) {
                result.append(" 线圈 ").append(coilTemperature).append("K");
            }
            if (containmentTier > 0) {
                result.append(" 恒星热力容器 ").append(containmentTier).append("级");
            }
            if (cleanroomTier > 0) {
                result.append(' ').append(cleanroomName(cleanroomTier));
            }
            if (gravity) {
                result.append(" 重力");
            }
            if (research) {
                result.append(" 研究");
            }
            for (String dimension : dimensions) {
                result.append(" 维度[").append(dimension).append(']');
            }
            if (isBlank()) {
                result.append(" 非挂载物");
            }
            return result.toString();
        }
    }

    public enum Deny {
        NONE,
        SLOT_EMPTY,
        WRONG_ITEM,
        NOT_FULL,
        CLEANROOM_TIER,
        HEAT_TEMP,
        SC_TIER
    }

    public static final class Outcome {
        public final boolean allowed;
        public final Deny deny;
        public final Requirement blocked;
        public final int have;
        public final List<SlotContent> slots;
        public final List<Requirement> satisfied;
        public final List<Requirement> needs;

        private Outcome(boolean allowed, Deny deny, Requirement blocked, int have,
                        List<SlotContent> slots, List<Requirement> satisfied,
                        List<Requirement> needs) {
            this.allowed = allowed;
            this.deny = deny;
            this.blocked = blocked;
            this.have = have;
            this.slots = List.copyOf(slots);
            this.satisfied = List.copyOf(satisfied);
            this.needs = List.copyOf(needs);
        }

        public String describe() {
            StringBuilder result = new StringBuilder("需求=").append(needs).append(" 槽=");
            for (int i = 0; i < slots.size(); i++) {
                result.append('[').append(i + 1).append(']').append(slots.get(i).describe()).append(' ');
            }
            return result.append("⇒ ").append(allowed ? "放行" : "拦下：" + deny).toString();
        }
    }

    public static Outcome evaluate(List<Requirement> needs, List<SlotContent> slots) {
        List<SlotContent> safeSlots = slots == null ? List.of() : slots;
        List<Requirement> uniqueNeeds = deduplicate(needs);
        List<Requirement> satisfied = new ArrayList<>();
        Requirement blocked = null;
        for (Requirement requirement : uniqueNeeds) {
            if (firstSatisfyingSlot(requirement, safeSlots) >= 0) {
                satisfied.add(requirement);
            } else if (blocked == null) {
                blocked = requirement;
            }
        }
        if (blocked == null) {
            return new Outcome(true, Deny.NONE, null, 0, safeSlots, satisfied, uniqueNeeds);
        }
        return new Outcome(false, denyFor(blocked, safeSlots), blocked,
                haveFor(blocked, safeSlots), safeSlots, satisfied, uniqueNeeds);
    }

    public static boolean slotSatisfies(Requirement requirement, SlotContent slot) {
        if (requirement == null || slot == null || slot.count <= 0) {
            return false;
        }
        return switch (requirement.kind) {
            case CLEANROOM -> slot.cleanroomTier >= requirement.number;
            case GRAVITY -> slot.gravity;
            case DIMENSION -> slot.dimensions.contains(requirement.text);
            case RESEARCH -> slot.research;
            case HEAT_TEMP -> slot.count >= REQUIRED_COUNT
                    && slot.coilTemperature >= requirement.number;
            case SC_TIER -> slot.count >= REQUIRED_COUNT
                    && slot.containmentTier >= requirement.number;
        };
    }

    private static List<Requirement> deduplicate(List<Requirement> needs) {
        List<Requirement> result = new ArrayList<>();
        if (needs != null) {
            for (Requirement requirement : needs) {
                if (requirement != null && !result.contains(requirement)) {
                    result.add(requirement);
                }
            }
        }
        return result;
    }

    private static int firstSatisfyingSlot(Requirement requirement, List<SlotContent> slots) {
        for (int i = 0; i < slots.size(); i++) {
            if (slotSatisfies(requirement, slots.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int haveFor(Requirement requirement, List<SlotContent> slots) {
        int best = 0;
        for (SlotContent slot : slots) {
            int value = switch (requirement.kind) {
                case CLEANROOM -> slot.cleanroomTier;
                case HEAT_TEMP -> slot.coilTemperature;
                case SC_TIER -> slot.containmentTier;
                default -> 0;
            };
            best = Math.max(best, value);
        }
        return best;
    }

    private static Deny denyFor(Requirement requirement, List<SlotContent> slots) {
        boolean anyItem = false;
        for (SlotContent slot : slots) {
            if (slot.count > 0) {
                anyItem = true;
            }
        }
        if (!anyItem) {
            return Deny.SLOT_EMPTY;
        }
        if (requirement.kind == Kind.HEAT_TEMP) {
            for (SlotContent slot : slots) {
                if (slot.coilTemperature > 0 && slot.count < REQUIRED_COUNT) {
                    return Deny.NOT_FULL;
                }
            }
            return haveFor(requirement, slots) > 0 ? Deny.HEAT_TEMP : Deny.WRONG_ITEM;
        }
        if (requirement.kind == Kind.SC_TIER) {
            for (SlotContent slot : slots) {
                if (slot.containmentTier > 0 && slot.count < REQUIRED_COUNT) {
                    return Deny.NOT_FULL;
                }
            }
            return haveFor(requirement, slots) > 0 ? Deny.SC_TIER : Deny.WRONG_ITEM;
        }
        if (requirement.kind == Kind.CLEANROOM) {
            return haveFor(requirement, slots) > 0 ? Deny.CLEANROOM_TIER : Deny.WRONG_ITEM;
        }
        return Deny.WRONG_ITEM;
    }

    private static final Map<String, String> DIMENSION_TO_FRAGMENT = buildDimensionTable();

    private static Map<String, String> buildDimensionTable() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("minecraft:overworld", "gtlcore:world_fragments_overworld");
        result.put("minecraft:the_nether", "gtlcore:world_fragments_nether");
        result.put("minecraft:the_end", "gtlcore:world_fragments_end");
        result.put("kubejs:pluto", "gtlcore:world_fragments_pluto");
        result.put("ad_astra:venus", "gtlcore:world_fragments_venus");
        result.put("kubejs:barnarda", "gtlcore:world_fragments_barnarda");
        result.put("ad_astra:moon", "gtlcore:world_fragments_moon");
        result.put("ad_astra:mars", "gtlcore:world_fragments_mars");
        result.put("ad_astra:mercury", "gtlcore:world_fragments_mercury");
        result.put("ad_astra:glacio", "gtlcore:world_fragments_glacio");
        result.put("ad_astra:ceres", "gtlcore:world_fragments_ceres");
        result.put("ad_astra:enceladus", "gtlcore:world_fragments_enceladus");
        result.put("ad_astra:ganymede", "gtlcore:world_fragments_ganymede");
        result.put("ad_astra:io", "gtlcore:world_fragments_io");
        result.put("ad_astra:titan", "gtlcore:world_fragments_titan");
        result.put("ad_astra:pluto", "gtlcore:world_fragments_pluto");
        result.put("kubejs:ancient_world", "gtlcore:world_fragments_reactor");
        result.put("kubejs:create", "dishanhai:world_fragments_creation");
        return Map.copyOf(result);
    }

    public static String fragmentForDimension(String dimensionId) {
        return dimensionId == null ? null : DIMENSION_TO_FRAGMENT.get(dimensionId);
    }

    public static String dimensionOfFragment(String itemId) {
        if (itemId == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : DIMENSION_TO_FRAGMENT.entrySet()) {
            if (entry.getValue().equals(itemId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static Map<String, String> dimensionTable() {
        return DIMENSION_TO_FRAGMENT;
    }
}
