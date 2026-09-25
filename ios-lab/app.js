(()=>{
'use strict';
const $=id=>document.getElementById(id);
const lab=window.MetiLab||{flags:63,gpuSafe:false,enabled:()=>true,event:()=>{},workerError:()=>{},log:()=>{},setGpuSafe:()=>{}};
let processed=$('processedCanvas');
const video=$('sourceVideo'),stage=$('stage'),originalPane=$('originalPane'),
      processedPane=$('processedPane'),notice=$('notice'),empty=$('empty');
const state={
 source:null,stream:null,objectUrl:null,facing:'environment',view:'split',fit:'cover',
 rotation:0,zoom:1,panX:0,panY:0,enabled:true,manual:false,strength:.55,
 autoStrength:.55,autoAvailable:true,playing:false,paused:false,freeze:false,
 lastAnalysis:0,analysisFailed:false,frameCounter:0,lastStats:0,lastFrame:0,renderGeneration:0,
 installedEvent:null,usingCpu:false,hybridEpoch:0
};
const analysis=document.createElement('canvas');analysis.width=64;analysis.height=36;
const hybridCanvas=document.createElement('canvas');hybridCanvas.width=128;hybridCanvas.height=72;
const hybridCtx=hybridCanvas.getContext('2d',{willReadFrequently:true});
const actx=analysis.getContext('2d',{willReadFrequently:true});
let gl=null,program=null,tex=null,uniforms={},posLoc=-1,quad=null,canvas2d=null;
let worker=null,workerBusy=false,pendingMap=null,hybridReady=false,workerFailed=false;
let gpuMap=null,gpuLut=null,air=[.85,.85,.85],lastHybrid=0,gpuSimple=false,lastGlCheck=0;
let loopRaf=0,noticeTimeout=0,fp=null;
const clip=(v,a,b)=>Math.max(a,Math.min(b,v));
const icon={};
function showNotice(message,timeout=4400){
 clearTimeout(noticeTimeout);
 notice.textContent=message;notice.hidden=false;
 if(timeout)noticeTimeout=setTimeout(()=>notice.hidden=true,timeout);
}
function setStatus(message){
 $('sourceBadge').innerHTML='<span class="dot"></span>'+message;
}
function syncUI(){
 stage.dataset.view=state.view;
 $('viewSplit').classList.toggle('active',state.view==='split');
 $('viewWipe').classList.toggle('active',state.view==='wipe');
 $('viewFull').classList.toggle('active',state.view==='full');
 $('fitButton').textContent=state.fit==='cover'?'FILL':'FIT';
 $('filterEnabled').checked=state.enabled;
 $('manualCheckbox').checked=state.manual;
 $('modeName').textContent=state.manual?'РУЧНИЙ':'AUTO MAX';
 $('strengthSlider').disabled=!state.manual;
 $('strengthSlider').style.opacity=state.manual?'1':'.46';
 $('strengthSlider').value=String(Math.round(100*(state.manual?state.strength:state.autoStrength)));
 $('strengthLabel').textContent=Math.round(100*(state.manual?state.strength:state.autoStrength))+'%';
 $('autoBadge').textContent=(state.manual?'РУЧНИЙ ':'AUTO MAX ')+
    Math.round(100*(state.manual?state.strength:state.autoStrength))+'%';
 $('cameraSwitch').hidden=state.source!=='camera';
 $('fileControls').hidden=state.source!=='file';
 $('playPause').textContent=state.paused?'▶':'Ⅱ';
 $('playPause').setAttribute('aria-label',state.paused?'Відтворити':'Пауза');
 $('resumeOverlay').hidden=!state.source||!state.paused;
 $('zoomIndicator').hidden=state.zoom<=1.01;
 $('zoomIndicator').textContent=state.zoom.toFixed(1)+'×';
 updateMediaGeometry();
}
function drawer(open){
 $('drawer').hidden=!open;$('backdrop').hidden=!open;
 $('menuButton').setAttribute('aria-expanded',String(open));
}
$('menuButton').onclick=()=>drawer($('drawer').hidden);
$('quickSettings').onclick=()=>drawer(true);
$('drawerClose').onclick=()=>drawer(false);
$('backdrop').onclick=()=>drawer(false);
document.addEventListener('keydown',e=>{if(e.key==='Escape')drawer(false);});
function setView(v){state.view=v;syncUI();drawer(false);}
$('viewSplit').onclick=()=>setView('split');
$('viewWipe').onclick=()=>setView('wipe');
$('viewFull').onclick=()=>setView('full');
$('fitButton').onclick=()=>{state.fit=state.fit==='cover'?'contain':'cover';syncUI();};
$('filterEnabled').onchange=e=>{state.enabled=e.target.checked;syncUI();renderFrame(true);};
$('manualCheckbox').onchange=e=>{
 state.manual=e.target.checked;
 if(state.manual){state.strength=state.autoStrength;}
 syncUI();renderFrame(true);
};
$('strengthSlider').oninput=e=>{
 if(!state.manual)return;
 state.strength=Number(e.target.value)/100;syncUI();renderFrame(true);
};
$('resetZoom').onclick=()=>{state.zoom=1;state.panX=0;state.panY=0;syncUI();drawer(false);};
$('rotateButton').onclick=()=>{
 state.rotation=(state.rotation+90)%360;updateMediaGeometry();
 showNotice('Поворот зображення: '+state.rotation+'°. Екран пристрою не повертається.');
};
function sourceSize(){return {w:video.videoWidth||1280,h:video.videoHeight||720};}
function positionMedia(media,pane){
 const w=pane.clientWidth,h=pane.clientHeight;
 if(!w||!h)return;
 const turn=(state.rotation%180)!==0;
 const width=turn?h:w,height=turn?w:h;
 media.style.width=width+'px';media.style.height=height+'px';
 media.style.objectFit=state.fit;
 media.style.transform='translate(-50%,-50%) rotate('+state.rotation+'deg) translate('+
    state.panX+'px,'+state.panY+'px) scale('+state.zoom+')';
}
function updateMediaGeometry(){
 if(!stage.isConnected)return;
 positionMedia(video,originalPane);
 positionMedia(processed,processedPane);
}
if(typeof ResizeObserver!=='undefined'){
 const ro=new ResizeObserver(updateMediaGeometry);
 ro.observe(stage);ro.observe(originalPane);ro.observe(processedPane);
}
window.addEventListener('resize',updateMediaGeometry);
window.addEventListener('orientationchange',()=>setTimeout(updateMediaGeometry,200));
const pointers=new Map();
let prevDist=0,lastTap=0,dragStart=null;
function dist(a,b){return Math.hypot(a.x-b.x,a.y-b.y);}
stage.addEventListener('pointerdown',e=>{
 if(!state.source)return;
 if(e.target.closest('button'))return;
 stage.setPointerCapture(e.pointerId);
 pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});
 if(pointers.size===2){const [a,b]=[...pointers.values()];prevDist=dist(a,b);}
 else if(pointers.size===1){
   dragStart={x:e.clientX,y:e.clientY};
   if(e.pointerType!=='mouse'&&performance.now()-lastTap<350){
     state.zoom=state.zoom>1.5?1:2;state.panX=0;state.panY=0;syncUI();
   }
   lastTap=performance.now();
 }
});
stage.addEventListener('pointermove',e=>{
 if(!pointers.has(e.pointerId))return;
 const old=pointers.get(e.pointerId),current={x:e.clientX,y:e.clientY};
 pointers.set(e.pointerId,current);
 if(pointers.size===2){
   const [a,b]=[...pointers.values()],d=dist(a,b);
   if(prevDist>0&&d>0){state.zoom=clip(state.zoom*d/prevDist,1,6);syncUI();}
   prevDist=d;
 }else if(pointers.size===1&&state.zoom>1){
   state.panX=clip(state.panX+current.x-old.x,-stage.clientWidth*.45,stage.clientWidth*.45);
   state.panY=clip(state.panY+current.y-old.y,-stage.clientHeight*.45,stage.clientHeight*.45);
   syncUI();
 }
});
function release(e){pointers.delete(e.pointerId);if(pointers.size<2)prevDist=0;}
stage.addEventListener('pointerup',release);
stage.addEventListener('pointercancel',release);
stage.addEventListener('lostpointercapture',release);
stage.addEventListener('wheel',e=>{
 if(!state.source)return;e.preventDefault();
 state.zoom=clip(state.zoom*(e.deltaY>0?.91:1.1),1,6);
 syncUI();
},{passive:false});
function shader(type,source){
 const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);
 if(!gl.getShaderParameter(s,gl.COMPILE_STATUS)){
   const err=gl.getShaderInfoLog(s);gl.deleteShader(s);throw Error(err||'WebGL shader');
 }
 return s;
}
function initGpu(){
 if(gl||state.usingCpu)return;
 try{
   gl=processed.getContext('webgl',{alpha:false,depth:false,stencil:false,antialias:false,
      preserveDrawingBuffer:true,powerPreference:'high-performance'});
   if(!gl)throw Error('WebGL unavailable');
   const vs=shader(gl.VERTEX_SHADER,`attribute vec2 aPosition;
       varying vec2 uv;
       void main(){uv=(aPosition+1.0)*0.5;gl_Position=vec4(aPosition,0.0,1.0);}`);
   let fs;
   try{fs=shader(gl.FRAGMENT_SHADER,`precision mediump float;
       varying vec2 uv;
       uniform sampler2D tex;
       uniform vec2 pixel;
       uniform float intensity;
       uniform float hybridEnabled;
       uniform sampler2D uMap;
       uniform sampler2D uLut;
       uniform vec3 airLight;
       uniform float uRetinex;
       uniform float uFusion;
       void main(){
         vec3 c=texture2D(tex,uv).rgb;
         if(intensity<0.001){gl_FragColor=vec4(c,1.0);return;}
         vec3 local=(texture2D(tex,uv+vec2(pixel.x*2.0,0.0)).rgb+
          texture2D(tex,uv-vec2(pixel.x*2.0,0.0)).rgb+
          texture2D(tex,uv+vec2(0.0,pixel.y*2.0)).rgb+
          texture2D(tex,uv-vec2(0.0,pixel.y*2.0)).rgb)*0.25;
         float y=dot(c,vec3(.299,.587,.114));
         float edge=length(c-local);
         float protect=smoothstep(.69,.91,y)*(1.0-smoothstep(.01,.07,edge));
         float mask=1.0-.85*protect;
         if(hybridEnabled>.5){
           vec4 m=texture2D(uMap,vec2(uv.x,1.0-uv.y));
           float sky=m.g;
           float amount=clamp(intensity*1.08*(1.0-.94*sky),0.0,.97);
           vec3 clear=clamp((c-airLight)/max(.27,m.r)+airLight,0.0,1.0);
           vec3 result=mix(c,clear,amount);
           float lum=dot(result,vec3(.299,.587,.114));
           vec2 xy=clamp(vec2(uv.x,1.0-uv.y)*vec2(8.0,6.0)-.5,vec2(0.0),vec2(7.0,5.0));
           vec2 lo=floor(xy),hi=min(lo+vec2(1.0),vec2(7.0,5.0));
           vec2 p=fract(xy);
           float k=floor(clamp(lum,0.0,1.0)*255.0+.5);
           float ll=texture2D(uLut,vec2((k+.5)/256.0,(lo.y*8.0+lo.x+.5)/48.0)).r;
           float lh=texture2D(uLut,vec2((k+.5)/256.0,(lo.y*8.0+hi.x+.5)/48.0)).r;
           float hl=texture2D(uLut,vec2((k+.5)/256.0,(hi.y*8.0+lo.x+.5)/48.0)).r;
           float hh=texture2D(uLut,vec2((k+.5)/256.0,(hi.y*8.0+hi.x+.5)/48.0)).r;
           float mapped=mix(mix(ll,lh,p.x),mix(hl,hh,p.x),p.y);
           float contrast=clamp(mapped-lum,-.17,.17)*.41*amount*(1.0-sky);
           result=clamp(result+vec3(contrast),0.0,1.0);
           result=clamp(result+clamp(c-local,vec3(-.09),vec3(.09))*
                          (.35*amount*m.a),0.0,1.0);
           if(uRetinex>.5){
             float darkness=clamp((.58-m.b)*1.7,0.0,1.0);
             float gamma=1.0-.28*darkness*(1.0-sky);
             result=mix(result,pow(max(result,vec3(.001)),vec3(gamma)),clamp(intensity,0.0,1.0));
           }
           if(uFusion>.5){
             float ft=max(.55,1.0-intensity*(.23+.14*y));
             vec3 fast=clamp((c-vec3(.84))/ft+vec3(.84),0.0,1.0);
             result=mix(result,fast,clamp(.30*m.a*(1.0-sky)*intensity,0.0,.35));
           }
           gl_FragColor=vec4(result,1.0);return;
         }
         float t=max(.58,1.0-intensity*(.25+.18*y));
         vec3 rec=clamp((c-vec3(.81))/t+vec3(.81),0.0,1.0);
         vec3 corrected=mix(c,rec,.79*mask);
         corrected+=clamp(c-local,vec3(-.10),vec3(.10))*(.48*intensity*mask);
         gl_FragColor=vec4(clamp(corrected,0.0,1.0),1.0);
       }`);}
   catch(e){
     gpuSimple=true;lab.event('gpu','РЕЗЕРВНИЙ','Гібридний шейдер: '+e.message);
     fs=shader(gl.FRAGMENT_SHADER,`precision mediump float;
       varying vec2 uv;
       uniform sampler2D tex;
       uniform vec2 pixel;
       uniform float intensity;
       void main(){
         vec3 c=texture2D(tex,uv).rgb;
         if(intensity<.001){gl_FragColor=vec4(c,1.0);return;}
         vec3 local=(texture2D(tex,uv+vec2(0.,pixel.y)).rgb+
           texture2D(tex,uv-vec2(0.,pixel.y)).rgb+
           texture2D(tex,uv+vec2(pixel.x,0.)).rgb+
           texture2D(tex,uv-vec2(pixel.x,0.)).rgb)*.25;
         gl_FragColor=vec4(clamp(c+clamp(c-local,vec3(-.08),vec3(.08))*
           intensity*.35,0.,1.),1.);
       }`);
   }
   program=gl.createProgram();gl.attachShader(program,vs);gl.attachShader(program,fs);gl.linkProgram(program);
   if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(program)||'WebGL link');
   gl.useProgram(program);
   quad=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,quad);
   gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,1,1]),gl.STATIC_DRAW);
   posLoc=gl.getAttribLocation(program,'aPosition');
   gl.enableVertexAttribArray(posLoc);gl.vertexAttribPointer(posLoc,2,gl.FLOAT,false,0,0);
   tex=gl.createTexture();gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,tex);
   gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
   gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
   gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);
   gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
   gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true);
   uniforms.intensity=gl.getUniformLocation(program,'intensity');
   uniforms.pixel=gl.getUniformLocation(program,'pixel');
   gl.uniform1i(gl.getUniformLocation(program,'tex'),0);
   uniforms.hybrid=gl.getUniformLocation(program,'hybridEnabled');
   uniforms.air=gl.getUniformLocation(program,'airLight');
   uniforms.retinex=gl.getUniformLocation(program,'uRetinex');
   uniforms.fusion=gl.getUniformLocation(program,'uFusion');
   gl.uniform1i(gl.getUniformLocation(program,'uMap'),1);
   gl.uniform1i(gl.getUniformLocation(program,'uLut'),2);
   function newMap(unit,w,h,filter){
     let t=gl.createTexture();gl.activeTexture(unit);gl.bindTexture(gl.TEXTURE_2D,t);
     gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
     gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
     gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,filter);
     gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,filter);
     gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,w,h,0,gl.RGBA,gl.UNSIGNED_BYTE,null);
     return t;
   }
   gpuMap=newMap(gl.TEXTURE1,128,72,gl.LINEAR);
   gpuLut=newMap(gl.TEXTURE2,256,48,gl.NEAREST);
   gl.activeTexture(gl.TEXTURE0);
   if(!gpuSimple)initWorker();
   lab.event('gpu',gpuSimple?'РЕЗЕРВНИЙ':'ГОТОВО',gpuSimple?'FAST GPU':'WebGL шейдер зібрано');
 }catch(e){
   console.warn('WebGL fallback:',e);
   gl=null;state.usingCpu=true;
   lab.event('gpu','РЕЗЕРВНИЙ','CPU замість GPU: '+e.message);
   const other=processed.cloneNode(false);
   processed.replaceWith(other);processed=other;
   canvas2d=processed.getContext('2d',{willReadFrequently:true});
   updateMediaGeometry();
   showNotice('WebGL недоступний. Увімкнено сумісний режим CPU; FPS може бути нижчим.',6500);
 }
}
function initWorker(){
  if(worker||workerFailed||typeof Worker==='undefined')return;
  try{
    worker=new Worker('./video-worker.js');
    worker.onmessage=e=>{
      if(e.data.epoch!==state.hybridEpoch)return;
      workerBusy=false;
      if(e.data.type==='error'){
        workerFailed=true;worker.terminate();worker=null;
        showNotice('LIVE MAX: швидкий резервний антитуман.',5000);
        return;
      }
      pendingMap={map:new Uint8Array(e.data.map),lut:new Uint8Array(e.data.lut),air:e.data.air};
      if(!state.manual){
        state.autoStrength=.62*state.autoStrength+.38*(.75+.22*e.data.haze);
        syncUI();
      }
    };
    worker.onerror=()=>{
      workerBusy=false;workerFailed=true;worker?.terminate();worker=null;
      showNotice('LIVE MAX недоступний: працює швидкий фільтр.',5500);
    };
  }catch(e){workerFailed=true;}
}
function scheduleHybrid(now){
  if(!worker||workerBusy||!hybridCtx||!state.source||video.paused||
     !state.enabled||now-lastHybrid<850)return;
  lastHybrid=now;
  try{
    hybridCtx.drawImage(video,0,0,128,72);
    const bytes=hybridCtx.getImageData(0,0,128,72).data.slice().buffer;
    workerBusy=true;
    worker.postMessage({pixels:bytes,epoch:state.hybridEpoch},[bytes]);
  }catch(e){
    workerBusy=false;workerFailed=true;worker?.terminate();worker=null;
    showNotice('LIVE MAX недоступний для цього потоку. Швидкий режим активний.',5500);
  }
}
function uploadMaps(){
  if(!pendingMap||!gl)return;
  const next=pendingMap;pendingMap=null;
  gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,false);
  gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,gpuMap);
  gl.texSubImage2D(gl.TEXTURE_2D,0,0,0,128,72,gl.RGBA,gl.UNSIGNED_BYTE,next.map);
  gl.activeTexture(gl.TEXTURE2);gl.bindTexture(gl.TEXTURE_2D,gpuLut);
  gl.texSubImage2D(gl.TEXTURE_2D,0,0,0,256,48,gl.RGBA,gl.UNSIGNED_BYTE,next.lut);
  gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true);
  gl.activeTexture(gl.TEXTURE0);
  air=next.air;hybridReady=true;
}
function dimensions(){
 const {w,h}=sourceSize();
 const reduced=matchMedia('(max-width:720px)').matches||/iPhone|iPad|iPod/.test(navigator.userAgent);
 const max=reduced?960:1280;
 const sc=Math.min(1,max/Math.max(w,h));
 return {w:Math.max(2,Math.round(w*sc)),h:Math.max(2,Math.round(h*sc))};
}
function analyze(now){
 if(state.manual||state.analysisFailed||!actx||now-state.lastAnalysis<1300||video.readyState<2)return;
 state.lastAnalysis=now;
 try{
   actx.drawImage(video,0,0,64,36);
   const pixels=actx.getImageData(0,0,64,36).data;
   const hist=new Uint16Array(256),prev=new Int16Array(64);
   let e=0,edges=0,sat=0,mean=0;
   for(let y=0;y<36;y++){
     let left=0;
     for(let x=0;x<64;x++){
       const i=(y*64+x)*4,r=pixels[i],g=pixels[i+1],b=pixels[i+2];
       const lum=Math.round(.299*r+.587*g+.114*b);
       hist[lum]++;mean+=lum;
       sat+=Math.max(r,g,b)-Math.min(r,g,b);
       if(x>0){e+=Math.abs(lum-left);edges++;}
       if(y>0){e+=Math.abs(lum-prev[x]);edges++;}
       left=lum;prev[x]=lum;
     }
   }
   const n=64*36;
   let sum=0,p10=0,p90=255;
   for(let k=0;k<256;k++){sum+=hist[k];if(sum>=n*.10){p10=k;break;}}
   sum=0;
   for(let k=0;k<256;k++){sum+=hist[k];if(sum>=n*.90){p90=k;break;}}
   const contrast=clip((105-(p90-p10))/100,0,1);
   const texture=clip((18-e/Math.max(1,edges))/18,0,1);
   const gray=clip((48-sat/n)/48,0,1);
   const proxy=.45*contrast+.35*texture+.20*gray;
   let target=.22+.64*proxy;
   if(mean/n<65)target=.22+(target-.22)*.65;
   state.autoStrength=.72*state.autoStrength+.28*target;
   syncUI();
 }catch(e){
   state.analysisFailed=true;
   showNotice('Автоматична оцінка недоступна для цього потоку. Можна вибрати ручний режим.',5500);
 }
}
function renderFrame(force=false){
 if(!state.source||video.readyState<2)return;
 if(!force&&video.paused)return;
 const now=performance.now(),{w,h}=dimensions(),start=performance.now();
 try{
   initGpu();
   if(processed.width!==w||processed.height!==h){
     processed.width=w;processed.height=h;
     if(gl)gl.viewport(0,0,w,h);
   }
   const amount=state.enabled?(state.manual?state.strength:state.autoStrength):0;
   if(gl){
     gl.useProgram(program);uploadMaps();gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,tex);
     gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,video);
     gl.uniform2f(uniforms.pixel,1/w,1/h);
     gl.uniform1f(uniforms.intensity,amount);
     gl.uniform1f(uniforms.hybrid,hybridReady&&amount>0?1:0);
     gl.uniform3f(uniforms.air,...air);
     gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
   }else if(canvas2d){
     canvas2d.drawImage(video,0,0,w,h);
     if(amount>0){
       const frame=canvas2d.getImageData(0,0,w,h);
       const d=frame.data,f=1+amount*.34;
       for(let i=0;i<d.length;i+=4){
         const y=.299*d[i]+.587*d[i+1]+.114*d[i+2];
         const sky=clip((y-170)/70,0,1);
         const gain=amount*(1-sky*.92);
         for(let c=0;c<3;c++)d[i+c]=clip(d[i+c]+(d[i+c]-128)*(f-1)*gain-(150-y)*gain*.07,0,255);
       }
       canvas2d.putImageData(frame,0,0);
     }
   }
   if(!state.manual)analyze(now);
   scheduleHybrid(now);
   state.frameCounter++;
   if(now-state.lastStats>=1000){
     const elapsed=Math.max(1,now-state.lastStats);
     const fps=Math.round(state.frameCounter*1000/elapsed);
     $('fps').textContent=String(fps);
     $('procMs').textContent=(performance.now()-start).toFixed(1);
     $('resolution').textContent=video.videoWidth+'×'+video.videoHeight;
     state.frameCounter=0;state.lastStats=now;
     $('fileTime').textContent=formatTime(video.currentTime)+' / '+formatTime(video.duration);
   }
 }catch(e){
   console.warn('Video GPU failed:',e);
   if(gl){
     gl=null;state.usingCpu=true;
   lab.event('gpu','РЕЗЕРВНИЙ','CPU замість GPU: '+e.message);
     const other=processed.cloneNode(false);processed.replaceWith(other);processed=other;
     canvas2d=processed.getContext('2d',{willReadFrequently:true});
     updateMediaGeometry();showNotice('Сумісний режим CPU: '+e.message,5000);
   }else{
     stopLoop();showNotice('Не вдалося обробити відео: '+e.message,0);
   }
 }
}
function nextFrame(generation){
 if(generation!==state.renderGeneration||!state.source||video.paused||document.hidden)return;
 if(typeof video.requestVideoFrameCallback==='function'){
   video.requestVideoFrameCallback(()=>{
     if(generation!==state.renderGeneration)return;
     renderFrame();
     nextFrame(generation);
   });
 }else{
   loopRaf=requestAnimationFrame(()=>{
     if(generation!==state.renderGeneration)return;
     if(performance.now()-state.lastFrame>27){
       state.lastFrame=performance.now();renderFrame();
     }
     nextFrame(generation);
   });
 }
}
function stopLoop(){
 state.renderGeneration++;
 if(loopRaf)cancelAnimationFrame(loopRaf);
 loopRaf=0;
 $('fps').textContent='—';$('procMs').textContent='—';
}
function startLoop(){
 if(!state.source||video.paused||document.hidden)return;
 stopLoop();
 state.lastStats=performance.now();state.frameCounter=0;
 nextFrame(state.renderGeneration);
}
async function cleanup(){
 stopLoop();
 state.hybridEpoch++;
 pendingMap=null;hybridReady=false;workerBusy=false;lastHybrid=0;
 if(worker)worker.postMessage({reset:true});
 try{video.pause();}catch(_){}
 if(state.stream){state.stream.getTracks().forEach(t=>t.stop());state.stream=null;}
 video.srcObject=null;
 video.removeAttribute('src');
 try{video.load();}catch(_){}
 if(state.objectUrl){URL.revokeObjectURL(state.objectUrl);state.objectUrl=null;}
 state.source=null;state.paused=false;
 $('resumeOverlay').hidden=true;
 state.rotation=0;state.zoom=1;state.panX=0;state.panY=0;
 $('fileControls').hidden=true;
}
function showActive(kind){
 state.source=kind;
 empty.hidden=true;$('floattools').hidden=false;
 setStatus(kind==='camera'?'КАМЕРА':'ФАЙЛ');
 syncUI();
}
async function openCamera(nextFace=state.facing){
 if(!navigator.mediaDevices?.getUserMedia){
   showNotice('Камера недоступна. Відкрий сайт через HTTPS у Safari або Chrome.',7000);
   return;
 }
 await cleanup();
 state.facing=nextFace;
 drawer(false);setStatus('ЗАПУСК…');
 try{
   const stream=await navigator.mediaDevices.getUserMedia({
     video:{facingMode:{ideal:nextFace},width:{ideal:1280},height:{ideal:720},frameRate:{ideal:30,max:30}},
     audio:false
   });
   state.stream=stream;
   video.srcObject=stream;
   video.muted=true;video.loop=false;
   video.playsInline=true;
   showActive('camera');
   try{await video.play();}
   catch(e){showNotice('Натисни ▶ на екрані, щоб запустити камеру.',6000);}
   if(!video.paused)startLoop();
 }catch(e){
   empty.hidden=false;$('floattools').hidden=true;
   setStatus('НЕМАЄ ДОСТУПУ');
   const reason=e.name==='NotAllowedError'?'Надай браузеру дозвіл на камеру.'
     :e.name==='NotFoundError'?'На пристрої немає доступної камери.':e.message;
   showNotice('Камеру не відкрито: '+reason,7000);
 }
}
async function openFile(file){
 if(!file)return;
 await cleanup();drawer(false);
 if(!file.type.startsWith('video/')&&!/\.(mp4|mov|webm|m4v)$/i.test(file.name)){
   showNotice('Обери відеофайл MP4, MOV або WebM.',5000);return;
 }
 state.objectUrl=URL.createObjectURL(file);
 video.src=state.objectUrl;video.muted=true;video.loop=true;
 showActive('file');
 try{await video.play();}
 catch(e){showNotice('Файл відкрито. Натисни ▶ для запуску.',5500);}
 if(!video.paused)startLoop();
}
function stopSource(){
 cleanup();empty.hidden=false;$('floattools').hidden=true;
 setStatus('ГОТОВИЙ');$('resolution').textContent='—';drawer(false);
}
$('startCamera').onclick=()=>openCamera();
$('quickCamera').onclick=()=>openCamera();
$('cameraMenu').onclick=()=>openCamera();
$('cameraSwitch').onclick=()=>openCamera(state.facing==='environment'?'user':'environment');
$('quickFile').onclick=()=>$('videoFile').click();
$('startFile').onclick=()=>$('videoFile').click();
$('fileMenu').onclick=()=>$('videoFile').click();
$('videoFile').onchange=e=>{const f=e.target.files?.[0];e.target.value='';openFile(f);};
$('stopButton').onclick=stopSource;
function togglePause(){
 if(!state.source)return;
 if(video.paused){
   video.play().then(()=>{state.paused=false;syncUI();startLoop();})
     .catch(e=>showNotice('Не вдається запустити відео: '+e.message));
 }else{
   video.pause();state.paused=true;syncUI();
 }
 drawer(false);
}
$('resumeOverlay').onclick=togglePause;
$('playPause').onclick=togglePause;
$('drawerPause').onclick=togglePause;
$('videoPlay').onclick=togglePause;
video.addEventListener('playing',()=>{
 if(!state.source)return;
 state.paused=false;syncUI();startLoop();
});
video.addEventListener('pause',()=>{
 if(!state.source)return;
 state.paused=true;stopLoop();syncUI();
});
video.addEventListener('loadedmetadata',()=>{
 if(!state.source)return;
 $('resolution').textContent=video.videoWidth+'×'+video.videoHeight;
 updateMediaGeometry();
 renderFrame(true);
});
video.addEventListener('error',()=>{
 if(state.source==='file')showNotice('Браузер не підтримує цей кодек. Спробуй MP4 (H.264).',6500);
});
video.addEventListener('timeupdate',()=>{
 if(state.source==='file')$('fileTime').textContent=formatTime(video.currentTime)+' / '+formatTime(video.duration);
});
function formatTime(t){
 if(!Number.isFinite(t))return '--:--';
 const s=Math.max(0,Math.floor(t));
 return String(Math.floor(s/60)).padStart(2,'0')+':'+String(s%60).padStart(2,'0');
}
document.addEventListener('visibilitychange',()=>{
 if(document.hidden){stopLoop();}
 else if(state.source&&!video.paused){startLoop();}
});
window.addEventListener('pagehide',()=>{
 if(state.stream)state.stream.getTracks().forEach(t=>t.stop());
});
function drawFitted(ctx,element,x,y,w,h){
 const sw=element.videoWidth||element.width||1,sh=element.videoHeight||element.height||1;
 const rot=state.rotation;
 const turned=rot===90||rot===270;
 const boxW=turned?h:w,boxH=turned?w:h;
 const scale=state.fit==='cover'?Math.max(boxW/sw,boxH/sh):Math.min(boxW/sw,boxH/sh);
 ctx.save();ctx.beginPath();ctx.rect(x,y,w,h);ctx.clip();
 ctx.translate(x+w/2,y+h/2);
 ctx.rotate(rot*Math.PI/180);
 ctx.translate(state.panX,state.panY);
 ctx.scale(state.zoom,state.zoom);
 ctx.drawImage(element,-sw*scale/2,-sh*scale/2,sw*scale,sh*scale);
 ctx.restore();
}
async function screenshot(){
 if(!state.source||video.readyState<2)return;
 renderFrame(true);
 const baseW=stage.clientWidth,baseH=stage.clientHeight;
 const scale=Math.min(1.5,1920/Math.max(baseW,baseH));
 const w=Math.max(2,Math.round(baseW*scale)),h=Math.max(2,Math.round(baseH*scale));
 const out=document.createElement('canvas');out.width=w;out.height=h;
 const ctx=out.getContext('2d');ctx.setTransform(scale,0,0,scale,0,0);
 ctx.fillStyle='#191c20';ctx.fillRect(0,0,baseW,baseH);
 try{
   if(state.view==='split'){
     const portrait=matchMedia('(orientation:portrait)').matches;
     if(portrait){
       drawFitted(ctx,video,0,0,baseW,baseH/2);
       drawFitted(ctx,processed,0,baseH/2,baseW,baseH/2);
     }else{
       drawFitted(ctx,video,0,0,baseW/2,baseH);
       drawFitted(ctx,processed,baseW/2,0,baseW/2,baseH);
     }
   }else if(state.view==='wipe'){
     drawFitted(ctx,video,0,0,baseW,baseH);
     ctx.save();ctx.beginPath();
     if(matchMedia('(orientation:portrait)').matches)ctx.rect(0,baseH/2,baseW,baseH/2);
     else ctx.rect(baseW/2,0,baseW/2,baseH);
     ctx.clip();drawFitted(ctx,processed,0,0,baseW,baseH);ctx.restore();
   }else{drawFitted(ctx,processed,0,0,baseW,baseH);}
   out.toBlob(async blob=>{
     if(!blob){showNotice('Знімок не вдався.');return;}
     const name='Meti-Tuman-'+Date.now()+'.png';
     const file=new File([blob],name,{type:'image/png'});
     try{
       // Native iOS/Android share sheet saves to Photos, Files or messaging.
       if(navigator.canShare?.({files:[file]})&&navigator.share){
         await navigator.share({files:[file],title:'Меті Туман VIDEO MAX'});
       }else{
         const url=URL.createObjectURL(blob),a=document.createElement('a');
         a.href=url;a.download=name;document.body.append(a);a.click();a.remove();
         setTimeout(()=>URL.revokeObjectURL(url),12000);
       }
     }catch(e){if(e.name!=='AbortError')showNotice('Не вдалося зберегти: '+e.message,6000);}
   },'image/png');
 }catch(e){showNotice('Не вдалося створити знімок: '+e.message,5000);}
}
$('screenshot').onclick=screenshot;
$('drawerScreenshot').onclick=()=>{drawer(false);screenshot();};
let installPrompt=null;
window.addEventListener('beforeinstallprompt',e=>{
 e.preventDefault();installPrompt=e;
 $('installButton').hidden=false;
});
$('installButton').onclick=async()=>{
 if(!installPrompt)return;
 installPrompt.prompt();
 await installPrompt.userChoice;
 installPrompt=null;$('installButton').hidden=true;
};
async function updateOffline(){
 const label=$('offlineStatus');
 if(!('serviceWorker' in navigator)){label.textContent='Офлайн-кеш не підтримується цим браузером';return;}
 if(!navigator.onLine){label.textContent='ОФЛАЙН • збережені файли';return;}
 try{
   const keys=await caches.keys();
   label.textContent=keys.some(k=>k.startsWith('meti-ios-video-max-'))
     ?'Офлайн готовий • інтернет необов’язковий'
     :'Перше завантаження • готуємо офлайн-кеш';
 }catch(e){label.textContent='Вебдодаток працює • перевір офлайн-кеш';}
}
window.addEventListener('online',updateOffline);
window.addEventListener('offline',updateOffline);
if('serviceWorker' in navigator){
 navigator.serviceWorker.register('./sw.js',{scope:'./'})
   .then(()=>navigator.serviceWorker.ready)
   .then(updateOffline)
   .catch(e=>{$('offlineStatus').textContent='Офлайн-кеш не вдалося підготувати';});
 navigator.serviceWorker.addEventListener('controllerchange',updateOffline);
}
if(/iPhone|iPad|iPod/i.test(navigator.userAgent)){
 $('installInstructions').textContent='iPhone/iPad: у Safari натисни Поділитися → На екран «Додому». Після встановлення відкрий додаток ще раз онлайн для офлайн-кешу.';
}
syncUI();updateOffline();
})();
