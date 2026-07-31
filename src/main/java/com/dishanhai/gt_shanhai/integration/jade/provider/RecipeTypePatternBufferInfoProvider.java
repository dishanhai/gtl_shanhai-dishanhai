package com.dishanhai.gt_shanhai.integration.jade.provider;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.item.WildcardPatternRecipeTypeBinding;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferProxyPartMachine;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 星律样板总成 Jade 文本信息。
 */
public enum RecipeTypePatternBufferInfoProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "recipe_type_pattern_buffer_info");
    private static final int MAX_VISIBLE_TYPES = 5;
    private static final String DATA_KEY = "recipeTypePatternBuffer";

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        try {
            if (accessor == null || accessor.getBlockEntity() == null) return;
            if (!(accessor.getBlockEntity() instanceof MetaMachineBlockEntity mbe)) return;

            Object metaMachine = mbe.getMetaMachine();
            RecipeTypePatternBufferPartMachine machine = null;
            RecipeTypePatternBufferProxyPartMachine proxy = null;
            if (metaMachine instanceof RecipeTypePatternBufferPartMachine targetMachine) {
                machine = targetMachine;
            } else if (metaMachine instanceof RecipeTypePatternBufferProxyPartMachine targetProxy) {
                proxy = targetProxy;
                Object buffer = targetProxy.getBuffer();
                if (buffer instanceof RecipeTypePatternBufferPartMachine targetMachine) {
                    machine = targetMachine;
                }
            } else {
                return;
            }

            CompoundTag tag = new CompoundTag();
            tag.putBoolean("proxy", proxy != null);
            if (proxy != null) {
                tag.putBoolean("bound", machine != null);
                if (machine != null) {
                    BlockPos pos = machine.getPos();
                    tag.putIntArray("boundPos", new int[] {pos.getX(), pos.getY(), pos.getZ()});
                }
            }

            if (machine != null) {
                var node = machine.getMainNode();
                tag.putBoolean("formed", machine.isFormed());
                tag.putInt("patternSlots", machine.getPatternInventory().getSlots());
                tag.putInt("proxyCount", machine.getProxies().size());
                tag.putBoolean("nodeOnline", node != null && node.isOnline());
                tag.putBoolean("nodePowered", node != null && node.isPowered());
                tag.putBoolean("nodeActive", node != null && node.isActive());
                tag.putInt("warningCount", machine.gtShanhai$getWarningSlotCount());
                tag.putInt("stuckWarningCount", machine.gtShanhai$getStuckWarningSlotCount());
                tag.putString("hostTypes", joinRecipeTypeNames(collectHostRecipeTypeIds(machine)));
                tag.putString("patternTypes", joinRecipeTypeNames(collectPatternRecipeTypeIds(machine)));
            }

            data.put(DATA_KEY, tag);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (accessor == null || accessor.getBlockEntity() == null) return;
        if (!(accessor.getBlockEntity() instanceof MetaMachineBlockEntity mbe)) return;

        Object metaMachine = mbe.getMetaMachine();
        boolean proxy = metaMachine instanceof RecipeTypePatternBufferProxyPartMachine;
        if (!(metaMachine instanceof RecipeTypePatternBufferPartMachine)
                && !(metaMachine instanceof RecipeTypePatternBufferProxyPartMachine)) {
            return;
        }

        CompoundTag data = accessor.getServerData().getCompound(DATA_KEY);
        if (data == null || data.isEmpty()) return;

        IElementHelper helper = IElementHelper.get();
        tooltip.add(helper.text(Component.literal(proxy ? "§b§l星律样板代理" : "§b§l星律样板总成")));

        if (proxy) {
            if (!data.getBoolean("bound")) {
                tooltip.add(helper.text(Component.literal("§c◆ 代理未绑定主机")));
                return;
            }
            if (data.contains("boundPos")) {
                int[] pos = data.getIntArray("boundPos");
                if (pos.length >= 3) {
                    tooltip.add(helper.text(Component.literal("§7◆ 绑定主机: §f"
                            + pos[0] + ", " + pos[1] + ", " + pos[2])));
                }
            }
        }

        tooltip.add(helper.text(Component.literal(
                data.getBoolean("formed") ? "§a◆ 结构已成型" : "§c◆ 结构未成型")));
        tooltip.add(helper.text(Component.literal("§7◆ 样板槽位: §f" + data.getInt("patternSlots"))));
        tooltip.add(helper.text(Component.literal("§7◆ 已绑定代理: §f" + data.getInt("proxyCount"))));
        tooltip.add(helper.text(Component.literal("§7◆ ME节点: " + formatNodeState(
                data.getBoolean("nodeOnline"),
                data.getBoolean("nodePowered"),
                data.getBoolean("nodeActive")))));

        String hostTypes = data.getString("hostTypes");
        tooltip.add(helper.text(Component.literal(hostTypes.isEmpty()
                ? "§7◆ 主机配方类型: §8无"
                : "§7◆ 主机配方类型: §f" + hostTypes)));

        String patternTypes = data.getString("patternTypes");
        tooltip.add(helper.text(Component.literal(patternTypes.isEmpty()
                ? "§7◆ 样板配方类型: §8无"
                : "§7◆ 样板配方类型: §f" + patternTypes)));

        tooltip.add(helper.text(Component.literal("§7◆ 异常槽位: §f" + data.getInt("warningCount")
                + " §7/ §c卡死槽位: §f" + data.getInt("stuckWarningCount"))));
    }

    private static List<String> collectHostRecipeTypeIds(RecipeTypePatternBufferPartMachine machine) {
        List<GTRecipeType> types = WildcardPatternRecipeTypeBinding.collectHostRecipeTypes(machine.getControllers());
        if (types.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(types.size());
        for (GTRecipeType type : types) {
            if (type != null && type.registryName != null) {
                result.add(type.registryName.toString());
            }
        }
        return result;
    }

    private static List<String> collectPatternRecipeTypeIds(RecipeTypePatternBufferPartMachine machine) {
        int slotCount = machine.getPatternInventory().getSlots();
        if (slotCount <= 0) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (int slot = 0; slot < slotCount; slot++) {
            String recipeTypeId = PatternRecipeTypeHelper.readRecipeTypeId(machine.gtShanhai$getPatternStack(slot));
            if (!recipeTypeId.isEmpty()) {
                result.add(recipeTypeId);
            }
        }
        return new ArrayList<>(result);
    }

    private static String joinRecipeTypeNames(List<String> recipeTypeIds) {
        if (recipeTypeIds == null || recipeTypeIds.isEmpty()) return "";
        LinkedHashSet<String> displayNames = new LinkedHashSet<>();
        for (String recipeTypeId : recipeTypeIds) {
            String display = recipeTypeDisplayName(recipeTypeId);
            if (!display.isEmpty()) {
                displayNames.add(display);
            }
        }
        if (displayNames.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        int size = displayNames.size();
        for (String displayName : displayNames) {
            if (shown >= MAX_VISIBLE_TYPES) break;
            if (builder.length() > 0) builder.append(" / ");
            builder.append(displayName);
            shown++;
        }
        if (size > MAX_VISIBLE_TYPES) {
            if (builder.length() > 0) builder.append(" / ");
            builder.append("等").append(size - MAX_VISIBLE_TYPES).append("项");
        }
        return builder.toString();
    }

    private static String recipeTypeDisplayName(String recipeTypeId) {
        GTRecipeType type = PatternRecipeTypeHelper.resolveRecipeType(recipeTypeId);
        if (type == null || type.registryName == null) return recipeTypeId == null ? "" : recipeTypeId;
        for (String key : recipeTypeLanguageKeys(type)) {
            String translated = Component.translatable(key).getString();
            if (!translated.equals(key)) {
                return translated;
            }
        }
        return recipeTypeId;
    }

    private static String[] recipeTypeLanguageKeys(GTRecipeType type) {
        String namespace = type.registryName.getNamespace();
        String path = type.registryName.getPath();
        return new String[] {
                type.registryName.toLanguageKey(),
                "gtceu." + path,
                "gtceu.recipe_type." + path,
                "recipe_type." + path,
                "gtceu.recipe_type." + namespace + "." + path
        };
    }

    private static String formatNodeState(boolean online, boolean powered, boolean active) {
        return (online ? "§a在线" : "§c离线")
                + " §7/ " + (powered ? "§a供电" : "§7缺电")
                + " §7/ " + (active ? "§a活跃" : "§7停用");
    }
}
