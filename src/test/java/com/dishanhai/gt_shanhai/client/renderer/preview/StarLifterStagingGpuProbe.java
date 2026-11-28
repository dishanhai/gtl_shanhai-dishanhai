package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL15.*;

public final class StarLifterStagingGpuProbe {
    private StarLifterStagingGpuProbe() {}

    public static void main(String[] args) {
        if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        try {
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            long window = glfwCreateWindow(32, 32, "Star Lifter staging probe", 0, 0);
            if (window == 0) throw new IllegalStateException("OpenGL context creation failed");
            try {
                glfwMakeContextCurrent(window);
                GL.createCapabilities();
                RenderSystem.initRenderThread();
                verifyReuse();
            } finally {
                glfwDestroyWindow(window);
            }
        } finally {
            glfwTerminate();
        }
    }

    private static void verifyReuse() {
        BufferBuilder[] owner = new BufferBuilder[1];
        try (var mesh = StarLifterMeshUpload.upload(DefaultVertexFormat.NEW_ENTITY, builder -> {
            owner[0] = builder;
            writeStructure(builder);
        })) {
            require(!mesh.isInvalid(), "first structure upload");
        }
        try {
            StarLifterMeshUpload.upload(DefaultVertexFormat.NEW_ENTITY, builder -> {
                writeStructure(builder);
                throw new IllegalStateException("interrupted bake");
            });
            throw new AssertionError("interruption was swallowed");
        } catch (IllegalStateException error) {
            require(error.getMessage().equals("interrupted bake"), "bake failure preserved");
        }
        for (int i = 0; i < 32; i++) {
            try (var structure = StarLifterMeshUpload.upload(DefaultVertexFormat.NEW_ENTITY, builder -> {
                require(builder == owner[0], "structure staging resource changed");
                writeStructure(builder);
            }); var effect = StarLifterMeshUpload.upload(DefaultVertexFormat.POSITION_TEX_COLOR, builder -> {
                require(builder == owner[0], "effect staging resource changed");
                for (int j = 0; j < 4; j++) builder.vertex(0, 0, 0).uv(0, 0).color(255, 255, 255, 255).endVertex();
            })) {
                require(structure.getFormat() == DefaultVertexFormat.NEW_ENTITY, "structure format");
                require(effect.getFormat() == DefaultVertexFormat.POSITION_TEX_COLOR, "effect format");
                require(!structure.isInvalid() && !effect.isInvalid(), "mesh rebuild");
                require(glGetError() == GL_NO_ERROR, "OpenGL upload");
            }
        }
        System.out.println("Star Lifter: 32 alternating mesh rebuilds reuse one staging buffer and recover after interruption.");
    }

    private static void writeStructure(BufferBuilder builder) {
        for (int i = 0; i < 4; i++) {
            builder.vertex(0, 0, 0).color(255, 255, 255, 255).uv(0, 0).overlayCoords(0).uv2(0).normal(0, 1, 0).endVertex();
        }
    }

    private static void require(boolean expected, String message) {
        if (!expected) throw new AssertionError(message);
    }
}
