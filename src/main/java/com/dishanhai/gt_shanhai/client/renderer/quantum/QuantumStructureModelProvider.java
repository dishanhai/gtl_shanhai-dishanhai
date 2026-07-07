package com.dishanhai.gt_shanhai.client.renderer.quantum;

import appeng.client.render.crafting.AbstractCraftingUnitModelProvider;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitType;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingUnitTypes;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public class QuantumStructureModelProvider extends AbstractCraftingUnitModelProvider<QuantumCraftingUnitType> {

    private static final List<Material> MATERIALS = new ArrayList<Material>();
    private static final Material STRUCTURE_FORMED_FACE = texture("quantum_structure_formed_face");
    private static final Material STRUCTURE_FORMED_SIDES = texture("quantum_structure_formed_sides");
    private static final Material STRUCTURE_ANIMATION_SIDES = texture("quantum_structure_powered_sides");

    public QuantumStructureModelProvider() {
        super(QuantumCraftingUnitTypes.STRUCTURE);
    }

    @Override
    public List<Material> getMaterials() {
        return Collections.unmodifiableList(MATERIALS);
    }

    @Override
    public BakedModel getBakedModel(Function<Material, TextureAtlasSprite> spriteGetter) {
        return new QuantumStructureBakedModel(
                spriteGetter.apply(STRUCTURE_FORMED_FACE),
                spriteGetter.apply(STRUCTURE_FORMED_SIDES),
                spriteGetter.apply(STRUCTURE_ANIMATION_SIDES));
    }

    private static Material texture(String name) {
        Material material = new Material(TextureAtlas.LOCATION_BLOCKS,
                new ResourceLocation(GTDishanhaiMod.MOD_ID, "block/crafting/" + name));
        MATERIALS.add(material);
        return material;
    }
}
