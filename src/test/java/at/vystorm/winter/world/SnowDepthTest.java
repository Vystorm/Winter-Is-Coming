package at.vystorm.winter.world;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SnowDepthTest {
    @Test void distributesDepthAcrossFullLowerBlocksAndOnePartialTop() {
        assertEquals(8, SnowDepth.atHeight(12, 0));
        assertEquals(4, SnowDepth.atHeight(12, 1));
        assertEquals(0, SnowDepth.atHeight(12, 2));
        for (int total=0;total<=64;total++) {
            int sum=0;boolean partial=false;
            for(int y=0;y<8;y++) {
                int count=SnowDepth.atHeight(total,y);
                assertTrue(count>=0&&count<=8);
                if(partial)assertEquals(0,count,"no snow above a partial supporting layer");
                if(count<8)partial=true;
                sum+=count;
            }
            assertEquals(total,sum);
        }
    }
    @Test void seasonalDepthIsBoundedAndMonotoneAndThawsCompletely() {
        for(int x=-20;x<=20;x++)for(int z=-20;z<=20;z++) {
            assertEquals(0,SnowDepth.target(12,0,x,z));
            int previous=0;
            for(int step=0;step<=100;step++) {
                int target=SnowDepth.target(12,step/100.0,x,z);
                assertTrue(target>=previous&&target<=12);
                previous=target;
            }
            assertTrue(previous>8,"default peak spans more than one block");
        }
    }
    @Test void coherentDriftsAreBoundedSmoothAndSeasonallyMonotone() {
        java.util.Set<Integer> depths=new java.util.HashSet<>();
        for(int x=-50;x<=50;x++)for(int z=-50;z<=50;z++) {
            int peak=SnowDepth.target(12,1,x,z,0.45,1);depths.add(peak);
            assertTrue(peak>=6 && peak<=12);
            assertTrue(Math.abs(peak-SnowDepth.target(12,1,x+1,z,0.45,1))<=2);
            assertEquals(12,SnowDepth.target(12,1,x,z,0,1));
            assertEquals(0,SnowDepth.target(12,0,x,z,0.45,1));
            int previous=0;
            for(int t=0;t<=10;t++) {
                int current=SnowDepth.target(12,t/10.0,x,z,0.45,1);
                assertTrue(current>=previous);previous=current;
            }
            assertTrue(SnowDepth.target(12,1,x,z,0.45,0.2)<peak);
        }
        assertTrue(depths.size()>=4,"drifts must visibly vary in depth");
    }
    @Test void rejectsOffsetsOutsideTheBoundedOwnedStack() {
        assertEquals(0,SnowDepth.atHeight(64,-1));
        assertEquals(0,SnowDepth.atHeight(64,8));
        assertEquals(8,SnowDepth.atHeight(64,7));
    }

    @org.junit.jupiter.api.Test void rampsClimbTowardsAStep() {
        org.junit.jupiter.api.Assertions.assertEquals(14, SnowDepth.withRamp(8, 1, 1));
        org.junit.jupiter.api.Assertions.assertEquals(12, SnowDepth.withRamp(8, 2, 1));
        org.junit.jupiter.api.Assertions.assertEquals(10, SnowDepth.withRamp(8, 3, 1));
        org.junit.jupiter.api.Assertions.assertEquals(2, SnowDepth.withRamp(2, 0, 1));
        org.junit.jupiter.api.Assertions.assertEquals(0, SnowDepth.withRamp(0, 1, 1));
        org.junit.jupiter.api.Assertions.assertEquals(7, SnowDepth.withRamp(4, 1, 0.5));
        org.junit.jupiter.api.Assertions.assertEquals(4, SnowDepth.withRamp(2, 1, 1), "1.24.1: a ramp is never deeper than its base");
    }
}
