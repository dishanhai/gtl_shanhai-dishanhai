package com.dishanhai.gt_shanhai.client.renderer.item;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.SelectedRecipePattern;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * GUI 中先画通配样板本体，再把前几条配方的物品材质放到四个角。
 * 角标尺寸和虚拟物品提供器的角标相同。手上、地上仍只画本体。
 */
@OnlyIn(Dist.CLIENT)
public final class SelectedRecipePatternRenderer extends BlockEntityWithoutLevelRenderer {

    public static final ResourceLocation BASE_MODEL =
            new ResourceLocation(GTDishanhaiMod.MOD_ID, "item/selected_recipe_pattern_base");

    /** 和 {@code VirtualItemProviderRenderer} 的角标一致，占槽位的一个象限。 */
    private static final float ICON_SCALE = 0.5F;
    /** 左上、右上、左下、右下。GUI 里 +X 向右，+Y 向上。 */
    private static final float[] CORNER_X = {-0.25F, 0.25F, -0.25F, 0.25F};
    private static final float[] CORNER_Y = {0.25F, 0.25F, -0.25F, -0.25F};

    private static SelectedRecipePatternRenderer instance;

    private final Minecraft minecraft;
    private final ItemRenderer itemRenderer;
    @Nullable
    private CompoundTag cachedTag;
    @Nullable
    private Level cachedLevel;
    private List<ItemStack> cachedIcons = List.of();
    private int cachedTick = Integer.MIN_VALUE;

    private SelectedRecipePatternRenderer(Minecraft minecraft) {
        super(minecraft.getBlockEntityRenderDispatcher(), minecraft.getEntityModels());
        this.minecraft = minecraft;
        this.itemRenderer = minecraft.getItemRenderer();
    }

    public static SelectedRecipePatternRenderer getInstance() {
        if (instance == null) {
            instance = new SelectedRecipePatternRenderer(Minecraft.getInstance());
        }
        return instance;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                             MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (displayContext == ItemDisplayContext.GUI) {
            List<ItemStack> icons = icons(stack);
            if (!icons.isEmpty()) {
                renderGuiComposite(stack, icons, poseStack, buffer, packedLight, packedOverlay);
                return;
            }
        }
        renderBaseOnly(stack, displayContext, poseStack, buffer, packedLight, packedOverlay);
    }

    private void renderGuiComposite(ItemStack stack, List<ItemStack> icons, PoseStack poseStack,
                                    MultiBufferSource buffer, int packedLight, int packedOverlay) {
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        renderBase(stack, ItemDisplayContext.GUI, poseStack, buffer, packedLight, packedOverlay);
        int count = Math.min(icons.size(), SelectedRecipePattern.CORNER_ICONS);
        for (int index = 0; index < count; index++) {
            poseStack.pushPose();
            poseStack.translate(CORNER_X[index], CORNER_Y[index], 0.25F + index * 0.02F);
            poseStack.scale(ICON_SCALE, ICON_SCALE, ICON_SCALE);
            itemRenderer.renderStatic(icons.get(index), ItemDisplayContext.GUI, packedLight, packedOverlay,
                    poseStack, buffer, minecraft.level, index + 1);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    private void renderBaseOnly(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                                MultiBufferSource buffer, int packedLight, int packedOverlay) {
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        renderBase(stack, displayContext, poseStack, buffer, packedLight, packedOverlay);
        poseStack.popPose();
    }

    private void renderBase(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                            MultiBufferSource buffer, int packedLight, int packedOverlay) {
        BakedModel model = minecraft.getModelManager().getModel(BASE_MODEL);
        poseStack.pushPose();
        itemRenderer.render(stack, displayContext, false, poseStack, buffer, packedLight, packedOverlay, model);
        poseStack.popPose();
    }

    /** 配方表晚于物品同步时，空结果每隔一会儿再查一次。标签对象不变且已经有角标就不再查。 */
    private List<ItemStack> icons(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        Level level = minecraft.level;
        int tick = minecraft.player == null ? 0 : minecraft.player.tickCount;
        if (tag == cachedTag && level == cachedLevel) {
            if (!cachedIcons.isEmpty() || tick - cachedTick < 20) return cachedIcons;
        }
        cachedTag = tag;
        cachedLevel = level;
        cachedTick = tick;
        cachedIcons = SelectedRecipePattern.cornerIcons(stack, level);
        return cachedIcons;
    }
}
