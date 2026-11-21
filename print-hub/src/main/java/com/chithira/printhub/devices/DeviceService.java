package com.chithira.printhub.devices;

import com.chithira.printhub.audit.AuditService;
import com.chithira.printhub.common.BusinessException;
import com.chithira.printhub.common.NotFoundException;
import com.chithira.printhub.common.TextUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Desktop registration and API keys: register, rotate, revoke, and check the key on every API call.
 * The plain key is returned only at creation / rotation; only its hash is stored.
 */
@Service
public class DeviceService {

    private final DeviceRepository repo;
    private final AuditService audit;

    public DeviceService(DeviceRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public List<Device> all() { return repo.findAllByOrderByName(); }

    public Device get(long id) { return repo.findById(id).orElseThrow(() -> new NotFoundException("Desktop")); }

    public long activeCount() { return repo.countByStatus(DeviceStatus.ACTIVE); }

    /** Registers a desktop. The returned key is shown to the admin once and never again. */
    @Transactional
    public String register(String name, String location, String createdBy) {
        String deviceName = TextUtils.clean(name);
        if (deviceName == null) throw new BusinessException("Enter a name for the desktop, e.g. Front-PC-1");
        if (repo.findByNameIgnoreCase(deviceName).isPresent()) throw new BusinessException("A desktop called '" + deviceName + "' already exists");
        ApiKeys.NewKey newKey = ApiKeys.generate();
        Device device = new Device();
        device.setName(deviceName);
        device.setLocation(TextUtils.clean(location));
        device.setCreatedBy(createdBy);
        device.setKey(newKey);
        repo.save(device);
        audit.record("DEVICE_CREATED", "DEVICE", device.getId(), "Desktop " + deviceName + " registered", null, device.toAudit());
        return newKey.plain();
    }

    /** New key; the old one stops working immediately. */
    @Transactional
    public String rotate(long id) {
        Device device = get(id);
        var before = device.toAudit();
        ApiKeys.NewKey newKey = ApiKeys.generate();
        device.setKey(newKey);
        device.setKeyRotatedAt(Instant.now());
        device.setStatus(DeviceStatus.ACTIVE);
        device.setRevokedAt(null);
        audit.record("DEVICE_KEY_ROTATED", "DEVICE", id, "New API key for " + device.getName(), before, device.toAudit());
        return newKey.plain();
    }

    @Transactional
    public void revoke(long id) {
        Device device = get(id);
        var before = device.toAudit();
        device.setStatus(DeviceStatus.REVOKED);
        device.setRevokedAt(Instant.now());
        audit.record("DEVICE_REVOKED", "DEVICE", id, "API key of " + device.getName() + " revoked", before, device.toAudit());
    }

    /** Checks a presented key; also records when and from where the desktop was last seen. */
    @Transactional
    public Optional<Device> authenticate(String key, String ip, String appVersion) {
        String prefix = ApiKeys.prefixOf(key);
        if (prefix == null) return Optional.empty();
        Optional<Device> found = repo.findByKeyPrefix(prefix)
                .filter(device -> device.getStatus() == DeviceStatus.ACTIVE)
                .filter(device -> ApiKeys.matches(key, device.getKeyHash()));
        found.ifPresent(device -> {
            Instant now = Instant.now();
            if (device.getLastSeenAt() == null || Duration.between(device.getLastSeenAt(), now).toSeconds() >= 30
                    || !Objects.equals(ip, device.getLastIp())) {
                device.setLastSeenAt(now);
                device.setLastIp(ip);
                if (appVersion != null && !appVersion.isBlank()) device.setAppVersion(appVersion.trim());
            }
        });
        return found;
    }
}
