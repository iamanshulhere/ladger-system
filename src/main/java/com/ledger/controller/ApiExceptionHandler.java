package com.ledger.controller;

import com.ledger.account.AccountNotFoundException;
import com.ledger.account.InvalidAccountIdException;
import com.ledger.auth.DuplicateEmailException;
import com.ledger.auth.InvalidCredentialsException;
import com.ledger.controller.dto.MessageResponse;
import com.ledger.transaction.TransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<DuplicateUserResponse> duplicateEmail(DuplicateEmailException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new DuplicateUserResponse(exception.getMessage(), "failed"));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<MessageResponse> invalidCredentials(InvalidCredentialsException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new MessageResponse(exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<MessageResponse> invalidRequest(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException validationException) {
            for (FieldError fieldError : validationException.getBindingResult().getFieldErrors()) {
                String message = fieldError.getDefaultMessage();
                if ("FromAccount, toAccount, amount and idempotencyKey are required".equals(message)
                        || "toAccount, amount and idempotencyKey are required".equals(message)
                        || "Transaction amount must be greater than zero".equals(message)) {
                    return ResponseEntity.badRequest().body(new MessageResponse(message));
                }
            }
        }
        return ResponseEntity.badRequest().body(new MessageResponse("Invalid request"));
    }

    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<MessageResponse> transactionError(TransactionException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new MessageResponse(exception.getMessage()));
    }

    @ExceptionHandler(InvalidAccountIdException.class)
    public ResponseEntity<MessageResponse> invalidAccountId(InvalidAccountIdException exception) {
        return ResponseEntity.badRequest().body(new MessageResponse(exception.getMessage()));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<MessageResponse> accountNotFound(AccountNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse(exception.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<MessageResponse> databaseError(DataAccessException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new MessageResponse("Unable to complete request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<MessageResponse> unexpectedError(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new MessageResponse("Unable to complete request"));
    }

    public record DuplicateUserResponse(String message, String status) {
    }
}
