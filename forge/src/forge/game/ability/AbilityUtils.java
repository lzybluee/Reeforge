package forge.game.ability;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostParser;
import forge.card.mana.ManaCostShard;
import forge.game.CardTraitBase;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.AbilityFactory.AbilityRecordType;
import forge.game.card.*;
import forge.game.cost.Cost;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.*;
import forge.game.zone.ZoneType;
import forge.util.Expressions;
import forge.util.TextUtil;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.WordUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class AbilityUtils {
	private final static ImmutableList<String> cmpList = ImmutableList.of("LT", "LE", "EQ", "GE", "GT", "NE");

    public static CounterType getCounterType(String name, SpellAbility sa) throws Exception {
        CounterType counterType;
        if ("ReplacedCounterType".equals(name)) {
        	name = (String) sa.getReplacingObject("CounterType");
        }
        try {
            counterType = CounterType.getType(name);
        } catch (Exception e) {
            String type = sa.getSVar(name);
            if (type.equals("")) {
                type = sa.getHostCard().getSVar(name);
            }

            if (type.equals("")) {
                throw new Exception("Counter type doesn't match, nor does an SVar exist with the type name.");
            }
            counterType = CounterType.getType(type);
        }

        return counterType;
    }

    // should the three getDefined functions be merged into one? Or better to
    // have separate?
    // If we only have one, each function needs to Cast the Object to the
    // appropriate type when using
    // But then we only need update one function at a time once the casting is
    // everywhere.
    // Probably will move to One function solution sometime in the future
    public static CardCollection getDefinedCards(final Card hostCard, final String def, final SpellAbility sa) {
        CardCollection cards = new CardCollection();
        String defined = (def == null) ? "Self" : applyAbilityTextChangeEffects(def, sa); // default to Self
        final String[] incR = defined.split("\\.", 2);
        defined = incR[0];
        final Game game = hostCard.getGame();

        Card c = null;

        if (defined.equals("Self")) {
            c = hostCard;
        } else if (defined.equals("CorrectedSelf")) {
            c = game.getCardState(hostCard);
        }
        else if (defined.equals("OriginalHost")) {
            c = sa.getRootAbility().getOriginalHost();
        }
        else if (defined.equals("EffectSource")) {
            if (hostCard.isEmblem() || hostCard.getType().hasSubtype("Effect")) {
                c = AbilityUtils.findEffectRoot(hostCard);
            }
        }
        else if (defined.equals("Equipped")) {
            c = hostCard.getEquipping();
        }

        else if (defined.equals("Enchanted")) {
            c = hostCard.getEnchantingCard();
            if ((c == null) && (sa.getRootAbility() != null)
                    && (sa.getRootAbility().getPaidList("Sacrificed") != null)
                    && !sa.getRootAbility().getPaidList("Sacrificed").isEmpty()) {
                c = sa.getRootAbility().getPaidList("Sacrificed").get(0).getEnchantingCard();
            }
        }
        else if (defined.endsWith("OfLibrary")) {
            final CardCollectionView lib = hostCard.getController().getCardsIn(ZoneType.Library);
            if (lib.size() > 0) { // TopOfLibrary or BottomOfLibrary
                c = lib.get(defined.startsWith("Top") ? 0 : lib.size() - 1);
            } else {
                // we don't want this to fall through and return the "Self"
                return cards;
            }
        }
        else if (defined.equals("Targeted")) {
            final SpellAbility saTargeting = sa.getSATargetingCard();
            if (saTargeting != null) {
                Iterables.addAll(cards, saTargeting.getTargets().getTargetCards());
            }
        }
        else if (defined.equals("ThisTargetedCard")) { // do not add parent targeted
            if (sa != null && sa.getTargets() != null) {
                Iterables.addAll(cards, sa.getTargets().getTargetCards());
            }
        }
        else if (defined.equals("ParentTarget")) {
            final SpellAbility parent = sa.getParentTargetingCard();
            if (parent != null) {
                Iterables.addAll(cards, parent.getTargets().getTargetCards());
            }

        }
        else if (defined.startsWith("Triggered") && (sa != null)) {
            final SpellAbility root = sa.getRootAbility();
            if (defined.contains("LKICopy")) { //Triggered*LKICopy
                int lkiPosition = defined.indexOf("LKICopy");
                final Object crd = root.getTriggeringObject(defined.substring(9, lkiPosition));
                if (crd instanceof Card) {
                    c = (Card) crd;
                }
            }
            else {
                final Object crd = root.getTriggeringObject(defined.substring(9));
                if (crd instanceof Card) {
                    c = game.getCardState((Card) crd);
                } else if (crd instanceof Iterable) {
                    for (final Card cardItem : Iterables.filter((Iterable<?>) crd, Card.class)) {
                        cards.add(cardItem);
                    }
                }
            }
        }
        else if (defined.startsWith("Replaced") && (sa != null)) {
            final SpellAbility root = sa.getRootAbility();
            final Object crd = root.getReplacingObject(defined.substring(8));
            if (crd instanceof Card) {
                c = game.getCardState((Card) crd);
            } else if (crd instanceof List<?>) {
                for (final Card cardItem : (CardCollection) crd) {
                    cards.add(cardItem);
                }
            }
        }
        else if (defined.equals("Remembered") || defined.equals("RememberedCard")) {
            if (!hostCard.hasRemembered()) {
                final Card newCard = game.getCardState(hostCard);
                for (final Object o : newCard.getRemembered()) {
                    if (o instanceof Card) {
                        cards.add(game.getCardState((Card) o));
                    }
                }
            }
            // game.getCardState(Card c) is not working for LKI
            for (final Object o : hostCard.getRemembered()) {
                if (o instanceof Card) {
                    cards.addAll(addRememberedFromCardState(game, (Card)o));
                }
            }
        } else if (defined.equals("RememberedLKI")) {
            for (final Object o : hostCard.getRemembered()) {
                if (o instanceof Card) {
                    cards.add((Card) o);
                }
            }
        } else if (defined.equals("DirectRemembered")) {
            if (!hostCard.hasRemembered()) {
                final Card newCard = game.getCardState(hostCard);
                for (final Object o : newCard.getRemembered()) {
                    if (o instanceof Card) {
                        cards.add((Card) o);
                    }
                }
            }

            for (final Object o : hostCard.getRemembered()) {
                if (o instanceof Card) {
                    cards.add((Card) o);
                }
            }
        } else if (defined.equals("DelayTriggerRemembered")) {
            SpellAbility trigSa = sa.getTriggeringAbility();
            if (trigSa != null) {
                for (Object o : trigSa.getTriggerRemembered()) {
                    if (o instanceof Card) {
                        cards.addAll(addRememberedFromCardState(game, (Card)o));
                    }
                }
            } else {
                System.err.println("Warning: couldn't find trigger SA in the chain of SpellAbility " + sa);
            }
        } else if (defined.equals("FirstRemembered")) {
            Object o = Iterables.getFirst(hostCard.getRemembered(), null);
            if (o != null && o instanceof Card) {
                cards.add(game.getCardState((Card) o));
            }
        } else if (defined.equals("LastRemembered")) {
            Object o = Iterables.getLast(hostCard.getRemembered(), null);
            if (o != null && o instanceof Card) {
                cards.add(game.getCardState((Card) o));
            }
        } else if (defined.equals("Clones")) {
            for (final Card clone : hostCard.getClones()) {
                cards.add(game.getCardState(clone));
            }
        } else if (defined.equals("Imprinted")) {
            for (final Card imprint : hostCard.getImprintedCards()) {
                cards.add(game.getCardState(imprint));
            }
        } else if (defined.startsWith("ThisTurnEntered")) {
            final String[] workingCopy = defined.split("_");
            ZoneType destination, origin;
            String validFilter;

            destination = ZoneType.smartValueOf(workingCopy[1]);
            if (workingCopy[2].equals("from")) {
                origin = ZoneType.smartValueOf(workingCopy[3]);
                validFilter = workingCopy[4];
            } else {
                origin = null;
                validFilter = workingCopy[2];
            }
            for (final Card cl : CardUtil.getThisTurnEntered(destination, origin, validFilter, hostCard)) {
                cards.add(game.getCardState(cl));
            }
        } else if (defined.equals("ChosenCard")) {
            for (final Card chosen : hostCard.getChosenCards()) {
                cards.add(game.getCardState(chosen));
            }
        } else if (defined.startsWith("CardUID_")) {
            String idString = defined.substring(8);
            for (final Card cardByID : game.getCardsInGame()) {
                if (cardByID.getId() == Integer.valueOf(idString)) {
                    cards.add(game.getCardState(cardByID));
                }
            }
        } else {
            CardCollection list = null;
            if (defined.startsWith("SacrificedCards")) {
                list = sa.getRootAbility().getPaidList("SacrificedCards");
            } else if (defined.startsWith("Sacrificed")) {
                list = sa.getRootAbility().getPaidList("Sacrificed");
            } else if (defined.startsWith("DiscardedCards")) {
                list = sa.getRootAbility().getPaidList("DiscardedCards");
            } else if (defined.startsWith("Discarded")) {
                list = sa.getRootAbility().getPaidList("Discarded");
            } else if (defined.startsWith("ExiledCards")) {
                list = sa.getRootAbility().getPaidList("ExiledCards");
            } else if (defined.startsWith("Exiled")) {
                list = sa.getRootAbility().getPaidList("Exiled");
            } else if (defined.startsWith("TappedCards")) {
                list = sa.getRootAbility().getPaidList("TappedCards");
            } else if (defined.startsWith("Tapped")) {
                list = sa.getRootAbility().getPaidList("Tapped");
            } else if (defined.startsWith("UntappedCards")) {
                list = sa.getRootAbility().getPaidList("UntappedCards");
            } else if (defined.startsWith("Untapped")) {
                list = sa.getRootAbility().getPaidList("Untapped");
            } else if (defined.startsWith("Valid ")) {
                String validDefined = defined.substring("Valid ".length());
                list = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), validDefined.split(","), hostCard.getController(), hostCard, sa);
            } else if (defined.startsWith("ValidAll ")) {
                String validDefined = defined.substring("ValidAll ".length());
                list = CardLists.getValidCards(game.getCardsInGame(), validDefined.split(","), hostCard.getController(), hostCard, sa);
            } else if (defined.startsWith("Valid")) {
                String[] s = defined.split(" ");
                String zone = s[0].substring("Valid".length());
                String validDefined = s[1];
                list = CardLists.getValidCards(game.getCardsIn(ZoneType.smartValueOf(zone)), validDefined.split(","), hostCard.getController(), hostCard, sa);
            } else {
                return cards;
            }

            if (list != null) {
                cards.addAll(list);
            }
        }

        if (c != null) {
            cards.add(c);
        }

        if (incR.length > 1 && !cards.isEmpty()) {
            final String excR = "Card." + incR[1];
            cards = CardLists.getValidCards(cards, excR.split(","), hostCard.getController(), hostCard, sa);
        }

        return cards;
    }

    private static CardCollection addRememberedFromCardState(Game game, Card c) {
        CardCollection coll = new CardCollection();
        Card newState = game.getCardState(c);
        if (c.getMeldedWith() != null) {
            // When remembering a card that flickers, also remember it's meld pair
            coll.add(game.getCardState(c.getMeldedWith()));
        }
        coll.add(newState);
        return coll;
    }

    private static Card findEffectRoot(Card startCard) {
        Card cc = startCard.getEffectSource();
        if (cc != null) {
            if (cc.isEmblem() || cc.getType().hasSubtype("Effect")) {
                return findEffectRoot(cc);
            }
            return cc;
        }
        return null; //If this happens there is a card in the game that is not in any zone
    }

    // Utility functions used by the AFs
    /**
     * <p>
     * calculateAmount.
     * </p>
     * 
     * @param card
     *            a {@link forge.game.card.Card} object.
     * @param amount
     *            a {@link java.lang.String} object.
     * @param ability
     *            a {@link forge.game.CardTraitBase} object.
     * @return a int.
     */
    public static int calculateAmount(final Card card, String amount, final CardTraitBase ability) {
        return calculateAmount(card, amount, ability, false);
    }

    public static int calculateAmount(final Card card, String amount, final CardTraitBase ability, boolean maxto) {
        // return empty strings and constants
        if (StringUtils.isBlank(amount)) { return 0; }
        if (card == null) { return 0; }
        final Player player = card.getController();
        final Game game = player.getGame();

        // Strip and save sign for calculations
        final boolean startsWithPlus = amount.charAt(0) == '+';
        final boolean startsWithMinus = amount.charAt(0) == '-';
        if (startsWithPlus || startsWithMinus) { amount = amount.substring(1); }
        int multiplier = startsWithMinus ? -1 : 1;

        // return result soon for plain numbers
        if (StringUtils.isNumeric(amount)) {
            int val = Integer.parseInt(amount);
            if (maxto) {
                val = Math.max(val, 0);
            }
            return val * multiplier;
        }

        // Try to fetch variable, try ability first, then card.
        String svarval = null;
        if (amount.indexOf('$') > 0) { // when there is a dollar sign, it's not a reference, it's a raw value!
            svarval = amount;
        }
        else if (ability != null) {
            svarval = ability.getSVar(amount);
        }
        if (StringUtils.isBlank(svarval)) {
            if ((ability != null) && (ability instanceof SpellAbility) && !(ability instanceof SpellPermanent)) {
                System.err.printf("SVar '%s' not found in ability, fallback to Card (%s). Ability is (%s)%n", amount, card.getName(), ability);
            }
            svarval = card.getSVar(amount);
        }

        if (StringUtils.isBlank(svarval)) {
            // Some variables may be not chosen yet at this moment
            // So return 0 and don't issue an error.
            if (amount.equals("ChosenX")) {
                // isn't made yet
                return 0;
            }
            // cost hasn't been paid yet
            if (amount.startsWith("Cost")) {
                return 0;
            }
            // Nothing to do here if value is missing or blank
            System.err.printf("SVar '%s' not defined in Card (%s)%n", amount, card.getName());
            return 0;
        }

        // Handle numeric constant coming in svar value
        if (StringUtils.isNumeric(svarval)) {
            int val = Integer.parseInt(svarval);
            if (maxto) {
                val = Math.max(val, 0);
            }
            return val * multiplier;
        }

        // Parse Object$Property string
        final String[] calcX = svarval.split("\\$", 2);

        // Incorrect parses mean zero.
        if (calcX.length == 1 || calcX[1].equals("none")) {
            return 0;
        }

        // modify amount string for text changes
        calcX[1] = AbilityUtils.applyAbilityTextChangeEffects(calcX[1], ability);

        Integer val = null;
        if (calcX[0].startsWith("Count")) {
            val = AbilityUtils.xCount(card, calcX[1], ability);
        } else if (calcX[0].startsWith("Number")) {
            val = CardFactoryUtil.xCount(card, svarval);
        } else if (calcX[0].startsWith("SVar")) {
            final String[] l = calcX[1].split("/");
            final String m = CardFactoryUtil.extractOperators(calcX[1]);
            val = CardFactoryUtil.doXMath(AbilityUtils.calculateAmount(card, l[0], ability), m, card);
        } else if (calcX[0].startsWith("PlayerCount")) {
            final String hType = calcX[0].substring(11);
            final FCollection<Player> players = new FCollection<Player>();
            if (hType.equals("Players") || hType.equals("")) {
                players.addAll(game.getPlayers());
                val = CardFactoryUtil.playerXCount(players, calcX[1], card);
            }
            else if (hType.equals("Opponents")) {
                players.addAll(player.getOpponents());
                val = CardFactoryUtil.playerXCount(players, calcX[1], card);
            }
            else if (hType.equals("RegisteredOpponents")) {
                players.addAll(Iterables.filter(game.getRegisteredPlayers(),PlayerPredicates.isOpponentOf(player)));
                val = CardFactoryUtil.playerXCount(players, calcX[1], card);
            }
            else if (hType.equals("Other")) {
                players.addAll(player.getAllOtherPlayers());
                val = CardFactoryUtil.playerXCount(players, calcX[1], card);
            }
            else if (hType.equals("Remembered")) {
                for (final Object o : card.getRemembered()) {
                    if (o instanceof Player) {
                        players.add((Player) o);
                    }
                }
                val = CardFactoryUtil.playerXCount(players, calcX[1], card);
            }
            else if (hType.equals("NonActive")) {
                players.addAll(game.getPlayers());
                players.remove(game.getPhaseHandler().getPlayerTurn());
                val = CardFactoryUtil.playerXCount(players, calcX[1], card);
            }
            else if (hType.startsWith("Property") && ability instanceof SpellAbility) {
                String defined = hType.split("Property")[1];
                for (Player p : game.getPlayersInTurnOrder()) {
                    if (p.hasProperty(defined, ((SpellAbility)ability).getActivatingPlayer(), ability.getHostCard(), (SpellAbility)ability)) {
                        players.add(p);
                    }
                }
                val = CardFactoryUtil.playerXCount(players, calcX[1], card);
            }
            else {
                val = 0;
            }
        }

        if (val != null) {
            if (maxto) {
                val = Math.max(val, 0);
            }
            return val * multiplier;
        }

        if (calcX[0].startsWith("Remembered")) {
            // Add whole Remembered list to handlePaid
            final CardCollection list = new CardCollection();
            Card newCard = card;
            if (!card.hasRemembered()) {
                newCard = game.getCardState(card);
            }

            if (calcX[0].endsWith("LKI")) { // last known information
                for (final Object o : newCard.getRemembered()) {
                    if (o instanceof Card) {
                        list.add((Card) o);
                    }
                }
            }
            else {
                for (final Object o : newCard.getRemembered()) {
                    if (o instanceof Card) {
                        list.add(game.getCardState((Card) o));
                    }
                }
            }

            return CardFactoryUtil.handlePaid(list, calcX[1], card) * multiplier;
        }

        if (calcX[0].startsWith("Imprinted")) {
            // Add whole Imprinted list to handlePaid
            final CardCollection list = new CardCollection();
            Card newCard = card;
            if (card.getImprintedCards().isEmpty()) {
                newCard = game.getCardState(card);
            }

            if (calcX[0].endsWith("LKI")) { // last known information
                for (final Card c : newCard.getImprintedCards()) {
                    list.add(c);
                }
            }
            else {
                for (final Card c : newCard.getImprintedCards()) {
                    list.add(game.getCardState(c));
                }
            }

            return CardFactoryUtil.handlePaid(list, calcX[1], card) * multiplier;
        }

        if (calcX[0].matches("Enchanted")) {
            // Add whole Enchanted list to handlePaid
            final CardCollection list = new CardCollection();
            if (card.isEnchanting()) {
                Object o = card.getEnchanting();
                if (o instanceof Card) {
                    list.add(game.getCardState((Card) o));
                }
            }
            return CardFactoryUtil.handlePaid(list, calcX[1], card) * multiplier;
        }

        // All the following only work for SpellAbilities
        if (!(ability instanceof SpellAbility)) {
            return 0;
        }

        final SpellAbility sa = (SpellAbility) ability;
        if (calcX[0].startsWith("Modes")) {
            int chosenModes = 0;
            SpellAbility sub = sa;
            while(sub != null) {
                if (!sub.getSVar("CharmOrder").equals("")) {
                    chosenModes++;
                }
                sub = sub.getSubAbility();
            }
            // Count Math
            final String m = CardFactoryUtil.extractOperators(calcX[1]);
            return CardFactoryUtil.doXMath(chosenModes, m, card) * multiplier;
        }

        // Player attribute counting
        if (calcX[0].startsWith("TargetedPlayer")) {
            final List<Player> players = new ArrayList<Player>();
            final SpellAbility saTargeting = sa.getSATargetingPlayer();
            if (null != saTargeting) {
                Iterables.addAll(players, saTargeting.getTargets().getTargetPlayers());
            }
            return CardFactoryUtil.playerXCount(players, calcX[1], card) * multiplier;
        }
        if (calcX[0].startsWith("ThisTargetedPlayer")) {
            final List<Player> players = new ArrayList<Player>();
            Iterables.addAll(players, sa.getTargets().getTargetPlayers());
            return CardFactoryUtil.playerXCount(players, calcX[1], card) * multiplier;
        }
        if (calcX[0].startsWith("TargetedObjects")) {
            final List<GameObject> objects = new ArrayList<GameObject>();
            // Make list of all targeted objects starting with the root SpellAbility
            SpellAbility loopSA = sa.getRootAbility();
            while (loopSA != null) {
                if (loopSA.getTargetRestrictions() != null) {
                    Iterables.addAll(objects, loopSA.getTargets().getTargets());
                }
                loopSA = loopSA.getSubAbility();
            }
            return CardFactoryUtil.objectXCount(objects, calcX[1], card) * multiplier;
        }
        if (calcX[0].startsWith("TargetedController")) {
            final List<Player> players = new ArrayList<Player>();
            final CardCollection list = getDefinedCards(card, "Targeted", sa);
            final List<SpellAbility> sas = AbilityUtils.getDefinedSpellAbilities(card, "Targeted", sa);

            for (final Card c : list) {
                final Player p = c.getController();
                if (!players.contains(p)) {
                    players.add(p);
                }
            }
            for (final SpellAbility s : sas) {
                final Player p = s.getHostCard().getController();
                if (!players.contains(p)) {
                    players.add(p);
                }
            }
            return CardFactoryUtil.playerXCount(players, calcX[1], card) * multiplier;
        }
        if (calcX[0].startsWith("TargetedByTarget")) {
            final CardCollection tgtList = new CardCollection();
            final List<SpellAbility> saList = getDefinedSpellAbilities(card, "Targeted", sa);

            for (final SpellAbility s : saList) {
                tgtList.addAll(getDefinedCards(s.getHostCard(), "Targeted", s));
                // Check sub-abilities, so that modal cards like Abzan Charm are correctly handled.
                // TODO: Should this be done in a more general place, like in getDefinedCards()?
                AbilitySub abSub = s.getSubAbility();
                while (abSub != null) {
                    tgtList.addAll(getDefinedCards(abSub.getHostCard(), "Targeted", abSub));
                    abSub = abSub.getSubAbility();
                }
            }
            return CardFactoryUtil.handlePaid(tgtList, calcX[1], card) * multiplier;
        }
        if (calcX[0].startsWith("TriggeredPlayers") || calcX[0].equals("TriggeredCardController")) {
            String key = calcX[0];
            if (calcX[0].startsWith("TriggeredPlayers")) {
                key = "Triggered" + key.substring(16);
            }
            final List<Player> players = new ArrayList<Player>();
            Iterables.addAll(players, getDefinedPlayers(card, key, sa));
            return CardFactoryUtil.playerXCount(players, calcX[1], card) * multiplier;
        }
        if (calcX[0].startsWith("TriggeredPlayer") || calcX[0].startsWith("TriggeredTarget")) {
            final SpellAbility root = sa.getRootAbility();
            Object o = root.getTriggeringObject(calcX[0].substring(9));
            return o instanceof Player ? CardFactoryUtil.playerXProperty((Player) o, calcX[1], card) * multiplier : 0;
        }
        if (calcX[0].equals("TriggeredSpellAbility")) {
            final SpellAbility root = sa.getRootAbility();
            SpellAbility sat = (SpellAbility) root.getTriggeringObject("SpellAbility");
            return calculateAmount(sat.getHostCard(), calcX[1], sat);
        }
        // Added on 9/30/12 (ArsenalNut) - Ended up not using but might be useful in future
        /*
        if (calcX[0].startsWith("EnchantedController")) {
            final ArrayList<Player> players = new ArrayList<Player>();
            players.addAll(AbilityFactory.getDefinedPlayers(card, "EnchantedController", ability));
            return CardFactoryUtil.playerXCount(players, calcX[1], card) * multiplier;
        }
         */

        CardCollectionView list;
        if (calcX[0].startsWith("Sacrificed")) {
            list = sa.getRootAbility().getPaidList("Sacrificed");
        }
        else if (calcX[0].startsWith("Discarded")) {
            final SpellAbility root = sa.getRootAbility();
            list = root.getPaidList("Discarded");
            if ((null == list) && root.isTrigger()) {
                list = root.getHostCard().getSpellPermanent().getPaidList("Discarded");
            }
        }
        else if (calcX[0].startsWith("Exiled")) {
            list = sa.getRootAbility().getPaidList("Exiled");
        }
        else if (calcX[0].startsWith("Milled")) {
            list = sa.getRootAbility().getPaidList("Milled");
        }
        else if (calcX[0].startsWith("Tapped")) {
            list = sa.getRootAbility().getPaidList("Tapped");
        }
        else if (calcX[0].startsWith("Revealed")) {
            list = sa.getRootAbility().getPaidList("Revealed");
        }
        else if (calcX[0].startsWith("Targeted")) {
            list = sa.findTargetedCards();
        }
        else if (calcX[0].startsWith("ParentTargeted")) {
            SpellAbility parent = sa.getParentTargetingCard();
            if (parent != null) {
                list = parent.findTargetedCards();
            }
            else {
                list = null;
            }
        }
        else if (calcX[0].startsWith("TriggerObjects")) {
            final SpellAbility root = sa.getRootAbility();
            list = (CardCollection) root.getTriggeringObject(calcX[0].substring(14));
        }
        else if (calcX[0].startsWith("Triggered")) {
            final SpellAbility root = sa.getRootAbility();
            list = new CardCollection((Card) root.getTriggeringObject(calcX[0].substring(9)));
        }
        else if (calcX[0].startsWith("TriggerCount")) {
            // TriggerCount is similar to a regular Count, but just
            // pulls Integer Values from Trigger objects
            final SpellAbility root = sa.getRootAbility();
            final String[] l = calcX[1].split("/");
            final String m = CardFactoryUtil.extractOperators(calcX[1]);
            final int count = (Integer) root.getTriggeringObject(l[0]);

            return CardFactoryUtil.doXMath(count, m, card) * multiplier;
        }
        else if (calcX[0].startsWith("Replaced")) {
            final SpellAbility root = sa.getRootAbility();
            list = new CardCollection((Card) root.getReplacingObject(calcX[0].substring(8)));
        }
        else if (calcX[0].startsWith("ReplaceCount")) {
            // ReplaceCount is similar to a regular Count, but just
            // pulls Integer Values from Replacement objects
            final SpellAbility root = sa.getRootAbility();
            final String[] l = calcX[1].split("/");
            final String m = CardFactoryUtil.extractOperators(calcX[1]);
            final int count = (Integer) root.getReplacingObject(l[0]);

            return CardFactoryUtil.doXMath(count, m, card) * multiplier;
        }
        else {
            return 0;
        }
        return CardFactoryUtil.handlePaid(list, calcX[1], card) * multiplier;
    }

    /**
     * <p>
     * getDefinedObjects.
     * </p>
     * 
     * @param card
     *            a {@link forge.game.card.Card} object.
     * @param def
     *            a {@link java.lang.String} object.
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a {@link java.util.ArrayList} object.
     */
    public static FCollection<GameObject> getDefinedObjects(final Card card, final String def, final SpellAbility sa) {
        final FCollection<GameObject> objects = new FCollection<GameObject>();
        final String defined = (def == null) ? "Self" : def;

        objects.addAll(AbilityUtils.getDefinedPlayers(card, defined, sa));
        objects.addAll(getDefinedCards(card, defined, sa));
        objects.addAll(AbilityUtils.getDefinedSpellAbilities(card, defined, sa));
        return objects;
    }

    /**
     * Filter list by type.
     * 
     * @param list
     *            a CardList
     * @param type
     *            a card type
     * @param sa
     *            a SpellAbility
     * @return a {@link forge.game.card.CardCollectionView} object.
     */
    public static CardCollectionView filterListByType(final CardCollectionView list, String type, final SpellAbility sa) {
        if (type == null) {
            return list;
        }

        // Filter List Can send a different Source card in for things like
        // Mishra and Lobotomy

        Card source = sa.getHostCard();
        final Object o;
        if (type.startsWith("Triggered")) {
            if (type.contains("Card")) {
                o = sa.getTriggeringObject("Card");
            }
            else if (type.contains("Attacker")) {
                o = sa.getTriggeringObject("Attacker");
            }
            else if (type.contains("Blocker")) {
                o = sa.getTriggeringObject("Blocker");
            }
            else {
                o = sa.getTriggeringObject("Card");
            }

            if (!(o instanceof Card)) {
                return new CardCollection();
            }

            if (type.equals("Triggered") || (type.equals("TriggeredCard")) || (type.equals("TriggeredAttacker"))
                    || (type.equals("TriggeredBlocker"))) {
                type = "Card.Self";
            }

            source = (Card) (o);
            if (type.contains("TriggeredCard")) {
                type = TextUtil.fastReplace(type, "TriggeredCard", "Card");
            }
            else if (type.contains("TriggeredAttacker")) {
                type = TextUtil.fastReplace(type, "TriggeredAttacker", "Card");
            }
            else if (type.contains("TriggeredBlocker")) {
                type = TextUtil.fastReplace(type, "TriggeredBlocker", "Card");
            }
            else {
                type = TextUtil.fastReplace(type, "Triggered", "Card");
            }
        }
        else if (type.startsWith("Targeted")) {
            source = null;
            CardCollectionView tgts = sa.findTargetedCards();
            if (!tgts.isEmpty()) {
                source = tgts.get(0);
            }
            if (source == null) {
                return new CardCollection();
            }

            if (type.startsWith("TargetedCard")) {
                type = TextUtil.fastReplace(type, "TargetedCard", "Card");
            }
            else {
                type = TextUtil.fastReplace(type, "Targeted", "Card");
            }
        }
        else if (type.startsWith("Remembered")) {
            boolean hasRememberedCard = false;
            for (final Object object : source.getRemembered()) {
                if (object instanceof Card) {
                    hasRememberedCard = true;
                    source = (Card) object;
                    type = TextUtil.fastReplace(type, "Remembered", "Card");

                    break;
                }
            }

            if (!hasRememberedCard) {
                return new CardCollection();
            }
        }
        else if (type.startsWith("Imprinted")) {
            type = TextUtil.fastReplace(type, "Imprinted", "Card");
        }
        else if (type.equals("Card.AttachedBy")) {
            source = source.getEnchantingCard();
            type = TextUtil.fastReplace(type, "Card.AttachedBy", "Card.Self");
        }

        String valid = type;

        for (String t : cmpList) {
            int index = valid.indexOf(t);
            if (index >= 0) {
                char reference = valid.charAt(index + 2); // take whatever goes after EQ
                if (Character.isLetter(reference)) {
                    String varName = valid.split(",")[0].split(t)[1].split("\\+")[0];
                    if (!sa.getSVar(varName).isEmpty() || source.hasSVar(varName)) {
                        valid = TextUtil.fastReplace(valid, TextUtil.concatNoSpace(t, varName),
                                TextUtil.concatNoSpace(t, Integer.toString(calculateAmount(source, varName, sa))));
                    }
                }
            }
        }
        if (sa.hasParam("AbilityCount")) { // replace specific string other than "EQ" cases
        	String var = sa.getParam("AbilityCount");
        	valid = TextUtil.fastReplace(valid, var, Integer.toString(calculateAmount(source, var, sa)));
        }
        return CardLists.getValidCards(list, valid.split(","), sa.getActivatingPlayer(), source, sa);
    }

    /**
     * <p>
     * getDefinedPlayers.
     * </p>
     * 
     * @param card
     *            a {@link forge.game.card.Card} object.
     * @param def
     *            a {@link java.lang.String} object.
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a {@link java.util.ArrayList} object.
     */
    public static PlayerCollection getDefinedPlayers(final Card card, final String def, final SpellAbility sa) {
        final PlayerCollection players = new PlayerCollection();
        final String defined = (def == null) ? "You" : applyAbilityTextChangeEffects(def, sa);
        final Game game = card == null ? null : card.getGame();

        final Player player = sa == null ? card.getController() : sa.getActivatingPlayer();

        if (defined.equals("Targeted") || defined.equals("TargetedPlayer")) {
            final SpellAbility saTargeting = sa.getSATargetingPlayer();
            if (saTargeting != null) {
                players.addAll(saTargeting.getTargets().getTargetPlayers());
            }
        }
        else if (defined.equals("ParentTarget")) {
            final SpellAbility parent = sa.getParentTargetingPlayer();
            if (parent != null) {
                players.addAll(parent.getTargets().getTargetPlayers());
            }
        }
        else if (defined.equals("ThisTargetedPlayer")) { // do not add parent targeted
            if (sa != null && sa.getTargets() != null) {
                Iterables.addAll(players, sa.getTargets().getTargetPlayers());
            }
        }
        else if (defined.equals("TargetedController")) {
            final CardCollection list = getDefinedCards(card, "Targeted", sa);
            final List<SpellAbility> sas = getDefinedSpellAbilities(card, "Targeted", sa);

            for (final Card c : list) {
                final Player p = c.getController();
                if (!players.contains(p)) {
                    players.add(p);
                }
            }
            for (final SpellAbility s : sas) {
                final Player p = s.getActivatingPlayer();
                if (!players.contains(p)) {
                    players.add(p);
                }
            }
        }
        else if (defined.equals("TargetedOwner")) {
            final CardCollection list = getDefinedCards(card, "Targeted", sa);

            for (final Card c : list) {
                final Player p = c.getOwner();
                if (!players.contains(p)) {
                    players.add(p);
                }
            }
        }
        else if (defined.equals("TargetedAndYou")) {
            final SpellAbility saTargeting = sa.getSATargetingPlayer();
            if (saTargeting != null) {
                players.addAll(saTargeting.getTargets().getTargetPlayers());
                players.add(sa.getActivatingPlayer());
            }
        }
        else if (defined.equals("ParentTargetedController")) {
            final CardCollection list = getDefinedCards(card, "ParentTarget", sa);
            final List<SpellAbility> sas = getDefinedSpellAbilities(card, "Targeted", sa);

            for (final Card c : list) {
                final Player p = c.getController();
                if (!players.contains(p)) {
                    players.add(p);
                }
            }
            for (final SpellAbility s : sas) {
                final Player p = s.getActivatingPlayer();
                if (!players.contains(p)) {
                    players.add(p);
                }
            }
        }
        else if (defined.startsWith("Remembered")) {
            addPlayer(card.getRemembered(), defined, players);
        }
        else if (defined.startsWith("DelayTriggerRemembered")) {
            SpellAbility trigSa = sa.getTriggeringAbility();
            if (trigSa != null) {
                addPlayer(trigSa.getTriggerRemembered(), defined, players);
            } else {
                System.err.println("Warning: couldn't find trigger SA in the chain of SpellAbility " + sa);
            }
        }
        else if (defined.equals("ImprintedController")) {
            for (final Card rem : card.getImprintedCards()) {
                players.add(rem.getController());
            }
        }
        else if (defined.equals("ImprintedOwner")) {
            for (final Card rem : card.getImprintedCards()) {
                players.add(rem.getOwner());
            }
        }
        else if (defined.startsWith("Triggered")) {
            String defParsed = defined.endsWith("AndYou") ? defined.substring(0, defined.indexOf("AndYou")) : defined;
            if (defined.endsWith("AndYou")) {
                players.add(sa.getActivatingPlayer());
            }
            final SpellAbility root = sa.getRootAbility();
            Object o = null;
            if (defParsed.endsWith("Controller")) {
                String triggeringType = defParsed.substring(9);
                triggeringType = triggeringType.substring(0, triggeringType.length() - 10);
                final Object c = root.getTriggeringObject(triggeringType);
                if (c instanceof Card) {
                    o = ((Card) c).getController();
                }
                if (c instanceof SpellAbility) {
                    o = ((SpellAbility) c).getActivatingPlayer();
                }
            }
            else if (defParsed.endsWith("Opponent")) {
                String triggeringType = defParsed.substring(9);
                triggeringType = triggeringType.substring(0, triggeringType.length() - 8);
                final Object c = root.getTriggeringObject(triggeringType);
                if (c instanceof Card) {
                    o = ((Card) c).getController().getOpponents();
                }
                if (c instanceof SpellAbility) {
                    o = ((SpellAbility) c).getActivatingPlayer().getOpponents();
                }
            }
            else if (defParsed.endsWith("Owner")) {
                String triggeringType = defParsed.substring(9);
                triggeringType = triggeringType.substring(0, triggeringType.length() - 5);
                final Object c = root.getTriggeringObject(triggeringType);
                if (c instanceof Card) {
                    o = ((Card) c).getOwner();
                }
            }
            else {
                final String triggeringType = defParsed.substring(9);
                o = root.getTriggeringObject(triggeringType);
            }
            if (o != null) {
                if (o instanceof Player) {
                    final Player p = (Player) o;
                    if (!players.contains(p)) {
                        players.add(p);
                    }
                }
                if (o instanceof List) {
                    final List<?> pList = (List<?>)o;
                    if (!pList.isEmpty()) {
                        for (final Object p : pList) {
                            if (p instanceof Player && !players.contains(p)) {
                                players.add((Player) p);
                            }
                        }
                    }
                }
            }
        }
        else if (defined.startsWith("OppNon")) {
            players.addAll(player.getOpponents());
            players.removeAll((Collection<?>)getDefinedPlayers(card, defined.substring(6), sa));
        }
        else if (defined.startsWith("Replaced")) {
            final SpellAbility root = sa.getRootAbility();
            Object o = null;
            if (defined.endsWith("Controller")) {
                String replacingType = defined.substring(8);
                replacingType = replacingType.substring(0, replacingType.length() - 10);
                final Object c = root.getReplacingObject(replacingType);
                if (c instanceof Card) {
                    o = ((Card) c).getController();
                }
                if (c instanceof SpellAbility) {
                    o = ((SpellAbility) c).getHostCard().getController();
                }
            }
            else if (defined.endsWith("Owner")) {
                String replacingType = defined.substring(8);
                replacingType = replacingType.substring(0, replacingType.length() - 5);
                final Object c = root.getReplacingObject(replacingType);
                if (c instanceof Card) {
                    o = ((Card) c).getOwner();
                }
            }
            else {
                final String replacingType = defined.substring(8);
                o = root.getReplacingObject(replacingType);
            }
            if (o != null) {
                if (o instanceof Player) {
                    final Player p = (Player) o;
                    if (!players.contains(p)) {
                        players.add(p);
                    }
                }
            }
        }
        else if (defined.startsWith("Non")) {
            players.addAll(game.getPlayersInTurnOrder());
            players.removeAll((FCollectionView<Player>)getDefinedPlayers(card, defined.substring(3), sa));
        }
        else if (defined.equals("EnchantedController")) {
            if (card.getEnchantingCard() == null) {
                return players;
            }
            final Player p = card.getEnchantingCard().getController();
            if (!players.contains(p)) {
                players.add(p);
            }
        }
        else if (defined.equals("EnchantedOwner")) {
            if (card.getEnchantingCard() == null) {
                return players;
            }
            final Player p = card.getEnchantingCard().getOwner();
            if (!players.contains(p)) {
                players.add(p);
            }
        }
        else if (defined.equals("EnchantedPlayer")) {
            final Object o = sa.getHostCard().getEnchanting();
            if (o instanceof Player) {
                if (!players.contains(o)) {
                    players.add((Player) o);
                }
            }
        }
        else if (defined.equals("AttackingPlayer")) {
            final Player p = game.getCombat().getAttackingPlayer();
            if (!players.contains(p)) {
                players.add(p);
            }
        }
        else if (defined.equals("DefendingPlayer")) {
            players.add(game.getCombat().getDefendingPlayerRelatedTo(card));
        }
        else if (defined.equals("OpponentsOtherThanDefendingPlayer")) {
            players.addAll(player.getOpponents());
            players.remove(game.getCombat().getDefendingPlayerRelatedTo(card));
        }
        else if (defined.equals("ChosenPlayer")) {
            final Player p = card.getChosenPlayer();
            if (p != null && !players.contains(p)) {
                players.add(p);
            }
        }
        else if (defined.equals("ChosenAndYou")) {
            players.add(player);
            final Player p = card.getChosenPlayer();
            if (p != null && !players.contains(p)) {
                players.add(p);
            }
        }
        else if (defined.equals("ChosenCardController")) {
            for (final Card chosen : card.getChosenCards()) {
                players.add(game.getCardState(chosen).getController());
            }
        }
        else if (defined.equals("SourceController")) {
            final Player p = sa.getHostCard().getController();
            if (!players.contains(p)) {
                players.add(p);
            }
        }
        else if (defined.equals("CardController")) {
            players.add(card.getController());
        }
        else if (defined.equals("CardOwner")) {
            players.add(card.getOwner());
        }
        else if (defined.startsWith("PlayerNamed_")) {
            for (Player p : game.getPlayersInTurnOrder()) {
                //System.out.println("Named player " + defined.substring(12));
                if (p.getName().equals(defined.substring(12))) {
                    players.add(p);
                }
            }
        }
        else if (defined.startsWith("Flipped")) {
            for (Player p : game.getPlayersInTurnOrder()) {
                if (null != sa.getHostCard().getFlipResult(p)) {
                    if (sa.getHostCard().getFlipResult(p).equals(defined.substring(7))) {
                        players.add(p);
                    }
                }
            }
        }
        else if (defined.equals("ActivePlayer")) {
        	players.add(game.getPhaseHandler().getPlayerTurn());
        }
        else if (defined.equals("You")) {
            players.add(player);
        }
        else if (defined.equals("Opponent")) {
            players.addAll(player.getOpponents());
        }
        else {
            for (Player p : game.getPlayersInTurnOrder()) {
                if (p.isValid(defined, player, card, sa)) {
                    players.add(p);
                }
            }
        }
        return players;
    }

    /**
     * <p>
     * getDefinedSpellAbilities.
     * </p>
     * 
     * @param card
     *            a {@link forge.game.card.Card} object.
     * @param def
     *            a {@link java.lang.String} object.
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a {@link java.util.ArrayList} object.
     */
    public static FCollection<SpellAbility> getDefinedSpellAbilities(final Card card, final String def,
            final SpellAbility sa) {
        final FCollection<SpellAbility> sas = new FCollection<SpellAbility>();
        final String defined = (def == null) ? "Self" : applyAbilityTextChangeEffects(def, sa); // default to Self
        final Game game = card.getGame();

        SpellAbility s = null;

        // TODO - this probably needs to be fleshed out a bit, but the basics
        // work
        if (defined.equals("Self")) {
            s = sa;
        }
        else if (defined.equals("Parent")) {
            s = sa.getRootAbility();
        }
        else if (defined.equals("Targeted")) {
            final SpellAbility saTargeting = sa.getSATargetingSA();
            if (saTargeting != null) {
                for (SpellAbility targetSpell : saTargeting.getTargets().getTargetSpells()) {
                    SpellAbilityStackInstance stackInstance = game.getStack().getInstanceFromSpellAbility(targetSpell);
                    if (stackInstance != null) {
                        SpellAbility instanceSA = stackInstance.getSpellAbility(true);
                        if (instanceSA != null) {
                            sas.add(instanceSA);
                        }
                    }
                    else {
                        sas.add(targetSpell);
                    }
                }
            }
        }
        else if (defined.startsWith("Triggered")) {
            final SpellAbility root = sa.getRootAbility();

            final String triggeringType = defined.substring(9);
            final Object o = root.getTriggeringObject(triggeringType);
            if (o instanceof SpellAbility) {
                s = (SpellAbility) o;
                // if there is no target information in SA but targets are listed in SpellAbilityTargeting cards, copy that
                // information so it's not lost if the calling code is interested in targets of the triggered SA.
                if (triggeringType.equals("SpellAbility")) {
                    final CardCollectionView tgtList = (CardCollectionView)root.getTriggeringObject("SpellAbilityTargetingCards");
                    if (s.getTargets() != null && s.getTargets().getNumTargeted() == 0) {
                        if (tgtList != null && tgtList.size() > 0) {
                            TargetChoices tc = new TargetChoices();
                            for (Card c : tgtList) {
                                tc.add(c);
                            }
                            s.setTargets(tc);
                        }
                    }
                }
            } else if (o instanceof SpellAbilityStackInstance) {
                s = ((SpellAbilityStackInstance) o).getSpellAbility(true);
            }
        }
        else if (defined.equals("Remembered")) {
            for (final Object o : card.getRemembered()) {
                if (o instanceof Card) {
                    final Card rem = (Card) o;
                    sas.addAll(game.getCardState(rem).getSpellAbilities());
                }
            }
        }
        else if (defined.equals("Imprinted")) {
            for (final Card imp : card.getImprintedCards()) {
                sas.addAll(imp.getSpellAbilities());
            }
        }
        else if (defined.equals("EffectSource")) {
            if (card.getEffectSource() != null) {
                sas.addAll(card.getEffectSource().getSpellAbilities());
            }
        }
        else if (defined.equals("SourceFirstSpell")) {
            sas.add(card.getFirstSpellAbility());
        }

        if (s != null) {
            sas.add(s);
        }

        return sas;
    }


    /////////////////////////////////////////////////////////////////////////////////////
    //
    // BELOW ARE resove() METHOD AND ITS DEPENDANTS, CONSIDER MOVING TO DEDICATED CLASS
    //
    /////////////////////////////////////////////////////////////////////////////////////
    public static void resolve(final SpellAbility sa) {
        if (sa == null) {
            return;
        }

        // do blessing there before condition checks
        if (sa.isSpell() && sa.isBlessing() && !sa.getHostCard().isPermanent()) {
            Player pl = sa.getActivatingPlayer();
            if (pl != null && pl.getZone(ZoneType.Battlefield).size() >= 10) {
                pl.setBlessing(true);
            }
        }

        final ApiType api = sa.getApi();
        if (api == null) {
            sa.resolve();
            if (sa.getSubAbility() != null) {
                resolve(sa.getSubAbility());
            }
            return;
        }

        AbilityUtils.resolveApiAbility(sa, sa.getActivatingPlayer().getGame());
    }

    private static void resolveSubAbilities(final SpellAbility sa, final Game game) {
        final AbilitySub abSub = sa.getSubAbility();
        if (abSub == null || sa.isWrapper()) {
            return;
        }

        // Needed - Equip an untapped creature with Sword of the Paruns then cast Deadshot on it. Should deal 2 more damage.
        game.getAction().checkStaticAbilities(); // this will refresh continuous abilities for players and permanents.
        if (sa.isReplacementAbility() && abSub.getApi() == ApiType.InternalEtbReplacement) {
            game.getTriggerHandler().resetActiveTriggers(false);
        } else {
            game.getTriggerHandler().resetActiveTriggers();
        }
        AbilityUtils.resolveApiAbility(abSub, game);
    }

    private static void resolveApiAbility(final SpellAbility sa, final Game game) {
        // check conditions
        if (sa.getConditions().areMet(sa)) {
            if (sa.isWrapper() || StringUtils.isBlank(sa.getParam("UnlessCost"))) {
                sa.resolve();
            }
            else {
                handleUnlessCost(sa, game);
                return;
            }
        }
        resolveSubAbilities(sa, game);
    }

    private static void handleUnlessCost(final SpellAbility sa, final Game game) {
        final Card source = sa.getHostCard();

        // The player who has the chance to cancel the ability
        final String pays = sa.hasParam("UnlessPayer") ? sa.getParam("UnlessPayer") : "TargetedController";
        final FCollectionView<Player> allPayers = getDefinedPlayers(sa.getHostCard(), pays, sa);
        final String  resolveSubs = sa.getParam("UnlessResolveSubs"); // no value means 'Always'
        final boolean execSubsWhenPaid = "WhenPaid".equals(resolveSubs) || StringUtils.isBlank(resolveSubs);
        final boolean execSubsWhenNotPaid = "WhenNotPaid".equals(resolveSubs) || StringUtils.isBlank(resolveSubs);
        final boolean isSwitched = sa.hasParam("UnlessSwitched");

        // The cost
        Cost cost;
        String unlessCost = sa.getParam("UnlessCost").trim();
        if (unlessCost.equals("CardManaCost")) {
            cost = new Cost(source.getManaCost(), true);
        }
        else if (unlessCost.equals("TriggeredSpellManaCost")) {
            SpellAbility triggered = (SpellAbility) sa.getRootAbility().getTriggeringObject("SpellAbility");
            Card triggeredCard = triggered.getHostCard();
            if (triggeredCard.getManaCost() == null) {
                cost = new Cost(ManaCost.ZERO, true);
            } else {
                int xCount = triggeredCard.getManaCost().countX();
                int xPaid = triggeredCard.getXManaCostPaid() * xCount;
                ManaCostBeingPaid toPay = new ManaCostBeingPaid(triggeredCard.getManaCost());
                toPay.decreaseShard(ManaCostShard.X, xCount);
                toPay.increaseGenericMana(xPaid);
                cost = new Cost(toPay.toManaCost(), true);
            }
        }
        else if (unlessCost.equals("ChosenManaCost")) {
        	if (!source.hasChosenCard()) {
                cost = new Cost(ManaCost.ZERO, true);
            }
        	else {
            	cost = new Cost(Iterables.getFirst(source.getChosenCards(), null).getManaCost(), true);
            }
        }
        else if (unlessCost.equals("ChosenNumber")) {
            cost = new Cost(new ManaCost(new ManaCostParser(String.valueOf(source.getChosenNumber()))), true);
        }
        else if (unlessCost.equals("RememberedCostMinus2")) {
            Card rememberedCard = (Card) source.getFirstRemembered();
            if (rememberedCard == null) {
                sa.resolve();
                resolveSubAbilities(sa, game);
                return;
            }
            ManaCostBeingPaid newCost = new ManaCostBeingPaid(rememberedCard.getManaCost());
            newCost.decreaseGenericMana(2);
            cost = new Cost(newCost.toManaCost(), true);
        }
        else if (!StringUtils.isBlank(sa.getSVar(unlessCost)) || !StringUtils.isBlank(source.getSVar(unlessCost))) {
            // check for X costs (stored in SVars
            int xCost = calculateAmount(source, TextUtil.fastReplace(sa.getParam("UnlessCost"),
                    " ", ""), sa);
            //Check for XColor
            ManaCostBeingPaid toPay = new ManaCostBeingPaid(ManaCost.ZERO);
            byte xColor = ManaAtom.fromName(sa.hasParam("UnlessXColor") ? sa.getParam("UnlessXColor") : "1");
            toPay.increaseShard(ManaCostShard.valueOf(xColor), xCost);
            cost = new Cost(toPay.toManaCost(), true);
        }
        else {
            cost = new Cost(unlessCost, true);
        }

        boolean alreadyPaid = false;
        for (Player payer : allPayers) {
            if (unlessCost.equals("LifeTotalHalfUp")) {
                String halfup = Integer.toString((int) Math.ceil(payer.getLife() / 2.0));
                cost = new Cost("PayLife<" + halfup + ">", true);
            }
            alreadyPaid |= payer.getController().payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
        }

        if (alreadyPaid == isSwitched) {
            sa.resolve();
        }

        if (alreadyPaid && execSubsWhenPaid || !alreadyPaid && execSubsWhenNotPaid) { // switched refers only to main ability!
            resolveSubAbilities(sa, game);
        }
    }

    /**
     * <p>
     * handleRemembering.
     * </p>
     * 
     * @param sa
     *            a SpellAbility object.
     */
    public static void handleRemembering(final SpellAbility sa) {
        Card host = sa.getHostCard();

        if (sa.hasParam("RememberTargets") && sa.getTargetRestrictions() != null) {
            if (sa.hasParam("ForgetOtherTargets")) {
                host.clearRemembered();
            }
            for (final GameObject o : sa.getTargets().getTargets()) {
                host.addRemembered(o);
            }
        }

        if (sa.hasParam("ImprintTargets") && sa.getTargetRestrictions() != null) {
            for (final Card c : sa.getTargets().getTargetCards()) {
                host.addImprintedCard(c);
            }
        }

        if (sa.hasParam("RememberCostMana")) {
            host.clearRemembered();
            host.addRemembered(sa.getPayingMana());
        }

        if (sa.hasParam("RememberCostCards") && !sa.getPaidHash().isEmpty()) {
            if (sa.getParam("Cost").contains("Exile")) {
                final CardCollection paidListExiled = sa.getPaidList("Exiled");
                for (final Card exiledAsCost : paidListExiled) {
                    host.addRemembered(exiledAsCost);
                }
            }
            else if (sa.getParam("Cost").contains("Sac")) {
                final CardCollection paidListSacrificed = sa.getPaidList("Sacrificed");
                for (final Card sacrificedAsCost : paidListSacrificed) {
                    host.addRemembered(sacrificedAsCost);
                }
            }
            else if (sa.getParam("Cost").contains("tapXType")) {
                final CardCollection paidListTapped = sa.getPaidList("Tapped");
                for (final Card tappedAsCost : paidListTapped) {
                    host.addRemembered(tappedAsCost);
                }
            }
            else if (sa.getParam("Cost").contains("Unattach")) {
                final CardCollection paidListUnattached = sa.getPaidList("Unattached");
                for (final Card unattachedAsCost : paidListUnattached) {
                    host.addRemembered(unattachedAsCost);
                }
            }
            else if (sa.getParam("Cost").contains("Discard")) {
                final CardCollection paidListDiscarded = sa.getPaidList("Discarded");
                for (final Card discardedAsCost : paidListDiscarded) {
                    host.addRemembered(discardedAsCost);
                }
            }
        }
    }

    /**
     * <p>
     * Parse non-mana X variables.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @param s
     *            a {@link java.lang.String} object.
     * @param ctb
     *            a {@link forge.game.CardTraitBase} object.
     * @return a int.
     */
    public static int xCount(final Card c, final String s, final CardTraitBase ctb) {
        final String s2 = AbilityUtils.applyAbilityTextChangeEffects(s, ctb);
        final String[] l = s2.split("/");
        final String expr = CardFactoryUtil.extractOperators(s2);

        final String[] sq;
        sq = l[0].split("\\.");

        if (ctb != null) {
            // Count$Compare <int comparator value>.<True>.<False>
            if (sq[0].startsWith("Compare")) {
                final String[] compString = sq[0].split(" ");
                final int lhs = calculateAmount(c, compString[1], ctb);
                final int rhs =  calculateAmount(c, compString[2].substring(2), ctb);
                if (Expressions.compare(lhs, compString[2], rhs)) {
                    return CardFactoryUtil.doXMath(calculateAmount(c, sq[1], ctb), expr, c);
                }
                else {
                    return CardFactoryUtil.doXMath(calculateAmount(c, sq[2], ctb), expr, c);
                }
            }
            if (ctb instanceof SpellAbility) {
                final SpellAbility sa = (SpellAbility) ctb;

                // special logic for xPaid in SpellAbility
                if (sq[0].contains("xPaid")) {
                    // ETB effects of cloned cards have xPaid = 0
                    if (sa.hasParam("ETB") && sa.getOriginalHost() != null) {
                        return 0;
                    }
                    return CardFactoryUtil.doXMath(c.getXManaCostPaid(), expr, c);
                }

                // Count$Kicked.<numHB>.<numNotHB>
                if (sq[0].startsWith("Kicked")) {
                    if (((SpellAbility)ctb).isKicked()) {
                        return CardFactoryUtil.doXMath(Integer.parseInt(sq[1]), expr, c); // Kicked
                    }
                    else {
                        return CardFactoryUtil.doXMath(Integer.parseInt(sq[2]), expr, c); // not Kicked
                    }
                }

                //Count$SearchedLibrary.<DefinedPlayer>
                if (sq[0].contains("SearchedLibrary")) {
                    int sum = 0;
                    for (Player p : AbilityUtils.getDefinedPlayers(c, sq[1], sa)) {
                        sum += p.getLibrarySearched();
                    }

                    return sum;
                }
                //Count$HasNumChosenColors.<DefinedCards related to spellability>
                if (sq[0].contains("HasNumChosenColors")) {
                    int sum = 0;
                    for (Card card : AbilityUtils.getDefinedCards(sa.getHostCard(), sq[1], sa)) {
                        sum += CardUtil.getColors(card).getSharedColors(ColorSet.fromNames(c.getChosenColors())).countColors();
                    }
                    return sum;
                }
                if (sq[0].startsWith("TriggerRememberAmount")) {
                    SpellAbility trigSa = sa.getTriggeringAbility();
                    int count = 0;
                    for (final Object o : trigSa.getTriggerRemembered()) {
                        if (o instanceof Integer) {
                            count += (Integer) o;
                        }
                    }
                    return count;
                }
                // Count$TriggeredPayingMana.<Color1>.<Color2>
                if (sq[0].startsWith("TriggeredPayingMana")) {
                    final SpellAbility root = sa.getRootAbility();
                    String mana = (String) root.getTriggeringObject("PayingMana");
                    int count = 0;
                    Matcher mat = Pattern.compile(StringUtils.join(sq, "|", 1, sq.length)).matcher(mana);
                    while (mat.find()) {
                        count++;
                    }
                    return count;
                }

                if (l[0].startsWith("LastStateBattlefield")) {
                    final String[] k = l[0].split(" ");
                    CardCollectionView list = null;
                    if (sa.getLastStateBattlefield() != null && !sa.getLastStateBattlefield().isEmpty()) {
                    	list = new CardCollection(sa.getLastStateBattlefield());
                    } else { // LastState is Empty
                    	list = sa.getHostCard().getGame().getCardsIn(ZoneType.Battlefield);
                    }
                    list = CardLists.getValidCards(list, k[1].split(","), sa.getActivatingPlayer(), c, sa);
                    return CardFactoryUtil.doXMath(list.size(), expr, c);
                }

                if (l[0].startsWith("LastStateGraveyard")) {
                    final String[] k = l[0].split(" ");
                    CardCollectionView list = null;
                    if (sa.getLastStateGraveyard() != null && !sa.getLastStateGraveyard().isEmpty()) {
                        list = new CardCollection(sa.getLastStateGraveyard());
                    } else { // LastState is Empty
                        list = sa.getHostCard().getGame().getCardsIn(ZoneType.Graveyard);
                    }
                    list = CardLists.getValidCards(list, k[1].split(","), sa.getActivatingPlayer(), c, sa);
                    return CardFactoryUtil.doXMath(list.size(), expr, c);
                }

                // Count$TargetedLifeTotal (targeted player's life total)
                // Not optimal but since xCount doesn't take SAs, we need to replicate while we have it
                // Probably would be best if xCount took an optional SA to use in these circumstances
                if (sq[0].contains("TargetedLifeTotal")) {
                    final SpellAbility saTargeting = sa.getSATargetingPlayer();
                    if (saTargeting != null) {
                        for (final Player tgtP : saTargeting.getTargets().getTargetPlayers()) {
                            return CardFactoryUtil.doXMath(tgtP.getLife(), expr, c);
                        }
                    }
                }
            }
        }
        if(ctb instanceof SpellAbility) {
            Player activator = ((SpellAbility)ctb).getActivatingPlayer();
            if(activator != null) {
                return CardFactoryUtil.xCount(c, s2, activator);
            }
        }
        return CardFactoryUtil.xCount(c, s2);
    }

    public static final void applyManaColorConversion(final Player p, final Map<String, String> params) {
        String conversionType = params.get("ManaColorConversion");

        // Choices are Additives(OR) or Restrictive(AND)
        boolean additive = "Additive".equals(conversionType);

        for(String c : MagicColor.Constant.COLORS_AND_COLORLESS) {
            // Use the strings from MagicColor, since that's how the Script will be coming in as
            String key = WordUtils.capitalize(c) + "Conversion";
            if (params.containsKey(key)) {
                String convertTo = params.get(key);
                byte convertByte = 0;
                if ("All".equals(convertTo)) {
                    convertByte = ColorSet.ALL_COLORS.getColor();
                } else {
                    for (final String convertColor : convertTo.split(",")) {
                        convertByte |= ManaAtom.fromName(convertColor);
                    }
                }
                // AdjustColorReplacement has two different matrices handling final mana conversion under the covers
                p.getManaPool().adjustColorReplacement(ManaAtom.fromName(c), convertByte, additive);
            }
        }
    }

    private static boolean checkZone(final Spell spell, final Card card, final Player player) {
        if (spell.toString().startsWith("Fuse (") && !player.getGame().getZoneOf(card).is(ZoneType.Hand, player)) {
            return false;
        }
        if (spell.isAftermath() && !player.getGame().getZoneOf(card).is(ZoneType.Graveyard, player)) {
            return false;
        }
        return true;
    }

    public static final List<SpellAbility> getBasicSpellsFromPlayEffect(final Card tgtCard, final Player controller) {
        List<SpellAbility> sas = new ArrayList<SpellAbility>();
        for (SpellAbility s : tgtCard.getBasicSpells()) {
            final Spell newSA = (Spell) s.copy();
            if (!checkZone(newSA, tgtCard, controller)) {
                continue;
            }
            newSA.setActivatingPlayer(controller);
            SpellAbilityRestriction res = new SpellAbilityRestriction();
            // timing restrictions still apply
            res.setPlayerTurn(s.getRestrictions().getPlayerTurn());
            res.setOpponentTurn(s.getRestrictions().getOpponentTurn());
            res.setPhases(s.getRestrictions().getPhases());
            res.setZone(null);
            newSA.setRestrictions(res);
            // timing restrictions still apply
            if (res.checkTimingRestrictions(tgtCard, newSA) && newSA.checkOtherRestrictions()) {
                sas.add(newSA);
            }
        }
        return sas;
    }

    public static final String applyAbilityTextChangeEffects(final String def, final CardTraitBase ability) {
        if (ability == null || !ability.isIntrinsic() || ability.getMapParams().containsKey("LockInText")) {
            return def;
        }
        return applyTextChangeEffects(def, ability.getHostCard(), false);
    }

    public static final String applyKeywordTextChangeEffects(final String kw, final Card card) {
        if (!CardUtil.isKeywordModifiable(kw)) {
            return kw;
        }
        return applyTextChangeEffects(kw, card, false);
    }

    public static final String applyDescriptionTextChangeEffects(final String def, final CardTraitBase ability) {
        if (ability == null || !ability.isIntrinsic() || ability.getMapParams().containsKey("LockInText")) {
            return def;
        }
        return applyTextChangeEffects(def, ability.getHostCard(), true);
    }

    /**
     * Apply description-based text changes of a {@link Card} to a String. No
     * checks are made on traits being intrinsic.
     *
     * @param def a String.
     * @param card a {@link Card}.
     * @return a new String, taking text changes into account.
     */
    public static final String applyDescriptionTextChangeEffects(final String def, final Card card) {
        return applyTextChangeEffects(def, card, true);
    }

    private static final String applyTextChangeEffects(final String def, final Card card, final boolean isDescriptive) {
        if (StringUtils.isEmpty(def)) {
            return def;
        }

        String replaced = def;
        for (final Entry<String, String> e : card.getChangedTextColorWords().entrySet()) {
            final String key = e.getKey();
            String value;
            if (key.equals("Any")) {
                for (final byte c : MagicColor.WUBRG) {
                    final String colorLowerCase = MagicColor.toLongString(c).toLowerCase(),
                            colorCaptCase = StringUtils.capitalize(MagicColor.toLongString(c));
                    // Color should not replace itself.
                    if (e.getValue().equalsIgnoreCase(colorLowerCase)) {
                        continue;
                	}
                    value = getReplacedText(colorLowerCase, e.getValue(), isDescriptive);
                    replaced = replaced.replaceAll("(?<!>)" + colorLowerCase, value.toLowerCase());
                    value = getReplacedText(colorCaptCase, e.getValue(), isDescriptive);
                    replaced = replaced.replaceAll("(?<!>)" + colorCaptCase, StringUtils.capitalize(value));
                }
            } else {
                value = getReplacedText(key, e.getValue(), isDescriptive);
                replaced = replaced.replaceAll("(?<!>)" + key, value);
            }
        }
        for (final Entry<String, String> e : card.getChangedTextTypeWords().entrySet()) {
            final String key = e.getKey();
            final String pkey = CardType.getPluralType(key);
            final String pvalue = getReplacedText(pkey, CardType.getPluralType(e.getValue()), isDescriptive);
            replaced = replaced.replaceAll("(?<!>)" + pkey, pvalue);
            final String value = getReplacedText(key, e.getValue(), isDescriptive);
            replaced = replaced.replaceAll("(?<!>)" + key, value);
        }
        return replaced;
    }

    private static final String getReplacedText(final String originalWord, final String newWord, final boolean isDescriptive) {
        if (isDescriptive) {
            return "<strike>" + originalWord + "</strike> " + newWord;
        }
        return newWord;
    }

    public static final String getSVar(final CardTraitBase ability, final String sVarName) {
        String val = null;
        if (ability instanceof SpellAbility) {
            val = ((SpellAbility) ability).getSVar(sVarName);
        }
        if (StringUtils.isEmpty(val)) {
            Card host = null;
            if (ability instanceof SpellAbility) {
                host = ((SpellAbility) ability).getOriginalHost();
            }
            if (host == null) {
                host = ability.getHostCard();
            }
            val = host.getSVar(sVarName);
        }
        if (!ability.isIntrinsic() || StringUtils.isEmpty(val)) {
            return val;
        }
        return applyAbilityTextChangeEffects(val, ability);
    }

    private static void addPlayer(Iterable<Object> objects, final String def, FCollection<Player> players) {
        for (Object o : objects) {
            if (o instanceof Player) {
                final Player p = (Player) o;
                if (def.endsWith("Opponents")) {
                    players.addAll(p.getOpponents());
                } else {
                    players.add(p);
                }
            } else if (o instanceof Card) {
                final Card c = (Card) o;
                if (def.endsWith("Controller")) {
                    players.add(c.getController());
                } else if (def.endsWith("Owner")) {
                    players.add(c.getOwner());
                }
            }
        }
    }
    
    public static SpellAbility addSpliceEffects(final SpellAbility sa) {
        final Card source = sa.getHostCard();
        final Player player = sa.getActivatingPlayer();
        
        final CardCollection splices = CardLists.filter(player.getCardsIn(ZoneType.Hand), new Predicate<Card>() {
            @Override
            public boolean apply(Card input) {
                return input.hasStartOfKeyword("Splice");
            }
        });

        splices.remove(source);

        if (splices.isEmpty()) {
            return sa;
        }

        final List<Card> choosen = player.getController().chooseCardsForSplice(sa, splices);

        if (choosen.isEmpty()) {
            return sa;
        }

        final SpellAbility newSA = sa.copy();
        for (final Card c : choosen) {
            addSpliceEffect(newSA, c);
        }
        return newSA;
    }

    public static void addSpliceEffect(final SpellAbility sa, final Card c) {
        Cost spliceCost = null;
        for (final KeywordInterface inst : c.getKeywords()) {
            final String k = inst.getOriginal();
            if (k.startsWith("Splice")) {
                final String n[] = k.split(":");
                spliceCost = new Cost(n[2], false);
            }
        }

        if (spliceCost == null)
            return;
        
        SpellAbility firstSpell = c.getFirstSpellAbility();
        Map<String, String> params = Maps.newHashMap(firstSpell.getMapParams());
        AbilityRecordType rc = AbilityRecordType.getRecordType(params);
        ApiType api = rc.getApiTypeOf(params);
        AbilitySub subAbility = (AbilitySub) AbilityFactory.getAbility(AbilityRecordType.SubAbility, api, params, null, c, null);

        subAbility.setActivatingPlayer(sa.getActivatingPlayer());
        subAbility.setHostCard(sa.getHostCard());
        subAbility.setOriginalHost(c);

        //add the spliced ability to the end of the chain
        sa.appendSubAbility(subAbility);

        // update master SpellAbility
        sa.setBasicSpell(false);
        sa.setPayCosts(spliceCost.add(sa.getPayCosts()));
        sa.setDescription(sa.getDescription() + " (Splicing " + c + " onto it)");
        sa.addSplicedCards(c);
    }
}
