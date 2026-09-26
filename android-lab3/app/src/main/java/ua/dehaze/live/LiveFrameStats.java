package ua.dehaze.live;

/** Counts consumed source frames, never UI redraws. All times are monotonic ms. */
final class LiveFrameStats {
    private long start=-1,last=-1,total,count;
    synchronized void frame(long now,boolean fresh){
        if(start<0)start=now;
        if(fresh){last=now;total++;count++;}
    }
    synchronized double fps(long now){return start<0?0:count*1000.0/Math.max(1,now-start);}
    synchronized long frameAge(long now){return last<0?-1:Math.max(0,now-last);}
    synchronized long totalFrames(){return total;}
    synchronized void interval(long now){start=now;count=0;}
    synchronized void reset(){start=last=-1;count=total=0;}
}
