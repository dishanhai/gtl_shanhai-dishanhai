package com.dishanhai.gt_shanhai.client.loading;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 山海：啟動時不依賴尚在 reload 的 FontManager、資源包或中文語言檔。 */
final class ShanhaiLoadingFont {
    private static final String ROOT = "/assets/gt_shanhai/textures/gui/loading/shanhai_loading_font";
    private static ShanhaiLoadingFont instance;
    final ResourceLocation texture;
    private final String characters;
    private final float[] advances;
    private final int cell, columns, width, height, fontSize;

    private ShanhaiLoadingFont() throws IOException {
        try (InputStream metadata = resource(".json");
                var reader = new InputStreamReader(metadata, StandardCharsets.UTF_8);
                InputStream pixels = resource(".png")) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();
            characters = json.get("characters").getAsString();
            cell = json.get("cell").getAsInt();
            columns = json.get("columns").getAsInt();
            width = json.get("width").getAsInt();
            height = json.get("height").getAsInt();
            fontSize = json.get("fontSize").getAsInt();
            advances = new float[characters.length()];
            for (int i = 0; i < advances.length; i++) advances[i] = json.getAsJsonArray("advances").get(i).getAsFloat();
            DynamicTexture glyphs = new DynamicTexture(NativeImage.read(pixels));
            glyphs.setFilter(true, false);
            texture = Minecraft.getInstance().getTextureManager().register("shanhai_loading_font", glyphs);
        }
    }

    static ShanhaiLoadingFont get() {
        if (instance == null) {
            try { instance = new ShanhaiLoadingFont(); }
            catch (IOException error) { throw new IllegalStateException("山海載入字形圖集缺失", error); }
        }
        return instance;
    }

    private static InputStream resource(String suffix) throws IOException {
        InputStream input = ShanhaiLoadingFont.class.getResourceAsStream(ROOT + suffix);
        if (input == null) throw new IOException(ROOT + suffix);
        return input;
    }

    float width(String text, float size) {
        float result = 0;
        for (int offset = 0; offset < text.length();) {
            int codepoint = text.codePointAt(offset);
            int index = index(codepoint);
            result += advances[index] * size / fontSize;
            offset += Character.charCount(codepoint);
        }
        return result;
    }

    void append(BufferBuilder buffer, String text, float x, float y, float size, int color) {
        float scale = size / fontSize;
        for (int offset = 0; offset < text.length();) {
            int codepoint = text.codePointAt(offset);
            int index = index(codepoint);
            if (codepoint != ' ') {
                float left = (index % columns) * cell / (float) width;
                float top = (index / columns) * cell / (float) height;
                float right = left + cell / (float) width;
                float bottom = top + cell / (float) height;
                float endX = x + cell * scale;
                float endY = y + cell * scale;
                buffer.vertex(x, endY, 0).uv(left, bottom).color(color).endVertex();
                buffer.vertex(endX, endY, 0).uv(right, bottom).color(color).endVertex();
                buffer.vertex(endX, y, 0).uv(right, top).color(color).endVertex();
                buffer.vertex(x, y, 0).uv(left, top).color(color).endVertex();
            }
            x += advances[index] * scale;
            offset += Character.charCount(codepoint);
        }
    }

    private int index(int codepoint) {
        int index = characters.indexOf(codepoint);
        return index >= 0 ? index : characters.indexOf('?');
    }
}
