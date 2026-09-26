package ua.dehaze.live;

/** CPU snapshot interpretation of LAB 2's hybrid shader, using its unchanged maps.
 * The original GLES live and PHOTO MAX implementations remain separate entry points.
 */
final class ClassicSnapshot {
    static int[] process(int[] input,int w,int h,int flags){
        final int mw=VideoDehazeProcessor.W,mh=VideoDehazeProcessor.H;
        byte[] bytes=new byte[mw*mh*4];
        for(int y=0;y<mh;y++)for(int x=0;x<mw;x++){
            int c=input[Math.min(h-1,(int)((mh-1-y+.5)*h/mh))*w+Math.min(w-1,(int)((x+.5)*w/mw))],i=(y*mw+x)*4;
            bytes[i]=(byte)(c>>16);bytes[i+1]=(byte)(c>>8);bytes[i+2]=(byte)c;bytes[i+3]=(byte)255;
        }
        VideoDehazeProcessor.Result map=VideoDehazeProcessor.process(bytes,null,flags);
        if(map.fallbackBits!=0)throw new IllegalStateException("CLASSIC stage fallback "+map.fallbackBits);
        int[] result=new int[input.length];double[] color=new double[3],local=new double[3],dcp=new double[3];
        double[] air={map.ar,map.ag,map.ab};
        for(int y=0;y<h;y++){
            if((y&15)==0)BcDehaze.checkCancel();
            for(int x=0;x<w;x++){
                double u=(x+.5)/w,v=1-(y+.5)/h;
                double t=Math.max(.26,sample(map.map,mw,mh,u,v,0)),sky=sample(map.map,mw,mh,u,v,1),lumMap=sample(map.map,mw,mh,u,v,2),detail=sample(map.map,mw,mh,u,v,3);
                double blend=Math.min(.96,1.02*(1-.91*sky));
                int[] adjacent={input[y*w+Math.min(w-1,x+2)],input[y*w+Math.max(0,x-2)],input[Math.min(h-1,y+2)*w+x],input[Math.max(0,y-2)*w+x]};
                int p=input[y*w+x];
                for(int c=0;c<3;c++){
                    int shift=16-c*8;color[c]=((p>>shift)&255)/255.;local[c]=0;
                    for(int near:adjacent)local[c]+=((near>>shift)&255)/1020.;
                    dcp[c]=color[c]+(clamp((color[c]-air[c])/t+air[c])-color[c])*blend;
                }
                double lum=.299*dcp[0]+.587*dcp[1]+.114*dcp[2];
                double tx=Math.max(0,Math.min(7,u*8-.5)),ty=Math.max(0,Math.min(5,v*6-.5));
                int x0=(int)tx,y0=(int)ty,x1=Math.min(7,x0+1),y1=Math.min(5,y0+1),bin=(int)Math.round(clamp(lum)*255);
                double ax=tx-x0,ay=ty-y0;
                double mapped=(1-ay)*((1-ax)*lut(map.lut,x0,y0,bin)+ax*lut(map.lut,x1,y0,bin))+ay*((1-ax)*lut(map.lut,x0,y1,bin)+ax*lut(map.lut,x1,y1,bin));
                double delta=Math.max(-.18,Math.min(.18,mapped-lum))*.39*blend*(1-sky);
                double darkness=Math.max(0,Math.min(1,(.58-lumMap)*1.7)),gamma=1-.28*darkness*(1-sky);
                double originalLum=.299*color[0]+.587*color[1]+.114*color[2],ft=Math.max(.55,1-(.23+.14*originalLum));
                int out=0xff000000;
                for(int c=0;c<3;c++){
                    double value=clamp(clamp(dcp[c]+delta)+Math.max(-.09,Math.min(.09,color[c]-local[c]))*.35*blend*detail);
                    if((flags&8)!=0)value=Math.pow(Math.max(.001,value),gamma);
                    if((flags&32)!=0){double weight=Math.min(.35,.30*detail*(1-sky));value=value*(1-weight)+clamp((color[c]-.84)/ft+.84)*weight;}
                    out|=((int)Math.round(clamp(value)*255))<<(16-c*8);
                }result[y*w+x]=out;
            }
        }return result;
    }
    private static double clamp(double v){return Math.max(0,Math.min(1,v));}
    private static double lut(byte[] data,int x,int y,int bin){return (data[((y*8+x)*256+bin)*4]&255)/255.;}
    private static double sample(byte[] a,int w,int h,double u,double v,int c){
        double px=Math.max(0,Math.min(w-1,u*w-.5)),py=Math.max(0,Math.min(h-1,v*h-.5));
        int x=(int)px,y=(int)py,x1=Math.min(w-1,x+1),y1=Math.min(h-1,y+1);double fx=px-x,fy=py-y;
        return ((1-fy)*((1-fx)*(a[(y*w+x)*4+c]&255)+fx*(a[(y*w+x1)*4+c]&255))+fy*((1-fx)*(a[(y1*w+x)*4+c]&255)+fx*(a[(y1*w+x1)*4+c]&255)))/255.;
    }
}
