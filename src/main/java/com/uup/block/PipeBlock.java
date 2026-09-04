package com.uup.block;

import com.uup.blockentity.PipeBlockEntity;
import com.uup.core.transfer.GasTransferExecutor;
import com.uup.setup.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public class PipeBlock extends Block implements EntityBlock {

    public enum PipeType {
        UNIVERSAL,
        ENERGY,
        FLUID,
        ITEM,
        GAS
    }

    private final PipeType type;

    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");

    public static final Map<Direction, BooleanProperty> DIRECTION_PROPERTIES = new HashMap<>();

    private static final VoxelShape CORE_SHAPE = Block.box(4.0, 4.0, 4.0, 12.0, 12.0, 12.0);
    private static final VoxelShape NORTH_SHAPE = Block.box(4.0, 4.0, 0.0, 12.0, 12.0, 4.0);
    private static final VoxelShape SOUTH_SHAPE = Block.box(4.0, 4.0, 12.0, 12.0, 12.0, 16.0);
    private static final VoxelShape EAST_SHAPE = Block.box(12.0, 4.0, 4.0, 16.0, 12.0, 12.0);
    private static final VoxelShape WEST_SHAPE = Block.box(0.0, 4.0, 4.0, 4.0, 12.0, 12.0);
    private static final VoxelShape UP_SHAPE = Block.box(4.0, 12.0, 4.0, 12.0, 16.0, 12.0);
    private static final VoxelShape DOWN_SHAPE = Block.box(4.0, 0.0, 4.0, 12.0, 4.0, 12.0);

    static {
        DIRECTION_PROPERTIES.put(Direction.NORTH, NORTH);
        DIRECTION_PROPERTIES.put(Direction.SOUTH, SOUTH);
        DIRECTION_PROPERTIES.put(Direction.EAST, EAST);
        DIRECTION_PROPERTIES.put(Direction.WEST, WEST);
        DIRECTION_PROPERTIES.put(Direction.UP, UP);
        DIRECTION_PROPERTIES.put(Direction.DOWN, DOWN);
    }

    public PipeBlock(PipeType type) {
        super(Properties.of()
                .strength(2.0F, 10.0F)
                .sound(SoundType.NETHERITE_BLOCK)
                .noOcclusion());
        this.type = type;
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(NORTH, false)
                .setValue(SOUTH, false)
                .setValue(EAST, false)
                .setValue(WEST, false)
                .setValue(UP, false)
                .setValue(DOWN, false));
    }

    public PipeType getType() {
        return type;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, EAST, WEST, UP, DOWN);
    }

    public boolean canConnectTo(LevelAccessor level, BlockPos pos, Direction direction) {
        BlockPos neighbor = pos.relative(direction);
        if (!level.hasChunkAt(neighbor)) return false;

        BlockState neighborState = level.getBlockState(neighbor);
        if (neighborState.getBlock() instanceof PipeBlock neighborPipe) {
            return this.type == PipeType.UNIVERSAL || neighborPipe.type == PipeType.UNIVERSAL || this.type == neighborPipe.type;
        }
        if (neighborState.is(ModBlocks.CONTROLLER.get()) || neighborState.is(ModBlocks.NODE.get())) {
            return true;
        }

        BlockEntity be = level.getBlockEntity(neighbor);
        if (be != null) {
            Direction opposite = direction.getOpposite();
            if (this.type == PipeType.ENERGY) {
                return be.getCapability(ForgeCapabilities.ENERGY, opposite).isPresent();
            }
            if (this.type == PipeType.FLUID) {
                return be.getCapability(ForgeCapabilities.FLUID_HANDLER, opposite).isPresent();
            }
            if (this.type == PipeType.ITEM) {
                return be.getCapability(ForgeCapabilities.ITEM_HANDLER, opposite).isPresent();
            }
            if (this.type == PipeType.GAS) {
                return GasTransferExecutor.canConnectGas(be, opposite);
            }
            return be.getCapability(ForgeCapabilities.ITEM_HANDLER, opposite).isPresent()
                    || be.getCapability(ForgeCapabilities.FLUID_HANDLER, opposite).isPresent()
                    || be.getCapability(ForgeCapabilities.ENERGY, opposite).isPresent()
                    || GasTransferExecutor.canConnectGas(be, opposite);
        }
        return false;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = this.defaultBlockState();
        for (Direction dir : Direction.values()) {
            state = state.setValue(DIRECTION_PROPERTIES.get(dir), canConnectTo(level, pos, dir));
        }
        return state;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos currentPos, BlockPos neighborPos) {
        return state.setValue(DIRECTION_PROPERTIES.get(direction), canConnectTo(level, currentPos, direction));
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PipeBlockEntity pipeBE) {
                pipeBE.markNetworkDirty();
            }
        }
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = CORE_SHAPE;
        if (state.getValue(NORTH)) shape = Shapes.or(shape, NORTH_SHAPE);
        if (state.getValue(SOUTH)) shape = Shapes.or(shape, SOUTH_SHAPE);
        if (state.getValue(EAST))  shape = Shapes.or(shape, EAST_SHAPE);
        if (state.getValue(WEST))  shape = Shapes.or(shape, WEST_SHAPE);
        if (state.getValue(UP))    shape = Shapes.or(shape, UP_SHAPE);
        if (state.getValue(DOWN))  shape = Shapes.or(shape, DOWN_SHAPE);
        return shape;
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
        if (!level.isClientSide && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PipeBlockEntity pipeBE && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                Direction clickedSide = hit.getDirection();
                net.minecraftforge.network.NetworkHooks.openScreen(
                        serverPlayer,
                        pipeBE.getMenuProvider(clickedSide),
                        buf -> {
                            buf.writeBlockPos(pos);
                            buf.writeEnum(clickedSide);
                        }
                );
                return net.minecraft.world.InteractionResult.SUCCESS;
            }
        }
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PipeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : (lvl, pos, st, be) -> {
            if (be instanceof PipeBlockEntity pipeBE && lvl instanceof ServerLevel serverLevel) {
                pipeBE.serverTick(serverLevel);
            }
        };
    }
}
