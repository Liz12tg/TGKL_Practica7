# Práctica 7: Apps y PWA (REST + manifest + service worker)

La boletería de la práctica 6 (un **sistema central**, varias **cajas** con varios **clientes** cada una) ahora se usa
como una **aplicación instalable**: una PWA con API REST versionada, Web App Manifest y service worker.

```
Navegador / app instalada ── HTTP ──> Interfaz (REST v1 + archivos de la PWA) ──> Clientes ──> Cajas ──> Sistema central
   manifest + service worker              :8080            (el token interno nunca llega al navegador)
```

| Programa | Rol |
|----------|-----|
| `Sistema` | Inventario único de boletos; vende de forma atómica (nunca de más) |
| `Caja` | Semáforo de clientes simultáneos, sesiones, lease, límite por cliente, relojes de Lamport |
| `Cliente` | Llega a una caja; si está llena prueba otra; si todas están llenas espera en fila |
| `Interfaz` | **API REST v1** + sirve la PWA (`web/`). Es un BFF: guarda el token y lanza los clientes. Sin argumentos arranca *todo en uno* |
| `Web` | Utilidades compartidas (token, HTTP, JSON, pool acotado) |

Solo usa el JDK 17+.

## Inicio rápido (Windows)

```bat
cd practica-07-apps-pwa
iniciar.bat
```

Un solo proceso levanta el sistema central, las dos cajas y la interfaz, y abre <http://localhost:8080> (usa **Chrome o Edge**).
Linux/macOS: `./iniciar.sh`. Para ver la fila rápido: `iniciar.bat demo` (6 boletos, cajas de 1 cliente) o el botón
**Probar la fila** en la pestaña *En vivo*. Detener: Ctrl+C o cerrar la ventana.

> Ejecuta desde esta carpeta (busca los archivos en `web/`; otra ruta: `-Dweb=ruta\a\web`).
> El modo de 4 procesos de la práctica 6 sigue existiendo: `java -cp out Sistema ...`, `Caja ...`, `Interfaz 8080 host:puerto ...`.

## Desplegar en internet (con HTTPS)

Una PWA instalable necesita HTTPS fuera de `localhost`. Los hostings dan el certificado gratis, así que basta con subir el repo:

1. Sube la carpeta a GitHub (el `Dockerfile` ya está en la raíz de la práctica).
2. En [Render](https://render.com) → *New → Web Service* → elige el repo → *Runtime: Docker* → *Create*. (Railway, Fly.io o Google Cloud Run funcionan igual.)
3. En unos minutos queda en `https://tu-app.onrender.com`; ábrela en el celular y *Instalar*.

Variables opcionales: `BOLETOS` (por defecto 20) y `CUPO` (clientes por caja, 2). El hosting define `PORT` y el programa lo detecta:
al verlo escucha en todas las interfaces y acepta el dominio público; además genera un **token interno aleatorio** en cada arranque
(solo lo conocen los componentes del mismo proceso). Local con Docker: `docker build -t boleteria . && docker run -p 8080:8080 -e PORT=8080 boleteria`.

### Instalarla como app

- **Chrome/Edge (PC y Android):** icono de instalar en la barra de direcciones, o el botón **Instalar** de la propia app.
- **iOS (Safari):** Compartir → *Añadir a pantalla de inicio* (la app muestra una pista).
- `localhost` cuenta como contexto seguro, por eso el service worker funciona sin HTTPS. En otra máquina se necesita HTTPS.

### Probar el modo sin conexión

1. Abre la app y navega un poco (se guarda la copia).
2. Cierra la ventana de **Interfaz** (o en DevTools → Network → *Offline*).
3. Recarga: la app abre desde caché, el indicador pasa a **Datos guardados** y aparece el aviso amarillo.
4. Compra: queda en **Mis boletos → En cola**. Al volver el servidor se envía sola, **una sola vez** (`Idempotency-Key`).

DevTools → **Application** muestra *Manifest*, *Service Workers* y *Cache Storage*.

## API REST v1 (`docs/openapi.json`)

| Método y ruta | Descripción | Respuestas |
|---|---|---|
| `GET /api/v1/salud` | Estado del servicio | 200 |
| `GET /api/v1/evento` | Boletos totales, vendidos, disponibles, `instancia` | 200, **304** (ETag) |
| `GET /api/v1/cajas` | Cajas y ocupación | 200, 304 |
| `GET /api/v1/eventos?desde=N` | Bitácora incremental | 200 |
| `GET /api/v1/compras?ids=&activas=&limite=` | Lista de compras | 200 |
| `POST /api/v1/compras` | Crea una compra `{nombre, boletos}` | **202** + `Location`, **200** si repite la llave, 400, 403, 413, 415, **429** + `Retry-After` |
| `GET /api/v1/compras/{id}` | Estado de una compra | 200, 404, 405 (+`Allow`) |

Decisiones de diseño: la compra es **asíncrona** (202 y se consulta), `Idempotency-Key` evita cobrar dos veces al reintentar
(respuesta con `Idempotent-Replayed: true`), y los recursos tienen enlaces (`enlaces.self`).

## Web App Manifest (`web/manifest.webmanifest`)

| Campo | Valor / para qué sirve |
|---|---|
| `id`, `start_url`, `scope` | Identidad estable de la app; `/?origen=pwa` permite medir aperturas como app |
| `name`, `short_name`, `lang`, `dir`, `description` | Texto bajo el icono y en la instalación |
| `display: standalone` + `display_override` | Ventana sin barra del navegador (con alternativas) |
| `theme_color`, `background_color` | Color de la barra y de la pantalla de arranque |
| `icons` | 192, 512, *maskable* 512 y SVG |
| `shortcuts` | Atajos de icono: *Comprar boletos* y *Mis boletos* |
| `screenshots` | Capturas móvil y ancha para el diálogo de instalación |
| `launch_handler`, `categories`, `orientation` | Reutiliza la ventana abierta; clasificación; orientación |

## Service worker (`web/sw.js`)

| Recurso | Estrategia | Por qué |
|---|---|---|
| Navegación (HTML) | Red primero (3 s) → `/` en caché → `offline.html` | Siempre la versión nueva, pero abre sin red |
| CSS/JS/manifest | *Stale-while-revalidate* | Rápido y se actualiza solo |
| Iconos | Caché primero | No cambian |
| `GET /api/v1/*` | Red primero con respaldo en caché (marca `X-Desde-Cache`) | Datos frescos; si no hay red, últimos datos |
| `GET /api/v1/eventos` | Solo red | Es una bitácora en vivo |
| `POST` | No se intercepta | La cola offline la maneja la app |

Cachés versionadas (`boleteria-app-v1`, `boleteria-datos-v1`). Ciclo de actualización: si hay un SW nuevo, la app muestra
**"Hay una versión nueva" → Actualizar** (`ACTIVAR` → `skipWaiting` → recarga). También hay Background Sync (`enviar-cola`),
clic en notificaciones y *badge* con el número de boletos.

## Seguridad

- En local: solo `127.0.0.1` y verificación de cabecera `Host`. En hosting (`PORT`): red pública, token interno aleatorio.
- CSP estricta (`default-src 'none'`, sin scripts ni estilos en línea), `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors 'none'`.
- `X-Requested-With` obligatorio en POST (anti-CSRF) y `Content-Type: application/json`.
- Archivos estáticos solo por lista permitida (sin *path traversal*). Cuerpo máximo 2 KB, máximo de compras activas.

## Limitaciones

- La compra offline se encola en `localStorage` y se envía al volver la app/servidor; no es una bandeja IndexedDB completa.
- Las compras viven en memoria de `Interfaz`: al reiniciarla se pierden y la app las marca como *sesión anterior*.
- No hay notificaciones push reales (requieren un servidor Web Push y HTTPS).
