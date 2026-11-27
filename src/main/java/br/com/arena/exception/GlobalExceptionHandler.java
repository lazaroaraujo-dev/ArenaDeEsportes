package br.com.arena.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> tratarRecursoNaoEncontrado(
            ResourceNotFoundException ex) {

        ErrorResponse erro = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(erro);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> tratarRegraDeNegocio(
            BusinessException ex) {

        ErrorResponse erro = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                ex.getMessage()
        );

        return ResponseEntity.badRequest().body(erro);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> tratarConflito(ConflictException ex) {
        return respostaErro(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> tratarValidacao(
            MethodArgumentNotValidException ex) {

        String mensagem = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("Erro de validação nos dados enviados.");

        ErrorResponse erro = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                mensagem
        );

        return ResponseEntity.badRequest().body(erro);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> tratarErroGenerico(
            Exception ex) {
        if (violacaoDaConstraintDeSobreposicao(ex)) {
            return respostaErro(
                    HttpStatus.CONFLICT,
                    "Já existe um agendamento bloqueante nesse horário."
            );
        }
        if (falhaTransitoriaDeConcorrencia(ex)) {
            ErrorResponse erro = new ErrorResponse(
                    LocalDateTime.now(),
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Conflito transitório no banco de dados; tente novamente."
            );
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(erro);
        }

        log.error("Erro interno não tratado:", ex);
        return respostaErro(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno do servidor.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> tratarMetodoNaoSuportado(
            HttpRequestMethodNotSupportedException ex) {
        return respostaErro(
                HttpStatus.METHOD_NOT_ALLOWED,
                "O método HTTP solicitado não está disponível para este recurso."
        );
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> tratarCredenciaisInvalidas(
            BadCredentialsException ex) {

        ErrorResponse erro = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.UNAUTHORIZED.value(),
                "E-mail ou senha inválidos."
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(erro);
    }
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> tratarLimiteExcedido(
            TooManyRequestsException ex) {
        long retryAfter = Math.max(1L, ex.getRetryAfterSeconds());
        ErrorResponse erro = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Limite de requisições excedido. Tente novamente mais tarde."
        );
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));

        RateLimitSource source = ex.getSource();
        if (source != null) {
            builder.header(RateLimitSource.HEADER_NAME, source.headerValue());
        } else {
            log.warn("TooManyRequestsException sem RateLimitSource; cabeçalho {} omitido.",
                    RateLimitSource.HEADER_NAME);
        }

        return builder.body(erro);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> tratarAcessoNegado(
            org.springframework.security.access.AccessDeniedException ex) {

        ErrorResponse erro = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.FORBIDDEN.value(),
                "Você não tem permissão para acessar este recurso."
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(erro);
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> tratarJsonInvalido(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {

        ErrorResponse erro = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Corpo da requisição inválido ou mal formatado."
        );

        return ResponseEntity.badRequest().body(erro);
    }

    private ResponseEntity<ErrorResponse> respostaErro(HttpStatus status, String mensagem) {
        ErrorResponse erro = new ErrorResponse(
                LocalDateTime.now(),
                status.value(),
                mensagem
        );
        return ResponseEntity.status(status).body(erro);
    }

    private boolean violacaoDaConstraintDeSobreposicao(Throwable erro) {
        String constraintEsperada = "ex_agendamento_quadra_periodo_bloqueante";
        for (Throwable causa = erro; causa != null; causa = causa.getCause()) {
            if (causa instanceof org.hibernate.exception.ConstraintViolationException violacao
                    && "23P01".equals(violacao.getSQLState())
                    && constraintEsperada.equals(violacao.getConstraintName())) {
                return true;
            }
            if (causa instanceof SQLException sql
                    && "23P01".equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains(constraintEsperada)) {
                return true;
            }
        }
        return false;
    }

    private boolean falhaTransitoriaDeConcorrencia(Throwable erro) {
        for (Throwable causa = erro; causa != null; causa = causa.getCause()) {
            if (causa instanceof SQLException sql
                    && ("40P01".equals(sql.getSQLState())
                    || "40001".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
}
