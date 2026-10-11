package barbearia;

import barbearia.barbeiro.Barber;
import barbearia.barbeiro.BarberChair;
import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.PosTerminal;
import barbearia.integracao.Capacity;
import barbearia.integracao.AuditLogger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Integracao final da simulacao do Problema da Barbearia de Hilzer.
 */
public class Main {

    private static final int CLIENT_COUNT = 50;
    private static final int MAX_CAPACITY = 20;

    public static void main(String[] args) {
        try (AuditLogger logger = new AuditLogger(Path.of("logs", "execucao-B.log"), true, false)) {
            run(logger);
        }
    }

    private static void run(AuditLogger logger) {
        System.out.println("======================================");
        System.out.println("   SIMULACAO DA BARBEARIA DE HILZER");
        System.out.println("======================================");

        WaitingRoom waitingRoom = new WaitingRoom(logger);
        PosTerminal pos = new PosTerminal(logger);
        Capacity capacity = new Capacity(MAX_CAPACITY, logger);

        Barber barber1 = new Barber(1, "Barbeiro-01", new BarberChair(1, "Barbeiro-01"), waitingRoom, pos, 30L, 15L, logger);
        Barber barber2 = new Barber(2, "Barbeiro-02", new BarberChair(2, "Barbeiro-02"), waitingRoom, pos, 30L, 15L, logger);
        Barber barber3 = new Barber(3, "Barbeiro-03", new BarberChair(3, "Barbeiro-03"), waitingRoom, pos, 30L, 15L, logger);

        Thread barberThread1 = new Thread(barber1, "Thread-Barbeiro-01");
        Thread barberThread2 = new Thread(barber2, "Thread-Barbeiro-02");
        Thread barberThread3 = new Thread(barber3, "Thread-Barbeiro-03");

        barberThread1.start();
        barberThread2.start();
        barberThread3.start();

        List<Thread> clientThreads = new ArrayList<>();

        for (int i = 1; i <= CLIENT_COUNT; i++) {
            String clientName = "Cliente-" + String.format("%02d", i);
            Client client = new Client(clientName, waitingRoom, capacity, logger);
            Thread clientThread = new Thread(client, "Thread-" + clientName);

            clientThreads.add(clientThread);
            clientThread.start();
        }

        joinAll(clientThreads);

        barber1.stop();
        barber2.stop();
        barber3.stop();

        joinThread(barberThread1);
        joinThread(barberThread2);
        joinThread(barberThread3);

        int totalServed = barber1.getClientsServed()
                + barber2.getClientsServed()
                + barber3.getClientsServed();

        printSummary(waitingRoom, pos, capacity, barber1, barber2, barber3, totalServed);
        validateInvariants(waitingRoom, pos, capacity, totalServed);
    }

    private static void joinAll(List<Thread> threads) {
        for (Thread thread : threads) {
            joinThread(thread);
        }
    }

    private static void joinThread(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Thread principal interrompida durante join().", e);
        }
    }

    private static void printSummary(
            WaitingRoom waitingRoom,
            PosTerminal pos,
            Capacity capacity,
            Barber b1,
            Barber b2,
            Barber b3,
            int totalServed
    ) {
        System.out.println();
        System.out.println("======================================");
        System.out.println("          RESUMO DA SIMULACAO");
        System.out.println("======================================");
        System.out.println("Clientes tentados: " + CLIENT_COUNT);
        System.out.println("Entradas aceitas pelo controle de capacidade: " + capacity.getAdmittedCount());
        System.out.println("Desistencias por lotacao total: " + capacity.getRejectedCount());
        System.out.println("Desistencias por sala de espera cheia: " + waitingRoom.getRejectedCount());
        System.out.println("Clientes atendidos: " + totalServed);
        System.out.println("  - " + b1.getName() + ": " + b1.getClientsServed());
        System.out.println("  - " + b2.getName() + ": " + b2.getClientsServed());
        System.out.println("  - " + b3.getName() + ": " + b3.getClientsServed());
        System.out.println("Transacoes concluidas na POS: " + pos.getCompletedTransactions());
        System.out.println("Maior concorrencia observada na POS: " + pos.getMaxConcurrentTransactions());
        System.out.println("Lotacao maxima observada: " + capacity.getMaxObserved() + "/" + MAX_CAPACITY);
        System.out.println("Vagas disponiveis ao final: " + capacity.availablePermits());
        System.out.println("Sala de espera vazia ao final: " + waitingRoom.isEmpty());
        System.out.println("======================================");
    }

    private static void validateInvariants(
            WaitingRoom waitingRoom,
            PosTerminal pos,
            Capacity capacity,
            int totalServed
    ) {
        if (capacity.getMaxObserved() > MAX_CAPACITY) {
            throw new IllegalStateException("A capacidade maxima foi ultrapassada.");
        }

        if (capacity.availablePermits() != MAX_CAPACITY || capacity.getCurrentOccupancy() != 0) {
            throw new IllegalStateException("Nem todas as vagas foram devolvidas ao final da simulacao.");
        }

        if (!waitingRoom.isEmpty()) {
            throw new IllegalStateException("A sala de espera nao terminou vazia.");
        }

        if (pos.getMaxConcurrentTransactions() > 1) {
            throw new IllegalStateException("Mais de um pagamento ocorreu simultaneamente na POS.");
        }

        if (pos.getCompletedTransactions() != totalServed) {
            throw new IllegalStateException("Quantidade de pagamentos diferente da quantidade de atendimentos.");
        }

        System.out.println("VALIDACAO FINAL: todas as invariantes foram respeitadas.");
    }
}
