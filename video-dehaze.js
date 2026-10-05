'use strict';
/* Digital Dehazing LIVE v5
   One source -> one WebGL output. View modes: 50/50, processed full, two frames.
   AUTO HYBRID keeps the preferred gentle web look and escalates only when haze persists.
   No generative AI and no synthetic object insertion. */
(() => {
 const $=id=>document.getElementById(id);
 const source=$('videoSource'),canvas=$('videoAfter'),status=$('videoStatus');
 const output=$('videoProcessedFig'),viewer=$('videoViewer'),caption=$('videoProcessedCaption');
 const filter=$('videoEnabled'),strength=$('videoStrength'),readout=$('videoStrengthVal');
 const seek=$('videoSeek'),time=$('videoTime'),playPause=$('videoPlayPause'),stopPlayback=$('videoStopPlayback');
 let stream=null,objectURL=null,gl=null,program=null,texture=null,vbo=null;
 let started=false,rendering=false,requested=false,fallbackRAF=0,frameCounter=0,lastFps=0;
 let viewMode='split',preset='auto',autoStrength=.45,autoHybrid=0,autoLevel=1,autoNight=false;
 let lastAnalysis=0,pendingLevel=1,pendingCount=0,seeking=false;
 const analysis=document.createElement('canvas');analysis.width=64;analysis.height=36;
 const actx=analysis.getContext('2d',{willReadFrequently:true});
 const clamp=(v,a=0,b=1)=>Math.max(a,Math.min(b,v));
 const levelNames=['CLEAR','LIGHT','MEDIUM','STRONG','ADAPTIVE OBJECT'];

 function message(s){status.textContent=s;}
 function fmt(sec){if(!isFinite(sec))return'00:00';sec=Math.max(0,Math.round(sec));return String(Math.floor(sec/60)).padStart(2,'0')+':'+String(sec%60).padStart(2,'0');}
 function activeStrength(){
  if(preset==='low')return .34;
  if(preset==='medium')return .53;
  if(preset==='high')return .72;
  if(preset==='night')return .46;
  return autoStrength;
 }
 function hybridAmount(){
  if(preset==='high')return .85;
  if(preset==='night')return .22;
  if(preset!=='auto')return 0;
  return autoHybrid;
 }
 function isNight(){return preset==='night'||(preset==='auto'&&autoNight);}
 function updatePresetUi(){
  ['Auto','Low','Medium','High','Night'].forEach(n=>{
   const el=$('video'+n); if(el)el.classList.toggle('selected',preset===n.toLowerCase());
  });
  strength.disabled=preset==='auto';
  const pct=Math.round(activeStrength()*100);
  strength.value=pct;
  readout.textContent=preset==='auto'?'AUTO '+levelNames[autoLevel]+' • '+pct+'%':preset.toUpperCase()+' • '+pct+'%';
 }
 function setPreset(p){
  preset=p;
  if(p!=='auto'){
   const fixed={low:34,medium:53,high:72,night:46}[p]||45;
   strength.value=fixed;
  }
  updatePresetUi();message(p==='auto'?'AUTO HYBRID увімкнено. Алгоритм перемикається плавно за станом кадру.':p.toUpperCase()+' зафіксовано вручну.');
 }
 function setView(){
  const active=filter.checked;
  viewer.classList.toggle('original-only',!active);
  viewer.classList.toggle('two-frames',active&&viewMode==='two');
  output.hidden=!active;
  $('videoOriginalFig').style.display=active&&viewMode==='two'?'block':'none';
  $('viewSplit').classList.toggle('selected',viewMode==='split');
  $('viewFull').classList.toggle('selected',viewMode==='full');
  $('viewTwo').classList.toggle('selected',viewMode==='two');
  caption.textContent=viewMode==='split'?'50/50 • Оригінал / Digital Dehazing':
      viewMode==='full'?'Digital Dehazing • повний кадр':'Digital Dehazing • оброблений кадр';
 }
 function showPhoto(){
  stopRendering();
  if(stream){stream.getTracks().forEach(t=>t.stop());stream=null;source.srcObject=null;}
  source.pause();$('videoPreview').hidden=true;$('photoPreview').hidden=false;$('photoControls').hidden=false;
  $('photoTab').classList.add('selected');$('videoTab').classList.remove('selected');
  $('photoTab').setAttribute('aria-pressed','true');$('videoTab').setAttribute('aria-pressed','false');
  $('photoQuick').hidden=false;$('videoQuick').hidden=true;
 }
 function showVideo(){
  $('photoPreview').hidden=true;$('videoPreview').hidden=false;$('photoControls').hidden=true;
  $('photoTab').classList.remove('selected');$('videoTab').classList.add('selected');
  $('photoTab').setAttribute('aria-pressed','false');$('videoTab').setAttribute('aria-pressed','true');
  $('photoQuick').hidden=true;$('videoQuick').hidden=false;setView();
  if(started&&!source.paused&&filter.checked)startRendering();
 }
 function shader(type,src){
  const sh=gl.createShader(type);gl.shaderSource(sh,src);gl.compileShader(sh);
  if(!gl.getShaderParameter(sh,gl.COMPILE_STATUS))throw new Error(gl.getShaderInfoLog(sh)||'GL shader error');
  return sh;
 }
 function initGL(){
  if(gl)return;
  gl=canvas.getContext('webgl',{alpha:false,depth:false,stencil:false,antialias:false,preserveDrawingBuffer:false,powerPreference:'high-performance'});
  if(!gl)throw new Error('WebGL недоступний у цьому браузері.');
  const vs=shader(gl.VERTEX_SHADER,`attribute vec2 aPosition;varying vec2 vUV;void main(){vUV=(aPosition+1.0)*0.5;gl_Position=vec4(aPosition,0.0,1.0);}`);
  const fs=shader(gl.FRAGMENT_SHADER,`precision mediump float;
 varying vec2 vUV;uniform sampler2D uTex;uniform vec2 uPixel;uniform float uStrength;uniform float uHybrid;uniform float uSplit;uniform float uNight;
 vec3 grab(vec2 p){return texture2D(uTex,clamp(p,vec2(.001),vec2(.999))).rgb;}
 float lum(vec3 c){return dot(c,vec3(.299,.587,.114));}
 float dc(vec3 c){return min(c.r,min(c.g,c.b));}
 void main(){
  vec3 c=grab(vUV);
  if(uSplit>.5&&vUV.x<.5){gl_FragColor=vec4(c,1.);return;}
  float s=clamp(uStrength,0.,1.),night=step(.5,uNight);
  vec2 p=uPixel*2.0,p4=uPixel*4.0;
  vec3 a=grab(vUV+vec2(p.x,0.)),b=grab(vUV-vec2(p.x,0.)),d=grab(vUV+vec2(0.,p.y)),e=grab(vUV-vec2(0.,p.y));
  vec3 q1=grab(vUV+vec2(p4.x,p4.y)),q2=grab(vUV+vec2(-p4.x,p4.y)),q3=grab(vUV+vec2(p4.x,-p4.y)),q4=grab(vUV-vec2(p4.x,p4.y));
  vec3 local=(a+b+d+e)*.25,wide=(q1+q2+q3+q4)*.25;
  float y=lum(c),edge=clamp(length(c-local)*3.0+abs(y-lum(wide))*1.8,0.,1.);
  float sky=smoothstep(.62,.86,y)*(1.-smoothstep(.015,.085,edge))*smoothstep(.16,.90,vUV.y);
  float mask=1.-.88*sky;
  // Gentle web look: restrained veil removal + local contrast.
  float tw=clamp(1.-s*(.22+.14*y),.58,1.);
  vec3 Aw=vec3(.83);vec3 web=clamp((c-Aw)/tw+Aw,0.,1.);
  web=mix(c,web,.74*mask);web+=clamp(c-local,-.115,.115)*(.34*s*mask);
  float ly=lum(local);web=vec3(ly)+(web-vec3(ly))*(1.+.10*s*mask);
  // Adaptive path: only blended in by AUTO for persistent strong haze.
  float dark=dc(c);dark=min(dark,min(min(dc(a),dc(b)),min(dc(d),dc(e))));dark=min(dark,min(min(dc(q1),dc(q2)),min(dc(q3),dc(q4))));
  float flat=1.-smoothstep(.025,.16,edge),haze=clamp(dark*.70+y*.17+flat*.13,0.,1.);
  float floorT=mix(.38,.23,s);floorT=mix(floorT,max(floorT,.34),night);
  float ta=clamp(1.-mix(.66,.88,s)*haze,floorT,1.);
  float weakReal=smoothstep(.014,.075,edge)*(1.-smoothstep(.20,.38,edge))*smoothstep(.28,.86,haze);
  ta=max(.18,ta-weakReal*.07*s*(1.-.45*night));
  vec3 A=mix(vec3(.82),vec3(.93),clamp(haze*.8+.1,0.,1.));
  vec3 adaptive=clamp((c-A)/ta+A,0.,1.);
  adaptive+=clamp(c-local,-.095,.095)*(s*(.30+.46*weakReal)*(1.-.45*night));
  float ay=lum(local);adaptive=vec3(ay)+(adaptive-vec3(ay))*(1.+s*(.10+.20*weakReal));
  adaptive=mix(adaptive,local,flat*haze*(.04+.10*night));
  float h=clamp(uHybrid,0.,1.);vec3 result=mix(web,adaptive,h);
  gl_FragColor=vec4(clamp(mix(c,result,clamp(.32+.64*s,0.,.96)),0.,1.),1.);
 }`);
  program=gl.createProgram();gl.attachShader(program,vs);gl.attachShader(program,fs);gl.linkProgram(program);
  if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(program)||'GL link error');
  gl.useProgram(program);vbo=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,vbo);
  gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,1,1]),gl.STATIC_DRAW);
  const loc=gl.getAttribLocation(program,'aPosition');gl.enableVertexAttribArray(loc);gl.vertexAttribPointer(loc,2,gl.FLOAT,false,0,0);
  texture=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,texture);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
  gl.uniform1i(gl.getUniformLocation(program,'uTex'),0);gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true);
 }
 function analyze(now){
  if(preset!=='auto'||now-lastAnalysis<1200||source.readyState<2)return;
  lastAnalysis=now;
  try{
   actx.drawImage(source,0,0,64,36);const d=actx.getImageData(0,0,64,36).data,hist=new Uint32Array(256),prev=new Int16Array(64);
   let sum=0,edge=0,edges=0,sat=0;
   for(let y=0;y<36;y++){let left=0;for(let x=0;x<64;x++){const i=(y*64+x)*4,r=d[i],g=d[i+1],b=d[i+2],L=Math.min(255,Math.round(.299*r+.587*g+.114*b));hist[L]++;sum+=L;sat+=Math.max(r,g,b)-Math.min(r,g,b);if(x){edge+=Math.abs(L-left);edges++;}if(y){edge+=Math.abs(L-prev[x]);edges++;}left=L;prev[x]=L;}}
   const n=64*36;let acc=0,p10=0,p90=255,got10=false;
   for(let i=0;i<256;i++){acc+=hist[i];if(!got10&&acc>=n*.10){p10=i;got10=true;}if(acc>=n*.90){p90=i;break;}}
   const mean=sum/n,edgeMean=edge/Math.max(1,edges),satMean=sat/n;
   const contrast=clamp((100-(p90-p10))/100),texture=clamp((18-edgeMean)/18),gray=clamp((48-satMean)/48);
   const haze=clamp(contrast*.47+texture*.33+gray*.20);
   let candidate=haze<.22?0:haze<.40?1:haze<.57?2:haze<.72?3:4;
   if(candidate===pendingLevel)pendingCount++;else{pendingLevel=candidate;pendingCount=1;}
   if(pendingCount>=2||Math.abs(candidate-autoLevel)>=2){autoLevel=candidate;pendingCount=0;}
   const targets=[.12,.30,.46,.62,.78],hybrids=[0,0,0,.28,.82];
   autoNight=mean<58;
   let target=targets[autoLevel],hyb=hybrids[autoLevel];
   if(autoNight){target=Math.max(.24,target*.78);hyb*=.55;}
   autoStrength=autoStrength*.78+target*.22;autoHybrid=autoHybrid*.76+hyb*.24;
   updatePresetUi();
  }catch(e){}
 }
 function render(){
  if(!rendering||!filter.checked||source.paused||source.readyState<2||$('videoPreview').hidden)return;
  try{
   const width=source.videoWidth,height=source.videoHeight,maxSide=1920;if(!width||!height)return;
   const scale=Math.min(1,maxSide/Math.max(width,height)),w=Math.max(2,Math.round(width*scale)),h=Math.max(2,Math.round(height*scale));
   if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;gl.viewport(0,0,w,h);}
   const now=performance.now();analyze(now);
   gl.useProgram(program);gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,texture);
   gl.texImage2D(gl.TEXTURE_2D,0,gl.RGB,gl.RGB,gl.UNSIGNED_BYTE,source);
   gl.uniform2f(gl.getUniformLocation(program,'uPixel'),1/w,1/h);
   gl.uniform1f(gl.getUniformLocation(program,'uStrength'),activeStrength());
   gl.uniform1f(gl.getUniformLocation(program,'uHybrid'),hybridAmount());
   gl.uniform1f(gl.getUniformLocation(program,'uSplit'),viewMode==='split'?1:0);
   gl.uniform1f(gl.getUniformLocation(program,'uNight'),isNight()?1:0);
   gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
   frameCounter++;if(now-lastFps>1000){$('videoFps').textContent=Math.round(frameCounter*1000/(now-lastFps))+' FPS • '+(preset==='auto'?levelNames[autoLevel]:preset.toUpperCase());frameCounter=0;lastFps=now;}
  }catch(err){message('Помилка відеообробки: '+err.message);filter.checked=false;setView();stopRendering();}
 }
 function frameCallback(){requested=false;if(!rendering)return;render();requestFrame();}
 function requestFrame(){if(!rendering||requested||!filter.checked)return;requested=true;if(typeof source.requestVideoFrameCallback==='function')source.requestVideoFrameCallback(frameCallback);else fallbackRAF=requestAnimationFrame(frameCallback);}
 function startRendering(){if(!filter.checked||source.readyState<2||$('videoPreview').hidden)return;try{initGL();}catch(err){filter.checked=false;setView();message(err.message);return;}rendering=true;lastFps=performance.now();frameCounter=0;requestFrame();}
 function stopRendering(){rendering=false;if(fallbackRAF)cancelAnimationFrame(fallbackRAF);fallbackRAF=0;requested=false;$('videoFps').textContent='— FPS';}
 function updateTransport(){
  if(stream){seek.disabled=true;playPause.disabled=true;stopPlayback.disabled=true;time.textContent='LIVE';return;}
  seek.disabled=false;playPause.disabled=false;stopPlayback.disabled=false;
  const dur=source.duration||0,pos=source.currentTime||0;if(!seeking&&dur>0)seek.value=Math.round(pos/dur*1000);time.textContent=fmt(pos)+' / '+fmt(dur);playPause.textContent=source.paused?'▶':'Ⅱ';
 }
 async function openFile(file){
  if(!file)return;stopRendering();if(stream){stream.getTracks().forEach(t=>t.stop());stream=null;source.srcObject=null;}
  if(objectURL)URL.revokeObjectURL(objectURL);objectURL=URL.createObjectURL(file);source.src=objectURL;source.loop=false;source.controls=false;started=true;showVideo();
  message('Локальний файл: '+file.name+' • AUTO HYBRID • обробка тільки на пристрої.');
  try{await source.play();startRendering();}catch(e){message('Натисни ▶ для запуску відео.');}updateTransport();
 }
 async function openCamera(){
  if(!navigator.mediaDevices?.getUserMedia){message('Камера недоступна. Відкрий сайт через HTTPS та дозволь доступ.');return;}
  stopRendering();source.pause();if(objectURL){URL.revokeObjectURL(objectURL);objectURL=null;source.removeAttribute('src');}if(stream)stream.getTracks().forEach(t=>t.stop());
  try{
   stream=await navigator.mediaDevices.getUserMedia({video:{facingMode:'environment',width:{ideal:1920},height:{ideal:1080}},audio:false});
   source.srcObject=stream;source.controls=false;source.loop=false;started=true;showVideo();await source.play();message('LIVE камера • AUTO HYBRID • локальна GPU-обробка.');startRendering();updateTransport();
  }catch(e){message('Не вдалося запустити камеру: '+e.message);}
 }
 function stopVideo(){stopRendering();source.pause();if(stream){stream.getTracks().forEach(t=>t.stop());stream=null;source.srcObject=null;}if(objectURL){URL.revokeObjectURL(objectURL);objectURL=null;source.removeAttribute('src');source.load();}started=false;seek.value=0;updateTransport();message('Відео зупинено.');}
 $('photoTab').addEventListener('click',showPhoto);$('videoTab').addEventListener('click',showVideo);
 $('videoChoose').addEventListener('click',()=>$('videoFile').click());$('videoFile').addEventListener('change',e=>openFile(e.target.files[0]));
 $('videoCamera').addEventListener('click',openCamera);$('videoStop').addEventListener('click',stopVideo);
 $('viewSplit').onclick=()=>{viewMode='split';setView();};$('viewFull').onclick=()=>{viewMode='full';setView();};$('viewTwo').onclick=()=>{viewMode='two';setView();};
 $('videoAuto').onclick=()=>setPreset('auto');$('videoLow').onclick=()=>setPreset('low');$('videoMedium').onclick=()=>setPreset('medium');$('videoHigh').onclick=()=>setPreset('high');$('videoNight').onclick=()=>setPreset('night');
 filter.addEventListener('change',()=>{setView();if(filter.checked){message('Антитуман увімкнено.');startRendering();}else{stopRendering();message('Антитуман вимкнено.');}});
 strength.addEventListener('input',()=>{if(preset==='auto')return;readout.textContent=preset.toUpperCase()+' • '+strength.value+'%';});
 playPause.onclick=async()=>{if(stream)return;try{if(source.paused)await source.play();else source.pause();updateTransport();}catch(e){}};
 stopPlayback.onclick=()=>{if(stream)return;source.pause();source.currentTime=0;updateTransport();};
 seek.addEventListener('input',()=>{seeking=true;if(!stream&&source.duration)time.textContent=fmt(source.duration*seek.value/1000)+' / '+fmt(source.duration);});
 seek.addEventListener('change',()=>{if(!stream&&source.duration)source.currentTime=source.duration*seek.value/1000;seeking=false;updateTransport();});
 source.addEventListener('playing',()=>{if(filter.checked)startRendering();updateTransport();});source.addEventListener('pause',()=>{stopRendering();updateTransport();});
 source.addEventListener('timeupdate',updateTransport);source.addEventListener('loadedmetadata',updateTransport);source.addEventListener('ended',()=>{stopRendering();updateTransport();});
 source.addEventListener('error',()=>message('Цей відеоформат браузер не підтримує. Спробуй MP4 (H.264).'));
 document.addEventListener('visibilitychange',()=>{if(document.hidden)stopRendering();else if(filter.checked&&!source.paused)startRendering();});
 window.dehazeShowPhoto=showPhoto;setPreset('auto');setView();updateTransport();
})();