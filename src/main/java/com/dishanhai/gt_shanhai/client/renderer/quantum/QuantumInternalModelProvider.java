package com.dishanhai.gt_shanhai.client.renderer.quantum;

import appeng.client.render.crafting.AbstractCraftingUnitModelProvider;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitType;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Function;

public class QuantumInternalModelProvider extends AbstractCraftingUnitModelProvider<QuantumCraftingUnitType> {

    private static final List<Material> MATERIALS = new ArrayList<Material>();
    private static final Material INTERNAL_FORMED_FACE = texture("quantum_internal_formed_face");
    private static final Material INTERNAL_FORMED_SIDES = texture("quantum_internal_formed_sides");
    private static final Material INTERNAL_ANIMATION_SIDES = texture("quantum_internal_powered_sides");
    private static final Material INTERNAL_ANIMATION_FACE = texture("quantum_internal_powered_animation");
    private static final Material INTERNAL_ANIMATION_FACE_TB = texture("quantum_internal_powered_animation_tb");

    public QuantumInternalModelProvider(QuantumCraftingUnitType type) {
        super(type);
    }

    @Override
    public List<Material> getMaterials() {
        return Collections.unmodifiableList(MATERIALS);
    }

    @Override
    public BakedModel getBakedModel(Function<Material, TextureAtlasSprite> spriteGetter) {
        EnumMap<net.minecraft.core.Direction, TextureAtlasSprite> faceAnimations = new EnumMap<net.minecraft.core.Direction, TextureAtlasSprite>(net.minecraft.core.Direction.class);
        TextureAtlasSprite sideAnimation = spriteGetter.apply(INTERNAL_ANIMATION_FACE);
        TextureAtlasSprite topBottomAnimation = spriteGetter.apply(INTERNAL_ANIMATION_FACE_TB);
        faceAnimations.put(net.minecraft.core.Direction.UP, topBottomAnimation);
        faceAnimations.put(net.minecraft.core.Direction.DOWN, topBottomAnimation);
        faceAnimations.put(net.minecraft.core.Direction.NORTH, sideAnimation);
        faceAnimations.put(net.minecraft.core.Direction.SOUTH, sideAnimation);
        faceAnimations.put(net.minecraft.core.Direction.EAST, sideAnimation);
        faceAnimations.put(net.minecraft.core.Direction.WEST, sideAnimation);
        return new QuantumInternalBakedModel(
                spriteGetter.apply(INTERNAL_FORMED_FACE),
                spriteGetter.apply(INTERNAL_FORMED_SIDES),
                spriteGetter.apply(INTERNAL_ANIMATION_SIDES),
                faceAnimations);
    }

    private static Material texture(String name) {
        Material material = new Material(TextureAtlas.LOCATION_BLOCKS,
                new ResourceLocation(GTDishanhaiMod.MOD_ID, "block/crafting/" + name));
        MATERIALS.add(material);
        return material;
    }
}
