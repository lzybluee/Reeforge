package forge.ai.ability;

import forge.ai.SpellAbilityAi;
import forge.game.card.Card;
import forge.game.card.Card.SplitCMCMode;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

import java.util.Random;

import com.google.common.base.Predicates;

public class ScryAi extends SpellAbilityAi {

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#doTriggerAINoCost(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility, boolean)
     */
    @Override
    protected boolean doTriggerAINoCost(Player ai, SpellAbility sa, boolean mandatory) {
        final TargetRestrictions tgt = sa.getTargetRestrictions();

        if (tgt != null) { // It doesn't appear that Scry ever targets
            // ability is targeted
            sa.resetTargets();

            sa.getTargets().add(ai);
        }

        return true;
    } // scryTargetAI()

    @Override
    public boolean chkAIDrawback(SpellAbility sa, Player ai) {
        return doTriggerAINoCost(ai, sa, false);
    }
    
    /**
     * Checks if the AI will play a SpellAbility based on its phase restrictions
     */
    @Override
    protected boolean checkPhaseRestrictions(final Player ai, final SpellAbility sa, final PhaseHandler ph) {
        // in the playerturn Scry should only be done in Main1 or in upkeep if able
        if (ph.isPlayerTurn(ai)) {
            if (SpellAbilityAi.isSorcerySpeed(sa)) {
                return ph.is(PhaseType.MAIN1) || sa.hasParam("Planeswalker");
            } else {
                return ph.is(PhaseType.UPKEEP);
            }
        }
        return true;
    }
    
    /**
     * Checks if the AI will play a SpellAbility with the specified AiLogic
     */
    @Override
    protected boolean checkAiLogic(final Player ai, final SpellAbility sa, final String aiLogic) {
        if ("Never".equals(aiLogic)) {
            return false;
        } else if ("BrainJar".equals(aiLogic)) {
            final Card source = sa.getHostCard();
            
            int counterNum = source.getCounters(CounterType.CHARGE);
            // no need for logic
            if (counterNum == 0) {
                return false;
            }
            int libsize = ai.getCardsIn(ZoneType.Library).size();
            
            final CardCollection hand = CardLists.filter(ai.getCardsIn(ZoneType.Hand), Predicates.or( 
                    CardPredicates.isType("Instant"), CardPredicates.isType("Sorcery")));
            if (!hand.isEmpty()) {
                // has spell that can be cast in hand with put ability
                if (!CardLists.filter(hand, CardPredicates.hasCMC(counterNum + 1)).isEmpty()) {
                    return false;
                }
                // has spell that can be cast if one counter is removed
                if (!CardLists.filter(hand, CardPredicates.hasCMC(counterNum)).isEmpty()) {
                    sa.setSVar("ChosenX", "Number$1");
                    return true;
                }
            }
            final CardCollection library = CardLists.filter(ai.getCardsIn(ZoneType.Library), Predicates.or( 
                    CardPredicates.isType("Instant"), CardPredicates.isType("Sorcery")));
            if (!library.isEmpty()) {
                // get max cmc of instant or sorceries in the libary 
                int maxCMC = 0;
                for (final Card c : library) {
                    int v = c.getCMC(); 
                    if (c.isSplitCard()) {
                        v = Math.max(c.getCMC(SplitCMCMode.LeftSplitCMC), c.getCMC(SplitCMCMode.RightSplitCMC));
                    }
                    if (v > maxCMC) {
                        maxCMC = v;
                    }
                }
                // there is a spell with more CMC, no need to remove counter
                if (counterNum + 1 < maxCMC) {
                    return false;
                }
                int maxToRemove = counterNum - maxCMC + 1;
                // no Scry 0, even if its catched from later stuff 
                if (maxToRemove <= 0) {
                	return false;
                }
                sa.setSVar("ChosenX", "Number$" + Integer.toString(maxToRemove));
            } else {
                // no Instant or Sorceries anymore, just scry
                sa.setSVar("ChosenX", "Number$" + Integer.toString(Math.min(counterNum, libsize)));
            }
        }
        return true;
    }
    
    @Override
    protected boolean checkApiLogic(Player ai, SpellAbility sa) {
        // does Scry make sense with no Library cards?
        if (ai.getCardsIn(ZoneType.Library).isEmpty()) {
            return false;
        }

        double chance = .4; // 40 percent chance of milling with instant speed
                            // stuff
        if (SpellAbilityAi.isSorcerySpeed(sa)) {
            chance = .667; // 66.7% chance for sorcery speed (since it will
                           // never activate EOT)
        }
        final Random r = MyRandom.getRandom();
        boolean randomReturn = r.nextFloat() <= Math.pow(chance, sa.getActivationsThisTurn() + 1);

        if (SpellAbilityAi.playReusable(ai, sa)) {
            randomReturn = true;
        }

        return randomReturn;
    }

    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message) {
        return true;
    }
}
