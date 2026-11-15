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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * <b>把一个方法的方法体换成"安全默认返回"</b>——用 ASM 做<b>结构级</b>改写，其余一概原样写回。
 *
 * <h2>🔴 为什么绝不能做"字节偏移替换"</h2>
 * 实测（{@code temp\hotfix\报告.md} §5.1）：{@code retransformClasses} 交下来的
 * {@code classfileBuffer} 常量池<b>已经被合并过</b>（同长度、内容不同）⇒ 它在磁盘那份 class 里的
 * 偏移位置上<b>不是</b>同一个东西。任何"按偏移改几个字节"的做法都会改错地方。
 * ⇒ 唯一可行的路是：{@link ClassReader} 读成结构 → 只动目标 {@link MethodNode} 的方法体 →
 * {@link ClassWriter} 重新写出。代价是新字节的<b>长度和偏移全都变了</b>（这不是 bug，是这条路的本质）。
 *
 * <h2>替换规则（规格逐条）</h2>
 * <table border="1">
 *   <tr><th>返回类型</th><th>指令</th><th>{@code default=} 字段</th></tr>
 *   <tr><td>{@code void}</td><td>{@code RETURN}</td><td>{@code void:RETURN}</td></tr>
 *   <tr><td>{@code boolean}</td><td>{@code ICONST_0 IRETURN}</td><td>{@code boolean:false}</td></tr>
 *   <tr><td>{@code byte}/{@code char}/{@code short}/{@code int}</td><td>{@code ICONST_0 IRETURN}</td>
 *       <td>{@code byte:0}/{@code char:0}/{@code short:0}/{@code int:0}</td></tr>
 *   <tr><td>{@code long}</td><td>{@code LCONST_0 LRETURN}</td><td>{@code long:0L}</td></tr>
 *   <tr><td>{@code float}</td><td>{@code FCONST_0 FRETURN}</td><td>{@code float:0.0}</td></tr>
 *   <tr><td>{@code double}</td><td>{@code DCONST_0 DRETURN}</td><td>{@code double:0.0}</td></tr>
 *   <tr><td>引用／数组</td><td>{@code ACONST_NULL ARETURN}</td><td>{@code ref:null}</td></tr>
 * </table>
 * <p><b>签名与访问标志一个字都不动</b>：我们只碰 {@code MethodNode.instructions}（以及它自己那几个
 * 「指向指令里 Label 的附属表」：try-catch、局部变量表），{@code name/desc/access/exceptions/
 * signature/annotation} 全部原样。
 *
 * <h2>为什么 {@link ClassWriter#ClassWriter(int)} 用 {@code 0} 而不用 {@code COMPUTE_MAXS}</h2>
 * {@code COMPUTE_MAXS} 会<b>重算所有方法</b>的 {@code maxStack/maxLocals} —— 那等于让"没被要求改的
 * 方法"也换了字节。用 {@code 0} 时 ASM 原样复制每个 {@link MethodNode} 的 maxs 与 StackMapTable，
 * 于是"只改了这一个方法"这件事变成<b>可自证</b>的（见下面的自证 ②③）：目标方法的 maxStack 由我们
 * 显式抬到够用（新方法体最多压 2 个字：long/double），其余方法的指纹必须逐条不变。
 *
 * <h2>三道自证（不通过就整份拒绝，返回 {@code null}）</h2>
 * <ol>
 *   <li><b>形状不变</b>：类名/父类/接口/版本/字段表/方法表（名＋描述符＋访问标志）逐项相等；</li>
 *   <li><b>别的方法没动</b>：除目标之外，每个方法的「指令条数＋首尾操作码＋行号条数」指纹必须不变；</li>
 *   <li><b>目标真的被换掉了</b>：重新读一遍产物，目标方法的指令必须<b>正好</b>是我们写的那两条。</li>
 * </ol>
 *
 * <h2>匹配口径（描述符拿不到时）</h2>
 * 栈帧只能给出「类名＃方法名:行号」，<b>没有描述符</b>（{@code temp\hotfix\报告.md} 里那条规格）。
 * 于是：① 给得出描述符 ⇒ 按 (名,描述符) 精确匹配；
 * ② 只有方法名 ⇒ 唯一同名 ⇒ 用；同名多个 ⇒ 用行号挑（"行号落在它的行号表区间里"），
 * 仍分不开 ⇒ 拒绝（{@code method_ambiguous_name}）——<b>猜错方法比不动手更糟</b>。
 */
public final class HotfixMethodBodyPatch {

    /** 匹配口径：按（方法名＋描述符）精确匹配。 */
    public static final String MATCH_DESC = "desc";

    /** 匹配口径：只有方法名，且同名方法唯一。 */
    public static final String MATCH_NAME_ONLY = "name_only";

    /** 匹配口径：只有方法名，靠栈帧行号在多个同名重载里挑出来的。 */
    public static final String MATCH_NAME_LINE = "name_line";

    private HotfixMethodBodyPatch() {
        // 纯静态工具
    }

    /** 改写结果（不可变；失败时 {@link #bytes} 为 {@code null}）。 */
    public static final class Result {

        /** 改好的 class 字节；{@code null} = 被拒绝（原因见 {@link #reason}）。 */
        public final byte[] bytes;

        /** {@code ok} 或一条机器可判的拒绝原因。 */
        public final String reason;

        /** 目标方法的描述符（匹配成功时一定非空）。 */
        public final String methodDesc;

        /** 目标方法编译出来的第一行（没有行号表 ⇒ -1）。 */
        public final int firstLine;

        /** 实际用的匹配口径：{@link #MATCH_DESC}／{@link #MATCH_NAME_ONLY}／{@link #MATCH_NAME_LINE}。 */
        public final String match;

        /** 换上的默认值形态（{@code void:RETURN}／{@code boolean:false}／{@code ref:null}／…）；失败时为空串。 */
        public final String defaultKind;

        Result(byte[] bytes, String reason, String methodDesc, int firstLine, String match, String defaultKind) {
            this.bytes = bytes;
            this.reason = reason;
            this.methodDesc = methodDesc;
            this.firstLine = firstLine;
            this.match = match;
            this.defaultKind = defaultKind;
        }

        public boolean ok() {
            return bytes != null;
        }

        @Override
        public String toString() {
            return "ok=" + ok() + " reason=" + reason + " desc=" + methodDesc + " match=" + match
                    + " default=" + defaultKind;
        }
    }

    /**
     * 把 {@code methodName} 的方法体换成"安全默认返回"。
     *
     * @param classBytes 原始 class 字节（transformer 收到的 {@code classfileBuffer}）
     * @param methodName 目标方法名（栈帧给出的那个）
     * @param methodDesc 目标方法描述符；{@code null} = 栈帧里拿不到，按 {@link #MATCH_NAME_ONLY} 口径挑
     * @param lineHint   栈帧里的行号（{@code -1}/非法 = 没有），只用于在同名重载里挑
     * @return 成功时 {@code bytes} 是新 class（长度一般与输入不同）
     */
    public static Result patch(byte[] classBytes, String methodName, String methodDesc, int lineHint) {
        if (classBytes == null || classBytes.length < 16) {
            return fail("bad_class_bytes", methodDesc);
        }
        if (methodName == null || methodName.isEmpty()) {
            return fail("empty_method_name", methodDesc);
        }
        if (methodName.charAt(0) == '<') {
            // <init>/<clinit> 不能这么换：<init> 少了 super() 直接 VerifyError，<clinit> 会把静态初始化抹掉
            return fail("forbidden_special_method", methodDesc);
        }
        final ClassNode before = read(classBytes);
        if (before == null) {
            return fail("class_reader_failed", methodDesc);
        }

        // ---- ① 选方法
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
        if ((target.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return fail("no_code", target.desc);
        }
        if (target.instructions == null || target.instructions.size() == 0) {
            return fail("no_code", target.desc);
        }

        // ---- ② 默认值形态（按返回类型）
        final Default dflt = defaultFor(target.desc);
        if (dflt == null) {
            return fail("bad_return_type", target.desc);
        }

        // ---- ③ 自证基线：形状 ／ 其余方法的指纹
        final String shapeBefore = describeShape(before);
        final Map<String, String> printsBefore = fingerprints(before);
        final String targetKey = methodKey(target);
        final int firstLine = firstLineOf(target);

        // ---- ④ 只动这一个方法
        clearBody(target, dflt.stackSlots);
        if (dflt.pushOpcode >= 0) {
            target.instructions.add(new InsnNode(dflt.pushOpcode));
        }
        target.instructions.add(new InsnNode(dflt.returnOpcode));

        final byte[] out;
        try {
            final ClassWriter cw = new ClassWriter(0);
            before.accept(cw);
            out = cw.toByteArray();
        } catch (Throwable t) {
            return fail("class_writer_failed", target.desc);
        }

        // ---- ⑤ 自证 ①：形状不变
        final ClassNode after = read(out);
        if (after == null) {
            return fail("writer_output_unreadable", target.desc);
        }
        if (!shapeBefore.equals(describeShape(after))) {
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
        // ---- ⑦ 自证 ③：目标方法真的就是那两条（void 时是一条）指令
        final MethodNode patched = find(after, target.name, target.desc);
        if (patched == null) {
            return fail("self_check_method_missing", target.desc);
        }
        final int expectedSize = dflt.pushOpcode >= 0 ? 2 : 1;
        if (patched.instructions.size() != expectedSize
                || (dflt.pushOpcode >= 0 && patched.instructions.get(0).getOpcode() != dflt.pushOpcode)
                || patched.instructions.get(expectedSize - 1).getOpcode() != dflt.returnOpcode) {
            return fail("self_check_body_not_replaced", target.desc);
        }
        return new Result(out, "ok", target.desc, firstLine, match, dflt.kind);
    }

    /**
     * 形状指纹（<b>只含</b>名字/描述符/访问标志/层次关系，与任何方法体无关）。
     *
     * <p>用途：判"改写前后类的形状有没有变"（不增删方法/字段、不改签名/继承）。
     */
    public static String describeShape(byte[] classBytes) {
        final ClassNode cn = read(classBytes);
        return cn == null ? "<unreadable>" : describeShape(cn);
    }

    /** 形状指纹的内部重载（已经读到 {@link ClassNode} 时省一次解析）。 */
    public static String describeShape(ClassNode cn) {
        if (cn == null) {
            return "<unreadable>";
        }
        final StringBuilder sb = new StringBuilder(512);
        sb.append("name=").append(cn.name)
                .append(" access=").append(cn.access)
                .append(" version=").append(cn.version)
                .append(" super=").append(cn.superName)
                .append(" ifaces=").append(cn.interfaces)
                .append(" fields=[");
        for (int i = 0; i < cn.fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cn.fields.get(i).name).append(':').append(cn.fields.get(i).desc)
                    .append(':').append(cn.fields.get(i).access);
        }
        sb.append("] methods=[");
        for (int i = 0; i < cn.methods.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            final MethodNode mn = cn.methods.get(i);
            sb.append(mn.name).append(mn.desc).append(':').append(mn.access);
        }
        return sb.append(']').toString();
    }

    /** 把 class 字节读成 {@link ClassNode}；解析失败 ⇒ {@code null}（不抛）。 */
    public static ClassNode read(byte[] classBytes) {
        if (classBytes == null || classBytes.length < 16) {
            return null;
        }
        try {
            final ClassNode cn = new ClassNode();
            new ClassReader(classBytes).accept(cn, 0);
            return cn;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 一个默认值形态（压栈指令 ＋ 返回指令 ＋ 需要的栈槽数）。 */
    private static final class Default {

        final int pushOpcode;
        final int returnOpcode;
        final int stackSlots;
        final String kind;

        Default(int pushOpcode, int returnOpcode, int stackSlots, String kind) {
            this.pushOpcode = pushOpcode;
            this.returnOpcode = returnOpcode;
            this.stackSlots = stackSlots;
            this.kind = kind;
        }
    }

    /** 按返回类型选默认值（规格表）；不认识的返回类型 ⇒ {@code null}（拒绝）。 */
    private static Default defaultFor(String methodDesc) {
        if (methodDesc == null || methodDesc.isEmpty()) {
            return null;
        }
        final Type ret;
        try {
            ret = Type.getReturnType(methodDesc);
        } catch (Throwable t) {
            return null;
        }
        switch (ret.getSort()) {
            case Type.VOID:
                // void：按规格「只 RETURN」——不压任何值（pushOpcode = -1 表示不发射）
                return new Default(-1, Opcodes.RETURN, 0, "void:RETURN");
            case Type.BOOLEAN:
                return new Default(Opcodes.ICONST_0, Opcodes.IRETURN, 1, "boolean:false");
            case Type.BYTE:
                return new Default(Opcodes.ICONST_0, Opcodes.IRETURN, 1, "byte:0");
            case Type.CHAR:
                return new Default(Opcodes.ICONST_0, Opcodes.IRETURN, 1, "char:0");
            case Type.SHORT:
                return new Default(Opcodes.ICONST_0, Opcodes.IRETURN, 1, "short:0");
            case Type.INT:
                return new Default(Opcodes.ICONST_0, Opcodes.IRETURN, 1, "int:0");
            case Type.LONG:
                return new Default(Opcodes.LCONST_0, Opcodes.LRETURN, 2, "long:0L");
            case Type.FLOAT:
                return new Default(Opcodes.FCONST_0, Opcodes.FRETURN, 1, "float:0.0");
            case Type.DOUBLE:
                return new Default(Opcodes.DCONST_0, Opcodes.DRETURN, 2, "double:0.0");
            case Type.ARRAY:
            case Type.OBJECT:
                return new Default(Opcodes.ACONST_NULL, Opcodes.ARETURN, 1, "ref:null");
            default:
                return null;
        }
    }

    /**
     * 换掉方法体：清指令、清所有"指向指令里 Label"的附属表，并把 {@code maxStack} 抬到够用。
     *
     * <p>⚠️ 只清与 {@code instructions} 强绑定的那几张表（try-catch、局部变量表、局部变量注解），
     * 方法级注解/签名/异常表一概不动。
     */
    private static void clearBody(MethodNode mn, int stackSlots) {
        mn.instructions.clear();
        if (mn.tryCatchBlocks != null) {
            mn.tryCatchBlocks.clear();
        }
        if (mn.localVariables != null) {
            mn.localVariables.clear();
        }
        mn.visibleLocalVariableAnnotations = null;
        mn.invisibleLocalVariableAnnotations = null;
        // ⚠️ 不能只信原值：void 方法的原 maxStack 可能是 0，而 long 返回值需要 2 个字。
        if (mn.maxStack < stackSlots) {
            mn.maxStack = stackSlots;
        }
        // maxLocals 保持原值：新方法体一个局部变量都不用，原值一定够。
    }

    /**
     * 同名多个重载时，用栈帧行号挑。
     *
     * <p>口径：每个候选算出"行号表里 ≤ {@code lineHint} 的最大行号"，
     * 取该值最大的那一个；并列最大 ⇒ 分不开 ⇒ {@code null}（拒绝）。
     */
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

    /** 行号表里 ≤ {@code hint} 的最大行号；没有 ⇒ -1。 */
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

    /** 方法编译出来的最小行号；没有行号表 ⇒ -1。 */
    private static int firstLineOf(MethodNode mn) {
        if (mn.instructions == null) {
            return -1;
        }
        int best = -1;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getType() == AbstractInsnNode.LINE) {
                final int line = ((LineNumberNode) insn).line;
                if (line > 0 && (best < 0 || line < best)) {
                    best = line;
                }
            }
        }
        return best;
    }

    /**
     * 每个方法一个指纹：{@code 指令条数 + 首操作码 + 末操作码 + 行号条数}。
     *
     * <p>只用来判"这个方法有没有被动过"，所以不需要覆盖所有细节（指令本身没法被复制）。
     */
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
            out.put(methodKey(mn), count + "/" + lines + "/" + first + "/" + last
                    + "/" + mn.maxStack + "/" + mn.maxLocals);
        }
        return out;
    }

    private static String methodKey(MethodNode mn) {
        return mn == null ? "?" : mn.name + mn.desc;
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

    private static Result fail(String reason, String desc) {
        return new Result(null, reason, desc, -1, "none", "");
    }
}
