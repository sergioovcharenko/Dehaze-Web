'use strict';
/* Offline low-resolution video model: dark channel + edge-preserving guided
   transmission, 8x6 CLAHE and sky protection. Runs off the UI thread. */
const W=128,H=72,N=W*H,TILES_X=8,TILES_Y=6;
let previous=null;
const clamp=(x,a=0,b=1)=>Math.max(a,Math.min(b,x));
const b8=x=>Math.round(clamp(x)*255);
function minBox(src,radius){
  const horizontal=new Float32Array(N),out=new Float32Array(N);
  for(let y=0;y<H;y++)for(let x=0;x<W;x++){
    let min=Infinity;
    for(let xx=Math.max(0,x-radius);xx<=Math.min(W-1,x+radius);xx++)
      min=Math.min(min,src[y*W+xx]);
    horizontal[y*W+x]=min;
  }
  for(let y=0;y<H;y++)for(let x=0;x<W;x++){
    let min=Infinity;
    for(let yy=Math.max(0,y-radius);yy<=Math.min(H-1,y+radius);yy++)
      min=Math.min(min,horizontal[yy*W+x]);
    out[y*W+x]=min;
  }
  return out;
}
function meanBox(src,radius){
  const stride=W+1,integral=new Float64Array((H+1)*stride),out=new Float32Array(N);
  for(let y=1;y<=H;y++){
    let run=0;
    for(let x=1;x<=W;x++){
      run+=src[(y-1)*W+x-1];
      integral[y*stride+x]=integral[(y-1)*stride+x]+run;
    }
  }
  for(let y=0;y<H;y++){
    const top=Math.max(0,y-radius),bottom=Math.min(H,y+radius+1);
    for(let x=0;x<W;x++){
      const left=Math.max(0,x-radius),right=Math.min(W,x+radius+1);
      const sum=integral[bottom*stride+right]-integral[top*stride+right]
          -integral[bottom*stride+left]+integral[top*stride+left];
      out[y*W+x]=sum/((right-left)*(bottom-top));
    }
  }
  return out;
}
function run(pixels){
  if(pixels.length!==N*4)throw Error('Розмір кадру');
  const r=new Float32Array(N),g=new Float32Array(N),b=new Float32Array(N),
    gray=new Float32Array(N),minRgb=new Float32Array(N),hist=new Uint32Array(256);
  let lumaSum=0,sat=0,edge=0,edgeN=0;
  for(let y=0;y<H;y++)for(let x=0;x<W;x++){
    const i=y*W+x,k=i*4,R=pixels[k]/255,G=pixels[k+1]/255,B=pixels[k+2]/255;
    r[i]=R;g[i]=G;b[i]=B;
    const Y=.299*R+.587*G+.114*B;
    gray[i]=Y;minRgb[i]=Math.min(R,G,B);hist[b8(Y)]++;
    lumaSum+=Y;sat+=Math.max(R,G,B)-minRgb[i];
    if(x){edge+=Math.abs(Y-gray[i-1]);edgeN++;}
    if(y){edge+=Math.abs(Y-gray[i-W]);edgeN++;}
  }
  const dark=minBox(minRgb,3),dhist=new Uint32Array(256);
  for(const v of dark)dhist[b8(v)]++;
  let threshold=255,sum=0;
  for(let v=255;v>=0;v--){sum+=dhist[v];if(sum>=Math.max(3,N*.002)){threshold=v;break;}}
  let best=0,bright=-Infinity;
  for(let i=0;i<N;i++)if(b8(dark[i])>=threshold){
    const val=r[i]+g[i]+b[i];
    if(val>bright){bright=val;best=i;}
  }
  let air=[Math.max(.42,r[best]),Math.max(.42,g[best]),Math.max(.42,b[best])];
  const normal=new Float32Array(N),raw=new Float32Array(N),
        g2=new Float32Array(N),gt=new Float32Array(N);
  for(let i=0;i<N;i++){
    normal[i]=Math.min(r[i]/air[0],g[i]/air[1],b[i]/air[2]);
  }
  const coarse=minBox(normal,3);
  for(let i=0;i<N;i++){
    raw[i]=clamp(1-.84*coarse[i],.05,1);
    g2[i]=gray[i]*gray[i];
    gt[i]=gray[i]*raw[i];
  }
  const mi=meanBox(gray,5),mp=meanBox(raw,5),mii=meanBox(g2,5),mip=meanBox(gt,5);
  const a=new Float32Array(N),bb=new Float32Array(N);
  for(let i=0;i<N;i++){
    a[i]=(mip[i]-mi[i]*mp[i])/(Math.max(0,mii[i]-mi[i]*mi[i])+.0018);
    bb[i]=mp[i]-a[i]*mi[i];
  }
  const ma=meanBox(a,5),mb=meanBox(bb,5),
        local=meanBox(gray,5),localSq=meanBox(g2,5);
  const map=new Uint8Array(N*4);
  for(let y=0;y<H;y++)for(let x=0;x<W;x++){
    const i=y*W+x,p=i*4,
        t=clamp(ma[i]*gray[i]+mb[i],.25,1),
        sigma=Math.sqrt(Math.max(0,localSq[i]-local[i]*local[i])),
        detail=clamp((sigma-.008)/.079),
        sky=clamp((.62-y/H)/.38)*clamp((gray[i]-.56)/.29)*
             (1-clamp((sigma-.014)/.095));
    map[p]=b8(t);map[p+1]=b8(sky);map[p+2]=b8(local[i]);map[p+3]=b8(detail);
  }
  const lut=new Uint8Array(256*TILES_X*TILES_Y*4),
        tw=Math.ceil(W/TILES_X),th=Math.ceil(H/TILES_Y);
  for(let ty=0;ty<TILES_Y;ty++)for(let tx=0;tx<TILES_X;tx++){
    const h=new Float32Array(256),x1=Math.min(W,(tx+1)*tw),
      y1=Math.min(H,(ty+1)*th),x0=tx*tw,y0=ty*th;
    let count=0,total=0,total2=0;
    for(let y=y0;y<y1;y++)for(let x=x0;x<x1;x++){
      const lum=gray[y*W+x];h[b8(lum)]++;
      count++;total+=lum;total2+=lum*lum;
    }
    const std=Math.sqrt(Math.max(0,total2/Math.max(1,count)-(total/Math.max(1,count))**2));
    const texture=clamp((std-.015)/.085),clip=Math.max(1,2.2*count/256);
    let excess=0;
    for(let k=0;k<256;k++)if(h[k]>clip){excess+=h[k]-clip;h[k]=clip;}
    let cdf=0;const row=ty*TILES_X+tx;
    for(let k=0;k<256;k++){
      cdf+=h[k]+excess/256;
      const raw=cdf/Math.max(1,count);
      const balanced=k/255+(raw-k/255)*(.36+.64*texture);
      const p=(row*256+k)*4,v=b8(balanced);
      lut[p]=lut[p+1]=lut[p+2]=v;lut[p+3]=255;
    }
  }
  sum=0;let p10=0,p90=255;
  for(let i=0;i<256;i++){sum+=hist[i];if(sum>=N*.1){p10=i;break;}}
  sum=0;for(let i=0;i<256;i++){sum+=hist[i];if(sum>=N*.9){p90=i;break;}}
  let haze=clamp((105-(p90-p10))/100)*.45+
     clamp((18-edge/Math.max(1,edgeN)*255)/18)*.35+
     clamp((48-sat/N*255)/48)*.2;
  let mean=lumaSum/N;
  if(previous&&Math.abs(mean-previous.mean)<.13){
    const older=.58,newer=1-older;
    for(let i=0;i<map.length;i++)
      map[i]=Math.round(previous.map[i]*older+map[i]*newer);
    for(let i=0;i<lut.length;i+=4)
      lut[i]=lut[i+1]=lut[i+2]=Math.round(previous.lut[i]*older+lut[i]*newer);
    air=air.map((v,i)=>previous.air[i]*older+v*newer);
    haze=previous.haze*older+haze*newer;
  }
  previous={map,lut,air,haze,mean};
  return {map,lut,air,haze};
}
self.onmessage=event=>{
  if(event.data.reset){previous=null;return;}
  const {pixels,epoch}=event.data;
  try{
    const result=run(new Uint8ClampedArray(pixels));
    // Keep copies for smoothing before the transferred buffers detach.
    previous={map:new Uint8Array(result.map),lut:new Uint8Array(result.lut),
      air:result.air,haze:result.haze,mean:previous.mean};
    self.postMessage({type:'map',epoch,air:result.air,haze:result.haze,
      map:result.map.buffer,lut:result.lut.buffer},
      [result.map.buffer,result.lut.buffer]);
  }catch(e){self.postMessage({type:'error',epoch,message:String(e)});}
};