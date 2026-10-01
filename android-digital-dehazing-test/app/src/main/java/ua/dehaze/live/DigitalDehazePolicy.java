package ua.dehaze.live;

/** Small deterministic policy used by the standalone Digital Dehazing video tester.
 * It only selects strength/cadence; it never invents image content.
 */
final class DigitalDehazePolicy {
    static final int LOW=35,MEDIUM=60,HIGH=85;
    static final class Decision {
        final float strength,haze,mean;
        final boolean night;
        final long intervalNs;
        Decision(float strength,float haze,float mean,boolean night,long intervalNs){
            this.strength=strength;this.haze=haze;this.mean=mean;this.night=night;this.intervalNs=intervalNs;
        }
    }
    private DigitalDehazePolicy(){}
    static float clamp01(float v){return Math.max(0f,Math.min(1f,v));}
    static float manualStrength(int level){
        if(level==LOW)return .35f;
        if(level==HIGH)return .85f;
        return .60f;
    }
    static Decision decide(float haze,float mean,float sceneDelta,long processingMs){
        haze=clamp01(haze);
        mean=Math.max(0f,Math.min(255f,mean));
        // Clean images should remain effectively original. No permanent 22% floor.
        float normalized=clamp01((haze-.14f)/.56f);
        float target=normalized*.90f;
        boolean night=mean<72f;
        if(night){
            target*=.62f;
            target=Math.min(target,.55f);
            if(mean<38f)target=Math.min(target,.38f);
        }
        if(haze<.14f)target=0f;
        long interval;
        if(sceneDelta>.10f) interval=180_000_000L;
        else if(processingMs>180) interval=850_000_000L;
        else if(haze<.12f) interval=950_000_000L;
        else if(night) interval=650_000_000L;
        else interval=420_000_000L;
        return new Decision(target,haze,mean,night,interval);
    }
}
