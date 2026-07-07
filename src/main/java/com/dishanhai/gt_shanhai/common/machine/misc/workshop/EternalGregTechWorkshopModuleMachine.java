package com.dishanhai.gt_shanhai.common.machine.misc.workshop;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.common.machine.misc.EternalGregTechWorkshopMachine;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.gtlcore.gtlcore.api.machine.trait.ILockRecipe;

import java.util.List;
import java.util.UUID;

/** 永恒格雷工坊模块基类。当前承载连接状态，后续接入原版模块结构与增益计算。 */
public abstract class EternalGregTechWorkshopModuleMachine
        extends GTLAddWirelessWorkableElectricMultipleRecipesMachine {

    private static final String KEY_ROOT = "sh_eternal_workshop_module";
    private static final String KEY_VERSION = "version";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_HOST_X = "hostX";
    private static final String KEY_HOST_Y = "hostY";
    private static final String KEY_HOST_Z = "hostZ";
    private static final String KEY_HOST_DIMENSION = "hostDimension";
    private static final String KEY_OWNER = "owner";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_MAX_USE_EUT = "maxUseEUt";
    private static final String KEY_EUT_DISCOUNT = "eutDiscount";
    private static final String KEY_DURATION_MODIFIER = "durationModifier";
    private static final String KEY_MAX_PARALLEL = "maxParallel";
    private static final String KEY_HEAT = "heat";
    private static final int MAX_SAFE_PARALLEL = Integer.MAX_VALUE - 1;

    private boolean connected;
    private BlockPos hostPosition;
    private ResourceLocation hostDimension;
    private UUID ownerUUID;
    private int moduleLevel = 1;
    private long maxUseEUt = Long.MAX_VALUE;
    private double eutDiscount = 1.0D;
    private double durationModifier = 1.0D;
    private int maxParallel = 1;
    private int heat;
    private TickableSubscription moduleTickSubscription;
    private String lastWorkshopRecipeStatus = "尚未搜索配方";
    private String lastWorkshopRecipeId = "";
    private String lastWorkshopRecipeType = "";

    protected EternalGregTechWorkshopModuleMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    public abstract EternalGregTechWorkshopModuleType getWorkshopModuleType();

    public abstract Component getWorkshopModuleName();

    @Override
    public RecipeLogic createRecipeLogic(Object... args) {
        return new EternalGregTechWorkshopModuleRecipeLogic(this);
    }

    @Override
    public int getMaxParallel() {
        return Math.max(1, getEffectiveMaxParallel());
    }

    @Override
    public GTRecipeType[] getRecipeTypes() {
        return getAllWorkshopRecipeTypes();
    }

    @Override
    public int getActiveRecipeType() {
        return sanitizeRecipeTypeIndex(super.getActiveRecipeType());
    }

    @Override
    public long getMaxVoltage() {
        if (!isFormed() || !isConnectedToWorkshopHost()) {
            return 0L;
        }
        return Math.max(0L, getEffectiveMaxUseEUt());
    }

    @Override
    public void setActiveRecipeType(int activeRecipeType) {
        int sanitized = sanitizeRecipeTypeIndex(activeRecipeType);
        int previous = getActiveRecipeType();
        super.setActiveRecipeType(sanitized);
        if (sanitized != previous) {
            RecipeLogic logic = getRecipeLogic();
            if (logic != null) {
                if (logic instanceof ILockRecipe lockRecipe) {
                    lockRecipe.setLockRecipe(null);
                }
                logic.resetRecipeLogic();
            }
        }
        markDirty();
        if (getLevel() != null && !getLevel().isClientSide) {
            notifyBlockUpdate();
        }
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        if (moduleLevel <= 0) {
            moduleLevel = 1;
        }
        updateModuleTickSubscription();
        pushStateToHost();
        markDirty();
    }

    @Override
    public void onStructureInvalid() {
        EternalGregTechWorkshopMachine host = getConnectedHost();
        if (host != null) {
            host.clearModuleState(getWorkshopModuleType());
        }
        if (moduleTickSubscription != null) {
            moduleTickSubscription.unsubscribe();
            moduleTickSubscription = null;
        }
        resetWorkshopRecipeLogic();
        super.onStructureInvalid();
        markDirty();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (getUuid() == null) {
            setUuid(UUID.randomUUID());
        }
        if (ownerUUID == null) {
            ownerUUID = getUuid();
        }
    }

    @Override
    public void onUnload() {
        super.onUnload();
        if (moduleTickSubscription != null) {
            moduleTickSubscription.unsubscribe();
            moduleTickSubscription = null;
        }
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        CompoundTag root = new CompoundTag();
        root.putInt(KEY_VERSION, 1);
        root.putBoolean(KEY_CONNECTED, connected);
        if (hostPosition != null) {
            root.putInt(KEY_HOST_X, hostPosition.getX());
            root.putInt(KEY_HOST_Y, hostPosition.getY());
            root.putInt(KEY_HOST_Z, hostPosition.getZ());
        }
        if (hostDimension != null) {
            root.putString(KEY_HOST_DIMENSION, hostDimension.toString());
        }
        if (ownerUUID != null) {
            root.putUUID(KEY_OWNER, ownerUUID);
        }
        root.putInt(KEY_LEVEL, moduleLevel);
        root.putLong(KEY_MAX_USE_EUT, maxUseEUt);
        root.putDouble(KEY_EUT_DISCOUNT, eutDiscount);
        root.putDouble(KEY_DURATION_MODIFIER, durationModifier);
        root.putInt(KEY_MAX_PARALLEL, maxParallel);
        root.putInt(KEY_HEAT, heat);
        tag.put(KEY_ROOT, root);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (!tag.contains(KEY_ROOT)) {
            return;
        }
        CompoundTag root = tag.getCompound(KEY_ROOT);
        connected = root.getBoolean(KEY_CONNECTED);
        if (root.contains(KEY_HOST_X) && root.contains(KEY_HOST_Y) && root.contains(KEY_HOST_Z)) {
            hostPosition = new BlockPos(root.getInt(KEY_HOST_X), root.getInt(KEY_HOST_Y), root.getInt(KEY_HOST_Z));
        }
        if (root.contains(KEY_HOST_DIMENSION)) {
            hostDimension = new ResourceLocation(root.getString(KEY_HOST_DIMENSION));
        }
        if (root.hasUUID(KEY_OWNER)) ownerUUID = root.getUUID(KEY_OWNER);
        if (root.contains(KEY_LEVEL)) moduleLevel = Math.max(1, root.getInt(KEY_LEVEL));
        if (root.contains(KEY_MAX_USE_EUT)) maxUseEUt = Math.max(0L, root.getLong(KEY_MAX_USE_EUT));
        if (root.contains(KEY_EUT_DISCOUNT)) eutDiscount = Math.max(0.0D, root.getDouble(KEY_EUT_DISCOUNT));
        if (root.contains(KEY_DURATION_MODIFIER)) durationModifier = Math.max(0.0D, root.getDouble(KEY_DURATION_MODIFIER));
        if (root.contains(KEY_MAX_PARALLEL)) maxParallel = sanitizeParallel(root.getInt(KEY_MAX_PARALLEL));
        if (root.contains(KEY_HEAT)) heat = Math.max(0, root.getInt(KEY_HEAT));
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (!isFormed()) {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        textList.add(getWorkshopModuleName().copy().withStyle(ChatFormatting.GOLD));
        textList.add(Component.literal("结构已成型"));
        textList.add(Component.literal("模块类型: " + getWorkshopModuleType().id()));
        textList.add(Component.literal("模块等级: " + moduleLevel));
        textList.add(Component.literal("已连接主机: " + (connected && getConnectedHost() != null ? "是" : "否")));
        if (hostPosition != null) {
            textList.add(Component.literal("主机坐标: " + hostPosition.toShortString()));
        }
        textList.add(Component.literal("EU折扣: " + eutDiscount + " / 时长系数: " + durationModifier));
        textList.add(Component.literal("最大并行: " + maxParallel + " / 热量: " + heat));
        textList.add(Component.literal("配方类型: " + getWorkshopActiveRecipeTypeIndex()
                + "/" + getWorkshopRecipeTypeCount()
                + " " + getWorkshopActiveRecipeTypeName()));
        textList.add(Component.literal("配方诊断: " + getLastWorkshopRecipeStatus()));
        if (!lastWorkshopRecipeId.isEmpty()) {
            textList.add(Component.literal("最近配方: " + lastWorkshopRecipeType + " / " + lastWorkshopRecipeId));
        }
        textList.add(Component.literal("实际EU折扣: " + formatMultiplier(getEffectiveEUtDiscount())
                + " / 实际时长: " + formatMultiplier(getEffectiveDurationModifier())));
        textList.add(Component.literal("实际并行: " + formatParallel(getEffectiveMaxParallel())
                + " / 实际最大EU: " + formatLong(getEffectiveMaxUseEUt())));
        textList.add(DShanhaiTextUtil.createAuroraText("已按绑定主机汇总增益修正模块配方"));
    }

    public void connectToHost(EternalGregTechWorkshopMachine host) {
        if (host == null || getLevel() == null || host.getLevel() != getLevel() || !host.isFormed()) {
            disconnectFromHost();
            return;
        }
        if (!host.canAcceptModuleConnection(getWorkshopModuleType(), getWorkshopModuleId())) {
            disconnectFromHost();
            return;
        }
        EternalGregTechWorkshopMachine oldHost = getConnectedHost();
        if (oldHost != null && oldHost != host) {
            oldHost.clearModuleState(getWorkshopModuleType());
        }
        hostPosition = host.getPos();
        hostDimension = getLevel().dimension().location();
        connected = true;
        refreshModuleLevelFromHost(host);
        updateModuleTickSubscription();
        pushStateToHost();
        resetWorkshopRecipeLogic();
        markDirty();
    }

    public void disconnectFromHost() {
        EternalGregTechWorkshopMachine host = getConnectedHost();
        if (host != null) {
            host.clearModuleState(getWorkshopModuleType());
        }
        connected = false;
        hostPosition = null;
        hostDimension = null;
        resetWorkshopRecipeLogic();
        markDirty();
    }

    public EternalGregTechWorkshopModuleState exportModuleState() {
        EternalGregTechWorkshopModuleState state = new EternalGregTechWorkshopModuleState();
        state.setConnected(connected && getConnectedHost() != null);
        state.setFormed(isFormed());
        state.setLevel(moduleLevel);
        state.setOwnerUUID(getWorkshopModuleId());
        state.setMaxUseEUt(maxUseEUt);
        state.setEUtDiscount(eutDiscount);
        state.setDurationModifier(durationModifier);
        state.setMaxParallel(maxParallel);
        state.setHeat(heat);
        state.setWorking(isWorkshopModuleWorking());
        if (getLevel() != null) {
            state.setLastSeenGameTime(getLevel().getGameTime());
        }
        return state;
    }

    public boolean isWorkshopModuleWorking() {
        RecipeLogic logic = getRecipeLogic();
        return logic != null && logic.isWorking();
    }

    public boolean isConnectedToWorkshopHost() {
        return connected && getConnectedHost() != null;
    }

    public int getWorkshopRecipeTypeCount() {
        GTRecipeType[] types = getAllWorkshopRecipeTypes();
        return types == null ? 0 : types.length;
    }

    @Override
    public GTRecipeType getRecipeType() {
        GTRecipeType[] types = getAllWorkshopRecipeTypes();
        if (types == null || types.length == 0) {
            return super.getRecipeType();
        }
        return types[sanitizeRecipeTypeIndex(super.getActiveRecipeType())];
    }

    public int getWorkshopActiveRecipeTypeIndex() {
        int count = getWorkshopRecipeTypeCount();
        if (count <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(count - 1, super.getActiveRecipeType()));
    }

    public String getWorkshopActiveRecipeTypeName() {
        GTRecipeType[] types = getAllWorkshopRecipeTypes();
        if (types == null || types.length == 0) {
            return "none";
        }
        GTRecipeType type = types[getWorkshopActiveRecipeTypeIndex()];
        if (type == null || type.registryName == null) {
            return "unknown";
        }
        return type.registryName.toString();
    }

    public BlockPos getWorkshopHostPosition() {
        return hostPosition;
    }

    public ResourceLocation getWorkshopHostDimension() {
        return hostDimension;
    }

    public int getWorkshopModuleLevel() {
        return moduleLevel;
    }

    public UUID getWorkshopModuleId() {
        if (ownerUUID == null) {
            ownerUUID = getUuid() != null ? getUuid() : UUID.randomUUID();
            markDirty();
        }
        return ownerUUID;
    }

    public double getWorkshopEUtDiscount() {
        return eutDiscount;
    }

    public double getWorkshopDurationModifier() {
        return durationModifier;
    }

    public int getWorkshopHeat() {
        return heat;
    }

    public double getEffectiveEUtDiscount() {
        EternalGregTechWorkshopMachine host = getConnectedHost();
        if (host != null) {
            return host.getEffectiveModuleEUtDiscount();
        }
        return Math.max(0.0D, eutDiscount);
    }

    public double getEffectiveDurationModifier() {
        EternalGregTechWorkshopMachine host = getConnectedHost();
        if (host != null) {
            return host.getEffectiveModuleDurationModifier();
        }
        return Math.max(0.0D, durationModifier);
    }

    public int getEffectiveMaxParallel() {
        EternalGregTechWorkshopMachine host = getConnectedHost();
        if (host != null) {
            return host.getEffectiveModuleMaxParallel();
        }
        return sanitizeParallel(maxParallel);
    }

    public long getEffectiveMaxUseEUt() {
        EternalGregTechWorkshopMachine host = getConnectedHost();
        if (host != null) {
            return host.getEffectiveModuleMaxUseEUt();
        }
        return Math.max(0L, maxUseEUt);
    }

    public int getEffectiveHeat() {
        EternalGregTechWorkshopMachine host = getConnectedHost();
        if (host != null) {
            return host.getEffectiveModuleHeat();
        }
        return Math.max(0, heat);
    }

    boolean canRunWorkshopRecipe(GTRecipe recipe) {
        if (recipe == null) {
            setWorkshopRecipeStatus("未找到匹配配方");
            return false;
        }
        if (!isFormed()) {
            setWorkshopRecipeStatus("结构未成型");
            return false;
        }
        if (!connected) {
            setWorkshopRecipeStatus("未连接工坊主机");
            return false;
        }
        if (!isConnectedToWorkshopHost()) {
            setWorkshopRecipeStatus("主机无效或未成型");
            return false;
        }
        EternalGregTechWorkshopMachine host = getConnectedHost();
        if (host == null) {
            setWorkshopRecipeStatus("主机无效或未成型");
            return false;
        }
        if (!host.isFuelAvailableForModule(this)) {
            setWorkshopRecipeStatus("工坊燃料不足");
            return false;
        }
        return true;
    }

    GTRecipe applyWorkshopRecipeBonuses(GTRecipe recipe) {
        if (recipe == null || !isFormed() || !isConnectedToWorkshopHost()) {
            return null;
        }
        double eutMul = getEffectiveEUtDiscount();
        double durationMul = getEffectiveDurationModifier();
        if (eutMul == 1.0D && durationMul == 1.0D) {
            return recipe;
        }

        GTRecipe copy = recipe.copy();
        if (durationMul != 1.0D) {
            copy.duration = Math.max(1, (int) Math.ceil(copy.duration * Math.max(0.0D, durationMul)));
        }
        if (eutMul != 1.0D) {
            multiplyPositiveEU(copy.inputs, eutMul);
            multiplyPositiveEU(copy.tickInputs, eutMul);
        }
        return copy;
    }

    void reportRecipeStartedToWorkshop(GTRecipe recipe) {
        EternalGregTechWorkshopMachine host = getConnectedHost();
        if (host != null) {
            setWorkshopRecipeMatched(recipe, "配方已启动");
            pushStateToHost();
            host.recordModuleRecipeStarted(recipe, this);
        }
    }

    void setWorkshopRecipeStatus(String status) {
        lastWorkshopRecipeStatus = status == null || status.isBlank() ? "未知状态" : status;
    }

    void setWorkshopRecipeMatched(GTRecipe recipe, String status) {
        setWorkshopRecipeStatus(status);
        if (recipe == null) {
            lastWorkshopRecipeId = "";
            lastWorkshopRecipeType = "";
            return;
        }
        lastWorkshopRecipeId = recipe.getId() == null ? "unknown" : recipe.getId().toString();
        lastWorkshopRecipeType = recipe.recipeType == null || recipe.recipeType.registryName == null
                ? "unknown" : recipe.recipeType.registryName.toString();
    }

    public String getLastWorkshopRecipeStatus() {
        return lastWorkshopRecipeStatus;
    }

    public String getLastWorkshopRecipeId() {
        return lastWorkshopRecipeId;
    }

    public String getLastWorkshopRecipeType() {
        return lastWorkshopRecipeType;
    }

    String getLastWorkshopRecipeStatusRaw() {
        return lastWorkshopRecipeStatus;
    }

    private GTRecipeType[] getAllWorkshopRecipeTypes() {
        return super.getRecipeTypes();
    }

    protected void setModuleDefaults(int level, long maxUseEUt, double eutDiscount,
                                     double durationModifier, int maxParallel, int heat) {
        this.moduleLevel = Math.max(1, level);
        this.maxUseEUt = Math.max(0L, maxUseEUt);
        this.eutDiscount = Math.max(0.0D, eutDiscount);
        this.durationModifier = Math.max(0.0D, durationModifier);
        this.maxParallel = sanitizeParallel(maxParallel);
        this.heat = Math.max(0, heat);
    }

    protected EternalGregTechWorkshopMachine getConnectedHost() {
        Level level = getLevel();
        if (level == null || hostPosition == null) {
            return null;
        }
        if (hostDimension != null && !hostDimension.equals(level.dimension().location())) {
            return null;
        }
        if (!level.hasChunkAt(hostPosition)) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(hostPosition);
        if (!(blockEntity instanceof IMachineBlockEntity machineBlockEntity)) {
            return null;
        }
        MetaMachine machine = machineBlockEntity.getMetaMachine();
        if (machine instanceof EternalGregTechWorkshopMachine workshop && workshop.isFormed()) {
            return workshop;
        }
        return null;
    }

    private void pushStateToHost() {
        EternalGregTechWorkshopMachine host = getConnectedHost();
        if (host != null) {
            refreshModuleLevelFromHost(host);
            host.applyModuleState(getWorkshopModuleType(), exportModuleState());
        }
    }

    private void refreshModuleLevelFromHost(EternalGregTechWorkshopMachine host) {
        if (host != null) {
            moduleLevel = Math.max(1, host.getModuleTier());
        }
    }

    private void updateModuleTickSubscription() {
        if (getLevel() == null || getLevel().isClientSide || !isFormed() || !connected) {
            return;
        }
        if (moduleTickSubscription == null) {
            moduleTickSubscription = subscribeServerTick(this::doWorkshopModuleServerTick);
        }
    }

    private void doWorkshopModuleServerTick() {
        if (!isFormed() || !connected) {
            if (moduleTickSubscription != null) {
                moduleTickSubscription.unsubscribe();
                moduleTickSubscription = null;
            }
            return;
        }
        if (getLevel() != null && getLevel().getGameTime() % 100L == 0L) {
            pushStateToHost();
        }
    }

    private static void multiplyPositiveEU(
            java.util.Map<com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability<?>,
                    java.util.List<com.gregtechceu.gtceu.api.recipe.content.Content>> contentsMap,
            double multiplier) {
        java.util.List<com.gregtechceu.gtceu.api.recipe.content.Content> contents =
                contentsMap.get(com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability.CAP);
        if (contents == null || contents.isEmpty()) {
            return;
        }
        double safeMultiplier = Math.max(0.0D, multiplier);
        for (com.gregtechceu.gtceu.api.recipe.content.Content content : contents) {
            if (content == null || !(content.getContent() instanceof Number number)) {
                continue;
            }
            long eut = number.longValue();
            if (eut <= 0L) {
                continue;
            }
            content.content = multiplyLongCeil(eut, safeMultiplier);
        }
    }

    private static long multiplyLongCeil(long value, double multiplier) {
        double result = value * multiplier;
        if (Double.isNaN(result) || result <= 0.0D) {
            return 1L;
        }
        if (Double.isInfinite(result) || result >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, (long) Math.ceil(result));
    }

    private void resetWorkshopRecipeLogic() {
        RecipeLogic logic = getRecipeLogic();
        if (logic != null) {
            logic.resetRecipeLogic();
        }
    }

    private static int sanitizeParallel(int value) {
        if (value <= 0) {
            return 1;
        }
        if (value >= Integer.MAX_VALUE) {
            return 1;
        }
        return Math.min(value, MAX_SAFE_PARALLEL);
    }

    private int sanitizeRecipeTypeIndex(int index) {
        int count = getWorkshopRecipeTypeCount();
        if (count <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(count - 1, index));
    }

    private static String formatMultiplier(double value) {
        return String.format(java.util.Locale.ROOT, "%.4fx", value);
    }

    private static String formatParallel(int parallel) {
        return parallel >= Integer.MAX_VALUE ? "∞" : String.format(java.util.Locale.ROOT, "%,d", parallel);
    }

    private static String formatLong(long value) {
        return value >= Long.MAX_VALUE ? "∞" : String.format(java.util.Locale.ROOT, "%,d", value);
    }
}
