package com.shanhai.common.saveedit.audit;

import com.shanhai.common.saveedit.ShanhaiSaveEditFlow;
import com.shanhai.common.saveedit.ShanhaiSaveEditStorage;

import java.nio.file.Path;
import java.util.List;

/**
 * 「双份登记」给<b>面板</b>用的极简门面（下一个 agent 只要照这个接线）。
 *
 * <h2>一、为什么要有这一层</h2>
 * 面板页需要的东西只有四件：
 * <pre>
 *   ① 扫一遍（可带进度回调与上限）      → {@link ShanhaiSaveEditAuditScan.Report}
 *   ② 拿清单（每行一个可显示的字符串）  → {@link #findings()} / {@link ShanhaiSaveEditAuditScan.Report#panelLines(int)}
 *   ③ 删区块里那份                     → {@link #dropEntity(String)}
 *   ④ 删旁挂那行                       → {@link #dropSidecar(String)}
 * </pre>
 * 把"世界目录 + 备份根"绑在这一个对象上，页面就<b>不用</b>再散着传路径、也不用知道白名单、
 * 不收 UUID 归一化、不碰 NBT。
 *
 * <h2>二、🔴 两种动作入口的区别（面板一定要用对的那一个）</h2>
 * <ul>
 *   <li><b>面板页</b>挂在既有的停服流程后面（那一层已经调过
 *       {@link ShanhaiSaveEditFlow#tryApply(String)}）⇒ 用 {@link #dropEntity(String)} /
 *       {@link #dropSidecar(String)}（<b>不带闸</b>，内部走 {@code …InWindow}）。</li>
 *   <li><b>命令 / 离线判据</b>是"自己开窗"的 ⇒ 用
 *       {@link #dropEntityGated(String, ShanhaiSaveEditFlow)}（<b>带闸</b>，窗口没开就零字节写入并拒绝）。</li>
 * </ul>
 * 用错会得到两种都很"像 bug"的结果：面板里用带闸的那个 ⇒ 永远 {@code window_closed}；
 * 命令里用不带闸的那个 ⇒ 绕过了红线。所以这里把名字写死成两组，别混。
 *
 * <h2>三、线程</h2>
 * 全部方法都是<b>同步阻塞 IO</b>（扫一遍几百 KB 到几 MB）。面板请把它放在"点按钮 → 服务端执行"
 * 的那条路上，别放渲染线程。
 */
public final class ShanhaiSaveEditAuditApi {

    /** 面板清单一次最多显示多少行（超出由 {@code ...共 N 条} 收尾）。 */
    public static final int PANEL_LINES = 9;

    private final Path worldDir;
    private final Path backupRoot;

    private ShanhaiSaveEditAuditApi(Path worldDir, Path backupRoot) {
        this.worldDir = worldDir;
        this.backupRoot = backupRoot;
    }

    /**
     * 绑定一个存档（只读扫描随时可跑；两个删除动作另需窗口）。
     *
     * @param worldDir   存档根目录
     * @param backupRoot 备份根目录（生产 = {@code <游戏根>/temp/saveedit-backup}）
     */
    public static ShanhaiSaveEditAuditApi of(Path worldDir, Path backupRoot) {
        return new ShanhaiSaveEditAuditApi(worldDir, backupRoot);
    }

    /** 没有备份根时也行（扫描不需要它；删除动作会失败并如实报原因）。 */
    public static ShanhaiSaveEditAuditApi of(Path worldDir) {
        return new ShanhaiSaveEditAuditApi(worldDir, null);
    }

    public Path worldDir() {
        return this.worldDir;
    }

    public Path backupRoot() {
        return this.backupRoot;
    }

    // ================================================================== 扫描

    /** 扫一遍（默认上限；只读）。 */
    public ShanhaiSaveEditAuditScan.Report scan() {
        return ShanhaiSaveEditAuditScan.scan(this.worldDir);
    }

    /** 扫一遍（可带进度回调/上限/对照集）。 */
    public ShanhaiSaveEditAuditScan.Report scan(ShanhaiSaveEditAuditScan.Options opts) {
        return ShanhaiSaveEditAuditScan.scan(this.worldDir, opts);
    }

    /** 只要清单（面板清单区用；每行格式见 {@link ShanhaiSaveEditAuditScan.Finding#panelLine()}）。 */
    public List<ShanhaiSaveEditAuditScan.Finding> findings() {
        return scan().findings();
    }

    // ================================================================== 修改（面板：窗口已开）

    /** 删区块文件里那条实体（<b>调用方必须已经在窗口里</b>）。 */
    public ShanhaiSaveEditAuditFix.Result dropEntity(String uuid) {
        return ShanhaiSaveEditAuditFix.dropEntityInWindow(this.worldDir, uuid, this.backupRoot, stamp());
    }

    /** 删旁挂文件里含该 UUID/坐标的整行（<b>调用方必须已经在窗口里</b>；二进制只报不改）。 */
    public ShanhaiSaveEditAuditFix.Result dropSidecar(String uuid) {
        return ShanhaiSaveEditAuditFix.dropSidecarInWindow(this.worldDir, uuid, this.backupRoot, stamp());
    }

    // ================================================================== 修改（命令/探针：自己开窗过闸）

    /** 删区块里那份（带闸：窗口没开 ⇒ 零字节写入 + 拒绝）。 */
    public ShanhaiSaveEditAuditFix.Result dropEntityGated(String uuid, ShanhaiSaveEditFlow flow) {
        return ShanhaiSaveEditAuditFix.dropEntity(this.worldDir, uuid, flow, this.backupRoot, stamp());
    }

    /** 删旁挂那行（带闸：窗口没开 ⇒ 零字节写入 + 拒绝）。 */
    public ShanhaiSaveEditAuditFix.Result dropSidecarGated(String uuid, ShanhaiSaveEditFlow flow) {
        return ShanhaiSaveEditAuditFix.dropSidecar(this.worldDir, uuid, flow, this.backupRoot, stamp());
    }

    private static String stamp() {
        return ShanhaiSaveEditStorage.stamp(System.currentTimeMillis());
    }

    @Override
    public String toString() {
        return "ShanhaiSaveEditAuditApi[world=" + this.worldDir + " backup=" + this.backupRoot + "]";
    }
}
