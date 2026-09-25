package ua.dehaze.live;

import java.util.Arrays;

/**
 * Low-resolution, asynchronous equivalent of the PHOTO MAX image model:
 * dark-channel prior + airlight estimation + guided transmission + per-tile
 * CLAHE. The resulting maps are applied to full-resolution frames on GLES2.
 * The GPU renderer never waits for these CPU calculations.
 */
final class VideoDehazeProcessor {
    static final int W=192,H=108,TILES_X=8,TILES_Y=6;
    static final int LUT_W=256,LUT_H=TILES_X*TILES_Y;
    static final int N=W*H;
    private VideoDehazeProcessor(){}
    static final class Result {
        final byte[] map,lut;
        final float ar,ag,ab,haze,mean;
        final long computationMs,dcpMs,guidedMs,claheMs,temporalMs;
        final int fallbackBits;
        Result(byte[] map,byte[] lut,float ar,float ag,float ab,float haze,float mean,
               long ms,long dcpMs,long guidedMs,long claheMs,long temporalMs,int fallbackBits){
            this.map=map;this.lut=lut;this.ar=ar;this.ag=ag;this.ab=ab;
            this.haze=haze;this.mean=mean;this.computationMs=ms;
            this.dcpMs=dcpMs;this.guidedMs=guidedMs;this.claheMs=claheMs;
            this.temporalMs=temporalMs;this.fallbackBits=fallbackBits;
        }
    }
    private static float clamp(float x,float low,float high){
        return Math.max(low,Math.min(high,x));
    }
    private static int byteValue(float f){return Math.max(0,Math.min(255,Math.round(f*255f)));}
    private static float[] minBox(float[] input,int rad){
        final float[] row=new float[N],output=new float[N];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            float min=Float.POSITIVE_INFINITY;
            for(int xx=Math.max(0,x-rad);xx<=Math.min(W-1,x+rad);xx++)
                min=Math.min(min,input[y*W+xx]);
            row[y*W+x]=min;
        }
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            float min=Float.POSITIVE_INFINITY;
            for(int yy=Math.max(0,y-rad);yy<=Math.min(H-1,y+rad);yy++)
                min=Math.min(min,row[yy*W+x]);
            output[y*W+x]=min;
        }
        return output;
    }
    private static float[] box(float[] source,int radius){
        final int stride=W+1;
        double[] integral=new double[(H+1)*stride];
        float[] output=new float[N];
        for(int y=1;y<=H;y++){
            double sum=0.0;
            for(int x=1;x<=W;x++){
                sum+=source[(y-1)*W+x-1];
                integral[y*stride+x]=integral[(y-1)*stride+x]+sum;
            }
        }
        for(int y=0;y<H;y++){
            int top=Math.max(0,y-radius),bottom=Math.min(H,y+radius+1);
            for(int x=0;x<W;x++){
                int left=Math.max(0,x-radius),right=Math.min(W,x+radius+1);
                double sum=integral[bottom*stride+right]-integral[top*stride+right]
                    -integral[bottom*stride+left]+integral[top*stride+left];
                output[y*W+x]=(float)(sum/((right-left)*(bottom-top)));
            }
        }
        return output;
    }
    private static byte[] buildClahe(float[] gray){
        final byte[] lut=new byte[LUT_W*LUT_H*4];
        final int tileW=(W+TILES_X-1)/TILES_X,tileH=(H+TILES_Y-1)/TILES_Y;
        for(int ty=0;ty<TILES_Y;ty++)for(int tx=0;tx<TILES_X;tx++){
            int[] hist=new int[256];
            int x0=tx*tileW,y0=ty*tileH;
            int x1=Math.min(W,x0+tileW),y1=Math.min(H,y0+tileH);
            int count=0;
            float sum=0f,sumSq=0f;
            for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){
                float v=gray[y*W+x];int lum=byteValue(v);
                hist[lum]++;count++;sum+=v;sumSq+=v*v;
            }
            float sigma=(float)Math.sqrt(Math.max(0,sumSq/Math.max(1,count)
                -(sum/Math.max(1,count))*(sum/Math.max(1,count))));
            final float textureWeight=clamp((sigma-.015f)/.085f,0f,1f);
            int clip=Math.max(1,Math.round(2.05f*count/256f)),excess=0;
            for(int k=0;k<256;k++){
                if(hist[k]>clip){excess+=hist[k]-clip;hist[k]=clip;}
            }
            float cdf=0;
            int row=ty*TILES_X+tx;
            for(int k=0;k<256;k++){
                cdf+=hist[k]+(float)excess/256f;
                float mapped=cdf/Math.max(1,count);
                // Avoid extra contrast where the tile contains only blank sky.
                float adjusted=k/255f+(mapped-k/255f)*(.35f+.65f*textureWeight);
                int p=(row*256+k)*4;
                byte v=(byte)byteValue(adjusted);
                lut[p]=v;lut[p+1]=v;lut[p+2]=v;lut[p+3]=(byte)255;
            }
        }
        return lut;
    }

    static Result process(byte[] input,Result previous){
        return process(input,previous,LabRuntime.flags());
    }

    /** Each switch controls a real stage; a failing stage uses a local fallback. */
    static Result process(byte[] input,Result previous,int flags){
        long started=System.nanoTime(),stamp=started;
        long dcpMs=0,guidedMs=0,claheMs=0,temporalMs=0;
        int fallbackBits=0;
        if(input==null||input.length!=N*4)
            throw new IllegalArgumentException("Unexpected analysis frame size");
        float[] red=new float[N],green=new float[N],blue=new float[N],gray=new float[N],mins=new float[N];
        int[] grayHist=new int[256];
        float sumLuma=0f,sumSaturation=0f,edges=0f;
        int edgeCount=0;
        for(int y=0;y<H;y++){
            for(int x=0;x<W;x++){
                int p=(y*W+x)*4,i=y*W+x;
                float r=(input[p]&255)/255f,g=(input[p+1]&255)/255f,b=(input[p+2]&255)/255f;
                red[i]=r;green[i]=g;blue[i]=b;
                float v=.299f*r+.587f*g+.114f*b;
                gray[i]=v;mins[i]=Math.min(r,Math.min(g,b));grayHist[byteValue(v)]++;
                sumLuma+=v;sumSaturation+=Math.max(r,Math.max(g,b))-mins[i];
                if(x>0){edges+=Math.abs(v-gray[i-1]);edgeCount++;}
                if(y>0){edges+=Math.abs(v-gray[i-W]);edgeCount++;}
            }
        }
        // Estimate airlight from both small and large dark-channel scales.
        float[] dark=minBox(mins,4);
        int[] darkHist=new int[256];
        for(float v:dark)darkHist[byteValue(v)]++;
        int needed=Math.max(3,(int)(N*.0015f)),cutoff=255,acc=0;
        for(int i=255;i>=0;i--){acc+=darkHist[i];if(acc>=needed){cutoff=i;break;}}
        int best=0;float brightest=-1f;
        for(int i=0;i<N;i++){
            if(byteValue(dark[i])>=cutoff){
                float value=red[i]+green[i]+blue[i];
                if(value>brightest){brightest=value;best=i;}
            }
        }
        float ar=clamp(red[best],.42f,1f),ag=clamp(green[best],.42f,1f),
              ab=clamp(blue[best],.42f,1f);
        float[] norm=new float[N];
        for(int i=0;i<N;i++)
            norm[i]=Math.min(red[i]/ar,Math.min(green[i]/ag,blue[i]/ab));
        float[] raw=new float[N],sq=new float[N],grayRaw=new float[N];
        final boolean useDcp=(flags&(1<<(LabRuntime.DCP-2)))!=0;
        final boolean useGuide=(flags&(1<<(LabRuntime.GUIDED-2)))!=0;
        try{
            if(useDcp){
                float[] small=minBox(norm,4),large=minBox(norm,10);
                for(int i=0;i<N;i++)
                    raw[i]=clamp(1f-.84f*(.78f*small[i]+.22f*large[i]),.1f,1f);
            }else Arrays.fill(raw,1f);
        }catch(RuntimeException e){
            Arrays.fill(raw,1f);
            fallbackBits|=1<<(LabRuntime.DCP-2);
        }
        dcpMs=(System.nanoTime()-stamp)/1_000_000L;
        stamp=System.nanoTime();
        for(int i=0;i<N;i++){
            sq[i]=gray[i]*gray[i];grayRaw[i]=gray[i]*raw[i];
        }
        float[] localMean=box(gray,7),localSq=box(sq,7);
        float[] meanA=null,meanB=null;
        if(useGuide){
            try{
                float[] meanI=localMean,meanP=box(raw,7),
                        meanII=localSq,meanIP=box(grayRaw,7);
                float[] a=new float[N],b=new float[N];
                for(int i=0;i<N;i++){
                    float cov=meanIP[i]-meanI[i]*meanP[i];
                    float variance=Math.max(0f,meanII[i]-meanI[i]*meanI[i]);
                    a[i]=cov/(variance+.0018f);
                    b[i]=meanP[i]-a[i]*meanI[i];
                }
                meanA=box(a,7);meanB=box(b,7);
            }catch(RuntimeException e){
                meanA=null;meanB=null;
                fallbackBits|=1<<(LabRuntime.GUIDED-2);
            }
        }
        guidedMs=(System.nanoTime()-stamp)/1_000_000L;
        int cdf=0,p10=0,p90=255;
        for(int k=0;k<256;k++){cdf+=grayHist[k];if(cdf>=N*.10){p10=k;break;}}
        cdf=0;for(int k=0;k<256;k++){cdf+=grayHist[k];if(cdf>=N*.90){p90=k;break;}}
        float contrast=clamp((105f-(p90-p10))/100f,0f,1f),
              texture=clamp((18f-edges/Math.max(1,edgeCount)*255f)/18f,0f,1f),
              muted=clamp((48f-sumSaturation/N*255f)/48f,0f,1f);
        float haze=contrast*.45f+texture*.35f+muted*.20f;
        float mean=sumLuma/N;
        byte[] map=new byte[N*4];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            int i=y*W+x,p=i*4;
            float trans=meanA==null?raw[i]:
                clamp(meanA[i]*gray[i]+meanB[i],.24f,1f);
            float sigma=(float)Math.sqrt(Math.max(0f,localSq[i]-localMean[i]*localMean[i]));
            float detail=clamp((sigma-.009f)/.078f,0f,1f);
            // y=H-1 is the top row of the GL texture.
            float top=clamp(((float)y/H-.38f)/.36f,0f,1f);
            float light=clamp((gray[i]-.57f)/.32f,0f,1f);
            float sky=top*light*(1f-clamp((sigma-.018f)/.09f,0f,1f));
            // Sky-protection strength travels inside the map so no segmentation
            // inference or cloud model is required on the device.
            map[p]=(byte)byteValue(trans);
            map[p+1]=(byte)byteValue(sky);
            map[p+2]=(byte)byteValue(localMean[i]);
            map[p+3]=(byte)byteValue(detail);
        }
        stamp=System.nanoTime();
        byte[] lut;
        if((flags&(1<<(LabRuntime.CLAHE-2)))!=0){
            try{lut=buildClahe(gray);}
            catch(RuntimeException e){
                lut=identityLut();
                fallbackBits|=1<<(LabRuntime.CLAHE-2);
            }
        }else lut=identityLut();
        claheMs=(System.nanoTime()-stamp)/1_000_000L;
        stamp=System.nanoTime();
        if((flags&(1<<(LabRuntime.TEMPORAL-2)))!=0
            &&previous!=null&&Math.abs(mean-previous.mean)<.15f){
            float old=.55f,next=1f-old;
            for(int i=0;i<map.length;i++){
                int blended=Math.round((previous.map[i]&255)*old+(map[i]&255)*next);
                map[i]=(byte)blended;
            }
            for(int i=0;i<lut.length;i+=4){
                int blended=Math.round((previous.lut[i]&255)*old+(lut[i]&255)*next);
                lut[i]=lut[i+1]=lut[i+2]=(byte)blended;
            }
            ar=previous.ar*old+ar*next;
            ag=previous.ag*old+ag*next;
            ab=previous.ab*old+ab*next;
            haze=previous.haze*old+haze*next;
        }
        temporalMs=(System.nanoTime()-stamp)/1_000_000L;
        return new Result(map,lut,ar,ag,ab,haze,mean,
            (System.nanoTime()-started)/1_000_000L,
            dcpMs,guidedMs,claheMs,temporalMs,fallbackBits);
    }
    static byte[] identityLut(){
        byte[] lut=new byte[LUT_W*LUT_H*4];
        for(int tile=0;tile<LUT_H;tile++){
            for(int k=0;k<LUT_W;k++){
                int p=(tile*LUT_W+k)*4;
                lut[p]=lut[p+1]=lut[p+2]=(byte)k;
                lut[p+3]=(byte)255;
            }
        }
        return lut;
    }
}
