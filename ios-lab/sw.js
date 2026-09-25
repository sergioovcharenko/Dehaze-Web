'use strict';
const CACHE='meti-ios-lab-v1';
const ROOT='./';
const ASSETS=[
  './','./index.html','./style.css','./app.js','./video-worker.js',
  './manifest.webmanifest','./icon.svg','./photo.html','./photo.js',
  './dehaze-dcp.js','./diagnostics.js'
];
self.addEventListener('install',event=>{
 event.waitUntil((async()=>{
  const c=await caches.open(CACHE);
  await c.addAll(ASSETS);
  await self.skipWaiting();
 })());
});
self.addEventListener('activate',event=>{
 event.waitUntil((async()=>{
  const keys=await caches.keys();
  await Promise.all(keys.filter(k=>k.startsWith('meti-ios-lab-')&&k!==CACHE)
    .map(k=>caches.delete(k)));
  await self.clients.claim();
 })());
});
self.addEventListener('fetch',event=>{
 const req=event.request;
 if(req.method!=='GET')return;
 const url=new URL(req.url);
 if(url.origin!==self.location.origin||!url.pathname.includes('/ios-lab/'))return;
 event.respondWith((async()=>{
  const c=await caches.open(CACHE);
  if(req.mode==='navigate'){
   try{
    const response=await fetch(req);
    if(response.ok)await c.put(req,response.clone());
    return response;
   }catch(e){
    return (await c.match(req,{ignoreSearch:true}))||
      (await c.match('./index.html'))||Response.error();
   }
  }
  const cached=await c.match(req,{ignoreSearch:true});
  if(cached)return cached;
  const response=await fetch(req);
  if(response.ok)await c.put(req,response.clone());
  return response;
 })());
});