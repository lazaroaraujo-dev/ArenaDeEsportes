package br.com.arena.service;

import br.com.arena.repository.AgendamentoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgendamentoExpirationService {

    private static final int TAMANHO_LOTE = 500;

    private final AgendamentoRepository agendamentoRepository;

    public AgendamentoExpirationService(AgendamentoRepository agendamentoRepository) {
        this.agendamentoRepository = agendamentoRepository;
    }

    @Transactional
    public int expirarProximoLote() {
        return agendamentoRepository.expirarLoteVencido(TAMANHO_LOTE);
    }
}
