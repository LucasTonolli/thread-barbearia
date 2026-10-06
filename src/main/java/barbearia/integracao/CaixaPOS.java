package barbearia.integracao;

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Representa a maquina de cartao (POS) unica compartilhada por todos os barbeiros.
 * Garante exclusao mutua estrita (apenas um pagamento por vez na barbearia).
 * Utiliza ReentrantLock com politica de fairness para evitar starvation.
 */
public class CaixaPOS {

    private final ReentrantLock lock = new ReentrantLock(true); // Fairness ativado
    private final AtomicInteger completedTransactions = new AtomicInteger(0);
    private volatile String currentBarber = null;
    private volatile String currentClient = null;

    /**
     * Adquire a posse da maquina POS para iniciar o pagamento.
     * Bloqueia a thread ate que a maquina esteja livre.
     */
    public void acquire(String barberName, String clientName) throws InterruptedException {
        lock.lockInterruptibly();
        this.currentBarber = barberName;
        this.currentClient = clientName;
    }

    /**
     * Libera a maquina POS apos a conclusao do pagamento.
     */
    public void release(String barberName, String clientName) {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("Thread atual nao possui a posse da maquina POS.");
        }
        this.currentBarber = null;
        this.currentClient = null;
        this.completedTransactions.incrementAndGet();
        lock.unlock();
    }

    /**
     * Metodo de conveniencia para processar o pagamento completo com simulacao de tempo.
     */
    public void processPayment(String barberName, String clientName, long paymentDurationMs) throws InterruptedException {
        acquire(barberName, clientName);
        try {
            if (paymentDurationMs > 0) {
                Thread.sleep(paymentDurationMs);
            }
        } finally {
            release(barberName, clientName);
        }
    }

    public boolean isBusy() {
        return lock.isLocked();
    }

    public String getCurrentBarber() {
        return currentBarber;
    }

    public String getCurrentClient() {
        return currentClient;
    }

    public int getCompletedTransactions() {
        return completedTransactions.get();
    }

    public int getQueueLength() {
        return lock.getQueueLength();
    }
}
