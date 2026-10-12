package tests;

import barbearia.barbeiro.Barber;
import barbearia.barbeiro.BarberChair;
import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.AuditLogger;
import barbearia.integracao.Capacity;
import barbearia.integracao.PosTerminal;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Casos de falha e interrupcao:
 *  - gap 6: excecao inesperada no atendimento nao deixa o cliente preso;
 *  - gap 7: interrupcao depois de o cliente ser chamado nao gera atendimento "fantasma";
 *  - interrupcao antes de ser chamado: o cliente desiste, devolve a vaga e o proximo e promovido;
 *  - interrupcao durante o corte: o cliente so sai depois que o barbeiro encerra.
 */
public class RobustnessTest {

    private static final long TIMEOUT_MS = 2000;
    private static final int GAP7_ATTEMPTS = 200;

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
        testUnexpectedExceptionReleasesClient();
        testInterruptAfterCalled();
        testInterruptBeforeCalled();
        testInterruptDuringHaircut();

        System.out.println();
        System.out.println("=== Resultado: " + passed + " passaram, " + failed + " falharam ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** Espera, no monitor da sala, ate haver 'expected' clientes na sala (enter() faz notifyAll). */
    static void awaitWaiting(WaitingRoom room, int expected) throws InterruptedException {
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

    private static void testUnexpectedExceptionReleasesClient() throws InterruptedException {
        System.out.println("--- Gap 6: excecao inesperada em chair.occupy ---");
        AuditLogger logger = AuditLogger.inMemory();
        WaitingRoom room = new WaitingRoom(logger);
        PosTerminal pos = new PosTerminal(logger);
        Capacity capacity = new Capacity(Capacity.DEFAULT_MAX, logger);

        // Cadeira ja ocupada: occupy() lanca IllegalStateException (nao e InterruptedException).
        BarberChair chair = new BarberChair(1, "Barbeiro-01");
        Client intruder = new Client("Intruso", room);
        chair.occupy(intruder);

        Barber barber = new Barber(1, "Barbeiro-01", chair, room, pos, 5, 2, logger);
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread barberThread = new Thread(barber, "Thread-Barbeiro-01");
        barberThread.setUncaughtExceptionHandler((t, e) -> uncaught.set(e));

        Client client = new Client("Cliente-01", room, capacity, logger);
        Thread clientThread = new Thread(client, "Thread-Cliente-01");

        barberThread.start();
        clientThread.start();
        clientThread.join(TIMEOUT_MS);
        barberThread.join(TIMEOUT_MS);

        check("thread do cliente terminou (nao ficou orfa)", !clientThread.isAlive());
        check("cliente nao consta como atendido", !client.wasServed());
        check("vaga devolvida (lotacao 0, 20 permissoes)",
                capacity.getCurrentOccupancy() == 0 && capacity.availablePermits() == Capacity.DEFAULT_MAX);
        check("excecao chegou ao UncaughtExceptionHandler: " + uncaught.get(),
                uncaught.get() instanceof IllegalStateException);
        check("cadeira do intruso nao foi liberada indevidamente", chair.getCurrentClient() == intruder);
        check("nenhum pagamento registrado", pos.getCompletedTransactions() == 0);
        check("SAIDA registrada sem atendimento", lastLineOf(logger, "Cliente-01").contains("sem concluir"));
    }

    /**
     * Gap 7. Sequencia forcada: o teste segura o monitor da sala, interrompe o cliente (que esta
     * em wait()) e so entao o chama com getNext(). Quando o cliente recupera o monitor, o wait()
     * pode lancar InterruptedException mesmo com iAmNext ja marcado. O sleep(1) so alarga a janela
     * para que esse caminho aconteca; as verificacoes valem em qualquer intercalacao.
     */
    private static void testInterruptAfterCalled() throws InterruptedException {
        System.out.println("--- Gap 7: interrupcao depois de iAmNext (" + GAP7_ATTEMPTS + " tentativas) ---");
        int ghostAttendances = 0;
        int lostClients = 0;
        int interruptLost = 0;
        int lateInterrupts = 0;

        for (int i = 1; i <= GAP7_ATTEMPTS; i++) {
            AuditLogger logger = AuditLogger.inMemory();
            WaitingRoom room = new WaitingRoom(logger);
            Capacity capacity = new Capacity(Capacity.DEFAULT_MAX, logger);
            Client client = new Client("Cliente-01", room, capacity, logger);
            AtomicBoolean interruptKept = new AtomicBoolean(false);
            Thread clientThread = new Thread(() -> {
                client.run();
                interruptKept.set(Thread.currentThread().isInterrupted());
            }, "Thread-Cliente-01");
            clientThread.start();
            awaitWaiting(room, 1);

            Client called;
            synchronized (room) {
                clientThread.interrupt();
                Thread.sleep(1);
                called = room.getNext("Barbeiro-Teste", 1);
            }

            // O barbeiro "de teste" ja tem o cliente: ele nao pode ter desistido.
            clientThread.join(20);
            boolean stillWaiting = clientThread.isAlive() && capacity.getCurrentOccupancy() == 1;
            if (!stillWaiting) {
                ghostAttendances++;
            }

            called.setAttendedBy("Barbeiro-Teste");
            called.completeAttendance();
            clientThread.join(TIMEOUT_MS);

            if (clientThread.isAlive() || !client.wasServed() || capacity.getCurrentOccupancy() != 0) {
                lostClients++;
            }
            if (!interruptKept.get()) {
                interruptLost++;
            }
            lateInterrupts += room.getInterruptedAfterCallCount();
        }

        check("cliente chamado nunca desistiu antes do fim do atendimento (" + ghostAttendances + " falhas)",
                ghostAttendances == 0);
        check("cliente sempre atendido e vaga devolvida (" + lostClients + " falhas)", lostClients == 0);
        check("flag de interrupcao preservada ao fim (" + interruptLost + " perdas)", interruptLost == 0);
        check("caminho do gap 7 (InterruptedException apos iAmNext) exercitado: "
                + lateInterrupts + "/" + GAP7_ATTEMPTS + " tentativas", lateInterrupts > 0);
    }

    private static void testInterruptBeforeCalled() throws InterruptedException {
        System.out.println("--- Interrupcao antes de ser chamado ---");
        AuditLogger logger = AuditLogger.inMemory();
        WaitingRoom room = new WaitingRoom(logger);
        Capacity capacity = new Capacity(Capacity.DEFAULT_MAX, logger);
        Thread[] threads = new Thread[5];
        Client[] clients = new Client[5];
        for (int i = 0; i < 5; i++) {
            clients[i] = new Client("Cliente-0" + (i + 1), room, capacity, logger);
            threads[i] = new Thread(clients[i], "Thread-Cliente-0" + (i + 1));
            threads[i].start();
            awaitWaiting(room, i + 1);
        }

        threads[0].interrupt(); // Cliente-01 esta no sofa; Cliente-05 esta em pe
        threads[0].join(TIMEOUT_MS);
        check("cliente interrompido saiu", !threads[0].isAlive());
        check("cliente interrompido nao foi atendido", !clients[0].wasServed());
        check("Cliente-05 promovido para o sofa", room.getSeatedCount() == 4 && room.getStandingCount() == 0);
        check("vaga devolvida (lotacao 4)", capacity.getCurrentOccupancy() == 4);
        check("SAIDA com motivo da desistencia", lastLineOf(logger, "Cliente-01").contains("interrompido"));

        for (int expected = 2; expected <= 5; expected++) {
            Client next = room.getNext("Barbeiro-Teste", 1);
            check("FIFO apos desistencia: chamou Cliente-0" + expected,
                    next != null && next.getName().equals("Cliente-0" + expected));
            next.setAttendedBy("Barbeiro-Teste");
            next.completeAttendance();
        }
        for (Thread t : threads) {
            t.join(TIMEOUT_MS);
        }
        check("lotacao final 0", capacity.getCurrentOccupancy() == 0);
    }

    private static void testInterruptDuringHaircut() throws InterruptedException {
        System.out.println("--- Interrupcao do cliente durante o corte ---");
        AuditLogger logger = AuditLogger.inMemory();
        WaitingRoom room = new WaitingRoom(logger);
        PosTerminal pos = new PosTerminal(logger);
        Capacity capacity = new Capacity(Capacity.DEFAULT_MAX, logger);
        Barber barber = new Barber(1, "Barbeiro-01", new BarberChair(1, "Barbeiro-01"), room, pos, 150, 5, logger);
        Thread barberThread = new Thread(barber, "Thread-Barbeiro-01");
        barberThread.start();

        Client client = new Client("Cliente-01", room, capacity, logger);
        Thread clientThread = new Thread(client, "Thread-Cliente-01");
        clientThread.start();

        // Espera (no monitor da sala) o barbeiro tirar o cliente do sofa.
        synchronized (room) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (!client.isTheNext() && System.currentTimeMillis() < deadline) {
                room.wait(deadline - System.currentTimeMillis());
            }
        }
        clientThread.interrupt(); // corte de 150 ms em andamento

        clientThread.join(TIMEOUT_MS);
        check("cliente terminou", !clientThread.isAlive());
        check("cliente so saiu depois do pagamento (atendido)", client.wasServed());
        check("1 pagamento registrado", pos.getCompletedTransactions() == 1);
        check("SAIDA informa o barbeiro", lastLineOf(logger, "Cliente-01").contains("com Barbeiro-01"));

        barber.stopWhenEmpty();
        barberThread.join(TIMEOUT_MS);
        check("barbeiro encerrou com stopWhenEmpty", !barberThread.isAlive());
    }

    private static String lastLineOf(AuditLogger logger, String actor) {
        List<String> lines = logger.lines();
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).contains("[Thread-" + actor + "]")) {
                return lines.get(i);
            }
        }
        return "";
    }
}
