package com.anbar.wms.identity.controller;

import com.anbar.wms.identity.dto.ChangePasswordRequest;
import com.anbar.wms.identity.dto.LoginRequest;
import com.anbar.wms.identity.dto.RegisterRequest;
import com.anbar.wms.identity.dto.TokenResponse;
import com.anbar.wms.identity.dto.UserResponse;
import com.anbar.wms.identity.service.AuthService;
import com.anbar.wms.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "Register, log in, and see who you are")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Register a new account", description = "The account gets the USER role.")
    @ApiResponse(responseCode = "201", description = "Registered")
    @ApiResponse(responseCode = "400", description = "Validation failed (VALIDATION_FAILED, MALFORMED_REQUEST)")
    @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_REGISTERED or USERNAME_ALREADY_TAKEN")
    @SecurityRequirements // public: no bearer token
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @Operation(summary = "Log in and get an access token",
            description = "Use the token as `Authorization: Bearer <accessToken>` (in Swagger: the Authorize button).")
    @ApiResponse(responseCode = "200", description = "Logged in")
    @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS, ACCOUNT_LOCKED or ACCOUNT_DISABLED")
    @SecurityRequirements // public: no bearer token
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()) // tokens must not be cached (RFC 6749 §5.1)
                .body(authService.login(request));
    }

    @Operation(summary = "The authenticated user")
    @ApiResponse(responseCode = "200", description = "Current user")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED, INVALID_TOKEN or TOKEN_EXPIRED")
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authService.currentUser(principal.id());
    }

    @Operation(summary = "Change your own password",
            description = "Re-checks the current password, then sets the new one. Existing tokens keep working until "
                    + "they expire.")
    @ApiResponse(responseCode = "204", description = "Password changed")
    @ApiResponse(responseCode = "400", description = "Validation failed (weak new password)")
    @ApiResponse(responseCode = "401", description = "The current password is incorrect")
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                               @AuthenticationPrincipal AuthenticatedUser principal) {
        authService.changePassword(principal.id(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
