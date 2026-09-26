package ua.dehaze.live;
import org.junit.Test;
import static org.junit.Assert.*;
public class AutoStrengthTest {
    private int[] stripes(int lo,int hi){int[] p=new int[64*32];for(int y=0;y<32;y++)for(int x=0;x<64;x++){int c=(x/8)%2==0?lo:hi;p[y*64+x]=0xff000000|c<<16|c<<8|c;}return p;}
    @Test public void clippedFullStrengthCanUseGentlerResult(){
        double f=AutoStrength.choose(stripes(140,160),stripes(0,255),64,32);
        assertTrue(f>0&&f<1);
    }
    @Test public void identityDoesNotRequestUnnecessaryProcessing(){int[] p=stripes(120,180);assertEquals(0,AutoStrength.choose(p,p,64,32),0);}
    @Test public void moderateImprovementIsKept(){assertTrue(AutoStrength.choose(stripes(140,160),stripes(120,180),64,32)>0);}
}
