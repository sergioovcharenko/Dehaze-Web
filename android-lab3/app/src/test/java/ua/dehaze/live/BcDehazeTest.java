package ua.dehaze.live;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public class BcDehazeTest {
    @Test public void boundaryUsesBothRadianceBounds() {
        assertEquals(100.0/170.0,BcDehaze.boundary(100,200),1e-8);
        assertEquals(.55,BcDehaze.boundary(255,200),1e-8);
        assertTrue(Double.isFinite(BcDehaze.boundary(30,30)));
    }
    @Test public void fftImpulseAndInverseHaveCorrectScale() {
        double[] r={1,0,0,0,0,0,0,0},i=new double[8];
        BcDehaze.fft2(r,i,4,2,false);
        for(int n=0;n<8;n++){assertEquals(1,r[n],1e-9);assertEquals(0,i[n],1e-9);}
        BcDehaze.fft2(r,i,4,2,true);
        assertEquals(1,r[0],1e-9);
        for(int n=1;n<8;n++)assertEquals(0,r[n],1e-9);
    }
    @Test public void regularizationPreservesAConstantTransmission() {
        double[] t=new double[32];Arrays.fill(t,.6);
        int[] pixels=new int[32];Arrays.fill(pixels,0xffaaccee);
        double[] out=BcDehaze.regularize(pixels,t,8,4);
        for(double v:out)assertEquals(.6,v,1e-6);
    }
    @Test public void blackWhiteAndSmallImagesRemainFiniteAndOpaque() {
        for(int color:new int[]{0xff000000,0xffffffff,0xff506070}){
            int[] p=new int[15];Arrays.fill(p,color);
            BcDehaze.Result r=BcDehaze.process(p,5,3,32);
            assertEquals(15,r.argb.length);
            for(int v:r.argb)assertEquals(255,v>>>24);
            if(color==0xff000000||color==0xffffffff)assertArrayEquals(p,r.argb);
        }
    }
    @Test public void rejectsInvalidBufferBeforeAllocating() {
        try{BcDehaze.process(new int[2],5,4,64);fail("invalid buffer accepted");}
        catch(IllegalArgumentException expected){}
    }
    @Test public void nonUniformHazeActuallyChangesAndDoesNotMutateSource() {
        int[] p=new int[32*16];
        for(int y=0;y<16;y++)for(int x=0;x<32;x++){
            int c=110+x*3;p[y*32+x]=0xff000000|(c<<16)|((c+5)<<8)|(c+9);
        }
        int[] original=p.clone();BcDehaze.Result r=BcDehaze.process(p,32,16,32);
        assertArrayEquals(original,p);assertFalse(Arrays.equals(p,r.argb));
        assertEquals(6,r.iterations);assertTrue(r.totalMs>=0);
    }
}
