'use strict';
const CACHE='dehaze-layered-compare-v1';
const ASSETS=[
  './index.html',
  './video-dehaze.js?v=8',
  './dehaze-dcp.js?v=4',
  './manifest.webmanifest',
  './offline-icon.svg'
];
self.addEventListener('install',event=>{
 event.waitUntil((async()=>{const cache=await caches.open(CACHE);await cache.addAll(ASSETS);await self.skipWaiting();})());
});
self.addEventListener('activate',event=>{
 event.waitUntil((async()=>{const names=await caches.keys();await Promise.all(names.filter(n=>n!==CACHE).map(n=>caches.delete(n)));await self.clients.claim();})());
});
self.addEventListener('fetch',event=>{
 const req=event.request;if(req.method!=='GET')return;
 const url=new URL(req.url);if(url.origin!==self.location.origin)return;
 if(req.mode==='navigate'){
  event.respondWith((async()=>{
   try{const response=await fetch(req);if(response.ok){const cache=await caches.open(CACHE);await cache.put('./index.html',response.clone());}return response;}
   catch(err){const cache=await caches.open(CACHE);return await cache.match('./index.html')||Response.error();}
  })());
 }else{
  event.respondWith((async()=>{const cached=await caches.match(req,{ignoreSearch:true});if(cached)return cached;return fetch(req);})());
 }
});