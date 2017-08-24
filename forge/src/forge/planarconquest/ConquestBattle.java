package forge.planarconquest;

import java.util.Set;

import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.game.GameType;
import forge.game.GameView;
import forge.interfaces.IButton;
import forge.interfaces.IGuiGame;
import forge.interfaces.IWinLoseView;
import forge.item.PaperCard;

public abstract class ConquestBattle {
    private final ConquestLocation location;
    private final int tier;
    private Deck opponentDeck;
    private boolean conquered;
    private boolean finished;

    protected ConquestBattle(ConquestLocation location0, int tier0) {
        location = location0;
        tier = tier0;
    }

    public ConquestLocation getLocation() {
        return location;
    }

    public int getTier() {
        return tier;
    }

    public Deck getOpponentDeck() {
        if (opponentDeck == null) {
            opponentDeck = buildOpponentDeck();
        }
        return opponentDeck;
    }

    public boolean wasConquered() {
        return conquered;
    }
    public void setConquered(boolean conquered0) {
        conquered = conquered0;
    }

    public void showGameOutcome(final ConquestData model, final GameView game, final LobbyPlayer humanPlayer, final IWinLoseView<? extends IButton> view) {
        if (game.isMatchWonBy(humanPlayer)) {
            view.getBtnRestart().setVisible(false);
            view.getBtnQuit().setText("Great!");
            model.addWin(this);
        }
        else {
            view.getBtnRestart().setVisible(true);
            view.getBtnRestart().setText("Retry");
            view.getBtnQuit().setText("Quit");
            model.addLoss(this);
        }
        model.resetNewCards(); //reset new cards after finishing a game
        model.saveData();
    }

    public void onFinished(final ConquestData model, final IWinLoseView<? extends IButton> view) {
        finished = true;
    }

    public boolean isFinished() {
        return finished;
    }

    protected abstract Deck buildOpponentDeck();

    public abstract String getEventName();
    public abstract String getOpponentName();
    public abstract PaperCard getPlaneswalker();
    public abstract void setOpponentAvatar(LobbyPlayer aiPlayer, IGuiGame gui);
    public abstract Set<GameType> getVariants();
    public abstract int gamesPerMatch();
}
