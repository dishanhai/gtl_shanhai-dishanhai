package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxDraw;
import com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxSwitch;
import com.dishanhai.gt_shanhai.config.GuixuClientConfig;
import com.dishanhai.gt_shanhai.common.machine.preview.ShanhaiNyarlathotepPreviewMachine;
import com.dishanhai.gt_shanhai.common.machine.preview.ShanhaiNyarlathotepPreviewStructure;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 山海：整座觸腕的黑日、向心流、衝擊環與不穩定電弧；只讀客戶端世界。 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiNyarlathotepInstabilityRenderer extends WorkableCasingMachineRenderer {
    private static ShanhaiInstabilityGeometry geometry;
    private static boolean loadAttempted;
    private static RenderType blackCoreLayer;
    private record GlowBatch(Level world, Matrix4f pose, Matrix4f modelView,
                             ShanhaiInstabilityGeometry.Frame surface, ShanhaiBlackSunGeometry.Frame sun,
                             Direction x, Direction y, Direction z) {}
    private static final List<GlowBatch> WORLD_GLOW = new ArrayList<>();
    private record LinkGlowBatch(Level world, Matrix4f pose, Matrix4f modelView,
                                 List<ShanhaiInstabilityGeometry.Quad> glow) {}
    private static final List<LinkGlowBatch> WORLD_LINK_GLOW = new ArrayList<>();

    public ShanhaiNyarlathotepInstabilityRenderer(ResourceLocation casing, ResourceLocation overlay) {
        super(casing, overlay);
    }

    @Override public boolean hasTESR(BlockEntity blockEntity) { return true; }
    @Override public boolean isGlobalRenderer(BlockEntity blockEntity) { return true; }
    @Override public int getViewDistance() { return 384; }

    @Override
    public void render(BlockEntity blockEntity, float partialTick, PoseStack poses,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!(blockEntity instanceof IMachineBlockEntity holder)
                || !(holder.getMetaMachine() instanceof ShanhaiNyarlathotepPreviewMachine machine)) return;
        var world = blockEntity.getLevel();
        if (world == null) return;
        var mesh = geometry();
        if (mesh == null) return;
        Direction front = machine.getFrontFacing(), upwards = machine.getUpwardsFacing();
        Direction x = RelativeDirection.LEFT.getRelativeFacing(front, upwards, machine.isFlipped());
        // GTCEu 1.4.4 的 BlockPattern 在朝下時，X 軸與 RelativeDirection.LEFT 相反。
        if (front == Direction.DOWN) x = x.getOpposite();
        Direction y = RelativeDirection.UP.getRelativeFacing(front, upwards, machine.isFlipped());
        Direction z = RelativeDirection.FRONT.getRelativeFacing(front, upwards, machine.isFlipped());
        var present = new boolean[mesh.nodes().size()];
        // 每幀每種符號僅解析一次；不長期快取晚註冊／缺失方塊的結果。
        var candidates = new HashMap<Character, Set<Block>>();
        int count = 0;
        for (int i = 0; i < present.length; i++) {
            var node = mesh.nodes().get(i);
            BlockPos at = blockEntity.getBlockPos().offset(
                    node.x()*x.getStepX()+node.y()*y.getStepX()+node.z()*z.getStepX(),
                    node.x()*x.getStepY()+node.y()*y.getStepY()+node.z()*z.getStepY(),
                    node.x()*x.getStepZ()+node.y()*y.getStepZ()+node.z()*z.getStepZ());
            if (candidates.computeIfAbsent(node.symbol(), ShanhaiNyarlathotepPreviewStructure::previewBlocks)
                    .contains(world.getBlockState(at).getBlock())) { present[i] = true; count++; }
        }
        if (count == 0) return;
        // JEI 的假世界時間可能停在 0；沿用目前客戶端的時鐘，世界內與預覽都能播放。
        var clientWorld = Minecraft.getInstance().level;
        double seconds = ((clientWorld == null ? world.getGameTime() : clientWorld.getGameTime()) + partialTick) / 20.0;
        long seed = blockEntity.getBlockPos().asLong();
        var frame = mesh.frame(seconds, seed, i -> present[i]);
        var sun = ShanhaiBlackSunGeometry.frame(seconds, seed, mesh.nodes(), i -> present[i]);
        // 只使用服務端同步的有效連結；跨維度客戶端不查另一端世界。
        boolean linked = world == clientWorld && machine.isFormed() && machine.isGuixuLinkReady()
                && !sun.opaque().isEmpty() && GuixuClientConfig.hostFxEnabled();
        var link = ShanhaiNyarlathotepLinkGeometry.frame(seconds,
                seed ^ machine.getLinkedGuixuPos().asLong(), false, linked, HostFxSwitch.degraded(),
                ShanhaiBlackSunGeometry.RADIUS);
        if (!link.glow().isEmpty()) {
            var glow = new ArrayList<>(frame.quads());
            glow.addAll(link.glow());
            frame = new ShanhaiInstabilityGeometry.Frame(glow, frame.arcs());
        }
        Matrix4f matrix = poses.last().pose();
        // 世界內先完成所有黑核；光暈延後到 AFTER_BLOCK_ENTITIES，避免遠處黑核擦掉前景光暈。
        if (!sun.opaque().isEmpty()) draw(buffers.getBuffer(blackCoreLayer()),matrix,sun.opaque(),x,y,z);
        if (!link.opaque().isEmpty()) draw(buffers.getBuffer(blackCoreLayer()),matrix,link.opaque(),x,y,z);
        if (world == clientWorld) {
            WORLD_GLOW.add(new GlowBatch(world,new Matrix4f(matrix),new Matrix4f(RenderSystem.getModelViewMatrix()),
                    frame,sun,x,y,z));
            return;
        }
        // JEI 假世界每個 MB 僅有一個預覽控制器，沒有世界階段事件；由場景結束批次。
        var consumer = buffers.getBuffer(HostFxDraw.beamLayer());
        draw(consumer,matrix,frame.quads(),x,y,z);
        draw(consumer,matrix,sun.glow(),x,y,z);
    }

    @SubscribeEvent
    public static void beginFrame(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            WORLD_GLOW.clear();
            WORLD_LINK_GLOW.clear();
        }
    }

    /** 歸墟呼叫端已套環心／朝向 pivot；仍共用黑核先畫、發光延後的世界批次。 */
    public static void renderLinkAtPivot(Level world, PoseStack poses, MultiBufferSource buffers,
                                         ShanhaiNyarlathotepLinkGeometry.Frame frame) {
        if (frame.opaque().isEmpty() && frame.glow().isEmpty()) return;
        Matrix4f pose = poses.last().pose();
        drawLocal(buffers.getBuffer(blackCoreLayer()), pose, frame.opaque());
        if (world == Minecraft.getInstance().level) {
            WORLD_LINK_GLOW.add(new LinkGlowBatch(world, new Matrix4f(pose),
                    new Matrix4f(RenderSystem.getModelViewMatrix()), frame.glow()));
        } else drawLocal(buffers.getBuffer(HostFxDraw.beamLayer()), pose, frame.glow());
    }

    @SubscribeEvent
    public static void finishBlackCores(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
                || (WORLD_GLOW.isEmpty() && WORLD_LINK_GLOW.isEmpty())) return;
        var client = Minecraft.getInstance();
        var buffers = client.renderBuffers().bufferSource();
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        try {
            // 尾端黑核仍可能位於共用非固定 buffer，先明確完成它。
            buffers.endBatch(blackCoreLayer());
            for (var batch : WORLD_GLOW) {
                if (batch.world() != client.level) continue;
                modelView.last().pose().set(batch.modelView());
                RenderSystem.applyModelViewMatrix();
                var consumer = buffers.getBuffer(HostFxDraw.beamLayer());
                draw(consumer,batch.pose(),batch.surface().quads(),batch.x(),batch.y(),batch.z());
                draw(consumer,batch.pose(),batch.sun().glow(),batch.x(),batch.y(),batch.z());
                buffers.endBatch(HostFxDraw.beamLayer());
            }
            for (var batch : WORLD_LINK_GLOW) {
                if (batch.world() != client.level) continue;
                modelView.last().pose().set(batch.modelView());
                RenderSystem.applyModelViewMatrix();
                drawLocal(buffers.getBuffer(HostFxDraw.beamLayer()), batch.pose(), batch.glow());
                buffers.endBatch(HostFxDraw.beamLayer());
            }
        } finally {
            WORLD_GLOW.clear();
            WORLD_LINK_GLOW.clear();
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void drawLocal(VertexConsumer consumer, Matrix4f matrix, List<ShanhaiInstabilityGeometry.Quad> quads) {
        for (var quad : quads) {
            vertexLocal(consumer,matrix,quad.a(),quad);
            vertexLocal(consumer,matrix,quad.b(),quad);
            vertexLocal(consumer,matrix,quad.c(),quad);
            vertexLocal(consumer,matrix,quad.d(),quad);
        }
    }

    private static void vertexLocal(VertexConsumer consumer, Matrix4f matrix, ShanhaiInstabilityGeometry.Point point,
                                    ShanhaiInstabilityGeometry.Quad quad) {
        consumer.vertex(matrix, (float) point.x(), (float) point.y(), (float) point.z())
                .color(quad.red(),quad.green(),quad.blue(),quad.alpha()).endVertex();
    }

    private static void draw(VertexConsumer consumer, Matrix4f matrix, List<ShanhaiInstabilityGeometry.Quad> quads,
                             Direction x, Direction y, Direction z) {
        for (var quad : quads) {
            vertex(consumer, matrix, quad.a(), quad, x, y, z);
            vertex(consumer, matrix, quad.b(), quad, x, y, z);
            vertex(consumer, matrix, quad.c(), quad, x, y, z);
            vertex(consumer, matrix, quad.d(), quad, x, y, z);
        }
    }

    private static RenderType blackCoreLayer() {
        if (blackCoreLayer == null) {
            blackCoreLayer = RenderType.create("shanhai_nyarlathotep_black_sun_core",
                    DefaultVertexFormat.POSITION_COLOR,VertexFormat.Mode.QUADS,32768,false,false,
                    RenderType.CompositeState.builder()
                            .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                            .setTransparencyState(new RenderStateShard.TransparencyStateShard("shanhai_black_sun_opaque",
                                    RenderSystem::disableBlend,RenderSystem::disableBlend))
                            .setDepthTestState(new RenderStateShard.DepthTestStateShard("lequal",GL11.GL_LEQUAL))
                            .setWriteMaskState(new RenderStateShard.WriteMaskStateShard(true,true))
                            .setCullState(new RenderStateShard.CullStateShard(false)).createCompositeState(false));
        }
        return blackCoreLayer;
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, ShanhaiInstabilityGeometry.Point point,
                               ShanhaiInstabilityGeometry.Quad quad, Direction x, Direction y, Direction z) {
        consumer.vertex(pose,
                (float) (0.5 + point.x()*x.getStepX()+point.y()*y.getStepX()+point.z()*z.getStepX()),
                (float) (0.5 + point.x()*x.getStepY()+point.y()*y.getStepY()+point.z()*z.getStepY()),
                (float) (0.5 + point.x()*x.getStepZ()+point.y()*y.getStepZ()+point.z()*z.getStepZ()))
                .color(quad.red(), quad.green(), quad.blue(), quad.alpha()).endVertex();
    }

    private static ShanhaiInstabilityGeometry geometry() {
        if (!loadAttempted) {
            loadAttempted = true;
            try (var input = ShanhaiNyarlathotepInstabilityRenderer.class.getResourceAsStream(
                    "/assets/gt_shanhai/multiblock/nyarlathoteps_tentacle.mb")) {
                if (input == null) throw new IOException("Missing Nyarlathotep MB resource");
                geometry = ShanhaiInstabilityGeometry.fromMb(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException | IllegalArgumentException error) {
                GTDishanhaiMod.LOGGER.error("[山海不穩定特效] 結構錨點載入失敗", error);
            }
        }
        return geometry;
    }
}
