package barbearia;

import barbearia.barbeiro.Barber;
import barbearia.barbeiro.BarberChair;
import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.AuditLogger;
import barbearia.integracao.Capacity;
import barbearia.integracao.PosTerminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Monta e executa uma simulacao completa (3 barbeiros, N clientes, recursos compartilhados).
 * Usada pelo Main e pelos testes de integracao, que assim exercitam exatamente o mesmo codigo.
 *
 * Ordem de encerramento: join de todos os clientes -> stopWhenEmpty() nos barbeiros ->
 * join dos barbeiros -> invariantes. stop() so e usado no caminho de emergencia (timeout).
 */
public class Simulation {

    public static final int NUM_BARBERS = 3;

    /** Parametros de uma execucao. */
    public static final class Config {
        public int clients = 50;
        public long seed = 42L;
        /** Intervalo entre chegadas sorteado em [0, max] ms; 0 = todos chegam juntos. */
        public int maxArrivalIntervalMs = 20;
        public long cutMs = 30L;
        public long paymentMs = 15L;
        public long timeoutMs = 60_000L;
    }

    /** Objetos e resultados de uma execucao (inspecionados pelo Main e pelos testes). */
    public static final class Result {
        public Config config;
        public WaitingRoom room;
        public PosTerminal pos;
        public Capacity capacity;
        public final List<Barber> barbers = new ArrayList<>();
        public final List<Client> clients = new ArrayList<>();
        public boolean timedOut;

        public int served() {
            return barbers.stream().mapToInt(Barber::getClientsServed).sum();
        }

        public int dropouts() {
            return capacity.getRejectedCount() + room.getRejectedCount();
        }
    }

    private Simulation() {
    }

    public static Result run(Config cfg, AuditLogger logger) throws InterruptedException {
        Result r = new Result();
        r.config = cfg;
        r.room = new WaitingRoom(logger);
        r.pos = new PosTerminal(logger);
        r.capacity = new Capacity(Capacity.DEFAULT_MAX, logger);

        List<Thread> barberThreads = new ArrayList<>();
        for (int i = 1; i <= NUM_BARBERS; i++) {
            String name = String.format("Barbeiro-%02d", i);
            Barber barber = new Barber(i, name, new BarberChair(i, name), r.room, r.pos,
                    cfg.cutMs, cfg.paymentMs, logger);
            r.barbers.add(barber);
            barberThreads.add(newThread(barber, "Thread-" + name));
        }

        List<Thread> clientThreads = new ArrayList<>();
        for (int i = 1; i <= cfg.clients; i++) {
            String name = String.format("Cliente-%02d", i);
            Client client = new Client(name, r.room, r.capacity, logger);
            r.clients.add(client);
            clientThreads.add(newThread(client, "Thread-" + name));
        }

        long deadline = System.nanoTime() + cfg.timeoutMs * 1_000_000L;
        barberThreads.forEach(Thread::start);

        // Chegadas escalonadas com semente fixa (reprodutivel). O sleep apenas simula o tempo
        // entre chegadas; nenhuma sincronizacao depende dele.
        Random rng = new Random(cfg.seed);
        for (int i = 0; i < clientThreads.size(); i++) {
            clientThreads.get(i).start();
            if (cfg.maxArrivalIntervalMs > 0 && i < clientThreads.size() - 1) {
                Thread.sleep(rng.nextInt(cfg.maxArrivalIntervalMs + 1));
            }
        }

        for (Thread t : clientThreads) {
            if (!joinUntil(t, deadline)) {
                r.timedOut = true;
                break;
            }
        }

        if (r.timedOut) {
            emergencyStop(r, clientThreads, barberThreads);
        } else {
            r.barbers.forEach(Barber::stopWhenEmpty);
            for (Thread t : barberThreads) {
                if (!joinUntil(t, deadline)) {
                    r.timedOut = true;
                }
            }
            if (r.timedOut) {
                emergencyStop(r, clientThreads, barberThreads);
            }
        }
        return r;
    }

    /**
     * Confere as invariantes ao fim da execucao.
     * @return uma mensagem por invariante violada (lista vazia = tudo certo)
     */
    public static List<String> checkInvariants(Result r) {
        List<String> violations = new ArrayList<>();
        int max = r.capacity.getMaxCapacity();
        int n = r.config.clients;

        expect(violations, !r.timedOut, "simulacao nao terminou dentro do timeout (possivel deadlock)");
        expect(violations, r.capacity.getMaxObserved() <= max,
                "lotacao maxima " + r.capacity.getMaxObserved() + " > " + max);
        expect(violations, r.capacity.getCurrentOccupancy() == 0 && r.capacity.availablePermits() == max,
                "vagas nao devolvidas: lotacao final " + r.capacity.getCurrentOccupancy()
                        + ", permissoes " + r.capacity.availablePermits() + "/" + max);
        expect(violations, r.room.isEmpty(), "sala de espera nao terminou vazia: " + r.room.describe());
        expect(violations, r.room.getPeakSeated() <= WaitingRoom.MAX_SEATED_CLIENTS,
                "sofa com " + r.room.getPeakSeated() + " > " + WaitingRoom.MAX_SEATED_CLIENTS);
        expect(violations, r.room.getPeakStanding() <= WaitingRoom.MAX_STANDING_CLIENTS,
                "fila em pe com " + r.room.getPeakStanding() + " > " + WaitingRoom.MAX_STANDING_CLIENTS);
        expect(violations, r.pos.getMaxConcurrentTransactions() <= 1,
                "pagamentos simultaneos na POS: " + r.pos.getMaxConcurrentTransactions());
        expect(violations, r.pos.getCompletedTransactions() == r.served(),
                "pagamentos (" + r.pos.getCompletedTransactions() + ") != atendimentos (" + r.served() + ")");
        expect(violations, r.served() + r.dropouts() == n,
                "atendidos (" + r.served() + ") + desistentes (" + r.dropouts() + ") != " + n);
        return violations;
    }

    private static void expect(List<String> violations, boolean condition, String message) {
        if (!condition) {
            violations.add(message);
        }
    }

    private static Thread newThread(Runnable task, String name) {
        Thread t = new Thread(task, name);
        // Daemon: uma simulacao travada nao impede a JVM de encerrar depois do diagnostico.
        t.setDaemon(true);
        return t;
    }

    /** join com prazo absoluto; @return false se a thread continua viva. */
    private static boolean joinUntil(Thread t, long deadlineNanos) throws InterruptedException {
        long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        t.join(Math.max(1L, remainingMs)); // join(0) esperaria para sempre
        return !t.isAlive();
    }

    /** Caminho de emergencia: stop() abortivo e interrupcao de todas as threads. */
    private static void emergencyStop(Result r, List<Thread> clientThreads, List<Thread> barberThreads)
            throws InterruptedException {
        r.barbers.forEach(Barber::stop);
        barberThreads.forEach(Thread::interrupt);
        clientThreads.forEach(Thread::interrupt);
        long grace = System.nanoTime() + 2_000_000_000L;
        for (Thread t : barberThreads) {
            joinUntil(t, grace);
        }
    }
}
