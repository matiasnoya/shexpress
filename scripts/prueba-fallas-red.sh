#!/usr/bin/env bash
# Fallas de red durante la SAGA (requiere docker compose en este directorio).
#  A) Catalogo DETENIDO: la conexion falla -> se sabe que no hubo reserva -> sin compensacion (saga ABORTADA).
#  B) Catalogo CONGELADO (docker pause): conecta pero no responde -> resultado desconocido ->
#     la compensacion falla (COMPENSACION_FALLIDA) -> al volver el catalogo, el job de reintentos
#     libera la reserva que llego "tarde" y la saga queda COMPENSADA (consistencia eventual).
set -u
US=http://localhost:8081; CA=http://localhost:8082; PE=http://localhost:8083
J='Content-Type: application/json'; S=$RANDOM$RANDOM
campo() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
saga() { grep -o '"saga":{"estado":"[A-Z_]*"' | cut -d'"' -f6; }
stock() { curl -s "$CA/api/productos/$1" | grep -o '"stock":[0-9]*' | cut -d: -f2; }
esperar_up() { for _ in $(seq 1 40); do [[ "$(curl -s "$1/actuator/health")" == *UP* ]] && return; sleep 2; done; }

USR=$(curl -s -X POST "$US/api/usuarios" -H "$J" -d "{\"tipo_documento\":\"DNI\",\"nro_documento\":\"r$S\",\"nombre\":\"Red\",\"apellido\":\"Test\",\"email\":\"red.$S@example.com\",\"direcciones\":[{\"calle\":\"a\",\"ciudad\":\"Montevideo\",\"codigo_postal\":\"11000\"}]}" | campo id)
P=$(curl -s -X POST "$CA/api/productos" -H "$J" -d '{"nombre":"Router","categoria":"Redes","precio":10,"stock":5}' | campo id)
BODY="{\"usuario_id\":\"$USR\",\"items\":[{\"producto_id\":\"$P\",\"cantidad\":2}],\"pago\":{\"metodo\":\"TARJETA_CREDITO\"}}"
echo "Producto $P con stock $(stock "$P")"

echo; echo "== A) Catalogo detenido"
docker compose stop catalogo-service >/dev/null 2>&1
R=$(curl -s -X POST "$PE/api/pedidos" -H "$J" -H "Idempotency-Key: ra-$S" -d "$BODY")
echo "   codigo=$(echo "$R" | campo codigo_rechazo)  saga=$(echo "$R" | saga)"
docker compose start catalogo-service >/dev/null 2>&1; esperar_up "$CA"
echo "   stock=$(stock "$P") (sin cambios)"

echo; echo "== B) Catalogo congelado"
docker compose pause catalogo-service >/dev/null 2>&1
R=$(curl -s -X POST "$PE/api/pedidos" -H "$J" -H "Idempotency-Key: rb-$S" -d "$BODY"); ORD=$(echo "$R" | campo pedido_id)
echo "   pedido=$ORD saga=$(echo "$R" | saga) $(echo "$R" | grep -o '"compensaciones_pendientes":\[[^]]*\]')"
docker compose unpause catalogo-service >/dev/null 2>&1; sleep 3
echo "   catalogo despausado: proceso la reserva atrasada -> stock=$(stock "$P")"
echo "   esperando al job de reintento de compensaciones..."
for _ in $(seq 1 40); do R=$(curl -s "$PE/api/pedidos/$ORD"); [ "$(echo "$R" | saga)" == "COMPENSADA" ] && break; sleep 2; done
echo "   saga=$(echo "$R" | saga)  stock=$(stock "$P")  reserva=$(curl -s "$CA/api/inventario/reservas/$ORD" | campo estado)"
