package com.chithira.printhub.devices;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** Desktop "Test hub connection": proves the key works. */
@RestController
@RequestMapping("/api/v1")
public class PingApiController {

    @GetMapping("/ping")
    public Map<String, Object> ping(@AuthenticationPrincipal DevicePrincipal device) {
        return Map.of("ok", true, "device", device.name(), "serverTime", Instant.now().toString(), "apiVersion", 1);
    }
}
