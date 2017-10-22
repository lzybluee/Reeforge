package forge.screens.home.puzzle;

import javax.swing.*;
import java.awt.event.KeyEvent;

public class PuzzleGameMenu {
    private PuzzleGameMenu() { }

    public static JMenu getMenu() {
        JMenu menu = new JMenu("Puzzle");
        menu.setMnemonic(KeyEvent.VK_G);
        return menu;
    }
}
