package barbearia.cliente;

public class Client implements Runnable {
    private final String name;
    private final WaitingRoom waitingRoom;

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

    @Override 
    public void run() {
      boolean entrou = enterWaitingRoom(); // ajustar o retorno de enterWaitingRoom() pra devolver o boolean
      if (!entrou) {
          return; // desiste, thread termina aqui
      }
    }

    @Override
    public String toString() {
        return name;
    }
}