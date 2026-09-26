package ua.dehaze.live;

import java.util.concurrent.CancellationException;
import java.util.Locale;

/** Independent implementation of Meng et al. ICCV 2013 BC + weighted L1 CR.
 * Eight distinct Kirsch directions; reduced transmission grid; recovery floor .1.
 * These mobile choices are recorded in the report, not claimed as author-code parity.
 */
public final class BcDehaze {
    private BcDehaze(){}
    public static final class Result {
        public final int[] argb;
        public final double[] air;
        public final long airMs,boundaryMs,regularizationMs,recoveryMs,totalMs;
        public final int mapWidth,mapHeight,iterations=6;
        Result(int[] p,double[] a,long[] time,int w,int h){
            argb=p;air=a;airMs=time[0];boundaryMs=time[1];regularizationMs=time[2];
            recoveryMs=time[3];totalMs=airMs+boundaryMs+regularizationMs+recoveryMs;
            mapWidth=w;mapHeight=h;
        }
        public String report(){return String.format(Locale.US,
            "BC/CR: Airlight OK %.1f/%.1f/%.1f (%d ms); Boundary OK %d ms; CR OK %d ms; Recovery OK %d ms; total %d ms; map %dx%d; 8 Kirsch; 6 iterations; lambda=2 sigma=.5 delta=.85 floor=.1; postfilters OFF",
            air[0],air[1],air[2],airMs,boundaryMs,regularizationMs,recoveryMs,totalMs,mapWidth,mapHeight);}
    }
    static void checkCancel(){if(Thread.currentThread().isInterrupted())throw new CancellationException("Cancelled");}
    static long ms(long from){return (System.nanoTime()-from)/1_000_000L;}
    static double boundary(double c,double a){
        double lo=(a-c)/Math.max(1e-6,a-30),hi=(c-a)/Math.max(1e-6,300-a);
        return Math.max(0,Math.min(1,Math.max(lo,hi)));
    }
    static int reflect(int x,int n){
        if(n<=1)return 0;
        int period=2*n-2;x=((x%period)+period)%period;return x<n?x:period-x;
    }
    private static int power2(int n){int k=8;while(k<n)k<<=1;return k;}
    public static Result process(int[] pixels,int width,int height,int mapLimit){
        if(width<1||height<1||(long)width*height!=pixels.length||mapLimit<8||mapLimit>512)
            throw new IllegalArgumentException("Invalid image or map limit");
        long start=System.nanoTime();long[] times=new long[4];
        double scale=Math.min(1,(double)mapLimit/Math.max(width,height));
        int rw=Math.max(1,(int)Math.round(width*scale)),rh=Math.max(1,(int)Math.round(height*scale));
        int[] small=new int[rw*rh];
        for(int y=0;y<rh;y++)for(int x=0;x<rw;x++)
            small[y*rw+x]=pixels[Math.min(height-1,(int)((y+.5)/scale))*width+Math.min(width-1,(int)((x+.5)/scale))];
        double[] air=new double[3];
        for(int channel=0;channel<3;channel++){
            double[] raw=new double[small.length];int shift=16-channel*8;
            for(int n=0;n<raw.length;n++)raw[n]=(small[n]>>shift)&255;
            double[] eroded=morph(raw,rw,rh,7,false);
            for(double v:eroded)air[channel]=Math.max(air[channel],v);
            air[channel]=Math.max(31,air[channel]);
        }
        times[0]=ms(start);start=System.nanoTime();checkCancel();
        double[] initial=new double[small.length];
        for(int n=0;n<initial.length;n++){
            int c=small[n];initial[n]=Math.max(boundary((c>>16)&255,air[0]),
                Math.max(boundary((c>>8)&255,air[1]),boundary(c&255,air[2])));
        }
        initial=morph(morph(initial,rw,rh,1,true),rw,rh,1,false);
        int w=power2(rw),h=power2(rh);double[] padded=new double[w*h];int[] context=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int s=reflect(y,rh)*rw+reflect(x,rw);padded[y*w+x]=initial[s];context[y*w+x]=small[s];
        }
        times[1]=ms(start);start=System.nanoTime();
        double[] t=regularize(context,padded,w,h);
        times[2]=ms(start);start=System.nanoTime();checkCancel();
        int[] output=new int[pixels.length];
        for(int y=0;y<height;y++){
            if((y&31)==0)checkCancel();
            double sy=Math.max(0,Math.min(rh-1,(y+.5)*rh/height-.5));int y0=(int)sy,y1=Math.min(rh-1,y0+1);double fy=sy-y0;
            for(int x=0;x<width;x++){
                double sx=Math.max(0,Math.min(rw-1,(x+.5)*rw/width-.5));int x0=(int)sx,x1=Math.min(rw-1,x0+1);double fx=sx-x0;
                double tr=(1-fy)*((1-fx)*t[y0*w+x0]+fx*t[y0*w+x1])+fy*((1-fx)*t[y1*w+x0]+fx*t[y1*w+x1]);
                tr=Math.max(.1,Math.pow(Math.max(0,Math.min(1,tr)),.85));
                int c=pixels[y*width+x],out=0xff000000;
                for(int k=0;k<3;k++){
                    int shift=16-k*8;double v=(((c>>shift)&255)-air[k])/tr+air[k];
                    if(!Double.isFinite(v))throw new ArithmeticException("Non-finite recovery");
                    out|=Math.max(0,Math.min(255,(int)Math.round(v)))<<shift;
                }
                output[y*width+x]=out;
            }
        }
        times[3]=ms(start);return new Result(output,air,times,rw,rh);
    }
    private static double[] morph(double[] in,int w,int h,int radius,boolean max){
        double[] mid=new double[in.length],out=new double[in.length];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            double v=max?-Double.MAX_VALUE:Double.MAX_VALUE;
            for(int k=-radius;k<=radius;k++){double a=in[y*w+reflect(x+k,w)];v=max?Math.max(v,a):Math.min(v,a);}mid[y*w+x]=v;
        }
        for(int y=0;y<h;y++){checkCancel();for(int x=0;x<w;x++){
            double v=max?-Double.MAX_VALUE:Double.MAX_VALUE;
            for(int k=-radius;k<=radius;k++){double a=mid[reflect(y+k,h)*w+x];v=max?Math.max(v,a):Math.min(v,a);}out[y*w+x]=v;
        }}return out;
    }
    private static double[][] kernels(){
        int[] ring={0,1,2,5,8,7,6,3};double[][] out=new double[8][9];
        for(int k=0;k<8;k++)for(int j=0;j<8;j++)out[k][ring[j]]=((j-k+8)%8<3?5:-3)/Math.sqrt(120);
        return out;
    }
    private static void correlate(double[] in,double[] out,double[] kernel,int w,int h){
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            double sum=0;int n=0;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)sum+=kernel[n++]*in[((y+dy)&(h-1))*w+((x+dx)&(w-1))];
            out[y*w+x]=sum;
        }
    }
    static double[] regularize(int[] rgb,double[] seed,int w,int h){
        int n=w*h;if((w&(w-1))!=0||(h&(h-1))!=0||seed.length!=n||rgb.length!=n)throw new IllegalArgumentException("FFT geometry");
        double[][] filters=kernels(),weights=new double[8][n],dr=new double[8][n],di=new double[8][n];
        double[] ds=new double[n],fr=seed.clone(),fi=new double[n],channel=new double[n],grad=new double[n];
        fft2(fr,fi,w,h,false);
        for(int k=0;k<8;k++){
            checkCancel();
            for(int c=0;c<3;c++){
                int shift=16-c*8;for(int z=0;z<n;z++)channel[z]=((rgb[z]>>shift)&255)/255.0;
                correlate(channel,grad,filters[k],w,h);
                for(int z=0;z<n;z++)weights[k][z]+=grad[z]*grad[z];
            }
            for(int z=0;z<n;z++)weights[k][z]=Math.exp(-weights[k][z]); // 2*sigma = 1
            int q=0;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)dr[k][((-dy)&(h-1))*w+((-dx)&(w-1))]+=filters[k][q++];
            fft2(dr[k],di[k],w,h,false);
            for(int z=0;z<n;z++)ds[z]+=dr[k][z]*dr[k][z]+di[k][z]*di[k][z];
        }
        double[] t=seed.clone(),sumR=new double[n],sumI=new double[n],ur=new double[n],ui=new double[n];
        for(double beta=1;beta<256;beta*=2*Math.sqrt(2)){
            checkCancel();java.util.Arrays.fill(sumR,0);java.util.Arrays.fill(sumI,0);
            for(int k=0;k<8;k++){
                checkCancel();correlate(t,grad,filters[k],w,h);
                for(int z=0;z<n;z++){ur[z]=Math.copySign(Math.max(0,Math.abs(grad[z])-weights[k][z]/beta/8),grad[z]);ui[z]=0;}
                fft2(ur,ui,w,h,false);
                for(int z=0;z<n;z++){
                    sumR[z]+=dr[k][z]*ur[z]+di[k][z]*ui[z];
                    sumI[z]+=dr[k][z]*ui[z]-di[k][z]*ur[z];
                }
            }
            double gamma=2/beta;
            for(int z=0;z<n;z++){sumR[z]=(gamma*fr[z]+sumR[z])/(gamma+ds[z]);sumI[z]=(gamma*fi[z]+sumI[z])/(gamma+ds[z]);}
            fft2(sumR,sumI,w,h,true);
            for(int z=0;z<n;z++)t[z]=Math.abs(sumR[z]);
        }
        for(double v:t)if(!Double.isFinite(v))throw new ArithmeticException("Non-finite transmission");
        return t;
    }
    static void fft2(double[] r,double[] i,int w,int h,boolean inverse){
        int size=Math.max(w,h);double[] rr=new double[size],ii=new double[size];
        for(int y=0;y<h;y++){
            System.arraycopy(r,y*w,rr,0,w);System.arraycopy(i,y*w,ii,0,w);fft(rr,ii,w,inverse);
            System.arraycopy(rr,0,r,y*w,w);System.arraycopy(ii,0,i,y*w,w);
        }
        for(int x=0;x<w;x++){
            for(int y=0;y<h;y++){rr[y]=r[y*w+x];ii[y]=i[y*w+x];}fft(rr,ii,h,inverse);
            for(int y=0;y<h;y++){r[y*w+x]=rr[y];i[y*w+x]=ii[y];}
        }
    }
    private static void fft(double[] r,double[] im,int n,boolean inverse){
        for(int i=1,j=0;i<n;i++){
            int bit=n>>1;for(; (j&bit)!=0;bit>>=1)j^=bit;j^=bit;
            if(i<j){double a=r[i];r[i]=r[j];r[j]=a;a=im[i];im[i]=im[j];im[j]=a;}
        }
        for(int len=2;len<=n;len<<=1){
            double angle=(inverse?2:-2)*Math.PI/len,wr=Math.cos(angle),wi=Math.sin(angle);
            for(int i=0;i<n;i+=len){double cr=1,ci=0;for(int j=0;j<len/2;j++){
                int a=i+j,b=a+len/2;double vr=r[b]*cr-im[b]*ci,vi=r[b]*ci+im[b]*cr;
                r[b]=r[a]-vr;im[b]=im[a]-vi;r[a]+=vr;im[a]+=vi;
                double next=cr*wr-ci*wi;ci=cr*wi+ci*wr;cr=next;
            }}
        }
        if(inverse)for(int i=0;i<n;i++){r[i]/=n;im[i]/=n;}
    }
}
