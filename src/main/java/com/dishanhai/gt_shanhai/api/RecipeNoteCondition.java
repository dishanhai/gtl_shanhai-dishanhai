package com.dishanhai.gt_shanhai.api;

import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

/**
 * 配方备注：.notes("任意文本")
 *
 * 纯展示用途，不参与配方是否可执行的判定（test() 恒为 true），只是借用
 * GTCEu 配方条件列表的 JEI 渲染管线，把一行任意文本显示在配方栏里。
 * 支持中文与英文标点，原样透传，不做任何解析/拆分。
 */
public class RecipeNoteCondition extends RecipeCondition {

    public static final Codec<RecipeNoteCondition> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("text").forGetter(c -> c.text)
            ).apply(instance, RecipeNoteCondition::new)
    );

    public static final RecipeConditionType<RecipeNoteCondition> TYPE = new RecipeConditionType<>(
            () -> new RecipeNoteCondition(""),
            RecipeNoteCondition.CODEC
    );

    public final String text;

    public RecipeNoteCondition(String text) {
        super(false);
        this.text = text == null ? "" : text;
    }

    @Override
    public RecipeConditionType<?> getType() {
        return TYPE;
    }

    @Override
    public Component getTooltips() {
        return Component.literal("§e备注：§f" + text);
    }

    @Override
    public boolean test(GTRecipe recipe, RecipeLogic recipeLogic) {
        return true;
    }

    @Override
    public RecipeCondition createTemplate() {
        return new RecipeNoteCondition("");
    }

    @Override
    public JsonObject serialize() {
        JsonObject json = super.serialize();
        json.addProperty("text", text);
        return json;
    }

    @Override
    public RecipeNoteCondition deserialize(JsonObject json) {
        super.deserialize(json);
        return new RecipeNoteCondition(json.get("text").getAsString());
    }

    @Override
    public void toNetwork(FriendlyByteBuf buf) {
        super.toNetwork(buf);
        buf.writeUtf(text);
    }

    @Override
    public RecipeNoteCondition fromNetwork(FriendlyByteBuf buf) {
        super.fromNetwork(buf);
        return new RecipeNoteCondition(buf.readUtf());
    }
}
