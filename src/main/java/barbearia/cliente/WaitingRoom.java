package main.java.barbearia.cliente;
import java.util.ArrayDeque;
import java.util.Queue;

public class WaitingRoom{
  private final Queue<String> standingClients = new ArrayDeque<>();
  private final Queue<String> seatedClients = new ArrayDeque<>();
  // Máximo é 20 clientes , porém 3 estão sendo atendidos, então 17 clientes podem estar na sala de espera  
  private static final int MAX_SEATED_CLIENTS = 4;
  private static final int MAX_STANDING_CLIENTS = 13; 

  public boolean enter(String clientName){
    if (!this.isSeatedClientsFull()) {   
      seatedClients.offer(clientName);
      System.out.println(clientName + " is seated in the waiting room.");
      return true;
    } else if (!this.isStandingClientsFull()) {
      standingClients.offer(clientName);
      System.out.println(clientName + " is standing in the waiting room.");
      return true;
    } 
    System.out.println("Waiting room is full. " + clientName + " cannot enter."); 
    return false;
  }

  public String getNext() {
    if (this.seatedClients.isEmpty()) {
      System.out.println("No seated clients to serve.");
      return null;
    }
    String client = seatedClients.poll();
    System.out.println(client + " is being served.");
    if (this.standingClients.isEmpty()) {
      System.out.println("No standing clients to seat.");
    } else {
      this.seatedClients.offer(this.standingClients.poll());
      System.out.println("A standing client has been seated.");
    }

    return client;
  }

  public void printWaitingRoomStatus() {
    System.out.println("Seated clients: " + this.seatedClients);
    System.out.println("Standing clients: " + this.standingClients);
  }

  private boolean isSeatedClientsFull() {
    return seatedClients.size() >= MAX_SEATED_CLIENTS;
  }

  private boolean isStandingClientsFull() {
    return standingClients.size() >= MAX_STANDING_CLIENTS;
  } 

}