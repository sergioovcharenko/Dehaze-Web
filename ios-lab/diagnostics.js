(()=>{
'use strict';
const $=id=>document.getElementById(id);
const modules=[
  {id:'camera',label:'Камера / декодер',bit:0},
  {id:'gpu',label:'GPU / WebGL',bit:0},
  {id:'dcp',label:'Multi-scale DCP',bit:1},
  {id:'guided',label:'Guided Filter',bit:2},
  {id:'clahe',label:'Адаптивний CLAHE',bit:4},
  {id:'retinex',label:'Retinex',bit:8},
  {id:'temporal',label:'Temporal Filter',bit:16},
  {id:'fusion',label:'Image Fusion',bit:32}
];
let flags=63;
try{const saved=localStorage.getItem('meti-lab-flags');if(saved!==null)flags=Number(saved)&63;}
catch(_){}
const status={},logs=[];
let gpuSafe=false,testing=false,workerErrorCount=0;
const stamp=()=>new Date().toLocaleTimeString('uk-UA');
function log(item){
  logs.push(stamp()+'  '+item);
  if(logs.length>60)logs.shift();
}
function event(id,state,info='',time=null){
  status[id]={state,info:String(info).slice(0,140),time,at:Date.now()};
  if(state==='ПОМИЛКА'||state==='РЕЗЕРВНИЙ')log(id+': '+info);
  draw();
}
function enabled(id){
  const m=modules.find(x=>x.id===id);
  return m?m.bit===0||!!(flags&m.bit):false;
}
function set(id,checked){
  const m=modules.find(x=>x.id===id);
  if(!m||!m.bit)return;
  flags=checked?(flags|m.bit):(flags&~m.bit);
  try{localStorage.setItem('meti-lab-flags',String(flags));}catch(_){}
  event(id,checked?'ОЧІКУЄ':'ВИМКНЕНО',checked?'Очікуємо нові кадри':'Вимкнено вручну');
  log(m.label+': '+(checked?'ON':'OFF'));
}
function draw(){
  const root=$('labModules');
  if(!root)return;
  for(const m of modules){
    let node=root.querySelector('[data-name="'+m.id+'"]');
    if(!node){
      node=document.createElement('div');node.dataset.name=m.id;node.className='lab-module';
      const heading=document.createElement('div');heading.className='lab-module-heading';
      const title=document.createElement('b');title.textContent=m.label;
      heading.append(title);
      if(m.bit){
        const label=document.createElement('label');label.className='lab-switch';
        const checkbox=document.createElement('input');
        checkbox.type='checkbox';checkbox.checked=enabled(m.id);
        checkbox.addEventListener('change',()=>set(m.id,checkbox.checked));
        const text=document.createElement('span');text.textContent='УВІМКНЕНО';
        label.append(checkbox,text);heading.append(label);
      }
      node.append(heading);
      const details=document.createElement('div');details.className='lab-detail';
      node.append(details);root.append(node);
    }
    const v=status[m.id]||{state:'НЕ ПЕРЕВІРЕНО',info:'Потрібна камера пристрою'};
    const label=node.querySelector('.lab-detail'),toggle=node.querySelector('input');
    if(toggle){toggle.checked=enabled(m.id);node.classList.toggle('disabled',!enabled(m.id));}
    label.textContent=v.state+(v.time===null||v.time===undefined?'':' • '+Math.round(v.time)+' мс')+
      (v.info?' — '+v.info:'');
    node.dataset.status=v.state;
  }
  const test=$('labTestStatus');
  if(test)test.textContent=testing?'Виконується…':test.textContent||'Не запускався';
  const latest=$('labEvents');
  if(latest)latest.textContent=logs.slice(-14).reverse().join('\n')||'Подій ще немає';
}
function report(){
  let out='МЕТІ ТУМАН LAB — iPhone/iPad\n'+new Date().toISOString()+'\n'+
    'User Agent: '+navigator.userAgent+'\n'+
    'Кадри і фото не включено до звіту.\n'+
    'Режим GPU SAFE: '+gpuSafe+'\n\n';
  out+='САМОТЕСТ: '+($('labTestStatus')?.textContent||'Не запускали')+'\n\n';
  for(const m of modules){
    const value=status[m.id]||{state:'НЕ ПЕРЕВІРЕНО',info:''};
    out+=m.label+': '+(enabled(m.id)?value.state:'ВИМКНЕНО')+
      (value.time==null?'':' '+Math.round(value.time)+' мс')+
      (value.info?' — '+value.info:'')+'\n';
  }
  return out+'\nЖУРНАЛ\n'+logs.join('\n')+'\n';
}
function exportReport(){
  const blob=new Blob([report()],{type:'text/plain;charset=utf-8'});
  const file=new File([blob],'Meti-Tuman-LAB-report.txt',{type:'text/plain'});
  if(navigator.share&&navigator.canShare?.({files:[file]})){
    navigator.share({files:[file],title:'Меті Туман LAB — технічний звіт'})
      .catch(e=>{if(e.name!=='AbortError')download(blob);});
  }else download(blob);
}
function download(blob){
  const url=URL.createObjectURL(blob);
  const link=document.createElement('a');link.href=url;link.download='Meti-Tuman-LAB-report.txt';
  document.body.append(link);link.click();link.remove();
  setTimeout(()=>URL.revokeObjectURL(url),15000);
}
function selfTest(){
  if(testing)return;
  testing=true;draw();
  $('labTestStatus').textContent='Самотест алгоритмів на контрольних кадрах…';
  let w=null,timeout=0,finished=false;
  const done=()=>{
    if(finished)return false;
    finished=true;testing=false;
    clearTimeout(timeout);w?.terminate();draw();return true;
  };
  try{
    w=new Worker('./video-worker.js');
    w.onmessage=e=>{
      if(e.data.type!=='selfTest'||!done())return;
      if(e.data.error){
        $('labTestStatus').textContent='ПОМИЛКА: '+e.data.error;log('Самотест: '+e.data.error);
        return;
      }
      for(const [key,value] of Object.entries(e.data.checks)){
        const msg=String(value),passed=msg.startsWith('OK')||msg.startsWith('CPU OK');
        event(key,passed?'САМОТЕСТ OK':'САМОТЕСТ ПОМИЛКА',msg);
      }
      $('labTestStatus').textContent='Завершено • окремо перевір камеру й GPU у LIVE';
      log('Синтетичний самотест завершено. Камера й GPU потребують фізичного тесту.');
    };
    w.onerror=e=>{
      if(!done())return;
      $('labTestStatus').textContent='ПОМИЛКА воркера: '+(e.message||'невідома');
      log('Worker selftest: '+e.message);
    };
    timeout=setTimeout(()=>{
      if(!done())return;
      $('labTestStatus').textContent='ТАЙМАУТ: перевір швидкодію пристрою';
      log('Самотест перевищив 20 с');
    },20000);
    w.postMessage({selfTest:true});
  }catch(e){
    done();$('labTestStatus').textContent='Помилка запуску: '+e.message;log(String(e));
  }
}
window.MetiLab={
  get flags(){return flags;},enabled,set,event,log,
  get gpuSafe(){return gpuSafe;},
  get workerErrorCount(){return workerErrorCount;},
  workerError(message){
    workerErrorCount++;
    event('dcp','РЕЗЕРВНИЙ',message);
  },
  setGpuSafe(value){
    gpuSafe=!!value;
    const btn=$('labSafe');
    if(btn)btn.textContent=gpuSafe?'GPU FAST (SAFE ON)':'GPU SAFE';
    event('gpu',gpuSafe?'РЕЗЕРВНИЙ':'ОЧІКУЄ',
      gpuSafe?'GPU MAX вимкнуто вручну':'Очікуємо новий кадр');
    log('GPU SAFE: '+gpuSafe);
  }
};
for(const m of modules)event(m.id,'НЕ ПЕРЕВІРЕНО','Самотест ще не запускали');
$('labButton')?.addEventListener('click',()=>{
  $('menuButton')?.click();
  $('labDiagnostics')?.scrollIntoView({behavior:'smooth',block:'start'});
});
$('labSelfTest')?.addEventListener('click',selfTest);
$('labExport')?.addEventListener('click',exportReport);
$('labSafe')?.addEventListener('click',()=>window.MetiLab.setGpuSafe(!gpuSafe));
draw();
})();