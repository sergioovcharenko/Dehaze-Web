package ua.dehaze.live;
/** Reuses one algorithm result; never reruns inference to compare strengths. */
final class AutoStrength {
    static double choose(int[] original,int[] processed,int w,int h){
        double best=0,fraction=0;
        for(double f:new double[]{.35,.65,1}){
            int[] candidate=ImagePlanes.blend(original,processed,(float)f);
            double score=AutoQuality.score(original,candidate,w,h);
            if(score>best+.15){best=score;fraction=f;}
        }
        return fraction;
    }
}
