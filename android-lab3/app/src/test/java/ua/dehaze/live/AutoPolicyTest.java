package ua.dehaze.live;
import org.junit.Test;
import static org.junit.Assert.*;
public class AutoPolicyTest {
    @Test public void photoSelectsHighestScoreWithoutSpeedPenalty(){AutoPolicy p=new AutoPolicy();assertEquals(2,p.choose(new double[]{5,8,12},new long[]{10,20,5000},false,0));}
    @Test public void originalWinsWhenAllAlgorithmsDamageImage(){assertEquals(-1,new AutoPolicy().choose(new double[]{-3,Double.NaN,Double.NEGATIVE_INFINITY},new long[]{10,20,30},true,0));}
    @Test public void videoNeedsRepeatedMeaningfulGainAndDwell(){AutoPolicy p=new AutoPolicy();long[] t={0,0,0};assertEquals(0,p.choose(new double[]{8,4,3},t,true,0));assertEquals(0,p.choose(new double[]{8,12,3},t,true,6000));assertEquals(1,p.choose(new double[]{8,12,3},t,true,12000));assertEquals(1,p.choose(new double[]{13,12,3},t,true,18000));}
    @Test public void alternatingWinnersDoNotSwitch(){AutoPolicy p=new AutoPolicy();long[] t={0,0,0};p.choose(new double[]{8,4,3},t,true,0);p.choose(new double[]{8,12,3},t,true,6000);p.choose(new double[]{8,4,13},t,true,12000);assertEquals(0,p.choose(new double[]{8,12,3},t,true,18000));}
    @Test public void errorsAndSlowVideoCandidatesHaveCooldown(){AutoPolicy p=new AutoPolicy();assertEquals(0,p.choose(new double[]{6,20,30},new long[]{30,-1,2100},true,0));assertFalse(p.allowed(1,29999));assertFalse(p.allowed(2,29999));assertTrue(p.allowed(1,30000));assertTrue(p.allowed(2,30000));}
    @Test public void unavailableIncumbentReleasesImmediately(){AutoPolicy p=new AutoPolicy();p.choose(new double[]{6,10,4},new long[3],true,0);assertEquals(0,p.choose(new double[]{6,Double.NEGATIVE_INFINITY,4},new long[]{30,-1,30},true,1000));}
    @Test public void resetRemovesPriorSceneAndCooldown(){AutoPolicy p=new AutoPolicy();p.choose(new double[]{6,20,30},new long[]{30,-1,2100},true,0);p.reset();assertTrue(p.allowed(1,1));assertEquals(2,p.choose(new double[]{3,5,10},new long[3],true,1));}
    @Test public void feedbackDropsDamagingCurrentFrame(){AutoPolicy p=new AutoPolicy();p.choose(new double[]{6,10,4},new long[3],true,0);assertTrue(p.rejectCurrent(Double.NEGATIVE_INFINITY,20,false,100));assertEquals(-1,p.current());assertTrue(p.allowed(1,100));}
    @Test public void failedCurrentIsNotRetriedNextFrame(){AutoPolicy p=new AutoPolicy();p.choose(new double[]{6,10,4},new long[3],true,0);assertTrue(p.rejectCurrent(0,20,true,100));assertFalse(p.allowed(1,101));assertEquals(0,p.choose(new double[]{6,Double.NEGATIVE_INFINITY,4},new long[]{10,-2,10},true,1000));}
}
