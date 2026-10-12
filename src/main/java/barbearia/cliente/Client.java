package barbearia.cliente;

import barbearia.integracao.AuditLogger;
import barbearia.integracao.Capacity;
import barbearia.integracao.Metrics;
import barbearia.integracao.Metrics.DropReason;

/**
 * Cliente da simulacao concorrente da Barbearia de Hilzer.
 *
 * Ciclo de vida (run): reserva uma vaga na Capacity (ou desiste se lotada) -> espera no sofa
 * ou em pe ate ser chamado -> aguarda o barbeiro concluir corte + pagamento -> devolve a vaga.
 * A vaga e devolvida num finally, em qualquer caminho.
 *
 * Tambem guarda os timestamps (nanoTime) e as ordens de fila usados pelas metricas e pelos
 * testes de FIFO. Cada campo e escrito por uma unica thread (a sala, sob o monitor dela, ou o
 * barbeiro que o atende) e lido depois, por isso basta volatile.
 */
public class Client implements Runnable {

    private final String name;
    private final WaitingRoom waitingRoom;
    private final Capacity capacity;
    private final AuditLogger logger;
    private final Metrics metrics;

    // Escrito pela WaitingRoom (sob o monitor da sala) e lido pelo proprio cliente.
    private volatile boolean iAmNext = false;
    // Protegido pelo monitor do proprio cliente.
    private boolean completedAttendance = false;
    // Barbeiro que concluiu o pagamento; null se o atendimento nao foi concluido.
    private volatile String attendedBy;

    private volatile boolean enteredWaitingRoom = false;

    // Ordens atribuidas pela WaitingRoom (0 = nao aconteceu): entrada na sala, entrada na fila
    // em pe, obtencao de lugar no sofa (direto ou por promocao) e chamada pelo barbeiro.
    private volatile long enqueueOrder;
    private volatile long standingOrder;
    private volatile long sofaOrder;
    private volatile long callOrder;

    // Timestamps (System.nanoTime); 0 = evento nao ocorreu.
    private volatile long arrivalNanos;
    private volatile long standingStartNanos;
    private volatile long sofaStartNanos;
    private volatile long calledNanos;
    private volatile long posRequestNanos;
    private volatile long posAcquiredNanos;
    private volatile long paymentEndNanos;

    public Client(String name, WaitingRoom waitingRoom, Capacity capacity, AuditLogger logger, Metrics metrics) {
        this.name = name;
        this.waitingRoom = waitingRoom;
        this.capacity = capacity;
        this.logger = logger;
        this.metrics = metrics;
    }

    public Client(String name, WaitingRoom waitingRoom, Capacity capacity, AuditLogger logger) {
        this(name, waitingRoom, capacity, logger, new Metrics());
    }

    /**
     * Construtor de conveniencia para testes isolados da sala de espera: o cliente recebe uma
     * Capacity propria (sempre o admite), logger silencioso e metricas descartaveis.
     */
    public Client(String name, WaitingRoom waitingRoom) {
        this(name, waitingRoom, new Capacity(), AuditLogger.silent());
    }

    public String getName() {
        return name;
    }

    public boolean enterWaitingRoom() {
        boolean entered = waitingRoom.enter(this);
        enteredWaitingRoom = entered;
        return entered;
    }

    public void iAmTheNext() {
        this.iAmNext = true;
    }

    public boolean isTheNext() {
        return iAmNext;
    }

    /** Marca o barbeiro que concluiu o pagamento. Chamar antes de completeAttendance(). */
    public void setAttendedBy(String barberName) {
        this.attendedBy = barberName;
    }

    public String getAttendedBy() {
        return attendedBy;
    }

    /** Destrava o cliente (atendimento encerrado, com ou sem sucesso). */
    public synchronized void completeAttendance() {
        this.completedAttendance = true;
        this.notifyAll();
    }

    /**
     * Bloqueia ate o barbeiro encerrar o atendimento. Uma vez chamado, o cliente esta na cadeira:
     * a interrupcao nao o tira dali (senao devolveria a vaga com o corte em andamento). Ela e
     * guardada e restaurada no fim. Termina sempre, porque Barber.serve chama
     * completeAttendance() num finally.
     */
    public synchronized void waitUntilAttended() {
        boolean interrupted = false;
        while (!this.completedAttendance) {
            try {
                wait();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** true se o barbeiro ja encerrou o atendimento (com ou sem sucesso). */
    public synchronized boolean isAttendanceCompleted() {
        return completedAttendance;
    }

    /** true se o cliente chegou a entrar na sala de espera. */
    public boolean hasEnteredWaitingRoom() {
        return enteredWaitingRoom;
    }

    /** true se o atendimento foi concluido (corte + pagamento). */
    public boolean wasServed() {
        return attendedBy != null && isAttendanceCompleted();
    }

    @Override
    public void run() {
        arrivalNanos = System.nanoTime();
        if (!capacity.tryEnter(name)) {
            // Desistencia por lotacao: CHEGADA ja registrada pela Capacity.
            metrics.recordDropout(DropReason.CAPACITY_FULL);
            return;
        }

        String reason = "Saiu sem concluir o atendimento.";
        try {
            if (!enterWaitingRoom()) {
                if (Thread.currentThread().isInterrupted()) {
                    reason = "Desistiu da espera (interrompido).";
                    metrics.recordDropout(DropReason.INTERRUPTED);
                } else {
                    reason = "Sala de espera cheia. Desistiu.";
                    metrics.recordDropout(DropReason.ROOM_FULL);
                }
                return;
            }
            waitUntilAttended();
            if (!wasServed()) {
                metrics.recordDropout(DropReason.NOT_COMPLETED);
            }
        } finally {
            // Texto montado fora do lock do logger (wasServed() usa o monitor do cliente).
            String prefix = wasServed() ? "Pagamento conclu\u00eddo com " + attendedBy + "." : reason;
            capacity.leave(name, current -> prefix + " Saiu (" + capacity.occupancyLabel(current) + ").");
        }
    }

    // ---- ordens de fila (escritas pela WaitingRoom sob o monitor da sala) ----

    void markEnqueued(long order) {
        enqueueOrder = order;
    }

    void markStanding(long order, long nanos) {
        standingOrder = order;
        standingStartNanos = nanos;
    }

    void markSofa(long order, long nanos) {
        sofaOrder = order;
        sofaStartNanos = nanos;
    }

    void markCalled(long order, long nanos) {
        callOrder = order;
        calledNanos = nanos;
    }

    // ---- timestamps do pagamento (escritos pelo barbeiro) ----

    public void markPosRequest(long nanos) {
        posRequestNanos = nanos;
    }

    public void markPosAcquired(long nanos) {
        posAcquiredNanos = nanos;
    }

    public void markPaymentEnd(long nanos) {
        paymentEndNanos = nanos;
    }

    public long getEnqueueOrder() {
        return enqueueOrder;
    }

    public long getStandingOrder() {
        return standingOrder;
    }

    public long getSofaOrder() {
        return sofaOrder;
    }

    public long getCallOrder() {
        return callOrder;
    }

    public long getArrivalNanos() {
        return arrivalNanos;
    }

    public long getStandingStartNanos() {
        return standingStartNanos;
    }

    public long getSofaStartNanos() {
        return sofaStartNanos;
    }

    public long getCalledNanos() {
        return calledNanos;
    }

    public long getPosRequestNanos() {
        return posRequestNanos;
    }

    public long getPosAcquiredNanos() {
        return posAcquiredNanos;
    }

    public long getPaymentEndNanos() {
        return paymentEndNanos;
    }

    @Override
    public String toString() {
        return name;
    }
}
