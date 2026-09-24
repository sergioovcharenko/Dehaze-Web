'use strict';
// DCP with guided transmission, sky protection and texture-adaptive blending.
// All image processing runs locally; photographs are never uploaded.
function dehazeStrongDCP(source,w,h,p){
  const clamp01=v=>Math.max(0,Math.min(1,v));
  const side=720,scale=Math.min(1,side/Math.max(w,h));
  const pw=Math.max(1,Math.round(w*scale)),ph=Math.max(1,Math.round(h*scale)),n=pw*ph;
  const r=new Float32Array(n),g=new Float32Array(n),b=new Float32Array(n);
  const gray=new Float32Array(n),mn=new Float32Array(n);
  for(let y=0;y<ph;y++){
    const sy=Math.min(h-1,Math.floor((y+.5)*h/ph));
    for(let x=0;x<pw;x++){
      const sx=Math.min(w-1,Math.floor((x+.5)*w/pw)),i=(sy*w+sx)*4,k=y*pw+x;
      const rr=source[i]/255,gg=source[i+1]/255,bb=source[i+2]/255;
      r[k]=rr;g[k]=gg;b[k]=bb;gray[k]=.299*rr+.587*gg+.114*bb;mn[k]=Math.min(rr,gg,bb);
    }
  }
  function meanBox(a,rad){
    const iw=pw+1,integral=new Float64Array(iw*(ph+1)),out=new Float32Array(n);
    for(let y=1;y<=ph;y++){
      let row=0;
      for(let x=1;x<=pw;x++){
        row+=a[(y-1)*pw+(x-1)];
        integral[y*iw+x]=integral[(y-1)*iw+x]+row;
      }
    }
    for(let y=0;y<ph;y++){
      const y0=Math.max(0,y-rad),y1=Math.min(ph,y+rad+1);
      for(let x=0;x<pw;x++){
        const x0=Math.max(0,x-rad),x1=Math.min(pw,x+rad+1);
        out[y*pw+x]=(integral[y1*iw+x1]-integral[y0*iw+x1]-integral[y1*iw+x0]+integral[y0*iw+x0])/((x1-x0)*(y1-y0));
      }
    }
    return out;
  }
  function minBox(a,rad){
    const row=new Float32Array(n),out=new Float32Array(n);
    for(let y=0;y<ph;y++){
      const line=y*pw;
      for(let x=0;x<pw;x++){
        let v=Infinity;
        for(let xx=Math.max(0,x-rad);xx<=Math.min(pw-1,x+rad);xx++)v=Math.min(v,a[line+xx]);
        row[line+x]=v;
      }
    }
    for(let y=0;y<ph;y++)for(let x=0;x<pw;x++){
      let v=Infinity;
      for(let yy=Math.max(0,y-rad);yy<=Math.min(ph-1,y+rad);yy++)v=Math.min(v,row[yy*pw+x]);
      out[y*pw+x]=v;
    }
    return out;
  }
  const dark=minBox(mn,5),hist=new Uint32Array(256);
  for(let i=0;i<n;i++)hist[Math.min(255,Math.floor(dark[i]*255))]++;
  const target=Math.max(3,Math.round(n*.001));let count=0,cutoff=255;
  for(let k=255;k>=0;k--){count+=hist[k];if(count>=target){cutoff=k;break;}}
  const candidates=[];
  for(let i=0;i<n;i++){
    if(Math.floor(dark[i]*255)>=cutoff)candidates.push([i,r[i]+g[i]+b[i]]);
    if(candidates.length>=5000)break;
  }
  candidates.sort((a,b)=>b[1]-a[1]);
  const used=Math.max(1,Math.round(candidates.length*.3));
  let Ar=0,Ag=0,Ab=0;
  for(let j=0;j<used;j++){const k=candidates[j][0];Ar+=r[k];Ag+=g[k];Ab+=b[k];}
  Ar=Math.max(.35,Ar/used);Ag=Math.max(.35,Ag/used);Ab=Math.max(.35,Ab/used);
  const normMin=new Float32Array(n);
  for(let i=0;i<n;i++)normMin[i]=Math.min(r[i]/Ar,g[i]/Ag,b[i]/Ab);
  const transmissionMin=minBox(normMin,5),rawT=new Float32Array(n);
  const omega=.83;
  for(let i=0;i<n;i++)rawT[i]=Math.max(.02,1-omega*transmissionMin[i]);
  // Guided filter: preserves building/forest boundaries better than box blur.
  const rad=12,eps=.002,graySq=new Float32Array(n),grayT=new Float32Array(n);
  for(let i=0;i<n;i++){graySq[i]=gray[i]*gray[i];grayT[i]=gray[i]*rawT[i];}
  const meanI=meanBox(gray,rad),meanT=meanBox(rawT,rad);
  const meanII=meanBox(graySq,rad),meanIT=meanBox(grayT,rad);
  const a=new Float32Array(n),bb=new Float32Array(n);
  for(let i=0;i<n;i++){const va=Math.max(0,meanII[i]-meanI[i]*meanI[i]);a[i]=(meanIT[i]-meanI[i]*meanT[i])/(va+eps);bb[i]=meanT[i]-a[i]*meanI[i];}
  const meanA=meanBox(a,rad),meanB=meanBox(bb,rad);
  const smoothMean=meanBox(gray,17),smoothII=meanBox(graySq,17);
  const trans=new Float32Array(n),alpha=new Float32Array(n);
  const skyProtect=clamp01((p.skyProtect??85)/100),strength=clamp01((p.strength??83)/100);
  for(let y=0;y<ph;y++)for(let x=0;x<pw;x++){
    const i=y*pw+x,yy=y/ph;
    trans[i]=clamp01(meanA[i]*gray[i]+meanB[i]);
    const std=Math.sqrt(Math.max(0,smoothII[i]-smoothMean[i]*smoothMean[i]));
    const texture=clamp01((std-.003)/.028);
    const flat=clamp01((std-.004)/.027);
    const bright=clamp01((gray[i]-.56)/.25);
    const top=clamp01((.83-yy)/.7);
    const sky=bright*top*(1-flat);
    alpha[i]=strength*(1-skyProtect*sky)*(.22+.78*texture);
  }
  const out=new Uint8ClampedArray(source.length),t0=.32;
  // Bilinear interpolation of coarse transmission/alpha prevents block boundaries.
  for(let y=0;y<h;y++){
    const fy=Math.max(0,Math.min(ph-1,(y+.5)*ph/h-.5)),y0=Math.floor(fy),y1=Math.min(ph-1,y0+1),wy=fy-y0;
    for(let x=0;x<w;x++){
      const fx=Math.max(0,Math.min(pw-1,(x+.5)*pw/w-.5)),x0=Math.floor(fx),x1=Math.min(pw-1,x0+1),wx=fx-x0;
      const k00=y0*pw+x0,k10=y0*pw+x1,k01=y1*pw+x0,k11=y1*pw+x1;
      const t=(trans[k00]*(1-wx)+trans[k10]*wx)*(1-wy)+(trans[k01]*(1-wx)+trans[k11]*wx)*wy;
      const blend=(alpha[k00]*(1-wx)+alpha[k10]*wx)*(1-wy)+(alpha[k01]*(1-wx)+alpha[k11]*wx)*wy;
      const tt=Math.max(t,t0),i=(y*w+x)*4;
      const rr=source[i]/255,gg=source[i+1]/255,bbb=source[i+2]/255;
      const recoveredR=clamp01((rr-Ar)/tt+Ar),recoveredG=clamp01((gg-Ag)/tt+Ag),recoveredB=clamp01((bbb-Ab)/tt+Ab);
      out[i]=255*(rr*(1-blend)+recoveredR*blend);
      out[i+1]=255*(gg*(1-blend)+recoveredG*blend);
      out[i+2]=255*(bbb*(1-blend)+recoveredB*blend);
      out[i+3]=source[i+3];
    }
  }
  return out;
}
