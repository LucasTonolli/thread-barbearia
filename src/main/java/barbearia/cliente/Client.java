package barbearia.cliente;

public class Client implements Runnable {
    private final String name;
    private final WaitingRoom waitingRoom;
    private volatile boolean iAmNext = false; // Indica se este cliente é o próximo a ser atendido
    private boolean completedAttendance = false; // Indica se este cliente completou o atendimento
    public Client(String name, WaitingRoom waitingRoom) {
        super();
        this.name = name;
        this.waitingRoom = waitingRoom;
    }

    public String getName() {
        return name;
    }

    public boolean enterWaitingRoom() {
        boolean entered = this.waitingRoom.enter(this);
        if (entered) {
            System.out.println(name + " has entered the waiting room.");
        } else {
            System.out.println(name + " could not enter the waiting room.");
        }
        return entered;
    }

    public void iAmTheNext() {
        this.iAmNext = true;
    }

    public boolean isTheNext() {
        return iAmNext;
    }

    public synchronized void completeAttendance() {
        this.completedAttendance = true;
        this.notifyAll();
    }

    public synchronized void waitUntilAttended() {
        while (!this.completedAttendance) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override 
    public void run() {
      boolean entrou = enterWaitingRoom();
      if (!entrou) {
          return; // desiste, thread termina aqui
      }

      waitUntilAttended();
    }

    @Override
    public String toString() {
        return name;
    }


}