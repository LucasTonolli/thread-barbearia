package barbearia;

import barbearia.barbeiro.Barbeiro;
import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.CaixaPOS;
import barbearia.integracao.CapacityControl;
import barbearia.integracao.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Integracao final da simulacao do Problema da Barbearia de Hilzer.
 */
public class Main {

    private static final int NUMERO_DE_CLIENTES = 50;
    private static final int CAPACIDADE_MAXIMA = 20;

    public static void main(String[] args) {
        Logger.reset();

        System.out.println("======================================");
        System.out.println("   SIMULACAO DA BARBEARIA DE HILZER");
        System.out.println("======================================");

        WaitingRoom waitingRoom = new WaitingRoom();
        CaixaPOS pos = new CaixaPOS();
        CapacityControl capacity = new CapacityControl(CAPACIDADE_MAXIMA);

        Barbeiro barbeiro1 = new Barbeiro(1, "Barbeiro-01", waitingRoom, pos);
        Barbeiro barbeiro2 = new Barbeiro(2, "Barbeiro-02", waitingRoom, pos);
        Barbeiro barbeiro3 = new Barbeiro(3, "Barbeiro-03", waitingRoom, pos);

        Thread threadBarbeiro1 = new Thread(barbeiro1, "Thread-Barbeiro-01");
        Thread threadBarbeiro2 = new Thread(barbeiro2, "Thread-Barbeiro-02");
        Thread threadBarbeiro3 = new Thread(barbeiro3, "Thread-Barbeiro-03");

        threadBarbeiro1.start();
        threadBarbeiro2.start();
        threadBarbeiro3.start();

        List<Thread> clientThreads = new ArrayList<>();

        for (int i = 1; i <= NUMERO_DE_CLIENTES; i++) {
            String clientName = "Cliente-" + String.format("%02d", i);
            Client client = new Client(clientName, waitingRoom);

            Thread clientThread = new Thread(() -> {
                if (!capacity.tryEnter(client.getName())) {
                    return;
                }

                try {
                    client.run();
                } finally {
                    capacity.leave(client.getName(), client.isAttendanceCompleted());
                }
            }, "Thread-" + clientName);

            clientThreads.add(clientThread);
            clientThread.start();
        }

        joinAll(clientThreads);

        barbeiro1.stop();
        barbeiro2.stop();
        barbeiro3.stop();

        joinThread(threadBarbeiro1);
        joinThread(threadBarbeiro2);
        joinThread(threadBarbeiro3);

        int totalServed = barbeiro1.getClientsServed()
                + barbeiro2.getClientsServed()
                + barbeiro3.getClientsServed();

        printSummary(waitingRoom, pos, capacity, barbeiro1, barbeiro2, barbeiro3, totalServed);
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
            CaixaPOS pos,
            CapacityControl capacity,
            Barbeiro b1,
            Barbeiro b2,
            Barbeiro b3,
            int totalServed
    ) {
        System.out.println();
        System.out.println("======================================");
        System.out.println("          RESUMO DA SIMULACAO");
        System.out.println("======================================");
        System.out.println("Clientes tentados: " + NUMERO_DE_CLIENTES);
        System.out.println("Entradas aceitas pelo controle de capacidade: " + capacity.getAdmittedCount());
        System.out.println("Desistencias por lotacao total: " + capacity.getRejectedCount());
        System.out.println("Desistencias por sala de espera cheia: " + waitingRoom.getRejectedCount());
        System.out.println("Clientes atendidos: " + totalServed);
        System.out.println("  - " + b1.getName() + ": " + b1.getClientsServed());
        System.out.println("  - " + b2.getName() + ": " + b2.getClientsServed());
        System.out.println("  - " + b3.getName() + ": " + b3.getClientsServed());
        System.out.println("Transacoes concluidas na POS: " + pos.getCompletedTransactions());
        System.out.println("Maior concorrencia observada na POS: " + pos.getMaxConcurrentTransactions());
        System.out.println("Lotacao maxima observada: " + capacity.getMaxObserved() + "/" + CAPACIDADE_MAXIMA);
        System.out.println("Vagas disponiveis ao final: " + capacity.availablePermits());
        System.out.println("Sala de espera vazia ao final: " + waitingRoom.isEmpty());
        System.out.println("======================================");
    }

    private static void validateInvariants(
            WaitingRoom waitingRoom,
            CaixaPOS pos,
            CapacityControl capacity,
            int totalServed
    ) {
        if (capacity.getMaxObserved() > CAPACIDADE_MAXIMA) {
            throw new IllegalStateException("A capacidade maxima foi ultrapassada.");
        }

        if (capacity.availablePermits() != CAPACIDADE_MAXIMA || capacity.getCurrentOccupancy() != 0) {
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
