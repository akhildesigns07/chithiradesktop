package com.chithira.printhub.web;

import com.chithira.printhub.auth.AccessService;
import com.chithira.printhub.auth.HubUser;
import com.chithira.printhub.common.PageKey;
import com.chithira.printhub.common.PrintHubProperties;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Values every page layout needs: shop name, the user and their menu. */
@ControllerAdvice(annotations = Controller.class)
public class GlobalModelAdvice {

    private final PrintHubProperties props;
    private final AccessService access;

    public GlobalModelAdvice(PrintHubProperties props, AccessService access) {
        this.props = props;
        this.access = access;
    }

    @ModelAttribute("shopName")
    public String shopName() { return props.shopName(); }

    @ModelAttribute("me")
    public HubUser me(@AuthenticationPrincipal Object principal) {
        return principal instanceof HubUser u ? u : null;
    }

    @ModelAttribute("menu")
    public Map<String, List<PageKey>> menu() { return access.menu(); }

    @ModelAttribute("zone")
    public ZoneId zone() { return ZoneId.systemDefault(); }
}
