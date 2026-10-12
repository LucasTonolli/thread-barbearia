package tests;

import barbearia.Simulation;
import barbearia.integracao.AuditLogger;

import java.util.List;

/**
 * Teste de carga da integracao final: 20 rodadas, cada uma com 100 clientes chegando todos ao
 * mesmo tempo (rajada) e os tempos padrao de corte e pagamento. Cada rodada falha com a lista
 * das invariantes violadas e os valores observados (Simulation.checkInvariants).
 */
public class IntegrationStressTest {

    private static final int ROUNDS = 20;
    private static final int CLIENTS = 100;

    public static void main(String[] args) throws Exception {
        int failed = 0;
        for (int round = 1; round <= ROUNDS; round++) {
            Simulation.Config cfg = new Simulation.Config();
            cfg.clients = CLIENTS;
            cfg.maxArrivalIntervalMs = 0;
            cfg.seed = round;
            cfg.timeoutMs = 20_000;
            Simulation.Result r = Simulation.run(cfg, AuditLogger.silent());

            List<String> violations = Simulation.checkInvariants(r);
            if (!violations.isEmpty()) {
                failed++;
                System.out.println("Rodada " + round + " FALHOU:");
                violations.forEach(v -> System.out.println("  - " + v));
                if (r.diagnostic != null) {
                    System.out.println(r.diagnostic);
                }
            }
        }

        System.out.println("Resultado: " + (ROUNDS - failed) + "/" + ROUNDS + " rodadas aprovadas.");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
