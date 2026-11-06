package com.shopexpress.pedidos.cliente;

import com.shopexpress.pedidos.dto.Dtos.ItemSolicitado;
import com.shopexpress.pedidos.dto.Dtos.ReservaRemota;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Puertos hacia los microservicios que orquesta la SAGA. */
public final class Clientes {

    private Clientes() {
    }

    @Component
    public static class UsuariosCliente {
        private final ClienteHttp http;

        public UsuariosCliente(@Value("${shopexpress.usuarios.url}") String url,
                               @Value("${shopexpress.http.timeout-conexion}") Duration tc,
                               @Value("${shopexpress.http.timeout-lectura}") Duration tl,
                               ObjectMapper json) {
            this.http = new ClienteHttp("usuarios-service", url, tc, tl, json);
        }

        /** @return false si el usuario no existe (404). */
        public boolean existe(String usuarioId) {
            try {
                http.get("/api/usuarios/{id}".replace("{id}", usuarioId), JsonNode.class);
                return true;
            } catch (ClienteHttp.ErrorRemoto e) {
                if (e.getStatus() == 404) {
                    return false;
                }
                throw e;
            }
        }

        public void registrarCompra(String usuarioId, String pedidoId, BigDecimal total) {
            http.post("/api/usuarios/" + usuarioId + "/historial",
                    Map.of("pedido_id", pedidoId, "total", total), Void.class);
        }

        public void quitarCompra(String usuarioId, String pedidoId) {
            http.delete("/api/usuarios/" + usuarioId + "/historial/" + pedidoId);
        }
    }

    @Component
    public static class CatalogoCliente {
        private final ClienteHttp http;

        public CatalogoCliente(@Value("${shopexpress.catalogo.url}") String url,
                               @Value("${shopexpress.http.timeout-conexion}") Duration tc,
                               @Value("${shopexpress.http.timeout-lectura}") Duration tl,
                               ObjectMapper json) {
            this.http = new ClienteHttp("catalogo-service", url, tc, tl, json);
        }

        public ReservaRemota reservar(String pedidoId, List<ItemSolicitado> items) {
            return http.post("/api/inventario/reservas", Map.of("pedido_id", pedidoId, "items", items), ReservaRemota.class);
        }

        public void liberar(String pedidoId) {
            http.delete("/api/inventario/reservas/" + pedidoId);
        }
    }
}
