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
package forge.game.spellability;

import com.google.common.collect.Sets;

import forge.card.CardStateName;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardUtil;
import forge.game.cost.Cost;
import forge.game.cost.CostPayment;
import forge.game.player.Player;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

/**
 * <p>
 * Abstract Spell class.
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public abstract class Spell extends SpellAbility implements java.io.Serializable, Cloneable {

    /** Constant <code>serialVersionUID=-7930920571482203460L</code>. */
    private static final long serialVersionUID = -7930920571482203460L;

    private static boolean performanceMode = false;

    public static void setPerformanceMode(boolean performanceMode){
        Spell.performanceMode=performanceMode;
    }

    private boolean castFaceDown = false;

    /**
     * <p>
     * Constructor for Spell.
     * </p>
     * 
     * @param sourceCard
     *            a {@link forge.game.card.Card} object.
     */
    public Spell(final Card sourceCard) {
        this(sourceCard, new Cost(sourceCard.getManaCost(), false));
    }
    public Spell(final Card sourceCard, final Cost abCost) {
        super(sourceCard, abCost);

        this.setStackDescription(sourceCard.getSpellText());
        this.getRestrictions().setZone(ZoneType.Hand);
    }

    /** {@inheritDoc} */
    @Override
    public boolean canPlay() {
        Card card = this.getHostCard();

        // Save the original cost and the face down info for a later check since the LKI copy will overwrite them
        ManaCost origCost = card.getState(card.isFaceDown() ? CardStateName.Original : card.getCurrentStateName()).getManaCost();
        boolean wasFaceDownInstant = card.isFaceDown()
                && !card.getLastKnownZone().is(ZoneType.Battlefield)
                && card.getState(CardStateName.Original).getType().isInstant();

        Player activator = this.getActivatingPlayer();
        if (activator == null) {
            activator = card.getController();
            if (activator == null) {
            	return false;
            }
        }

        final Game game = activator.getGame();
        if (game.getStack().isSplitSecondOnStack()) {
            return false;
        }

        boolean isInstant = card.isInstant();
        // special case for split cards
        if (card.isSplitCard()) {
            CardStateName name = isLeftSplit() ? CardStateName.LeftSplit : CardStateName.RightSplit;
            isInstant = card.getState(name).getType().isInstant();
        }

        boolean lkicheck = false;
        boolean flash = false;

        // do performanceMode only for cases where the activator is different than controller
        if (!Spell.performanceMode && activator != null && !card.getController().equals(activator) && getMayPlay() == null
                && !card.isInZone(ZoneType.Battlefield)) {
            // always make a lki copy in this case?
            card = CardUtil.getLKICopy(card);
            card.setController(activator, 0);
            lkicheck = true;
        }

        if (hasParam("Bestow") && !card.isBestowed() && !card.isInZone(ZoneType.Battlefield)) {
            // Rule 601.3: cast Bestow with Flash
            // for the check the card does need to be animated
            // otherwise the StaticAbility will not found them
            if (!card.isLKI()) {
                card = CardUtil.getLKICopy(card);
            }
            card.animateBestow(false);
            lkicheck = true;
        } else if (isCastFaceDown()) {
            // need a copy of the card to turn facedown without trigger anything
            if (!card.isLKI()) {
                card = CardUtil.getLKICopy(card);
            }
            card.turnFaceDownNoUpdate();
            lkicheck = true;
        }


        if (lkicheck) {
            game.getTracker().freeze(); //prevent views flickering during while updating for state-based effects
            game.getAction().checkStaticAbilities(false, Sets.newHashSet(card), new CardCollection(card));
        }

        flash = card.withFlash(activator);

        // reset static abilities
        if (lkicheck) {
            game.getAction().checkStaticAbilities(false);
            game.getTracker().unfreeze();
        }

        if (!(isInstant || activator.canCastSorcery() || flash || getRestrictions().isInstantSpeed()
               || hasSVar("IsCastFromPlayEffect")
               || wasFaceDownInstant)) {
            return false;
        }

        if (!this.getRestrictions().canPlay(getHostCard(), this)) {
            return false;
        }

        // for uncastables like lotus bloom, check if manaCost is blank (except for morph spells)
        // but ignore if it comes from PlayEffect
        if (!isCastFaceDown()
                && !hasSVar("IsCastFromPlayEffect")
                && isBasicSpell()
                && origCost.isNoCost()) {
            return false;
        }

        if (this.getPayCosts() != null) {
            if (!CostPayment.canPayAdditionalCosts(this.getPayCosts(), this)) {
                return false;
            }
        }

        return checkOtherRestrictions();
    } // canPlay()
    
    public boolean checkOtherRestrictions() {
        final Card source = this.getHostCard();
        Player activator = getActivatingPlayer();
        final Game game = activator.getGame();
        // CantBeCast static abilities
        final CardCollection allp = new CardCollection(game.getCardsIn(ZoneType.listValueOf("Battlefield,Command")));
        allp.add(source);
        for (final Card ca : allp) {
            final FCollectionView<StaticAbility> staticAbilities = ca.getStaticAbilities();
            for (final StaticAbility stAb : staticAbilities) {
                if (stAb.applyAbility("CantBeCast", source, activator)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public final Object clone() {
        try {
            return super.clone();
        } catch (final Exception ex) {
            throw new RuntimeException("Spell : clone() error, " + ex);
        }
    }

    @Override
    public boolean isSpell() { return true; }
    @Override
    public boolean isAbility() { return false; }


    /**
     * @return the castFaceDown
     */
    @Override
    public boolean isCastFaceDown() {
        return castFaceDown;
    }

    /**
     * @param faceDown the castFaceDown to set
     */
    public void setCastFaceDown(boolean faceDown) {
        this.castFaceDown = faceDown;
    }

    public boolean meetsCommonRequirements() {
    	return meetsCommonRequirements(this.mapParams);
    }
}
