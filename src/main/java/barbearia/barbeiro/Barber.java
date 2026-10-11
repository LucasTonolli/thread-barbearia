package barbearia.barbeiro;

import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.AuditLogger;
import barbearia.integracao.AuditLogger.Event;
import barbearia.integracao.PosTerminal;

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
    private final PosTerminal pos;
    private final AuditLogger logger;

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
            PosTerminal pos,
            long cutDurationMs,
            long paymentDurationMs,
            AuditLogger logger
    ) {
        this.id = id;
        this.name = name;
        this.chair = chair;
        this.waitingRoom = waitingRoom;
        this.pos = pos;
        this.cutDurationMs = cutDurationMs;
        this.paymentDurationMs = paymentDurationMs;
        this.logger = logger;
    }

    public Barber(
            int id,
            String name,
            BarberChair chair,
            WaitingRoom waitingRoom,
            PosTerminal pos,
            long cutDurationMs,
            long paymentDurationMs
    ) {
        this(id, name, chair, waitingRoom, pos, cutDurationMs, paymentDurationMs, AuditLogger.silent());
    }

    public Barber(int id, String name, WaitingRoom waitingRoom, PosTerminal pos) {
        this(id, name, new BarberChair(id, name), waitingRoom, pos, 30L, 15L);
    }

    @Override
    public void run() {
        while (running) {
            Client client = waitForNextClient();

            if (client == null) {
                continue;
            }

            serve(client);
        }

        state = BarberState.FINISHED;
    }

    private Client waitForNextClient() {
        synchronized (waitingRoom) {
            Client client = waitingRoom.getNext(name, chair.getChairId());

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

                client = waitingRoom.getNext(name, chair.getChairId());
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

            state = BarberState.CUTTING_HAIR;
            if (cutDurationMs > 0) {
                Thread.sleep(cutDurationMs);
            }
            totalCuttingTimeMs.addAndGet(cutDurationMs);

            state = BarberState.WAITING_POS;
            logger.log(client.getName(), Event.PAGAMENTO, "Corte conclu\u00eddo. Solicitando m\u00e1quina POS.");
            pos.acquire(name, client.getName());
            try {
                state = BarberState.RECEIVING_PAYMENT;

                if (paymentDurationMs > 0) {
                    Thread.sleep(paymentDurationMs);
                }
                totalPaymentTimeMs.addAndGet(paymentDurationMs);
            } finally {
                pos.release(name, client.getName());
            }

            clientsServed.incrementAndGet();
            client.setAttendedBy(name);
            client.completeAttendance();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            // Evita que a thread do cliente permane\u00e7a bloqueada indefinidamente.
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
