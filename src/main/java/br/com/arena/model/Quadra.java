package br.com.arena.model;

import br.com.arena.enumerate.TipoQuadra;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Quadra {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoQuadra tipo;

    @Column(nullable = false)
    private boolean ativa;

    @OneToMany(mappedBy = "quadra")
    private List<Agendamento> agendamentos;
}