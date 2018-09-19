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
package forge.game.trigger;

import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactoryUtil;
import forge.game.cost.IndividualCostPaymentInstance;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.util.Expressions;

import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.ArrayUtils;

import com.google.common.collect.Sets;

/**
 * <p>
 * Trigger_ChangesZone class.
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class TriggerChangesZone extends Trigger {

    // stores the costs when this trigger has already been run (to prevent multiple card draw triggers for single
    // discard event of multiple cards on the Gitrog Moster for instance)
    private Set<Integer> processedCostEffects = Sets.newHashSet();

    /**
     * <p>
     * Constructor for Trigger_ChangesZone.
     * </p>
     * 
     * @param params
     *            a {@link java.util.HashMap} object.
     * @param host
     *            a {@link forge.game.card.Card} object.
     * @param intrinsic
     *            the intrinsic
     */
    public TriggerChangesZone(final Map<String, String> params, final Card host, final boolean intrinsic) {
        super(params, host, intrinsic);
    }

    /** {@inheritDoc} */
    @Override
    public final boolean performTest(final Map<String, Object> runParams2) {
        if (hasParam("Origin")) {
            if (!getParam("Origin").equals("Any")) {
                if (getParam("Origin") == null) {
                    return false;
                }
                if (!ArrayUtils.contains(
                    getParam("Origin").split(","), runParams2.get("Origin")
                )) {
                    return false;
                }
            }
        }

        if (hasParam("Destination")) {
            if (!getParam("Destination").equals("Any")) {
                if (!ArrayUtils.contains(
                    getParam("Destination").split(","), runParams2.get("Destination")
                )) {
                    return false;
                }
            }
        }

        if (hasParam("ExcludedDestinations")) {
            if (!ArrayUtils.contains(
                getParam("ExcludedDestinations").split(","), runParams2.get("Destination")
            )) {
                return false;
            }
        }

        if (hasParam("ValidCard")) {
            Card moved = (Card) runParams2.get("Card");
            final Game game = getHostCard().getGame();
            boolean isDiesTrig = "Battlefield".equals(getParam("Origin"))
                    && "Graveyard".equals(getParam("Destination"));

            if (isDiesTrig) {
                moved = game.getChangeZoneLKIInfo(moved);
            }

            if (!moved.isValid(getParam("ValidCard").split(","), getHostCard().getController(),
                    getHostCard(), null)) {
                return false;
            }
        }

        if (hasParam("ValidCause")) {
            if (!runParams2.containsKey("Cause") ) {
                return false;
            }
            SpellAbility cause = (SpellAbility) runParams2.get("Cause");
            if (!cause.getHostCard().isValid(getParam("ValidCause").split(","), getHostCard().getController(),
                    getHostCard(), null)) {
                return false;
            }
        }

        // Check number of lands ETB this turn on triggered card's controller
        if (hasParam("CheckOnTriggeredCard")) {
            final String[] condition = getParam("CheckOnTriggeredCard").split(" ", 2);

            final Card host = hostCard.getGame().getCardState(hostCard);
            final String comparator = condition.length < 2 ? "GE1" : condition[1];
            final int referenceValue = AbilityUtils.calculateAmount(host, comparator.substring(2), this);
            final Card triggered = (Card)runParams2.get("Card"); 
            final int actualValue = CardFactoryUtil.xCount(triggered, host.getSVar(condition[0]));
            if (!Expressions.compare(actualValue, comparator.substring(0, 2), referenceValue)) {
                return false;
            }
        }

        // Check amount of damage dealt to the triggered card
        if (hasParam("DamageReceivedCondition")) {
            final String cond = getParam("DamageReceivedCondition");
            if (cond.length() < 3) {
                return false;
            }

            final Card card;
            final int rightSide;
            try {
                card = (Card) runParams2.get("Card");
                rightSide = Integer.parseInt(cond.substring(2));
            } catch (NumberFormatException | ClassCastException e) {
                return false;
            }
            if (card == null) {
                return false;
            }

            final boolean expr = Expressions.compare(card.getTotalDamageRecievedThisTurn(), cond, rightSide);
            if (!expr) {
                return false;
            }
        }

        if (hasParam("OncePerEffect")) {
            // A "once per effect" trigger will only trigger once regardless of how many things the effect caused
            // to change zones.

            // check if this is triggered by a cost payment & only fire if it isn't a duplicate trigger
            IndividualCostPaymentInstance currentPayment = (IndividualCostPaymentInstance) runParams2.get("IndividualCostPaymentInstance");
            if (currentPayment != null) {  // only if there is an active cost

                // each cost in a payment can trigger the effect for example Sinsiter Concoction has five costs:
                // {B}, Pay one life, Mill a card, Discard a Card, and sacrifice Sinister Concoction
                // If you mill a land and discard a land, The Gitrog Moster should trigger twice since each of these
                // costs is an independent action
                // however, due to forge implementation multiple triggers may be created for a single cost. For example,
                // Zombie Infestation has a cost of "Discard two cards".  If you discard two lands, The Gitrog Moster
                // should only trigger once because discarding two lands is a single action.
                return this.processedCostEffects.add(currentPayment.getId());
            }
            // otherwise use the stack ability
            else {
                // The SpellAbilityStackInstance keeps track of which host cards with "OncePerEffect"
                // triggers already fired as a result of that effect.
                // TODO This isn't quite ideal, since it really should be keeping track of the SpellAbility of the host
                // card, rather than keeping track of the host card itself - but it's good enough for now - since there
                // are no cards with multiple different OncePerEffect triggers.
                SpellAbilityStackInstance si = (SpellAbilityStackInstance) runParams2.get("SpellAbilityStackInstance");

                // si == null means the stack is empty
                return si == null || !si.hasOncePerEffectTrigger(this);
            }
        }

        /* this trigger can only be activated once per turn, verify it hasn't already run */
        if (hasParam("ActivationLimit")) {
            return this.getActivationsThisTurn() < Integer.parseInt(getParam("ActivationLimit"));
        }

        return true;
    }

    /** {@inheritDoc} */
    @Override
    public final void setTriggeringObjects(final SpellAbility sa) {
        sa.setTriggeringObject("Card", this.getRunParams().get("Card"));
    }

    @Override
    public String getImportantStackObjects(SpellAbility sa) {
        StringBuilder sb = new StringBuilder();
        sb.append("Zone Changer: ").append(sa.getTriggeringObject("Card"));
        return sb.toString();
    }

    @Override
    // Resets the state stored each turn for per-instance restriction
    public void resetTurnState() {
        super.resetTurnState();
        this.processedCostEffects.clear();
    }
}
