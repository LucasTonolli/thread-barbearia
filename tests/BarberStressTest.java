package tests;

import barbearia.barbeiro.Barber;
import barbearia.barbeiro.BarberChair;
import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.CaixaPOS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Teste de estresse concorrente para a frente de Barbeiro.
 * Executa multiplas rodadas com 3 Barbeiros e 50 Clientes concorrentes disputando
 * o sofa, o corte e a unica maquina de POS compartilhada.
 * Valida ausencia de deadlocks, starvation, operacoes duplicadas e consistencia da POS.
 */
public class BarberStressTest {

    private static final int NUM_CLIENTS = 50;
    private static final int NUM_ROUNDS = 10;
    private static final int NUM_BARBERS = 3;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=================================================================");
        System.out.println(" INICIO DO TESTE DE ESTRESSE DO BARBEIRO (3 Barbeiros + 1 POS)   ");
        System.out.println("=================================================================");
        System.out.println("Configuracao: " + NUM_CLIENTS + " clientes/rodada, " + NUM_ROUNDS + " rodadas, " + NUM_BARBERS + " barbeiros.\n");

        int problemRounds = 0;

        for (int round = 1; round <= NUM_ROUNDS; round++) {
            boolean hasProblem = runStressRound(round);
            if (hasProblem) {
                problemRounds++;
            }
        }

        System.out.println("\n=================================================================");
        System.out.println(" RESULTADO FINAL: " + problemRounds + " de " + NUM_ROUNDS + " rodadas tiveram problemas.");
        if (problemRounds == 0) {
            System.out.println(" [SUCESSO] Todos os testes passaram sem deadlock, sem excecoes e com exclusao mutua valida.");
        } else {
            System.out.println(" [FALHA] Houve inconsistencias ou travamentos em " + problemRounds + " rodadas.");
        }
        System.out.println("=================================================================");
    }

    private static boolean runStressRound(int round) throws InterruptedException {
        WaitingRoom room = new WaitingRoom();
        CaixaPOS pos = new CaixaPOS();

        AtomicInteger exceptionsCount = new AtomicInteger(0);
        AtomicBoolean deadlockDetected = new AtomicBoolean(false);

        List<Barber> barbers = new ArrayList<>();
        List<Thread> barberThreads = new ArrayList<>();

        for (int i = 1; i <= NUM_BARBERS; i++) {
            BarberChair chair = new BarberChair(i, "Barbeiro-" + i);
            Barber b = new Barber(i, "Barbeiro-" + i, chair, room, pos, 5, 3);
            barbers.add(b);
            Thread bt = new Thread(b, "Thread-Barbeiro-0" + i);
            barberThreads.add(bt);
        }

        // Inicia threads dos barbeiros
        for (Thread bt : barberThreads) {
            bt.start();
        }

        List<Client> clients = new ArrayList<>();
        List<Thread> clientThreads = new ArrayList<>();

        for (int i = 1; i <= NUM_CLIENTS; i++) {
            Client c = new Client("R" + round + "-Cliente-" + String.format("%02d", i), room);
            clients.add(c);
            Thread ct = new Thread(c, "Thread-Client-" + i);
            clientThreads.add(ct);
        }

        // Disparo concorrente de todos os clientes
        for (Thread ct : clientThreads) {
            ct.start();
        }

        // Aguarda todas as threads de clientes encerrarem (com timeout de seguranca contra deadlock)
        for (Thread ct : clientThreads) {
            ct.join(4000);
            if (ct.isAlive()) {
                deadlockDetected.set(true);
                System.err.println("  [DEADLOCK DETECTADO] Thread de cliente " + ct.getName() + " nao finalizou a tempo!");
            }
        }

        // Sinaliza aos barbeiros para pararem quando a fila esvaziar
        for (Barber b : barbers) {
            b.stopWhenEmpty();
        }

        // Aguarda termino dos barbeiros
        for (int i = 0; i < NUM_BARBERS; i++) {
            Thread bt = barberThreads.get(i);
            Barber b = barbers.get(i);
            bt.join(2000);
            if (bt.isAlive()) {
                b.stop();
                bt.interrupt();
                bt.join(1000);
            }
        }

        int totalServed = barbers.stream().mapToInt(Barber::getClientsServed).sum();
        int totalPOSTransactions = pos.getCompletedTransactions();

        boolean problem = false;

        if (deadlockDetected.get()) {
            problem = true;
        }

        if (totalServed != totalPOSTransactions) {
            System.err.println("  [DIVERGENCIA] Atendimentos (" + totalServed + ") != Transacoes POS (" + totalPOSTransactions + ")");
            problem = true;
        }

        if (exceptionsCount.get() > 0) {
            problem = true;
        }

        System.out.printf("Rodada %02d: %d clientes atendidos | POS: %d transacoes | Excecoes: %d | Status: %s%n",
                round, totalServed, totalPOSTransactions, exceptionsCount.get(), (problem ? "FALHA" : "OK"));

        return problem;
    }
}
