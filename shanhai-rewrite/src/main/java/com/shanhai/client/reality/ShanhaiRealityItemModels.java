package com.shanhai.client.reality;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.reality.ShanhaiRealityItemTable;
import com.shanhai.item.RealityShellItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
import net.minecraftforge.client.model.geometry.IGeometryLoader;
import net.minecraftforge.client.model.geometry.IUnbakedGeometry;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.function.Function;

/**
 * 🔴 <b>「图标要热」的那一半</b>：128 个现实物品的 {@link BakedModel} 与 {@link ItemOverrides}。
 *
 * <h2>1. 链路（为什么不需要重新烘焙 / 不需要 F3+T）</h2>
 * <pre>
 *   模型 json（构建期就在 jar 里）：{ "loader": "shanhai:reality_item", "slot": N,
 *                                     "fallback": "shanhai:item/reality_shell_K" }
 *        ↓ 加载期（本类的几何加载器）
 *   RealityItemGeometry.bake(...)  →  RealityShellModel（槽位号 + 兜底模型 + 自己的 ItemOverrides）
 *        ↓ 每次渲染（ItemRenderer.getModel 里【没有缓存】，见类注释的证据）
 *   RealityOverrides.resolve(...)  →  现读表 → 兜底色块 / 借用物品的 BakedModel
 * </pre>
 * ⇒ "改表"这一件事只需要让 {@link ShanhaiRealityItemTable#version()} 变一下：
 * 本类的解析缓存按版本号整块作废，<b>下一帧就是新图标</b>。既不用改资源包，也不用重烘焙。
 *
 * <h2>2. 证据（都是只读取证，不是猜的）</h2>
 * <ul>
 *   <li>{@code IGeometryLoader#read(JsonObject, JsonDeserializationContext)} 与
 *       {@code IUnbakedGeometry#bake(IGeometryBakingContext, ModelBaker, Function, ModelState,
 *       ItemOverrides, ResourceLocation)} —— 见解出的
 *       {@code temp/creative-items/forge-src/net/minecraftforge/client/model/geometry/*.java}；</li>
 *   <li>注册口 = {@code ModelEvent.RegisterGeometryLoaders#register(String, IGeometryLoader)}（同一个
 *       解出的 {@code ModelEvent.java}）——名字会落到「当前 mod 命名空间」= {@code shanhai}，
 *       所以 json 里写 {@code "loader": "shanhai:reality_item"};</li>
 *   <li>烘焙期能烤另一个模型：{@code IForgeModelBaker#bake(ResourceLocation, ModelState, Function)}
 *       （{@code javap} forge 47.4.20 实测）；</li>
 *   <li>{@code ItemRenderer.getModel} 每次都调 {@code getOverrides().resolve(...)} 且不缓存结果
 *       （解出的 {@code ItemRenderer.java:204-216}）；{@code ItemOverrides} 的构造器是
 *       {@code protected}（{@code javap}），可以继承。</li>
 * </ul>
 *
 * <h2>3. 兜底 16 张色块怎么进图集（构建期产物，不是运行期生成）</h2>
 * 1.20.1 的 {@code assets/minecraft/atlases/blocks.json} 里有
 * {@code {"type":"directory","source":"item","prefix":"item/"}} ⇒ <b>所有命名空间</b>的
 * {@code textures/item/*.png} 都会被缝进方块/物品图集。
 * 所以 {@code assets/shanhai/textures/item/reality_block_0..15.png} 只要在 jar 里，
 * 对应的 {@code shanhai:item/reality_shell_0..15} 模型（{@code parent: item/generated}）就能正常取到贴图。
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShanhaiRealityItemModels {

    public static final String PREFIX = "[SHANHAI-REALITY]";

    /** 几何加载器在模型 json 里用的名字（前面会补上 {@code shanhai:}）。 */
    public static final String LOADER_NAME = "reality_item";

    private ShanhaiRealityItemModels() {}

    @SubscribeEvent
    public static void onRegisterGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(LOADER_NAME, RealityItemGeometry.LOADER);
        ShanhaiMod.LOGGER.info("{} geometry_loader_registered name=shanhai:{}", PREFIX, LOADER_NAME);
    }

    @SubscribeEvent
    public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        // JEI 的增量同步要一个"启动时 JEI 里已经有什么"的基线（否则第一次保存会重复 add）
        ShanhaiRealityClientRefresh.captureBaseline();
        ShanhaiMod.LOGGER.info("{} client_setup {} | {}", PREFIX,
                com.shanhai.common.reality.ShanhaiCreativeItemsPanel.layoutLine(),
                com.shanhai.common.reality.ShanhaiCreativeIconPicker.layoutLine());
    }

    // ==================================================================
    // 几何：从模型 json 读槽位号与兜底模型
    // ==================================================================

    /** 自定义几何（一个槽位一份；由 json 里的 {@code slot} / {@code fallback} 决定）。 */
    public static final class RealityItemGeometry implements IUnbakedGeometry<RealityItemGeometry> {

        static final IGeometryLoader<RealityItemGeometry> LOADER =
                (json, ctx) -> new RealityItemGeometry(readSlot(json), readFallback(json));

        private final int slot;
        private final ResourceLocation fallback;

        private RealityItemGeometry(int slot, ResourceLocation fallback) {
            this.slot = slot;
            this.fallback = fallback;
        }

        private static int readSlot(com.google.gson.JsonObject json) {
            if (json.has("slot")) {
                try {
                    // 🔴 json 里的 slot 是【1 基】（= 用户看到的槽位号 = id 后缀），
                    //    而表与渲染层一律用【0 基】⇒ 这里必须减 1。
                    //    （这一处偏移写错过一次：图标会整体错一格 —— 1 号槽位显示 2 号的图标。）
                    return json.get("slot").getAsInt() - 1;
                } catch (Throwable ignored) {
                    // 落到下面的兜底
                }
            }
            return -1;
        }

        private static ResourceLocation readFallback(com.google.gson.JsonObject json) {
            final String raw = json.has("fallback") ? json.get("fallback").getAsString()
                    : "shanhai:item/reality_shell_0";
            final ResourceLocation rl = ResourceLocation.tryParse(raw);
            return rl == null ? new ResourceLocation("shanhai", "item/reality_shell_0") : rl;
        }

        @Override
        public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
                              Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState,
                              ItemOverrides overrides, ResourceLocation modelLocation) {
            final BakedModel[] shells = ShellCache.get(baker, modelState, spriteGetter);
            final int slot = this.slot >= 0 ? this.slot : slotFromModelLocation(modelLocation);
            final int k = Math.floorMod(slot, shells.length);
            BakedModel base = shells[k];
            if (base == null) {
                base = Minecraft.getInstance().getModelManager().getMissingModel();
                ShanhaiMod.LOGGER.error("{} bake_fallback_missing slot={} shell={}",
                        PREFIX, slot + 1, this.fallback);
            }
            return new RealityShellModel(slot, base, shells);
        }

        /** json 里没写 slot 时，从模型位置 {@code shanhai:item/reality_item_N} 反推。 */
        private static int slotFromModelLocation(ResourceLocation location) {
            if (location == null) {
                return 0;
            }
            final String path = location.getPath();
            final int cut = path.lastIndexOf("reality_item_");
            if (cut < 0) {
                return 0;
            }
            try {
                return Integer.parseInt(path.substring(cut + "reality_item_".length())) - 1;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /** 16 张兜底模型（整个资源重载里只烤一次，所有槽位共用）。 */
    private static final class ShellCache {

        private static final Object LOCK = new Object();
        private static BakedModel[] shells;

        private ShellCache() {}

        static BakedModel[] get(ModelBaker baker, ModelState state,
                                Function<Material, TextureAtlasSprite> spriteGetter) {
            synchronized (LOCK) {
                if (shells != null) {
                    return shells;
                }
                final BakedModel[] out =
                        new BakedModel[ShanhaiRealityItemTable.BUILTIN_ICON_COUNT];
                for (int k = 0; k < out.length; k++) {
                    try {
                        out[k] = baker.bake(new ResourceLocation("shanhai", "item/reality_shell_" + k),
                                state, spriteGetter);
                    } catch (Throwable t) {
                        ShanhaiMod.LOGGER.error("{} shell_bake_failed k={} err={}", PREFIX, k, t.toString());
                        out[k] = null;
                    }
                }
                shells = out;
                ShanhaiMod.LOGGER.info("{} shell_models_baked count={} (兜底色块 16 张)",
                        PREFIX, out.length);
                return shells;
            }
        }

        /** 资源重载时重新烤（由 {@link ShanhaiRealityItemModels#onModelBakeCompleted} 调）。 */
        static void invalidate() {
            synchronized (LOCK) {
                shells = null;
            }
        }
    }

    /** 资源重载后必须丢掉上一次烘焙的模型引用（否则新图集下画的是旧 sprite）。 */
    @SubscribeEvent
    public static void onModelBakeCompleted(ModelEvent.BakingCompleted event) {
        ShellCache.invalidate();
    }

    // ==================================================================
    // BakedModel：一律转发给兜底模型，只有 getOverrides() 换成我们那一份
    // ==================================================================

    /** 一个槽位的物品模型（薄壳）。 */
    public static final class RealityShellModel implements BakedModel {

        private final int slot;
        private final BakedModel base;
        private final BakedModel[] shells;
        private final ItemOverrides overrides;

        RealityShellModel(int slot, BakedModel base, BakedModel[] shells) {
            this.slot = slot;
            this.base = base;
            this.shells = shells;
            this.overrides = new RealityOverrides(slot, shells);
        }

        int slot() {
            return this.slot;
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand) {
            return this.base.getQuads(state, side, rand);
        }

        @Override
        public boolean useAmbientOcclusion() {
            return this.base.useAmbientOcclusion();
        }

        @Override
        public boolean isGui3d() {
            return this.base.isGui3d();
        }

        @Override
        public boolean usesBlockLight() {
            return this.base.usesBlockLight();
        }

        @Override
        public boolean isCustomRenderer() {
            return false;
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return this.base.getParticleIcon();
        }

        @Override
        public ItemTransforms getTransforms() {
            return this.base.getTransforms();
        }

        @Override
        public ItemOverrides getOverrides() {
            return this.overrides;
        }

        @Override
        public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
            return this.base.getRenderPasses(stack, fabulous);
        }

        @Override
        public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
            return this.base.getRenderTypes(stack, fabulous);
        }
    }

    // ==================================================================
    // ItemOverrides：每次渲染现读表
    // ==================================================================

    /** 按"表版本号"缓存解析结果；版本一变就整块作废（第 ③ 条：立刻换图）。 */
    public static final class RealityOverrides extends ItemOverrides {

        private final int slot;
        private final BakedModel[] shells;

        private int cachedVersion = Integer.MIN_VALUE;
        private BakedModel cached;

        RealityOverrides(int slot, BakedModel[] shells) {
            super();
            this.slot = slot;
            this.shells = shells;
        }

        @Override
        public BakedModel resolve(BakedModel originalModel, ItemStack stack,
                                 net.minecraft.client.multiplayer.ClientLevel level,
                                 LivingEntity entity, int seed) {
            final int v = ShanhaiRealityItemTable.version();
            if (v == this.cachedVersion) {
                return this.cached == null ? originalModel : this.cached;
            }
            this.cachedVersion = v;
            final BakedModel resolved = compute(level, entity, seed);
            this.cached = resolved;
            return resolved == null ? originalModel : resolved;
        }

        /** 现算一次；{@code null} = 就用兜底（= {@code originalModel}）。 */
        private BakedModel compute(net.minecraft.client.multiplayer.ClientLevel level,
                                  LivingEntity entity, int seed) {
            final String icon = ShanhaiRealityItemTable.iconOf(this.slot);
            if (icon == null || icon.isEmpty() || icon.startsWith("builtin:")) {
                final int k = builtinIndex(icon);
                if (k < 0) {
                    return null;
                }
                if (this.shells != null && k < this.shells.length && this.shells[k] != null) {
                    return this.shells[k];
                }
                return null;
            }
            if (icon.startsWith("item:")) {
                final ResourceLocation id = ResourceLocation.tryParse(icon.substring("item:".length()));
                final Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
                if (item == null || item == Items.AIR || item instanceof RealityShellItem) {
                    return null;
                }
                try {
                    final ItemStack borrowed = new ItemStack(item);
                    final BakedModel model = Minecraft.getInstance().getItemRenderer()
                            .getModel(borrowed, level, entity, seed);
                    return model;
                } catch (Throwable t) {
                    ShanhaiMod.LOGGER.warn("{} borrow_icon_failed slot={} id={} err={}",
                            PREFIX, this.slot + 1, id, t.toString());
                    return null;
                }
            }
            return null;
        }

        private int builtinIndex(String icon) {
            if (icon == null || icon.isEmpty()) {
                return Math.floorMod(this.slot, ShanhaiRealityItemTable.BUILTIN_ICON_COUNT);
            }
            try {
                return Math.floorMod(
                        Integer.parseInt(icon.substring("builtin:".length()).trim()),
                        ShanhaiRealityItemTable.BUILTIN_ICON_COUNT);
            } catch (Throwable t) {
                return Math.floorMod(this.slot, ShanhaiRealityItemTable.BUILTIN_ICON_COUNT);
            }
        }
    }
}
