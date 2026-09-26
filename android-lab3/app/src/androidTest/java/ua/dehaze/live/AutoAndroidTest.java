package ua.dehaze.live;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class AutoAndroidTest {
    private static Object field(Lab3Activity a,String name){try{Field f=Lab3Activity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}catch(Exception e){throw new AssertionError(e);}}
    private static void await(ActivityScenario<Lab3Activity> s,java.util.function.Predicate<Lab3Activity> p){long end=SystemClock.elapsedRealtime()+30000;AtomicBoolean ready=new AtomicBoolean();do{s.onActivity(a->ready.set(p.test(a)));if(ready.get())return;SystemClock.sleep(50);}while(SystemClock.elapsedRealtime()<end);fail("AUTO did not publish a result");}
    private static File photo() throws Exception {File f=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"auto-photo.png");Bitmap b=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888);for(int y=0;y<32;y++)for(int x=0;x<64;x++){int c=x%16<8?130:175;b.setPixel(x,y,0xff000000|c<<16|c<<8|c);}try(OutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.PNG,100,out);}return f;}
    @Test public void defaultAutoProcessesPhotoWithoutPressingProcess() throws Exception {
        File file=photo();try(ActivityScenario<Lab3Activity> s=ActivityScenario.launch(Lab3Activity.class)){
            s.onActivity(a->{assertTrue(((Spinner)field(a,"algorithms")).getSelectedItem().toString().contains("AUTO"));a.onActivityResult(10,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(file)));});
            await(s,a->((ImageView)field(a,"after")).getDrawable()!=null);
            s.onActivity(a->assertTrue(((TextView)field(a,"info")).getText().toString().contains("AUTO")));
        }
    }
    @Test public void disabledAutoDoesNotProcessNewPhoto() throws Exception {
        File file=photo();try(ActivityScenario<Lab3Activity> s=ActivityScenario.launch(Lab3Activity.class)){
            s.onActivity(a->{((CheckBox)field(a,"enabled")).setChecked(false);a.onActivityResult(10,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(file)));});
            await(s,a->field(a,"photo")!=null);SystemClock.sleep(1000);
            s.onActivity(a->assertNull(((ImageView)field(a,"after")).getDrawable()));
            s.onActivity(a->((CheckBox)field(a,"enabled")).setChecked(true));
            await(s,a->((ImageView)field(a,"after")).getDrawable()!=null);
        }
    }
    @Test public void manualModeIgnoresQueuedAutoDecision() throws Exception {
        File file=photo();CountDownLatch blocker=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ActivityScenario<Lab3Activity> s=ActivityScenario.launch(Lab3Activity.class)){
            s.onActivity(a->{((ExecutorService)field(a,"worker")).execute(()->{blocker.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});a.onActivityResult(10,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(file)));});
            assertTrue(blocker.await(5,TimeUnit.SECONDS));s.onActivity(a->((Spinner)field(a,"algorithms")).setSelection(1));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();release.countDown();await(s,a->field(a,"photo")!=null);SystemClock.sleep(1000);
            s.onActivity(a->{assertEquals(1,((Spinner)field(a,"algorithms")).getSelectedItemPosition());assertNull(((ImageView)field(a,"after")).getDrawable());});
        }finally{release.countDown();}
    }
}
