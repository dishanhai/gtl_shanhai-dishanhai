package com.dishanhai.gt_shanhai.api;

import com.dishanhai.gt_shanhai.common.machine.misc.ShanhaiBlackHoleContainmentMachine;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BHC 黑洞遏制场配方条件 — 黑洞活跃 / 时空催化倍率 / 稳定度需求
 *
 * conditions: ["bhc_active"]                     → 黑洞必须已开启
 * conditions: ["bhc_catalyst:4"]                 → 时空催化倍率 >= 4
 * conditions: ["bhc_stability:80"]               → 稳定度 >= 80%
 */
public class BHCRecipeCondition extends RecipeCondition {

    public enum Mode { ACTIVE, CATALYST, STABILITY }

    public static final Codec<BHCRecipeCondition> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("mode").forGetter(c -> c.mode.name()),
                    Codec.INT.fieldOf("value").forGetter(c -> c.value)
            ).apply(instance, BHCRecipeCondition::new)
    );

    public static final RecipeConditionType<BHCRecipeCondition> TYPE = new RecipeConditionType<>(
            () -> new BHCRecipeCondition(Mode.ACTIVE, 0),
            BHCRecipeCondition.CODEC
    );

    // KubeJS 绕过: 配方ID → 条件列表
    private static final Map<String, List<BHCRecipeCondition>> REQUIREMENTS = new ConcurrentHashMap<>();

    public final Mode mode;
    public final int value;

    public BHCRecipeCondition(Mode mode, int value) {
        super(false);
        this.mode = mode;
        this.value = value;
    }

    /** 从 KJS 字符串构造并写入注册表 */
    public BHCRecipeCondition(String modeName, int value) {
        this(Mode.valueOf(modeName), value);
    }

    public static void register(String recipeId, BHCRecipeCondition cond) {
        REQUIREMENTS.computeIfAbsent(recipeId, k -> new ArrayList<>()).add(cond);
    }

    public static List<BHCRecipeCondition> getRequirements(String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) return null;
        List<BHCRecipeCondition> exact = REQUIREMENTS.get(recipeId);
        if (exact != null) return exact;
        String suffix = lastSegment(recipeId);
        for (var entry : REQUIREMENTS.entrySet()) {
            String keySuffix = lastSegment(entry.getKey());
            if (!suffix.isEmpty() && (suffix.equals(keySuffix) || suffix.contains(keySuffix) || keySuffix.contains(suffix)))
                return entry.getValue();
            if (recipeId.contains(entry.getKey()) || entry.getKey().contains(recipeId))
                return entry.getValue();
        }
        return null;
    }

    private static String lastSegment(String id) {
        if (id == null || id.isEmpty()) return "";
        int slash = id.lastIndexOf('/'), colon = id.lastIndexOf(':');
        int start = Math.max(slash, colon);
        return start >= 0 && start < id.length() - 1 ? id.substring(start + 1) : id;
    }

    @Override
    public RecipeConditionType<?> getType() { return TYPE; }

    @Override
    public Component getTooltips() {
        return switch (mode) {
            case ACTIVE -> Component.literal("§d需要黑洞已开启");
            case CATALYST -> Component.literal("§5需要时空催化倍率 >= §e" + value);
            case STABILITY -> Component.literal("§3需要稳定度 >= §e" + value + "%");
        };
    }

    @Override
    public boolean test(GTRecipe recipe, RecipeLogic recipeLogic) {
        MetaMachine machine = recipeLogic.getMachine();
        if (!(machine instanceof ShanhaiBlackHoleContainmentMachine bhc)) return false;
        if (!bhc.isFormed()) return false;
        return switch (mode) {
            case ACTIVE -> bhc.getBlackHoleStatus() != 1;
            case CATALYST -> bhc.getBlackHoleCatalyzingCostModifier() >= value;
            case STABILITY -> bhc.getBlackHoleStability() >= value;
        };
    }

    @Override
    public RecipeCondition createTemplate() { return new BHCRecipeCondition(Mode.ACTIVE, 0); }

    @Override
    public JsonObject serialize() {
        JsonObject json = super.serialize();
        json.addProperty("mode", mode.name());
        json.addProperty("value", value);
        return json;
    }

    @Override
    public BHCRecipeCondition deserialize(JsonObject json) {
        super.deserialize(json);
        return new BHCRecipeCondition(Mode.valueOf(json.get("mode").getAsString()), json.get("value").getAsInt());
    }

    @Override
    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeEnum(mode);
        buf.writeInt(value);
    }

    @Override
    public BHCRecipeCondition fromNetwork(FriendlyByteBuf buf) {
        return new BHCRecipeCondition(buf.readEnum(Mode.class), buf.readInt());
    }
}
