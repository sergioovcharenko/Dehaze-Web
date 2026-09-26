package ua.dehaze.live;

import java.util.Arrays;

/** Deterministic CAP adaptation and lightweight DCP. Maps are bounded to 320/192 px.
 * CAP: Zhu/Mai/Shao TIP 2015 coefficients; no random depth term; grayscale guided
 * refinement and a conservative transmission floor. FAST omits CLAHE/Retinex/AI.
 */
final class LightDehaze {
    static double capDepth(double value,double saturation){return Math.max(0,.121779+.959710*value-.780245*saturation);}
    static int[] process(int[] input,int w,int h,boolean cap){
        if(w<1||h<1||(long)w*h!=input.length)throw new IllegalArgumentException("Image dimensions");
        BcDehaze.checkCancel();
        int limit=cap?320:192;double scale=Math.min(1,limit/(double)Math.max(w,h));
        int mw=Math.max(1,(int)Math.round(w*scale)),mh=Math.max(1,(int)Math.round(h*scale)),n=mw*mh;
        float[] guide=new float[n],prior=new float[n];int[] small=new int[n];
        for(int y=0;y<mh;y++)for(int x=0;x<mw;x++){
            int p=input[Math.min(h-1,(int)((y+.5)*h/mh))*w+Math.min(w-1,(int)((x+.5)*w/mw))],i=y*mw+x;small[i]=p;
            float r=((p>>16)&255)/255f,g=((p>>8)&255)/255f,b=(p&255)/255f;
            float hi=Math.max(r,Math.max(g,b)),lo=Math.min(r,Math.min(g,b));
            guide[i]=.299f*r+.587f*g+.114f*b;
            prior[i]=cap?(float)capDepth(hi,hi==0?0:(hi-lo)/hi):lo;
        }
        int radius=cap?7:3;float[] local=minBox(prior,mw,mh,radius),ordered=local.clone();Arrays.sort(ordered);
        float threshold=ordered[Math.max(0,n-Math.max(1,n/1000))];int airPixel=small[0];float brightest=-1;
        for(int i=0;i<n;i++)if(local[i]>=threshold&&guide[i]>brightest){brightest=guide[i];airPixel=small[i];}
        float[] air={Math.max(.125f,((airPixel>>16)&255)/255f),Math.max(.125f,((airPixel>>8)&255)/255f),Math.max(.125f,(airPixel&255)/255f)};
        float[] t=new float[n];
        if(cap){float[] depth=guided(guide,local,mw,mh,12,.001f);for(int i=0;i<n;i++)t[i]=(float)Math.exp(-Math.max(0,depth[i]));}
        else{
            for(int i=0;i<n;i++){int p=small[i];prior[i]=Math.min(((p>>16)&255)/(255f*air[0]),Math.min(((p>>8)&255)/(255f*air[1]),(p&255)/(255f*air[2])));}
            float[] dark=minBox(prior,mw,mh,3);for(int i=0;i<n;i++)t[i]=Math.max(.30f,1-.85f*dark[i]);
            t=guided(guide,t,mw,mh,6,.002f);
        }
        int[] result=new int[input.length];
        for(int y=0;y<h;y++){
            if((y&15)==0)BcDehaze.checkCancel();
            for(int x=0;x<w;x++){
                float transmission=Math.max(cap?.18f:.30f,Math.min(1,sample(t,mw,mh,(x+.5f)*mw/w-.5f,(y+.5f)*mh/h-.5f)));
                int p=input[y*w+x],out=p&0xff000000;
                for(int c=0;c<3;c++){int shift=16-c*8;float v=(((p>>shift)&255)/255f-air[c])/transmission+air[c];out|=Math.max(0,Math.min(255,Math.round(v*255)))<<shift;}
                result[y*w+x]=out;
            }
        }
        return result;
    }
    static float[] minBox(float[] input,int w,int h,int radius){
        float[] temp=new float[input.length],out=new float[input.length];int[] deque=new int[Math.max(w,h)];
        for(int y=0;y<h;y++)minLine(input,temp,y*w,1,w,radius,deque);
        for(int x=0;x<w;x++)minLine(temp,out,x,w,h,radius,deque);
        return out;
    }
    private static void minLine(float[] in,float[] out,int start,int stride,int n,int r,int[] q){
        int head=0,tail=0,right=-1;
        for(int x=0;x<n;x++){
            int limit=Math.min(n-1,x+r);
            while(right<limit){right++;while(tail>head&&in[start+q[tail-1]*stride]>=in[start+right*stride])tail--;q[tail++]=right;}
            while(q[head]<x-r)head++;
            out[start+x*stride]=in[start+q[head]*stride];
        }
    }
    private static float[] box(float[] p,int w,int h,int r){
        int stride=w+1;double[] integral=new double[(h+1)*stride];float[] out=new float[p.length];
        for(int y=0;y<h;y++){double row=0;for(int x=0;x<w;x++){row+=p[y*w+x];integral[(y+1)*stride+x+1]=integral[y*stride+x+1]+row;}}
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int x0=Math.max(0,x-r),x1=Math.min(w,x+r+1),y0=Math.max(0,y-r),y1=Math.min(h,y+r+1);out[y*w+x]=(float)((integral[y1*stride+x1]-integral[y1*stride+x0]-integral[y0*stride+x1]+integral[y0*stride+x0])/((x1-x0)*(y1-y0)));}
        return out;
    }
    static float[] guided(float[] guide,float[] p,int w,int h,int radius,float eps){
        float[] meanI=box(guide,w,h,radius),meanP=box(p,w,h,radius),ii=new float[p.length],ip=new float[p.length];
        for(int i=0;i<p.length;i++){ii[i]=guide[i]*guide[i];ip[i]=guide[i]*p[i];}
        ii=box(ii,w,h,radius);ip=box(ip,w,h,radius);
        float[] a=new float[p.length],b=new float[p.length];
        for(int i=0;i<p.length;i++){a[i]=(ip[i]-meanI[i]*meanP[i])/(Math.max(0,ii[i]-meanI[i]*meanI[i])+eps);b[i]=meanP[i]-a[i]*meanI[i];}
        a=box(a,w,h,radius);b=box(b,w,h,radius);for(int i=0;i<p.length;i++)b[i]+=a[i]*guide[i];return b;
    }
    private static float sample(float[] p,int w,int h,float x,float y){
        x=Math.max(0,Math.min(w-1,x));y=Math.max(0,Math.min(h-1,y));int x0=(int)x,y0=(int)y,x1=Math.min(w-1,x0+1),y1=Math.min(h-1,y0+1);float ax=x-x0,ay=y-y0;
        return (1-ay)*((1-ax)*p[y0*w+x0]+ax*p[y0*w+x1])+ay*((1-ax)*p[y1*w+x0]+ax*p[y1*w+x1]);
    }
}
