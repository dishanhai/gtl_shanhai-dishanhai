package com.dishanhai.gt_shanhai.api;

import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineHost;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;

import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配方模块条件：.ml("moduleId", level)
 *
 * 指定配方需要机器上安装了多少个特定模块。
 * moduleId: 模块物品 ID 后缀，如 "wzjc"（物质基础）
 * level: 需要该模块的数量
 */
public class ModuleLevelCondition extends RecipeCondition {

    public static final Codec<ModuleLevelCondition> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("module_id").forGetter(c -> c.moduleId),
                    Codec.INT.fieldOf("level").forGetter(c -> c.requiredLevel)
            ).apply(instance, ModuleLevelCondition::new)
    );

    public static final RecipeConditionType<ModuleLevelCondition> TYPE = new RecipeConditionType<>(
            () -> new ModuleLevelCondition("", 0),
            ModuleLevelCondition.CODEC
    );

    public final String moduleId;
    public final int requiredLevel;

    // ====== 静态注册表：绕过 KubeJS 序列化/反序列化问题 ======
    /** 配方ID → 模块条件列表 */
    private static final Map<String, List<ModuleLevelCondition>> REQUIREMENTS = new ConcurrentHashMap<>();

    /** 配方注册时调用，将条件存入内存表 */
    public static void register(String recipeId, ModuleLevelCondition cond) {
        REQUIREMENTS.computeIfAbsent(recipeId, k -> new ArrayList<>()).add(cond);
    }

    /** 运行时查询配方模块条件（模糊匹配 ID） */
    public static List<ModuleLevelCondition> getRequirements(String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) return null;
        // 精确匹配
        List<ModuleLevelCondition> exact = REQUIREMENTS.get(recipeId);
        if (exact != null) return exact;
        // 提取注册 ID 和 GTRecipe ID 的公共后缀做模糊匹配（如 kubejs:xxx vs gtceu:type/xxx）
        String suffix = lastSegment(recipeId);
        for (var entry : REQUIREMENTS.entrySet()) {
            String keySuffix = lastSegment(entry.getKey());
            if (!suffix.isEmpty() && (suffix.equals(keySuffix) || suffix.contains(keySuffix) || keySuffix.contains(suffix))) {
                return entry.getValue();
            }
            if (recipeId.contains(entry.getKey()) || entry.getKey().contains(recipeId)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 提取 ID 最后一段（去命名空间和路径前缀） */
    private static String lastSegment(String id) {
        if (id == null || id.isEmpty()) return "";
        // 先取 / 最后一段，再取 : 最后一段
        int slash = id.lastIndexOf('/');
        int colon = id.lastIndexOf(':');
        int start = Math.max(slash, colon);
        return start >= 0 && start < id.length() - 1 ? id.substring(start + 1) : id;
    }

    public ModuleLevelCondition(String moduleId, int level) {
        super(false);
        this.moduleId = moduleId;
        this.requiredLevel = level;
    }

    @Override
    public RecipeConditionType<?> getType() {
        return TYPE;
    }

    @Override
    public Component getTooltips() {
        return Component.literal("§b模块要求：").append(getStyledName()).append(Component.literal(" §7×" + requiredLevel));
    }

    public Component getFailTooltip() {
        return Component.literal("§c✗ 模块不足：").append(getStyledName()).append(Component.literal(" §7×" + requiredLevel));
    }

    public Component getPassTooltip() {
        return Component.literal("§a✓ 模块满足：").append(getStyledName()).append(Component.literal(" §7×" + requiredLevel));
    }

    /** 解析 &$style-/&Sstyle- 前缀并用 FCS 渲染，未匹配则回退为 body_silver */
    private Component getStyledName() {
        String raw = getRawDisplayName();
        // 解析 &$style- 或 &Sstyle- 前缀
        if (raw.startsWith("&$")) {
            int dash = raw.indexOf('-');
            if (dash > 0) {
                String style = raw.substring(2, dash);
                String text = raw.substring(dash + 1);
                if (!text.isEmpty()) {
                    try { return DShanhaiTextUtil.createStyled(text, style); } catch (Exception ignored) {}
                }
            }
        }
        // 无 &$ 前缀 → 用 body_silver 内联渲染
        try { return ShanhaiTextAPI.inline("{body_silver}" + raw + "{/}"); }
        catch (Exception e) { return Component.literal(raw); }
    }

    /** 从物品 Component 取原文（含未解析的 &$ 前缀） */
    private String getRawDisplayName() {
        try {
            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                    new net.minecraft.resources.ResourceLocation(moduleId));
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                return item.getDefaultInstance().getHoverName().getString();
            }
        } catch (Exception ignored) {}
        return moduleId;
    }

    /** 模块侧直接检查——只看当前模块自己的槽 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean checkModuleLevel(MetaMachine machine) {
        // 模块机器路径（PrimordialModuleRecipeLogic 调用）：只看自己的槽
        if (machine instanceof com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase mb) {
            String slotId = mb.getModuleItemId();
            int requiredLv = com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase.getModuleLevelById(moduleId);
            LOG.debug("[MLC] check self {}: slotId={} requiredLv={} needCount={}",
                    mb.getClass().getSimpleName(), slotId, requiredLv, requiredLevel);
            if (slotId == null) return false;
            int slotLv = com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase.getModuleLevelById(slotId);
            if (slotLv < requiredLv) return false;
            int multiplier = slotLv - requiredLv + 1;
            int count = mb.getModuleCount() * multiplier;
            LOG.debug("[MLC]   slotLv={} multiplier={}x stackCount={} -> count={}", slotLv, multiplier, mb.getModuleCount(), count);
            return count >= requiredLevel;
        }

        // 主机路径：遍历所有模块
        IModularMachineHost host = null;
        if (machine instanceof IModularMachineHost h) {
            host = h;
        } else if (machine instanceof IModularMachineModule mod) {
            Object h2 = mod.getHost();
            if (h2 instanceof IModularMachineHost h3) host = h3;
        }
        if (host == null || !host.isFormed()) return false;

        Set modules = host.getModuleSet();
        if (modules == null || modules.isEmpty()) return false;

        int requiredLv = com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase.getModuleLevelById(moduleId);
        LOG.debug("[MLC] check host: requiredLv={} needCount={} hostModules={}", requiredLv, requiredLevel, modules.size());
        int count = 0;
        for (Object mod : modules) {
            if (!(mod instanceof com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase mb)) continue;
            String slotId = mb.getModuleItemId();
            if (slotId == null) continue;
            int slotLv = com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase.getModuleLevelById(slotId);
            if (slotLv >= requiredLv) {
                count += mb.getModuleCount() * (slotLv - requiredLv + 1);
            }
        }
        return count >= requiredLevel;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean test(GTRecipe recipe, RecipeLogic recipeLogic) {
        MetaMachine machine = recipeLogic.getMachine();
        IModularMachineHost host = null;
        if (machine instanceof IModularMachineHost h) {
            host = h;
        } else if (machine instanceof IModularMachineModule mod) {
            Object h2 = mod.getHost();
            if (h2 instanceof IModularMachineHost h3) host = h3;
        }
        if (host == null || !host.isFormed()) return false;

        @SuppressWarnings("rawtypes")
        Set modules = host.getModuleSet();
        if (modules == null || modules.isEmpty()) return false;

        int requiredLv = com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase.getModuleLevelById(moduleId);
        LOG.debug("[MLC] check {}: requiredLv={} needCount={} hostModules={}", moduleId, requiredLv, requiredLevel, modules.size());
        int count = 0;
        for (Object mod : modules) {
            if (!(mod instanceof com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase mb)) continue;
            String slotId = mb.getModuleItemId();
            if (slotId == null) continue;
            int slotLv = com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase.getModuleLevelById(slotId);
            if (slotLv >= requiredLv) {
                count += mb.getModuleCount() * (slotLv - requiredLv + 1);
            }
        }

        boolean result = count >= requiredLevel;
        LOG.debug("[MLC] result={} (count={} >= {})", result, count, requiredLevel);
        if (!result) LOG.warn("[ModuleLCond] FAIL {}: need {}x {} found {}", moduleId, requiredLevel, moduleId, count);
        return result;
    }

    @Override
    public RecipeCondition createTemplate() {
        return new ModuleLevelCondition("", 0);
    }

    @Override
    public JsonObject serialize() {
        JsonObject json = super.serialize();
        json.addProperty("module_id", moduleId);
        json.addProperty("level", requiredLevel);
        return json;
    }

    @Override
    public ModuleLevelCondition deserialize(JsonObject json) {
        super.deserialize(json);
        return new ModuleLevelCondition(
                json.get("module_id").getAsString(),
                json.get("level").getAsInt()
        );
    }

    @Override
    public void toNetwork(FriendlyByteBuf buf) {
        super.toNetwork(buf);
        buf.writeUtf(moduleId);
        buf.writeVarInt(requiredLevel);
    }

    @Override
    public ModuleLevelCondition fromNetwork(FriendlyByteBuf buf) {
        super.fromNetwork(buf);
        return new ModuleLevelCondition(buf.readUtf(), buf.readVarInt());
    }
    private static final Logger LOG = LoggerFactory.getLogger("ModuleLCond");
}
