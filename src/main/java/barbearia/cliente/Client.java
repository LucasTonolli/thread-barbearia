package barbearia.cliente;

import barbearia.integracao.AuditLogger;
import barbearia.integracao.Capacity;

/**
 * Cliente da simulacao concorrente da Barbearia de Hilzer.
 *
 * Ciclo de vida (run): reserva uma vaga na Capacity (ou desiste se lotada) -> espera no sofa
 * ou em pe ate ser chamado -> aguarda o barbeiro concluir corte + pagamento -> devolve a vaga.
 * A vaga e devolvida num finally, em qualquer caminho.
 */
public class Client implements Runnable {

    private final String name;
    private final WaitingRoom waitingRoom;
    private final Capacity capacity;
    private final AuditLogger logger;

    // Escrito pela WaitingRoom (sob o monitor da sala) e lido pelo proprio cliente.
    private volatile boolean iAmNext = false;
    // Protegido pelo monitor do proprio cliente.
    private boolean completedAttendance = false;
    // Barbeiro que concluiu o pagamento; null se o atendimento nao foi concluido.
    private volatile String attendedBy;

    private volatile boolean enteredWaitingRoom = false;

    public Client(String name, WaitingRoom waitingRoom, Capacity capacity, AuditLogger logger) {
        this.name = name;
        this.waitingRoom = waitingRoom;
        this.capacity = capacity;
        this.logger = logger;
    }

    /**
     * Construtor de conveniencia para testes isolados da sala de espera: o cliente recebe uma
     * Capacity propria (sempre o admite) e um logger silencioso.
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

    public synchronized void waitUntilAttended() {
        while (!this.completedAttendance) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Mantido para o controle da capacidade e os testes de integracao. */
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
        if (!capacity.tryEnter(name)) {
            return; // Desistencia por lotacao: CHEGADA ja registrada pela Capacity.
        }

        String reason = "Saiu sem concluir o atendimento.";
        try {
            if (!enterWaitingRoom()) {
                reason = Thread.currentThread().isInterrupted()
                        ? "Desistiu da espera (interrompido)."
                        : "Sala de espera cheia. Desistiu.";
                return;
            }
            waitUntilAttended();
        } finally {
            // Texto montado fora do lock do logger (wasServed() usa o monitor do cliente).
            String prefix = wasServed() ? "Pagamento conclu\u00eddo com " + attendedBy + "." : reason;
            capacity.leave(name, current -> prefix + " Saiu (" + capacity.occupancyLabel(current) + ").");
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
