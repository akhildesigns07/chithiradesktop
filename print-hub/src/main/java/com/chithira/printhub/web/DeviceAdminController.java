package com.chithira.printhub.web;

import com.chithira.printhub.auth.HubUser;
import com.chithira.printhub.auth.RequiresPage;
import com.chithira.printhub.common.BusinessException;
import com.chithira.printhub.common.PageKey;
import com.chithira.printhub.devices.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Admin → Desktops & API keys. */
@Controller
@RequestMapping("/admin/devices")
@RequiresPage(PageKey.ADMIN_DEVICES)
public class DeviceAdminController {

    private final DeviceService devices;

    public DeviceAdminController(DeviceService devices) { this.devices = devices; }

    @GetMapping
    public String list(Model model, HttpServletRequest request) {
        model.addAttribute("devices", devices.all());
        model.addAttribute("hubUrl", request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort());
        return "admin/devices";
    }

    @PostMapping
    public String register(@RequestParam String name, @RequestParam(defaultValue = "") String location,
                           @AuthenticationPrincipal HubUser me, RedirectAttributes redirect) {
        try {
            String apiKey = devices.register(name, location, me.getUsername());
            showKey(redirect, "Desktop " + name.trim() + " registered", apiKey);
        } catch (BusinessException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/devices";
    }

    @PostMapping("/{id}/rotate")
    public String rotate(@PathVariable long id, RedirectAttributes redirect) {
        String apiKey = devices.rotate(id);
        showKey(redirect, "New API key for " + devices.get(id).getName(), apiKey);
        return "redirect:/admin/devices";
    }

    @PostMapping("/{id}/revoke")
    public String revoke(@PathVariable long id, RedirectAttributes redirect) {
        devices.revoke(id);
        redirect.addFlashAttribute("ok", "Key revoked: " + devices.get(id).getName() + " can no longer connect");
        return "redirect:/admin/devices";
    }

    private static void showKey(RedirectAttributes redirect, String title, String apiKey) {
        redirect.addFlashAttribute("secretTitle", title);
        redirect.addFlashAttribute("secret", apiKey);
        redirect.addFlashAttribute("secretNote", "Copy it now into hub.api-key in that PC's fiery-hotfolder.properties. "
                + "It is shown only once; if lost, rotate the key.");
    }
}
