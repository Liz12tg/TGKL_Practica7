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

La aplicación utiliza una PWA (Progressive Web App), que permite instalar una página web como si fuera una aplicación normal.

Para instalarla desde internet necesita HTTPS. Esto se puede conseguir desplegando el proyecto en servicios como Render, Railway o Google Cloud Run.

- Chrome y Edge (PC y Android): selecciona el icono de instalación o el botón Instalar.

- iOS (Safari): selecciona Compartir y después Añadir a pantalla de inicio.

### Funcionamiento sin conexión

La aplicación puede abrirse sin internet gracias al Service Worker, que guarda una copia de los archivos necesarios.

También permite realizar compras sin conexión:

1. Se guarda una copia de la aplicación al visitarla.

2. Si se pierde la conexión, la aplicación sigue abriendo con los datos almacenados.

3. Las compras realizadas se guardan en una cola.

4. Cuando regresa la conexión, se envían automáticamente al servidor, evitando que una misma compra se registre dos veces.

### API REST

La aplicación cuenta con una API REST versión 1, que permite consultar información y realizar compras mediante solicitudes HTTP.

| Método y ruta | Función | 
|---|---|
| `GET /api/v1/salud` | Comprueba si el servicio está funcionando | 
| `GET /api/v1/evento` | Consulta los boletos disponibles, vendidos y totales | 
| `GET /api/v1/cajas` | Consulta las cajas y su ocupación | 
| `GET /api/v1/eventos?desde=N` | Consulta las compras realizada | 
| `GET /api/v1/compras?ids=&activas=&limite=` | Registra una nueva compra | 
| `POST /api/v1/compras` | Consulta el estado de una compra específica |
| `GET /api/v1/compras/{id}` | Consulta el registro de eventos del sistema | 

La API también utiliza mecanismos para evitar compras duplicadas y controlar las solicitudes.

La documentación completa de los endpoints se encuentra en docs/openapi.json.

## Componentes de la PWA

La aplicación utiliza dos elementos principales:

- Web App Manifest: contiene la información de la aplicación, como su nombre, iconos, colores, forma de presentación y accesos directos. Permite que el navegador la reconozca como una aplicación instalable.

- Service Worker: administra la caché, permite abrir la aplicación sin conexión y controla la actualización de los archivos almacenados.

Cuando existe una nueva versión, la aplicación muestra un aviso para que el usuario pueda actualizarla.

## Seguridad

Se implementan medidas para proteger la comunicación y controlar las solicitudes:

- Uso de un token interno para la comunicación entre programas.

- Validación de las solicitudes de compra para evitar peticiones no autorizadas.

- Restricción de los archivos que pueden consultarse desde la aplicación.

- Límite al tamaño de las solicitudes y a las compras activas.

- Uso de políticas de seguridad para proteger el contenido web.

## Limitaciones

- Las compras sin conexión se guardan en localStorage hasta que se recupera la conexión.

- Las compras se almacenan en memoria, por lo que se pierden al reiniciar la interfaz. La aplicación las identifica como pertenecientes a una sesión anterior.

- No se implementan notificaciones push reales.