package ua.dehaze.live;
import org.junit.Test;
import static org.junit.Assert.*;
public class AutoQualityTest {
    private int[] stripes(int low,int high){int[] p=new int[64*32];for(int y=0;y<32;y++)for(int x=0;x<64;x++){int c=(x/8)%2==0?low:high;p[y*64+x]=0xff000000|c<<16|c<<8|c;}return p;}
    @Test public void identityHasNoQualityGain(){int[] p=stripes(130,160);assertEquals(0,AutoQuality.score(p,p,64,32),1e-9);}
    @Test public void moderateContrastWinsAgainstHazySource(){int[] source=stripes(140,160);assertTrue(AutoQuality.score(source,stripes(120,180),64,32)>3);}
    @Test public void clippedAndBlackResultsAreRejected(){int[] source=stripes(140,160);assertEquals(Double.NEGATIVE_INFINITY,AutoQuality.score(source,stripes(0,255),64,32),0);assertEquals(Double.NEGATIVE_INFINITY,AutoQuality.score(source,stripes(0,0),64,32),0);}
    @Test public void AddedNoiseIsNotMistakenForRecoveredDetail(){int[] source=stripes(140,140),noisy=source.clone();for(int i=0;i<noisy.length;i++){int c=((i+i/64)&1)==0?115:165;noisy[i]=0xff000000|c<<16|c<<8|c;}assertTrue(AutoQuality.score(source,noisy,64,32)<0);}
    @Test public void invalidGeometryFailsBeforeReadingPixels(){try{AutoQuality.score(new int[2],new int[2],2,2);fail();}catch(IllegalArgumentException expected){}}
}
