package barbearia.cliente;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class WaitingRoomStressTest {

    static final int NUM_CLIENTS = 40;      // bem mais que a capacidade (17), de proposito
    static final int BARBER_ATTEMPTS = 80;  // chamadas de getNext() do "barbeiro falso"
    static final int NUM_ROUNDS = 20;       // repete varias vezes: bug de concorrencia e probabilistico

    public static void main(String[] args) throws InterruptedException {
        int roundsWithProblems = 0;

        for (int round = 1; round <= NUM_ROUNDS; round++) {
            boolean problem = runOneRound(round);
            if (problem) {
                roundsWithProblems++;
            }
        }

        System.out.println();
        System.out.println("=== " + roundsWithProblems + " de " + NUM_ROUNDS + " rodadas tiveram algum problema ===");
        if (roundsWithProblems == 0) {
            System.out.println("(nao apareceu problema nessas rodadas - nao significa que esta 100% seguro,");
            System.out.println(" so que essa carga especifica nao teve azar de expor a race condition)");
        }
    }

    static boolean runOneRound(int round) throws InterruptedException {
        WaitingRoom waitingRoom = new WaitingRoom();
        AtomicInteger exceptionCount = new AtomicInteger(0);
        List<Client> servedClients = Collections.synchronizedList(new ArrayList<>());

        List<Thread> clientThreads = new ArrayList<>();
        for (int i = 1; i <= NUM_CLIENTS; i++) {
            Client client = new Client("R" + round + "-Cliente" + i, waitingRoom);
            clientThreads.add(new Thread(client));
        }

        // "Barbeiro falso": so pra gerar concorrencia real sobre getNext() ao mesmo
        // tempo que os clientes chamam enter(). Nao e a implementacao final do Barbeiro.
        Thread fakeBarbeiro = new Thread(() -> {
            for (int i = 0; i < BARBER_ATTEMPTS; i++) {
                try {
                    Client served = waitingRoom.getNext();
                    if (served != null) {
                        servedClients.add(served);
                        served.completeAttendance(); // simula fim do atendimento (corte + pagamento)
                    }
                    Thread.sleep(2);
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                    System.out.println("  [EXCECAO no barbeiro] " + e);
                }
            }
        });

        // dispara tudo o mais proximo possivel de "ao mesmo tempo"
        fakeBarbeiro.start();
        for (Thread t : clientThreads) {
            t.start();
        }

        for (Thread t : clientThreads) {
            t.join();
        }
        fakeBarbeiro.join();

        boolean problem = exceptionCount.get() > 0;

        long distinctServed = servedClients.stream().distinct().count();
        if (distinctServed != servedClients.size()) {
            System.out.println("  [PROBLEMA] cliente atendido mais de uma vez! ("
                + servedClients.size() + " atendimentos, " + distinctServed + " distintos)");
            problem = true;
        }

        System.out.println("Rodada " + round + ": " + exceptionCount.get() + " excecoes, "
            + servedClients.size() + " atendimentos, " + distinctServed + " distintos"
            + (problem ? "  <-- PROBLEMA" : ""));

        return problem;
    }
}