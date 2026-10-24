package com.ledger.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping(path = "/", produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() {
        return "Ledger Service is up and running";
    }
}
