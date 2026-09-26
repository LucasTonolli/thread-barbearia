package barbearia.cliente;
import java.util.ArrayDeque;
import java.util.Queue;

public class WaitingRoom{
  private final Queue<Client> standingClients = new ArrayDeque<>();
  private final Queue<Client> seatedClients = new ArrayDeque<>();
  // Máximo é 20 clientes , porém 3 estão sendo atendidos, então 17 clientes podem estar na sala de espera  
  private static final int MAX_SEATED_CLIENTS = 4;
  private static final int MAX_STANDING_CLIENTS = 13; 

  public synchronized boolean enter(Client client){
    if(this.isSeatedClientsFull() && this.isStandingClientsFull()){
      return false;
    }

    if (!this.isSeatedClientsFull()) {  
      seatedClients.offer(client);
      System.out.println(client.getName() + " is seated in the waiting room.");  
    } else  {
      standingClients.offer(client);
      System.out.println(client.getName() + " is standing in the waiting room.");
    } 

    while(!client.isTheNext()){
      try {
        this.wait();
      } catch (InterruptedException e) {
        if (!seatedClients.remove(client)) {
            standingClients.remove(client);
        }
        System.out.println("Client left before being served: " + e.getMessage());
        return false;
      }
    }
    return true;
  }

  public synchronized Client getNext() {
    if (this.seatedClients.isEmpty()) {
      System.out.println("No seated clients to serve.");
      return null;
    }
    Client client = seatedClients.poll();
    System.out.println(client.getName() + " is being served.");
    if (this.standingClients.isEmpty()) {
      System.out.println("No standing clients to seat.");
    } else {
      this.seatedClients.offer(this.standingClients.poll());
      System.out.println("A standing client has been seated.");
      // Notifica o cliente que está sendo servido
    }
    client.iAmTheNext(); // Marca o cliente como o próximo a ser atendido
    this.notifyAll(); 
    return client;
  }

  public synchronized void printWaitingRoomStatus() {
    System.out.println("Seated clients: " + this.seatedClients);
    System.out.println("Standing clients: " + this.standingClients);
  }

  private synchronized boolean isSeatedClientsFull() {
    return seatedClients.size() >= MAX_SEATED_CLIENTS;
  }

  private synchronized boolean isStandingClientsFull() {
    return standingClients.size() >= MAX_STANDING_CLIENTS;
  } 

}