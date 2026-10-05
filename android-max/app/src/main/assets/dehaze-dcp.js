'use strict';
// Adaptive Object DCP. Uses only structure already present in the source image.
// Stack: dark channel -> atmospheric light -> transmission -> guided refinement
// -> structure-aware local recovery. No sky mask and no generated/invented detail.
function dehazeStrongDCP(source,w,h,p={}){
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
  const strength=clamp01((p.strength??82)/100),maxMode=p.maxMode?1:0;
  const omega=.80+.09*strength+.025*maxMode;
  for(let i=0;i<n;i++)rawT[i]=Math.max(.02,1-omega*transmissionMin[i]);

  // Guided edge-preserving refinement of the transmission map.
  const rad=12,eps=.002,graySq=new Float32Array(n),grayT=new Float32Array(n);
  for(let i=0;i<n;i++){graySq[i]=gray[i]*gray[i];grayT[i]=gray[i]*rawT[i];}
  const meanI=meanBox(gray,rad),meanT=meanBox(rawT,rad);
  const meanII=meanBox(graySq,rad),meanIT=meanBox(grayT,rad);
  const a=new Float32Array(n),bb=new Float32Array(n);
  for(let i=0;i<n;i++){
    const va=Math.max(0,meanII[i]-meanI[i]*meanI[i]);
    a[i]=(meanIT[i]-meanI[i]*meanT[i])/(va+eps);
    bb[i]=meanT[i]-a[i]*meanI[i];
  }
  const meanA=meanBox(a,rad),meanB=meanBox(bb,rad);
  const smoothMean=meanBox(gray,17),smoothII=meanBox(graySq,17);
  const trans=new Float32Array(n),alpha=new Float32Array(n),objectMap=new Float32Array(n);
  for(let i=0;i<n;i++){
    const t=clamp01(meanA[i]*gray[i]+meanB[i]);
    trans[i]=t;
    const std=Math.sqrt(Math.max(0,smoothII[i]-smoothMean[i]*smoothMean[i]));
    const broad=Math.abs(gray[i]-smoothMean[i]);
    const texture=clamp01((std-.004)/.040);
    // Weak broad structure catches contours/textures that are barely visible
    // through haze without inventing any content.
    const weak=clamp01((broad-.003)/.034)*(1-.35*clamp01((std-.09)/.10));
    const haze=clamp01(1-t);
    const object=clamp01(Math.max(texture*.84,weak)*(.30+.70*haze));
    objectMap[i]=object;
    alpha[i]=clamp01(strength*(.46+.28*haze+.34*object*(.88+.12*maxMode)));
  }

  const out=new Uint8ClampedArray(source.length);
  for(let y=0;y<h;y++){
    const fy=Math.max(0,Math.min(ph-1,(y+.5)*ph/h-.5)),y0=Math.floor(fy),y1=Math.min(ph-1,y0+1),wy=fy-y0;
    for(let x=0;x<w;x++){
      const fx=Math.max(0,Math.min(pw-1,(x+.5)*pw/w-.5)),x0=Math.floor(fx),x1=Math.min(pw-1,x0+1),wx=fx-x0;
      const k00=y0*pw+x0,k10=y0*pw+x1,k01=y1*pw+x0,k11=y1*pw+x1;
      const interp=arr=>(arr[k00]*(1-wx)+arr[k10]*wx)*(1-wy)+(arr[k01]*(1-wx)+arr[k11]*wx)*wy;
      const t=interp(trans),blend=interp(alpha),object=interp(objectMap);
      const floor=Math.max(.20,.33-.07*object*strength-.03*maxMode*object);
      const tt=Math.max(t,floor),i=(y*w+x)*4;
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
