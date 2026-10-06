package tests;

import barbearia.barbeiro.Barber;
import barbearia.barbeiro.BarberChair;
import barbearia.barbeiro.BarberState;
import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.CaixaPOS;

import java.util.concurrent.atomic.AtomicBoolean;

public class BarberManualTest {

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
        System.out.println("=== INICIO DOS TESTES MANUAIS DO BARBEIRO ===");

        testSingleClientSingleBarber();
        testThreeBarbersAndChairs();
        testPOSMutualExclusion();
        testBarberSleepAndWakeup();

        System.out.println();
        System.out.println("=== RESULTADO: " + passed + " passaram, " + failed + " falharam ===");
    }

    private static void testSingleClientSingleBarber() throws InterruptedException {
        System.out.println("\n--- Caso 1: 1 Barbeiro atende 1 Cliente completo ---");
        WaitingRoom room = new WaitingRoom();
        CaixaPOS pos = new CaixaPOS();
        BarberChair chair = new BarberChair(1, "Barbeiro-01");
        Barber barber = new Barber(1, "Barbeiro-01", chair, room, pos, 20, 10);

        Thread barberThread = new Thread(barber);
        barberThread.start();

        Client client = new Client("Cliente-01", room);
        Thread clientThread = new Thread(client);
        clientThread.start();

        clientThread.join(2000);
        check("Thread do cliente terminou (atendimento completo)", !clientThread.isAlive());
        check("Barbeiro atendeu exatamente 1 cliente", barber.getClientsServed() == 1);
        check("Cadeira voltou a ficar livre", !chair.isOccupied());
        check("Transacoes no CaixaPOS registradas: 1", pos.getCompletedTransactions() == 1);

        barber.stop();
        barberThread.join(1000);
    }

    private static void testThreeBarbersAndChairs() throws InterruptedException {
        System.out.println("\n--- Caso 2: 3 Barbeiros e 3 Cadeiras atendendo 4 clientes ---");
        WaitingRoom room = new WaitingRoom();
        CaixaPOS pos = new CaixaPOS();

        Barber b1 = new Barber(1, "Barbeiro-01", room, pos);
        Barber b2 = new Barber(2, "Barbeiro-02", room, pos);
        Barber b3 = new Barber(3, "Barbeiro-03", room, pos);

        Thread t1 = new Thread(b1);
        Thread t2 = new Thread(b2);
        Thread t3 = new Thread(b3);

        t1.start();
        t2.start();
        t3.start();

        Thread[] clientThreads = new Thread[4];
        for (int i = 0; i < 4; i++) {
            Client c = new Client("Cliente-Multi-" + (i + 1), room);
            clientThreads[i] = new Thread(c);
            clientThreads[i].start();
        }

        for (Thread ct : clientThreads) {
            ct.join(3000);
            check("Cliente terminou execucao sem travamento", !ct.isAlive());
        }

        int totalServed = b1.getClientsServed() + b2.getClientsServed() + b3.getClientsServed();
        check("Total de clientes atendidos pelos 3 barbeiros e 4", totalServed == 4);

        b1.stop();
        b2.stop();
        b3.stop();
        t1.join(1000);
        t2.join(1000);
        t3.join(1000);
    }

    private static void testPOSMutualExclusion() throws InterruptedException {
        System.out.println("\n--- Caso 3: Validacao de Exclusao Mutua no CaixaPOS ---");
        CaixaPOS pos = new CaixaPOS();
        AtomicBoolean concurrentViolation = new AtomicBoolean(false);

        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            final int id = i + 1;
            threads[i] = new Thread(() -> {
                try {
                    pos.acquire("Barbeiro-" + id, "Cliente-" + id);
                    if (pos.getQueueLength() > 0 && !pos.isBusy()) {
                        concurrentViolation.set(true);
                    }
                    Thread.sleep(15);
                    pos.release("Barbeiro-" + id, "Cliente-" + id);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join(2000);
        }

        check("Zero violacoes de exclusao mutua no CaixaPOS", !concurrentViolation.get());
        check("Total de 5 transacoes realizadas no CaixaPOS", pos.getCompletedTransactions() == 5);
    }

    private static void testBarberSleepAndWakeup() throws InterruptedException {
        System.out.println("\n--- Caso 4: Barbeiro dorme sem busy-wait e acorda quando chega cliente ---");
        WaitingRoom room = new WaitingRoom();
        CaixaPOS pos = new CaixaPOS();
        Barber barber = new Barber(1, "Barbeiro-Sono", room, pos);

        Thread barberThread = new Thread(barber);
        barberThread.start();

        Thread.sleep(100); // Dá tempo para o barbeiro verificar a sala vazia e dormir
        check("Barbeiro entrou em estado de sono", barber.getState() == BarberState.SLEEPING);

        Client client = new Client("Cliente-Acordador", room);
        Thread clientThread = new Thread(client);
        clientThread.start();

        clientThread.join(2000);
        check("Barbeiro acordou e atendeu o cliente", barber.getClientsServed() == 1);

        barber.stop();
        barberThread.join(1000);
    }
}
