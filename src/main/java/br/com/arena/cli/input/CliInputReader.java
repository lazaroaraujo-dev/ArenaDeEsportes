package br.com.arena.cli.input;

/**
 * Abstração de leitura de input do CLI. Permite substituir {@code System.console}
 * por implementações de teste.
 */
public interface CliInputReader {

    String readLine(String prompt);

    char[] readPassword(String prompt);
}
