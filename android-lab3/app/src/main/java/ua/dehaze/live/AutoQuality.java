package ua.dehaze.live;

/** Conservative, untrained image-quality heuristic. Scores are not probabilities. */
final class AutoQuality {
    static double score(int[] original,int[] result,int w,int h){
        if(w<1||h<1||original==null||result==null||(long)w*h!=original.length||result.length!=original.length)throw new IllegalArgumentException("Image geometry");
        int n=original.length;double[] a=new double[n],b=new double[n];double sa=0,sb=0,saa=0,sbb=0,colorShift=0;int newClip=0;
        for(int i=0;i<n;i++){
            int ar=(original[i]>>16)&255,ag=(original[i]>>8)&255,ab=original[i]&255;
            int br=(result[i]>>16)&255,bg=(result[i]>>8)&255,bb=result[i]&255;
            a[i]=(.299*ar+.587*ag+.114*ab)/255.;b[i]=(.299*br+.587*bg+.114*bb)/255.;
            sa+=a[i];sb+=b[i];saa+=a[i]*a[i];sbb+=b[i]*b[i];
            int[] old={ar,ag,ab},out={br,bg,bb};
            for(int c=0;c<3;c++){if((out[c]<=2||out[c]>=253)&&old[c]>2&&old[c]<253)newClip++;colorShift+=Math.abs((out[c]/255.-b[i])-(old[c]/255.-a[i]));}
        }
        double shift=Math.abs(sb-sa)/n,clipping=newClip/(3.*n);
        if(clipping>.08||shift>.20)return Double.NEGATIVE_INFINITY;
        double sdA=Math.sqrt(Math.max(0,saa/n-(sa/n)*(sa/n))),sdB=Math.sqrt(Math.max(0,sbb/n-(sb/n)*(sb/n)));
        double edgeA=0,edgeB=0,noise=0;int edges=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int i=y*w+x;
            if(x>0){double da=Math.abs(a[i]-a[i-1]),db=Math.abs(b[i]-b[i-1]);if(da>=.012){edgeA+=da;edgeB+=db;edges++;}else noise+=Math.max(0,db-da);}
            if(y>0){double da=Math.abs(a[i]-a[i-w]),db=Math.abs(b[i]-b[i-w]);if(da>=.012){edgeA+=da;edgeB+=db;edges++;}else noise+=Math.max(0,db-da);}
        }
        edgeA/=Math.max(1,edges);edgeB/=Math.max(1,edges);
        // No contrast reward for inventing texture in an originally flat image.
        double contrast=sdA<.015?0:12*Math.log(Math.min(2,(sdB+.02)/(sdA+.02)))/Math.log(2);
        double detail=18*Math.log(Math.min(2,(edgeB+.005)/(edgeA+.005)))/Math.log(2);
        return contrast+detail-120*clipping-40*shift-45*colorShift/(3*n)-180*noise/n;
    }
}
