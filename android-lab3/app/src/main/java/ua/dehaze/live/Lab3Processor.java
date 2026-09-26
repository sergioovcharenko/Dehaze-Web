package ua.dehaze.live;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

final class Lab3Processor implements AutoCloseable {
    static final String[] NAMES={"CLASSIC MAX · кадр","BC/CR","DehazeFormer-T"};
    static final class Pair {
        final Bitmap original,result;final String report;final long ms;final int algorithm;
        Pair(Bitmap a,Bitmap b,String report,long ms,int algorithm){original=a;result=b;this.report=report;this.ms=ms;this.algorithm=algorithm;}
    }
    private final NeuralDehaze neural;
    Lab3Processor(Context context){neural=new NeuralDehaze(context);}
    static Bitmap fit(Bitmap source,int limit){
        double s=Math.min(1,limit/(double)Math.max(source.getWidth(),source.getHeight()));
        return s==1?source:Bitmap.createScaledBitmap(source,Math.max(1,(int)Math.round(source.getWidth()*s)),Math.max(1,(int)Math.round(source.getHeight()*s)),true);
    }
    Pair process(Bitmap source,int algorithm,float strength,boolean photo,int flags) throws Exception {
        if(algorithm<0||algorithm>2)throw new IllegalArgumentException("Unknown algorithm");
        long start=SystemClock.elapsedRealtime();Bitmap prepared=fit(source,algorithm==2?256:1600);
        int w=prepared.getWidth(),h=prepared.getHeight();int[] original=new int[w*h];prepared.getPixels(original,0,w,0,0,w,h);
        int[] processed;String report;
        if(strength==0){processed=original.clone();report="BYPASS — original, no processing";}
        else if(algorithm==0){processed=ClassicSnapshot.process(original,w,h,flags);report="CLASSIC CPU snapshot; LAB 2 maps 192x108; flags="+flags+"; temporal OFF for independent snapshots";}
        else if(algorithm==1){BcDehaze.Result bc=BcDehaze.process(original,w,h,photo?512:256);processed=bc.argb;report=bc.report();}
        else{Bitmap ai=neural.process(prepared);processed=new int[w*h];ai.getPixels(processed,0,w,0,0,w,h);ai.recycle();report="DehazeFormer-T outdoor; real ONNX inference; CPU 2 threads; RGB [-1,1]; padded 256x256";}
        BcDehaze.checkCancel();int[] blended=ImagePlanes.blend(original,processed,strength);
        long elapsed=SystemClock.elapsedRealtime()-start;
        return new Pair(prepared,Bitmap.createBitmap(blended,w,h,Bitmap.Config.ARGB_8888),report+"; strength="+strength+"; image="+w+"x"+h+"; total="+elapsed+" ms",elapsed,algorithm);
    }
    @Override public void close(){neural.close();}
}
