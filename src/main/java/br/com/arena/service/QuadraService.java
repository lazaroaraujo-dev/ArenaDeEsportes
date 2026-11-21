package br.com.arena.service;

import br.com.arena.dto.quadra.QuadraRequestDTO;
import br.com.arena.dto.quadra.QuadraResponseDTO;
import br.com.arena.exception.BusinessException;
import br.com.arena.exception.ResourceNotFoundException;
import br.com.arena.mapper.QuadraMapper;
import br.com.arena.model.Quadra;
import br.com.arena.repository.QuadraRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class QuadraService {

    private final QuadraRepository quadraRepository;

    public QuadraService(QuadraRepository quadraRepository) {
        this.quadraRepository = quadraRepository;
    }

    public List<QuadraResponseDTO> listarTodas() {

        return quadraRepository.findAll()
                .stream()
                .map(QuadraMapper::toDTO)
                .collect(Collectors.toList());
    }

    public QuadraResponseDTO buscarPorId(Long id) {

        return QuadraMapper.toDTO(buscarEntidadePorId(id));
    }

    public Quadra buscarEntidadePorId(Long id) {

        return quadraRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quadra não encontrada."));
    }

    /**
     * A chamada deve ocorrer dentro da transação que decide/escreve o estado da quadra.
     */
    @Transactional
    public Quadra buscarParaAtualizacao(Long id) {
        return quadraRepository.buscarPorIdParaAtualizacao(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quadra não encontrada."));
    }

    public QuadraResponseDTO salvar(QuadraRequestDTO dto) {

        Quadra quadra = QuadraMapper.toEntity(dto);

        quadra = quadraRepository.save(quadra);

        return QuadraMapper.toDTO(quadra);
    }

    @Transactional
    public QuadraResponseDTO atualizar(Long id, QuadraRequestDTO dto) {

        Quadra quadra = buscarParaAtualizacao(id);

        quadra.setNome(dto.getNome());
        quadra.setTipo(dto.getTipo());
        quadra.setAtiva(dto.isAtiva());

        quadra = quadraRepository.saveAndFlush(quadra);

        return QuadraMapper.toDTO(quadra);
    }

    @Transactional
    public QuadraResponseDTO alterarStatus(Long id, boolean ativa) {

        Quadra quadra = buscarParaAtualizacao(id);

        quadra.setAtiva(ativa);

        quadra = quadraRepository.saveAndFlush(quadra);

        return QuadraMapper.toDTO(quadra);
    }

    @Transactional
    public void deletar(Long id) {

        Quadra quadra = buscarParaAtualizacao(id);

        if (!quadra.getAgendamentos().isEmpty()) {
            throw new BusinessException(
                    "Não é possível excluir uma quadra que possui agendamentos."
            );
        }

        quadraRepository.delete(quadra);
    }

}
