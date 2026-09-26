package ua.dehaze.live;
import org.junit.Test;
import static org.junit.Assert.*;
public class ExpandedAutoPolicyTest {
    @Test public void fifthCandidateCanWin(){AutoPolicy p=new AutoPolicy(5);assertEquals(4,p.choose(new double[]{3,4,5,6,12},new long[]{5,5,5,5,5},false,0));}
    @Test public void failedAiStillAllowsCapAndFast(){AutoPolicy p=new AutoPolicy(5);assertEquals(3,p.choose(new double[]{2,3,100,12,7},new long[]{5,5,-1,5,5},true,0));assertFalse(p.allowed(2,1));assertTrue(p.allowed(4,1));}
    @Test public void dimensionMismatchIsRejected(){AutoPolicy p=new AutoPolicy(5);try{p.choose(new double[3],new long[3],false,0);fail();}catch(IllegalArgumentException expected){}}
}
