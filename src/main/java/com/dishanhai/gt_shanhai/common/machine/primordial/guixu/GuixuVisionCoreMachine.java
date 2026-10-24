package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.google.common.primitives.Ints;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedKey;
import com.mojang.logging.LogUtils;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.gtladd.gtladditions.api.machine.gui.LimitedDurationConfigurator;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gtladd.gtladditions.api.recipe.IWirelessGTRecipe;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gtladd.gtladditions.api.machine.trait.IWirelessNetworkEnergyHandler;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineModule;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;

/**
 * 山海：由 Integrate/acb081f 移植的歸墟視界樞核。
 * 沿用 master 的原初主機與多配方引擎，提供 24 個模組位、獨立等級覆寫槽、
 * 門控增產額外 +5、同款能源減免，以及獨立的動態環帶和黑洞渲染。
 * 原始實作：shanhai-rewrite/com/shanhai/machine/host。
 */
public class GuixuVisionCoreMachine extends PrimordialOmegaEngineMachine {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 材料增产的<b>额外</b>倍率：在原始终焉引擎那套（{@code 1 + 物质模块槽等级}）之上再加 5。
     *
     * <p>只加在"门控已生效"（等级 ≥ 1）的情况：门控未生效时倍率仍是 1
     * （空槽主机白得 6 倍产出不是"沿用那套 + 5"）。若以后要改成"永远 +5"，
     * 倍率由 {@link #productionMultiplierOf(int, int)} 統一計算。
     */
    public static final int PRODUCTION_BONUS_EXTRA = 5;

    /**
     * 🔴 <b>本类自己的 {@link ManagedFieldHolder}</b>（2026-10-23 新增字段 ⇒ 必须有它）。
     *
     * <p>LDLib 的 holder 按<b>类</b>反射登记 {@code @Persisted}/{@code @DescSynced} 字段，
     * 父类的 holder 里没有本类的字段；漏掉这个覆写的表现是
     * <b>编译通过、游戏不报错、字段永不落盘也永不同步</b>（玩家在专属槽里放满 64 个模块，
     * 关掉 GUI 再打开就"回到空"）—— 本工程最忌讳的"静默失败"。
     *
     * <p>第二参取<b>父类的 holder</b>（{@code PrimordialOmegaEngineMachine.managedFieldHolder()}，
     * 那是本类新加的只读出口）：<b>不许跳级</b>写 {@code WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER}，
     * 否则父类那一整段的字段（物质模块槽 / 最短耗时 / 球体风格…）会从本类的链上消失。
     */
    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            GuixuVisionCoreMachine.class, PrimordialOmegaEngineMachine.managedFieldHolder());

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    /**
     * 「模块等级复写」专属槽（1 格，只收物质模块）。
     *
     * <p>用户规格（原话）：「<b>物质模块槽下面再加一格，放满 64 个物质模块生效 —— 用它复写安装在身上的
     * 模块的物质模块等级</b>」 ⇒ 门控 {@link #matterLevelOverride()}：
     * <b>必须 64 个同种物质模块</b>才返回 1..17，否则 0（未生效）。
     *
     * <p>与基类的「物质模块专属槽」（管 N3 增产 / N5 减免门控）是<b>两个独立槽</b>：
     * 那个管"主机自己的增产"，这个管"模块的等级复写"，互不影响。
     */
    @Persisted
    private final NotifiableItemStackHandler levelOverrideSlot;

    /**
     * {@link #matterLevelOverride()} 的<b>客户端镜像</b>（渲染/抬头在客户端，槽里的物品到不了那边）。
     *
     * <p>与基类 {@code matterBonusLevel} 同一个套路：值真变了才 {@code notifyBlockUpdate()}，
     * 不无脑同步（那会变成每 tick 一个包）。
     */
    @DescSynced
    private int hostMatterLevelOverride = 0;

    /**
     * <b>服务端认定的"已成型"位</b>（2026-10-24 新增）：{@code @DescSynced} ⇒ 客户端也读得到。
     *
     * <h2>为什么在 {@code controller.isFormed()} 之外还要这一位</h2>
     * 客户端渲染器原本只读 {@code IMultiController#isFormed()}。那一份是 GTCEu 自己的同步位，
     * 理论上够用；但 2026-10-24 实机排查「特效一点没看见」时<b>无法排除</b>"客户端成型状态没跟上"这一档
     * （当时渲染路径还没有日志，见 {@code GuixuHostFxRenderer} 的类注释）。
     * 多一位自己维护、在 {@code onStructureFormed()} / {@code onStructureInvalid()} 里显式写的同步位，
     * 渲染器就能判 {@code isFormed() || formedSync}，并把<b>两个值都</b>打进 {@code draw}/{@code gate} 日志
     * ⇒ 下一轮再有异常，一行日志就能定位是哪一边没跟上。
     *
     * <p>默认 {@code false}；值变化才 {@code notifyBlockUpdate()}（无脑同步 = 每 tick 一个包）。
     */
    @DescSynced
    private boolean hostFormedSync = false;

    @DescSynced
    private int operationStateId;
    @DescSynced
    private int activeModuleCount;
    private GuixuEnergyHandler guixuEnergy;
    private final GuixuVisualState visualState = new GuixuVisualState();

    @Override
    public GuixuEnergyHandler getWirelessNetworkEnergyHandler() {
        if (guixuEnergy == null) guixuEnergy = new GuixuEnergyHandler(this);
        return guixuEnergy;
    }

    public GuixuOperationState getOperationState() { return GuixuOperationState.fromId(operationStateId); }
    public int getActiveModuleCount() { return activeModuleCount; }
    public GuixuVisualState.Profile getVisualProfile(double seconds) {
        return visualState.advance(getOperationState(), seconds);
    }

    /** 山海：原生多選集合在本機限制為一種方向，防止混合發電與耗電批次。 */
    @Override
    public GTRecipeType[] getSelectedRecipeTypes() {
        GTRecipeType[] selected = super.getSelectedRecipeTypes();
        return selected.length <= 1 ? selected : new GTRecipeType[]{selected[0]};
    }

    @Override
    public boolean isRecipeTypeSelected(GTRecipeType type) {
        GTRecipeType[] selected = getSelectedRecipeTypes();
        return selected.length == 1 && selected[0] == type;
    }

    @Override
    public void setRecipeTypeSelected(GTRecipeType type, boolean selected) {
        if (selected) selectOnlyRecipeType(type);
    }

    @Override
    public void selectAllRecipeTypes() { selectFirstRecipeType(); }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!isRemote() && super.getSelectedRecipeTypes().length != 1) {
            GTRecipeType[] selected = getSelectedRecipeTypes();
            GTRecipeType[] available = getAllSelectableRecipeTypes();
            if (selected.length > 0) selectOnlyRecipeType(selected[0]);
            else if (available.length > 0) selectOnlyRecipeType(available[0]);
        }
    }

    @Override
    public void attachSideTabs(TabsWidget tabs) {
        tabs.setMainTab(this);
        tabs.attachSubTab(GuixuControlPanels.modes(this));
        attachCleanSideTabs(tabs);
    }

    @Override
    protected void attachCleanSideTabs(TabsWidget tabs) {
        super.attachCleanSideTabs(tabs);
        tabs.attachSubTab(GuixuControlPanels.modules(this));
    }

    /** 只觀察有效回連且區塊已載入的模組，始終渲染開關不參與工作判定。 */
    public void refreshOperationState() {
        if (isRemote()) return;
        boolean working = isFormed() && getRecipeLogic().isWorking();
        boolean generating = working && isGenerating(getRecipeLogic());
        boolean blocked = isFormed() && isBlocked(getRecipeLogic());
        int active = 0;
        if (isFormed()) {
            for (var candidate : getModuleSet()) {
                if (!(candidate instanceof PrimordialOmegaEngineModuleBase module)
                        || module.getHost() != this || !module.isFormed()
                        || getLevel() == null || !getLevel().hasChunkAt(module.getPos())) continue;
                if (module.getRecipeLogic().isWorking()) {
                    active++;
                    working = true;
                    generating |= isGenerating(module.getRecipeLogic());
                }
                blocked |= isBlocked(module.getRecipeLogic());
            }
        }
        int next = GuixuOperationState.resolve(working, generating, blocked).ordinal();
        if (operationStateId != next || activeModuleCount != active) {
            operationStateId = next;
            activeModuleCount = active;
            notifyBlockUpdate();
        }
    }

    static boolean isBlocked(RecipeLogic logic) {
        if (!logic.isWorkingEnabled()) return false;
        if (logic.isWaiting() || logic.isHasNotEnoughEnergy()) return true;
        if (logic instanceof IRecipeStatus statuses) {
            RecipeResult result = statuses.getWorkingStatus();
            if (result == null || result.isSuccess()) result = statuses.getRecipeStatus();
            return result != null && !result.isSuccess() && !result.equals(RecipeResult.FAIL_FIND);
        }
        return false;
    }

    static boolean isGenerating(RecipeLogic logic) {
        var recipe = logic.getLastRecipe();
        if (recipe instanceof IWirelessGTRecipe wireless) {
            BigInteger amount = wireless.getWirelessEuTickInputs();
            return GuixuEnergyTransfer.generatesEnergy(amount);
        }
        return recipe != null && RecipeHelper.getOutputEUt(recipe) > 0L;
    }

    /** 去重後的原生能量能力；不把輸入和輸出存量混合。 */
    public List<IEnergyContainer> getGuixuEnergyContainers(IO io) {
        List<IEnergyContainer> result = new ArrayList<>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<IEnergyContainer, Boolean>());
        List<IRecipeHandler<?>> handlers = getCapabilitiesProxy().get(io, EURecipeCapability.CAP);
        if (handlers != null) {
            for (IRecipeHandler<?> handler : handlers) {
                if (handler instanceof IEnergyContainer energy && seen.add(energy)) result.add(energy);
            }
        }
        return result;
    }

    /** 给客户端渲染器读的"服务端成型位"（{@code @DescSynced} 镜像）。 */
    public boolean isHostFormedSync() {
        return hostFormedSync;
    }

    /** 只在值真的变化时写 + 推同步。 */
    private void setHostFormedSync(boolean value) {
        if (hostFormedSync == value) {
            return;
        }
        hostFormedSync = value;
        if (!isRemote()) {
            notifyBlockUpdate();
        }
    }

    public GuixuVisionCoreMachine(IMachineBlockEntity holder, Object... args) {
        super(holder);
        // 覆写 onContentsChanged ⇒ "槽一变就重算并推同步"，不等任何 tick 订阅。
        // 客户端也会收到这个回调（GUI 槽的本地交互），所以必须挡住 isRemote() 那一侧。
        this.levelOverrideSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.NONE) {
            @Override
            public void onContentsChanged() {
                super.onContentsChanged();
                if (!isRemote()) {
                    refreshMatterLevelOverride();
                }
            }
        }.setFilter(PrimordialOmegaEngineModuleBase::isMatterModuleStack);
    }

    // ------------------------------------------------------------------ 模块等级复写（门控 + 出口）

    /**
     * 门控：槽里<b>恰好 64 个同种物质模块</b> ⇒ 该模块的等级（1..17）；否则 0（未生效）。
     *
     * <p>槽只有 1 格、一个 {@code ItemStack} 按定义就是一种物品，且过滤器只放物质模块
     * ⇒ "单一物品类型"是<b>结构性保证</b>，这里只需判数量（与基类那个门控同一口径）。
     */
    public int matterLevelOverride() {
        if (levelOverrideSlot == null) {
            return 0;
        }
        final ItemStack stack = levelOverrideSlot.storage.getStackInSlot(0);
        return stack.getCount() == 64 ? PrimordialOmegaEngineModuleBase.getModuleLevelByStack(stack) : 0;
    }

    /** 重算门控并（仅在值变化时）推一次同步。 */
    private void refreshMatterLevelOverride() {
        final int now = matterLevelOverride();
        if (now == hostMatterLevelOverride) {
            return;
        }
        hostMatterLevelOverride = now;
        notifyBlockUpdate();
    }

    /**
     * <b>加载期自检</b>：本类的字段 holder 必须同时登记了本类两个新字段与父类的关键字段。
     *
     * <h2>为什么值得为它写一段代码</h2>
     * LDLib 的字段登记失败是<b>完全静默</b>的（编译通过、游戏不报错、字段永不落盘也永不同步）；
     * 唯一的现场症状是"玩家放满 64 个模块，关掉 GUI 再打开就回到空"。把这件事变成
     * <b>注册期抛异常 + 一行可 grep 的日志</b>，是唯一能在没有玩家操作的情况下抓住它的办法
     * （与 {@link GuixuHostGeometry#verify()} 同一条纪律）。
     *
     * @throws IllegalStateException 缺字段 / 注解没生效（说明 holder 的父链写错了，通常是"跳级"）
     */
    public static void assertFieldHolderComplete() {
        final java.util.Set<String> names = new java.util.HashSet<>();
        boolean overrideSlotPersisted = false;
        boolean overrideLevelSynced = false;
        for (final ManagedKey key : MANAGED_FIELD_HOLDER.getFields()) {
            names.add(key.getName());
            if ("levelOverrideSlot".equals(key.getName())) {
                overrideSlotPersisted = key.isPersist();
            }
            if ("hostMatterLevelOverride".equals(key.getName())) {
                overrideLevelSynced = key.isDestSync();
            }
        }
        final java.util.List<String> problems = new java.util.ArrayList<>();
        for (final String required : new String[]{"levelOverrideSlot", "hostMatterLevelOverride",
                "hostFormedSync", "matterBonusSlot", "limitedDuration", "matterBonusLevel",
                "operationStateId", "activeModuleCount"}) {
            if (!names.contains(required)) {
                problems.add("缺字段 " + required);
            }
        }
        if (!overrideSlotPersisted) {
            problems.add("levelOverrideSlot 没有落盘（@Persisted 未登记）");
        }
        if (!overrideLevelSynced) {
            problems.add("hostMatterLevelOverride 没有同步（@DescSynced 未登记）");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("[SHANHAI-HOST] 字段 holder 自检失败：" + problems
                    + " —— 这会导致「编译通过、游戏不报错、字段永不落盘也永不同步」。"
                    + "最常见原因是 MANAGED_FIELD_HOLDER 的父 holder 写成了跳级"
                    + "（必须 PrimordialOmegaEngineMachine.managedFieldHolder()，不能直接用"
                    + " WorkableElectricMultiblockMachine.MANAGED_FIELD_HOLDER）。实际登记的字段 = " + names);
        }
        LOGGER.info("[SHANHAI-HOST] 字段 holder 自检通过：{} 个字段（含本类 levelOverrideSlot/hostMatterLevelOverride"
                + " 与父类 matterBonusSlot/limitedDuration/matterBonusLevel）", names.size());
    }

    /**
     * 覆写基类的「模块等级复写」出口：模块侧 {@code getHostMatterLevelOverride()} 读的就是这个值。
     *
     * <p>返回 0 = 没有复写 ⇒ 模块侧 {@code max(自己, 0) == 自己} ⇒ 与今天逐位一致。
     * 服务端每次读都按槽内<b>实况</b>重算（不存在"要等某个 tick 才生效"的延迟）；
     * 客户端读 {@code @DescSynced} 镜像。
     */
    @Override
    public int getHostMatterLevelOverride() {
        if (!isRemote()) {
            refreshMatterLevelOverride();
            return hostMatterLevelOverride;
        }
        return hostMatterLevelOverride;
    }

    /**
     * 把「模块等级复写」槽画进 GUI —— 位置在基类那个物质模块槽的<b>正下方</b>。
     *
     * <p>基类 {@link PrimordialOmegaEngineMachine#createUIWidget()} 把物质模块槽放在
     * {@code (width-30, height-68)}（= (160, 57)）；本槽放在 {@code (width-30, height-48)}（= (160, 77)），
     * 仍在滚动区 {@code (4,4,182,117)} 之内、不越界。
     *
     * <p>🔴 少了这个覆写，玩家在界面上<b>找不到</b>这个槽 ⇒ 复写永远是 0，
     * <b>而且不报任何错</b>（与基类那个槽同一个坑，见基类方法的 javadoc）。
     */
    @Override
    public Widget createUIWidget() {
        final Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup group) {
            final var size = group.getSize();
            final SlotWidget overrideSlot = new SlotWidget(levelOverrideSlot.storage, 0,
                    size.width - 30, size.height - 48, true, true);
            overrideSlot.setBackground(SlotWidget.ITEM_SLOT_TEXTURE);
            overrideSlot.setHoverTooltips(
                    Component.literal("§5§l模块等级复写槽"),
                    Component.literal("§7只收「山海的神人私货」的 17 种物质模块"),
                    Component.literal("§7必须放满 §b64§7 个§b同一种§7模块才生效"),
                    Component.literal("§7生效后用它的等级复写本机 24 个模块的"),
                    Component.literal("§7物质模块等级（§f并行§7 ＋ §f配方等级需求§7）"),
                    Component.literal("§8模块自己装的等级更高时，以模块自己的为准"));
            group.addWidget(overrideSlot);
        }
        return widget;
    }

    @Override
    protected void syncRingVisibility(boolean hide) {
        // 本機由 HostFxHide 台帳維護；不能發送舊主機的藏環封包。
    }

    @Override
    public void onMachineRemoved() {
        super.onMachineRemoved();
        clearInventory(levelOverrideSlot.storage);
    }

    @Override
    public com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineRecipeLogic createRecipeLogic(Object... args) {
        return new GuixuVisionCoreRecipeLogic(this);
    }

    @Override
    public int getRecipeOutputMultiplier() {
        return Ints.saturatedCast((long) getMountedOutputMultiplier() * productionMultiplier());
    }

    public int getFormedModuleCount() {
        return (int) getModuleSet().stream().filter(module -> module.isFormed() && module.getHost() == this).count();
    }

    @Override
    protected void attachCleanConfigurators(ConfiguratorPanel panel) {
        super.attachCleanConfigurators(panel);
        panel.attachConfigurators(new LimitedDurationConfigurator(this));
    }

    // ------------------------------------------------------------------ 24 个模块槽位

    /** 24 个槽位的世界坐标；每次实时算（换朝向/换位置都跟着变，不做跨朝向缓存）。 */
    @Override
    public BlockPos[] getModuleScanPositions() {
        return GuixuHostGeometry.slotPositions(getPos(), getFrontFacing());
    }

    /** 本机 24 个模块位（基类常量 {@code MAX_MODULE_COUNT = 16} 是原始终焉引擎的）。 */
    @Override
    public int getMaxModuleCount() {
        return GuixuHostGeometry.SLOT_COUNT;
    }

    // ------------------------------------------------------------------ 成型日志

    /**
     * 成型：先让基类做一次调和式重扫（断失效、补新增），再打<b>一行</b>可 grep 的日志。
     *
     * <p>日志格式（一行，前缀 {@code [SHANHAI-HOST]}）：
     * <pre>
     * [SHANHAI-HOST] 归墟视界枢核 成型 @(x,y,z) 朝F | 槽位 24 | 回连 N/24 | #0[外环E0](x,y,z)=&lt;方块/机器id&gt;[已连] | …
     * </pre>
     * 槽位号与 {@link GuixuHostGeometry#slots()} 的下标一致（0..15 外环、16..19 上柱、20..23 下柱）。
     */
    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        // 成型位（@DescSynced）：客户端渲染器的第二道成型判据，见 hostFormedSync 的 javadoc
        setHostFormedSync(true);
        // 成型时按槽内实况重算一次等级复写（存档读回后 @DescSynced 的值可能还没跟上）
        if (!isRemote()) {
            refreshMatterLevelOverride();
        }
        logModuleSlots();
    }

    /** 结构失效：把成型位翻回 false 并推同步（渲染器据此停特效并恢复真方块）。 */
    @Override
    public void onStructureInvalid() {
        setHostFormedSync(false);
        operationStateId = GuixuOperationState.IDLE.ordinal();
        activeModuleCount = 0;
        super.onStructureInvalid();
    }

    /** 打那一行槽位/回连日志（服务端跑；客户端没有世界时直接跳过）。 */
    private void logModuleSlots() {
        final Level level = getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        final BlockPos controller = getPos();
        final Direction facing = getFrontFacing();
        final BlockPos[] positions = getModuleScanPositions();
        final StringBuilder detail = new StringBuilder(2048);
        int connected = 0;
        for (int i = 0; i < positions.length; i++) {
            final BlockPos pos = positions[i];
            final MetaMachine machine = MetaMachine.getMachine(level, pos);
            String id = "-";
            if (machine instanceof MultiblockControllerMachine controllerMachine) {
                id = String.valueOf(controllerMachine.getDefinition().getId());
            } else if (machine != null) {
                id = machine.getClass().getSimpleName();
            }
            boolean linked = false;
            if (machine instanceof IModularMachineModule<?, ?> module) {
                linked = module.isFormed() && module.getHost() == this;
            }
            if (linked) {
                connected++;
            }
            detail.append(" #").append(i).append('[').append(slotLabel(i)).append("](")
                    .append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append(")=")
                    .append(id).append(linked ? "[已连]" : "");
        }
        LOGGER.info("[SHANHAI-HOST] 归墟视界枢核 成型 @({},{},{}) 朝{} | 槽位 {} | 回连 {}/{} |{}",
                controller.getX(), controller.getY(), controller.getZ(), facing,
                positions.length, connected, positions.length, detail);
    }

    /** 槽位短标签：外环 E0..N3 / 上柱 UE..UN / 下柱 LE..LN（只用于日志可读性）。 */
    static String slotLabel(int index) {
        final List<GuixuHostGeometry.Slot> slots = GuixuHostGeometry.slots();
        if (index < 0 || index >= slots.size()) {
            return "?";
        }
        final GuixuHostGeometry.Slot slot = slots.get(index);
        final String side = switch (slot.side()) {
            case EAST -> "E";
            case WEST -> "W";
            case SOUTH -> "S";
            case NORTH -> "N";
            default -> "?";
        };
        return switch (slot.kind()) {
            case OUTER_RING -> "外环" + side + slot.index();
            case UPPER_PILLAR -> "上柱" + side;
            case LOWER_PILLAR -> "下柱" + side;
        };
    }

    // ------------------------------------------------------------------ 材料增产（+5）与耗能减免（同款）

    // ------ 两个宿主钩子（引擎路径读它们；见 PrimordialEngineRecipeLogic#buildFinalNormalRecipe）------

    /**
     * <b>材料增产的额外倍率</b>（第 3 项：用户原话「沿用原始终焉引擎那套…并且把增产倍率额外 +5」）。
     *
     * <p>🔴 <b>只在门控生效时非零</b>：{@code moduleSlotBonus() > 0}（= 主机那个物质模块专属槽放满 64 个）
     * 才返回 {@link #PRODUCTION_BONUS_EXTRA}；空槽返回 0 ⇒ 倍率仍是 {@code outputMultiplier(0) == 1}
     * （空槽白得 ×6 产出不是"沿用那套 + 5"）。
     *
     * <p>引擎路径与原生修饰链读的是<b>同一个方法</b> ⇒ 两条路的倍率不可能漂移。
     */
    public int getHostProductionBonusExtra() {
        return moduleSlotBonus() > 0 ? PRODUCTION_BONUS_EXTRA : 0;
    }

    /**
     * <b>N5 耗电成本系数</b>（第 4 项：用户原话「耗能减免：同款」）。
     *
     * <p>与基类默认实现<b>逐位相同</b>（直接返回 {@code reductionFactor(gateBonus)}）——
     * 覆写它的意义是让"减免"也走本机这一个出口：将来若要给归墟换减免公式，只改这里一处，
     * 不必再回头拆引擎尾链。
     */
    public double getHostEnergyReductionFactor(int gateBonus) {
        return GuixuRecipeMath.reductionFactor(gateBonus);
    }

    /**
     * 门控等级 + 额外倍率 ⇒ 实际倍率。
     *
     * <p>{@code outputMultiplier(gate)}：门控未生效（0）⇒ 1；生效 ⇒ {@code 1+等级}。
     * 额外量只加在"已生效"上：{@code extra <= 0} 或门控为 0 时结果就是 {@code outputMultiplier} 本身
     * ⇒ 老主机（extra 恒 0）与本机空槽（extra 恒 0）都退化成今天的行为。
     */
    public static int productionMultiplierOf(int gateBonus, int extra) {
        final int base = GuixuRecipeMath.outputMultiplier(gateBonus);
        return base > 1 ? base + Math.max(0, extra) : base;
    }

    /** 本机当前的实际倍率（显示用）。 */
    public int productionMultiplier() {
        return productionMultiplierOf(moduleSlotBonus(), getHostProductionBonusExtra());
    }

    // ------------------------------------------------------------------ 始终渲染为工作状态

    /**
     * 覆写「是否处于工作状态」：把基类侧栏那个开关（{@code isStarAlwaysWorking()}）接到本机的
     * 机壳/贴图活性上。
     *
     * <p>为什么只改这一处：GTCEu 渲染链里"是否工作状态"的读点就是
     * {@code IRecipeLogicMachine#isActive()}（<b>default 方法</b>，本类可覆写，javap 已核）。
     *
     * <p>🔴 为什么写 {@code getRecipeLogic().isActive()} 而不是 {@code super.isActive()}：
     * 接口 default 的字节码就是 {@code getRecipeLogic().isActive()}（javap 实证，见报告），
     * 两者语义逐位相同；但 {@code super.isActive()} 要求 JVM 在"类链"上解析到一个其实是
     * <b>接口 default</b> 的方法（JVMS 5.4.3.3 的 maximally-specific 回落），多一层解析依赖。
     * 本工程里<b>没有任何类</b>覆写过 {@code isActive()}（grep 全量核过）⇒ 显式调用与 super 等价，
     * 且开关关着时行为与加这个覆写之前<b>逐位相同</b>（默认 false）。
     */
    @Override
    public boolean isActive() {
        return isStarAlwaysWorking() || getRecipeLogic().isActive();
    }

    // ------------------------------------------------------------------ 能量双仓的存量（Jade 两行）

    /**
     * 某一侧的 {@code (存量, 容量)}。
     *
     * <p>{@code io = IO.IN} ⇒ <b>能源仓</b>（{@code PartAbility.INPUT_ENERGY} 贡献的 EU 输入仓）；
     * {@code io = IO.OUT} ⇒ <b>动力仓</b>（{@code PartAbility.OUTPUT_ENERGY} 贡献的 EU 输出仓）。
     * 两个集合都直接从 {@code getCapabilitiesProxy()} 取 —— 与 GTCEu 真正装配配方所用的
     * 是同一份集合（不是另算一遍），读法与 {@code EnergyHatchPower#containersOf} 同源。
     *
     * <p>纯读：任何异常都退回 {@code (0,0)}，绝不打断抬头渲染。
     */
    private BigInteger[] energyTotals(IO io) {
        BigInteger stored = BigInteger.ZERO;
        BigInteger capacity = BigInteger.ZERO;
        try {
            for (IEnergyContainer container : getGuixuEnergyContainers(io)) {
                stored = stored.add(BigInteger.valueOf(container.getEnergyStored()));
                capacity = capacity.add(BigInteger.valueOf(container.getEnergyCapacity()));
            }
        } catch (Throwable ignored) {
            // 纯读：显示用，异常 ⇒ 当作"没有仓"
        }
        return new BigInteger[]{stored, capacity};
    }

    // ------------------------------------------------------------------ 显示文案（别放假数据）

    /**
     * 覆写显示文案（Jade / GUI 抬头）。
     *
     * <p>🔴 <b>不调 {@code super}</b>：基类那套写的是「[原始终焉引擎]」「16 个模块位」
     * 「kubejs:steam_assembly_block 占位」，对本机全是假数据；而 GTCEu 默认那条能量行
     * 也被这条覆写一并挡掉 —— <b>于是两边存量只在下面这两行里出现，不会重复</b>。
     *
     * <p>能量双仓两条的读法：能源仓 = {@code IO.IN} 侧全部 {@link IEnergyContainer} 的存量/容量之和；
     * 动力仓 = {@code IO.OUT} 侧同理。两侧都没装仓时显示 {@code 0 / 0}（不是假数据，是"真的没有"）。
     */
    @Override
    public void addDisplayText(List<Component> textList) {
        if (!isFormed()) {
            textList.add(Component.literal("[归墟视界枢核] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("结构未成型").withStyle(ChatFormatting.RED)));
            textList.add(Component.literal("24 个模块位：空槽可以留空（插/拆模块不会让本机失效）")
                    .withStyle(ChatFormatting.GRAY));
            textList.add(Component.literal("已连接模块：0 / " + getMaxModuleCount())
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        textList.add(Component.literal("[归墟视界枢核] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("结构已成型").withStyle(ChatFormatting.GREEN)));
        textList.add(Component.literal("已连接模块：" + getFormedModuleCount() + " / " + getMaxModuleCount())
                .withStyle(ChatFormatting.GRAY));
        textList.add(Component.literal("運作：" + getOperationState().label + "｜工作模組 " + activeModuleCount)
                .withStyle(ChatFormatting.AQUA));
        GTRecipeType mode = getPrimarySelectedRecipeType();
        if (mode != null) textList.add(Component.literal("模式：")
                .append(Component.translatable("gtceu." + mode.registryName.getPath())));
        if (getRecipeLogic().isWorking() || getRecipeLogic().isWaiting()) {
            textList.add(Component.literal("本批進度：" + getRecipeLogic().getProgress() + " / "
                    + getRecipeLogic().getMaxProgress() + " tick；剩餘 "
                    + Math.max(0, getRecipeLogic().getMaxProgress() - getRecipeLogic().getProgress()) / 20.0 + " 秒"));
            var recipe = getRecipeLogic().getLastRecipe();
            if (recipe instanceof IWirelessGTRecipe wireless && wireless.getWirelessEuTickInputs() != null) {
                BigInteger eut = wireless.getWirelessEuTickInputs();
                textList.add(Component.literal((eut.signum() > 0 ? "本批發電：" : "本批耗電：") + eut.abs() + " EU/t"));
            }
            if (recipe != null) {
                BigInteger items = BigInteger.ZERO;
                BigInteger fluids = BigInteger.ZERO;
                for (var stack : RecipeHelper.getOutputItems(recipe)) items = items.add(BigInteger.valueOf(stack.getCount()));
                for (var fluid : RecipeHelper.getOutputFluids(recipe)) fluids = fluids.add(BigInteger.valueOf(fluid.getAmount()));
                int ticks = Math.max(1, getRecipeLogic().getMaxProgress());
                textList.add(Component.literal("本批預估產出：" + outputRate(items, ticks)
                        + " 個/s，" + outputRate(fluids, ticks) + " mB/s"));
            }
            if (getRecipeLogic().isWaiting()) textList.addAll(getRecipeLogic().getFancyTooltip());
        }

        // ── 能量双仓：两行分别显示（默认那条已被本覆写挡掉，不重复）──
        final BigInteger[] input = energyTotals(IO.IN);
        final BigInteger[] output = energyTotals(IO.OUT);
        textList.add(Component.literal("能源仓 (IN)：" + input[0] + " / " + input[1] + " EU")
                .withStyle(ChatFormatting.AQUA));
        textList.add(Component.literal("动力仓 (OUT)：" + output[0] + " / " + output[1] + " EU")
                .withStyle(ChatFormatting.AQUA));

        // ── 物质模块槽门控 ⇒ 增产 / 减免（生效了就必须看得见）──
        final int bonus = moduleSlotBonus();
        textList.add(Component.literal(bonus <= 0
                ? "物质模块等级：未生效（主机那格放 64 个物质模块才生效）"
                : "物质模块等级：Lv." + bonus + " / 17").withStyle(ChatFormatting.GRAY));
        // ── 模块等级复写（第二个专属槽）──
        final int levelOverride = getHostMatterLevelOverride();
        textList.add(Component.literal(levelOverride <= 0
                ? "模块等级复写：未生效（复写槽放满 64 个物质模块才生效）"
                : "模块等级复写：Lv." + levelOverride + "（本机 24 个模块的有效等级至少抬到这一档）")
                .withStyle(ChatFormatting.GRAY));
        textList.add(Component.literal("材料增产：x" + productionMultiplier()
                + "（主机那套 + " + PRODUCTION_BONUS_EXTRA + "）").withStyle(ChatFormatting.GRAY));
        textList.add(Component.literal(String.format(Locale.ROOT,
                "耗能减免：x%.4f 耗电（同主机那套）",
                getHostEnergyReductionFactor(bonus))).withStyle(ChatFormatting.GRAY));
    }

    private static String outputRate(BigInteger amount, int ticks) {
        return new BigDecimal(amount).multiply(BigDecimal.valueOf(20))
                .divide(BigDecimal.valueOf(ticks), 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
