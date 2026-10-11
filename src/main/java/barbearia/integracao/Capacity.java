package barbearia.integracao;

import barbearia.integracao.AuditLogger.Event;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * Controla a capacidade total da barbearia (20 = 3 cadeiras + 4 sofa + 13 em pe).
 *
 * A regra do trabalho determina que, se as 20 vagas estiverem ocupadas,
 * o cliente nao deve aguardar uma vaga: ele desiste imediatamente (tryAcquire nao bloqueante).
 *
 * A troca de estado (permissao + contador) acontece dentro do Supplier passado ao AuditLogger,
 * ou seja, sob o mesmo lock que da o timestamp da linha. Assim o numero "Lotacao: N/20"
 * impresso em cada CHEGADA/SAIDA bate com a ordem das linhas no log.
 */
public class Capacity {

    public static final int DEFAULT_MAX = 20;

    private final int maxCapacity;
    private final Semaphore permits;
    private final AuditLogger logger;
    private final AtomicInteger occupancy = new AtomicInteger(0);
    private final AtomicInteger maxObserved = new AtomicInteger(0);
    private final AtomicInteger admittedCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);

    public Capacity(int maxCapacity, AuditLogger logger) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("A capacidade maxima deve ser positiva.");
        }
        this.maxCapacity = maxCapacity;
        this.permits = new Semaphore(maxCapacity, true);
        this.logger = logger;
    }

    public Capacity(int maxCapacity) {
        this(maxCapacity, AuditLogger.silent());
    }

    public Capacity() {
        this(DEFAULT_MAX);
    }

    /**
     * Tenta reservar uma vaga sem bloquear a thread e registra a CHEGADA.
     * @return true se o cliente entrou; false se a barbearia estava lotada (desistencia).
     */
    public boolean tryEnter(String clientName) {
        boolean[] admitted = new boolean[1];
        logger.log(clientName, Event.CHEGADA, () -> {
            if (!permits.tryAcquire()) {
                rejectedCount.incrementAndGet();
                return "Barbearia lotada (" + occupancyLabel(occupancy.get()) + "). Desistiu.";
            }
            // O contador so sobe DEPOIS de obter a permissao e so desce ANTES de libera-la,
            // entao nunca passa de maxCapacity.
            int current = occupancy.incrementAndGet();
            admittedCount.incrementAndGet();
            maxObserved.accumulateAndGet(current, Math::max);
            admitted[0] = true;
            return "Entrou na barbearia (" + occupancyLabel(current) + ").";
        });
        return admitted[0];
    }

    /**
     * Devolve a vaga e registra a SAIDA. Chamar exatamente uma vez para cada tryEnter aceito,
     * sempre a partir de um bloco finally.
     *
     * @param message monta a mensagem a partir da lotacao logo apos a saida
     */
    public void leave(String clientName, IntFunction<String> message) {
        logger.log(clientName, Event.SAIDA, () -> {
            int current = occupancy.decrementAndGet();
            if (current < 0) {
                occupancy.incrementAndGet();
                throw new IllegalStateException("Controle de lotacao inconsistente: valor negativo.");
            }
            permits.release();
            return message.apply(current);
        });
    }

    /** Texto "Lotacao: N/20" (com acento), usado nas mensagens de CHEGADA e SAIDA. */
    public String occupancyLabel(int current) {
        return "Lota\u00e7\u00e3o: " + current + "/" + maxCapacity;
    }

    public int getCurrentOccupancy() {
        return occupancy.get();
    }

    public int getMaxObserved() {
        return maxObserved.get();
    }

    public int getAdmittedCount() {
        return admittedCount.get();
    }

    public int getRejectedCount() {
        return rejectedCount.get();
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }
}
