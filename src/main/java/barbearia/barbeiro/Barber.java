package barbearia.barbeiro;

import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.CaixaPOS;
import barbearia.integracao.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread trabalhadora de um barbeiro.
 *
 * Nao existe busy-wait: quando nao ha clientes no sofa, a thread aguarda
 * no monitor da WaitingRoom e somente acorda com notifyAll().
 */
public class Barber implements Runnable {

    private final int id;
    private final String name;
    private final BarberChair chair;
    private final WaitingRoom waitingRoom;
    private final CaixaPOS pos;

    private final long cutDurationMs;
    private final long paymentDurationMs;

    private volatile boolean running = true;
    private volatile boolean stopWhenEmpty = false;
    private volatile BarberState state = BarberState.SLEEPING;

    private final AtomicInteger clientsServed = new AtomicInteger(0);
    private final AtomicLong totalCuttingTimeMs = new AtomicLong(0);
    private final AtomicLong totalPaymentTimeMs = new AtomicLong(0);

    public Barber(
            int id,
            String name,
            BarberChair chair,
            WaitingRoom waitingRoom,
            CaixaPOS pos,
            long cutDurationMs,
            long paymentDurationMs
    ) {
        this.id = id;
        this.name = name;
        this.chair = chair;
        this.waitingRoom = waitingRoom;
        this.pos = pos;
        this.cutDurationMs = cutDurationMs;
        this.paymentDurationMs = paymentDurationMs;
    }

    public Barber(int id, String name, WaitingRoom waitingRoom, CaixaPOS pos) {
        this(id, name, new BarberChair(id, name), waitingRoom, pos, 30L, 15L);
    }

    @Override
    public void run() {
        Logger.log("INICIO", name + " pronto para o expediente na Cadeira " + chair.getChairId());

        while (running) {
            Client client = waitForNextClient();

            if (client == null) {
                continue;
            }

            serve(client);
        }

        state = BarberState.FINISHED;
        Logger.log("FINALIZADO", name + " encerrou o expediente. Atendidos: " + clientsServed.get());
    }

    private Client waitForNextClient() {
        synchronized (waitingRoom) {
            Client client = waitingRoom.getNext();

            while (client == null && running) {
                if (stopWhenEmpty && waitingRoom.isEmpty()) {
                    running = false;
                    return null;
                }

                try {
                    state = BarberState.SLEEPING;
                    waitingRoom.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                    return null;
                }

                client = waitingRoom.getNext();
            }

            return client;
        }
    }

    private void serve(Client client) {
        boolean chairOccupied = false;

        try {
            state = BarberState.CALLING_CLIENT;
            chair.occupy(client);
            chairOccupied = true;
            Logger.log(
                    "ATENDIMENTO",
                    name + " chamou " + client.getName() + " para a Cadeira " + chair.getChairId()
            );

            state = BarberState.CUTTING_HAIR;
            if (cutDurationMs > 0) {
                Thread.sleep(cutDurationMs);
            }
            totalCuttingTimeMs.addAndGet(cutDurationMs);
            Logger.log("CORTE_CONCLUIDO", name + " terminou o corte de " + client.getName());

            state = BarberState.WAITING_POS;
            Logger.log("PAGAMENTO", client.getName() + " aguardando a unica maquina POS");
            pos.acquire(name, client.getName());
            try {
                state = BarberState.RECEIVING_PAYMENT;
                Logger.log("PAGAMENTO", name + " iniciou o pagamento de " + client.getName());

                if (paymentDurationMs > 0) {
                    Thread.sleep(paymentDurationMs);
                }
                totalPaymentTimeMs.addAndGet(paymentDurationMs);

                Logger.log("PAGAMENTO_CONCLUIDO", "Pagamento de " + client.getName() + " concluido");
            } finally {
                pos.release(name, client.getName());
            }

            clientsServed.incrementAndGet();
            client.completeAttendance();
            Logger.log("CONCLUSAO", "Atendimento de " + client.getName() + " finalizado por " + name);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.log("INTERRUPCAO", name + " foi interrompido durante o atendimento de " + client.getName());

            // Evita que a thread do cliente permaneça bloqueada indefinidamente.
            client.completeAttendance();
            running = false;

        } finally {
            if (chairOccupied) {
                chair.release();
            }
        }
    }

    /** Solicita encerramento imediato, acordando a thread caso esteja dormindo. */
    public void stop() {
        running = false;
        synchronized (waitingRoom) {
            waitingRoom.notifyAll();
        }
    }

    /** Solicita encerramento assim que a sala de espera estiver vazia. */
    public void stopWhenEmpty() {
        stopWhenEmpty = true;
        synchronized (waitingRoom) {
            waitingRoom.notifyAll();
        }
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BarberChair getChair() {
        return chair;
    }

    public BarberState getState() {
        return state;
    }

    public int getClientsServed() {
        return clientsServed.get();
    }

    public long getTotalCuttingTimeMs() {
        return totalCuttingTimeMs.get();
    }

    public long getTotalPaymentTimeMs() {
        return totalPaymentTimeMs.get();
    }

    public boolean isRunning() {
        return running;
    }
}
