package com.dishanhai.gt_shanhai.api;

import net.minecraftforge.fml.loading.FMLPaths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 外置生图 API 集成工具。
 * 配合 Python 脚本 generate_textures.py 使用，Java 侧提供 KubeJS 绑定入口。
 */
public class DShanhaiImageGenUtil {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("gt_shanhai:imagegen");

    private DShanhaiImageGenUtil() {}

    public static String generateTexture(String itemId, String prompt) {
        return generateTexture(itemId, prompt, "dishanhai_item");
    }

    public static String generateTexture(String itemId, String prompt, String namespace) {
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            Path texturePath = gameDir.resolve("kubejs/assets/" + namespace + "/textures/item/" + itemId + ".png");
            if (Files.exists(texturePath)) {
                LOG.info("[ImageGen] 纹理已存在: {}", texturePath);
                return namespace + ":item/" + itemId;
            }

            Files.createDirectories(texturePath.getParent());

            String apiUrl = "http://127.0.0.1:7860";
            int width = 64;
            int height = 64;
            String fullPrompt = "minecraft item texture, pixel art, 64x64, game icon, " + prompt;

            String base64 = callTxt2Img(apiUrl, fullPrompt, "", width, height);

            // 解码并保存
            byte[] imageBytes = Base64.getDecoder().decode(base64);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
                BufferedImage img = ImageIO.read(bais);
                if (img == null) {
                    LOG.error("[ImageGen] ImageIO 无法解码 API 返回的图片数据");
                    return fallbackTexture();
                }
                ImageIO.write(img, "PNG", texturePath.toFile());
            }

            LOG.info("[ImageGen] 纹理已生成: {} ({}x{})", texturePath, width, height);
            return namespace + ":item/" + itemId;

        } catch (Exception e) {
            LOG.error("[ImageGen] 生成失败: itemId={} error={}", itemId, e.getMessage());
            return fallbackTexture();
        }
    }

    /**
     * 调用 SD WebUI txt2img API。
     */
    private static String callTxt2Img(String apiUrl, String prompt, String negativePrompt,
                                       int width, int height) throws Exception {
        String json = String.format(
                "{\"prompt\":\"%s\",\"negative_prompt\":\"%s\",\"width\":%d,\"height\":%d,\"steps\":20,\"cfg_scale\":7,\"sampler_name\":\"Euler a\",\"batch_size\":1}",
                escapeJson(prompt), escapeJson(negativePrompt), width, height);

        URL url = URI.create(apiUrl + "/sdapi/v1/txt2img").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            try (InputStream es = conn.getErrorStream()) {
                String errorBody = es != null ? new String(es.readAllBytes(), StandardCharsets.UTF_8) : "unknown";
                throw new IOException("API 返回 HTTP " + code + ": " + errorBody);
            }
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) response.append(line);
        }

        // 解析 JSON 获取 images[0]
        return extractBase64Image(response.toString());
    }

    /**
     * 从 SD API 返回的 JSON 中提取第一张 base64 图片。
     */
    private static String extractBase64Image(String json) {
        // 简单解析：查找 "images":[" 后的 base64 数据
        String key = "\"images\":[\"";
        int start = json.indexOf(key);
        if (start < 0) throw new RuntimeException("API 返回缺少 images 字段");
        start += key.length();
        int end = json.indexOf("\"", start);
        if (end < 0) throw new RuntimeException("API 返回格式异常");
        return json.substring(start, end);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String fallbackTexture() {
        return "minecraft:item/paper";
    }
}
