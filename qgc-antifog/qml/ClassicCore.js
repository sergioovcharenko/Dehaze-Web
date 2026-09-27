.pragma library
// Port of LAB4 VideoDehazeProcessor. Input and map rows are top-to-bottom.
var W=192,H=108,N=W*H,TX=8,TY=6;
function clamp(x,a,b){return Math.max(a,Math.min(b,x));}
function byteValue(x){return clamp(Math.round(x*255),0,255);}
function minBox(input,rad){
    var row=new Float32Array(N),out=new Float32Array(N),q=new Int32Array(W);
    for(var y=0;y<H;y++){
        var head=0,tail=0,next=0;
        for(var x=0;x<W;x++){
            var end=x+rad;if(end>=W)end=W-1;
            while(next<=end){
                while(tail>head&&input[y*W+q[tail-1]]>=input[y*W+next])tail--;
                q[tail++]=next++;
            }
            while(q[head]<x-rad)head++;
            row[y*W+x]=input[y*W+q[head]];
        }
    }
    for(x=0;x<W;x++){
        head=0;tail=0;next=0;
        for(y=0;y<H;y++){
            end=y+rad;if(end>=H)end=H-1;
            while(next<=end){
                while(tail>head&&row[q[tail-1]*W+x]>=row[next*W+x])tail--;
                q[tail++]=next++;
            }
            while(q[head]<y-rad)head++;
            out[y*W+x]=row[q[head]*W+x];
        }
    }
    return out;
}
function box(src,r){
    var stride=W+1,integ=new Float64Array((H+1)*stride),out=new Float32Array(N);
    for(var y=1;y<=H;y++){
        var sum=0;
        for(var x=1;x<=W;x++){sum+=src[(y-1)*W+x-1];integ[y*stride+x]=integ[(y-1)*stride+x]+sum;}
    }
    for(y=0;y<H;y++)for(x=0;x<W;x++){
        var t=Math.max(0,y-r),b=Math.min(H,y+r+1),l=Math.max(0,x-r),rr=Math.min(W,x+r+1);
        out[y*W+x]=(integ[b*stride+rr]-integ[t*stride+rr]-integ[b*stride+l]+integ[t*stride+l])/((rr-l)*(b-t));
    }
    return out;
}
function clahe(gray){
    var lut=new Array(256*48*4),tw=Math.ceil(W/TX),th=Math.ceil(H/TY);
    for(var ty=0;ty<TY;ty++)for(var tx=0;tx<TX;tx++){
        var hist=new Int32Array(256),count=0,sum=0,sq=0;
        for(var y=ty*th;y<Math.min(H,(ty+1)*th);y++)for(var x=tx*tw;x<Math.min(W,(tx+1)*tw);x++){
            var v=gray[y*W+x];hist[byteValue(v)]++;count++;sum+=v;sq+=v*v;
        }
        var sigma=Math.sqrt(Math.max(0,sq/count-(sum/count)*(sum/count)));
        var texture=clamp((sigma-.015)/.085,0,1),clip=Math.max(1,Math.round(2.05*count/256)),excess=0;
        for(var k=0;k<256;k++)if(hist[k]>clip){excess+=hist[k]-clip;hist[k]=clip;}
        var cdf=0;
        for(k=0;k<256;k++){
            cdf+=hist[k]+excess/256;
            var value=byteValue(k/255+(cdf/count-k/255)*(.35+.65*texture)),p=((ty*TX+tx)*256+k)*4;
            lut[p]=lut[p+1]=lut[p+2]=value;lut[p+3]=255;
        }
    }
    return lut;
}
function process(input,previous,strength){
    var started=Date.now();
    if(!input||input.length!==N*4)throw new Error('Unexpected analysis frame size');
    var red=new Float32Array(N),green=new Float32Array(N),blue=new Float32Array(N),gray=new Float32Array(N),mins=new Float32Array(N);
    var hist=new Int32Array(256),sumLuma=0,sumSat=0,edges=0,edgeCount=0;
    for(var y=0;y<H;y++)for(var x=0;x<W;x++){
        var i=y*W+x,p=i*4,r=input[p]/255,g=input[p+1]/255,b=input[p+2]/255;
        red[i]=r;green[i]=g;blue[i]=b;
        var v=.299*r+.587*g+.114*b;gray[i]=v;mins[i]=Math.min(r,g,b);hist[byteValue(v)]++;
        sumLuma+=v;sumSat+=Math.max(r,g,b)-mins[i];
        if(x>0){edges+=Math.abs(v-gray[i-1]);edgeCount++;}
        if(y>0){edges+=Math.abs(v-gray[i-W]);edgeCount++;}
    }
    var dark=minBox(mins,4),dh=new Int32Array(256);
    for(i=0;i<N;i++)dh[byteValue(dark[i])]++;
    var needed=Math.max(3,Math.floor(N*.0015)),cutoff=255,acc=0;
    for(i=255;i>=0;i--){acc+=dh[i];if(acc>=needed){cutoff=i;break;}}
    // Match the original GL bottom-to-top tie-breaking order.
    var best=0,brightest=-1;
    for(y=H-1;y>=0;y--)for(x=0;x<W;x++){
        i=y*W+x;
        if(byteValue(dark[i])>=cutoff){v=red[i]+green[i]+blue[i];if(v>brightest){brightest=v;best=i;}}
    }
    var ar=clamp(red[best],.42,1),ag=clamp(green[best],.42,1),ab=clamp(blue[best],.42,1);
    var norm=new Float32Array(N);
    for(i=0;i<N;i++)norm[i]=Math.min(red[i]/ar,green[i]/ag,blue[i]/ab);
    var small=minBox(norm,4),large=minBox(norm,10),raw=new Float32Array(N),sq=new Float32Array(N),ip=new Float32Array(N);
    for(i=0;i<N;i++){raw[i]=clamp(1-.84*(.78*small[i]+.22*large[i]),.1,1);sq[i]=gray[i]*gray[i];ip[i]=gray[i]*raw[i];}
    var mean=box(gray,7),meanSq=box(sq,7),meanP=box(raw,7),meanIP=box(ip,7),aa=new Float32Array(N),bb=new Float32Array(N);
    for(i=0;i<N;i++){
        var variance=Math.max(0,meanSq[i]-mean[i]*mean[i]);
        aa[i]=(meanIP[i]-mean[i]*meanP[i])/(variance+.0018);bb[i]=meanP[i]-aa[i]*mean[i];
    }
    var meanA=box(aa,7),meanB=box(bb,7),map=new Array(N*4);
    for(y=0;y<H;y++)for(x=0;x<W;x++){
        i=y*W+x;p=i*4;
        var sigma=Math.sqrt(Math.max(0,meanSq[i]-mean[i]*mean[i]));
        var top=clamp(((H-1-y)/H-.38)/.36,0,1),light=clamp((gray[i]-.57)/.32,0,1);
        map[p]=byteValue(clamp(meanA[i]*gray[i]+meanB[i],.24,1));
        map[p+1]=byteValue(top*light*(1-clamp((sigma-.018)/.09,0,1)));
        map[p+2]=byteValue(mean[i]);map[p+3]=byteValue(clamp((sigma-.009)/.078,0,1));
    }
    var p10=0,p90=255;acc=0;
    for(i=0;i<256;i++){acc+=hist[i];if(acc>=N*.1){p10=i;break;}}
    acc=0;for(i=0;i<256;i++){acc+=hist[i];if(acc>=N*.9){p90=i;break;}}
    var haze=clamp((105-(p90-p10))/100,0,1)*.45+clamp((18-edges/Math.max(1,edgeCount)*255)/18,0,1)*.35+clamp((48-sumSat/N*255)/48,0,1)*.2;
    var meanLuma=sumLuma/N,lut=clahe(gray);
    var target=.22+.64*haze;if(meanLuma*255<65)target=.22+(target-.22)*.65;
    var nextStrength=clamp((typeof strength==='number'?strength:.6)*.72+target*.28,0,1);
    if(previous&&Math.abs(meanLuma-previous.mean)<.15){
        for(i=0;i<map.length;i++)map[i]=Math.round(previous.map[i]*.55+map[i]*.45);
        for(i=0;i<lut.length;i+=4)lut[i]=lut[i+1]=lut[i+2]=Math.round(previous.lut[i]*.55+lut[i]*.45);
        ar=previous.ar*.55+ar*.45;ag=previous.ag*.55+ag*.45;ab=previous.ab*.55+ab*.45;haze=previous.haze*.55+haze*.45;
    }
    return {map:map,lut:lut,ar:ar,ag:ag,ab:ab,haze:haze,mean:meanLuma,strength:nextStrength,ms:Date.now()-started};
}
