package ua.dehaze.live;
import android.graphics.Bitmap;
import android.os.SystemClock;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Runs candidates independently on the existing worker; never commits UI policy. */
final class AutoProcessing {
    interface Processor { Lab3Processor.Pair process(Bitmap source,int algorithm,float strength,boolean photo,int flags) throws Exception; }
    static final class Batch {
        final Lab3Processor.Pair[] pairs;
        final float[] strength;
        final double[] quality;
        // -2: not attempted; -1: failed; >=0: measured processing time.
        final long[] elapsed;
        final String[] failures;
        final Lab3Processor.Pair original;
        long totalMs;
        Batch(Bitmap source,int count){pairs=new Lab3Processor.Pair[count];quality=new double[count];strength=new float[count];elapsed=new long[count];failures=new String[count];Arrays.fill(quality,Double.NEGATIVE_INFINITY);Arrays.fill(elapsed,-2);original=new Lab3Processor.Pair(source,source,"AUTO bypass: original",0,-1);}
        Lab3Processor.Pair selected(int k){return k<0||pairs[k]==null?original:pairs[k];}
        String report(){StringBuilder s=new StringBuilder("AUTO cycle "+totalMs+" ms; heuristic, not a quality guarantee");for(int k=0;k<pairs.length;k++)s.append(String.format(Locale.US,"\n%s: score %.2f; %d ms; strength %.0f%%; %s",Lab3Processor.NAMES[k],quality[k],elapsed[k],strength[k]*100,failures[k]==null?(elapsed[k]==-2?"skipped":"OK"):failures[k]));return s.toString();}
    }
    static Batch run(Bitmap source,float strength,boolean photo,int flags,boolean[] allowed,int only,Processor processor){
        if(allowed.length<1||allowed.length>Lab3Processor.NAMES.length||only< -2||only>=allowed.length)throw new IllegalArgumentException("Candidates");
        long started=SystemClock.elapsedRealtime();Bitmap small=Lab3Processor.fit(source,256),input=photo?source:small;Batch batch=new Batch(input,allowed.length);
        int w=small.getWidth(),h=small.getHeight();int[] reference=pixels(small);
        for(int k=0;k<allowed.length;k++){
            if(!allowed[k]||(only!=-2&&only!=k))continue;
            try{
                long candidateStart=SystemClock.elapsedRealtime();BcDehaze.checkCancel();Lab3Processor.Pair pair=processor.process(input,k,strength,photo,flags);
                Bitmap evaluation=pair.result.getWidth()==w&&pair.result.getHeight()==h?pair.result:Bitmap.createScaledBitmap(pair.result,w,h,true);
                int[] evaluated=pixels(evaluation);
                double fraction=only==-2?AutoStrength.choose(reference,evaluated,w,h):1;
                batch.quality[k]=AutoQuality.score(reference,ImagePlanes.blend(reference,evaluated,(float)fraction),w,h);
                batch.strength[k]=strength*(float)fraction;
                Bitmap chosen=pair.result;
                if(fraction<1){int pw=pair.original.getWidth(),ph=pair.original.getHeight();chosen=Bitmap.createBitmap(ImagePlanes.blend(pixels(pair.original),pixels(pair.result),(float)fraction),pw,ph,Bitmap.Config.ARGB_8888);}
                long ms=Math.max(pair.ms,SystemClock.elapsedRealtime()-candidateStart);batch.elapsed[k]=ms;
                batch.pairs[k]=new Lab3Processor.Pair(pair.original,chosen,pair.report+"; AUTO strength="+batch.strength[k],ms,k);
                if(evaluation!=pair.result)evaluation.recycle();
                if(chosen!=pair.result&&pair.result!=pair.original&&pair.result!=source)pair.result.recycle();
            }catch(CancellationException e){throw e;}
            catch(Exception|LinkageError|OutOfMemoryError e){batch.elapsed[k]=-1;batch.failures[k]=e.toString();}
        }
        batch.totalMs=SystemClock.elapsedRealtime()-started;if(photo&&small!=source)small.recycle();return batch;
    }
    static int[] pixels(Bitmap b){int w=b.getWidth(),h=b.getHeight();int[] data=new int[w*h];b.getPixels(data,0,w,0,0,w,h);return data;}
}
