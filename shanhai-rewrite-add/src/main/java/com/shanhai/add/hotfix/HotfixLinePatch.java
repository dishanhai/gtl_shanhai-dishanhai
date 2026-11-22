package com.shanhai.add.hotfix;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * <b>普通攻击（{@code attack=line}）：只废掉"写受保护状态的那一条指令"，方法其余逻辑照跑。</b>
 *
 * <h2>它改什么</h2>
 * <table border="1">
 *   <tr><th>站点</th><th>原始指令</th><th>换成</th><th>为什么栈效应相同</th></tr>
 *   <tr><td>{@code setHealth}</td>
 *       <td>{@code INVOKEVIRTUAL LivingEntity.m_21153_ (F)V}</td>
 *       <td>{@code INVOKESTATIC HotfixShield.dropSetHealth (Ljava/lang/Object;F)V}</td>
 *       <td>都是「弹出 1 个引用槽 ＋ 1 个 float 槽、不压返回值」</td></tr>
 *   <tr><td>{@code DATA_HEALTH_ID} 直写（2 参）</td>
 *       <td>{@code INVOKEVIRTUAL SynchedEntityData.set (Accessor;Object)V}</td>
 *       <td>{@code INVOKESTATIC HotfixShield.dropHealthData (Ljava/lang/Object;Ljava/lang/Object;)V}</td>
 *       <td>同上（两个引用槽）</td></tr>
 *   <tr><td>{@code DATA_HEALTH_ID} 直写（3 参）</td>
 *       <td>{@code INVOKEVIRTUAL SynchedEntityData.set (Accessor;Object;Z)V}</td>
 *       <td>{@code INVOKESTATIC HotfixShield.dropHealthDataForce (…;Z)V}</td>
 *       <td>同上（＋一个 int 槽）</td></tr>
 * </table>
 * 🔴 <b>形参一律用 {@code Object} 接接收者</b>：这样<b>任何</b>接收者类型都通过校验
 * （不需要断言它是不是 {@code LivingEntity}），也就不需要动 {@code StackMapTable}。
 * 而"接收者到底是不是 {@code LivingEntity}"这一条由<b>下面这道判据</b>在改写前解决：
 * 只有当 owner 沿继承链能找到 {@code net.minecraft.world.entity.LivingEntity} 时才接受
 * （用 {@code Class#getSuperclass()} 走链，<b>按类名比</b>，所以本类不必 import 那个类型）。
 *
 * <h2>🔴 为什么绝不按字节偏移改</h2>
 * 实测（{@code temp\hotfix\报告.md} §5.1）：{@code retransformClasses} 交下来的
 * {@code classfileBuffer} 常量池<b>已经被合并过</b>（同长度、内容不同）⇒ 它在磁盘那份 class 里的
 * 偏移位置上<b>不是</b>同一个东西。任何"按偏移改几个字节"的做法都会改错地方。
 * ⇒ 唯一可行的是 {@link ClassReader} 读成结构 → {@code InsnList#set} 换掉那几个节点 →
 * {@link ClassWriter} 重新写出。<b>指令条数不变</b>，所以偏移与 {@code StackMapTable} 全都不动。
 *
 * <h2>三道自证（不通过就整份拒绝，返回 {@code null}）</h2>
 * <ol>
 *   <li><b>形状不变</b>：类名/父类/接口/版本/字段表/方法表逐项相等；</li>
 *   <li><b>别的方法没动</b>：除目标之外，每个方法的「指令条数＋行号条数＋首尾操作码」指纹不变；</li>
 *   <li><b>目标方法里</b>：指令总数不变、我们写进去的 {@code INVOKESTATIC} 条数 == 站点数、
 *       且原来的写点一条都不剩。</li>
 * </ol>
 *
 * <h2>做不到的时候</h2>
 * <pre>
 *   line_no_site          这个方法里根本没有"写受保护状态"的指令 ⇒ 该升级成强力攻击（method）
 *   line_unresolvable     目标类的加载器解析不到 HotfixShield ⇒ 不许插（否则运行期 NoClassDefFoundError）
 *   line_ambiguous_name   同名重载挑不出来（猜错方法比不动手更糟）
 *   line_forbidden_special  <init>/<clinit> 不许改（未初始化的 this 传给静态方法会被校验器拒）
 * </pre>
 */
public final class HotfixLinePatch {

    /** 匹配口径：按（方法名＋描述符）精确匹配。 */
    public static final String MATCH_DESC = "desc";
    /** 匹配口径：只有方法名，且同名方法唯一。 */
    public static final String MATCH_NAME_ONLY = "name_only";
    /** 匹配口径：只有方法名，靠栈帧行号在多个同名重载里挑出来的。 */
    public static final String MATCH_NAME_LINE = "name_line";

    /** {@code setHealth} 的两个名字（开发名 ＋ SRG 名；都是原版字面量）。 */
    private static final String[] SET_HEALTH_NAMES = {"setHealth", "m_21153_"};

    /** {@code DATA_HEALTH_ID} 的两个名字。 */
    private static final String[] HEALTH_FIELD_NAMES = {"DATA_HEALTH_ID", "f_20961_"};

    /** {@code LivingEntity} 的源级名（判"接收者是不是一个活着的实体"，只比类名）。 */
    private static final String LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity";

    /** {@code SynchedEntityData} 的源级名。 */
    private static final String SYNCED_DATA = "net.minecraft.network.syncher.SynchedEntityData";

    /** {@code EntityDataAccessor} 的描述符（{@code SynchedEntityData#set} 的第 0 个参数）。 */
    private static final String DESC_ACCESSOR = "Lnet/minecraft/network/syncher/EntityDataAccessor;";

    /** 一个站点。 */
    public static final class Site {

        /** {@code setHealth|data_health|data_health3}（进日志的 {@code site=}）。 */
        public final String site;

        /** 被替换指令的方法名（原样的，可能是 SRG 名）。 */
        public final String target;

        Site(String site, String target) {
            this.site = site;
            this.target = target == null ? "?" : target;
        }

        @Override
        public String toString() {
            return site + ":" + target;
        }
    }

    /** 改写结果（不可变；失败时 {@link #bytes} 为 {@code null}）。 */
    public static final class Result {

        /** 改好的 class 字节；{@code null} = 被拒绝（原因见 {@link #reason}）。 */
        public final byte[] bytes;

        /** {@code ok} 或一条机器可判的拒绝原因。 */
        public final String reason;

        /** 目标方法的描述符（匹配成功时一定非空）。 */
        public final String methodDesc;

        /** 实际用的匹配口径。 */
        public final String match;

        /** 真的换掉了几条指令（成功率 > 0 才 {@code ok}）。 */
        public final int sites;

        /** 每个站点的名字（{@code setHealth:10} 这种形态，进日志与播报）。 */
        public final String siteList;

        /** 目标方法改写前后的指令条数（必须相等 —— 这就是"偏移与 StackMapTable 不用动"的证据）。 */
        public final int insnsBefore;
        public final int insnsAfter;

        Result(byte[] bytes, String reason, String methodDesc, String match,
               int sites, String siteList, int insnsBefore, int insnsAfter) {
            this.bytes = bytes;
            this.reason = reason;
            this.methodDesc = methodDesc == null ? "" : methodDesc;
            this.match = match == null ? "none" : match;
            this.sites = sites;
            this.siteList = siteList == null ? "" : siteList;
            this.insnsBefore = insnsBefore;
            this.insnsAfter = insnsAfter;
        }

        public boolean ok() {
            return bytes != null;
        }

        @Override
        public String toString() {
            return "ok=" + ok() + " reason=" + reason + " desc=" + methodDesc + " match=" + match
                    + " sites=" + sites + " site_list=" + siteList
                    + " insns=" + insnsBefore + "->" + insnsAfter;
        }
    }

    private HotfixLinePatch() {
        // 纯静态工具
    }

    /**
     * 把目标方法里"写受保护状态"的那几条指令换成 {@link HotfixShield} 的静态调用。
     *
     * @param classBytes 原始 class 字节（transformer 收到的 {@code classfileBuffer}）
     * @param methodName 目标方法名（栈帧给出的那个）
     * @param methodDesc 目标方法描述符；{@code null} = 栈帧里拿不到，按名称/行号挑
     * @param lineHint   栈帧里的行号（{@code -1}/非法 = 没有），只用于在同名重载里挑
     * @param loader     目标类的加载器（判"能不能解析到 {@link HotfixShield}"）
     * @return 成功时 {@code bytes} 是新 class（<b>长度会变</b>，指令条数不变）
     */
    public static Result patch(byte[] classBytes, String methodName, String methodDesc, int lineHint,
                               ClassLoader loader) {
        if (classBytes == null || classBytes.length < 16) {
            return fail("bad_class_bytes", methodDesc);
        }
        if (methodName == null || methodName.isEmpty()) {
            return fail("empty_method_name", methodDesc);
        }
        if (methodName.charAt(0) == '<') {
            // <init> 里可能把"未初始化的 this"当接收者传给静态方法 ⇒ 校验器会拒（VerifyError）
            return fail("forbidden_special_method", methodDesc);
        }
        if (!HotfixShield.resolvableFrom(loader)) {
            // 插进去的那条 INVOKESTATIC 会在别人家的方法体里抛 NoClassDefFoundError
            return fail("line_unresolvable", methodDesc);
        }
        final ClassNode before = HotfixMethodBodyPatch.read(classBytes);
        if (before == null) {
            return fail("class_reader_failed", methodDesc);
        }
        // ---- ① 选方法（与 HotfixMethodBodyPatch 同一套口径）
        final List<MethodNode> named = new ArrayList<>(4);
        for (int i = 0; i < before.methods.size(); i++) {
            final MethodNode mn = before.methods.get(i);
            if (mn != null && methodName.equals(mn.name)) {
                named.add(mn);
            }
        }
        if (named.isEmpty()) {
            return fail("method_not_found", methodDesc);
        }
        MethodNode target;
        String match;
        if (methodDesc != null && !methodDesc.isEmpty()) {
            target = null;
            for (int i = 0; i < named.size(); i++) {
                if (methodDesc.equals(named.get(i).desc)) {
                    target = named.get(i);
                    break;
                }
            }
            if (target == null) {
                return fail("method_desc_not_found", methodDesc);
            }
            match = MATCH_DESC;
        } else if (named.size() == 1) {
            target = named.get(0);
            match = MATCH_NAME_ONLY;
        } else {
            target = pickByLine(named, lineHint);
            if (target == null) {
                return fail("method_ambiguous_name", methodDesc);
            }
            match = MATCH_NAME_LINE;
        }
        if ((target.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || target.instructions == null || target.instructions.size() == 0) {
            return fail("no_code", target.desc);
        }
        // ---- ② 找站点
        final List<MethodInsnNode> hits = new ArrayList<>(4);
        final List<Site> sites = new ArrayList<>(4);
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) {
                continue;
            }
            final MethodInsnNode m = (MethodInsnNode) insn;
            final Site s = classify(m, loader);
            if (s != null) {
                hits.add(m);
                sites.add(s);
            }
        }
        if (hits.isEmpty()) {
            return fail("line_no_site", target.desc);
        }
        // ---- ③ 自证基线
        final String shapeBefore = HotfixMethodBodyPatch.describeShape(before);
        final Map<String, String> printsBefore = fingerprints(before);
        final String targetKey = target.name + target.desc;
        final int insnsBefore = countInstructions(target);
        // ---- ④ 只换那几条指令（指令条数不变）
        for (int i = 0; i < hits.size(); i++) {
            final MethodInsnNode old = hits.get(i);
            final Site s = sites.get(i);
            target.instructions.set(old, replacement(s));
        }
        final byte[] out;
        try {
            // ClassWriter(0)：原样复制每个方法的 maxs 与 StackMapTable —— 所以"只换了那几个节点"
            // 这件事是可自证的（COMPUTE_FRAMES 会重新生成全部帧，等于让没被要求改的方法也换字节）。
            final ClassWriter cw = new ClassWriter(0);
            before.accept(cw);
            out = cw.toByteArray();
        } catch (Throwable t) {
            return fail("class_writer_failed", target.desc);
        }
        // ---- ⑤ 自证 ①：形状不变
        final ClassNode after = HotfixMethodBodyPatch.read(out);
        if (after == null) {
            return fail("writer_output_unreadable", target.desc);
        }
        if (!shapeBefore.equals(HotfixMethodBodyPatch.describeShape(after))) {
            return fail("shape_changed", target.desc);
        }
        // ---- ⑥ 自证 ②：别的方法一个都没动
        final Map<String, String> printsAfter = fingerprints(after);
        if (printsAfter.size() != printsBefore.size()) {
            return fail("method_count_changed", target.desc);
        }
        for (Map.Entry<String, String> e : printsBefore.entrySet()) {
            if (targetKey.equals(e.getKey())) {
                continue;
            }
            if (!e.getValue().equals(printsAfter.get(e.getKey()))) {
                return fail("other_method_changed:" + e.getKey(), target.desc);
            }
        }
        // ---- ⑦ 自证 ③：目标方法的指令数与站点数都对得上
        final MethodNode patched = find(after, target.name, target.desc);
        if (patched == null) {
            return fail("self_check_method_missing", target.desc);
        }
        final int insnsAfter = countInstructions(patched);
        if (insnsAfter != insnsBefore) {
            return fail("insn_count_changed:" + insnsBefore + "->" + insnsAfter, target.desc);
        }
        int statics = 0;
        int leftover = 0;
        for (AbstractInsnNode insn = patched.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) {
                continue;
            }
            final MethodInsnNode m = (MethodInsnNode) insn;
            if (HotfixShield.SELF_INTERNAL.equals(m.owner) && m.getOpcode() == Opcodes.INVOKESTATIC) {
                statics++;
            } else if (classify(m, loader) != null) {
                leftover++;
            }
        }
        if (statics != hits.size() || leftover != 0) {
            return fail("self_check_sites:" + statics + "/" + hits.size() + "_left=" + leftover, target.desc);
        }
        final StringBuilder siteList = new StringBuilder(48);
        for (int i = 0; i < sites.size(); i++) {
            if (i > 0) {
                siteList.append(',');
            }
            siteList.append(sites.get(i));
        }
        return new Result(out, "ok", target.desc, match, hits.size(), siteList.toString(),
                insnsBefore, insnsAfter);
    }

    // ------------------------------------------------------------------ 站点识别

    /**
     * 这条调用是不是"写受保护状态"的站点。
     *
     * @return 命中 ⇒ 站点描述；不命中 ⇒ {@code null}
     */
    static Site classify(MethodInsnNode m, ClassLoader loader) {
        if (m == null || m.owner == null) {
            return null;
        }
        // ---- 站点 1：setHealth(float)
        if (m.getOpcode() == Opcodes.INVOKEVIRTUAL || m.getOpcode() == Opcodes.INVOKEINTERFACE) {
            if ("(F)V".equals(m.desc) && nameIn(m.name, SET_HEALTH_NAMES) && isLivingEntity(m.owner, loader)) {
                return new Site(HotfixShield.SITE_SET_HEALTH, m.name);
            }
            // ---- 站点 2/3：写同步血量字段
            //   ⚠️ 描述符必须以 '(' 开头：{@code (Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V}
            if (SYNCED_DATA.equals(m.owner.replace('/', '.'))) {
                if (("(" + DESC_ACCESSOR + "Ljava/lang/Object;)V").equals(m.desc)) {
                    return new Site(HotfixShield.SITE_DATA2, m.name);
                }
                if (("(" + DESC_ACCESSOR + "Ljava/lang/Object;Z)V").equals(m.desc)) {
                    return new Site(HotfixShield.SITE_DATA3, m.name);
                }
            }
        }
        return null;
    }

    /** 这条调用是不是"写受保护状态"的站点（不对 owner 做继承链检查的宽松版；{@link HotfixShield} 侧用）。 */
    static boolean nameIn(String name, String[] candidates) {
        if (name == null || candidates == null) {
            return false;
        }
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i].equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code owner}（内部名）是不是一个活着的实体（沿继承链按<b>类名</b>比，不 import 该类型）。
     *
     * <p>拿 {@code loader} 加载不到时退化成"包名以 {@code net/minecraft/} 开头"这条更宽的判据
     * —— 因为 {@code setHealth(float)} 在 1.20.1 上只有 {@code LivingEntity} 一处声明
     * （javap 已证：{@code public void setHealth(float); descriptor: (F)V}，全类只有一个）。
     */
    static boolean isLivingEntity(String ownerInternal, ClassLoader loader) {
        if (ownerInternal == null || ownerInternal.isEmpty()) {
            return false;
        }
        final String dotted = ownerInternal.replace('/', '.');
        if (LIVING_ENTITY.equals(dotted)) {
            return true;
        }
        if (dotted.startsWith("net.minecraft.")) {
            try {
                Class<?> c = Class.forName(dotted, false, loader);
                int guard = 0;
                while (c != null && guard++ < 64) {
                    if (LIVING_ENTITY.equals(c.getName())) {
                        return true;
                    }
                    c = c.getSuperclass();
                }
                return false;
            } catch (Throwable ignored) {
                // 加载不到 ⇒ 退化成下面的"包名"判据
                return true;
            }
        }
        // 第三方类：必须能沿继承链找到 LivingEntity，否则不许当成站点
        try {
            Class<?> c = Class.forName(dotted, false, loader);
            int guard = 0;
            while (c != null && guard++ < 64) {
                if (LIVING_ENTITY.equals(c.getName())) {
                    return true;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    /** 一个站点对应的替换指令。 */
    private static MethodInsnNode replacement(Site s) {
        if (HotfixShield.SITE_SET_HEALTH.equals(s.site)) {
            return new MethodInsnNode(Opcodes.INVOKESTATIC, HotfixShield.SELF_INTERNAL,
                    HotfixShield.CALL_SET_HEALTH, HotfixShield.DESC_SET_HEALTH, false);
        }
        if (HotfixShield.SITE_DATA3.equals(s.site)) {
            return new MethodInsnNode(Opcodes.INVOKESTATIC, HotfixShield.SELF_INTERNAL,
                    HotfixShield.CALL_DATA3, HotfixShield.DESC_DATA3, false);
        }
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HotfixShield.SELF_INTERNAL,
                HotfixShield.CALL_DATA2, HotfixShield.DESC_DATA2, false);
    }

    // ------------------------------------------------------------------ 小工具（与 HotfixMethodBodyPatch 同口径）

    /** 目标方法里 {@code GETSTATIC DATA_HEALTH_ID} 出现过没有（诊断用；不参与判定）。 */
    static boolean mentionsHealthField(MethodNode mn) {
        if (mn == null || mn.instructions == null) {
            return false;
        }
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode) {
                final FieldInsnNode f = (FieldInsnNode) insn;
                if (nameIn(f.name, HEALTH_FIELD_NAMES) && LIVING_ENTITY.equals(f.owner.replace('/', '.'))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 指令总条数（含标签/行号/帧节点，与 ASM 的 {@code size()} 同口径）。 */
    static int countInstructions(MethodNode mn) {
        return mn == null || mn.instructions == null ? 0 : mn.instructions.size();
    }

    /** 每个方法一个指纹：{@code 指令条数 + 行号条数 + 首操作码 + 末操作码 + maxStack + maxLocals}。 */
    private static Map<String, String> fingerprints(ClassNode cn) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < cn.methods.size(); i++) {
            final MethodNode mn = cn.methods.get(i);
            int count = 0;
            int lines = 0;
            int first = -1;
            int last = -1;
            if (mn.instructions != null) {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getType() == AbstractInsnNode.LINE) {
                        lines++;
                    }
                    if (first < 0) {
                        first = insn.getOpcode();
                    }
                    last = insn.getOpcode();
                    count++;
                }
            }
            out.put(mn.name + mn.desc, count + "/" + lines + "/" + first + "/" + last
                    + "/" + mn.maxStack + "/" + mn.maxLocals);
        }
        return out;
    }

    private static MethodNode find(ClassNode cn, String name, String desc) {
        if (cn == null) {
            return null;
        }
        for (int i = 0; i < cn.methods.size(); i++) {
            final MethodNode mn = cn.methods.get(i);
            if (mn != null && name.equals(mn.name) && desc.equals(mn.desc)) {
                return mn;
            }
        }
        return null;
    }

    private static MethodNode pickByLine(List<MethodNode> named, int lineHint) {
        if (lineHint <= 0) {
            return null;
        }
        MethodNode best = null;
        int bestLine = Integer.MIN_VALUE;
        boolean tie = false;
        for (int i = 0; i < named.size(); i++) {
            final MethodNode mn = named.get(i);
            final int atOrBelow = maxLineAtOrBelow(mn, lineHint);
            if (atOrBelow < 0) {
                continue;
            }
            if (atOrBelow > bestLine) {
                bestLine = atOrBelow;
                best = mn;
                tie = false;
            } else if (atOrBelow == bestLine) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    private static int maxLineAtOrBelow(MethodNode mn, int hint) {
        if (mn.instructions == null) {
            return -1;
        }
        int best = -1;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getType() == AbstractInsnNode.LINE) {
                final int line = ((LineNumberNode) insn).line;
                if (line > 0 && line <= hint && line > best) {
                    best = line;
                }
            }
        }
        return best;
    }

    /** 一个方法的返回类型（播报/日志用；拿不到 ⇒ {@code ?}）。 */
    static String returnKind(String methodDesc) {
        try {
            final Type ret = Type.getReturnType(methodDesc);
            return ret == null ? "?" : String.valueOf(ret.getSort());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static Result fail(String reason, String desc) {
        return new Result(null, reason, desc, "none", 0, "", 0, 0);
    }
}
