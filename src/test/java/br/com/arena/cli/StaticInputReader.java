package br.com.arena.cli;

import br.com.arena.cli.input.CliInputReader;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Implementação de {@link CliInputReader} para testes. Retorna valores
 * pré-programados em fila.
 */
class StaticInputReader implements CliInputReader {

    private final Queue<String> values = new ConcurrentLinkedQueue<>();

    StaticInputReader add(String value) {
        values.add(value);
        return this;
    }

    StaticInputReader addPassword(String value) {
        return add(value);
    }

    @Override
    public String readLine(String prompt) {
        String value = values.poll();
        if (value == null) {
            throw new IllegalStateException("No more input values configured for prompt: " + prompt);
        }
        return value;
    }

    @Override
    public char[] readPassword(String prompt) {
        String value = values.poll();
        if (value == null) {
            throw new IllegalStateException("No more input values configured for password prompt: " + prompt);
        }
        return value.toCharArray();
    }
}
