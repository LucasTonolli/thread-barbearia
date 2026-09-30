package barbearia.barbeiro;

import barbearia.cliente.Client;

/**
 * Representa uma das 3 cadeiras de atendimento da barbearia.
 * Cada cadeira e associada a um barbeiro especifico e controla a presenca do cliente.
 */
public class BarberChair {

    private final int chairId;
    private final String barberName;
    private volatile Client currentClient = null;

    public BarberChair(int chairId, String barberName) {
        this.chairId = chairId;
        this.barberName = barberName;
    }

    public synchronized void occupy(Client client) {
        if (this.currentClient != null) {
            throw new IllegalStateException("Cadeira " + chairId + " ja esta ocupada por " + this.currentClient.getName());
        }
        this.currentClient = client;
    }

    public synchronized void release() {
        this.currentClient = null;
    }

    public synchronized boolean isOccupied() {
        return this.currentClient != null;
    }

    public synchronized Client getCurrentClient() {
        return currentClient;
    }

    public int getChairId() {
        return chairId;
    }

    public String getBarberName() {
        return barberName;
    }

    @Override
    public String toString() {
        return "Cadeira " + chairId + " (" + barberName + "): " + (isOccupied() ? "Ocupada por " + currentClient.getName() : "Livre");
    }
}
