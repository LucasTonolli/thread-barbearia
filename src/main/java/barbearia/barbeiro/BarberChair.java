package barbearia.barbeiro;

import barbearia.cliente.Client;

/** Representa uma das tres cadeiras de atendimento. */
public class BarberChair {

    private final int chairId;
    private final String barberName;
    private Client currentClient;

    public BarberChair(int chairId, String barberName) {
        this.chairId = chairId;
        this.barberName = barberName;
    }

    public synchronized void occupy(Client client) {
        if (currentClient != null) {
            throw new IllegalStateException(
                    "Cadeira " + chairId + " ja esta ocupada por " + currentClient.getName()
            );
        }
        currentClient = client;
    }

    public synchronized void release() {
        currentClient = null;
    }

    public synchronized boolean isOccupied() {
        return currentClient != null;
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
    public synchronized String toString() {
        return "Cadeira " + chairId + " (" + barberName + "): "
                + (currentClient == null ? "Livre" : "Ocupada por " + currentClient.getName());
    }
}
