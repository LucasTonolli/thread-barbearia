package barbearia.cliente;

/**
 * Cliente da simulacao concorrente da Barbearia de Hilzer.
 *
 * Integra as alteracoes da classe Client do repositorio da equipe
 * (main, commit 81f132b), preservando a API da integracao final.
 */
public class Client implements Runnable {
    private final String name;
    private final WaitingRoom waitingRoom;

    // Atualizacao do repositorio: visibilidade entre threads garantida por volatile.
    private volatile boolean iAmNext = false;
    private boolean completedAttendance = false;

    // Contrato usado na integracao final para consultar o historico da entrada.
    private volatile boolean enteredWaitingRoom = false;

    public Client(String name, WaitingRoom waitingRoom) {
        super();
        this.name = name;
        this.waitingRoom = waitingRoom;
    }

    public String getName() {
        return name;
    }

    public boolean enterWaitingRoom() {
        boolean entered = this.waitingRoom.enter(this);
        enteredWaitingRoom = entered;
        if (entered) {
            System.out.println(name + " has entered the waiting room.");
        } else {
            System.out.println(name + " could not enter the waiting room.");
        }
        return entered;
    }

    public void iAmTheNext() {
        this.iAmNext = true;
    }

    public boolean isTheNext() {
        return iAmNext;
    }

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

    /** Mantido para o controle da capacidade e o teste de integracao. */
    public synchronized boolean isAttendanceCompleted() {
        return completedAttendance;
    }

    /** Mantido para compatibilidade com o codigo entregue anteriormente. */
    public boolean hasEnteredWaitingRoom() {
        return enteredWaitingRoom;
    }

    @Override
    public void run() {
        boolean entrou = enterWaitingRoom();
        if (!entrou) {
            return; // Desistencia: thread termina aqui.
        }
        waitUntilAttended();
    }

    @Override
    public String toString() {
        return name;
    }
}
