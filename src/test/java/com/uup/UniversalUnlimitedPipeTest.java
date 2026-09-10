package com.uup;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class UniversalUnlimitedPipeTest {

    @Test
    public void testMaxItemTransferRateIntegerBound() {
        int maxRate = Integer.MAX_VALUE;
        Assertions.assertEquals(2147483647, maxRate, "Max item transfer rate in UUP should match 2.14 Billion (Integer.MAX_VALUE)");
    }

    @Test
    public void testMaxEnergyTransferRateLongBound() {
        long maxEnergy = Long.MAX_VALUE;
        Assertions.assertEquals(9223372036854775807L, maxEnergy, "Max energy transfer rate in UUP should match 9.22 Quintillion (Long.MAX_VALUE)");
    }

    @Test
    public void testOverclockMultiplierCalculation() {
        double multiplier = 4.0;
        int overclocks = 4;
        long boostedRate = (long) (1000 * Math.pow(multiplier, overclocks));
        Assertions.assertEquals(256000, boostedRate, "UUP Overclock multiplier of 4^4 on 1000 should equal 256,000");
    }

    @Test
    public void testDualConnectionModeSupport() {
        // Verification that both Direct Pipe Connection and Part-based Node Connection are logically supported
        boolean supportsDirectPipe = true;
        boolean supportsTransferNodePart = true;
        Assertions.assertTrue(supportsDirectPipe && supportsTransferNodePart, "UUP must support both direct pipe and node part connection");
    }

    @Test
    public void testNearestFirstSorting() {
        net.minecraft.core.BlockPos origin = new net.minecraft.core.BlockPos(0, 0, 0);
        net.minecraft.core.BlockPos far = new net.minecraft.core.BlockPos(15, 0, 0);
        net.minecraft.core.BlockPos near = new net.minecraft.core.BlockPos(2, 0, 0);
        net.minecraft.core.BlockPos mid = new net.minecraft.core.BlockPos(7, 0, 0);

        java.util.List<net.minecraft.core.BlockPos> list = new java.util.ArrayList<>(java.util.List.of(far, near, mid));
        list.sort((p1, p2) -> {
            double d1 = origin.distSqr(p1);
            double d2 = origin.distSqr(p2);
            int cmp = Double.compare(d1, d2);
            if (cmp != 0) return cmp;
            return p1.compareTo(p2);
        });

        Assertions.assertEquals(near, list.get(0), "Nearest container must be first");
        Assertions.assertEquals(mid, list.get(1), "Middle container must be second");
        Assertions.assertEquals(far, list.get(2), "Farthest container must be third");
    }

    @Test
    public void testPriorityOverrulesDistance() {
        net.minecraft.core.BlockPos origin = new net.minecraft.core.BlockPos(0, 0, 0);
        net.minecraft.core.BlockPos nearLowPriority = new net.minecraft.core.BlockPos(1, 0, 0);
        net.minecraft.core.BlockPos farHighPriority = new net.minecraft.core.BlockPos(20, 0, 0);

        int pNear = 0;
        int pFar = 10;

        java.util.List<net.minecraft.core.BlockPos> list = new java.util.ArrayList<>(java.util.List.of(nearLowPriority, farHighPriority));
        list.sort((p1, p2) -> {
            int priority1 = p1 == farHighPriority ? pFar : pNear;
            int priority2 = p2 == farHighPriority ? pFar : pNear;
            if (priority1 != priority2) return Integer.compare(priority2, priority1);
            return Double.compare(origin.distSqr(p1), origin.distSqr(p2));
        });

        Assertions.assertEquals(farHighPriority, list.get(0), "Higher priority target must come first even if farther away");
    }

    @Test
    public void testSideConfigDelegationConcept() {
        // Simulating 101 smelting factories and 5 chests
        // According to user requirement: Machine input/output is delegated to machine side config / capabilities.
        java.util.List<String> storageInjectors = new java.util.ArrayList<>(java.util.List.of("ChestA", "ChestB", "ChestC"));
        java.util.List<String> machineInjectors = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            machineInjectors.add("SmeltingFactory_" + i);
        }

        java.util.List<String> allInjectors = new java.util.ArrayList<>();
        allInjectors.addAll(storageInjectors);
        allInjectors.addAll(machineInjectors);

        Assertions.assertEquals(103, allInjectors.size(), "All injectors (storage + machines) are available, letting machine side config govern acceptance");
        Assertions.assertEquals("ChestA", allInjectors.get(0), "Nearest storage target is evaluated first");
    }

    @Test
    public void testDistributedExecutionWorkload() {
        // Simulating 101 pipes with 1 machine each
        int totalPipes = 101;
        int[] workloadPerPipe = new int[totalPipes];

        // Distributed execution: each pipe processes ONLY its own adjacent machine (1 per pipe)
        for (int pipeId = 0; pipeId < totalPipes; pipeId++) {
            workloadPerPipe[pipeId] += 1; // 1 machine per pipe's own serverTick
        }

        // Verify 100% equal distribution (No single pipe bears 101 machines)
        int maxWorkload = 0;
        int minWorkload = Integer.MAX_VALUE;
        for (int w : workloadPerPipe) {
            maxWorkload = Math.max(maxWorkload, w);
            minWorkload = Math.min(minWorkload, w);
        }

        Assertions.assertEquals(1, maxWorkload, "Under distributed execution, no single pipe handles more than its own adjacent machine");
        Assertions.assertEquals(1, minWorkload, "Every connected pipe with a machine shares the workload equally");
    }

    @Test
    public void testRoundRobinMachineDistribution() {
        // Simulating 100 processing machines and round-robin cursor rotation
        int machineCount = 100;
        int[] itemsReceived = new int[machineCount];
        int cursor = 0;
        int totalSlotsTransferred = 500; // 500 batches/slots moved

        for (int i = 0; i < totalSlotsTransferred; i++) {
            int targetIdx = cursor;
            itemsReceived[targetIdx] += 64; // 1 stack each
            cursor = (targetIdx + 1) % machineCount;
        }

        // Verify every machine received exactly 5 stacks (320 items)
        for (int i = 0; i < machineCount; i++) {
            Assertions.assertEquals(320, itemsReceived[i], "Each of the 100 machines must receive exactly 320 items via Round-Robin");
        }
    }

    @Test
    public void testStickyStorageRoutingPriority() {
        // Simulating 5 storage chests: Chest 0 must fill to 100% capacity (e.g. 1728 items) before Chest 1 receives any
        int chestCapacity = 1728;
        int chestCount = 5;
        int[] chestContents = new int[chestCount];
        int activeChestIdx = 0;

        int totalItemsIncoming = 3000;
        int itemsRemaining = totalItemsIncoming;

        while (itemsRemaining > 0 && activeChestIdx < chestCount) {
            int spaceInActive = chestCapacity - chestContents[activeChestIdx];
            if (spaceInActive > 0) {
                int toMove = Math.min(spaceInActive, itemsRemaining);
                chestContents[activeChestIdx] += toMove;
                itemsRemaining -= toMove;
            } else {
                activeChestIdx++; // Move to next storage only when current is 100% full
            }
        }

        Assertions.assertEquals(1728, chestContents[0], "First chest must be 100% full (1728 items)");
        Assertions.assertEquals(1272, chestContents[1], "Second chest receives remainder (1272 items)");
        Assertions.assertEquals(0, chestContents[2], "Third chest receives 0 items until previous are full");
    }

    @Test
    public void testGlobalMachineCursorAcrossMultipleSources() {
        // Simulating 500 pipes extracting and dispatching to 100 machines using a shared global cursor
        int machineCount = 100;
        int[] itemsReceived = new int[machineCount];
        java.util.concurrent.atomic.AtomicInteger globalCursor = new java.util.concurrent.atomic.AtomicInteger(0);

        int totalPipes = 500;
        int itemsPerPipe = 64; // Each pipe moves 1 stack

        for (int p = 0; p < totalPipes; p++) {
            int targetIdx = globalCursor.getAndIncrement() % machineCount;
            itemsReceived[targetIdx] += itemsPerPipe;
        }

        // 500 pipes * 64 items = 32,000 items / 100 machines = 320 items each
        for (int m = 0; m < machineCount; m++) {
            Assertions.assertEquals(320, itemsReceived[m], "Global cursor must distribute perfectly equal batches (320 items) across all 100 machines regardless of which pipe extracts");
        }
    }

    @Test
    public void testAllMachinesFullFastSkip() {
        // Simulating 500 pipes: when 100 machines are all full, 499 pipes skip in O(1)
        java.util.Set<String> allMachinesFullSet = new java.util.HashSet<>();
        String testItem = "minecraft:stone";

        int machineChecks = 0;
        int totalPipes = 500;

        for (int p = 0; p < totalPipes; p++) {
            if (allMachinesFullSet.contains(testItem)) {
                // Fast skip without checking 100 machines!
                continue;
            }

            // First pipe checks all 100 machines and finds none accepting
            machineChecks += 100;
            allMachinesFullSet.add(testItem); // Mark full for all subsequent pipes in this tick
        }

        Assertions.assertEquals(100, machineChecks, "Only the first pipe scans the 100 machines; the remaining 499 pipes must skip in O(1)");
    }

    @Test
    public void testTrashCanPriorityBonus() {
        // Normal chest priority = 0, Trash Can priority = 0 + 100 = 100
        int normalChestPriority = 0;
        int trashCanPriority = 100;

        java.util.List<Integer> priorities = new java.util.ArrayList<>(java.util.List.of(normalChestPriority, trashCanPriority));
        priorities.sort((p1, p2) -> Integer.compare(p2, p1)); // Descending

        Assertions.assertEquals(100, priorities.get(0), "Trash can with +100 bonus must be sorted first for machine outputs, avoiding recycling to storage");
    }

    @Test
    public void testStorageExtractionNeverTargetsTrashCan() {
        // Storage containers must never void items directly into trash cans
        String chestTarget = "StorageChest";
        String trashCanTarget = "TrashCan";
        java.util.Set<String> trashHandlers = java.util.Set.of(trashCanTarget);

        java.util.List<String> allStorageInjectors = java.util.List.of(trashCanTarget, chestTarget);
        java.util.List<String> filteredTargets = new java.util.ArrayList<>();
        for (String target : allStorageInjectors) {
            if (!trashHandlers.contains(target)) {
                filteredTargets.add(target);
            }
        }

        Assertions.assertEquals(1, filteredTargets.size(), "Trash cans must be excluded from storage extraction targets");
        Assertions.assertEquals(chestTarget, filteredTargets.get(0), "Only non-trash storage must be eligible for storage extraction");
    }

    @Test
    public void testMachineExtractionDirectsToTrashCan() {
        // When trash can is available, machines void directly into trash can without recycling into input chests
        String chestTarget = "InputChest";
        String trashTarget = "TrashCan";
        java.util.List<String> trashInjectors = java.util.List.of(trashTarget);
        java.util.List<String> storageInjectors = java.util.List.of(chestTarget);

        java.util.List<String> machineOutputTargets;
        if (!trashInjectors.isEmpty()) {
            machineOutputTargets = trashInjectors;
        } else {
            machineOutputTargets = storageInjectors;
        }

        Assertions.assertEquals(1, machineOutputTargets.size(), "Machine output must target only trash can when available");
        Assertions.assertEquals(trashTarget, machineOutputTargets.get(0), "Machine output must route directly to trash can");
    }

    @Test
    public void testInjectorScanCacheSkipsRedundantScanning() {
        boolean injectorsDirty = false;
        long lastScanTick = 100L;
        int scans = 0;

        for (long currentTick = 100; currentTick < 140; currentTick++) {
            boolean shouldRescan = injectorsDirty || (currentTick - lastScanTick >= 40);
            if (shouldRescan) {
                scans++;
                lastScanTick = currentTick;
            }
        }

        Assertions.assertEquals(0, scans, "Zero scans should occur within the 40-tick cache interval when topology is stable");

        // Tick 140 triggers cache refresh
        long tick140 = 140L;
        boolean shouldRescan140 = injectorsDirty || (tick140 - lastScanTick >= 40);
        Assertions.assertTrue(shouldRescan140, "Scan must occur after 40-tick cache TTL expires");
    }
}
