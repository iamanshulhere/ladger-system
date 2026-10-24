package com.ledger.controller;

import com.ledger.controller.dto.InitialFundsRequest;
import com.ledger.controller.dto.TransactionApiResponse;
import com.ledger.controller.dto.TransferRequest;
import com.ledger.security.AuthenticatedPrincipal;
import com.ledger.transaction.TransactionOutcome;
import com.ledger.transaction.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping({"", "/"})
    public ResponseEntity<TransactionApiResponse> transfer(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody TransferRequest request) {
        return response(transactionService.transfer(principal.userId(), request));
    }

    @PostMapping("/system/initial-funds")
    public ResponseEntity<TransactionApiResponse> initialFunds(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody InitialFundsRequest request) {
        return response(transactionService.initialFunds(principal.userId(), request));
    }

    private ResponseEntity<TransactionApiResponse> response(TransactionOutcome outcome) {
        return ResponseEntity.status(outcome.status()).body(TransactionApiResponse.from(outcome));
    }
}
