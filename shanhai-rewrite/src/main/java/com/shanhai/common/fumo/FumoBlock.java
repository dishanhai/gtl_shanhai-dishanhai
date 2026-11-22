package com.shanhai.common.fumo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 静态 Fumo 玩偶方块（{@code shanhai:dishanhai_fumo} / {@code shanhai:beibeipuo_fumo} /
 * {@code shanhai:dgyjh02804_fumo}）。
 *
 * <h2>范围（用户拍板：方案 C「只做静态玩偶 ＋ 头饰，不跳舞、不音效」）</h2>
 * 本类<b>刻意只有</b>「4 向朝向 + 碰撞/选择箱 + 不遮挡相邻方块」三件事：
 * <ul>
 *   <li><b>没有</b>方块实体（{@code BlockEntity}）/ BER（{@code BlockEntityRenderer}）；</li>
 *   <li><b>没有</b>跳舞挤压动画（上游是 BER 每帧重画 + squash 数学，本工程不做）；</li>
 *   <li><b>没有</b>持物姿势（{@code _holding} 模型 / {@code holding} 属性）、<b>没有</b>音效；</li>
 *   <li><b>没有</b>右键抽奖（{@code FumoRewards}）、<b>没有</b> Curios 额外饰品槽。</li>
 * </ul>
 * 模型本身的渲染类型写在模型 JSON 里（{@code "render_type": "minecraft:cutout"}），
 * 不遮挡由注册时的 {@code Properties#noOcclusion()} 给出，二者都不需要本类写代码。
 *
 * <p>🔴 <b>贴图来源</b>：{@code dgyjh02804_fumo} 的皮肤是<b>上游原皮肤</b> ——
 * 逐字节复制自 {@code gt_shanhai-4.9.4.jar} 的
 * {@code assets/gt_shanhai/textures/block/dgyjh02804_fumo.png}
 * （sha256 {@code AECCB87C…}，一个字节都没有改）；几何仍是 dishanhai 那套（MIT）。
 * {@code #details} 按上游行为继承 {@code shanhai:block/fumo/dishanhai_fumo_details}
 * （所以这只头上会有金色徽章）。许可以上游仓库为准：{@code dishanhai/gtl_shanhai-dishanhai} 是
 * <b>LGPL-3.0</b>；本重制版本来就是该仓库的派生分支，LICENSE / {@code mods.toml} 的许可统一是
 * <b>未结事项</b>（另案处理）。详见 {@code assets/shanhai/models/block/fumo/CREDITS.txt} 与
 * {@code temp/fumo-recon/CREDITS-shanhai-fumo.md}。
 *
 * <h2>几何 / 朝向</h2>
 * 模型是「面朝北（{@code -z}）」建的（上游命名空间的那套几何，MIT；见
 * {@code assets/shanhai/models/block/fumo/CREDITS.txt}）。方块状态只有原版
 * {@code BlockStateProperties.HORIZONTAL_FACING} 这一个属性（即 {@link #FACING}）：
 * {@code facing=north} 不加旋转、{@code east/south/west} 分别 {@code y=90/180/270}
 * （与 {@code blockstates/*.json} 逐条对应）。
 *
 * <h2>碰撞箱 / 选择箱</h2>
 * 用模型在「朝北」时的水平包围盒（{@code x 3.5..12.5}、{@code z 3.5..14.75}，单位 1/16 格）：
 * 这是把 24 个 element 的 8 个角（含 element 自带 rotation 的保守外包，
 * 原始值 {@code x 3.575..12.425}、{@code z 3.508..14.698}）向外取整到 1/16 得到的值；
 * {@code y} 取 {@code 0..1}（模型头顶到 {@code 16.83/16}，即高出方块顶 0.83/16 ≈ 0.052 格，
 * 选择箱不跟着探出方块顶 —— 这是刻意的，避免选择框在方块上方多出一小截）。
 *
 * <p>另外三个朝向的箱子是<b>把上面那个箱子按 {@code y=90/180/270} 旋转</b>得到的闭式结果
 * （方块状态旋转 {@code y=90} 对应「北→东」，即 {@code (x,z) -> (16-z, x)}），
 * 因此模型转到哪个朝向，碰撞箱就转到哪个朝向，不需要运行时矩阵运算
 * （已用一个只读脚本把这四组常量与模型 AABB 逐项对过：{@code temp/fumo-recon/verify-shape.cjs}）。
 */
public class FumoBlock extends HorizontalDirectionalBlock {

    /** 朝北（模型未旋转）时的箱子：{@code x 3.5..12.5}, {@code y 0..16}, {@code z 3.5..14.75}（1/16 格）。 */
    private static final VoxelShape SHAPE_NORTH = Shapes.box(
            3.5D / 16.0D, 0.0D, 3.5D / 16.0D,
            12.5D / 16.0D, 1.0D, 14.75D / 16.0D);

    /** {@code facing=east}（{@code y=90}，北→东）：{@code x 1.25..12.5}, {@code z 3.5..12.5}。 */
    private static final VoxelShape SHAPE_EAST = Shapes.box(
            1.25D / 16.0D, 0.0D, 3.5D / 16.0D,
            12.5D / 16.0D, 1.0D, 12.5D / 16.0D);

    /** {@code facing=south}（{@code y=180}）：{@code x 3.5..12.5}, {@code z 1.25..12.5}。 */
    private static final VoxelShape SHAPE_SOUTH = Shapes.box(
            3.5D / 16.0D, 0.0D, 1.25D / 16.0D,
            12.5D / 16.0D, 1.0D, 12.5D / 16.0D);

    /** {@code facing=west}（{@code y=270}，北→西）：{@code x 3.5..14.75}, {@code z 3.5..12.5}。 */
    private static final VoxelShape SHAPE_WEST = Shapes.box(
            3.5D / 16.0D, 0.0D, 3.5D / 16.0D,
            14.75D / 16.0D, 1.0D, 12.5D / 16.0D);

    public FumoBlock(Properties properties) {
        super(properties);
        // HorizontalDirectionalBlock 只提供 FACING 常量 + rotate/mirror，不会自己给默认值。
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /**
     * 放置时朝向<b>玩家</b>（与上游一致：{@code facing = 玩家水平朝向的反方向}）。
     * 例：玩家面朝北放下去 ⇒ 方块 {@code facing=south} ⇒ 玩偶正脸对着玩家。
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case EAST -> SHAPE_EAST;
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            default -> SHAPE_NORTH;
        };
    }
}
