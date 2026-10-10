package tests;

import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Confere, com seis clientes, a FIFO do sofa e a promocao da fila em pe.
 * Usa os metodos reais da aplicacao; nao depende de JUnit.
 */
public class FifoIntegrationTest {
    public static void main(String[] args) throws Exception {
        Logger.setEnabled(false);
        WaitingRoom room = new WaitingRoom();
        List<Client> clients = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (int i = 1; i <= 6; i++) {
            Client client = new Client("FIFO-" + i, room);
            Thread thread = new Thread(client, "Test-Client-" + i);
            clients.add(client);
            threads.add(thread);
            thread.start();
            awaitCount(room, i, 2000);
        }

        assertState(room.getSeatedCount() == 4, "sofa deveria ter 4 clientes");
        assertState(room.getStandingCount() == 2, "fila em pe deveria ter 2 clientes");

        for (int i = 0; i < 6; i++) {
            Client next = room.getNext();
            assertState(next == clients.get(i), "FIFO violada no cliente " + (i + 1));
            next.completeAttendance();
        }

        for (Thread thread : threads) {
            thread.join(2000);
            assertState(!thread.isAlive(), "thread de cliente ficou presa");
        }
        assertState(room.isEmpty(), "sala deveria terminar vazia");
        for (Client client : clients) {
            assertState(client.isAttendanceCompleted(), "cliente sem conclusao " + client);
            assertState(client.hasEnteredWaitingRoom(), "cliente sem entrada registrada " + client);
        }
        Logger.setEnabled(true);
        System.out.println("Resultado: FIFO sofa/pe, notificacoes, clientes e API compativeis: OK");
    }

    private static void awaitCount(WaitingRoom room, int count, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (room.getTotalWaiting() == count) return;
            Thread.sleep(2);
        }
        throw new AssertionError("falha ao aguardar entrada do cliente " + count);
    }

    private static void assertState(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
