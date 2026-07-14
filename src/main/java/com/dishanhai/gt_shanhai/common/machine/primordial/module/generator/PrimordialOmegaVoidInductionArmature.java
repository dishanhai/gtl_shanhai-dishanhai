package com.dishanhai.gt_shanhai.common.machine.primordial.module.generator;
import com.dishanhai.gt_shanhai.common.machine.primordial.*;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;

import org.gtlcore.gtlcore.utils.MachineIO;

import java.math.BigInteger;
import java.util.UUID;
import java.util.List;

/**
 * 原始真空零点能发生器
 * 从真空量子涨落中提取零点能的蒸汽供能模块
 */
public class PrimordialOmegaVoidInductionArmature extends PrimordialOmegaEngineModuleBase {

    private static final long EU_PER_THEORETICAL_PARALLEL = 1048576L;
    private static final long ZERO_POINT_RECIPE_DURATION = 200L;
    private static final ResourceLocation PROGRAMMED_CIRCUIT_ID =
            new ResourceLocation("gtceu", "programmed_circuit");

    // 实际并行：1号=2^0=1, 2号=2^1=2, N号=2^(N-1)，16号以上 INT_MAX
    private int circuitNumber;
    private boolean circuitFound;
    private TickableSubscription circuitScanSubs;
    private final NotifiableItemStackHandler circuitSlot;

    // 高危电路（>5号）风险确认：未确认或拒绝时一律阻止配方运行
    private static final byte RISK_UNDECIDED = 0;
    private static final byte RISK_ACCEPTED = 1;
    private static final byte RISK_DECLINED = 2;
    private static final String KEY_RISK_DECISION = "sh_risk_decision";
    private byte riskDecision = RISK_UNDECIDED;

    public PrimordialOmegaVoidInductionArmature(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        if (getUuid() == null) {
            setUuid(UUID.randomUUID());
        }
        circuitSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH);
        circuitSlot.setFilter(stack -> stack == null || stack.isEmpty()
                || isProgrammedCircuit(stack));
    }

    @Override
    public PrimordialOmegaVoidInductionArmatureLogic createRecipeLogic(Object... args) {
        return new PrimordialOmegaVoidInductionArmatureLogic(this);
    }

    @Override
    public PrimordialOmegaVoidInductionArmatureLogic getRecipeLogic() {
        return (PrimordialOmegaVoidInductionArmatureLogic) recipeLogic;
    }

    @Override
    public long getMaxVoltage() {
        return Long.MAX_VALUE;  // 解除 ULV 配方限制
    }

    @Override
    protected boolean showsModuleAndThreadSlots() {
        // 零点能并行完全由编程电路（circuitSlot）决定，不读物质模块/线程倍率槽，
        // 显示这两个槽位只会误导玩家以为放东西能提升产电。
        return false;
    }

    private void scanCircuits() {
        var directCircuit = circuitSlot.storage.getStackInSlot(0);
        if (isProgrammedCircuit(directCircuit)) {
            int directNumber = IntCircuitBehaviour.getCircuitConfiguration(directCircuit);
            if (directNumber > 0) {
                circuitNumber = directNumber;
                circuitFound = true;
                return;
            }
        }
        for (int i = 20; i >= 1; i--) {
            try {
                if (MachineIO.notConsumableCircuit(this, i)) {
                    circuitNumber = i;
                    circuitFound = true;
                    return;
                }
            } catch (ClassCastException e) {
                // gtlcore 1.2.2.9+ 改 API 导致样板总成 handler cast 失败，跳过本次扫描
                circuitNumber = 0;
                circuitFound = false;
                return;
            }
        }
        circuitNumber = 0;
        circuitFound = false;
    }

    private static boolean isProgrammedCircuit(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = ForgeRegistries.ITEMS.getValue(PROGRAMMED_CIRCUIT_ID);
        return item != null && item != Items.AIR && stack.is(item);
    }

    @Override
    protected NotifiableItemStackHandler[] getPersistedStorages() {
        return new NotifiableItemStackHandler[]{ circuitSlot };
    }

    @Override
    public void saveCustomPersistedData(net.minecraft.nbt.CompoundTag tag, boolean forSyncing) {
        super.saveCustomPersistedData(tag, forSyncing);
        tag.putByte(KEY_RISK_DECISION, riskDecision);
    }

    @Override
    public void loadCustomPersistedData(net.minecraft.nbt.CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains(KEY_RISK_DECISION)) {
            riskDecision = tag.getByte(KEY_RISK_DECISION);
        }
    }

    private void acceptRisk() {
        if (isRemote()) return;
        riskDecision = RISK_ACCEPTED;
        markDirty();
    }

    private void declineRisk() {
        if (isRemote()) return;
        riskDecision = RISK_DECLINED;
        markDirty();
    }

    @Override
    public Widget createUIWidget() {
        Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup group) {
            var size = group.getSize();
            var slot = new SlotWidget(circuitSlot.storage, 0, size.width - 52, size.height - 30, true, true);
            slot.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
            slot.setHoverTooltips(
                    Component.literal("§e§l编程电路槽"),
                    Component.literal("§7放入 GT 编程电路决定真空零点能并行"),
                    Component.literal("§7优先级高于结构仓室内的电路"));
            group.addWidget(slot);

            var yesBtn = new ButtonWidget(size.width - 52, size.height - 50, 24, 16,
                    new TextTexture("YES", -1), clickData -> acceptRisk());
            yesBtn.setHoverTooltips(
                    Component.literal("§a§lYES · 承受风险"),
                    Component.literal("§7仅在电路号 > 5 时生效"),
                    Component.literal("§7点击后允许机器继续以当前电路号产电"),
                    Component.literal("§7发电量随电路号指数级暴涨，具体位数见下方警告文本"));
            group.addWidget(yesBtn);

            var noBtn = new ButtonWidget(size.width - 26, size.height - 50, 24, 16,
                    new TextTexture("NO", -1), clickData -> declineRisk());
            noBtn.setHoverTooltips(
                    Component.literal("§c§lNO · 拒绝风险"),
                    Component.literal("§7点击后机器拒绝执行任何配方"),
                    Component.literal("§7直到重新点击 YES 才会恢复运行"));
            group.addWidget(noBtn);
        }
        return widget;
    }

    private void scanCircuitsSafe() {
        try { scanCircuits(); } catch (Exception ignored) {}
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        refreshTier();  // 重新计算电压等级，避免 ULV
        scanCircuitsSafe();
        circuitScanSubs = subscribeServerTick(circuitScanSubs, () -> {
            try {
                if (getOffsetTimer() % 3 == 0) scanCircuits();
            } catch (Exception ignored) {}
        });
    }

    @Override
    public int getTier() {
        return 9;  // GTValues.MAX ≈ UHV+，解除配方等级限制
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        if (circuitScanSubs != null) {
            circuitScanSubs.unsubscribe();
            circuitScanSubs = null;
        }
    }

    @Override
    public boolean onWorking() {
        boolean working = super.onWorking();
        if (!working || !circuitFound) return working;
        var host = getHost();
        UUID targetUuid = host != null && host.getUuid() != null ? host.getUuid() : getUuid();
        if (targetUuid == null) return working;

        try {
            BigInteger eu = getGeneratedEuPerTick();
            Class<?> mgr = Class.forName("com.hepdd.gtmthings.api.misc.WirelessEnergyManager");
            mgr.getMethod("addEUToGlobalEnergyMap", UUID.class, BigInteger.class, MetaMachine.class)
                .invoke(null, targetUuid, eu, this);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return working;
    }

    /** 当前电路号（由 StartupUpdate 从编程电路自动读取） */
    public int getCircuitNumber() {
        return circuitNumber;
    }

    public boolean hasTargetWirelessGrid() {
        var host = getHost();
        return (host != null && host.getUuid() != null) || getUuid() != null;
    }

    public boolean canStartGeneratingRecipe() {
        // 编程电路决定并行；无电路时，暗能量倍增器可作为 1 号电路兜底
        boolean circuitOrFallback = circuitFound || hasDarkEnergyMultiplierMounted();
        // 高于 5 号电路必须先在 UI 点击 YES 接受风险；未确认或已拒绝一律阻止运行
        if (circuitNumber > 5 && riskDecision != RISK_ACCEPTED) {
            return false;
        }
        return isFormed() && hasProxies() && canModuleWork() && circuitOrFallback;
    }

    /** 理论并行值（BigInteger），仅用于显示 */
    public BigInteger getTheoreticalParallel() {
        if (circuitNumber <= 0) return BigInteger.ZERO;
        int exponent = 128 * (1 << (circuitNumber - 1));
        return BigInteger.ONE.shiftLeft(exponent);
    }

    private static final java.math.BigDecimal LOG10_2 =
            new java.math.BigDecimal("0.30102999566398119521373889472449302676818988146210854131042746");

    /** 精确计算 2^exponent 的十进制位数，不生成巨大字符串（高电路号下 toString 会直接卡死）。 */
    private static long decimalDigitsOfPowerOfTwo(int exponent) {
        if (exponent <= 0) return 1L;
        return new java.math.BigDecimal(exponent).multiply(LOG10_2).toBigInteger().longValueExact() + 1L;
    }

    /** 当前电路号对应发电量的真实十进制位数（>5 号警告用，此前固定写"几万亿位"是夸大的错误文案，
     *  实测 20 号电路（扫描上限）也只有约 3.2 亿位，远达不到万亿量级）。 */
    private long getCurrentCircuitDigitCount() {
        return decimalDigitsOfPowerOfTwo(128 * (1 << (circuitNumber - 1)));
    }

    public BigInteger getGeneratedEuPerTick() {
        return getTheoreticalParallel().multiply(BigInteger.valueOf(EU_PER_THEORETICAL_PARALLEL));
    }

    public BigInteger getGeneratedEuPerCycle() {
        return getGeneratedEuPerTick().multiply(BigInteger.valueOf(ZERO_POINT_RECIPE_DURATION));
    }

    @Override
    public int getMaxParallel() {
        if (!circuitFound) {
            // 无编程电路：暗能量倍增器兜底 → 按 1 号电路计算
            return hasDarkEnergyMultiplierMounted() ? 1 : 0;
        }
        return getCircuitParallel();
    }

    @Override
    public long getCurrentParallel() { return getMaxParallel(); }

    private int getCircuitParallel() {
        if (circuitNumber <= 0) return 0;
        if (circuitNumber >= 16) return Integer.MAX_VALUE;
        return 1 << (circuitNumber - 1);
    }

    @Override
    protected void addParallelDisplay(List<Component> textList) {
        int parallel = getMaxParallel();
        boolean infinite = parallel >= Integer.MAX_VALUE;
        textList.add(Component.literal("")
                .append(Component.literal("同时处理至多"))
                .append(infinite
                        ? com.dishanhai.gt_shanhai.api.DShanhaiTextUtil.createUltimateRainbow("无限")
                        : Component.literal(String.format("%,d", parallel)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("个配方")));
        int threads = getAdditionalThread();
        if (threads > 0) {
            textList.add(Component.translatable("gtladditions.multiblock.threads",
                    Component.literal(String.format("%,d", threads)).withStyle(ChatFormatting.LIGHT_PURPLE)));
        }
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            addHostStatusDisplay(textList);
            if (!canModuleWork()) {
                textList.add(Component.translatable("gt_shanhai.machine.primordial_void_induction_armature.name")
                        .withStyle(ChatFormatting.GOLD));
                return;
            }
            if (!circuitFound) {
                if (hasDarkEnergyMultiplierMounted()) {
                    textList.add(Component.literal("§e暗能量倍增器兜底模式（等效 1 号电路）"));
                } else {
                    textList.add(Component.literal("§c需要插入编程电路或暗能量倍增器以产电"));
                }
            } else {
                textList.add(Component.translatable("gt_shanhai.machine.primordial_void_induction_armature.mode")
                        .withStyle(ChatFormatting.GREEN));
                // 高于 5 号电路警告 + 风险确认状态
                if (circuitNumber > 5) {
                    String digitsText = String.format("%,d", getCurrentCircuitDigitCount());
                    if (riskDecision == RISK_ACCEPTED) {
                        textList.add(Component.literal("§c⚠ 警告：" + circuitNumber + " 号电路发电量约为 " + digitsText + " 位十进制整数 §a(已接受风险)")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                    } else if (riskDecision == RISK_DECLINED) {
                        textList.add(Component.literal("§c✗ 已拒绝风险，配方执行已阻止 · 点击右下角 YES 恢复运行")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                    } else {
                        textList.add(Component.literal("§c⚠ 警告：" + circuitNumber + " 号电路发电量约为 " + digitsText + " 位十进制整数")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                        textList.add(Component.literal("§c请在右下角点击 YES/NO 确认是否继续 · 未选择前拒绝运行")
                                .withStyle(ChatFormatting.RED));
                    }
                }
            }
            addEnergyDisplay(textList);
            addParallelDisplay(textList);
            addWorkingStatus(textList);
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.translatable("gt_shanhai.machine.primordial_void_induction_armature.name")
                .withStyle(ChatFormatting.GOLD));
    }
}

