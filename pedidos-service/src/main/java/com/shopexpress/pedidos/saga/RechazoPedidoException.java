package com.shopexpress.pedidos.saga;

/**
 * Corta la cadena de manejadores y dispara las compensaciones de la SAGA.
 * {@code codigo} identifica el motivo (SIN_STOCK, PAGO_RECHAZADO, ...) para tests y para el cliente.
 */
public class RechazoPedidoException extends RuntimeException {

    private final String codigo;

    public RechazoPedidoException(String codigo, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
    }

    public RechazoPedidoException(String codigo, String mensaje, Throwable causa) {
        super(mensaje, causa);
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }
}
