package ua.dehaze.live;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
public class LightDehazeTest {
    @Test public void publishedCapDepthCoefficientsAreUsed(){
        assertEquals(.121779+.959710*.8-.780245*.25,LightDehaze.capDepth(.8,.25),1e-9);
        assertEquals(0,LightDehaze.capDepth(.1,1),0);
    }
    @Test public void minimumFilterIncludesClippedEdgeWindows(){
        float[] a={9,8,7,6,5,4};
        assertArrayEquals(new float[]{5,4,4,5,4,4},LightDehaze.minBox(a,3,2,1),0);
    }
    @Test public void guidedFilterPreservesConstantTransmission(){
        float[] g=new float[32],p=new float[32];Arrays.fill(p,.6f);
        for(int i=0;i<g.length;i++)g[i]=i/32f;
        assertArrayEquals(p,LightDehaze.guided(g,p,8,4,2,.001f),1e-5f);
    }
    @Test public void constantImagesRemainConstantWithoutAddedTexture(){
        for(int color:new int[]{0xff000000,0xff888888,0xffffffff}){
            int[] input=new int[63];Arrays.fill(input,color);
            for(boolean cap:new boolean[]{true,false}){
                int[] result=LightDehaze.process(input,9,7,cap);
                for(int value:result)assertEquals(result[0],value);
                assertEquals(0xff000000,result[0]&0xff000000);
            }
        }
    }
    @Test public void bothMethodsIncreaseSyntheticHazyEdgeContrast(){
        int[] input=new int[64*32];
        for(int y=0;y<32;y++)for(int x=0;x<64;x++){int c=x<32?130:185;input[y*64+x]=0xff000000|c<<16|c<<8|c;}
        for(boolean cap:new boolean[]{true,false}){
            int[] result=LightDehaze.process(input,64,32,cap);
            assertTrue((result[48]&255)-(result[16]&255)>55);
            assertArrayEquals(result,LightDehaze.process(input,64,32,cap));
        }
    }
    @Test public void invalidGeometryIsRejected(){
        try{LightDehaze.process(new int[3],2,2,true);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void cancellationIsHonoured(){
        Thread.currentThread().interrupt();
        try{LightDehaze.process(new int[4],2,2,true);fail();}catch(java.util.concurrent.CancellationException expected){}finally{Thread.interrupted();}
    }
}
