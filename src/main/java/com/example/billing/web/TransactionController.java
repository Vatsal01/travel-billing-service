package com.example.billing.web;

import com.example.billing.common.Page;
import com.example.billing.transaction.Transaction;
import com.example.billing.transaction.TransactionService;
import com.example.billing.web.dto.CreateTransactionRequest;
import com.example.billing.web.dto.PageResponse;
import com.example.billing.web.dto.PatchTransactionRequest;
import com.example.billing.web.dto.TransactionResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping(path = "/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TransactionResponse> create(@Valid @RequestBody CreateTransactionRequest request) {
        Transaction created = service.create(request.toCommand());
        return ResponseEntity
                .created(URI.create("/transactions/" + created.id()))
                .body(TransactionResponse.of(created));
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable String id) {
        return TransactionResponse.of(service.get(id));
    }

    @GetMapping
    public PageResponse<TransactionResponse> list(
            @RequestParam(defaultValue = Pagination.DEFAULT_LIMIT) @Min(1) @Max(Pagination.MAX_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        Page<Transaction> page = service.list(limit, offset);
        return PageResponse.of(page, limit, offset, TransactionResponse::of);
    }

    /** @return 409 if the transaction is already billed on an invoice; nothing is written */
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TransactionResponse patch(@PathVariable String id,
                                     @Valid @RequestBody PatchTransactionRequest request) {
        return TransactionResponse.of(service.patch(id, request.toCommand()));
    }

    /** @return 409 if the transaction is already billed on an invoice; nothing is deleted */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
