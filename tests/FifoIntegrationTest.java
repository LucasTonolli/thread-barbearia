package tests;

import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * FIFO do sofa e da promocao em pe -> sofa.
 *
 *  - Caso controlado: 6 clientes entram um a um; a ordem de chamada e a ordem de chegada.
 *  - Caso concorrente: 17 clientes sao liberados ao mesmo tempo por um CountDownLatch; a ordem
 *    de chegada e a que o monitor da sala decidiu (enqueueOrder), e a ordem de chamada tem de
 *    segui-la, assim como a promocao tem de seguir a ordem da fila em pe.
 *
 * Nao ha polling: o teste espera no proprio monitor da sala, que enter() notifica (notifyAll)
 * a cada cliente enfileirado.
 */
public class FifoIntegrationTest {

    private static final long TIMEOUT_MS = 2000;

    public static void main(String[] args) throws Exception {
        controlledArrivals();
        concurrentArrivals();
        System.out.println("Resultado: FIFO sofa/pe (controlado e concorrente), notificacoes e API: OK");
    }

    private static void controlledArrivals() throws Exception {
        WaitingRoom room = new WaitingRoom();
        List<Client> clients = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (int i = 1; i <= 6; i++) {
            Client client = new Client("FIFO-" + i, room);
            Thread thread = new Thread(client, "Test-Client-" + i);
            clients.add(client);
            threads.add(thread);
            thread.start();
            awaitWaiting(room, i);
        }

        assertState(room.getSeatedCount() == 4, "sofa deveria ter 4 clientes, tem " + room.getSeatedCount());
        assertState(room.getStandingCount() == 2, "fila em pe deveria ter 2 clientes, tem " + room.getStandingCount());

        for (int i = 0; i < 6; i++) {
            Client next = room.getNext();
            assertState(next == clients.get(i), "FIFO violada: esperado " + clients.get(i) + ", veio " + next);
            next.completeAttendance();
        }

        joinAll(threads);
        assertState(room.isEmpty(), "sala deveria terminar vazia: " + room.describe());
        for (Client client : clients) {
            assertState(client.isAttendanceCompleted(), "cliente sem conclusao " + client);
            assertState(client.hasEnteredWaitingRoom(), "cliente sem entrada registrada " + client);
        }
        System.out.println("Caso controlado (6 clientes, um a um): OK");
    }

    private static void concurrentArrivals() throws Exception {
        int total = WaitingRoom.MAX_SEATED_CLIENTS + WaitingRoom.MAX_STANDING_CLIENTS; // 17
        WaitingRoom room = new WaitingRoom();
        CountDownLatch startGate = new CountDownLatch(1);
        List<Client> clients = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            Client client = new Client("CONC-" + i, room);
            Thread thread = new Thread(() -> {
                try {
                    startGate.await();
                    client.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Test-Conc-" + i);
            clients.add(client);
            threads.add(thread);
            thread.start();
        }
        startGate.countDown(); // todos disputam enter() ao mesmo tempo
        awaitWaiting(room, total);

        List<Client> byArrival = clients.stream()
                .sorted(Comparator.comparingLong(Client::getEnqueueOrder)).collect(Collectors.toList());
        List<Client> standing = byArrival.subList(WaitingRoom.MAX_SEATED_CLIENTS, total);
        assertState(standing.stream().allMatch(c -> c.getStandingOrder() > 0),
                "os ultimos 13 a entrar deveriam estar em pe");

        // Drena: a cada getNext o primeiro do sofa sai e o primeiro em pe e promovido.
        for (int i = 0; i < total; i++) {
            Client next = room.getNext();
            assertState(next == byArrival.get(i), "FIFO violada na chamada " + (i + 1) + ": esperado "
                    + byArrival.get(i) + " (entrada " + byArrival.get(i).getEnqueueOrder() + "), veio " + next
                    + " (entrada " + (next == null ? "-" : next.getEnqueueOrder()) + ")");
            next.completeAttendance();
        }
        joinAll(threads);

        for (int i = 1; i < standing.size(); i++) {
            Client before = standing.get(i - 1);
            Client after = standing.get(i);
            assertState(before.getSofaOrder() < after.getSofaOrder(), "promocao fora de ordem: " + before
                    + " (sofa " + before.getSofaOrder() + ") deveria subir antes de " + after
                    + " (sofa " + after.getSofaOrder() + ")");
        }
        assertState(room.isEmpty(), "sala deveria terminar vazia: " + room.describe());
        System.out.println("Caso concorrente (17 clientes liberados juntos): ordem de chamada == ordem de entrada,"
                + " promocao na ordem da fila em pe: OK");
    }

    /** Espera, no monitor da sala, ate haver 'expected' clientes; enter() faz notifyAll ao enfileirar. */
    private static void awaitWaiting(WaitingRoom room, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        synchronized (room) {
            while (room.getTotalWaiting() != expected) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new AssertionError("falha ao aguardar " + expected + " clientes na sala (ha "
                            + room.getTotalWaiting() + ")");
                }
                room.wait(remaining);
            }
        }
    }

    private static void joinAll(List<Thread> threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(TIMEOUT_MS);
            assertState(!thread.isAlive(), "thread de cliente ficou presa: " + thread.getName());
        }
    }

    private static void assertState(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
