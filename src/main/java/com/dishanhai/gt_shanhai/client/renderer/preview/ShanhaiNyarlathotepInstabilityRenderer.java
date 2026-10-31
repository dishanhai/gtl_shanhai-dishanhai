package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.renderer.hostfx.HostFxDraw;
import com.dishanhai.gt_shanhai.common.machine.preview.ShanhaiNyarlathotepPreviewMachine;
import com.dishanhai.gt_shanhai.common.machine.preview.ShanhaiNyarlathotepPreviewStructure;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Set;

/** 山海：整座觸腕結構的脈衝、斷裂光片與偶發電弧；只讀客戶端世界。 */
public final class ShanhaiNyarlathotepInstabilityRenderer extends WorkableCasingMachineRenderer {
    private static ShanhaiInstabilityGeometry geometry;
    private static boolean loadAttempted;

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
        var frame = mesh.frame(seconds, blockEntity.getBlockPos().asLong(), i -> present[i]);
        var consumer = buffers.getBuffer(HostFxDraw.beamLayer());
        Matrix4f matrix = poses.last().pose();
        for (var quad : frame.quads()) {
            vertex(consumer, matrix, quad.a(), quad, x, y, z);
            vertex(consumer, matrix, quad.b(), quad, x, y, z);
            vertex(consumer, matrix, quad.c(), quad, x, y, z);
            vertex(consumer, matrix, quad.d(), quad, x, y, z);
        }
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
