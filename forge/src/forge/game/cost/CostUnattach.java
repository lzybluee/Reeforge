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

import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

/**
 * The Class CostUnattach.
 */
public class CostUnattach extends CostPartWithList {
    // Unattach<CARDNAME> if ability is on the Equipment
    // Unattach<Card.Attached+namedHeartseeker/Equipped Heartseeker> if equipped creature has the ability

    /**
     * Instantiates a new cost unattach.
     */
    public CostUnattach(final String type, final String desc) {
        super("1", type, desc);
    }

    @Override
    public boolean isUndoable() { return false; }

    @Override
    public boolean isReusable() { return true; }

    /*
     * (non-Javadoc)
     * 
     * @see forge.card.cost.CostPart#toString()
     */
    @Override
    public final String toString() {
        return String.format("Unattach %s", this.getTypeDescription());
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.card.cost.CostPart#canPay(forge.card.spellability.SpellAbility,
     * forge.Card, forge.Player, forge.card.cost.Cost)
     */
    @Override
    public final boolean canPay(final SpellAbility ability, final Player payer) {
        final Card source = ability.getHostCard();

        final String type = this.getType();
        if (type.equals("CARDNAME")) {
            if (source.isEquipping()) {
                return true;
            }
        } else if (type.equals("OriginalHost")) {
            Card originalEquipment = ability.getOriginalHost();
            if (originalEquipment.isEquipping()) {
                return true;
            }
        } else {
            if (CardLists.getValidCards(source.getEquippedBy(false), type, payer, source).size() > 0) {
                return true;
            }
        }

        return false;
    }

    public Card findCardToUnattach(final Card source, Player activator, SpellAbility ability) {
        if (getType().equals("CARDNAME")) {
            if (source.isEquipping()) {
                return source;
            }
        } else if (getType().equals("OriginalHost")) {
            Card originalEquipment = ability.getOriginalHost();
            if (originalEquipment.isEquipping()) {
                return originalEquipment;
            }
        } else {
            List<Card> attachees = CardLists.getValidCards(source.getEquippedBy(false), this.getType(), activator, source);
            if (attachees.size() > 0) {
                // Just pick the first one, although maybe give a dialog
                return attachees.get(0);
            }
        }
        return null;
    }

    /* (non-Javadoc)
     * @see forge.card.cost.CostPartWithList#executePayment(forge.card.spellability.SpellAbility, forge.Card)
     */
    @Override
    protected Card doPayment(SpellAbility ability, Card targetCard) {
        targetCard.unEquipCard(targetCard.getEquipping());
        return targetCard;
    }

    @Override
    public String getHashForLKIList() {
        return "Unattached";
    }
    @Override
    public String getHashForCardList() {
    	return "UnattachedCards";
    }

   
    public <T> T accept(ICostVisitor<T> visitor) {
        return visitor.visit(this);
    }

}