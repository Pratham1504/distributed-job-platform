package io.jobplatform.identity;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final IdentityService identityService;
    private final LoginRateLimitService loginRateLimit;
    public AuthController(IdentityService identityService, LoginRateLimitService loginRateLimit) {
        this.identityService = identityService;
        this.loginRateLimit = loginRateLimit;
    }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    AuthSession register(@Valid @RequestBody RegisterRequest request) { return identityService.register(request); }
    @PostMapping("/login")
    AuthSession login(@Valid @RequestBody AuthRequest request, HttpServletRequest httpRequest) {
        try {
            loginRateLimit.reserveAttempt(request.email(), httpRequest.getRemoteAddr());
        } catch (LoginRateLimitService.LoginRateLimitExceededException exception) {
            identityService.recordRateLimitedLogin();
            throw exception;
        }
        return identityService.login(request);
    }
    @PostMapping("/refresh") AuthSession refresh(@Valid @RequestBody RefreshRequest request) { return identityService.refresh(request); }
}
