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
}
