package com.dishanhai.gt_shanhai.common.recipe;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeRuleCodecTest {

    @Test
    void codecRoundTripPreservesRuleFields() {
        RecipeRuleState state = RecipeRuleState.builder()
                .addStrip("gtceu:assembler", "minecraft:stone", true, false, "dishanhai:test")
                .addReplace("gtceu:assembler", "minecraft:stone", "minecraft:dirt",
                        false, false, "dishanhai:test", 2, -1)
                .addDelete("gtceu:assembler", "dishanhai:old")
                .setToggle("dishanhai:test", false)
                .addActivePreset("太空采矿预设")
                .build();

        JsonObject encoded = RecipeRuleCodec.toJson(state);
        RecipeRuleState decoded = RecipeRuleCodec.fromJson(encoded);

        assertEquals(state, decoded);
    }
}
