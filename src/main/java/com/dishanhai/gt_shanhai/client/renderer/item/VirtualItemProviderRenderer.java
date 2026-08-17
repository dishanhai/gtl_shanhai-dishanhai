package com.dishanhai.gt_shanhai.client.renderer.item;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.VirtualItemProviderHelper;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class VirtualItemProviderRenderer extends BlockEntityWithoutLevelRenderer {

    public static final ResourceLocation BASE_MODEL =
            new ResourceLocation(GTDishanhaiMod.MOD_ID, "item/virtual_item_provider_base");

    private static final float BADGE_SCALE = 0.5F;
    private static final float BADGE_OFFSET = 0.25F;
    private static VirtualItemProviderRenderer instance;

    private final Minecraft minecraft;
    private final ItemRenderer itemRenderer;

    private VirtualItemProviderRenderer(Minecraft minecraft) {
        super(minecraft.getBlockEntityRenderDispatcher(), minecraft.getEntityModels());
        this.minecraft = minecraft;
        this.itemRenderer = minecraft.getItemRenderer();
    }

    public static VirtualItemProviderRenderer getInstance() {
        if (instance == null) {
            instance = new VirtualItemProviderRenderer(Minecraft.getInstance());
        }
        return instance;
    }

    @Override
    public void renderByItem(ItemStack pStack, ItemDisplayContext pDisplayContext, PoseStack pPoseStack,
                             MultiBufferSource pBuffer, int pPackedLight, int pPackedOverlay) {
        ItemStack target = VirtualItemProviderHelper.getTarget(pStack);
        if (pDisplayContext == ItemDisplayContext.GUI
                && !target.isEmpty()
                && !VirtualItemProviderHelper.isProviderItem(target)) {
            renderGuiComposite(pStack, target, pPoseStack, pBuffer, pPackedLight, pPackedOverlay);
            return;
        }
        renderProviderOnly(pStack, pDisplayContext, pPoseStack, pBuffer, pPackedLight, pPackedOverlay);
    }

    private void renderGuiComposite(ItemStack provider, ItemStack target, PoseStack poseStack,
                                    MultiBufferSource buffer, int packedLight, int packedOverlay) {
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        itemRenderer.renderStatic(target, ItemDisplayContext.GUI, packedLight, packedOverlay,
                poseStack, buffer, minecraft.level, 0);

        poseStack.pushPose();
        poseStack.translate(BADGE_OFFSET, BADGE_OFFSET, BADGE_OFFSET);
        poseStack.scale(BADGE_SCALE, BADGE_SCALE, BADGE_SCALE);
        renderProviderModel(provider, ItemDisplayContext.GUI, poseStack, buffer, packedLight, packedOverlay);
        poseStack.popPose();
        poseStack.popPose();
    }

    private void renderProviderOnly(ItemStack pStack, ItemDisplayContext pDisplayContext, PoseStack pPoseStack,
                                    MultiBufferSource pBuffer, int pPackedLight, int pPackedOverlay) {
        pPoseStack.pushPose();
        pPoseStack.translate(0.5F, 0.5F, 0.5F);
        renderProviderModel(pStack, pDisplayContext, pPoseStack, pBuffer, pPackedLight, pPackedOverlay);
        pPoseStack.popPose();
    }

    private void renderProviderModel(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                                     MultiBufferSource buffer, int packedLight, int packedOverlay) {
        BakedModel model = minecraft.getModelManager().getModel(BASE_MODEL);
        poseStack.pushPose();
        itemRenderer.render(stack, displayContext, false, poseStack, buffer, packedLight, packedOverlay, model);
        poseStack.popPose();
    }
}
