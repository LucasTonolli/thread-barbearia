package barbearia;

import barbearia.barbeiro.Barber;
import barbearia.integracao.AuditLogger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Ponto de entrada da simulacao do Problema da Barbearia de Hilzer.
 *
 * Uso: java -cp out barbearia.Main [--clientes N] [--cenario A|B] [--seed S]
 *          [--intervalo-max-ms MS] [--corte-ms MS] [--pagamento-ms MS] [--timeout-s S]
 *          [--execucao K] [--silencioso]
 *
 * O cenario (A = monoprocessador, B = multicore) e um rotulo para o log e o CSV: a afinidade
 * de CPU e aplicada de fora, pelos scripts em scripts/. O numero de processadores que a JVM
 * enxerga e registrado no inicio do log como prova.
 */
public class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILURE = 2;
    static final int EXIT_USAGE = 64;

    public static void main(String[] args) throws InterruptedException {
        Simulation.Config cfg = new Simulation.Config();
        String scenario = "B";
        int execution = 0;
        boolean quiet = false;

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--clientes" -> cfg.clients = Integer.parseInt(args[++i]);
                    case "--cenario" -> scenario = args[++i].toUpperCase();
                    case "--seed" -> cfg.seed = Long.parseLong(args[++i]);
                    case "--intervalo-max-ms" -> cfg.maxArrivalIntervalMs = Integer.parseInt(args[++i]);
                    case "--corte-ms" -> cfg.cutMs = Long.parseLong(args[++i]);
                    case "--pagamento-ms" -> cfg.paymentMs = Long.parseLong(args[++i]);
                    case "--timeout-s" -> cfg.timeoutMs = Long.parseLong(args[++i]) * 1000L;
                    case "--execucao" -> execution = Integer.parseInt(args[++i]);
                    case "--silencioso" -> quiet = true;
                    default -> throw new IllegalArgumentException("argumento desconhecido: " + args[i]);
                }
            }
            if (!scenario.equals("A") && !scenario.equals("B")) {
                throw new IllegalArgumentException("--cenario deve ser A ou B");
            }
        } catch (RuntimeException e) {
            System.err.println("Erro: " + e.getMessage());
            System.err.println("Uso: java -cp out barbearia.Main [--clientes N] [--cenario A|B] [--seed S]"
                    + " [--intervalo-max-ms MS] [--corte-ms MS] [--pagamento-ms MS] [--timeout-s S]"
                    + " [--execucao K] [--silencioso]");
            System.exit(EXIT_USAGE);
            return;
        }

        String label = scenario + (execution > 0 ? "-" + execution : "");
        int cpus = Runtime.getRuntime().availableProcessors();
        String environment = String.format(
                "Cenario %s | processadores visiveis para a JVM: %d | Java %s | %d clientes, seed %d,"
                        + " chegadas 0-%d ms, corte %d ms, pagamento %d ms",
                scenario, cpus, System.getProperty("java.version"), cfg.clients, cfg.seed,
                cfg.maxArrivalIntervalMs, cfg.cutMs, cfg.paymentMs);

        int exitCode;
        try (AuditLogger logger = new AuditLogger(Path.of("logs", "execucao-" + label + ".log"), !quiet, false)) {
            logger.note(environment);
            if (quiet) {
                System.out.println("# " + environment);
            }

            Simulation.Result result;
            try {
                result = Simulation.run(cfg, logger);
            } catch (RuntimeException e) {
                // Falha na propria orquestracao (ex.: arquivo de log): diagnostico e saida 2.
                System.err.println("FALHA na simulacao: " + e);
                e.printStackTrace();
                System.err.println(Simulation.diagnoseThreads());
                System.exit(EXIT_FAILURE);
                return;
            }

            if (result.diagnostic != null) {
                System.err.println(result.timedOut
                        ? "TIMEOUT: a simulacao nao terminou em " + cfg.timeoutMs / 1000 + " s (possivel deadlock)."
                        : "FALHA: excecao nao tratada em alguma thread.");
                System.err.println(result.diagnostic);
                for (String line : result.diagnostic.split("\n")) {
                    logger.note(line);
                }
            }
            printSummary(result);
            Path csv = Path.of("resultados", "resultados-" + label + ".csv");
            try {
                result.metrics.writeCsv(csv, scenario, execution, cfg.clients, cpus);
                System.out.println("Metricas gravadas em " + csv);
            } catch (IOException e) {
                System.err.println("Nao foi possivel gravar " + csv + ": " + e);
            }

            List<String> violations = Simulation.checkInvariants(result);
            if (violations.isEmpty()) {
                System.out.println("VALIDACAO FINAL: todas as invariantes foram respeitadas.");
                exitCode = EXIT_OK;
            } else {
                System.err.println("VALIDACAO FINAL: invariantes violadas:");
                violations.forEach(v -> System.err.println("  - " + v));
                exitCode = EXIT_FAILURE;
            }
        }
        System.exit(exitCode);
    }

    private static void printSummary(Simulation.Result r) {
        System.out.println("================ RESUMO DA SIMULACAO ================");
        System.out.print(r.metrics.summary(r.config.clients));
        for (Barber b : r.barbers) {
            System.out.println("Atendidos por " + b.getName() + "               : " + b.getClientsServed());
        }
        System.out.println("Transacoes na POS                   : " + r.pos.getCompletedTransactions());
        System.out.println("Pico de lotacao / sofa / em pe      : " + r.capacity.getMaxObserved() + " / "
                + r.room.getPeakSeated() + " / " + r.room.getPeakStanding());
        System.out.println("Pico de cadeiras ocupadas           : " + r.metrics.getPeakChairs());
        System.out.println("Maximo de pagamentos simultaneos    : " + r.pos.getMaxConcurrentTransactions());
        System.out.println("=====================================================");
    }
}
