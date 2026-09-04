package com.uup.core.network;

import com.uup.block.PipeBlock;
import com.uup.blockentity.NodeBlockEntity;
import com.uup.blockentity.PipeBlockEntity;
import com.uup.config.ModConfig;
import com.uup.core.transfer.EnergyTransferExecutor;
import com.uup.core.transfer.FluidTransferExecutor;
import com.uup.core.transfer.GasTransferExecutor;
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

    // Cache of physical network components
    private final Set<BlockPos> cachedPipes = new HashSet<>();
    private final Set<BlockPos> scannedNodePositions = new HashSet<>();
    private final Set<BlockPos> foundControllers = new HashSet<>();
    private BlockPos lowestPipePos = null;
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

    public boolean hasController() {
        return !foundControllers.isEmpty();
    }

    public boolean isMasterPipe(BlockPos myPos) {
        return foundControllers.isEmpty() && myPos != null && myPos.equals(lowestPipePos);
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

    public void rebuildPipeNetwork(ServerLevel level, BlockPos startPos) {
        cachedPipes.clear();
        scannedNodePositions.clear();
        foundControllers.clear();
        lowestPipePos = null;

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        BlockState startState = level.getBlockState(startPos);
        if (startState.getBlock() instanceof PipeBlock) {
            queue.add(startPos);
            visited.add(startPos);
        } else if (startState.is(ModBlocks.CONTROLLER.get())) {
            foundControllers.add(startPos);
            for (Direction dir : Direction.values()) {
                BlockPos adj = startPos.relative(dir);
                if (level.isLoaded(adj) && level.getBlockState(adj).getBlock() instanceof PipeBlock) {
                    queue.add(adj);
                    visited.add(adj);
                }
            }
        }

        while (!queue.isEmpty() && visited.size() < 8192) {
            BlockPos current = queue.poll();
            cachedPipes.add(current);

            if (lowestPipePos == null || current.compareTo(lowestPipePos) < 0) {
                lowestPipePos = current;
            }

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
                    } else if (nextState.is(ModBlocks.CONTROLLER.get())) {
                        visited.add(next);
                        foundControllers.add(next);
                    }
                }
            }
        }
        networkDirty = false;
        UUPLogger.debug(String.format("Rebuilt UUP network: %d pipes, %d nodes, %d controllers.", cachedPipes.size(), scannedNodePositions.size(), foundControllers.size()));
    }

    public void tick(ServerLevel level, BlockPos originPos) {
        tickCounter++;
        int interval = ModConfig.COMMON != null && ModConfig.COMMON.tickInterval != null 
                ? ModConfig.COMMON.tickInterval.get() : 1;

        if (tickCounter % interval != 0) {
            return;
        }

        if (networkDirty || cachedPipes.isEmpty()) {
            rebuildPipeNetwork(level, originPos);
        }

        List<IItemHandler> itemInjectors = new ArrayList<>();
        List<IItemHandler> itemExtractors = new ArrayList<>();
        List<IFluidHandler> fluidInjectors = new ArrayList<>();
        List<IFluidHandler> fluidExtractors = new ArrayList<>();
        List<IEnergyStorage> energyInjectors = new ArrayList<>();
        List<IEnergyStorage> energyExtractors = new ArrayList<>();

        List<Object> gasInjectors = new ArrayList<>();
        List<Object> gasExtractors = new ArrayList<>();
        List<Object> infuseInjectors = new ArrayList<>();
        List<Object> infuseExtractors = new ArrayList<>();
        List<Object> pigmentInjectors = new ArrayList<>();
        List<Object> pigmentExtractors = new ArrayList<>();
        List<Object> slurryInjectors = new ArrayList<>();
        List<Object> slurryExtractors = new ArrayList<>();

        Set<BlockPos> handledPositions = new HashSet<>();

        // 1. Process configured nodes (Manual / Wireless Cards / Attached Node blocks / Pipe Side Settings)
        List<TransferNode> allActiveNodes = new ArrayList<>(configuredNodes);

        for (BlockPos nodePos : scannedNodePositions) {
            if (level.isLoaded(nodePos)) {
                BlockEntity be = level.getBlockEntity(nodePos);
                if (be instanceof NodeBlockEntity nodeBE) {
                    allActiveNodes.add(nodeBE.toNodeData());
                }
            }
        }

        // Collect configured side nodes from PipeBlockEntities
        for (BlockPos pipePos : cachedPipes) {
            if (level.isLoaded(pipePos)) {
                BlockEntity be = level.getBlockEntity(pipePos);
                if (be instanceof PipeBlockEntity pipeBE) {
                    for (Direction dir : Direction.values()) {
                        BlockPos adj = pipePos.relative(dir);
                        if (!cachedPipes.contains(adj) && !foundControllers.contains(adj)) {
                            allActiveNodes.add(pipeBE.toNodeData(dir));
                        }
                    }
                }
            }
        }

        // Sort nodes by Priority descending (Higher priority processed first)
        allActiveNodes.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));

        int totalNodeOverclocks = overclockCount;
        for (TransferNode node : allActiveNodes) {
            totalNodeOverclocks = Math.max(totalNodeOverclocks, node.getOverclocks());
        }

        for (TransferNode node : allActiveNodes) {
            if (node.getMode() == TransferMode.DISABLED) continue;
            
            BlockPos targetPos = node.isWirelessRemote() ? node.getPos() : node.getPos().relative(node.getTargetSide());
            if (!level.isLoaded(targetPos)) continue;

            BlockEntity be = level.getBlockEntity(targetPos);
            if (be == null) continue;

            handledPositions.add(targetPos);
            Direction side = node.getTargetSide().getOpposite();
            boolean canInsert = node.getMode() == TransferMode.INSERT || node.getMode() == TransferMode.BOTH;
            boolean canExtract = node.getMode() == TransferMode.EXTRACT || node.getMode() == TransferMode.BOTH;

            be.getCapability(ForgeCapabilities.ITEM_HANDLER, side).ifPresent(handler -> {
                if (canInsert && !itemInjectors.contains(handler)) itemInjectors.add(handler);
                if (canExtract && !itemExtractors.contains(handler)) itemExtractors.add(handler);
            });

            be.getCapability(ForgeCapabilities.FLUID_HANDLER, side).ifPresent(handler -> {
                if (canInsert && !fluidInjectors.contains(handler)) fluidInjectors.add(handler);
                if (canExtract && !fluidExtractors.contains(handler)) fluidExtractors.add(handler);
            });

            be.getCapability(ForgeCapabilities.ENERGY, side).ifPresent(handler -> {
                if (canInsert && !energyInjectors.contains(handler)) energyInjectors.add(handler);
                if (canExtract && !energyExtractors.contains(handler)) energyExtractors.add(handler);
            });

            GasTransferExecutor.collectCapabilities(
                    be, side,
                    canInsert, canExtract,
                    gasInjectors, gasExtractors,
                    infuseInjectors, infuseExtractors,
                    pigmentInjectors, pigmentExtractors,
                    slurryInjectors, slurryExtractors
            );
        }

        // 2. Process Direct Pipe Connections
        for (BlockPos pipePos : cachedPipes) {
            if (!level.isLoaded(pipePos)) continue;

            BlockState pipeState = level.getBlockState(pipePos);
            PipeBlock.PipeType pType = pipeState.getBlock() instanceof PipeBlock pb ? pb.getType() : PipeBlock.PipeType.UNIVERSAL;

            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = pipePos.relative(dir);
                if (cachedPipes.contains(neighborPos) || foundControllers.contains(neighborPos) || handledPositions.contains(neighborPos)) {
                    continue;
                }
                if (!level.isLoaded(neighborPos)) continue;

                BlockEntity be = level.getBlockEntity(neighborPos);
                if (be == null) continue;

                Direction side = dir.getOpposite();

                // Item transfer
                if (pType == PipeBlock.PipeType.UNIVERSAL || pType == PipeBlock.PipeType.ITEM) {
                    be.getCapability(ForgeCapabilities.ITEM_HANDLER, side).ifPresent(handler -> {
                        if (!itemInjectors.contains(handler)) itemInjectors.add(handler);
                        if (!itemExtractors.contains(handler)) itemExtractors.add(handler);
                    });
                }

                // Fluid transfer
                if (pType == PipeBlock.PipeType.UNIVERSAL || pType == PipeBlock.PipeType.FLUID) {
                    be.getCapability(ForgeCapabilities.FLUID_HANDLER, side).ifPresent(handler -> {
                        if (!fluidInjectors.contains(handler)) fluidInjectors.add(handler);
                        if (!fluidExtractors.contains(handler)) fluidExtractors.add(handler);
                    });
                }

                // Energy transfer
                if (pType == PipeBlock.PipeType.UNIVERSAL || pType == PipeBlock.PipeType.ENERGY) {
                    be.getCapability(ForgeCapabilities.ENERGY, side).ifPresent(handler -> {
                        if (!energyInjectors.contains(handler)) energyInjectors.add(handler);
                        if (!energyExtractors.contains(handler)) energyExtractors.add(handler);
                    });
                }

                // Gas & Chemical transfer
                if (pType == PipeBlock.PipeType.UNIVERSAL || pType == PipeBlock.PipeType.GAS) {
                    GasTransferExecutor.collectCapabilities(
                            be, side,
                            true, true,
                            gasInjectors, gasExtractors,
                            infuseInjectors, infuseExtractors,
                            pigmentInjectors, pigmentExtractors,
                            slurryInjectors, slurryExtractors
                    );
                }
            }
        }

        // 2.5 Process Direct Controller Connections (Machine directly touching Controller)
        for (BlockPos ctrlPos : foundControllers) {
            if (!level.isLoaded(ctrlPos)) continue;

            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = ctrlPos.relative(dir);
                if (cachedPipes.contains(neighborPos) || foundControllers.contains(neighborPos) || handledPositions.contains(neighborPos)) {
                    continue;
                }
                if (!level.isLoaded(neighborPos)) continue;

                BlockEntity be = level.getBlockEntity(neighborPos);
                if (be == null) continue;

                Direction side = dir.getOpposite();

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

                GasTransferExecutor.collectCapabilities(
                        be, side,
                        true, true,
                        gasInjectors, gasExtractors,
                        infuseInjectors, infuseExtractors,
                        pigmentInjectors, pigmentExtractors,
                        slurryInjectors, slurryExtractors
                );
            }
        }

        int effectiveOverclocks = Math.max(overclockCount, totalNodeOverclocks);

        // 3. Controller Internal Buffer Transfer (Dispatch & Ingest for all resources)
        if (!foundControllers.isEmpty()) {
            // Dispatch internal buffer -> network injection targets
            ItemTransferExecutor.dispatchInternalBuffer(directBuffer, itemInjectors, effectiveOverclocks);
            FluidTransferExecutor.dispatchInternalBuffer(directBuffer, fluidInjectors, effectiveOverclocks);
            EnergyTransferExecutor.dispatchInternalBuffer(directBuffer, energyInjectors, effectiveOverclocks);
            GasTransferExecutor.dispatchInternalBuffer(directBuffer.getMekanismBuffer(), gasInjectors, infuseInjectors, pigmentInjectors, slurryInjectors, effectiveOverclocks);

            // Ingest network extraction sources -> internal buffer
            ItemTransferExecutor.ingestToInternalBuffer(directBuffer, itemExtractors, effectiveOverclocks);
            FluidTransferExecutor.ingestToInternalBuffer(directBuffer, fluidExtractors, effectiveOverclocks);
            EnergyTransferExecutor.ingestToInternalBuffer(directBuffer, energyExtractors, effectiveOverclocks);
            GasTransferExecutor.ingestToInternalBuffer(directBuffer.getMekanismBuffer(), gasExtractors, infuseExtractors, pigmentExtractors, slurryExtractors, effectiveOverclocks);
        }

        // 4. Execute transfers between extractors and injectors
        for (IItemHandler extractor : itemExtractors) {
            ItemTransferExecutor.executeTransfer(extractor, itemInjectors, effectiveOverclocks, "UUP_Extract", "UUP_Insert");
        }
        for (IFluidHandler extractor : fluidExtractors) {
            FluidTransferExecutor.executeTransfer(extractor, fluidInjectors, effectiveOverclocks, "UUP_Fluid_Extract", "UUP_Fluid_Insert");
        }
        for (IEnergyStorage extractor : energyExtractors) {
            EnergyTransferExecutor.executeTransfer(extractor, energyInjectors, effectiveOverclocks, "UUP_Energy_Extract", "UUP_Energy_Insert");
        }
        GasTransferExecutor.executeAllTransfers(
                gasInjectors, gasExtractors,
                infuseInjectors, infuseExtractors,
                pigmentInjectors, pigmentExtractors,
                slurryInjectors, slurryExtractors,
                effectiveOverclocks
        );
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
