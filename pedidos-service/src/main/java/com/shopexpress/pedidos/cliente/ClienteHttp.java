package com.shopexpress.pedidos.cliente;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cliente REST sincrono con timeouts para comunicarse con otro microservicio.
 * Traduce cualquier falla a {@link ErrorRemoto}: status HTTP + detalle del ProblemDetail remoto,
 * o status 0 si el servicio no responde (caido / timeout).
 */
public class ClienteHttp {

    private final RestClient rest;
    private final ObjectMapper json;
    private final String servicio;

    public ClienteHttp(String servicio, String baseUrl, Duration timeoutConexion, Duration timeoutLectura, ObjectMapper json) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeoutConexion).build());
        factory.setReadTimeout(timeoutLectura);
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.json = json;
        this.servicio = servicio;
    }

    public <T> T get(String uri, Class<T> tipo) {
        return ejecutar(() -> leer(rest.get().uri(uri).retrieve().body(String.class), tipo));
    }

    public <T> T post(String uri, Object body, Class<T> tipo) {
        return ejecutar(() -> leer(rest.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body))
                .retrieve().body(String.class), tipo));
    }

    public void delete(String uri) {
        ejecutar(() -> rest.delete().uri(uri).retrieve().toBodilessEntity());
    }

    private <T> T leer(String cuerpo, Class<T> tipo) {
        return tipo == Void.class || cuerpo == null || cuerpo.isBlank() ? null : json.readValue(cuerpo, tipo);
    }

    private <T> T ejecutar(java.util.function.Supplier<T> llamada) {
        try {
            return llamada.get();
        } catch (RestClientResponseException e) {
            throw new ErrorRemoto(servicio, e.getStatusCode().value(), detalle(e.getResponseBodyAsString()));
        } catch (ResourceAccessException e) {
            ErrorRemoto er = new ErrorRemoto(servicio, 0, servicio + " no disponible: " + e.getMessage());
            er.noEnviada = noSeConecto(e);
            throw er;
        }
    }

    /** Si ni siquiera se pudo conectar, el servidor remoto no recibio la peticion: no hubo efecto. */
    private static boolean noSeConecto(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.http.HttpConnectTimeoutException
                    || t instanceof java.net.UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    private String detalle(String cuerpo) {
        try {
            JsonNode n = json.readTree(cuerpo);
            return n.hasNonNull("detail") ? n.get("detail").asString() : cuerpo;
        } catch (RuntimeException e) {
            return cuerpo;
        }
    }

    /** Falla al invocar otro microservicio. {@code status == 0} significa "no disponible". */
    public static class ErrorRemoto extends RuntimeException {
        private final String servicio;
        private final int status;
        private boolean noEnviada;

        public ErrorRemoto(String servicio, int status, String detalle) {
            super(detalle);
            this.servicio = servicio;
            this.status = status;
        }

        public String getServicio() { return servicio; }
        public int getStatus() { return status; }
        public boolean noDisponible() { return status == 0 || status >= 500; }
        /** true = seguro que el otro servicio no la recibio (fallo de conexion), no hay efecto que compensar. */
        public boolean noEnviada() { return noEnviada; }
    }
}
