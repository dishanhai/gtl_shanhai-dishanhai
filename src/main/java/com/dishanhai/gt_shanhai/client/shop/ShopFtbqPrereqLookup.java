package com.dishanhai.gt_shanhai.client.shop;

import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;

/**
 * 商店「前置任务」× FTBQ 集成（山海署名，仅客户端）：按商品条目配置的
 * {@link com.dishanhai.gt_shanhai.common.shop.ShopEntry#getPrerequisiteQuestId} 现查客户端已同步的
 * FTBQ 任务数据，供详情页显示完成状态 + 跳转。真正的购买门槛判定在服务端
 * （见 {@code ShopActionPacket#doBuy}），本类只做展示/跳转，不参与任何结算。
 *
 * <p>FTBQ 在本模组是强制依赖，不需要软依赖判空守卫，直接引用其 API 即可。</p>
 */
public final class ShopFtbqPrereqLookup {

    private ShopFtbqPrereqLookup() {}

    /** 按十六进制 ID 查客户端已同步的任务实例（未装 FTBQ / 未同步 / ID 非法返回 null）。 */
    public static Quest resolve(String hexId) {
        if (hexId == null || hexId.isEmpty()) return null;
        ClientQuestFile file = ClientQuestFile.INSTANCE;
        if (file == null) return null;
        long id = QuestObjectBase.parseCodeString(hexId);
        if (id == 0L) return null;
        return file.getQuest(id);
    }

    /** 本地玩家所在队伍是否已完成该任务（未加入队伍/数据未加载一律按未完成处理）。 */
    public static boolean isCompleted(Quest quest) {
        if (quest == null) return false;
        ClientQuestFile file = ClientQuestFile.INSTANCE;
        if (file == null || file.selfTeamData == null) return false;
        return file.selfTeamData.isCompleted(quest);
    }

    /** 打开任务书并定位聚焦到该任务（客户端调用，一步到位：未开先开，已开直接定位）。 */
    public static void open(String hexId) {
        if (hexId == null || hexId.isEmpty()) return;
        long id = QuestObjectBase.parseCodeString(hexId);
        if (id == 0L) return;
        ClientQuestFile.openBookToQuestObject(id);
    }
}
