package forge.game.card;

import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Iterables;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.Direction;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.card.CardPredicates.Presets;
import forge.game.combat.AttackingBand;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.util.Expressions;
import forge.util.collect.FCollectionView;

public class CardProperty {

    public static boolean cardHasProperty(Card card, String property, Player sourceController, Card source,
            SpellAbility spellAbility) {
        final Game game = card.getGame();
        final Combat combat = game.getCombat();
        // lki can't be null but it does return this
        final Card lki = game.getChangeZoneLKIInfo(card);
        final Player controller = lki.getController();

        // by name can also have color names, so needs to happen before colors.
        if (property.startsWith("named")) {
            String name = property.substring(5).replace(";", ","); // for some legendary cards
            if (!card.sharesNameWith(name)) {
                return false;
            }   
        } else if (property.startsWith("notnamed")) {
            if (card.sharesNameWith(property.substring(8))) {
                return false;
            }
        } else if (property.startsWith("sameName")) {
            if (!card.sharesNameWith(source)) {
                return false;
            }
        } else if (property.equals("NamedCard")) {
            if (!card.sharesNameWith(source.getNamedCard())) {
                return false;
            }
        } else if (property.equals("NamedByRememberedPlayer")) {
            if (!source.hasRemembered()) {
                final Card newCard = game.getCardState(source);
                for (final Object o : newCard.getRemembered()) {
                    if (o instanceof Player) {
                        if (!card.sharesNameWith(((Player) o).getNamedCard())) {
                            return false;
                        }
                    }
                }
            }
            for (final Object o : source.getRemembered()) {
                if (o instanceof Player) {
                    if (!card.sharesNameWith(((Player) o).getNamedCard())) {
                        return false;
                    }
                }
            }
        } else if (property.equals("Permanent")) {
            if (card.isInstant() || card.isSorcery()) {
                return false;
            }
        } else if (property.startsWith("CardUID_")) {// Protection with "doesn't remove effect"
            if (card.getId() != Integer.parseInt(property.split("CardUID_")[1])) {
                return false;
            }
        } else if (property.equals("ChosenCard")) {
            if (!source.hasChosenCard(card)) {
                return false;
            }
        } else if (property.equals("nonChosenCard")) {
            if (source.hasChosenCard(card)) {
                return false;
            }
        } else if (property.equals("DoubleFaced")) {
            if (!card.isDoubleFaced()) {
                return false;
            }
        } else if (property.equals("Flip")) {
            if (!card.isFlipCard()) {
                return false;
            }
        } else if (property.startsWith("YouCtrl")) {
            if (!controller.equals(sourceController)) {
                return false;
            }
        } else if (property.startsWith("YouDontCtrl")) {
            if (controller.equals(sourceController)) {
                return false;
            }
        } else if (property.startsWith("OppCtrl")) {
            if (!controller.getOpponents().contains(sourceController)) {
                return false;
            }
        } else if (property.startsWith("ChosenCtrl")) {
            if (!controller.equals(source.getChosenPlayer())) {
                return false;
            }
        } else if (property.startsWith("DefenderCtrl")) {
            if (!game.getPhaseHandler().inCombat()) {
                return false;
            }
            if (property.endsWith("ForRemembered")) {
                if (!source.hasRemembered()) {
                    return false;
                }
                if (combat.getDefendingPlayerRelatedTo((Card) source.getFirstRemembered()) != controller) {
                    return false;
                }
            } else {
                if (combat.getDefendingPlayerRelatedTo(source) != controller) {
                    return false;
                }
            }
        } else if (property.startsWith("DefendingPlayer")) {
            Player p = property.endsWith("Ctrl") ? controller : card.getOwner();
            if (!game.getPhaseHandler().inCombat()) {
                return false;
            }
            if (!combat.isPlayerAttacked(p)) {
                return false;
            }
        } else if (property.startsWith("EnchantedPlayer")) {
            Player p = property.endsWith("Ctrl") ? controller : card.getOwner();
            final Object o = source.getEnchanting();
            if (o instanceof Player) {
                if (!p.equals(o)) {
                    return false;
                }
            } else { // source not enchanting a player
                return false;
            }
        } else if (property.startsWith("EnchantedController")) {
            Player p = property.endsWith("Ctrl") ? controller : card.getOwner();
            final Object o = source.getEnchanting();
            if (o instanceof Card) {
                if (!p.equals(((Card) o).getController())) {
                    return false;
                }
            } else { // source not enchanting a card
                return false;
            }
        } else if (property.startsWith("RememberedPlayer")) {
            Player p = property.endsWith("Ctrl") ? controller : card.getOwner();
            if (!source.hasRemembered()) {
                final Card newCard = game.getCardState(source);
                for (final Object o : newCard.getRemembered()) {
                    if (o instanceof Player) {
                        if (!p.equals(o)) {
                            return false;
                        }
                    }
                }
            }

            for (final Object o : source.getRemembered()) {
                if (o instanceof Player) {
                    if (!p.equals(o)) {
                        return false;
                    }
                }
            }
        } else if (property.startsWith("nonRememberedPlayerCtrl")) {
            if (!source.hasRemembered()) {
                final Card newCard = game.getCardState(source);
                if (newCard.isRemembered(controller)) {
                    return false;
                }
            }

            if (source.isRemembered(controller)) {
                return false;
            }
        } else if (property.equals("TargetedPlayerCtrl")) {
            for (final SpellAbility sa : source.getCurrentState().getNonManaAbilities()) {
                final SpellAbility saTargeting = sa.getSATargetingPlayer();
                if (saTargeting != null) {
                    for (final Player p : saTargeting.getTargets().getTargetPlayers()) {
                        if (!controller.equals(p)) {
                            return false;
                        }
                    }
                }
            }
        } else if (property.equals("TargetedControllerCtrl")) {
            for (final SpellAbility sa : source.getCurrentState().getNonManaAbilities()) {
                final CardCollectionView cards = AbilityUtils.getDefinedCards(source, "Targeted", sa);
                final List<SpellAbility> sas = AbilityUtils.getDefinedSpellAbilities(source, "Targeted", sa);
                for (final Card c : cards) {
                    final Player p = c.getController();
                    if (!controller.equals(p)) {
                        return false;
                    }
                }
                for (final SpellAbility s : sas) {
                    final Player p = s.getHostCard().getController();
                    if (!controller.equals(p)) {
                        return false;
                    }
                }
            }
        } else if (property.startsWith("ActivePlayerCtrl")) {
            if (!game.getPhaseHandler().isPlayerTurn(controller)) {
                return false;
            }
        } else if (property.startsWith("NonActivePlayerCtrl")) {
            if (game.getPhaseHandler().isPlayerTurn(controller)) {
                return false;
            }
        } else if (property.startsWith("YouOwn")) {
            if (!card.getOwner().equals(sourceController)) {
                return false;
            }
        } else if (property.startsWith("YouDontOwn")) {
            if (card.getOwner().equals(sourceController)) {
                return false;
            }
        } else if (property.startsWith("OppOwn")) {
            if (!card.getOwner().getOpponents().contains(sourceController)) {
                return false;
            }
        } else if (property.equals("TargetedPlayerOwn")) {
            for (final SpellAbility sa : source.getCurrentState().getNonManaAbilities()) {
                final SpellAbility saTargeting = sa.getSATargetingPlayer();
                if (saTargeting != null) {
                    for (final Player p : saTargeting.getTargets().getTargetPlayers()) {
                        if (!card.getOwner().equals(p)) {
                            return false;
                        }
                    }
                }
            }
        } else if (property.startsWith("OwnedBy")) {
            final String valid = property.substring(8);
            if (!card.getOwner().isValid(valid, sourceController, source, spellAbility)) {
                return false;
            }
        } else if (property.startsWith("ControlledBy")) {
            final String valid = property.substring(13);
            if (!controller.isValid(valid, sourceController, source, spellAbility)) {
                return false;
            }
        } else if (property.startsWith("OwnerDoesntControl")) {
            if (card.getOwner().equals(controller)) {
                return false;
            }
        } else if (property.startsWith("ControllerControls")) {
            final String type = property.substring(18);
            if (type.startsWith("AtLeastAsMany")) {
                String realType = type.split("AtLeastAsMany")[1];
                CardCollectionView cards = CardLists.getType(controller.getCardsIn(ZoneType.Battlefield), realType);
                CardCollectionView yours = CardLists.getType(sourceController.getCardsIn(ZoneType.Battlefield), realType);
                if (cards.size() < yours.size()) {
                    return false;
                }
            } else {
                final CardCollectionView cards = controller.getCardsIn(ZoneType.Battlefield);
                if (CardLists.getType(cards, type).isEmpty()) {
                    return false;
                }
            }
        } else if (property.startsWith("Other")) {
            if (card.equals(source)) {
                return false;
            }
        } else if (property.startsWith("StrictlySelf")) {
            if (!card.equals(source) || card.getTimestamp() != source.getTimestamp()) {
                return false;
            }
        } else if (property.startsWith("Self")) {
            if (!card.equals(source)) {
                return false;
            }
        } else if (property.startsWith("ExiledWithSource")) {
            if (card.getExiledWith() == null) {
                return false;
            }

            Card host = source;
            //Static Abilites doesn't have spellAbility or OriginalHost
            if (spellAbility != null) {
                host = spellAbility.getOriginalHost();
                if (host == null) {
                    host = spellAbility.getHostCard();
                }
            }

            if (!card.getExiledWith().equals(host)) {
                return false;
            }
        } else if (property.equals("EncodedWithSource")) {
            if (!card.getEncodedCards().contains(source)) {
                return false;
            }
        } else if (property.equals("EffectSource")) {
            if (!source.isEmblem() && !source.getType().hasSubtype("Effect")) {
                return false;
            }

            if (!card.equals(source.getEffectSource())) {
                return false;
            }
        } else if (property.equals("CanBeSacrificedBy")) {
            if (!card.canBeSacrificedBy(spellAbility)) {
                return false;
            }
        } else if (property.startsWith("AttachedBy")) {
            if (!card.isEquippedBy(source) && !card.isEnchantedBy(source) && !card.isFortifiedBy(source)) {
                return false;
            }
        } else if (property.equals("Attached")) {
            if (card.getEquipping() != source && !source.equals(card.getEnchanting()) && card.getFortifying() != source) {
                return false;
            }
        } else if (property.startsWith("AttachedTo")) {
            final String restriction = property.split("AttachedTo ")[1];
            if (restriction.equals("Targeted")) {
                if (!source.getCurrentState().getTriggers().isEmpty()) {
                    for (final Trigger t : source.getCurrentState().getTriggers()) {
                        final SpellAbility sa = t.getTriggeredSA();
                        final CardCollectionView cards = AbilityUtils.getDefinedCards(source, "Targeted", sa);
                        for (final Card c : cards) {
                            if (card.getEquipping() != c && !c.equals(card.getEnchanting())) {
                                return false;
                            }
                        }
                    }
                } else {
                    for (final SpellAbility sa : source.getCurrentState().getNonManaAbilities()) {
                        final CardCollectionView cards = AbilityUtils.getDefinedCards(source, "Targeted", sa);
                        for (final Card c : cards) {
                            if (card.getEquipping() == c || c.equals(card.getEnchanting())) { // handle multiple targets
                                return true;
                            }
                        }
                    }
                    return false;
                }
            } else {
                if ((card.getEnchanting() == null || !card.getEnchanting().isValid(restriction, sourceController, source, spellAbility))
                        && (card.getEquipping() == null || !card.getEquipping().isValid(restriction, sourceController, source, spellAbility))
                        && (card.getFortifying() == null || !card.getFortifying().isValid(restriction, sourceController, source, spellAbility))) {
                    return false;
                }
            }
        } else if (property.equals("NameNotEnchantingEnchantedPlayer")) {
            Player enchantedPlayer = source.getEnchantingPlayer();
            if (enchantedPlayer == null || enchantedPlayer.isEnchantedBy(card.getName())) {
                return false;
            }
        } else if (property.equals("NotAttachedTo")) {
            if (card.getEquipping() == source || source.equals(card.getEnchanting()) || card.getFortifying() == source) {
                return false;
            }
        } else if (property.startsWith("EnchantedBy")) {
            if (property.equals("EnchantedBy")) {
                if (!card.isEnchantedBy(source) && !card.equals(source.getEnchanting())) {
                    return false;
                }
            } else {
                final String restriction = property.split("EnchantedBy ")[1];
                switch (restriction) {
                    case "Imprinted":
                        for (final Card c : source.getImprintedCards()) {
                            if (!card.isEnchantedBy(c) && !card.equals(c.getEnchanting())) {
                                return false;
                            }
                        }
                        break;
                    case "Targeted":
                        for (final SpellAbility sa : source.getCurrentState().getNonManaAbilities()) {
                            final SpellAbility saTargeting = sa.getSATargetingCard();
                            if (saTargeting != null) {
                                for (final Card c : saTargeting.getTargets().getTargetCards()) {
                                    if (!card.isEnchantedBy(c) && !card.equals(c.getEnchanting())) {
                                        return false;
                                    }
                                }
                            }
                        }
                        break;
                    default:  // EnchantedBy Aura.Other
                        for (final Card aura : card.getEnchantedBy(false)) {
                            if (aura.isValid(restriction, sourceController, source, spellAbility)) {
                                return true;
                            }
                        }
                        return false;
                }
            }
        } else if (property.startsWith("NotEnchantedBy")) {
            if (property.substring(14).equals("Targeted")) {
                for (final SpellAbility sa : source.getCurrentState().getNonManaAbilities()) {
                    final SpellAbility saTargeting = sa.getSATargetingCard();
                    if (saTargeting != null) {
                        for (final Card c : saTargeting.getTargets().getTargetCards()) {
                            if (card.isEnchantedBy(c)) {
                                return false;
                            }
                        }
                    }
                }
            } else {
                if (card.isEnchantedBy(source)) {
                    return false;
                }
            }
        } else if (property.startsWith("Enchanted")) {
            if (!source.equals(card.getEnchanting())) {
                return false;
            }
        } else if (property.startsWith("CanEnchant")) {
            final String restriction = property.substring(10);
            if (restriction.equals("Remembered")) {
                for (final Object rem : source.getRemembered()) {
                    if (!(rem instanceof Card) || !((Card) rem).canBeEnchantedBy(card))
                        return false;
                }
            } else if (restriction.equals("Source")) {
                if (!source.canBeEnchantedBy(card)) return false;
            }
        } else if (property.startsWith("CanBeEnchantedBy")) {
            if (property.substring(16).equals("Targeted")) {
                for (final SpellAbility sa : source.getCurrentState().getNonManaAbilities()) {
                    final SpellAbility saTargeting = sa.getSATargetingCard();
                    if (saTargeting != null) {
                        for (final Card c : saTargeting.getTargets().getTargetCards()) {
                            if (!card.canBeEnchantedBy(c)) {
                                return false;
                            }
                        }
                    }
                }
            } else if (property.substring(16).equals("AllRemembered")) {
                for (final Object rem : source.getRemembered()) {
                    if (rem instanceof Card) {
                        final Card c = (Card) rem;
                        if (!card.canBeEnchantedBy(c)) {
                            return false;
                        }
                    }
                }
            } else {
                if (!card.canBeEnchantedBy(source)) {
                    return false;
                }
            }
        } else if (property.startsWith("EquippedBy")) {
            if (property.substring(10).equals("Targeted")) {
                for (final SpellAbility sa : source.getCurrentState().getNonManaAbilities()) {
                    final SpellAbility saTargeting = sa.getSATargetingCard();
                    if (saTargeting != null) {
                        for (final Card c : saTargeting.getTargets().getTargetCards()) {
                            if (!card.isEquippedBy(c)) {
                                return false;
                            }
                        }
                    }
                }
            } else if (property.substring(10).equals("Enchanted")) {
                if (source.getEnchantingCard() == null ||
                        !card.isEquippedBy(source.getEnchantingCard())) {
                    return false;
                }
            } else {
                if (!card.isEquippedBy(source)) {
                    return false;
                }
            }
        } else if (property.startsWith("FortifiedBy")) {
            if (!card.isFortifiedBy(source)) {
                return false;
            }
        } else if (property.startsWith("CanBeEquippedBy")) {
            if (!card.canBeEquippedBy(source)) {
                return false;
            }
        } else if (property.startsWith("Equipped")) {
            if (card.getEquipping() != source) {
                return false;
            }
        } else if (property.startsWith("Fortified")) {
            if (card.getFortifying() != source) {
                return false;
            }
        } else if (property.startsWith("HauntedBy")) {
            if (!card.isHauntedBy(source)) {
                return false;
            }
        } else if (property.startsWith("notTributed")) {
            if (card.isTributed()) {
                return false;
            }
        } else if (property.startsWith("madness")) {
            if (!card.isMadness()) {
                return false;
            }
        } else if (property.contains("Paired")) {
            if (property.contains("With")) { // PairedWith
                if (!card.isPaired() || card.getPairedWith() != source) {
                    return false;
                }
            } else if (property.startsWith("Not")) {  // NotPaired
                if (card.isPaired()) {
                    return false;
                }
            } else { // Paired
                if (!card.isPaired()) {
                    return false;
                }
            }
        } else if (property.startsWith("Above")) { // "Are Above" Source
            final CardCollectionView cards = card.getOwner().getCardsIn(ZoneType.Graveyard);
            if (cards.indexOf(source) >= cards.indexOf(card)) {
                return false;
            }
        } else if (property.startsWith("DirectlyAbove")) { // "Are Directly Above" Source
            final CardCollectionView cards = card.getOwner().getCardsIn(ZoneType.Graveyard);
            if (cards.indexOf(card) - cards.indexOf(source) != 1) {
                return false;
            }
        } else if (property.startsWith("TopGraveyardCreature")) {
            CardCollection cards = CardLists.filter(card.getOwner().getCardsIn(ZoneType.Graveyard), CardPredicates.Presets.CREATURES);
            Collections.reverse(cards);
            if (cards.isEmpty() || !card.equals(cards.get(0))) {
                return false;
            }
        } else if (property.startsWith("TopGraveyard")) {
            final CardCollection cards = new CardCollection(card.getOwner().getCardsIn(ZoneType.Graveyard));
            Collections.reverse(cards);
            if (property.substring(12).matches("[0-9][0-9]?")) {
                int n = Integer.parseInt(property.substring(12));
                int num = Math.min(n, cards.size());
                final CardCollection newlist = new CardCollection();
                for (int i = 0; i < num; i++) {
                    newlist.add(cards.get(i));
                }
                if (cards.isEmpty() || !newlist.contains(card)) {
                    return false;
                }
            } else {
                if (cards.isEmpty() || !card.equals(cards.get(0))) {
                    return false;
                }
            }
        } else if (property.startsWith("BottomGraveyard")) {
            final CardCollectionView cards = card.getOwner().getCardsIn(ZoneType.Graveyard);
            if (cards.isEmpty() || !card.equals(cards.get(0))) {
                return false;
            }
        } else if (property.startsWith("TopLibrary")) {
            final CardCollectionView cards = card.getOwner().getCardsIn(ZoneType.Library);
            if (cards.isEmpty() || !card.equals(cards.get(0))) {
                return false;
            }
        } else if (property.startsWith("Cloned")) {
            if ((card.getCloneOrigin() == null) || !card.getCloneOrigin().equals(source)) {
                return false;
            }
        } else if (property.startsWith("DamagedBy")) {
            if ((property.endsWith("Source") || property.equals("DamagedBy")) &&
                    !card.getReceivedDamageFromThisTurn().containsKey(source)) {
                return false;
            } else if (property.endsWith("Remembered")) {
                boolean matched = false;
                for (final Object obj : source.getRemembered()) {
                    if (!(obj instanceof Card)) {
                        continue;
                    }
                    matched |= card.getReceivedDamageFromThisTurn().containsKey(obj);
                }
                if (!matched)
                    return false;
            } else if (property.endsWith("Equipped")) {
                final Card equipee = source.getEquipping();
                if (equipee == null || !card.getReceivedDamageFromThisTurn().containsKey(equipee))
                    return false;
            } else if (property.endsWith("Enchanted")) {
                final Card equipee = source.getEnchantingCard();
                if (equipee == null || !card.getReceivedDamageFromThisTurn().containsKey(equipee))
                    return false;
            }
        } else if (property.startsWith("Damaged")) {
            if (!card.getDealtDamageToThisTurn().containsKey(source)) {
                return false;
            }
        } else if (property.startsWith("IsTargetingSource")) {
            for (final SpellAbility sa : card.getCurrentState().getNonManaAbilities()) {
                final SpellAbility saTargeting = sa.getSATargetingCard();
                if (saTargeting != null) {
                    for (final Card c : saTargeting.getTargets().getTargetCards()) {
                        if (c.equals(source)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } else if (property.startsWith("SharesCMCWith")) {
            if (property.equals("SharesCMCWith")) {
                if (!card.sharesCMCWith(source)) {
                    return false;
                }
            } else {
                final String restriction = property.split("SharesCMCWith ")[1];
                CardCollection list = AbilityUtils.getDefinedCards(source, restriction, spellAbility);
                return !CardLists.filter(list, CardPredicates.sharesCMCWith(card)).isEmpty();
            }
        } else if (property.startsWith("SharesColorWith")) {
            if (property.equals("SharesColorWith")) {
                if (!card.sharesColorWith(source)) {
                    return false;
                }
            } else {
                final String restriction = property.split("SharesColorWith ")[1];
                if (restriction.startsWith("Remembered") || restriction.startsWith("Imprinted")) {
                    CardCollection list = AbilityUtils.getDefinedCards(source, restriction, spellAbility);
                    return !CardLists.filter(list, CardPredicates.sharesColorWith(card)).isEmpty();
                }

                switch (restriction) {
                    case "TopCardOfLibrary":
                        final CardCollectionView cards = sourceController.getCardsIn(ZoneType.Library);
                        if (cards.isEmpty() || !card.sharesColorWith(cards.get(0))) {
                            return false;
                        }
                        break;
                    case "Equipped":
                        if (!source.isEquipment() || !source.isEquipping()
                                || !card.sharesColorWith(source.getEquipping())) {
                            return false;
                        }
                        break;
                    case "MostProminentColor":
                        byte mask = CardFactoryUtil.getMostProminentColors(game.getCardsIn(ZoneType.Battlefield));
                        if (!CardUtil.getColors(card).hasAnyColor(mask))
                            return false;
                        break;
                    case "LastCastThisTurn":
                        final List<Card> c = game.getStack().getSpellsCastThisTurn();
                        if (c.isEmpty() || !card.sharesColorWith(c.get(c.size() - 1))) {
                            return false;
                        }
                        break;
                    case "ActivationColor":
                        byte manaSpent = source.getColorsPaid();
                        if (!CardUtil.getColors(card).hasAnyColor(manaSpent)) {
                            return false;
                        }
                        break;
                    default:
                        for (final Card c1 : sourceController.getCardsIn(ZoneType.Battlefield)) {
                            if (c1.isValid(restriction, sourceController, source, spellAbility) && card.sharesColorWith(c1)) {
                                return true;
                            }
                        }
                        return false;
                }
            }
        } else if (property.startsWith("MostProminentColor")) {
            // MostProminentColor <color>
            // e.g. MostProminentColor black
            String[] props = property.split(" ");
            if (props.length == 1) {
                System.out.println("WARNING! Using MostProminentColor property without a color.");
                return false;
            }
            String color = props[1];

            byte mostProm = CardFactoryUtil.getMostProminentColors(game.getCardsIn(ZoneType.Battlefield));
            return ColorSet.fromMask(mostProm).hasAnyColor(MagicColor.fromName(color));
        } else if (property.startsWith("notSharesColorWith")) {
            if (property.equals("notSharesColorWith")) {
                if (card.sharesColorWith(source)) {
                    return false;
                }
            } else {
                final String restriction = property.split("notSharesColorWith ")[1];
                for (final Card c : sourceController.getCardsIn(ZoneType.Battlefield)) {
                    if (c.isValid(restriction, sourceController, source, spellAbility) && card.sharesColorWith(c)) {
                        return false;
                    }
                }
            }
        } else if (property.startsWith("sharesCreatureTypeWith")) {
            if (property.equals("sharesCreatureTypeWith")) {
                if (!card.sharesCreatureTypeWith(source)) {
                    return false;
                }
            } else {
                final String restriction = property.split("sharesCreatureTypeWith ")[1];
                switch (restriction) {
                    case "TopCardOfLibrary":
                        final CardCollectionView cards = sourceController.getCardsIn(ZoneType.Library);
                        if (cards.isEmpty() || !card.sharesCreatureTypeWith(cards.get(0))) {
                            return false;
                        }
                        break;
                    case "Commander":
                        final List<Card> cmdrs = sourceController.getCommanders();
                        for (Card cmdr : cmdrs) {
                            if (card.sharesCreatureTypeWith(cmdr)) {
                                return true;
                            }
                        }
                        return false;
                    case "Enchanted":
                        for (final SpellAbility sa : source.getCurrentState().getNonManaAbilities()) {
                            final SpellAbility root = sa.getRootAbility();
                            Card c = source.getEnchantingCard();
                            if ((c == null) && (root != null)
                                    && (root.getPaidList("Sacrificed") != null)
                                    && !root.getPaidList("Sacrificed").isEmpty()) {
                                c = root.getPaidList("Sacrificed").get(0).getEnchantingCard();
                                if (!card.sharesCreatureTypeWith(c)) {
                                    return false;
                                }
                            }
                        }
                        break;
                    case "Equipped":
                        if (source.isEquipping() && card.sharesCreatureTypeWith(source.getEquipping())) {
                            return true;
                        }
                        return false;
                    case "Remembered":
                        for (final Object rem : source.getRemembered()) {
                            if (rem instanceof Card) {
                                final Card c = (Card) rem;
                                if (card.sharesCreatureTypeWith(c)) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    case "AllRemembered":
                        for (final Object rem : source.getRemembered()) {
                            if (rem instanceof Card) {
                                final Card c = (Card) rem;
                                if (!card.sharesCreatureTypeWith(c)) {
                                    return false;
                                }
                            }
                        }
                        break;
                    default:
                        boolean shares = false;
                        for (final Card c : sourceController.getCardsIn(ZoneType.Battlefield)) {
                            if (c.isValid(restriction, sourceController, source, spellAbility) && card.sharesCreatureTypeWith(c)) {
                                shares = true;
                            }
                        }
                        if (!shares) {
                            return false;
                        }
                        break;
                }
            }
        } else if (property.startsWith("sharesCardTypeWith")) {
            if (property.equals("sharesCardTypeWith")) {
                if (!card.sharesCardTypeWith(source)) {
                    return false;
                }
            } else {
                final String restriction = property.split("sharesCardTypeWith ")[1];
                switch (restriction) {
                    case "Imprinted":
                        if (!source.hasImprintedCard() || !card.sharesCardTypeWith(Iterables.getFirst(source.getImprintedCards(), null))) {
                            return false;
                        }
                        break;
                    case "Remembered":
                        for (final Object rem : source.getRemembered()) {
                            if (rem instanceof Card) {
                                final Card c = (Card) rem;
                                if (card.sharesCardTypeWith(c)) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    case "EachTopLibrary":
                        final CardCollection cards = new CardCollection();
                        for (Player p : game.getPlayers()) {
                            final Card top = p.getCardsIn(ZoneType.Library).get(0);
                            cards.add(top);
                        }
                        for (Card c : cards) {
                            if (card.sharesCardTypeWith(c)) {
                                return true;
                            }
                        }
                        return false;
                }
            }
        } else if (property.equals("sharesPermanentTypeWith")) {
            if (!card.sharesPermanentTypeWith(source)) {
                return false;
            }
        } else if (property.equals("canProduceSameManaTypeWith")) {
            if (!card.canProduceSameManaTypeWith(source)) {
                return false;
            }
        } else if (property.startsWith("sharesNameWith")) {
            if (property.equals("sharesNameWith")) {
                if (!card.sharesNameWith(source)) {
                    return false;
                }
            } else {
                final String restriction = property.split("sharesNameWith ")[1];
                if (restriction.equals("YourGraveyard")) {
                    return !CardLists.filter(sourceController.getCardsIn(ZoneType.Graveyard), CardPredicates.sharesNameWith(card)).isEmpty();
                } else if (restriction.equals(ZoneType.Graveyard.toString())) {
                    return !CardLists.filter(game.getCardsIn(ZoneType.Graveyard), CardPredicates.sharesNameWith(card)).isEmpty();
                } else if (restriction.equals(ZoneType.Battlefield.toString())) {
                    return !CardLists.filter(game.getCardsIn(ZoneType.Battlefield), CardPredicates.sharesNameWith(card)).isEmpty();
                } else if (restriction.equals("ThisTurnCast")) {
                    return !CardLists.filter(CardUtil.getThisTurnCast("Card", source), CardPredicates.sharesNameWith(card)).isEmpty();
                } else if (restriction.startsWith("Remembered") || restriction.startsWith("Imprinted")) {
                    CardCollection list = AbilityUtils.getDefinedCards(source, restriction, spellAbility);
                    return !CardLists.filter(list, CardPredicates.sharesNameWith(card)).isEmpty();
                } else if (restriction.equals("MovedToGrave")) {
                    for (final SpellAbility sa : source.getCurrentState().getNonManaAbilities()) {
                        final SpellAbility root = sa.getRootAbility();
                        if (root != null && (root.getPaidList("MovedToGrave") != null)
                                && !root.getPaidList("MovedToGrave").isEmpty()) {
                            final CardCollectionView cards = root.getPaidList("MovedToGrave");
                            for (final Card c : cards) {
                                String name = c.getName();
                                if (StringUtils.isEmpty(name)) {
                                    name = c.getPaperCard().getName();
                                }
                                if (card.getName().equals(name)) {
                                    return true;
                                }
                            }
                        }
                    }
                    return false;
                } else if (restriction.equals("NonToken")) {
                    return !CardLists.filter(game.getCardsIn(ZoneType.Battlefield),
                            Presets.NON_TOKEN, CardPredicates.sharesNameWith(card)).isEmpty();
                } else if (restriction.equals("TriggeredCard")) {
                    if (spellAbility == null) {
                        System.out.println("Looking at TriggeredCard but no SA?");
                    } else {
                        Card triggeredCard = ((Card)spellAbility.getTriggeringObject("Card"));
                        if (triggeredCard != null && card.sharesNameWith(triggeredCard)) {
                            return true;
                        }
                    }
                    return false;
                }
            }
        } else if (property.startsWith("doesNotShareNameWith")) {
            if (property.equals("doesNotShareNameWith")) {
                if (card.sharesNameWith(source)) {
                    return false;
                }
            } else {
                final String restriction = property.split("doesNotShareNameWith ")[1];
                if (restriction.startsWith("Remembered") || restriction.startsWith("Imprinted")) {
                    CardCollection list = AbilityUtils.getDefinedCards(source, restriction, spellAbility);
                    return CardLists.filter(list, CardPredicates.sharesNameWith(card)).isEmpty();
                }
            }
        } else if (property.startsWith("sharesControllerWith")) {
            if (property.equals("sharesControllerWith")) {
                if (!card.sharesControllerWith(source)) {
                    return false;
                }
            } else {
                final String restriction = property.split("sharesControllerWith ")[1];
                if (restriction.startsWith("Remembered") || restriction.startsWith("Imprinted")) {
                    CardCollection list = AbilityUtils.getDefinedCards(source, restriction, spellAbility);
                    return !CardLists.filter(list, CardPredicates.sharesControllerWith(card)).isEmpty();
                }
            }
        } else if (property.startsWith("sharesOwnerWith")) {
            if (property.equals("sharesOwnerWith")) {
                if (!card.getOwner().equals(source.getOwner())) {
                    return false;
                }
            } else {
                final String restriction = property.split("sharesOwnerWith ")[1];
                if (restriction.equals("Remembered")) {
                    for (final Object rem : source.getRemembered()) {
                        if (rem instanceof Card) {
                            final Card c = (Card) rem;
                            if (!card.getOwner().equals(c.getOwner())) {
                                return false;
                            }
                        }
                    }
                }
            }
        } else if (property.startsWith("SecondSpellCastThisTurn")) {
            final List<Card> cards = CardUtil.getThisTurnCast("Card", source);
            if (cards.size() < 2)  {
                return false;
            }
            else if (cards.get(1) != card) {
                return false;
            }
        } else if (property.equals("ThisTurnCast")) {
            for (final Card c : CardUtil.getThisTurnCast("Card", source)) {
                if (card.equals(c)) {
                    return true;
                }
            }
            return false;
        } else if (property.startsWith("ThisTurnEntered")) {
            final String restrictions = property.split("ThisTurnEntered_")[1];
            final String[] res = restrictions.split("_");
            final ZoneType destination = ZoneType.smartValueOf(res[0]);
            ZoneType origin = null;
            if (res.length > 1 && res[1].equals("from")) {
                origin = ZoneType.smartValueOf(res[2]);
            }
            CardCollectionView cards = CardUtil.getThisTurnEntered(destination,
                    origin, "Card", source);
            if (!cards.contains(card)) {
                return false;
            }
        } else if (property.equals("DiscardedThisTurn")) {
            if (card.getTurnInZone() != game.getPhaseHandler().getTurn()) {
                return false;
            }

            CardCollectionView cards = CardUtil.getThisTurnEntered(ZoneType.Graveyard, ZoneType.Hand, "Card", source);
            if (!cards.contains(card) && !card.getMadnessWithoutCast()) {
                return false;
            }
        } else if (property.startsWith("ControlledByPlayerInTheDirection")) {
            final String restrictions = property.split("ControlledByPlayerInTheDirection_")[1];
            final String[] res = restrictions.split("_");
            final Direction direction = Direction.valueOf(res[0]);
            Player p = null;
            if (res.length > 1) {
                for (Player pl : game.getPlayers()) {
                    if (pl.isValid(res[1], sourceController, source, spellAbility)) {
                        p = pl;
                        break;
                    }
                }
            } else {
                p = sourceController;
            }
            if (p == null || !controller.equals(game.getNextPlayerAfter(p, direction))) {
                return false;
            }
        } else if (property.startsWith("sharesTypeWith")) {
            if (property.equals("sharesTypeWith")) {
                if (!card.sharesTypeWith(source)) {
                    return false;
                }
            } else {
                final String restriction = property.split("sharesTypeWith ")[1];
                final Card checkCard;
                if (restriction.startsWith("Triggered")) {
                    final Object triggeringObject = source.getTriggeringObject(restriction.substring("Triggered".length()));
                    if (!(triggeringObject instanceof Card)) {
                        return false;
                    }
                    checkCard = (Card) triggeringObject;
                } else {
                    return false;
                }

                if (!card.sharesTypeWith(checkCard)) {
                    return false;
                }
            }
        } else if (property.startsWith("hasKeyword")) {
            // "withFlash" would find Flashback cards, add this to fix Mystical Teachings
            if (!card.hasKeyword(property.substring(10))) {
                return false;
            }
        } else if (property.startsWith("withFlashback")) {
            boolean fb = false;
            if (card.hasStartOfUnHiddenKeyword("Flashback")) {
                fb = true;
            }
            for (final SpellAbility sa : card.getSpellAbilities()) {
                if (sa.isFlashBackAbility()) {
                    fb = true;
                }
            }
            if (!fb) {
                return false;
            }
        } else if (property.startsWith("with")) {
            // ... Card keywords
            if (property.startsWith("without") && card.hasStartOfUnHiddenKeyword(property.substring(7))) {
                return false;
            }
            if (!property.startsWith("without") && !card.hasStartOfUnHiddenKeyword(property.substring(4))) {
                return false;
            }
        } else if (property.startsWith("tapped")) {
            if (!card.isTapped()) {
                return false;
            }
        } else if (property.startsWith("untapped")) {
            if (!card.isUntapped()) {
                return false;
            }
        } else if (property.startsWith("faceDown")) {
            if (!card.isFaceDown()) {
                return false;
            }
        } else if (property.startsWith("faceUp")) {
            if (card.isFaceDown()) {
                return false;
            }
        } else if (property.startsWith("manifested")) {
            if (!card.isManifested()) {
                return false;
            }
        } else if (property.startsWith("DrawnThisTurn")) {
          if (!card.getDrawnThisTurn()) {
              return false;
          }
        } else if (property.startsWith("enteredBattlefieldThisTurn")) {
            if (!(card.getTurnInZone() == game.getPhaseHandler().getTurn())) {
                return false;
            }
        } else if (property.startsWith("notEnteredBattlefieldThisTurn")) {
            if (card.getTurnInZone() == game.getPhaseHandler().getTurn()) {
                return false;
            }
        } else if (property.startsWith("firstTurnControlled")) {
            if (!card.isFirstTurnControlled()) {
                return false;
            }
        } else if (property.startsWith("notFirstTurnControlled")) {
            if (card.isFirstTurnControlled()) {
                return false;
            }
        } else if (property.startsWith("startedTheTurnUntapped")) {
            if (!card.hasStartedTheTurnUntapped()) {
                return false;
            }
        } else if (property.startsWith("cameUnderControlSinceLastUpkeep")) {
            if (!card.cameUnderControlSinceLastUpkeep()) {
                return false;
            }
        } else if (property.equals("attackedOrBlockedSinceYourLastUpkeep")) {
            if (!card.getDamageHistory().hasAttackedSinceLastUpkeepOf(sourceController)
                    && !card.getDamageHistory().hasBlockedSinceLastUpkeepOf(sourceController)) {
                return false;
            }
        } else if (property.equals("blockedOrBeenBlockedSinceYourLastUpkeep")) {
            if (!card.getDamageHistory().hasBeenBlockedSinceLastUpkeepOf(sourceController)
                    && !card.getDamageHistory().hasBlockedSinceLastUpkeepOf(sourceController)) {
                return false;
            }
        } else if (property.startsWith("dealtDamageToYouThisTurn")) {
            if (!card.getDamageHistory().getThisTurnDamaged().contains(sourceController)) {
                return false;
            }
        } else if (property.startsWith("dealtDamageToOppThisTurn")) {
            if (!card.hasDealtDamageToOpponentThisTurn()) {
                return false;
            }
        } else if (property.startsWith("dealtCombatDamageThisTurn ") || property.startsWith("notDealtCombatDamageThisTurn ")) {
            final String v = property.split(" ")[1];
            final List<GameEntity> list = card.getDamageHistory().getThisTurnCombatDamaged();
            boolean found = false;
            for (final GameEntity e : list) {
                if (e.isValid(v, sourceController, source, spellAbility)) {
                    found = true;
                    break;
                }
            }
            if (found == property.startsWith("not")) {
                return false;
            }
        } else if (property.startsWith("dealtCombatDamageThisCombat ") || property.startsWith("notDealtCombatDamageThisCombat ")) {
            final String v = property.split(" ")[1];
            final List<GameEntity> list = card.getDamageHistory().getThisCombatDamaged();
            boolean found = false;
            for (final GameEntity e : list) {
                if (e.isValid(v, sourceController, source, spellAbility)) {
                    found = true;
                    break;
                }
            }
            if (found == property.startsWith("not")) {
                return false;
            }
        } else if (property.startsWith("controllerWasDealtCombatDamageByThisTurn")) {
            if (!source.getDamageHistory().getThisTurnCombatDamaged().contains(controller)) {
                return false;
            }
        } else if (property.startsWith("controllerWasDealtDamageByThisTurn")) {
            if (!source.getDamageHistory().getThisTurnDamaged().contains(controller)) {
                return false;
            }
        } else if (property.startsWith("wasDealtDamageThisTurn")) {
            if ((card.getReceivedDamageFromThisTurn().keySet()).isEmpty()) {
                return false;
            }
        } else if (property.startsWith("dealtDamageThisTurn")) {
            if (card.getTotalDamageDoneBy() == 0) {
                return false;
            }
        } else if (property.startsWith("attackedThisTurn")) {
            if (!card.getDamageHistory().getCreatureAttackedThisTurn()) {
                return false;
            }
        } else if (property.startsWith("attackedLastTurn")) {
            return card.getDamageHistory().getCreatureAttackedLastTurnOf(controller);
        } else if (property.startsWith("blockedThisTurn")) {
            if (!card.getDamageHistory().getCreatureBlockedThisTurn()) {
                return false;
            }
        } else if (property.startsWith("notExertedThisTurn")) {
            if (card.getExertedThisTurn() > 0) {
                return false;
            }
        } else if (property.startsWith("gotBlockedThisTurn")) {
            if (!card.getDamageHistory().getCreatureGotBlockedThisTurn()) {
                return false;
            }
        } else if (property.startsWith("notAttackedThisTurn")) {
            if (card.getDamageHistory().getCreatureAttackedThisTurn()) {
                return false;
            }
        } else if (property.startsWith("notAttackedLastTurn")) {
            return !card.getDamageHistory().getCreatureAttackedLastTurnOf(controller);
        } else if (property.startsWith("notBlockedThisTurn")) {
            if (card.getDamageHistory().getCreatureBlockedThisTurn()) {
                return false;
            }
        } else if (property.startsWith("greatestPower")) {
            CardCollectionView cards = CardLists.filter(game.getCardsIn(ZoneType.Battlefield), Presets.CREATURES);
            if (property.contains("ControlledBy")) {
                FCollectionView<Player> p = AbilityUtils.getDefinedPlayers(source, property.split("ControlledBy")[1], null);
                cards = CardLists.filterControlledBy(cards, p);
                if (!cards.contains(card)) {
                    return false;
                }
            }
            for (final Card crd : cards) {
                if (crd.getNetPower() > card.getNetPower()) {
                    return false;
                }
            }
        } else if (property.startsWith("yardGreatestPower")) {
            final CardCollectionView cards = CardLists.filter(sourceController.getCardsIn(ZoneType.Graveyard), Presets.CREATURES);
            for (final Card crd : cards) {
                if (crd.getNetPower() > card.getNetPower()) {
                    return false;
                }
            }
        } else if (property.startsWith("leastPower")) {
            final CardCollectionView cards = CardLists.filter(game.getCardsIn(ZoneType.Battlefield), Presets.CREATURES);
            for (final Card crd : cards) {
                if (crd.getNetPower() < card.getNetPower()) {
                    return false;
                }
            }
        } else if (property.startsWith("leastToughness")) {
            final CardCollectionView cards = CardLists.filter(game.getCardsIn(ZoneType.Battlefield), Presets.CREATURES);
            for (final Card crd : cards) {
                if (crd.getNetToughness() < card.getNetToughness()) {
                    return false;
                }
            }
        } else if (property.startsWith("greatestCMC_")) {
            CardCollectionView cards = game.getCardsIn(ZoneType.Battlefield);
            String prop = property.substring("greatestCMC_".length());
            if (prop.contains("ControlledBy")) {
                prop = prop.split("ControlledBy")[0];
                FCollectionView<Player> p = AbilityUtils.getDefinedPlayers(source, property.split("ControlledBy")[1], null);
                cards = CardLists.filterControlledBy(cards, p);
            }
            cards = CardLists.getType(cards, prop);
            cards = CardLists.getCardsWithHighestCMC(cards);
            if (!cards.contains(card)) {
                return false;
            }
        } else if (property.startsWith("greatestRememberedCMC")) {
            CardCollection cards = new CardCollection();
            for (final Object o : source.getRemembered()) {
                if (o instanceof Card) {
                    cards.add(game.getCardState((Card) o));
                }
            }
            if (!cards.contains(card)) {
                return false;
            }
            cards = CardLists.getCardsWithHighestCMC(cards);
            if (!cards.contains(card)) {
                return false;
            }
        } else if (property.startsWith("lowestRememberedCMC")) {
            CardCollection cards = new CardCollection();
            for (final Object o : source.getRemembered()) {
                if (o instanceof Card) {
                    cards.add(game.getCardState((Card) o));
                }
            }
            if (!cards.contains(card)) {
                return false;
            }
            cards = CardLists.getCardsWithLowestCMC(cards);
            if (!cards.contains(card)) {
                return false;
            }
        }
        else if (property.startsWith("lowestCMC")) {
            final CardCollectionView cards = game.getCardsIn(ZoneType.Battlefield);
            for (final Card crd : cards) {
                if (!crd.isLand() && !crd.isImmutable()) {
                    // no check for SplitCard anymore
                    if (crd.getCMC() < card.getCMC()) {
                        return false;
                    }
                }
            }
        } else if (property.startsWith("enchanted")) {
            if (!card.isEnchanted()) {
                return false;
            }
        } else if (property.startsWith("unenchanted")) {
            if (card.isEnchanted()) {
                return false;
            }
        } else if (property.startsWith("enchanting")) {
            if (!card.isEnchanting()) {
                return false;
            }
        } else if (property.startsWith("equipped")) {
            if (!card.isEquipped()) {
                return false;
            }
        } else if (property.startsWith("unequipped")) {
            if (card.isEquipped()) {
                return false;
            }
        } else if (property.startsWith("equipping")) {
            if (!card.isEquipping()) {
                return false;
            }
        } else if (property.startsWith("token")) {
            if (!card.isToken()) {
                return false;
            }
        } else if (property.startsWith("nonToken")) {
            if (card.isToken()) {
                return false;
            }
        } else if (property.startsWith("hasXCost")) {
            SpellAbility sa1 = card.getFirstSpellAbility();
            if (sa1 != null && !sa1.isXCost()) {
                return false;
            }
        } else if (property.startsWith("suspended")) {
            if (!card.hasSuspend() || !game.isCardExiled(card)
                    || !(card.getCounters(CounterType.TIME) >= 1)) {
                return false;
            }
        } else if (property.startsWith("delved")) {
            if (!source.getDelved().contains(card)) {
                return false;
            }
        } else if (property.startsWith("unequalPT")) {
            if (card.getNetPower() == card.getNetToughness()) {
                return false;
            }
        } else if (property.equals("powerGTtoughness")) {
            if (card.getNetPower() <= card.getNetToughness()) {
                return false;
            }
        } else if (property.equals("powerLTtoughness")) {
            if (card.getNetPower() >= card.getNetToughness()) {
                return false;
            }
        } else if (property.startsWith("power") || property.startsWith("toughness")
                || property.startsWith("cmc") || property.startsWith("totalPT")) {
            int x;
            int y = 0;
            String rhs = "";

            if (property.startsWith("power")) {
                rhs = property.substring(7);
                y = card.getNetPower();
            } else if (property.startsWith("toughness")) {
                rhs = property.substring(11);
                y = card.getNetToughness();
            } else if (property.startsWith("cmc")) {
                rhs = property.substring(5);
                y = card.getCMC();
            } else if (property.startsWith("totalPT")) {
                rhs = property.substring(10);
                y = card.getNetPower() + card.getNetToughness();
            }
            try {
                x = Integer.parseInt(rhs);
            } catch (final NumberFormatException e) {
                x = AbilityUtils.calculateAmount(source, rhs, spellAbility);
            }

            if (!Expressions.compare(y, property, x)) {
                return false;
            }
        }

        // syntax example: countersGE9 P1P1 or countersLT12TIME (greater number
        // than 99 not supported)
        /*
         * slapshot5 - fair warning, you cannot use numbers with 2 digits
         * (greater number than 9 not supported you can use X and the
         * SVar:X:Number$12 to get two digits. This will need a better fix, and
         * I have the beginnings of a regex below
         */
        else if (property.startsWith("counters")) {
            /*
             * Pattern p = Pattern.compile("[a-z]*[A-Z][A-Z][X0-9]+.*$");
             * String[] parse = ???
             * System.out.println("Parsing completed of: "+Property); for (int i
             * = 0; i < parse.length; i++) {
             * System.out.println("parse["+i+"]: "+parse[i]); }
             */

            // TODO get a working regex out of this pattern so the amount of
            // digits doesn't matter
            int number;
            final String[] splitProperty = property.split("_");
            final String strNum = splitProperty[1].substring(2);
            final String comparator = splitProperty[1].substring(0, 2);
            String counterType;
            try {
                number = Integer.parseInt(strNum);
            } catch (final NumberFormatException e) {
                number = CardFactoryUtil.xCount(source, source.getSVar(strNum));
            }
            counterType = splitProperty[2];

            final int actualnumber = card.getCounters(CounterType.getType(counterType));

            if (!Expressions.compare(actualnumber, comparator, number)) {
                return false;
            }
        }
        // These predicated refer to ongoing combat. If no combat happens, they'll return false (meaning not attacking/blocking ATM)
        else if (property.startsWith("attacking")) {
            if (null == combat) return false;
            if (property.equals("attacking"))    return combat.isAttacking(card);
            if (property.equals("attackingLKI")) return combat.isLKIAttacking(card);
            if (property.equals("attackingYou")) return combat.isAttacking(card, sourceController);
            if (property.equals("attackingYouOrYourPW"))  {
                Player defender = combat.getDefenderPlayerByAttacker(card);
                if (!sourceController.equals(defender)) {
                    return false;
                }
            }
        } else if (property.startsWith("notattacking")) {
            return null == combat || !combat.isAttacking(card);
        } else if (property.equals("attackedThisCombat")) {
            if (null == combat || !card.getDamageHistory().getCreatureAttackedThisCombat()) {
                return false;
            }
        } else if (property.equals("blockedThisCombat")) {
            if (null == combat || !card.getDamageHistory().getCreatureBlockedThisCombat()) {
                return false;
            }
        } else if (property.equals("attackedBySourceThisCombat")) {
            if (null == combat) return false;
            final GameEntity defender = combat.getDefenderByAttacker(source);
            if (defender instanceof Card && !card.equals(defender)) {
                return false;
            }
        } else if (property.startsWith("blocking")) {
            if (null == combat) return false;
            String what = property.substring("blocking".length());

            if (StringUtils.isEmpty(what)) return combat.isBlocking(card);
            if (what.startsWith("Source")) return combat.isBlocking(card, source) ;
            if (what.startsWith("CreatureYouCtrl")) {
                for (final Card c : CardLists.filter(sourceController.getCardsIn(ZoneType.Battlefield), Presets.CREATURES))
                    if (combat.isBlocking(card, c))
                        return true;
                return false;
            }
            if (what.startsWith("Remembered")) {
                for (final Object o : source.getRemembered()) {
                    if (o instanceof Card && combat.isBlocking(card, (Card) o)) {
                        return true;
                    }
                }
                return false;
            }
        } else if (property.startsWith("sharesBlockingAssignmentWith")) {
            if (null == combat) { return false; }
            if (null == combat.getAttackersBlockedBy(source) || null == combat.getAttackersBlockedBy(card)) { return false; }

            CardCollection sourceBlocking = new CardCollection(combat.getAttackersBlockedBy(source));
            CardCollection thisBlocking = new CardCollection(combat.getAttackersBlockedBy(card));
            if (Collections.disjoint(sourceBlocking, thisBlocking)) {
                return false;
            }
        } else if (property.startsWith("notblocking")) {
            return null == combat || !combat.isBlocking(card);
        }
        // Nex predicates refer to past combat and don't need a reference to actual combat
        else if (property.equals("blocked")) {
            return null != combat && combat.isBlocked(card);
        } else if (property.startsWith("blockedBySource")) {
            return null != combat && combat.isBlocking(source, card);
        } else if (property.startsWith("blockedThisTurn")) {
            return !card.getBlockedThisTurn().isEmpty();
        } else if (property.startsWith("blockedByThisTurn")) {
            return !card.getBlockedByThisTurn().isEmpty();
        } else if (property.startsWith("blockedValidThisTurn ")) {
            if (card.getBlockedThisTurn() == null) {
                return false;
            }

            String valid = property.split(" ")[1];
            for(Card c : card.getBlockedThisTurn()) {
                if (c.isValid(valid, card.getController(), source, spellAbility)) {
                    return true;
                }
            }
            return false;
        } else if (property.startsWith("blockedByValidThisTurn ")) {
            if (card.getBlockedByThisTurn() == null) {
                return false;
            }
            String valid = property.split(" ")[1];
            for(Card c : card.getBlockedByThisTurn()) {
                if (c.isValid(valid, card.getController(), source, spellAbility)) {
                    return true;
                }
            }
            return false;
        } else if (property.startsWith("blockedBySourceThisTurn")) {
            return source.getBlockedByThisTurn().contains(card);
        } else if (property.startsWith("blockedSource")) {
            return null != combat && combat.isBlocking(card, source);
        } else if (property.startsWith("isBlockedByRemembered")) {
            if (null == combat) return false;
            for (final Object o : source.getRemembered()) {
                if (o instanceof Card && combat.isBlocking((Card) o, card)) {
                    return true;
                }
            }
            return false;
        } else if (property.startsWith("blockedRemembered")) {
            Card rememberedcard;
            for (final Object o : source.getRemembered()) {
                if (o instanceof Card) {
                    rememberedcard = (Card) o;
                    if (!card.getBlockedThisTurn().contains(rememberedcard)) {
                        return false;
                    }
                }
            }
        } else if (property.startsWith("blockedByRemembered")) {
            Card rememberedcard;
            for (final Object o : source.getRemembered()) {
                if (o instanceof Card) {
                    rememberedcard = (Card) o;
                    if (!card.getBlockedByThisTurn().contains(rememberedcard)) {
                        return false;
                    }
                }
            }
        } else if (property.startsWith("unblocked")) {
            if (combat == null || !combat.isUnblocked(card)) {
                return false;
            }
        } else if (property.equals("attackersBandedWith")) {
            if (card.equals(source)) {
                // You don't band with yourself
                return false;
            }
            AttackingBand band = combat == null ? null : combat.getBandOfAttacker(source);
            if (band == null || !band.getAttackers().contains(card)) {
                return false;
            }
        } else if (property.startsWith("kicked")) {
            if (property.equals("kicked")) {
                if (card.getKickerMagnitude() == 0) {
                    return false;
                }
            } else {
                String s = property.split("kicked ")[1];
                if ("1".equals(s) && !card.isOptionalCostPaid(OptionalCost.Kicker1)) return false;
                if ("2".equals(s) && !card.isOptionalCostPaid(OptionalCost.Kicker2)) return false;
            }
        } else if (property.startsWith("notkicked")) {
            if (card.getKickerMagnitude() > 0) {
                return false;
            }
        } else if (property.startsWith("pseudokicked")) {
            if (property.equals("pseudokicked")) {
                if (!card.isOptionalCostPaid(OptionalCost.Generic)) return false;
            }
        } else if (property.startsWith("notpseudokicked")) {
            if (property.equals("pseudokicked")) {
                if (card.isOptionalCostPaid(OptionalCost.Generic)) return false;
            }
        } else if (property.startsWith("surged")) {
            if (!card.isOptionalCostPaid(OptionalCost.Surge)) {
                return false;
            }
        } else if (property.startsWith("evoked")) {
            if (card.getCastSA() == null) {
                return false;
            }
            return card.getCastSA().isEvoke();
        } else if (property.equals("HasDevoured")) {
            if (card.getDevouredCards().isEmpty()) {
                return false;
            }
        } else if (property.equals("HasNotDevoured")) {
            if (!card.getDevouredCards().isEmpty()) {
                return false;
            }
        } else if (property.equals("IsMonstrous")) {
            if (!card.isMonstrous()) {
                return false;
            }
        } else if (property.equals("IsNotMonstrous")) {
            if (card.isMonstrous()) {
                return false;
            }
        } else if (property.equals("IsUnearthed")) {
            if (!card.isUnearthed()) {
                return false;
            }
        } else if (property.equals("IsRenowned")) {
            if (!card.isRenowned()) {
                return false;
            }
        } else if (property.equals("IsNotRenowned")) {
            if (card.isRenowned()) {
                return false;
            }
        } else if (property.startsWith("RememberMap")) {
            System.out.println(source.getRememberMap());
            for (SpellAbility sa : source.getSpellAbilities()) {
                if (sa.getActivatingPlayer() == null) continue;
                for (Player p : AbilityUtils.getDefinedPlayers(source, property.split("RememberMap_")[1], sa)) {
                    if (source.getRememberMap() != null && source.getRememberMap().get(p) != null) {
                        if (source.getRememberMap().get(p).contains(card)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } else if (property.equals("IsRemembered")) {
            if (!source.isRemembered(card)) {
                return false;
            }
        } else if (property.equals("IsNotRemembered")) {
            if (source.isRemembered(card)) {
                return false;
            }
        } else if (property.equals("IsImprinted")) {
            if (!source.hasImprintedCard(card)) {
                return false;
            }
        } else if (property.equals("IsNotImprinted")) {
            if (source.hasImprintedCard(card)) {
                return false;
            }
        } else if (property.equals("NoAbilities")) {
            if (!((card.getAbilityText().trim().equals("") || card.isFaceDown()) && (card.getUnhiddenKeywords().isEmpty()))) {
                return false;
            }
        } else if (property.equals("HasCounters")) {
            if (!card.hasCounters()) {
                return false;
            }
        } else if (property.equals("NoCounters")) {
            if (card.hasCounters()) {
                return false;
            }
        } else if (property.equals("wasCast")) {
            if (null == card.getCastFrom()) {
                return false;
            }
        } else if (property.equals("wasNotCast")) {
            if (null != card.getCastFrom()) {
                return false;
            }
        } else if (property.startsWith("wasCastFrom")) {
            // How are we getting in here with a comma?
            final String strZone = property.split(",")[0].substring(11);
            final ZoneType realZone = ZoneType.smartValueOf(strZone);
            if (realZone != card.getCastFrom()) {
                return false;
            }
        } else if (property.startsWith("wasNotCastFrom")) {
            final String strZone = property.substring(14);
            final ZoneType realZone = ZoneType.smartValueOf(strZone);
            if (realZone == card.getCastFrom()) {
                return false;
            }
        } else if (property.startsWith("set")) {
            final String setCode = property.substring(3, 6);
            if (!card.getSetCode().equals(setCode)) {
                return false;
            }
        } else if (property.startsWith("inZone")) {
            final String strZone = property.substring(6);
            final ZoneType realZone = ZoneType.smartValueOf(strZone);
            // lki last zone does fall back to this zone
            final Zone lkiZone = lki.getLastKnownZone();
            
            if (lkiZone == null || !lkiZone.is(realZone)) {
                return false;
            }
        } else if (property.startsWith("inRealZone")) {
            final String strZone = property.substring(10);
            final ZoneType realZone = ZoneType.smartValueOf(strZone);

            if (!card.isInZone(realZone)) {
                return false;
            }
        } else if (property.equals("IsCommander")) {
            if (!card.isCommander()) {
                return false;
            }
        } else {
            // StringType done in CardState
            if (!card.getCurrentState().hasProperty(property, sourceController, source, spellAbility)) {
                return false;
            }
        }
        return true;
    }

}
