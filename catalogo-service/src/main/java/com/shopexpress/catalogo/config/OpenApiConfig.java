package com.shopexpress.catalogo.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentacion OpenAPI. Swagger UI: /swagger-ui.html  -  JSON: /v3/api-docs */
@Configuration
public class OpenApiConfig {

    /** Los esquemas usan snake_case, igual que el JSON real de la API (spring.jackson.property-naming-strategy). */
    @Bean
    ModelResolver modelResolver() {
        return new ModelResolver(Json.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("ShopExpress - Servicio de Catalogo e Inventario")
                .version("1.0")
                .description("Productos, categorias, precios y stock. La reserva de stock es atomica (sin sobreventa) e idempotente por pedido_id."));
    }
}
