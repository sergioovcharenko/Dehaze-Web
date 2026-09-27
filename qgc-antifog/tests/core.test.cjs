const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const core={};const file=path.join(__dirname,'../qml/ClassicCore.js');
if(fs.existsSync(file))vm.runInNewContext(fs.readFileSync(file,'utf8').replace(/^\.pragma library\s*/,''),core);
function frame(rgb){return Array.from({length:192*108*4},(_,i)=>i%4===3?255:rgb[i%4]);}
test('core validates dimensions and returns bounded opaque texture inputs',()=>{
 assert.equal(typeof core.process,'function');
 assert.throws(()=>core.process([]));
 const r=core.process(frame([185,187,190]),null,.6);
 assert.equal(r.map.length,192*108*4);assert.equal(r.lut.length,256*48*4);
 assert.ok(r.map.every(v=>Number.isInteger(v)&&v>=0&&v<=255));
 assert.ok(r.lut.every(v=>Number.isInteger(v)&&v>=0&&v<=255));
 assert.ok(r.strength>=0&&r.strength<=1);assert.ok(r.ms>=0);
});
test('black and white frames remain finite; discontinuous scenes reset history',()=>{
 assert.equal(typeof core.process,'function');
 const black=core.process(frame([0,0,0]),null,.6);
 const white=core.process(frame([255,255,255]),black,.6);
 const fresh=core.process(frame([255,255,255]),null,.6);
 assert.deepEqual(white.map,fresh.map);assert.deepEqual(white.lut,fresh.lut);
 assert.ok(Number.isFinite(white.mean)&&Number.isFinite(black.haze));
});
