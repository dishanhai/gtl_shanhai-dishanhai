package com.shanhai.common.patternmover;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第一刀的<b>自证</b>（离线纯核）：6 组读数，每一组都带<b>负对照</b>。
 *
 * <p>本类只 import {@code net.minecraft.nbt} 与 {@code java.util} ⇒ 在裸 JVM 里能跑
 * （跑法见 {@code temp/pattern-mover/probe/}：classpath 只要 forge 的 mapped jar ＋ 它的几个库；
 * <b>不需要</b> GTCEu / Forge 启动、不需要任何注册表）。
 *
 * <h2>六组（编号与任务书一一对应）</h2>
 * <table border="1">
 *   <tr><th>#</th><th>验什么</th><th>负对照（必须红）</th></tr>
 *   <tr><td>1</td><td>同键两次（不同载荷）⇒ 只剩 1 条且是<b>后一条</b>；不同键 ⇒ 2 条</td>
 *       <td>把键换成「永远不同」（= 不去重）⇒ 应当报「剩 2 条」</td></tr>
 *   <tr><td>2</td><td>上限临时压到 200 B ⇒ 塞到超限时<b>拒绝且原有数据不变</b></td>
 *       <td>「先写后报错」的实现 ⇒ 数据已被污染，必须被抓住</td></tr>
 *   <tr><td>3</td><td>默认上限 1.5 MiB 下，一直塞到接近上限 ⇒ 打印<b>实际字节</b>与<b>可再装条数</b></td>
 *       <td>—（这是读数，不是判断；判据是「第 N+1 条被拒时 remaining &lt; 单条字节」）</td></tr>
 *   <tr><td>4</td><td>2 个假总成（各 3 格 / 各 2 格非空）⇒ 读到 4 条、槽 6、空 2，
 *       且<b>源槽指纹前后相同</b></td>
 *       <td>「顺手清源」的实现 ⇒ 指纹变了，必须被抓住</td></tr>
 *   <tr><td>5</td><td>类型为空 ⇒ 归到「类型未识别」那一组</td>
 *       <td>把空类型当成一个真类型显示 ⇒ 组名不该是空串</td></tr>
 *   <tr><td>6</td><td>字节算术 == 真的 {@code NbtIo.write} 全量写（0/1/2/5 条 × 有/无 fromHost ×
 *       中文/代理对类型串）</td><td>把固定开销改成 21 ⇒ 必须报不等</td></tr>
 * </table>
 * 另加两组合并进来的稳定性检查：<b>规范化键</b>（同内容不同插入顺序 ⇒ 同键；不同内容 ⇒ 不同键）
 * 与 <b>NBT 往返</b>（写出去再读回来，键/类型/字节/条数全等）。
 *
 * <p>⚠️ <b>本类是只读的</b>：不读存档、不碰机器、不写物品。它构造的「样板」全是内存里的合成 NBT。
 */
public final class HostPatternMoverSelfCheck {

    /** 单条检查结果。 */
    public record Case(String id, boolean pass, String expected, String actual) {
        public String line() {
            return (this.pass ? "PASS " : "FAIL ") + this.id + " | 期望: " + this.expected
                    + " | 实测: " + this.actual;
        }
    }

    /** 整份报告。 */
    public record Report(int pass, int total, List<Case> cases, List<String> notes) {

        public boolean allOk() {
            return this.pass == this.total;
        }

        /** 一行式读数（可 grep）。 */
        public String summary() {
            return "SELFCHECK " + this.pass + "/" + this.total + " PASS=" + allOk();
        }

        /** 逐条明细（多行；日志用）。 */
        public String details() {
            final StringBuilder sb = new StringBuilder();
            for (Case c : this.cases) {
                sb.append('\n').append("  ").append(c.line());
            }
            for (String n : this.notes) {
                sb.append('\n').append("  NOTE ").append(n);
            }
            return sb.toString();
        }
    }

    private HostPatternMoverSelfCheck() {}

    /** 跑全部检查并返回报告（不打印）。 */
    public static Report run() {
        final List<Case> cases = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        // ── 第一刀（读 / 字节闸 / 去重键 / 只读）
        c1Merge(cases);
        c2ByteGate(cases);
        c3LimitReal(cases, notes);
        c4ScanReadOnly(cases);
        c5UnknownType(cases);
        c6ByteArithmetic(cases);
        c7CanonicalKey(cases);
        c8NbtRoundTrip(cases);

        // ── 第二刀（放置：分配 / 无模块 / 覆盖 / 分区闸 / 半途失败 / 取消 / 通知次数）
        c9PlaceAssign(cases);
        c10NoModule(cases);
        c11Replace(cases);
        c12ZoneGate(cases);
        c13PartialFanout(cases);
        c14Cancel(cases);
        c15NotifyPerWrite(cases);
        // ── 第二刀 · 改（取出 = 交给玩家，不销毁）
        c16TakeOut(cases);

        // ── 第三刀（A 通配符识别 / B 分页 / C 刷新读数 / D 删除项 / E 有效类型 / G 清空主机）
        c17WildcardType(cases, notes);
        c18WildcardNoGuess(cases);
        c19Pager(cases, notes);
        c20RemovedFeatures(cases);
        c21ClearHost(cases, notes);
        c22EffectiveTypes(cases, notes);
        c23RefreshReadings(cases, notes);
        c24ClearItemData(cases, notes);

        // ── 第四刀（① 批量写入抑制重建 / ② 通配符类型落盘 / ③ 面板分组列表重建）
        c25BatchWrite(cases, notes);
        c26PanelRows(cases, notes);
        c27WildcardStamp(cases, notes);

        // ── 第五刀（① 面板状态包的 rev 与"旧镜像不许盖新值" / ② 普通样板补类型 / ③ 读取汇总行）
        c28PanelRev(cases, notes);
        c29PlainStamp(cases, notes);
        c30SummaryLine(cases);

        // ── 第六刀（通配符母槽的编程电路：读 / 放 / 类型闸门顺序 / 字节账）
        c31CircuitRead(cases, notes);
        c32CircuitApply(cases, notes);
        c33CircuitSkipped(cases, notes);
        c34CircuitFailure(cases, notes);
        c35CircuitBytes(cases, notes);

        // ── 追加（用户实测）：归墟目标上的普通样板"没配方类型"—— 落盘那一步不许再看来源主机
        c36GuixuStamp(cases, notes);

        // ── 第六刀 ②：同配方合并时电路取非 0 的那一个（用户 2026-11-06 拍板）
        c37CircuitMerge(cases, notes);

        // ── 第七刀（用户实测「通配符还是没有电路」）：超级通配符总成那套 per-slot API
        c41SuperWildcardCircuit(cases, notes);

        // ── 第七刀补（同一个 bug 的同源形态）：全通配符槽的总成也要把类型落盘
        c42WildcardOnlyStamp(cases, notes);
        return new Report(countPass(cases), cases.size(), Collections.unmodifiableList(cases),
                Collections.unmodifiableList(notes));
    }

    private static int countPass(List<Case> cases) {
        int n = 0;
        for (Case c : cases) {
            if (c.pass()) {
                n++;
            }
        }
        return n;
    }

    /** 跑一遍并把读数打进日志（装机时调用一次）。 */
    public static Report log() {
        final Report r;
        try {
            r = run();
        } catch (Throwable t) {
            HostPatternMoverLog.warn("{} SELFCHECK 抛异常（这不是某一项没过，是自检本身坏了）{}",
                    HostPatternMoverLog.PREFIX, t.toString());
            return null;
        }
        if (r.allOk()) {
            HostPatternMoverLog.info("{} {} 上限={} B（1.5 MiB）", HostPatternMoverLog.PREFIX,
                    r.summary(), HostPatternMoverData.LIMIT_BYTES);
        } else {
            HostPatternMoverLog.warn("{} {} 明细：{}", HostPatternMoverLog.PREFIX, r.summary(), r.details());
        }
        if (!r.notes().isEmpty()) {
            HostPatternMoverLog.info("{} SELFCHECK_NOTE{}", HostPatternMoverLog.PREFIX, r.details());
        }
        return r;
    }

    // ================================================================ 1 · 合并 / 覆盖

    private static void c1Merge(List<Case> cases) {
        final FakeCodec codec = new FakeCodec(64);
        final HostPatternMoverData<Payload> data = new HostPatternMoverData<>(codec);

        data.add(new Payload("recipe:gtceu:assembler/a", "gtceu:assembler", 100));
        data.add(new Payload("recipe:gtceu:assembler/a", "gtceu:assembler", 200));
        cases.add(new Case("C1a 同键两次只剩一条", data.size() == 1, "size=1", "size=" + data.size()));
        final boolean last = data.size() == 1 && data.entries().get(0).size == 200;
        cases.add(new Case("C1b 留的是后一条", last, "size=200", data.isEmpty() ? "空"
                : "size=" + data.entries().get(0).size));

        final HostPatternMoverData<Payload> two = new HostPatternMoverData<>(codec);
        two.add(new Payload("recipe:gtceu:assembler/a", "gtceu:assembler", 100));
        two.add(new Payload("recipe:gtceu:assembler/b", "gtceu:assembler", 100));
        cases.add(new Case("C1c 不同键两条", two.size() == 2, "size=2", "size=" + two.size()));

        // ── 负对照：键永远不同 ⇒ 同样的两次 add 会变成 2 条（证明 C1a 的绿不是"总返回1"）
        final HostPatternMoverData<Payload> neg = new HostPatternMoverData<>(new FakeCodec(64) {
            private int n;

            @Override
            public String dedupKey(Payload p) {
                return "always-" + (this.n++);
            }
        });
        neg.add(new Payload("recipe:gtceu:assembler/a", "gtceu:assembler", 100));
        neg.add(new Payload("recipe:gtceu:assembler/a", "gtceu:assembler", 200));
        cases.add(new Case("C1NEG 键永远不同时不去重（负对照必须红）", neg.size() == 2,
                "size=2（= 说明去重确实由键决定）", "size=" + neg.size()));
    }

    // ================================================================ 2 · 字节闸（上限压到 200 B）

    private static void c2ByteGate(List<Case> cases) {
        final int limit = 200;
        final FakeCodec codec = new FakeCodec(10); // 假字节模型：20 + Σpayload（见 FakeCodec）
        final HostPatternMoverData<Payload> data = new HostPatternMoverData<>(codec, limit);
        // 每条 60 B ⇒ 20/80/140/200 都能过闸，第 4 条（260）必然超限
        while (data.add(new Payload("recipe:x" + data.size(), "gtceu:assembler", 60)).ok()) {
            // 一直塞到满
        }
        final int beforeBytes = data.bytes();
        final int beforeSize = data.size();
        final String beforeFingerprint = fingerprint(data);

        // 触发一次必然超限的加入
        final HostPatternMoverData.AddOutcome rejected =
                data.add(new Payload("recipe:overflow", "gtceu:assembler", limit));

        final boolean rejectedOk = rejected.status() == HostPatternMoverData.AddStatus.REJECTED_FULL;
        cases.add(new Case("C2a 超限被拒绝", rejectedOk,
                "status=REJECTED_FULL", "status=" + rejected.status()));
        cases.add(new Case("C2b 拒绝时明确报「还剩多少字节」", rejected.remaining() >= 0,
                "remaining>=0（实测 " + rejected.remaining() + "，还差 " + rejected.overBy() + "）",
                "remaining=" + rejected.remaining() + " overBy=" + rejected.overBy()));
        final String afterFingerprint = fingerprint(data);
        cases.add(new Case("C2c 拒绝后原有数据一字未变",
                data.bytes() == beforeBytes && data.size() == beforeSize
                        && beforeFingerprint.equals(afterFingerprint),
                "bytes=" + beforeBytes + " size=" + beforeSize + " 指纹相同",
                "bytes=" + data.bytes() + " size=" + data.size() + " 指纹相同="
                        + beforeFingerprint.equals(afterFingerprint)));

        // ── 负对照：「先写后报错」的坏实现 ⇒ 被拒绝时数据已被污染，C2c 那类断言必须红
        final BadData bad = new BadData(limit);
        while (bad.add(new Payload("recipe:y" + bad.size(), "gtceu:assembler", 60)).ok()) {
            // 填满
        }
        final int badBefore = bad.size();
        final String badBeforeFp = bad.fingerprint();
        final HostPatternMoverData.AddStatus st =
                bad.add(new Payload("recipe:overflow", "gtceu:assembler", 60)).status();
        final boolean polluted = st == HostPatternMoverData.AddStatus.REJECTED_FULL
                && (bad.size() != badBefore || !bad.fingerprint().equals(badBeforeFp));
        cases.add(new Case("C2NEG 先写后报错会被抓出来（负对照必须红）", polluted,
                "改前 size/指纹 ≠ 改后（= 说明闸不是靠运气）",
                "status=" + st + " size " + badBefore + "→" + bad.size()
                        + " 指纹变=" + !bad.fingerprint().equals(badBeforeFp)));
    }

    private static String fingerprint(HostPatternMoverData<Payload> data) {
        final StringBuilder sb = new StringBuilder();
        for (Payload p : data.entries()) {
            sb.append(p.key).append('#').append(p.size).append(';');
        }
        return sb.toString();
    }

    // ================================================================ 3 · 1.5 MiB 实数

    private static void c3LimitReal(List<Case> cases, List<String> notes) {
        final HostPatternMoverData<HostPatternMoverEntry> data =
                new HostPatternMoverData<>(new HostPatternMoverCodec("guixu_vision_core"));
        int n = 0;
        HostPatternMoverData.AddOutcome lastOk = null;
        HostPatternMoverData.AddOutcome firstRejected = null;
        while (n < 20000) {
            final HostPatternMoverData.AddOutcome o = data.add(syntheticEntry(n));
            if (!o.ok()) {
                firstRejected = o;
                break;
            }
            lastOk = o;
            n++;
        }
        final int total = data.bytes();
        final int count = data.size();
        final int avg = count == 0 ? 0 : total / count;
        final int fitMore = avg == 0 ? 0 : data.remainingBytes() / avg;
        notes.add("C3 1.5 MiB 现场：装下 " + count + " 块，实测 " + total + " B / "
                + HostPatternMoverData.LIMIT_BYTES + " B（均 " + avg + " B/块），"
                + "剩余 " + data.remainingBytes() + " B ⇒ 按均值还能再塞约 " + fitMore + " 块"
                + (firstRejected == null ? "（没撞到上限就停了）"
                : "；第 " + (count + 1) + " 块被拒（候选 " + firstRejected.candidateBytes()
                + " B，超 " + firstRejected.overBy() + " B，还可用 " + firstRejected.remaining() + " B）"));
        notes.add("C3 参照（真实存档实测，temp/pattern-mover/取证.md §5.3）：单块 481–1315 B、"
                + "p50 909 B ⇒ 1.5 MiB 约 1730 块");

        cases.add(new Case("C3a 默认上限就是 1.5 MiB", HostPatternMoverData.LIMIT_BYTES == 1_572_864,
                "1572864", String.valueOf(HostPatternMoverData.LIMIT_BYTES)));
        cases.add(new Case("C3b 装到超限为止且不越界",
                firstRejected != null && total <= HostPatternMoverData.LIMIT_BYTES
                        && firstRejected.candidateBytes() > HostPatternMoverData.LIMIT_BYTES
                        && lastOk != null,
                "被拒候选 > 上限，且当前总量 ≤ 上限",
                "总量=" + total + " 候选=" + (firstRejected == null ? -1 : firstRejected.candidateBytes())));
        cases.add(new Case("C3c 条数无上限（只由字节决定）", count > 100,
                "count>100", "count=" + count));
    }

    // ================================================================ 4 · 读取（只读 + 计数）

    private static void c4ScanReadOnly(List<Case> cases) {
        final FakeAssembly a = new FakeAssembly("A", 3, new int[]{0, 2});
        final FakeAssembly b = new FakeAssembly("B", 3, new int[]{1, 2});
        final String fpBefore = a.fingerprint() + "|" + b.fingerprint();

        final HostPatternMoverScan.Result<HostPatternMoverEntry> r =
                HostPatternMoverScan.scan(List.of(a, b));

        final String fpAfter = a.fingerprint() + "|" + b.fingerprint();
        cases.add(new Case("C4a 2 台 ×(3 格/2 非空) ⇒ 读到 4 条",
                r.nonEmpty() == 4 && r.payloads().size() == 4, "nonEmpty=4", "nonEmpty=" + r.nonEmpty()));
        cases.add(new Case("C4b 槽数 6 / 总成 2", r.slots() == 6 && r.assemblies() == 2,
                "slots=6 assemblies=2", "slots=" + r.slots() + " assemblies=" + r.assemblies()));
        cases.add(new Case("C4c 源槽未被改动（before/after 指纹相同）", fpBefore.equals(fpAfter),
                "指纹相同", "指纹相同=" + fpBefore.equals(fpAfter)));
        cases.add(new Case("C4d 读到的条数 = 非空槽数 = 载荷数",
                r.nonEmpty() == r.payloads().size() && r.nonEmpty() == 4,
                "三个数一致", r.nonEmpty() + "/" + r.payloads().size()));

        // ── 负对照：「顺手清源」的实现 ⇒ 扫完源槽指纹必须变了（否则 C4c 的绿没意义）
        final FakeAssembly clearing = new FakeAssembly("NEG", 3, new int[]{0, 2}) {
            @Override
            public HostPatternMoverEntry view(int slot) {
                final HostPatternMoverEntry e = super.view(slot);
                if (e != null) {
                    this.slots[slot] = null; // ← 就是"剪切"语义：读完清源
                }
                return e;
            }
        };
        final String cleBefore = clearing.fingerprint();
        HostPatternMoverScan.scan(List.of(clearing));
        final boolean changed = !cleBefore.equals(clearing.fingerprint());
        cases.add(new Case("C4NEG 顺手清源会被抓出来（负对照必须红）", changed,
                "改前/改后指纹不同（= 说明只读不是自动成立的）", "指纹变=" + changed));
    }

    // ================================================================ 5 · 类型为空 ⇒ 类型未识别

    private static void c5UnknownType(List<Case> cases) {
        final HostPatternMoverData<HostPatternMoverEntry> data =
                new HostPatternMoverData<>(new HostPatternMoverCodec(""));
        data.add(entry("recipe:a", "gtceu:assembler"));
        data.add(entry("recipe:b", ""));
        data.add(entry("recipe:c", null));
        data.add(entry("recipe:d", "  "));

        final Map<String, Integer> groups = data.groupsByType();
        final int unknown = groups.getOrDefault("", 0);
        cases.add(new Case("C5a 空类型归到空串那一组（= 面板的「类型未识别」）", unknown == 3,
                "3 条", unknown + " 条"));
        cases.add(new Case("C5b 有类型的仍在自己那组", groups.getOrDefault("gtceu:assembler", 0) == 1,
                "1 条", groups.getOrDefault("gtceu:assembler", 0) + " 条"));
        cases.add(new Case("C5c unknownTypeCount 与分组一致", data.unknownTypeCount() == unknown,
                "两者相等", data.unknownTypeCount() + " vs " + unknown + "（组数=" + groups.size() + "）"));

        // ── 负对照：把空类型替换成一个"看起来像类型"的假值 ⇒ 组名就不再是空串
        final HostPatternMoverData<HostPatternMoverEntry> fake = new HostPatternMoverData<>(
                new HostPatternMoverData.Codec<HostPatternMoverEntry>() {
                    @Override
                    public int measureBytes(List<HostPatternMoverEntry> entries) {
                        return HostPatternMoverCodec.totalBytes(entries, "");
                    }

                    @Override
                    public String dedupKey(HostPatternMoverEntry e) {
                        return e == null ? "" : e.dedupKey();
                    }

                    @Override
                    public String typeOf(HostPatternMoverEntry e) {
                        final String t = e == null ? "" : e.type();
                        return t == null || t.trim().isEmpty() ? "unknown" : t;
                    }
                });
        fake.add(entry("recipe:b", ""));
        final boolean groupHasUnknownKey = fake.groupsByType().containsKey("unknown");
        cases.add(new Case("C5NEG 把空类型当假类型会换组名（负对照必须红）", groupHasUnknownKey,
                "出现 \"unknown\" 组（= 说明空串组不是自动来的）", "出现=" + groupHasUnknownKey));
    }

    // ================================================================ 6 · 字节算术 == 实写

    private static void c6ByteArithmetic(List<Case> cases) {
        final int[][] sizes = {{0}, {1}, {2}, {5}};
        final String[] hosts = {"", "guixu_vision_core", " primordial"};
        boolean allEqual = true;
        final StringBuilder detail = new StringBuilder();
        for (int[] size : sizes) {
            for (String host : hosts) {
                final List<HostPatternMoverEntry> list = new ArrayList<>();
                for (int i = 0; i < size[0]; i++) {
                    list.add(entry("recipe:h" + host + "_" + i, "gtceu:assembler"));
                }
                final int arith = HostPatternMoverCodec.totalBytes(list, host);
                final int real = HostPatternMoverCodec.realTotalBytes(list, host);
                if (arith != real) {
                    allEqual = false;
                    detail.append(" n=").append(size[0]).append(" host='").append(host)
                            .append("' arith=").append(arith).append(" real=").append(real);
                }
            }
        }
        cases.add(new Case("C6a 字节算术 == NbtIo.write 全量（4 种条数 × 3 种 fromHost）", allEqual,
                "逐字节相等", allEqual ? "全部相等" : "不等：" + detail));

        // 中文 / 代理对的类型串：writeUTF 的长度口径
        final List<HostPatternMoverEntry> cn = List.of(entry("recipe:cn", "原初因果编织"));
        final List<HostPatternMoverEntry> emoji = List.of(entry("recipe:em", "gtceu:\uD83D\uDE00"));
        final boolean cnOk = HostPatternMoverCodec.totalBytes(cn, "归墟视界枢核")
                == HostPatternMoverCodec.realTotalBytes(cn, "归墟视界枢核");
        final boolean emojiOk = HostPatternMoverCodec.totalBytes(emoji, "")
                == HostPatternMoverCodec.realTotalBytes(emoji, "");
        cases.add(new Case("C6b 中文类型串 + 中文 fromHost", cnOk, "相等",
                cnOk ? "相等" : "不等"));
        cases.add(new Case("C6c 代理对（emoji）类型串", emojiOk, "相等",
                emojiOk + "（nbtStringBytes=" + HostPatternMoverBytes.nbtStringBytes("gtceu:\uD83D\uDE00")
                        + "，UTF-8 会算成 "
                        + ("gtceu:\uD83D\uDE00".getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                        + "）"));

        // ── 负对照：把固定开销改大 1 B ⇒ 算术与实写必须对不上
        final List<HostPatternMoverEntry> one = List.of(entry("recipe:neg", "gtceu:assembler"));
        final int real = HostPatternMoverCodec.realTotalBytes(one, "");
        final int broken = HostPatternMoverCodec.totalBytes(one, "") + 1;
        cases.add(new Case("C6NEG 开销错了会被发现（负对照必须红）", broken != real,
                "两者不等", broken + " vs " + real));
    }

    // ================================================================ 7 · 规范化去重键

    private static void c7CanonicalKey(List<Case> cases) {
        // 同一个内容的两种插入顺序 ⇒ 必须同键
        final CompoundTag p1 = recipeIdPattern("gtceu:assembler/a");
        final CompoundTag p2 = new CompoundTag();
        p2.put("tag", recipeIdPattern("gtceu:assembler/a").getCompound("tag").copy());
        p2.putString("id", p1.getString("id"));
        p2.putByte("Count", p1.getByte("Count"));

        final HostPatternMoverEntry e1 = HostPatternMoverEntry.of(p1, "");
        final HostPatternMoverEntry e2 = HostPatternMoverEntry.of(p2, "");
        cases.add(new Case("C7a 注入了配方 id ⇒ 键以 recipe: 开头",
                e1.dedupKey().startsWith("recipe:"), "recipe:…", e1.dedupKey()));

        final HostPatternMoverEntry n1 = HostPatternMoverEntry.of(stripRecipeId(p1), "");
        final HostPatternMoverEntry n2 = HostPatternMoverEntry.of(stripRecipeId(p2), "");
        cases.add(new Case("C7b 无配方 id ⇒ 落到 nbt: 字节身份",
                n1.dedupKey().startsWith("nbt:"), "nbt:…", n1.dedupKey()));
        cases.add(new Case("C7c 同内容不同插入顺序 ⇒ 同键（规范化渲染）",
                n1.dedupKey().equals(n2.dedupKey()), "相等",
                n1.dedupKey().equals(n2.dedupKey()) ? "相等" : "不等"));
        final HostPatternMoverEntry other = HostPatternMoverEntry.of(
                stripRecipeId(differentContentPattern()), "");
        cases.add(new Case("C7d 不同内容 ⇒ 不同键", !n1.dedupKey().equals(other.dedupKey()),
                "不等", n1.dedupKey().equals(other.dedupKey()) ? "撞了" : "不等"));
        cases.add(new Case("C7e 同配方 id ⇒ 同键（哪怕别的 NBT 不同）",
                HostPatternMoverEntry.of(p1, "").dedupKey()
                        .equals(HostPatternMoverEntry.of(recipeIdPattern("gtceu:assembler/a"), "").dedupKey()),
                "相等", "相等"));
    }

    // ================================================================ 8 · NBT 往返

    private static void c8NbtRoundTrip(List<Case> cases) {
        final List<HostPatternMoverEntry> entries = List.of(
                entry("recipe:a", "gtceu:assembler"),
                entry("recipe:b", ""),
                HostPatternMoverEntry.of(recipeIdPattern("gtceu:chemical_reactor/x"), "gtceu:chemical_reactor"));
        final CompoundTag tag = HostPatternMoverNbt.toTag(entries, "guixu_vision_core",
                HostPatternMoverData.LIMIT_BYTES);
        cases.add(new Case("C8a 写出来的标签非空", tag != null, "非 null", String.valueOf(tag != null)));
        if (tag == null) {
            return;
        }
        final List<HostPatternMoverEntry> back = HostPatternMoverNbt.readEntries(tag);
        boolean same = back.size() == entries.size();
        for (int i = 0; same && i < back.size(); i++) {
            same = back.get(i).dedupKey().equals(entries.get(i).dedupKey())
                    && back.get(i).type().equals(entries.get(i).type())
                    && back.get(i).standaloneBytes() == entries.get(i).standaloneBytes();
        }
        cases.add(new Case("C8b 往返后条数/键/类型/字节全等", same,
                "逐条相等", same ? "逐条相等" : "有条目不等"));

        final int stored = HostPatternMoverNbt.readStoredBytes(tag);
        final int real = HostPatternMoverCodec.realTotalBytes(entries, "guixu_vision_core");
        cases.add(new Case("C8c bytes 字段 == 实写字节数", stored == real,
                String.valueOf(real), String.valueOf(stored)));
        cases.add(new Case("C8d fromHost 往返", "guixu_vision_core".equals(
                HostPatternMoverNbt.readFromHost(tag)), "guixu_vision_core",
                HostPatternMoverNbt.readFromHost(tag)));

        // 读档：用标签重建一份数据，条数/字节要对得上
        final HostPatternMoverData<HostPatternMoverEntry> data = HostPatternMoverNbt.readData(
                tag, "guixu_vision_core", HostPatternMoverData.LIMIT_BYTES);
        cases.add(new Case("C8e 读档重建：条数与字节一致",
                data.size() == entries.size() && data.bytes() == real,
                "size=" + entries.size() + " bytes=" + real,
                "size=" + data.size() + " bytes=" + data.bytes()));
    }

    // ================================================================ 第二刀的夹具与 7 组断言

    /**
     * 一块样板（placer 的 {@code Unit} 的断言实现）。
     *
     * <p>⚠️ 第二个分量叫 {@code kind} 而不是 {@code type}：record 的自生成访问器会叫
     * {@code type()}，与接口方法同名同参 ⇒ 直接重复定义（javac 报 "already defined"）。
     *
     * @param key      去重键
     * @param kind     类型（{@code ""} = 未识别）
     * @param wildcard 是不是通配符样板
     */
    record FakeUnit(String key, String kind, boolean wildcard) implements HostPatternMoverPlacer.Unit {

        @Override
        public String dedupKey() {
            return this.key;
        }

        @Override
        public String type() {
            return this.kind;
        }

        @Override
        public String toString() {
            return this.key + "(" + (this.kind.isEmpty() ? "?" : this.kind) + (this.wildcard ? ",wild" : "") + ")";
        }
    }

    /**
     * 第六刀：一块<b>带编程电路</b>的假样板（{@link HostPatternMoverPlacer.Unit#circuit()} 的断言实现）。
     *
     * @param circuitValue 1..32 = 要写回目标母槽的电路；0 = 不设置（那条路<b>一次都不许</b>调机器）
     */
    record FakeCircuitUnit(String key, String kind, boolean wildcard,
                           int circuitValue) implements HostPatternMoverPlacer.Unit {

        @Override
        public String dedupKey() {
            return this.key;
        }

        @Override
        public String type() {
            return this.kind;
        }

        @Override
        public int circuit() {
            return this.circuitValue;
        }

        @Override
        public String toString() {
            return this.key + "(" + this.kind + (this.wildcard ? ",wild" : "") + ",c=" + this.circuitValue + ")";
        }
    }

    /**
     * 一台假总成（placer 的 {@code Target} 的断言实现）。
     *
     * <p>🔴 它 <b>就是</b> 第一刀 {@code MEPatternBufferPartMachine.internalPatternInventory} 的模型：
     * <ul>
     *   <li>{@link #write(int, HostPatternMoverPlacer.Unit)} 真的落一格 ⇒ 记一次 {@code writeCount}；</li>
     *   <li>并且<b>顺带</b>记一次 {@link #notifyCount} —— 那代表上游
     *       {@code setItemDirect} 里的 {@code onPatternChange(slot)}（见
     *       {@code MEPatternBufferPartMachine.java:122-126}）。{@code skipNotify=true} 就是
     *       "写了但没通知"的坏实现（第 7 组的负对照）。</li>
     * </ul>
     */
    static class FakeTarget implements HostPatternMoverPlacer.Target {

        final String name;
        final String[] slots;
        final java.util.Set<String> types;
        boolean zoned;
        int normal;
        int wild;
        int writeCount;
        int notifyCount;
        boolean skipNotify;

        FakeTarget(String name, int size, String... types) {
            this.name = name;
            this.slots = new String[size];
            this.types = new java.util.LinkedHashSet<>(java.util.List.of(types));
        }

        FakeTarget zoned(int normalSlots, int wildcardSlots) {
            this.zoned = true;
            this.normal = normalSlots;
            this.wild = wildcardSlots;
            return this;
        }

        /** 直接指定"支持哪些类型"（E 那条自证要喂有效类型集进来）。 */
        FakeTarget useTypes(java.util.Collection<String> supported) {
            this.types.clear();
            if (supported != null) {
                this.types.addAll(supported);
            }
            return this;
        }

        FakeTarget put(int slot, String key) {
            this.slots[slot] = key;
            return this;
        }

        FakeTarget skipNotify() {
            this.skipNotify = true;
            return this;
        }

        @Override
        public String label() {
            return this.name;
        }

        @Override
        public int size() {
            return this.slots.length;
        }

        @Override
        public String keyAt(int slot) {
            return slot < 0 || slot >= this.slots.length ? null : this.slots[slot];
        }

        @Override
        public boolean supportsType(String type) {
            return type != null && this.types.contains(type.trim());
        }

        @Override
        public boolean zoned() {
            return this.zoned;
        }

        @Override
        public int normalSlots() {
            return this.normal;
        }

        @Override
        public int wildcardSlots() {
            return this.wild;
        }

        @Override
        public boolean validAt(int slot, HostPatternMoverPlacer.Unit unit) {
            return slot >= 0 && slot < this.slots.length;
        }

        @Override
        public void write(int slot, HostPatternMoverPlacer.Unit unit) {
            this.slots[slot] = unit == null ? null : unit.dedupKey();
            this.writeCount++;
            if (!this.skipNotify) {
                this.notifyCount++; // = 上游 setItemDirect 里那一次 onPatternChange
            }
        }

        /** 指纹：每格一个字符（{@code -} 空 / {@code #} 有），后接非空格数 —— 判"变没变"用它。 */
        String fingerprint() {
            final StringBuilder sb = new StringBuilder(this.slots.length + 8);
            int n = 0;
            for (String s : this.slots) {
                if (s == null) {
                    sb.append('-');
                } else {
                    sb.append('#');
                    n++;
                }
            }
            return sb + "|nonEmpty=" + n;
        }

        int nonEmpty() {
            int n = 0;
            for (String s : this.slots) {
                if (s != null) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * 第六刀 ＋ 第七刀：一台<b>有电路 API</b> 的假总成（{@code wildcardMotherAt} / {@code applyCircuit}）。
     *
     * <p>🔴 第七刀起它<b>不再是</b>"自己写一遍区间判断"：两个方法都转交给一台真的
     * {@link HostPatternMoverCircuit.Source}（星律 = {@code mothers(...)}，超级通配符 = {@code selfIndexed(...)}）
     * ⇒ 离线打的就是<b>生产那句</b>代码（索引算法只有一份），只是把"机器收到了什么"换成记账：
     * <pre>
     *   wildcardMotherAt(slot) → source.indexAt(slot)                    （纯算术）
     *   applyCircuit(slot, c)  → source.applyAt(slot, c) → writer.set(i,c)（writer 记 appliedIndex/appliedCircuit）
     * </pre>
     * {@code throwOnCircuit} = 机器侧 API 炸了（用来验「样板不回滚 ＋ WARN 只一行」）。
     */
    static class FakeCircuitTarget extends FakeTarget {

        /** 纯核调了几次 {@code applyCircuit}（= "它认为该写"的次数）。 */
        int circuitCalls;
        /** 机器侧真的收到几次写入（由 writer 记账；与上一条分开是为了证明"该写≠真写"）。 */
        int writerCalls;
        /** 机器侧收到的<b>电路索引</b>（星律 = 母槽号；超级通配符 = 它自己的槽号）。 */
        int appliedIndex = -1;
        /** 机器侧收到的电路值。 */
        int appliedCircuit = -1;
        boolean throwOnCircuit;
        /** 机器侧"拒绝"这个值（= 实机 {@code shanhaiApplySlotCircuit} 回 false：值没变/该格没样板）。 */
        boolean rejectCircuitWrites;
        private HostPatternMoverCircuit.Source source =
                HostPatternMoverCircuit.Source.NONE;

        FakeCircuitTarget(String name, int size, String... types) {
            super(name, size, types);
        }

        /** 协变返回（父类 {@code zoned} 回的是 {@link FakeTarget}，链式调用就会丢掉子类型）。 */
        @Override
        FakeCircuitTarget zoned(int normalSlots, int wildcardSlots) {
            super.zoned(normalSlots, wildcardSlots);
            return this;
        }

        /** 记一台"机器侧"的写：{@code appliedIndex/appliedCircuit/writerCalls} 就是"机器收到了什么"。 */
        private HostPatternMoverCircuit.SlotCircuit recorder() {
            return (index, circuit) -> {
                this.writerCalls++;
                this.appliedIndex = index;
                this.appliedCircuit = circuit;
                // 假机器默认"接受"；{@code rejectCircuitWrites} 模拟实机那种"值没变 ⇒ 回 false"
                return !this.rejectCircuitWrites;
            };
        }

        /** 星律式来源：按母槽号读写（起点 = {@code normal}）。 */
        FakeCircuitTarget motherSource(int normal, int wild,
                                       HostPatternMoverCircuit.MotherLookup read) {
            this.source = HostPatternMoverCircuit.Source.mothers(
                    HostPatternMoverCircuit.KIND_STELLAR, normal, wild, read, recorder());
            return this;
        }

        /** 超级通配符式来源：按它自己的槽号读写（起点 = 0）。 */
        FakeCircuitTarget superSource(int slots, HostPatternMoverCircuit.MotherLookup read) {
            this.source = HostPatternMoverCircuit.Source.selfIndexed(
                    HostPatternMoverCircuit.KIND_SUPER_WILDCARD, slots, read, recorder());
            return this;
        }

        /** 直接装一台来源（C41 里用来测"没有写适配器"的形态）。 */
        FakeCircuitTarget source(HostPatternMoverCircuit.Source src) {
            this.source = src == null ? HostPatternMoverCircuit.Source.NONE : src;
            return this;
        }

        HostPatternMoverCircuit.Source source() {
            return this.source;
        }

        @Override
        public int wildcardMotherAt(int slot) {
            return this.source.indexAt(slot);
        }

        @Override
        public void applyCircuit(int slot, int circuit) {
            if (this.throwOnCircuit) {
                throw new IllegalStateException("假机器：写电路失败");
            }
            this.circuitCalls++;
            this.source.applyAt(slot, circuit);
        }
    }

    // ================================================================ 9 · 分配（3 模块 / 6 样板）

    private static void c9PlaceAssign(List<Case> cases) {
        final FakeTarget a = new FakeTarget("A", 10, "A");
        final FakeTarget b = new FakeTarget("B", 10, "B");
        final FakeTarget c = new FakeTarget("C", 10, "C");
        final List<HostPatternMoverPlacer.Unit> units = List.of(
                new FakeUnit("recipe:a1", "A", false),
                new FakeUnit("recipe:a2", "A", false),
                new FakeUnit("recipe:a3", "A", false),
                new FakeUnit("recipe:b1", "B", false),
                new FakeUnit("recipe:b2", "B", false),
                new FakeUnit("recipe:u1", "", false));      // ← 未识别类型
        final HostPatternMoverPlacer.Plan plan = HostPatternMoverPlacer.plan(units, List.of(a, b, c));
        final HostPatternMoverPlacer.Result r = HostPatternMoverPlacer.apply(plan, units, List.of(a, b, c));
        cases.add(new Case("C9a 3 模块(A/B/C) × 6 样板(3A/2B/1未识别) ⇒ placed=5",
                r.placed() == 5, "placed=5", "placed=" + r.placed()));
        cases.add(new Case("C9b 未识别那 1 块计入「未识别类型」",
                r.skippedUnknownType() == 1, "skippedEmpty=1", "skippedEmpty=" + r.skippedUnknownType()));
        cases.add(new Case("C9c 有对应模块 ⇒ 一个都不算「无对应模块」",
                r.skippedNoModule() == 0, "skippedNoModule=0", "skippedNoModule=" + r.skippedNoModule()));
        // 分配真的按类型分到不同模块上（不是"全塞第一台"）
        cases.add(new Case("C9d A 进 A 模块 / B 进 B 模块 / C 一个没进",
                a.nonEmpty() == 3 && b.nonEmpty() == 2 && c.nonEmpty() == 0,
                "A=3 B=2 C=0", "A=" + a.nonEmpty() + " B=" + b.nonEmpty() + " C=" + c.nonEmpty()));
        // ── 负对照：让两台总成都"什么都支持" ⇒ 6 块全被第一台吃掉（第二台 0 块）
        //    ⇒ 说明 C9d 那个「A 进 A 模块 / B 进 B 模块」的分布确实是【类型】决定的，不是遍历顺序的巧合
        final FakeTarget looseA = new FakeTarget("W1", 10, "A", "B", "C");
        final FakeTarget looseB = new FakeTarget("W2", 10, "A", "B", "C");
        final List<HostPatternMoverPlacer.Target> looseTargets = List.of(looseA, looseB);
        final HostPatternMoverPlacer.Result loose = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(units, looseTargets), units, looseTargets);
        cases.add(new Case("C9NEG 不分类型时会被第一台全吃掉（负对照必须红）",
                looseA.nonEmpty() == 5 && looseB.nonEmpty() == 0 && loose.placed() == 5,
                "第一台 5 块、第二台 0 块（= 说明 C9d 的分布由类型决定）",
                "W1=" + looseA.nonEmpty() + " W2=" + looseB.nonEmpty() + " placed=" + loose.placed()));
    }

    // ================================================================ 10 · 无对应模块（留在物品里）

    private static void c10NoModule(List<Case> cases) {
        final FakeTarget a = new FakeTarget("A", 4, "A");
        final List<HostPatternMoverPlacer.Unit> units = List.of(
                new FakeUnit("recipe:a1", "A", false),
                new FakeUnit("recipe:d1", "D", false));     // ← 谁都不支持 D
        final List<HostPatternMoverPlacer.Target> targets = List.of(a);
        final HostPatternMoverPlacer.Plan plan = HostPatternMoverPlacer.plan(units, targets);
        final HostPatternMoverPlacer.Result r = HostPatternMoverPlacer.apply(plan, units, targets);
        cases.add(new Case("C10a 没有模块支持 D ⇒ skippedNoModule=1",
                r.skippedNoModule() == 1, "skippedNoModule=1", "skippedNoModule=" + r.skippedNoModule()));
        cases.add(new Case("C10b D 留在物品里（unplaced 含它的下标 1）",
                r.unplacedIndices().equals(List.of(1)), "[1]", r.unplacedIndices().toString()));
        cases.add(new Case("C10c D 没有被写进任何槽", a.nonEmpty() == 1, "只有 a1 进槽（1 块）",
                "槽里 " + a.nonEmpty() + " 块"));
        // ── 负对照：把 D 的 type 改成 A ⇒ 同一份数据立刻放得进去（说明 C10a 的 1 是"没人支持"造成的，
        //    不是"第二块永远放不进"）
        final List<HostPatternMoverPlacer.Unit> same = List.of(
                new FakeUnit("recipe:a1", "A", false),
                new FakeUnit("recipe:d1", "A", false));
        final FakeTarget a2 = new FakeTarget("A2", 4, "A");
        final HostPatternMoverPlacer.Result r2 = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(same, List.of(a2)), same, List.of(a2));
        cases.add(new Case("C10NEG 把类型改成受支持的 ⇒ 立刻放得进（负对照必须红）",
                r2.placed() == 2 && r2.skippedNoModule() == 0,
                "placed=2 skippedNoModule=0", "placed=" + r2.placed() + " noModule=" + r2.skippedNoModule()));
    }

    // ================================================================ 11 · 覆盖（原地、同 id）

    private static void c11Replace(List<Case> cases) {
        final FakeTarget a = new FakeTarget("A", 8, "A");
        a.put(0, "recipe:other");
        a.put(4, "recipe:same");
        final int before = a.nonEmpty();
        final List<HostPatternMoverPlacer.Unit> units = List.of(new FakeUnit("recipe:same", "A", false));
        final List<HostPatternMoverPlacer.Target> targets = List.of(a);
        final HostPatternMoverPlacer.Plan plan = HostPatternMoverPlacer.plan(units, targets);
        final HostPatternMoverPlacer.Result r = HostPatternMoverPlacer.apply(plan, units, targets);
        cases.add(new Case("C11a 同 id ⇒ 原地覆盖（槽号不变 = 4）",
                plan.writes().size() == 1 && plan.writes().get(0).slot() == 4,
                "slot=4", plan.writes().isEmpty() ? "没写" : "slot=" + plan.writes().get(0).slot()));
        cases.add(new Case("C11b replaced=1 且不是「新增」", r.replaced() == 1 && r.added() == 0,
                "replaced=1 added=0", "replaced=" + r.replaced() + " added=" + r.added()));
        cases.add(new Case("C11c 总块数不变（覆盖不增块）", a.nonEmpty() == before,
                "前后都是 " + before, "前 " + before + " 后 " + a.nonEmpty()));
        cases.add(new Case("C11d 绝不覆盖不同配方的样板（槽 0 还是 recipe:other）",
                "recipe:other".equals(a.slots[0]), "recipe:other", String.valueOf(a.slots[0])));
        // ── 负对照：把去重键改一个字符 ⇒ 不再匹配 ⇒ 它只能进空槽（块数 +1），
        //    说明 C11a 的"原地"确实由「同 id」决定
        final FakeTarget b = new FakeTarget("B", 8, "A");
        b.put(0, "recipe:other");
        b.put(4, "recipe:same");
        final int beforeB = b.nonEmpty();
        final List<HostPatternMoverPlacer.Unit> diff = List.of(new FakeUnit("recipe:sameX", "A", false));
        final HostPatternMoverPlacer.Result rb = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(diff, List.of(b)), diff, List.of(b));
        cases.add(new Case("C11NEG 键差一个字符就不再覆盖（负对照必须红）",
                rb.added() == 1 && rb.replaced() == 0 && b.nonEmpty() == beforeB + 1,
                "新增 1 块、总块数 +1（= 覆盖只认同一个键）",
                "added=" + rb.added() + " replaced=" + rb.replaced()
                        + " 块数 " + beforeB + "→" + b.nonEmpty()));
    }

    // ================================================================ 12 · 槽校验（分区）

    private static void c12ZoneGate(List<Case> cases) {
        // 分区总成：0..3 普通、4..5 通配符
        final FakeTarget zoned = new FakeTarget("Z", 6, "A").zoned(4, 2);
        final List<HostPatternMoverPlacer.Unit> wu = List.of(new FakeUnit("recipe:w", "A", true));
        final HostPatternMoverPlacer.Plan plan = HostPatternMoverPlacer.plan(wu, List.of(zoned));
        cases.add(new Case("C12a 通配符样板只落通配符槽（>=4）",
                plan.writes().size() == 1 && plan.writes().get(0).slot() >= 4,
                "slot>=4", plan.writes().isEmpty() ? "没写" : "slot=" + plan.writes().get(0).slot()));
        // 普通样板同理：只落 0..3
        final FakeTarget zoned2 = new FakeTarget("Z2", 6, "A").zoned(4, 2);
        for (int i = 0; i < 4; i++) {
            zoned2.put(i, "recipe:fill" + i);
        }
        final List<HostPatternMoverPlacer.Unit> nu = List.of(new FakeUnit("recipe:n", "A", false));
        final HostPatternMoverPlacer.Plan plan2 = HostPatternMoverPlacer.plan(nu, List.of(zoned2));
        cases.add(new Case("C12b 普通样板不许进通配符槽（普通槽满 ⇒ 不放入）",
                plan2.plannedWrite() == 0
                        && plan2.count(HostPatternMoverPlacer.Decision.SKIP_NO_SLOT) == 1,
                "skippedNoSlot=1", "put=" + plan2.plannedWrite() + " noSlot="
                        + plan2.count(HostPatternMoverPlacer.Decision.SKIP_NO_SLOT)));
        // 通配符槽满了 ⇒ 通配符样板也不许借用普通槽（这就是用户点名的"指向普通槽 ⇒ 拒绝"）
        final FakeTarget full = new FakeTarget("ZF", 6, "A").zoned(4, 2);
        full.put(4, "recipe:x1");
        full.put(5, "recipe:x2");
        final HostPatternMoverPlacer.Result rf = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(wu, List.of(full)), wu, List.of(full));
        cases.add(new Case("C12c 通配符槽满 ⇒ 计入 skippedNoSlot（绝不借用普通槽）",
                rf.placed() == 0 && rf.skippedNoSlot() == 1 && full.slots[0] == null,
                "placed=0 noSlot=1 槽0 仍空", "placed=" + rf.placed() + " noSlot=" + rf.skippedNoSlot()
                        + " 槽0=" + String.valueOf(full.slots[0])));
        // ── 负对照：把分区判据关掉（zoned=false）⇒ 通配符样板**真的**会写进普通槽
        final FakeTarget unzoned = new FakeTarget("U", 6, "A");
        final HostPatternMoverPlacer.Result ru = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(wu, List.of(unzoned)), wu, List.of(unzoned));
        cases.add(new Case("C12NEG 关掉分区判据 ⇒ 通配符样板写进普通槽（负对照必须红）",
                ru.placed() == 1 && unzoned.slots[0] != null,
                "写进槽 0（= 说明 C12a/C12c 的绿确实是那层判据拦住的）",
                "placed=" + ru.placed() + " 槽0=" + String.valueOf(unzoned.slots[0])));
    }

    // ================================================================ 13 · 半途失败（第 3 台满）

    private static void c13PartialFanout(List<Case> cases) {
        final FakeTarget t1 = new FakeTarget("T1", 2, "A");
        final FakeTarget t2 = new FakeTarget("T2", 2, "A");
        final FakeTarget t3 = new FakeTarget("T3", 2, "A");
        t3.put(0, "recipe:keep0");
        t3.put(1, "recipe:keep1");
        final String fp3 = t3.fingerprint();
        final List<HostPatternMoverPlacer.Unit> units = List.of(
                new FakeUnit("recipe:p0", "A", false),
                new FakeUnit("recipe:p1", "A", false),
                new FakeUnit("recipe:p2", "A", false),
                new FakeUnit("recipe:p3", "A", false),
                new FakeUnit("recipe:p4", "A", false));      // ← 第 5 块：T1/T2/T3 都满了
        final List<HostPatternMoverPlacer.Target> targets = List.of(t1, t2, t3);
        final HostPatternMoverPlacer.Result r = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(units, targets), units, targets);
        cases.add(new Case("C13a 前 2 台写满（各 2 块）", t1.nonEmpty() == 2 && t2.nonEmpty() == 2,
                "T1=2 T2=2", "T1=" + t1.nonEmpty() + " T2=" + t2.nonEmpty()));
        cases.add(new Case("C13b 第 3 台满 ⇒ 一格没动、指纹不变", fp3.equals(t3.fingerprint()),
                "指纹相同", "相同=" + fp3.equals(t3.fingerprint())));
        cases.add(new Case("C13c 第 5 块计入 skippedNoSlot、留在物品里",
                r.skippedNoSlot() == 1 && r.unplacedIndices().equals(List.of(4)),
                "noSlot=1 unplaced=[4]",
                "noSlot=" + r.skippedNoSlot() + " unplaced=" + r.unplacedIndices()));
        cases.add(new Case("C13d 整批不中断（placed=4 = 前两台容量）", r.placed() == 4,
                "placed=4", "placed=" + r.placed()));
        // ── 负对照：把 T3 的两格清空 ⇒ 第 5 块立刻放得下（说明 C13c 的 1 是"真没空槽"造成的）
        final FakeTarget t3b = new FakeTarget("T3b", 2, "A");
        final List<HostPatternMoverPlacer.Unit> one = List.of(new FakeUnit("recipe:p4", "A", false));
        final HostPatternMoverPlacer.Result rb = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(one, List.of(t3b)), one, List.of(t3b));
        cases.add(new Case("C13NEG 有空槽时同一块放得进（负对照必须红）",
                rb.placed() == 1 && rb.skippedNoSlot() == 0,
                "placed=1 noSlot=0", "placed=" + rb.placed() + " noSlot=" + rb.skippedNoSlot()));
    }

    // ================================================================ 14 · 取消（不写一个字节）

    private static void c14Cancel(List<Case> cases) {
        final FakeTarget a = new FakeTarget("A", 4, "A");
        a.put(0, "recipe:old");
        final FakeTarget b = new FakeTarget("B", 4, "A");
        final String before = a.fingerprint() + "|" + b.fingerprint();
        final List<HostPatternMoverPlacer.Unit> units = List.of(
                new FakeUnit("recipe:old", "A", false),
                new FakeUnit("recipe:new", "A", false));
        final List<HostPatternMoverPlacer.Target> targets = List.of(a, b);
        // 取消 = 只算计划、不调 apply（确认面板的「取消」走的就是这条路）
        final HostPatternMoverPlacer.Plan plan = HostPatternMoverPlacer.plan(units, targets);
        final String afterPlan = a.fingerprint() + "|" + b.fingerprint();
        cases.add(new Case("C14a 只算计划（= 取消）⇒ 目标指纹前后完全相同",
                before.equals(afterPlan) && plan.plannedWrite() == 2,
                "指纹相同，且计划里有 2 条写入（说明「算过但没写」）",
                "相同=" + before.equals(afterPlan) + " plan=" + plan.plannedWrite()));
        cases.add(new Case("C14b 机器上一格都没被写（writeCount=0）",
                a.writeCount == 0 && b.writeCount == 0, "0/0",
                a.writeCount + "/" + b.writeCount));
        // ── 负对照：同一份计划真执行 ⇒ 指纹必须变（否则 C14a 的"没变"没有意义）
        final HostPatternMoverPlacer.Result r = HostPatternMoverPlacer.apply(plan, units, targets);
        final String afterApply = a.fingerprint() + "|" + b.fingerprint();
        cases.add(new Case("C14NEG 真执行后指纹必须变（负对照必须红）",
                !before.equals(afterApply) && r.placed() == 2,
                "指纹不同且 placed=2", "不同=" + !before.equals(afterApply) + " placed=" + r.placed()));
    }

    // ================================================================ 15 · onPatternChange 次数 == 写入块数

    private static void c15NotifyPerWrite(List<Case> cases) {
        final FakeTarget a = new FakeTarget("A", 3, "A");
        final FakeTarget b = new FakeTarget("B", 3, "A");
        final List<HostPatternMoverPlacer.Unit> units = List.of(
                new FakeUnit("recipe:1", "A", false),
                new FakeUnit("recipe:2", "A", false),
                new FakeUnit("recipe:3", "A", false),
                new FakeUnit("recipe:4", "A", false));
        final List<HostPatternMoverPlacer.Target> targets = List.of(a, b);
        final HostPatternMoverPlacer.Result r = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(units, targets), units, targets);
        final int writes = a.writeCount + b.writeCount;
        final int notifies = a.notifyCount + b.notifyCount;
        cases.add(new Case("C15a 通知次数 == 实际写入块数 == placed",
                notifies == writes && writes == r.placed(),
                "三者相等", "notify=" + notifies + " write=" + writes + " placed=" + r.placed()));
        // ── 负对照：写了但不通知（= 漏调 onPatternChange 的坏实现）⇒ 次数对不上，必须被抓出来
        final FakeTarget bad = new FakeTarget("BAD", 3, "A").skipNotify();
        final List<HostPatternMoverPlacer.Unit> one = List.of(new FakeUnit("recipe:9", "A", false));
        HostPatternMoverPlacer.apply(HostPatternMoverPlacer.plan(one, List.of(bad)), one, List.of(bad));
        cases.add(new Case("C15NEG 漏通知的实现会被抓出来（负对照必须红）",
                bad.writeCount == 1 && bad.notifyCount == 0,
                "write=1 notify=0（= 与 C15a 的判据不一致）",
                "write=" + bad.writeCount + " notify=" + bad.notifyCount));
    }

    // ================================================================ 16 · 取出 = 交给玩家（不销毁）

    /**
     * 第 16 组：**取出 = 把那一组样板作为物品交给玩家**（用户 2026-11-06 改的口径）。
     *
     * <p>两条判据（{@link HostPatternMoverTakeout.Result#balanced()} / {@link
     * HostPatternMoverTakeout.Result#conserved()}）＋ 两条读数（背包几块 / 掉落几块）。
     * <p>现场：5 块 A ＋ 1 块 B；玩家侧"背包只能收 2 块、掉落只能成功 2 次"⇒ 第 5 块两条路都不通，
     * 必须<b>留在物品里</b>（不许静默丢）。
     */
    private static void c16TakeOut(List<Case> cases) {
        final List<HostPatternMoverPlacer.Unit> units = List.of(
                new FakeUnit("recipe:a0", "A", false),
                new FakeUnit("recipe:a1", "A", false),
                new FakeUnit("recipe:a2", "A", false),
                new FakeUnit("recipe:a3", "A", false),
                new FakeUnit("recipe:a4", "A", false),
                new FakeUnit("recipe:b0", "B", false));
        final FakeHandout hand = new FakeHandout(2, 2);
        final HostPatternMoverTakeout.Result r = HostPatternMoverTakeout.run(units, "A", hand);
        cases.add(new Case("C16a 背包/掉落收到的块数 == 交出去的块数",
                r.toInventory() + r.dropped() == r.removed() && r.balanced(),
                "inventory+dropped == removed",
                "inv=" + r.toInventory() + " drop=" + r.dropped() + " removed=" + r.removed()));
        cases.add(new Case("C16b 物品里少了同样多（交出去 4 / 这一组 5 / 留 1）",
                r.conserved() && r.removed() == 4 && r.kept() == 1
                        && r.keptIndices().equals(List.of(4)),
                "removed=4 kept=[4] wanted=5",
                "removed=" + r.removed() + " kept=" + r.keptIndices() + " wanted=" + r.wanted()));
        cases.add(new Case("C16c 背包 2 块、掉落 2 块（分开可读）",
                r.toInventory() == 2 && r.dropped() == 2,
                "inv=2 drop=2", "inv=" + r.toInventory() + " drop=" + r.dropped()));
        cases.add(new Case("C16d 别的组一块都没碰（只交了 A 的 4 块）",
                !hand.touched.contains(Integer.valueOf(5)) && hand.wantedTypesOnly,
                "touched 里没有下标 5（B 组）", "touched=" + hand.touched));
        // 全部给得下 ⇒ 物品里一块不剩
        final HostPatternMoverTakeout.Result all =
                HostPatternMoverTakeout.run(units, "A", new FakeHandout(99, 99));
        cases.add(new Case("C16e 全给得下 ⇒ kept=0、removed=5",
                all.kept() == 0 && all.removed() == 5 && all.balanced() && all.conserved(),
                "kept=0 removed=5",
                "kept=" + all.kept() + " removed=" + all.removed()));
        // 一条路都不通（背包满、掉落也失败）⇒ 一块都不许"消失"
        final HostPatternMoverTakeout.Result none =
                HostPatternMoverTakeout.run(units, "A", new FakeHandout(0, 0));
        cases.add(new Case("C16f 两条路都不通 ⇒ removed=0、5 块全留在物品里",
                none.removed() == 0 && none.kept() == 5 && none.conserved(),
                "removed=0 kept=5", "removed=" + none.removed() + " kept=" + none.kept()));
        // ── 负对照：**旧的销毁语义**（说"取走 5 块"、实际只交了 2 块）⇒ 两条判据都必须不成立
        final HostPatternMoverTakeout.Result destroyLike =
                new HostPatternMoverTakeout.Result(5, 5, 2, 0, List.of());
        cases.add(new Case("C16NEG 旧的销毁语义会被两条判据抓出来（负对照必须红）",
                !destroyLike.balanced() || !destroyLike.conserved(),
                "balanced=false（5 ≠ 2+0）…这说明判据真的能看出「删了但没给」",
                "balanced=" + destroyLike.balanced() + " conserved=" + destroyLike.conserved()
                        + " [" + destroyLike.describe() + "]"));
    }

    // ================================================================ 第三刀的 7 组（A / B / C / D / E / G）

    /**
     * <b>C17 · A：通配符样板的类型自动识别</b>（3 ＋ 1 块）。
     *
     * <pre>
     *   ① 老主机，它所属模块的有效类型恰好 1 个          ⇒ 写进 type
     *   ② 新主机，星律样板总成声明 2 个                  ⇒ 不写（多义未写）
     *   ③ 新主机，星律什么都没有 ⇒ 回落模块的 2 个类型   ⇒ 不写（多义未写）
     *   ④ 老主机，模块一个类型都没有                     ⇒ 不写（识别不到）
     * </pre>
     * 再把这三块真走一遍放置：只有第 ① 块能进通配符母槽（星律 1080..1129），另两块被
     * {@code SKIP_UNKNOWN_TYPE}。
     */
    private static void c17WildcardType(List<Case> cases, List<String> notes) {
        final List<String> one = List.of("gtceu:assembler");
        final List<String> two = List.of("gtceu:assembler", "gtceu:centrifuge");
        final List<String> stellarTwo = List.of("gtceu:electrolyzer", "gtceu:extractor");
        final HostPatternMoverWildcard.Origin oldHost = HostPatternMoverWildcard.Origin.OLD_HOST;
        final HostPatternMoverWildcard.Origin newHost = HostPatternMoverWildcard.Origin.NEW_HOST;

        final List<HostPatternMoverWildcard.Item> items = List.of(
                HostPatternMoverWildcard.Item.wildcard(oldHost, one, List.of()),
                HostPatternMoverWildcard.Item.wildcard(newHost, two, stellarTwo),
                HostPatternMoverWildcard.Item.wildcard(newHost, two, List.of()),
                HostPatternMoverWildcard.Item.wildcard(oldHost, List.of(), List.of()));
        final HostPatternMoverWildcard.Batch batch = HostPatternMoverWildcard.resolveAll(items);

        cases.add(new Case("C17a 只有第 1 块被写入类型",
                "gtceu:assembler".equals(batch.typeAt(0)),
                "gtceu:assembler", "'" + batch.typeAt(0) + "'"));
        cases.add(new Case("C17b 第 2、3 块（来源 ≥2 个类型）type 为空",
                batch.typeAt(1).isEmpty() && batch.typeAt(2).isEmpty(),
                "两块都为空", "['" + batch.typeAt(1) + "', '" + batch.typeAt(2) + "']"));
        cases.add(new Case("C17c 多义未写计数 = 2",
                batch.tally().ambiguous() == 2,
                "ambiguous=2", batch.tally().describe()));
        cases.add(new Case("C17d 写入 1 块 / 识别不到 1 块（三项互斥）",
                batch.tally().resolved() == 1 && batch.tally().unidentified() == 1
                        && batch.at(3).verdict() == HostPatternMoverWildcard.Verdict.NOT_IDENTIFIED,
                "resolved=1 unidentified=1", batch.describe()));

        // ── 放置：第 1 块进通配符母槽（1080..1129），另两块被 SKIP_UNKNOWN_TYPE
        final FakeTarget mother = new FakeTarget("星律样板总成", 1130, "gtceu:assembler").zoned(1080, 50);
        final List<HostPatternMoverPlacer.Unit> units = List.of(
                new FakeUnit("recipe:w0", batch.typeAt(0), true),
                new FakeUnit("recipe:w1", batch.typeAt(1), true),
                new FakeUnit("recipe:w2", batch.typeAt(2), true),
                new FakeUnit("recipe:w3", batch.typeAt(3), true));
        final HostPatternMoverPlacer.Plan plan = HostPatternMoverPlacer.plan(units, List.of(mother));
        final int slot = plan.writes().isEmpty() ? -1 : plan.writes().get(0).slot();
        cases.add(new Case("C17e 第 1 块落进通配符母槽 [1080,1130)",
                slot >= 1080 && slot < 1130, "1080 ≤ slot < 1130", "slot=" + slot));
        cases.add(new Case("C17f 另三块全部 SKIP_UNKNOWN_TYPE",
                plan.count(HostPatternMoverPlacer.Decision.SKIP_UNKNOWN_TYPE) == 3
                        && plan.plannedWrite() == 1,
                "skipUnknown=3 put=1", plan.describe()));
        notes.add("C17 通配符识别：" + batch.describe() + " | 落槽 " + plan.describe());
    }

    /**
     * <b>C18 · 多义负对照</b>：把「≥2 个不写」改成「取第一个」⇒ 这一条必须红。
     *
     * <p>判据写在"取第一个的那个值"上：真实现给空串，而"取第一个"会给
     * {@code candidates.get(0)}（非空）⇒ 任何"要求空串"的断言立刻不成立。
     */
    private static void c18WildcardNoGuess(List<Case> cases) {
        final List<String> stellarTwo = List.of("gtceu:electrolyzer", "gtceu:extractor");
        final HostPatternMoverWildcard.Resolution r = HostPatternMoverWildcard.resolve(
                true, "", HostPatternMoverWildcard.Origin.NEW_HOST, List.of(), stellarTwo);
        final String takeFirst = r.candidates().isEmpty() ? "" : r.candidates().get(0);
        cases.add(new Case("C18a 多义 ⇒ type 为空且标 AMBIGUOUS",
                r.type().isEmpty() && r.ambiguous(),
                "type='' verdict=AMBIGUOUS", "type='" + r.type() + "' verdict=" + r.verdict()));
        cases.add(new Case("C18NEG 若改成「取第一个」，type 就不再是空串 ⇒ 必须红",
                !r.type().isEmpty() || !takeFirst.isEmpty(),
                "取第一个会给 '" + takeFirst + "'（与空串不同 ⇒ C18a 必然红）",
                "真实现='" + r.type() + "' 取第一个='" + takeFirst + "'"));
    }

    /** <b>C19 · B：分页</b>（17 组 ⇒ 3 页 8/8/1；剩 10 组 ⇒ 2 页且当前页夹紧）。 */
    private static void c19Pager(List<Case> cases, List<String> notes) {
        final int g17 = 17;
        cases.add(new Case("C19a 17 组 ⇒ 3 页", HostPatternMoverPager.pages(g17) == 3,
                "pages=3", "pages=" + HostPatternMoverPager.pages(g17)));
        final String shape = HostPatternMoverPager.rowsOnPage(0, g17) + "/"
                + HostPatternMoverPager.rowsOnPage(1, g17) + "/" + HostPatternMoverPager.rowsOnPage(2, g17);
        cases.add(new Case("C19b 每页行数 = 8/8/1", "8/8/1".equals(shape), "8/8/1", shape));
        final int g10 = 10;
        cases.add(new Case("C19c 剩 10 组 ⇒ 2 页", HostPatternMoverPager.pages(g10) == 2,
                "pages=2", "pages=" + HostPatternMoverPager.pages(g10)));
        cases.add(new Case("C19d 原在第 3 页 ⇒ 夹到最后一页（下标 1）",
                HostPatternMoverPager.clamp(2, g10) == 1,
                "clamp(2,10)=1", "clamp(2,10)=" + HostPatternMoverPager.clamp(2, g10)));
        cases.add(new Case("C19NEG 若不做夹紧，老页号会停在 2（越界）—— 这一条必须红",
                HostPatternMoverPager.clamp(2, g10) != 2,
                "≠2", "=" + HostPatternMoverPager.clamp(2, g10)));
        cases.add(new Case("C19e 末页第 2 行没有组（绝对行号 -1）",
                HostPatternMoverPager.absoluteRow(2, 0, g17) == 16
                        && HostPatternMoverPager.absoluteRow(2, 1, g17) == -1,
                "row(2,0)=16 row(2,1)=-1",
                "row(2,0)=" + HostPatternMoverPager.absoluteRow(2, 0, g17)
                        + " row(2,1)=" + HostPatternMoverPager.absoluteRow(2, 1, g17)));
        notes.add("C19 分页：" + HostPatternMoverPager.describe(2, g17) + " ⇒ 取出后 "
                + HostPatternMoverPager.describe(2, g10));
    }

    /**
     * <b>C20 · D：两个功能真删干净了吗</b>。
     *
     * <p>正向：包内源码 ＋ 两份 lang 里那几个词 0 命中（真扫由探针
     * {@code temp/pattern-mover/probe/ThirdCutProbe.java} 做，这里验的是<b>扫描器本身</b>）。
     * 负对照：故意留一条 lang 键 / 一个方法 ⇒ 扫描器必须报出来。
     *
     * <p>⚠️ 负对照的"脏语料"是<b>拼</b>出来的（{@code "put_" + "no_source"} 这种写法）：
     * 否则这份自检自己的源文件里就含那两个词，全包扫描会把它自己报成命中
     * （第一版真撞了：扫出了本文件的名字）。
     */
    private static void c20RemovedFeatures(List<Case> cases) {
        final java.util.Map<String, String> clean = new java.util.LinkedHashMap<>();
        clean.put("A.java", "class A { void x() { takeOut(); clearItem(); } }");
        clean.put("zh_cn.json", "{\"shanhai.mover.read\": \"已读取\"}");
        clean.put(HostPatternMoverRemovedFeatures.SELF_FILE, "// 常量表自己含这些词，必须被排除");
        cases.add(new Case("C20a 干净语料 ⇒ 0 命中（且扫描器排除了它自己）",
                HostPatternMoverRemovedFeatures.scanAll(clean).isEmpty(),
                "hits=0", HostPatternMoverRemovedFeatures.describe(
                        HostPatternMoverRemovedFeatures.scanAll(clean))));

        final java.util.Map<String, String> dirtyLang = new java.util.LinkedHashMap<>();
        dirtyLang.put("zh_cn.json", "{\"shanhai.mover." + "put_" + "no_source\": \"另一只手里没有样板\"}");
        final List<String> hitLang = HostPatternMoverRemovedFeatures.scanAll(dirtyLang);
        cases.add(new Case("C20NEG 故意留一条 lang 键 ⇒ 必须报出来（负对照必须红）",
                !hitLang.isEmpty(), "hits ≥ 1", String.valueOf(hitLang)));

        final java.util.Map<String, String> dirtyCode = new java.util.LinkedHashMap<>();
        dirtyCode.put("X.java", "public static int " + "set" + "Type(ServerPlayer p) { return 0; }");
        final List<String> hitCode = HostPatternMoverRemovedFeatures.scanAll(dirtyCode);
        cases.add(new Case("C20NEG2 故意留一个服务端方法 ⇒ 必须报出来（负对照必须红）",
                !hitCode.isEmpty(), "hits ≥ 1", String.valueOf(hitCode)));

        // ── 假命中的负对照：NBT 的 putInt / offsetType 里含同样的字母序列，整词匹配必须<b>不</b>报
        final java.util.Map<String, String> lookalike = new java.util.LinkedHashMap<>();
        lookalike.put("Y.java", "tag.putInt(\"x\", 1); def.offsetType();");
        cases.add(new Case("C20NEG3 putInt / offsetType 这类同形词不许假命中（整词匹配）",
                HostPatternMoverRemovedFeatures.scanAll(lookalike).isEmpty(),
                "hits=0", String.valueOf(HostPatternMoverRemovedFeatures.scanAll(lookalike))));
    }

    /** <b>C21 · G：清空主机</b>（3 个总成 × 各 2 块 ⇒ cleared=6；取消路径指纹不变；负对照：真清指纹必变）。 */
    private static void c21ClearHost(List<Case> cases, List<String> notes) {
        final List<FakeSlots> targets = List.of(
                new FakeSlots("t1", 5, 0, 1),
                new FakeSlots("t2", 5, 2, 3),
                new FakeSlots("t3", 5, 1, 4));

        // ── 取消路径：只 plan，不 run ⇒ 指纹必须一模一样
        final String fpBefore = HostPatternMoverClear.fingerprint(targets);
        final HostPatternMoverClear.Plan plan = HostPatternMoverClear.plan(targets);
        final String fpAfterPlan = HostPatternMoverClear.fingerprint(targets);
        cases.add(new Case("C21a 二次确认页的预估：3 台总成 / 6 块",
                plan.assemblies() == 3 && plan.occupied() == 6,
                "assemblies=3 occupied=6", plan.describe()));
        cases.add(new Case("C21b 取消路径 ⇒ 指纹逐字符相同（一个字节都没写）",
                fpBefore.equals(fpAfterPlan), "指纹相同",
                fpBefore.equals(fpAfterPlan) ? "相同" : "变了：" + fpAfterPlan));

        // ── 真清
        final HostPatternMoverClear.Result r = HostPatternMoverClear.run(targets);
        final String fpAfterRun = HostPatternMoverClear.fingerprint(targets);
        cases.add(new Case("C21c 真清 ⇒ cleared=6 且完整",
                r.cleared() == 6 && r.complete(),
                "cleared=6 complete=true", r.describe()));
        cases.add(new Case("C21d 清完所有槽都空（指纹 nonEmpty=0）",
                fpAfterRun.endsWith("nonEmpty=0"), "nonEmpty=0", fpAfterRun));
        cases.add(new Case("C21NEG 真清之后指纹必须变（否则 C21b 的绿没意义）",
                !fpBefore.equals(fpAfterRun), "指纹变了",
                fpBefore.equals(fpAfterRun) ? "没变（说明清空没生效）" : "变了"));

        // ── 负对照：只清每台第一格（少清一半）⇒ 读数对不上 6
        final List<FakeSlots> partial = List.of(
                new FakeSlots("t1", 5, 0, 1),
                new FakeSlots("t2", 5, 2, 3),
                new FakeSlots("t3", 5, 1, 4));
        int wrong = 0;
        for (FakeSlots t : partial) {
            for (int i = 0; i < t.size(); i++) {
                if (t.occupied(i)) {
                    t.clear(i);
                    wrong++;
                    break;
                }
            }
        }
        cases.add(new Case("C21NEG2 只清第一格（漏清）⇒ 读数 3 ≠ 6，必须红",
                wrong != 6, "≠6", "少清版本=" + wrong));
        notes.add("C21 清空主机：" + r.describe() + " | 取消路径指纹 " + fpBefore);
    }

    /** <b>C22 · E：有效类型集</b>（老主机模块声明 [A,B] 且选中 A ⇒ 1 块 A 能放、1 块 B 被跳过）。 */
    private static void c22EffectiveTypes(List<Case> cases, List<String> notes) {
        final List<String> declared = List.of("gtceu:assembler", "gtceu:centrifuge");
        final java.util.Set<String> oldHost = HostPatternMoverRecipes.effective(false, declared, "gtceu:assembler");
        final java.util.Set<String> crossType = HostPatternMoverRecipes.effective(true, declared, "gtceu:assembler");
        cases.add(new Case("C22a 老主机：声明 [A,B] 且选中 A ⇒ 有效集只有 {A}",
                oldHost.size() == 1 && oldHost.contains("gtceu:assembler"),
                "[gtceu:assembler]", String.valueOf(oldHost)));
        cases.add(new Case("C22b 跨类型并行主机：有效集 = 声明的全部 {A,B}",
                crossType.size() == 2, "size=2", String.valueOf(crossType)));
        cases.add(new Case("C22c 读不到当前类型且声明 ≥2 ⇒ 空集（不猜）",
                HostPatternMoverRecipes.effective(false, declared, "").isEmpty(),
                "空集", String.valueOf(HostPatternMoverRecipes.effective(false, declared, ""))));
        cases.add(new Case("C22c2 当前类型不在声明表里（dummy/被锁）⇒ 不当成\"唯一支持的类型\"",
                HostPatternMoverRecipes.effective(false, declared, "gtceu:dummy_lock").isEmpty(),
                "空集", String.valueOf(HostPatternMoverRecipes.effective(false, declared, "gtceu:dummy_lock"))));

        // ── 老主机上：A 放得进，B 被 SKIP_NO_MODULE
        final FakeTarget module = new FakeTarget("老主机模块", 10)
                .useTypes(oldHost);
        final List<HostPatternMoverPlacer.Unit> units = List.of(
                new FakeUnit("recipe:a", "gtceu:assembler", false),
                new FakeUnit("recipe:b", "gtceu:centrifuge", false));
        final HostPatternMoverPlacer.Plan plan = HostPatternMoverPlacer.plan(units, List.of(module));
        cases.add(new Case("C22d 老主机：A 能放（1 块）、B 被跳过（无对应模块）",
                plan.plannedWrite() == 1 && plan.count(HostPatternMoverPlacer.Decision.SKIP_NO_MODULE) == 1,
                "put=1 skipNoModule=1", plan.describe()));

        // ── 负对照：改回用「声明的全部」⇒ B 也放得进去（说明 C22d 的 1 是新判据带来的）
        final FakeTarget legacy = new FakeTarget("旧口径(全部声明)", 10, "gtceu:assembler", "gtceu:centrifuge");
        final HostPatternMoverPlacer.Plan planLegacy =
                HostPatternMoverPlacer.plan(units, List.of(legacy));
        cases.add(new Case("C22NEG 用全部声明时 B 也放得进去 ⇒ 2 块（负对照必须红）",
                planLegacy.plannedWrite() == 2,
                "put=2", planLegacy.describe()));
        notes.add("C22 有效类型：" + HostPatternMoverRecipes.describe(false, declared, "gtceu:assembler")
                + " | 老主机 " + plan.describe() + " | 旧口径 " + planLegacy.describe());
    }

    /**
     * <b>C23 · C：动作回执里的读数 == 重读 NBT 的结果</b>。
     *
     * <p>做法：造一份「<b>10 个类型组 × 各 1 块</b>」的物品 NBT（⇒ 2 页）→ 把其中 2 组全取走 →
     * 写回 → 从<b>写回后的物品标签</b>重建 {@link HostPatternMoverPanelState}：
     * 块数 10⇒8、组数 10⇒8、字节变小且等于「真的再序列化一遍」的实数；
     * 页数 2⇒1，原先停在第 2 页会被夹回第 1 页（用户口径：组数变化后页数重算、越界夹到最后一页）。
     * 顺带验「多义未写」是<b>落盘</b>的（面板之后重开也看得到，A 那条要的）。
     */
    private static void c23RefreshReadings(List<Case> cases, List<String> notes) {
        final String fromHost = "guixu_vision_core";
        final List<HostPatternMoverEntry> before = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            before.add(entry("gtceu:type" + i + "/a", "gtceu:type" + i));
        }
        final CompoundTag tagBefore = HostPatternMoverNbt.toTag(before, fromHost, HostPatternMoverData.LIMIT_BYTES);
        if (tagBefore == null) {
            cases.add(new Case("C23 前置：10 块样板能写进标签", false, "非 null", "null"));
            return;
        }
        // 物品根上还挂着上一次读取的三项计数（A：多义未写要能在面板上看到）
        final CompoundTag rootBefore = itemTagWith(tagBefore);
        rootBefore.put(HostPatternMoverNbt.K_READ_STATS, new HostPatternMoverNbt.ReadStats(3, 2, 1).toTag());
        final HostPatternMoverPanelState s0 = HostPatternMoverPanelState.of(rootBefore);

        // 取出 2 组（等价于 takeOut 全部成功）⇒ 只剩 8 组
        final List<HostPatternMoverEntry> keep = new ArrayList<>();
        for (HostPatternMoverEntry e : before) {
            if (!"gtceu:type0".equals(e.type()) && !"gtceu:type1".equals(e.type())) {
                keep.add(e);
            }
        }
        final CompoundTag tagAfter = HostPatternMoverNbt.toTag(keep, fromHost, HostPatternMoverData.LIMIT_BYTES);
        final HostPatternMoverPanelState s1 = HostPatternMoverPanelState.of(itemTagWith(tagAfter));
        final int realBytes = HostPatternMoverCodec.realTotalBytes(keep, fromHost);

        cases.add(new Case("C23a 取出两组后：块数 10 ⇒ 8、组数 10 ⇒ 8",
                s0.count() == 10 && s1.count() == 8 && s0.groups() == 10 && s1.groups() == 8,
                "count 10⇒8 groups 10⇒8",
                "count " + s0.count() + "⇒" + s1.count() + " groups " + s0.groups() + "⇒" + s1.groups()));
        cases.add(new Case("C23b 取出后：已用字节变小，且 == 真序列化实数",
                s1.bytes() < s0.bytes() && s1.bytes() == realBytes,
                "bytes 变小 且 == " + realBytes,
                s0.bytes() + " ⇒ " + s1.bytes() + "（实数 " + realBytes + "）"));
        cases.add(new Case("C23c 回执状态包里的读数 == 重读 NBT 的读数（同一口径）",
                s1.count() == HostPatternMoverNbt.countOf(tagAfter)
                        && s1.bytes() == HostPatternMoverNbt.effectiveBytes(tagAfter)
                        && s1.groups() == HostPatternMoverNbt.groupCountsOf(tagAfter).groups(),
                "count/bytes/groups 三项相等",
                "count=" + s1.count() + " bytes=" + s1.bytes() + " groups=" + s1.groups()));
        cases.add(new Case("C23d 页数 2 ⇒ 1，原先第 2 页被夹回第 1 页",
                s0.pages() == 2 && s1.pages() == 1
                        && HostPatternMoverPager.clamp(1, s1.groups()) == 0,
                "pages 2⇒1 clamp(1,8)=0",
                "pages " + s0.pages() + "⇒" + s1.pages()
                        + " clamp=" + HostPatternMoverPager.clamp(1, s1.groups())));
        cases.add(new Case("C23e 「多义未写 2 块」是落盘的（面板重开也看得到）",
                s0.readResolved() == 3 && s0.readAmbiguous() == 2 && s0.readUnidentified() == 1,
                "resolved=3 ambiguous=2 unidentified=1",
                "resolved=" + s0.readResolved() + " ambiguous=" + s0.readAmbiguous()
                        + " unidentified=" + s0.readUnidentified()));
        notes.add("C23 刷新读数：" + s0.describe() + " ⇒ " + s1.describe());
    }

    /**
     * <b>C24 · F：清空物品的数据形态</b>。
     *
     * <p>{@code clearItem} 本身要 {@code ItemStack}（注册表，离线跑不了），但它的<b>数据面</b>
     * 是「空表 ⇒ 一份合法的 {@code shanhai:mover}」——这一条可以纯核验：
     * 写出来的标签非 null、条数 0、字节数等于真的再序列化一遍，且面板重读之后
     * 块数/组数/三项计数全 0（不留旧读数）。
     */
    private static void c24ClearItemData(List<Case> cases, List<String> notes) {
        final String fromHost = "primordial_omega_engine";
        final CompoundTag cleared = HostPatternMoverNbt.toTag(List.of(), fromHost, HostPatternMoverData.LIMIT_BYTES);
        if (cleared == null) {
            cases.add(new Case("C24a 清空后的标签能写出来", false, "非 null", "null"));
            return;
        }
        final int real = HostPatternMoverCodec.realTotalBytes(List.of(), fromHost);
        cases.add(new Case("C24a 清空后：条数 0、字节 == 真序列化实数",
                HostPatternMoverNbt.countOf(cleared) == 0 && HostPatternMoverNbt.effectiveBytes(cleared) == real,
                "count=0 bytes=" + real,
                "count=" + HostPatternMoverNbt.countOf(cleared)
                        + " bytes=" + HostPatternMoverNbt.effectiveBytes(cleared)));
        final HostPatternMoverPanelState empty = HostPatternMoverPanelState.of(itemTagWith(cleared));
        cases.add(new Case("C24b 面板重读：块数/组数/通配符计数全 0（不留旧读数）",
                empty.count() == 0 && empty.groups() == 0 && !empty.hasWildcardReads(),
                "count=0 groups=0 noWildcardReads",
                "count=" + empty.count() + " groups=" + empty.groups()
                        + " wildcard=" + empty.hasWildcardReads()));
        cases.add(new Case("C24c 清空后仍能继续往里放（fromHost 保留）",
                fromHost.equals(HostPatternMoverNbt.readFromHost(cleared)),
                fromHost, HostPatternMoverNbt.readFromHost(cleared)));
        notes.add("C24 清空物品形态：" + empty.describe());
    }

    /**
     * 机器侧读写「通配符配方类型」的那个 NBT 键（{@code gt_shanhai_recipe_type}）。
     *
     * <p>🔴 <b>引用</b>机器侧的常量而不是抄一个字面量：抄一份就会出现"自检过了、机器读的是另一个键"
     * 这种假绿。那两处都是 {@code static final String} 的编译期常量 ⇒ javac 会把它<b>内联</b>进本类，
     * 离线跑时<b>不会</b>去加载引 GT 的 {@code ShanhaiRecipeTypeIds}（这一点由本组断言自己证明：
     * 只要 C27 能跑出读数，就说明没有任何运行时类加载失败）。
     */
    private static final String STAMP_KEY = com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.KEY;

    /**
     * <b>C25 · 第四刀 ①：批量写入期间只置脏、收尾重建一次</b>。
     *
     * <p>跑的是<b>生产同一份代码</b>（{@link HostPatternMoverBatch}）＋ 一个与机器侧同构的假机器
     * （{@link FakeMachine#onPatternChange} 就是 {@code StellarSampleAssemblerPartMachine
     * #shanhaiBuildPatternTablesIfDirty} 那三行的模型：在批量里 ⇒ 只置脏；否则当场建）。
     *
     * <table border="1">
     *   <tr><th>#</th><th>验什么</th><th>判据</th></tr>
     *   <tr><td>a</td><td>1168 次 {@code onPatternChange} 在批量里</td><td>{@code writes=1168 rebuilds=1}</td></tr>
     *   <tr><td>aNEG</td><td><b>负对照</b>：同样 1168 次，<b>去掉批量</b></td><td>重建 = <b>1168</b>（必须 ≠ 1 ⇒ 证明上面那个 1 不是常数）</td></tr>
     *   <tr><td>b</td><td>批量外单次写</td><td>每次写当场重建（3 次写 ⇒ 3 次重建，与改动前逐位相同）</td></tr>
     *   <tr><td>c</td><td>可重入</td><td>嵌套 begin：内层 end 返回 null，只有最外层收尾 ⇒ 重建 1 次</td></tr>
     *   <tr><td>d</td><td>异常安全</td><td>批量中途抛 ⇒ finally 里 end，重建 1 次且 {@code active()=false}</td></tr>
     *   <tr><td>e</td><td>不平衡的 end</td><td>没 begin 就 end ⇒ null、不重建、不产生负深度</td></tr>
     *   <tr><td>fNEG</td><td><b>负对照</b>：把 suppress 写成"永远抑制"</td><td>批量外那次写会 0 重建 ⇒ 判据必须红</td></tr>
     * </table>
     */
    private static void c25BatchWrite(List<Case> cases, List<String> notes) {
        HostPatternMoverBatch.resetForProbe(); // 从干净线程态开始（上一组不许漏进来）

        final int n = 1168;
        final FakeMachine m1 = new FakeMachine("m1");
        HostPatternMoverBatch.begin();
        for (int i = 0; i < n; i++) {
            m1.onPatternChange("patternSlot:" + i);
        }
        final HostPatternMoverBatch.Report r1 = HostPatternMoverBatch.end();
        cases.add(new Case("C25a 批量内 " + n + " 次写 ⇒ 只重建 1 次",
                r1 != null && r1.rebuilds() == 1 && r1.writes() == n && m1.rebuilds == 1,
                "writes=" + n + " rebuilds=1", r1 == null ? "null" : r1.describe() + " machine=" + m1.rebuilds));
        cases.add(new Case("C25a2 收尾后批量态清零（不许永久卡住）",
                !HostPatternMoverBatch.active() && HostPatternMoverBatch.depth() == 0,
                "active=false depth=0", HostPatternMoverBatch.describe()));

        // ── 负对照：同样的 1168 次，去掉批量 ⇒ 1168 次重建（必须 ≠ 1）
        final FakeMachine m2 = new FakeMachine("m2");
        for (int i = 0; i < n; i++) {
            m2.onPatternChange("patternSlot:" + i);
        }
        cases.add(new Case("C25aNEG 负对照：去掉批量 ⇒ 重建 " + n + " 次（必须 ≠ 1，否则 C25a 的 1 没意义）",
                m2.rebuilds == n && m2.rebuilds != 1,
                "rebuilds=" + n, "rebuilds=" + m2.rebuilds));
        notes.add("C25 批量：" + (r1 == null ? "null" : r1.describe())
                + " ｜ 去掉批量：rebuilds=" + m2.rebuilds);

        // ── 批量外单次写：行为必须与改动前逐位相同（一次写 = 一次重建）
        final FakeMachine m3 = new FakeMachine("m3");
        for (int i = 0; i < 3; i++) {
            m3.onPatternChange("patternSlot:" + i);
        }
        cases.add(new Case("C25b 批量外 3 次写 ⇒ 3 次重建（逐位 = 改动前）",
                m3.rebuilds == 3 && m3.dirtyRebuilds == 3, "rebuilds=3",
                "rebuilds=" + m3.rebuilds + " dirtyRebuilds=" + m3.dirtyRebuilds));

        // ── 可重入：内层 end 什么都不做
        final FakeMachine m4 = new FakeMachine("m4");
        HostPatternMoverBatch.begin();
        HostPatternMoverBatch.begin();
        m4.onPatternChange("outer");
        final HostPatternMoverBatch.Report inner = HostPatternMoverBatch.end();
        m4.onPatternChange("still-batched");
        final boolean stillActive = HostPatternMoverBatch.active();
        final HostPatternMoverBatch.Report outer = HostPatternMoverBatch.end();
        cases.add(new Case("C25c 可重入：内层 end 返回 null、批量仍生效；最外层才建 1 次",
                inner == null && stillActive && outer != null && outer.rebuilds() == 1
                        && outer.writes() == 2 && m4.rebuilds == 1,
                "inner=null outer.rebuilds=1 writes=2",
                "inner=" + inner + " activeAfterInner=" + stillActive
                        + " outer=" + (outer == null ? "null" : outer.describe())
                        + " machine=" + m4.rebuilds));

        // ── 异常安全：try/finally 照常收尾
        final FakeMachine m5 = new FakeMachine("m5");
        boolean threw = false;
        HostPatternMoverBatch.begin();
        try {
            m5.onPatternChange("mid");
            throw new IllegalStateException("模拟批量中途抛异常");
        } catch (IllegalStateException expected) {
            threw = true;
        } finally {
            HostPatternMoverBatch.end(); // ← HostPatternMoverTargets#inBatchWrite 的 finally 同款
        }
        cases.add(new Case("C25d 批量中途抛异常 ⇒ finally 里仍收尾：重建 1 次且批量态清零",
                threw && m5.rebuilds == 1 && !HostPatternMoverBatch.active(),
                "rebuilds=1 active=false",
                "threw=" + threw + " rebuilds=" + m5.rebuilds
                        + " active=" + HostPatternMoverBatch.active()));

        // ── 不平衡的 end：没有 begin 就 end ⇒ 什么都不做
        final HostPatternMoverBatch.Report stray = HostPatternMoverBatch.end();
        cases.add(new Case("C25e 没 begin 就 end ⇒ 返回 null、不重建、不产生负深度",
                stray == null && HostPatternMoverBatch.depth() == 0,
                "null depth=0", String.valueOf(stray) + " depth=" + HostPatternMoverBatch.depth()));

        // ── 负对照：把「抑制」写成永远为真 ⇒ 批量外那次写就不会重建（必须被抓住）
        final FakeMachineAlwaysSuppressed bad = new FakeMachineAlwaysSuppressed("bad");
        bad.onPatternChange("outside");
        cases.add(new Case("C25fNEG 负对照：抑制点写成'永远抑制' ⇒ 批量外 0 次重建，判据必须红",
                bad.rebuilds == 0 && bad.rebuilds != 1, "0（= 坏的）必须 ≠ 1", "rebuilds=" + bad.rebuilds));
        HostPatternMoverBatch.resetForProbe();
    }

    /**
     * <b>C26 · 第四刀 ③：面板分组列表的「行计划」重建</b>。
     *
     * <p>用户实测：「页面点击取出后仍然没有实时刷新（分组没消失），点击清空也是同样的情况」。
     * 判据全部打在纯核 {@link HostPatternMoverPager#view}（面板的 {@code rebuildRows()} 就是用它）：
     * <pre>
     *   3 组 → 取出 1 组 ⇒ 行数 2
     *   再清空        ⇒ 行数 0 且 empty=true（面板显示空提示行）
     *   11 组停在第 2 页 → 取出 6 组 ⇒ 页号夹到第 1 页（5 组只剩 1 页）
     * </pre>
     * 负对照：不夹页号 ⇒ 第 2 页上 5 行全部"不存在"（{@code absoluteRow=-1}）⇒ 界面会空白而组还在。
     */
    private static void c26PanelRows(List<Case> cases, List<String> notes) {
        final HostPatternMoverPager.View v3 = HostPatternMoverPager.view(0, 3);
        cases.add(new Case("C26a 3 组 ⇒ 3 行（第 1 页）",
                v3.rows() == 3 && !v3.empty() && v3.absoluteRow(0) == 0 && v3.absoluteRow(2) == 2,
                "rows=3 startRow=0", v3.describe()));

        // 取出一组 ⇒ 组数 3⇒2（页号仍 0）
        final int afterTake = 2;
        final HostPatternMoverPager.View v2 = HostPatternMoverPager.view(v3.page(), afterTake);
        cases.add(new Case("C26b ★判据：3 组取出一组 ⇒ 重建后行数 = 2（第 3 行连按钮一起消失）",
                v2.rows() == 2 && liveRows(v2) == 2 && v2.absoluteRow(2) < 0,
                "rows=2 liveRows=2 row2=-1", v2.describe() + " liveRows=" + liveRows(v2)
                        + " row2=" + v2.absoluteRow(2)));

        // 清空 ⇒ 0 组
        final HostPatternMoverPager.View v0 = HostPatternMoverPager.view(v2.page(), 0);
        cases.add(new Case("C26c ★判据：清空 ⇒ 行数 0 且显示空提示（empty=true）",
                v0.rows() == 0 && v0.empty() && liveRows(v0) == 0 && v0.pages() == 1,
                "rows=0 empty=true pages=1", v0.describe() + " liveRows=" + liveRows(v0)));

        // 11 组停在第 2 页（下标 1），取出 6 组 ⇒ 5 组
        final int g11 = 11;
        final HostPatternMoverPager.View p11 = HostPatternMoverPager.view(1, g11);
        final int afterTake6 = 5;
        final HostPatternMoverPager.View clamped = HostPatternMoverPager.view(1, afterTake6);
        cases.add(new Case("C26d ★判据：11 组在第 2 页取出 6 组 ⇒ 页号夹到第 1 页（下标 0）且 5 行都在",
                p11.page() == 1 && p11.rows() == 3 && clamped.page() == 0
                        && clamped.rows() == 5 && liveRows(clamped) == 5,
                "page 1⇒0 rows=5",
                "before=" + p11.describe() + " after=" + clamped.describe()
                        + " liveRows=" + liveRows(clamped)));

        // 负对照：不夹页号（照着"老页号 × 每页行数"直接算起点）⇒ 5 组在第 2 页上一行都显示不出来
        //        ⇒ 界面空白、组还在（这就是"点了取出之后看不见东西"的形态）—— 必须红。
        final int rawStartRow = 1 * HostPatternMoverPager.ROWS; // 不夹紧的算法：1 × 8 = 8
        final int rawRows = Math.min(HostPatternMoverPager.ROWS, Math.max(0, afterTake6 - rawStartRow));
        cases.add(new Case("C26dNEG 负对照：不夹页号 ⇒ 第 2 页上 5 组一行都显示不出来（必须红）",
                rawRows == 0 && clamped.rows() == 5 && clamped.page() != p11.page(),
                "不夹= 0 行 / 夹紧后 = 5 行",
                "不夹 startRow=" + rawStartRow + " rows=" + rawRows
                        + " / 夹紧 page=" + clamped.page() + " rows=" + clamped.rows()));

        // 反向：这次重建之前的状态不该被"提前"用上（组数没变 ⇒ 行数不该变）
        final HostPatternMoverPager.View same = HostPatternMoverPager.view(0, 3);
        cases.add(new Case("C26e 组数没变 ⇒ 行计划逐字段相同（不会因为重建而少行/多行）",
                same.rows() == v3.rows() && same.startRow() == v3.startRow() && same.pages() == v3.pages(),
                "与 C26a 相同", same.describe()));
        notes.add("C26 行计划：3组 " + v3.describe() + " ⇒ 取出后 " + v2.describe()
                + " ⇒ 清空 " + v0.describe() + " ｜ 11组 " + p11.describe() + " ⇒ " + clamped.describe());
    }

    /** 这份行计划上"真的存在"的行数（= 面板里会被 {@code setVisible(true)} 的行数）。 */
    private static int liveRows(HostPatternMoverPager.View view) {
        int n = 0;
        for (int row = 0; row < HostPatternMoverPager.ROWS; row++) {
            if (view.absoluteRow(row) >= 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * <b>C27 · 第四刀 ②：通配符的类型落到样板自己的 NBT 上</b>。
     *
     * <p>用户原话：「通配符放入星律样板总成不会自动选择要执行的配方类型」。
     * 判据打在纯核 {@link HostPatternMoverWildcard#typeToStamp}（生产写入点
     * {@code HostPatternMoverTargets.InventoryTarget#write} 调的就是它），
     * 再加一条<b>真的过 CompoundTag</b> 的落盘/读回。
     *
     * <p>⚠️ 读回用的是机器侧同一个键常量（{@code ShanhaiWildcardTypeNbt.KEY}，编译期常量 ⇒ 内联，
     * 离线不会去加载那个引 GT 的类）。生产写入点还会过一次
     * {@code ShanhaiRecipeTypeIds#canonical}（源码两行，报告里如实标注为"读源码核过、离线跑不了"）。
     */
    private static void c27WildcardStamp(List<Case> cases, List<String> notes) {
        final String key = STAMP_KEY;
        final int normal = 1080;
        final int wild = 50;
        final String a = "gtceu:assembler";

        final String stamp = HostPatternMoverWildcard.typeToStamp(true, normal, wild, 1080, true, a);
        cases.add(new Case("C27a 母槽 1080 ＋ 通配符 ＋ 识别出 A ⇒ 要写 A",
                a.equals(stamp), a, stamp));

        // ★判据：真的写进 CompoundTag，再读回来 == A
        final CompoundTag tag = new CompoundTag();
        tag.putString("id", "wildcard_pattern:wildcard_pattern");
        tag.putString(key, stamp);
        cases.add(new Case("C27b ★判据：写进样板 NBT 后读回该槽的类型 = A（同一个键）",
                a.equals(tag.getString(key)) && tag.getString(key).equals(HostPatternMoverWildcard
                        .typeToStamp(true, normal, wild, 1129, true, a)),
                a, tag.getString(key)));

        // 多义 / 识别不到 ⇒ 一个字都不写，而且【不许】写空串覆盖（负对照见 C27NEG）
        final CompoundTag untouched = new CompoundTag();
        untouched.putString("id", "wildcard_pattern:wildcard_pattern");
        untouched.putString(key, "gtceu:centrifuge"); // 样板本来就带着一个（"自动识别"不该动它）
        final String ambiguous = HostPatternMoverWildcard.typeToStamp(true, normal, wild, 1081, true, "");
        if (!ambiguous.isEmpty()) {
            untouched.putString(key, ambiguous);
        }
        cases.add(new Case("C27c 多义 / 未识别（type 为空）⇒ 不写，样板原有类型不被覆盖",
                ambiguous.isEmpty() && "gtceu:centrifuge".equals(untouched.getString(key)),
                "不写 & 原值保留", "stamp='" + ambiguous + "' tag=" + untouched.getString(key)));

        cases.add(new Case("C27d 普通样板（非通配符）⇒ 不写",
                HostPatternMoverWildcard.typeToStamp(true, normal, wild, 1080, false, a).isEmpty(),
                "", HostPatternMoverWildcard.typeToStamp(true, normal, wild, 1080, false, a)));
        cases.add(new Case("C27e 普通槽 300 ⇒ 不写（只有母槽才有这个中间配置）",
                HostPatternMoverWildcard.typeToStamp(true, normal, wild, 300, true, a).isEmpty(),
                "", HostPatternMoverWildcard.typeToStamp(true, normal, wild, 300, true, a)));
        cases.add(new Case("C27f 不分区总成（老主机模块）⇒ 不写",
                HostPatternMoverWildcard.typeToStamp(false, normal, wild, 1080, true, a).isEmpty(),
                "", HostPatternMoverWildcard.typeToStamp(false, normal, wild, 1080, true, a)));
        cases.add(new Case("C27g 母槽区间右边界：1080..1129 写、1130（展开槽）不写",
                !HostPatternMoverWildcard.typeToStamp(true, normal, wild, 1129, true, a).isEmpty()
                        && HostPatternMoverWildcard.typeToStamp(true, normal, wild, 1130, true, a).isEmpty(),
                "1129 写 / 1130 不写",
                "1129=" + HostPatternMoverWildcard.typeToStamp(true, normal, wild, 1129, true, a)
                        + " 1130=" + HostPatternMoverWildcard.typeToStamp(true, normal, wild, 1130, true, a)));

        // 🔴 负对照：把"空类型也照写"那种实现放进来 ⇒ 读回就变成空串（必须红）
        final CompoundTag bad = new CompoundTag();
        bad.putString("id", "wildcard_pattern:wildcard_pattern");
        final String badStamp = ""; // 坏实现：不多判一句，直接把 "" 写下去
        bad.putString(key, badStamp);
        cases.add(new Case("C27NEG 负对照：把空串写下去 ⇒ 读回 = \"\"，与 C27a/b 的 A 不同（必须红）",
                !a.equals(bad.getString(key)), "≠ " + a, "读回='" + bad.getString(key) + "'"));

        // 类型为空白串（"  "）也必须当成"没有"
        cases.add(new Case("C27h 类型是空白串 ⇒ 不写（trim 后为空）",
                HostPatternMoverWildcard.typeToStamp(true, normal, wild, 1080, true, "   ").isEmpty(),
                "", "'" + HostPatternMoverWildcard.typeToStamp(true, normal, wild, 1080, true, "   ") + "'"));
        notes.add("C27 通配符类型落盘：slot=1080 type=" + stamp + " key=" + key
                + " ⇒ 写后读回=" + tag.getString(key));
    }

    /**
     * <b>C28 · 第五刀 ①：面板状态包的 {@code rev} ／ 旧的镜像不许盖掉新值</b>。
     *
     * <p>用户实测原话：「取出后会瞬间刷新一次到正确的位置，但是过零点几秒它又会重新刷新到原本的
     * 不正确（和取出之前）的位置」。
     * 根因：动作回执是对的，客户端那条<b>周期镜像</b>读的是自己手上还没同步的物品 NBT ⇒ 算出旧状态
     * ⇒ 把新状态盖了回去。<b>判据全部打在纯核</b>
     * {@link HostPatternMoverPanelState#afterMirror} / {@link HostPatternMoverPanelState#acceptsMirror}
     * （面板 {@code updateScreen} 调的就是它）。
     *
     * <table border="1">
     *   <tr><th>#</th><th>现场</th><th>判据</th></tr>
     *   <tr><td>a</td><td>动作后 10 组 rev=7，随后到达旧镜像 8 组 rev=6</td><td><b>仍显示 10 组</b></td></tr>
     *   <tr><td>aNEG</td><td>把 rev 比较去掉（照收不误）</td><td>回退成 8 组 ⇒ <b>必须红</b></td></tr>
     *   <tr><td>b</td><td>rev 相同（同一份数据）</td><td>不覆盖</td></tr>
     *   <tr><td>c</td><td>镜像 rev 更大（推送丢了、客户端 NBT 反而更新）</td><td>允许覆盖（闸不是"永远拒绝"）</td></tr>
     *   <tr><td>d</td><td>版本戳只在唯一写入口 ＋1</td><td>两次写 ⇒ 严格变大</td></tr>
     *   <tr><td>e</td><td>没有版本戳的老物品</td><td>rev=0（不编数）</td></tr>
     * </table>
     */
    private static void c28PanelRev(List<Case> cases, List<String> notes) {
        // ── 现场（全部由纯 NBT 造出来：10 个不同组 / 版本戳写在物品 tag 根上）
        final HostPatternMoverPanelState afterAction = panelState(10, 7L);
        final HostPatternMoverPanelState staleMirror = panelState(8, 6L);
        cases.add(new Case("C28 现场：动作后 10 组 rev=7 ／ 旧镜像 8 组 rev=6（数据面）",
                afterAction.groups() == 10 && afterAction.rev() == 7L
                        && staleMirror.groups() == 8 && staleMirror.rev() == 6L,
                "10 组/rev=7 与 8 组/rev=6",
                afterAction.groups() + " 组/rev=" + afterAction.rev() + " 与 "
                        + staleMirror.groups() + " 组/rev=" + staleMirror.rev()));

        // ★判据 1：镜像 rev 更小 ⇒ 不许覆盖
        final HostPatternMoverPanelState shown =
                HostPatternMoverPanelState.afterMirror(afterAction, staleMirror);
        cases.add(new Case("C28a ★判据：动作后 10 组、随后到达一份旧的镜像（8 组、rev 更小）⇒ 客户端仍显示 10 组",
                shown == afterAction && shown.groups() == 10
                        && !HostPatternMoverPanelState.acceptsMirror(afterAction, staleMirror),
                "groups=10（镜像被拒）",
                "groups=" + shown.groups() + " rev=" + shown.rev()
                        + " acceptsMirror=" + HostPatternMoverPanelState.acceptsMirror(afterAction, staleMirror)));

        // ── 负对照：去掉 rev 比较（= 第五刀之前那句"块数不一样就覆盖"）⇒ 必须回退成 8 组
        final HostPatternMoverPanelState naive = acceptAnyMirror(afterAction, staleMirror);
        cases.add(new Case("C28aNEG 负对照：去掉 rev 比较 ⇒ 必须回退成 8 组（红）",
                naive.groups() == 8 && naive.groups() != shown.groups(),
                "groups=8（= 回退，用户报的那个形态）",
                "groups=" + naive.groups() + " / 带闸=" + shown.groups()));

        // ── rev 相同（同一份数据）：不覆盖
        final HostPatternMoverPanelState sameRev = panelState(9, 7L);
        cases.add(new Case("C28b rev 相同（同一份数据）⇒ 也不覆盖",
                HostPatternMoverPanelState.afterMirror(afterAction, sameRev) == afterAction,
                "不覆盖", "acceptsMirror=" + HostPatternMoverPanelState.acceptsMirror(afterAction, sameRev)));

        // ── 反过来：镜像 rev 更大 ⇒ 允许覆盖（推送丢了、客户端 NBT 反而更新时，兜底仍在）
        final HostPatternMoverPanelState newer = panelState(11, 8L);
        final HostPatternMoverPanelState rescued = HostPatternMoverPanelState.afterMirror(afterAction, newer);
        cases.add(new Case("C28c 反过来：镜像 rev 更大（推送丢了、客户端 NBT 更新）⇒ 允许覆盖（闸不是'永远拒绝'）",
                rescued == newer && rescued.groups() == 11,
                "groups=11（镜像生效）", "groups=" + rescued.groups() + " rev=" + rescued.rev()));

        // ── 版本戳：唯一写入口（HostPatternMoverReader#storeTo）调的那个方法必须单调
        final CompoundTag root = new CompoundTag();
        final long r1 = HostPatternMoverNbt.bumpRevTag(root);
        final long r2 = HostPatternMoverNbt.bumpRevTag(root);
        cases.add(new Case("C28d 版本戳单调递增：同一个物品写两次 ⇒ 后一次的版本严格更大",
                r2 > r1 && r1 >= 1L && HostPatternMoverNbt.readRev(root) == r2,
                "r2 > r1 ≥ 1 且 readRev == r2", "r1=" + r1 + " r2=" + r2
                        + " readRev=" + HostPatternMoverNbt.readRev(root)));
        cases.add(new Case("C28e 没有版本戳的老物品 ⇒ rev=0（不编数、也不当成最新）",
                HostPatternMoverNbt.readRev(new CompoundTag()) == 0L,
                "rev=0", "rev=" + HostPatternMoverNbt.readRev(new CompoundTag())));

        notes.add("C28 面板 rev：动作后 " + afterAction.describe() + " ｜ 旧镜像 " + staleMirror.describe()
                + " ⇒ 显示 " + shown.describe() + "（无闸时 " + naive.describe() + "）");
    }

    /**
     * <b>负对照专用</b>：把第五刀之前的判据写出来 —— [[镜像照收不误]]。
     *
     * <p>它存在的唯一目的：证明 C28a 那条断言<b>真的能翻红</b>（若那条断言写成"只要有组数就算过"，
     * 这种坏实现就会混过去）。
     */
    static HostPatternMoverPanelState acceptAnyMirror(HostPatternMoverPanelState shown,
                                                      HostPatternMoverPanelState candidate) {
        return candidate == null ? shown : candidate;
    }

    /**
     * <b>C29 · 第五刀 ②：普通样板没填类型时自动补（只有老主机）</b>。
     *
     * <p>用户原话：「新增一个功能，若普通样板未填写配方类型，则尝试自动填写（只有老主机上的样板才能这样做）」。
     * 判据打在纯核 {@link HostPatternMoverWildcard#resolve}（读的那一侧，生产就是它）与
     * {@link HostPatternMoverWildcard#typeToStampPlain}（写进槽那一侧，生产
     * {@code HostPatternMoverTargets.InventoryTarget#write} 调的就是它）。
     *
     * <table border="1">
     *   <tr><th>#</th><th>现场</th><th>判据</th></tr>
     *   <tr><td>a</td><td>老主机 ＋ 模块有效类型恰好 1 个 ＋ 3 块无类型普通样板</td>
     *       <td><b>stampPlain=3</b>、type 全被填</td></tr>
     *   <tr><td>b</td><td>同一台模块有 2 个类型（有效类型集 ≥2）</td>
     *       <td><b>stampPlain=0、stampPlainSkip=3</b>、type 全空</td></tr>
     *   <tr><td>bNEG</td><td>若多义时"取第一个"</td><td>type 就不再是空串 ⇒ <b>必须红</b></td></tr>
     *   <tr><td>c</td><td>归墟（跨配方种类并行）</td><td>一律不补（两项计数都 0）—— ⚠️ 这只管【推断】</td></tr>
     *   <tr><td>d/e</td><td>自己已有类型 / 模块一个类型都没有</td><td>不补、不计"多义"</td></tr>
     *   <tr><td>f..k</td><td>写进槽的落盘判定 ＋ 真过 CompoundTag 读回</td><td>老主机普通槽写、其余不写</td></tr>
     *   <tr><td>g/gNEG</td><td>🔴 2026-11-06 修正：<b>归墟目标</b> ＋ 已知类型</td>
     *       <td><b>也要落盘</b>（旧行为"归墟不写"⇒ 必红）</td></tr>
     * </table>
     * <p>⚠️ <b>「推断」与「落盘」是两件事</b>（用户实测踩过的那条）：
     * <pre>
     *   推断（没类型时要不要替它猜一个）：只有老主机允许 ⇒ 在 resolve() 里，C29c 判的就是它
     *   落盘（把【已经知道】的类型写进样板自己的 NBT）：与来源主机无关，一律要做 ⇒ typeToStampPlain
     * </pre>
     * 之前落盘那一步也拿 {@code !oldHost} 挡了一道 ⇒ 从老主机读出来（推断成功、物品 type 有值）
     * 再黏贴到<b>归墟</b>机器上时，样板被放进去、机器却读不到类型
     * （星律「未标注类型不执行」把它拦死）—— 这就是用户报的那条 bug。
     */
    private static void c29PlainStamp(List<Case> cases, List<String> notes) {
        final String a = "gtceu:assembler";
        final List<String> one = List.of(a);
        final List<String> two = List.of(a, "gtceu:centrifuge");
        final HostPatternMoverWildcard.Origin oldHost = HostPatternMoverWildcard.Origin.OLD_HOST;
        final HostPatternMoverWildcard.Origin newHost = HostPatternMoverWildcard.Origin.NEW_HOST;

        // ① 老主机 ＋ 模块有效类型恰好 1 个 ⇒ 3 块全补
        final HostPatternMoverWildcard.Batch old3 = HostPatternMoverWildcard.resolveAll(List.of(
                plain(oldHost, one), plain(oldHost, one), plain(oldHost, one)));
        cases.add(new Case("C29a ★判据：老主机单类型模块的 3 块无类型普通样板 ⇒ type 被填、stampPlain=3",
                old3.tally().stampPlain() == 3 && old3.tally().stampPlainSkip() == 0
                        && old3.at(0).verdict() == HostPatternMoverWildcard.Verdict.RESOLVED_PLAIN
                        && a.equals(old3.typeAt(0)) && a.equals(old3.typeAt(1)) && a.equals(old3.typeAt(2)),
                "stampPlain=3 stampPlainSkip=0 type=[A,A,A]",
                old3.tally().describe() + " type=['" + old3.typeAt(0) + "','" + old3.typeAt(1)
                        + "','" + old3.typeAt(2) + "']"));

        // ② 负对照现场：同一台若模块有 2 个类型 ⇒ 0 块被填、3 块记"因多义没补"
        final HostPatternMoverWildcard.Batch two3 = HostPatternMoverWildcard.resolveAll(List.of(
                plain(oldHost, two), plain(oldHost, two), plain(oldHost, two)));
        cases.add(new Case("C29b ★判据（负对照现场）：模块有 2 个类型 ⇒ stampPlain=0、stampPlainSkip=3、type 全空",
                two3.tally().stampPlain() == 0 && two3.tally().stampPlainSkip() == 3
                        && two3.typeAt(0).isEmpty() && two3.typeAt(1).isEmpty() && two3.typeAt(2).isEmpty()
                        && two3.at(0).verdict() == HostPatternMoverWildcard.Verdict.AMBIGUOUS_PLAIN,
                "stampPlain=0 stampPlainSkip=3 type=['','','']", two3.tally().describe()
                        + " type=['" + two3.typeAt(0) + "','" + two3.typeAt(1) + "','" + two3.typeAt(2) + "']"));
        final String takeFirst = two3.at(0).candidates().isEmpty() ? "" : two3.at(0).candidates().get(0);
        cases.add(new Case("C29bNEG 负对照：若多义时'取第一个'，type 就不再是空串（必须红）",
                two3.typeAt(0).isEmpty() && !takeFirst.isEmpty(),
                "真实现='' ／ 取第一个='" + takeFirst + "'",
                "真实现='" + two3.typeAt(0) + "' 取第一个='" + takeFirst + "'"));

        // ③ 归墟 ⇒ 一律不补（也不把它算成"多义"）
        final HostPatternMoverWildcard.Batch guixu = HostPatternMoverWildcard.resolveAll(List.of(
                plain(newHost, one), plain(newHost, one), plain(newHost, one)));
        cases.add(new Case("C29c 归墟上的普通样板 ⇒ 一律不补（stampPlain=0、stampPlainSkip=0、type 空）",
                guixu.tally().stampPlain() == 0 && guixu.tally().stampPlainSkip() == 0
                        && guixu.typeAt(0).isEmpty(),
                "stampPlain=0 stampPlainSkip=0 type=''",
                guixu.tally().describe() + " type='" + guixu.typeAt(0) + "'"));

        // ④ 自己已经有类型 ⇒ KEEP（不是"补"，也不计任何一项）
        final HostPatternMoverWildcard.Batch keep = HostPatternMoverWildcard.resolveAll(List.of(
                new HostPatternMoverWildcard.Item(false, "gtceu:centrifuge", oldHost, one, List.of())));
        cases.add(new Case("C29d 普通样板自己已经写了类型 ⇒ KEEP（不改写、不计入补/多义）",
                "gtceu:centrifuge".equals(keep.typeAt(0))
                        && keep.at(0).verdict() == HostPatternMoverWildcard.Verdict.KEEP
                        && keep.tally().stampPlain() == 0 && keep.tally().stampPlainSkip() == 0,
                "KEEP gtceu:centrifuge", keep.at(0).describe() + " " + keep.tally().describe()));

        // ⑤ 老主机但模块一个类型都没有 ⇒ 不补，也不算"多义"
        final HostPatternMoverWildcard.Batch none = HostPatternMoverWildcard.resolveAll(List.of(
                plain(oldHost, List.of())));
        cases.add(new Case("C29e 老主机、模块一个类型都没有 ⇒ 不补（也不计'多义'）",
                none.typeAt(0).isEmpty() && none.tally().stampPlain() == 0
                        && none.tally().stampPlainSkip() == 0,
                "type='' 两项计数 0", none.at(0).describe() + " " + none.tally().describe()));

        // ⑥ 写进槽那一侧的落盘判定（生产 = InventoryTarget#write 调的同一个方法）
        final int normal = 1080;
        final int wild = 50;
        final String slotStamp = HostPatternMoverWildcard.typeToStampPlain(false, -1, 5, false, false, true, a);
        cases.add(new Case("C29f 放置：老主机 ＋ 普通槽 ＋ 自己没类型 ⇒ 要把类型写进样板 NBT",
                a.equals(slotStamp), a, slotStamp));
        // 🔴 2026-11-06 修正（用户实测：黏贴过来的样板"没有配方类型"却还是进了机器）：
        //    落盘这一步与「来源主机是不是老主机」无关 —— 推断层才管那个（C29c 那条只判推断）。
        final String guixuStamp = HostPatternMoverWildcard.typeToStampPlain(false, -1, 5, false, false,
                false, a);
        cases.add(new Case("C29g 放置：归墟（目标不是老主机）＋ 已知类型 ⇒ 【也要】写进样板 NBT",
                a.equals(guixuStamp), a, "'" + guixuStamp + "'"));
        // 负对照：恢复旧行为（!oldHost ⇒ 不写）⇒ 落盘的串就是空串，上面那条必然红
        final String oldBehavior = ""; // 旧实现（`if (!oldHost) return "";`）会返回的值
        cases.add(new Case("C29gNEG 旧行为（归墟不写）会让 C29g 读到空串 ⇒ 必须红",
                !oldBehavior.equals(guixuStamp) && a.equals(guixuStamp),
                "旧行为='' ／ 现在='" + a + "'",
                "旧行为='" + oldBehavior + "' 现在='" + guixuStamp + "'"));
        cases.add(new Case("C29h 放置：样板自己已有类型 ⇒ 不覆盖（尊重原值/玩家手选）",
                HostPatternMoverWildcard.typeToStampPlain(false, -1, 5, false, true, true, a).isEmpty(),
                "", HostPatternMoverWildcard.typeToStampPlain(false, -1, 5, false, true, true, a)));
        cases.add(new Case("C29i 放置：类型为空（多义/没补）⇒ 不写（🔴 绝不写空串覆盖）",
                HostPatternMoverWildcard.typeToStampPlain(false, -1, 5, false, false, true, "  ").isEmpty(),
                "", "'" + HostPatternMoverWildcard.typeToStampPlain(false, -1, 5, false, false, true, "  ") + "'"));
        cases.add(new Case("C29j 放置：通配符样板走另一条判据（这里一个字都不写）",
                HostPatternMoverWildcard.typeToStampPlain(false, -1, 5, true, false, true, a).isEmpty(),
                "", HostPatternMoverWildcard.typeToStampPlain(false, -1, 5, true, false, true, a)));
        cases.add(new Case("C29k 放置：分区总成只写普通槽（3 写 / 1080 母槽不写 / 越界不写）",
                !HostPatternMoverWildcard.typeToStampPlain(true, normal, 3, false, false, true, a).isEmpty()
                        && HostPatternMoverWildcard.typeToStampPlain(true, normal, 1080, false, false, true, a).isEmpty()
                        && HostPatternMoverWildcard.typeToStampPlain(true, normal, -1, false, false, true, a).isEmpty(),
                "3 写 / 1080 不写 / -1 不写",
                "3=" + !HostPatternMoverWildcard.typeToStampPlain(true, normal, 3, false, false, true, a).isEmpty()
                        + " 1080=" + HostPatternMoverWildcard.typeToStampPlain(true, normal, 1080, false, false, true, a)
                        + " -1=" + HostPatternMoverWildcard.typeToStampPlain(true, normal, -1, false, false, true, a)));

        // ⑦ ★判据：真写进样板 NBT 之后能读回该类型（与 C27b 同一手法：用机器侧的键常量）
        final CompoundTag pattern = new CompoundTag();
        pattern.putString("id", "ae2:processing_pattern");
        pattern.putString(STAMP_KEY, slotStamp);
        cases.add(new Case("C29l ★判据：落盘后样板 NBT 里能读回该类型",
                a.equals(pattern.getString(STAMP_KEY)), a, pattern.getString(STAMP_KEY)));
        // 负对照：多义那种情形若也照写，读回就是空串（与 A 不同 ⇒ 上面那条必然红）
        final String badStamp = HostPatternMoverWildcard.typeToStampPlain(false, -1, 5, false, false, true,
                two3.typeAt(0));
        final CompoundTag badPattern = new CompoundTag();
        badPattern.putString(STAMP_KEY, badStamp);
        cases.add(new Case("C29lNEG 负对照：多义那块不写 ⇒ 读回是空串（≠ A），与真实现一致",
                !a.equals(badPattern.getString(STAMP_KEY)) && badPattern.getString(STAMP_KEY).isEmpty(),
                "读回='' ≠ " + a, "读回='" + badPattern.getString(STAMP_KEY) + "'"));

        notes.add("C29 普通样板补类型：老主机单类型 " + old3.tally().describe()
                + " ／ 双类型 " + two3.tally().describe() + " ／ 归墟 " + guixu.tally().describe()
                + " ｜ 落盘 type=" + slotStamp + " key=" + STAMP_KEY + " 读回=" + pattern.getString(STAMP_KEY));
    }

    /**
     * <b>C30 · 第五刀 ②：读取汇总行里的两个新计数</b>。
     *
     * <p>用户点名：「读的汇总行里加两个计数：{@code stampPlain=<补了几块>}、
     * {@code stampPlainSkip=<因多义没补的块数>}」。
     * 这里直接造一份 {@link HostPatternMoverReader.Summary} 打它的 {@code logLine}（纯字符串，不需要运行时）。
     */
    private static void c30SummaryLine(List<Case> cases) {
        final HostPatternMoverReader.Summary s = new HostPatternMoverReader.Summary(
                3, 120, 3, 3, 0, 0, 9000, HostPatternMoverData.LIMIT_BYTES,
                "primordial_omega_engine", 0, 0, 0, 3, 0, 2);
        final String line = s.logLine(null);
        cases.add(new Case("C30a 读取汇总行里有 stampPlain= 与 stampPlainSkip=（用户点名的字段名）",
                line.contains(" stampPlain=3") && line.contains(" stampPlainSkip=0"),
                "含 stampPlain=3 与 stampPlainSkip=0", line));
        cases.add(new Case("C30b 前半段的固定形状没变（read pos=… assemblies=… added=… bytes=…）",
                line.startsWith("[SHANHAI-MOVER] read pos=? assemblies=3 slots=120 nonEmpty=3"
                        + " added=3 dedup=0 bytes=9000/"),
                "[SHANHAI-MOVER] read pos=? assemblies=3 … bytes=9000/…", line));
        // 第六刀：读取汇总行要带 circuits=<记下了几个非 0>
        cases.add(new Case("C30c 汇总行带 circuits=（第六刀）", line.endsWith(" circuits=2"),
                "以 circuits=2 结尾", line));
    }

    /** 造一条「普通样板、自身没类型」的识别输入（第五刀）。 */
    static HostPatternMoverWildcard.Item plain(HostPatternMoverWildcard.Origin origin,
                                               List<String> moduleTypes) {
        return new HostPatternMoverWildcard.Item(false, "", origin,
                moduleTypes == null ? List.of() : moduleTypes, List.of());
    }

    // ================================================================ 第六刀 · 母槽编程电路

    /**
     * 造一台「<b>只有</b>母槽 {@code mother} 设了 {@code value}」的假机器（生产侧那一半就是
     * {@code StellarSampleAssemblerPartMachine#shanhaiWildcardCircuit}）。
     */
    static HostPatternMoverCircuit.MotherLookup circuitOn(int mother, int value) {
        return m -> m == mother ? value : HostPatternMoverCircuit.NONE;
    }

    /**
     * 第六刀 ①：<b>读</b>的时候把母槽电路记进条目的 {@code circuit} 键。
     *
     * <p>自证 1（任务书）：母槽 {@code i} 设 5 ⇒ 物品 NBT 里该条 pattern 的 {@code circuit=5}；
     * 普通槽 ⇒ <b>无该键</b>。
     */
    private static void c31CircuitRead(List<Case> cases, List<String> notes) {
        // 母槽 1080..1129 是本工程既有口径（PATTERN_SLOT_COUNT=1080 / WILDCARD_SLOT_COUNT=50）
        final int normal = 1080;
        final int wild = 50;
        cases.add(new Case("C31a 槽号 ⇒ 母槽号（1080→0 / 1085→5 / 1129→49 / 1130→-1 / 1079→-1）",
                HostPatternMoverCircuit.motherSlotOf(true, normal, wild, 1080) == 0
                        && HostPatternMoverCircuit.motherSlotOf(true, normal, wild, 1085) == 5
                        && HostPatternMoverCircuit.motherSlotOf(true, normal, wild, 1129) == 49
                        && HostPatternMoverCircuit.motherSlotOf(true, normal, wild, 1130) == -1
                        && HostPatternMoverCircuit.motherSlotOf(true, normal, wild, 1079) == -1,
                "0 / 5 / 49 / -1 / -1",
                HostPatternMoverCircuit.motherSlotOf(true, normal, wild, 1080) + " / "
                        + HostPatternMoverCircuit.motherSlotOf(true, normal, wild, 1085) + " / "
                        + HostPatternMoverCircuit.motherSlotOf(true, normal, wild, 1129) + " / "
                        + HostPatternMoverCircuit.motherSlotOf(true, normal, wild, 1130) + " / "
                        + HostPatternMoverCircuit.motherSlotOf(true, normal, wild, 1079)));

        // ── 母槽 5 设 5：槽 1085 读到 5，条目里真的带 circuit=5
        final HostPatternMoverCircuit.Source src = new HostPatternMoverCircuit.Source(
                true, normal, wild, circuitOn(5, 5));
        final int atMother = src.circuitAt(1085);
        final HostPatternMoverEntry on = HostPatternMoverEntry.of(
                recipeIdPattern("gtceu:assembler/c31_mother"), "gtceu:assembler", atMother);
        final HostPatternMoverEntry onBack = HostPatternMoverEntry.fromTag(on.entryTag());
        cases.add(new Case("C31b 母槽 5 设 5 ⇒ 槽 1085 读到 5，条目 circuit=5（写出去读回来都是 5）",
                atMother == 5 && on.circuit() == 5 && onBack.circuit() == 5
                        && on.entryTag().getInt(HostPatternMoverEntry.K_CIRCUIT) == 5,
                "atMother=5 circuit=5 往返=5，键=5",
                "atMother=" + atMother + " circuit=" + on.circuit() + " 往返=" + onBack.circuit()
                        + " 键=" + on.entryTag().getInt(HostPatternMoverEntry.K_CIRCUIT)));

        // ── 普通槽：一个 0 都不记，条目里【没有】那个键（= 老数据的形状，一个字节都不多）
        final int atNormal = src.circuitAt(3);
        final HostPatternMoverEntry off = HostPatternMoverEntry.of(
                recipeIdPattern("gtceu:assembler/c31_normal"), "gtceu:assembler", atNormal);
        cases.add(new Case("C31c 普通槽（3 号）⇒ 读到 0，条目里【没有】circuit 键",
                atNormal == 0 && off.circuit() == 0
                        && !off.entryTag().contains(HostPatternMoverEntry.K_CIRCUIT,
                        net.minecraft.nbt.Tag.TAG_INT),
                "0 且无键", atNormal + " 且无键="
                        + !off.entryTag().contains(HostPatternMoverEntry.K_CIRCUIT,
                        net.minecraft.nbt.Tag.TAG_INT)));

        // ── 非星律总成（Source.NONE）：即使槽号落在 1085 也读到 0
        cases.add(new Case("C31d 非星律总成 ⇒ 槽 1085 也读 0（Source.NONE）",
                HostPatternMoverCircuit.Source.NONE.circuitAt(1085) == 0,
                "0", String.valueOf(HostPatternMoverCircuit.Source.NONE.circuitAt(1085))));

        // ── 源被手改成越界值（40）⇒ 折成 0（与机器侧 shanhaiApplyWildcardCircuit 的归一化同口径）
        final HostPatternMoverEntry bad = HostPatternMoverEntry.of(
                recipeIdPattern("gtceu:assembler/c31_bad"), "gtceu:assembler", 40);
        cases.add(new Case("C31e 越界电路号（40）⇒ 归一成 0，且不写键",
                bad.circuit() == 0 && !bad.entryTag().contains(HostPatternMoverEntry.K_CIRCUIT,
                        net.minecraft.nbt.Tag.TAG_INT),
                "0 且无键", bad.circuit() + " 且无键="
                        + !bad.entryTag().contains(HostPatternMoverEntry.K_CIRCUIT,
                        net.minecraft.nbt.Tag.TAG_INT)));

        // ── 负对照（对应 run-negctl 的 M7）：把「只认母槽区间」那道门去掉 ——
        //    直接拿【槽号】当母槽号去问 lookup ⇒ 普通槽 3 会读到一个值。用它证明 C31c 不是恒真。
        final HostPatternMoverCircuit.MotherLookup trap = circuitOn(3, 7);
        final int ignoreZone = HostPatternMoverCircuit.normalize(trap.circuitAt(3));
        final int throughGate = new HostPatternMoverCircuit.Source(true, normal, wild, trap).circuitAt(3);
        cases.add(new Case("C31NEG 去掉区间门就会读到值（负对照必须红）",
                ignoreZone == 7 && throughGate == 0,
                "直问=7，过门=0（= 说明 C31c 的 0 是区间判据给的）",
                "直问=" + ignoreZone + " 过门=" + throughGate));

        notes.add("C31 第六刀读取：母槽 5 设 5 ⇒ 1085 读到 " + atMother
                + "、3 号普通槽读到 " + atNormal + "；条目字节 " + on.standaloneBytes()
                + " B（带电路）/ " + off.standaloneBytes() + " B（不带）");
    }

    /**
     * 第六刀 ②：<b>放</b>的时候把电路写回目标母槽。
     *
     * <p>自证 2（任务书）：目标母槽 j 收到电路 5（用假机器记录调用）；
     * {@code circuit=0} ⇒ <b>不调用</b> apply（负对照：改成调用 ⇒ 必须红）。
     * <p>🔴 第七刀起 {@link FakeCircuitTarget} 走<b>生产那句</b> {@code Source.applyAt}，
     * 所以这里的读数直接就是"机器侧收到了哪个索引、哪个值"（星律 = 母槽号，不是容器槽号）。
     */
    private static void c32CircuitApply(List<Case> cases, List<String> notes) {
        final FakeCircuitTarget mother = new FakeCircuitTarget("星律样板总成", 1130, "gtceu:assembler")
                .zoned(1080, 50)
                .motherSource(1080, 50, circuitOn(0, 0));
        final List<HostPatternMoverPlacer.Unit> units =
                List.of(new FakeCircuitUnit("recipe:w1", "gtceu:assembler", true, 5));
        final HostPatternMoverPlacer.Result r = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(units, List.of(mother)), units, List.of(mother));

        cases.add(new Case("C32a 机器侧收到电路：索引=母槽 0、值=5、只调 1 次（容器槽是 1080）",
                mother.circuitCalls == 1 && mother.writerCalls == 1
                        && mother.appliedIndex == 0 && mother.appliedCircuit == 5,
                "calls=1 writer=1 索引=0 值=5",
                "calls=" + mother.circuitCalls + " writer=" + mother.writerCalls
                        + " 索引=" + mother.appliedIndex + " 值=" + mother.appliedCircuit));
        cases.add(new Case("C32b 汇总里 circuits=1、circuitSkipped=0（样板也放进去了）",
                r.circuits() == 1 && r.circuitSkipped() == 0 && r.placed() == 1
                        && "recipe:w1".equals(mother.slots[1080]),
                "circuits=1 skipped=0 placed=1 且槽 1080 有样板",
                "circuits=" + r.circuits() + " skipped=" + r.circuitSkipped()
                        + " placed=" + r.placed() + " 槽1080=" + mother.slots[1080]));

        // ── 0 = 不设置 ⇒【一次都不许调】
        final FakeCircuitTarget zero = new FakeCircuitTarget("星律样板总成0", 1130, "gtceu:assembler")
                .zoned(1080, 50)
                .motherSource(1080, 50, circuitOn(0, 0));
        final List<HostPatternMoverPlacer.Unit> zeros =
                List.of(new FakeCircuitUnit("recipe:z1", "gtceu:assembler", true,
                        HostPatternMoverCircuit.NONE));
        final HostPatternMoverPlacer.Result rz = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(zeros, List.of(zero)), zeros, List.of(zero));
        cases.add(new Case("C32c circuit=0 ⇒ applyCircuit 一次都没调（也不计 skipped）",
                zero.circuitCalls == 0 && zero.writerCalls == 0 && rz.circuits() == 0
                        && rz.circuitSkipped() == 0 && rz.placed() == 1,
                "calls=0 writer=0 circuits=0 skipped=0 placed=1",
                "calls=" + zero.circuitCalls + " writer=" + zero.writerCalls
                        + " circuits=" + rz.circuits()
                        + " skipped=" + rz.circuitSkipped() + " placed=" + rz.placed()));

        // ── 负对照：把"0 不往下写"那道门（在纯核 Source.applyAt 里）删掉 ⇒ 上面那条必然红。
        //    ①先证明 applyAt(…, 0) 会被门挡住（writer 不动）；
        //    ②再模拟坏实现：直接拿 writer 交一个 0 给机器 ⇒ 计数真的会动。
        final boolean zeroThroughGate = zero.source().applyAt(1080, HostPatternMoverCircuit.NONE);
        final int writerBefore = zero.writerCalls;
        zero.source().atSlot().set(0, HostPatternMoverCircuit.NONE); // = "去掉那道门"的形态
        cases.add(new Case("C32NEG 去掉 0 那道门就真的会交给机器（负对照必须红）",
                !zeroThroughGate && zero.writerCalls == writerBefore + 1,
                "过门=false 且 writer 从 " + writerBefore + " 变 " + (writerBefore + 1),
                "过门=" + zeroThroughGate + " writer=" + writerBefore + "→" + zero.writerCalls));

        // ── 星律总成但落在【普通槽】：电路同样不该写（母槽判据不是"是不是星律"就够）
        final FakeCircuitTarget starNormal = new FakeCircuitTarget("星律样板总成N", 1130, "gtceu:assembler")
                .zoned(1080, 50)
                .motherSource(1080, 50, circuitOn(0, 0));
        final List<HostPatternMoverPlacer.Unit> normalUnits =
                List.of(new FakeCircuitUnit("recipe:n1", "gtceu:assembler", false, 5));
        final HostPatternMoverPlacer.Result rn = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(normalUnits, List.of(starNormal)), normalUnits,
                List.of(starNormal));
        cases.add(new Case("C32d 星律总成上的【普通槽】⇒ 不写电路，计 circuitSkipped=1",
                starNormal.circuitCalls == 0 && rn.circuitSkipped() == 1 && rn.circuits() == 0
                        && rn.placed() == 1,
                "calls=0 skipped=1 circuits=0 placed=1",
                "calls=" + starNormal.circuitCalls + " skipped=" + rn.circuitSkipped()
                        + " circuits=" + rn.circuits() + " placed=" + rn.placed()));

        notes.add("C32 放置（星律）：容器槽 1080 → 机器侧索引 " + mother.appliedIndex
                + "、值 " + mother.appliedCircuit + "（calls=" + mother.circuitCalls + "）；"
                + "circuit=0 的 calls=" + (zero.circuitCalls - 0) + "、writer=" + (zero.writerCalls - 1)
                + "（负对照另计 1 次）");
    }

    /**
     * 第六刀 ③：目标<b>不是</b>星律母槽 ⇒ 忽略 + {@code circuitSkipped} 计数（样板照放）。
     *
     * <p>自证 3（任务书）：目标不是星律 ⇒ {@code circuitSkipped} 计数 +1。
     * 这里用的是<b>没有</b> {@code wildcardMotherAt} / {@code applyCircuit} 覆写的
     * {@link FakeTarget}（= 一台 gtlcore 总成：真没有这两个 API）。
     */
    private static void c33CircuitSkipped(List<Case> cases, List<String> notes) {
        final FakeTarget plainTarget = new FakeTarget("gtl总成", 4, "gtceu:assembler");
        final List<HostPatternMoverPlacer.Unit> units =
                List.of(new FakeCircuitUnit("recipe:x1", "gtceu:assembler", false, 5));
        final HostPatternMoverPlacer.Result r = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(units, List.of(plainTarget)), units, List.of(plainTarget));
        cases.add(new Case("C33a 非星律目标 ⇒ circuitSkipped=1、circuits=0、circuitFailed=0",
                r.circuitSkipped() == 1 && r.circuits() == 0 && r.circuitFailed() == 0,
                "skipped=1 circuits=0 failed=0",
                "skipped=" + r.circuitSkipped() + " circuits=" + r.circuits()
                        + " failed=" + r.circuitFailed()));
        cases.add(new Case("C33b 电路被跳过不影响样板：placed=1 且槽 0 有样板",
                r.placed() == 1 && "recipe:x1".equals(plainTarget.slots[0]),
                "placed=1 槽0=recipe:x1", "placed=" + r.placed() + " 槽0=" + plainTarget.slots[0]));
        cases.add(new Case("C33c 一次 applyCircuit 都没调（默认实现是抛，真调了会变成 failed）",
                r.circuitFailed() == 0, "failed=0（说明没调）", "failed=" + r.circuitFailed()));
        notes.add("C33 第六刀非星律目标：" + r.describe());
    }

    /**
     * 第六刀 ④：写电路<b>失败不许回滚样板</b>，且同一批的 WARN <b>只打一行</b>。
     */
    private static void c34CircuitFailure(List<Case> cases, List<String> notes) {
        final FakeCircuitTarget broken = new FakeCircuitTarget("星律样板总成X", 1130, "gtceu:assembler")
                .zoned(1080, 50)
                .motherSource(1080, 50, circuitOn(0, 0));
        broken.throwOnCircuit = true;
        final List<HostPatternMoverPlacer.Unit> units = List.of(
                new FakeCircuitUnit("recipe:f1", "gtceu:assembler", true, 5),
                new FakeCircuitUnit("recipe:f2", "gtceu:assembler", true, 7),
                new FakeCircuitUnit("recipe:f3", "gtceu:assembler", true, 9));

        final int[] warns = {0};
        final HostPatternMoverLog.Sink prev = HostPatternMoverLog.swap(new HostPatternMoverLog.Sink() {
            @Override
            public void info(String format, Object... args) {
                // 自检里不需要 info
            }

            @Override
            public void warn(String format, Object... args) {
                warns[0]++;
            }
        });
        final HostPatternMoverPlacer.Result r;
        try {
            r = HostPatternMoverPlacer.apply(HostPatternMoverPlacer.plan(units, List.of(broken)),
                    units, List.of(broken));
        } finally {
            HostPatternMoverLog.swap(prev);
        }

        cases.add(new Case("C34a 写电路失败 ⇒ circuitFailed=3、circuits=0",
                r.circuitFailed() == 3 && r.circuits() == 0,
                "failed=3 circuits=0", "failed=" + r.circuitFailed() + " circuits=" + r.circuits()));
        cases.add(new Case("C34b 样板【不回滚】：三块都在槽里、placed=3",
                r.placed() == 3 && broken.nonEmpty() == 3,
                "placed=3 槽里 3 块", "placed=" + r.placed() + " 槽里 " + broken.nonEmpty() + " 块"));
        cases.add(new Case("C34c 同一批 WARN 只打 1 行（3 次失败也只报 1 行）", warns[0] == 1,
                "warn=1", "warn=" + warns[0]));
        notes.add("C34 第六刀写电路失败：" + r.describe() + " ｜ WARN 行数=" + warns[0]
                + "（回滚负对照：placed 仍是 3）");
    }

    /**
     * 第六刀 ⑤：{@code circuit} 键<b>必须计入 1.5 MiB 判据</b>（自证 4）。
     *
     * <p>两件事分开验：① 每块 ＋14 B 且算术 == 实写（真的进闸了）；
     * ② 同一批合成样板，带电路之后 1.5 MiB 还能装几块（实测，不是估）。
     */
    private static void c35CircuitBytes(List<Case> cases, List<String> notes) {
        final CompoundTag pattern = recipeIdPattern("gtceu:assembler/c35");
        final HostPatternMoverEntry plainEntry = HostPatternMoverEntry.of(pattern, "gtceu:assembler");
        final HostPatternMoverEntry circEntry = HostPatternMoverEntry.of(pattern, "gtceu:assembler", 5);
        final int delta = circEntry.standaloneBytes() - plainEntry.standaloneBytes();
        final int listDelta = circEntry.listElementBytes() - plainEntry.listElementBytes();
        // "circuit" 键的字节：1 (TAG_Int) + 2 (名字长) + 7 ("circuit") + 4 (int) = 14
        cases.add(new Case("C35a 带电路的那一条比不带的多 14 B（且列表元素增量同为 14）",
                delta == 14 && listDelta == 14, "14 / 14", delta + " / " + listDelta));

        // ── 算术 == 实写：说明 circuit 真的在闸的口径里（漏掉它两边就会不等）
        final List<HostPatternMoverEntry> two = List.of(plainEntry, circEntry);
        final int arith = HostPatternMoverCodec.totalBytes(two, "guixu_vision_core");
        final int real = HostPatternMoverCodec.realTotalBytes(two, "guixu_vision_core");
        cases.add(new Case("C35b 带电路时「算术口径 == 真的 NbtIo.write 一遍」", arith == real,
                "相等", arith + " vs " + real));

        // ── 老数据兼容：没有 circuit 键的条目，字节与带 circuit 的 of() 完全不差
        final CompoundTag legacy = plainEntry.entryTag();
        final HostPatternMoverEntry back = HostPatternMoverEntry.fromTag(legacy);
        cases.add(new Case("C35c 老数据（无 circuit 键）读回来 = 0，字节一字节不多",
                back.circuit() == 0 && back.standaloneBytes() == plainEntry.standaloneBytes(),
                "0 且字节相同", back.circuit() + " 且字节 " + back.standaloneBytes()
                        + " vs " + plainEntry.standaloneBytes()));

        // ── 实测「1.5 MiB 装几块」：同一批合成样板，circuit=0 vs circuit=5
        final int fitZero = fillCount(0);
        final int fitFive = fillCount(5);
        cases.add(new Case("C35d 加了 circuit 之后装得更少（1.5 MiB 闸真的算了那 14 B）",
                fitFive < fitZero && fitZero > 100,
                "fit(5) < fit(0)", "circuit=0 ⇒ " + fitZero + " 块 / circuit=5 ⇒ " + fitFive + " 块"));
        cases.add(new Case("C35e 少掉的块数 ≈ 1653 × 14 ÷ 单块字节（读数合理性）",
                fitZero - fitFive >= 15 && fitZero - fitFive <= 40,
                "少 15..40 块", "少了 " + (fitZero - fitFive) + " 块"));
        notes.add("C35 第六刀字节账：单块 +14 B ⇒ 1.5 MiB 由 " + fitZero + " 块变成 " + fitFive
                + " 块（少 " + (fitZero - fitFive) + " 块）；" + "带电路条目 " + circEntry.standaloneBytes()
                + " B / 不带 " + plainEntry.standaloneBytes() + " B");
    }

    /**
     * 用与 {@link #c3LimitReal} <b>同一种</b>合成样板一直塞到 1.5 MiB 超限，返回装下的块数。
     *
     * <p>唯一变量是 {@code circuit}：{@code 0} 与 {@code 5} 各跑一遍，
     * 「1653 块 → 多少块」就是这么量出来的（不是估的）。
     */
    static int fillCount(int circuit) {
        final HostPatternMoverData<HostPatternMoverEntry> data =
                new HostPatternMoverData<>(new HostPatternMoverCodec("guixu_vision_core"));
        int n = 0;
        while (n < 20000) {
            if (!data.add(syntheticEntry(n, circuit)).ok()) {
                break;
            }
            n++;
        }
        return data.size();
    }

    // ================================================================ 追加 · 归墟落盘（用户实测那条 bug）

    /**
     * <b>用户实测 bug 的自证</b>：「这两个样板是黏贴的，它没有配方类型，但是还是被黏贴下来了」
     * 「你是不是没有给样板补配方类型？」
     *
     * <h2>现场还原（谁是来源、谁是目标）</h2>
     * <pre>
     *   读：来源是老主机（primordial）⇒ resolve() 推断出唯一类型 "gtceu:assembler"
     *       ⇒ 搬运物品的 type 字段有值 ⇒ 放置时"类型闸门"放行（所以样板真的被黏贴下来了）
     *   放：目标是【归墟】机器上的星律普通槽
     *       ⇒ 旧代码的 typeToStampPlain 里还有一条 `if (!oldHost) return "";`
     *       ⇒ 不落盘 ⇒ 机器读到的是"无类型" ⇒ 星律「未标注类型不执行」把它拦死
     * </pre>
     * 本组走的是<b>生产同一条链</b>（判据 → 机器侧同一个落笔处
     * {@code ShanhaiWildcardTypeNbt.write}）→ 再从样板 NBT 读回。
     * <p>负对照：把落盘的串换成旧行为会得到的空串 ⇒ 读回是空串、{@code write} 返回 false，
     * 与 C36a 的正向读数<b>必然不同</b>（= 这条断言不是恒真）。
     */
    private static void c36GuixuStamp(List<Case> cases, List<String> notes) {
        final String type = "gtceu:assembler";
        // 一块「黏贴过来的、自己没有类型」的普通样板（形状与真样板一致：id/Count/tag{in,out,recipeId}）
        final CompoundTag stack = syntheticPattern("gtceu:assembler/guixu_pasted", 10, 5);
        final CompoundTag tag = stack.getCompound("tag");

        // ① 落盘判定：目标 = 星律分区总成 ＋ 归墟（oldHost=false）＋ 5 号普通槽 ＋ 自己没类型
        final String stamp = HostPatternMoverWildcard.typeToStampPlain(true, 1080, 5, false, false,
                false, type);
        cases.add(new Case("C36a ★归墟目标 也判「要落盘」", type.equals(stamp), type, stamp));

        // ② 真的落进样板 NBT（机器侧同一个落笔处），再读回
        final boolean changed = com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.write(tag, stamp);
        final String back = com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.readCanonical(tag);
        cases.add(new Case("C36b ★落盘后从样板 NBT 能读回类型（用户报的那条）",
                changed && type.equals(back), "changed=true 读回=" + type,
                "changed=" + changed + " 读回='" + back + "'"));

        // ③ 负对照：按旧行为写空串 ⇒ 读回也是空串（≠ type）⇒ C36b 会红
        final CompoundTag oldTag = syntheticPattern("gtceu:assembler/guixu_old", 10, 5)
                .getCompound("tag");
        final boolean oldChanged = com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.write(oldTag, "");
        final String oldBack = com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.readCanonical(oldTag);
        cases.add(new Case("C36NEG 旧行为（不落盘）⇒ 读回空串 ≠ 类型（负对照必须红）",
                !oldChanged || !type.equals(oldBack),
                "changed=false 或 读回=''",
                "changed=" + oldChanged + " 读回='" + oldBack + "'"));

        // ④ 老主机走同一条链也应该落盘（正对照：说明 ② 不是归墟特例）
        final CompoundTag oldHostTag = syntheticPattern("gtceu:assembler/oldhost_pasted", 10, 5)
                .getCompound("tag");
        final String oldHostStamp = HostPatternMoverWildcard.typeToStampPlain(true, 1080, 5, false,
                false, true, type);
        com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.write(oldHostTag, oldHostStamp);
        cases.add(new Case("C36c 正对照：老主机也落盘、也读得回（同一处判据的两侧）",
                type.equals(com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.readCanonical(oldHostTag)),
                type,
                com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.readCanonical(oldHostTag)));

        // ⑤ 自己已经有类型 ⇒ 一个字都不改（尊重原值）
        final CompoundTag keepTag = syntheticPattern("gtceu:assembler/keep", 10, 5).getCompound("tag");
        com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.write(keepTag, "gtceu:centrifuge");
        final String keepStamp = HostPatternMoverWildcard.typeToStampPlain(true, 1080, 5, false, true,
                false, type);
        cases.add(new Case("C36d 样板自己已有类型 ⇒ 不覆盖（归墟也一样）",
                keepStamp.isEmpty()
                        && "gtceu:centrifuge".equals(
                        com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.readCanonical(keepTag)),
                "'' 且仍是 gtceu:centrifuge",
                "'" + keepStamp + "' 且仍是 "
                        + com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.readCanonical(keepTag)));

        notes.add("C36 归墟落盘：stamp='" + stamp + "' changed=" + changed + " 读回='" + back
                + "'；旧行为 changed=" + oldChanged + " 读回='" + oldBack + "'");
    }

    // ================================================================ 第六刀 ② · 同配方合并时的电路

    /**
     * <b>用户 2026-11-06 拍板</b>：「同一条记录被合并两次时，{@code circuit} <b>取非 0 的那一个</b>」。
     *
     * <h2>为什么（真实丢数据风险）</h2>
     * <pre>
     *   第 1 次读：星律母槽上有这块样板，电路 = 5
     *   第 2 次读：同一配方又出现在普通槽 / 另一台机器，那里没设电路 ⇒ 0
     *   「新的覆盖旧的」⇒ 合并后 0 ⇒ 放置时一个电路都写不回去（用户报的"没复制黏贴"复发）
     * </pre>
     * 判据打在纯核 {@link HostPatternMoverCircuit#mergeForDedup} ＋ 生产 codec
     * {@link HostPatternMoverCodec#merge}（走的就是读取时真正用的那条
     * {@link HostPatternMoverData#add} 路径）。
     *
     * <table border="1">
     *   <tr><th>#</th><th>现场（同 recipe id）</th><th>判据</th></tr>
     *   <tr><td>a</td><td>先 5，后 0</td><td>合并后 <b>5</b>（aNEG：旧行为会得 0 ⇒ 必红）</td></tr>
     *   <tr><td>b</td><td>先 0，后 5</td><td>5（对称性）</td></tr>
     *   <tr><td>c</td><td>先 5，后 7（两边不同非 0）</td><td>保留后读到的 <b>7</b> ＋ 一行 {@code circuit_conflict}</td></tr>
     *   <tr><td>d</td><td>同 key 连合并三次（5→7→9）</td><td>9，且 conflict <b>只 1 行</b>（不刷屏）</td></tr>
     *   <tr><td>e</td><td>5 与 5 / 0 与 0</td><td>5 / 0，且<b>不</b>打 conflict</td></tr>
     *   <tr><td>f</td><td>后读到的那条 type 不同</td><td>type 仍按"新的覆盖旧的"，电路仍是合并值</td></tr>
     * </table>
     */
    private static void c37CircuitMerge(List<Case> cases, List<String> notes) {
        final String rid = "gtceu:assembler/c37";
        final String type = "gtceu:assembler";

        // ── a：先 5，后 0（这就是丢电路的那个现场）
        final HostPatternMoverData<HostPatternMoverEntry> a = newMoverData();
        a.add(entryC(rid, type, 5));
        a.add(entryC(rid, type, 0));
        final int aCircuit = a.size() == 1 ? a.entries().get(0).circuit() : -1;
        cases.add(new Case("C37a ★先 5 后 0（同配方）⇒ 合并后仍是 5（不许被 0 覆盖）",
                aCircuit == 5 && a.size() == 1, "circuit=5 size=1",
                "circuit=" + aCircuit + " size=" + a.size()));
        // 负对照：旧行为（后读覆盖）合并结果就是 0 ⇒ 上面那条必然红
        final int oldBehavior = entryC(rid, type, 0).circuit();
        cases.add(new Case("C37aNEG 旧行为（后读覆盖）会得 0 ⇒ C37a 必须红",
                oldBehavior == 0 && oldBehavior != aCircuit,
                "旧行为=0 ／ 现在=5", "旧行为=" + oldBehavior + " 现在=" + aCircuit));

        // ── b：先 0，后 5（对称性：两种顺序都必须保住 5）
        final HostPatternMoverData<HostPatternMoverEntry> b = newMoverData();
        b.add(entryC(rid, type, 0));
        b.add(entryC(rid, type, 5));
        final int bCircuit = b.size() == 1 ? b.entries().get(0).circuit() : -1;
        cases.add(new Case("C37b ★先 0 后 5（同配方）⇒ 合并后 5", bCircuit == 5 && b.size() == 1,
                "circuit=5 size=1", "circuit=" + bCircuit + " size=" + b.size()));

        // ── c：两边不同非 0 ⇒ 保留后读到的 ＋ 一行 circuit_conflict
        final CountingSink cSink = new CountingSink();
        final HostPatternMoverLog.Sink cPrev = HostPatternMoverLog.swap(cSink);
        final HostPatternMoverData<HostPatternMoverEntry> c = newMoverData();
        try {
            c.add(entryC(rid, type, 5));
            c.add(entryC(rid, type, 7));
        } finally {
            HostPatternMoverLog.swap(cPrev);
        }
        final int cCircuit = c.size() == 1 ? c.entries().get(0).circuit() : -1;
        cases.add(new Case("C37c 先 5 后 7（不同非 0）⇒ 保留后读到的 7 ＋ 一行 circuit_conflict",
                cCircuit == 7 && cSink.conflicts == 1, "circuit=7 conflict=1",
                "circuit=" + cCircuit + " conflict=" + cSink.conflicts));

        // ── d：同一 key 连合并三次 ⇒ conflict 只 1 行（不刷屏），电路取最后一次的非 0
        final CountingSink dSink = new CountingSink();
        final HostPatternMoverLog.Sink dPrev = HostPatternMoverLog.swap(dSink);
        final HostPatternMoverData<HostPatternMoverEntry> d = newMoverData();
        try {
            d.add(entryC(rid, type, 5));
            d.add(entryC(rid, type, 7));
            d.add(entryC(rid, type, 9));
        } finally {
            HostPatternMoverLog.swap(dPrev);
        }
        final int dCircuit = d.size() == 1 ? d.entries().get(0).circuit() : -1;
        cases.add(new Case("C37d 同 key 连合并三次（5→7→9）⇒ 9，conflict 仍只 1 行（不刷屏）",
                dCircuit == 9 && dSink.conflicts == 1, "circuit=9 conflict=1",
                "circuit=" + dCircuit + " conflict=" + dSink.conflicts));

        // ── e：同值合并 / 两边都 0 ⇒ 不许打 conflict
        final CountingSink eSink = new CountingSink();
        final HostPatternMoverLog.Sink ePrev = HostPatternMoverLog.swap(eSink);
        final HostPatternMoverData<HostPatternMoverEntry> e5 = newMoverData();
        final HostPatternMoverData<HostPatternMoverEntry> e0 = newMoverData();
        try {
            e5.add(entryC(rid, type, 5));
            e5.add(entryC(rid, type, 5));
            e0.add(entryC(rid, type, 0));
            e0.add(entryC(rid, type, 0));
        } finally {
            HostPatternMoverLog.swap(ePrev);
        }
        cases.add(new Case("C37e 同值合并（5 与 5）与两边都 0 ⇒ 不打 circuit_conflict",
                eSink.conflicts == 0 && e5.entries().get(0).circuit() == 5
                        && e0.entries().get(0).circuit() == 0,
                "conflict=0 / 5 / 0",
                "conflict=" + eSink.conflicts + " / " + e5.entries().get(0).circuit()
                        + " / " + e0.entries().get(0).circuit()));

        // ── f：其余字段仍按"新的覆盖旧的"（后读到的那条 type 变了要跟着变），只有电路取合并值
        final HostPatternMoverData<HostPatternMoverEntry> f = newMoverData();
        f.add(entryC(rid, "gtceu:centrifuge", 5));
        f.add(entryC(rid, "gtceu:assembler", 0));
        final HostPatternMoverEntry fEntry = f.entries().get(0);
        cases.add(new Case("C37f 其余字段新的覆盖旧的：type=gtceu:assembler（新）＋ circuit=5（合并）",
                "gtceu:assembler".equals(fEntry.type()) && fEntry.circuit() == 5,
                "type=gtceu:assembler circuit=5",
                "type=" + fEntry.type() + " circuit=" + fEntry.circuit()));

        notes.add("C37 同配方合并电路：5→0 = " + aCircuit + " ／ 0→5 = " + bCircuit
                + " ／ 5→7 = " + cCircuit + "（conflict " + cSink.conflicts + " 行）"
                + " ／ 5→7→9 = " + dCircuit + "（conflict " + dSink.conflicts + " 行）"
                + " ／ 5→5 与 0→0 conflict=" + eSink.conflicts);
    }

    // ================================================================ 第七刀 · 超级通配符总成的电路

    /**
     * 第七刀 ①：<b>超级通配符总成</b>的电路 —— 另一套 API、<b>另一个槽号域</b>。
     *
     * <p>用户反馈原话：「2-3修好了，但是通配符还是没有电路，这可能是由于通配符的电路选择是我们那里
     * 比较特殊的选法导致的」＋ 实机读数（星律那台 {@code wildcardResolved=32 … circuits=0}）
     * ⇒ 他的电路是设<b>另一台</b>机器上的（第六刀只接了星律）。
     *
     * <p>机器侧事实（{@code SuperWildcardPatternBufferPartMachine}，逐行核过）：
     * <pre>
     *   WILDCARD_SLOT_COUNT = 10                                  （L143）
     *   internalPatternInventory#size()          → 10              （L428-430）
     *   internalPatternInventory#getStackInSlot(i) → wildcardPatternSlot[i]（L433-435）
     *   slotCircuit = new int[10]（@Persisted @DescSynced）        （L190-192）
     *   shanhaiSlotCircuit(i) / shanhaiApplySlotCircuit(i, n)      （L902-953，i ∈ 0..9）
     * </pre>
     * ⇒ <b>容器槽号 i 就是它自己的槽号 i</b>（没有 1080 那种偏移）⇒ 纯核用
     * {@link HostPatternMoverCircuit.Source#selfIndexed}（起点 0）。
     */
    private static void c41SuperWildcardCircuit(List<Case> cases, List<String> notes) {
        // ⚠️ 10 这个数在纯核里只作为"格数"输入（机器侧常量 SuperWildcardPatternBufferPartMachine
        //    .WILDCARD_SLOT_COUNT = 10；生产侧的接线由源码断言 W7-2 钉住）。纯核不 import 那台机器。
        final int slots = 10;

        // ── ① 读：第 3 格设 7 ⇒ 槽 3 读到 7，索引就是 3
        final HostPatternMoverCircuit.Source sup = HostPatternMoverCircuit.Source.selfIndexed(
                HostPatternMoverCircuit.KIND_SUPER_WILDCARD, slots, circuitOn(3, 7), null);
        final int at3 = sup.circuitAt(3);
        cases.add(new Case("C41a 超级通配符：槽 3 设 7 ⇒ 读到 7，索引就是 3（不是 1083）",
                at3 == 7 && sup.indexAt(3) == 3,
                "at3=7 索引=3", "at3=" + at3 + " 索引=" + sup.indexAt(3)));

        // ── ② 边界：0 与 9 在域内；10 出域（读 0、索引 -1）
        cases.add(new Case("C41b 超级通配符边界：0 / 9 在域内，10 出域（读到 0、索引 -1）",
                sup.indexAt(0) == 0 && sup.indexAt(9) == 9 && sup.indexAt(10) == -1
                        && sup.indexAt(-1) == -1 && sup.circuitAt(10) == 0,
                "0 / 9 / -1 / -1 且 10 号读 0",
                sup.indexAt(0) + " / " + sup.indexAt(9) + " / " + sup.indexAt(10)
                        + " / " + sup.indexAt(-1) + " 且 10 号读 " + sup.circuitAt(10)));

        // ── ③ 条目：circuit=7 真的写进 NBT 并往返；没设的那一格一个键都不多
        final HostPatternMoverEntry on = HostPatternMoverEntry.of(
                recipeIdPattern("gtceu:assembler/c41_super"), "gtceu:assembler", at3);
        final HostPatternMoverEntry onBack = HostPatternMoverEntry.fromTag(on.entryTag());
        final int at4 = sup.circuitAt(4);
        final HostPatternMoverEntry off = HostPatternMoverEntry.of(
                recipeIdPattern("gtceu:assembler/c41_super_off"), "gtceu:assembler", at4);
        cases.add(new Case("C41c 超级通配符：条目 circuit=7 往返一致；没设电路的那格【没有】该键",
                on.circuit() == 7 && onBack.circuit() == 7 && at4 == 0
                        && !off.entryTag().contains(HostPatternMoverEntry.K_CIRCUIT,
                        net.minecraft.nbt.Tag.TAG_INT),
                "circuit=7 往返=7；4 号读 0 且无键",
                "circuit=" + on.circuit() + " 往返=" + onBack.circuit() + "；4 号读 "
                        + at4 + " 且无键="
                        + !off.entryTag().contains(HostPatternMoverEntry.K_CIRCUIT,
                        net.minecraft.nbt.Tag.TAG_INT)));

        // ── ④ 🔴 负对照：把超级通配符当成"1080 起"那台（= 第六刀只支持星律时的形态）
        //    ⇒ 槽 3 一个电路都读不到。这就是用户这次看到的现象本身，用来说明 C41a/C41c 不是恒真。
        final HostPatternMoverCircuit.Source wrongOffset = HostPatternMoverCircuit.Source.mothers(
                "wrong", 1080, 50, circuitOn(3, 7), null);
        cases.add(new Case("C41NEG 用错槽号域（当成 1080 起）⇒ 槽 3 一个都读不到（负对照必须红）",
                wrongOffset.circuitAt(3) == 0 && wrongOffset.indexAt(3) == -1,
                "槽 3 读 0、索引 -1（= 只有 1083 才读得到）",
                "槽 3 读 " + wrongOffset.circuitAt(3) + "、索引 " + wrongOffset.indexAt(3)
                        + "；1083 读 " + wrongOffset.circuitAt(1083)));

        // ── ⑤ 写：放置路径打到一台假超级通配符（0..2 预填 ⇒ 样板落 3 号格）
        final FakeCircuitTarget superTarget =
                new FakeCircuitTarget("超级通配符总成", slots, "gtceu:assembler")
                        .superSource(slots, circuitOn(3, 7));
        superTarget.put(0, "recipe:pre0");
        superTarget.put(1, "recipe:pre1");
        superTarget.put(2, "recipe:pre2");
        final List<HostPatternMoverPlacer.Unit> units =
                List.of(new FakeCircuitUnit("recipe:s1", "gtceu:assembler", true, 7));
        final HostPatternMoverPlacer.Result r = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(units, List.of(superTarget)), units, List.of(superTarget));
        cases.add(new Case("C41d 超级通配符放置：机器侧收到 (索引=3, 值=7)，circuits=1、skipped=0",
                superTarget.circuitCalls == 1 && superTarget.writerCalls == 1
                        && superTarget.appliedIndex == 3 && superTarget.appliedCircuit == 7
                        && r.circuits() == 1 && r.circuitSkipped() == 0 && r.placed() == 1,
                "calls=1 writer=1 索引=3 值=7 circuits=1 skipped=0 placed=1",
                "calls=" + superTarget.circuitCalls + " writer=" + superTarget.writerCalls
                        + " 索引=" + superTarget.appliedIndex + " 值=" + superTarget.appliedCircuit
                        + " circuits=" + r.circuits() + " skipped=" + r.circuitSkipped()
                        + " placed=" + r.placed()));
        cases.add(new Case("C41e 样板也真的落进了 3 号格（写电路不替代写样板）",
                "recipe:s1".equals(superTarget.slots[3]),
                "槽3=recipe:s1", "槽3=" + superTarget.slots[3]));

        // ── ⑥ 0 = 不设置 ⇒ 一次都不许调（超级通配符这条路上同样成立）
        final FakeCircuitTarget superZero =
                new FakeCircuitTarget("超级通配符总成0", slots, "gtceu:assembler")
                        .superSource(slots, circuitOn(3, 7));
        superZero.put(0, "recipe:z0");
        superZero.put(1, "recipe:z1");
        superZero.put(2, "recipe:z2");
        final List<HostPatternMoverPlacer.Unit> zeros = List.of(
                new FakeCircuitUnit("recipe:sz", "gtceu:assembler", true, HostPatternMoverCircuit.NONE));
        final HostPatternMoverPlacer.Result rz = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(zeros, List.of(superZero)), zeros, List.of(superZero));
        cases.add(new Case("C41f 超级通配符上 circuit=0 ⇒ 机器侧一次都没调（也不计 skipped）",
                superZero.circuitCalls == 0 && superZero.writerCalls == 0
                        && rz.circuits() == 0 && rz.circuitSkipped() == 0 && rz.placed() == 1,
                "calls=0 writer=0 circuits=0 skipped=0 placed=1",
                "calls=" + superZero.circuitCalls + " writer=" + superZero.writerCalls
                        + " circuits=" + rz.circuits() + " skipped=" + rz.circuitSkipped()
                        + " placed=" + rz.placed()));
        // 负对照：去掉"0 不写"那道门（直接把 0 交给机器）⇒ writer 必然动
        final int before = superZero.writerCalls;
        superZero.source().atSlot().set(3, HostPatternMoverCircuit.NONE);
        cases.add(new Case("C41NEG2 去掉 0 那道门就真的会交给机器（负对照必须红）",
                superZero.writerCalls == before + 1,
                "writer 从 " + before + " 变 " + (before + 1),
                "writer=" + before + "→" + superZero.writerCalls));

        // ── ⑦ 星律回归：同一套算法、起点 1080 ⇒ 索引 5（第七刀没有改坏的旧行为）
        final FakeCircuitTarget stellar =
                new FakeCircuitTarget("星律样板总成", 1130, "gtceu:assembler")
                        .zoned(1080, 50)
                        .motherSource(1080, 50, circuitOn(5, 5));
        cases.add(new Case("C41g 星律回归：槽 1085 ⇒ 索引 5、读回 5；两种来源同一套索引算法",
                stellar.wildcardMotherAt(1085) == 5
                        && stellar.source().circuitAt(1085) == 5
                        && stellar.source().kind().equals(HostPatternMoverCircuit.KIND_STELLAR)
                        && sup.kind().equals(HostPatternMoverCircuit.KIND_SUPER_WILDCARD),
                "索引=5 读回=5 kind=stellar/super",
                "索引=" + stellar.wildcardMotherAt(1085) + " 读回="
                        + stellar.source().circuitAt(1085) + " kind="
                        + stellar.source().kind() + "/" + sup.kind()));

        // ── ⑧ 其它总成（Source.NONE）：不可写、写不动 —— 放置时那条 circuitSkipped 就是它
        final HostPatternMoverCircuit.Source none = HostPatternMoverCircuit.Source.NONE;
        cases.add(new Case("C41h 其它总成 = Source.NONE：不可写、applyAt 回 false（⇒ circuitSkipped）",
                !none.writableAt(0) && !none.applyAt(0, 7) && none.indexAt(0) == -1
                        && none.circuitAt(0) == 0
                        && none.kind().equals(HostPatternMoverCircuit.KIND_NONE),
                "writable=false apply=false 索引=-1 读=0 kind=none",
                "writable=" + none.writableAt(0) + " apply=" + none.applyAt(0, 7)
                        + " 索引=" + none.indexAt(0) + " 读=" + none.circuitAt(0)
                        + " kind=" + none.kind()));

        // ── ⑨ 机器"拒绝"（= 超级通配符回 false：值没变 / 该格没通配符样板）：样板照放、不算失败，
        //    只是日志走 circuit_rejected 那个用词 ⇒ 口径写清楚：circuits= 是"调了没抛"，不是"机器改了"
        final FakeCircuitTarget superReject =
                new FakeCircuitTarget("超级通配符总成R", slots, "gtceu:assembler")
                        .superSource(slots, circuitOn(3, 7));
        superReject.rejectCircuitWrites = true;
        superReject.put(0, "recipe:r0");
        final List<HostPatternMoverPlacer.Unit> rejectUnits = List.of(
                new FakeCircuitUnit("recipe:rj", "gtceu:assembler", true, 7));
        final HostPatternMoverPlacer.Result rr = HostPatternMoverPlacer.apply(
                HostPatternMoverPlacer.plan(rejectUnits, List.of(superReject)), rejectUnits,
                List.of(superReject));
        cases.add(new Case("C41j 机器拒绝（回 false）⇒ 样板照放、circuitFailed=0、不计 skipped",
                rr.placed() == 1 && "recipe:rj".equals(superReject.slots[1])
                        && rr.circuitFailed() == 0 && rr.circuitSkipped() == 0
                        && superReject.writerCalls == 1 && rr.circuits() == 1,
                "placed=1 槽1=recipe:rj failed=0 skipped=0 writer=1 circuits=1（调了没抛）",
                "placed=" + rr.placed() + " 槽1=" + superReject.slots[1]
                        + " failed=" + rr.circuitFailed() + " skipped=" + rr.circuitSkipped()
                        + " writer=" + superReject.writerCalls + " circuits=" + rr.circuits()));

        // ── ⑩ 读取汇总那一行的来源片段（用户这次"读侧一个都没抓到"要靠它判）
        final Map<String, Integer> kinds = new LinkedHashMap<>();
        kinds.put(HostPatternMoverCircuit.KIND_SUPER_WILDCARD, 1);
        final Map<String, Integer> kinds2 = new LinkedHashMap<>();
        kinds2.put(HostPatternMoverCircuit.KIND_STELLAR, 1);
        kinds2.put(HostPatternMoverCircuit.KIND_SUPER_WILDCARD, 2);
        cases.add(new Case("C41i 读取汇总带来源：src=super=1 / src=stellar=1,super=2 / 空表 ⇒ 空串",
                " src=super=1".equals(HostPatternMoverReader.describeCircuitKinds(kinds))
                        && " src=stellar=1,super=2".equals(
                        HostPatternMoverReader.describeCircuitKinds(kinds2))
                        && "".equals(HostPatternMoverReader.describeCircuitKinds(
                        new LinkedHashMap<>())),
                " src=super=1 /  src=stellar=1,super=2 / \"\"",
                " src=super=1 /  src=stellar=1,super=2 / \"\""));

        notes.add("C41 第七刀（超级通配符）：槽 3 读 " + at3 + "、4 号读 " + at4
                + "；放置时机器侧收到 索引=" + superTarget.appliedIndex + " 值="
                + superTarget.appliedCircuit + "；"
                + "用错槽号域（1080 起）时槽 3 读 " + wrongOffset.circuitAt(3)
                + "（= 用户这次的现象）；星律回归 1085→" + stellar.source().circuitAt(1085));
    }

    // ================================================================ 第七刀补 · 「整台都是通配符槽」的类型落盘

    /**
     * 第七刀补 ①：<b>超级通配符总成</b>（全通配符槽、不分普通槽/母槽）上的类型落盘。
     *
     * <p>为什么补：这是用户上一次那个「黏贴过来没配方类型」bug 的<b>同源形态</b> ——
     * 类型识别出来了、样板也放得进槽，但 {@code typeToStamp} 原来只要 {@code zoned=true} 才落盘，
     * 而超级通配符<b>不是</b>分区总成（它的 10 格全是通配符槽）⇒ 又是"进去了却没类型"。
     *
     * <p>生产侧对这台的实参逐字是
     * {@code typeToStamp(zoned=false, normalSlots=-1, wildcardSlots=10, slot, wildcard=true,
     * wildcardOnly=true, type)}（{@code HostPatternMoverTargets.java:733-735}）——
     * 这里就用同一组实参打纯核，落笔仍走机器侧同一个 {@code ShanhaiWildcardTypeNbt.write}。
     */
    private static void c42WildcardOnlyStamp(List<Case> cases, List<String> notes) {
        // = SuperWildcardPatternBufferPartMachine.WILDCARD_SLOT_COUNT（纯核这边只当"格数"用）
        final int slots = 10;
        final String type = "gtceu:assembler";

        // 🔴 生产侧对这台的实参逐字是：
        //   HostPatternMoverWildcard.stampSlotCount(wildcardOnly=true, wildcardSlots=-1, size=10) ⇒ 10
        //   typeToStamp(zoned=false, normalSlots=-1, 10, slot, wildcard=true, wildcardOnly=true, type)
        //   这里就用这两个**生产同一个函数**算，不手写 10（否则测的就不是生产那条路）
        final int stampSlots = HostPatternMoverWildcard.stampSlotCount(true, -1, slots);

        // ── ① 槽 3 ＋ 识别出 A ⇒ 要写，而且写进样板 NBT 后读得回
        final String stamp = HostPatternMoverWildcard.typeToStamp(false, -1, stampSlots, 3, true,
                true, type);
        final CompoundTag wtag = new CompoundTag();
        wtag.putString("id", "wildcard_pattern:wildcard_pattern");
        final boolean changed = com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.write(wtag, stamp);
        final String back = com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.readCanonical(wtag);
        cases.add(new Case("C42a ★超级通配符（全通配符槽, 10 格）槽 3 ＋ 类型 A ⇒ 写入且能读回 A",
                type.equals(stamp) && changed && type.equals(back),
                "stamp=A changed=true 读回=A",
                "stamp='" + stamp + "' changed=" + changed + " 读回='" + back + "'"));

        // ── ② 类型为空（多义 / 识别不到）⇒ 不写；也不许写空串覆盖
        final String none = HostPatternMoverWildcard.typeToStamp(false, -1, slots, 3, true, true, "");
        final CompoundTag emptyTag = new CompoundTag();
        emptyTag.putString("id", "wildcard_pattern:wildcard_pattern");
        final boolean emptyChanged =
                com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.write(emptyTag, none);
        cases.add(new Case("C42b 同机 type=\"\" ⇒ 不写（changed=false、读回仍是空）",
                none.isEmpty() && !emptyChanged
                        && com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt
                        .readCanonical(emptyTag).isEmpty(),
                "stamp='' changed=false 读回=''",
                "stamp='" + none + "' changed=" + emptyChanged + " 读回='"
                        + com.shanhai.machine.stellar.ShanhaiWildcardTypeNbt.readCanonical(emptyTag)
                        + "'"));

        // ── ③ 星律那台（zoned）回归：1080..1129 照旧写、普通槽不写
        final String starIn = HostPatternMoverWildcard.typeToStamp(true, 1080, 50, 1085, true, false, type);
        final String starOut = HostPatternMoverWildcard.typeToStamp(true, 1080, 50, 5, true, false, type);
        final String starEdge = HostPatternMoverWildcard.typeToStamp(true, 1080, 50, 1130, true, false, type);
        cases.add(new Case("C42c 星律 zoned 回归：1085 写、5 不写、1130（展开槽）不写",
                type.equals(starIn) && starOut.isEmpty() && starEdge.isEmpty(),
                "1085=A / 5='' / 1130=''",
                "1085='" + starIn + "' / 5='" + starOut + "' / 1130='" + starEdge + "'"));

        // ── ④ 边界：0 与 9 写；10 与 -1 不写；格数 0（不知道）不写
        cases.add(new Case("C42d 超级通配符边界：0 / 9 写，10 / -1 / 格数0 不写",
                !HostPatternMoverWildcard.typeToStamp(false, -1, slots, 0, true, true, type).isEmpty()
                        && !HostPatternMoverWildcard.typeToStamp(false, -1, slots, 9, true, true, type).isEmpty()
                        && HostPatternMoverWildcard.typeToStamp(false, -1, slots, 10, true, true, type).isEmpty()
                        && HostPatternMoverWildcard.typeToStamp(false, -1, slots, -1, true, true, type).isEmpty()
                        && HostPatternMoverWildcard.typeToStamp(false, -1, 0, 3, true, true, type).isEmpty(),
                "0=写 9=写 10='' -1='' 格数0=''",
                "0=" + HostPatternMoverWildcard.typeToStamp(false, -1, slots, 0, true, true, type)
                        + " 9=" + HostPatternMoverWildcard.typeToStamp(false, -1, slots, 9, true, true, type)
                        + " 10='" + HostPatternMoverWildcard.typeToStamp(false, -1, slots, 10, true, true, type)
                        + "' -1='" + HostPatternMoverWildcard.typeToStamp(false, -1, slots, -1, true, true, type)
                        + "' 格数0='" + HostPatternMoverWildcard.typeToStamp(false, -1, 0, 3, true, true, type) + "'"));

        // ── ⑤ 非通配符样板 ⇒ 不写（这台机器不会走到，但判据不许松）
        cases.add(new Case("C42e 全通配符槽机器上的【非通配符样板】⇒ 不写",
                HostPatternMoverWildcard.typeToStamp(false, -1, slots, 3, false, true, type).isEmpty(),
                "", "'" + HostPatternMoverWildcard.typeToStamp(false, -1, slots, 3, false, true, type) + "'"));

        // ── ⑥ ★槽数从哪来（2026-11-06 实测踩到的坑，别再踩）
        //    非分区总成的 wildcardSlotsOf(...) = -1；把它直接传给 typeToStamp ⇒ 撞上
        //    `wildcardSlots <= 0 ⇒ 不写` 那条早退 ⇒ 整个新口径等于没开。
        //    读数来自真跑：probe/ProdArgsProbe.java ⇒ prod(-1)='' / fixed(10)='gtceu:assembler'
        final int starCount = HostPatternMoverWildcard.stampSlotCount(false, 50, 1130);
        final int plainCount = HostPatternMoverWildcard.stampSlotCount(false, -1, 10);
        final String viaMinusOne = HostPatternMoverWildcard.typeToStamp(false, -1, -1, 3, true, true, type);
        cases.add(new Case("C42f ★槽数：全通配符槽用【容器槽数】(10)、分区总成仍用该区格数(50)、"
                        + "传 -1 会早退（不写）",
                stampSlots == 10 && starCount == 50 && plainCount == -1
                        && type.equals(stamp) && viaMinusOne.isEmpty(),
                "stampSlots=10 / 星律=50 / 非分区=-1；传-1⇒'' 传10⇒A",
                "stampSlots=" + stampSlots + " / 星律=" + starCount + " / 非分区=" + plainCount
                        + "；传-1⇒'" + viaMinusOne + "' 传10⇒'" + stamp + "'"));

        // ── 🔴 负对照（成对）：同一组实参但走【旧口径】（wildcardOnly=false）⇒ 一个字都不写。
        //    这条与 C42a 是同一组输入的两种口径 ⇒ C42a 的 A 只能来自新口径（不是恒真）。
        final String legacy = HostPatternMoverWildcard.typeToStamp(false, -1, slots, 3, true, false, type);
        cases.add(new Case("C42NEG 旧口径（只认 zoned）⇒ 同一格不写（负对照必须红）",
                legacy.isEmpty() && !stamp.isEmpty(),
                "旧='' 且 新=A",
                "旧='" + legacy + "' 新='" + stamp + "'"));

        notes.add("C42 第七刀补（超级通配符类型落盘）：槽 3 stamp='" + stamp + "'（槽数=" + stampSlots
                + "）changed=" + changed + " 读回='" + back + "'；type='' ⇒ changed=" + emptyChanged
                + "；星律 1085='" + starIn + "'；旧口径 slot3='" + legacy
                + "'；直接传 -1 ⇒ '" + viaMinusOne + "'");
    }

    /** 一份空的生产数据（走真 codec ⇒ 合并规则就是读取时用的那条）。 */
    static HostPatternMoverData<HostPatternMoverEntry> newMoverData() {
        return new HostPatternMoverData<>(new HostPatternMoverCodec(""));
    }
    /** 一条「指定 recipe id ＋ 类型 ＋ 电路」的合成条目。 */
    static HostPatternMoverEntry entryC(String recipeId, String type, int circuit) {
        return HostPatternMoverEntry.of(recipeIdPattern(recipeId), type, circuit);
    }

    /**
     * 造一份"面板状态包"（纯 NBT）：{@code groups} 个不同的类型、每类 1 块；
     * 版本戳 {@code rev} 写在物品 tag 根上（就是 {@code shanhai:mover_rev}）。
     */
    static HostPatternMoverPanelState panelState(int groups, long rev) {
        final List<HostPatternMoverEntry> entries = new ArrayList<>(Math.max(0, groups));
        for (int i = 0; i < groups; i++) {
            entries.add(entry("gtceu:t" + i + "/a", "gtceu:t" + i));
        }
        final CompoundTag mover = HostPatternMoverNbt.toTag(entries, "primordial_omega_engine",
                HostPatternMoverData.LIMIT_BYTES);
        final CompoundTag root = itemTagWith(mover == null ? new CompoundTag() : mover);
        root.putLong(HostPatternMoverNbt.K_REV, rev);
        return HostPatternMoverPanelState.of(root);
    }

    /** 把 {@code shanhai:mover} 那份标签包成"物品 tag 根"（{@link HostPatternMoverPanelState#of} 的口径）。 */
    static CompoundTag itemTagWith(CompoundTag mover) {
        final CompoundTag root = new CompoundTag();
        root.put(HostPatternMoverNbt.ROOT_KEY, mover);
        return root;
    }

    /**
     * <b>数日志行数</b>用的假出口（第六刀）：只数，不打印。
     *
     * <p>{@link #conflicts} 只数 {@code circuit_conflict} 那一行（按格式串认）；
     * {@link #warns} 数全部 WARN。用完<b>必须</b> {@code HostPatternMoverLog.swap(旧的)} 换回去。
     */
    static final class CountingSink implements HostPatternMoverLog.Sink {

        int conflicts;
        int warns;
        int infos;

        @Override
        public void info(String format, Object... args) {
            this.infos++;
            if (format != null && format.contains("circuit_conflict")) {
                this.conflicts++;
            }
        }

        @Override
        public void warn(String format, Object... args) {
            this.warns++;
        }
    }

    /**
     * 一台<b>与机器侧同构</b>的假星律总成：{@link #onPatternChange(String)} 就是
     * {@code StellarSampleAssemblerPartMachine#shanhaiBuildPatternTablesIfDirty} 那三行的模型 ——
     * <pre>
     *   置脏（dirty=true）→ 在批量里就 suppress（只置脏）→ 否则当场重建（rebuilds++）
     * </pre>
     * 收尾那一次走 {@link #rebuildAfterBatch()}（机器的 {@code shanhaiBatchRebuilder()} 同款：
     * 不脏就不建，建了才返回 true）。
     */
    static class FakeMachine implements HostPatternMoverBatch.Rebuilder {

        final String name;

        /** 真重建的总次数（批量外当场建 ＋ 批量收尾建）。 */
        int rebuilds;

        /** 其中"批量外当场重建"的次数（判"批量外逐位不变"用它）。 */
        int dirtyRebuilds;

        boolean dirty;

        FakeMachine(String name) {
            this.name = name;
        }

        /** 一次「这一格的样板变了」（生产 = {@code onPatternChange(slot)} → ifDirty）。 */
        void onPatternChange(String reason) {
            this.dirty = true; // 与机器侧同款：先置脏（那里是两张标记，这里一张够判据）
            if (HostPatternMoverBatch.suppress(this)) {
                return; // 批量里：只置脏，绝不重建
            }
            this.rebuild(reason);
        }

        private void rebuild(String reason) {
            this.dirty = false;
            this.rebuilds++;
            if (!"batch".equals(reason)) {
                this.dirtyRebuilds++;
            }
        }

        @Override
        public boolean rebuildAfterBatch() {
            if (!this.dirty) {
                return false;
            }
            this.rebuild("batch");
            return true;
        }

        @Override
        public String toString() {
            return this.name + "(rebuilds=" + this.rebuilds + ", dirty=" + this.dirty + ")";
        }
    }

    /**
     * <b>负对照专用的坏实现</b>：抑制点写成"永远抑制"（批量外也抑制）。
     *
     * <p>它存在的意义：证明 C25b 那条判据<b>真的能翻红</b> ——
     * 若判据写成"只要有重建就算过"，这种坏实现就会混过去。
     */
    static final class FakeMachineAlwaysSuppressed extends FakeMachine {

        FakeMachineAlwaysSuppressed(String name) {
            super(name);
        }

        @Override
        void onPatternChange(String reason) {
            // 坏实现：不判批量，直接"只置脏"（= 生产里那次 if 永远走 return）
            this.dirty = true;
        }
    }

    /**
     * 一块可清槽的假总成（{@link HostPatternMoverClear.Slots} 的断言实现）。
     *
     * <p>只有"哪几格有东西"，因为 G 的判据是<b>块数账</b>与<b>指纹变没变</b>，
     * 不涉及 ItemStack（那个要注册表、离线跑不了）。
     */
    static final class FakeSlots implements HostPatternMoverClear.Slots {

        private final String name;
        private final boolean[] filled;

        FakeSlots(String name, int size, int... filledSlots) {
            this.name = name;
            this.filled = new boolean[size];
            for (int i : filledSlots) {
                this.filled[i] = true;
            }
        }

        @Override
        public String label() {
            return this.name;
        }

        @Override
        public int size() {
            return this.filled.length;
        }

        @Override
        public boolean occupied(int slot) {
            return slot >= 0 && slot < this.filled.length && this.filled[slot];
        }

        @Override
        public boolean clear(int slot) {
            if (slot < 0 || slot >= this.filled.length) {
                return false;
            }
            this.filled[slot] = false;
            return true;
        }
    }

    /**
     * 玩家侧的假实现：背包只能收 {@code invCap} 块、掉落只能成功 {@code dropCap} 次。
     *
     * <p>它<b>不</b>模拟 ItemStack（那个要注册表、离线跑不了），只记录"收下了哪几块"——
     * 因为这一组断言要的是<b>账</b>（交出去的块数 = 背包 ＋ 掉落），不是物品实体。
     */
    static final class FakeHandout implements HostPatternMoverTakeout.Handout {
        private final int invCap;
        private final int dropCap;
        private int invUsed;
        private int dropUsed;
        final List<Integer> touched = new ArrayList<>();
        boolean wantedTypesOnly = true;

        FakeHandout(int invCap, int dropCap) {
            this.invCap = invCap;
            this.dropCap = dropCap;
        }

        @Override
        public boolean toInventory(HostPatternMoverPlacer.Unit unit, int index) {
            if (!"A".equals(unit.type())) {
                this.wantedTypesOnly = false;
            }
            if (this.invUsed >= this.invCap) {
                return false;
            }
            this.invUsed++;
            this.touched.add(Integer.valueOf(index));
            return true;
        }

        @Override
        public boolean drop(HostPatternMoverPlacer.Unit unit, int index) {
            if (!"A".equals(unit.type())) {
                this.wantedTypesOnly = false;
            }
            if (this.dropUsed >= this.dropCap) {
                return false;
            }
            this.dropUsed++;
            this.touched.add(Integer.valueOf(index));
            return true;
        }
    }

    // ================================================================ 夹具

    /** 断言用的最小载荷（有 size 字段，好写「后一条覆盖前一条」的判据）。 */
    static final class Payload {
        final String key;
        final String type;
        final int size;

        Payload(String key, String type, int size) {
            this.key = key;
            this.type = type;
            this.size = size;
        }
    }

    /**
     * 假的字节模型：{@code 20 + Σ(entry.size)}。
     *
     * <p>它存在的意义：让「闸」的测试<b>不依赖</b> NBT 的真实体积 —— 200 B 的现场是这么造出来的
     * （真实的 NBT 单块就 481+ B，压不到 200 B 以下）。
     */
    static class FakeCodec implements HostPatternMoverData.Codec<Payload> {
        private final int cap;

        FakeCodec(int cap) {
            this.cap = cap;
        }

        @Override
        public int measureBytes(List<Payload> entries) {
            int sum = 20;
            for (Payload p : entries) {
                sum += p.size;
            }
            return sum;
        }

        @Override
        public String dedupKey(Payload entry) {
            return entry == null ? "" : entry.key;
        }

        @Override
        public String typeOf(Payload entry) {
            return entry == null ? "" : entry.type;
        }
    }

    /**
     * <b>坏实现</b>（负对照）：先加进列表、再量字节、量完超了就返回拒绝 ——
     * 也就是「先写后报错」。它<b>故意</b>不继承真实现（真实现是 {@code final}，
     * 而且「不许继承就能改坏」本身也是这条纪律的一部分）：它只复刻<b>顺序</b>上的错误。
     *
     * <p>它存在的唯一目的：证明 C2c 那类断言<b>真的能看见这个病</b>。
     */
    static final class BadData {

        private final List<Payload> raw = new ArrayList<>();
        private final int limit;

        BadData(int limit) {
            this.limit = limit;
        }

        HostPatternMoverData.AddOutcome add(Payload entry) {
            // 🔴 故意的错误顺序：先写
            this.raw.add(entry);
            // 再量
            int sum = 20;
            for (Payload p : this.raw) {
                sum += p.size;
            }
            // 量完发现超了才"报错" —— 此时数据已经被污染
            if (sum > this.limit) {
                return new HostPatternMoverData.AddOutcome(
                        HostPatternMoverData.AddStatus.REJECTED_FULL, entry.key, sum, sum, sum, this.limit);
            }
            return new HostPatternMoverData.AddOutcome(
                    HostPatternMoverData.AddStatus.ADDED, entry.key, sum, sum, sum, this.limit);
        }

        int size() {
            return this.raw.size();
        }

        String fingerprint() {
            final StringBuilder sb = new StringBuilder();
            for (Payload p : this.raw) {
                sb.append(p.key).append('#').append(p.size).append(';');
            }
            return sb.toString();
        }
    }

    /** 假总成：{@code slots} 个格子里指定下标非空。 */
    static class FakeAssembly implements HostPatternMoverScan.SlotView<HostPatternMoverEntry> {
        final String name;
        final Object[] slots;

        FakeAssembly(String name, int size, int[] filled) {
            this.name = name;
            this.slots = new Object[size];
            for (int i : filled) {
                this.slots[i] = HostPatternMoverEntry.of(
                        recipeIdPattern("gtceu:assembler/" + name + "_" + i), "gtceu:assembler");
            }
        }

        @Override
        public String label() {
            return this.name + ":" + this.slots.length;
        }

        @Override
        public int size() {
            return this.slots.length;
        }

        @Override
        public HostPatternMoverEntry view(int slot) {
            return (HostPatternMoverEntry) this.slots[slot];
        }

        String fingerprint() {
            final StringBuilder sb = new StringBuilder();
            for (Object o : this.slots) {
                sb.append(o == null ? "-" : Integer.toHexString(o.hashCode())).append(',');
            }
            return sb.toString();
        }
    }

    /** 造一块"像真样板"的合成 NBT（形状照真实 AE 处理样板：{@code {id, Count, tag:{in,out,…}}}）。 */
    static CompoundTag recipeIdPattern(String recipeId) {
        return syntheticPattern(recipeId, 10, 5);
    }

    /**
     * 内容<b>真的不同</b>的一块（用于「不同内容 ⇒ 不同键」这条，且不能靠配方 id 拉开差距）。
     *
     * <p>为什么需要它：{@link #stripRecipeId} 把配方 id 抹掉之后，两块只差 id 的样板会变成
     * <b>逐字节相同</b> ⇒ 那时「不同内容」这个前提本身就不成立了（第一版自检就在这里栽过一次）。
     */
    static CompoundTag differentContentPattern() {
        final CompoundTag stack = syntheticPattern("gtceu:assembler/b", 10, 5);
        final CompoundTag tag = stack.getCompound("tag");
        tag.put("out", ingredientList("gtceu:aluminium_dust", 5));
        tag.remove(HostPatternMoverEntry.KEY_RECIPE_ID);
        return stack;
    }

    /**
     * 合成一块样板。
     *
     * <p>⚠️ 条数的<b>代表性</b>：真实存档 42 块实测 <b>481–1315 B、p50 909 B</b>
     * （{@code temp/pattern-mover/取证.md} §5.3）。本方法按 {@code in/out} 条数扫出接近 909 B 的形状，
     * 让 C3 的「1.5 MiB 能装几块」与真实读数可比 —— 但它是<b>合成</b>的，报告里两个数都要写。
     */
    static CompoundTag syntheticPattern(String recipeId, int inCount, int outCount) {
        final CompoundTag stack = new CompoundTag();
        stack.putString("id", "ae2:processing_pattern");
        stack.putByte("Count", (byte) 1);
        final CompoundTag tag = new CompoundTag();
        tag.put("in", ingredientList("gtceu:aluminium_ingot", inCount));
        tag.put("out", ingredientList("gtceu:aluminium_nugget", outCount));
        if (recipeId != null && !recipeId.isEmpty()) {
            tag.putString(HostPatternMoverEntry.KEY_RECIPE_ID, recipeId);
        }
        stack.put("tag", tag);
        return stack;
    }

    /** 去掉配方 id（用来测「取不到 id ⇒ 落到字节身份」那条路）。 */
    static CompoundTag stripRecipeId(CompoundTag stack) {
        final CompoundTag copy = stack.copy();
        if (copy.contains("tag", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            copy.getCompound("tag").remove(HostPatternMoverEntry.KEY_RECIPE_ID);
        }
        return copy;
    }

    static ListTag ingredientList(String itemId, int n) {
        final ListTag list = new ListTag();
        for (int i = 0; i < n; i++) {
            final CompoundTag e = new CompoundTag();
            e.putString("#c", "ae2:i");
            e.putLong("#", (long) (i + 1));
            e.putString("id", itemId);
            list.add(e);
        }
        return list;
    }

    /** 一条带 recipe id 的合成条目（C3/C5/C6 用）。 */
    static HostPatternMoverEntry syntheticEntry(int n) {
        return syntheticEntry(n, HostPatternMoverCircuit.NONE);
    }

    /**
     * 同上，但可以带编程电路（第六刀 C35 的「1.5 MiB 装几块」实测用）。
     *
     * <p>🔴 除电路外<b>逐字节相同</b>的合成样板 ⇒ 两次填充的差就是那 14 B/块造成的。
     */
    static HostPatternMoverEntry syntheticEntry(int n, int circuit) {
        return HostPatternMoverEntry.of(recipeIdPattern("gtceu:assembler/synthetic_" + n),
                n % 3 == 0 ? "" : "gtceu:assembler", circuit);
    }

    static HostPatternMoverEntry entry(String recipeId, String type) {
        return HostPatternMoverEntry.of(recipeIdPattern(recipeId), type);
    }
}
