package br.com.arena.auth;

import br.com.arena.exception.RateLimitSource;
import br.com.arena.exception.TooManyRequestsException;
import br.com.arena.model.Usuario;
import br.com.arena.security.JwtService;
import br.com.arena.security.LoginAttemptService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;

    private final JwtService jwtService;

    private final LoginAttemptService loginAttemptService;

    public LoginResponseDTO login(LoginRequestDTO request, String clientIp) {

        LoginAttemptService.LockCheckResult lockCheck =
                loginAttemptService.checkLock(clientIp, request.getEmail());
        if (lockCheck.locked()) {
            // Mensagem genérica: não revela se a conta existe nem seu estado.
            throw new TooManyRequestsException(
                    "Tentativas de login temporariamente bloqueadas.",
                    RateLimitSource.LOGIN_LOCKOUT,
                    lockCheck.retryAfterSeconds()
            );
        }

        try {
            Usuario usuario = (Usuario) authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getEmail(),
                            request.getSenha()
                    )
            ).getPrincipal();

            loginAttemptService.recordSuccess(clientIp, request.getEmail());
            return new LoginResponseDTO(jwtService.generateToken(usuario));
        } catch (AuthenticationException ex) {
            loginAttemptService.recordFailure(clientIp, request.getEmail());
            // Relança a exceção original: o handler global já retorna 401 genérico.
            throw ex;
        }
    }
}
