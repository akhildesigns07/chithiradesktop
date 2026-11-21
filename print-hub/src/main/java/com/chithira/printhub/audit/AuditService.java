package com.chithira.printhub.audit;

import com.chithira.printhub.auth.HubUser;
import com.chithira.printhub.devices.DevicePrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the audit trail. Call {@link #record} from every service method that changes data,
 * inside the same transaction, so the change and its audit row are saved (or rolled back) together.
 */
@Service
public class AuditService {

    /** Header the desktop sends with the Windows user name of the person at the PC. */
    public static final String OPERATOR_HEADER = "X-Operator";

    private final AuditLogRepository repository;
    private final ObjectMapper jsonMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public AuditService(AuditLogRepository repository) { this.repository = repository; }

    @Transactional
    public void record(String action, String entityType, Object entityId, String summary, Object before, Object after) {
        record(current(), action, entityType, entityId, summary, before, after);
    }

    @Transactional
    public void record(Actor actor, String action, String entityType, Object entityId, String summary,
                       Object before, Object after) {
        repository.save(new AuditLog(actor, action, entityType, entityId == null ? null : String.valueOf(entityId),
                summary, toJson(before), toJson(after), ip()));
    }

    /** The logged-in user, the desktop (with its operator), or SYSTEM. */
    public Actor current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof HubUser user) return Actor.user(user.getId(), user.getUsername());
        if (authentication != null && authentication.getPrincipal() instanceof DevicePrincipal device) {
            HttpServletRequest request = request();
            String operator = request == null ? null : request.getHeader(OPERATOR_HEADER);
            return Actor.device(device.id(), device.name() + (operator == null || operator.isBlank() ? "" : " (" + operator.trim() + ")"));
        }
        return Actor.SYSTEM;
    }

    public List<AuditLog> latest() { return repository.findTop10ByOrderByIdDesc(); }

    /** Audit page search; every filter is optional. */
    public Page<AuditLog> search(String action, String entityType, String actor, String entityId,
                                 Instant from, Instant to, int page) {
        Specification<AuditLog> spec = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (action != null) predicates.add(builder.equal(root.get("action"), action));
            if (entityType != null) predicates.add(builder.equal(root.get("entityType"), entityType));
            if (entityId != null) predicates.add(builder.equal(root.get("entityId"), entityId));
            if (actor != null) predicates.add(builder.like(builder.lower(root.get("actorName")), "%" + actor.toLowerCase() + "%"));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("at"), from));
            if (to != null) predicates.add(builder.lessThan(root.get("at"), to));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return repository.findAll(spec, PageRequest.of(Math.max(0, page), 50, Sort.by(Sort.Direction.DESC, "id")));
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String ip() {
        HttpServletRequest request = request();
        return request == null ? null : request.getRemoteAddr();
    }

    private static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes ? attributes.getRequest() : null;
    }
}
