import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

public class Sistema {
    private static final int VENTA_MS = Integer.getInteger("venta", 800);
    private static final int MEMORIA_PETICIONES = 10000;

    private final String evento;
    private final int total;
    private volatile int disponibles;
    private volatile int reloj;
    private final Object candado = new Object();
    private final Map<String, String> atendidas = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > MEMORIA_PETICIONES; }
    };

    public Sistema(String evento, int total) {
        this.evento = evento;
        this.total = total;
        this.disponibles = total;
        this.reloj = 0;
    }

    private String respuesta(String estado, boolean repetida) {
        return "{\"estado\":\"" + estado + "\",\"disponibles\":" + disponibles + ",\"reloj\":" + reloj
                + (repetida ? ",\"repetida\":true" : "") + "}";
    }

    public String vender(String caja, String cliente, int relojCaja, String idPeticion) {
        synchronized (candado) {
            reloj = Math.max(reloj, relojCaja) + 1;
            String quien = "[" + caja + " " + cliente + "]";

            String previa = atendidas.get(idPeticion);
            if (previa != null) {
                System.out.println("[Sistema][reloj " + reloj + "]" + quien + " Petición repetida " + idPeticion.substring(0, Math.min(8, idPeticion.length())) + ": se devuelve la respuesta guardada, sin vender otro boleto.");
                return respuesta(previa, true);
            }
            if (disponibles <= 0) {
                System.out.println("[Sistema][reloj " + reloj + "]" + quien + " No quedan boletos para " + evento);
                atendidas.put(idPeticion, "agotado");
                return respuesta("agotado", false);
            }

            System.out.println("[Sistema][reloj " + reloj + "]" + quien + " Procesando venta... Boletos antes: " + disponibles);
            try {
                Thread.sleep(VENTA_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            disponibles--;
            reloj++;
            System.out.println("[Sistema][reloj " + reloj + "]" + quien + " Boleto vendido. Sobran: " + disponibles + " de " + total);
            atendidas.put(idPeticion, "ok");
            return respuesta("ok", false);
        }
    }

    private String estado() {
        int d = disponibles;
        return "{\"evento\":\"" + evento + "\",\"total\":" + total + ",\"vendidos\":" + (total - d)
                + ",\"disponibles\":" + d + ",\"reloj\":" + reloj + "}";
    }

    public static void main(String[] args) throws IOException {
        int boletos = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        int puerto = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        String evento = "Concierto de ópera";
        Sistema sistema = new Sistema(evento, boletos);

        HttpServer http = HttpServer.create(new InetSocketAddress(puerto), 0);
        http.setExecutor(Web.poolAcotado());
        Web.ruta(http, "/vender", "POST", (ex, p) -> {
            String caja = Web.limpiar(p.get("caja"));
            String cliente = Web.limpiar(p.get("cliente"));
            String idPeticion = p.get("idPeticion");
            if (caja.isEmpty() || cliente.isEmpty() || idPeticion == null || idPeticion.isBlank() || idPeticion.length() > 64) {
                Web.responder(ex, 400, "{\"error\":\"Faltan caja, cliente o idPeticion\"}");
                return;
            }
            Web.responder(ex, 200, sistema.vender(caja, cliente, Web.leerReloj(p.get("reloj")), idPeticion));
        });
        Web.ruta(http, "/estado", "GET", (ex, p) -> Web.responder(ex, 200, sistema.estado()));
        http.start();

        System.out.println();
        System.out.println("[Sistema] Inventario central iniciado en http://localhost:" + puerto);
        System.out.println("[Sistema] Evento: '" + evento + "'");
        System.out.println("[Sistema] Boletos del evento: " + boletos);
        System.out.println("[Sistema] " + Web.infoPool() + ", tiempo por venta: " + VENTA_MS + " ms");
        System.out.println("[Sistema] Autenticación: Bearer token" + (Web.tkn.equals(Web.tkn_pre) ? " (token por defecto: cámbielo con -Dtkn=...)" : ""));
        System.out.println("[Sistema] Esperando a las cajas...\n");
    }
}
