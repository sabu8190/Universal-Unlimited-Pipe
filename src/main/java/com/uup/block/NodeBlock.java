package com.uup.block;

import com.uup.blockentity.NodeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class NodeBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = DirectionalBlock.FACING;

    private static final VoxelShape NORTH_SHAPE = Shapes.or(Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 3.0), Block.box(4.0, 4.0, 3.0, 12.0, 12.0, 16.0));
    private static final VoxelShape SOUTH_SHAPE = Shapes.or(Block.box(0.0, 0.0, 13.0, 16.0, 16.0, 16.0), Block.box(4.0, 4.0, 0.0, 12.0, 12.0, 13.0));
    private static final VoxelShape WEST_SHAPE  = Shapes.or(Block.box(0.0, 0.0, 0.0, 3.0, 16.0, 16.0), Block.box(3.0, 4.0, 4.0, 16.0, 12.0, 12.0));
    private static final VoxelShape EAST_SHAPE  = Shapes.or(Block.box(13.0, 0.0, 0.0, 16.0, 16.0, 16.0), Block.box(0.0, 4.0, 4.0, 13.0, 12.0, 12.0));
    private static final VoxelShape DOWN_SHAPE  = Shapes.or(Block.box(0.0, 0.0, 0.0, 16.0, 3.0, 16.0), Block.box(4.0, 3.0, 4.0, 12.0, 16.0, 12.0));
    private static final VoxelShape UP_SHAPE    = Shapes.or(Block.box(0.0, 13.0, 0.0, 16.0, 16.0, 16.0), Block.box(4.0, 0.0, 4.0, 12.0, 13.0, 12.0));


    public NodeBlock() {
        super(Properties.of()
                .strength(2.0F, 10.0F)
                .sound(SoundType.METAL)
                .noOcclusion());
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        return switch (facing) {
            case NORTH -> NORTH_SHAPE;
            case SOUTH -> SOUTH_SHAPE;
            case WEST -> WEST_SHAPE;
            case EAST -> EAST_SHAPE;
            case DOWN -> DOWN_SHAPE;
            case UP -> UP_SHAPE;
        };
    }

    @Override
    public net.minecraft.world.InteractionResult use(
            BlockState state,
            Level level,
            BlockPos pos,
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hit
    ) {
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof NodeBlockEntity nodeBE && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                net.minecraftforge.network.NetworkHooks.openScreen(
                        serverPlayer,
                        nodeBE,
                        buf -> {
                            buf.writeBlockPos(pos);
                            buf.writeEnum(nodeBE.getAttachedFacing());
                        }
                );
            }
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NodeBlockEntity(pos, state);
    }
}
