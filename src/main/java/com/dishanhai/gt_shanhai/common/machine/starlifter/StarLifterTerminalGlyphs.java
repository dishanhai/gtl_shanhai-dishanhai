package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.integration.jei.RecipeButtonSvgMesh;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

final class StarLifterTerminalGlyphs {
    private static final Map<String, RecipeButtonSvgMesh.IconMesh> ICONS = new HashMap<>();

    private StarLifterTerminalGlyphs() {}

    static void draw(GuiGraphics graphics, String name, int x, int y, int side, int color) {
        var mesh = ICONS.computeIfAbsent(name, icon -> RecipeButtonSvgMesh.loadResource(
                "/assets/gt_shanhai/textures/gui/star_lifter/" + icon + ".svg"));
        var matrix = new Matrix4f(graphics.pose().last().pose()).translate(x, y, 0)
                .scale((float) side / RecipeButtonSvgMesh.VIEW_SIZE);
        var vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        for (var quad : mesh.quads()) {
            vertex(vertices, matrix, quad.a(), color);
            vertex(vertices, matrix, quad.b(), color);
            vertex(vertices, matrix, quad.c(), color);
            vertex(vertices, matrix, quad.d(), color);
        }
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, RecipeButtonSvgMesh.Point point, int color) {
        buffer.vertex(matrix, point.x(), point.y(), 0)
                .color(color >> 16 & 255, color >> 8 & 255, color & 255, color >>> 24).endVertex();
    }
}
