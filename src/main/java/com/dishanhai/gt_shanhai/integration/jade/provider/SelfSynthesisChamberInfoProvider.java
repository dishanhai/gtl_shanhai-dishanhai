package com.dishanhai.gt_shanhai.integration.jade.provider;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.common.machine.part.SelfSynthesisChamberPartMachine;
import com.dishanhai.gt_shanhai.common.machine.part.SelfSynthesisUpload;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import appeng.api.inventories.InternalInventory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

import java.util.List;

/**
 * 自合成仓 Jade 文本信息。
 */
public enum SelfSynthesisChamberInfoProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = new ResourceLocation("gt_shanhai", "self_synthesis_chamber_info");
    private static final String DATA_KEY = "selfSynthesisChamber";
    private static final int MAX_VISIBLE_TYPES = 5;

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        try {
            SelfSynthesisChamberPartMachine machine = getMachine(accessor);
            if (machine == null) return;

            CompoundTag tag = new CompoundTag();
            tag.putBoolean("online", machine.isOnline());
            var node = machine.getMainNode();
            tag.putBoolean("nodeOnline", node != null && node.isOnline());
            tag.putBoolean("powered", node != null && node.isPowered());
            tag.putBoolean("active", node != null && node.isActive());
            tag.putBoolean("busy", machine.isBusy());
            boolean hasEmpty = machine.hasEmptyPatternSlot();
            tag.putBoolean("hasEmpty", hasEmpty);
            tag.putBoolean("uploadReady", hasEmpty && node != null && node.isActive());

            int slots = 0;
            int filled = 0;
            InternalInventory inventory = machine.getTerminalPatternInventory();
            if (inventory != null) {
                slots = inventory.size();
                for (int slot = 0; slot < slots; slot++) {
                    if (!inventory.getStackInSlot(slot).isEmpty()) {
                        filled++;
                    }
                }
            }
            tag.putInt("slots", slots);
            tag.putInt("filled", filled);
            tag.putInt("patterns", machine.getAvailablePatterns().size());
            tag.putString("group", SelfSynthesisUpload.DISPLAY_NAME);
            tag.putString("types", joinTypeNames(machine.storedRecipeTypeIds()));
            data.put(DATA_KEY, tag);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (getMachine(accessor) == null) return;
        CompoundTag data = accessor.getServerData().getCompound(DATA_KEY);
        if (data == null || data.isEmpty()) return;

        IElementHelper helper = IElementHelper.get();
        boolean online = data.getBoolean("online") || data.getBoolean("nodeOnline");
        boolean powered = data.getBoolean("powered");
        tooltip.add(helper.text(Component.literal("§6§l自合成仓")));
        if (online && powered) {
            tooltip.add(helper.text(Component.literal("§a◆ 设备在线")));
        } else if (online) {
            tooltip.add(helper.text(Component.literal("§e◆ 设备在线 §7(缺少 AE 能量)")));
        } else {
            tooltip.add(helper.text(Component.literal("§c◆ 设备离线")));
        }
        tooltip.add(helper.text(Component.literal(data.getBoolean("active")
                ? "§a◆ AE 节点: 活跃"
                : "§8◆ AE 节点: 未激活")));
        tooltip.add(helper.text(Component.literal("§7◆ 样板: §f" + data.getInt("filled")
                + "/" + data.getInt("slots")
                + " §7有效 §f" + data.getInt("patterns"))));
        tooltip.add(helper.text(Component.literal(data.getBoolean("busy")
                ? "§e◆ 发配: 正在退回本网"
                : "§a◆ 发配: 空闲")));
        String upload;
        if (data.getBoolean("uploadReady")) {
            upload = "§a◆ 上传: 有空槽";
        } else if (!data.getBoolean("hasEmpty")) {
            upload = "§c◆ 上传: 样板已满";
        } else {
            upload = "§c◆ 上传: 节点未激活";
        }
        tooltip.add(helper.text(Component.literal(upload)));
        tooltip.add(helper.text(Component.literal("§7◆ 分组: §d" + data.getString("group"))));
        String types = data.getString("types");
        if (types != null && !types.isEmpty()) {
            tooltip.add(helper.text(Component.literal("§7◆ 样板类型: §f" + types)));
        }
    }

    private static SelfSynthesisChamberPartMachine getMachine(BlockAccessor accessor) {
        if (accessor == null || accessor.getBlockEntity() == null) return null;
        if (!(accessor.getBlockEntity() instanceof MetaMachineBlockEntity mbe)) return null;
        if (!(mbe.getMetaMachine() instanceof SelfSynthesisChamberPartMachine machine)) return null;
        return machine;
    }

    private static String joinTypeNames(List<String> recipeTypeIds) {
        if (recipeTypeIds == null || recipeTypeIds.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        int size = recipeTypeIds.size();
        for (String recipeTypeId : recipeTypeIds) {
            if (shown >= MAX_VISIBLE_TYPES) break;
            String display = displayName(recipeTypeId);
            if (display.isEmpty()) continue;
            if (builder.length() > 0) builder.append(" / ");
            builder.append(display);
            shown++;
        }
        if (size > MAX_VISIBLE_TYPES) {
            if (builder.length() > 0) builder.append(" / ");
            builder.append("等").append(size - MAX_VISIBLE_TYPES).append("项");
        }
        return builder.toString();
    }

    private static String displayName(String recipeTypeId) {
        if (recipeTypeId == null || recipeTypeId.isBlank()) return "";
        if (SelfSynthesisUpload.RECIPE_TYPE_ID.equals(recipeTypeId)) {
            return SelfSynthesisUpload.DISPLAY_NAME;
        }
        GTRecipeType type = PatternRecipeTypeHelper.resolveRecipeType(recipeTypeId);
        if (type == null || type.registryName == null) return recipeTypeId;
        String key = "gtceu." + type.registryName.getPath();
        String translated = Component.translatable(key).getString();
        return translated.equals(key) ? recipeTypeId : translated;
    }
}
