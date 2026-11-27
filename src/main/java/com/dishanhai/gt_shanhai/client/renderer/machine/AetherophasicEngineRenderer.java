package com.dishanhai.gt_shanhai.client.renderer.machine;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.aetherophasic.AetherophasicEnginePreviewMachine;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.function.Consumer;

/** 山海：以太相引擎成型后，在既有星核和装甲外渲染日冕。 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AetherophasicEngineRenderer extends WorkableCasingMachineRenderer {
    private static final RenderType STELLAR_SURFACE = plasmaLayer("shanhai_aetherophasic_surface", false);
    private static final RenderType CORONA = plasmaLayer("shanhai_aetherophasic_corona", true);
    private static ShaderInstance surfaceShader;
    private static ShaderInstance coronaShader;

    public AetherophasicEngineRenderer(ResourceLocation casing, ResourceLocation overlay) {
        super(casing, overlay);
    }

    @SubscribeEvent
    public static void registerShader(RegisterShadersEvent event) {
        surfaceShader = null;
        coronaShader = null;
        // ShaderInstance.apply 会应用 JSON 中的混合状态，两层共享 GLSL 并分别声明混合方式。
        registerShader(event, "aetherophasic_surface", loaded -> surfaceShader = loaded);
        registerShader(event, "aetherophasic_plasma", loaded -> coronaShader = loaded);
    }

    private static void registerShader(RegisterShadersEvent event, String name, Consumer<ShaderInstance> onLoaded) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), new ResourceLocation(GTDishanhaiMod.MOD_ID, name),
                    DefaultVertexFormat.POSITION_TEX_COLOR), onLoaded);
        } catch (IOException | RuntimeException error) {
            GTDishanhaiMod.LOGGER.warn("以太相引擎着色器加载失败 {}", name, error);
        }
    }

    private static RenderType plasmaLayer(String name, boolean additive) {
        return RenderType.create(name, DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS,
                additive ? 65536 : 32768, false, false, RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(() -> additive ? coronaShader : surfaceShader))
                        .setTransparencyState(new RenderStateShard.TransparencyStateShard(name,
                                () -> {
                                    RenderSystem.enableBlend();
                                    RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, additive
                                            ? GlStateManager.DestFactor.ONE : GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                                },
                                () -> {
                                    RenderSystem.disableBlend();
                                    RenderSystem.defaultBlendFunc();
                                }))
                        .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal", GL11.GL_LEQUAL))
                        .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true, false))
                        .setCullState(new RenderStateShard.CullStateShard(!additive))
                        .createCompositeState(false));
    }

    @Override public boolean hasTESR(BlockEntity blockEntity) { return true; }
    @Override public boolean isGlobalRenderer(BlockEntity blockEntity) { return true; }
    @Override public int getViewDistance() { return 320; }

    @Override
    public void render(BlockEntity blockEntity, float partialTick, PoseStack poses,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        var client = Minecraft.getInstance();
        var level = blockEntity.getLevel();
        if (surfaceShader == null || coronaShader == null || level == null || level != client.level
                || !(blockEntity instanceof IMachineBlockEntity holder)
                || !(holder.getMetaMachine() instanceof AetherophasicEnginePreviewMachine machine)
                || !machine.isFormed()) return;

        BlockPos controller = blockEntity.getBlockPos();
        Direction front = machine.getFrontFacing();
        Direction roll = machine.getUpwardsFacing();
        boolean flipped = machine.isFlipped();
        BlockPos center = stellarCenter(controller, front, roll, flipped);
        Vec3 camera = client.gameRenderer.getMainCamera().getPosition().subtract(Vec3.atCenterOf(center));
        double time = level.getGameTime() + (double) partialTick;
        double seconds = time / 20;
        double progress = machine.getRecipeLogic().isWorking() ? machine.getVisualProgress() : 0;
        long lastCompletionTick = machine.getLastCompletionTick();
        double completionAge = lastCompletionTick < 0 ? -1 : time - lastCompletionTick;
        poses.pushPose();
        try {
            poses.translate(center.getX() - controller.getX() + 0.5,
                    center.getY() - controller.getY() + 0.5, center.getZ() - controller.getZ() + 0.5);
            var matrix = poses.last().pose();
            AetherophasicPlasma.drawStar(buffers.getBuffer(STELLAR_SURFACE), matrix, progress);
            var corona = buffers.getBuffer(CORONA);
            AetherophasicPlasma.drawCorona(corona, matrix, camera, seconds, progress, completionAge);
            if (progress > 0) {
                AetherophasicPlasma.drawApertures(corona, matrix, camera, seconds, progress,
                        RelativeDirection.LEFT.getRelativeFacing(front, roll, flipped),
                        RelativeDirection.UP.getRelativeFacing(front, roll, flipped),
                        RelativeDirection.FRONT.getRelativeFacing(front, roll, flipped));
            }
        } finally {
            poses.popPose();
        }
    }

    static BlockPos stellarCenter(BlockPos controller, Direction front, Direction up, boolean flipped) {
        // JSON 中恒星表层的球心是 (42,48,42)，控制器是 (42,42,73)。
        return RelativeDirection.offsetPos(controller, front, up, flipped, 6, 0, -31);
    }
}
