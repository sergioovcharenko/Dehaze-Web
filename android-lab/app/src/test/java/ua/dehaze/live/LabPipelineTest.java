package ua.dehaze.live;

import org.junit.Test;
import static org.junit.Assert.*;

/** Offline deterministic CPU tests; physical camera/GLES still require device validation. */
public class LabPipelineTest {
    private byte[] fogFrame(int shift){
        byte[] b=new byte[VideoDehazeProcessor.N*4];
        for(int y=0;y<VideoDehazeProcessor.H;y++)
            for(int x=0;x<VideoDehazeProcessor.W;x++){
                int k=(y*VideoDehazeProcessor.W+x)*4;
                int value=(x/13+y/9)%2==0?67:147;
                if(y>72)value=184;
                value=Math.min(245,Math.max(0,Math.round(value*.44f+206*.56f)+((x*7+y*11+shift*5)%23-11)));
                b[k]=(byte)value;b[k+1]=(byte)Math.min(255,value+5);
                b[k+2]=(byte)Math.min(255,value+8);b[k+3]=(byte)255;
            }
        return b;
    }
    private static VideoDehazeProcessor.Result call(byte[] frame,VideoDehazeProcessor.Result p,int mask){
        VideoDehazeProcessor.Result result=VideoDehazeProcessor.process(frame,p,mask);
        assertEquals(VideoDehazeProcessor.N*4,result.map.length);
        assertEquals(VideoDehazeProcessor.LUT_H*VideoDehazeProcessor.LUT_W*4,result.lut.length);
        assertTrue(Float.isFinite(result.ar)&&Float.isFinite(result.ag)&&Float.isFinite(result.ab));
        return result;
    }
    @Test public void multiScaleDcpCreatesVariableTransmission(){
        VideoDehazeProcessor.Result r=call(fogFrame(0),null,LabRuntime.ALL);
        int min=255,max=0;
        for(int i=0;i<r.map.length;i+=4){
            int transmission=r.map[i]&255;
            min=Math.min(min,transmission);max=Math.max(max,transmission);
            assertTrue(transmission>=25&&transmission<=255);
        }
        assertTrue("DCP map must not be flat",max-min>3);
    }
    @Test public void dcpOffDisablesTransmission(){
        int flags=LabRuntime.ALL&~(1<<(LabRuntime.DCP-2));
        VideoDehazeProcessor.Result r=call(fogFrame(0),null,flags);
        for(int i=0;i<r.map.length;i+=4)assertEquals(255,r.map[i]&255);
    }
    @Test public void guidedSwitchChangesMap(){
        byte[] frame=fogFrame(0);
        VideoDehazeProcessor.Result guided=call(frame,null,LabRuntime.ALL);
        VideoDehazeProcessor.Result raw=call(frame,null,
            LabRuntime.ALL&~(1<<(LabRuntime.GUIDED-2)));
        long diff=0;
        for(int i=0;i<guided.map.length;i+=8)
            diff+=Math.abs((guided.map[i]&255)-(raw.map[i]&255));
        assertTrue("Guided must alter raw transmission",diff>20);
    }
    @Test public void claheOffProducesExactIdentityLut(){
        VideoDehazeProcessor.Result r=call(fogFrame(0),null,
            LabRuntime.ALL&~(1<<(LabRuntime.CLAHE-2)));
        for(int tile=0;tile<VideoDehazeProcessor.LUT_H;tile++)
            for(int x=0;x<256;x+=17){
                int k=(tile*256+x)*4;
                assertEquals(x,r.lut[k]&255);
                assertEquals(255,r.lut[k+3]&255);
            }
    }
    @Test public void temporalSwitchActuallySmooths(){
        byte[] a=fogFrame(0),b=fogFrame(1);
        VideoDehazeProcessor.Result first=call(a,null,LabRuntime.ALL);
        VideoDehazeProcessor.Result temporal=call(b,first,LabRuntime.ALL);
        VideoDehazeProcessor.Result raw=call(b,null,
            LabRuntime.ALL&~(1<<(LabRuntime.TEMPORAL-2)));
        long difference=0;
        for(int i=0;i<temporal.map.length;i+=8)
            difference+=Math.abs((temporal.map[i]&255)-(raw.map[i]&255));
        assertTrue("Neighboring video frames must be temporally blended",difference>0);
    }
}
