package com.shopexpress.usuarios.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Errores de negocio; Spring los serializa como ProblemDetail (RFC 9457). */
public final class Errores {

    private Errores() {
    }

    public static ErrorResponseException noEncontrado(String detalle) {
        return error(HttpStatus.NOT_FOUND, detalle);
    }

    public static ErrorResponseException conflicto(String detalle) {
        return error(HttpStatus.CONFLICT, detalle);
    }

    public static ErrorResponseException error(HttpStatus status, String detalle) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detalle), null);
    }
}
