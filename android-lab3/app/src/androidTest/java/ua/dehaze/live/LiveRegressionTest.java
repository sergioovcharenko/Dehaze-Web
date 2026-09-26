package ua.dehaze.live;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LiveRegressionTest {
    static Object field(Object o,String name){try{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    static void call(Object o,String name,Class<?>[] types,Object... values){try{java.lang.reflect.Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(o,values);}catch(Exception e){throw new AssertionError(e);}}
    static void await(ActivityScenario<MainActivity> scene,java.util.function.Predicate<MainActivity> condition){
        long end=SystemClock.elapsedRealtime()+15000;AtomicBoolean ok=new AtomicBoolean();
        do{scene.onActivity(a->ok.set(condition.test(a)));if(ok.get())return;SystemClock.sleep(50);}while(SystemClock.elapsedRealtime()<end);
        fail("Live state timed out");
    }
    static SplitRenderer renderer(MainActivity a){return (SplitRenderer)field(a,"renderer");}
    static long frames(MainActivity a){return ((LiveFrameStats)field(renderer(a),"frameStats")).totalFrames();}
    static ActivityScenario<MainActivity> video() throws Exception {
        android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        String pkg=inst.getTargetContext().getPackageName();
        try(android.os.ParcelFileDescriptor descriptor=inst.getUiAutomation().executeShellCommand("pm grant "+pkg+" android.permission.CAMERA")){
            try(InputStream output=new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)){while(output.read()!=-1){}}
        }
        File file=new File(inst.getTargetContext().getCacheDir(),"live-regression.mp4");
        try(InputStream in=inst.getContext().getAssets().open("live-motion.mp4");OutputStream out=new FileOutputStream(file)){byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class);
        await(scene,a->field(a,"cameraTexture")!=null);
        scene.onActivity(a->a.onActivityResult(20,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(file))));
        await(scene,a->frames(a)>=3);return scene;
    }
    static Bitmap capture(ActivityScenario<MainActivity> scene) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Bitmap> result=new AtomicReference<>();
        scene.onActivity(a->{renderer(a).captureNext(b->{result.set(b);done.countDown();});renderer(a).refresh();});
        assertTrue("GPU capture did not complete",done.await(15,TimeUnit.SECONDS));return result.get();
    }
    static void screenshot(String name) throws Exception {
        Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);
        File dir=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),"live-screenshots");assertTrue(dir.isDirectory()||dir.mkdirs());
        try(OutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}
    }
    @Test public void launcherIsFullscreenLiveAndControlsFitSmallAndTabletScreens() throws Exception {
        android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent launch=c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());assertNotNull(launch);
        assertEquals(MainActivity.class.getName(),launch.getComponent().getClassName());
        try(ActivityScenario<MainActivity> scene=video()){
            scene.onActivity(a->{
                View root=(View)field(a,"root"),gl=(View)field(a,"glView"),toggle=(View)field(a,"headerToggle");float d=a.getResources().getDisplayMetrics().density;
                for(int[] size:new int[][]{{640,360},{1024,640}}){
                    root.measure(View.MeasureSpec.makeMeasureSpec(Math.round(size[0]*d),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Math.round(size[1]*d),View.MeasureSpec.EXACTLY));root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
                    assertTrue("Video must occupy >80% of height",gl.getHeight()>root.getHeight()*.80);
                    assertTrue("ANTI-FOG button clipped",toggle.getRight()<root.getWidth());
                    assertTrue("ANTI-FOG button missing",toggle.getWidth()>90*d);
                }
                root.requestLayout();
            });
        }
    }
    @Test public void blockedMapWorkerDoesNotFreezeVideoAndOffShowsIdenticalFrames() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ActivityScenario<MainActivity> scene=video()){
            scene.onActivity(a->((ExecutorService)field(renderer(a),"hybridWorker")).execute(()->{entered.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            AtomicLong start=new AtomicLong();scene.onActivity(a->start.set(frames(a)));await(scene,a->frames(a)>start.get()+8);
            Bitmap first=capture(scene);scene.onActivity(a->start.set(frames(a)));await(scene,a->frames(a)>start.get()+3);Bitmap second=capture(scene);
            assertFalse("Moving source became stale bitmap",first.sameAs(second));first.recycle();second.recycle();
            scene.onActivity(a->((TextView)field(a,"headerToggle")).performClick());
            Bitmap off=capture(scene);int half=off.getWidth()/2;long delta=0;int samples=0;
            for(int y=off.getHeight()/4;y<off.getHeight()*3/4;y+=7)for(int x=half/4;x<half*3/4;x+=7){
                int l=off.getPixel(x,y),r=off.getPixel(x+half,y);for(int shift=0;shift<=16;shift+=8)delta+=Math.abs(((l>>shift)&255)-((r>>shift)&255));samples+=3;
            }
            assertTrue("OFF changed pixels: "+delta/(double)samples,delta/(double)samples<1.5);off.recycle();
            release.countDown();
            await(scene,a->!((AtomicBoolean)field(renderer(a),"hybridBusy")).get());
            scene.onActivity(a->{assertFalse((Boolean)field(renderer(a),"enhanced"));assertNull("Disabled map published",field(renderer(a),"readyHybrid"));});
        }finally{release.countDown();}
    }
    @Test public void videoResumesAfterBackgroundAndFreezeOffReturnsToMovingOriginal() throws Exception {
        try(ActivityScenario<MainActivity> scene=video()){
            scene.moveToState(Lifecycle.State.CREATED);
            scene.onActivity(a->{assertNull(field(a,"mediaPlayer"));assertNull(field(a,"cameraDevice"));});
            scene.moveToState(Lifecycle.State.RESUMED);await(scene,a->frames(a)>=4);
            scene.onActivity(a->call(a,"toggleFreeze",new Class<?>[]{}));
            await(scene,a->((View)field(a,"freezeOverlay")).getVisibility()==View.VISIBLE);
            scene.onActivity(a->{((TextView)field(a,"headerToggle")).performClick();assertEquals(View.GONE,((View)field(a,"freezeOverlay")).getVisibility());assertFalse((Boolean)field(a,"frozen"));});
            AtomicLong start=new AtomicLong();scene.onActivity(a->start.set(frames(a)));await(scene,a->frames(a)>start.get()+4);
            scene.onActivity(a->{assertEquals("ANTI-FOG OFF",((TextView)field(a,"headerToggle")).getText().toString());assertNull(field(a,"cameraDevice"));});
        }
    }
    @Test public void classicChangesGpuPixelsAndMenuLayoutsStayLive() throws Exception {
        try(ActivityScenario<MainActivity> scene=video()){
            scene.onActivity(a->{((android.widget.CheckBox)field(a,"manualCheck")).setChecked(true);((android.widget.SeekBar)field(a,"strengthSeek")).setProgress(80);});
            await(scene,a->field(renderer(a),"currentHybrid")!=null);
            Bitmap on=capture(scene);int half=on.getWidth()/2;long delta=0;
            for(int y=on.getHeight()/4;y<on.getHeight()*3/4;y+=9)for(int x=half/4;x<half*3/4;x+=9){int l=on.getPixel(x,y),r=on.getPixel(x+half,y);for(int shift=0;shift<=16;shift+=8)delta+=Math.abs(((l>>shift)&255)-((r>>shift)&255));}
            assertTrue("Filter did not change rendered pixels",delta>100);on.recycle();screenshot("01-live");
            scene.onActivity(a->call(a,"setDrawer",new Class<?>[]{boolean.class},true));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();screenshot("02-menu");
            for(int mode:new int[]{0,1,2}){
                scene.onActivity(a->call(a,"selectMode",new Class<?>[]{int.class},mode));
                AtomicLong start=new AtomicLong();scene.onActivity(a->start.set(frames(a)));await(scene,a->frames(a)>start.get()+2);
            }
            screenshot("03-fullscreen");
        }
    }
    @Test public void failedReplacementCannotKeepShowingPreviousVideo() throws Exception {
        try(ActivityScenario<MainActivity> scene=video()){
            scene.onActivity(a->a.onActivityResult(20,Activity.RESULT_OK,new Intent().setData(Uri.fromFile(new File(a.getCacheDir(),"missing-video.mp4")))));
            await(scene,a->!Boolean.TRUE.equals(field(renderer(a),"textureHasFrame")));
            scene.onActivity(a->{assertEquals(0,frames(a));renderer(a).refresh();});
            SystemClock.sleep(250);
            scene.onActivity(a->{assertFalse((Boolean)field(renderer(a),"textureHasFrame"));assertEquals(0,frames(a));});
        }
    }
}
