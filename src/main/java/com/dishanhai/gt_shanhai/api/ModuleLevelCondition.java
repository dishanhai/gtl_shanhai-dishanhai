package com.dishanhai.gt_shanhai.api;

import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineHost;
import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;

import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;


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

    /** 每次配方重载前清空上一轮条件，避免修改后的旧需求继续残留。 */
    public static void clearRequirements() {
        REQUIREMENTS.clear();
    }

    /** 运行时按完整配方 ID 精确查询，禁止相似配方之间串条件。 */
    public static List<ModuleLevelCondition> getRequirements(String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) return null;
        return REQUIREMENTS.get(recipeId);
    }

    /** 等级差 0~3 时逐级翻倍；高出要求 4 级或以上时直接视为无限等效量。 */
    public static long calculateEquivalentCount(int installedLevel, int installedCount, int requiredModuleLevel) {
        return ModuleLevelEquivalence.calculateEquivalentCount(installedLevel, installedCount, requiredModuleLevel);
    }

    public static boolean isRequirementSatisfied(int installedLevel, int installedCount,
                                                  int requiredModuleLevel, int requiredCount) {
        return ModuleLevelEquivalence.isRequirementSatisfied(
                installedLevel, installedCount, requiredModuleLevel, requiredCount);
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
            if (slotId == null) return false;
            int slotLv = com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase.getModuleLevelById(slotId);
            return isRequirementSatisfied(slotLv, mb.getModuleCount(), requiredLv, requiredLevel);
        }

        // 主机路径：走主机每 tick 一次的等级聚合缓存，替代逐候选遍历全部模块
        IModularMachineHost host = null;
        if (machine instanceof IModularMachineHost h) {
            host = h;
        } else if (machine instanceof IModularMachineModule mod) {
            Object h2 = mod.getHost();
            if (h2 instanceof IModularMachineHost h3) host = h3;
        }
        return hostEquivalentCountSatisfies(host);
    }

    /**
     * 主机聚合判定统一实现：原初主机走 {@code getEquivalentModuleCountForLevel}（每 tick 一次
     * 等级聚合 + O(等级数) 查询），其他 IModularMachineHost 实现回退逐模块遍历。语义与旧遍历一致。
     */
    @SuppressWarnings("rawtypes")
    private boolean hostEquivalentCountSatisfies(IModularMachineHost host) {
        if (host == null || !host.isFormed()) return false;
        int requiredLv = com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase.getModuleLevelById(moduleId);
        if (host instanceof com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine engine) {
            Set modules = engine.getModuleSet();
            if (modules == null || modules.isEmpty()) return false;
            return engine.getEquivalentModuleCountForLevel(requiredLv) >= requiredLevel;
        }
        Set modules = host.getModuleSet();
        if (modules == null || modules.isEmpty()) return false;
        long count = 0L;
        for (Object mod : modules) {
            if (!(mod instanceof com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase mb)) continue;
            String slotId = mb.getModuleItemId();
            if (slotId == null) continue;
            int slotLv = com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase.getModuleLevelById(slotId);
            if (slotLv >= requiredLv) {
                long equivalent = calculateEquivalentCount(slotLv, mb.getModuleCount(), requiredLv);
                if (equivalent == Long.MAX_VALUE || count > Long.MAX_VALUE - equivalent) return true;
                count += equivalent;
                if (count >= requiredLevel) return true;
            }
        }
        return count >= requiredLevel;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public boolean test(GTRecipe recipe, RecipeLogic recipeLogic) {
        MetaMachine machine = recipeLogic.getMachine();
        IModularMachineHost host = null;
        if (machine instanceof IModularMachineHost h) {
            host = h;
        } else if (machine instanceof IModularMachineModule mod) {
            Object h2 = mod.getHost();
            if (h2 instanceof IModularMachineHost h3) host = h3;
        }
        // 每候选配方都会走到这里，判定统一走主机等级聚合缓存（热路径禁止逐模块遍历与调试日志）。
        return hostEquivalentCountSatisfies(host);
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
}
