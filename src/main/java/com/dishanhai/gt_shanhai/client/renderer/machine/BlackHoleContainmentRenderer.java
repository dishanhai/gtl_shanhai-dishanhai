package com.dishanhai.gt_shanhai.client.renderer.machine;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.misc.ShanhaiBlackHoleContainmentMachine;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.client.model.WorkableOverlayModel;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import org.gtlcore.gtlcore.client.ClientUtil;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class BlackHoleContainmentRenderer extends WorkableCasingMachineRenderer {

    private static final List<ResourceLocation> HARMONY_ORBIT_OBJECTS = List.of(
            new ResourceLocation("gtlcore", "obj/the_nether"),
            new ResourceLocation("gtlcore", "obj/overworld"),
            new ResourceLocation("gtlcore", "obj/the_end")
    );

    private static final int STATUS_ACTIVE = 2;
    private static final int STATUS_UNSTABLE = 3;
    private static final int STATUS_HYPER_STABLE = 4;

    private final WorkableOverlayModel activeOverlayModel;
    private final WorkableOverlayModel unstableOverlayModel;

    private static int bhProg = -1, u_CameraPosition, u_Scale, u_Time, u_Stability, u_MVP;
    private static int bhTexId = -1;
    private static int bhVao, bhVbo, bhFaceCount; // 手动 VAO+VBO, 绕过 VertexBuffer.draw() 的 shader 冲突

    private static int laserProg = -1, uL_CameraPosition, uL_Color, uL_Alpha, uL_ModelMatrix, uL_MVP;
    private static int laserTexId = -1;
    private static int laserVao, laserVbo, laserFaceCount;

    private static boolean initialized;

    public BlackHoleContainmentRenderer(ResourceLocation b, ResourceLocation closed) {
        this(b, closed,
                new ResourceLocation(closed.getNamespace(), "block/multiblock/blackhole_active"),
                new ResourceLocation(closed.getNamespace(), "block/multiblock/blackhole_unstable"));
    }

    public BlackHoleContainmentRenderer(ResourceLocation b, ResourceLocation closed, ResourceLocation active, ResourceLocation unstable) {
        super(b, closed);
        this.activeOverlayModel = new WorkableOverlayModel(active);
        this.unstableOverlayModel = new WorkableOverlayModel(unstable);
    }

    @Override
    public void renderMachine(List<BakedQuad> quads, MachineDefinition definition, MetaMachine machine,
                              Direction frontFacing, Direction side, RandomSource rand,
                              Direction modelFacing, ModelState modelState) {
        renderBaseModel(quads, definition, machine, frontFacing, side, rand);

        Direction upwards = Direction.NORTH;
        if (machine instanceof IMultiController controller) {
            upwards = controller.self().getUpwardsFacing();
        }

        WorkableOverlayModel statusOverlay = overlayModel;
        if (machine instanceof ShanhaiBlackHoleContainmentMachine bhc) {
            int status = bhc.getBlackHoleStatus();
            if (status == STATUS_ACTIVE || status == STATUS_HYPER_STABLE) {
                statusOverlay = activeOverlayModel;
            } else if (status == STATUS_UNSTABLE) {
                statusOverlay = unstableOverlayModel;
            }
        }
        quads.addAll(statusOverlay.bakeQuads(side, frontFacing, upwards, false, false));
    }

    @Override
    public void onPrepareTextureAtlas(ResourceLocation atlasName, Consumer<ResourceLocation> register) {
        super.onPrepareTextureAtlas(atlasName, register);
        if (TextureAtlas.LOCATION_BLOCKS.equals(atlasName)) {
            activeOverlayModel.registerTextureAtlas(register);
            unstableOverlayModel.registerTextureAtlas(register);
        }
    }

    @Override
    public void onAdditionalModel(Consumer<ResourceLocation> registry) {
        super.onAdditionalModel(registry);
        for (ResourceLocation model : HARMONY_ORBIT_OBJECTS) {
            registry.accept(model);
        }
    }

    @Override public boolean hasTESR(BlockEntity be) { return true; }
    @Override public boolean isGlobalRenderer(BlockEntity be) { return true; }
    @Override public int getViewDistance() { return 256; }

    private static void lazyInit() {
        if (initialized) return;
        initialized = true;

        String vs = readRes("/assets/gt_shanhai/shaders/core/blackhole.vsh");
        String fs = readRes("/assets/gt_shanhai/shaders/core/blackhole.fsh");
        if (vs == null || fs == null) { GTDishanhaiMod.LOGGER.error("[BHC] BH shader missing"); return; }
        bhProg = compileAndLink(vs, fs, new String[]{"Position","TexCoord0"}, new int[]{0,1});
        if (bhProg < 0) return;
        u_CameraPosition = GL20.glGetUniformLocation(bhProg, "u_CameraPosition");
        u_Scale = GL20.glGetUniformLocation(bhProg, "u_Scale");
        u_Time = GL20.glGetUniformLocation(bhProg, "u_Time");
        u_Stability = GL20.glGetUniformLocation(bhProg, "u_Stability");
        u_MVP = GL20.glGetUniformLocation(bhProg, "u_ModelViewProjectionMatrix");

        buildBhVbo();
        bhTexId = loadTexture("/assets/gt_shanhai/textures/model/blackhole.png");

        vs = readRes("/assets/gt_shanhai/shaders/core/laser.vsh");
        fs = readRes("/assets/gt_shanhai/shaders/core/laser.fsh");
        if (vs != null && fs != null) {
            laserProg = compileAndLink(vs, fs, new String[]{"Position","TexCoord0"}, new int[]{0,1});
            if (laserProg >= 0) {
                uL_CameraPosition = GL20.glGetUniformLocation(laserProg, "u_CameraPosition");
                uL_Color = GL20.glGetUniformLocation(laserProg, "u_Color");
                uL_Alpha = GL20.glGetUniformLocation(laserProg, "u_Alpha");
                uL_ModelMatrix = GL20.glGetUniformLocation(laserProg, "u_ModelMatrix");
                uL_MVP = GL20.glGetUniformLocation(laserProg, "u_ModelViewProjectionMatrix");
                buildLaserVbo();
                laserTexId = loadTexture("/assets/gt_shanhai/textures/model/laser.png");
            }
        }
        GTDishanhaiMod.LOGGER.info("[BHC] init: bhProg={} laserProg={} bhTex={} laserTex={}", bhProg, laserProg, bhTexId, laserTexId);
    }

    private static int compileAndLink(String vs, String fs, String[] names, int[] locs) {
        int v = compileShader(GL20.GL_VERTEX_SHADER, vs);
        int f = compileShader(GL20.GL_FRAGMENT_SHADER, fs);
        if (v < 0 || f < 0) return -1;
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v); GL20.glAttachShader(p, f);
        for (int i = 0; i < names.length; i++)
            GL20.glBindAttribLocation(p, locs[i], names[i]);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL20.GL_FALSE) {
            GTDishanhaiMod.LOGGER.error("[BHC] link fail: {}", GL20.glGetProgramInfoLog(p, 1024));
            return -1;
        }
        GL20.glDeleteShader(v); GL20.glDeleteShader(f);
        return p;
    }

    private static int compileShader(int type, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src); GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL20.GL_FALSE) {
            GTDishanhaiMod.LOGGER.error("[BHC] compile fail: {}", GL20.glGetShaderInfoLog(id, 1024));
            GL20.glDeleteShader(id); return -1;
        }
        return id;
    }

    private static String readRes(String path) {
        try (InputStream is = BlackHoleContainmentRenderer.class.getResourceAsStream(path)) {
            return is == null ? null : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    private static int loadTexture(String path) {
        try (InputStream is = BlackHoleContainmentRenderer.class.getResourceAsStream(path)) {
            if (is == null) { GTDishanhaiMod.LOGGER.error("[BHC] tex missing: {}", path); return -1; }
            try (NativeImage img = NativeImage.read(is)) {
                int w = img.getWidth(), h = img.getHeight();
                int id = GlStateManager._genTexture();
                GlStateManager._bindTexture(id);
                TextureUtil.prepareImage(id, w, h);
                GL20.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_LINEAR);
                GL20.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_LINEAR);
                GL20.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_REPEAT);
                GL20.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_REPEAT);
                img.upload(0, 0, 0, false);
                GTDishanhaiMod.LOGGER.info("[BHC] tex loaded: {} ({}x{}) id={}", path, w, h, id);
                return id;
            }
        } catch (Exception e) {
            GTDishanhaiMod.LOGGER.error("[BHC] tex fail: {}", path, e);
            return -1;
        }
    }

    private static void buildBhVbo() {
        try (InputStream is = BlackHoleContainmentRenderer.class.getResourceAsStream("/assets/gt_shanhai/models/obj/blackhole.obj")) {
            if (is == null) { GTDishanhaiMod.LOGGER.error("[BHC] obj missing"); return; }
            List<Float> verts = new ArrayList<>();
            List<Float> uvs = new ArrayList<>();
            List<int[]> faces = new ArrayList<>();
            verts.add(0f); verts.add(0f); verts.add(0f);
            uvs.add(0f); uvs.add(0f);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("v ")) {
                        String[] p = line.split("\\s+");
                        verts.add(Float.parseFloat(p[1])); verts.add(Float.parseFloat(p[2])); verts.add(Float.parseFloat(p[3]));
                    } else if (line.startsWith("vt ")) {
                        String[] p = line.split("\\s+");
                        uvs.add(Float.parseFloat(p[1])); uvs.add(Float.parseFloat(p[2]));
                    } else if (line.startsWith("f ")) {
                        String[] p = line.split("\\s+");
                        int[] f = new int[9];
                        for (int i = 0; i < 3; i++) {
                            String[] idx = p[i+1].split("/");
                            f[i*3] = Integer.parseInt(idx[0]); f[i*3+1] = Integer.parseInt(idx[1]);
                        }
                        faces.add(f);
                    }
                }
            }
            bhFaceCount = faces.size() * 3;
            // 构建交错顶点数组: x,y,z,u,v
            float[] data = new float[bhFaceCount * 5];
            int di = 0;
            for (int[] f : faces) {
                for (int i = 0; i < 3; i++) {
                    int vi = f[i*3], uvi = f[i*3+1];
                    data[di++] = verts.get(vi*3); data[di++] = verts.get(vi*3+1); data[di++] = verts.get(vi*3+2);
                    data[di++] = uvs.get(uvi*2); data[di++] = 1f - uvs.get(uvi*2+1);
                }
            }
            // 手动创建 VAO + VBO (避免 VertexBuffer.draw() 覆盖自定义 shader)
            bhVao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(bhVao);
            bhVbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, bhVbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 20, 0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 20, 12);
            GL30.glBindVertexArray(0);
            GTDishanhaiMod.LOGGER.info("[BHC] BH VAO={} VBO={} faces={}", bhVao, bhVbo, faces.size());
        } catch (Exception e) { GTDishanhaiMod.LOGGER.error("[BHC] OBJ parse fail", e); }
    }

    private static void buildLaserVbo() {
        float[] quad = {
             8.5f,0,-0.25f,0,0, 8.5f,0,0.25f,0,1, 1.0f,0,0.25f,1,1, 1.0f,0,-0.25f,1,0,
        };
        float[] tris = new float[6 * 5]; // 1 quad → 2 tris × 5 floats
        int[] idx = {0,1,2, 0,2,3};
        int di = 0;
        for (int t : idx) {
            System.arraycopy(quad, t*5, tris, di, 5); di += 5;
        }
        laserFaceCount = 6;
        laserVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(laserVao);
        laserVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, laserVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, tris, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 20, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 20, 12);
        GL30.glBindVertexArray(0);
        GTDishanhaiMod.LOGGER.info("[BHC] Laser VAO={} VBO={}", laserVao, laserVbo);
    }

    private void renderBlackHole(ShanhaiBlackHoleContainmentMachine bhc, BlockEntity be, float pt, PoseStack ps) {
        GL20.glUseProgram(bhProg);
        GL20.glBindTexture(GL20.GL_TEXTURE_2D, bhTexId);
        // GTNH OBJ 有多层重叠面 (中心/前盘/后盘/底部), 需要深度写入正确遮挡
        RenderSystem.depthMask(true);
        GL20.glUniform1f(u_Stability, bhc.getBlackHoleStability() / 100f);

        float timer = bhc.getSmoothTick(pt);
        float scaleF = timer - bhc.getAnimationStartTick();
        if (!bhc.isAnimationScaling()) scaleF = 40.0f - scaleF;
        scaleF = Mth.clamp(scaleF / 40.0f, 0.0f, 1.0f) * 0.5f;
        scaleF = scaleF * scaleF * scaleF * (scaleF * (6.0f * scaleF - 15.0f) + 10.0f);
        GL20.glUniform1f(u_Scale, scaleF);

        var center = bhc.getRenderCenter();
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        GL20.glUniform3f(u_CameraPosition, (float)(cam.x - center.x), (float)(cam.y - center.y), (float)(cam.z - center.z));
        GL20.glUniform1f(u_Time, timer);

        Matrix4f mvp = new Matrix4f(RenderSystem.getProjectionMatrix());
        mvp.mul(ps.last().pose());
        float[] m = new float[16]; mvp.get(m);
        GL20.glUniformMatrix4fv(u_MVP, false, m);

        GL30.glBindVertexArray(bhVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, bhFaceCount);
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);
    }

    private void renderLasers(ShanhaiBlackHoleContainmentMachine bhc, PoseStack ps) {
        if (laserProg < 0 || laserVao < 0 || laserTexId < 0) return;
        GL20.glUseProgram(laserProg);
        GL20.glBindTexture(GL20.GL_TEXTURE_2D, laserTexId);
        GL20.glUniform3f(uL_Color, 0.4f, 0.6f, 1.0f);
        GL20.glUniform1f(uL_Alpha, getLaserAlpha(bhc));

        var center = bhc.getRenderCenter();
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();

        // MVP = Projection × PoseStack (PoseStack 已含平移到渲染中心)
        Matrix4f mvp = new Matrix4f(RenderSystem.getProjectionMatrix());
        mvp.mul(ps.last().pose());
        float[] mbuf = new float[16];

        // modelMatrix: 激光在模型空间原点, 不需要平移 (PoseStack 已翻译)
        // GTNH: modelMatrix 仅含旋转, 平移由 glTranslated 处理
        Matrix4f model = new Matrix4f();

        // 摄像机相对渲染中心 (模型空间)
        float crx = (float)(cam.x - center.x), cry = (float)(cam.y - center.y), crz = (float)(cam.z - center.z);

        // pass 1: X-轴光束 (0°)
        GL20.glUniform3f(uL_CameraPosition, crx, cry, crz);
        mvp.get(mbuf); GL20.glUniformMatrix4fv(uL_MVP, false, mbuf);
        model.get(mbuf); GL20.glUniformMatrix4fv(uL_ModelMatrix, false, mbuf);

        GL11.glDisable(GL11.GL_CULL_FACE);
        GL30.glBindVertexArray(laserVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, laserFaceCount);

        // pass 2: Z-轴光束 (90° 绕 Y)
        model.rotateY((float)(Math.PI / 2));
        float sin = (float)Math.sin(-Math.PI / 2), cos = (float)Math.cos(-Math.PI / 2);
        float cx2 = crx * cos - crz * sin, cz2 = crx * sin + crz * cos;
        GL20.glUniform3f(uL_CameraPosition, cx2, cry, cz2);
        mvp.get(mbuf); GL20.glUniformMatrix4fv(uL_MVP, false, mbuf);
        model.get(mbuf); GL20.glUniformMatrix4fv(uL_ModelMatrix, false, mbuf);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, laserFaceCount);

        // pass 3: 180°
        model.rotateY((float)(Math.PI / 2));
        float cx3 = -crx, cz3 = -crz;
        GL20.glUniform3f(uL_CameraPosition, cx3, cry, cz3);
        mvp.get(mbuf); GL20.glUniformMatrix4fv(uL_MVP, false, mbuf);
        model.get(mbuf); GL20.glUniformMatrix4fv(uL_ModelMatrix, false, mbuf);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, laserFaceCount);

        // pass 4: 270°
        model.rotateY((float)(Math.PI / 2));
        float cx4 = -cx2, cz4 = -cz2;
        GL20.glUniform3f(uL_CameraPosition, cx4, cry, cz4);
        mvp.get(mbuf); GL20.glUniformMatrix4fv(uL_MVP, false, mbuf);
        model.get(mbuf); GL20.glUniformMatrix4fv(uL_ModelMatrix, false, mbuf);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, laserFaceCount);

        GL30.glBindVertexArray(0);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL20.glUseProgram(0);
    }

    private void renderHarmonyOrbitObjects(ShanhaiBlackHoleContainmentMachine bhc, float partialTick,
                                           PoseStack poseStack, MultiBufferSource buffer) {
        float smoothTick = bhc.getSmoothTick(partialTick);
        float alpha = getLaserAlpha(bhc);
        for (int i = 0; i < HARMONY_ORBIT_OBJECTS.size(); i++) {
            float scale = 0.0050f + i * 0.0018f;
            double radius = 3.2 + i * 1.55;
            float orbitAngle = (smoothTick * (0.72f - i * 0.13f) + i * 120.0f) % 360.0f;
            float spinAngle = (smoothTick * (1.8f + i * 0.35f)) % 360.0f;

            poseStack.pushPose();
            poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(0, 1, 0, orbitAngle));
            poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(1, 0, 1, 18.0f + i * 17.0f));
            poseStack.translate(radius, Math.sin((smoothTick + i * 31.0f) / 38.0f) * 0.35, 0.0);
            poseStack.mulPose(new Quaternionf().fromAxisAngleDeg(0, 1, 0, spinAngle));
            poseStack.scale(scale, scale, scale);

            ClientUtil.modelRenderer().renderModel(
                    poseStack.last(),
                    buffer.getBuffer(RenderType.translucent()),
                    null,
                    ClientUtil.getBakedModel(HARMONY_ORBIT_OBJECTS.get(i)),
                    alpha, alpha, alpha,
                    15728880,
                    OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY,
                    RenderType.translucent()
            );
            poseStack.popPose();
        }
    }

    private static float getLaserAlpha(ShanhaiBlackHoleContainmentMachine bhc) {
        int collapseTimer = bhc.getCollapseTimer();
        if (collapseTimer <= 0) return 1.0f;
        float t = Mth.clamp(collapseTimer / 40.0f, 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    @Override
    public void render(BlockEntity be, float pt, PoseStack ps, MultiBufferSource buf, int light, int overlay) {
        MetaMachine meta = null;
        if (be instanceof IMachineBlockEntity mbe) meta = mbe.getMetaMachine();
        if (!(meta instanceof IMultiController ctrl) || !ctrl.isFormed()) return;
        if (!(meta instanceof ShanhaiBlackHoleContainmentMachine bhc)) return;
        if (!bhc.isShouldRender()) return;

        lazyInit();
        if (bhProg < 0 || bhVao < 0 || bhTexId < 0) return;

        ps.pushPose();
        GLStateSnapshot state = captureGLState();
        try {
            var d = bhc.getFrontFacing().getOpposite();
            ps.translate(0.5 + 7 * d.getStepX(), 0.5 + 11, 0.5 + 7 * d.getStepZ());

            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);

            // 激光: 无深度屏蔽, 覆盖在一切之上
            RenderSystem.depthMask(false);
            renderLasers(bhc, ps);
            // 黑洞: 恢复深度写入, OBJ 多层重叠面需正确遮挡
            renderBlackHole(bhc, be, pt, ps);

            renderHarmonyOrbitObjects(bhc, pt, ps, buf);
        } finally {
            restoreGLState(state);
            ps.popPose();
        }
    }

    private static GLStateSnapshot captureGLState() {
        return new GLStateSnapshot(
                GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
                GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),
                GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
                GL11.glGetBoolean(GL11.GL_BLEND),
                GL11.glGetBoolean(GL11.GL_CULL_FACE),
                GL11.glGetBoolean(GL11.GL_DEPTH_TEST),
                GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        );
    }

    private static void restoreGLState(GLStateSnapshot state) {
        GL20.glUseProgram(state.program);
        GL30.glBindVertexArray(state.vertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, state.arrayBuffer);
        GL13.glActiveTexture(state.activeTexture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, state.texture2d);
        if (state.blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        if (state.cull) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
        if (state.depthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        RenderSystem.depthMask(state.depthMask);
    }

    private static class GLStateSnapshot {
        private final int program;
        private final int vertexArray;
        private final int arrayBuffer;
        private final int activeTexture;
        private final int texture2d;
        private final boolean blend;
        private final boolean cull;
        private final boolean depthTest;
        private final boolean depthMask;

        private GLStateSnapshot(int program, int vertexArray, int arrayBuffer, int activeTexture, int texture2d,
                                boolean blend, boolean cull, boolean depthTest, boolean depthMask) {
            this.program = program;
            this.vertexArray = vertexArray;
            this.arrayBuffer = arrayBuffer;
            this.activeTexture = activeTexture;
            this.texture2d = texture2d;
            this.blend = blend;
            this.cull = cull;
            this.depthTest = depthTest;
            this.depthMask = depthMask;
        }
    }
}
