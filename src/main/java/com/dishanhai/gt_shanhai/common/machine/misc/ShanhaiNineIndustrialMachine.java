package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.gui.configurators.NineIndustrialModeConfigurator;

import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;

import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleTypeRecipesMachine;

import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;
import static com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes.NINE_INDUSTRIAL;

/**
 * 大明科技 — 仿 FOTC 聚合模式：只注册 1 个配方类型，内部搜索 36 大类全部类型
 *
 * 原出处: GTnotleisure-0.2.5.jar (GTNH mod)
 *   com.science.gtnl.common.machine.multiblock.wireless.NineIndustrialMultiMachine
 *
 * @author 山海恒长在 / dishanhai
 */
public class ShanhaiNineIndustrialMachine extends GTLAddWirelessWorkableElectricMultipleTypeRecipesMachine {

    private static final String[] MODE_NAMES = {
        "被逼造反，聚义梁山","黄泥岗劫取贪官赃银","雪夜杀仇，投奔水泊",
        "力大无穷，显英雄本色","景阳冈三拳打死猛虎","醉酒闹寺，拳打僧众",
        "杀仇人，走投无路","救林冲于险境","街头受辱，显英雄孤苦",
        "巧计拉拢卢俊义","血战救兄，豪气冲天","因情被陷，走投无路",
        "机智除恶僧","揭发奸情，快意恩仇","水战英勇沉敌船",
        "力挫巨汉，显机巧","斩奸夫淫妇","脚快如飞，传递军机",
        "连战三次，终破庄院","大军征伐，威震四方","鏖战江南，众将折损惨重",
        "杀人放火，不拘法度","谋略定江山","家奴背叛，坠入牢狱",
        "豪侠舍财助义","四处漂泊","武艺无双，收降名将",
        "智勇双全","接受朝廷诏书","飞石百发百中",
        "天生水性，屡建奇功","投奔义军","兵临城下，威震敌军",
        "官军屡屡失利","天罡地煞齐聚","梁山泊传奇落幕",
        "金鼎淬火炼精钢","折铁弯弓显神通","旋刃削铁如泥","万钧之力压成形",
        "千度熔流铸新器","冰晶凝液化坚石","烈焰加温锻神兵","封存万物入铁罐",
        "热力旋转分精华","磁力吸附选精矿","光偏振筛分晶石","精密电路初组装",
        "扫描万物探本源","虚空采集纳灵气","巨石破碎化齑粉","岁月沉淀酿醇香",
        "超时空装配神兵","部件精密总装成","印刷电路板上线","电力聚爆压奇点",
        "恒星烈焰锻神材","超维度等离子熔炼","超维度完美搅拌","中子态素极限压缩",
        "深度化学扭曲仪","万物溶解归本源","温火慢煮解精华","热力交换传能量",
        "等离子冷凝结晶","原子能激发突变","温室培育生命","培养缸中演化",
        "屠宰场中取精华","闪电处理激活","衰变加速扭曲","元素复制镜像",
        "稀土离心分选","浮游选矿取精","虚空集气纳资源","材料回收再利用",
        "太空采矿掘星辰","集成矿石全处理","世界碎片采集器","核裂变释放能量",
        "质量发生器造物","物质生成创奇迹","超级粒子对撞机","熔岩炉中炼真金",
        "魔力生成化奇迹","超临界合成万物","量子操纵者降临","拆解万物归虚无",
        "脱硫净化除杂质","真空干燥去水分","燃料精炼提纯度","太空组装模块化",
        "精密激光蚀刻纹","维度聚焦蚀刻阵","光子晶阵蚀刻术","天基矿石全处理",
        "星核剥离取精华","反熵冷凝归秩序","终焉时空扭曲现","七十二变演万象",
        "混沌合成定乾坤"
    };

    @Persisted
    private int currentMode = 0;

    public ShanhaiNineIndustrialMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, NINE_INDUSTRIAL, args);
    }

    public int getCurrentMode() { return currentMode; }

    /** 重写配方类型数组 —— 只返回当前模式的配方类型，实现模式切换实际过滤配方 */
    @Override
    public GTRecipeType[] getRecipeTypes() {
        return ShanhaiMachineModeMap.getRecipeTypes(currentMode);
    }

    /** 重写单个配方类型 —— 返回当前模式的显示类型，Jade 自动显示翻译后的水浒传大名 */
    @Override
    public GTRecipeType getRecipeType() {
        return ShanhaiMachineModeMap.getModeDisplayType(currentMode);
    }

    public void setCurrentMode(int mode) {
        if (mode >= 0 && mode < ShanhaiMachineModeMap.TOTAL_MODES && mode != this.currentMode) {
            this.currentMode = mode;
            // 配方逻辑重启，让 getRecipeTypes() 返回的新类型生效
            var logic = getRecipeLogic();
            if (logic != null) logic.resetRecipeLogic();
            if (getLevel() != null && !getLevel().isClientSide) {
                notifyBlockUpdate();
            }
        }
    }

    public String getModeName() {
        int idx = currentMode;
        return idx >= 0 && idx < MODE_NAMES.length ? MODE_NAMES[idx] : "未知";
    }

    // ═══════════════════════════════════════════════════════════════
    // 配方注册
    // ═══════════════════════════════════════════════════════════════

    public static GTRecipeType[] getAllRecipeTypes() {
        return ShanhaiMachineModeMap.getPrimaryTypes();
    }

    @Override
    public void attachSideTabs(TabsWidget tabsWidget) {
        super.attachSideTabs(tabsWidget);
        tabsWidget.attachSubTab(new NineIndustrialModeConfigurator(
            MODE_NAMES,
            () -> currentMode,
            m -> setCurrentMode(m),
            ShanhaiMachineModeMap.TOTAL_MODES));
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            textList.add(Component.translatable("gtceu.gui.machinemode")
                .append(": §6§l" + getModeName() + " §7[" + currentMode + "/35]"));
            textList.add(DShanhaiTextUtil.createUltimateRainbow("同时处理至多无限个配方"));
            textList.add(Component.literal("§d∞ §7无线模式 · §d∞ §7并行 · §a不耗电 · §e无需维护"));
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure")
                .withStyle(ChatFormatting.RED));
        }
        textList.add(Component.literal("§6§l大明科技 §7- dishanhai").withStyle(ChatFormatting.GOLD));
    }

    // ═══════════════════════════════════════════════════════════════
    // 结构定义
    // ═══════════════════════════════════════════════════════════════

    private static Block blk(String ns, String p) {
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(ns, p));
        return b != null ? b : Blocks.BARRIER;
    }

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        Block BLK_E = blk("gtlcore", "dimensionally_transcendent_casing");
        Block BLK_D = blk("kubejs", "dimension_creation_casing");
        Block BLK_J = blk("gtlcore", "dimension_injection_casing");
        Block BLK_K = blk("kubejs", "eternity_coil_block");
        Block BLK_F = blk("gtceu", "high_power_casing");
        Block BLK_G = blk("kubejs", "dimensional_bridge_casing");
        Block BLK_M = blk("gtlcore", "molecular_casing");
        Block BLK_L = blk("gtlcore", "create_casing");
        Block BLK_O = blk("kubejs", "dimensional_stability_casing");
        Block BLK_I = blk("gt_shanhai", "active_neutron_casing");
        Block BLK_C = blk("gtlcore", "dimension_injection_casing");
        Block BLK_N = BLK_M, BLK_H = BLK_E, BLK_B = BLK_E, BLK_A = BLK_J;

        return FactoryBlockPattern.start(RelativeDirection.RIGHT, RelativeDirection.UP, RelativeDirection.BACK)
            .aisle("EEEEEEEEEEEEEEEEEEEEEEEEEEEEE", "EEEEEEEEEEEEOOOOOEEEEEEEEEEEE", "EEFF                     FFEE", "EEF                       FEE", "EE                         EE", "EE          MLMLM          EE", "EE         EEEEEEE         EE", "EE        EEEEEEEEE        EE", "EE       EGGGGGGGGGE       EE", "EE      EEEEEEEEEEEEE      EE", "EE     EGEEEEEEEEEEEGE     EE", "EE    EEGEEEOEEEOEEEGEE    EE", "EO   MEEGEEOOEEEOOEEGEEM   OE", "EO   LEEGEEEEEEEEEEEGEEL   OE", "EO   MEEGEEEEE~EEEEEGEEM   OE", "EO   LEEGEEEEEEEEEEEGEEL   OE", "EO   MEEGEEOOEEEOOEEGEEM   OE", "EE    EEGEEEOEEEOEEEGEE    EE", "EE     EGEEEEEEEEEEEGE     EE", "EE      EEEEEEEEEEEEE      EE", "EE       EGGGGGGGGGE       EE", "EE        EEEEEEEEE        EE", "EE         EEEEEEE         EE", "EE          MLMLM          EE", "EE                         EE", "EEF                       FEE", "EEFF                     FFEE", "EEEEEEEEEEEEOOOOOEEEEEEEEEEEE", "EEEEEEEEEEEEEEEEEEEEEEEEEEEEE")
            .aisle("EEEEEEEEEEEEEEEEEEEEEEEEEEEEE", "E  B                     B  E", "E  B                     B  E", "EBB                       BBE", "E           MLMLM           E", "E           MLMLM           E", "E          EEEEEEE          E", "E        IE       EI        E", "E        E         E        E", "E      IE           EI      E", "E      E  KKKKKKKKK  E      E", "E     E   K       K   E     E", "E   MME   K B   B K   EMM   E", "E   LLE   K       K   ELL   E", "E   MME   K   P   K   EMM   E", "E   LLE   K       K   ELL   E", "E   MME   K B   B K   EMM   E", "E     E   K       K   E     E", "E      E  KKKKKKKKK  E      E", "E      IE           EI      E", "E        E         E        E", "E        IE       EI        E", "E          EEEEEEE          E", "E           MLMLM           E", "E           MLMLM           E", "EBB                       BBE", "E  B                     B  E", "E  B                     B  E", "EEEEEEEEEEEEEEEEEEEEEEEEEEEEE")
            .aisle("EEFF                     FFEE", "E  B                     B  E", "F  N                     N  F", "FBNB        MLMLM        BNBF", "            MLMLM            ", "            MLMLM            ", "         I EEEEEEE I         ", "         HE       EH         ", "         E         E         ", "      IHE           EHI      ", "       E             E       ", "      E               E      ", "   MMME     B   B     EMMM   ", "   LLLE               ELLL   ", "   MMME       P       EMMM   ", "   LLLE               ELLL   ", "   MMME     B   B     EMMM   ", "      E               E      ", "       E             E       ", "      IHE           EHI      ", "         E         E         ", "         HE       EH         ", "         I EEEEEEE I         ", "            MLMLM            ", "            MLMLM            ", "FBNB        MLMLM        BNBF", "F  N                     N  F", "E  B                     B  E", "EEFF                     FFEE")
            .aisle("EEF                       FEE", "EBB                       BBE", "FBNB        MLMLM        BNBF", "  BNBLL     MLMLM     LLBNB  ", "   B L      CCCCC      L B   ", "   LL    I  MLMLM  I    LL   ", "   L    NH EEEEEEE HN    L   ", "       NNHE       EHNN       ", "      NN E         E NN      ", "     IHHE           EHHI     ", "       E             E       ", "      E               E      ", "  MMCME     B   B     EMCMM  ", "  LLCLE               ELCLL  ", "  MMCME       P       EMCMM  ", "  LLCLE               ELCLL  ", "  MMCME     B   B     EMCMM  ", "      E               E      ", "       E             E       ", "     IHHE           EHHI     ", "      NN E         E NN      ", "       NNHE       EHNN       ", "   L    NH EEEEEEE HN    L   ", "   LL    I  MLMLM  I    LL   ", "   B L      CCCCC      L B   ", "  BNBLL     MLMLM     LLBNB  ", "FBNB        MLMLM        BNBF", "EBB                       BBE", "EEF                       FEE")
            .aisle("EE                         EE", "E           MLMLM           E", "            MLMLM            ", "   B L      CCCCC      L B   ", "         I  MLMLM  I         ", "   L    FH  MLMLM  HF    L   ", "        FH EEEEEEE HF        ", "        FHE       EHF        ", "     FFF E         E FFF     ", "    IHHHE           EHHHI    ", "       E  KKKKKKKKK  E       ", "      E   K       K   E      ", " MMCMME   K B   B K   EMMCMM ", " LLCLLE   K       K   ELLCLL ", " MMCMME   K   P   K   EMMCMM ", " LLCLLE   K       K   ELLCLL ", " MMCMME   K B   B K   EMMCMM ", "      E   K       K   E      ", "       E  KKKKKKKKK  E       ", "    IHHHE           EHHHI    ", "     FFF E         E FFF     ", "        FHE       EHF        ", "        FH EEEEEEE HF        ", "   L    FH  MLMLM  HF    L   ", "         I  MLMLM  I         ", "   B L      CCCCC      L B   ", "            MLMLM            ", "E           MLMLM           E", "EE                         EE")
            .aisle("EE          MLMLM          EE", "E           MLMLM           E", "            MLMLM            ", "   LL    I  MLMLM  I    LL   ", "   L    FH  MLMLM  HF    L   ", "        FH  MLMLM  HF        ", "      DDFH EEEEEEE HFDD      ", "      DDFHEJJJJJJJEHFDD      ", "    FFFF EJJJJJJJJJE FFFF    ", "   IHHHHEJJJJJJJJJJJEHHHHI   ", "       EJJJJJJJJJJJJJE       ", "      EJJJJJJJJJJJJJJJE      ", "MMMMMMEJJJJJBJJJBJJJJJEMMMMMM", "LLLLLLEJJJJJJJJJJJJJJJELLLLLL", "MMMMMMEJJJJJJJPJJJJJJJEMMMMMM", "LLLLLLEJJJJJJJJJJJJJJJELLLLLL", "MMMMMMEJJJJJBJJJBJJJJJEMMMMMM", "      EJJJJJJJJJJJJJJJE      ", "       EJJJJJJJJJJJJJE       ", "   IHHHHEJJJJJJJJJJJEHHHHI   ", "    FFFF EJJJJJJJJJE FFFF    ", "      DDFHEJJJJJJJEHFDD      ", "      DDFH EEEEEEE HFDD      ", "        FH  MLMLM  HF        ", "   L    FH  MLMLM  HF    L   ", "   LL    I  MLMLM  I    LL   ", "            MLMLM            ", "E           MLMLM           E", "EE          MLMLM          EE")
            .aisle("EE         EEEEEEE         EE", "E          EEEEEEE          E", "         I EEEEEEE I         ", "   L    NH EEEEEEE HN    L   ", "        FH EEEEEEE HF        ", "      DDFH EEEEEEE HFDD      ", "     D   H         H   D     ", "     D   H         H   D     ", "   NFF     DDDDDDD     FFN   ", "  IHHHHH  D       D  HHHHHI  ", "         D         D         ", "EEEEEE  D           D  EEEEEE", "EEEEEE  D   B   B   D  EEEEEE", "EEEEEE  D           D  EEEEEE", "EEEEEE  D     P     D  EEEEEE", "EEEEEE  D           D  EEEEEE", "EEEEEE  D   B   B   D  EEEEEE", "EEEEEE  D           D  EEEEEE", "         D         D         ", "  IHHHHH  D       D  HHHHHI  ", "   NFF     DDDDDDD     FFN   ", "     D   H         H   D     ", "     D   H         H   D     ", "      DDFH EEEEEEE HFDD      ", "        FH EEEEEEE HF        ", "   L    NH EEEEEEE HN    L   ", "         I EEEEEEE I         ", "E          EEEEEEE          E", "EE         EEEEEEE         EE")
            .aisle("EE        EEEEEEEEE        EE", "E        IE       EI        E", "         HE       EH         ", "       NNHE       EHNN       ", "        FHE       EHF        ", "      DDFHEJJJJJJJEHFDD      ", "     D   H         H   D     ", "   N D  FH         HF  D N   ", "   NFF F   DDDDDDD   F FFN   ", " IHHHHHH  D       D  HHHHHHI ", "EEEEEE   D         D   EEEEEE", "E    J  D           D  J    E", "E    J  D   B   B   D  J    E", "E    J  D           D  J    E", "E    J  D     P     D  J    E", "E    J  D           D  J    E", "E    J  D   B   B   D  J    E", "E    J  D           D  J    E", "EEEEEE   D         D   EEEEEE", " IHHHHHH  D       D  HHHHHHI ", "   NFF F   DDDDDDD   F FFN   ", "   N D  FH         HF  D N   ", "     D   H         H   D     ", "      DDFHEJJJJJJJEHFDD      ", "        FHE       EHF        ", "       NNHE       EHNN       ", "         HE       EH         ", "E        IE       EI        E", "EE        EEEEEEEEE        EE")
            .aisle("EE       EGGGGGGGGGE       EE", "E        E         E        E", "         E         E         ", "      NN E         E NN      ", "     FFF E         E FFF     ", "    FFFF EJJJJJJJJJE FFFF    ", "   NFF     DDDDDDD     FFN   ", "   NFF F   DDDDDDD   F FFN   ", "           DDDDDDD           ", "EEEEEE                 EEEEEE", "G    J                 J    G", "G    JDDD           DDDJ    G", "G    JDDD   B   B   DDDJ    G", "G    JDDD           DDDJ    G", "G    JDDD     P     DDDJ    G", "G    JDDD           DDDJ    G", "G    JDDD   B   B   DDDJ    G", "G    JDDD           DDDJ    G", "G    J                 J    G", "EEEEEE                 EEEEEE", "           DDDDDDD           ", "   NFF F   DDDDDDD   F FFN   ", "   NFF     DDDDDDD     FFN   ", "    FFFF EJJJJJJJJJE FFFF    ", "     FFF E         E FFF     ", "      NN E         E NN      ", "         E         E         ", "E        E         E        E", "EE       EGGGGGGGGGE       EE")
            .aisle("EE      EEEEEEEEEEEEE      EE", "E      IE           EI      E", "      IHE           EHI      ", "     IHHE           EHHI     ", "    IHHHE           EHHHI    ", "   IHHHHEJJJJJJJJJJJEHHHHI   ", "  IHHHHH  D       D  HHHHHI  ", " IHHHHHH  D       D  HHHHHHI ", "EEEEEE    D       D    EEEEEE", "E    J                 J    E", "E    JDDD           DDDJ    E", "E    J                 J    E", "E    J      B   B      J    E", "E    J                 J    E", "E    J        P        J    E", "E    J                 J    E", "E    J      B   B      J    E", "E    J                 J    E", "E    JDDD           DDDJ    E", "E    J                 J    E", "EEEEEE    D       D    EEEEEE", " IHHHHHH  D       D  HHHHHHI ", "  IHHHHH  D       D  HHHHHI  ", "   IHHHHEJJJJJJJJJJJEHHHHI   ", "    IHHHE           EHHHI    ", "     IHHE           EHHI     ", "      IHE           EHI      ", "E      IE           EI      E", "EE      EEEEEEEEEEEEE      EE")
            .aisle("EE     EGEEEEEEEEEEEGE     EE", "E      E  KKKKKKKKK  E      E", "       E             E       ", "       E             E       ", "       E  KKKKKKKKK  E       ", "       EJJJJJJJJJJJJJE       ", "         D         D         ", "EEEEEE   D         D   EEEEEE", "G    J   D         D   J    G", "E    JDDD           DDDJ    E", "EK  KJ                 JK  KE", "EK  KJ                 JK  KE", "EK  KJ      B   B      JK  KE", "EK  KJ                 JK  KE", "EK  KJ        P        JK  KE", "EK  KJ                 JK  KE", "EK  KJ      B   B      JK  KE", "EK  KJ                 JK  KE", "EK  KJ                 JK  KE", "E    JDDD           DDDJ    E", "G    J   D         D   J    G", "EEEEEE   D         D   EEEEEE", "         D         D         ", "       EJJJJJJJJJJJJJE       ", "       E  KKKKKKKKK  E       ", "       E             E       ", "       E             E       ", "E      E  KKKKKKKKK  E      E", "EE     EGEEEEEEEEEEEGE     EE")
            .aisle("EE    EEGEEEOEEEOEEEGEE    EE", "E     E   K       K   E     E", "      E               E      ", "      E               E      ", "      E   K       K   E      ", "      EJJJJJJJJJJJJJJJE      ", "EEEEEE  D           D  EEEEEE", "E    J  D           D  J    E", "G    JDDD           DDDJ    G", "E    J                 J    E", "EK  KJ                 JK  KE", "E    J                 J    E", "O    J      B   B      J    O", "E    J                 J    E", "E    J        P        J    E", "E    J                 J    E", "O    J      B   B      J    O", "E    J                 J    E", "EK  KJ                 JK  KE", "E    J                 J    E", "G    JDDD           DDDJ    G", "E    J  D           D  J    E", "EEEEEE  D           D  EEEEEE", "      EJJJJJJJJJJJJJJJE      ", "      E   K       K   E      ", "      E               E      ", "      E               E      ", "E     E   K       K   E     E", "EE    EEGEEEOEEEOEEEGEE    EE")
            .aisle("EE   MEEGEEOOEEEOOEEGEEM   EE", "E   MME   K B   B K   EMM   E", "   MMME     B   B     EMMM   ", "  MMCME     B   B     EMCMM  ", " MMCMME   K B   B K   EMMCMM ", "MMMMMMEJJJJJBJJJBJJJJJEMMMMMM", "EEEEEE  D   B   B   D  EEEEEE", "E    J  D   B   B   D  J    E", "G    JDDD   B   B   DDDJ    G", "E    J      B   B      J    E", "EK  KJ      B   B      JK  KE", "O    J      B   B      J    O", "OBBBBBBBBBBBBBBBBBBBBBBBBBBBO", "E    J      BAAAB      J    E", "E    J      BAAAB      J    E", "E    J      BAAAB      J    E", "OBBBBBBBBBBBBBBBBBBBBBBBBBBBO", "O    J      B   B      J    O", "EK  KJ      B   B      JK  KE", "E    J      B   B      J    E", "G    JDDD   B   B   DDDJ    G", "E    J  D   B   B   D  J    E", "EEEEEE  D   B   B   D  EEEEEE", "MMMMMMEJJJJJBJJJBJJJJJEMMMMMM", " MMCMME   K B   B K   EMMCMM ", "  MMCME     B   B     EMCMM  ", "   MMME     B   B     EMMM   ", "E   MME   K B   B K   EMM   E", "EE   MEEGEEOOEEEOOEEGEEM   EE")
            .aisle("EE   LEEGEEEEEEEEEEEGEEL   EE", "E   LLE   K       K   ELL   E", "   LLLE               ELLL   ", "  LLCLE               ELCLL  ", " LLCLLE   K       K   ELLCLL ", "LLLLLLEJJJJJJJJJJJJJJJELLLLLL", "EEEEEE  D           D  EEEEEE", "E    J  D           D  J    E", "G    JDDD           DDDJ    G", "E    J                 J    E", "EK  KJ                 JK  KE", "E    J                 J    E", "E    J      BAAAB      J    E", "E    J      A   A      J    E", "E    J      A   A      J    E", "E    J      A   A      J    E", "E    J      BAAAB      J    E", "E    J                 J    E", "EK  KJ                 JK  KE", "E    J                 J    E", "G    JDDD           DDDJ    G", "E    J  D           D  J    E", "EEEEEE  D           D  EEEEEE", "LLLLLLEJJJJJJJJJJJJJJJELLLLLL", " LLCLLE   K       K   ELLCLL ", "  LLCLE               ELCLL  ", "   LLLE               ELLL   ", "E   LLE   K       K   ELL   E", "EE   LEEGEEEEEEEEEEEGEEL   EE")
            .aisle("EE   MEEGEEEEEPEEEEEGEEM   EE", "E   MME   K   P   K   EMM   E", "   MMME       P       EMMM   ", "  MMCME       P       EMCMM  ", " MMCMME   K   P   K   EMMCMM ", "MMMMMMEJJJJJJJPJJJJJJJEMMMMMM", "EEEEEE  D     P     D  EEEEEE", "E    J  D     P     D  J    E", "G    JDDD     P     DDDJ    G", "E    J        P        J    E", "EK  KJ        P        JK  KE", "E    J        P        J    E", "E    J      BAAAB      J    E", "E    J      A   A      J    E", "PPPPPPPPPPPPA   APPPPPPPPPPPP", "E    J      A   A      J    E", "E    J      BAAAB      J    E", "E    J        P        J    E", "EK  KJ        P        JK  KE", "E    J        P        J    E", "G    JDDD     P     DDDJ    G", "E    J  D     P     D  J    E", "EEEEEE  D     P     D  EEEEEE", "MMMMMMEJJJJJJJPJJJJJJJEMMMMMM", " MMCMME   K   P   K   EMMCMM ", "  MMCME       P       EMCMM  ", "   MMME       P       EMMM   ", "E   MME   K   P   K   EMM   E", "EE   MEEGEEEEEPEEEEEGEEM   EE")
            .aisle("EE   LEEGEEEEEEEEEEEGEEL   EE", "E   LLE   K       K   ELL   E", "   LLLE               ELLL   ", "  LLCLE               ELCLL  ", " LLCLLE   K       K   ELLCLL ", "LLLLLLEJJJJJJJJJJJJJJJELLLLLL", "EEEEEE  D           D  EEEEEE", "E    J  D           D  J    E", "G    JDDD           DDDJ    G", "E    J                 J    E", "EK  KJ                 JK  KE", "E    J                 J    E", "E    J      BAAAB      J    E", "E    J      A   A      J    E", "E    J      A   A      J    E", "E    J      A   A      J    E", "E    J      BAAAB      J    E", "E    J                 J    E", "EK  KJ                 JK  KE", "E    J                 J    E", "G    JDDD           DDDJ    G", "E    J  D           D  J    E", "EEEEEE  D           D  EEEEEE", "LLLLLLEJJJJJJJJJJJJJJJELLLLLL", " LLCLLE   K       K   ELLCLL ", "  LLCLE               ELCLL  ", "   LLLE               ELLL   ", "E   LLE   K       K   ELL   E", "EE   LEEGEEEEEEEEEEEGEEL   EE")
            .aisle("EE   MEEGEEOOEEEOOEEGEEM   EE", "E   MME   K B   B K   EMM   E", "   MMME     B   B     EMMM   ", "  MMCME     B   B     EMCMM  ", " MMCMME   K B   B K   EMMCMM ", "MMMMMMEJJJJJBJJJBJJJJJEMMMMMM", "EEEEEE  D   B   B   D  EEEEEE", "E    J  D   B   B   D  J    E", "G    JDDD   B   B   DDDJ    G", "E    J      B   B      J    E", "EK  KJ      B   B      JK  KE", "O    J      B   B      J    O", "OBBBBBBBBBBBBBBBBBBBBBBBBBBBO", "E    J      BAAAB      J    E", "E    J      BAAAB      J    E", "E    J      BAAAB      J    E", "OBBBBBBBBBBBBBBBBBBBBBBBBBBBO", "O    J      B   B      J    O", "EK  KJ      B   B      JK  KE", "E    J      B   B      J    E", "G    JDDD   B   B   DDDJ    G", "E    J  D   B   B   D  J    E", "EEEEEE  D   B   B   D  EEEEEE", "MMMMMMEJJJJJBJJJBJJJJJEMMMMMM", " MMCMME   K B   B K   EMMCMM ", "  MMCME     B   B     EMCMM  ", "   MMME     B   B     EMMM   ", "E   MME   K B   B K   EMM   E", "EE   MEEGEEOOEEEOOEEGEEM   EE")
            .aisle("EE    EEGEEEOEEEOEEEGEE    EE", "E     E   K       K   E     E", "      E               E      ", "      E               E      ", "      E   K       K   E      ", "      EJJJJJJJJJJJJJJJE      ", "EEEEEE  D           D  EEEEEE", "E    J  D           D  J    E", "G    JDDD           DDDJ    G", "E    J                 J    E", "EK  KJ                 JK  KE", "E    J                 J    E", "O    J      B   B      J    O", "E    J                 J    E", "E    J        P        J    E", "E    J                 J    E", "O    J      B   B      J    O", "E    J                 J    E", "EK  KJ                 JK  KE", "E    J                 J    E", "G    JDDD           DDDJ    G", "E    J  D           D  J    E", "EEEEEE  D           D  EEEEEE", "      EJJJJJJJJJJJJJJJE      ", "      E   K       K   E      ", "      E               E      ", "      E               E      ", "E     E   K       K   E     E", "EE    EEGEEEOEEEOEEEGEE    EE")
            .aisle("EE     EGEEEEEEEEEEEGE     EE", "E      E  KKKKKKKKK  E      E", "       E             E       ", "       E             E       ", "       E  KKKKKKKKK  E       ", "       EJJJJJJJJJJJJJE       ", "         D         D         ", "EEEEEE   D         D   EEEEEE", "G    J   D         D   J    G", "E    JDDD           DDDJ    E", "EK  KJ                 JK  KE", "EK  KJ                 JK  KE", "EK  KJ      B   B      JK  KE", "EK  KJ                 JK  KE", "EK  KJ        P        JK  KE", "EK  KJ                 JK  KE", "EK  KJ      B   B      JK  KE", "EK  KJ                 JK  KE", "EK  KJ                 JK  KE", "E    JDDD           DDDJ    E", "G    J   D         D   J    G", "EEEEEE   D         D   EEEEEE", "         D         D         ", "       EJJJJJJJJJJJJJE       ", "       E  KKKKKKKKK  E       ", "       E             E       ", "       E             E       ", "E      E  KKKKKKKKK  E      E", "EE     EGEEEEEEEEEEEGE     EE")
            .aisle("EE      EEEEEEEEEEEEE      EE", "E      IE           EI      E", "      IHE           EHI      ", "     IHHE           EHHI     ", "    IHHHE           EHHHI    ", "   IHHHHEJJJJJJJJJJJEHHHHI   ", "  IHHHHH  D       D  HHHHHI  ", " IHHHHHH  D       D  HHHHHHI ", "EEEEEE    D       D    EEEEEE", "E    J                 J    E", "E    JDDD           DDDJ    E", "E    J                 J    E", "E    J      B   B      J    E", "E    J                 J    E", "E    J        P        J    E", "E    J                 J    E", "E    J      B   B      J    E", "E    J                 J    E", "E    JDDD           DDDJ    E", "E    J                 J    E", "EEEEEE    D       D    EEEEEE", " IHHHHHH  D       D  HHHHHHI ", "  IHHHHH  D       D  HHHHHI  ", "   IHHHHEJJJJJJJJJJJEHHHHI   ", "    IHHHE           EHHHI    ", "     IHHE           EHHI     ", "      IHE           EHI      ", "E      IE           EI      E", "EE      EEEEEEEEEEEEE      EE")
            .aisle("EE       EGGGGGGGGGE       EE", "E        E         E        E", "         E         E         ", "      NN E         E NN      ", "     FFF E         E FFF     ", "    FFFF EJJJJJJJJJE FFFF    ", "   NFF     DDDDDDD     FFN   ", "   NFF F   DDDDDDD   F FFN   ", "           DDDDDDD           ", "EEEEEE                 EEEEEE", "G    J                 J    G", "G    JDDD           DDDJ    G", "G    JDDD   B   B   DDDJ    G", "G    JDDD           DDDJ    G", "G    JDDD     P     DDDJ    G", "G    JDDD           DDDJ    G", "G    JDDD   B   B   DDDJ    G", "G    JDDD           DDDJ    G", "G    J                 J    G", "EEEEEE                 EEEEEE", "           DDDDDDD           ", "   NFF F   DDDDDDD   F FFN   ", "   NFF     DDDDDDD     FFN   ", "    FFFF EJJJJJJJJJE FFFF    ", "     FFF E         E FFF     ", "      NN E         E NN      ", "         E         E         ", "E        E         E        E", "EE       EGGGGGGGGGE       EE")
            .aisle("EE        EEEEEEEEE        EE", "E        IE       EI        E", "         HE       EH         ", "       NNHE       EHNN       ", "        FHE       EHF        ", "      DDFHEJJJJJJJEHFDD      ", "     D   H         H   D     ", "   N D  FH         HF  D N   ", "   NFF F   DDDDDDD   F FFN   ", " IHHHHHH  D       D  HHHHHHI ", "EEEEEE   D         D   EEEEEE", "E    J  D           D  J    E", "E    J  D   B   B   D  J    E", "E    J  D           D  J    E", "E    J  D     P     D  J    E", "E    J  D           D  J    E", "E    J  D   B   B   D  J    E", "E    J  D           D  J    E", "EEEEEE   D         D   EEEEEE", " IHHHHHH  D       D  HHHHHHI ", "   NFF F   DDDDDDD   F FFN   ", "   N D  FH         HF  D N   ", "     D   H         H   D     ", "      DDFHEJJJJJJJEHFDD      ", "        FHE       EHF        ", "       NNHE       EHNN       ", "         HE       EH         ", "E        IE       EI        E", "EE        EEEEEEEEE        EE")
            .aisle("EE         EEEEEEE         EE", "E          EEEEEEE          E", "         I EEEEEEE I         ", "   L    NH EEEEEEE HN    L   ", "        FH EEEEEEE HF        ", "      DDFH EEEEEEE HFDD      ", "     D   H         H   D     ", "     D   H         H   D     ", "   NFF     DDDDDDD     FFN   ", "  IHHHHH  D       D  HHHHHI  ", "         D         D         ", "EEEEEE  D           D  EEEEEE", "EEEEEE  D   B   B   D  EEEEEE", "EEEEEE  D           D  EEEEEE", "EEEEEE  D     P     D  EEEEEE", "EEEEEE  D           D  EEEEEE", "EEEEEE  D   B   B   D  EEEEEE", "EEEEEE  D           D  EEEEEE", "         D         D         ", "  IHHHHH  D       D  HHHHHI  ", "   NFF     DDDDDDD     FFN   ", "     D   H         H   D     ", "     D   H         H   D     ", "      DDFH EEEEEEE HFDD      ", "        FH EEEEEEE HF        ", "   L    NH EEEEEEE HN    L   ", "         I EEEEEEE I         ", "E          EEEEEEE          E", "EE         EEEEEEE         EE")
            .aisle("EE          MLMLM          EE", "E           MLMLM           E", "            MLMLM            ", "   LL    I  MLMLM  I    LL   ", "   L    FH  MLMLM  HF    L   ", "        FH  MLMLM  HF        ", "      DDFH EEEEEEE HFDD      ", "      DDFHEJJJJJJJEHFDD      ", "    FFFF EJJJJJJJJJE FFFF    ", "   IHHHHEJJJJJJJJJJJEHHHHI   ", "       EJJJJJJJJJJJJJE       ", "      EJJJJJJJJJJJJJJJE      ", "MMMMMMEJJJJJBJJJBJJJJJEMMMMMM", "LLLLLLEJJJJJJJJJJJJJJJELLLLLL", "MMMMMMEJJJJJJJPJJJJJJJEMMMMMM", "LLLLLLEJJJJJJJJJJJJJJJELLLLLL", "MMMMMMEJJJJJBJJJBJJJJJEMMMMMM", "      EJJJJJJJJJJJJJJJE      ", "       EJJJJJJJJJJJJJE       ", "   IHHHHEJJJJJJJJJJJEHHHHI   ", "    FFFF EJJJJJJJJJE FFFF    ", "      DDFHEJJJJJJJEHFDD      ", "      DDFH EEEEEEE HFDD      ", "        FH  MLMLM  HF        ", "   L    FH  MLMLM  HF    L   ", "   LL    I  MLMLM  I    LL   ", "            MLMLM            ", "E           MLMLM           E", "EE          MLMLM          EE")
            .aisle("EE                         EE", "E           MLMLM           E", "            MLMLM            ", "   B L      CCCCC      L B   ", "         I  MLMLM  I         ", "   L    FH  MLMLM  HF    L   ", "        FH EEEEEEE HF        ", "        FHE       EHF        ", "     FFF E         E FFF     ", "    IHHHE           EHHHI    ", "       E  KKKKKKKKK  E       ", "      E   K       K   E      ", " MMCMME   K B   B K   EMMCMM ", " LLCLLE   K       K   ELLCLL ", " MMCMME   K   P   K   EMMCMM ", " LLCLLE   K       K   ELLCLL ", " MMCMME   K B   B K   EMMCMM ", "      E   K       K   E      ", "       E  KKKKKKKKK  E       ", "    IHHHE           EHHHI    ", "     FFF E         E FFF     ", "        FHE       EHF        ", "        FH EEEEEEE HF        ", "   L    FH  MLMLM  HF    L   ", "         I  MLMLM  I         ", "   B L      CCCCC      L B   ", "            MLMLM            ", "E           MLMLM           E", "EE                         EE")
            .aisle("EEF                       FEE", "EBB                       BBE", "FBNB        MLMLM        BNBF", "  BNBLL     MLMLM     LLBNB  ", "   B L      CCCCC      L B   ", "   LL    I  MLMLM  I    LL   ", "   L    NH EEEEEEE HN    L   ", "       NNHE       EHNN       ", "      NN E         E NN      ", "     IHHE           EHHI     ", "       E             E       ", "      E               E      ", "  MMCME     B   B     EMCMM  ", "  LLCLE               ELCLL  ", "  MMCME       P       EMCMM  ", "  LLCLE               ELCLL  ", "  MMCME     B   B     EMCMM  ", "      E               E      ", "       E             E       ", "     IHHE           EHHI     ", "      NN E         E NN      ", "       NNHE       EHNN       ", "   L    NH EEEEEEE HN    L   ", "   LL    I  MLMLM  I    LL   ", "   B L      CCCCC      L B   ", "  BNBLL     MLMLM     LLBNB  ", "FBNB        MLMLM        BNBF", "EBB                       BBE", "EEF                       FEE")
            .aisle("EEFF                     FFEE", "E  B                     B  E", "F  N                     N  F", "FBNB        MLMLM        BNBF", "            MLMLM            ", "            MLMLM            ", "         I EEEEEEE I         ", "         HE       EH         ", "         E         E         ", "      IHE           EHI      ", "       E             E       ", "      E               E      ", "   MMME     B   B     EMMM   ", "   LLLE               ELLL   ", "   MMME       P       EMMM   ", "   LLLE               ELLL   ", "   MMME     B   B     EMMM   ", "      E               E      ", "       E             E       ", "      IHE           EHI      ", "         E         E         ", "         HE       EH         ", "         I EEEEEEE I         ", "            MLMLM            ", "            MLMLM            ", "FBNB        MLMLM        BNBF", "F  N                     N  F", "E  B                     B  E", "EEFF                     FFEE")
            .aisle("EEEEEEEEEEEEEEPEEEEEEEEEEEEEE", "E  B                     B  E", "E  B                     B  E", "EBB                       BBE", "E           MLMLM           E", "E           MLMLM           E", "E          EEEEEEE          E", "E        IE       EI        E", "E        E         E        E", "E      IE           EI      E", "E      E  KKKKKKKKK  E      E", "E     E   K       K   E     E", "E   MME   K B   B K   EMM   E", "E   LLE   K       K   ELL   E", "E   MME   K   P   K   EMM   E", "E   LLE   K       K   ELL   E", "E   MME   K B   B K   EMM   E", "E     E   K       K   E     E", "E      E  KKKKKKKKK  E      E", "E      IE           EI      E", "E        E         E        E", "E        IE       EI        E", "E          EEEEEEE          E", "E           MLMLM           E", "E           MLMLM           E", "EBB                       BBE", "E  B                     B  E", "E  B                     B  E", "EEEEEEEEEEEEEEEEEEEEEEEEEEEEE")
            .aisle("EEEEEEEEEEEEEEEEEEEEEEEEEEEEE", "EEEEEEEEEEEEOOOOOEEEEEEEEEEEE", "EEFF                     FFEE", "EEF                       FEE", "EE                         EE", "EE          MLMLM          EE", "EE         EEEEEEE         EE", "EE        EEEEEEEEE        EE", "EE       EGGGGGGGGGE       EE", "EE      EEEEEEEEEEEEE      EE", "EE     EGEEEEEEEEEEEGE     EE", "EE    EEGEEEOEEEOEEEGEE    EE", "EO   MEEGEEOOEEEOOEEGEEM   OE", "EO   LEEGEEEEEEEEEEEGEEL   OE", "EO   MEEGEEEEEPEEEEEGEEM   OE", "EO   LEEGEEEEEEEEEEEGEEL   OE", "EO   MEEGEEOOEEEOOEEGEEM   OE", "EE    EEGEEEOEEEOEEEGEE    EE", "EE     EGEEEEEEEEEEEGE     EE", "EE      EEEEEEEEEEEEE      EE", "EE       EGGGGGGGGGE       EE", "EE        EEEEEEEEE        EE", "EE         EEEEEEE         EE", "EE          MLMLM          EE", "EE                         EE", "EEF                       FEE", "EEFF                     FFEE", "EEEEEEEEEEEEOOOOOEEEEEEEEEEEE", "EEEEEEEEEEEEEEEEEEEEEEEEEEEEE")
            .where(' ', Predicates.any())
            .where('E', Predicates.blocks(BLK_E))
            .where('D', Predicates.blocks(BLK_D))
            .where('J', Predicates.blocks(BLK_J))
            .where('K', Predicates.blocks(BLK_K))
            .where('F', Predicates.blocks(BLK_F)
                .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1)))
            .where('G', Predicates.blocks(BLK_G))
            .where('M', Predicates.blocks(BLK_M)
                .or(Predicates.abilities(PartAbility.MAINTENANCE).setPreviewCount(1)))
            .where('L', Predicates.blocks(BLK_L)
                .or(Predicates.abilities(PartAbility.MAINTENANCE).setPreviewCount(1)))
            .where('O', Predicates.blocks(BLK_O)
                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1)))
            .where('B', Predicates.blocks(BLK_B)
                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1)))
            .where('I', Predicates.blocks(BLK_I)
                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1)))
            .where('H', Predicates.blocks(BLK_H))
            .where('N', Predicates.blocks(BLK_N)
                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1)))
            .where('C', Predicates.blocks(BLK_C))
            .where('A', Predicates.blocks(BLK_A))
            .where('P', Predicates.blocks(BLK_K))
            .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // 注册 — 只注册 NINE_INDUSTRIAL 一个聚合类型
    // ═══════════════════════════════════════════════════════════════

    public static MultiblockMachineDefinition register() {
        var def = GTDishanhaiRegistration.REGISTRATE
            .multiblock("shanhai_nine_industrial", ShanhaiNineIndustrialMachine::new)
            .rotationState(RotationState.NON_Y_AXIS)
            .recipeTypes(getAllRecipeTypes())
            .appearanceBlock(() -> ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gt_shanhai", "active_neutron_casing")))
            .pattern(ShanhaiNineIndustrialMachine::createPattern)
            .workableCasingRenderer(
                new ResourceLocation("gt_shanhai", "block/active_neutron_casing"),
                new ResourceLocation(MOD_ID, "block/multiblock/primordial_omega_engine"))
            .register();

        def.setTooltipBuilder((stack, tips) -> {
            tips.add(Component.literal("§6§l大明科技"));
            tips.add(Component.literal(""));
            tips.add(Component.translatable("gtnl.tooltip.nine_industrial.0"));
            tips.add(Component.translatable("gtnl.tooltip.nine_industrial.1"));
            tips.add(Component.translatable("gtnl.tooltip.nine_industrial.2"));
            tips.add(Component.translatable("gtnl.tooltip.nine_industrial.3"));
            tips.add(Component.translatable("gtnl.tooltip.nine_industrial.4"));
            tips.add(Component.translatable("gtnl.tooltip.nine_industrial.5"));
            tips.add(Component.literal(""));
            tips.add(DShanhaiTextUtil.createUltimateRainbow("无限并行 · 无线模式 · 108模式可切换"));
            tips.add(Component.literal("§7原出处: GTnotleisure (GTNH)"));
        });
        return def;
    }
}
