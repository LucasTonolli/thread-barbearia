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
    if (!this.isSeatedClientsFull()) {  
       try { Thread.sleep(2); } catch (InterruptedException e) {}
      seatedClients.offer(client);
      System.out.println(client.getName() + " is seated in the waiting room.");
      return true;
    } else if (!this.isStandingClientsFull()) {
       try { Thread.sleep(2); } catch (InterruptedException e) {}
      standingClients.offer(client);
      System.out.println(client.getName() + " is standing in the waiting room.");
      return true;
    } 
    System.out.println("Waiting room is full. " + client.getName() + " cannot enter."); 
    return false;
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
    }

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