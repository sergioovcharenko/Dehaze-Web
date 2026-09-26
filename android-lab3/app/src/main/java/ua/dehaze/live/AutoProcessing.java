package ua.dehaze.live;
import android.graphics.Bitmap;
import android.os.SystemClock;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Runs candidates independently on the existing worker; never commits UI policy. */
final class AutoProcessing {
    interface Processor { Lab3Processor.Pair process(Bitmap source,int algorithm,float strength,boolean photo,int flags) throws Exception; }
    static final class Batch {
        final Lab3Processor.Pair[] pairs=new Lab3Processor.Pair[3];
        final double[] quality={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        // -2: not attempted; -1: failed; >=0: measured processing time.
        final long[] elapsed={-2,-2,-2};
        final String[] failures=new String[3];
        final Lab3Processor.Pair original;
        long totalMs;
        Batch(Bitmap source){original=new Lab3Processor.Pair(source,source,"AUTO bypass: original",0,-1);}
        Lab3Processor.Pair selected(int k){return k<0||pairs[k]==null?original:pairs[k];}
        String report(){StringBuilder s=new StringBuilder("AUTO cycle "+totalMs+" ms; heuristic, not a quality guarantee");for(int k=0;k<3;k++)s.append(String.format(Locale.US,"\n%s: score %.2f; %d ms; %s",Lab3Processor.NAMES[k],quality[k],elapsed[k],failures[k]==null?(elapsed[k]==-2?"skipped":"OK"):failures[k]));return s.toString();}
    }
    static Batch run(Bitmap source,float strength,boolean photo,int flags,boolean[] allowed,int only,Processor processor){
        if(allowed.length!=3||only< -2||only>2)throw new IllegalArgumentException("Candidates");
        long started=SystemClock.elapsedRealtime();Bitmap small=Lab3Processor.fit(source,256),input=photo?source:small;Batch batch=new Batch(input);
        int w=small.getWidth(),h=small.getHeight();int[] reference=pixels(small);
        for(int k=0;k<3;k++){
            if(!allowed[k]||(only!=-2&&only!=k))continue;
            try{
                BcDehaze.checkCancel();Lab3Processor.Pair pair=processor.process(input,k,strength,photo,flags);
                Bitmap evaluation=pair.result.getWidth()==w&&pair.result.getHeight()==h?pair.result:Bitmap.createScaledBitmap(pair.result,w,h,true);
                batch.quality[k]=AutoQuality.score(reference,pixels(evaluation),w,h);batch.elapsed[k]=pair.ms;batch.pairs[k]=pair;
                if(evaluation!=pair.result)evaluation.recycle();
            }catch(CancellationException e){throw e;}
            catch(Exception|LinkageError|OutOfMemoryError e){batch.elapsed[k]=-1;batch.failures[k]=e.toString();}
        }
        batch.totalMs=SystemClock.elapsedRealtime()-started;return batch;
    }
    private static int[] pixels(Bitmap b){int w=b.getWidth(),h=b.getHeight();int[] data=new int[w*h];b.getPixels(data,0,w,0,0,w,h);return data;}
}
