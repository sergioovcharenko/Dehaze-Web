'use strict';
/* Existing preferred LIVE WebGL algorithm preserved.
   Only presentation is changed: one canvas with 50/50 or full processed view,
   plus an optional two-frame mode. */
(() => {
 const $=id=>document.getElementById(id);
 const source=$('videoSource'),canvas=$('videoAfter'),status=$('videoStatus');
 const output=$('videoProcessedFig'),viewer=$('videoViewer'),caption=$('videoProcessedCaption');
 const originalFig=$('videoOriginalFig'),sourceKeeper=$('videoSourceKeeper');
 const filter=$('videoEnabled'),autoStrengthToggle=$('videoAutoStrength'),strength=$('videoStrength'),readout=$('videoStrengthVal');
 const seek=$('videoSeek'),time=$('videoTime'),playPause=$('videoPlayPause'),stopPlayback=$('videoStopPlayback');
 let stream=null,objectURL=null,gl=null,program=null,texture=null,vbo=null;
 let started=false,rendering=false,requested=false,fallbackRAF=0;
 let frameCounter=0,lastFps=0,seeking=false;
 let viewMode='split';
 let autoStrengthValue=.58,autoAtmosphere=.84,autoLevel=2,autoNight=false,lastAutoAnalysis=0,presetMode='AUTO';
 const autoCanvas=document.createElement('canvas');
 autoCanvas.width=96;autoCanvas.height=54;
 const autoCtx=autoCanvas.getContext('2d',{willReadFrequently:true});

 function message(s){status.textContent=s;}
 function clamp01(v){return Math.max(0,Math.min(1,v));}
 function currentStrength(){
  return autoStrengthToggle.checked?autoStrengthValue:Number(strength.value)/100;
 }
 function modeFlags(){
  const auto=autoStrengthToggle.checked;
  return {
   max:auto?autoLevel>=4:presetMode==='NIGHT/MAX',
   night:auto?autoNight:presetMode==='NIGHT/MAX'
  };
 }
 function updatePresetUi(){
  const map={OFF:'videoModeOff',AUTO:'videoModeAuto',LOW:'videoModeLow',MEDIUM:'videoModeMedium',HIGH:'videoModeHigh','NIGHT/MAX':'videoModeNight'};
  Object.entries(map).forEach(([name,id])=>{const el=$(id);if(el)el.classList.toggle('selected',name===presetMode);});
 }
 function updateStrengthUi(){
  const pct=Math.round(currentStrength()*100);
  strength.disabled=autoStrengthToggle.checked;
  strength.value=pct;
  readout.textContent=autoStrengthToggle.checked?'AUTO • '+pct+'%':(presetMode==='MANUAL'?pct+'% MANUAL':presetMode+' • '+pct+'%');
  updatePresetUi();
 }
 function analyzeAutoStrength(now){
  if(!autoStrengthToggle.checked||now-lastAutoAnalysis<700||source.readyState<2)return;
  lastAutoAnalysis=now;
  try{
   const W=96,H=54;
   autoCtx.drawImage(source,0,0,W,H);
   const d=autoCtx.getImageData(0,0,W,H).data;
   const hist=new Uint32Array(256),prev=new Int16Array(W);
   let sum=0,sat=0,darkSum=0,edgeSum=0,edgeCount=0,brightGray=0;
   for(let y=0;y<H;y++){
    let left=0;
    for(let x=0;x<W;x++){
     const i=(y*W+x)*4,r=d[i],g=d[i+1],bl=d[i+2];
     const L=Math.min(255,Math.round(.299*r+.587*g+.114*bl));
     const mn=Math.min(r,g,bl),mx=Math.max(r,g,bl);
     hist[L]++;sum+=L;sat+=mx-mn;darkSum+=mn;
     if(L>135 && (mx-mn)<34)brightGray++;
     if(x){edgeSum+=Math.abs(L-left);edgeCount++;}
     if(y){edgeSum+=Math.abs(L-prev[x]);edgeCount++;}
     left=L;prev[x]=L;
    }
   }
   const n=W*H;
   let acc=0,p10=0,p90=255;
   for(let i=0;i<256;i++){acc+=hist[i];if(acc>=n*.10){p10=i;break;}}
   acc=0;
   for(let i=0;i<256;i++){acc+=hist[i];if(acc>=n*.90){p90=i;break;}}
   const mean=sum/n,range=p90-p10,edgeMean=edgeSum/Math.max(1,edgeCount),satMean=sat/n,darkMean=darkSum/n;
   const lowContrast=clamp01((128-range)/108);
   const lowTexture=clamp01((17-edgeMean)/15);
   const grayness=clamp01((48-satMean)/45);
   const veil=clamp01((darkMean-78)/96);
   const brightVeil=clamp01((brightGray/n-.05)/.42);
   const hazeScore=clamp01(lowContrast*.34+lowTexture*.20+grayness*.16+veil*.18+brightVeil*.12);

   autoNight=mean<52;
   let target;
   if(hazeScore<.18) target=0.0;
   else if(hazeScore<.30) target=.18+(hazeScore-.18)*1.65;
   else if(hazeScore<.48) target=.38+(hazeScore-.30)*1.55;
   else target=.66+(hazeScore-.48)*.58;
   target=clamp01(Math.min(.92,target));
   if(autoNight)target=Math.max(.28,target*.78);

   autoLevel=target<.12?0:target<.34?1:target<.56?2:target<.74?3:4;
   const rise=target>autoStrengthValue?.24:.10;
   autoStrengthValue=clamp01(autoStrengthValue*(1-rise)+target*rise);

   const nextAtmosphere=Math.max(.72,Math.min(.95,(p90+10)/255));
   autoAtmosphere=autoAtmosphere*.84+nextAtmosphere*.16;
   updateStrengthUi();
  }catch(e){}
 }
 function fmt(sec){
  if(!isFinite(sec))return'00:00';
  sec=Math.max(0,Math.round(sec));
  return String(Math.floor(sec/60)).padStart(2,'0')+':'+String(sec%60).padStart(2,'0');
 }
 function setView(){
  const active=filter.checked;
  output.hidden=false;
  viewer.classList.toggle('two-frames',active&&viewMode==='two');
  $('viewSplit').classList.toggle('selected',viewMode==='split');
  $('viewFull').classList.toggle('selected',viewMode==='full');
  $('viewTwo').classList.toggle('selected',viewMode==='two');

  if(active&&viewMode==='two'){
   originalFig.hidden=false;
   caption.textContent='WebGL • оброблений кадр';
  }else{
   originalFig.hidden=true;
   caption.textContent=viewMode==='split'?'50/50 • Оригінал / WebGL':
      viewMode==='full'?'WebGL • повний кадр':'Оригінал';
  }
  canvas.style.display=active?'block':'none';
 }
 function showPhoto(){
  stopRendering();
  if(stream){stream.getTracks().forEach(t=>t.stop());stream=null;source.srcObject=null;}
  source.pause();
  $('videoPreview').hidden=true;
  $('photoPreview').hidden=false;
  $('photoControls').hidden=false;
  $('photoTab').classList.add('selected');$('videoTab').classList.remove('selected');
  $('photoTab').setAttribute('aria-pressed','true');$('videoTab').setAttribute('aria-pressed','false');
  $('photoQuick').hidden=false;$('videoQuick').hidden=true;
  const side=$('videoControlsPanel');if(side)side.hidden=true;
  const photos=$('photoControls');if(photos)photos.hidden=false;
 }
 function showVideo(){
  $('photoPreview').hidden=true;
  $('videoPreview').hidden=false;
  $('photoControls').hidden=true;
  $('photoTab').classList.remove('selected');$('videoTab').classList.add('selected');
  $('photoTab').setAttribute('aria-pressed','false');$('videoTab').setAttribute('aria-pressed','true');
  $('photoQuick').hidden=true;$('videoQuick').hidden=false;
  const side=$('videoControlsPanel');if(side)side.hidden=false;
  const photos=$('photoControls');if(photos)photos.hidden=true;
  setView();
  if(started&&!source.paused&&filter.checked)startRendering();
 }
 function shader(type,src){
  const sh=gl.createShader(type);
  gl.shaderSource(sh,src);gl.compileShader(sh);
  if(!gl.getShaderParameter(sh,gl.COMPILE_STATUS))throw new Error(gl.getShaderInfoLog(sh)||'GL shader error');
  return sh;
 }
 function initGL(){
  if(gl)return;
  gl=canvas.getContext('webgl',{alpha:false,depth:false,stencil:false,antialias:false,preserveDrawingBuffer:false,powerPreference:'high-performance'});
  if(!gl)throw new Error('WebGL недоступний у цьому браузері. Звичайне відео доступне без обробки.');
  const vs=shader(gl.VERTEX_SHADER,`attribute vec2 aPosition;
 varying vec2 vUV;
 void main(){vUV=(aPosition+1.0)*0.5;gl_Position=vec4(aPosition,0.0,1.0);}`);
  const fs=shader(gl.FRAGMENT_SHADER,`precision mediump float;
 varying vec2 vUV;
 uniform sampler2D uTex;
 uniform vec2 uPixel;
 uniform float uStrength;
 uniform float uSplit;
 uniform float uAtmosphere;
 uniform float uMax;
 uniform float uNight;
 float lum(vec3 c){return dot(c,vec3(.299,.587,.114));}
 float dc(vec3 c){return min(c.r,min(c.g,c.b));}
 void main(){
  vec3 c=texture2D(uTex,vUV).rgb;
  if(uSplit>.5&&vUV.x<.5){gl_FragColor=vec4(c,1.0);return;}
  if(uStrength<.001){gl_FragColor=vec4(c,1.0);return;}
  vec2 p1=uPixel*1.75,p4=uPixel*4.5;
  vec3 a=texture2D(uTex,vUV+vec2(p1.x,0.0)).rgb,b=texture2D(uTex,vUV-vec2(p1.x,0.0)).rgb;
  vec3 d=texture2D(uTex,vUV+vec2(0.0,p1.y)).rgb,e=texture2D(uTex,vUV-vec2(0.0,p1.y)).rgb;
  vec3 q1=texture2D(uTex,vUV+vec2(p4.x,p4.y)).rgb,q2=texture2D(uTex,vUV+vec2(-p4.x,p4.y)).rgb;
  vec3 q3=texture2D(uTex,vUV+vec2(p4.x,-p4.y)).rgb,q4=texture2D(uTex,vUV-vec2(p4.x,p4.y)).rgb;
  vec3 nearMean=(a+b+d+e)*.25,wideMean=(q1+q2+q3+q4)*.25;
  float y=lum(c),yn=lum(nearMean),yw=lum(wideMean);
  float e1=abs(y-yn)+length(c-nearMean)*.42;
  float e2=abs(yn-yw)+abs(y-yw)*.55;
  float structure=clamp(smoothstep(.004,.085,e1+.62*e2),0.0,1.0);
  float weakStructure=smoothstep(.003,.040,abs(y-yw))*(1.0-smoothstep(.12,.26,e1));
  float darkNear=min(dc(c),min(min(dc(a),dc(b)),min(dc(d),dc(e))));
  float darkWide=min(min(dc(q1),dc(q2)),min(dc(q3),dc(q4)));
  float A=clamp(uAtmosphere,.72,.96);
  float hazeNear=clamp(darkNear/max(A,.35),0.0,1.0);
  float hazeWide=clamp(min(darkNear,darkWide)/max(A,.35),0.0,1.0);
  float guidedHaze=mix(hazeWide,hazeNear,clamp(.10+.78*structure,0.0,1.0));
  float veil=clamp(guidedHaze*.78+(1.0-structure)*.12+y*.10,0.0,1.0);
  float objectMask=clamp(max(structure*.82,weakStructure)*smoothstep(.10,.74,veil),0.0,1.0);
  float s=clamp(uStrength*(1.0+.06*uMax),0.0,1.0);
  if(uNight>.5)s*=.88;
  float omega=clamp(mix(.68,.91,s)*(1.0+.035*uMax),.62,.94);
  float floorT=mix(.42,.23,s)-.035*objectMask*uMax+.035*uNight;
  float t=clamp(1.0-omega*veil,max(.19,floorT),1.0);
  vec3 Avec=vec3(A);
  vec3 recovered=clamp((c-Avec)/t+Avec,0.0,1.0);
  float dehazeMix=clamp(s*(.42+.45*smoothstep(.10,.78,veil)),0.0,.94);
  vec3 result=mix(c,recovered,dehazeMix);
  vec3 localMean=nearMean*.72+wideMean*.28;
  vec3 realDetail=clamp(c-localMean,vec3(-.085),vec3(.085));
  result+=realDetail*s*(.18+.70*objectMask+.18*uMax*objectMask);
  float localDelta=clamp(lum(result)-lum(localMean),-.07,.07);
  result+=vec3(localDelta)*s*(.08+.24*objectMask);
  float flat=1.0-smoothstep(.008,.055,e1);
  float denoise=s*(.035+.095*uNight)*flat*smoothstep(.25,.78,veil);
  result=mix(result,nearMean,clamp(denoise,0.0,.16));
  float ry=lum(result);
  float colorGain=1.0+.055*s+.095*objectMask*(1.0-.45*uNight);
  result=vec3(ry)+(result-vec3(ry))*colorGain;
  gl_FragColor=vec4(clamp(result,0.0,1.0),1.0);
 }`);
  program=gl.createProgram();gl.attachShader(program,vs);gl.attachShader(program,fs);gl.linkProgram(program);
  if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(program)||'GL link error');
  gl.useProgram(program);
  vbo=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,vbo);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,1,1]),gl.STATIC_DRAW);
  const loc=gl.getAttribLocation(program,'aPosition');gl.enableVertexAttribArray(loc);gl.vertexAttribPointer(loc,2,gl.FLOAT,false,0,0);
  texture=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,texture);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
  gl.uniform1i(gl.getUniformLocation(program,'uTex'),0);
  gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true);
 }
 function render(){
  if(!rendering||!filter.checked||source.paused||source.readyState<2||$('videoPreview').hidden)return;
  try{
   const width=source.videoWidth,height=source.videoHeight,maxSide=1920;
   if(!width||!height)return;
   const scale=Math.min(1,maxSide/Math.max(width,height));
   const w=Math.max(2,Math.round(width*scale)),h=Math.max(2,Math.round(height*scale));
   if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;gl.viewport(0,0,w,h);}
   gl.useProgram(program);gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,texture);
   gl.texImage2D(gl.TEXTURE_2D,0,gl.RGB,gl.RGB,gl.UNSIGNED_BYTE,source);
   gl.uniform2f(gl.getUniformLocation(program,'uPixel'),1/w,1/h);
   analyzeAutoStrength(performance.now());
   gl.uniform1f(gl.getUniformLocation(program,'uStrength'),currentStrength());
   gl.uniform1f(gl.getUniformLocation(program,'uSplit'),viewMode==='split'?1:0);
   gl.uniform1f(gl.getUniformLocation(program,'uAtmosphere'),autoAtmosphere);
   const flags=modeFlags();
   gl.uniform1f(gl.getUniformLocation(program,'uMax'),flags.max?1:0);
   gl.uniform1f(gl.getUniformLocation(program,'uNight'),flags.night?1:0);
   gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
   frameCounter++;
   const now=performance.now();
   if(now-lastFps>1000){
    $('videoFps').textContent=String(Math.round(frameCounter*1000/(now-lastFps)))+' FPS обробки';
    frameCounter=0;lastFps=now;
   }
  }catch(err){
   message('WebGL недоступний: '+err.message+' • показую оригінальне відео.');
   filter.checked=false;originalFig.hidden=false;viewer.classList.add('two-frames');canvas.style.display='none';stopRendering();
  }
 }
 function frameCallback(){requested=false;if(!rendering)return;render();requestFrame();}
 function requestFrame(){
  if(!rendering||requested||!filter.checked)return;
  requested=true;
  if(typeof source.requestVideoFrameCallback==='function')source.requestVideoFrameCallback(frameCallback);
  else fallbackRAF=requestAnimationFrame(frameCallback);
 }
 function startRendering(){
  if(!filter.checked||source.readyState<2||$('videoPreview').hidden)return;
  try{initGL();}
  catch(err){filter.checked=false;originalFig.hidden=false;viewer.classList.add('two-frames');canvas.style.display='none';message('WebGL недоступний: '+err.message+' • показую оригінальне відео.');return;}
  rendering=true;lastFps=performance.now();frameCounter=0;
  requestFrame();
 }
 function stopRendering(){
  rendering=false;
  if(fallbackRAF)cancelAnimationFrame(fallbackRAF);
  fallbackRAF=0;requested=false;
  $('videoFps').textContent='— FPS';
 }
 function updateTransport(){
  if(stream){
   seek.disabled=true;playPause.disabled=true;stopPlayback.disabled=true;time.textContent='LIVE';
   return;
  }
  seek.disabled=false;playPause.disabled=false;stopPlayback.disabled=false;
  const dur=source.duration||0,pos=source.currentTime||0;
  if(!seeking&&dur>0)seek.value=Math.round(pos/dur*1000);
  time.textContent=fmt(pos)+' / '+fmt(dur);
  playPause.textContent=source.paused?'▶':'Ⅱ';
 }
 async function openFile(file){
  if(!file)return;
  stopRendering();
  if(stream){stream.getTracks().forEach(t=>t.stop());stream=null;source.srcObject=null;}
  if(objectURL)URL.revokeObjectURL(objectURL);
  objectURL=URL.createObjectURL(file);
  source.src=objectURL;source.loop=false;source.controls=false;
  started=true;showVideo();
  message('Локальний файл: '+file.name+' — WebGL Adaptive Object Dehaze • AUTO готовий.');
  try{await source.play();if(filter.checked)startRendering();}
  catch(e){message('Натисни ▶, щоб почати перегляд.');}
  updateTransport();
 }
 async function openCamera(){
  if(!navigator.mediaDevices?.getUserMedia){message('Камера недоступна. Відкрий сайт через HTTPS та дозволь доступ.');return;}
  stopRendering();source.pause();
  if(objectURL){URL.revokeObjectURL(objectURL);objectURL=null;source.removeAttribute('src');}
  if(stream)stream.getTracks().forEach(t=>t.stop());
  try{
   stream=await navigator.mediaDevices.getUserMedia({
    video:{facingMode:{ideal:'environment'},width:{ideal:3840},height:{ideal:2160},frameRate:{ideal:30,max:60}},
    audio:false
   });
   const track=stream.getVideoTracks()[0];
   if(track&&track.getCapabilities&&track.applyConstraints){
    const caps=track.getCapabilities();
    const advanced={};
    if(caps.width&&caps.width.max)advanced.width={ideal:Math.min(3840,caps.width.max)};
    if(caps.height&&caps.height.max)advanced.height={ideal:Math.min(2160,caps.height.max)};
    if(caps.frameRate&&caps.frameRate.max)advanced.frameRate={ideal:Math.min(30,caps.frameRate.max)};
    try{await track.applyConstraints(advanced);}catch(_){}
   }
   source.srcObject=stream;source.controls=false;source.loop=false;started=true;showVideo();
   await source.play();
   const settings=track&&track.getSettings?track.getSettings():{};
   const res=settings.width&&settings.height?(' • '+settings.width+'×'+settings.height):'';
   message('Камера пристрою'+res+' • без примусового 16:9 crop • WebGL Adaptive Object Dehaze.');
   if(filter.checked)startRendering();
   updateTransport();
  }catch(e){message('Не вдалося запустити камеру: '+e.message);}
 }
 function stopVideo(){
  stopRendering();source.pause();
  if(stream){stream.getTracks().forEach(t=>t.stop());stream=null;source.srcObject=null;}
  if(objectURL){URL.revokeObjectURL(objectURL);objectURL=null;source.removeAttribute('src');source.load();}
  started=false;seek.value=0;updateTransport();message('Відео зупинено.');setView();
 }

 const headerPhoto=$('headerPhotoMax'),sidePhoto=$('sidePhotoMax'),webMenu=$('webMenu');
 if(headerPhoto)headerPhoto.onclick=()=>showPhoto();
 if(sidePhoto)sidePhoto.onclick=()=>showPhoto();
 if(webMenu)webMenu.onclick=()=>{
   document.body.classList.toggle('side-hidden');
 };
 $('photoTab').addEventListener('click',showPhoto);
 $('videoTab').addEventListener('click',showVideo);
 $('videoChoose').addEventListener('click',()=>$('videoFile').click());
 $('videoFile').addEventListener('change',e=>openFile(e.target.files[0]));
 $('videoCamera').addEventListener('click',openCamera);
 $('videoStop').addEventListener('click',stopVideo);

 $('viewSplit').onclick=()=>{viewMode='split';setView();};
 $('viewFull').onclick=()=>{viewMode='full';setView();};
 $('viewTwo').onclick=()=>{viewMode='two';setView();};

 function choosePreset(mode,value){
  presetMode=mode;
  if(mode==='OFF'){
   filter.checked=false;autoStrengthToggle.checked=false;stopRendering();setView();updateStrengthUi();
   message('OFF — показується оригінальне відео.');
   return;
  }
  filter.checked=true;
  if(mode==='AUTO'){
   autoStrengthToggle.checked=true;lastAutoAnalysis=0;
  }else{
   autoStrengthToggle.checked=false;
   strength.value=String(value);
  }
  updateStrengthUi();setView();
  message(mode==='AUTO'?'AUTO — щільність завіси і сила обробки визначаються з кадру.':mode+' — Adaptive Object Dehaze.');
  if(!source.paused)startRendering();
 }
 const presets=[
  ['videoModeOff','OFF',0],['videoModeAuto','AUTO',0],['videoModeLow','LOW',34],
  ['videoModeMedium','MEDIUM',52],['videoModeHigh','HIGH',70],['videoModeNight','NIGHT/MAX',82]
 ];
 presets.forEach(([id,name,value])=>{const el=$(id);if(el)el.onclick=()=>choosePreset(name,value);});

 filter.addEventListener('change',()=>{
  setView();
  if(filter.checked){if(presetMode==='OFF')presetMode='AUTO';message('Adaptive Object Dehaze увімкнено.');startRendering();}
  else{presetMode='OFF';autoStrengthToggle.checked=false;stopRendering();message('OFF — оригінальне відео без обробки.');}
  updateStrengthUi();
 });
 autoStrengthToggle.addEventListener('change',()=>{
  if(autoStrengthToggle.checked){presetMode='AUTO';lastAutoAnalysis=0;message('AUTO — сила підбирається за поточним кадром.');}
  else{presetMode='MANUAL';message('Ручна сила — використовуй повзунок.');}
  updateStrengthUi();
 });
 strength.addEventListener('input',()=>{if(!autoStrengthToggle.checked){presetMode='MANUAL';updateStrengthUi();}});

 playPause.onclick=async()=>{
  if(stream)return;
  try{if(source.paused)await source.play();else source.pause();updateTransport();}catch(e){}
 };
 stopPlayback.onclick=()=>{
  if(stream)return;
  source.pause();source.currentTime=0;updateTransport();
 };
 seek.addEventListener('input',()=>{
  seeking=true;
  if(!stream&&source.duration)time.textContent=fmt(source.duration*seek.value/1000)+' / '+fmt(source.duration);
 });
 seek.addEventListener('change',()=>{
  if(!stream&&source.duration)source.currentTime=source.duration*seek.value/1000;
  seeking=false;updateTransport();
 });

 source.addEventListener('playing',()=>{if(filter.checked)startRendering();updateTransport();});
 source.addEventListener('pause',()=>{stopRendering();updateTransport();});
 source.addEventListener('timeupdate',updateTransport);
 source.addEventListener('loadedmetadata',updateTransport);
 source.addEventListener('ended',()=>{stopRendering();updateTransport();});
 source.addEventListener('error',()=>message('Цей відеоформат браузер не підтримує. Спробуй MP4 (H.264).'));
 document.addEventListener('visibilitychange',()=>{if(document.hidden)stopRendering();else if(filter.checked&&!source.paused)startRendering();});
 window.dehazeShowPhoto=showPhoto;
 showVideo();updateTransport();updateStrengthUi();
})();