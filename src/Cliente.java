import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Cliente {
    private static final int REINTENTOS = 3;
    private static final int MAX_RONDAS = 45;
    private static final long ESPERA_FILA_MS = 1000;

    interface Escucha {
        void evento(String tipo, String caja, int reloj, int faltan, String texto);
    }

    private static String postConReintentos(Web.Nodo caja, String camino, String sesion,
                                            Escucha escucha, int reloj, int faltan) throws IOException, InterruptedException {
        for (int intento = 1; ; intento++) {
            try {
                return Web.post(caja, camino, sesion, Duration.ofSeconds(30));
            } catch (Web.ErrorHttp e) {
                if (e.codigo < 500 || intento == REINTENTOS) { throw e; }
            } catch (IOException e) {
                if (intento == REINTENTOS) { throw e; }
            }
            escucha.evento("reintento", caja.toString(), reloj, faltan,
                    "Sin respuesta de la caja " + caja + ". Reintento " + intento + " de " + (REINTENTOS - 1) + " con la misma petición.");
            Thread.sleep(200L << (intento - 1));
        }
    }

    static int comprar(String nombre, int boletos, List<Web.Nodo> cajas, Escucha escucha) {
        int faltan = boletos;
        int reloj = 0;
        boolean terminar = false;
        boolean agotado = false;
        String nombreUrl = Web.enc(nombre);

        for (int ronda = 1; ronda <= MAX_RONDAS && faltan > 0 && !terminar; ronda++) {
            boolean atendido = false;
            for (Web.Nodo caja : cajas) {
                if (faltan <= 0 || terminar) { break; }
                String dir = caja.toString();
                escucha.evento("intento", dir, reloj, faltan, "Intentando pasar a la caja " + caja);
                try {
                    reloj++;
                    String rc = Web.post(caja, "/conectar?nombre=" + nombreUrl, null, Duration.ofSeconds(10));
                    reloj = Math.max(reloj, Integer.parseInt(Web.campo(rc, "reloj"))) + 1;

                    if (Web.campo(rc, "estado").equalsIgnoreCase("caja_llena")) {
                        escucha.evento("llena", dir, reloj, faltan, "La caja " + caja + " está llena.");
                        continue;
                    }
                    int idCliente = Integer.parseInt(Web.campo(rc, "idCliente"));
                    String sesion = Web.campo(rc, "sesion");
                    escucha.evento("conectado", dir, reloj, faltan, "Atendido en la caja " + caja + " como cliente #" + idCliente);

                    while (faltan > 0) {
                        reloj++;
                        String idPeticion = UUID.randomUUID().toString();
                        String rv = postConReintentos(caja, "/vender?reloj=" + reloj + "&idPeticion=" + idPeticion,
                                sesion, escucha, reloj, faltan);
                        String estado = Web.campo(rv, "estado");
                        reloj = Math.max(reloj, Integer.parseInt(Web.campo(rv, "reloj"))) + 1;

                        if (estado.equalsIgnoreCase("ok")) {
                            faltan--;
                            String nota = Web.campo(rv, "repetida").equals("true") ? " (respuesta repetida: no se vendió otro boleto)" : "";
                            escucha.evento("vendido", dir, reloj, faltan, "Boleto vendido en la caja " + caja + ". Sobran "
                                    + Web.campo(rv, "disponibles") + " boletos del evento. Le faltan comprar: " + faltan + nota);
                        } else {
                            agotado = true;
                            terminar = true;
                            escucha.evento("agotado", dir, reloj, faltan, "Se agotaron los boletos del evento.");
                            break;
                        }
                    }

                    reloj++;
                    String rs = postConReintentos(caja, "/salir?reloj=" + reloj, sesion, escucha, reloj, faltan);
                    reloj = Math.max(reloj, Integer.parseInt(Web.campo(rs, "reloj"))) + 1;
                    escucha.evento("salio", dir, reloj, faltan, null);
                    atendido = true;
                    break;
                } catch (Web.ErrorHttp e) {
                    if (e.codigo == 409) {
                        terminar = true;
                        escucha.evento("rechazo", dir, reloj, faltan, "La caja " + caja + " no permite más boletos para este cliente (" + e.getMessage() + ").");
                    } else {
                        escucha.evento("rechazo", dir, reloj, faltan, "La caja " + caja + " rechazó la petición (" + e.getMessage() + "). Se probará la siguiente caja.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    terminar = true;
                } catch (IOException | NumberFormatException e) {
                    escucha.evento("error", dir, reloj, faltan, "No se pudo conectar con la caja " + caja + ". Se probará la siguiente caja.");
                }
            }
            if (!atendido && faltan > 0 && !terminar) {
                escucha.evento("fila", "", reloj, faltan, "Todas las cajas están llenas. Espera en la fila (intento " + ronda + ").");
                try {
                    Thread.sleep(ESPERA_FILA_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    terminar = true;
                }
            }
        }

        String fin;
        if (faltan == 0) {
            fin = "Terminó su turno. Compró todos los boletos solicitados.";
        } else if (agotado) {
            fin = "Terminó su turno. Compró " + (boletos - faltan) + " de " + boletos + ": se agotaron los boletos del evento.";
        } else {
            fin = "Terminó su turno. Boletos que faltó comprar: " + faltan;
        }
        escucha.evento("fin", "", reloj, faltan, fin);
        return faltan;
    }

    public static void main(String[] args) {
        String nombre = args.length > 0 ? args[0] : "Cliente";
        int boletos = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        List<Web.Nodo> cajas = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            Web.Nodo n = Web.Nodo.de(args[i]);
            if (n != null) { cajas.add(n); }
        }
        if (cajas.isEmpty()) {
            cajas.add(new Web.Nodo("localhost", 5001));
        }

        comprar(nombre, boletos, cajas, (tipo, caja, reloj, faltan, texto) -> {
            if (texto == null) { return; }
            boolean sinReloj = tipo.equals("intento") || tipo.equals("reintento") || tipo.equals("fila") || tipo.equals("fin");
            if (tipo.equals("fin")) { System.out.println(); }
            System.out.println(sinReloj
                    ? "[" + nombre + "] " + texto
                    : "[reloj " + reloj + "][" + nombre + "] " + texto);
        });
    }
}
