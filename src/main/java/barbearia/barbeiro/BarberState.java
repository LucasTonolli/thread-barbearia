package barbearia.barbeiro;

/**
 * Estados do ciclo de vida de uma thread de Barbeiro.
 */
public enum BarberState {
    SLEEPING("DORMINDO"),
    CALLING_CLIENT("CHAMANDO_CLIENTE"),
    CUTTING_HAIR("CORTANDO_CABELO"),
    WAITING_POS("AGUARDANDO_POS"),
    RECEIVING_PAYMENT("PROCESSANDO_PAGAMENTO"),
    FINISHED("FINALIZADO");

    private final String description;

    BarberState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return description;
    }
}
