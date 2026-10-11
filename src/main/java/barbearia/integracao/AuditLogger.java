package barbearia.integracao;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Logger de auditoria thread-safe, com instancia propria (sem estado global).
 *
 * Formato do enunciado: [mm:ss.SSS] [Thread-Ator] EVENTO: mensagem
 *
 * - O timestamp e calculado DENTRO da secao critica de escrita, entao a ordem das linhas
 *   e sempre a ordem cronologica dos timestamps.
 * - O "ator" e o ator logico do evento (Cliente-04, Barbeiro-01), nao o nome da thread Java
 *   que escreve: o PAGAMENTO e escrito pela thread do barbeiro, mas descreve o cliente.
 * - A mensagem pode ser um Supplier avaliado dentro do lock: e assim que a Capacity le e
 *   altera o contador de lotacao na mesma secao critica da linha de log.
 *
 * Saidas: console (System.out), arquivo UTF-8 e/ou memoria (para os testes auditarem).
 * O lock do logger e sempre o mais interno: o Supplier nunca bloqueia nem pega outro lock.
 */
public class AuditLogger implements AutoCloseable {

    /** Vocabulario de eventos do enunciado; nenhum outro evento pode ser registrado. */
    public enum Event {
        CHEGADA("CHEGADA"),
        ESPERA("ESPERA"),
        ATENDIMENTO("ATENDIMENTO"),
        PROMOCAO("PROMO\u00c7\u00c3O"),
        PAGAMENTO("PAGAMENTO"),
        SAIDA("SA\u00cdDA");

        private final String label;

        Event(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final long startNanos = System.nanoTime();
    private final PrintStream console;
    private final BufferedWriter file;
    private final List<String> memory;

    /**
     * @param filePath     arquivo de log (null = nao grava em arquivo)
     * @param toConsole    escreve no console (System.out)
     * @param keepInMemory guarda as linhas em memoria (usado pelos testes)
     */
    public AuditLogger(Path filePath, boolean toConsole, boolean keepInMemory) {
        this.console = toConsole ? System.out : null;
        this.memory = keepInMemory ? new ArrayList<>() : null;
        this.file = filePath == null ? null : openFile(filePath);
    }

    /** Logger que descarta as linhas (mas ainda executa a secao critica). */
    public static AuditLogger silent() {
        return new AuditLogger(null, false, false);
    }

    /** Logger que so guarda as linhas em memoria. */
    public static AuditLogger inMemory() {
        return new AuditLogger(null, false, true);
    }

    public void log(String actor, Event event, String message) {
        log(actor, event, () -> message);
    }

    /**
     * Registra um evento. O Supplier e avaliado sob o lock, junto com o timestamp, de modo que
     * qualquer estado que ele leia (ou altere) fica coerente com a posicao da linha no log.
     */
    public void log(String actor, Event event, Supplier<String> message) {
        lock.lock();
        try {
            String text = message.get();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            write(format(elapsedMs, actor, event, text), true);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Linha informativa fora do fluxo de eventos (ex.: ambiente da execucao), prefixada com "# ".
     * Vai para console e arquivo, mas nao para a memoria auditada pelos testes.
     */
    public void note(String text) {
        lock.lock();
        try {
            write("# " + text, false);
        } finally {
            lock.unlock();
        }
    }

    public static String format(long elapsedMs, String actor, Event event, String message) {
        return String.format("[%02d:%02d.%03d] [Thread-%s] %s: %s",
                elapsedMs / 60_000L, (elapsedMs / 1_000L) % 60L, elapsedMs % 1_000L,
                actor, event.label(), message);
    }

    /** Copia das linhas guardadas em memoria (vazia se keepInMemory = false). */
    public List<String> lines() {
        lock.lock();
        try {
            return memory == null ? Collections.emptyList() : new ArrayList<>(memory);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (file != null) {
                try {
                    file.close();
                } catch (IOException ignored) {
                    // nada a fazer ao fechar
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void write(String line, boolean event) {
        if (console != null) {
            console.println(line);
        }
        if (file != null) {
            try {
                file.write(line);
                file.newLine();
                file.flush();
            } catch (IOException e) {
                throw new IllegalStateException("Falha ao gravar o log de auditoria", e);
            }
        }
        if (memory != null && event) {
            memory.add(line);
        }
    }

    /**
     * FileOutputStream (e nao Files.newBufferedWriter): o canal NIO e interrompivel e seria
     * fechado se uma thread com a flag de interrupcao ativa escrevesse no log.
     */
    private static BufferedWriter openFile(Path filePath) {
        try {
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            return new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(filePath.toFile()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Nao foi possivel abrir o arquivo de log " + filePath, e);
        }
    }
}
