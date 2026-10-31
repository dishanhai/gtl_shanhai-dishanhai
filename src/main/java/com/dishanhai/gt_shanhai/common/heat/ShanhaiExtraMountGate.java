package com.dishanhai.gt_shanhai.common.heat;

// Author: dishanhai
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.machine.multiblock.CleanroomType;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.gregtechceu.gtceu.common.recipe.condition.CleanroomCondition;
import com.gregtechceu.gtceu.common.recipe.condition.DimensionCondition;
import com.gregtechceu.gtceu.common.recipe.condition.ResearchCondition;
import org.gtlcore.gtlcore.common.recipe.condition.GravityCondition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 山海：主機及模組共用掛載聯集；每個需求仍必須由某一格獨立滿足。 */
public final class ShanhaiExtraMountGate {
    private ShanhaiExtraMountGate() {}

    public static List<ShanhaiHeatGate.SlotContent> merge(List<ShanhaiHeatGate.SlotContent> local,
            List<ShanhaiHeatGate.SlotContent> host) {
        if (host == null || host.isEmpty()) return local == null ? List.of() : local;
        var merged = new ArrayList<ShanhaiHeatGate.SlotContent>();
        if (local != null) merged.addAll(local);
        merged.addAll(host);
        return List.copyOf(merged);
    }

    public static ShanhaiHeatGate.Outcome evaluate(List<ShanhaiHeatGate.Requirement> needs,
            List<ShanhaiHeatGate.SlotContent> local, List<ShanhaiHeatGate.SlotContent> host) {
        return ShanhaiHeatGate.evaluate(needs, merge(local, host));
    }

    public static ShanhaiHeatGate.Requirement requirementOf(RecipeCondition condition) {
        if (condition instanceof CleanroomCondition cleanroom) {
            CleanroomType type = cleanroom.getCleanroom();
            int tier = ShanhaiHeatGate.cleanroomTierOfName(type == null ? null : type.getName());
            return tier == 0 ? null : ShanhaiHeatGate.Requirement.cleanroom(tier);
        }
        if (condition instanceof DimensionCondition dimension) {
            var id = dimension.getDimension();
            return id == null ? null : ShanhaiHeatGate.Requirement.dimension(id.toString());
        }
        if (condition instanceof ResearchCondition) return ShanhaiHeatGate.Requirement.research();
        if (condition instanceof GravityCondition) return ShanhaiHeatGate.Requirement.gravity();
        return null;
    }

    private static boolean ignored(ShanhaiHeatGate.Requirement requirement, boolean modulePolicy,
            boolean unrestricted) {
        return modulePolicy && requirement != null
                && ShanhaiHeatGate.isRequirementIgnored(requirement.kind, unrestricted);
    }

    /** OR 組只需要一個原生成立或掛載可滿足的分支，不把其它分支錯當成 AND。 */
    public static List<ShanhaiHeatGate.Requirement> conditionRequirements(GTRecipe recipe, RecipeLogic logic,
            List<ShanhaiHeatGate.SlotContent> slots, boolean modulePolicy, boolean unrestricted) {
        var needs = new ArrayList<ShanhaiHeatGate.Requirement>();
        var groups = new LinkedHashMap<RecipeConditionType<?>, List<RecipeCondition>>();
        if (recipe.conditions == null) return needs;
        for (RecipeCondition condition : recipe.conditions) {
            if (condition == null) continue;
            if (condition.isOr()) {
                groups.computeIfAbsent(condition.getType(), unused -> new ArrayList<>()).add(condition);
            } else if (!condition.isReverse()) {
                var requirement = requirementOf(condition);
                if (requirement != null && !ignored(requirement, modulePolicy, unrestricted)
                        && !condition.test(recipe, logic)) needs.add(requirement);
            }
        }
        for (var group : groups.values()) {
            boolean nativePass = group.stream().anyMatch(condition ->
                    condition.test(recipe, logic) != condition.isReverse()
                    || (!condition.isReverse() && ignored(requirementOf(condition), modulePolicy, unrestricted)));
            if (nativePass) continue;
            ShanhaiHeatGate.Requirement first = null;
            for (RecipeCondition condition : group) {
                if (condition.isReverse()) continue;
                var requirement = requirementOf(condition);
                if (requirement == null) continue;
                if (first == null) first = requirement;
                if (ShanhaiHeatGate.evaluate(List.of(requirement), slots).allowed) {
                    first = requirement;
                    break;
                }
            }
            if (first != null) needs.add(first);
        }
        return needs;
    }

    public static boolean conditionsPass(GTRecipe recipe, RecipeLogic logic, ShanhaiHeatGate.Outcome outcome,
            boolean modulePolicy, boolean unrestricted) {
        Map<RecipeConditionType<?>, Boolean> allFail = new LinkedHashMap<>();
        if (recipe.conditions == null) return true;
        for (RecipeCondition condition : recipe.conditions) {
            if (condition == null) continue;
            var requirement = requirementOf(condition);
            boolean passed = condition.test(recipe, logic) != condition.isReverse();
            if (!condition.isReverse()) {
                passed |= ignored(requirement, modulePolicy, unrestricted)
                        || (outcome != null && requirement != null && outcome.satisfied.contains(requirement));
            }
            if (condition.isOr()) allFail.merge(condition.getType(), !passed, (left, right) -> left && right);
            else if (!passed) return false;
        }
        return allFail.values().stream().noneMatch(Boolean.TRUE::equals);
    }
}
