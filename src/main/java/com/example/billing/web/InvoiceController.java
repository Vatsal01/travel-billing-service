package com.example.billing.web;

import com.example.billing.common.Page;
import com.example.billing.invoice.Invoice;
import com.example.billing.invoice.InvoiceDetail;
import com.example.billing.invoice.InvoiceService;
import com.example.billing.web.dto.InvoiceRequest;
import com.example.billing.web.dto.InvoiceResponse;
import com.example.billing.web.dto.InvoiceSummaryResponse;
import com.example.billing.web.dto.PageResponse;
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
@RequestMapping(path = "/invoices", produces = MediaType.APPLICATION_JSON_VALUE)
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InvoiceResponse> create(@RequestBody InvoiceRequest request) {
        InvoiceDetail invoice = service.create(request.ids());
        return ResponseEntity
                .created(URI.create("/invoices/" + invoice.invoice().id()))
                .body(InvoiceResponse.of(invoice));
    }

    @GetMapping("/{id}")
    public InvoiceResponse get(@PathVariable String id) {
        return InvoiceResponse.of(service.get(id));
    }

    @GetMapping
    public PageResponse<InvoiceSummaryResponse> list(
            @RequestParam(defaultValue = Pagination.DEFAULT_LIMIT) @Min(1) @Max(Pagination.MAX_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        Page<Invoice> page = service.list(limit, offset);
        return PageResponse.of(page, limit, offset, InvoiceSummaryResponse::of);
    }

    // Replaces the billed set and rebuilds the snapshot from the transactions as they are now. 
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InvoiceResponse patch(@PathVariable String id, @RequestBody InvoiceRequest request) {
        return InvoiceResponse.of(service.replaceTransactions(id, request.ids()));
    }

    // Deletes the invoice and its snapshot. The billed transactions are left alone.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
