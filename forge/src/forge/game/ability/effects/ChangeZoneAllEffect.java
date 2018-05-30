package forge.game.ability.effects;

import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.*;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.util.Lang;
import forge.util.TextUtil;

import java.util.List;
import java.util.Map;

public class ChangeZoneAllEffect extends SpellAbilityEffect {
    @Override
    protected String getStackDescription(SpellAbility sa) {
        // TODO build Stack Description will need expansion as more cards are added

        final String[] desc = sa.getDescription().split(":");

        if (desc.length > 1) {
            return desc[1];
        } else {
            return desc[0];
        }
    }

    @Override
    public void resolve(SpellAbility sa) {
        final ZoneType destination = ZoneType.smartValueOf(sa.getParam("Destination"));
        final List<ZoneType> origin = ZoneType.listValueOf(sa.getParam("Origin"));

        CardCollection cards;
        List<Player> tgtPlayers = getTargetPlayers(sa);
        final Game game = sa.getActivatingPlayer().getGame();
        final Card source = sa.getHostCard();

        if ((!sa.usesTargeting() && !sa.hasParam("Defined")) || sa.hasParam("UseAllOriginZones")) {
            cards = new CardCollection(game.getCardsIn(origin));
        } else {
            cards = new CardCollection();
            for (final Player p : tgtPlayers) {
                cards.addAll(p.getCardsIn(origin));
            }
            if (origin.contains(ZoneType.Library) && sa.hasParam("Search")) {
                // Search library using changezoneall effect need a param "Search"
                if (sa.getActivatingPlayer().hasKeyword("LimitSearchLibrary")) {
                    for (final Player p : tgtPlayers) {
                        cards.removeAll(p.getCardsIn(ZoneType.Library));
                        int fetchNum = Math.min(p.getCardsIn(ZoneType.Library).size(), 4);
                        cards.addAll(p.getCardsIn(ZoneType.Library, fetchNum));
                    }
                }
                if (sa.getActivatingPlayer().hasKeyword("CantSearchLibrary")) {
                    // all these cards have "then that player shuffles", mandatory shuffle
                    cards.removeAll(game.getCardsIn(ZoneType.Library));
                }
            }
        }

        if (origin.contains(ZoneType.Library) && sa.hasParam("Search") && !sa.getActivatingPlayer().hasKeyword("CantSearchLibrary")) {
            CardCollection libCards = CardLists.getValidCards(cards, "Card.inZoneLibrary", sa.getActivatingPlayer(), source);
            CardCollection libCardsYouOwn = CardLists.filterControlledBy(libCards, sa.getActivatingPlayer());
            if (!libCardsYouOwn.isEmpty()) { // Only searching one's own library would fire Archive Trap's altcost
                sa.getActivatingPlayer().incLibrarySearched();
            }
            if (!libCards.isEmpty()) {
                sa.getActivatingPlayer().getController().reveal(libCards, ZoneType.Library, libCards.get(0).getOwner());
            }
            final Map<String, Object> runParams = Maps.newHashMap();
            runParams.put("Player", sa.getActivatingPlayer());
            runParams.put("Target", tgtPlayers);
            game.getTriggerHandler().runTrigger(TriggerType.SearchedLibrary, runParams, false);
        }
        if (origin.contains(ZoneType.Hand) && sa.hasParam("Search")) {
            CardCollection handCards = CardLists.filterControlledBy(CardLists.getValidCards(cards, "Card.inZoneHand", sa.getActivatingPlayer(), source),
                sa.getActivatingPlayer().getOpponents());
            if (!handCards.isEmpty()) {
                sa.getActivatingPlayer().getController().reveal(handCards, ZoneType.Hand, handCards.get(0).getOwner());
            }
        }
        
        cards = (CardCollection)AbilityUtils.filterListByType(cards, sa.getParam("ChangeType"), sa);

        if (sa.hasParam("Optional")) {
            final String targets = Lang.joinHomogenous(cards);
            final String message;
            if (sa.hasParam("OptionQuestion")) {
            	message = TextUtil.fastReplace(sa.getParam("OptionQuestion"), "TARGETS", targets);
            } else {
            	final StringBuilder sb = new StringBuilder();

            	sb.append("Move ");
            	sb.append(targets);
            	sb.append(" from ");
            	sb.append(Lang.joinHomogenous(origin));
            	sb.append(" to ");
            	sb.append(destination);
            	sb.append("?");

            	message = sb.toString();
            }

            if (!sa.getActivatingPlayer().getController().confirmAction(sa, null, message)) {
                return;
            }
        }

        if (sa.hasParam("ForgetOtherRemembered")) {
            sa.getHostCard().clearRemembered();
        }

        final String remember = sa.getParam("RememberChanged");
        final String forget = sa.getParam("ForgetChanged");
        final String imprint = sa.getParam("Imprint");
        final boolean random = sa.hasParam("RandomOrder");
        final boolean remLKI = sa.hasParam("RememberLKI");

        final int libraryPos = sa.hasParam("LibraryPosition") ? Integer.parseInt(sa.getParam("LibraryPosition")) : 0;

        if ((destination == ZoneType.Library || destination == ZoneType.PlanarDeck)
        		&& !sa.hasParam("Shuffle") && cards.size() >= 2 && !random) {
            Player p = AbilityUtils.getDefinedPlayers(source, sa.getParamOrDefault("DefinedPlayer", "You"), sa).get(0);
            cards = (CardCollection) p.getController().orderMoveToZoneList(cards, destination);
        }

        if (destination == ZoneType.Graveyard) {
            cards = (CardCollection) GameActionUtil.orderCardsByTheirOwners(game, cards, ZoneType.Graveyard);
        }

        if (destination.equals(ZoneType.Library) && random) {
            CardLists.shuffle(cards);
        }
        // movedCards should have same timestamp
        long ts = game.getNextTimestamp();
        final Map<ZoneType, CardCollection> triggerList = Maps.newEnumMap(ZoneType.class);
        for (final Card c : cards) {
            final Zone originZone = game.getZoneOf(c);

            // Fizzle spells so that they are removed from stack (e.g. Summary Dismissal)
            if (sa.hasParam("Fizzle")) {
                if (originZone.is(ZoneType.Exile) || originZone.is(ZoneType.Hand) || originZone.is(ZoneType.Stack)) {
                    game.getStack().remove(c);
                }
            }

            if (destination == ZoneType.Battlefield) {
                // Auras without Candidates stay in their current location
                if (c.isAura()) {
                    final SpellAbility saAura = c.getFirstAttachSpell();
                    if (!saAura.getTargetRestrictions().hasCandidates(saAura, false)) {
                        continue;
                    }
                }
                if (sa.hasParam("Tapped")) {
                    c.setTapped(true);
                }
            }
            Card movedCard = null;
            if (sa.hasParam("GainControl")) {
                c.setController(sa.getActivatingPlayer(), game.getNextTimestamp());
                movedCard = game.getAction().moveToPlay(c, sa.getActivatingPlayer(), sa);
            } else {
                movedCard = game.getAction().moveTo(destination, c, libraryPos, sa);
                if (destination == ZoneType.Exile && !c.isToken()) {
                    Card host = sa.getOriginalHost();
                    if (host == null) {
                        host = sa.getHostCard();
                    }
                    movedCard.setExiledWith(host);
                }
                if (sa.hasParam("ExileFaceDown")) {
                    movedCard.setState(CardStateName.FaceDown, true);
                }
                if (sa.hasParam("Tapped")) {
                    movedCard.setTapped(true);
                }
            }

            if (remember != null) {
                game.getCardState(source).addRemembered(movedCard);
                if (!source.isRemembered(movedCard)) {
                    source.addRemembered(movedCard);
                }
            }
            if (remLKI && movedCard != null) {
                final Card lki = CardUtil.getLKICopy(c);
                game.getCardState(source).addRemembered(lki);
                if (!source.isRemembered(lki)) {
                    source.addRemembered(lki);
                }
            }
            if (forget != null) {
                game.getCardState(source).removeRemembered(c);
            }
            if (imprint != null) {
                game.getCardState(source).addImprintedCard(movedCard);
            }
            if (destination == ZoneType.Battlefield) {
                movedCard.setTimestamp(ts);
            }
            
            if (!movedCard.getZone().equals(originZone)) {
                if (!triggerList.containsKey(originZone.getZoneType())) {
                    triggerList.put(originZone.getZoneType(), new CardCollection());
                }
                triggerList.get(originZone.getZoneType()).add(movedCard);
            }
        }

        game.getTriggerHandler().resetActiveTriggers(false);

        if (!triggerList.isEmpty()) {
            final Map<String, Object> runParams = Maps.newHashMap();
            runParams.put("Cards", triggerList);
            runParams.put("Destination", destination);
            game.getTriggerHandler().runTrigger(TriggerType.ChangesZoneAll, runParams, false);
        }

        // if Shuffle parameter exists, and any amount of cards were owned by
        // that player, then shuffle that library
        if (sa.hasParam("Shuffle")) {
            for (Player p : game.getPlayers()) {
                if (Iterables.any(cards, CardPredicates.isOwner(p))) {
                    p.shuffle(sa);
                }
            }
        }
    }

}
