package ua.dehaze.live;
import org.junit.Test;
import static org.junit.Assert.*;
public class ImagePlanesTest {
    @Test public void rgbChannelsUseMinusOneToOneAndReflection(){
        float[] f=ImagePlanes.pack(new int[]{0xffff0000,0xff00ff00},2,1,4);
        assertEquals(48,f.length);
        assertArrayEquals(new float[]{1,-1,1,-1},java.util.Arrays.copyOf(f,4),0);
        assertEquals(-1,f[16],0);assertEquals(1,f[17],0);assertEquals(-1,f[32],0);
        assertEquals(f[0],f[4],0);
    }
    @Test public void outputCropAndClampAreInRgbOrder(){
        float[] f={2,-2,0,0, -1,1,0,0, -1,-1,0,0};
        assertArrayEquals(new int[]{0xffff0000,0xff00ff00},ImagePlanes.unpack(f,2,2,1));
    }
    @Test public void zeroStrengthIsExactOriginal(){
        int[] original={0xff102030,0xffabcdef};
        assertArrayEquals(original,ImagePlanes.blend(original,new int[]{0xffffffff,0xff000000},0));
    }
    @Test public void invalidModelOutputIsAnError(){
        try{ImagePlanes.unpack(new float[]{Float.NaN,0,0},1,1,1);fail("NaN accepted");}
        catch(IllegalArgumentException expected){}
    }
}
