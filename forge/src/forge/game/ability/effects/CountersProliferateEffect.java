package forge.game.ability.effects;

import forge.game.GameEntity;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.Map;
import java.util.Map.Entry;

public class CountersProliferateEffect extends SpellAbilityEffect {
    @Override
    protected String getStackDescription(SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Proliferate.");
        sb.append(" (You choose any number of permanents and/or players with ");
        sb.append("counters on them, then give each another counter of a kind already there.)");

        return sb.toString();
    }

    @Override
    public void resolve(SpellAbility sa) {
        final Card host = sa.getHostCard();
        Player controller = host.getController();
        int max = 0;

        for (Player player : controller.getGame().getPlayers()) {
            if (player.hasCounters()) {
                max++;
            }
            for (final Card c : player.getCardsIn(ZoneType.Battlefield)) {
                if (c.hasCounters()) {
                    max++;
                }
            }
        }
        if (max == 0)
            return;

        Map<GameEntity, CounterType> proliferateChoice = null;
        do {
            proliferateChoice = controller.getController().chooseProliferation(sa, max);
            if (proliferateChoice != null && proliferateChoice.size() == 0) {
                if (controller.getController().confirmAction(sa, null, "Cancal Proliferation?")) {
                    break;
                } else {
                    continue;
                }
            }
        }
        while (proliferateChoice != null && proliferateChoice.size() == 0);

        if (proliferateChoice == null)
            return;

        for(Entry<GameEntity, CounterType> ge: proliferateChoice.entrySet()) {
            if( ge.getKey() instanceof Player )
                ((Player) ge.getKey()).addCounter(ge.getValue(), 1, host, true);
            else if( ge.getKey() instanceof Card)
                ((Card) ge.getKey()).addCounter(ge.getValue(), 1, host, true);
        }
    }
}
