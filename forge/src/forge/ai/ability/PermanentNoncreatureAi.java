package forge.ai.ability;

import forge.ai.ComputerUtilAbility;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

/** 
 * AbilityFactory for Creature Spells.
 *
 */
public class PermanentNoncreatureAi extends PermanentAi {

    @Override
    protected boolean checkAiLogic(final Player ai, final SpellAbility sa, final String aiLogic) {
        if ("Never".equals(aiLogic) || "DontCast".equals(aiLogic)) {
            return false;
        }
        return true;
    }

    /**
     * The rest of the logic not covered by the canPlayAI template is defined
     * here
     */
    @Override
    protected boolean checkApiLogic(final Player ai, final SpellAbility sa) {

        if (!super.checkApiLogic(ai, sa))
            return false;

        final Card host = sa.getHostCard();
        final String sourceName = ComputerUtilAbility.getAbilitySourceName(sa);
        final Game game = ai.getGame();

        // Check for valid targets before casting
        if (host.hasSVar("OblivionRing")) {
            SpellAbility effectExile = AbilityFactory.getAbility(host.getSVar("TrigExile"), host);
            final ZoneType origin = ZoneType.listValueOf(effectExile.getParam("Origin")).get(0);
            final TargetRestrictions tgt = effectExile.getTargetRestrictions();
            final CardCollection list = CardLists.getValidCards(game.getCardsIn(origin), tgt.getValidTgts(), ai, host,
                    effectExile);
            CardCollection targets = CardLists.getTargetableCards(list, sa);
            if (sourceName.equals("Suspension Field") 
                    || sourceName.equals("Detention Sphere")) {
                // existing "exile until leaves" enchantments only target
                // opponent's permanents
                // TODO: consider replacing the condition with host.hasSVar("OblivionRing")
                targets = CardLists.filterControlledBy(targets, ai.getOpponents());
            }
            if (targets.isEmpty()) {
                // AiPlayDecision.AnotherTime
                return false;
            }
        }
        return true;
    }
}
