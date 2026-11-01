import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Caja {
    private static final long LEASE_MS = Long.getLong("lease", 15L) * 1000;
    private static final int LIMITE_POR_CLIENTE = Integer.getInteger("limite", 8);

    private static class Sesion {
        final int idCliente;
        final String nombre;
        volatile long ultimaActividad = System.currentTimeMillis();
        int comprados;

        Sesion(int idCliente, String nombre) {
            this.idCliente = idCliente;
            this.nombre = nombre;
        }

        void tocar() { ultimaActividad = System.currentTimeMillis(); }
    }

    private final String idCaja;
    private final Web.Nodo sistema;
    private final int maxClientes;
    private final Semaphore cupos;
    private final AtomicInteger contadorClientes = new AtomicInteger(0);
    private final AtomicInteger ventas = new AtomicInteger(0);
    private final Map<String, Sesion> sesiones = new ConcurrentHashMap<>();
    private int reloj;

    public Caja(String idCaja, Web.Nodo sistema, int maxClientes) {
        this.idCaja = idCaja;
        this.sistema = sistema;
        this.maxClientes = maxClientes;
        this.cupos = new Semaphore(maxClientes);
        this.reloj = 0;
    }

    private synchronized int eventoLocal() {
        reloj++;
        return reloj;
    }

    private synchronized int actualizarConMensaje(int relojRecibido) {
        reloj = Math.max(reloj, relojRecibido) + 1;
        return reloj;
    }

    private synchronized int getReloj() { return reloj; }

    private void conectar(HttpExchange ex, Map<String, String> p) throws IOException {
        String nombre = Web.limpiar(p.get("nombre"));
        if (nombre.isEmpty()) {
            Web.responder(ex, 400, "{\"error\":\"Falta el parametro nombre\"}");
            return;
        }
        int idCliente = contadorClientes.incrementAndGet();
        int r = eventoLocal();
        System.out.println("[" + idCaja + "][reloj " + r + "] Cliente #" + idCliente + " llega a la caja");

        if (!cupos.tryAcquire()) {
            r = eventoLocal();
            System.out.println("[" + idCaja + "][reloj " + r + "][cliente #" + idCliente + "] La caja está llena.");
            Web.responder(ex, 200, "{\"estado\":\"caja_llena\",\"idCliente\":" + idCliente + ",\"reloj\":" + r + "}");
            return;
        }

        String idSesion = UUID.randomUUID().toString();
        sesiones.put(idSesion, new Sesion(idCliente, nombre));
        System.out.println("[" + idCaja + "][cliente #" + idCliente + "] Atendiendo a " + nombre);
        Web.responder(ex, 200, "{\"estado\":\"ok\",\"idCliente\":" + idCliente + ",\"sesion\":\"" + idSesion + "\",\"reloj\":" + r + "}");
    }

    private void vender(HttpExchange ex, Map<String, String> p) throws IOException {
        String idSesion = ex.getRequestHeaders().getFirst("X-Sesion");
        Sesion s = idSesion == null ? null : sesiones.get(idSesion);
        if (s == null) {
            Web.responder(ex, 403, "{\"error\":\"Sesion invalida o vencida. Use /conectar\"}");
            return;
        }
        String idPeticion = p.get("idPeticion");
        if (idPeticion == null || idPeticion.isBlank() || idPeticion.length() > 64) {
            Web.responder(ex, 400, "{\"error\":\"Falta idPeticion (maximo 64 caracteres)\"}");
            return;
        }
        int relojCliente = Web.leerReloj(p.get("reloj"));

        synchronized (s) {
            if (!sesiones.containsKey(idSesion)) {
                Web.responder(ex, 403, "{\"error\":\"Sesion invalida o vencida. Use /conectar\"}");
                return;
            }
            s.tocar();
            if (s.comprados >= LIMITE_POR_CLIENTE) {
                Web.responder(ex, 409, "{\"error\":\"Limite de " + LIMITE_POR_CLIENTE + " boletos por cliente\"}");
                return;
            }

            int r = actualizarConMensaje(relojCliente);
            String respuesta;
            try {
                respuesta = Web.post(sistema, "/vender?caja=" + Web.enc(idCaja) + "&cliente=" + Web.enc(s.nombre)
                        + "&reloj=" + r + "&idPeticion=" + Web.enc(idPeticion), null, Duration.ofSeconds(15));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                int re = eventoLocal();
                System.out.println("[" + idCaja + "][reloj " + re + "][cliente #" + s.idCliente + " " + s.nombre + "] El sistema central no responde: " + e.getMessage());
                Web.responder(ex, 502, "{\"error\":\"El sistema central no responde\"}");
                return;
            }

            r = actualizarConMensaje(Integer.parseInt(Web.campo(respuesta, "reloj")));
            String estado = Web.campo(respuesta, "estado");
            boolean repetida = Web.campo(respuesta, "repetida").equals("true");
            if (estado.equals("ok") && !repetida) {
                s.comprados++;
                ventas.incrementAndGet();
            }
            s.tocar();
            System.out.println("[" + idCaja + "][reloj " + r + "][cliente #" + s.idCliente + " " + s.nombre + "] "
                    + (estado.equals("ok") ? (repetida ? "Venta ya registrada por el sistema." : "Boleto vendido.") : "Se agotaron los boletos del evento.")
                    + " Sobran: " + Web.campo(respuesta, "disponibles"));
            Web.responder(ex, 200, "{\"estado\":\"" + estado + "\",\"disponibles\":" + Web.campo(respuesta, "disponibles")
                    + ",\"reloj\":" + r + (repetida ? ",\"repetida\":true" : "") + "}");
        }
    }

    private void salir(HttpExchange ex, Map<String, String> p) throws IOException {
        String idSesion = ex.getRequestHeaders().getFirst("X-Sesion");
        Sesion s = idSesion == null ? null : sesiones.remove(idSesion);
        int relojCliente = Web.leerReloj(p.get("reloj"));

        int r = actualizarConMensaje(relojCliente);
        if (s != null) {
            System.out.println("[" + idCaja + "][reloj " + r + "][cliente #" + s.idCliente + " " + s.nombre + "] Terminó su turno.");
            cupos.release();
        } else {
            System.out.println("[" + idCaja + "][reloj " + r + "] Salida repetida o sesión vencida: no hay lugar que liberar.");
        }
        Web.responder(ex, 200, "{\"estado\":\"ok\",\"reloj\":" + r + "}");
    }

    private void estado(HttpExchange ex, Map<String, String> p) throws IOException {
        Web.responder(ex, 200, "{\"idCaja\":\"" + idCaja + "\",\"ventas\":" + ventas.get() + ",\"maxClientes\":" + maxClientes
                + ",\"sesiones\":" + sesiones.size() + ",\"reloj\":" + getReloj() + "}");
    }

    private void expirarSesiones() {
        long ahora = System.currentTimeMillis();
        for (Map.Entry<String, Sesion> e : sesiones.entrySet()) {
            Sesion s = e.getValue();
            if (ahora - s.ultimaActividad > LEASE_MS && sesiones.remove(e.getKey(), s)) {
                cupos.release();
                int r = eventoLocal();
                System.out.println("[" + idCaja + "][reloj " + r + "][cliente #" + s.idCliente + " " + s.nombre + "] Lease vencido: se libera su lugar por inactividad.");
            }
        }
    }

    public static void main(String[] args) throws IOException {
        int maxClientes = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        int puerto = args.length > 1 ? Integer.parseInt(args[1]) : 5001;
        String idCaja = args.length > 2 ? args[2] : "C1";
        Web.Nodo sistema = Web.Nodo.de(args.length > 3 ? args[3] : "localhost:5000");
        if (sistema == null) {
            System.out.println("El sistema central debe indicarse como host:puerto");
            return;
        }

        Caja caja = new Caja(idCaja, sistema, maxClientes);

        ScheduledExecutorService limpiador = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "limpiador-de-sesiones");
            t.setDaemon(true);
            return t;
        });
        limpiador.scheduleAtFixedRate(() -> {
            try {
                caja.expirarSesiones();
            } catch (Throwable t) {
                System.out.println("[" + idCaja + "] Error en el limpiador: " + t);
            }
        }, 1, 1, TimeUnit.SECONDS);

        HttpServer http = HttpServer.create(new InetSocketAddress(puerto), 0);
        http.setExecutor(Web.poolAcotado());
        Web.ruta(http, "/conectar", "POST", caja::conectar);
        Web.ruta(http, "/vender", "POST", caja::vender);
        Web.ruta(http, "/salir", "POST", caja::salir);
        Web.ruta(http, "/estado", "GET", caja::estado);
        http.start();

        System.out.println();
        System.out.println("[" + idCaja + "] Caja iniciada en http://localhost:" + puerto);
        System.out.println("[" + idCaja + "] Vende del inventario del sistema central en " + sistema);
        System.out.println("[" + idCaja + "] Clientes simultáneos permitidos: " + maxClientes + ", límite por cliente: " + LIMITE_POR_CLIENTE + " boletos");
        System.out.println("[" + idCaja + "] " + Web.infoPool() + ", lease: " + (LEASE_MS / 1000) + " s");
        System.out.println("[" + idCaja + "] Esperando clientes...\n");
    }
}
