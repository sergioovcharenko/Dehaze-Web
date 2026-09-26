package ua.dehaze.live;
import org.junit.Test;
import static org.junit.Assert.*;
public class FrameGateTest {
    @Test public void resetInvalidatesOldResultWithoutAllowingBacklog(){
        FrameGate g=new FrameGate();long a=g.begin();assertTrue(a>0);
        assertEquals(-1,g.begin());g.reset();assertEquals(-1,g.begin());
        assertFalse(g.finish(a));long b=g.begin();assertTrue(b>a);
        assertTrue(g.finish(b));assertFalse(g.finish(a));
    }
    @Test public void finishingWrongTicketDoesNotReleaseCurrentWork(){
        FrameGate g=new FrameGate();long t=g.begin();assertFalse(g.finish(t+1));
        assertEquals(-1,g.begin());assertTrue(g.finish(t));assertTrue(g.begin()>t);
    }
}
