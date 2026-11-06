package com.shopexpress.catalogo.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Respuestas de error en formato ProblemDetail, con el detalle por campo en las validaciones. */
@RestControllerAdvice
public class ManejadorErrores extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errores = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errores.putIfAbsent(aSnakeCase(fe.getField()), fe.getDefaultMessage()));
        ex.getBody().setDetail("Datos invalidos");
        ex.getBody().setProperty("errores", errores);
        return handleExceptionInternal(ex, ex.getBody(), headers, status, request);
    }

    private static String aSnakeCase(String campo) {
        return campo.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
