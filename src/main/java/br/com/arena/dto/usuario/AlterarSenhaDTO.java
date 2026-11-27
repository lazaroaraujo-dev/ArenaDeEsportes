package br.com.arena.dto.usuario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** DTO exclusivo da alteração da própria senha; nenhum ID de usuário é aceito. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlterarSenhaDTO {

    @NotBlank(message = "A senha atual é obrigatória.")
    private String senhaAtual;

    @NotBlank(message = "A nova senha é obrigatória.")
    @Size(min = 6, message = "A nova senha deve ter pelo menos 6 caracteres.")
    private String novaSenha;

    @NotBlank(message = "A confirmação da nova senha é obrigatória.")
    @Size(min = 6, message = "A confirmação da nova senha deve ter pelo menos 6 caracteres.")
    private String confirmacaoNovaSenha;
}
