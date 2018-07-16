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
package forge.game.cost;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * Cost_Payment class.
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class CostPayment {
    private final Cost cost;
    private Cost adjustedCost;
    private final SpellAbility ability;
    private final List<CostPart> paidCostParts = Lists.newArrayList();

    /**
     * <p>
     * Getter for the field <code>cost</code>.
     * </p>
     * 
     * @return a {@link forge.game.cost.Cost} object.
     */
    public final Cost getCost() {
        return this.cost;
    }

    public final SpellAbility getAbility() {
        return this.ability;
    }
    
    /**
     * <p>
     * Constructor for Cost_Payment.
     * </p>
     * 
     * @param cost
     *            a {@link forge.game.cost.Cost} object.
     * @param abil
     *            a {@link forge.game.spellability.SpellAbility} object.
     */
    public CostPayment(final Cost cost, final SpellAbility abil) {
        this.cost = cost;
        this.adjustedCost = cost;
        this.ability = abil;
    }

    /**
     * <p>
     * canPayAdditionalCosts.
     * </p>
     * 
     * @param cost
     *            a {@link forge.game.cost.Cost} object.
     * @param ability
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a boolean.
     */
    public static boolean canPayAdditionalCosts(final Cost cost, final SpellAbility ability) {
        if (cost == null) {
            return true;
        }

        return cost.canPay(ability);
    }

    /**
     * <p>
     * isAllPaid.
     * </p>
     * 
     * @return a boolean.
     */
    public final boolean isFullyPaid() {
        for (final CostPart part : adjustedCost.getCostParts()) {
            if (!this.paidCostParts.contains(part)) {
                return false;
            }
        }

        return true;
    }

    /**
     * <p>
     * cancelPayment.
     * </p>
     */
    public final void refundPayment() {
        Card sourceCard = this.ability.getHostCard();
        for (final CostPart part : this.paidCostParts) {
            if (part.isUndoable()) {
                part.refund(sourceCard);
            }
        }

        // Move this to CostMana
        this.ability.getActivatingPlayer().getManaPool().refundManaPaid(this.ability);
    }

    public boolean payCost(final CostDecisionMakerBase decisionMaker) {
        adjustedCost = CostAdjustment.adjust(cost, ability);
        final List<CostPart> costParts = adjustedCost.getCostPartsWithZeroMana();

        final Game game = decisionMaker.getPlayer().getGame();
        CostPart lastPart = null;

        int index = 0;
        for (final CostPart part : costParts) {
            // Wrap the cost and push onto the cost stack
            game.costPaymentStack.push(part, this);

            if(lastPart != null && lastPart instanceof CostDiscard && part instanceof CostSacrifice) {
                part.setMustPay(true);
            }

            PaymentDecision pd = part.accept(decisionMaker);

            if (pd == null || !part.payAsDecided(decisionMaker.getPlayer(), pd, ability)) {
                part.setMustPay(false);
                game.costPaymentStack.pop(); // cost is resolved
                return false;
            }
            this.paidCostParts.add(part);
            if(index < cost.getCostParts().size()) {
                cost.getCostParts().get(index).setPaidAmount(part.getPaidAmount());
            }

            part.setMustPay(false);
            game.costPaymentStack.pop(); // cost is resolved

            lastPart = part;

            index++;
        }

        // this clears lists used for undo. 
        for (final CostPart part1 : this.paidCostParts) {
            if (part1 instanceof CostPartWithList) {
                ((CostPartWithList) part1).resetLists();
            }
        }

        return true;
    }

    public final boolean payComputerCosts(final CostDecisionMakerBase decisionMaker) {
        // Just in case it wasn't set, but honestly it shouldn't have gotten
        // here without being set
        if (this.ability.getActivatingPlayer() == null) {
            this.ability.setActivatingPlayer(decisionMaker.getPlayer());
        }

        Map<CostPart, PaymentDecision> decisions = Maps.newHashMap();
        
        List<CostPart> parts = CostAdjustment.adjust(cost, ability).getCostParts();
        // Set all of the decisions before attempting to pay anything

        final Game game = decisionMaker.getPlayer().getGame();

        for (final CostPart part : parts) {
            PaymentDecision decision = part.accept(decisionMaker);
            // the AI will try to exile the same card repeatedly unless it does it immediately
            final boolean payImmediately = part instanceof CostExile && ((CostExile) part).from == ZoneType.Library;
            if (null == decision) return false;

            // wrap the payment and push onto the cost stack
            game.costPaymentStack.push(part, this);
            if ((decisionMaker.paysRightAfterDecision() || payImmediately) && !part.payAsDecided(decisionMaker.getPlayer(), decision, ability)) {
                game.costPaymentStack.pop(); // cost is resolved
                return false;
            }

            game.costPaymentStack.pop(); // cost is either paid or deferred
            decisions.put(part, decision);
        }

        for (final CostPart part : parts) {
            // wrap the payment and push onto the cost stack
            game.costPaymentStack.push(part, this);

            if (!part.payAsDecided(decisionMaker.getPlayer(), decisions.get(part), this.ability)) {
                game.costPaymentStack.pop(); // cost is resolved
                return false;
            }
            // abilities care what was used to pay for them
            if( part instanceof CostPartWithList ) {
                ((CostPartWithList) part).resetLists();
            }

            game.costPaymentStack.pop(); // cost is resolved
        }
        return true;
    }
}
