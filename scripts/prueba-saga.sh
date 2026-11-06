#!/usr/bin/env bash
# Recorre todos los escenarios de la SAGA de pedidos contra el sistema levantado con docker compose.
# Uso: ./scripts/prueba-saga.sh
set -u
US=${USUARIOS_URL:-http://localhost:8081}
CA=${CATALOGO_URL:-http://localhost:8082}
PE=${PEDIDOS_URL:-http://localhost:8083}
J='Content-Type: application/json'
FALLAS=0

campo() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
stock() { curl -s "$CA/api/productos/$1" | grep -o '"stock":-\?[0-9]*' | cut -d: -f2; }
pasos() { grep -o '"paso":"[A-Z_]*","tipo":"[A-Z]*","resultado":"[A-Z]*"' | sed 's/"paso":"\([A-Z_]*\)","tipo":"\([A-Z]*\)","resultado":"\([A-Z]*\)"/\1(\2:\3)/' | tr '\n' ' '; }
esperar() { # descripcion valor_obtenido valor_esperado
  if [ "$2" == "$3" ]; then echo "   OK   $1 = $2"; else echo "   FALLA $1: obtenido '$2', esperado '$3'"; FALLAS=$((FALLAS+1)); fi
}
pedido() { # key body [header extra]
  curl -s -w '\n%{http_code}' -X POST "$PE/api/pedidos" -H "$J" -H "Idempotency-Key: $1" ${3:+-H "$3"} -d "$2"
}

SUF=$RANDOM$RANDOM
echo "== Preparacion: usuario y productos"
USR=$(curl -s -X POST "$US/api/usuarios" -H "$J" -d "{\"tipo_documento\":\"DNI\",\"nro_documento\":\"$SUF\",\"nombre\":\"Juan\",\"apellido\":\"Perez\",\"email\":\"juan.$SUF@example.com\",\"direcciones\":[{\"calle\":\"Av. Principal 123\",\"ciudad\":\"Montevideo\",\"codigo_postal\":\"11000\"}]}" | campo id)
P1=$(curl -s -X POST "$CA/api/productos" -H "$J" -d '{"nombre":"Auriculares","categoria":"Electronica","precio":25.50,"stock":10}' | campo id)
P2=$(curl -s -X POST "$CA/api/productos" -H "$J" -d '{"nombre":"Notebook","categoria":"Electronica","precio":9000,"stock":3}' | campo id)
echo "   usuario=$USR  p1=$P1 (stock 10, \$25.50)  p2=$P2 (stock 3, \$9000)"

echo; echo "== 1. Pedido exitoso (2 x p1)"
R=$(pedido "k1-$SUF" "{\"usuario_id\":\"$USR\",\"items\":[{\"producto_id\":\"$P1\",\"cantidad\":2}],\"pago\":{\"metodo\":\"TARJETA_CREDITO\"}}")
esperar "HTTP" "$(echo "$R" | tail -1)" 201
esperar "estado" "$(echo "$R" | campo estado)" CONFIRMADO
esperar "stock p1" "$(stock "$P1")" 8
echo "   pasos: $(echo "$R" | pasos)"
ORD1=$(echo "$R" | campo pedido_id)

echo; echo "== 2. Reintento con la misma Idempotency-Key (no cobra ni reserva de nuevo)"
R=$(pedido "k1-$SUF" "{\"usuario_id\":\"$USR\",\"items\":[{\"producto_id\":\"$P1\",\"cantidad\":2}],\"pago\":{\"metodo\":\"TARJETA_CREDITO\"}}")
esperar "HTTP" "$(echo "$R" | tail -1)" 200
esperar "mismo pedido" "$(echo "$R" | campo pedido_id)" "$ORD1"
esperar "stock p1 sin cambios" "$(stock "$P1")" 8

echo; echo "== 3. Sin stock (pide 5 de p2, hay 3)"
R=$(pedido "k3-$SUF" "{\"usuario_id\":\"$USR\",\"items\":[{\"producto_id\":\"$P2\",\"cantidad\":5}],\"pago\":{\"metodo\":\"TARJETA_CREDITO\"}}")
esperar "HTTP" "$(echo "$R" | tail -1)" 422
esperar "codigo" "$(echo "$R" | campo codigo_rechazo)" SIN_STOCK
esperar "saga" "$(echo "$R" | grep -o '"saga":{"estado":"[A-Z_]*"' | cut -d'"' -f6)" ABORTADA
esperar "stock p2" "$(stock "$P2")" 3

echo; echo "== 4. Usuario inexistente"
R=$(pedido "k4-$SUF" "{\"usuario_id\":\"usr_noexiste\",\"items\":[{\"producto_id\":\"$P1\",\"cantidad\":1}],\"pago\":{\"metodo\":\"TARJETA_CREDITO\"}}")
esperar "codigo" "$(echo "$R" | campo codigo_rechazo)" USUARIO_INEXISTENTE
esperar "stock p1" "$(stock "$P1")" 8

echo; echo "== 5. Pago rechazado -> compensacion: se libera el stock"
R=$(pedido "k5-$SUF" "{\"usuario_id\":\"$USR\",\"items\":[{\"producto_id\":\"$P1\",\"cantidad\":3}],\"pago\":{\"metodo\":\"TARJETA_RECHAZADA\"}}")
esperar "codigo" "$(echo "$R" | campo codigo_rechazo)" PAGO_RECHAZADO
esperar "estado_pago" "$(echo "$R" | campo estado_pago)" RECHAZADO
esperar "saga" "$(echo "$R" | grep -o '"saga":{"estado":"[A-Z_]*"' | cut -d'"' -f6)" COMPENSADA
esperar "stock p1 restituido" "$(stock "$P1")" 8
echo "   pasos: $(echo "$R" | pasos)"

echo; echo "== 6. Monto sobre el limite (2 x 9000 > 10000)"
R=$(pedido "k6-$SUF" "{\"usuario_id\":\"$USR\",\"items\":[{\"producto_id\":\"$P2\",\"cantidad\":2}],\"pago\":{\"metodo\":\"TARJETA_CREDITO\"}}")
esperar "codigo" "$(echo "$R" | campo codigo_rechazo)" PAGO_RECHAZADO
esperar "stock p2 restituido" "$(stock "$P2")" 3

echo; echo "== 7. Falla el ultimo paso (historial) -> reembolso + liberar stock"
R=$(pedido "k7-$SUF" "{\"usuario_id\":\"$USR\",\"items\":[{\"producto_id\":\"$P1\",\"cantidad\":1}],\"pago\":{\"metodo\":\"TARJETA_DEBITO\"}}" "X-Simular-Fallo: HISTORIAL")
esperar "codigo" "$(echo "$R" | campo codigo_rechazo)" FALLO_HISTORIAL
esperar "estado_pago" "$(echo "$R" | campo estado_pago)" REEMBOLSADO
esperar "stock p1 restituido" "$(stock "$P1")" 8
echo "   pasos: $(echo "$R" | pasos)"

echo; echo "== 8. Datos invalidos"
R=$(pedido "k8-$SUF" '{"items":[{"producto_id":"","cantidad":0}],"pago":{"metodo":"BITCOIN"}}')
esperar "codigo" "$(echo "$R" | campo codigo_rechazo)" DATOS_INVALIDOS
echo "   motivo: $(echo "$R" | campo motivo_rechazo)"

echo; echo "== 9. Sin header Idempotency-Key"
R=$(curl -s -w '\n%{http_code}' -X POST "$PE/api/pedidos" -H "$J" -d '{}')
esperar "HTTP" "$(echo "$R" | tail -1)" 400

echo; echo "== 10. Historial del usuario: solo el pedido confirmado"
H=$(curl -s "$US/api/usuarios/$USR" | grep -o '"pedido_id":"ord_[a-z0-9]*"' | cut -d'"' -f4 | tr '\n' ' ')
esperar "historial" "$H" "$ORD1 "

echo; echo "== 11. Concurrencia: 15 pedidos en paralelo por un producto con stock 3"
P3=$(curl -s -X POST "$CA/api/productos" -H "$J" -d '{"nombre":"Edicion limitada","categoria":"Coleccionables","precio":50,"stock":3}' | campo id)
TMP=$(mktemp -d)
for i in $(seq 1 15); do
  { pedido "kc$i-$SUF" "{\"usuario_id\":\"$USR\",\"items\":[{\"producto_id\":\"$P3\",\"cantidad\":1}],\"pago\":{\"metodo\":\"TARJETA_CREDITO\"}}" | tail -1; echo; } > "$TMP/$i" &
done
wait
esperar "confirmados (201)" "$(cat "$TMP"/* | grep -c '^201$')" 3
esperar "rechazados (422)" "$(cat "$TMP"/* | grep -c '^422$')" 12
esperar "stock final" "$(stock "$P3")" 0
rm -rf "$TMP"

echo
if [ "$FALLAS" -eq 0 ]; then echo "TODOS LOS ESCENARIOS OK"; else echo "$FALLAS VERIFICACION(ES) FALLARON"; exit 1; fi
