package ua.dehaze.live;

import org.junit.Test;
import static org.junit.Assert.*;

public class DigitalDehazePolicyTest {
    @Test public void clearSceneFallsToZero(){
        assertEquals(0f,DigitalDehazePolicy.decide(.10f,120f,0f,20).strength,.0001f);
    }
    @Test public void nightIsGentlerThanDay(){
        float day=DigitalDehazePolicy.decide(.60f,150f,0f,20).strength;
        float night=DigitalDehazePolicy.decide(.60f,45f,0f,20).strength;
        assertTrue(night<day);
    }
    @Test public void brightMonochromeSceneCanStillBeNight(){
        DigitalDehazePolicy.Decision d=
            DigitalDehazePolicy.decide(.45f,105f,4f,.20f,0f,20);
        assertTrue(d.night);
    }

    @Test public void brightColorSceneRemainsDay(){
        DigitalDehazePolicy.Decision d=
            DigitalDehazePolicy.decide(.45f,105f,32f,.05f,0f,20);
        assertFalse(d.night);
    }

    @Test public void sceneChangeUsesFastCadence(){
        assertEquals(180_000_000L,DigitalDehazePolicy.decide(.4f,120f,.2f,20).intervalNs);
    }
    @Test public void manualLevelsAreStable(){
        assertEquals(.35f,DigitalDehazePolicy.manualStrength(DigitalDehazePolicy.LOW),.0001f);
        assertEquals(.60f,DigitalDehazePolicy.manualStrength(DigitalDehazePolicy.MEDIUM),.0001f);
        assertEquals(.85f,DigitalDehazePolicy.manualStrength(DigitalDehazePolicy.HIGH),.0001f);
    }
}
