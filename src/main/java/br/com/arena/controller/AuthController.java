package br.com.arena.controller;

import br.com.arena.auth.AuthService;
import br.com.arena.auth.LoginRequestDTO;
import br.com.arena.auth.LoginResponseDTO;
import br.com.arena.security.ClientIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final ClientIdentityResolver clientIdentityResolver;

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(
            @Valid @RequestBody LoginRequestDTO request,
            HttpServletRequest httpRequest) {

        String clientIp = clientIdentityResolver.resolve(httpRequest);
        return ResponseEntity.ok(authService.login(request, clientIp));
    }
}
