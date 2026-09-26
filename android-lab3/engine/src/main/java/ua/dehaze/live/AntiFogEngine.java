package ua.dehaze.live;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import java.util.IdentityHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/** Offline, UI-independent Android frame processor. Call process/close on a worker.
 * The host owns capture, display and its one-frame/no-backlog scheduling policy.
 */
public final class AntiFogEngine implements AutoCloseable {
    public static final int ORIGINAL=-1, CLASSIC=0, BC_CR=1, AI=2, CAP=3, FAST=4;
    private final Object workLock=new Object();
    private final AtomicLong generation=new AtomicLong();
    private final AutoProcessing.Processor processor;
    private final Lab3Processor owned;
    private final AutoPolicy policy=new AutoPolicy(5);
    private volatile boolean enabled,closed;
    private long policyGeneration=-1,lastProbe;
    private float chosenStrength=.7f,lastCeiling=-1;
    private boolean lastPhoto;

    public static final class Result implements AutoCloseable {
        public final Bitmap image;
        public final int algorithm;
        public final String algorithmName,report;
        public final long processingMs;
        public final float strength;
        private final long generation;
        private final boolean ownsImage;
        private Result(Bitmap image,int algorithm,long ms,float strength,long generation,boolean ownsImage,String report){
            this.image=image;this.algorithm=algorithm;this.processingMs=ms;this.strength=strength;this.generation=generation;this.ownsImage=ownsImage;this.report=report;
            this.algorithmName=algorithm<0?"Original":Lab3Processor.NAMES[algorithm];
        }
        /** Call only after the image is no longer displayed. Never recycles input. */
        @Override public void close(){if(ownsImage&&!image.isRecycled())image.recycle();}
    }
    public AntiFogEngine(Context context){owned=new Lab3Processor(context.getApplicationContext());processor=owned::process;}
    AntiFogEngine(AutoProcessing.Processor processor){this.processor=processor;owned=null;}
    /** Nonblocking; the host should show its original frame immediately on OFF. */
    public void setEnabled(boolean value){enabled=value;generation.incrementAndGet();}
    /** Invalidate queued results when source/stream/configuration changes. */
    public void reset(){generation.incrementAndGet();}
    public boolean isEnabled(){return enabled&&!closed;}
    /** Recheck on the UI thread immediately before publishing a worker result. */
    public boolean isCurrent(Result result){return !closed&&result.generation==generation.get();}

    public Result process(Bitmap input,float strengthCeiling,boolean photo){
        synchronized(workLock){
            if(closed)throw new IllegalStateException("Engine closed");
            if(input==null||input.isRecycled()||!Float.isFinite(strengthCeiling)||strengthCeiling<0||strengthCeiling>1)throw new IllegalArgumentException("Image/strength");
            final long token=generation.get(),started=SystemClock.elapsedRealtime();
            if(!enabled||strengthCeiling==0)return original(input,0,token,"Disabled or zero strength");
            if(policyGeneration!=token||lastCeiling!=strengthCeiling||lastPhoto!=photo){policy.reset();lastProbe=0;chosenStrength=strengthCeiling;policyGeneration=token;lastCeiling=strengthCeiling;lastPhoto=photo;}
            boolean probe=photo||!policy.initialized()||started-lastProbe>=AutoPolicy.PROBE_MS;
            boolean[] allowed=new boolean[5];for(int k=0;k<5;k++)allowed[k]=policy.allowed(k,started);
            int current=policy.current();AutoProcessing.Batch batch;
            try{
                batch=AutoProcessing.run(input,probe?strengthCeiling:chosenStrength,photo,63,allowed,probe?-2:current,(source,algorithm,strength,isPhoto,flags)->{
                    if(generation.get()!=token||!enabled)throw new CancellationException("Superseded frame");
                    return processor.process(source,algorithm,strength,isPhoto,flags);
                });
            }catch(CancellationException e){return original(input,SystemClock.elapsedRealtime()-started,token,"Cancelled/superseded frame");}
            long now=SystemClock.elapsedRealtime();
            if(generation.get()!=token||!enabled){release(batch,input,input);return original(input,now-started,token,"Superseded frame");}
            if(probe){policy.choose(batch.quality,batch.elapsed,!photo,now);lastProbe=now;}
            else if(current>=0)policy.rejectCurrent(batch.quality[current],batch.elapsed[current],batch.elapsed[current]<0,now);
            int selected=policy.current();
            if(probe)chosenStrength=selected<0?0:batch.strength[selected];
            Bitmap output=selected<0?input:batch.selected(selected).result;
            String report=batch.report();release(batch,input,output);
            return new Result(output,selected,SystemClock.elapsedRealtime()-started,selected<0?0:chosenStrength,token,output!=input,report);
        }
    }
    private static Result original(Bitmap input,long ms,long generation,String report){return new Result(input,ORIGINAL,ms,0,generation,false,report);}
    private static void release(AutoProcessing.Batch batch,Bitmap input,Bitmap keep){
        IdentityHashMap<Bitmap,Boolean> images=new IdentityHashMap<>();images.put(batch.original.result,true);
        for(Lab3Processor.Pair pair:batch.pairs)if(pair!=null){images.put(pair.original,true);images.put(pair.result,true);}
        for(Bitmap bitmap:images.keySet())if(bitmap!=input&&bitmap!=keep&&!bitmap.isRecycled())bitmap.recycle();
    }
    @Override public void close(){enabled=false;closed=true;generation.incrementAndGet();synchronized(workLock){if(owned!=null)owned.close();}}
}
