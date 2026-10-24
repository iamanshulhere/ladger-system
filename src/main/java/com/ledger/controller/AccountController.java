package com.ledger.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledger.account.AccountService;
import com.ledger.controller.dto.AccountBalanceResponse;
import com.ledger.controller.dto.AccountResponse;
import com.ledger.controller.dto.AccountsResponse;
import com.ledger.security.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping({"", "/"})
    public ResponseEntity<CreatedAccountResponse> createAccount(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody(required = false) JsonNode ignoredRequestBody) {
        AccountResponse account = AccountResponse.from(accountService.createAccount(principal.userId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedAccountResponse(account));
    }

    @GetMapping({"", "/"})
    public AccountsResponse getAccounts(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        List<AccountResponse> accounts = accountService.getAccounts(principal.userId()).stream()
                .map(AccountResponse::from)
                .toList();
        return new AccountsResponse(accounts);
    }

    @GetMapping("/balance/{accountId}")
    public AccountBalanceResponse getBalance(@PathVariable String accountId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return new AccountBalanceResponse(accountId,
                accountService.getBalance(accountId, principal.userId()));
    }

    public record CreatedAccountResponse(AccountResponse account) {
    }
}
