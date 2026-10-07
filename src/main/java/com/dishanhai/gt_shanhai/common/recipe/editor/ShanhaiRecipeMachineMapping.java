package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.common.recipe.DShanhaiRecipeCache;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * GTL-EH 风格的机器映射：机器幽灵槽 -> MetaMachineItem -> recipe types -> 配方表。
 */
public final class ShanhaiRecipeMachineMapping {

    public record TypeOption(String id, String label, int recipeCount, boolean owned) {}

    private ShanhaiRecipeMachineMapping() {}

    public static List<GTRecipeType> recipeTypes(ItemStack machine) {
        if (machine == null || machine.isEmpty()
                || !(machine.getItem() instanceof MetaMachineItem metaMachine)) {
            return List.of();
        }
        GTRecipeType[] types = metaMachine.getDefinition().getRecipeTypes();
        if (types == null || types.length == 0) {
            MachineDefinition registered = metaMachine.getDefinition().getId() == null
                    ? null : GTRegistries.MACHINES.get(metaMachine.getDefinition().getId());
            GTRecipeType[] registeredTypes = registered == null ? null : registered.getRecipeTypes();
            if (registeredTypes != null && registeredTypes.length > 0) types = registeredTypes;
        }
        if (types == null) return List.of();
        return java.util.Arrays.stream(types)
                .filter(type -> type != null && type.registryName != null)
                .distinct()
                .sorted(Comparator.comparing(type -> type.registryName.toString()))
                .toList();
    }

    public static List<TypeOption> describe(ServerPlayer player, ItemStack machine) {
        if (player == null) return List.of();
        List<TypeOption> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GTRecipeType type : recipeTypes(machine)) {
            addOption(result, seen, player, type, false);
        }
        // gt_shanhai 的类型经 GTCEu.register 后 id 是 gtceu:路径，不在机器 getRecipeTypes() 里就不会出现。
        for (GTRecipeType type : DShanhaiRecipeCache.ownedRecipeTypes()) {
            addOption(result, seen, player, type, true);
        }
        return List.copyOf(result);
    }

    private static void addOption(List<TypeOption> result, Set<String> seen, ServerPlayer player,
                                  GTRecipeType type, boolean owned) {
        if (type == null || type.registryName == null) return;
        String id = type.registryName.toString();
        if (!seen.add(id)) return;
        int count = owned
                ? rawCount(type)
                : RecipeOriginalSnapshotStoreCompat.count(player, type.registryName);
        result.add(new TypeOption(id, ShanhaiRecipeTypeNames.display(type.registryName), count, owned));
    }

    /** Lookup size only. Owned types are listed in bulk, so this does not rebuild each table. */
    private static int rawCount(GTRecipeType type) {
        try {
            if (type.getLookup() == null || type.getLookup().getLookup() == null) return 0;
            int[] count = new int[1];
            type.getLookup().getLookup().getRecipes(true).forEach(recipe -> {
                if (recipe != null) count[0]++;
            });
            return count[0];
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    public static String machineId(ItemStack machine) {
        if (machine == null || machine.isEmpty()) return "";
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(machine.getItem());
        return id == null ? "" : id.toString();
    }

    public static boolean isRegisteredRecipeType(String id) {
        if (id == null || id.isBlank()) return false;
        return GTRegistries.RECIPE_TYPES.get(new ResourceLocation(id)) != null;
    }

    /**
     * Keeps the mapper independent from the internal snapshot implementation.
     * The editor query remains the single source of truth for recipe counts.
     */
    private static final class RecipeOriginalSnapshotStoreCompat {
        private static int count(ServerPlayer player, ResourceLocation type) {
            if (player == null || type == null) return 0;
            return ShanhaiRecipeQuery.query(type.toString(), "", 0, 1).total();
        }
    }
}
