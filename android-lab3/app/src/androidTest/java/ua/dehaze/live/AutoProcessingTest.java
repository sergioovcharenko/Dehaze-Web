package ua.dehaze.live;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class AutoProcessingTest {
    @Test public void oneBrokenAlgorithmKeepsOtherResultsAndExactOriginal() throws Exception {
        Bitmap source=Bitmap.createBitmap(32,16,Bitmap.Config.ARGB_8888);source.eraseColor(0xff8899aa);
        AutoProcessing.Batch b=AutoProcessing.run(source,.7f,true,63,new boolean[]{true,true,true},-2,(image,algorithm,strength,photo,flags)->{
            if(algorithm==1)throw new IllegalStateException("candidate failed");
            return new Lab3Processor.Pair(image,image,"identity",5,algorithm);
        });
        assertNotNull(b.pairs[0]);assertNull(b.pairs[1]);assertNotNull(b.pairs[2]);assertEquals(-1,b.elapsed[1]);assertEquals(0,b.quality[0],1e-9);assertTrue(source.sameAs(b.original.result));assertTrue(b.report().contains("candidate failed"));
    }
    @Test public void normalAutoFrameRunsOnlyChosenAlgorithm() throws Exception {
        Bitmap source=Bitmap.createBitmap(32,16,Bitmap.Config.ARGB_8888);int[] calls=new int[3];
        AutoProcessing.Batch b=AutoProcessing.run(source,.7f,false,63,new boolean[]{true,true,true},1,(image,algorithm,strength,photo,flags)->{calls[algorithm]++;return new Lab3Processor.Pair(image,image,"identity",5,algorithm);});
        assertArrayEquals(new int[]{0,1,0},calls);assertEquals(-2,b.elapsed[0]);assertNotNull(b.pairs[1]);
    }
    @Test public void cooldownCandidateIsNotInvoked(){
        Bitmap source=Bitmap.createBitmap(32,16,Bitmap.Config.ARGB_8888);int[] calls=new int[3];
        AutoProcessing.run(source,.7f,false,63,new boolean[]{true,false,true},-2,(image,algorithm,strength,photo,flags)->{calls[algorithm]++;return new Lab3Processor.Pair(image,image,"identity",5,algorithm);});
        assertArrayEquals(new int[]{1,0,1},calls);
    }
}
