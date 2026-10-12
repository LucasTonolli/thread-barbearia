package barbearia.integracao;

import barbearia.cliente.Client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Metricas da simulacao, thread-safe (metodos synchronized; contadores de cadeira atomicos).
 *
 * Tudo e medido em tempo real (System.nanoTime), nao a partir das duracoes configuradas:
 *  - espera em pe   = entrada no sofa - entrada na fila em pe (so quem ficou em pe);
 *  - espera no sofa = chamada pelo barbeiro - entrada no sofa;
 *  - espera na POS  = tempo bloqueado em PosTerminal.acquire (posse - pedido);
 *  - utilizacao     = (corte + pagamento medidos) / tempo total, por barbeiro.
 * Os timestamps ficam no proprio Client; o barbeiro entrega o cliente atendido em recordServed.
 */
public class Metrics {

    /** Motivo de um cliente sair sem ser atendido. */
    public enum DropReason {
        /** Lotacao (20) cheia na chegada. */
        CAPACITY_FULL,
        /** Sala de espera (17) cheia: caminho defensivo. */
        ROOM_FULL,
        /** Interrompido enquanto esperava ser chamado. */
        INTERRUPTED,
        /** Chamado, mas o atendimento nao foi concluido (barbeiro interrompido ou falhou). */
        NOT_COMPLETED
    }

    private long startNanos;
    private long endNanos;

    private int served;
    private long sofaWaitNanos;
    private int standingCount;
    private long standingWaitNanos;
    private long posWaitNanos;
    private final int[] drops = new int[DropReason.values().length];
    private final Map<Integer, Long> barberBusyNanos = new TreeMap<>();

    private final AtomicInteger chairsInUse = new AtomicInteger(0);
    private final AtomicInteger peakChairs = new AtomicInteger(0);

    public synchronized void start() {
        startNanos = System.nanoTime();
        endNanos = 0;
    }

    public synchronized void stop() {
        endNanos = System.nanoTime();
    }

    /** Chamado pelo barbeiro ao fim do pagamento, com os timestamps ja preenchidos no cliente. */
    public synchronized void recordServed(Client c) {
        served++;
        sofaWaitNanos += c.getCalledNanos() - c.getSofaStartNanos();
        if (c.getStandingStartNanos() > 0) {
            standingCount++;
            standingWaitNanos += c.getSofaStartNanos() - c.getStandingStartNanos();
        }
        posWaitNanos += c.getPosAcquiredNanos() - c.getPosRequestNanos();
    }

    public synchronized void recordDropout(DropReason reason) {
        drops[reason.ordinal()]++;
    }

    /** Soma tempo ocupado (corte ou pagamento) medido para o barbeiro 'barberId'. */
    public synchronized void addBarberBusy(int barberId, long nanos) {
        barberBusyNanos.merge(barberId, nanos, Long::sum);
    }

    public void chairTaken() {
        peakChairs.accumulateAndGet(chairsInUse.incrementAndGet(), Math::max);
    }

    public void chairFreed() {
        chairsInUse.decrementAndGet();
    }

    public int getPeakChairs() {
        return peakChairs.get();
    }

    public synchronized int getServed() {
        return served;
    }

    public synchronized int getDropouts(DropReason reason) {
        return drops[reason.ordinal()];
    }

    public synchronized int getTotalDropouts() {
        int total = 0;
        for (int d : drops) {
            total += d;
        }
        return total;
    }

    public synchronized double getTotalMs() {
        long end = endNanos != 0 ? endNanos : System.nanoTime();
        return (end - startNanos) / 1e6;
    }

    /** Media da espera no sofa (ms), sobre todos os atendidos. */
    public synchronized double getAvgSofaWaitMs() {
        return served == 0 ? 0 : sofaWaitNanos / 1e6 / served;
    }

    /** Media da espera em pe (ms), considerando apenas quem ficou em pe. */
    public synchronized double getAvgStandingWaitMs() {
        return standingCount == 0 ? 0 : standingWaitNanos / 1e6 / standingCount;
    }

    /** Quantos atendidos passaram pela fila em pe. */
    public synchronized int getStandingCount() {
        return standingCount;
    }

    /** Media da espera na fila da POS (ms) = tempo bloqueado em PosTerminal.acquire. */
    public synchronized double getAvgPosWaitMs() {
        return served == 0 ? 0 : posWaitNanos / 1e6 / served;
    }

    /** Utilizacao (%) por id de barbeiro = (corte + pagamento medidos) / tempo total. */
    public synchronized Map<Integer, Double> getUtilizationPercent() {
        Map<Integer, Double> result = new TreeMap<>();
        double total = getTotalMs();
        for (Map.Entry<Integer, Long> e : barberBusyNanos.entrySet()) {
            result.put(e.getKey(), total == 0 ? 0 : 100.0 * (e.getValue() / 1e6) / total);
        }
        return result;
    }

    public synchronized String summary(int totalClients) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Clientes                            : %d%n", totalClients));
        sb.append(String.format(Locale.ROOT, "Atendidos                           : %d%n", served));
        sb.append(String.format(Locale.ROOT, "Desistentes (lotacao 20 cheia)      : %d%n", getDropouts(DropReason.CAPACITY_FULL)));
        sb.append(String.format(Locale.ROOT, "Desistentes (sala 17 cheia)         : %d%n", getDropouts(DropReason.ROOM_FULL)));
        sb.append(String.format(Locale.ROOT, "Desistentes (interrompidos)         : %d%n", getDropouts(DropReason.INTERRUPTED)));
        sb.append(String.format(Locale.ROOT, "Atendimentos nao concluidos         : %d%n", getDropouts(DropReason.NOT_COMPLETED)));
        sb.append(String.format(Locale.ROOT, "Tempo total                         : %.1f ms%n", getTotalMs()));
        sb.append(String.format(Locale.ROOT, "Espera media em pe                  : %.2f ms (%d clientes ficaram em pe)%n",
                getAvgStandingWaitMs(), standingCount));
        sb.append(String.format(Locale.ROOT, "Espera media no sofa                : %.2f ms%n", getAvgSofaWaitMs()));
        sb.append(String.format(Locale.ROOT, "Espera media na fila da POS         : %.2f ms%n", getAvgPosWaitMs()));
        for (Map.Entry<Integer, Double> e : getUtilizationPercent().entrySet()) {
            sb.append(String.format(Locale.ROOT, "Utilizacao do barbeiro B%d           : %.1f %%%n", e.getKey(), e.getValue()));
        }
        return sb.toString();
    }

    /** Grava (sobrescreve) um CSV com cabecalho e uma unica linha de resultados. */
    public synchronized void writeCsv(Path file, String scenario, int execution, int totalClients, int cpus)
            throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        StringBuilder header = new StringBuilder("cenario,execucao,processadores,clientes,atendidos,"
                + "desist_lotacao,desist_sala,desist_interrompido,nao_concluido,tempo_total_ms,"
                + "espera_em_pe_ms,clientes_em_pe,espera_sofa_ms,espera_pos_ms");
        StringBuilder row = new StringBuilder(String.format(Locale.ROOT,
                "%s,%d,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%d,%.3f,%.3f",
                scenario, execution, cpus, totalClients, served,
                getDropouts(DropReason.CAPACITY_FULL), getDropouts(DropReason.ROOM_FULL),
                getDropouts(DropReason.INTERRUPTED), getDropouts(DropReason.NOT_COMPLETED),
                getTotalMs(), getAvgStandingWaitMs(), standingCount, getAvgSofaWaitMs(), getAvgPosWaitMs()));
        for (Map.Entry<Integer, Double> e : getUtilizationPercent().entrySet()) {
            header.append(",util_b").append(e.getKey()).append("_pct");
            row.append(String.format(Locale.ROOT, ",%.2f", e.getValue()));
        }
        Files.writeString(file, header + "\n" + row + "\n", StandardCharsets.UTF_8);
    }
}
