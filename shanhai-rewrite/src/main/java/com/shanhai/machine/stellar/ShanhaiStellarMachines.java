package com.shanhai.machine.stellar;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.mojang.logging.LogUtils;
import com.shanhai.registry.ShanhaiRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <b>星律样板总成</b>的注册入口 —— 写法<b>逐项照抄</b>已验收的
 * {@link com.shanhai.machine.wildcard.ShanhaiWildcardMachines}（同类仓室、同一注册时机），
 * 只换三处：注册名、机器类、tooltip 文案。
 *
 * <h2>注册链（与既有机器<b>同一拍</b>，没有新时机）</h2>
 * <pre>
 *   ShanhaiMod 构造器
 *     └─ modEventBus.addGenericListener(MachineDefinition.class, ShanhaiRegistry::onMachineRegister)
 *   GTCEu 的 GTMachines.init() 内部 post 的 MachineDefinition RegisterEvent
 *     └─ ShanhaiRegistry.onMachineRegister(…)
 *          ├─ ShanhaiMachines.init()            （主机 + 模块）
 *          ├─ ShanhaiWildcardMachines.init()    （超级通配符样板总成）
 *          └─ ShanhaiStellarMachines.init()     ← 本类（2026-10 新增的一行）
 * </pre>
 * <p>⚠️ 必须在 {@code ShanhaiMachines.init()} <b>之后</b>：后者末尾有「模块表为空就抛」的硬校验，
 * 那一段的失败语义是「模块链坏了」，把本类插在它前面会让该判据指向错的元凶
 * （同 {@code ShanhaiWildcardMachines} 的注释）。
 *
 * <h2>🔴 外观：<b>照抄老山海那台</b>（{@code gt_shanhai:recipe_type_pattern_buffer}）的贴图</h2>
 * 阶段七按用户原话「星律样板总成复用一下老山海的贴图」改成与老机器<b>同一个渲染器 + 同一批贴图</b>：
 * <pre>
 *   gt_shanhai 原注册（反编译 upstream-DShanhaiMachines.java:1450-1452）：
 *     .renderer(() -&gt; new WorkableCasingMachineRenderer(
 *             new ResourceLocation("gt_shanhai", "block/casings/recipe_type_pattern_buffer_casing"),
 *             new ResourceLocation("gt_shanhai", "block/machine/part/recipe_type_pattern_buffer")))
 *   本类：同一构造，只把命名空间换成 shanhai（贴图按字节原样拷到我们的 assets 下）
 *     sha256 清单见交付报告「阶段七 · 贴图复用」一节
 * </pre>
 * ⚠️ 为什么必须把贴图<b>拷一份</b>到 {@code assets/shanhai/...}：渲染器是拿我们传进去的
 * {@link ResourceLocation} 去<b>我们自己</b>的命名空间里找路径的，跨命名空间直接引用别人的 jar
 * 在「别人没装/改了名」时会变成紫黑格；拷贝后我们自足（代价 = 8 个文件、约 9.6 KB）。
 * <p>旧实现（阶段一~六）用的是 {@code overlayTieredHullRenderer("me_pattern_buffer")} +
 * {@code assets/shanhai/models/block/machine/part/me_pattern_buffer.json}；那个 json 现在是<b>死文件</b>
 * （没有任何注册再指向它），保留未删（删文件不在本轮范围），报告里标了。
 *
 * <h2>🔴 PartAbility 为什么必须是这 4 个</h2>
 * 与上游 {@code gt_shanhai:recipe_type_pattern_buffer} 的注册完全一致
 * （反编译 {@code DShanhaiMachines.java:1462-1464} 原文：
 * {@code .abilities(PartAbility.IMPORT_ITEMS, PartAbility.IMPORT_FLUIDS,
 * PartAbility.EXPORT_ITEMS, PartAbility.EXPORT_FLUIDS)}），也与本工程已验收的
 * 超级通配符样板总成相同 ⇒ 它能插进任何「接受 ME 样板仓」的多方块仓室位。
 */
public final class ShanhaiStellarMachines {

    /**
     * 注册名（方块 / 物品 / BlockEntity 三合一）。
     *
     * <p>⇒ 物品 id = {@code shanhai:stellar_sample_assembler}。
     * <b>刻意不用</b>上游的 {@code gt_shanhai:recipe_type_pattern_buffer}：
     * 上游是另一个 mod 的 id，同名会撞注册表（且我们本来就是改编版，不是同一台机器）。
     */
    public static final String STELLAR_SAMPLE_ASSEMBLER_ID = "stellar_sample_assembler";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 幂等守卫：GTCEu 将来若多 post 一次事件，重复注册会抛「registry contains key … already」。 */
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    /** 注册后非空。 */
    private static volatile MachineDefinition stellarSampleAssembler;

    private ShanhaiStellarMachines() {}

    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        // 🔴 工厂 lambda 里传 IO.BOTH —— 与上游 RecipeTypePatternBufferPartMachine 的注册实参同一口径。
        //    IO.BOTH 是「产物回 ME」那条 Ticker 的注册硬条件（见 StellarSampleAssemblerPartMachine 类注释 §三）。
        stellarSampleAssembler = ShanhaiRegistration.REGISTRATE
                .machine(STELLAR_SAMPLE_ASSEMBLER_ID,
                        holder -> new StellarSampleAssemblerPartMachine(holder, IO.BOTH))
                .langValue("Stellar Sample Assembler")
                .tier(9)
                .rotationState(RotationState.ALL)
                .abilities(
                        PartAbility.IMPORT_ITEMS,
                        PartAbility.IMPORT_FLUIDS,
                        PartAbility.EXPORT_ITEMS,
                        PartAbility.EXPORT_FLUIDS)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation("shanhai", "block/casings/recipe_type_pattern_buffer_casing"),
                        new ResourceLocation("shanhai", "block/machine/part/recipe_type_pattern_buffer")))
                .tooltips(
                        Component.translatable("block.gtceu.pattern_buffer.desc.0"),
                        Component.translatable("shanhai.machine.stellar_sample_assembler.desc.0"),
                        Component.translatable("gtceu.machine.me_pattern_buffer.desc.3"),
                        Component.translatable("gtceu.machine.me_pattern_buffer.desc.5"),
                        Component.translatable("block.gtceu.pattern_buffer.desc.2"),
                        Component.translatable("gtceu.universal.enabled"))
                .register();
        LOGGER.info("[SHANHAI-STELLAR] 已注册仓室：shanhai:{}（普通样板槽 {} 格 = {} 列 × {} 行 × {} 页；"
                        + "通配符样板槽常量 {} 格，本阶段未接入）",
                STELLAR_SAMPLE_ASSEMBLER_ID,
                StellarSampleAssemblerPartMachine.PATTERN_SLOT_COUNT,
                StellarSampleAssemblerPartMachine.PATTERN_COLUMNS,
                StellarSampleAssemblerPartMachine.PATTERN_ROWS_PER_PAGE,
                StellarSampleAssemblerPartMachine.PATTERN_PAGE_COUNT,
                StellarSampleAssemblerPartMachine.WILDCARD_SLOT_COUNT);
    }

    /** 注册后的定义句柄；未注册时为 {@code null}。 */
    public static MachineDefinition stellarSampleAssembler() {
        return stellarSampleAssembler;
    }
}
