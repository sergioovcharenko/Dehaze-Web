package ua.dehaze.live;

import org.junit.Test;
import static org.junit.Assert.*;

public class LiveFrameStatsTest {
    @Test public void redrawsNeverIncreaseVideoFps(){
        LiveFrameStats stats=new LiveFrameStats();
        stats.frame(1000,true);stats.frame(1100,true);
        for(int i=0;i<30;i++)stats.frame(1200+i*10,false);
        assertEquals(2,stats.totalFrames());
        assertEquals(2.0,stats.fps(2000),.001);
    }
    @Test public void staleSourceShowsNoFakeCurrentFrames(){
        LiveFrameStats stats=new LiveFrameStats();stats.frame(1000,true);
        assertEquals(750,stats.frameAge(1750));
        stats.reset();assertEquals(-1,stats.frameAge(2000));
        assertEquals(0,stats.totalFrames());
    }
    @Test public void intervalResetPreservesAgeAndTotal(){
        LiveFrameStats stats=new LiveFrameStats();stats.frame(1000,true);
        stats.interval(2000);stats.frame(2250,true);
        assertEquals(1.0,stats.fps(3000),.001);
        assertEquals(750,stats.frameAge(3000));assertEquals(2,stats.totalFrames());
    }
}
