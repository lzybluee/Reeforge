package forge.ai.ability;

import com.google.common.base.Predicate;

import forge.ai.*;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.cost.Cost;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.game.combat.Combat;

public class DestroyAllAi extends SpellAbilityAi {

    private static final Predicate<Card> predicate = new Predicate<Card>() {
        @Override
        public boolean apply(final Card c) {
            return !(c.hasKeyword("Indestructible") || c.getSVar("SacMe").length() > 0);
        }
    };

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellAiLogic#doTriggerAINoCost(forge.game.player.Player, java.util.Map, forge.card.spellability.SpellAbility, boolean)
     */
    @Override
    protected boolean doTriggerAINoCost(Player ai, SpellAbility sa, boolean mandatory) {
        if (mandatory) {
            return true;
        }

        return doMassRemovalLogic(ai, sa);
    }

    @Override
    public boolean chkAIDrawback(SpellAbility sa, Player aiPlayer) {
        //TODO: Check for bad outcome
        return true;
    }

    @Override
    protected boolean canPlayAI(final Player ai, SpellAbility sa) {
        // AI needs to be expanded, since this function can be pretty complex
        // based on what the expected targets could be
        final Cost abCost = sa.getPayCosts();
        final Card source = sa.getHostCard();

        if (abCost != null) {
            // AI currently disabled for some costs
            if (!ComputerUtilCost.checkLifeCost(ai, abCost, source, 4, sa)) {
                return false;
            }
        }

        // prevent run-away activations - first time will always return true
        if (ComputerUtil.preventRunAwayActivations(sa)) {
            return false;
        }
        
        return doMassRemovalLogic(ai, sa);
    }

    public boolean doMassRemovalLogic(Player ai, SpellAbility sa) {
        final Card source = sa.getHostCard();
        Player opponent = ComputerUtil.getOpponentFor(ai); // TODO: how should this AI logic work for multiplayer and getOpponents()?

        final int CREATURE_EVAL_THRESHOLD = 200;

        String valid = "";
        if (sa.hasParam("ValidCards")) {
            valid = sa.getParam("ValidCards");
        }

        if (valid.contains("X") && source.getSVar("X").equals("Count$xPaid")) {
            // Set PayX here to maximum value.
            final int xPay = ComputerUtilMana.determineLeftoverMana(sa, ai);
            source.setSVar("PayX", Integer.toString(xPay));
            valid = valid.replace("X", Integer.toString(xPay));
        }

        CardCollection opplist = CardLists.getValidCards(opponent.getCardsIn(ZoneType.Battlefield),
                valid.split(","), source.getController(), source, sa);
        CardCollection ailist = CardLists.getValidCards(ai.getCardsIn(ZoneType.Battlefield), valid.split(","),
                source.getController(), source, sa);
        
        opplist = CardLists.filter(opplist, predicate);
        ailist = CardLists.filter(ailist, predicate);
        if (opplist.isEmpty()) {
            return false;
        }

        if (sa.usesTargeting()) {
            sa.resetTargets();
            if (sa.canTarget(opponent)) {
                sa.getTargets().add(opponent);
                ailist.clear();
            } else {
                return false;
            }
        }

        // if only creatures are affected evaluate both lists and pass only if
        // human creatures are more valuable
        if (CardLists.getNotType(opplist, "Creature").isEmpty() && CardLists.getNotType(ailist, "Creature").isEmpty()) {
            if (ComputerUtilCard.evaluateCreatureList(ailist) + CREATURE_EVAL_THRESHOLD < ComputerUtilCard.evaluateCreatureList(opplist)) {
                return true;
            }
            
            if (ai.getGame().getPhaseHandler().getPhase().isBefore(PhaseType.MAIN2)) {
            	return false;
            }
            
            // test whether the human can kill the ai next turn
            Combat combat = new Combat(opponent);
            boolean containsAttacker = false;
            for (Card att : opponent.getCreaturesInPlay()) {
            	if (ComputerUtilCombat.canAttackNextTurn(att, ai)) {
            		combat.addAttacker(att, ai);
            		containsAttacker = containsAttacker | opplist.contains(att);
            	}
            }
            if (!containsAttacker) {
            	return false;
            }
            AiBlockController block = new AiBlockController(ai);
            block.assignBlockersForCombat(combat);

            if (ComputerUtilCombat.lifeInSeriousDanger(ai, combat)) {
            	return true;
            }
            return false;
        } // only lands involved
        else if (CardLists.getNotType(opplist, "Land").isEmpty() && CardLists.getNotType(ailist, "Land").isEmpty()) {
        	if (ai.isCardInPlay("Crucible of Worlds") && !opponent.isCardInPlay("Crucible of Worlds") && !opplist.isEmpty()) {
        		return true;
        	}
            // evaluate the situation with creatures on the battlefield separately, as that's where the AI typically makes mistakes
            CardCollection aiCreatures = ai.getCreaturesInPlay();
            CardCollection oppCreatures = opponent.getCreaturesInPlay();
            if (!oppCreatures.isEmpty()) {
                if (ComputerUtilCard.evaluateCreatureList(aiCreatures) < ComputerUtilCard.evaluateCreatureList(oppCreatures) + CREATURE_EVAL_THRESHOLD) {
                    return false;
                }
            }
            // check if the AI would lose more lands than the opponent would
            if (ComputerUtilCard.evaluatePermanentList(ailist) > ComputerUtilCard.evaluatePermanentList(opplist) + 1) {
                return false;
            }
        } // otherwise evaluate both lists by CMC and pass only if human permanents are more valuable
        else if ((ComputerUtilCard.evaluatePermanentList(ailist) + 3) >= ComputerUtilCard.evaluatePermanentList(opplist)) {
            return false;
        }

        return true;
    }
}
