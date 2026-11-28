package com.dishanhai.gt_shanhai.integration.jei;

import appeng.client.gui.Icon;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mezz.jei.api.gui.drawable.IDrawable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/** The button frame stays native; the glyph is geometry drawn at the current GUI scale. */
final class RecipeButtonSvgIcon implements IDrawable {
    private static final int SIZE = 13;
    private static final float SCALE = (float) (SIZE - 2) / RecipeButtonSvgMesh.VIEW_SIZE;
    private final RecipeButtonSvgMesh.IconMesh mesh;

    RecipeButtonSvgIcon(String name) {
        this(RecipeButtonSvgMesh.load(name));
    }

    RecipeButtonSvgIcon(RecipeButtonSvgMesh.IconMesh mesh) {
        this.mesh = mesh;
    }

    @Override public int getWidth() { return SIZE; }
    @Override public int getHeight() { return SIZE; }

    @Override
    public void draw(GuiGraphics graphics, int x, int y) {
        Icon.TOOLBAR_BUTTON_BACKGROUND.getBlitter().dest(x, y, SIZE, SIZE).blit(graphics);
        Matrix4f matrix = graphics.pose().last().pose();
        VertexConsumer vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        for (var quad : mesh.quads()) {
            emit(vertices, matrix, quad.a(), x + 1, y + 1);
            emit(vertices, matrix, quad.b(), x + 1, y + 1);
            emit(vertices, matrix, quad.c(), x + 1, y + 1);
            emit(vertices, matrix, quad.d(), x + 1, y + 1);
        }
        // Submit before JEI adds its hover overlays or the next button draws its frame.
        graphics.flush();
    }

    private void emit(VertexConsumer vertices, Matrix4f matrix, RecipeButtonSvgMesh.Point p, int x, int y) {
        int rgb = mesh.rgb();
        vertices.vertex(matrix, x + p.x() * SCALE, y + p.y() * SCALE, 0)
                .color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, 255)
                .endVertex();
    }
}
