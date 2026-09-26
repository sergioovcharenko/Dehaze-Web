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
    private static Object field(Object a,String name){try{Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(a);}catch(Exception e){throw new AssertionError(e);}}
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
    @Test public void offDuringQueuedAutoWorkDiscardsItsResult() throws Exception {
        File file=photo();CountDownLatch release=new CountDownLatch(1),done=new CountDownLatch(1);
        try(ActivityScenario<Lab3Activity> s=ActivityScenario.launch(Lab3Activity.class)){
            s.onActivity(a->{((CheckBox)field(a,"enabled")).setChecked(false);a.onActivityResult(10,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(file)));});
            await(s,a->field(a,"photo")!=null);
            s.onActivity(a->{((ExecutorService)field(a,"worker")).execute(()->{try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});((CheckBox)field(a,"enabled")).setChecked(true);});
            await(s,a->((Long)field(field(a,"gate"),"active"))>=0);
            s.onActivity(a->{((CheckBox)field(a,"enabled")).setChecked(false);((ExecutorService)field(a,"worker")).execute(done::countDown);});
            release.countDown();assertTrue(done.await(30,TimeUnit.SECONDS));InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            s.onActivity(a->{assertNull(((ImageView)field(a,"after")).getDrawable());assertFalse(((CheckBox)field(a,"enabled")).isChecked());});
        }finally{release.countDown();}
    }

    @Test public void photoSelectedDuringVideoTestStartsAuto() throws Exception {
        File file=photo(),video=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"auto-switch.mp4");
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("test-video.mp4");OutputStream out=new FileOutputStream(video)){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
        try(ActivityScenario<Lab3Activity> s=ActivityScenario.launch(Lab3Activity.class)){
            s.onActivity(a->a.onActivityResult(11,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(video))));
            await(s,a->Boolean.TRUE.equals(field(a,"ready")));
            s.onActivity(a->{try{java.lang.reflect.Method m=Lab3Activity.class.getDeclaredMethod("startTest");m.setAccessible(true);m.invoke(a);}catch(Exception e){throw new AssertionError(e);}assertTrue((Boolean)field(a,"testing"));a.onActivityResult(10,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(file)));});
            await(s,a->field(a,"photo")!=null&&((ImageView)field(a,"after")).getDrawable()!=null);
            s.onActivity(a->assertFalse((Boolean)field(a,"holdComparison")));
        }
    }

    @Test public void manualBrowsingKeepsAllThreeComparedVideoResults() throws Exception {
        File video=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"auto-compare.mp4");
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("test-video.mp4");OutputStream out=new FileOutputStream(video)){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
        try(ActivityScenario<Lab3Activity> s=ActivityScenario.launch(Lab3Activity.class)){
            s.onActivity(a->a.onActivityResult(11,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(video))));
            await(s,a->{if(!Boolean.TRUE.equals(field(a,"ready"))||((Long)field(field(a,"gate"),"active"))>=0)return false;try{java.lang.reflect.Method m=Lab3Activity.class.getDeclaredMethod("capture",boolean.class);m.setAccessible(true);m.invoke(a,true);}catch(Exception e){throw new AssertionError(e);}return true;});
            await(s,a->{Lab3Processor.Pair[] p=(Lab3Processor.Pair[])field(a,"cached");return p[0]!=null&&p[1]!=null&&p[2]!=null&&((Long)field(field(a,"gate"),"active"))<0;});
            java.util.concurrent.atomic.AtomicReference<Object> saved=new java.util.concurrent.atomic.AtomicReference<>();long[] captured=new long[1];
            s.onActivity(a->{saved.set(field(a,"cached"));captured[0]=(Long)field(a,"pairCaptured");((Spinner)field(a,"algorithms")).setSelection(1);});
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(1200);
            s.onActivity(a->{assertTrue("Manual browsing released comparison",(Boolean)field(a,"holdComparison"));assertSame(saved.get(),field(a,"cached"));assertEquals(captured[0],((Long)field(a,"pairCaptured")).longValue());});
        }
    }

}
