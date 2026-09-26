package main.java.barbearia.cliente;

public class WaitingRoomManualTest {

    static int passed = 0;
    static int failed = 0;

    static void check(String description, boolean condition) {
        if (condition) {
            System.out.println("  [OK] " + description);
            passed++;
        } else {
            System.out.println("  [FALHOU] " + description);
            failed++;
        }
    }

    public static void main(String[] args) {

        System.out.println("=== Caso 1: sofa enche, 1 vai pra fila em pe, getNext() uma vez ===");
        WaitingRoom room1 = new WaitingRoom();
        room1.enter("C1");
        room1.enter("C2");
        room1.enter("C3");
        room1.enter("C4");
        room1.enter("C5"); // deveria ir pra fila em pe
        room1.printWaitingRoomStatus();
        String served1 = room1.getNext(); // deveria tirar C1 e promover C5
        check("getNext() retornou C1 (primeiro do sofa)", "C1".equals(served1));
        room1.printWaitingRoomStatus();

        System.out.println();
        System.out.println("=== Caso 2: sofa com gente, fila em pe VAZIA, getNext() ===");
        WaitingRoom room2 = new WaitingRoom();
        room2.enter("A1");
        room2.enter("A2");
        // ninguem na fila em pe
        try {
            String served2 = room2.getNext();
            check("getNext() nao lancou excecao com fila em pe vazia", true);
            check("getNext() retornou A1", "A1".equals(served2));
        } catch (Exception e) {
            check("getNext() nao lancou excecao com fila em pe vazia", false);
            System.out.println("      excecao: " + e);
        }
        room2.printWaitingRoomStatus();

        System.out.println();
        System.out.println("=== Caso 3: sofa vazio, getNext() ===");
        WaitingRoom room3 = new WaitingRoom();
        String served3 = room3.getNext();
        check("getNext() com sofa vazio retorna null", served3 == null);

        System.out.println();
        System.out.println("=== Caso 4: encher tudo (4 sentados + 13 em pe = 17), 18o nao entra ===");
        WaitingRoom room4 = new WaitingRoom();
        for (int i = 1; i <= 17; i++) {
            room4.enter("Cliente" + i);
        }
        room4.printWaitingRoomStatus();
        boolean accepted18 = room4.enter("Cliente18");
        check("18o cliente e rejeitado (sala cheia)", accepted18 == false);
        room4.printWaitingRoomStatus();
        // no estado atual nao ha getter que confirme isso via retorno, so pelo print acima

        System.out.println();
        System.out.println("=== Caso 5: drenar tudo e conferir ordem FIFO ===");
        WaitingRoom room5 = new WaitingRoom();
        for (int i = 1; i <= 17; i++) {
            room5.enter("Cliente" + i);
        }
        String expectedOrder[] = new String[17];
        for (int i = 1; i <= 17; i++) expectedOrder[i - 1] = "Cliente" + i;

        boolean fifoOk = true;
        for (int i = 0; i < 17; i++) {
            String served = room5.getNext();
            if (!expectedOrder[i].equals(served)) {
                fifoOk = false;
                System.out.println("      esperado " + expectedOrder[i] + " mas veio " + served);
            }
        }
        check("ordem de atendimento bate com ordem de chegada (FIFO)", fifoOk);
        check("depois de drenar tudo, getNext() volta a retornar null", room5.getNext() == null);

        System.out.println();
        System.out.println("=== Resultado: " + passed + " passaram, " + failed + " falharam ===");
    }
}