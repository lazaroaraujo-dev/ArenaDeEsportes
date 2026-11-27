package br.com.arena.service;

import br.com.arena.dto.usuario.AlterarSenhaDTO;
import br.com.arena.enumerate.Perfil;
import br.com.arena.exception.BusinessException;
import br.com.arena.model.Usuario;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.security.ClientRateLimiter;
import br.com.arena.security.SecurityHardeningProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** V5.8.2 — validações e chamada atômica da alteração de senha. */
class PasswordChangeServiceTest {

    private static final Long ACTOR_ID = 10L;
    private static final String HASH_ATUAL = "hash-atual";
    private static final String IP = "192.0.2.10";

    private UsuarioRepository usuarioRepository;
    private PasswordEncoder passwordEncoder;
    private PasswordChangeService service;

    @BeforeEach
    void setUp() {
        usuarioRepository = mock(UsuarioRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        ClientRateLimiter rateLimiter = new ClientRateLimiter(
                new SecurityHardeningProperties(10, 5, 60, false, 5, 300));
        service = new PasswordChangeService(usuarioRepository, passwordEncoder, rateLimiter);
        autenticar(Perfil.CLIENTE);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void autenticar(Perfil perfil) {
        Usuario usuario = new Usuario(ACTOR_ID, "Usuario", "usuario@exemplo.invalid",
                "11999999999", HASH_ATUAL, perfil, 0L, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities()));
    }

    private static AlterarSenhaDTO dto(String atual, String nova, String confirmacao) {
        return AlterarSenhaDTO.builder()
                .senhaAtual(atual).novaSenha(nova).confirmacaoNovaSenha(confirmacao)
                .build();
    }

    @Test
    void alteracaoValidaChamaFuncaoComHashDaNovaSenhaEIdDoPrincipal() {
        when(passwordEncoder.matches("atual123", HASH_ATUAL)).thenReturn(true);
        when(passwordEncoder.matches("novaSenha123", HASH_ATUAL)).thenReturn(false);
        when(passwordEncoder.encode("novaSenha123")).thenReturn("novo-hash");

        service.alterarSenha(dto("atual123", "novaSenha123", "novaSenha123"), IP);

        verify(usuarioRepository).alterarSenha(ACTOR_ID, HASH_ATUAL, "novo-hash");
    }

    @Test
    void senhaAtualIncorretaNaoChamaFuncao() {
        when(passwordEncoder.matches("errada", HASH_ATUAL)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.alterarSenha(dto("errada", "novaSenha123", "novaSenha123"), IP));

        assertEquals("Senha atual inválida.", ex.getMessage());
        verify(usuarioRepository, never()).alterarSenha(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirmacaoDivergenteNaoChamaFuncao() {
        when(passwordEncoder.matches("atual123", HASH_ATUAL)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.alterarSenha(dto("atual123", "novaSenha123", "outraSenha123"), IP));

        assertEquals("A confirmação da nova senha não confere.", ex.getMessage());
        verify(usuarioRepository, never()).alterarSenha(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void novaSenhaIgualAtualNaoChamaFuncao() {
        when(passwordEncoder.matches("atual123", HASH_ATUAL)).thenReturn(true);
        when(passwordEncoder.matches("atual123", HASH_ATUAL)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.alterarSenha(dto("atual123", "atual123", "atual123"), IP));

        assertEquals("A nova senha deve ser diferente da atual.", ex.getMessage());
        verify(usuarioRepository, never()).alterarSenha(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void novaSenhaCurtaNaoChamaFuncao() {
        when(passwordEncoder.matches("atual123", HASH_ATUAL)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.alterarSenha(dto("atual123", "123", "123"), IP));

        assertEquals("A nova senha deve ter pelo menos 6 caracteres.", ex.getMessage());
        verify(usuarioRepository, never()).alterarSenha(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void semPrincipalAutenticadoNaoAltera() {
        SecurityContextHolder.clearContext();

        assertThrows(AccessDeniedException.class,
                () -> service.alterarSenha(dto("atual123", "novaSenha123", "novaSenha123"), IP));
        verify(usuarioRepository, never()).alterarSenha(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
