package ua.dehaze.live;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class ExpandedEngineTest {
    private Bitmap source(){Bitmap b=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888);for(int y=0;y<32;y++)for(int x=0;x<64;x++){int c=x<32?130:185;b.setPixel(x,y,0xff000000|c<<16|c<<8|c);}return b;}
    @Test public void capAndFastProcessAndZeroStrengthIsExact() throws Exception {
        Bitmap input=source();try(Lab3Processor p=new Lab3Processor(InstrumentationRegistry.getInstrumentation().getTargetContext())){
            for(int k=3;k<=4;k++){assertFalse(input.sameAs(p.process(input,k,.7f,true,63).result));assertTrue(input.sameAs(p.process(input,k,0,true,63).result));}
        }
    }
    @Test public void autoAttemptsAllFiveAndKeepsNewModesAfterFailure(){
        int[] calls=new int[5];Bitmap input=source();AutoProcessing.Batch b=AutoProcessing.run(input,.7f,true,63,new boolean[]{true,true,true,true,true},-2,(s,k,a,p,f)->{calls[k]++;if(k==2)throw new IllegalStateException("model missing");return new Lab3Processor.Pair(s,s,"identity",1,k);});
        assertArrayEquals(new int[]{1,1,1,1,1},calls);assertNotNull(b.pairs[3]);assertNotNull(b.pairs[4]);assertEquals(-1,b.elapsed[2]);assertFalse(input.isRecycled());
    }
    @Test public void disabledLibraryDoesNoWorkAndNeverRecyclesCallerFrame(){
        Bitmap input=source();try(AntiFogEngine e=new AntiFogEngine((s,k,a,p,f)->{throw new AssertionError("disabled processor called");})){
            AntiFogEngine.Result r=e.process(input,.7f,false);assertSame(input,r.image);assertEquals(-1,r.algorithm);assertTrue(e.isCurrent(r));r.close();assertFalse(input.isRecycled());
        }
    }
    @Test public void offDuringInferenceInvalidatesResultAndKeepsInput() throws Exception {
        Bitmap input=source();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
        try(AntiFogEngine e=new AntiFogEngine((s,k,a,p,f)->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("timeout");return new Lab3Processor.Pair(s,s,"identity",1,k);})){
            e.setEnabled(true);Future<AntiFogEngine.Result> future=worker.submit(()->e.process(input,.7f,true));assertTrue(entered.await(5,TimeUnit.SECONDS));e.setEnabled(false);release.countDown();
            AntiFogEngine.Result r=future.get(20,TimeUnit.SECONDS);assertFalse(e.isCurrent(r));assertSame(input,r.image);r.close();assertFalse(input.isRecycled());
        }finally{release.countDown();worker.shutdownNow();}
    }
    @Test public void libraryRunsRealOfflineCandidates(){
        Bitmap input=source();try(AntiFogEngine e=new AntiFogEngine(InstrumentationRegistry.getInstrumentation().getTargetContext())){
            e.setEnabled(true);try(AntiFogEngine.Result r=e.process(input,.7f,true)){assertTrue(e.isCurrent(r));assertTrue(r.report.contains("CAP"));assertTrue(r.report.contains("FAST"));assertTrue(r.report.contains("DehazeFormer"));assertTrue(r.strength<=.7f);assertFalse(input.isRecycled());}
        }
    }
}
