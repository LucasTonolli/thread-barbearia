package tests;

import barbearia.barbeiro.Barber;
import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.PosTerminal;
import barbearia.integracao.Capacity;
import barbearia.integracao.AuditLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Teste de carga da integracao final.
 * Executa 20 rodadas, cada uma com 100 tentativas de clientes.
 */
public class IntegrationStressTest {

    private static final int ROUNDS = 20;
    private static final int CLIENTS = 100;
    private static final int CAPACITY = 20;

    public static void main(String[] args) throws Exception {
        AuditLogger.setEnabled(false);

        int failed = 0;
        for (int round = 1; round <= ROUNDS; round++) {
            if (!runRound(round)) {
                failed++;
            }
        }

        AuditLogger.setEnabled(true);
        System.out.println("Resultado: " + (ROUNDS - failed) + "/" + ROUNDS + " rodadas aprovadas.");

        if (failed > 0) {
            throw new AssertionError("Falharam " + failed + " rodadas de estresse.");
        }
    }

    private static boolean runRound(int round) throws Exception {
        WaitingRoom room = new WaitingRoom();
        PosTerminal pos = new PosTerminal();
        Capacity capacity = new Capacity(CAPACITY);

        Barber b1 = new Barber(1, "B1", room, pos);
        Barber b2 = new Barber(2, "B2", room, pos);
        Barber b3 = new Barber(3, "B3", room, pos);

        Thread t1 = new Thread(b1, "B1");
        Thread t2 = new Thread(b2, "B2");
        Thread t3 = new Thread(b3, "B3");
        t1.start();
        t2.start();
        t3.start();

        List<Thread> clients = new ArrayList<>();
        for (int i = 1; i <= CLIENTS; i++) {
            Client c = new Client("R" + round + "-C" + i, room);
            Thread t = new Thread(() -> {
                if (!capacity.tryEnter(c.getName())) {
                    return;
                }
                try {
                    c.run();
                } finally {
                    capacity.leave(c.getName(), c.isAttendanceCompleted());
                }
            });
            clients.add(t);
            t.start();
        }

        for (Thread t : clients) {
            t.join(5000);
            if (t.isAlive()) {
                return false;
            }
        }

        b1.stop();
        b2.stop();
        b3.stop();
        t1.join(2000);
        t2.join(2000);
        t3.join(2000);

        int served = b1.getClientsServed() + b2.getClientsServed() + b3.getClientsServed();

        return !t1.isAlive()
                && !t2.isAlive()
                && !t3.isAlive()
                && capacity.getMaxObserved() <= CAPACITY
                && capacity.getCurrentOccupancy() == 0
                && capacity.availablePermits() == CAPACITY
                && pos.getMaxConcurrentTransactions() <= 1
                && pos.getCompletedTransactions() == served
                && room.isEmpty();
    }
}
