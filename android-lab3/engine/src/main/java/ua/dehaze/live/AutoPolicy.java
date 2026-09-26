package ua.dehaze.live;

/** UI-thread selection state; only a current generation may commit observations. */
final class AutoPolicy {
    static final long PROBE_MS=6000,DWELL_MS=8000,RETRY_MS=30000,MAX_VIDEO_MS=2000;
    private final long[] retryAfter;
    AutoPolicy(){this(3);}
    AutoPolicy(int count){if(count<1||count>16)throw new IllegalArgumentException("Candidate count");retryAfter=new long[count];}
    private int current=-1,challenger=-2,wins;
    private boolean initialized;
    private long lastSwitch;
    int current(){return current;}
    boolean initialized(){return initialized;}
    boolean allowed(int algorithm,long now){return now>=retryAfter[algorithm];}
    void reset(){java.util.Arrays.fill(retryAfter,0);current=-1;challenger=-2;wins=0;initialized=false;lastSwitch=0;}
    int choose(double[] quality,long[] elapsed,boolean video,long now){
        if(quality.length!=retryAfter.length||elapsed.length!=retryAfter.length)throw new IllegalArgumentException("Candidate count mismatch");
        double[] adjusted=new double[retryAfter.length];int best=-1;double bestScore=1.5;
        for(int k=0;k<retryAfter.length;k++){
            if(elapsed[k]==-1||(video&&elapsed[k]>MAX_VIDEO_MS))retryAfter[k]=now+RETRY_MS;
            adjusted[k]=elapsed[k]<0||!allowed(k,now)||!Double.isFinite(quality[k])?Double.NEGATIVE_INFINITY:quality[k]-(video?.004*elapsed[k]:0);
            if(adjusted[k]>bestScore){best=k;bestScore=adjusted[k];}
        }
        double incumbent=current<0?0:adjusted[current];
        if(!video||!initialized||!Double.isFinite(incumbent)||incumbent< -3){set(best,now);return current;}
        if(best==current){challenger=-2;wins=0;return current;}
        double score=best<0?0:adjusted[best];
        if(score-incumbent<3){challenger=-2;wins=0;return current;}
        if(best==challenger)wins++;else{challenger=best;wins=1;}
        if(wins>=2&&now-lastSwitch>=DWELL_MS)set(best,now);
        return current;
    }
    boolean rejectCurrent(double quality,long elapsed,boolean failed,long now){
        if(current<0)return false;
        if(failed||elapsed>MAX_VIDEO_MS||!Double.isFinite(quality)||quality< -3){
            if(failed||elapsed>MAX_VIDEO_MS)retryAfter[current]=now+RETRY_MS;
            current=-1;initialized=false;challenger=-2;wins=0;return true;
        }
        return false;
    }
    private void set(int algorithm,long now){current=algorithm;initialized=true;lastSwitch=now;challenger=-2;wins=0;}
}
