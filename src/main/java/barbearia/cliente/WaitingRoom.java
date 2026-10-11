package barbearia.cliente;

import barbearia.integracao.AuditLogger;
import barbearia.integracao.AuditLogger.Event;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Sala de espera com dois niveis FIFO:
 *  - sofa: 4 lugares;
 *  - fila em pe: 13 lugares.
 *
 * Monitor unico (synchronized/wait/notifyAll): clientes e barbeiros esperam neste mesmo objeto.
 * Os logs ESPERA, ATENDIMENTO e PROMOCAO sao emitidos dentro do monitor, entao a ordem das
 * linhas reflete a ordem real das operacoes nas filas.
 */
public class WaitingRoom {

    public static final int MAX_SEATED_CLIENTS = 4;
    public static final int MAX_STANDING_CLIENTS = 13;

    private final Queue<Client> standingClients = new ArrayDeque<>();
    private final Queue<Client> seatedClients = new ArrayDeque<>();
    private final AuditLogger logger;

    private int rejectedCount = 0;
    private int interruptedAfterCallCount = 0;
    private int peakSeated = 0;
    private int peakStanding = 0;

    public WaitingRoom(AuditLogger logger) {
        this.logger = logger;
    }

    public WaitingRoom() {
        this(AuditLogger.silent());
    }

    /**
     * Coloca o cliente no sofa ou na fila em pe e bloqueia sua thread
     * ate que um barbeiro o escolha para atendimento.
     * @return false se a sala esta cheia ou se a thread foi interrompida antes de ser chamada.
     */
    public synchronized boolean enter(Client client) {
        if (isSeatedClientsFull() && isStandingClientsFull()) {
            rejectedCount++;
            logger.log(client.getName(), Event.ESPERA, "Sala de espera cheia (" + MAX_SEATED_CLIENTS
                    + " no sof\u00e1 e " + MAX_STANDING_CLIENTS + " em p\u00e9).");
            return false;
        }

        if (!isSeatedClientsFull()) {
            seatedClients.offer(client);
            logger.log(client.getName(), Event.ESPERA,
                    "Sentou no sof\u00e1 (Posi\u00e7\u00e3o " + seatedClients.size() + " do sof\u00e1).");
        } else {
            standingClients.offer(client);
            logger.log(client.getName(), Event.ESPERA,
                    "Sof\u00e1 lotado. Aguardando em p\u00e9 (Posi\u00e7\u00e3o " + standingClients.size() + " da fila em p\u00e9).");
        }

        peakSeated = Math.max(peakSeated, seatedClients.size());
        peakStanding = Math.max(peakStanding, standingClients.size());

        // Acorda barbeiros que estejam dormindo no monitor da sala.
        notifyAll();

        while (!client.isTheNext()) {
            try {
                wait();
            } catch (InterruptedException e) {
                if (client.isTheNext()) {
                    // A interrupcao chegou depois de getNext() marcar o cliente: ele ja saiu da
                    // fila e um barbeiro ja o atende. Desistir agora geraria um atendimento
                    // "fantasma"; ele segue para o atendimento e a interrupcao fica registrada.
                    interruptedAfterCallCount++;
                    Thread.currentThread().interrupt();
                    return true;
                }
                boolean wasSeated = seatedClients.remove(client);
                standingClients.remove(client);

                if (wasSeated) {
                    promoteStandingClientIfPossible();
                }

                notifyAll();
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return true;
    }

    /** Versao sem log de ATENDIMENTO, para testes que fazem o papel de barbeiro. */
    public synchronized Client getNext() {
        return getNext(null, 0);
    }

    /**
     * Chamado pelo barbeiro: retira o cliente mais antigo do sofa e promove o mais antigo da fila
     * em pe, tudo atomicamente sob o monitor da sala (inclusive a marca iAmNext e os logs).
     * @return o cliente chamado, ou null se o sofa esta vazio.
     */
    public synchronized Client getNext(String barberName, int chairId) {
        if (seatedClients.isEmpty()) {
            return null;
        }

        Client client = seatedClients.poll();
        if (barberName != null) {
            logger.log(barberName, Event.ATENDIMENTO,
                    "Chamou " + client.getName() + " do sof\u00e1 para a Cadeira " + chairId + ".");
        }
        promoteStandingClientIfPossible();
        client.iAmTheNext();

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
        peakSeated = Math.max(peakSeated, seatedClients.size());
        logger.log(promoted.getName(), Event.PROMOCAO,
                "Assumiu assento no sof\u00e1 (Posi\u00e7\u00e3o " + seatedClients.size() + " do sof\u00e1).");
        return promoted;
    }

    /** Retrato das duas filas (para depuracao); nao imprime nada. */
    public synchronized String describe() {
        return "Sof\u00e1: " + seatedClients + " | Em p\u00e9: " + standingClients;
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

    /**
     * Clientes recusados por sala fisicamente cheia (17). Caminho defensivo: com a Capacity
     * limitando a 20 e 3 clientes nas cadeiras, a sala raramente chega a recusar alguem.
     */
    public synchronized int getRejectedCount() {
        return rejectedCount;
    }

    /** Maior ocupacao observada do sofa (deve ser <= 4). */
    public synchronized int getPeakSeated() {
        return peakSeated;
    }

    /** Maior ocupacao observada da fila em pe (deve ser <= 13). */
    public synchronized int getPeakStanding() {
        return peakStanding;
    }

    /** Quantas vezes um cliente foi interrompido ja depois de chamado (e seguiu para o atendimento). */
    public synchronized int getInterruptedAfterCallCount() {
        return interruptedAfterCallCount;
    }

    private boolean isSeatedClientsFull() {
        return seatedClients.size() >= MAX_SEATED_CLIENTS;
    }

    private boolean isStandingClientsFull() {
        return standingClients.size() >= MAX_STANDING_CLIENTS;
    }
}
