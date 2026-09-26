package ua.dehaze.live;

/** One active computation. Invalidating its result does not create a second worker. */
public final class FrameGate {
    private long generation, serial, active=-1, activeGeneration;
    public synchronized long begin(){
        if(active!=-1)return -1;
        active=++serial;activeGeneration=generation;return active;
    }
    public synchronized void reset(){generation++;}
    public synchronized boolean finish(long ticket){
        if(ticket!=active||active==-1)return false;
        boolean valid=activeGeneration==generation;active=-1;return valid;
    }
}
