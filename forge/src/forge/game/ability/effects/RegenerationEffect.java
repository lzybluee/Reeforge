package forge.game.ability.effects;

import forge.game.Game;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.event.GameEventCardRegenerated;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;

import java.util.Map;

import com.google.common.collect.Maps;

public class RegenerationEffect extends SpellAbilityEffect {

    /*
     * (non-Javadoc)
     * @see forge.game.ability.SpellAbilityEffect#resolve(forge.game.spellability.SpellAbility)
     */
    @Override
    public void resolve(SpellAbility sa) {
        final Card host = sa.getHostCard();
        final Game game = host.getGame();
        for (Card c : getTargetCards(sa)) {
            if (!c.canBeShielded() || !c.isInPlay()) {
                continue;
            }

            c.setDamage(0);
            c.setHasBeenDealtDeathtouchDamage(false);
            c.tap();
            c.addRegeneratedThisTurn();

            if (game.getCombat() != null) {
                game.getCombat().removeFromCombat(c);
            }

            // Play the Regen sound
            game.fireEvent(new GameEventCardRegenerated());
            
            if (host.getType().hasStringType("Effect")) {
                c.subtractShield(host);
                host.removeRemembered(c);
            }

            // Run triggers
            final Map<String, Object> runParams = Maps.newHashMap();
            runParams.put("Card", c);
            runParams.put("Cause", host);
            game.getTriggerHandler().runTrigger(TriggerType.Regenerated, runParams, false);
        }

    }

}
