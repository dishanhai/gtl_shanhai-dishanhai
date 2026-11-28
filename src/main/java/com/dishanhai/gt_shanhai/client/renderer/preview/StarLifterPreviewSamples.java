package com.dishanhai.gt_shanhai.client.renderer.preview;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL;

import static org.lwjgl.opengl.EXTTextureFilterAnisotropic.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.*;

/** Multisampled color/depth storage and a preview-only sampler for the existing block atlas. */
final class StarLifterPreviewSamples {
    private int framebuffer, color, depth, sampler, samples, mipLevels;

    void resize(int width, int height, int requestedSamples) {
        releaseAttachments();
        if (requestedSamples < 2) return;
        int previous = glGetInteger(GL_RENDERBUFFER_BINDING);
        framebuffer = glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        try {
            color = attach(width, height, requestedSamples, GL_RGBA8, GL_COLOR_ATTACHMENT0);
            depth = attach(width, height, requestedSamples, GL_DEPTH_COMPONENT24, GL_DEPTH_ATTACHMENT);
            samples = glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_SAMPLES);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) releaseAttachments();
        } finally {
            glBindRenderbuffer(GL_RENDERBUFFER, previous);
        }
    }

    private static int attach(int width, int height, int samples, int format, int attachment) {
        int buffer = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, buffer);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, format, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, attachment, GL_RENDERBUFFER, buffer);
        return buffer;
    }

    boolean multisampled() { return framebuffer != 0; }
    int samples() { return samples; }
    int mipLevels() { return mipLevels; }
    long attachmentBytes(int width, int height) { return 8L * width * height * samples; }
    static boolean hasSamplerObjects() {
        return GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects;
    }

    void bind() { GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, framebuffer); }

    void resolve(int destination, int width, int height) {
        GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, destination);
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }

    int bindAtlas(int atlas) {
        if (!hasSamplerObjects()) return -1;
        int previous = glGetIntegeri(GL_SAMPLER_BINDING, 0);
        if (sampler == 0) {
            int texture = glGetInteger(GL_TEXTURE_BINDING_2D);
            glBindTexture(GL_TEXTURE_2D, atlas);
            try {
                // The smallest ring sprite is 16 pixels, so level four is its final independent texel.
                mipLevels = Math.min(4, glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL));
                // Some resources are uploaded without mipmaps even when a global level was requested.
                while (mipLevels > 0 && glGetTexLevelParameteri(GL_TEXTURE_2D, mipLevels, GL_TEXTURE_WIDTH) == 0) mipLevels--;
            } finally {
                glBindTexture(GL_TEXTURE_2D, texture);
            }
            sampler = glGenSamplers();
            glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, mipLevels > 0 ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
            glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glSamplerParameteri(sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glSamplerParameterf(sampler, GL_TEXTURE_LOD_BIAS, 0);
            glSamplerParameterf(sampler, GL_TEXTURE_MAX_LOD, mipLevels);
            if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
                glSamplerParameterf(sampler, GL_TEXTURE_MAX_ANISOTROPY_EXT,
                        Math.min(4, glGetFloat(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT)));
            }
        }
        glBindSampler(0, sampler);
        return previous;
    }

    private void releaseAttachments() {
        if (framebuffer != 0) {
            glDeleteFramebuffers(framebuffer);
            glDeleteRenderbuffers(color);
            glDeleteRenderbuffers(depth);
        }
        framebuffer = color = depth = samples = 0;
    }

    void close() {
        releaseAttachments();
        if (sampler != 0) glDeleteSamplers(sampler);
        sampler = mipLevels = 0;
    }
}
