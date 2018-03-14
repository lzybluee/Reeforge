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
package forge.game.zone;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CounterType;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.Lang;
import forge.util.TextUtil;

/**
 * <p>
 * DefaultPlayerZone class.
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class PlayerZone extends Zone {
    private static final long serialVersionUID = -5687652485777639176L;

    // the this is not the owner of the card
    private static Predicate<Card> alienCardsActivationFilter(final Player who) {
        return new Predicate<Card>() {
            @Override
            public boolean apply(final Card c) {
                if (!c.mayPlay(who).isEmpty() || c.mayPlayerLook(who)) {
                    return true;
                }
                return false;
            }
        };
    }

    private final class OwnCardsActivationFilter implements Predicate<Card> {
        @Override
        public boolean apply(final Card c) {
            if (c.mayPlayerLook(c.getController())) {
                return true;
            }

            if (c.isLand() && (!c.mayPlay(c.getController()).isEmpty())) {
                return true;
            }

            for (final SpellAbility sa : c.getSpellAbilities()) {
                final ZoneType restrictZone = sa.getRestrictions().getZone();

                // for mayPlay the restrictZone is null for reasons
                if (sa.isSpell() && c.mayPlay(sa.getMayPlay()) != null) {
                    return true;
                }

                if (sa.isSpell() && c.mayPlay(player).size() > 0) {
                    return true;
                }

                if (PlayerZone.this.is(restrictZone)) {
                    return true;
                }

                if (sa.isSpell()
                        && (c.hasStartOfKeyword("Flashback") && PlayerZone.this.is(ZoneType.Graveyard))
                        && restrictZone.equals(ZoneType.Hand)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Player player;

    public PlayerZone(final ZoneType zone, final Player inPlayer) {
        super(zone, inPlayer.getGame());
        player = inPlayer;
    }

    @Override
    protected void onChanged() {
        player.updateZoneForView(this);
    }

    public final Player getPlayer() {
        return player;
    }

    @Override
    public final String toString() {
        return TextUtil.concatWithSpace(Lang.getPossesive(player.toString()), zoneType.toString());
    }

    public CardCollectionView getCardsPlayerCanActivate(Player who) {
        CardCollectionView cl = getCards(false);
        boolean checkingForOwner = who == player;

        if (checkingForOwner && (is(ZoneType.Battlefield) || is(ZoneType.Hand))) {
            return cl;
        }

        // Only check the top card of the library
        Iterable<Card> cards = cl;
        if (is(ZoneType.Library)) {
            cards = Iterables.limit(cards, 1);
        }

        final Predicate<Card> filterPredicate = checkingForOwner ? new OwnCardsActivationFilter() : alienCardsActivationFilter(who);
        return CardLists.filter(cl, filterPredicate);
    }

    public CardCollectionView getCardsRetrace(Player who, CardCollectionView hand) {
        boolean checkingForOwner = who == player;

        CardCollection cards = new CardCollection();
        
        if (hand == null || hand.size() == 0) {
            return cards;
        }

        boolean hasLand = false;
        for (Card c : hand) {
            if (c.isLand()) {
                hasLand = true;
                break;
            }
        }
        if (!hasLand) {
            return cards;
        }

        if (checkingForOwner) {
            CardCollectionView cl = getCards(false);
            for(Card c : cl) {
                for (KeywordInterface keyword : c.getKeywords()) {
                    if (keyword.getKeyword() != null && keyword.getOriginal().startsWith("Retrace")) {
                        cards.add(c);
                    }
                }
            }
        }

        return cards;
    }

    public CardCollectionView getCardsSuspended(Player who) {
        boolean checkingForOwner = who == player;

        CardCollection cards = new CardCollection();
        if (checkingForOwner) {
            CardCollectionView cl = getCards(false);
            for(Card c : cl) {
                if(c.hasSuspend() && c.getCounters(CounterType.TIME) >= 1) {
                    cards.add(c);
                }
            }
        }

        return cards;
    }
}
