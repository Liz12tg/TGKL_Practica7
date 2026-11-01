import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Web {
    static final String tkn_pre = "boleteria-2026";
    static final String tkn = System.getProperty("tkn", tkn_pre);
    static final int NOMBRE_MAX = 40;
    static final int RELOJ_MAX = 1_000_000_000;
    private static final int HILOS = Integer.getInteger("hilos", 16);
    private static final int COLA = Integer.getInteger("cola", 64);

    private Web() { }

    static class Nodo {
        final String host;
        final int puerto;

        Nodo(String host, int puerto) {
            this.host = host;
            this.puerto = puerto;
        }

        static Nodo de(String texto) {
            String[] hp = texto.split(":");
            return hp.length == 2 ? new Nodo(hp[0], Integer.parseInt(hp[1])) : null;
        }

        @Override
        public String toString() { return host + ":" + puerto; }
    }

    static class ErrorHttp extends IOException {
        final int codigo;

        ErrorHttp(int codigo, String cuerpo) {
            super("HTTP " + codigo + " " + cuerpo);
            this.codigo = codigo;
        }
    }

    interface Accion { void ejecutar(HttpExchange ex, Map<String, String> p) throws IOException; }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private static String enviar(HttpRequest.Builder b, Nodo nodo, String camino, String sesion, Duration limite)
            throws IOException, InterruptedException {
        b.uri(URI.create("http://" + nodo.host + ":" + nodo.puerto + camino))
                .timeout(limite)
                .header("Authorization", "Bearer " + tkn);
        if (sesion != null) {
            b.header("X-Sesion", sesion);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) {
            throw new ErrorHttp(r.statusCode(), r.body());
        }
        return r.body();
    }

    static String post(Nodo nodo, String camino, String sesion, Duration limite) throws IOException, InterruptedException {
        return enviar(HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.noBody()), nodo, camino, sesion, limite);
    }

    static String get(Nodo nodo, String camino, Duration limite) throws IOException, InterruptedException {
        return enviar(HttpRequest.newBuilder().GET(), nodo, camino, null, limite);
    }

    static String campo(String json, String nombre) {
        Matcher m = Pattern.compile("\"" + nombre + "\"\\s*:\\s*\"?([^\",}]*)\"?").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    static String enc(String texto) { return URLEncoder.encode(texto, StandardCharsets.UTF_8); }

    static Map<String, String> parametros(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) {
            for (String par : q.split("&")) {
                String[] kv = par.split("=", 2);
                m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                      kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        return m;
    }

    static void responder(HttpExchange ex, int codigo, String json) throws IOException {
        byte[] cuerpo = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(codigo, cuerpo.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(cuerpo); }
    }

    static boolean autenticado(HttpExchange ex) {
        String recibido = ex.getRequestHeaders().getFirst("Authorization");
        if (recibido == null) { return false; }
        String esperado = "Bearer " + tkn;
        return MessageDigest.isEqual(recibido.getBytes(StandardCharsets.UTF_8), esperado.getBytes(StandardCharsets.UTF_8));
    }

    static String limpiar(String texto) {
        if (texto == null) { return ""; }
        String limpio = texto.replaceAll("\\p{Cntrl}", "").trim();
        return limpio.length() > NOMBRE_MAX ? limpio.substring(0, NOMBRE_MAX) : limpio;
    }

    static int leerReloj(String texto) {
        int valor = Integer.parseInt(texto);
        if (valor < 0 || valor > RELOJ_MAX) {
            throw new NumberFormatException("reloj fuera de rango");
        }
        return valor;
    }

    static void ruta(HttpServer http, String camino, String metodo, Accion accion) {
        http.createContext(camino, ex -> {
            try {
                if (!autenticado(ex)) {
                    ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    responder(ex, 401, "{\"error\":\"Token ausente o invalido\"}");
                    return;
                }
                if (!ex.getRequestMethod().equalsIgnoreCase(metodo)) {
                    responder(ex, 405, "{\"error\":\"Use el metodo " + metodo + "\"}");
                    return;
                }
                accion.ejecutar(ex, parametros(ex));
            } catch (NumberFormatException e) {
                responder(ex, 400, "{\"error\":\"Parametro numerico invalido o fuera de rango\"}");
            } finally {
                ex.close();
            }
        });
    }

    static ThreadPoolExecutor poolAcotado() {
        return new ThreadPoolExecutor(HILOS, HILOS, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(COLA), new ThreadPoolExecutor.AbortPolicy());
    }

    static String infoPool() { return "hilos: " + HILOS + ", cola: " + COLA; }
}
