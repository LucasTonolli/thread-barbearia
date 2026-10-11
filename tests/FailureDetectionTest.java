package tests;

import barbearia.Simulation;
import barbearia.integracao.AuditLogger;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Deteccao de falhas da Simulation/Main:
 *  - um deadlock real (dois locks em ordem cruzada) e reconhecido por diagnoseThreads();
 *  - uma simulacao que nao termina no prazo e marcada como timeout, com dump e invariante violada;
 *  - o Main sai com codigo 2 nesse caso e com 0 numa execucao normal.
 */
public class FailureDetectionTest {

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

    public static void main(String[] args) throws Exception {
        testSyntheticDeadlockIsDetected();
        testSimulationTimeout();
        testMainExitCodes();

        System.out.println();
        System.out.println("=== Resultado: " + passed + " passaram, " + failed + " falharam ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testSyntheticDeadlockIsDetected() throws InterruptedException {
        System.out.println("--- Deadlock sintetico (A->B e B->A) ---");
        ReentrantLock a = new ReentrantLock();
        ReentrantLock b = new ReentrantLock();
        CountDownLatch bothHoldFirst = new CountDownLatch(2);
        Thread t1 = new Thread(() -> crossLock(a, b, bothHoldFirst), "Deadlock-1");
        Thread t2 = new Thread(() -> crossLock(b, a, bothHoldFirst), "Deadlock-2");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        bothHoldFirst.await();
        t1.join(300); // as duas ficam presas; o join so limita a espera do teste

        String diagnostic = Simulation.diagnoseThreads();
        check("diagnoseThreads reconhece o ciclo de locks", diagnostic.startsWith("DEADLOCK detectado"));
        check("dump cita as duas threads", diagnostic.contains("Deadlock-1") && diagnostic.contains("Deadlock-2"));
        check("dump informa o dono do lock esperado", diagnostic.contains("(dono: Deadlock-"));
        // As threads sao daemon e ficam presas: e o proprio deadlock que queremos detectar.
    }

    private static void crossLock(ReentrantLock first, ReentrantLock second, CountDownLatch latch) {
        first.lock();
        latch.countDown();
        try {
            latch.await();
            second.lock();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void testSimulationTimeout() throws InterruptedException {
        System.out.println("--- Simulation que nao termina no prazo ---");
        Simulation.Config cfg = new Simulation.Config();
        cfg.clients = 10;
        cfg.maxArrivalIntervalMs = 0;
        cfg.cutMs = 3000;      // corte muito mais longo que o prazo
        cfg.timeoutMs = 500;
        long t0 = System.nanoTime();
        Simulation.Result r = Simulation.run(cfg, AuditLogger.silent());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        check("timeout detectado", r.timedOut);
        check("diagnostico preenchido com dump", r.diagnostic != null && r.diagnostic.contains("Thread-Barbeiro-01"));
        List<String> violations = Simulation.checkInvariants(r);
        check("invariante de timeout reportada: " + violations,
                violations.stream().anyMatch(v -> v.contains("timeout")));
        check("caminho de emergencia nao espera o corte inteiro (" + elapsedMs + " ms)", elapsedMs < 3000);
    }

    private static void testMainExitCodes() throws Exception {
        System.out.println("--- Codigo de saida do Main ---");
        int failure = runMain("--silencioso", "--clientes", "5", "--corte-ms", "3000", "--timeout-s", "1",
                "--execucao", "901");
        check("timeout -> codigo 2 (obtido " + failure + ")", failure == 2);
        int ok = runMain("--silencioso", "--clientes", "10", "--corte-ms", "5", "--pagamento-ms", "2",
                "--execucao", "902");
        check("execucao normal -> codigo 0 (obtido " + ok + ")", ok == 0);
        int usage = runMain("--cenario", "Z");
        check("argumento invalido -> codigo 64 (obtido " + usage + ")", usage == 64);
        for (String run : new String[] {"B-901", "B-902"}) {
            new File("logs/execucao-" + run + ".log").delete();
            new File("resultados/resultados-" + run + ".csv").delete();
        }
    }

    private static int runMain(String... args) throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String[] cmd = new String[args.length + 4];
        cmd[0] = java;
        cmd[1] = "-cp";
        cmd[2] = System.getProperty("java.class.path");
        cmd[3] = "barbearia.Main";
        System.arraycopy(args, 0, cmd, 4, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        return p.waitFor();
    }
}
