import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Interfaz {
    private static final int COLORES = 8;
    private static final int MAX_ACTIVOS = 30;
    private static final int MAX_BOLETOS = Integer.getInteger("limite", 8);
    private static final int MAX_CUERPO = 2048;
    private static final int MEMORIA_LLAVES = 1000;
    private static final String API = "/api/v1";
    private static final Pattern LLAVE = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern ESTATICO = Pattern.compile("/(app\\.css|app\\.js|sw\\.js|manifest\\.webmanifest|offline\\.html|favicon\\.svg)|/icons/[a-z0-9-]+\\.(png|svg)");

    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; " + "connect-src 'self'; manifest-src 'self'; worker-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private static class EstadoSistema {
        final boolean enLinea;
        final String evento;
        final int total, vendidos, disponibles, rlj;

        EstadoSistema(boolean enLinea, String evento, int total, int vendidos, int disponibles, int rlj) {
            this.enLinea = enLinea;
            this.evento = evento;
            this.total = total;
            this.vendidos = vendidos;
            this.disponibles = disponibles;
            this.rlj = rlj;
        }
    }

    private static class EstadoCaja {
        final boolean enLinea;
        final String idCaja;
        final int ventas, maxC, sesiones, rlj;

        EstadoCaja(boolean enLinea, String idCaja, int ventas, int maxC, int sesiones, int rlj) {
            this.enLinea = enLinea;
            this.idCaja = idCaja;
            this.ventas = ventas;
            this.maxC = maxC;
            this.sesiones = sesiones;
            this.rlj = rlj;
        }
    }

    private static class Compra {
        final int id, pedidos, color;
        final String nom;
        final long creada = System.currentTimeMillis();
        final List<Integer> folios = new CopyOnWriteArrayList<>();
        volatile int comprados, rlj;
        volatile String estado = "buscando", caja = "", msj = "Llegó a la taquilla.";
        volatile boolean activa = true;

        Compra(int id, String nom, int pedidos) {
            this.id = id;
            this.nom = nom;
            this.pedidos = pedidos;
            this.color = (id - 1) % COLORES;
        }

        String resultado() {
            if (activa) { return null; }
            return comprados >= pedidos ? "completa" : comprados > 0 ? "parcial" : "ninguno";
        }
    }

    private static class Evento {
        final int compra, rlj;
        final String nom, tipo, caja, txt;

        Evento(int compra, String nom, String tipo, String caja, int rlj, String txt) {
            this.compra = compra;
            this.nom = nom;
            this.tipo = tipo;
            this.caja = caja;
            this.rlj = rlj;
            this.txt = txt;
        }
    }

    private static final long INSTANCIA = System.currentTimeMillis();

    private final int prto;
    private final boolean publico = System.getenv("PORT") != null || Boolean.getBoolean("publico");
    private final Path web = Paths.get(System.getProperty("web", "web")).toAbsolutePath().normalize();
    private final Web.Nodo sistema;
    private final List<Web.Nodo> cajas;
    private volatile EstadoSistema estadoSistema = new EstadoSistema(false, "", 0, 0, 0, 0);
    private volatile EstadoCaja[] estadoCajas;
    private final List<Evento> eventos = new ArrayList<>();
    private final List<Compra> compras = new CopyOnWriteArrayList<>();
    private final AtomicInteger contador = new AtomicInteger(0);
    private final AtomicInteger folio = new AtomicInteger(0);
    private volatile boolean folioIniciado = false;
    private final Map<String, Integer> llaves = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> e) { return size() > MEMORIA_LLAVES; }
    };

    Interfaz(int prto, Web.Nodo sistema, List<Web.Nodo> cajas) {
        this.prto = prto;
        this.sistema = sistema;
        this.cajas = cajas;
        EstadoCaja[] inicial = new EstadoCaja[cajas.size()];
        for (int i = 0; i < inicial.length; i++) {
            inicial[i] = new EstadoCaja(false, "", 0, 0, 0, 0);
        }
        this.estadoCajas = inicial;
    }

    private void sondear() {
        try {
            String j = Web.get(sistema, "/estado", Duration.ofMillis(800));
            EstadoSistema s = new EstadoSistema(true, Web.campo(j, "evento"),
                    Integer.parseInt(Web.campo(j, "total")), Integer.parseInt(Web.campo(j, "vendidos")),
                    Integer.parseInt(Web.campo(j, "disponibles")), Integer.parseInt(Web.campo(j, "reloj")));
            if (!folioIniciado) {
                // Si el Sistema ya había vendido boletos antes de abrir la app, los folios continúan desde ahí.
                folio.compareAndSet(0, s.vendidos);
                folioIniciado = true;
            }
            estadoSistema = s;
        } catch (Exception e) {
            EstadoSistema a = estadoSistema;
            estadoSistema = new EstadoSistema(false, a.evento, a.total, a.vendidos, a.disponibles, a.rlj);
        }
        EstadoCaja[] nuevo = new EstadoCaja[cajas.size()];
        for (int i = 0; i < nuevo.length; i++) {
            try {
                String j = Web.get(cajas.get(i), "/estado", Duration.ofMillis(800));
                nuevo[i] = new EstadoCaja(true, Web.campo(j, "idCaja"), Integer.parseInt(Web.campo(j, "ventas")),
                        Integer.parseInt(Web.campo(j, "maxClientes")), Integer.parseInt(Web.campo(j, "sesiones")),
                        Integer.parseInt(Web.campo(j, "reloj")));
            } catch (Exception e) {
                nuevo[i] = new EstadoCaja(false, "", 0, 0, 0, 0);
            }
        }
        estadoCajas = nuevo;
    }

    private synchronized void registrar(Compra c, String tipo, String caja, int rlj, int faltan, String txt) {
        c.rlj = rlj;
        c.comprados = c.pedidos - faltan;
        switch (tipo) {
            case "vendido":
                c.folios.add(folio.incrementAndGet());
                c.estado = "en_caja";
                c.caja = caja;
                break;
            case "conectado":
            case "agotado":
                c.estado = "en_caja";
                c.caja = caja;
                break;
            case "fila":
                c.estado = "en_fila";
                c.caja = "";
                break;
            case "intento":
            case "llena":
            case "reintento":
            case "rechazo":
            case "error":
                c.estado = "buscando";
                break;
            case "fin":
                c.estado = "terminada";
                c.activa = false;
                c.caja = "";
                break;
            default:
                break;
        }
        if (txt != null) { c.msj = txt; }
        eventos.add(new Evento(c.id, c.nom, tipo, caja, rlj, txt));
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) { b.append(String.format("\\u%04x", (int) c)); } else { b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    private static String error(String msj, String campo) {
        return "{\"error\":" + esc(msj) + (campo == null ? "" : ",\"campo\":" + esc(campo)) + "}";
    }

    static Map<String, String> leerJson(String txt) {
        Map<String, String> m = new HashMap<>();
        int i = 0, n = txt.length();
        while (i < n && Character.isWhitespace(txt.charAt(i))) { i++; }
        if (i >= n || txt.charAt(i++) != '{') { return null; }
        while (true) {
            while (i < n && Character.isWhitespace(txt.charAt(i))) { i++; }
            if (i < n && txt.charAt(i) == '}') { return m; }
            if (i >= n || txt.charAt(i) != '"') { return null; }
            int[] pos = {i};
            String clave = leerCadena(txt, pos);
            if (clave == null) { return null; }
            i = pos[0];
            while (i < n && Character.isWhitespace(txt.charAt(i))) { i++; }
            if (i >= n || txt.charAt(i++) != ':') { return null; }
            while (i < n && Character.isWhitespace(txt.charAt(i))) { i++; }
            if (i >= n) { return null; }
            String valor;
            if (txt.charAt(i) == '"') {
                pos[0] = i;
                valor = leerCadena(txt, pos);
                if (valor == null) { return null; }
                i = pos[0];
            } else {
                int ini = i;
                while (i < n && "-+.0123456789eE".indexOf(txt.charAt(i)) >= 0) { i++; }
                if (i == ini) { return null; }
                valor = txt.substring(ini, i);
            }
            m.put(clave, valor);
            while (i < n && Character.isWhitespace(txt.charAt(i))) { i++; }
            if (i < n && txt.charAt(i) == ',') { i++; continue; }
            if (i < n && txt.charAt(i) == '}') { return m; }
            return null;
        }
    }

    private static String leerCadena(String t, int[] pos) {
        StringBuilder b = new StringBuilder();
        int i = pos[0] + 1;
        while (i < t.length()) {
            char c = t.charAt(i++);
            if (c == '"') {
                pos[0] = i;
                return b.toString();
            }
            if (c == '\\') {
                if (i >= t.length()) { return null; }
                char e = t.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'u':
                        if (i + 4 > t.length()) { return null; }
                        try {
                            b.append((char) Integer.parseInt(t.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            return null;
                        }
                        i += 4;
                        break;
                    default: b.append(e);
                }
            } else {
                b.append(c);
            }
        }
        return null;
    }

    private String jsonEvento() {
        EstadoSistema s = estadoSistema;
        return "{\"nombre\":" + esc(s.evento.isEmpty() ? "Concierto de ópera" : s.evento)
                + ",\"enLinea\":" + s.enLinea + ",\"total\":" + s.total + ",\"vendidos\":" + s.vendidos
                + ",\"disponibles\":" + s.disponibles + ",\"reloj\":" + s.rlj + ",\"instancia\":" + INSTANCIA + "}";
    }

    private String jsonCajas() {
        StringBuilder b = new StringBuilder("{\"cajas\":[");
        EstadoCaja[] es = estadoCajas;
        for (int i = 0; i < es.length; i++) {
            EstadoCaja e = es[i];
            if (i > 0) { b.append(','); }
            b.append("{\"id\":").append(esc(e.enLinea ? e.idCaja : "Caja " + (i + 1)))
                    .append(",\"direccion\":").append(esc(cajas.get(i).toString()))
                    .append(",\"enLinea\":").append(e.enLinea)
                    .append(",\"ventas\":").append(e.ventas)
                    .append(",\"maxClientes\":").append(e.maxC)
                    .append(",\"sesiones\":").append(e.sesiones)
                    .append(",\"reloj\":").append(e.rlj).append('}');
        }
        return b.append("]}").toString();
    }

    private static String jsonCompra(Compra c) {
        StringBuilder b = new StringBuilder("{\"id\":").append(c.id)
                .append(",\"nombre\":").append(esc(c.nom))
                .append(",\"boletos\":").append(c.pedidos)
                .append(",\"comprados\":").append(c.comprados)
                .append(",\"estado\":").append(esc(c.estado))
                .append(",\"resultado\":").append(c.resultado() == null ? "null" : esc(c.resultado()))
                .append(",\"caja\":").append(esc(c.caja))
                .append(",\"mensaje\":").append(esc(c.msj))
                .append(",\"reloj\":").append(c.rlj)
                .append(",\"color\":").append(c.color)
                .append(",\"creada\":").append(c.creada)
                .append(",\"folios\":[");
        List<Integer> f = c.folios;
        for (int i = 0; i < f.size(); i++) {
            if (i > 0) { b.append(','); }
            b.append(f.get(i));
        }
        return b.append("],\"enlaces\":{\"self\":\"").append(API).append("/compras/").append(c.id).append("\"}}").toString();
    }

    private String jsonCompras(Set<Integer> ids, boolean soloActivas, int limite) {
        List<Compra> elegidas = new ArrayList<>();
        for (Compra c : compras) {
            if (ids != null && !ids.contains(c.id)) { continue; }
            if (soloActivas && !c.activa) { continue; }
            elegidas.add(c);
        }
        int desde = Math.max(0, elegidas.size() - limite);
        StringBuilder b = new StringBuilder("{\"total\":" + elegidas.size() + ",\"compras\":[");
        for (int i = desde; i < elegidas.size(); i++) {
            if (i > desde) { b.append(','); }
            b.append(jsonCompra(elegidas.get(i)));
        }
        return b.append("]}").toString();
    }

    private synchronized String jsonEventos(int desde) {
        boolean reinicio = desde > eventos.size();
        if (reinicio || desde < 0) { desde = 0; }
        StringBuilder b = new StringBuilder("{\"reinicio\":").append(reinicio).append(",\"eventos\":[");
        for (int i = desde; i < eventos.size(); i++) {
            Evento e = eventos.get(i);
            if (e.txt == null) { continue; }
            if (b.charAt(b.length() - 1) != '[') { b.append(','); }
            b.append("{\"compra\":").append(e.compra)
                    .append(",\"nombre\":").append(esc(e.nom))
                    .append(",\"tipo\":").append(esc(e.tipo))
                    .append(",\"caja\":").append(esc(e.caja))
                    .append(",\"reloj\":").append(e.rlj)
                    .append(",\"texto\":").append(esc(e.txt)).append('}');
        }
        return b.append("],\"siguiente\":").append(eventos.size()).append('}').toString();
    }

    // ------------------------------------------------------------------ HTTP
    private static void cabeceras(HttpExchange ex, String tipo) {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", tipo);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", CSP);
    }

    private static void responder(HttpExchange ex, int codigo, String tipo, byte[] cuerpo, String... extra) throws IOException {
        cabeceras(ex, tipo);
        for (int i = 0; i + 1 < extra.length; i += 2) { ex.getResponseHeaders().set(extra[i], extra[i + 1]); }
        boolean sinCuerpo = codigo == 304 || ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(codigo, sinCuerpo ? -1 : cuerpo.length);
        if (!sinCuerpo) {
            try (OutputStream os = ex.getResponseBody()) { os.write(cuerpo); }
        }
    }

    private static void json(HttpExchange ex, int codigo, String cuerpo, String... extra) throws IOException {
        String[] todo = Arrays.copyOf(extra, extra.length + 2);
        todo[extra.length] = "Cache-Control";
        todo[extra.length + 1] = "no-store";
        responder(ex, codigo, "application/json; charset=UTF-8", cuerpo.getBytes(StandardCharsets.UTF_8), todo);
    }

    /** GET con ETag: si el cliente ya tiene esta versión, responde 304 sin cuerpo. */
    private static void jsonConEtag(HttpExchange ex, String cuerpo) throws IOException {
        String etag = "\"" + Integer.toHexString(cuerpo.hashCode()) + "\"";
        String previo = ex.getRequestHeaders().getFirst("If-None-Match");
        if (etag.equals(previo)) {
            responder(ex, 304, "application/json", new byte[0], "ETag", etag, "Cache-Control", "no-cache");
            return;
        }
        responder(ex, 200, "application/json; charset=UTF-8", cuerpo.getBytes(StandardCharsets.UTF_8),
                "ETag", etag, "Cache-Control", "no-cache");
    }

    private boolean hostValido(HttpExchange ex) {
        if (publico) { return true; }
        String host = ex.getRequestHeaders().getFirst("Host");
        return host != null && (host.equals("localhost:" + prto) || host.equals("127.0.0.1:" + prto));
    }

    private static boolean metodo(HttpExchange ex, String... permitidos) throws IOException {
        String m = ex.getRequestMethod();
        for (String p : permitidos) {
            if (p.equals(m) || (p.equals("GET") && m.equals("HEAD"))) { return true; }
        }
        json(ex, 405, error("Método no permitido", null), "Allow", String.join(", ", permitidos));
        return false;
    }

    private void estatico(HttpExchange ex, String ruta) throws IOException {
        if (!metodo(ex, "GET")) { return; }
        if (ruta.equals("/") || ruta.equals("/index.html")) { ruta = "/index.html"; }
        else if (!ESTATICO.matcher(ruta).matches()) {
            responder(ex, 404, "text/plain; charset=UTF-8", "No encontrado".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Path archivo = web.resolve(ruta.substring(1)).normalize();
        if (!archivo.startsWith(web) || !Files.isRegularFile(archivo)) {
            responder(ex, 404, "text/plain; charset=UTF-8", ("No se encontró " + ruta).getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] datos = Files.readAllBytes(archivo);
        String nombre = archivo.getFileName().toString();
        String tipo = nombre.endsWith(".html") ? "text/html; charset=UTF-8"
                : nombre.endsWith(".css") ? "text/css; charset=UTF-8"
                : nombre.endsWith(".js") ? "text/javascript; charset=UTF-8"
                : nombre.endsWith(".webmanifest") ? "application/manifest+json; charset=UTF-8"
                : nombre.endsWith(".svg") ? "image/svg+xml"
                : "image/png";
        String etag = "\"" + Integer.toHexString(Arrays.hashCode(datos)) + "\"";
        if (etag.equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
            responder(ex, 304, tipo, new byte[0], "ETag", etag, "Cache-Control", "no-cache");
            return;
        }
        // no-cache = "revalida con el servidor antes de usarlo"; el service worker es quien guarda la copia sin conexión.
        if (nombre.equals("sw.js")) {
            responder(ex, 200, tipo, datos, "ETag", etag, "Cache-Control", "no-cache", "Service-Worker-Allowed", "/");
        } else {
            responder(ex, 200, tipo, datos, "ETag", etag, "Cache-Control", "no-cache");
        }
    }

    private void api(HttpExchange ex, String ruta) throws IOException {
        String r = ruta.substring(API.length());
        if (r.endsWith("/") && r.length() > 1) { r = r.substring(0, r.length() - 1); }
        Map<String, String> q = Web.parametros(ex);

        if (r.equals("/salud")) {
            if (metodo(ex, "GET")) { json(ex, 200, "{\"estado\":\"ok\"}"); }
        } else if (r.equals("/evento")) {
            if (metodo(ex, "GET")) { jsonConEtag(ex, jsonEvento()); }
        } else if (r.equals("/cajas")) {
            if (metodo(ex, "GET")) { jsonConEtag(ex, jsonCajas()); }
        } else if (r.equals("/eventos")) {
            if (!metodo(ex, "GET")) { return; }
            int desde;
            try {
                desde = Integer.parseInt(q.getOrDefault("desde", "0"));
            } catch (NumberFormatException e) {
                desde = 0;
            }
            json(ex, 200, jsonEventos(desde));
        } else if (r.equals("/compras")) {
            if (!metodo(ex, "GET", "POST")) { return; }
            if (ex.getRequestMethod().equals("POST")) { crearCompra(ex); } else { listarCompras(ex, q); }
        } else if (r.matches("/compras/\\d{1,9}")) {
            if (!metodo(ex, "GET")) { return; }
            int id = Integer.parseInt(r.substring("/compras/".length()));
            Compra c = compras.stream().filter(x -> x.id == id).findFirst().orElse(null);
            if (c == null) { json(ex, 404, error("La compra no existe", null)); } else { json(ex, 200, jsonCompra(c)); }
        } else {
            json(ex, 404, error("Recurso no encontrado", null));
        }
    }

    private void listarCompras(HttpExchange ex, Map<String, String> q) throws IOException {
        Set<Integer> ids = null;
        if (q.containsKey("ids")) {
            ids = new HashSet<>();
            for (String t : q.get("ids").split(",")) {
                try {
                    ids.add(Integer.parseInt(t.trim()));
                } catch (NumberFormatException e) {
                    json(ex, 400, error("ids debe ser una lista de números separados por coma", "ids"));
                    return;
                }
            }
        }
        int limite = 100;
        if (q.containsKey("limite")) {
            try {
                limite = Math.max(1, Math.min(500, Integer.parseInt(q.get("limite"))));
            } catch (NumberFormatException e) {
                json(ex, 400, error("limite debe ser un número entre 1 y 500", "limite"));
                return;
            }
        }
        json(ex, 200, jsonCompras(ids, "true".equals(q.get("activas")), limite));
    }

    private void crearCompra(HttpExchange ex) throws IOException {
        // CSRF: otra página no puede mandar este encabezado ni este tipo de contenido sin que el navegador pida permiso.
        if (ex.getRequestHeaders().getFirst("X-Requested-With") == null) {
            json(ex, 403, error("Falta el encabezado X-Requested-With", null));
            return;
        }
        String tipo = ex.getRequestHeaders().getFirst("Content-Type");
        if (tipo == null || !tipo.toLowerCase().startsWith("application/json")) {
            json(ex, 415, error("Use Content-Type: application/json", null));
            return;
        }
        byte[] crudo;
        try (InputStream in = ex.getRequestBody()) { crudo = in.readNBytes(MAX_CUERPO + 1); }
        if (crudo.length > MAX_CUERPO) {
            json(ex, 413, error("El cuerpo es demasiado grande", null));
            return;
        }
        Map<String, String> cuerpo = leerJson(new String(crudo, StandardCharsets.UTF_8));
        if (cuerpo == null) {
            json(ex, 400, error("El cuerpo no es un objeto JSON válido", null));
            return;
        }
        String nombre = Web.limpiar(cuerpo.get("nombre"));
        if (nombre.isEmpty()) {
            json(ex, 400, error("Escribe tu nombre", "nombre"));
            return;
        }
        int boletos;
        try {
            boletos = Integer.parseInt(cuerpo.getOrDefault("boletos", ""));
        } catch (NumberFormatException e) {
            boletos = 0;
        }
        if (boletos < 1 || boletos > MAX_BOLETOS) {
            json(ex, 400, error("Puedes comprar de 1 a " + MAX_BOLETOS + " boletos", "boletos"));
            return;
        }
        String llave = ex.getRequestHeaders().getFirst("Idempotency-Key");
        if (llave != null && !LLAVE.matcher(llave).matches()) {
            json(ex, 400, error("Idempotency-Key debe tener de 8 a 64 caracteres (letras, números, - y _)", "Idempotency-Key"));
            return;
        }

        Compra nueva;
        synchronized (llaves) {
            // Idempotencia: si la app reintenta la misma compra (por ejemplo, al volver la conexión), no se crea otra.
            if (llave != null && llaves.containsKey(llave)) {
                int id = llaves.get(llave);
                Compra previa = compras.stream().filter(x -> x.id == id).findFirst().orElse(null);
                if (previa != null) {
                    json(ex, 200, jsonCompra(previa), "Location", API + "/compras/" + id, "Idempotent-Replayed", "true");
                    return;
                }
            }
            long activas = compras.stream().filter(c -> c.activa).count();
            if (activas >= MAX_ACTIVOS) {
                json(ex, 429, error("Hay " + activas + " compras en curso. Intenta en unos segundos", null), "Retry-After", "5");
                return;
            }
            nueva = new Compra(contador.incrementAndGet(), nombre, boletos);
            compras.add(nueva);
            if (llave != null) { llaves.put(llave, nueva.id); }
        }

        List<Web.Nodo> orden = new ArrayList<>(cajas);
        Collections.rotate(orden, -((nueva.id - 1) % cajas.size()));
        final Compra c = nueva;
        final int pedidos = boletos;
        Thread hilo = new Thread(() -> {
            try {
                Cliente.comprar(nombre, pedidos, orden, (t, caja, reloj, faltan, texto) -> registrar(c, t, caja, reloj, faltan, texto));
            } catch (RuntimeException e) {
                registrar(c, "error", "", c.rlj, pedidos - c.comprados, "Error inesperado: " + e.getMessage());
                registrar(c, "fin", "", c.rlj, pedidos - c.comprados, "Terminó por un error.");
            }
        }, "cliente-" + nombre);
        hilo.setDaemon(true);
        hilo.start();
        // 202 Accepted: la compra se procesa en segundo plano; la app consulta Location para ver cómo va.
        json(ex, 202, jsonCompra(c), "Location", API + "/compras/" + c.id);
    }

    private void atender(HttpExchange ex) throws IOException {
        try {
            if (!hostValido(ex)) {
                responder(ex, 403, "text/plain; charset=UTF-8", "Host no permitido".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String ruta = ex.getRequestURI().getPath();
            if (ruta.equals(API) || ruta.startsWith(API + "/")) { api(ex, ruta); } else { estatico(ex, ruta); }
        } catch (RuntimeException e) {
            System.out.println("[Interfaz] Error atendiendo " + ex.getRequestURI() + ": " + e);
            try {
                json(ex, 500, error("Error interno", null));
            } catch (IOException | RuntimeException ignorado) {
                // la respuesta ya había empezado
            }
        } finally {
            ex.close();
        }
    }

    void iniciar() throws IOException {
        HttpServer servidor = HttpServer.create(new InetSocketAddress(publico ? InetAddress.getByName("0.0.0.0") : InetAddress.getLoopbackAddress(), prto), 0);
        servidor.setExecutor(Executors.newFixedThreadPool(8));
        servidor.createContext("/", this::atender);

        ScheduledExecutorService sondeo = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sondeo");
            t.setDaemon(true);
            return t;
        });
        sondeo.scheduleWithFixedDelay(() -> {
            try {
                sondear();
            } catch (Throwable t) {
                System.out.println("[Interfaz] Error al consultar el sistema: " + t);
            }
        }, 0, 500, TimeUnit.MILLISECONDS);

        servidor.start();
        System.out.println();
        System.out.println("[Interfaz] App disponible en http://localhost:" + prto + "  (ábrela en Chrome o Edge para instalarla)");
        System.out.println("[Interfaz] API REST en http://localhost:" + prto + API);
        System.out.println("[Interfaz] Sistema central: " + sistema + ". Cajas: " + cajas);
        System.out.println("[Interfaz] Archivos de la app: " + web);
        System.out.println(publico
                ? "[Interfaz] Modo público: acepta conexiones de la red (el token interno no sale del servidor)."
                : "[Interfaz] Solo acepta conexiones desde este equipo. El token no sale de este proceso.");
        System.out.println();
    }

    /**
     * Todo en uno: un solo proceso con el sistema central, dos cajas y la interfaz.
     * Es lo que usa el despliegue (Docker/hosting): variables PORT, BOLETOS y CUPO.
     */
    private static void todoEnUno() throws IOException {
        // Token interno aleatorio: solo lo conocen los componentes de este proceso.
        if (System.getProperty("tkn") == null) { System.setProperty("tkn", java.util.UUID.randomUUID().toString()); }
        String pe = System.getenv("PORT");
        int puerto = pe != null ? Integer.parseInt(pe) : 8080;
        String boletos = System.getenv("BOLETOS") != null ? System.getenv("BOLETOS") : "20";
        String cupo = System.getenv("CUPO") != null ? System.getenv("CUPO") : "2";
        Sistema.main(new String[] { boletos, "5000" });
        Caja.main(new String[] { cupo, "5001", "C1", "localhost:5000" });
        Caja.main(new String[] { cupo, "5002", "C2", "localhost:5000" });
        new Interfaz(puerto, new Web.Nodo("localhost", 5000),
                new ArrayList<>(Arrays.asList(new Web.Nodo("localhost", 5001), new Web.Nodo("localhost", 5002)))).iniciar();
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) { todoEnUno(); return; }
        int puerto = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Web.Nodo sistema = Web.Nodo.de(args.length > 1 ? args[1] : "localhost:5000");
        List<Web.Nodo> cajas = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            Web.Nodo n = Web.Nodo.de(args[i]);
            if (n != null) { cajas.add(n); }
        }
        if (cajas.isEmpty()) {
            cajas.add(new Web.Nodo("localhost", 5001));
            cajas.add(new Web.Nodo("localhost", 5002));
        }
        if (sistema == null) {
            System.out.println("El sistema central debe indicarse como host:puerto");
            return;
        }
        new Interfaz(puerto, sistema, cajas).iniciar();
    }
}
