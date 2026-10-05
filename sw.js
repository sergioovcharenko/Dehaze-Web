'use strict';
const CACHE='dehaze-adaptive-object-v17';
const ASSETS=[
  './index.html',
  './video-dehaze-adaptive-v17.js',
  './dehaze-dcp-adaptive-v6.js',
  './manifest.webmanifest',
  './offline-icon.svg'
];
self.addEventListener('install',event=>{
 event.waitUntil((async()=>{
   const cache=await caches.open(CACHE);
   await cache.addAll(ASSETS);
   await self.skipWaiting();
 })());
});
self.addEventListener('activate',event=>{
 event.waitUntil((async()=>{
   const names=await caches.keys();
   await Promise.all(names.filter(n=>n!==CACHE).map(n=>caches.delete(n)));
   await self.clients.claim();
 })());
});
self.addEventListener('fetch',event=>{
 const req=event.request;
 if(req.method!=='GET')return;
 const url=new URL(req.url);
 if(url.origin!==self.location.origin)return;

 const isCode=req.destination==='script'||url.pathname.endsWith('.js');
 if(req.mode==='navigate'||isCode){
   event.respondWith((async()=>{
     try{
       const response=await fetch(req,{cache:'no-store'});
       if(response&&response.ok){
         const cache=await caches.open(CACHE);
         await cache.put(req,response.clone());
       }
       return response;
     }catch(err){
       const cache=await caches.open(CACHE);
       return (await cache.match(req)) ||
              (req.mode==='navigate' ? await cache.match('./index.html') : Response.error());
     }
   })());
   return;
 }

 event.respondWith((async()=>{
   const cache=await caches.open(CACHE);
   const cached=await cache.match(req);
   if(cached)return cached;
   const response=await fetch(req);
   if(response&&response.ok)await cache.put(req,response.clone());
   return response;
 })());
});
