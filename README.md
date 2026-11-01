# Práctica 7: Apps y PWA

En esta práctica se retoma la boletería de la práctica 6 y se convierte en una aplicación web instalable (PWA), que puede utilizarse desde una computadora o un celular, incluso sin conexión a internet.
El sistema está formado por cinco programas:

| Programa | Funcion |
|----------|-----|
| `Sistema` | Administra el inventario de boletos y evita que se vendan más de los disponibles |
| `Caja` | Atiende a los clientes, controla las sesiones y limita las compras simultáneas |
| `Cliente` | Solicita una caja disponible. Si todas están ocupadas, espera en una fila |
| `Interfaz` | Proporciona la API REST, muestra la aplicación web y coordina los demás programas |
| `Web` | Contiene herramientas compartidas para manejar la comunicación, los datos y las solicitudes |

Solo usa el JDK 17+.

## Cómo ejecutar la práctica en windows

```bat
cd TGKL_Practica7
.\iniciar.bat
```
Esto inicia automáticamente el sistema, las dos cajas y la interfaz. Después, abre http://localhost:8080 en Chrome o Edge.

En Linux o macOS se utiliza:

```bat
./iniciar.sh
```
Para detener el sistema, presiona Ctrl + C o cierra la ventana.

## Instalación como aplicación

Una PWA instalable necesita HTTPS fuera de `localhost`. Los hostings dan el certificado gratis, así que basta con subir el repo:

1. Sube la carpeta a GitHub (el `Dockerfile` ya está en la raíz de la práctica).
2. En [Render](https://render.com) → *New → Web Service* → elige el repo → *Runtime: Docker* → *Create*. (Railway, Fly.io o Google Cloud Run funcionan igual.)
3. En unos minutos queda en `https://tu-app.onrender.com`; ábrela en el celular y *Instalar*.

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
