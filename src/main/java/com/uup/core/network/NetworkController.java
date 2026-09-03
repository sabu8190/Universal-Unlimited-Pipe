package com.uup.core.network;

import com.uup.block.PipeBlock;
import com.uup.blockentity.NodeBlockEntity;
import com.uup.config.ModConfig;
import com.uup.core.transfer.EnergyTransferExecutor;
import com.uup.core.transfer.FluidTransferExecutor;
import com.uup.core.transfer.ItemTransferExecutor;
import com.uup.logging.UUPLogger;
import com.uup.setup.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

import java.util.*;

public class NetworkController {

    private final List<TransferNode> configuredNodes = new ArrayList<>();
    private final DirectBufferStorage directBuffer = new DirectBufferStorage();
    private int overclockCount = 0;
    private long tickCounter = 0;

    // Cache of physical pipe positions connected to this controller
    private final Set<BlockPos> cachedPipes = new HashSet<>();
    private final Set<BlockPos> scannedNodePositions = new HashSet<>();
    private boolean networkDirty = true;

    public NetworkController() {
    }

    public List<TransferNode> getNodes() {
        return configuredNodes;
    }

    public DirectBufferStorage getDirectBuffer() {
        return directBuffer;
    }

    public int getOverclockCount() {
        return overclockCount;
    }

    public void setOverclockCount(int overclockCount) {
        this.overclockCount = overclockCount;
    }

    public void markNetworkDirty() {
        this.networkDirty = true;
    }

    public void addNode(TransferNode node) {
        if (!configuredNodes.contains(node)) {
            configuredNodes.add(node);
            UUPLogger.info(String.format("Added UUP configured node at %s (Mode=%s, Channel=%d)", node.getPos(), node.getMode(), node.getChannelId()));
        }
    }

    public void removeNode(TransferNode node) {
        configuredNodes.remove(node);
        UUPLogger.info(String.format("Removed UUP node at %s", node.getPos()));
    }

    private void rebuildPipeNetwork(ServerLevel level, BlockPos controllerPos) {
        cachedPipes.clear();
        scannedNodePositions.clear();
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        // Start scanning all pipes directly adjacent to controller
        for (Direction dir : Direction.values()) {
            BlockPos adj = controllerPos.relative(dir);
            if (level.isLoaded(adj) && level.getBlockState(adj).getBlock() instanceof PipeBlock) {
                queue.add(adj);
                visited.add(adj);
            }
        }

        while (!queue.isEmpty() && visited.size() < 8192) {
            BlockPos current = queue.poll();
            cachedPipes.add(current);

            for (Direction dir : Direction.values()) {
                BlockPos next = current.relative(dir);
                if (!visited.contains(next) && level.isLoaded(next)) {
                    BlockState nextState = level.getBlockState(next);
                    if (nextState.getBlock() instanceof PipeBlock) {
                        visited.add(next);
                        queue.add(next);
                    } else if (nextState.is(ModBlocks.NODE.get())) {
                        visited.add(next);
                        scannedNodePositions.add(next);
                    }
                }
            }
        }
        networkDirty = false;
        UUPLogger.debug(String.format("Rebuilt UUP pipe network: %d pipes and %d attached nodes discovered.", cachedPipes.size(), scannedNodePositions.size()));
    }

    public void tick(ServerLevel level, BlockPos controllerPos) {
        tickCounter++;
        int interval = ModConfig.COMMON != null && ModConfig.COMMON.tickInterval != null 
                ? ModConfig.COMMON.tickInterval.get() : 1;

        if (tickCounter % interval != 0) {
            return;
        }

        if (networkDirty) {
            rebuildPipeNetwork(level, controllerPos);
        }

        List<IItemHandler> itemInjectors = new ArrayList<>();
        List<IItemHandler> itemExtractors = new ArrayList<>();
        List<IFluidHandler> fluidInjectors = new ArrayList<>();
        List<IFluidHandler> fluidExtractors = new ArrayList<>();
        List<IEnergyStorage> energyInjectors = new ArrayList<>();
        List<IEnergyStorage> energyExtractors = new ArrayList<>();

        // Track positions where dedicated transfer node parts are placed to prevent duplicate direct pipe connections
        Set<BlockPos> handledPositions = new HashSet<>();

        // 1. Process configured nodes (Manual / Wireless Cards)
        List<TransferNode> allActiveNodes = new ArrayList<>(configuredNodes);

        // Also add scanned physical nodes on the pipe network
        for (BlockPos nodePos : scannedNodePositions) {
            if (level.isLoaded(nodePos)) {
                BlockEntity be = level.getBlockEntity(nodePos);
                if (be instanceof NodeBlockEntity nodeBE) {
                    allActiveNodes.add(nodeBE.toNodeData());
                }
            }
        }

        for (TransferNode node : allActiveNodes) {
            if (node.getMode() == TransferMode.DISABLED) continue;
            
            BlockPos targetPos = node.isWirelessRemote() ? node.getPos() : node.getPos().relative(node.getTargetSide());
            if (!level.isLoaded(targetPos)) continue;

            BlockEntity be = level.getBlockEntity(targetPos);
            if (be == null) continue;

            handledPositions.add(targetPos);
            Direction side = node.getTargetSide().getOpposite();

            be.getCapability(ForgeCapabilities.ITEM_HANDLER, side).ifPresent(handler -> {
                if (node.getMode() == TransferMode.INSERT || node.getMode() == TransferMode.BOTH) {
                    if (!itemInjectors.contains(handler)) itemInjectors.add(handler);
                }
                if (node.getMode() == TransferMode.EXTRACT || node.getMode() == TransferMode.BOTH) {
                    if (!itemExtractors.contains(handler)) itemExtractors.add(handler);
                }
            });

            be.getCapability(ForgeCapabilities.FLUID_HANDLER, side).ifPresent(handler -> {
                if (node.getMode() == TransferMode.INSERT || node.getMode() == TransferMode.BOTH) {
                    if (!fluidInjectors.contains(handler)) fluidInjectors.add(handler);
                }
                if (node.getMode() == TransferMode.EXTRACT || node.getMode() == TransferMode.BOTH) {
                    if (!fluidExtractors.contains(handler)) fluidExtractors.add(handler);
                }
            });

            be.getCapability(ForgeCapabilities.ENERGY, side).ifPresent(handler -> {
                if (node.getMode() == TransferMode.INSERT || node.getMode() == TransferMode.BOTH) {
                    if (!energyInjectors.contains(handler)) energyInjectors.add(handler);
                }
                if (node.getMode() == TransferMode.EXTRACT || node.getMode() == TransferMode.BOTH) {
                    if (!energyExtractors.contains(handler)) energyExtractors.add(handler);
                }
            });
        }

        // 2. Process Direct Pipe Connections (Pipes connected directly to machines without extra parts)
        for (BlockPos pipePos : cachedPipes) {
            if (!level.isLoaded(pipePos)) continue;

            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = pipePos.relative(dir);
                if (neighborPos.equals(controllerPos) || cachedPipes.contains(neighborPos) || handledPositions.contains(neighborPos)) {
                    continue;
                }
                if (!level.isLoaded(neighborPos)) continue;

                BlockEntity be = level.getBlockEntity(neighborPos);
                if (be == null) continue;

                Direction side = dir.getOpposite();

                // Direct connected machine: automatically supports both extract and insert
                be.getCapability(ForgeCapabilities.ITEM_HANDLER, side).ifPresent(handler -> {
                    if (!itemInjectors.contains(handler)) itemInjectors.add(handler);
                    if (!itemExtractors.contains(handler)) itemExtractors.add(handler);
                });

                be.getCapability(ForgeCapabilities.FLUID_HANDLER, side).ifPresent(handler -> {
                    if (!fluidInjectors.contains(handler)) fluidInjectors.add(handler);
                    if (!fluidExtractors.contains(handler)) fluidExtractors.add(handler);
                });

                be.getCapability(ForgeCapabilities.ENERGY, side).ifPresent(handler -> {
                    if (!energyInjectors.contains(handler)) energyInjectors.add(handler);
                    if (!energyExtractors.contains(handler)) energyExtractors.add(handler);
                });
            }
        }

        // 3. Dispatch Controller Internal Buffer
        ItemTransferExecutor.dispatchInternalBuffer(directBuffer, itemInjectors, overclockCount);
        FluidTransferExecutor.dispatchInternalBuffer(directBuffer, fluidInjectors, overclockCount);
        EnergyTransferExecutor.dispatchInternalBuffer(directBuffer, energyInjectors, overclockCount);

        // 4. Execute transfers between extractors and injectors
        for (IItemHandler extractor : itemExtractors) {
            ItemTransferExecutor.executeTransfer(extractor, itemInjectors, overclockCount, "UUP_Extract", "UUP_Insert");
        }
        for (IFluidHandler extractor : fluidExtractors) {
            FluidTransferExecutor.executeTransfer(extractor, fluidInjectors, overclockCount, "UUP_Fluid_Extract", "UUP_Fluid_Insert");
        }
        for (IEnergyStorage extractor : energyExtractors) {
            EnergyTransferExecutor.executeTransfer(extractor, energyInjectors, overclockCount, "UUP_Energy_Extract", "UUP_Energy_Insert");
        }
    }

    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.put("Buffer", directBuffer.serializeNBT());
        tag.putInt("Overclocks", overclockCount);

        ListTag list = new ListTag();
        for (TransferNode node : configuredNodes) {
            list.add(node.serializeNBT());
        }
        tag.put("Nodes", list);
        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        if (tag.contains("Buffer")) {
            directBuffer.deserializeNBT(tag.getCompound("Buffer"));
        }
        if (tag.contains("Overclocks")) {
            overclockCount = tag.getInt("Overclocks");
        }
        configuredNodes.clear();
        if (tag.contains("Nodes")) {
            ListTag list = tag.getList("Nodes", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                configuredNodes.add(TransferNode.deserializeNBT(list.getCompound(i)));
            }
        }
        networkDirty = true;
    }
}
