'use strict';
/* Video preview: one-pass WebGL processing of local MP4/WebM or browser camera.
   No upload, no remote dependency, no frame queue, no claim of true multi-scale Fusion. */
(() => {
 const $=id=>document.getElementById(id);
 const source=$('videoSource'),canvas=$('videoAfter'),status=$('videoStatus');
 const output=$('videoProcessedFig'),viewer=$('videoViewer');
 const filter=$('videoEnabled'),strength=$('videoStrength'),readout=$('videoStrengthVal');
 let stream=null,objectURL=null,gl=null,program=null,texture=null,vbo=null;
 let enabled=false,started=false,rendering=false,requested=false,fallbackRAF=0;
 let frameCounter=0,lastFps=0;
 function message(s){status.textContent=s;}
 function setView(){
  const active=filter.checked;
  viewer.classList.toggle('original-only',!active);
  output.hidden=!active;
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
 }
 function showVideo(){
  $('photoPreview').hidden=true;
  $('videoPreview').hidden=false;
  $('photoControls').hidden=true;
  $('photoTab').classList.remove('selected');$('videoTab').classList.add('selected');
  $('photoTab').setAttribute('aria-pressed','false');$('videoTab').setAttribute('aria-pressed','true');
  $('photoQuick').hidden=true;$('videoQuick').hidden=false;
  setView();
  if(started&&source.paused===false&&filter.checked)startRendering();
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
 void main(){
  vec3 c=texture2D(uTex,vUV).rgb;
  vec3 local=(texture2D(uTex,vUV+vec2(uPixel.x*2.0,0.0)).rgb+
              texture2D(uTex,vUV-vec2(uPixel.x*2.0,0.0)).rgb+
              texture2D(uTex,vUV+vec2(0.0,uPixel.y*2.0)).rgb+
              texture2D(uTex,vUV-vec2(0.0,uPixel.y*2.0)).rgb)*0.25;
  float l=dot(c,vec3(.299,.587,.114));
  float edge=length(c-local);
  // Protect near-uniform bright regions, typically sky, to limit artifacts.
  float sky=smoothstep(.62,.84,l)*(1.0-smoothstep(.01,.065,edge))*smoothstep(.18,.88,vUV.y);
  float mask=1.0-sky*.95;
  // Fast local contrast and restrained atmospheric-veil correction.
  float t=1.0-uStrength*(.26+.16*l);
  vec3 recovered=clamp((c-vec3(.83)) / max(t,.57)+vec3(.83),0.0,1.0);
  vec3 enhanced=mix(c,recovered,mask*.84);
  enhanced+=clamp(c-local,-.13,.13)*(.40*uStrength*mask);
  gl_FragColor=vec4(clamp(enhanced,0.0,1.0),1.0);
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
   const width=source.videoWidth,height=source.videoHeight,maxSide=1280;
   if(!width||!height)return;
   const scale=Math.min(1,maxSide/Math.max(width,height));
   const w=Math.max(2,Math.round(width*scale)),h=Math.max(2,Math.round(height*scale));
   if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;gl.viewport(0,0,w,h);}
   gl.useProgram(program);gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,texture);
   gl.texImage2D(gl.TEXTURE_2D,0,gl.RGB,gl.RGB,gl.UNSIGNED_BYTE,source);
   gl.uniform2f(gl.getUniformLocation(program,'uPixel'),1/w,1/h);
   gl.uniform1f(gl.getUniformLocation(program,'uStrength'),Number(strength.value)/100);
   gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
   frameCounter++;
   const now=performance.now();
   if(now-lastFps>1000){$('videoFps').textContent=String(Math.round(frameCounter*1000/(now-lastFps)))+' FPS обробки';frameCounter=0;lastFps=now;}
  }catch(err){
   message('Помилка відеообробки: '+err.message);
   filter.checked=false;setView();stopRendering();
  }
 }
 function frameCallback(){
  requested=false;
  if(!rendering)return;
  render();
  requestFrame();
 }
 function requestFrame(){
  if(!rendering||requested||!filter.checked)return;
  requested=true;
  if(typeof source.requestVideoFrameCallback==='function'){
   source.requestVideoFrameCallback(frameCallback);
  }else{
   fallbackRAF=requestAnimationFrame(frameCallback);
  }
 }
 function startRendering(){
  if(!filter.checked||source.readyState<2||$('videoPreview').hidden)return;
  try{initGL();}
  catch(err){filter.checked=false;setView();message(err.message);return;}
  rendering=true;lastFps=performance.now();frameCounter=0;
  requestFrame();
 }
 function stopRendering(){
  rendering=false;
  if(fallbackRAF)cancelAnimationFrame(fallbackRAF);
  fallbackRAF=0;requested=false;
  $('videoFps').textContent='— FPS';
 }
 async function openFile(file){
  if(!file)return;
  stopRendering();if(stream){stream.getTracks().forEach(t=>t.stop());stream=null;source.srcObject=null;}
  if(objectURL)URL.revokeObjectURL(objectURL);
  objectURL=URL.createObjectURL(file);source.src=objectURL;source.loop=true;source.controls=true;
  started=true;showVideo();
  message('Локальний файл: '+file.name+' — відео не надсилається в інтернет.');
  try{await source.play();if(filter.checked)startRendering();}
  catch(e){message('Натисни Play на відео, щоб почати перегляд.');}
 }
 async function openCamera(){
  if(!navigator.mediaDevices?.getUserMedia){message('Камера недоступна. Відкрий сайт через HTTPS та дозволь доступ.');return;}
  stopRendering();source.pause();
  if(objectURL){URL.revokeObjectURL(objectURL);objectURL=null;source.removeAttribute('src');}
  if(stream)stream.getTracks().forEach(t=>t.stop());
  try{
   stream=await navigator.mediaDevices.getUserMedia({video:{facingMode:'environment',width:{ideal:1280},height:{ideal:720}},audio:false});
   source.srcObject=stream;source.controls=false;source.loop=false;started=true;showVideo();
   await source.play();
   message('Камера пристрою • обробка локально, без завантаження кадрів.');
   if(filter.checked)startRendering();
  }catch(e){message('Не вдалося запустити камеру: '+e.message);}
 }
 function stopVideo(){
  stopRendering();source.pause();
  if(stream){stream.getTracks().forEach(t=>t.stop());stream=null;source.srcObject=null;}
  if(objectURL){URL.revokeObjectURL(objectURL);objectURL=null;source.removeAttribute('src');source.load();}
  started=false;message('Відео зупинено.');setView();
 }
 $('photoTab').addEventListener('click',showPhoto);
 $('videoTab').addEventListener('click',showVideo);
 $('videoChoose').addEventListener('click',()=>$('videoFile').click());
 $('videoFile').addEventListener('change',e=>openFile(e.target.files[0]));
 $('videoCamera').addEventListener('click',openCamera);
 $('videoStop').addEventListener('click',stopVideo);
 filter.addEventListener('change',()=>{
  setView();
  if(filter.checked){message('Антитуман увімкнено — швидка GPU-обробка кадрів.');startRendering();}
  else{stopRendering();message('Антитуман вимкнено — оригінальне відео без обробки.');}
 });
 strength.addEventListener('input',()=>readout.textContent=strength.value+'%');
 source.addEventListener('playing',()=>{if(filter.checked)startRendering();});
 source.addEventListener('pause',stopRendering);
 source.addEventListener('ended',stopRendering);
 source.addEventListener('error',()=>message('Цей відеоформат браузер не підтримує. Спробуй MP4 (H.264).'));
 document.addEventListener('visibilitychange',()=>{if(document.hidden)stopRendering();else if(filter.checked)startRendering();});
 window.dehazeShowPhoto=showPhoto;
 setView();
})();