package br.com.arena.service;

import br.com.arena.dto.usuario.AlterarSenhaDTO;
import br.com.arena.exception.BusinessException;
import br.com.arena.exception.RateLimitSource;
import br.com.arena.exception.TooManyRequestsException;
import br.com.arena.model.Usuario;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.security.ClientRateLimiter;
import br.com.arena.security.Outcome;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Alteração autenticada da própria senha.
 *
 * <p>O ator é lido exclusivamente do principal autenticado. A atualização do
 * hash e o incremento de {@code credential_version} acontecem na mesma função
 * PostgreSQL {@code SECURITY DEFINER}; o runtime não possui UPDATE direto nessas
 * colunas. Nenhuma senha, hash ou token é registrado em log ou auditoria.</p>
 *
 * <p>V5.9.2.1: controle atômico de concorrência e rate limit via {@link ClientRateLimiter}.
 * Garante no máximo 5 operações simultâneas por conta, cobrindo o ciclo completo
 * de computação BCrypt e persistência. Falhas de senha atual incorreta são contabilizadas
 * separadamente por conta/IP com janela deslizante de 60 s.</p>
 */
@Service
@RequiredArgsConstructor
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    public static final String RATE_LIMIT_BUCKET = "password_change";
    private static final int MINIMUM_PASSWORD_LENGTH = 6;

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final ClientRateLimiter rateLimiter;

    public void alterarSenha(AlterarSenhaDTO dto, String clientIp) {
        Usuario ator = obterAtorAutenticado();

        ClientRateLimiter.PasswordAdmissionResult admission =
                rateLimiter.admitPasswordChangeWithDetails(ator.getId(), clientIp);
        if (!admission.admitted()) {
            throw limiteExcedido(admission.rejectionSource(), admission.retryAfterSeconds());
        }
        UUID opId = admission.operationId();

        Outcome outcome = Outcome.INFRA;
        Throwable primaryException = null;

        try {
            boolean senhaValida = passwordEncoder.matches(dto.getSenhaAtual(), ator.getSenha());
            if (!senhaValida) {
                outcome = Outcome.FAILED;
                throw new BusinessException("Senha atual inválida.");
            }

            outcome = Outcome.SUCCESS;

            if (!dto.getNovaSenha().equals(dto.getConfirmacaoNovaSenha())) {
                throw new BusinessException("A confirmação da nova senha não confere.");
            }
            if (dto.getNovaSenha().length() < MINIMUM_PASSWORD_LENGTH) {
                throw new BusinessException("A nova senha deve ter pelo menos 6 caracteres.");
            }
            if (passwordEncoder.matches(dto.getNovaSenha(), ator.getSenha())) {
                throw new BusinessException("A nova senha deve ser diferente da atual.");
            }

            String senhaHash = passwordEncoder.encode(dto.getNovaSenha());
            // O hash esperado vem do principal autenticado (carregado do banco pelo
            // filtro JWT). A função compara esse valor sob lock antes de atualizar.
            usuarioRepository.alterarSenha(ator.getId(), ator.getSenha(), senhaHash);
        } catch (Throwable t) {
            primaryException = t;
            throw t;
        } finally {
            try {
                rateLimiter.completePasswordChange(ator.getId(), opId, outcome);
            } catch (Error err) {
                throw err;
            } catch (Throwable completeEx) {
                if (primaryException != null) {
                    primaryException.addSuppressed(completeEx);
                    log.error("Falha ao concluir rate limiter para accountId={}; exceção de conclusão suprimida",
                            ator.getId(), completeEx);
                } else {
                    log.error("Falha ao concluir rate limiter para accountId={}", ator.getId(), completeEx);
                    throw new IllegalStateException(
                            "Falha de consistência interna ao liberar rate limiter.", completeEx);
                }
            }
        }
    }

    private static Usuario obterAtorAutenticado() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Usuario usuario)
                || usuario.getId() == null) {
            throw new AccessDeniedException("Operador não autenticado.");
        }
        return usuario;
    }

    private static TooManyRequestsException limiteExcedido(
            RateLimitSource source, long retryAfterSeconds) {
        return new TooManyRequestsException(
                "Limite de requisições excedido. Tente novamente mais tarde.",
                source,
                retryAfterSeconds);
    }
}
