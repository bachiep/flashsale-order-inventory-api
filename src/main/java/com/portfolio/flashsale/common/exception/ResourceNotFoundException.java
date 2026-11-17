package com.portfolio.flashsale.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String errorCode, String message) {
        super(HttpStatus.NOT_FOUND, errorCode, message);
    }

    public ResourceNotFoundException(String message) {
        this("RESOURCE_NOT_FOUND", message);
    }
}
