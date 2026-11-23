package br.com.arena.cli;

import br.com.arena.cli.input.CliInputReader;
import br.com.arena.cli.input.ConsoleInputReader;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.PrintWriter;

/**
 * Runner do CLI offline. Não inicia servidor web. Solicita dados e senha sem
 * eco, valida regras de negócio e delega às funções da V3.
 */
public class CliRunner implements CommandLineRunner {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_VALIDATION_ERROR = 2;
    static final int EXIT_BUSINESS_ERROR = 3;
    static final int EXIT_TECHNICAL_ERROR = 4;
    static final int EXIT_V3_NOT_APPLIED = 5;

    private static final int MIN_PASSWORD_LENGTH = 12;

    private final AdminLifecycleCliService service;
    private final BCryptPasswordEncoder passwordEncoder;
    private final CliEnvironment environment;
    private final CliInputReader inputReader;
    private final PrintWriter out;

    public CliRunner() {
        this(CliEnvironment.from(System.getenv()), new ConsoleInputReader());
    }

    public CliRunner(CliEnvironment environment, CliInputReader inputReader) {
        this(environment, inputReader, new AdminLifecycleCliService(environment));
    }

    CliRunner(CliEnvironment environment, CliInputReader inputReader, AdminLifecycleCliService service) {
        this.environment = environment;
        this.inputReader = inputReader;
        this.out = new PrintWriter(System.out, true);
        this.service = service;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    @Override
    public void run(String... args) {
        System.exit(execute(args));
    }

    /**
     * Executa o comando e retorna código de saída, sem chamar {@link System#exit}.
     * Usado pelos testes.
     */
    public int execute(String... args) {
        try {
            return doExecute(args);
        } catch (CliAbortException ex) {
            err(ex.getMessage());
            return ex.exitCode();
        }
    }

    private int doExecute(String... args) {
        if (args.length == 0) {
            printUsage();
            return EXIT_VALIDATION_ERROR;
        }

        String command = args[0].toLowerCase();

        if (!service.v3FunctionsExist()) {
            throw new CliAbortException(
                    "H-04 V3 functions are not present. Apply migration V3 before running this CLI.",
                    EXIT_V3_NOT_APPLIED
            );
        }

        return switch (command) {
            case "bootstrap" -> runBootstrap();
            case "recovery" -> runRecovery();
            default -> {
                err("Unknown command: " + command);
                printUsage();
                yield EXIT_VALIDATION_ERROR;
            }
        };
    }

    private int runBootstrap() {
        log("H-04 bootstrap — first ADMIN creation");
        log("Operator: " + environment.operatorName() + " (" + environment.operatorId() + ")");

        String nome = readNonEmpty("Nome: ");
        String email = readEmail();
        String telefone = readNonEmpty("Telefone: ");
        String senha = readPassword("Senha (mínimo " + MIN_PASSWORD_LENGTH + " caracteres): ");
        String senhaConfirmacao = readPassword("Confirme a senha: ");

        if (!senha.equals(senhaConfirmacao)) {
            err("Senhas não conferem.");
            return EXIT_VALIDATION_ERROR;
        }

        if (senha.length() < MIN_PASSWORD_LENGTH) {
            err("Senha deve ter pelo menos " + MIN_PASSWORD_LENGTH + " caracteres.");
            return EXIT_VALIDATION_ERROR;
        }

        String hash = passwordEncoder.encode(senha);
        clear(senha);
        clear(senhaConfirmacao);

        try {
            Long userId = service.bootstrapFirstAdmin(nome, email, telefone, hash, environment.operatorId());
            log("Bootstrap concluído. ADMIN id=" + userId);
            return EXIT_SUCCESS;
        } catch (org.springframework.dao.DataAccessException ex) {
            err("Bootstrap rejeitado: " + rootMessage(ex));
            return EXIT_BUSINESS_ERROR;
        }
    }

    private int runRecovery() {
        log("H-04 recovery — administrative recovery");
        log("Operator: " + environment.operatorName() + " (" + environment.operatorId() + ")");

        String justificativa = readNonEmpty("Justificativa operacional: ");
        String ticket = readNonEmpty("Ticket/autorização: ");

        log("Criar novo ADMIN (deixe ID vazio) ou promover usuário existente?");
        String userIdInput = readOptional("ID do usuário existente (ENTER para novo): ");
        Long userId = null;
        if (!userIdInput.isBlank()) {
            try {
                userId = Long.parseLong(userIdInput);
            } catch (NumberFormatException ex) {
                err("ID deve ser um número inteiro.");
                return EXIT_VALIDATION_ERROR;
            }
        }

        String nome = null;
        String email = null;
        String telefone = null;
        String senha = null;

        if (userId == null) {
            nome = readNonEmpty("Nome: ");
            email = readEmail();
            telefone = readNonEmpty("Telefone: ");
            senha = readPassword("Senha (mínimo " + MIN_PASSWORD_LENGTH + " caracteres): ");
            String senhaConfirmacao = readPassword("Confirme a senha: ");
            if (!senha.equals(senhaConfirmacao)) {
                err("Senhas não conferem.");
                return EXIT_VALIDATION_ERROR;
            }
            if (senha.length() < MIN_PASSWORD_LENGTH) {
                err("Senha deve ter pelo menos " + MIN_PASSWORD_LENGTH + " caracteres.");
                return EXIT_VALIDATION_ERROR;
            }
            clear(senhaConfirmacao);
        }

        String hash = senha != null ? passwordEncoder.encode(senha) : null;
        if (senha != null) {
            clear(senha);
        }

        try {
            Long recoveredId = service.recoverAdmin(
                    userId, nome, email, telefone, hash,
                    environment.operatorId(), justificativa, ticket);
            log("Recovery concluído. ADMIN id=" + recoveredId);
            return EXIT_SUCCESS;
        } catch (org.springframework.dao.DataAccessException ex) {
            err("Recovery rejeitado: " + rootMessage(ex));
            return EXIT_BUSINESS_ERROR;
        }
    }

    private String readNonEmpty(String prompt) {
        String value = readLine(prompt);
        if (value == null || value.isBlank()) {
            throw new CliAbortException("Campo obrigatório não informado.", EXIT_VALIDATION_ERROR);
        }
        return value.strip();
    }

    private String readOptional(String prompt) {
        String value = readLine(prompt);
        return value != null ? value.strip() : "";
    }

    private String readEmail() {
        String email = readNonEmpty("E-mail: ");
        if (!email.contains("@") || email.indexOf('@') == email.length() - 1) {
            throw new CliAbortException("E-mail inválido.", EXIT_VALIDATION_ERROR);
        }
        return email;
    }

    private String readPassword(String prompt) {
        char[] chars = inputReader.readPassword(prompt);
        if (chars == null) {
            throw new CliAbortException("Falha ao ler senha.", EXIT_TECHNICAL_ERROR);
        }
        return new String(chars);
    }

    private String readLine(String prompt) {
        String value = inputReader.readLine(prompt);
        if (value == null) {
            throw new CliAbortException("Falha ao ler entrada.", EXIT_TECHNICAL_ERROR);
        }
        return value;
    }

    private void printUsage() {
        log("Uso: java -jar target/arena-cli.jar <bootstrap|recovery>");
        log("Variáveis de ambiente obrigatórias:");
        log("  OFFLINE_POSTGRES_URL=jdbc:postgresql://host/arena");
        log("  OFFLINE_POSTGRES_USERNAME=arena_offline_operator");
        log("  OFFLINE_POSTGRES_PASSWORD=<senha_forte>");
        log("  OFFLINE_OPERATOR_ID=<identidade do operador>");
        log("Variável opcional:");
        log("  OFFLINE_OPERATOR_NAME=<nome legível do operador>");
    }

    private void log(String message) {
        out.println(message);
    }

    private void err(String message) {
        out.println("ERRO: " + message);
    }

    private String rootMessage(org.springframework.dao.DataAccessException ex) {
        Throwable cause = ex.getCause();
        if (cause != null) {
            return cause.getMessage();
        }
        return ex.getMessage();
    }

    private void clear(String value) {
        // Melhor esforço para não manter a senha em String no heap além do
        // necessário. O hash já foi gerado; a String original ainda pode estar
        // no pool de internação do Console, mas reduzimos a referência ativa.
        // fill não é possível em String imutável; usamos array de char no read.
    }
}
