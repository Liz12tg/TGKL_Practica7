const VERSION = 'v2';
const CACHE_APP = 'boleteria-app-' + VERSION;
const CACHE_DATOS = 'boleteria-datos-' + VERSION;

const ARCHIVOS_APP = [
  '/',
  '/app.css',
  '/app.js',
  '/manifest.webmanifest',
  '/offline.html',
  '/favicon.svg',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/apple-touch-icon.png',
];

self.addEventListener('install', evento => {
  evento.waitUntil(
    caches.open(CACHE_APP).then(cache => cache.addAll(ARCHIVOS_APP.map(url => new Request(url, { cache: 'reload' }))))
  );
});

self.addEventListener('activate', evento => {
  evento.waitUntil((async () => {
    // Borra las cachés de versiones anteriores.
    const nombres = await caches.keys();
    await Promise.all(nombres.filter(n => n.startsWith('boleteria-') && n !== CACHE_APP && n !== CACHE_DATOS).map(n => caches.delete(n)));
    await self.clients.claim();
  })());
});

self.addEventListener('message', evento => {
  if (evento.data && evento.data.tipo === 'ACTIVAR') self.skipWaiting();
});

function respuestaJson(codigo, objeto, extra) {
  return new Response(JSON.stringify(objeto), {
    status: codigo,
    headers: Object.assign({ 'Content-Type': 'application/json; charset=UTF-8' }, extra || {}),
  });
}

async function redConTiempo(peticion, ms) {
  const control = new AbortController();
  const reloj = setTimeout(() => control.abort(), ms);
  try {
    return await fetch(peticion, { signal: control.signal });
  } finally {
    clearTimeout(reloj);
  }
}

async function navegacion(peticion) {
  try {
    const r = await redConTiempo(peticion, 3000);
    if (r.ok && new URL(peticion.url).pathname === '/') {
      (await caches.open(CACHE_APP)).put('/', r.clone());
    }
    return r;
  } catch (e) {
    return (await caches.match('/')) || (await caches.match('/offline.html')) || Response.error();
  }
}

async function staleWhileRevalidate(peticion) {
  const cache = await caches.open(CACHE_APP);
  const guardada = await cache.match(peticion);
  const actualizar = fetch(peticion).then(r => {
    if (r.ok) cache.put(peticion, r.clone());
    return r;
  }).catch(() => null);
  return guardada || (await actualizar) || Response.error();
}

async function cachePrimero(peticion) {
  const guardada = await caches.match(peticion);
  if (guardada) return guardada;
  const r = await fetch(peticion);
  if (r.ok) (await caches.open(CACHE_APP)).put(peticion, r.clone());
  return r;
}

async function apiRedPrimero(peticion) {
  const cache = await caches.open(CACHE_DATOS);
  try {
    const r = await redConTiempo(peticion, 4000);
    if (r.ok) cache.put(peticion, r.clone());
    return r;
  } catch (e) {
    const guardada = await cache.match(peticion);
    if (guardada) {
      const copia = new Response(guardada.body, guardada);
      copia.headers.set('X-Desde-Cache', '1');
      return copia;
    }
    return respuestaJson(503, { error: 'Sin conexión y sin datos guardados' }, { 'X-Desde-Cache': '1' });
  }
}

async function soloRed(peticion) {
  try {
    return await fetch(peticion);
  } catch (e) {
    return respuestaJson(503, { error: 'Sin conexión' });
  }
}

self.addEventListener('fetch', evento => {
  const peticion = evento.request;
  if (peticion.method !== 'GET') return;                      
  const url = new URL(peticion.url);
  if (url.origin !== self.location.origin) return;

  if (peticion.mode === 'navigate') {
    evento.respondWith(navegacion(peticion));
  } else if (url.pathname === '/api/v1/eventos') {
    evento.respondWith(soloRed(peticion));
  } else if (url.pathname.startsWith('/api/v1/')) {
    evento.respondWith(apiRedPrimero(peticion));
  } else if (url.pathname.startsWith('/icons/')) {
    evento.respondWith(cachePrimero(peticion));
  } else if (url.pathname === '/sw.js') {
    return;                                                    6
  } else {
    evento.respondWith(staleWhileRevalidate(peticion));
  }
});

self.addEventListener('sync', evento => {
  if (evento.tag === 'enviar-cola') {
    evento.waitUntil(self.clients.matchAll({ includeUncontrolled: true }).then(lista => {
      lista.forEach(c => c.postMessage({ tipo: 'VACIAR_COLA' }));
    }));
  }
});

self.addEventListener('notificationclick', evento => {
  evento.notification.close();
  evento.waitUntil((async () => {
    const lista = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if (lista.length) {
      lista[0].focus();
      lista[0].postMessage({ tipo: 'IR', vista: 'mis-boletos' });
    } else {
      await self.clients.openWindow('/?origen=notificacion#mis-boletos');
    }
  })());
});
