package forge.ai.ability;

import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilMana;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

public class ChooseColorAi extends SpellAbilityAi {

    @Override
    protected boolean canPlayAI(Player ai, SpellAbility sa) {
        final Card source = sa.getHostCard();
        final Game game = ai.getGame();
        final String sourceName = ComputerUtilAbility.getAbilitySourceName(sa);
        final PhaseHandler ph = game.getPhaseHandler();

        if (!sa.hasParam("AILogic")) {
            return false;
        }
        final String logic = sa.getParam("AILogic");

        if (ComputerUtil.preventRunAwayActivations(sa)) {
            return false;
        }

        if ("Nykthos, Shrine to Nyx".equals(sourceName)) {
            return SpecialCardAi.NykthosShrineToNyx.consider(ai, sa);
        }

        if ("Oona, Queen of the Fae".equals(sourceName)) {
            if (ph.isPlayerTurn(ai) || ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
                return false;
            }
            // Set PayX here to maximum value.
            int x = ComputerUtilMana.determineLeftoverMana(sa, ai);
            source.setSVar("PayX", Integer.toString(x));
            return true;
        }

        if ("Addle".equals(sourceName)) {
            if (ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS) || ComputerUtil.getOpponentFor(ai).getCardsIn(ZoneType.Hand).isEmpty()) {
                return false;
            }
            return true;
        }

        if (logic.equals("MostExcessOpponentControls")) {
            for (byte color : MagicColor.WUBRG) {
                CardCollectionView ailist = ai.getCardsIn(ZoneType.Battlefield);
                CardCollectionView opplist = ComputerUtil.getOpponentFor(ai).getCardsIn(ZoneType.Battlefield);

                ailist = CardLists.filter(ailist, CardPredicates.isColor(color));
                opplist = CardLists.filter(opplist, CardPredicates.isColor(color));

                int excess = ComputerUtilCard.evaluatePermanentList(opplist) - ComputerUtilCard.evaluatePermanentList(ailist);
                if (excess > 4) {
                    return true;
                }
            }
            return false;
        }

        if (logic.equals("MostProminentInComputerDeck")) {
            if ("Astral Cornucopia".equals(sourceName)) {
                // activate in Main 2 hoping that the extra mana surplus will make a difference
                // if there are some nonland permanents in hand
                CardCollectionView permanents = CardLists.filter(ai.getCardsIn(ZoneType.Hand), 
                        CardPredicates.Presets.NONLAND_PERMANENTS);

                return permanents.size() > 0 && ph.is(PhaseType.MAIN2, ai);
            }
        }

        boolean chance = MyRandom.getRandom().nextFloat() <= Math.pow(.6667, sa.getActivationsThisTurn());
        return chance;
    }

    @Override
    protected boolean doTriggerAINoCost(Player ai, SpellAbility sa, boolean mandatory) {
        return mandatory || canPlayAI(ai, sa);
    }

}
