/*

* Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.ai.ability;

import forge.ai.*;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CounterType;
import forge.game.cost.Cost;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostPart;
import forge.game.cost.PaymentDecision;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class DrawAi extends SpellAbilityAi {

    /* (non-Javadoc)
     * @see forge.ai.SpellAbilityAi#checkApiLogic(forge.game.player.Player, forge.game.spellability.SpellAbility)
     */
    @Override
    protected boolean checkApiLogic(Player ai, SpellAbility sa) {
        if (!targetAI(ai, sa, false)) {
            return false;
        }

        if (sa.usesTargeting()) {
            final Player player = sa.getTargets().getFirstTargetedPlayer();
            if (player != null && player.isOpponentOf(ai)) {
                return true;
            }
        }

        // prevent run-away activations - first time will always return true
        if (ComputerUtil.preventRunAwayActivations(sa)) {
            return false;
        }

        if (ComputerUtil.playImmediately(ai, sa)) {
            return true;
        }

        // Don't tap creatures that may be able to block
        if (ComputerUtil.waitForBlocking(sa)) {
            return false;
        }

        if (!canLoot(ai, sa)) {
                return false;
        }
        return true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see forge.ai.SpellAbilityAi#willPayCosts(forge.game.player.Player,
     * forge.game.spellability.SpellAbility, forge.game.cost.Cost,
     * forge.game.card.Card)
     */
    @Override
    protected boolean willPayCosts(Player ai, SpellAbility sa, Cost cost, Card source) {
        if (!ComputerUtilCost.checkCreatureSacrificeCost(ai, cost, source, sa)) {
            return false;
        }

        if (!ComputerUtilCost.checkLifeCost(ai, cost, source, 4, sa)) {
            return false;
        }

        if (!ComputerUtilCost.checkDiscardCost(ai, cost, source)) {
            AiCostDecision aiDecisions = new AiCostDecision(ai, sa);
            for (final CostPart part : cost.getCostParts()) {
                if (part instanceof CostDiscard) {
                    PaymentDecision decision = part.accept(aiDecisions);
                    if (null == decision)
                        return false;
                    for (Card discard : decision.cards) {
                        if (!ComputerUtil.isWorseThanDraw(ai, discard)) {
                            return false;
                        }
                    }
                }
            }
        }

        if (!ComputerUtilCost.checkRemoveCounterCost(cost, source)) {
            return false;
        }

        return true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.ai.SpellAbilityAi#checkPhaseRestrictions(forge.game.player.Player,
     * forge.game.spellability.SpellAbility, forge.game.phase.PhaseHandler)
     */
    @Override
    protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, PhaseHandler ph) {
        String logic = sa.getParamOrDefault("AILogic", "");

        if (logic.startsWith("LifeLessThan.")) {
            // LifeLessThan logic presupposes activation as soon as possible in an
            // attempt to save the AI from dying
            return true;
        } else if (logic.equals("AlwaysAtOppEOT")) {
            return ph.is(PhaseType.END_OF_TURN) && ph.getNextTurn().equals(ai);
        }

        // Don't use draw abilities before main 2 if possible
        if (ph.getPhase().isBefore(PhaseType.MAIN2) && !sa.hasParam("ActivationPhases")
                && !ComputerUtil.castSpellInMain1(ai, sa)) {
            return false;
        }

        return super.checkPhaseRestrictions(ai, sa, ph);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.ai.SpellAbilityAi#checkPhaseRestrictions(forge.game.player.Player,
     * forge.game.spellability.SpellAbility, forge.game.phase.PhaseHandler,
     * java.lang.String)
     */
    @Override
    protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, PhaseHandler ph, String logic) {
        if ((!ph.getNextTurn().equals(ai) || ph.getPhase().isBefore(PhaseType.END_OF_TURN))
                && !sa.hasParam("PlayerTurn") && !SpellAbilityAi.isSorcerySpeed(sa)
                && ai.getCardsIn(ZoneType.Hand).size() > 1 && !ComputerUtil.activateForCost(sa, ai)
                && !"YawgmothsBargain".equals(logic)) {
            return false;
        }
        return super.checkPhaseRestrictions(ai, sa, ph, logic);
    }

    @Override
    public boolean chkAIDrawback(SpellAbility sa, Player ai) {
        return targetAI(ai, sa, false);
    }

    /**
     * Check if looter (draw + discard) effects are worthwhile
     */
    private boolean canLoot(Player ai, SpellAbility sa) {
        final SpellAbility sub = sa.findSubAbilityByType(ApiType.Discard);
        if (sub != null) {
            final Card source = sa.getHostCard();
            final String sourceName = ComputerUtilAbility.getAbilitySourceName(sa);

            int numHand = ai.getCardsIn(ZoneType.Hand).size();
            if ("Jace, Vryn's Prodigy".equals(sourceName) && ai.getCardsIn(ZoneType.Graveyard).size() > 3) {
                return CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.Presets.PLANESWALKERS,
                        CardPredicates.isType("Jace")).size() <= 0;
            }
            if (source.isSpell() && ai.getCardsIn(ZoneType.Hand).contains(source)) {
                numHand--; // remember to count looter card if it is a spell in hand
            }
            int numDraw = 1;

            if (sa.hasParam("NumCards")) {
                String numDrawStr = sa.getParam("NumCards");
                if (numDrawStr.equals("X") && sa.getSVar(numDrawStr).equals("Count$Converge")) {
                    numDraw = ComputerUtilMana.getConvergeCount(sa, ai);
                } else {
                    numDraw = AbilityUtils.calculateAmount(source, numDrawStr, sa);
                }
            }
            int numDiscard = 1;
            if (sub.hasParam("NumCards")) {
                numDiscard = AbilityUtils.calculateAmount(source, sub.getParam("NumCards"), sub);
            }
            if (numHand == 0 && numDraw == numDiscard) {
                return false; // no looting since everything is dumped
            }
            if (numHand + numDraw < numDiscard) {
                return false; // net loss of cards
            }
        }
        return true;
    }

    private boolean targetAI(final Player ai, final SpellAbility sa, final boolean mandatory) {
        final Card source = sa.getHostCard();
        final boolean drawback = sa.getParent() != null;
        final Game game = ai.getGame();
        final String logic = sa.getParamOrDefault("AILogic", "");

        int computerHandSize = ai.getCardsIn(ZoneType.Hand).size();
        final int computerLibrarySize = ai.getCardsIn(ZoneType.Library).size();
        final int computerMaxHandSize = ai.getMaxHandSize();

        final SpellAbility loseLife = sa.findSubAbilityByType(ApiType.LoseLife);
        final SpellAbility getPoison = sa.findSubAbilityByType(ApiType.Poison);

        //if a spell is used don't count the card
        if (sa.isSpell() && source.isInZone(ZoneType.Hand)) {
            computerHandSize -= 1;
        }

        int numCards = 1;
        if (sa.hasParam("NumCards")) {
            numCards = AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("NumCards"), sa);
        }

        boolean xPaid = false;
        final String num = sa.getParam("NumCards");
        if (num != null && num.equals("X")) {
            if (source.getSVar(num).equals("Count$xPaid")) {
                // Set PayX here to maximum value.
                if (drawback && !source.getSVar("PayX").equals("")) {
                    numCards = Integer.parseInt(source.getSVar("PayX"));
                } else {
                    numCards = ComputerUtilMana.determineLeftoverMana(sa, ai);
                    source.setSVar("PayX", Integer.toString(numCards));
                }
                xPaid = true;
            }
            if (sa.getSVar(num).equals("Count$Converge")) {
                numCards = ComputerUtilMana.getConvergeCount(sa, ai);
            }
        }

        if (num != null && num.equals("ChosenX")) {
            // Necrologia, Pay X Life : Draw X Cards
            if (sa.getSVar("X").equals("XChoice")) {
                // Draw up to max hand size but leave at least 3 in library
                numCards = Math.min(computerMaxHandSize - computerHandSize, computerLibrarySize - 3);
                // But no more than what's "safe" and doesn't risk a near death experience
                // Maybe would be better to check for "serious danger" and take more risk?
                while ((ComputerUtil.aiLifeInDanger(ai, false, numCards) && (numCards > 0))) {
                    numCards--;
                }
                sa.setSVar("ChosenX", Integer.toString(numCards));
                source.setSVar("ChosenX", Integer.toString(numCards));
            }
        }
        // Logic for cards that require special handling
        if ("YawgmothsBargain".equals(logic)) {
            return SpecialCardAi.YawgmothsBargain.consider(ai, sa);
        }

        // Generic logic for all cards that do not need any special handling

        // TODO: if xPaid and one of the below reasons would fail, instead of
        // bailing reduce toPay amount to acceptable level
        if (sa.usesTargeting()) {
            // ability is targeted
            sa.resetTargets();

            // if it wouldn't draw anything and its not mandatory, skip it
            if (numCards == 0 && !mandatory && !drawback) {
                return false;
            }

            // filter player that can be targeted
            PlayerCollection players = game.getPlayers().filter(PlayerPredicates.isTargetableBy(sa));

            // no targets skip it
            if (players.isEmpty()) {
                return false;
            }

            // filter opponents
            PlayerCollection opps = players.filter(PlayerPredicates.isOpponentOf(ai));

            for (Player oppA : opps) {
                // try to kill opponent
                if (oppA.cantLose()) {
                    continue;
                }

                // try to mill opponent
                if (numCards >= oppA.getCardsIn(ZoneType.Library).size()) {
                    // but only it he doesn't have Laboratory Maniac
                    // also disable it for other checks later too
                    if (oppA.isCardInPlay("Laboratory Maniac")) {
                        continue;
                    }

                    sa.getTargets().add(oppA);
                    return true;
                }

                // try to make opponent pay to death
                if (loseLife != null && oppA.canLoseLife()) {
                    // loseLife for Target
                    if (loseLife.hasParam("Defined") && "Targeted".equals(loseLife.getParam("Defined"))) {
                        // currently all Draw / Lose cards use the same value
                        // for drawing and losing life
                        if (numCards >= oppA.getLife()) {
                            if (xPaid) {
                                source.setSVar("PayX", Integer.toString(oppA.getLife()));
                            }
                            sa.getTargets().add(oppA);
                            return true;
                        }
                    }
                }
                // try to make opponent lose to poison
                // currently only Caress of Phyrexia
                if (getPoison != null && oppA.canReceiveCounters(CounterType.POISON)) {
                    if (oppA.getPoisonCounters() + numCards > 9) {
                        sa.getTargets().add(oppA);
                        return true;
                    }
                }
                // we're trying to save ourselves from death
                // (e.g. Bargain), so target the opp anyway
                if (logic.startsWith("LifeLessThan.")) {
                    int threshold = Integer.parseInt(logic.substring(logic.indexOf(".") + 1));
                    sa.getTargets().add(oppA);
                    return ai.getLife() < threshold;
                }
            }
            
            boolean aiTarget = sa.canTarget(ai);
            // checks what the ai prevent from casting it on itself
            // if spell is not mandatory
            if (aiTarget && !ai.cantLose()) {
                if (numCards >= computerLibrarySize) {
                    if (xPaid) {
                        numCards = computerLibrarySize - 1;
                    } else if (!ai.isCardInPlay("Laboratory Maniac")) {
                        aiTarget = false;
                    }
                }

                if (loseLife != null && ai.canLoseLife()) {
                    if (numCards >= ai.getLife() + 5) {
                        if (xPaid) {
                            numCards = Math.min(numCards, ai.getLife() - 5);
                            if (numCards <= 0) {
                                aiTarget = false;
                            }
                        } else {
                            aiTarget = false;
                        }
                    }
                }

                if (getPoison != null && ai.canReceiveCounters(CounterType.POISON)) {
                    if (numCards + ai.getPoisonCounters() >= 8) {
                        aiTarget = false;
                    }
                }

                if (xPaid) {
                    source.setSVar("PayX", Integer.toString(numCards));
                }
            }

            if (aiTarget) {
                if (computerHandSize + numCards > computerMaxHandSize && game.getPhaseHandler().isPlayerTurn(ai)) {
                    if (xPaid) {
                        numCards = computerMaxHandSize - computerHandSize;
                        source.setSVar("PayX", Integer.toString(numCards));
                    } else {
                        // Don't draw too many cards and then risk discarding
                        // cards at EOT
                        if (!drawback && !mandatory) {
                            return false;
                        }
                    }
                }

                sa.getTargets().add(ai);
                return true;
            }

            // try to benefit ally
            for (Player ally : ai.getAllies()) {
                // try to select ally to help
                if (!sa.canTarget(ally)) {
                    continue;
                }

                // use xPaid abilties only for itself
                if (xPaid) {
                    continue;
                }

                // ally would draw more than it can
                if (numCards >= ally.getCardsIn(ZoneType.Library).size()) {
                    if (!ally.isCardInPlay("Laboratory Maniac")) {
                        continue;
                    }
                }

                // ally would lose because of life lost
                if (loseLife != null && ally.canLoseLife()) {
                    if (numCards < ai.getLife() - 5) {
                        continue;
                    }
                }

                // ally would lose because of poison
                if (getPoison != null && ally.canReceiveCounters(CounterType.POISON)) {
                    if (ally.getPoisonCounters() + numCards > 9) {
                        continue;
                    }
                }

                sa.getTargets().add(ally);
                return true;
            }

            // no nice targets, don't do it
            if (!mandatory) {
                return false;
            }

            // still try to target opponent first
            Player oppMin = opps.min(PlayerPredicates.compareByLife());
            if (oppMin != null) {
                sa.getTargets().add(oppMin);
                return true;
            }

            // final solution for a possible target
            Player result = players.min(PlayerPredicates.compareByLife());
            if (result != null) {
                sa.getTargets().add(result);
                return true;
            }
        } else if (!mandatory) {
            // TODO: consider if human is the defined player

            // ability is not targeted
            if (numCards >= computerLibrarySize) {
                if (ai.isCardInPlay("Laboratory Maniac")) {
                    return true;
                }
                // Don't deck yourself
                return false;
            }

            if (numCards == 0 && !drawback) {
                return false;
            }

            if ((computerHandSize + numCards > computerMaxHandSize)
                    && game.getPhaseHandler().isPlayerTurn(ai)
                    && !sa.isTrigger()) {
                // Don't draw too many cards and then risk discarding cards at
                // EOT
                if (!drawback) {
                    return false;
                }
            }
        }
        return true;
    } // drawTargetAI()


    @Override
    protected boolean doTriggerAINoCost(Player ai, SpellAbility sa, boolean mandatory) {
        return targetAI(ai, sa, mandatory);
    }

    
    /* (non-Javadoc)
     * @see forge.card.ability.SpellAbilityAi#confirmAction(forge.game.player.Player, forge.card.spellability.SpellAbility, forge.game.player.PlayerActionConfirmMode, java.lang.String)
     */
    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message) {
        int numCards = sa.hasParam("NumCards") ? AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("NumCards"), sa) : 1;
        // AI shouldn't mill itself
        if (numCards < player.getZone(ZoneType.Library).size())
            return true;
        // except it has Laboratory Maniac
        return player.isCardInPlay("Laboratory Maniac");
    }
}
