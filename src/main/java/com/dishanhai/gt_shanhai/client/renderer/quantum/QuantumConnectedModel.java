package com.dishanhai.gt_shanhai.client.renderer.quantum;

import appeng.client.render.crafting.AbstractCraftingUnitModelProvider;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
import net.minecraftforge.client.model.geometry.IGeometryLoader;
import net.minecraftforge.client.model.geometry.IUnbakedGeometry;

import java.util.function.Function;
import java.util.function.Supplier;

// 原生 Forge 自定义几何加载器（照搬 ExtendedAE 的 AssemblerGlassModel 做法），
// 直接把 provider 的 BakedModel 作为方块顶层模型烘焙出来。之前走 AE2 的
// BuiltInModelHooks.addBuiltInModel 是 mixin 拦截式替换，AE2 15.4.10 的
// BuiltInModelHooks.getBuiltInModel 只认 "ae2" 命名空间，gt_shanhai 的替换请求
// 全部被静默吞掉，方块根本没换上这个连接材质模型——这才是黑紫块/接缝空隙的根因。
public class QuantumConnectedModel implements IUnbakedGeometry<QuantumConnectedModel> {

    private final AbstractCraftingUnitModelProvider<?> provider;

    public QuantumConnectedModel(AbstractCraftingUnitModelProvider<?> provider) {
        this.provider = provider;
    }

    @Override
    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState,
            ItemOverrides overrides, ResourceLocation modelLocation) {
        return provider.getBakedModel(spriteGetter);
    }

    public static class Loader implements IGeometryLoader<QuantumConnectedModel> {

        private final Supplier<AbstractCraftingUnitModelProvider<?>> providerFactory;

        public Loader(Supplier<AbstractCraftingUnitModelProvider<?>> providerFactory) {
            this.providerFactory = providerFactory;
        }

        @Override
        public QuantumConnectedModel read(JsonObject jsonObject, JsonDeserializationContext context)
                throws JsonParseException {
            return new QuantumConnectedModel(providerFactory.get());
        }
    }
}
