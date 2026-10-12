package tests;

import barbearia.Simulation;
import barbearia.barbeiro.Barber;
import barbearia.barbeiro.BarberChair;
import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.AuditLogger;
import barbearia.integracao.Capacity;
import barbearia.integracao.Metrics;
import barbearia.integracao.Metrics.DropReason;
import barbearia.integracao.PosTerminal;

import java.util.ArrayList;
import java.util.List;

/**
 * Gap 17: a lotacao de 20 e imposta pela Capacity; a recusa por "sala fisicamente cheia" (17)
 * na WaitingRoom e um caminho defensivo.
 *
 *  - Estrutural: com as 3 cadeiras ocupadas, 3 + 17 = 20 = lotacao, entao o 21o cliente e barrado
 *    pela Capacity antes de chegar a sala. A sala so pode recusar alguem se houver cadeira vazia
 *    enquanto ela esta cheia (barbeiro ainda nao chamou o proximo).
 *  - Empirico: com chegadas escalonadas (configuracao do experimento) a sala nunca recusa; em
 *    rajada (todos chegam juntos, barbeiros ainda sem cliente) pode recusar alguns, sempre muito
 *    menos que a Capacity, e a conta atendidos + desistentes continua fechando.
 */
public class CapacityVsRoomTest {

    private static final long TIMEOUT_MS = 3000;
    private static final int STAGGERED_RUNS = 10;
    private static final int BURST_RUNS = 20;

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String description, boolean condition) {
        if (condition) {
            System.out.println("  [OK] " + description);
            passed++;
        } else {
            System.out.println("  [FALHOU] " + description);
            failed++;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        testChairsFullMeansCapacityRejects();
        testStaggeredArrivals();
        testBurstArrivals();

        System.out.println();
        System.out.println("=== Resultado: " + passed + " passaram, " + failed + " falharam ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testChairsFullMeansCapacityRejects() throws InterruptedException {
        System.out.println("--- Estrutural: 3 nas cadeiras + 17 na sala = 20 ---");
        AuditLogger logger = AuditLogger.silent();
        WaitingRoom room = new WaitingRoom(logger);
        PosTerminal pos = new PosTerminal(logger);
        Capacity capacity = new Capacity(Capacity.DEFAULT_MAX, logger);
        Metrics metrics = new Metrics();
        List<Barber> barbers = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String name = String.format("Barbeiro-%02d", i);
            Barber b = new Barber(i, name, new BarberChair(i, name), room, pos, 60_000, 1, logger, metrics);
            barbers.add(b);
            threads.add(daemon(b, "Thread-" + name));
        }

        List<Client> inChairs = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Client c = new Client(String.format("Cliente-%02d", i), room, capacity, logger, metrics);
            inChairs.add(c);
            threads.add(daemon(c, "Thread-" + c.getName()));
        }
        awaitCalled(room, inChairs); // os 3 primeiros ja estao nas cadeiras (corte de 60 s)

        for (int i = 4; i <= 20; i++) {
            Client c = new Client(String.format("Cliente-%02d", i), room, capacity, logger, metrics);
            threads.add(daemon(c, "Thread-" + c.getName()));
            awaitWaiting(room, i - 3);
        }
        check("sala cheia: 4 no sofa e 13 em pe", room.getSeatedCount() == 4 && room.getStandingCount() == 13);
        check("lotacao = 20 (obtido " + capacity.getCurrentOccupancy() + ")", capacity.getCurrentOccupancy() == 20);

        Client extra = new Client("Cliente-21", room, capacity, logger, metrics);
        extra.run(); // nao bloqueia: a Capacity recusa
        check("21o cliente recusado pela Capacity (rejeicoes: " + capacity.getRejectedCount() + ")",
                capacity.getRejectedCount() == 1 && metrics.getDropouts(DropReason.CAPACITY_FULL) == 1);
        check("sala nao precisou recusar ninguem (rejeicoes da sala: " + room.getRejectedCount() + ")",
                room.getRejectedCount() == 0);

        // Limpeza: caminho de emergencia (as threads sao daemon).
        barbers.forEach(Barber::stop);
        threads.forEach(Thread::interrupt);
    }

    private static void testStaggeredArrivals() throws InterruptedException {
        System.out.println("--- Empirico: chegadas escalonadas (configuracao do experimento) ---");
        int roomRejections = 0;
        int capacityRejections = 0;
        int served = 0;
        for (int run = 1; run <= STAGGERED_RUNS; run++) {
            Simulation.Config cfg = new Simulation.Config();
            cfg.seed = run;
            Simulation.Result r = Simulation.run(cfg, AuditLogger.silent());
            check("execucao " + run + ": invariantes " + Simulation.checkInvariants(r),
                    Simulation.checkInvariants(r).isEmpty());
            roomRejections += r.room.getRejectedCount();
            capacityRejections += r.capacity.getRejectedCount();
            served += r.metrics.getServed();
        }
        System.out.println("  " + STAGGERED_RUNS + " execucoes x 50 clientes: atendidos=" + served
                + ", recusas da Capacity=" + capacityRejections + ", recusas da sala=" + roomRejections);
        check("sala nunca recusou com chegadas escalonadas (" + roomRejections + ")", roomRejections == 0);
    }

    private static void testBurstArrivals() throws InterruptedException {
        System.out.println("--- Empirico: rajada de 100 clientes ---");
        int roomRejections = 0;
        int capacityRejections = 0;
        int runsWithRoomRejection = 0;
        for (int run = 1; run <= BURST_RUNS; run++) {
            Simulation.Config cfg = new Simulation.Config();
            cfg.clients = 100;
            cfg.maxArrivalIntervalMs = 0;
            cfg.seed = run;
            Simulation.Result r = Simulation.run(cfg, AuditLogger.silent());
            if (!Simulation.checkInvariants(r).isEmpty()) {
                check("rajada " + run + ": invariantes " + Simulation.checkInvariants(r), false);
            }
            check("rajada " + run + ": recusas da sala contadas como ROOM_FULL nas metricas",
                    r.metrics.getDropouts(DropReason.ROOM_FULL) == r.room.getRejectedCount());
            roomRejections += r.room.getRejectedCount();
            capacityRejections += r.capacity.getRejectedCount();
            if (r.room.getRejectedCount() > 0) {
                runsWithRoomRejection++;
            }
        }
        System.out.println("  " + BURST_RUNS + " rajadas x 100 clientes: recusas da Capacity=" + capacityRejections
                + ", recusas da sala=" + roomRejections + " (em " + runsWithRoomRejection + " rajadas)");
        check("recusa da sala e muito mais rara que a da Capacity (" + roomRejections + " vs " + capacityRejections + ")",
                roomRejections * 10 < capacityRejections);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Espera, no monitor da sala, ate getNext() marcar todos os clientes dados. */
    private static void awaitCalled(WaitingRoom room, List<Client> clients) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        synchronized (room) {
            while (!clients.stream().allMatch(Client::isTheNext)) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new AssertionError("barbeiros nao chamaram os 3 primeiros clientes");
                }
                room.wait(remaining);
            }
        }
    }

    private static void awaitWaiting(WaitingRoom room, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        synchronized (room) {
            while (room.getTotalWaiting() != expected) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new AssertionError("sala nao chegou a " + expected + " clientes");
                }
                room.wait(remaining);
            }
        }
    }
}
