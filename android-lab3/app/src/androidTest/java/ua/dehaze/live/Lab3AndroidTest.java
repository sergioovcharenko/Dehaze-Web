package ua.dehaze.live;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class Lab3AndroidTest {
    @Test(timeout=120000) public void everyAlgorithmProcessesRealPixelsAndPreservesSource() throws Exception {
        int w=33,h=19;int[] pixels=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=90+x*4;pixels[y*w+x]=0xff000000|(c<<16)|((c+4)<<8)|(c+8);}
        Bitmap original=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
        try(Lab3Processor processor=new Lab3Processor(InstrumentationRegistry.getInstrumentation().getTargetContext())){
            for(int algorithm=0;algorithm<Lab3Processor.NAMES.length;algorithm++){
                Lab3Processor.Pair pair=processor.process(original,algorithm,1,false,63);
                assertEquals(w,pair.result.getWidth());assertEquals(h,pair.result.getHeight());
                int[] actual=new int[w*h];pair.result.getPixels(actual,0,w,0,0,w,h);
                assertFalse("Algorithm did nothing: "+algorithm,java.util.Arrays.equals(pixels,actual));
                int[] inputNow=new int[w*h];original.getPixels(inputNow,0,w,0,0,w,h);assertArrayEquals(pixels,inputNow);
            }
            Lab3Processor.Pair bypass=processor.process(original,2,0,false,63);
            assertTrue(original.sameAs(bypass.result));
            try{processor.process(original,99,1,false,63);fail("unknown algorithm");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void separateLauncherOpensAndSurvivesRecreation(){
        try(ActivityScenario<Lab3Activity> activity=ActivityScenario.launch(Lab3Activity.class)){
            activity.onActivity(a->{assertEquals("ua.meti.tuman.lab4.auto",a.getPackageName());assertNotNull(a.findViewById(android.R.id.content));});
            activity.recreate();activity.onActivity(a->assertFalse(a.isFinishing()));
        }
    }
}
