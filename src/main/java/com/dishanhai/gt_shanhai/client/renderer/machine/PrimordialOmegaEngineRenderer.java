package com.dishanhai.gt_shanhai.client.renderer.machine;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;

import com.gregtechceu.gtceu.api.machine.MetaMachine;

import org.gtlcore.gtlcore.utils.RenderUtil;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialSphereStyle;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

import java.util.function.Consumer;

/**
 * 原始终焉引擎 TESR 渲染器。
 * <p>
 * 蒸汽时代轨道环由 {@link AbstractRingRenderer} 统一处理；中心球体按机器上的
 * {@link PrimordialSphereStyle} 分派给两套互斥的实现：
 * <ul>
 * <li>{@link PrimordialSphereStyle#UNIVERSE} → {@link PrimordialUniverseSphereRenderer}（鸿蒙微型宇宙）</li>
 * <li>{@link PrimordialSphereStyle#NEUTRON_STAR} → {@link PrimordialNeutronStarSphereRenderer}（伪神锻星体管线）</li>
 * </ul>
 * 两条分支的时钟语义不同：宇宙模式沿用「停机归零」的 smoothTick（行星停摆即可），
 * 中子星常驻可见必须用连续时钟，否则累积角度会在工作状态翻转时弹跳。
 */
public class PrimordialOmegaEngineRenderer extends AbstractRingRenderer {

    private static final ResourceLocation SPACE_MODEL = new ResourceLocation("gtlcore", "obj/space");
    private static final ResourceLocation STAR_MODEL = new ResourceLocation("gtlcore", "obj/star");

    public PrimordialOmegaEngineRenderer(ResourceLocation baseCasing, ResourceLocation workableModel) {
        super(baseCasing, workableModel);
    }

    @Override
    protected VertexBuffer[] getRingBuffers(MetaMachine machine) {
        return PrimordialOmegaEngineRingBuffer.getRingBuffers();
    }

    @Override
    protected float getSmoothTick(MetaMachine machine, float partialTick) {
        if (machine instanceof PrimordialOmegaEngineMachine poe && poe.getRecipeLogic().isWorking()) {
            return RenderUtil.getSmoothTick(poe, partialTick);
        }
        return 0f;
    }

    @Override
    protected void renderSpecialEffects(MetaMachine machine, BlockEntity blockEntity,
                                        float smoothTick, boolean isWorking,
                                        Direction facing, float partialTick,
                                        PoseStack poseStack, MultiBufferSource buffer) {
        if (sphereStyleOf(machine) == PrimordialSphereStyle.NEUTRON_STAR) {
            // 中子星常驻可见，必须用连续时钟：smoothTick 停机归零会让三层球壳的累积角度瞬间弹回基准。
            PrimordialNeutronStarSphereRenderer.enqueue(
                    blockEntity, facing, RenderUtil.getSmoothTick(machine, partialTick), isWorking);
        } else {
            PrimordialUniverseSphereRenderer.render(smoothTick, facing, poseStack);
        }
    }

    private static PrimordialSphereStyle sphereStyleOf(MetaMachine machine) {
        PrimordialSphereStyle forced = clientForcedStyle();
        if (forced != null) return forced;
        return machine instanceof PrimordialOmegaEngineMachine poe
                ? poe.getSphereStyle()
                : PrimordialSphereStyle.UNIVERSE;
    }

    /**
     * 模组配置里的客户端显示覆盖；返回 null 表示 FOLLOW_MACHINE（听机器的）。
     * <p>
     * 用 COMMON spec 是刻意的：Forge 的 COMMON 不同步（各端各读各自 global config 目录下的 toml），
     * 多人环境下玩家改的就是自己那份，正好符合「只影响我自己画面」的语义。
     * <p>
     * isLoaded() 兜底不可省：配置未加载时 ConfigValue.get() 在开发环境会直接抛 IllegalStateException。
     * 逐帧调用无额外开销——get() 命中 ConfigValue 内部的 cachedValue，cloth 保存走的 set() 与配置重载
     * 都会自行失效该缓存，所以这里不需要再包一层本地缓存或挂 ModConfigEvent 监听。
     */
    private static PrimordialSphereStyle clientForcedStyle() {
        if (!DShanhaiConfig.COMMON_SPEC.isLoaded()) return null;
        return switch (DShanhaiConfig.COMMON.primordialSphereStyle.get()) {
            case UNIVERSE -> PrimordialSphereStyle.UNIVERSE;
            case NEUTRON_STAR -> PrimordialSphereStyle.NEUTRON_STAR;
            default -> null;
        };
    }

    @Override
    protected void registerAdditionalModels(Consumer<ResourceLocation> registry) {
        registry.accept(SPACE_MODEL);
        registry.accept(STAR_MODEL);
        registry.accept(new ResourceLocation("gtlcore", "obj/the_nether"));
        registry.accept(new ResourceLocation("gtlcore", "obj/overworld"));
        registry.accept(new ResourceLocation("gtlcore", "obj/the_end"));
    }
}
