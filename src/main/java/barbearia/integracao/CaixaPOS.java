package barbearia.integracao;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Representa a unica maquina POS compartilhada pelos tres barbeiros.
 * O lock justo evita que um barbeiro fique indefinidamente sem acesso ao recurso.
 */
public class CaixaPOS {

    private final ReentrantLock lock = new ReentrantLock(true);
    private final AtomicInteger completedTransactions = new AtomicInteger(0);
    private final AtomicInteger activeTransactions = new AtomicInteger(0);
    private final AtomicInteger maxConcurrentTransactions = new AtomicInteger(0);

    private volatile String currentBarber;
    private volatile String currentClient;

    public void acquire(String barberName, String clientName) throws InterruptedException {
        lock.lockInterruptibly();
        currentBarber = barberName;
        currentClient = clientName;

        int active = activeTransactions.incrementAndGet();
        maxConcurrentTransactions.accumulateAndGet(active, Math::max);
    }

    public void release(String barberName, String clientName) {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("A thread atual nao possui a maquina POS.");
        }

        completedTransactions.incrementAndGet();
        activeTransactions.decrementAndGet();
        currentBarber = null;
        currentClient = null;
        lock.unlock();
    }

    public void processPayment(String barberName, String clientName, long paymentDurationMs)
            throws InterruptedException {
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

    public int getMaxConcurrentTransactions() {
        return maxConcurrentTransactions.get();
    }
}
