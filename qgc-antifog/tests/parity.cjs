const fs=require('node:fs'),assert=require('node:assert/strict');
const core=Function(fs.readFileSync(__dirname+'/../qml/ClassicCore.js','utf8').replace(/^\.pragma library\s*/,'')+';return {process};')();
const input=Array.from(fs.readFileSync(process.argv[2])),expected=fs.readFileSync(process.argv[3]);
const r=core.process(input,null,.6);
['ar','ag','ab','haze','mean'].forEach((k,i)=>assert.ok(Math.abs(r[k]-expected.readFloatBE(i*4))<.002,k));
let pos=20,max=0;for(const arr of [r.map,r.lut])for(const x of arr){max=Math.max(max,Math.abs(x-expected[pos++]));}
assert.ok(max<=2,'Map or LUT differs by '+max);console.log('Java parity: max byte delta',max,'JS time',r.ms,'ms');
