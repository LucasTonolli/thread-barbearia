package barbearia.cliente;

import barbearia.integracao.Logger;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Sala de espera com dois niveis FIFO:
 *  - sofa: 4 lugares;
 *  - fila em pe: 13 lugares.
 */
public class WaitingRoom {

    private final Queue<Client> standingClients = new ArrayDeque<>();
    private final Queue<Client> seatedClients = new ArrayDeque<>();

    private static final int MAX_SEATED_CLIENTS = 4;
    private static final int MAX_STANDING_CLIENTS = 13;

    private int rejectedCount = 0;

    /**
     * Coloca o cliente no sofa ou na fila em pe e bloqueia sua thread
     * ate que um barbeiro o escolha para atendimento.
     */
    public synchronized boolean enter(Client client) {
        if (isSeatedClientsFull() && isStandingClientsFull()) {
            rejectedCount++;
            Logger.log("DESISTENCIA", client.getName() + " encontrou a sala de espera fisicamente cheia");
            return false;
        }

        if (!isSeatedClientsFull()) {
            seatedClients.offer(client);
            Logger.log(
                    "ESPERA",
                    client.getName() + " sentou no sofa (posicao " + seatedClients.size() + "/" + MAX_SEATED_CLIENTS + ")"
            );
        } else {
            standingClients.offer(client);
            Logger.log(
                    "ESPERA",
                    client.getName() + " aguardando em pe (posicao " + standingClients.size() + "/" + MAX_STANDING_CLIENTS + ")"
            );
        }

        // Acorda barbeiros que estejam dormindo no monitor da sala.
        notifyAll();

        while (!client.isTheNext()) {
            try {
                wait();
            } catch (InterruptedException e) {
                boolean wasSeated = seatedClients.remove(client);
                standingClients.remove(client);

                if (wasSeated) {
                    promoteStandingClientIfPossible();
                }

                notifyAll();
                Thread.currentThread().interrupt();
                Logger.log("INTERRUPCAO", client.getName() + " deixou a fila antes do atendimento");
                return false;
            }
        }

        return true;
    }

    /**
     * Retira o cliente mais antigo do sofa e promove o mais antigo da fila em pe.
     */
    public synchronized Client getNext() {
        if (seatedClients.isEmpty()) {
            return null;
        }

        Client client = seatedClients.poll();
        Client promoted = promoteStandingClientIfPossible();

        client.iAmTheNext();

        Logger.log("CHAMADA", client.getName() + " foi retirado do sofa para atendimento");
        if (promoted != null) {
            Logger.log("PROMOCAO", promoted.getName() + " saiu da fila em pe e ocupou o sofa");
        }

        // Todos acordam, mas apenas o cliente marcado por iAmTheNext() prossegue.
        notifyAll();
        return client;
    }

    private Client promoteStandingClientIfPossible() {
        if (standingClients.isEmpty() || isSeatedClientsFull()) {
            return null;
        }

        Client promoted = standingClients.poll();
        seatedClients.offer(promoted);
        return promoted;
    }

    public synchronized void printWaitingRoomStatus() {
        System.out.println("Seated clients: " + seatedClients);
        System.out.println("Standing clients: " + standingClients);
    }

    public synchronized boolean isEmpty() {
        return seatedClients.isEmpty() && standingClients.isEmpty();
    }

    public synchronized int getSeatedCount() {
        return seatedClients.size();
    }

    public synchronized int getStandingCount() {
        return standingClients.size();
    }

    public synchronized int getTotalWaiting() {
        return seatedClients.size() + standingClients.size();
    }

    public synchronized int getRejectedCount() {
        return rejectedCount;
    }

    private boolean isSeatedClientsFull() {
        return seatedClients.size() >= MAX_SEATED_CLIENTS;
    }

    private boolean isStandingClientsFull() {
        return standingClients.size() >= MAX_STANDING_CLIENTS;
    }
}
