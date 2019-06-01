package forge.match.input;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import forge.model.FModel;
import forge.properties.ForgePreferences;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.player.PlayerControllerHuman;
import forge.util.ITriggerEvent;

public final class InputProliferate extends InputSelectManyBase<GameEntity> {
    private static final long serialVersionUID = -1779224307654698954L;
    private final List<GameEntity> chosenObjects = new ArrayList<>();
    private SpellAbility sa;

    private int maxChosen = 0;

    public InputProliferate(final PlayerControllerHuman controller, final SpellAbility sa, final int max) {
        super(controller, 1, Integer.MAX_VALUE);
        this.sa = sa;
        this.maxChosen = max;
    }

    @Override
    protected String getMessage() {
    	final StringBuilder sb = new StringBuilder();
        if ( FModel.getPreferences().getPrefBoolean(ForgePreferences.FPref.UI_DETAILED_SPELLDESC_IN_PROMPT) &&
	     sa != null ) {
	    sb.append(sa.getStackDescription()).append("\n");
    	}
    	sb.append("Choose permanents and/or players with counters on them to add one more counter of each type.");
        sb.append("\n\nYou've selected so far:\n");
        if (chosenObjects.isEmpty()) {
            sb.append("(none)");
        }
        else {
            for (final GameEntity ge : chosenObjects) {
                sb.append("* ").append(ge).append("\n");
            }
        }

        return sb.toString();
    }

    @Override
    protected boolean onCardSelected(final Card card, final List<Card> otherCardsToSelect, final ITriggerEvent triggerEvent) {
        if (!card.hasCounters()) {
            return false;
        }

        final boolean entityWasSelected = chosenObjects.contains(card);
        if (entityWasSelected) {
            this.chosenObjects.remove(card);
            getController().getGui().setUsedToPay(CardView.get(card), false);
        }
        else {
        	this.chosenObjects.add(card);
            getController().getGui().setUsedToPay(CardView.get(card), true);
        }

        refresh();
        return true;
    }

    @Override
    public String getActivateAction(final Card card) {
        if (card.hasCounters() && !chosenObjects.contains(card)) {
            return "add counter to card";
        }
        return null;
    }

    @Override
    protected final void onPlayerSelected(final Player player, final ITriggerEvent triggerEvent) {
        if (!player.hasCounters()) {
            // Can't select a player without counters
            return;
        }

        final boolean entityWasSelected = chosenObjects.contains(player);
        if (entityWasSelected) {
            this.chosenObjects.remove(player);
            getController().getGui().setHighlighted(PlayerView.get(player), false);
        } else {
            this.chosenObjects.add(player);
            getController().getGui().setHighlighted(PlayerView.get(player), true);
        }

        refresh();
    }

    public List<GameEntity> getProliferation() {
        return chosenObjects;
    }


    @Override
    protected boolean hasEnoughTargets() { return true; }

    @Override
    protected boolean hasAllTargets() {
        return this.chosenObjects.size() == maxChosen;
    }

    @Override
    public Collection<GameEntity> getSelected() {
        // TODO Auto-generated method stub
        return chosenObjects;
    }
}
