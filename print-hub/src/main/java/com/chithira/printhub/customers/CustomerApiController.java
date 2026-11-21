package com.chithira.printhub.customers;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desktop API.
 * <ul>
 *   <li>{@code GET /api/v1/customers} – all active customers (desktop cache), or {@code ?q=} search</li>
 *   <li>{@code GET /api/v1/customers?updatedSince=2026-11-21T10:00:00Z} – only changes (incl. deactivated)</li>
 *   <li>{@code POST /api/v1/customers} – quick add; 409 with the existing customer if the phone is already used</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerApiController {

    private final CustomerService customers;

    public CustomerApiController(CustomerService customers) { this.customers = customers; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince,
                                    @RequestParam(defaultValue = "5000") int limit) {
        Instant serverTime = Instant.now();
        List<CustomerDto> items = updatedSince != null
                ? customers.changedSince(updatedSince).stream().map(CustomerDto::of).toList()
                : customers.search(q, false, 0, limit).map(CustomerDto::of).getContent();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("serverTime", serverTime.toString());   // pass back as updatedSince next time
        return body;
    }

    @GetMapping("/{id}")
    public CustomerDto one(@PathVariable long id) { return CustomerDto.of(customers.get(id)); }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CustomerForm form) {
        try {
            Customer customer = customers.create(form, "DESKTOP");
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("item", CustomerDto.of(customer)));
        } catch (DuplicateCustomerException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", exception.getMessage(), "item", CustomerDto.of(exception.existing())));
        }
    }
}
