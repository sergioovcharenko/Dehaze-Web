package ua.dehaze.live;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Spinner;
import androidx.lifecycle.Lifecycle;
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
public class Lab3LifecycleTest {
    private static Object field(Lab3Activity a,String name){try{Field f=Lab3Activity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}catch(Exception e){throw new AssertionError(e);}}
    private static void await(ActivityScenario<Lab3Activity> scenario,java.util.function.Predicate<Lab3Activity> condition){
        long end=SystemClock.elapsedRealtime()+10000;AtomicBoolean ok=new AtomicBoolean();
        do{scenario.onActivity(a->ok.set(condition.test(a)));if(ok.get())return;SystemClock.sleep(40);}while(SystemClock.elapsedRealtime()<end);
        fail("Timed out waiting for activity state");
    }
    @Test public void comparisonImagesRemainVisibleOnSmallLandscapeScreen(){
        try(ActivityScenario<Lab3Activity> scenario=ActivityScenario.launch(Lab3Activity.class)){
            scenario.onActivity(a->{
                View root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);
                float density=a.getResources().getDisplayMetrics().density;
                int[] heights=new int[2];
                for(int i=0;i<2;i++){
                    ((View)field(a,"preview")).setVisibility(i==0?View.GONE:View.VISIBLE);
                    root.measure(View.MeasureSpec.makeMeasureSpec(Math.round(640*density),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Math.round(360*density),View.MeasureSpec.EXACTLY));
                    root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
                    heights[i]=Math.round(((ImageView)field(a,"after")).getHeight()/density);
                }
                assertTrue("Photo/video image heights: "+heights[0]+"/"+heights[1]+" dp",heights[0]>=64&&heights[1]>=64);
            });
        }
    }
    @Test public void changingAlgorithmDuringDecodeKeepsTheSelectedPhoto() throws Exception { delayedPhoto(false); }
    @Test public void pausingDuringDecodeKeepsTheSelectedPhoto() throws Exception { delayedPhoto(true); }
    private void delayedPhoto(boolean pause) throws Exception {
        File photo=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"decode-test.png");
        Bitmap bitmap=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff91a0af);
        try(OutputStream out=new FileOutputStream(photo)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ActivityScenario<Lab3Activity> scenario=ActivityScenario.launch(Lab3Activity.class)){
            scenario.onActivity(a->{((ExecutorService)field(a,"worker")).execute(()->{blocked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                a.onActivityResult(10,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(photo)));});
            assertTrue(blocked.await(5,TimeUnit.SECONDS));
            await(scenario,a->field(a,"pendingPhoto")==null);
            if(pause)scenario.moveToState(Lifecycle.State.CREATED);
            else scenario.onActivity(a->((Spinner)field(a,"algorithms")).setSelection(2));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            release.countDown();
            if(pause)scenario.moveToState(Lifecycle.State.RESUMED);
            await(scenario,a->field(a,"photo")!=null);
            scenario.onActivity(a->{Bitmap decoded=(Bitmap)field(a,"photo");assertEquals(80,decoded.getWidth());assertEquals(0xff91a0af,decoded.getPixel(20,20));});
        }finally{release.countDown();}
    }
    @Test public void pausingVideoReleasesDecoderAndResumeReopensIt() throws Exception {
        File video=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"lifecycle-test.mp4");
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("test-video.mp4");OutputStream out=new FileOutputStream(video)){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
        try(ActivityScenario<Lab3Activity> scenario=ActivityScenario.launch(Lab3Activity.class)){
            scenario.onActivity(a->a.onActivityResult(11,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(video))));
            await(scenario,a->Boolean.TRUE.equals(field(a,"prepared")));
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(a->{assertNull("Decoder retained while backgrounded",field(a,"player"));assertNull("Surface retained while backgrounded",field(a,"videoSurface"));});
            scenario.moveToState(Lifecycle.State.RESUMED);
            await(scenario,a->Boolean.TRUE.equals(field(a,"prepared")));
        }
    }
}
