package br.com.arena.dto.usuario;

import br.com.arena.enumerate.Perfil;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsuarioPerfilDTO {

    @NotNull(message = "O perfil é obrigatório.")
    private Perfil perfil;
}
