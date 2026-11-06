package com.shopexpress.pedidos.saga;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Primera barrera de idempotencia (rapida): {@code SET idem:{key} pedidoId NX EX 24h} en Redis.
 * La segunda barrera (definitiva) es el indice unico {@code idempotency_key} en MongoDB, que
 * cubre el caso en que Redis no este disponible o haya perdido la clave.
 */
@Service
public class IdempotenciaService {

    private static final Logger log = LoggerFactory.getLogger(IdempotenciaService.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public IdempotenciaService(StringRedisTemplate redis, @Value("${shopexpress.idempotencia.ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /**
     * Intenta reservar la clave para {@code pedidoId}.
     * @return vacio si la clave es nueva; el pedido_id previo si ya se uso.
     */
    public Optional<String> reclamar(String clave, String pedidoId) {
        try {
            Boolean nueva = redis.opsForValue().setIfAbsent("idem:" + clave, pedidoId, ttl);
            if (Boolean.TRUE.equals(nueva)) {
                return Optional.empty();
            }
            return Optional.ofNullable(redis.opsForValue().get("idem:" + clave));
        } catch (RuntimeException e) {
            log.warn("Redis no disponible para idempotencia, se usa el indice unico de Mongo: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
