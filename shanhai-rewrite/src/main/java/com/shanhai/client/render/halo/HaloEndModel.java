package com.shanhai.client.render.halo;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 终末之环的头顶模型 —— <b>几何逐值照抄</b>启示录的实现
 * （{@code [Forge]RevelationFix-1.20.1-4.4.jar!com.mega.revelationfix.common.odamane.client.OdamaneHaloModel}），
 * 但<b>不依赖它的任何类</b>（那个 mod 不在目标实例里）。所有数值来源都是 {@code javap -p -c} 的
 * 浮点常量，逐条抄录，未做任何"看起来差不多"的近似。
 *
 * <h2>部件树（javap 逐指令还原）</h2>
 * <pre>
 * root（PlayerModel.createMesh(NONE, true) 的原版根）
 *  └─ head                                   ← 取自 HumanoidModel.head（不新建）
 *      └─ Halo   PartPose.offset(0, -5, 10)  ← 无自带 cube，纯容器
 *          ├─ bone                        offset(0,0,0)   18x18 主圆盘 + 4 段装饰条
 *          ├─ bone2                       offset(0,0,0)   右侧横条 + cube_r1/r2/r3
 *          ├─ bone3                       offset(0,0,0)   左侧横条 + cube_r4/r5/r6
 *          ├─ bone4                       offset(0,0,0)   上侧小块 + cube_r7/r8
 *          └─ bone5                       offset(0,0,0)   下侧小块 + cube_r9/r10
 * </pre>
 * ⚠️ 它<b>是 head 的子部件</b>（不是 root 的），这一点是"环跟着头转"的几何前提。
 *
 * <h2>贴图布局是 64×64 空间</h2>
 * {@code texOffs} 最大到 (18, 36)，且 {@code LayerDefinition.create(mesh, 64, 64)}。
 * <p>🔴 <b>所以实体贴图必须是原版那张 64×64 的图，不是我们自己拼的、也不是物品栏那张 16×256 的动画条。</b>
 * 本轮已换成<b>逐字节拷贝</b>的启示录原图：
 * {@code assets/shanhai/textures/entity/halo_end.png}，sha256
 * {@code 816296D2496A078D6C8A17BD2615D6E392C6BFDDF926E8C4ABC0DC13CA7B59F5}（819 B，64×64）。
 * 取图路径：{@code [诡厄巫法：启示录] GoetyRevelation-2.3.3fix(1).jar}
 * → {@code META-INF/jarjar/[Forge]RevelationFix-1.20.1-4.4.jar}
 * → {@code assets/revelationfix/textures/entity/player/halo_the_end.png}。
 * <p>物品栏贴图（{@code textures/item/halo_end.png}，16×256 动画条）<b>与头顶渲染无关</b>，
 * 保持不动。
 */
@OnlyIn(Dist.CLIENT)
public class HaloEndModel extends PlayerModel<AbstractClientPlayer> {

    /**
     * 与启示录同构的层标识（但它们的是 {@code revelationfix:halo_of_the_end}）。
     * 用我们自己的命名空间，避免与任何外部 mod 撞车。
     */
    public static final ModelLayerLocation LAYER_LOCATION =
            new ModelLayerLocation(new ResourceLocation("shanhai", "halo_of_the_end"), "main");

    public final ModelPart Halo;
    public final ModelPart bone;
    public final ModelPart bone2;
    public final ModelPart bone3;
    public final ModelPart bone4;
    public final ModelPart bone5;

    public HaloEndModel(ModelPart root, boolean slim) {
        super(root, slim);
        this.Halo = this.head.getChild("Halo");
        this.bone = this.Halo.getChild("bone");
        this.bone2 = this.Halo.getChild("bone2");
        this.bone3 = this.Halo.getChild("bone3");
        this.bone4 = this.Halo.getChild("bone4");
        this.bone5 = this.Halo.getChild("bone5");
    }

    /** 逐值与 {@code OdamaneHaloModel.createBodyLayer()} 一致（javap 常量逐个抄录）。 */
    public static LayerDefinition createBodyLayer() {
        final CubeDeformation none = CubeDeformation.NONE;
        MeshDefinition mesh = PlayerModel.createMesh(none, true);
        PartDefinition head = mesh.getRoot().getChild("head");

        PartDefinition halo = head.addOrReplaceChild("Halo", CubeListBuilder.create(),
                PartPose.offset(0.0F, -5.0F, 10.0F));

        halo.addOrReplaceChild("bone", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-9.0F, -9.0F, 0.0F, 18.0F, 18.0F, 0.0F, none)
                        .texOffs(0, 18).addBox(9.0F, -3.0F, 0.0F, 8.0F, 6.0F, 0.0F, none)
                        .texOffs(16, 18).addBox(-17.0F, -3.0F, 0.0F, 8.0F, 6.0F, 0.0F, none)
                        .texOffs(0, 24).addBox(-1.5F, 9.0F, 0.0F, 4.0F, 6.0F, 0.0F, none)
                        .texOffs(8, 24).addBox(-1.5F, -15.0F, 0.0F, 4.0F, 6.0F, 0.0F, none)
                        .texOffs(8, 24).mirror().addBox(-2.5F, -15.0F, 0.0F, 4.0F, 6.0F, 0.0F, none).mirror(false)
                        .texOffs(0, 24).mirror().addBox(-2.5F, 9.0F, 0.0F, 4.0F, 6.0F, 0.0F, none).mirror(false),
                PartPose.offset(0.0F, 0.0F, 0.0F));

        PartDefinition bone2 = halo.addOrReplaceChild("bone2", CubeListBuilder.create()
                        .texOffs(16, 31).addBox(20.5F, -1.5F, 0.0F, 7.0F, 3.0F, 0.0F, none),
                PartPose.offset(0.0F, 0.0F, 0.0F));
        bone2.addOrReplaceChild("cube_r1", CubeListBuilder.create()
                        .texOffs(3, 35).mirror().addBox(0.2164F, -0.9763F, 0.0F, 2.0F, 1.0F, 0.0F, none).mirror(false),
                PartPose.offsetAndRotation(21.0237F, -0.7836F, 0.0F, 0.0F, 0.0F, -1.789F));
        bone2.addOrReplaceChild("cube_r2", CubeListBuilder.create()
                        .texOffs(5, 34).mirror().addBox(-1.0F, 0.0F, 0.0F, 2.0F, 1.0F, 0.0F, none).mirror(false),
                PartPose.offsetAndRotation(19.7456F, 1.653F, 0.0F, 0.0F, 0.0F, -1.2654F));
        bone2.addOrReplaceChild("cube_r3", CubeListBuilder.create()
                        .texOffs(1, 34).mirror()
                        .addBox(1.0F, 19.0F, 0.0F, 2.0F, 1.0F, 0.0F, new CubeDeformation(5.0E-4F)).mirror(false),
                PartPose.offsetAndRotation(1.0F, 2.0F, 0.0F, 0.0F, 0.0F, -1.5708F));

        PartDefinition bone3 = halo.addOrReplaceChild("bone3", CubeListBuilder.create()
                        .texOffs(16, 31).mirror().addBox(-27.5F, -1.5F, 0.0F, 7.0F, 3.0F, 0.0F, none).mirror(false),
                PartPose.offset(0.0F, 0.0F, 0.0F));
        bone3.addOrReplaceChild("cube_r4", CubeListBuilder.create()
                        .texOffs(7, 35).addBox(-1.0F, 0.0F, 0.0F, 2.0F, 1.0F, 0.0F, none),
                PartPose.offsetAndRotation(-19.7456F, 1.653F, 0.0F, 0.0F, 0.0F, 1.2654F));
        bone3.addOrReplaceChild("cube_r5", CubeListBuilder.create()
                        .texOffs(1, 36).addBox(-3.0F, 19.0F, 0.0F, 2.0F, 1.0F, 0.0F, new CubeDeformation(5.0E-4F)),
                PartPose.offsetAndRotation(-1.0F, 2.0F, 0.0F, 0.0F, 0.0F, 1.5708F));
        bone3.addOrReplaceChild("cube_r6", CubeListBuilder.create()
                        .texOffs(1, 35).addBox(-2.2164F, -0.9763F, 0.0F, 2.0F, 1.0F, 0.0F, none),
                PartPose.offsetAndRotation(-21.0237F, -0.7836F, 0.0F, 0.0F, 0.0F, 1.789F));

        PartDefinition bone4 = halo.addOrReplaceChild("bone4", CubeListBuilder.create()
                        .texOffs(16, 36).addBox(-0.5F, 17.5F, 0.0F, 1.0F, 1.0F, 0.0F, new CubeDeformation(-0.005F)),
                PartPose.offset(0.0F, 0.0F, 0.0F));
        bone4.addOrReplaceChild("cube_r7", CubeListBuilder.create()
                        .texOffs(16, 29).mirror()
                        .addBox(-1.0F, 0.0F, -1.0F, 2.0F, 1.0F, 0.0F, new CubeDeformation(-5.0E-4F)).mirror(false),
                PartPose.offsetAndRotation(-0.7218F, 17.0F, 1.0F, 0.0F, 0.0F, 0.2182F));
        bone4.addOrReplaceChild("cube_r8", CubeListBuilder.create()
                        .texOffs(17, 29).mirror()
                        .addBox(-1.0F, 0.0F, -1.0F, 2.0F, 1.0F, 0.0F, new CubeDeformation(-5.0E-4F)).mirror(false),
                PartPose.offsetAndRotation(0.7218F, 17.0F, 1.0F, 0.0F, 0.0F, -0.2182F));

        PartDefinition bone5 = halo.addOrReplaceChild("bone5", CubeListBuilder.create()
                        .texOffs(16, 36).addBox(-0.5F, -18.5F, 0.0F, 1.0F, 1.0F, 0.0F, new CubeDeformation(-9.0E-4F)),
                PartPose.offset(0.0F, 0.0F, 0.0F));
        bone5.addOrReplaceChild("cube_r9", CubeListBuilder.create()
                        .texOffs(16, 29).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 1.0F, 0.0F, none),
                PartPose.offsetAndRotation(0.7218F, -17.0F, 1.0F, 0.0F, 0.0F, 0.2182F));
        bone5.addOrReplaceChild("cube_r10", CubeListBuilder.create()
                        .texOffs(18, 29).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 1.0F, 0.0F, none),
                PartPose.offsetAndRotation(-0.7218F, -17.0F, 1.0F, 0.0F, 0.0F, -0.2182F));

        return LayerDefinition.create(mesh, 64, 64);
    }

    /**
     * ⚠️ <b>刻意【不】重写 {@code setupAnim}</b>（上一轮曾经重写过，本轮删掉）。
     *
     * <p>理由有取证件、也有实测对照：
     * <ol>
     *   <li>启示录<b>从不</b>调用它自己的光环模型 {@code setupAnim}
     *       —— 全内层 jar 里只有 {@code OdamaneHaloModel} 自己引用该方法
     *       （{@code OdamaneHaloModel.javap} 的方法表里 {@code m_6973_} 两个桥接方法都只是转调
     *       它自己的 {@code setupAnim}，没有任何外部调用者）。⇒ 原版光环的
     *       {@code head.xRot/yRot} <b>恒为 0</b>，环只跟身体、不跟头的俯仰。</li>
     *   <li>更关键：<b>不调用 ⇒ {@code head.translateAndRotate(poseStack)} 退化为纯位移
     *       （而 {@code head} 的位移是 {@code PartPose.ZERO}）⇒ 整个调用是一条恒等变换。</b>
     *       这一点是"默认参数 = 与启示录逐值等价"能被字节码证明的前提；
     *       一旦调了 {@code setupAnim}，{@code head} 就带上 {@code netHeadYaw/headPitch}，
     *       用户在 YSM 下调好的那组默认值立刻失效。</li>
     * </ol>
     * 删除 {@code setupAnim} 后，类里只剩"部件树 + 字段"，与 {@code OdamaneHaloModel} 的结构一一对应。
     */
}
