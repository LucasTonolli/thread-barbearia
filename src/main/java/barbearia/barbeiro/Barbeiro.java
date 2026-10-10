package barbearia.barbeiro;

import barbearia.cliente.WaitingRoom;
import barbearia.integracao.CaixaPOS;

/**
 * Alias em portugues para manter compatibilidade com o nome sugerido no README.
 */
public class Barbeiro extends Barber {

    public Barbeiro(
            int id,
            String name,
            BarberChair chair,
            WaitingRoom waitingRoom,
            CaixaPOS pos,
            long cutDurationMs,
            long paymentDurationMs
    ) {
        super(id, name, chair, waitingRoom, pos, cutDurationMs, paymentDurationMs);
    }

    public Barbeiro(int id, String name, WaitingRoom waitingRoom, CaixaPOS pos) {
        super(id, name, waitingRoom, pos);
    }
}
