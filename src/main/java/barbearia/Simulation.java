package barbearia;

import barbearia.barbeiro.Barber;
import barbearia.barbeiro.BarberChair;
import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.AuditLogger;
import barbearia.integracao.Capacity;
import barbearia.integracao.PosTerminal;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Monta e executa uma simulacao completa (3 barbeiros, N clientes, recursos compartilhados).
 * Usada pelo Main e pelos testes de integracao, que assim exercitam exatamente o mesmo codigo.
 *
 * Ordem de encerramento: join de todos os clientes -> stopWhenEmpty() nos barbeiros ->
 * join dos barbeiros -> invariantes. stop() so e usado no caminho de emergencia.
 *
 * Deteccao de falhas: todas as threads tem um UncaughtExceptionHandler e a espera tem prazo.
 * Se o prazo estoura ou alguma thread morre com excecao, a simulacao gera um diagnostico
 * (ThreadMXBean.findDeadlockedThreads + dump de todas as threads) e entra no caminho de emergencia.
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
        /** Excecoes que escaparam de alguma thread ("thread: excecao"). */
        public final List<String> uncaughtExceptions = Collections.synchronizedList(new ArrayList<>());
        /** Dump das threads e ciclos de lock, preenchido so quando a simulacao falha. */
        public String diagnostic;

        public boolean failed() {
            return timedOut || !uncaughtExceptions.isEmpty();
        }

        public int served() {
            return barbers.stream().mapToInt(Barber::getClientsServed).sum();
        }

        public int dropouts() {
            return capacity.getRejectedCount() + room.getRejectedCount();
        }
    }

    /** Acompanha os clientes ainda vivos e as falhas; a thread principal espera neste monitor. */
    private static final class Tracker {
        private int runningClients;
        private int failures;

        synchronized void clientFinished() {
            runningClients--;
            notifyAll();
        }

        synchronized void failure() {
            failures++;
            notifyAll();
        }

        /** @return true se todos os clientes terminaram sem falha antes do prazo. */
        synchronized boolean awaitClients(long deadlineNanos) throws InterruptedException {
            while (runningClients > 0 && failures == 0) {
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    return false;
                }
                wait(remainingMs);
            }
            return failures == 0;
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

        Tracker tracker = new Tracker();
        tracker.runningClients = cfg.clients;
        Thread.UncaughtExceptionHandler handler = (t, e) -> {
            r.uncaughtExceptions.add(t.getName() + ": " + e);
            System.err.println("Excecao nao tratada em " + t.getName() + ": " + e);
            e.printStackTrace();
            tracker.failure();
        };

        List<Thread> barberThreads = new ArrayList<>();
        for (int i = 1; i <= NUM_BARBERS; i++) {
            String name = String.format("Barbeiro-%02d", i);
            Barber barber = new Barber(i, name, new BarberChair(i, name), r.room, r.pos,
                    cfg.cutMs, cfg.paymentMs, logger);
            r.barbers.add(barber);
            barberThreads.add(newThread(barber, "Thread-" + name, handler));
        }

        List<Thread> clientThreads = new ArrayList<>();
        for (int i = 1; i <= cfg.clients; i++) {
            String name = String.format("Cliente-%02d", i);
            Client client = new Client(name, r.room, r.capacity, logger);
            r.clients.add(client);
            Runnable tracked = () -> {
                try {
                    client.run();
                } finally {
                    tracker.clientFinished();
                }
            };
            clientThreads.add(newThread(tracked, "Thread-" + name, handler));
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

        // 1. Todos os clientes precisam terminar (atendidos ou desistentes).
        boolean clientsDone = tracker.awaitClients(deadline);
        if (clientsDone) {
            for (Thread t : clientThreads) {
                clientsDone &= joinUntil(t, deadline);
            }
        }
        if (!clientsDone) {
            r.timedOut = r.uncaughtExceptions.isEmpty();
            abort(r, clientThreads, barberThreads);
            return r;
        }

        // 2. Encerramento normal: barbeiros terminam quando a sala estiver vazia.
        r.barbers.forEach(Barber::stopWhenEmpty);
        boolean barbersDone = true;
        for (Thread t : barberThreads) {
            barbersDone &= joinUntil(t, deadline);
        }
        if (!barbersDone) {
            r.timedOut = true;
        }
        if (r.failed()) {
            abort(r, clientThreads, barberThreads);
        }
        return r;
    }

    /** Diagnostico + caminho de emergencia. */
    private static void abort(Result r, List<Thread> clientThreads, List<Thread> barberThreads)
            throws InterruptedException {
        r.diagnostic = diagnoseThreads();
        emergencyStop(r, clientThreads, barberThreads);
    }

    /**
     * Procura ciclos de lock (ThreadMXBean.findDeadlockedThreads) e faz o dump de todas as threads,
     * com o lock que cada uma espera e quem o possui.
     */
    public static String diagnoseThreads() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        StringBuilder sb = new StringBuilder();
        long[] deadlocked = mx.findDeadlockedThreads();
        if (deadlocked != null) {
            sb.append("DEADLOCK detectado pela JVM (ciclo de locks) em ").append(deadlocked.length)
                    .append(" threads:\n");
            for (ThreadInfo info : mx.getThreadInfo(deadlocked, true, true)) {
                appendThread(sb, info);
            }
        } else {
            sb.append("findDeadlockedThreads: nenhum ciclo de locks (threads paradas em wait/join,"
                    + " lentas ou mortas).\n");
        }
        sb.append("Dump de todas as threads:\n");
        for (ThreadInfo info : mx.dumpAllThreads(true, true)) {
            appendThread(sb, info);
        }
        return sb.toString();
    }

    private static void appendThread(StringBuilder sb, ThreadInfo info) {
        if (info == null) {
            return;
        }
        sb.append('"').append(info.getThreadName()).append("\" ").append(info.getThreadState());
        if (info.getLockName() != null) {
            sb.append(" aguardando ").append(info.getLockName());
        }
        if (info.getLockOwnerName() != null) {
            sb.append(" (dono: ").append(info.getLockOwnerName()).append(')');
        }
        sb.append('\n');
        StackTraceElement[] stack = info.getStackTrace();
        for (int i = 0; i < stack.length; i++) {
            sb.append("    at ").append(stack[i]).append('\n');
            for (MonitorInfo monitor : info.getLockedMonitors()) {
                if (monitor.getLockedStackDepth() == i) {
                    sb.append("    - segura ").append(monitor).append('\n');
                }
            }
        }
        for (LockInfo lock : info.getLockedSynchronizers()) {
            sb.append("    - segura ").append(lock).append('\n');
        }
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
        expect(violations, r.uncaughtExceptions.isEmpty(), "excecoes nao tratadas: " + r.uncaughtExceptions);
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

    private static Thread newThread(Runnable task, String name, Thread.UncaughtExceptionHandler handler) {
        Thread t = new Thread(task, name);
        t.setUncaughtExceptionHandler(handler);
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
