package com.rulepilot.identity.adapter.in.web;

import com.rulepilot.identity.application.RegisterAccountService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/auth")
public class AuthenticationController {

    private final RegisterAccountService registerAccountService;

    AuthenticationController(RegisterAccountService registerAccountService) {
        this.registerAccountService = registerAccountService;
    }

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getParameterName(), csrfToken.getToken());
    }

    @GetMapping("/session")
    SessionResponse session(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .filter(authority -> authority.getAuthority().startsWith("ROLE_"))
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .sorted()
                .toList();
        return new SessionResponse(authentication.getName(), roles);
    }

    @PostMapping("/register")
    ResponseEntity<RegistrationResponse> register(@RequestBody RegistrationRequest request) {
        RegisterAccountService.RegistrationResult result;
        try {
            result = registerAccountService.register(request.username(), request.email(), request.password());
        } catch (com.rulepilot.identity.application.EmailAlreadyRegisteredException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new RegistrationResponse(null, "EMAIL_ALREADY_REGISTERED"));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(new RegistrationResponse(null, "INVALID_REGISTRATION"));
        }
        if (!result.created()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new RegistrationResponse(null, "USERNAME_ALREADY_REGISTERED"));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegistrationResponse(result.username(), null));
    }

    record CsrfResponse(String headerName, String parameterName, String token) {}

    record SessionResponse(String username, List<String> roles) {}

    record RegistrationRequest(String username, String email, String password) {}

    record RegistrationResponse(String username, String code) {}
}
