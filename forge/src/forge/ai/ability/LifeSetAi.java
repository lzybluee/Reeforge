package forge.ai.ability;

import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilMana;
import forge.ai.SpellAbilityAi;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.util.MyRandom;

public class LifeSetAi extends SpellAbilityAi {

    @Override
    protected boolean canPlayAI(Player ai, SpellAbility sa) {
        // Ability_Cost abCost = sa.getPayCosts();
        final Card source = sa.getHostCard();
        final int myLife = ai.getLife();
        final Player opponent = ComputerUtil.getOpponentFor(ai);
        final int hlife = opponent.getLife();
        final String amountStr = sa.getParam("LifeAmount");

        if (!ai.canGainLife()) {
            return false;
        }

        // Don't use setLife before main 2 if possible
        if (ai.getGame().getPhaseHandler().getPhase().isBefore(PhaseType.MAIN2)
                && !sa.hasParam("ActivationPhases")) {
            return false;
        }

        // TODO add AI logic for that
        if (sa.hasParam("Redistribute")) {
            return false;
        }

        // TODO handle proper calculation of X values based on Cost and what
        // would be paid
        int amount;
        // we shouldn't have to worry too much about PayX for SetLife
        if (amountStr.equals("X") && source.getSVar(amountStr).equals("Count$xPaid")) {
            // Set PayX here to maximum value.
            final int xPay = ComputerUtilMana.determineLeftoverMana(sa, ai);
            source.setSVar("PayX", Integer.toString(xPay));
            amount = xPay;
        } else {
            amount = AbilityUtils.calculateAmount(sa.getHostCard(), amountStr, sa);
        }

        // prevent run-away activations - first time will always return true
        final boolean chance = MyRandom.getRandom().nextFloat() <= Math.pow(.6667, sa.getActivationsThisTurn());

        final TargetRestrictions tgt = sa.getTargetRestrictions();
        if (tgt != null) {
            sa.resetTargets();
            if (tgt.canOnlyTgtOpponent()) {
                sa.getTargets().add(opponent);
                // if we can only target the human, and the Human's life
                // would
                // go up, don't play it.
                // possibly add a combo here for Magister Sphinx and
                // Higedetsu's
                // (sp?) Second Rite
                if ((amount > hlife) || !opponent.canLoseLife()) {
                    return false;
                }
            } else {
                if ((amount > myLife) && (myLife <= 10)) {
                    sa.getTargets().add(ai);
                } else if (hlife > amount) {
                    sa.getTargets().add(opponent);
                } else if (amount > myLife) {
                    sa.getTargets().add(ai);
                } else {
                    return false;
                }
            }
        } else {
            if (sa.getParam("Defined").equals("Player")) {
                if (amount == 0) {
                    return false;
                } else if (myLife > amount) { // will decrease computer's
                                              // life
                    if ((myLife < 5) || ((myLife - amount) > (hlife - amount))) {
                        return false;
                    }
                }
            }
            if (amount <= myLife) {
                return false;
            }
        }

        // if life is in danger, always activate
        if ((myLife < 3) && (amount > myLife)) {
            return true;
        }

        return ((MyRandom.getRandom().nextFloat() < .6667) && chance);
    }

    @Override
    protected boolean doTriggerAINoCost(Player ai, SpellAbility sa, boolean mandatory) {
        final int myLife = ai.getLife();
        final Player opponent = ComputerUtil.getOpponentFor(ai);
        final int hlife = opponent.getLife();
        final Card source = sa.getHostCard();
        final String sourceName = ComputerUtilAbility.getAbilitySourceName(sa);

        final String amountStr = sa.getParam("LifeAmount");

        int amount;
        if (amountStr.equals("X") && source.getSVar(amountStr).equals("Count$xPaid")) {
            // Set PayX here to maximum value.
            final int xPay = ComputerUtilMana.determineLeftoverMana(sa, ai);
            source.setSVar("PayX", Integer.toString(xPay));
            amount = xPay;
        } else {
            amount = AbilityUtils.calculateAmount(sa.getHostCard(), amountStr, sa);
        }

        // special cases when amount can't be calculated without targeting first
        if (amount == 0 && "TargetedPlayer$StartingLife/HalfDown".equals(source.getSVar(amountStr))) {
            // e.g. Torgaar, Famine Incarnate
            return doHalfStartingLifeLogic(ai, opponent, sa);
        }

        if (sourceName.equals("Eternity Vessel")
                && (opponent.isCardInPlay("Vampire Hexmage") || (source.getCounters(CounterType.CHARGE) == 0))) {
            return false;
        }

        // If the Target is gaining life, target self.
        // if the Target is modifying how much life is gained, this needs to
        // be handled better
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        if (tgt != null) {
            sa.resetTargets();
            if (tgt.canOnlyTgtOpponent()) {
                sa.getTargets().add(opponent);
            } else {
                if ((amount > myLife) && (myLife <= 10)) {
                    sa.getTargets().add(ai);
                } else if (hlife > amount) {
                    sa.getTargets().add(opponent);
                } else if (amount > myLife) {
                    sa.getTargets().add(ai);
                } else {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean doHalfStartingLifeLogic(Player ai, Player opponent, SpellAbility sa) {
        int aiAmount = ai.getStartingLife() / 2;
        int oppAmount = opponent.getStartingLife() / 2;
        int aiLife = ai.getLife();
        int oppLife = opponent.getLife();

        sa.resetTargets();

        final TargetRestrictions tgt = sa.getTargetRestrictions();
        if (tgt != null) {
            if (tgt.canOnlyTgtOpponent()) {
                if (oppLife > oppAmount) {
                    sa.getTargets().add(opponent);
                } else {
                    return false;
                }
            } else {
                if (aiAmount > ai.getLife() && aiLife < 5) {
                    sa.getTargets().add(ai);
                } else if (oppLife > oppAmount) {
                    sa.getTargets().add(opponent);
                } else if (aiAmount > aiLife) {
                    sa.getTargets().add(ai);
                } else {
                    return false;
                }
            }
        }

        return true;
    }

}
