package barbearia.barbeiro;

import barbearia.cliente.Client;
import barbearia.cliente.WaitingRoom;
import barbearia.integracao.CaixaPOS;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Representa a thread de um Barbeiro na Barbearia de Ralph Hilzer.
 * Cada barbeiro possui sua propria cadeira, atende clientes em ordem FIFO do sofa,
 * simula o corte de cabelo e disputa exclusivamente a maquina POS para receber pagamentos.
 * Dorme de forma cooperativa (sem busy-wait) quando a sala de espera esta vazia.
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

    public Barber(int id, String name, BarberChair chair, WaitingRoom waitingRoom, CaixaPOS pos, long cutDurationMs, long paymentDurationMs) {
        this.id = id;
        this.name = name;
        this.chair = chair;
        this.waitingRoom = waitingRoom;
        this.pos = pos;
        this.cutDurationMs = cutDurationMs;
        this.paymentDurationMs = paymentDurationMs;
    }

    public Barber(int id, String name, WaitingRoom waitingRoom, CaixaPOS pos) {
        this(id, name, new BarberChair(id, name), waitingRoom, pos, 30, 15);
    }

    @Override
    public void run() {
        System.out.println("[" + name + "] INICIO: Barbeiro pronto para o expediente na Cadeira " + chair.getChairId() + ".");

        while (running) {
            Client client = null;

            // Tentativa de obter o proximo cliente sem busy-wait
            synchronized (waitingRoom) {
                client = waitingRoom.getNext();
                while (client == null && running) {
                    if (stopWhenEmpty && waitingRoom.isEmpty()) {
                        running = false;
                        break;
                    }
                    try {
                        this.state = BarberState.SLEEPING;
                        System.out.println("[" + name + "] DORMINDO: Nenhum cliente no sofa. Barbeiro dormindo...");
                        waitingRoom.wait(); // Bloqueio real (sem busy-wait)
                    } catch (InterruptedException e) {
                        if (!running) {
                            break;
                        }
                    }
                    client = waitingRoom.getNext();
                }
            }

            if (client == null) {
                continue;
            }

            // 1. Chamou o cliente para a cadeira
            this.state = BarberState.CALLING_CLIENT;
            chair.occupy(client);
            System.out.println("[" + name + "] ATENDIMENTO: Chamou " + client.getName() + " do sofa para a Cadeira " + chair.getChairId() + ".");

            // 2. Simula o corte de cabelo
            this.state = BarberState.CUTTING_HAIR;
            try {
                if (cutDurationMs > 0) {
                    Thread.sleep(cutDurationMs);
                }
                totalCuttingTimeMs.addAndGet(cutDurationMs);
                System.out.println("[" + name + "] CORTE_CONCLUIDO: Finalizou corte de " + client.getName() + ". Solicitando maquina POS.");

                // 3. Disputa a maquina POS para pagamento (exclusao mutua)
                this.state = BarberState.WAITING_POS;
                pos.acquire(name, client.getName());
                try {
                    this.state = BarberState.RECEIVING_PAYMENT;
                    System.out.println("[" + name + "] PAGAMENTO: Iniciou recebimento de " + client.getName() + " na maquina POS.");
                    if (paymentDurationMs > 0) {
                        Thread.sleep(paymentDurationMs);
                    }
                    totalPaymentTimeMs.addAndGet(paymentDurationMs);
                    System.out.println("[" + name + "] PAGAMENTO_CONCLUIDO: Pagamento de " + client.getName() + " processado com sucesso.");
                } finally {
                    pos.release(name, client.getName());
                }

                // 4. Conclusao do atendimento e liberacao da thread do cliente
                clientsServed.incrementAndGet();
                chair.release();
                client.completeAttendance();
                System.out.println("[" + name + "] CONCLUSAO: Atendimento completo de " + client.getName() + " finalizado.");

            } catch (InterruptedException e) {
                System.out.println("[" + name + "] AVISO: Barbeiro interrompido durante atendimento de " + client.getName() + ".");
                // Garante que o cliente nao fique travado em caso de interrupcao
                clientsServed.incrementAndGet();
                chair.release();
                client.completeAttendance();
                Thread.currentThread().interrupt();
                break;
            }
        }

        this.state = BarberState.FINISHED;
        System.out.println("[" + name + "] FINALIZADO: Expediente encerrado. Total de clientes atendidos: " + clientsServed.get() + ".");
    }

    /**
     * Solicita o encerramento da thread do barbeiro.
     */
    public void stop() {
        this.running = false;
        synchronized (waitingRoom) {
            waitingRoom.notifyAll();
        }
    }

    /**
     * Solicita encerramento assim que a sala de espera estiver completamente vazia.
     */
    public void stopWhenEmpty() {
        this.stopWhenEmpty = true;
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
