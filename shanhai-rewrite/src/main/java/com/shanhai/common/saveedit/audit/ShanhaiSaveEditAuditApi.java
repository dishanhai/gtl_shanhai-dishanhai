package com.shanhai.common.saveedit.audit;

import com.shanhai.common.saveedit.ShanhaiSaveEditFlow;
import com.shanhai.common.saveedit.ShanhaiSaveEditStorage;

import java.nio.file.Path;
import java.util.List;

/**
 * 「存档体检」给<b>面板/命令/探针</b>用的极简门面（一个存档 = 一个对象）。
 *
 * <h2>一、这一层只做四件事</h2>
 * <pre>
 *   ① 扫一遍（只读）            → {@link #scan()} / {@link #scan(ShanhaiSaveEditAuditScan.Options)}
 *   ② 拿清单与摘要              → {@link #items()} / {@link #summary()} / {@link #items(String)}
 *   ③ 删一条（面板：窗口已开）  → {@link #drop(String)}
 *   ④ 删一条（命令/探针：带闸） → {@link #dropGated(String, ShanhaiSaveEditFlow)}
 * </pre>
 * 白名单、回收目录、回读校验都关在 {@link ShanhaiSaveEditAuditScan} / {@link ShanhaiSaveEditAuditFix}
 * 里，面板不用知道它们。
 *
 * <h2>二、🔴 两个删除入口别用错</h2>
 * <ul>
 *   <li><b>面板页</b>挂在既有停服流程后面（那一层已经调过 {@link ShanhaiSaveEditFlow#tryApply(String)}）
 *       ⇒ 用 {@link #drop(String)}；</li>
 *   <li><b>命令 / 离线判据</b>是"自己开窗"的 ⇒ 用 {@link #dropGated(String, ShanhaiSaveEditFlow)}
 *       （窗口没开就零字节拒绝）。</li>
 * </ul>
 * 用错会得到两种都很"像 bug"的结果：面板里用带闸的那个 ⇒ 永远 {@code window_closed}；
 * 命令里用不带闸的那个 ⇒ 绕过了红线。
 *
 * <h2>三、线程</h2>
 * 全部方法都是<b>同步阻塞 IO</b>（扫一遍几百 KB 到几 MB）。面板请把它放在"点按钮 → 服务端执行"
 * 的那条路上，别放渲染线程。
 */
public final class ShanhaiSaveEditAuditApi {

    /** 面板清单一页几行（= {@code ShanhaiSaveEditLayout.AU_ROWS}；改一处必须改两处）。 */
    public static final int PANEL_LINES = 7;

    private final Path worldDir;

    private ShanhaiSaveEditAuditApi(Path worldDir) {
        this.worldDir = worldDir;
    }

    /** 绑定一个存档（扫描随时可跑；删除另需停服窗口）。 */
    public static ShanhaiSaveEditAuditApi of(Path worldDir) {
        return new ShanhaiSaveEditAuditApi(worldDir);
    }

    public Path worldDir() {
        return this.worldDir;
    }

    // ================================================================== 扫描

    /** 扫一遍（默认上限；只读）。 */
    public ShanhaiSaveEditAuditScan.Report scan() {
        return ShanhaiSaveEditAuditScan.scan(this.worldDir);
    }

    /** 扫一遍（可带进度回调/上限）。 */
    public ShanhaiSaveEditAuditScan.Report scan(ShanhaiSaveEditAuditScan.Options opts) {
        return ShanhaiSaveEditAuditScan.scan(this.worldDir, opts);
    }

    /** 白名单之外的全部条目（面板清单的原料）。 */
    public List<ShanhaiSaveEditAuditScan.Item> items() {
        return scan().items();
    }

    /** 同上，但按子串过滤（面板搜索框）。 */
    public List<ShanhaiSaveEditAuditScan.Item> items(String needle) {
        return scan().filter(needle);
    }

    /** 面板顶部那一行：{@code 共 N 条 · 文件 a · 目录 b · 文本 c · 二进制 d}。 */
    public String summary() {
        return scan().panelSummary();
    }

    // ================================================================== 删除（面板：窗口已开）

    /** 删掉白名单之外的这一条（<b>调用方必须已经在窗口里</b>）。 */
    public ShanhaiSaveEditAuditFix.Result drop(String rel) {
        return ShanhaiSaveEditAuditFix.dropInWindow(this.worldDir, rel, stamp());
    }

    // ================================================================== 删除（命令/探针：自己开窗过闸）

    /** 删掉这一条（带闸：窗口没开 ⇒ 零字节拒绝）。 */
    public ShanhaiSaveEditAuditFix.Result dropGated(String rel, ShanhaiSaveEditFlow flow) {
        return ShanhaiSaveEditAuditFix.drop(this.worldDir, rel, flow, stamp());
    }

    /** 回收目录的<b>根</b>（不含本次时间戳）；面板提示"可撤回"时用它。 */
    public Path trashBase() {
        final Path root = ShanhaiSaveEditAuditFix.trashRoot(this.worldDir, "x");
        final Path parent = root.getParent();
        return parent == null ? root : parent;
    }

    private static String stamp() {
        return ShanhaiSaveEditStorage.stamp(System.currentTimeMillis());
    }

    @Override
    public String toString() {
        return "ShanhaiSaveEditAuditApi[world=" + this.worldDir + "]";
    }
}
