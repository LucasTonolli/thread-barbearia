package tests;

import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;

import java.util.ArrayList;
import java.util.List;

/**
 * Casos manuais da WaitingRoom usando Clients reais (cada Client.run bloqueia em enter()
 * ate ser chamado, por isso cada um roda em sua propria thread).
 */
public class WaitingRoomManualTest {

    private static final long TIMEOUT_MS = 2000;

    static int passed = 0;
    static int failed = 0;

    static void check(String description, boolean condition) {
        if (condition) {
            System.out.println("  [OK] " + description);
            passed++;
        } else {
            System.out.println("  [FALHOU] " + description);
            failed++;
        }
    }

    /**
     * Sobe um cliente e espera, no monitor da propria sala, ate ele estar enfileirado.
     * enter() faz notifyAll() ao enfileirar, entao nao ha polling.
     */
    static Thread arrive(WaitingRoom room, Client c, int expectedWaiting) throws InterruptedException {
        Thread t = new Thread(c, "Thread-" + c.getName());
        t.start();
        awaitWaiting(room, expectedWaiting);
        return t;
    }

    static void awaitWaiting(WaitingRoom room, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        synchronized (room) {
            while (room.getTotalWaiting() != expected) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new AssertionError("sala nao chegou a " + expected + " clientes (tem "
                            + room.getTotalWaiting() + ")");
                }
                room.wait(remaining);
            }
        }
    }

    static void drain(WaitingRoom room, List<Thread> threads) throws InterruptedException {
        Client c;
        while ((c = room.getNext()) != null) {
            c.completeAttendance();
        }
        for (Thread t : threads) {
            t.join(TIMEOUT_MS);
        }
        boolean allDone = threads.stream().noneMatch(Thread::isAlive);
        check("todas as threads de cliente terminaram", allDone);
    }

    public static void main(String[] args) throws InterruptedException {

        System.out.println("=== Caso 1: sofa enche, 1 vai pra fila em pe, getNext() promove ===");
        WaitingRoom room1 = new WaitingRoom();
        List<Thread> threads1 = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            threads1.add(arrive(room1, new Client("C" + i, room1), i));
        }
        check("4 no sofa e 1 em pe", room1.getSeatedCount() == 4 && room1.getStandingCount() == 1);
        Client served1 = room1.getNext();
        check("getNext() retornou C1 (primeiro do sofa)", served1 != null && "C1".equals(served1.getName()));
        check("C5 foi promovido: sofa 4, em pe 0", room1.getSeatedCount() == 4 && room1.getStandingCount() == 0);
        served1.completeAttendance();
        drain(room1, threads1);

        System.out.println("=== Caso 2: sofa com gente, fila em pe vazia, getNext() ===");
        WaitingRoom room2 = new WaitingRoom();
        List<Thread> threads2 = new ArrayList<>();
        threads2.add(arrive(room2, new Client("A1", room2), 1));
        threads2.add(arrive(room2, new Client("A2", room2), 2));
        Client served2 = room2.getNext();
        check("getNext() retornou A1", served2 != null && "A1".equals(served2.getName()));
        check("sobra 1 no sofa", room2.getSeatedCount() == 1);
        served2.completeAttendance();
        drain(room2, threads2);

        System.out.println("=== Caso 3: sofa vazio, getNext() ===");
        check("getNext() com sofa vazio retorna null", new WaitingRoom().getNext() == null);

        System.out.println("=== Caso 4: encher tudo (4 + 13 = 17), 18o nao entra ===");
        WaitingRoom room4 = new WaitingRoom();
        List<Thread> threads4 = new ArrayList<>();
        for (int i = 1; i <= 17; i++) {
            threads4.add(arrive(room4, new Client("Cliente" + i, room4), i));
        }
        check("sala com 4 sentados e 13 em pe", room4.getSeatedCount() == 4 && room4.getStandingCount() == 13);
        boolean accepted18 = room4.enter(new Client("Cliente18", room4)); // nao bloqueia: sala cheia
        check("18o cliente e rejeitado (sala cheia)", !accepted18);
        check("rejeicao contabilizada", room4.getRejectedCount() == 1);
        drain(room4, threads4);

        System.out.println("=== Caso 5: drenar tudo e conferir ordem FIFO ===");
        WaitingRoom room5 = new WaitingRoom();
        List<Thread> threads5 = new ArrayList<>();
        for (int i = 1; i <= 17; i++) {
            threads5.add(arrive(room5, new Client("Cliente" + i, room5), i));
        }
        boolean fifoOk = true;
        for (int i = 1; i <= 17; i++) {
            Client served = room5.getNext();
            if (served == null || !("Cliente" + i).equals(served.getName())) {
                fifoOk = false;
                System.out.println("      esperado Cliente" + i + " mas veio " + served);
            }
            if (served != null) {
                served.completeAttendance();
            }
        }
        check("ordem de atendimento bate com ordem de chegada (FIFO)", fifoOk);
        check("depois de drenar tudo, getNext() volta a retornar null", room5.getNext() == null);
        drain(room5, threads5);

        System.out.println("=== Caso 6: cliente do sofa interrompido -> promove o primeiro em pe ===");
        WaitingRoom room6 = new WaitingRoom();
        List<Thread> threads6 = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            threads6.add(arrive(room6, new Client("D" + i, room6), i));
        }
        threads6.get(1).interrupt(); // D2 desiste (esta no sofa)
        threads6.get(1).join(TIMEOUT_MS);
        check("D2 saiu e D5 foi promovido: sofa 4, em pe 0",
                room6.getSeatedCount() == 4 && room6.getStandingCount() == 0);
        Client first = room6.getNext();
        Client second = room6.getNext();
        check("ordem preservada apos desistencia: D1, D3", first != null && second != null
                && "D1".equals(first.getName()) && "D3".equals(second.getName()));
        first.completeAttendance();
        second.completeAttendance();
        drain(room6, threads6);

        System.out.println();
        System.out.println("=== Resultado: " + passed + " passaram, " + failed + " falharam ===");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
