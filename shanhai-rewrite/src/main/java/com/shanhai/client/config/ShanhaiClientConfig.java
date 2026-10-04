package com.shanhai.client.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 山海的<b>客户端配置</b>（{@code config/shanhai-client.toml}）。
 *
 * <h2>为什么必须有这一份</h2>
 * 终末之环的最终位姿只有「进游戏看一眼」才能判定 —— 而光环落在哪、多大、歪不歪，
 * 受用户装的 YSM 模型、模型缩放、模型自身骨骼影响。把 8 个旋钮交给用户，
 * 比让他等我们重编一次 jar 快得多（这正是他上一个同类 mod 的做法，他已熟悉）。
 *
 * <h2>🔴 两条硬约定（都是用户上一个 mod 定过的口径）</h2>
 * <ol>
 *   <li><b>变换顺序 = {@code translate → rotate → scale}（缩放放最后）</b>。
 *       这样位移量的单位含义不受缩放影响（1 格 = 16 像素），用户调过的数不用重调。
 *       实现见 {@link com.shanhai.client.render.halo.HaloPose#applyTo}。</li>
 *   <li><b>注册必须在 {@code @Mod} 构造器里</b>（{@code ModLoadingContext.get().registerConfig(...)}）。
 *       放进 {@code FMLClientSetupEvent} 的话，游戏内 Mods 列表认不出 Config 按钮 ——
 *       用户就没法在游戏里点齿轮改数。见 {@code ShanhaiMod} 构造器。</li>
 * </ol>
 *
 * <h2>默认值的来源（不是拍的）</h2>
 * <ul>
 *   <li>{@code [halo_end.model_pose]} 全 0 / 1.0 —— <b>照抄启示录原版</b>
 *       （{@code OdamaneHaloLayer.render} 在 Curios 路径下除模型的 head/Halo 部件自身位移外
 *       <b>不施加任何额外变换</b>，所以"默认 = 什么都不加"就是原版行为；改了反而错）。</li>
 *   <li>{@code [halo_end.ysm_pose]} = {@code 0 / 20 / -5 px}、{@code 0/0/180 度}、{@code scale 1.0} ——
 *       位姿（位置 / 旋转）<b>逐值抄自用户 2026-09-21 实机验收通过的</b>
 *       {@code test/config/ysm_render_compat-client.toml} 的 {@code [halo_of_the_end]} 一节；
 *       只有 {@code scale} 于 2026-10-03 按用户实测要求改成 {@code 1.0}
 *       （用户原话：「大小不要是1.5，要是1，1是正常大小」）。
 *       ⚠️ {@code scale} 是<b>线性倍率、在渲染路径上只施加一次</b>
 *       （取证：{@code temp\halo-scale\verify\ScaleCountProbe} 的实测几何比 = 1.500 而不是 2.250）。</li>
 * </ul>
 * ⚠️ <b>改完要重启游戏才生效</b>（本类在渲染每帧读 {@code .get()}，
 * 但 Forge 配置文件本身的重新加载只在游戏启动时发生；游戏内 Mods→Config 界面改动
 * 会触发 {@code ModConfigEvent}，但为稳妥起见，改数后重启一次最保险）。
 */
public final class ShanhaiClientConfig {

    /**
     * 走哪条姿势路径。
     *
     * <ul>
     *   <li>{@link #AUTO}（默认）：按<b>渲染器类型</b>自动判定 ——
     *       渲染器是原版 {@code PlayerRenderer} ⇒ 走 {@code [model_pose]}（CuriosLayer 已经在画了）；
     *       不是 ⇒ 走 {@code [ysm_pose]}（YSM 自算姿势）。</li>
     *   <li>{@link #SELF_POSE}：<b>强制自算姿势</b>（不看渲染器类型，且主动让 Curios 路径闭嘴）。
     *       万一自动判定在用户机器上选错了，他一键切到这里即可。</li>
     *   <li>{@link #MODEL_POSE}：<b>强制原版路径</b>（YSM 兜底路径完全不画）。</li>
     * </ul>
     */
    public enum PoseMode {
        AUTO,
        SELF_POSE,
        MODEL_POSE
    }

    public static final ForgeConfigSpec CLIENT_SPEC;

    private static final ForgeConfigSpec.EnumValue<PoseMode> POSE_MODE;

    private static final ForgeConfigSpec.BooleanValue HALO_ENABLED;

    // ---- [halo_end.model_pose] 原版权威路径（CuriosLayer 内部）----
    private static final ForgeConfigSpec.DoubleValue MODEL_OFFSET_X;
    private static final ForgeConfigSpec.DoubleValue MODEL_OFFSET_Y;
    private static final ForgeConfigSpec.DoubleValue MODEL_OFFSET_Z;
    private static final ForgeConfigSpec.DoubleValue MODEL_ROT_X;
    private static final ForgeConfigSpec.DoubleValue MODEL_ROT_Y;
    private static final ForgeConfigSpec.DoubleValue MODEL_ROT_Z;
    private static final ForgeConfigSpec.DoubleValue MODEL_SCALE;

    // ---- [halo_end.ysm_pose] YSM 自算姿势路径（RenderLivingEvent.Post）----
    private static final ForgeConfigSpec.DoubleValue YSM_OFFSET_X;
    private static final ForgeConfigSpec.DoubleValue YSM_OFFSET_Y;
    private static final ForgeConfigSpec.DoubleValue YSM_OFFSET_Z;
    private static final ForgeConfigSpec.DoubleValue YSM_ROT_X;
    private static final ForgeConfigSpec.DoubleValue YSM_ROT_Y;
    private static final ForgeConfigSpec.DoubleValue YSM_ROT_Z;
    private static final ForgeConfigSpec.DoubleValue YSM_SCALE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("终末之环（shanhai:halo_end）的头顶渲染参数。",
                        "位置单位 = 像素（1 格 = 16 像素）：X = 左右，Y = 上下（正 = 上），Z = 前后。",
                        "旋转单位 = 度：X = 俯仰，Y = 偏航，Z = 翻滚。",
                        "缩放 = 倍数：1.0 = 原大小。",
                        "变换顺序恒为 位移 → 旋转 → 缩放（缩放放最后，所以位移量不受缩放影响）。",
                        "⚠️ 改完请重启游戏。")
                .push("halo_end");

        HALO_ENABLED = b.comment("是否渲染终末之环的头顶光环。")
                .define("enabled", true);

        POSE_MODE = b.comment("走哪条姿势路径：AUTO = 按渲染器类型自动判定（默认）；",
                        "SELF_POSE = 强制自算姿势（自动判定不对时切这个）；",
                        "MODEL_POSE = 强制原版路径（本 mod 不补画）。")
                .defineEnum("poseMode", PoseMode.AUTO);

        // 两组位姿必须各占【自己的小节】。少了这两个 push，"scale / position / rotation"
        // 会在同一个路径上被定义两遍 ⇒ 后者覆盖前者 ⇒ model_pose 的默认值会静默变成
        // YSM 那组（Curios 路径就会被多施加一次位移）。实测判据见 verify\ConfigSpecProbe。
        b.comment("【A 原版/Curios 路径】的额外位姿。默认全 0 / 1.0 = 与启示录逐值等价（不改就对）。")
                .push("model_pose");
        MODEL_SCALE = b.comment("缩放倍数。原版 = 1.0。")
                .defineInRange("scale", 1.0D, 0.05D, 20.0D);
        b.comment("位移（像素）。原版 = 全 0（启示录在 Curios 路径下不施加额外位移）。").push("position");
        MODEL_OFFSET_X = b.defineInRange("offsetX", 0.0D, -512.0D, 512.0D);
        MODEL_OFFSET_Y = b.defineInRange("offsetY", 0.0D, -512.0D, 512.0D);
        MODEL_OFFSET_Z = b.defineInRange("offsetZ", 0.0D, -512.0D, 512.0D);
        b.pop();
        b.comment("旋转（度）。原版 = 全 0。").push("rotation");
        MODEL_ROT_X = b.defineInRange("rotX", 0.0D, -3600.0D, 3600.0D);
        MODEL_ROT_Y = b.defineInRange("rotY", 0.0D, -3600.0D, 3600.0D);
        MODEL_ROT_Z = b.defineInRange("rotZ", 0.0D, -3600.0D, 3600.0D);
        b.pop();
        b.pop();

        b.comment("【B 自算姿势/YSM 路径】的位姿。默认值 = 用户 2026-09-21 在 YSM 下实机验收值。")
                .push("ysm_pose");
        YSM_SCALE = b.comment("缩放倍数。默认 1.0 = 原大小（线性倍率，只施加一次）。")
                .defineInRange("scale", 1.0D, 0.05D, 20.0D);
        b.comment("位移（像素）。默认 0 / 20 / -5 = 用户实机验收值（YSM 下光环浮在头顶上方）。")
                .push("position");
        YSM_OFFSET_X = b.defineInRange("offsetX", 0.0D, -512.0D, 512.0D);
        YSM_OFFSET_Y = b.defineInRange("offsetY", 20.0D, -512.0D, 512.0D);
        YSM_OFFSET_Z = b.defineInRange("offsetZ", -5.0D, -512.0D, 512.0D);
        b.pop();
        b.comment("旋转（度）。默认 0 / 0 / 180 = 用户实机验收值。")
                .push("rotation");
        YSM_ROT_X = b.defineInRange("rotX", 0.0D, -3600.0D, 3600.0D);
        YSM_ROT_Y = b.defineInRange("rotY", 0.0D, -3600.0D, 3600.0D);
        YSM_ROT_Z = b.defineInRange("rotZ", 180.0D, -3600.0D, 3600.0D);
        b.pop();
        b.pop();

        b.pop();
        CLIENT_SPEC = b.build();
    }

    private ShanhaiClientConfig() {}

    // 读配置时全部兜底：配置还没加载时 .get() 会抛，宁可退回"原版默认值"也不让渲染线程炸。

    public static boolean haloEnabled() {
        try {
            return HALO_ENABLED.get();
        } catch (Throwable t) {
            return true;
        }
    }

    public static PoseMode poseMode() {
        try {
            return POSE_MODE.get();
        } catch (Throwable t) {
            return PoseMode.AUTO;
        }
    }

    public static double[] modelPose() {
        return new double[]{
                dv(MODEL_OFFSET_X, 0.0D), dv(MODEL_OFFSET_Y, 0.0D), dv(MODEL_OFFSET_Z, 0.0D),
                dv(MODEL_ROT_X, 0.0D), dv(MODEL_ROT_Y, 0.0D), dv(MODEL_ROT_Z, 0.0D),
                dv(MODEL_SCALE, 1.0D)
        };
    }

    public static double[] ysmPose() {
        return new double[]{
                dv(YSM_OFFSET_X, 0.0D), dv(YSM_OFFSET_Y, 20.0D), dv(YSM_OFFSET_Z, -5.0D),
                dv(YSM_ROT_X, 0.0D), dv(YSM_ROT_Y, 0.0D), dv(YSM_ROT_Z, 180.0D),
                dv(YSM_SCALE, 1.0D)
        };
    }

    private static double dv(ForgeConfigSpec.DoubleValue v, double fallback) {
        try {
            return v.get();
        } catch (Throwable t) {
            return fallback;
        }
    }
}
