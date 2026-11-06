package com.shopexpress.pedidos.cadena;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Arma la Chain of Responsibility. Agregar, quitar o reordenar validaciones es tocar solo esto:
 * <pre>
 * ValidadorDeDatos -> ValidadorDeUsuario -> ValidadorDeStock -> CalculadorDeTotal
 *   -> ProcesadorDePago -> RegistradorDeHistorial -> ConfirmadorDePedido
 * </pre>
 */
@Configuration
public class CadenaConfig {

    @Bean
    ManejadorPedido cadenaPedido(ValidadorDeDatos datos,
                                 ValidadorDeUsuario usuario,
                                 ValidadorDeStock stock,
                                 CalculadorDeTotal total,
                                 ProcesadorDePago pago,
                                 RegistradorDeHistorial historial,
                                 ConfirmadorDePedido confirmador) {
        datos.enlazar(usuario)
                .enlazar(stock)
                .enlazar(total)
                .enlazar(pago)
                .enlazar(historial)
                .enlazar(confirmador);
        return datos;
    }
}
