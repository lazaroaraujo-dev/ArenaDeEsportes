package br.com.arena.repository;

import br.com.arena.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmail(String email);

    @Query(value = """
            SELECT public.h04_registrar_cliente(
                :nome,
                :email,
                :telefone,
                :senhaHash
            )
            """, nativeQuery = true)
    Long registrarCliente(
            @Param("nome") String nome,
            @Param("email") String email,
            @Param("telefone") String telefone,
            @Param("senhaHash") String senhaHash
    );

    /**
     * Atualização explícita e determinística de dados pessoais. O SQL toca
     * exclusivamente nome, email e telefone: perfil e senha nunca são escritos.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE public.usuario
               SET nome = :nome,
                   email = :email,
                   telefone = :telefone
             WHERE id = :id
            """, nativeQuery = true)
    int atualizarDadosPessoais(
            @Param("id") Long id,
            @Param("nome") String nome,
            @Param("email") String email,
            @Param("telefone") String telefone
    );

    /** Executa a função SECURITY DEFINER que valida o hash sob lock e atualiza senha/versão. */
    @Query(value = """
            SELECT public.h04_alterar_senha(:actorUserId, :hashAtualEsperado, :novoHash)
            """, nativeQuery = true)
    Long alterarSenha(
            @Param("actorUserId") Long actorUserId,
            @Param("hashAtualEsperado") String hashAtualEsperado,
            @Param("novoHash") String novoHash
    );

}
