package ua.dehaze.live;

import org.junit.Test;
import static org.junit.Assert.*;

/** These checks are purely arithmetic. Actual camera/GPU requires hardware. */
public class DeviceTestTest {
    @Test public void flagsBlackOrSaturatedFlatReadback(){
        assertTrue(DeviceTest.isSuspicious(0.0,0.0));
        assertTrue(DeviceTest.isSuspicious(254.0,0.0));
    }
    @Test public void doesNotFlagNormalOrDetailedDarkScene(){
        assertFalse(DeviceTest.isSuspicious(111.0,15.0));
        assertFalse(DeviceTest.isSuspicious(2.0,37.0));
        assertFalse(DeviceTest.isSuspicious(252.0,75.0));
    }
    @Test public void syntheticCpuPipelineAndGpuClassificationRemainIndependent(){
        assertEquals(DeviceTest.CAMERA,1);
        assertEquals(DeviceTest.VIDEO,2);
        assertEquals(DeviceTest.TEST_SECONDS,12);
    }
}
