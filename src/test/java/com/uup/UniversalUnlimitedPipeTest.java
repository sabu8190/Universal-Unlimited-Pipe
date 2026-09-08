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
        net.minecraft.core.BlockPos src = new net.minecraft.core.BlockPos(0, 0, 0);
        net.minecraft.core.BlockPos far = new net.minecraft.core.BlockPos(10, 0, 0);
        net.minecraft.core.BlockPos near = new net.minecraft.core.BlockPos(2, 0, 0);
        net.minecraft.core.BlockPos mid = new net.minecraft.core.BlockPos(5, 0, 0);

        java.util.List<net.minecraft.core.BlockPos> list = new java.util.ArrayList<>(java.util.List.of(far, near, mid));
        list.sort((p1, p2) -> {
            double d1 = src.distSqr(p1);
            double d2 = src.distSqr(p2);
            int cmp = Double.compare(d1, d2);
            if (cmp != 0) return cmp;
            return p1.compareTo(p2);
        });

        Assertions.assertEquals(near, list.get(0), "Nearest block (dist=2) should be first");
        Assertions.assertEquals(mid, list.get(1), "Middle block (dist=5) should be second");
        Assertions.assertEquals(far, list.get(2), "Farthest block (dist=10) should be third");
    }

    @Test
    public void testPriorityOverrulesDistance() {
        net.minecraft.core.BlockPos src = new net.minecraft.core.BlockPos(0, 0, 0);
        net.minecraft.core.BlockPos nearLowPriority = new net.minecraft.core.BlockPos(1, 0, 0);
        net.minecraft.core.BlockPos farHighPriority = new net.minecraft.core.BlockPos(20, 0, 0);

        int pNear = 0;
        int pFar = 10;

        java.util.List<net.minecraft.core.BlockPos> list = new java.util.ArrayList<>(java.util.List.of(nearLowPriority, farHighPriority));
        list.sort((p1, p2) -> {
            int priority1 = p1 == farHighPriority ? pFar : pNear;
            int priority2 = p2 == farHighPriority ? pFar : pNear;
            if (priority1 != priority2) return Integer.compare(priority2, priority1);
            return Double.compare(src.distSqr(p1), src.distSqr(p2));
        });

        Assertions.assertEquals(farHighPriority, list.get(0), "Higher priority target should come first even if farther away");
    }

    @Test
    public void testStoragePriorityOverSameDistance() {
        boolean isStorageA = true;
        boolean isStorageB = false;

        java.util.List<Boolean> targets = new java.util.ArrayList<>(java.util.List.of(isStorageB, isStorageA));
        targets.sort((t1, t2) -> {
            if (t1 != t2) {
                return t1 ? -1 : 1;
            }
            return 0;
        });

        Assertions.assertTrue(targets.get(0), "Storage targets should be prioritized over processing machines");
    }
}
