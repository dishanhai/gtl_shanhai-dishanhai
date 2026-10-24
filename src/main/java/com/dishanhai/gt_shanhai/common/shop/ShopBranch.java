package com.dishanhai.gt_shanhai.common.shop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 商品分支（山海署名）：把一个分类节点的整棵子树连同商品复制到若干「阶段」节点之下，复制出的商品是副本，
 * 内容跟随主支同步。纯函数，不碰磁盘/网络，由 {@link ShopConfig} 套上发布与存档。
 *
 * <p>同步分两类字段：</p>
 * <ul>
 *     <li><b>内容（跟随主支）</b>：商品清单、显示名称、描述、显示图标、奖励模式/奖励池/FTBQ 表、交易方向；</li>
 *     <li><b>阶段自己的（副本各留各的）</b>：分类、成本、服务端/存档限购、周期限购、限时折扣、前置任务、
 *     一次性提交物品、隐藏、跳转、稳定 ID。各阶段定价和门槛常常不同，所以副本可以单独改。</li>
 * </ul>
 * 默认单向：主支改了，所有副本跟着改；编辑某个副本时可以勾「同步到主支」，把它的内容推给主支，再由主支转给其余副本。
 */
public final class ShopBranch {

    /** 分类最多四层（主/二级/三级/四级）。 */
    public static final int MAX_DEPTH = 4;

    private ShopBranch() {}

    /** 分类路径是否在 {@code path} 这棵子树内（含它自己）。 */
    public static boolean inBranch(String category, String path) {
        if (category == null || path == null || path.isEmpty()) return false;
        return category.equals(path) || category.startsWith(path + "/");
    }

    /** 路径最后一段（"a/b/c" → "c"）。 */
    public static String lastSegment(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** 路径层数（"a/b" → 2；空串 → 0）。 */
    public static int depth(String path) {
        if (path == null || path.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') n++;
        return n;
    }

    /** 把 {@code category}（在 {@code sourcePath} 子树内）搬到 {@code newRoot} 之下，保留子树内的相对路径。 */
    public static String relocate(String category, String sourcePath, String newRoot) {
        return newRoot + category.substring(sourcePath.length());
    }

    /** 副本跟随的主支 ID：自己是副本就沿用它的主支（不叠成链），否则自己就是主支。 */
    public static String masterIdOf(ShopEntry entry) {
        return entry.getBranchSource().isEmpty() ? entry.getStableId() : entry.getBranchSource();
    }

    /** 内容取 {@code content}，阶段自己的字段取 {@code local}，保留 local 的身份、分类与存档余量。 */
    public static ShopEntry merge(ShopEntry content, ShopEntry local) {
        ShopEntry merged = new ShopEntry(content.getGoodsList(), local.getCategory(), local.getCost(),
                content.getDescription(), local.getServerUses(), content.getDisplayIcons(),
                content.getRewardMode(), content.getRewardPool(), local.isHidden(), local.getLinkKey(),
                local.getLinkTo(), content.getDisplayName(), content.getFtbqTableId(), content.getFtbqSubMode(),
                content.getTradeMode(), local.getPeriodTicks(), local.getPeriodLimit(),
                local.getPrerequisiteQuestId(), local.getStableId(), local.getDiscountPercent(),
                local.getDiscountStartMs(), local.getDiscountEndMs(), local.getSubmissionItems());
        merged.overrideRemainingUses(local.getRemainingUses());
        return merged.withBranchSource(local.getBranchSource());
    }

    /**
     * 主支 {@code master} 已经是最新内容：把 {@code entries} 里所有跟随它的副本换成合并后的版本（原地改列表）。
     * 返回改了几条。
     */
    public static int propagate(List<ShopEntry> entries, ShopEntry master) {
        int changed = 0;
        for (int i = 0; i < entries.size(); i++) {
            ShopEntry e = entries.get(i);
            if (e == master || !master.getStableId().equals(e.getBranchSource())) continue;
            entries.set(i, merge(master, e));
            changed++;
        }
        return changed;
    }

    /** 一次复制的结果。 */
    public record CopyResult(List<ShopEntry> entries, int created, int synced, int skipped) {
        public boolean changed() {
            return created > 0 || synced > 0;
        }
    }

    /**
     * 把 {@code sourcePath} 子树复制到每个 {@code targetParents} 之下：新分支根 = 目标/源路径最后一段，子树内的相对结构原样保留。
     * <ul>
     *     <li>目标已经有某商品的副本（{@code branchSource} 指向同一个主支）→ 只把内容重新同步，不重复创建，所以重复执行 = 重新同步 + 补齐新增商品；</li>
     *     <li>目标落在源子树内部、会超过 {@link #MAX_DEPTH} 层的商品 → 跳过并计入 skipped；</li>
     *     <li>副本的成本、限购、折扣等先和主支一致，之后可单独改；跳转目标指向同一子树内的商品时，改指向对应的副本。</li>
     * </ul>
     */
    public static CopyResult copy(List<ShopEntry> entries, String sourcePath, Collection<String> targetParents) {
        List<ShopEntry> updated = new ArrayList<>(entries);
        int created = 0, synced = 0, skipped = 0;
        if (sourcePath == null || sourcePath.isBlank()) return new CopyResult(updated, 0, 0, 0);

        List<ShopEntry> sources = new ArrayList<>();
        for (ShopEntry e : entries) if (inBranch(e.getCategory(), sourcePath)) sources.add(e);
        if (sources.isEmpty()) return new CopyResult(updated, 0, 0, 0);

        String leaf = lastSegment(sourcePath);
        for (String target : new LinkedHashSet<>(targetParents == null ? List.<String>of() : targetParents)) {
            if (target == null || target.isBlank() || inBranch(target, sourcePath)) continue;
            String newRoot = target + "/" + leaf;
            int[] n = place(updated, sources, src -> {
                String category = relocate(src.getCategory(), sourcePath, newRoot);
                return depth(category) > MAX_DEPTH ? null : category;
            });
            created += n[0];
            synced += n[1];
            skipped += n[2];
        }
        return new CopyResult(updated, created, synced, skipped);
    }

    /**
     * 单个/若干商品复制到不同分组：每个目标分组就是副本落脚的分类（不再套分支根路径）。
     * 与 {@link #copy} 同一套规则——已有副本只重新同步，不重复创建；商品本来就在目标分组里、目标超过
     * {@link #MAX_DEPTH} 层都跳过并计入 skipped。
     */
    public static CopyResult copyEntries(List<ShopEntry> entries, Collection<String> stableIds,
                                         Collection<String> targetCategories) {
        List<ShopEntry> updated = new ArrayList<>(entries);
        LinkedHashSet<String> wanted = new LinkedHashSet<>(stableIds == null ? List.<String>of() : stableIds);
        List<ShopEntry> sources = new ArrayList<>();
        for (ShopEntry e : entries) if (wanted.contains(e.getStableId())) sources.add(e);
        int created = 0, synced = 0, skipped = 0;
        if (sources.isEmpty()) return new CopyResult(updated, 0, 0, 0);
        for (String target : new LinkedHashSet<>(targetCategories == null ? List.<String>of() : targetCategories)) {
            if (target == null || target.isBlank()) continue;
            int[] n = place(updated, sources,
                    src -> depth(target) > MAX_DEPTH || target.equals(src.getCategory()) ? null : target);
            created += n[0];
            synced += n[1];
            skipped += n[2];
        }
        return new CopyResult(updated, created, synced, skipped);
    }

    /**
     * 把 {@code sources} 逐个放到 {@code categoryOf} 给出的分类（返回 null = 跳过）：已有同主支同分类的副本就重新同步，
     * 否则新建；跳转目标指向本批商品的，改指向对应副本。返回 {新建, 同步, 跳过}，新副本追加到 {@code updated} 末尾。
     */
    private static int[] place(List<ShopEntry> updated, List<ShopEntry> sources,
                               java.util.function.Function<ShopEntry, String> categoryOf) {
        int created = 0, synced = 0, skipped = 0;
        Map<String, String> idMap = new HashMap<>(); // 源 stableId → 本次落脚的副本 stableId
        List<ShopEntry> fresh = new ArrayList<>();
        for (ShopEntry src : sources) {
            String category = categoryOf.apply(src);
            if (category == null) {
                skipped++;
                continue;
            }
            String masterId = masterIdOf(src);
            int existing = indexOfCopy(updated, masterId, category);
            if (existing >= 0) {
                ShopEntry old = updated.get(existing);
                updated.set(existing, merge(src, old));
                idMap.put(src.getStableId(), old.getStableId());
                synced++;
                continue;
            }
            ShopEntry copy = withNewIdentity(src, category).withBranchSource(masterId);
            copy.overrideRemainingUses(src.getServerUses());
            fresh.add(copy);
            idMap.put(src.getStableId(), copy.getStableId());
            created++;
        }
        for (int i = 0; i < fresh.size(); i++) {
            ShopEntry copy = fresh.get(i);
            String mapped = idMap.get(copy.getLinkTo());
            if (mapped != null) fresh.set(i, relink(copy, mapped));
        }
        updated.addAll(fresh);
        return new int[]{created, synced, skipped};
    }

    private static int indexOfCopy(List<ShopEntry> entries, String masterId, String category) {
        for (int i = 0; i < entries.size(); i++) {
            ShopEntry e = entries.get(i);
            if (masterId.equals(e.getBranchSource()) && category.equals(e.getCategory())) return i;
        }
        return -1;
    }

    /** 以 {@code src} 的阶段字段为底、换上新分类和一个全新的稳定 ID（跳转别名不复制，免得重名）。 */
    private static ShopEntry withNewIdentity(ShopEntry src, String category) {
        return new ShopEntry(src.getGoodsList(), category, src.getCost(), src.getDescription(), src.getServerUses(),
                src.getDisplayIcons(), src.getRewardMode(), src.getRewardPool(), src.isHidden(), "",
                src.getLinkTo(), src.getDisplayName(), src.getFtbqTableId(), src.getFtbqSubMode(),
                src.getTradeMode(), src.getPeriodTicks(), src.getPeriodLimit(), src.getPrerequisiteQuestId(),
                null, src.getDiscountPercent(), src.getDiscountStartMs(), src.getDiscountEndMs(),
                src.getSubmissionItems());
    }

    private static ShopEntry relink(ShopEntry copy, String linkTo) {
        ShopEntry out = new ShopEntry(copy.getGoodsList(), copy.getCategory(), copy.getCost(), copy.getDescription(),
                copy.getServerUses(), copy.getDisplayIcons(), copy.getRewardMode(), copy.getRewardPool(),
                copy.isHidden(), copy.getLinkKey(), linkTo, copy.getDisplayName(), copy.getFtbqTableId(),
                copy.getFtbqSubMode(), copy.getTradeMode(), copy.getPeriodTicks(), copy.getPeriodLimit(),
                copy.getPrerequisiteQuestId(), copy.getStableId(), copy.getDiscountPercent(),
                copy.getDiscountStartMs(), copy.getDiscountEndMs(), copy.getSubmissionItems());
        out.overrideRemainingUses(copy.getRemainingUses());
        return out.withBranchSource(copy.getBranchSource());
    }
}
