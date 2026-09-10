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
import com.uup.setup.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
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

    // Persistent Tick-to-Tick Rejection & Full Caches (TTL: 10 ticks = 0.5s)
    private final Map<IItemHandler, Map<ItemTransferExecutor.ItemKey, Long>> itemRejectionCache = new IdentityHashMap<>();
    private final Map<IFluidHandler, Map<net.minecraft.world.level.material.Fluid, Long>> fluidRejectionCache = new IdentityHashMap<>();
    private final Map<IEnergyStorage, Long> energyRejectionCache = new IdentityHashMap<>();

    // Shared Injector Targets across the network (Prepared by master pipe, read by worker pipes)
    private final List<IItemHandler> sharedStorageItemInjectors = new ArrayList<>();
    private final List<IItemHandler> sharedMachineItemInjectors = new ArrayList<>();
    private final List<IItemHandler> sharedAllItemInjectors = new ArrayList<>();

    private final List<IFluidHandler> sharedStorageFluidInjectors = new ArrayList<>();
    private final List<IFluidHandler> sharedMachineFluidInjectors = new ArrayList<>();
    private final List<IFluidHandler> sharedAllFluidInjectors = new ArrayList<>();

    private final List<IEnergyStorage> sharedStorageEnergyInjectors = new ArrayList<>();
    private final List<IEnergyStorage> sharedMachineEnergyInjectors = new ArrayList<>();
    private final List<IEnergyStorage> sharedAllEnergyInjectors = new ArrayList<>();

    private final List<Object> sharedMekEnergyInjectors = new ArrayList<>();
    private final List<Object> sharedGasInjectors = new ArrayList<>();
    private final List<Object> sharedInfuseInjectors = new ArrayList<>();
    private final List<Object> sharedPigmentInjectors = new ArrayList<>();
    private final List<Object> sharedSlurryInjectors = new ArrayList<>();

    private final Map<Object, BlockPos> sharedHandlerPositions = new IdentityHashMap<>();
    private final Map<Object, Integer> sharedHandlerPriorities = new IdentityHashMap<>();
    private final Set<Object> sharedStorageHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> sharedTrashHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<IItemHandler> sharedTrashItemInjectors = new ArrayList<>();
    private final List<IFluidHandler> sharedTrashFluidInjectors = new ArrayList<>();

    // Injector Scan Cache to eliminate 8,000+ redundant scans per tick on master pipe
    private boolean injectorsDirty = true;
    private long lastInjectorsScanTick = -100L;

    // Per-Tick Shared Deduplication & Fast-Skip Maps
    private final Map<IItemHandler, Set<ItemTransferExecutor.ItemKey>> tickItemRejectedMap = new IdentityHashMap<>();
    private final Set<IItemHandler> tickReceivedItemHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<IFluidHandler, Set<net.minecraft.world.level.material.Fluid>> tickFluidRejectedMap = new IdentityHashMap<>();
    private final Set<IFluidHandler> tickReceivedFluidHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<IEnergyStorage> tickReceivedEnergyHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ItemTransferExecutor.ItemKey> tickAllMachinesFullSet = new HashSet<>();

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
        this.injectorsDirty = true;
    }

    public boolean hasController() {
        return !foundControllers.isEmpty();
    }

    public boolean isMasterPipe(BlockPos myPos) {
        return foundControllers.isEmpty() && myPos != null && myPos.equals(lowestPipePos);
    }

    public boolean shouldTickStandalone(BlockPos myPos) {
        if (!foundControllers.isEmpty()) {
            return false;
        }
        if (lowestPipePos == null || networkDirty) {
            return true;
        }
        return myPos != null && myPos.equals(lowestPipePos);
    }

    public void syncNetworkState(BlockPos lowestPos, Set<BlockPos> controllers) {
        this.lowestPipePos = lowestPos;
        this.foundControllers.clear();
        this.foundControllers.addAll(controllers);
        this.networkDirty = false;
    }

    public void addNode(TransferNode node) {
        if (!configuredNodes.contains(node)) {
            configuredNodes.add(node);
            this.injectorsDirty = true;
            UUPLogger.info(String.format("Added UUP configured node at %s (Mode=%s, Channel=%d)", node.getPos(), node.getMode(), node.getChannelId()));
        }
    }

    public void removeNode(TransferNode node) {
        configuredNodes.remove(node);
        this.injectorsDirty = true;
        UUPLogger.info(String.format("Removed UUP node at %s", node.getPos()));
    }

    public void rebuildPipeNetwork(ServerLevel level, BlockPos startPos) {
        cachedPipes.clear();
        scannedNodePositions.clear();
        foundControllers.clear();
        itemRejectionCache.clear();
        fluidRejectionCache.clear();
        energyRejectionCache.clear();
        lowestPipePos = null;
        this.injectorsDirty = true;

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

        // 同一配管網内の他のすべてのPipeBlockEntityにマスター情報を即時同期し、初回重複スキャンを完全防止
        for (BlockPos pPos : cachedPipes) {
            if (level.isLoaded(pPos)) {
                BlockEntity be = level.getBlockEntity(pPos);
                if (be instanceof PipeBlockEntity otherPipe) {
                    otherPipe.syncStandaloneMaster(this, lowestPipePos, foundControllers);
                }
            }
        }
    }

    private static <T> void collectForgeCap(
            BlockEntity be,
            net.minecraftforge.common.capabilities.Capability<T> cap,
            Direction side,
            int priority,
            boolean canInsert,
            boolean canExtract,
            List<T> storageInjectors,
            List<T> machineInjectors,
            List<T> trashInjectors,
            List<T> extractors,
            Map<Object, BlockPos> positions,
            Map<Object, Integer> priorities,
            Set<Object> storageSet,
            Set<Object> trashSet
    ) {
        if (be == null) return;
        var opt = be.getCapability(cap, side);
        if (opt.isPresent()) {
            opt.ifPresent(handler -> registerCap(handler, be, priority, canInsert, canExtract, storageInjectors, machineInjectors, trashInjectors, extractors, positions, priorities, storageSet, trashSet));
        } else if (side != null) {
            be.getCapability(cap, null).ifPresent(handler -> registerCap(handler, be, priority, canInsert, canExtract, storageInjectors, machineInjectors, trashInjectors, extractors, positions, priorities, storageSet, trashSet));
        }
    }

    private static <T> void registerCap(
            T handler,
            BlockEntity be,
            int priority,
            boolean canInsert,
            boolean canExtract,
            List<T> storageInjectors,
            List<T> machineInjectors,
            List<T> trashInjectors,
            List<T> extractors,
            Map<Object, BlockPos> positions,
            Map<Object, Integer> priorities,
            Set<Object> storageSet,
            Set<Object> trashSet
    ) {
        if (positions != null && be != null) {
            positions.put(handler, be.getBlockPos());
        }
        if (priorities != null) {
            int effectivePriority = priority;
            if (StorageDetector.isTrashCan(be)) {
                effectivePriority += 100; // Trash / Void receptacles get priority bonus (+100) to safely void output items instead of recycling to input storage
            }
            priorities.put(handler, effectivePriority);
        }
        boolean isStorage = StorageDetector.isStorage(be);
        if (isStorage && storageSet != null) {
            storageSet.add(handler);
        }
        boolean isTrash = StorageDetector.isTrashCan(be);
        if (isTrash && trashSet != null) {
            trashSet.add(handler);
        }

        if (canInsert) {
            if (isTrash && trashInjectors != null) {
                if (!trashInjectors.contains(handler)) trashInjectors.add(handler);
            }
            if (isStorage) {
                if (!storageInjectors.contains(handler)) storageInjectors.add(handler);
            } else {
                if (!machineInjectors.contains(handler)) machineInjectors.add(handler);
            }
        }
        if (canExtract && !extractors.contains(handler)) {
            extractors.add(handler);
        }
    }

    private static <T> void sortStorageInjectors(
            List<T> injectors,
            BlockPos originPos,
            Map<Object, BlockPos> positions,
            Map<Object, Integer> priorities
    ) {
        if (injectors.size() <= 1) return;
        injectors.sort((t1, t2) -> {
            int p1 = priorities != null ? priorities.getOrDefault(t1, 0) : 0;
            int p2 = priorities != null ? priorities.getOrDefault(t2, 0) : 0;
            if (p1 != p2) return Integer.compare(p2, p1);

            if (originPos != null && positions != null) {
                BlockPos pos1 = positions.get(t1);
                BlockPos pos2 = positions.get(t2);
                if (pos1 != null && pos2 != null) {
                    double d1 = originPos.distSqr(pos1);
                    double d2 = originPos.distSqr(pos2);
                    int cmp = Double.compare(d1, d2);
                    if (cmp != 0) return cmp;
                    return pos1.compareTo(pos2);
                }
            }
            return 0;
        });
    }

    public void prepareNetworkTick(ServerLevel level, BlockPos originPos) {
        tickCounter++;
        int interval = ModConfig.COMMON != null && ModConfig.COMMON.tickInterval != null 
                ? ModConfig.COMMON.tickInterval.get() : 1;

        if (tickCounter % interval != 0) {
            return;
        }

        if (networkDirty || cachedPipes.isEmpty()) {
            rebuildPipeNetwork(level, originPos);
        }

        // Per-Tick Shared Deduplication & Fast-Skip Maps
        tickItemRejectedMap.clear();
        tickReceivedItemHandlers.clear();
        tickFluidRejectedMap.clear();
        tickReceivedFluidHandlers.clear();
        tickReceivedEnergyHandlers.clear();
        tickAllMachinesFullSet.clear();

        long gameTime = level.getGameTime();
        boolean shouldRescan = injectorsDirty || (gameTime - lastInjectorsScanTick >= 40) || (gameTime < lastInjectorsScanTick);

        if (shouldRescan) {
            lastInjectorsScanTick = gameTime;
            injectorsDirty = false;

            sharedStorageItemInjectors.clear();
            sharedMachineItemInjectors.clear();
            sharedAllItemInjectors.clear();
            sharedTrashItemInjectors.clear();

            sharedStorageFluidInjectors.clear();
            sharedMachineFluidInjectors.clear();
            sharedAllFluidInjectors.clear();
            sharedTrashFluidInjectors.clear();

            sharedStorageEnergyInjectors.clear();
            sharedMachineEnergyInjectors.clear();
            sharedAllEnergyInjectors.clear();

            sharedMekEnergyInjectors.clear();
            sharedGasInjectors.clear();
            sharedInfuseInjectors.clear();
            sharedPigmentInjectors.clear();
            sharedSlurryInjectors.clear();

            sharedHandlerPositions.clear();
            sharedHandlerPriorities.clear();
            sharedStorageHandlers.clear();
            sharedTrashHandlers.clear();

            Set<BlockPos> handledPositions = new HashSet<>();

            // 1. Collect configured wireless nodes and connected nodes
            List<TransferNode> allActiveNodes = new ArrayList<>(configuredNodes);

            // Process physically connected wired transfer nodes (Must be adjacent to a pipe in cachedPipes)
            for (BlockPos nodePos : scannedNodePositions) {
                if (level.isLoaded(nodePos)) {
                    BlockEntity be = level.getBlockEntity(nodePos);
                    if (be instanceof NodeBlockEntity nodeBE) {
                        allActiveNodes.add(nodeBE.toNodeData());
                    }
                }
            }

            // Collect configured side nodes from PipeBlockEntities (INSERT only for shared injector targets)
            for (BlockPos pipePos : cachedPipes) {
                if (level.isLoaded(pipePos)) {
                    BlockEntity be = level.getBlockEntity(pipePos);
                    if (be instanceof PipeBlockEntity pipeBE) {
                        for (Direction dir : Direction.values()) {
                            BlockPos adj = pipePos.relative(dir);
                            if (!cachedPipes.contains(adj) && !foundControllers.contains(adj)) {
                                TransferNode node = pipeBE.toNodeData(dir);
                                if (node.getMode() == TransferMode.INSERT || node.getMode() == TransferMode.BOTH) {
                                    allActiveNodes.add(node);
                                }
                            }
                        }
                    }
                }
            }

            // Sort nodes by Priority descending
            allActiveNodes.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));

            List<TransferNode> brokenWirelessNodes = null;

            for (TransferNode node : allActiveNodes) {
                BlockPos targetPos = node.isWirelessRemote() ? node.getPos() : node.getPos().relative(node.getTargetSide());
                ServerLevel targetLevel = (node.isWirelessRemote() && level.getServer() != null && node.getDimension() != null)
                        ? level.getServer().getLevel(node.getDimension())
                        : level;
                if (targetLevel == null || !targetLevel.isLoaded(targetPos)) continue;

                BlockEntity be = targetLevel.getBlockEntity(targetPos);

                if (node.isWirelessRemote() && (be == null || targetLevel.getBlockState(targetPos).isAir())) {
                    Containers.dropItemStack(
                            targetLevel,
                            targetPos.getX() + 0.5,
                            targetPos.getY() + 0.5,
                            targetPos.getZ() + 0.5,
                            new ItemStack(ModItems.NETWORK_CARD.get())
                    );
                    UUPLogger.info(String.format("Wireless target block at %s was destroyed! Dropped reset Network Card and removed node.", targetPos.toShortString()));
                    if (brokenWirelessNodes == null) {
                        brokenWirelessNodes = new ArrayList<>();
                    }
                    brokenWirelessNodes.add(node);
                    continue;
                }

                if (be == null || node.getMode() == TransferMode.DISABLED) continue;

                handledPositions.add(targetPos);
                Direction side = node.getTargetSide().getOpposite();
                boolean canInsert = node.getMode() == TransferMode.INSERT || node.getMode() == TransferMode.BOTH;
                int nodePriority = node.getPriority();

                if (canInsert) {
                    collectForgeCap(be, ForgeCapabilities.ITEM_HANDLER, side, nodePriority, true, false, sharedStorageItemInjectors, sharedMachineItemInjectors, sharedTrashItemInjectors, null, sharedHandlerPositions, sharedHandlerPriorities, sharedStorageHandlers, sharedTrashHandlers);
                    collectForgeCap(be, ForgeCapabilities.FLUID_HANDLER, side, nodePriority, true, false, sharedStorageFluidInjectors, sharedMachineFluidInjectors, sharedTrashFluidInjectors, null, sharedHandlerPositions, sharedHandlerPriorities, sharedStorageHandlers, sharedTrashHandlers);
                    collectForgeCap(be, ForgeCapabilities.ENERGY, side, nodePriority, true, false, sharedStorageEnergyInjectors, sharedMachineEnergyInjectors, null, null, sharedHandlerPositions, sharedHandlerPriorities, sharedStorageHandlers, sharedTrashHandlers);

                    EnergyTransferExecutor.collectMekanismCapabilities(be, side, true, false, sharedMekEnergyInjectors, null);
                    GasTransferExecutor.collectCapabilities(be, side, true, false, sharedGasInjectors, null, sharedInfuseInjectors, null, sharedPigmentInjectors, null, sharedSlurryInjectors, null);
                }
            }

            if (brokenWirelessNodes != null) {
                configuredNodes.removeAll(brokenWirelessNodes);
                networkDirty = true;
                injectorsDirty = true;
            }

            // Direct Controller Connections (Machine directly touching Controller)
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

                    collectForgeCap(be, ForgeCapabilities.ITEM_HANDLER, side, 0, true, false, sharedStorageItemInjectors, sharedMachineItemInjectors, sharedTrashItemInjectors, null, sharedHandlerPositions, sharedHandlerPriorities, sharedStorageHandlers, sharedTrashHandlers);
                    collectForgeCap(be, ForgeCapabilities.FLUID_HANDLER, side, 0, true, false, sharedStorageFluidInjectors, sharedMachineFluidInjectors, sharedTrashFluidInjectors, null, sharedHandlerPositions, sharedHandlerPriorities, sharedStorageHandlers, sharedTrashHandlers);
                    collectForgeCap(be, ForgeCapabilities.ENERGY, side, 0, true, false, sharedStorageEnergyInjectors, sharedMachineEnergyInjectors, null, null, sharedHandlerPositions, sharedHandlerPriorities, sharedStorageHandlers, sharedTrashHandlers);

                    EnergyTransferExecutor.collectMekanismCapabilities(be, side, true, false, sharedMekEnergyInjectors, null);
                    GasTransferExecutor.collectCapabilities(be, side, true, false, sharedGasInjectors, null, sharedInfuseInjectors, null, sharedPigmentInjectors, null, sharedSlurryInjectors, null);
                }
            }

            // Sort storage injectors once per tick: Priority descending, then distance from origin ascending (Nearest-First)
            sortStorageInjectors(sharedStorageItemInjectors, originPos, sharedHandlerPositions, sharedHandlerPriorities);
            sortStorageInjectors(sharedStorageFluidInjectors, originPos, sharedHandlerPositions, sharedHandlerPriorities);
            sortStorageInjectors(sharedStorageEnergyInjectors, originPos, sharedHandlerPositions, sharedHandlerPriorities);

            sharedAllItemInjectors.addAll(sharedStorageItemInjectors);
            sharedAllItemInjectors.addAll(sharedMachineItemInjectors);

            sharedAllFluidInjectors.addAll(sharedStorageFluidInjectors);
            sharedAllFluidInjectors.addAll(sharedMachineFluidInjectors);

            sharedAllEnergyInjectors.addAll(sharedStorageEnergyInjectors);
            sharedAllEnergyInjectors.addAll(sharedMachineEnergyInjectors);
        }

        // Controller Internal Buffer Transfer (Dispatch to shared targets)
        if (!foundControllers.isEmpty()) {
            int effectiveOverclocks = overclockCount;
            ItemTransferExecutor.dispatchInternalBuffer(directBuffer, sharedAllItemInjectors, effectiveOverclocks);
            FluidTransferExecutor.dispatchInternalBuffer(directBuffer, sharedAllFluidInjectors, effectiveOverclocks);
            EnergyTransferExecutor.dispatchInternalBuffer(directBuffer, sharedAllEnergyInjectors, effectiveOverclocks);
            EnergyTransferExecutor.dispatchMekanismBuffer(directBuffer.getMekanismBuffer(), sharedMekEnergyInjectors, effectiveOverclocks);
            GasTransferExecutor.dispatchInternalBuffer(directBuffer.getMekanismBuffer(), sharedGasInjectors, sharedInfuseInjectors, sharedPigmentInjectors, sharedSlurryInjectors, effectiveOverclocks);
        }
    }

    public boolean tickPipeExtract(ServerLevel level, PipeBlockEntity pipe) {
        if (pipe == null) return false;
        if (sharedAllItemInjectors.isEmpty() && sharedAllFluidInjectors.isEmpty() && sharedAllEnergyInjectors.isEmpty() 
                && sharedMekEnergyInjectors.isEmpty() && sharedGasInjectors.isEmpty() && sharedInfuseInjectors.isEmpty() 
                && sharedPigmentInjectors.isEmpty() && sharedSlurryInjectors.isEmpty()) {
            return false;
        }

        boolean foundAnyInventory = false;
        BlockPos pipePos = pipe.getBlockPos();
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pipePos.relative(dir);
            if (cachedPipes.contains(neighborPos) || foundControllers.contains(neighborPos)) {
                continue;
            }
            if (!level.isLoaded(neighborPos)) continue;

            TransferMode mode = pipe.getMode(dir);
            if (mode != TransferMode.EXTRACT && mode != TransferMode.BOTH) {
                continue;
            }

            BlockEntity neighborBE = level.getBlockEntity(neighborPos);
            if (neighborBE == null) continue;

            foundAnyInventory = true;
            Direction side = dir.getOpposite();
            int nodeOverclocks = pipe.getUpgradeHandler(dir).getStackInSlot(0).getCount();
            int effectiveOverclocks = Math.max(this.overclockCount, nodeOverclocks);
            int sourcePriority = pipe.getPriority(dir);
            boolean isSourceStorage = StorageDetector.isStorage(neighborBE);

            // 1. アイテム分散搬出
            if (!sharedAllItemInjectors.isEmpty()) {
                var itemOpt = neighborBE.getCapability(ForgeCapabilities.ITEM_HANDLER, side);
                IItemHandler itemHandler = itemOpt.isPresent() ? itemOpt.orElse(null) : neighborBE.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
                if (itemHandler != null && !tickReceivedItemHandlers.contains(itemHandler)) {
                    List<IItemHandler> itemTargets;
                    if (isSourceStorage) {
                        int maxStoragePriority = sharedStorageItemInjectors.isEmpty() ? Integer.MIN_VALUE 
                                : sharedHandlerPriorities.getOrDefault(sharedStorageItemInjectors.get(0), 0);
                        itemTargets = new ArrayList<>();
                        if (maxStoragePriority > sourcePriority) {
                            for (IItemHandler target : sharedStorageItemInjectors) {
                                if (sharedTrashHandlers.contains(target)) continue; // Never void from input storage
                                if (sharedHandlerPriorities.getOrDefault(target, 0) > sourcePriority) {
                                    itemTargets.add(target);
                                } else {
                                    break;
                                }
                            }
                        }
                        for (IItemHandler target : sharedMachineItemInjectors) {
                            if (!sharedTrashHandlers.contains(target)) {
                                itemTargets.add(target);
                            }
                        }
                    } else {
                        if (!sharedTrashItemInjectors.isEmpty()) {
                            itemTargets = sharedTrashItemInjectors; // Directly void machine products without recycling into input chests!
                        } else if (!sharedStorageItemInjectors.isEmpty()) {
                            itemTargets = sharedStorageItemInjectors;
                        } else {
                            itemTargets = sharedAllItemInjectors;
                        }
                    }

                    if (!itemTargets.isEmpty()) {
                        sharedHandlerPositions.putIfAbsent(itemHandler, neighborPos);
                        ItemTransferExecutor.executeTransfer(
                                itemHandler,
                                itemTargets,
                                effectiveOverclocks,
                                neighborPos.toShortString(),
                                "Network_Target",
                                tickItemRejectedMap,
                                tickReceivedItemHandlers,
                                0L,
                                null,
                                sharedHandlerPositions,
                                sharedStorageHandlers,
                                tickAllMachinesFullSet
                        );
                    }
                }
            }

            // 2. 流体分散搬出
            if (!sharedAllFluidInjectors.isEmpty()) {
                var fluidOpt = neighborBE.getCapability(ForgeCapabilities.FLUID_HANDLER, side);
                IFluidHandler fluidHandler = fluidOpt.isPresent() ? fluidOpt.orElse(null) : neighborBE.getCapability(ForgeCapabilities.FLUID_HANDLER, null).orElse(null);
                if (fluidHandler != null && !tickReceivedFluidHandlers.contains(fluidHandler)) {
                    List<IFluidHandler> fluidTargets;
                    if (isSourceStorage) {
                        int maxStoragePriority = sharedStorageFluidInjectors.isEmpty() ? Integer.MIN_VALUE 
                                : sharedHandlerPriorities.getOrDefault(sharedStorageFluidInjectors.get(0), 0);
                        fluidTargets = new ArrayList<>();
                        if (maxStoragePriority > sourcePriority) {
                            for (IFluidHandler target : sharedStorageFluidInjectors) {
                                if (sharedTrashHandlers.contains(target)) continue;
                                if (sharedHandlerPriorities.getOrDefault(target, 0) > sourcePriority) {
                                    fluidTargets.add(target);
                                } else {
                                    break;
                                }
                            }
                        }
                        for (IFluidHandler target : sharedMachineFluidInjectors) {
                            if (!sharedTrashHandlers.contains(target)) {
                                fluidTargets.add(target);
                            }
                        }
                    } else {
                        if (!sharedTrashFluidInjectors.isEmpty()) {
                            fluidTargets = sharedTrashFluidInjectors;
                        } else if (!sharedStorageFluidInjectors.isEmpty()) {
                            fluidTargets = sharedStorageFluidInjectors;
                        } else {
                            fluidTargets = sharedAllFluidInjectors;
                        }
                    }

                    if (!fluidTargets.isEmpty()) {
                        sharedHandlerPositions.putIfAbsent(fluidHandler, neighborPos);
                        FluidTransferExecutor.executeTransfer(
                                fluidHandler,
                                fluidTargets,
                                effectiveOverclocks,
                                neighborPos.toShortString(),
                                "Network_Fluid_Target",
                                tickFluidRejectedMap,
                                tickReceivedFluidHandlers,
                                0L,
                                null
                        );
                    }
                }
            }

            // 3. エネルギー分散搬出
            if (!sharedAllEnergyInjectors.isEmpty()) {
                var energyOpt = neighborBE.getCapability(ForgeCapabilities.ENERGY, side);
                IEnergyStorage energyHandler = energyOpt.isPresent() ? energyOpt.orElse(null) : neighborBE.getCapability(ForgeCapabilities.ENERGY, null).orElse(null);
                if (energyHandler != null && !tickReceivedEnergyHandlers.contains(energyHandler)) {
                    List<IEnergyStorage> energyTargets;
                    if (isSourceStorage) {
                        int maxStoragePriority = sharedStorageEnergyInjectors.isEmpty() ? Integer.MIN_VALUE 
                                : sharedHandlerPriorities.getOrDefault(sharedStorageEnergyInjectors.get(0), 0);
                        if (maxStoragePriority <= sourcePriority) {
                            energyTargets = sharedMachineEnergyInjectors;
                        } else {
                            energyTargets = new ArrayList<>();
                            for (IEnergyStorage target : sharedStorageEnergyInjectors) {
                                if (sharedHandlerPriorities.getOrDefault(target, 0) > sourcePriority) {
                                    energyTargets.add(target);
                                } else {
                                    break;
                                }
                            }
                            energyTargets.addAll(sharedMachineEnergyInjectors);
                        }
                    } else {
                        energyTargets = sharedAllEnergyInjectors;
                    }

                    if (!energyTargets.isEmpty()) {
                        sharedHandlerPositions.putIfAbsent(energyHandler, neighborPos);
                        EnergyTransferExecutor.executeTransfer(
                                energyHandler,
                                energyTargets,
                                effectiveOverclocks,
                                neighborPos.toShortString(),
                                "Network_Energy_Target",
                                tickReceivedEnergyHandlers,
                                0L,
                                null
                        );
                    }
                }
            }

            // 4. Mekanism エネルギー＆ガス分散搬出
            if (!sharedMekEnergyInjectors.isEmpty()) {
                List<Object> singleMekExt = new ArrayList<>(1);
                EnergyTransferExecutor.collectMekanismCapabilities(neighborBE, side, false, true, null, singleMekExt);
                if (!singleMekExt.isEmpty()) {
                    EnergyTransferExecutor.executeMekanismTransfers(singleMekExt, sharedMekEnergyInjectors, effectiveOverclocks);
                }
            }

            if (!sharedGasInjectors.isEmpty() || !sharedInfuseInjectors.isEmpty() || !sharedPigmentInjectors.isEmpty() || !sharedSlurryInjectors.isEmpty()) {
                List<Object> sGasExt = new ArrayList<>(1);
                List<Object> sInfExt = new ArrayList<>(1);
                List<Object> sPigExt = new ArrayList<>(1);
                List<Object> sSluExt = new ArrayList<>(1);
                GasTransferExecutor.collectCapabilities(neighborBE, side, false, true, null, sGasExt, null, sInfExt, null, sPigExt, null, sSluExt);
                if (!sGasExt.isEmpty() || !sInfExt.isEmpty() || !sPigExt.isEmpty() || !sSluExt.isEmpty()) {
                    GasTransferExecutor.executeAllTransfers(
                            sharedGasInjectors, sGasExt,
                            sharedInfuseInjectors, sInfExt,
                            sharedPigmentInjectors, sPigExt,
                            sharedSlurryInjectors, sSluExt,
                            effectiveOverclocks
                    );
                }
            }
        }
        return foundAnyInventory;
    }

    public void tick(ServerLevel level, BlockPos originPos) {
        prepareNetworkTick(level, originPos);

        // Fallback for Controller ingest & wireless extraction
        if (!foundControllers.isEmpty()) {
            List<IItemHandler> itemExtractors = new ArrayList<>();
            List<IFluidHandler> fluidExtractors = new ArrayList<>();
            List<IEnergyStorage> energyExtractors = new ArrayList<>();
            List<Object> mekEnergyExtractors = new ArrayList<>();
            List<Object> gasExtractors = new ArrayList<>();
            List<Object> infuseExtractors = new ArrayList<>();
            List<Object> pigmentExtractors = new ArrayList<>();
            List<Object> slurryExtractors = new ArrayList<>();

            for (BlockPos ctrlPos : foundControllers) {
                if (!level.isLoaded(ctrlPos)) continue;
                for (Direction dir : Direction.values()) {
                    BlockPos neighborPos = ctrlPos.relative(dir);
                    if (cachedPipes.contains(neighborPos) || foundControllers.contains(neighborPos)) continue;
                    if (!level.isLoaded(neighborPos)) continue;
                    BlockEntity be = level.getBlockEntity(neighborPos);
                    if (be == null) continue;
                    Direction side = dir.getOpposite();
                    collectForgeCap(be, ForgeCapabilities.ITEM_HANDLER, side, 0, false, true, null, null, null, itemExtractors, null, null, null, null);
                    collectForgeCap(be, ForgeCapabilities.FLUID_HANDLER, side, 0, false, true, null, null, null, fluidExtractors, null, null, null, null);
                    collectForgeCap(be, ForgeCapabilities.ENERGY, side, 0, false, true, null, null, null, energyExtractors, null, null, null, null);
                    EnergyTransferExecutor.collectMekanismCapabilities(be, side, false, true, null, mekEnergyExtractors);
                    GasTransferExecutor.collectCapabilities(be, side, false, true, null, gasExtractors, null, infuseExtractors, null, pigmentExtractors, null, slurryExtractors);
                }
            }

            int effectiveOverclocks = overclockCount;
            ItemTransferExecutor.ingestToInternalBuffer(directBuffer, itemExtractors, effectiveOverclocks);
            FluidTransferExecutor.ingestToInternalBuffer(directBuffer, fluidExtractors, effectiveOverclocks);
            EnergyTransferExecutor.ingestToInternalBuffer(directBuffer, energyExtractors, effectiveOverclocks);
            EnergyTransferExecutor.ingestMekanismBuffer(directBuffer.getMekanismBuffer(), mekEnergyExtractors, effectiveOverclocks);
            GasTransferExecutor.ingestToInternalBuffer(directBuffer.getMekanismBuffer(), gasExtractors, infuseExtractors, pigmentExtractors, slurryExtractors, effectiveOverclocks);
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
                TransferNode node = TransferNode.deserializeNBT(list.getCompound(i));
                if (node.isWirelessRemote()) {
                    configuredNodes.add(node);
                }
            }
        }
        networkDirty = true;
    }
}
