package com.shanhai.common.recipe.editor;

/**
 * 山海重构 · <b>「恢复一条配方之后，JEI 到底该收到哪一种载荷」的纯核</b>
 * （零 MC / 零 GT / 零 Forge 依赖 ⇒ 能离线 {@code javac + java} 跑断言）。
 *
 * <h2>1. 它修的是哪一个缺口（用户原话 + 现场读数）</h2>
 * <blockquote>「<b>恢复配方之后我不能立刻在 jei 中看到被恢复的配方</b>」</blockquote>
 *
 * <p>真因不是"包没带字节"，而是<b>发错了方向</b>。恢复那条路
 * （{@code ShanhaiRecipeEditorOps#restoreOneById}）的判据是
 * "活表里还读得到 ⇒ 发 changed（带整条字节）；读不到 ⇒ 发 removed"：</p>
 * <pre>
 *   ShanhaiRecipeEditorOps.java:871-880
 *     final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, id);
 *     if (live != null) { ShanhaiJeiBridge.broadcastRecipeChanged(server, live); }
 *     else              { ShanhaiJeiBridge.broadcastRecipeRemoved(server, id, type); }   // ← 病在这里
 * </pre>
 *
 * <p>而<b>上一局被删掉的那条</b>，这一局压根不在表里（KubeJS 覆盖层在开机那一刻就把
 * {@code op=remove} 套用了 ⇒ 它从没进过 {@code RecipeManager} ⇒ 底本里也没有它）。
 * 用户实例 {@code logs\2026-11-07-1.log.gz} 逐字（18:53:19.414）：</p>
 * <pre>
 *   RESTORE_DELETED id=gtceu:assembler/casing_large_scale_assembling …
 *                   has_snapshot=true <b>in_base=false in_live_index=false</b>
 *   18:53:19.779  net_sent action=<b>removed</b> id=gtceu:assembler/casing_large_scale_assembling (by_id)
 *   18:53:19.780  RESTORE_ONE … net_changed=0 <b>net_removed=1</b>
 *   ── 客户端那一侧（同一个日志，Render thread）────────────────────────────────
 *   18:53:19.800  jei_patch_stored … action=<b>removed</b> patches=28 kind=gt <b>recipe_bytes=0</b>
 *   18:53:19.804  jei_runtime_registered … ok=false added=false
 *   18:53:29.443  jei_runtime_hide_gave_up … reason=<b>removed_not_present</b>
 *   18:53:32.300  jei_sync matched=0 hidden=0 added=0 kept_original=0 … <b>visible_expected=0</b>
 * </pre>
 * ⇒ <b>恢复之后客户端手里存下的是一条"删掉"的补丁</b>（{@code action=removed}），
 * 查询出口按补丁把这一条从那一页里滤掉 ⇒ 用户看到的就是"恢复了、JEI 里却没有"。
 *
 * <h2>2. 判据表（本类只做这一件事）</h2>
 * <table border="1">
 *   <caption>恢复之后发什么</caption>
 *   <tr><th>事实</th><th>{@link Kind}</th><th>动作</th></tr>
 *   <tr>
 *     <td>恢复后活表里读得到它</td>
 *     <td>{@link Kind#ALREADY_LIVE} / {@link Kind#REVIVED_NOW}</td>
 *     <td><b>发 changed（带整条配方字节）</b> ⇒ 客户端 {@code buildRuntimeObject} 有正文、
 *         走"加进 JEI 自己的表"那条路 ⇒ 这一条当场出现在那一页</td>
 *   </tr>
 *   <tr>
 *     <td>恢复后仍然读不到，且<b>快照缺失 / 残 / 解析失败 / 类型对不上 / 登记被拒</b></td>
 *     <td>{@code REMOVED_*}</td>
 *     <td>照旧发 removed（<b>语义不变</b>：这一局它真的不在表里，JEI 里也不该有它 ——
 *         界面显示的东西必须和机器能跑的东西一致，"宁可缺，不可假"），
 *         但必须打一行能定位到哪一段的日志（{@link #code()}）</td>
 *   </tr>
 * </table>
 *
 * <h2>3. 为什么"能救就救"（revive）而不是只补一条假显示</h2>
 * 只把字节塞进包里（不发 removed）也能让 JEI 立刻看见，但那一份在服务端配方表里
 * <b>仍然不存在</b> ⇒ 看得见、机器不认、重连就没 = 本工程明令禁止的"幽灵配方"
 * （见 {@code ShanhaiRecipeBase#forgetNew} 的注释）。
 * 所以本类的 {@code REVIVED_NOW} 对应的是"先按快照把配方<b>真造回底本</b>，再发 changed"
 * —— JEI 与机器看到的是同一条。
 *
 * <h2>4. 只加判据、不碰既有语义</h2>
 * 本类<b>不</b>参与"恢复要不要成功"（那是 {@code restoreOneById} 的返回值，一行没改）；
 * 它只决定"恢复之后往 JEI 发哪一种包"。{@code (缺快照)} / {@code (主产物未知)}
 * 那两条既有提示一个字没动。
 */
public final class ShanhaiRestoreJeiPlan {

    /**
     * 恢复之后这一条在 JEI 上的归宿（<b>每一种都带一个可 grep 的 {@link Plan#code()}</b>）。
     */
    public enum Kind {
        /** 恢复后活表里本来就有它（同一局里删的那种）⇒ 发 changed。 */
        ALREADY_LIVE,
        /** 底本里没有、但快照把配方救回来了 ⇒ 发 changed（这就是本条修复的正路）。 */
        REVIVED_NOW,
        /** 救回来了、但活表里仍然读不到（极端）⇒ 只能发 removed + WARN。 */
        REVIVED_BUT_NOT_LIVE,
        /** 连类型都查不出来 ⇒ 发 removed。 */
        REMOVED_NO_TYPE,
        /** 盘上没有这一条的快照 ⇒ 发 removed（下一局开机会自己回来）。 */
        REMOVED_NO_SNAPSHOT,
        /** 有快照但不完整（缺 type / 缺产出键）⇒ 发 removed，不拿半成品造配方。 */
        REMOVED_SNAPSHOT_INCOMPLETE,
        /** 快照完整但 GT 反序列化失败 ⇒ 发 removed，且把异常打进日志。 */
        REMOVED_PARSE_FAILED,
        /** 快照里的类型和覆盖条目记的类型不是同一个 ⇒ 发 removed（保守，不猜）。 */
        REMOVED_TYPE_MISMATCH,
        /** 底本里已经有同 id（拒绝覆盖）⇒ 发 removed。 */
        REMOVED_REGISTER_REFUSED,
        /** 兜底（调用方给的 code 不认识）⇒ 发 removed。 */
        REMOVED_UNKNOWN
    }

    /**
     * 这一次恢复的结论。
     *
     * @param kind        JEI 上的归宿（见 {@link Kind}）
     * @param sendChanged true = 发 {@code ACTION_CHANGED}；false = 发 {@code ACTION_REMOVED}
     * @param needsBody   true = 这一条 changed 包里<b>必须</b>带整条配方字节
     *                    （没字节客户端就重建不出来 ⇒ 又回到"看不到"）
     * @param code        可 grep 的小写原因（日志里打的就是它）
     */
    public record Plan(Kind kind, boolean sendChanged, boolean needsBody, String code) {

        /** 一行中文，给状态行/日志用。 */
        public String zh() {
            return switch (kind) {
                case ALREADY_LIVE -> "活表里本来就有 ⇒ 发整条新值（带字节）";
                case REVIVED_NOW -> "用快照把它造回了底本 ⇒ 发整条新值（带字节）";
                case REVIVED_BUT_NOT_LIVE -> "救回来了但活表仍读不到 ⇒ 只能发删除（这一局看不到它）";
                case REMOVED_NO_TYPE -> "查不到类型 ⇒ 只能发删除（下一局开机会自己回来）";
                case REMOVED_NO_SNAPSHOT -> "没有快照可救 ⇒ 只能发删除（下一局开机会自己回来）";
                case REMOVED_SNAPSHOT_INCOMPLETE -> "快照缺产出键/类型 ⇒ 不敢造半成品 ⇒ 发删除";
                case REMOVED_PARSE_FAILED -> "快照解析失败 ⇒ 发删除（日志里有异常）";
                case REMOVED_TYPE_MISMATCH -> "快照类型与覆盖条目不一致 ⇒ 保守发删除";
                case REMOVED_REGISTER_REFUSED -> "底本已有同 id ⇒ 拒绝覆盖 ⇒ 发删除";
                case REMOVED_UNKNOWN -> "判据没对上 ⇒ 保守发删除";
            };
        }
    }

    /** 救活那一步的结论词表（{@code reviveFromBaseSnapshot} 的返回值，全部小写下划线）。 */
    public static final String R_ALREADY_IN_BASE = "already_in_base";
    public static final String R_REVIVED = "revived";
    public static final String R_NO_SNAPSHOT = "no_snapshot";
    public static final String R_SNAPSHOT_INCOMPLETE = "snapshot_incomplete";
    public static final String R_PARSE_FAILED = "parse_failed";
    public static final String R_TYPE_MISMATCH = "type_mismatch";
    public static final String R_REGISTER_REFUSED = "register_refused";
    public static final String R_NO_TYPE = "no_type";

    private ShanhaiRestoreJeiPlan() {}

    /**
     * <b>恢复之后发什么</b>（唯一判据点；生产路径 {@code restoreOneById} 就是拿它决定发哪种包）。
     *
     * @param liveNow   恢复流程走完之后，活表（{@code RecipeManager}）里还读不读得到这一条
     * @param typeKnown 覆盖条目/底本/活表里能不能确定它属于哪个 GT 配方类型
     * @param revive    救活那一步的结论（{@code already_in_base} / {@code revived} /
     *                  {@code no_snapshot} / … 见本类的 {@code R_*} 常量；可 {@code null}）
     */
    public static Plan of(boolean liveNow, boolean typeKnown, String revive) {
        if (liveNow) {
            // 活表里有 → 一定有对象可编字节 ⇒ 发整条新值。
            // 区分两种是为了日志能说清"是本来就在"还是"刚被快照救回来"。
            return R_REVIVED.equals(revive)
                    ? new Plan(Kind.REVIVED_NOW, true, true, "changed_revived")
                    : new Plan(Kind.ALREADY_LIVE, true, true, "changed_live");
        }
        if (!typeKnown) {
            return removed(Kind.REMOVED_NO_TYPE, R_NO_TYPE);
        }
        final String r = revive == null ? "" : revive;
        switch (r) {
            case R_NO_SNAPSHOT:
                return removed(Kind.REMOVED_NO_SNAPSHOT, R_NO_SNAPSHOT);
            case R_SNAPSHOT_INCOMPLETE:
                return removed(Kind.REMOVED_SNAPSHOT_INCOMPLETE, R_SNAPSHOT_INCOMPLETE);
            case R_PARSE_FAILED:
                return removed(Kind.REMOVED_PARSE_FAILED, R_PARSE_FAILED);
            case R_TYPE_MISMATCH:
                return removed(Kind.REMOVED_TYPE_MISMATCH, R_TYPE_MISMATCH);
            case R_REGISTER_REFUSED:
                return removed(Kind.REMOVED_REGISTER_REFUSED, R_REGISTER_REFUSED);
            case R_REVIVED:
            case R_ALREADY_IN_BASE:
                // 救活那一步说"成了"（或底本里本来就有），而活表里仍然没有 ⇒ 不能骗自己发 changed
                // （没有活对象就编不出字节，客户端只会走进 cannot_rebuild）。
                return removed(Kind.REVIVED_BUT_NOT_LIVE, "revived_but_not_live");
            default:
                return removed(Kind.REMOVED_UNKNOWN, r.isEmpty() ? "no_revive_code" : r);
        }
    }

    private static Plan removed(Kind kind, String code) {
        // 注意：removed 这一支 needsBody 恒 false —— 它的语义是"这一局它不在这里"，
        // 与"发一条没有正文的 changed"（客户端 cannot_rebuild：不藏不加）不是一回事。
        return new Plan(kind, false, false, "removed_" + code);
    }
}
