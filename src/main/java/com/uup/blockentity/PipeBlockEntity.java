package com.uup.blockentity;

import com.uup.core.network.NetworkController;
import com.uup.core.network.TransferMode;
import com.uup.core.network.TransferNode;
import com.uup.gui.NodeMenu;
import com.uup.item.OverclockUpgradeItem;
import com.uup.setup.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PipeBlockEntity extends BlockEntity {

    private final NetworkController standaloneNetwork = new NetworkController();

    // 6-directional configurations for Pipez-style side configuration
    private final TransferMode[] modes = new TransferMode[6];
    private final int[] priorities = new int[6];
    private final int[] channels = new int[6];
    private final ItemStackHandler[] upgradeHandlers = new ItemStackHandler[6];

    public PipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PIPE.get(), pos, state);
        for (int i = 0; i < 6; i++) {
            modes[i] = TransferMode.BOTH;
            priorities[i] = 0;
            channels[i] = 0;
            upgradeHandlers[i] = new ItemStackHandler(1) {
                @Override
                public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                    return stack.getItem() instanceof OverclockUpgradeItem;
                }

                @Override
                public int getSlotLimit(int slot) {
                    return 16;
                }

                @Override
                protected void onContentsChanged(int slot) {
                    setChanged();
                }
            };
        }
    }

    private NetworkController masterController = null;
    private boolean hasAdjacentInventory = true;
    private long lastInventoryCheckTick = -1;

    public void invalidateInventoryCache() {
        this.hasAdjacentInventory = true;
        this.lastInventoryCheckTick = -1;
    }

    public boolean hasAdjacentInventory() {
        return this.hasAdjacentInventory;
    }

    public void markNetworkDirty() {
        standaloneNetwork.markNetworkDirty();
        this.masterController = null;
        this.hasAdjacentInventory = true;
        this.lastInventoryCheckTick = -1;
    }

    public void syncStandaloneMaster(NetworkController controller, BlockPos lowestPos, java.util.Set<BlockPos> controllers) {
        this.masterController = controller;
        standaloneNetwork.syncNetworkState(lowestPos, controllers);
    }

    public void syncStandaloneMaster(BlockPos lowestPos, java.util.Set<BlockPos> controllers) {
        standaloneNetwork.syncNetworkState(lowestPos, controllers);
    }

    public void serverTick(ServerLevel level) {
        // 1. マスターパイプの判定とネットワーク準備（トポロジー探索・搬入先リスト構築＆最寄りソート）
        if (standaloneNetwork.shouldTickStandalone(worldPosition)) {
            standaloneNetwork.prepareNetworkTick(level, worldPosition);
            this.masterController = standaloneNetwork;
        }

        // 2. 全パイプ共通：自分自身の隣接インベントリからの搬出を自身のTickで分散実行（負荷均等分散）！
        // 隣接外部インベントリがない中継パイプは 0 ナノ秒で完全スキップ！
        long gameTime = level.getGameTime();
        if (hasAdjacentInventory) {
            NetworkController controller = masterController != null ? masterController : (standaloneNetwork.isMasterPipe(worldPosition) ? standaloneNetwork : null);
            if (controller != null) {
                boolean foundAny = controller.tickPipeExtract(level, this);
                if (!foundAny && (gameTime - lastInventoryCheckTick > 20)) {
                    this.hasAdjacentInventory = false;
                    this.lastInventoryCheckTick = gameTime;
                }
            }
        } else {
            // 20 ticks (1秒) ごとに1度だけ隣接インベントリの有無を再確認
            if (gameTime - lastInventoryCheckTick > 20) {
                this.hasAdjacentInventory = true;
                this.lastInventoryCheckTick = gameTime;
            }
        }
    }

    public TransferMode getMode(Direction side) {
        return modes[side.ordinal()];
    }

    public int getPriority(Direction side) {
        return priorities[side.ordinal()];
    }

    public int getChannelId(Direction side) {
        return channels[side.ordinal()];
    }

    public ItemStackHandler getUpgradeHandler(Direction side) {
        return upgradeHandlers[side.ordinal()];
    }

    public TransferNode toNodeData(Direction side) {
        int idx = side.ordinal();
        int overclocks = upgradeHandlers[idx].getStackInSlot(0).getCount();
        return new TransferNode(
                worldPosition,
                level != null ? level.dimension() : Level.OVERWORLD,
                side,
                modes[idx],
                priorities[idx],
                channels[idx],
                overclocks,
                false
        );
    }

    public MenuProvider getMenuProvider(Direction side) {
        return new MenuProvider() {
            @Override
            public Component getDisplayName() {
                if (level != null) {
                    BlockPos neighborPos = worldPosition.relative(side);
                    if (level.hasChunkAt(neighborPos)) {
                        BlockState neighborState = level.getBlockState(neighborPos);
                        if (!neighborState.isAir()) {
                            return Component.literal(neighborState.getBlock().getName().getString() + " (" + side.getName().toUpperCase() + ")");
                        }
                    }
                }
                return Component.literal("接続設定 (" + side.getName().toUpperCase() + ")");
            }

            @Nullable
            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
                int idx = side.ordinal();
                ContainerData data = new ContainerData() {
                    @Override
                    public int get(int index) {
                        return switch (index) {
                            case 0 -> priorities[idx];
                            case 1 -> modes[idx].ordinal();
                            case 2 -> channels[idx];
                            default -> 0;
                        };
                    }

                    @Override
                    public void set(int index, int value) {
                        switch (index) {
                            case 0 -> priorities[idx] = value;
                            case 1 -> {
                                TransferMode[] vals = TransferMode.values();
                                if (value >= 0 && value < vals.length) {
                                    modes[idx] = vals[value];
                                }
                            }
                            case 2 -> channels[idx] = value;
                        }
                        setChanged();
                    }

                    @Override
                    public int getCount() {
                        return 3;
                    }
                };
                return new NodeMenu(containerId, playerInv, worldPosition, side, upgradeHandlers[idx], data);
            }
        };
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        for (Direction dir : Direction.values()) {
            int i = dir.ordinal();
            CompoundTag sideTag = new CompoundTag();
            sideTag.putString("Mode", modes[i].name());
            sideTag.putInt("Priority", priorities[i]);
            sideTag.putInt("Channel", channels[i]);
            sideTag.put("Upgrades", upgradeHandlers[i].serializeNBT());
            tag.put("Side_" + dir.name(), sideTag);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        for (Direction dir : Direction.values()) {
            int i = dir.ordinal();
            String key = "Side_" + dir.name();
            if (tag.contains(key)) {
                CompoundTag sideTag = tag.getCompound(key);
                if (sideTag.contains("Mode")) modes[i] = TransferMode.valueOf(sideTag.getString("Mode"));
                if (sideTag.contains("Priority")) priorities[i] = sideTag.getInt("Priority");
                if (sideTag.contains("Channel")) channels[i] = sideTag.getInt("Channel");
                if (sideTag.contains("Upgrades")) upgradeHandlers[i].deserializeNBT(sideTag.getCompound("Upgrades"));
            }
        }
    }
}
