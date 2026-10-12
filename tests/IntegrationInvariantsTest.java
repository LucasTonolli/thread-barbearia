package tests;

import barbearia.Simulation;
import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.AuditLogger;
import barbearia.integracao.AuditLogger.Event;
import barbearia.integracao.Capacity;
import barbearia.integracao.Metrics;
import barbearia.integracao.Metrics.DropReason;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Teste de integracao (Client + Barber + PosTerminal + Capacity + WaitingRoom + AuditLogger +
 * Metrics) e de invariantes sob estresse. Cada rodada executa a Simulation completa, com semente
 * e carga proprias, e confere:
 *
 *  - recursos: lotacao <= 20, sofa <= 4, em pe <= 13, cadeiras <= 3, POS com no maximo 1 pagamento;
 *  - contas: atendidos + desistentes == N, pagamentos == atendimentos, barbeiros == metricas;
 *  - fim: vagas devolvidas, sala vazia, sem timeout e sem excecao nao tratada;
 *  - FIFO sob concorrencia: chamada na ordem de entrada na sala, sofa na ordem de chegada ao sofa
 *    e promocao em pe -> sofa na ordem da fila em pe;
 *  - log: formato, vocabulario, ator logico, ordem cronologica e lotacao variando de 1 em 1.
 *
 * Cada verificacao que falha imprime qual invariante quebrou e os valores observados.
 */
public class IntegrationInvariantsTest {

    private static final int ROUNDS = 30;

    private static final String EVENTS = Arrays.stream(Event.values())
            .map(e -> Pattern.quote(e.label())).collect(Collectors.joining("|"));
    private static final Pattern LOG_LINE = Pattern.compile(
            "^\\[(\\d{2}):(\\d{2})\\.(\\d{3})\\] \\[Thread-((Cliente|Barbeiro)-\\d{2,})\\] (" + EVENTS + "): (.+)$");
    private static final Pattern OCCUPANCY = Pattern.compile("\\(Lota\\S+ (\\d+)/(\\d+)\\)");

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("  [FALHOU] " + description);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Unitario: Capacity ===");
        testCapacity();

        System.out.println("=== Invariantes sob estresse: " + ROUNDS + " rodadas ===");
        int totalClients = 0;
        int totalServed = 0;
        int totalDropped = 0;
        for (int round = 1; round <= ROUNDS; round++) {
            Simulation.Config cfg = configFor(round);
            int[] counts = runRound(round, cfg);
            totalClients += cfg.clients;
            totalServed += counts[0];
            totalDropped += counts[1];
        }

        System.out.println();
        System.out.println("=== RESULTADO: " + passed + " verificacoes passaram, " + failed + " falharam ("
                + ROUNDS + " rodadas, " + totalClients + " clientes, " + totalServed + " atendidos, "
                + totalDropped + " desistencias) ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * Alterna cargas: rajada (todos juntos), chegadas rapidas, chegadas moderadas e, a cada 6
     * rodadas, a configuracao calibrada do experimento (50 clientes, 0-25 ms, corte 30, pagamento 15).
     */
    private static Simulation.Config configFor(int round) {
        Simulation.Config cfg = new Simulation.Config();
        cfg.seed = 1000 + round;
        cfg.timeoutMs = 30_000;
        if (round % 6 == 0) {
            return cfg; // padrao calibrado
        }
        cfg.cutMs = 4;
        cfg.paymentMs = 2;
        switch (round % 3) {
            case 0 -> {
                cfg.clients = 80;
                cfg.maxArrivalIntervalMs = 0;
            }
            case 1 -> {
                cfg.clients = 55;
                cfg.maxArrivalIntervalMs = 2;
            }
            default -> {
                cfg.clients = 55;
                cfg.maxArrivalIntervalMs = 6;
            }
        }
        return cfg;
    }

    private static void testCapacity() {
        Capacity cap = new Capacity();
        boolean allIn = true;
        for (int i = 1; i <= 20; i++) {
            allIn &= cap.tryEnter("C" + i) && cap.getCurrentOccupancy() == i;
        }
        check("Capacity: 20 primeiras entradas aceitas em sequencia", allIn);
        check("Capacity: 21a entrada recusada sem bloquear", !cap.tryEnter("C21"));
        check("Capacity: lotacao = 20 apos a recusa (obtido " + cap.getCurrentOccupancy() + ")",
                cap.getCurrentOccupancy() == 20);
        cap.leave("C1", n -> "saiu");
        check("Capacity: leave devolve a vaga (lotacao " + cap.getCurrentOccupancy() + ", permissoes "
                + cap.availablePermits() + ")", cap.getCurrentOccupancy() == 19 && cap.availablePermits() == 1);
        check("Capacity: vaga devolvida pode ser reutilizada", cap.tryEnter("C22"));
        check("Capacity: pico = 20 (obtido " + cap.getMaxObserved() + ")", cap.getMaxObserved() == 20);
        check("Capacity: 1 rejeicao contada (obtido " + cap.getRejectedCount() + ")", cap.getRejectedCount() == 1);
    }

    /** @return {atendidos, desistentes} */
    private static int[] runRound(int round, Simulation.Config cfg) throws InterruptedException {
        AuditLogger logger = AuditLogger.inMemory();
        Simulation.Result r = Simulation.run(cfg, logger);
        Metrics m = r.metrics;
        int n = cfg.clients;
        String tag = String.format("Rodada %02d (N=%d, chegadas 0-%d ms): ", round, n, cfg.maxArrivalIntervalMs);

        // --- fim da execucao ---
        check(tag + "sem timeout/deadlock", !r.timedOut);
        check(tag + "sem excecao nao tratada: " + r.uncaughtExceptions, r.uncaughtExceptions.isEmpty());
        check(tag + "Simulation.checkInvariants vazio: " + Simulation.checkInvariants(r),
                Simulation.checkInvariants(r).isEmpty());

        // --- recursos ---
        check(tag + "lotacao <= 20 (pico " + r.capacity.getMaxObserved() + ")", r.capacity.getMaxObserved() <= 20);
        check(tag + "sofa <= 4 (pico " + r.room.getPeakSeated() + ")",
                r.room.getPeakSeated() <= WaitingRoom.MAX_SEATED_CLIENTS);
        check(tag + "em pe <= 13 (pico " + r.room.getPeakStanding() + ")",
                r.room.getPeakStanding() <= WaitingRoom.MAX_STANDING_CLIENTS);
        check(tag + "cadeiras <= 3 (pico " + m.getPeakChairs() + ")", m.getPeakChairs() <= 3);
        check(tag + "POS: no maximo 1 pagamento simultaneo (pico " + r.pos.getMaxConcurrentTransactions() + ")",
                r.pos.getMaxConcurrentTransactions() <= 1);

        // --- contas ---
        check(tag + "atendidos + desistentes == N (" + m.getServed() + " + " + m.getTotalDropouts() + " != " + n + ")",
                m.getServed() + m.getTotalDropouts() == n);
        check(tag + "pagamentos == atendimentos (" + r.pos.getCompletedTransactions() + " vs " + m.getServed() + ")",
                r.pos.getCompletedTransactions() == m.getServed());
        check(tag + "soma dos barbeiros == metricas (" + r.served() + " vs " + m.getServed() + ")",
                r.served() == m.getServed());
        check(tag + "desistencias por lotacao: metricas == Capacity (" + m.getDropouts(DropReason.CAPACITY_FULL)
                + " vs " + r.capacity.getRejectedCount() + ")",
                m.getDropouts(DropReason.CAPACITY_FULL) == r.capacity.getRejectedCount());
        check(tag + "desistencias por sala cheia: metricas == WaitingRoom (" + m.getDropouts(DropReason.ROOM_FULL)
                + " vs " + r.room.getRejectedCount() + ")",
                m.getDropouts(DropReason.ROOM_FULL) == r.room.getRejectedCount());
        check(tag + "nenhuma interrupcao/atendimento incompleto em execucao normal ("
                        + m.getDropouts(DropReason.INTERRUPTED) + "/" + m.getDropouts(DropReason.NOT_COMPLETED) + ")",
                m.getDropouts(DropReason.INTERRUPTED) + m.getDropouts(DropReason.NOT_COMPLETED) == 0);

        // --- estado final ---
        check(tag + "vagas devolvidas (lotacao " + r.capacity.getCurrentOccupancy() + ", permissoes "
                        + r.capacity.availablePermits() + "/20)",
                r.capacity.getCurrentOccupancy() == 0 && r.capacity.availablePermits() == 20);
        check(tag + "sala vazia no fim: " + r.room.describe(), r.room.isEmpty());

        checkFifo(tag, r.clients);
        checkLog(tag, logger.lines(), r);

        System.out.printf("Rodada %02d: N=%d atendidos=%d desistentes=%d (lotacao %d, sala %d) | picos: lotacao=%d"
                        + " sofa=%d pe=%d cadeiras=%d POS=%d | %d linhas de log%n",
                round, n, m.getServed(), m.getTotalDropouts(), m.getDropouts(DropReason.CAPACITY_FULL),
                m.getDropouts(DropReason.ROOM_FULL), r.capacity.getMaxObserved(), r.room.getPeakSeated(),
                r.room.getPeakStanding(), m.getPeakChairs(), r.pos.getMaxConcurrentTransactions(),
                logger.lines().size());
        return new int[] {m.getServed(), m.getTotalDropouts()};
    }

    /**
     * FIFO nos dois niveis, sob concorrencia real. As ordens sao atribuidas sob o monitor da sala,
     * entao refletem a ordem real das operacoes nas filas.
     */
    private static void checkFifo(String tag, List<Client> clients) {
        List<Client> called = clients.stream().filter(c -> c.getCallOrder() > 0)
                .sorted(Comparator.comparingLong(Client::getCallOrder)).collect(Collectors.toList());
        int inversions = inversions(called, Client::getEnqueueOrder);
        check(tag + "FIFO global: chamada na ordem de entrada na sala (" + inversions + " inversoes em "
                + called.size() + " chamados)", inversions == 0);

        inversions = inversions(called, Client::getSofaOrder);
        check(tag + "FIFO do sofa: chamada na ordem de chegada ao sofa (" + inversions + " inversoes)",
                inversions == 0);

        List<Client> promoted = clients.stream().filter(c -> c.getStandingOrder() > 0 && c.getSofaOrder() > 0)
                .sorted(Comparator.comparingLong(Client::getStandingOrder)).collect(Collectors.toList());
        inversions = inversions(promoted, Client::getSofaOrder);
        check(tag + "FIFO da promocao: em pe -> sofa na ordem da fila em pe (" + inversions + " inversoes em "
                + promoted.size() + " promovidos)", inversions == 0);

        long servedWithoutCall = clients.stream().filter(c -> c.wasServed() && c.getCallOrder() == 0).count();
        check(tag + "todo atendido foi chamado do sofa (" + servedWithoutCall + " sem chamada)", servedWithoutCall == 0);
    }

    /** Quantos pares consecutivos da lista (ja ordenada por um criterio) estao fora de ordem pelo outro. */
    private static int inversions(List<Client> ordered, ToLongFunction<Client> key) {
        int count = 0;
        for (int i = 1; i < ordered.size(); i++) {
            if (key.applyAsLong(ordered.get(i)) <= key.applyAsLong(ordered.get(i - 1))) {
                count++;
            }
        }
        return count;
    }

    private static void checkLog(String tag, List<String> lines, Simulation.Result r) {
        String badFormat = null;
        String badActor = null;
        String outOfOrder = null;
        String badOccupancy = null;
        long previousMs = -1;
        int occupancy = 0;
        int max = r.capacity.getMaxCapacity();
        Map<String, Integer> arrivals = new HashMap<>();
        Map<String, String> exits = new HashMap<>();
        Map<Event, Integer> perEvent = new HashMap<>();

        for (String line : lines) {
            Matcher lm = LOG_LINE.matcher(line);
            if (!lm.matches()) {
                badFormat = badFormat == null ? line : badFormat;
                continue;
            }
            long ms = Long.parseLong(lm.group(1)) * 60_000 + Long.parseLong(lm.group(2)) * 1000
                    + Long.parseLong(lm.group(3));
            if (ms < previousMs && outOfOrder == null) {
                outOfOrder = line;
            }
            previousMs = ms;

            String actor = lm.group(4);
            Event event = eventOf(lm.group(6));
            String message = lm.group(7);
            perEvent.merge(event, 1, Integer::sum);
            boolean barberEvent = event == Event.ATENDIMENTO;
            if (barberEvent != actor.startsWith("Barbeiro") && badActor == null) {
                badActor = line;
            }

            if (event == Event.CHEGADA || event == Event.SAIDA) {
                Matcher om = OCCUPANCY.matcher(message);
                int expected;
                if (event == Event.SAIDA) {
                    expected = occupancy - 1;
                    exits.put(actor, message);
                } else {
                    arrivals.merge(actor, 1, Integer::sum);
                    expected = message.startsWith("Entrou") ? occupancy + 1 : occupancy;
                }
                if (!om.find() || Integer.parseInt(om.group(1)) != expected || Integer.parseInt(om.group(2)) != max
                        || expected > max) {
                    badOccupancy = badOccupancy == null ? line + " (esperado " + expected + "/" + max + ")" : badOccupancy;
                } else {
                    occupancy = expected;
                }
            }
        }

        check(tag + "log: todas as " + lines.size() + " linhas no formato do enunciado (primeira invalida: "
                + badFormat + ")", badFormat == null);
        check(tag + "log: timestamps em ordem cronologica (primeira fora de ordem: " + outOfOrder + ")",
                outOfOrder == null);
        check(tag + "log: ator logico correto (ATENDIMENTO = barbeiro, demais = cliente; primeira errada: "
                + badActor + ")", badActor == null);
        check(tag + "log: lotacao varia de 1 em 1 e nunca passa de " + max + " (primeira errada: " + badOccupancy + ")",
                badOccupancy == null);
        check(tag + "log: lotacao volta a 0 no fim (obtido " + occupancy + ")", occupancy == 0);

        long multipleArrivals = arrivals.values().stream().filter(v -> v != 1).count();
        check(tag + "log: exatamente 1 CHEGADA por cliente (" + arrivals.size() + " clientes, " + multipleArrivals
                + " com contagem errada)", arrivals.size() == r.config.clients && multipleArrivals == 0);
        int served = r.metrics.getServed();
        check(tag + "log: ATENDIMENTO == atendidos (" + perEvent.getOrDefault(Event.ATENDIMENTO, 0) + " vs " + served + ")",
                perEvent.getOrDefault(Event.ATENDIMENTO, 0) == served);
        check(tag + "log: 2 PAGAMENTO por atendido (" + perEvent.getOrDefault(Event.PAGAMENTO, 0) + " vs " + 2 * served + ")",
                perEvent.getOrDefault(Event.PAGAMENTO, 0) == 2 * served);
        check(tag + "log: 1 SAIDA por cliente admitido (" + exits.size() + " vs " + r.capacity.getAdmittedCount() + ")",
                exits.size() == r.capacity.getAdmittedCount());

        List<String> wrongBarber = new ArrayList<>();
        for (Client c : r.clients) {
            if (c.wasServed()) {
                String exit = exits.get(c.getName());
                if (exit == null || !exit.contains(" com " + c.getAttendedBy() + ". Saiu")) {
                    wrongBarber.add(c.getName());
                }
            }
        }
        check(tag + "log: SAIDA de cada atendido informa o barbeiro que recebeu o pagamento (erros: "
                + wrongBarber + ")", wrongBarber.isEmpty());
    }

    private static Event eventOf(String label) {
        for (Event e : Event.values()) {
            if (e.label().equals(label)) {
                return e;
            }
        }
        throw new IllegalArgumentException(label);
    }
}
