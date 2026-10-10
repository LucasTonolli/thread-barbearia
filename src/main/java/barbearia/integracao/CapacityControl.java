package barbearia.integracao;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controla a capacidade total da barbearia.
 *
 * A regra do trabalho determina que, se as 20 vagas estiverem ocupadas,
 * o cliente nao deve aguardar uma vaga: ele desiste imediatamente.
 */
public class CapacityControl {

    private final int maxCapacity;
    private final Semaphore permits;
    private final AtomicInteger occupancy = new AtomicInteger(0);
    private final AtomicInteger maxObserved = new AtomicInteger(0);
    private final AtomicInteger admittedCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);

    public CapacityControl(int maxCapacity) {
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("A capacidade maxima deve ser positiva.");
        }
        this.maxCapacity = maxCapacity;
        this.permits = new Semaphore(maxCapacity, true);
    }

    /**
     * Tenta reservar uma vaga sem bloquear a thread.
     */
    public boolean tryEnter(String clientName) {
        if (!permits.tryAcquire()) {
            rejectedCount.incrementAndGet();
            Logger.log(
                    "DESISTENCIA",
                    clientName + " encontrou a barbearia lotada e nao entrou",
                    occupancy.get(),
                    maxCapacity
            );
            return false;
        }

        int current = occupancy.incrementAndGet();
        admittedCount.incrementAndGet();
        maxObserved.accumulateAndGet(current, Math::max);

        Logger.log(
                "CHEGADA",
                clientName + " entrou na barbearia",
                current,
                maxCapacity
        );
        return true;
    }

    public void leave(String clientName, boolean served) {
        int current = occupancy.decrementAndGet();
        if (current < 0) {
            occupancy.incrementAndGet();
            throw new IllegalStateException("Controle de lotacao inconsistente: valor negativo.");
        }

        permits.release();

        if (served) {
            Logger.log(
                    "SAIDA",
                    clientName + " concluiu o atendimento e saiu",
                    current,
                    maxCapacity
            );
        } else {
            Logger.log(
                    "SAIDA_SEM_ATENDIMENTO",
                    clientName + " liberou a vaga sem concluir atendimento",
                    current,
                    maxCapacity
            );
        }
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
