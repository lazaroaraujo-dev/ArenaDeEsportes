package br.com.arena.auth;

import br.com.arena.model.Usuario;
import br.com.arena.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;

    private final JwtService jwtService;

    public LoginResponseDTO login(LoginRequestDTO request) {

        Usuario usuario = (Usuario) authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getSenha()
                )
        ).getPrincipal();

        String token = jwtService.generateToken(usuario);

        return new LoginResponseDTO(token);
    }

}