package br.com.arena.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AgendamentoExpirationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(AgendamentoExpirationScheduler.class);

    private final AgendamentoExpirationService expirationService;

    public AgendamentoExpirationScheduler(AgendamentoExpirationService expirationService) {
        this.expirationService = expirationService;
    }

    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    public void processarAgendamentosVencidos() {
        int quantidade;
        int totalExpirado = 0;

        while ((quantidade = expirationService.expirarProximoLote()) > 0) {
            totalExpirado += quantidade;
        }

        if (totalExpirado > 0) {
            log.info("{} agendamento(s) pendente(s) expirado(s).", totalExpirado);
        }
    }
}
