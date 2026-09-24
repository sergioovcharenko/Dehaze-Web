'use strict';
// Real CPU processing is isolated from the iPhone UI/rendering thread.
// Input: downscaled RGBA camera frame. Output: DCP/guided transmission map,
// sky mask and clipped local histogram LUT for the WebGL video shader.
const WIDTH=192,HEIGHT=108,N=WIDTH*HEIGHT,NX=8,NY=6;
let last=null,lastEpoch=-1;
const clamp=(x,lo=0,hi=1)=>Math.max(lo,Math.min(hi,x));
const byte=x=>Math.min(255,Math.max(0,Math.round(x*255)));
function minBox(a,r){
 const row=new Float32Array(N),out=new Float32Array(N);
 for(let y=0;y<HEIGHT;y++)for(let x=0;x<WIDTH;x++){
  let v=Infinity;
  for(let xx=Math.max(0,x-r);xx<=Math.min(WIDTH-1,x+r);xx++)v=Math.min(v,a[y*WIDTH+xx]);
  row[y*WIDTH+x]=v;
 }
 for(let y=0;y<HEIGHT;y++)for(let x=0;x<WIDTH;x++){
  let v=Infinity;
  for(let yy=Math.max(0,y-r);yy<=Math.min(HEIGHT-1,y+r);yy++)v=Math.min(v,row[yy*WIDTH+x]);
  out[y*WIDTH+x]=v;
 }
 return out;
}
function box(a,r){
 const stride=WIDTH+1,integral=new Float64Array((HEIGHT+1)*stride),out=new Float32Array(N);
 for(let y=1;y<=HEIGHT;y++){
  let sum=0;
  for(let x=1;x<=WIDTH;x++){sum+=a[(y-1)*WIDTH+x-1];integral[y*stride+x]=integral[(y-1)*stride+x]+sum;}
 }
 for(let y=0;y<HEIGHT;y++){
  const y0=Math.max(0,y-r),y1=Math.min(HEIGHT,y+r+1);
  for(let x=0;x<WIDTH;x++){
   const x0=Math.max(0,x-r),x1=Math.min(WIDTH,x+r+1);
   out[y*WIDTH+x]=(integral[y1*stride+x1]-integral[y0*stride+x1]-
       integral[y1*stride+x0]+integral[y0*stride+x0])/((y1-y0)*(x1-x0));
  }
 }
 return out;
}
function clahe(gray){
 const lut=new Uint8Array(256*NX*NY*4),tw=Math.ceil(WIDTH/NX),th=Math.ceil(HEIGHT/NY);
 for(let ty=0;ty<NY;ty++)for(let tx=0;tx<NX;tx++){
  const hist=new Uint16Array(256),x0=tx*tw,x1=Math.min(WIDTH,x0+tw),
       y0=ty*th,y1=Math.min(HEIGHT,y0+th);
  let n=0,sum=0,sumSq=0;
  for(let y=y0;y<y1;y++)for(let x=x0;x<x1;x++){
   const v=gray[y*WIDTH+x];hist[byte(v)]++;sum+=v;sumSq+=v*v;n++;
  }
  const sigma=Math.sqrt(Math.max(0,sumSq/Math.max(1,n)-(sum/Math.max(1,n))**2));
  const tex=clamp((sigma-.015)/.085);
  const maxBin=Math.max(1,Math.round(2.05*n/256));
  let excess=0;
  for(let k=0;k<256;k++)if(hist[k]>maxBin){excess+=hist[k]-maxBin;hist[k]=maxBin;}
  let acc=0;
  const row=ty*NX+tx;
  for(let k=0;k<256;k++){
   acc+=hist[k]+excess/256;
   const value=clamp(k/255+(acc/Math.max(1,n)-k/255)*(.35+.65*tex));
   const p=(row*256+k)*4;lut[p]=lut[p+1]=lut[p+2]=byte(value);lut[p+3]=255;
  }
 }
 return lut;
}
function calculate(frame,epoch){
 if(frame.length!==N*4)throw Error('Invalid frame length');
 const red=new Float32Array(N),green=new Float32Array(N),blue=new Float32Array(N),
       gray=new Float32Array(N),mins=new Float32Array(N),hist=new Uint16Array(256);
 let sum=0,sat=0,edge=0,edgeCount=0;
 for(let y=0;y<HEIGHT;y++)for(let x=0;x<WIDTH;x++){
  const i=y*WIDTH+x,p=i*4,r=frame[p]/255,g=frame[p+1]/255,b=frame[p+2]/255;
  red[i]=r;green[i]=g;blue[i]=b;
  const lum=.299*r+.587*g+.114*b;
  gray[i]=lum;mins[i]=Math.min(r,g,b);hist[byte(lum)]++;
  sum+=lum;sat+=Math.max(r,g,b)-mins[i];
  if(x>0){edge+=Math.abs(lum-gray[i-1]);edgeCount++;}
  if(y>0){edge+=Math.abs(lum-gray[i-WIDTH]);edgeCount++;}
 }
 const dark=minBox(mins,4),dhist=new Uint16Array(256);
 for(let i=0;i<N;i++)dhist[byte(dark[i])]++;
 const need=Math.max(3,Math.floor(N*.0015));
 let c=0,cutoff=255;
 for(let j=255;j>=0;j--){c+=dhist[j];if(c>=need){cutoff=j;break;}}
 let best=0,bright=-1;
 for(let i=0;i<N;i++)if(byte(dark[i])>=cutoff){
   const value=red[i]+green[i]+blue[i];
   if(value>bright){best=i;bright=value;}
 }
 let ar=clamp(red[best],.42,1),ag=clamp(green[best],.42,1),ab=clamp(blue[best],.42,1);
 const norm=new Float32Array(N);
 for(let i=0;i<N;i++)norm[i]=Math.min(red[i]/ar,green[i]/ag,blue[i]/ab);
 const dcp=minBox(norm,4),raw=new Float32Array(N),sq=new Float32Array(N),cross=new Float32Array(N);
 for(let i=0;i<N;i++){
  raw[i]=clamp(1-.84*dcp[i],.1,1);
  sq[i]=gray[i]*gray[i];cross[i]=gray[i]*raw[i];
 }
 const meanI=box(gray,7),meanP=box(raw,7),meanII=box(sq,7),meanIP=box(cross,7);
 const a=new Float32Array(N),b=new Float32Array(N);
 for(let i=0;i<N;i++){
  a[i]=(meanIP[i]-meanI[i]*meanP[i])/(Math.max(0,meanII[i]-meanI[i]*meanI[i])+.0018);
  b[i]=meanP[i]-a[i]*meanI[i];
 }
 const meanA=box(a,7),meanB=box(b,7),localI=box(gray,7),localSq=box(sq,7);
 c=0;let p10=0,p90=255;
 for(let i=0;i<256;i++){c+=hist[i];if(c>=N*.1){p10=i;break;}}
 c=0;for(let i=0;i<256;i++){c+=hist[i];if(c>=N*.9){p90=i;break;}}
 const contrast=clamp((105-(p90-p10))/100),
       texture=clamp((18-edge/Math.max(1,edgeCount)*255)/18),
       muted=clamp((48-sat/N*255)/48);
 let haze=.45*contrast+.35*texture+.2*muted;
 const map=new Uint8Array(N*4);
 for(let y=0;y<HEIGHT;y++)for(let x=0;x<WIDTH;x++){
  const i=y*WIDTH+x,p=i*4,mean=localI[i];
  const transmission=clamp(meanA[i]*gray[i]+meanB[i],.24,1);
  const std=Math.sqrt(Math.max(0,localSq[i]-mean*mean));
  const detail=clamp((std-.009)/.078);
  // Canvas pixels are top to bottom. WebGL upload uses UNPACK_FLIP_Y_WEBGL
  // so shader texture's lower-left samples still correspond to lower image.
  const upper=clamp((.62-y/HEIGHT)/.36);
  const light=clamp((gray[i]-.57)/.32);
  const sky=upper*light*(1-clamp((std-.018)/.09));
  map[p]=byte(transmission);map[p+1]=byte(sky);
  map[p+2]=byte(mean);map[p+3]=byte(detail);
 }
 const lut=clahe(gray);
 if(last&&epoch===lastEpoch&&Math.abs(sum/N-last.mean)<.15){
   for(let i=0;i<map.length;i++)map[i]=Math.round(last.map[i]*.55+map[i]*.45);
   for(let i=0;i<lut.length;i+=4){
    const v=Math.round(last.lut[i]*.55+lut[i]*.45);
    lut[i]=lut[i+1]=lut[i+2]=v;
   }
   ar=.55*last.ar+.45*ar;
   ag=.55*last.ag+.45*ag;
   ab=.55*last.ab+.45*ab;
   haze=.55*last.haze+.45*haze;
 }
 lastEpoch=epoch;
 last={map:map.slice(),lut:lut.slice(),ar,ag,ab,haze,mean:sum/N};
 return{map,lut,ar,ag,ab,haze};
}
self.onmessage=e=>{
 const {frame,epoch}=e.data;
 try{
  const start=performance.now(),r=calculate(new Uint8Array(frame),epoch);
  self.postMessage({epoch,map:r.map.buffer,lut:r.lut.buffer,air:[r.ar,r.ag,r.ab],
    haze:r.haze,elapsed:Math.round(performance.now()-start)},[r.map.buffer,r.lut.buffer]);
 }catch(error){self.postMessage({epoch,error:String(error.message||error)});}
};
