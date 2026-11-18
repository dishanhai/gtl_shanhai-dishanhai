package com.dishanhai.gt_shanhai.common.saveedit;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 {@code playerdata/<uuid>.dat} 读出来的<b>只读摘要</b>（纯 JDK：不含任何 NBT / Minecraft 类型）。
 *
 * <h2>为什么单独抽一个"摘要"类</h2>
 * 面板上要显示的那三行字是<b>服务端权威</b>的（靠 LDLib {@code LabelWidget} 的 update-info
 * 推给客户端），而服务端拼字时不能引用 {@code @OnlyIn(CLIENT)} 的东西。把它做成纯数据 +
 * 纯字符串，就能：① 两侧都能安全地拿来画；② 离线判据也能直接构造/断言。
 */
public final class ShanhaiPlayerDataSnapshot {

    /** 读的是哪个文件（只放文件名，绝对路径太长）。 */
    public final String fileName;
    /** 文件在不在。 */
    public final boolean present;
    /** 文件大小（字节）。 */
    public final long bytes;
    /** 解析/读取失败的原因（空 = 正常）。 */
    public final String error;

    public final String dataVersion;

    public final float health;
    public final int xpLevel;
    public final int foodLevel;
    public final float saturation;
    public final int gameMode;
    public final String dimension;
    public final double x, y, z;
    public final float yaw, pitch;

    /**
     * 41 个"玩家槽位"里有多少格非空（36 主背包 + 4 盔甲 + 1 副手）。
     *
     * <p>🔴 分母用的是 {@link ShanhaiPlayerDataEdit#INVENTORY_GRID_SLOTS}（= 41），
     * 不是写死的 40：线 B 之后物品栏是 <b>41 格</b>那一套口径（副手 {@code Slot=-106} 也是玩家槽位），
     * 分母写 40 会显示出"41/40"这种自相矛盾的读数。
     */
    public final int usedSlots;
    /** 面板上列出来的前几件（{@code 背包1=minecraft:diamond*64}）。 */
    public final List<String> preview;

    public ShanhaiPlayerDataSnapshot(String fileName, boolean present, long bytes, String error,
                                     String dataVersion, float health, int xpLevel, int foodLevel,
                                     float saturation, int gameMode, String dimension,
                                     double x, double y, double z, float yaw, float pitch,
                                     int usedSlots, List<String> preview) {
        this.fileName = fileName == null ? "-" : fileName;
        this.present = present;
        this.bytes = bytes;
        this.error = error == null ? "" : error;
        this.dataVersion = dataVersion == null ? "-" : dataVersion;
        this.health = health;
        this.xpLevel = xpLevel;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.gameMode = gameMode;
        this.dimension = dimension == null ? "-" : dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.usedSlots = usedSlots;
        this.preview = preview == null ? new ArrayList<>() : preview;
    }

    /** 一个"没读到"的占位（面板上诚实地说"还没有数据"，绝不假造数字）。 */
    public static ShanhaiPlayerDataSnapshot missing(String fileName, String why) {
        return new ShanhaiPlayerDataSnapshot(fileName, false, 0L, why, "-", 0F, 0, 0, 0F, 0,
                "-", 0D, 0D, 0D, 0F, 0F, 0, null);
    }

    /** 第一行：血量 / 等级 / 饥饿 / 模式。 */
    public String line1() {
        if (!this.present) {
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ed8e29363c6af318") + this.error + "）";
        }
        return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.84f6462d266e058b") + ShanhaiPlayerDataEdit.fmt(this.health)
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.43592611e99aa912") + this.xpLevel
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4de38e4649afaa35") + this.foodLevel + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ecd9e12d69db552d") + ShanhaiPlayerDataEdit.fmt(this.saturation)
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.4b96693026ecea9c") + ShanhaiPlayerDataEdit.gameModeName(this.gameMode);
    }

    /** 第二行：维度 / 位置 / 朝向。 */
    public String line2() {
        if (!this.present) {
            return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.600d6c80f066db42");
        }
        return ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.2c826d9525b5266f") + this.dimension
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.15c202e52a27e0b3") + ShanhaiPlayerDataEdit.fmt(this.x) + " / "
                + ShanhaiPlayerDataEdit.fmt(this.y) + " / " + ShanhaiPlayerDataEdit.fmt(this.z)
                + ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.ee5e69c78ff3dfe7") + ShanhaiPlayerDataEdit.fmt(this.yaw) + " / "
                + ShanhaiPlayerDataEdit.fmt(this.pitch);
    }

    /** 第三行：背包用了几格 + 前几件。 */
    public String line3() {
        if (!this.present) {
            return "§8DataVersion=" + this.dataVersion + " bytes=" + this.bytes;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.150fa17a13e75397")).append(this.usedSlots).append("§8/")
                .append(ShanhaiPlayerDataEdit.INVENTORY_GRID_SLOTS)
                .append(ShanhaiSaveEditText.text("gt_shanhai.saveedit.text.a83d75853d7eea33")).append(this.dataVersion);
        if (!this.preview.isEmpty()) {
            sb.append(" §8· ");
            for (int i = 0; i < this.preview.size(); i++) {
                sb.append(i == 0 ? "" : "§8, §f").append(this.preview.get(i));
            }
        }
        return sb.toString();
    }

    /** 三行拼一起（日志里一条打完，省得日志翻页）。 */
    public String joined() {
        return line1() + " ‖ " + line2() + " ‖ " + line3();
    }
}
