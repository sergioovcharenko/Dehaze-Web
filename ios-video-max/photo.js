'use strict';
(()=>{
const $=id=>document.getElementById(id);
const clamp=(v,a=0,b=255)=>Math.max(a,Math.min(b,v));
const original=$('before'),result=$('after');
const originalContext=original.getContext('2d',{willReadFrequently:true}),
      resultContext=result.getContext('2d',{willReadFrequently:true});
let picture=null,imageUrl=null,processed=false,busy=false,variant='max',auto=85;
const inputs=['strength','sky','detail','noise'];
function setStatus(msg){$('status').textContent=msg;}
function placeholder(canvas,context,msg){
 canvas.width=640;canvas.height=360;
 context.fillStyle='#23272a';context.fillRect(0,0,640,360);
 context.fillStyle='#aab9b2';context.textAlign='center';
 context.font='18px sans-serif';context.fillText(msg,320,180);
}
placeholder(original,originalContext,'ОРИГІНАЛ');
placeholder(result,resultContext,'ПІСЛЯ ОБРОБКИ');
$('back').onclick=()=>{
 location.href='./index.html';
};
const open=()=>$('file').click();
$('headerOpen').onclick=open;$('open').onclick=open;
function updateLabels(){
 for(const id of inputs)$(id+'Out').textContent=$(id).value+'%';
 if(!$('manual').checked){
   $('strengthOut').textContent=auto+'% AUTO';
 }
}
inputs.forEach(id=>$(id).addEventListener('input',updateLabels));
function setMode(value){
 variant=value;
 $('maxMode').classList.toggle('active',variant==='max');
 $('balancedMode').classList.toggle('active',variant==='balanced');
 if(picture){
   estimateImage(picture);
   setStatus('Режим '+(variant==='max'?'AUTO MAX':'AUTO')+' вибрано. Натисни Обробити.');
 }
}
$('maxMode').onclick=()=>setMode('max');
$('balancedMode').onclick=()=>setMode('balanced');
$('manual').addEventListener('change',()=>{
 $('strength').disabled=!$('manual').checked;
 if($('manual').checked)$('strength').value=String(auto);
 updateLabels();
});
function estimateImage(img){
 const c=document.createElement('canvas');c.width=256;c.height=144;
 const ctx=c.getContext('2d',{willReadFrequently:true});
 ctx.drawImage(img,0,0,c.width,c.height);
 const d=ctx.getImageData(0,0,c.width,c.height).data;
 const hist=new Uint32Array(256),n=d.length/4;
 let sum=0,sat=0,edges=0,count=0;
 const prior=new Uint8Array(c.width);
 for(let y=0;y<c.height;y++){
   let previous=0;
   for(let x=0;x<c.width;x++){
     const i=(y*c.width+x)*4,r=d[i],g=d[i+1],b=d[i+2];
     const lum=Math.round(.299*r+.587*g+.114*b);
     hist[lum]++;sum+=lum;
     sat+=Math.max(r,g,b)-Math.min(r,g,b);
     if(x>0){edges+=Math.abs(lum-previous);count++;}
     if(y>0){edges+=Math.abs(lum-prior[x]);count++;}
     previous=lum;prior[x]=lum;
   }
 }
 let acc=0,p10=0,p90=255;
 for(let i=0;i<256;i++){acc+=hist[i];if(acc>=n*.10){p10=i;break;}}
 acc=0;
 for(let i=0;i<256;i++){acc+=hist[i];if(acc>=n*.90){p90=i;break;}}
 const lowContrast=clamp((110-(p90-p10))/105,0,1);
 const lowTexture=clamp((17-edges/Math.max(1,count))/17,0,1);
 const lowSat=clamp((40-sat/n)/40,0,1);
 const haze=clamp(lowContrast*.48+lowTexture*.34+lowSat*.18,0,1);
 auto=Math.round(variant==='max'?78+haze*18:52+haze*17);
 $('estimate').textContent='Орієнтовна вираженість серпанку: '+Math.round(haze*100)+'% (оцінка з кадру).';
 if(!$('manual').checked)$('strength').value=String(auto);
 updateLabels();
}
async function loadImage(file){
 if(!file)return;
 if(!file.type.startsWith('image/')){
   setStatus('Потрібна фотографія JPG, PNG або WebP.');return;
 }
 if(imageUrl)URL.revokeObjectURL(imageUrl);
 imageUrl=URL.createObjectURL(file);
 const next=new Image();
 next.onload=()=>{
   picture=next;
   const max=1600,scale=Math.min(1,max/Math.max(next.naturalWidth,next.naturalHeight));
   const w=Math.max(1,Math.round(next.naturalWidth*scale)),
         h=Math.max(1,Math.round(next.naturalHeight*scale));
   original.width=w;original.height=h;
   originalContext.drawImage(next,0,0,w,h);
   placeholder(result,resultContext,'Натисни ОБРОБИТИ');
   processed=false;
   $('process').disabled=false;$('quickProcess').disabled=false;
   $('save').disabled=true;$('saveTop').disabled=true;
   $('info').textContent=file.name+' • '+next.naturalWidth+' × '+next.naturalHeight;
   estimateImage(next);setStatus('Фото завантажено. Готово до обробки.');
 };
 next.onerror=()=>setStatus('Не вдалося відкрити фото.');
 next.src=imageUrl;
}
$('file').addEventListener('change',e=>{
 const file=e.target.files&&e.target.files[0];
 e.target.value='';
 loadImage(file);
});
function localContrast(src,w,h,opts){
 const nx=8,ny=Math.max(3,Math.min(10,Math.round(nx*h/w))),
       tw=Math.ceil(w/nx),th=Math.ceil(h/ny),luts=[],weights=[];
 for(let ty=0;ty<ny;ty++)for(let tx=0;tx<nx;tx++){
   const hist=new Float32Array(256),x0=tx*tw,y0=ty*th,
         x1=Math.min(w,x0+tw),y1=Math.min(h,y0+th);
   let n=0,sum=0,sumSq=0;
   for(let y=y0;y<y1;y++)for(let x=x0;x<x1;x++){
     const i=(y*w+x)*4,lum=Math.round(.299*src[i]+.587*src[i+1]+.114*src[i+2]);
     hist[lum]++;n++;sum+=lum;sumSq+=lum*lum;
   }
   const std=Math.sqrt(Math.max(0,sumSq/Math.max(1,n)-Math.pow(sum/Math.max(1,n),2)));
   weights.push(clamp((std-4.0)/24,0,1));
   const limit=Math.max(1,opts.clip*Math.max(1,n)/256);let excess=0;
   for(let k=0;k<256;k++)if(hist[k]>limit){excess+=hist[k]-limit;hist[k]=limit;}
   const lut=new Uint8Array(256);let acc=0;
   for(let k=0;k<256;k++){
     acc+=hist[k]+excess/256;
     lut[k]=clamp(Math.round(acc*255/Math.max(1,n)));
   }
   luts.push(lut);
 }
 const out=new Uint8ClampedArray(src.length);
 const skyProtect=opts.sky/100;
 for(let y=0;y<h;y++){
   const fy=(y+.5)/th-.5,ty0=clamp(Math.floor(fy),0,ny-1),
         ty1=Math.min(ny-1,ty0+1),ay=clamp(fy-ty0,0,1);
   for(let x=0;x<w;x++){
     const fx=(x+.5)/tw-.5,tx0=clamp(Math.floor(fx),0,nx-1),
           tx1=Math.min(nx-1,tx0+1),ax=clamp(fx-tx0,0,1);
     const i=(y*w+x)*4;
     const lum=Math.round(.299*src[i]+.587*src[i+1]+.114*src[i+2]);
     const k00=ty0*nx+tx0,k10=ty0*nx+tx1,
           k01=ty1*nx+tx0,k11=ty1*nx+tx1;
     const v0=luts[k00][lum]*(1-ax)+luts[k10][lum]*ax;
     const v1=luts[k01][lum]*(1-ax)+luts[k11][lum]*ax;
     const texture=(weights[k00]*(1-ax)+weights[k10]*ax)*(1-ay)+
           (weights[k01]*(1-ax)+weights[k11]*ax)*ay;
     const mapped=v0*(1-ay)+v1*ay;
     const highlight=clamp((lum-164)/68,0,1);
     const top=clamp((.7-y/h)/.5,0,1);
     const protect=1-highlight*top*skyProtect;
     const delta=clamp(mapped-lum,-45,45)*texture*opts.gain*protect;
     out[i]=clamp(src[i]+delta);
     out[i+1]=clamp(src[i+1]+delta);
     out[i+2]=clamp(src[i+2]+delta);
     out[i+3]=src[i+3];
   }
 }
 return out;
}
function adaptiveDenoise(src,w,h,intensity){
 if(intensity<=0)return src;
 const out=new Uint8ClampedArray(src),mix=(intensity/100)*.68;
 for(let y=1;y<h-1;y++)for(let x=1;x<w-1;x++){
   const i=(y*w+x)*4;
   const lum=.299*src[i]+.587*src[i+1]+.114*src[i+2];
   const neighbors=[i-4,i+4,i-4*w,i+4*w];
   let r=src[i]*1.5,g=src[i+1]*1.5,b=src[i+2]*1.5,total=1.5;
   for(const j of neighbors){
     const neighborLum=.299*src[j]+.587*src[j+1]+.114*src[j+2];
     const weight=clamp(1-Math.abs(lum-neighborLum)/18,0,1);
     total+=weight;r+=src[j]*weight;g+=src[j+1]*weight;b+=src[j+2]*weight;
   }
   out[i]=clamp(src[i]*(1-mix)+(r/total)*mix);
   out[i+1]=clamp(src[i+1]*(1-mix)+(g/total)*mix);
   out[i+2]=clamp(src[i+2]*(1-mix)+(b/total)*mix);
 }
 return out;
}
function fineDetail(src,w,h,power,skyProtect){
 if(power<=0)return src;
 const out=new Uint8ClampedArray(src),amount=(power/100)*.6,protect=skyProtect/100;
 for(let y=1;y<h-1;y++)for(let x=1;x<w-1;x++){
   const i=(y*w+x)*4;
   const lum=.299*src[i]+.587*src[i+1]+.114*src[i+2];
   const sky=clamp((lum-172)/67,0,1)*clamp((.7-y/h)/.6,0,1);
   const factor=amount*(1-sky*protect);
   for(let c=0;c<3;c++){
     const blur=(src[i+c-4]+src[i+c+4]+src[i+c-w*4]+src[i+c+w*4])*.25;
     const edge=clamp(src[i+c]-blur,-25,25);
     out[i+c]=clamp(src[i+c]+edge*factor);
   }
 }
 return out;
}
function fit(w,h,max){
 const sc=Math.min(1,max/Math.max(w,h));
 return [Math.max(1,Math.round(w*sc)),Math.max(1,Math.round(h*sc))];
}
async function process(){
 if(!picture||busy)return;
 busy=true;$('panel').classList.add('busy');
 $('process').disabled=true;$('quickProcess').disabled=true;
 $('save').disabled=true;$('saveTop').disabled=true;
 setStatus('Обробка MAX • оцінювання туману → DCP → guided filter → локальний контраст…');
 await new Promise(resolve=>setTimeout(resolve,70));
 const started=performance.now();
 try{
   const [w,h]=fit(picture.naturalWidth,picture.naturalHeight,Number($('resolution').value));
   const temp=document.createElement('canvas');temp.width=w;temp.height=h;
   const ctx=temp.getContext('2d',{willReadFrequently:true});
   ctx.drawImage(picture,0,0,w,h);
   const frame=ctx.getImageData(0,0,w,h);
   const source=new Uint8ClampedArray(frame.data);
   const strength=Number($('manual').checked?$('strength').value:auto);
   const sky=Number($('sky').value),detail=Number($('detail').value),
         noise=Number($('noise').value);
   let output;
   if(strength===0){
     output=source;
   }else{
     if(typeof dehazeStrongDCP!=='function')
       throw Error('Не завантажився офлайн-алгоритм DCP.');
     const recovered=dehazeStrongDCP(source,w,h,{
       strength,skyProtect:sky
     });
     // Multistage quality pipeline. Each stage preserves true pixels; no
     // generative AI or invented textures are used.
     const contrasted=localContrast(recovered,w,h,{
       sky,clip:variant==='max'?2.2:1.5,
       gain:(variant==='max'?.43:.27)*strength/100
     });
     const denoised=adaptiveDenoise(contrasted,w,h,noise);
     output=fineDetail(denoised,w,h,detail,sky);
   }
   frame.data.set(output);
   result.width=w;result.height=h;
   resultContext.putImageData(frame,0,0);
   processed=true;
   $('save').disabled=false;$('saveTop').disabled=false;
   $('info').textContent=w+' × '+h+' • '+(variant==='max'?'AUTO MAX':'AUTO')+
     ' • сила '+strength+'% • захист неба '+sky+'%';
   setStatus('Готово за '+((performance.now()-started)/1000).toFixed(1)+
     ' с. Результат збережи в Галерею.');
 }catch(e){
   setStatus('Помилка обробки: '+e.message);
   console.error('Photo MAX:',e);
 }finally{
   busy=false;$('panel').classList.remove('busy');
   $('process').disabled=false;$('quickProcess').disabled=false;
 }
}
$('process').onclick=process;$('quickProcess').onclick=process;
const save=async()=>{
 if(!processed)return;
 setStatus('Збереження PNG…');
 try{
   const data=result.toDataURL('image/png');
   if(window.MetiAndroid&&typeof MetiAndroid.savePng==='function'){
     MetiAndroid.savePng(data);
   }else{
     const blob=await new Promise(ok=>result.toBlob(ok,'image/png'));
     if(!blob)throw Error('Не вдалося створити PNG');
     const f=new File([blob],'Meti-Tuman-VIDEO-MAX.png',{type:'image/png'});
     if(navigator.canShare?.({files:[f]})&&navigator.share){
       await navigator.share({files:[f],title:'Меті Туман VIDEO MAX'});
     }else{
       const url=URL.createObjectURL(blob),link=document.createElement('a');
       link.href=url;link.download=f.name;link.click();
       setTimeout(()=>URL.revokeObjectURL(url),12000);
     }
     setStatus('Фото готове до збереження.');
   }
 }catch(e){setStatus('Помилка збереження PNG: '+e.message);}
};
$('save').onclick=save;$('saveTop').onclick=save;
window.onNativeSave=msg=>setStatus(msg);
$('reset').onclick=()=>{
 picture=null;processed=false;
 $('file').value='';
 if(imageUrl)URL.revokeObjectURL(imageUrl);imageUrl=null;
 placeholder(original,originalContext,'ОРИГІНАЛ');
 placeholder(result,resultContext,'ПІСЛЯ ОБРОБКИ');
 $('process').disabled=true;$('quickProcess').disabled=true;
 $('save').disabled=true;$('saveTop').disabled=true;
 $('info').textContent='Вибери фотографію з туманом.';
 setStatus('Очікую на фотографію.');
};
updateLabels();
})();
