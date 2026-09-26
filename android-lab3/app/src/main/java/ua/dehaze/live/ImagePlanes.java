package ua.dehaze.live;

/** Tensor contract shared by preprocessing tests and the actual Android inference. */
public final class ImagePlanes {
    private ImagePlanes(){}
    public static float[] pack(int[] pixels,int w,int h,int side){
        if(w<1||h<1||w>side||h>side||(long)w*h!=pixels.length||side>2048)throw new IllegalArgumentException("Input dimensions");
        int n=side*side;float[] out=new float[3*n];
        for(int y=0;y<side;y++)for(int x=0;x<side;x++){
            int p=pixels[BcDehaze.reflect(y,h)*w+BcDehaze.reflect(x,w)],i=y*side+x;
            out[i]=((p>>16)&255)/127.5f-1;out[n+i]=((p>>8)&255)/127.5f-1;out[2*n+i]=(p&255)/127.5f-1;
        }return out;
    }
    public static int[] unpack(float[] tensor,int side,int w,int h){
        int n=side*side;if(w<1||h<1||w>side||h>side||tensor.length!=n*3)throw new IllegalArgumentException("Output dimensions");
        int[] out=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int i=y*side+x,p=0xff000000;
            for(int c=0;c<3;c++){
                float v=tensor[c*n+i];if(!Float.isFinite(v))throw new IllegalArgumentException("Non-finite AI output");
                p|=Math.round((Math.max(-1,Math.min(1,v))+1)*127.5f)<<(16-c*8);
            }out[y*w+x]=p;
        }return out;
    }
    public static int[] blend(int[] original,int[] result,float strength){
        if(original.length!=result.length||!Float.isFinite(strength))throw new IllegalArgumentException("Blend input");
        strength=Math.max(0,Math.min(1,strength));int[] out=new int[original.length];
        for(int i=0;i<out.length;i++){
            int p=0xff000000;for(int shift=0;shift<=16;shift+=8){int a=(original[i]>>shift)&255,b=(result[i]>>shift)&255;p|=Math.round(a+(b-a)*strength)<<shift;}out[i]=p;
        }return out;
    }
}
