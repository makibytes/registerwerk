package de.makibytes.registerwerk.web.controller;

import de.makibytes.registerwerk.application.customer.CompanyUserService;
import de.makibytes.registerwerk.web.dto.PublicPasswordResetCompleteRequest;
import de.makibytes.registerwerk.web.dto.PublicUserActionTokenInfoResponse;
import de.makibytes.registerwerk.web.dto.PublicUserRegistrationCompleteRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/public/company-users")
public class PublicCompanyUserController {

    private final CompanyUserService companyUserService;

    public PublicCompanyUserController(CompanyUserService companyUserService) {
        this.companyUserService = companyUserService;
    }

    @GetMapping("/registration/{token}")
    public ResponseEntity<PublicUserActionTokenInfoResponse> getRegistrationInfo(@PathVariable String token) {
        return ResponseEntity.ok(companyUserService.getRegistrationTokenInfo(token));
    }

    @PostMapping("/registration/complete")
    public ResponseEntity<Map<String, String>> completeRegistration(
            @Valid @RequestBody PublicUserRegistrationCompleteRequest request) {
        companyUserService.completeRegistration(request);
        return ResponseEntity.ok(Map.of("message", "Registration completed successfully"));
    }

    @GetMapping("/password-reset/{token}")
    public ResponseEntity<PublicUserActionTokenInfoResponse> getPasswordResetInfo(@PathVariable String token) {
        return ResponseEntity.ok(companyUserService.getPasswordResetTokenInfo(token));
    }

    @PostMapping("/password-reset/complete")
    public ResponseEntity<Map<String, String>> completePasswordReset(
            @Valid @RequestBody PublicPasswordResetCompleteRequest request) {
        companyUserService.completePasswordReset(request);
        return ResponseEntity.ok(Map.of("message", "Password reset completed successfully"));
    }
}
