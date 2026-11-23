package br.com.arena.cli.input;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Implementação de {@link CliInputReader} baseada em {@link System#console()}.
 * Usa senha sem eco quando o console está disponível; em ambientes sem console
 * (pipe, CI) faz leitura linha a linha via {@code System.in}.
 */
public class ConsoleInputReader implements CliInputReader {

    private final Console console;
    private final BufferedReader fallbackReader;

    public ConsoleInputReader() {
        this.console = System.console();
        this.fallbackReader = this.console == null
                ? new BufferedReader(new InputStreamReader(System.in))
                : null;
    }

    @Override
    public String readLine(String prompt) {
        if (console != null) {
            return console.readLine(prompt);
        }
        System.out.print(prompt);
        System.out.flush();
        try {
            return fallbackReader.readLine();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read input", ex);
        }
    }

    @Override
    public char[] readPassword(String prompt) {
        if (console != null) {
            return console.readPassword(prompt);
        }
        System.out.print(prompt);
        System.out.flush();
        try {
            String line = fallbackReader.readLine();
            return line != null ? line.toCharArray() : null;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read password", ex);
        }
    }
}
