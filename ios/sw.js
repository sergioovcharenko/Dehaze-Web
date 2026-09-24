'use strict';
const CACHE='meti-ios-offline-2026-09-24-a';
const CORE=[
 './','./index.html','./universal.css','./universal.js',
 './video-worker.js','./photo.html','./photo.js','./dehaze-dcp.js',
 './manifest.webmanifest','./icon.svg','./icon-180.png'
];
self.addEventListener('install',event=>{
 event.waitUntil((async()=>{
  const cache=await caches.open(CACHE);
  await cache.addAll(CORE.map(x=>new Request(x,{cache:'reload'})));
  await self.skipWaiting();
 })());
});
self.addEventListener('activate',event=>{
 event.waitUntil((async()=>{
  const keys=await caches.keys();
  await Promise.all(keys.filter(x=>x.startsWith('meti-ios-offline-')&&x!==CACHE)
      .map(x=>caches.delete(x)));
  await self.clients.claim();
 })());
});
self.addEventListener('fetch',event=>{
 const request=event.request;
 if(request.method!=='GET')return;
 const url=new URL(request.url);
 if(url.origin!==self.location.origin||!url.pathname.startsWith(new URL('./',self.location.href).pathname))return;
 if(request.mode==='navigate'){
  event.respondWith((async()=>{
   const cache=await caches.open(CACHE);
   try{
    const response=await fetch(request);
    if(response.ok)await cache.put(request,response.clone());
    return response;
   }catch(error){
    return await cache.match(request,{ignoreSearch:true})
      ||await cache.match('./index.html')||Response.error();
   }
  })());
 }else{
  event.respondWith((async()=>{
   const cached=await caches.match(request,{ignoreSearch:true});
   if(cached)return cached;
   const response=await fetch(request);
   if(response.ok){const cache=await caches.open(CACHE);await cache.put(request,response.clone());}
   return response;
  })());
 }
});
