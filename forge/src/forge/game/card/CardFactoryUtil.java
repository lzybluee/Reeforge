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
package forge.game.card;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import forge.GameCommand;
import forge.card.CardStateName;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostParser;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameLogEntryType;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.CardPredicates.Presets;
import forge.game.cost.Cost;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordsChange;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.replacement.ReplacementLayer;
import forge.game.spellability.AbilityStatic;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityRestriction;
import forge.game.spellability.TargetRestrictions;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import forge.util.Lang;
import forge.util.TextUtil;
import forge.util.collect.FCollectionView;

/**
 * <p>
 * CardFactoryUtil class.
 * </p>
 * 
 * @author Forge
 * @version $Id: CardFactoryUtil.java 35197 2017-08-24 13:29:46Z Agetian $
 */
public class CardFactoryUtil {

    /**
     * <p>
     * abilityMorphDown.
     * </p>
     * 
     * @param sourceCard
     *            a {@link forge.game.card.Card} object.
     * @return a {@link forge.game.spellability.SpellAbility} object.
     */
    public static SpellAbility abilityMorphDown(final Card sourceCard) {
        final Spell morphDown = new Spell(sourceCard, new Cost(ManaCost.THREE, false)) {
            private static final long serialVersionUID = -1438810964807867610L;

            @Override
            public void resolve() {
                Card c = hostCard.getGame().getAction().moveToPlay(hostCard, this);
                c.setPreFaceDownState(CardStateName.Original);
            }

            @Override
            public boolean canPlay() {
                CardStateName stateBackup = hostCard.getCurrentStateName();
                hostCard.setState(CardStateName.FaceDown, false);
                boolean success = super.canPlay();
                hostCard.setState(stateBackup, false);
                return success;
            }
        };

        morphDown.setDescription("(You may cast this card face down as a 2/2 creature for {3}.)");
        morphDown.setStackDescription("Morph - Creature 2/2");
        morphDown.setCastFaceDown(true);
        morphDown.setBasicSpell(false);

        return morphDown;
    }

    /**
     * <p>
     * abilityMorphUp.
     * </p>
     * 
     * @param sourceCard
     *            a {@link forge.game.card.Card} object.
     * @return a {@link forge.game.spellability.AbilityActivated} object.
     */
    public static SpellAbility abilityMorphUp(final Card sourceCard, final String costStr, final boolean mega) {
        Cost cost = new Cost(costStr, true);
        String costDesc = cost.toString();
        // get rid of the ": " at the end
        costDesc = costDesc.substring(0, costDesc.length() - 2);

        if (!cost.isOnlyManaCost()) {
            costDesc = "- " + costDesc;
        }

        String ab = "ST$ SetState | Cost$ " + costStr + " | CostDesc$ Morph " + costDesc
                + " | MorphUp$ True"
                + " | ConditionDefined$ Self | ConditionPresent$ Card.faceDown"
                + " | Mode$ TurnFace | SpellDescription$ (Turn this face up any time for its morph cost.)";
        if (mega) {
            ab += " | Mega$ True";
        }

        final SpellAbility morphUp = AbilityFactory.getAbility(ab, sourceCard);

        final StringBuilder sbStack = new StringBuilder();
        sbStack.append(sourceCard.getName()).append(" - turn this card face up.");
        morphUp.setStackDescription(sbStack.toString());
        morphUp.setIsMorphUp(true);

        return morphUp;
    }

    public static SpellAbility abilityManifestFaceUp(final Card sourceCard, final ManaCost manaCost) {

        String costDesc = manaCost.toString();

        // Cost need to be set later
        String ab = "ST$ SetState | Cost$ 0 | CostDesc$ Unmanifest " + costDesc
                + " | ManifestUp$ True"
                + " | ConditionDefined$ Self | ConditionPresent$ Card.faceDown+manifested"
                + " | Mode$ TurnFace | SpellDescription$ (Turn this face up any time for its mana cost.)";

        final SpellAbility manifestUp = AbilityFactory.getAbility(ab, sourceCard);
        manifestUp.setPayCosts(new Cost(manaCost, true));

        final StringBuilder sbStack = new StringBuilder();
        sbStack.append(sourceCard.getName()).append(" - turn this card face up.");
        manifestUp.setStackDescription(sbStack.toString());
        manifestUp.setIsManifestUp(true);

        return manifestUp;
    }

    public static boolean handleHiddenAgenda(Player player, Card card) {
        SpellAbility sa = new SpellAbility.EmptySa(card);
        sa.getMapParams().put("AILogic", card.getSVar("AgendaLogic"));
        Predicate<ICardFace> cpp = Predicates.alwaysTrue();
        //Predicate<Card> pc = Predicates.in(player.getAllCards());
        // TODO This would be better to send in the player's deck, not all cards
        String name = player.getController().chooseCardName(sa, cpp, "Card", "Name a card for " + card.getName());
        if (name == null || name.isEmpty()) {
            return false;
        }

        card.setNamedCard(name);
        card.turnFaceDown();
        card.setMayLookAt(player, true);
        card.addSpellAbility(abilityRevealHiddenAgenda(card));
        return true;
    }

    private static SpellAbility abilityRevealHiddenAgenda(final Card sourceCard) {
        String ab = "ST$ SetState | Cost$ 0"
                + " | ConditionDefined$ Self | ConditionPresent$ Card.faceDown+inZoneCommand"
                + " | HiddenAgenda$ True"
                + " | Mode$ TurnFace | SpellDescription$ Reveal this Hidden Agenda at any time.";
        return AbilityFactory.getAbility(ab, sourceCard);
    }

    /**
     * <p>
     * abilitySuspendStatic.
     * </p>
     * 
     * @param sourceCard
     *            a {@link forge.game.card.Card} object.
     * @param suspendCost
     *            a {@link java.lang.String} object.
     * @param timeCounters
     *            a int.
     * @return a {@link forge.game.spellability.SpellAbility} object.
     */
    public static SpellAbility abilitySuspendStatic(final Card sourceCard, final String suspendCost, final String timeCounters) {
        // be careful with Suspend ability, it will not hit the stack
        Cost cost = new Cost(suspendCost, true);
        final SpellAbility suspend = new AbilityStatic(sourceCard, cost, null) {
            @Override
            public boolean canPlay() {
                if (!(this.getRestrictions().canPlay(sourceCard, this))) {
                    return false;
                }

                if (sourceCard.isInstant() || sourceCard.hasKeyword("Flash")) {
                    return true;
                }

                return sourceCard.getOwner().canCastSorcery();
            }

            @Override
            public void resolve() {
                final Game game = sourceCard.getGame();
                final Card c = game.getAction().exile(sourceCard, this);

                int counters = AbilityUtils.calculateAmount(c, timeCounters, this);
                c.addCounter(CounterType.TIME, counters, c, true);
                
                String sb = String.format("%s has suspended %s with %d time counters on it.", this.getActivatingPlayer(), c.getName(), counters);
                game.getGameLog().add(GameLogEntryType.STACK_RESOLVE, sb);
            }
        };
        final StringBuilder sbDesc = new StringBuilder();
        sbDesc.append("Suspend ").append(timeCounters).append(" - ").append(cost.toSimpleString());
        sbDesc.append(" (Rather than cast CARDNAME from your hand, you may pay cost and exile it with three time counters on it. At the beginning of your upkeep, remove a time counter. When the last is removed, cast it without paying its mana cost.)");
        suspend.setDescription(sbDesc.toString());

        String svar = "X"; // emulate "References X" here
        suspend.setSVar(svar, sourceCard.getSVar(svar));

        final StringBuilder sbStack = new StringBuilder();
        sbStack.append(sourceCard.getName()).append(" suspending for ").append(timeCounters).append(" turns.)");
        suspend.setStackDescription(sbStack.toString());

        suspend.getRestrictions().setZone(ZoneType.Hand);
        return suspend;
    } // abilitySuspendStatic()

    /**
     * <p>
     * isTargetStillValid.
     * </p>
     * 
     * @param ability
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param target
     *            a {@link forge.game.card.Card} object.
     * @return a boolean.
     */
    public static boolean isTargetStillValid(final SpellAbility ability, final Card target) {
        Zone zone = target.getGame().getZoneOf(target);
        if (zone == null) {
            return false; // for tokens that disappeared
        }

        final Card source = ability.getHostCard();
        final TargetRestrictions tgt = ability.getTargetRestrictions();
        if (tgt != null) {
            // Reconfirm the Validity of a TgtValid, or if the Creature is still
            // a Creature
            if (tgt.doesTarget()
                    && !target.isValid(tgt.getValidTgts(), ability.getActivatingPlayer(), ability.getHostCard(), ability)) {
                return false;
            }

            // Check if the target is in the zone it needs to be in to be targeted
            if (!tgt.getZone().contains(zone.getZoneType())) {
                return false;
            }
        }
        else {
            // If an Aura's target is removed before it resolves, the Aura
            // fizzles
            if (source.isAura() && !target.isInZone(ZoneType.Battlefield)) {
                return false;
            }
        }

        // Make sure it's still targetable as well
        return ability.canTarget(target);
    }

    // does "target" have protection from "card"?
    /**
     * <p>
     * hasProtectionFrom.
     * </p>
     * 
     * @param card
     *            a {@link forge.game.card.Card} object.
     * @param target
     *            a {@link forge.game.card.Card} object.
     * @return a boolean.
     */
    public static boolean hasProtectionFrom(final Card card, final Card target) {
        if (target == null) {
            return false;
        }

        return target.hasProtectionFrom(card);
    }

    /**
     * <p>
     * isCounterable.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a boolean.
     */
    public static boolean isCounterable(final Card c) {
        if (c.hasKeyword("CARDNAME can't be countered.") || !c.getCanCounter()) {
            return false;
        }

        return true;
    }

    /**
     * <p>
     * isCounterableBy.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @param sa
     *            the sa
     * @return a boolean.
     */
    public static boolean isCounterableBy(final Card c, final SpellAbility sa) {
        if (!isCounterable(c)) {
            return false;
        }
        // Autumn's Veil
        if (c.hasKeyword("CARDNAME can't be countered by blue or black spells.") && sa.isSpell() 
                && (sa.getHostCard().isBlack() || sa.getHostCard().isBlue())) {
            return false;
        }
        return true;
    }

    /**
     * <p>
     * countOccurrences.
     * </p>
     * 
     * @param arg1
     *            a {@link java.lang.String} object.
     * @param arg2
     *            a {@link java.lang.String} object.
     * @return a int.
     */
    public static int countOccurrences(final String arg1, final String arg2) {
        int count = 0;
        int index = 0;
        while ((index = arg1.indexOf(arg2, index)) != -1) {
            ++index;
            ++count;
        }
        return count;
    }

    /**
     * <p>
     * parseMath.
     * </p>
     * 
     * @param expression
     *            a {@link java.lang.String} object.
     * @return an array of {@link java.lang.String} objects.
     */
    public static String extractOperators(final String expression) {
        String[] l = expression.split("/");
        return l.length > 1 ? l[1] : null;
    }

    /**
     * <p>
     * Parse player targeted X variables.
     * </p>
     * 
     * @param objects
     *            a {@link java.util.ArrayList} object.
     * @param s
     *            a {@link java.lang.String} object.
     * @param source
     *            a {@link forge.game.card.Card} object.
     * @return a int.
     */
    public static int objectXCount(final List<?> objects, final String s, final Card source) {
        if (objects.isEmpty()) {
            return 0;
        }

        if (s.startsWith("Valid")) {
            final CardCollection cards = new CardCollection();
            for (Object o : objects) {
                if (o instanceof Card) {
                    cards.add((Card) o);
                }
            }
            return CardFactoryUtil.handlePaid(cards, s, source);
        }

        int n = s.startsWith("Amount") ? objects.size() : 0;
        return doXMath(n, extractOperators(s), source);
    }

    /**
     * <p>
     * Parse player targeted X variables.
     * </p>
     * 
     * @param players
     *            a {@link java.util.ArrayList} object.
     * @param s
     *            a {@link java.lang.String} object.
     * @param source
     *            a {@link forge.game.card.Card} object.
     * @return a int.
     */
    public static int playerXCount(final List<Player> players, final String s, final Card source) {
        if (players.size() == 0) {
            return 0;
        }

        final String[] l = s.split("/");
        final String m = extractOperators(s);

        int n = 0;

        // methods for getting the highest/lowest playerXCount from a range of players
        if (l[0].startsWith("Highest")) {
            for (final Player player : players) {
                final int current = playerXProperty(player, s.replace("Highest", ""), source);
                if (current > n) {
                    n = current;
                }
            }

            return doXMath(n, m, source);
        }

        if (l[0].startsWith("Lowest")) {
            n = 99999; // if no players have fewer than 99999 valids, the game is frozen anyway
            for (final Player player : players) {
                final int current = playerXProperty(player, s.replace("Lowest", ""), source);
                if (current < n) {
                    n = current;
                }
            }
            return doXMath(n, m, source);
        }


        final String[] sq;
        sq = l[0].split("\\.");

        // the number of players passed in
        if (sq[0].equals("Amount")) {
            return doXMath(players.size(), m, source);
        }

        if (sq[0].startsWith("HasProperty")) {
            int totPlayer = 0;
            String property = sq[0].substring(11);
            for (Player p : players) {
                if (p.hasProperty(property, source.getController(), source, null)) {
                    totPlayer++;
                }
            }
            return doXMath(totPlayer, m, source);
        }

        if (sq[0].contains("DamageThisTurn")) {
            int totDmg = 0;
            for (Player p : players) {
                totDmg += p.getAssignedDamage();
            }
            return doXMath(totDmg, m, source);
        } else if (sq[0].contains("LifeLostThisTurn")) {
            int totDmg = 0;
            for (Player p : players) {
                totDmg += p.getLifeLostThisTurn();
            }
            return doXMath(totDmg, m, source);
        }

        if (players.size() > 0) {
            return playerXProperty(players.get(0), s, source);
        }

        return doXMath(n, m, source);
    }

    public static int playerXProperty(final Player player, final String s, final Card source) {
        final String[] l = s.split("/");
        final String m = extractOperators(s);
        
        final Game game = player.getGame();
        
        // count valid cards in any specified zone/s
        if (l[0].startsWith("Valid") && !l[0].contains("Valid ")) {
            String[] lparts = l[0].split(" ", 2);
            final List<ZoneType> vZone = ZoneType.listValueOf(lparts[0].split("Valid")[1]);
            String restrictions = l[0].replace(lparts[0] + " ", "");
            final String[] rest = restrictions.split(",");
            CardCollection cards = CardLists.getValidCards(game.getCardsIn(vZone), rest, player, source, null);
            return doXMath(cards.size(), m, source);
        }

        // count valid cards on the battlefield
        if (l[0].startsWith("Valid ")) {
            final String restrictions = l[0].substring(6);
            final String[] rest = restrictions.split(",");
            CardCollection cardsonbattlefield = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), rest, player, source, null);
            return doXMath(cardsonbattlefield.size(), m, source);
        }

        final String[] sq = l[0].split("\\.");
        final String value = sq[0];

        if (value.contains("CardsInHand")) {
            return doXMath(player.getCardsIn(ZoneType.Hand).size(), m, source);
        }

        if (value.contains("NumPowerSurgeLands")) {
            return doXMath(player.getNumPowerSurgeLands(), m, source);
        }

        if (value.contains("DomainPlayer")) {
            int n = 0;
            final CardCollectionView someCards = player.getCardsIn(ZoneType.Battlefield);
            final List<String> basic = MagicColor.Constant.BASIC_LANDS;

            for (int i = 0; i < basic.size(); i++) {
                if (!CardLists.getType(someCards, basic.get(i)).isEmpty()) {
                    n++;
                }
            }
            return doXMath(n, m, source);
        }

        if (value.contains("CardsInLibrary")) {
            return doXMath(player.getCardsIn(ZoneType.Library).size(), m, source);
        }

        if (value.contains("CardsInGraveyard")) {
            return doXMath(player.getCardsIn(ZoneType.Graveyard).size(), m, source);
        }
        if (value.contains("LandsInGraveyard")) {
            return doXMath(CardLists.getType(player.getCardsIn(ZoneType.Graveyard), "Land").size(), m, source);
        }

        if (value.contains("CreaturesInPlay")) {
            return doXMath(player.getCreaturesInPlay().size(), m, source);
        }

        if (value.contains("CardsInPlay")) {
            return doXMath(player.getCardsIn(ZoneType.Battlefield).size(), m, source);
        }

        if (value.contains("LifeTotal")) {
            return doXMath(player.getLife(), m, source);
        }

        if (value.contains("LifeLostThisTurn")) {
            return doXMath(player.getLifeLostThisTurn(), m, source);
        }

        if (value.contains("LifeLostLastTurn")) {
            return doXMath(player.getLifeLostLastTurn(), m, source);
        }

        if (value.contains("LifeGainedThisTurn")) {
            return doXMath(player.getLifeGainedThisTurn(), m, source);
        }

        if (value.contains("PoisonCounters")) {
            return doXMath(player.getPoisonCounters(), m, source);
        }

        if (value.contains("TopOfLibraryCMC")) {
            return doXMath(Aggregates.sum(player.getCardsIn(ZoneType.Library, 1), CardPredicates.Accessors.fnGetCmc), m, source);
        }

        if (value.contains("LandsPlayed")) {
            return doXMath(player.getLandsPlayedThisTurn(), m, source);
        }

        if (value.contains("CardsDrawn")) {
            return doXMath(player.getNumDrawnThisTurn(), m, source);
        }
        
        if (value.contains("CardsDiscardedThisTurn")) {
            return doXMath(player.getNumDiscardedThisTurn(), m, source);
        }

        if (value.contains("AttackersDeclared")) {
            return doXMath(player.getAttackersDeclaredThisTurn(), m, source);
        }

        if (value.equals("DamageDoneToPlayerBy")) {
            return doXMath(source.getDamageDoneToPlayerBy(player.getName()), m, source);
        }

        if (value.contains("DamageToOppsThisTurn")) {
            int oppDmg = 0;
            for (Player opp : player.getOpponents()) {
                oppDmg += opp.getAssignedDamage();
            }
            return doXMath(oppDmg, m, source);
        }
        
        return doXMath(0, m, source);
    }

    /**
     * <p>
     * Parse non-mana X variables.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @param expression
     *            a {@link java.lang.String} object.
     * @return a int.
     */
    public static int xCount(final Card c, final String expression) {
        if (StringUtils.isBlank(expression) || c == null) {
            return 0;
        }
        if (StringUtils.isNumeric(expression)) {
            return Integer.parseInt(expression);
        }

        final Player cc = c.getController();
        final Game game = c.getGame();
        final Player activePlayer = game.getPhaseHandler().getPlayerTurn();

        final String[] l = expression.split("/");
        final String m = extractOperators(expression);

        // accept straight numbers
        if (l[0].startsWith("Number$")) {
            final String number = l[0].substring(7);
            if (number.equals("ChosenNumber")) {
                return doXMath(c.getChosenNumber(), m, c);
            }
            return doXMath(Integer.parseInt(number), m, c);
        }

        if (l[0].startsWith("Count$")) {
            l[0] = l[0].substring(6);
        }

        if (l[0].startsWith("SVar$")) {
            return doXMath(xCount(c, c.getSVar(l[0].substring(5))), m, c);
        }

        if (l[0].startsWith("Controller$")) {
            return playerXProperty(cc, l[0].substring(11), c);
        }

        // Manapool
        if (l[0].startsWith("ManaPool")) {
            final String color = l[0].split(":")[1];
            if (color.equals("All")) {
                return cc.getManaPool().totalMana();
            }
            return cc.getManaPool().getAmountOfColor(ManaAtom.fromName(color));
        }

        // count valid cards in any specified zone/s
        if (l[0].startsWith("Valid")) {
            String[] lparts = l[0].split(" ", 2);
            final String[] rest = lparts[1].split(",");

            final CardCollectionView cardsInZones = lparts[0].length() > 5 
                ? game.getCardsIn(ZoneType.listValueOf(lparts[0].substring(5)))
                : game.getCardsIn(ZoneType.Battlefield);

            CardCollection cards = CardLists.getValidCards(cardsInZones, rest, cc, c, null);
            return doXMath(cards.size(), m, c);
        }

        if (l[0].startsWith("ImprintedCardManaCost") && !c.getImprintedCards().isEmpty()) {
            return c.getImprintedCards().get(0).getCMC();
        }

        if (l[0].startsWith("GreatestPower_")) {
            final String restriction = l[0].substring(14);
            final String[] rest = restriction.split(",");
            CardCollection list = CardLists.getValidCards(cc.getGame().getCardsIn(ZoneType.Battlefield), rest, cc, c, null);
            int highest = 0;
            for (final Card crd : list) {
                if (crd.getNetPower() > highest) {
                    highest = crd.getNetPower();
                }
            }
            return highest;
        }

        if (l[0].startsWith("GreatestToughness_")) {
            final String restriction = l[0].substring(18);
            final String[] rest = restriction.split(",");
            CardCollection list = CardLists.getValidCards(cc.getGame().getCardsIn(ZoneType.Battlefield), rest, cc, c, null);
            int highest = 0;
            for (final Card crd : list) {
                if (crd.getNetToughness() > highest) {
                    highest = crd.getNetToughness();
                }
            }
            return highest;
        }

        if (l[0].startsWith("HighestCMC_")) {
            final String restriction = l[0].substring(11);
            final String[] rest = restriction.split(",");
            CardCollection list = CardLists.getValidCards(cc.getGame().getCardsInGame(), rest, cc, c, null);
            int highest = 0;
            for (final Card crd : list) {
                // dont check for Split card anymore
                if (crd.getCMC() > highest) {
                    highest = crd.getCMC();
                }
            }
            return highest;
        }

        if (l[0].startsWith("MostCardName")) {
        	String[] lparts = l[0].split(" ", 2);
            final String[] rest = lparts[1].split(",");

            final CardCollectionView cardsInZones = lparts[0].length() > 12 
                ? game.getCardsIn(ZoneType.listValueOf(lparts[0].substring(12)))
                : game.getCardsIn(ZoneType.Battlefield);

            CardCollection cards = CardLists.getValidCards(cardsInZones, rest, cc, c, null);
            final Map<String, Integer> map = Maps.newHashMap();
            for (final Card card : cards) {
                // Remove Duplicated types
                final String name = card.getName();
                Integer count = map.get(name);
                map.put(name, count == null ? 1 : count + 1);
            }
            int max = 0;
            for (final Entry<String, Integer> entry : map.entrySet()) {
                if (max < entry.getValue()) {
                    max = entry.getValue();
                }
            }
            return max;
        }

        if (l[0].startsWith("DifferentCardNames_")) {
            final List<String> crdname = Lists.newArrayList();
            final String restriction = l[0].substring(19);
            final String[] rest = restriction.split(",");
            CardCollection list = CardLists.getValidCards(cc.getGame().getCardsInGame(), rest, cc, c, null);
            for (final Card card : list) {
                if (!crdname.contains(card.getName())) {
                    crdname.add(card.getName());
                }
            }
            return doXMath(crdname.size(), m, c);
        }

        if (l[0].startsWith("RememberedSize")) {
            return doXMath(c.getRememberedCount(), m, c);
        }

        if (l[0].startsWith("RememberedNumber")) {
            int num = 0;
            for (final Object o : c.getRemembered()) {
                if (o instanceof Integer) {
                    num += (Integer) o;
                }
            }
            return doXMath(num, m, c);
        }

        // Count$CountersAddedToPermYouCtrl <CounterType>
        if (l[0].startsWith("CountersAddedToPermYouCtrl")) {
            final String[] components = l[0].split(" ", 2);
            final CounterType counterType = CounterType.valueOf(components[1]);
            int n = cc.getCounterToPermThisTurn(counterType);
            return doXMath(n, m, c);
        }

        // Count$CountersAdded <CounterType> <ValidSource>
        if (l[0].startsWith("CountersAdded")) {
            final String[] components = l[0].split(" ", 3);
            final CounterType counterType = CounterType.valueOf(components[1]);
            String restrictions = components[2];
            final String[] rest = restrictions.split(",");
            CardCollection candidates = CardLists.getValidCards(game.getCardsInGame(), rest, cc, c, null);

            int added = 0;
            for (final Card counterSource : candidates) {
                added += c.getCountersAddedBy(counterSource, counterType);
            }
            return doXMath(added, m, c);
        }

        if (l[0].startsWith("CommanderCastFromCommandZone")) {
            // Read SVar CommanderCostRaise from Commander Effect
            Card commeff = CardLists.filter(cc.getCardsIn(ZoneType.Command), new Predicate<Card>() {
                @Override
                public boolean apply(Card input) {
                    return c.equals(input.getEffectSource()) && input.getName().endsWith("Commander Effect");
                }
            }).get(0);
            return doXMath(xCount(commeff, commeff.getSVar("CommanderCostRaise")), "DivideEvenlyDown.2", c);
        }
        
        if (l[0].startsWith("MostProminentCreatureType")) {
            String restriction = l[0].split(" ")[1];
            CardCollection list = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), restriction, cc, c);
            return doXMath(getMostProminentCreatureTypeSize(list), m, c);
        }

        if (l[0].startsWith("SecondMostProminentColor")) {
            String restriction = l[0].split(" ")[1];
            CardCollection list = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), restriction, cc, c);
            int[] colorSize = SortColorsFromList(list);
            return doXMath(colorSize[colorSize.length - 2], m, c);
        }

        if (l[0].startsWith("RolledThisTurn")) {
            return game.getPhaseHandler().getPlanarDiceRolledthisTurn();
        }

        //SacrificedThisTurn <type>
        if (l[0].startsWith("SacrificedThisTurn")) {
            CardCollectionView list = cc.getSacrificedThisTurn();
            if (l[0].contains(" ")) {
                final String[] components = l[0].split(" ", 2);
                list = CardLists.getValidCards(list, components[1], cc, c);
            }
            return list.size();
        }

        final String[] sq;
        sq = l[0].split("\\.");

        if (sq[0].contains("xPaid")) {
            return doXMath(c.getXManaCostPaid(), m, c);
        }

        if (sq[0].contains("xColorPaid")) {
            String[] attrs = sq[0].split(" ");
            String colors = "";
            for (int i = 1; i < attrs.length; i++) {
                colors += attrs[i];
            }
            return doXMath(c.getXManaCostPaidCount(colors), m, c);
        }


        if (sq[0].equals("YouDrewThisTurn")) {
            return doXMath(c.getController().getNumDrawnThisTurn(), m, c);
        }

        if (sq[0].equals("FirstSpellTotalManaSpent")) {
            try{
                return doXMath(c.getFirstSpellAbility().getTotalManaSpent(), m, c);
            } catch (NullPointerException e) {
                // This spell was not cast
                return 0;
            }

        }
        if (sq[0].equals("StormCount")) {
            return doXMath(game.getStack().getSpellsCastThisTurn().size() - 1, m, c);
        }
        if (sq[0].equals("DamageDoneThisTurn")) {
            return doXMath(c.getDamageDoneThisTurn(), m, c);
        }
        if (sq[0].equals("BloodthirstAmount")) {
            return doXMath(c.getController().getBloodthirstAmount(), m, c);
        }
        if (sq[0].equals("RegeneratedThisTurn")) {
            return doXMath(c.getRegeneratedThisTurn(), m, c);
        }
        
        // TriggeringObjects
        if (sq[0].startsWith("Triggered")) {
            return doXMath(xCount((Card) c.getTriggeringObject("Card"), sq[0].substring(9)), m, c);
        }

        if (sq[0].contains("YourStartingLife")) {
            return doXMath(cc.getStartingLife(), m, c);
        }

        if (sq[0].contains("YourLifeTotal")) {
            return doXMath(cc.getLife(), m, c);
        }
        if (sq[0].contains("OppGreatestLifeTotal")) {
            return doXMath(cc.getOpponentsGreatestLifeTotal(), m, c);
        }
        if (sq[0].contains("OppsAtLifeTotal")) {
                final int lifeTotal = xCount(c, sq[1]);
                int number = 0;
                for (final Player opp : cc.getOpponents()) {
                        if (opp.getLife() == lifeTotal) {
                                number++;
                        }
                }
                return doXMath(number, m, c);
        }

        //  Count$TargetedLifeTotal (targeted player's life total)
        if (sq[0].contains("TargetedLifeTotal")) {
            // This doesn't work in some circumstances, since the active SA isn't passed through
            for (final SpellAbility sa : c.getCurrentState().getNonManaAbilities()) {
                final SpellAbility saTargeting = sa.getSATargetingPlayer();
                if (saTargeting != null) {
                    for (final Player tgtP : saTargeting.getTargets().getTargetPlayers()) {
                        return doXMath(tgtP.getLife(), m, c);
                    }
                }
            }
        }

        if (sq[0].contains("LifeYouLostThisTurn")) {
            return doXMath(cc.getLifeLostThisTurn(), m, c);
        }
        if (sq[0].contains("LifeYouGainedThisTurn")) {
            return doXMath(cc.getLifeGainedThisTurn(), m, c);
        }
        if (sq[0].contains("LifeOppsLostThisTurn")) {
            int lost = 0;
            for (Player opp : cc.getOpponents()) {
                lost += opp.getLifeLostThisTurn();
            }
            return doXMath(lost, m, c);
        }

        if (sq[0].equals("TotalDamageDoneByThisTurn")) {
            return doXMath(c.getTotalDamageDoneBy(), m, c);
        }
        if (sq[0].equals("TotalDamageReceivedThisTurn")) {
            return doXMath(c.getTotalDamageRecievedThisTurn(), m, c);
        }

        if (sq[0].startsWith("YourCounters")) {
            // "YourCountersExperience" or "YourCountersPoison"
            String counterType = sq[0].substring(12);
            return doXMath(cc.getCounters(CounterType.getType(counterType)), m, c);
        }

        if (sq[0].contains("YourPoisonCounters")) {
            return doXMath(cc.getPoisonCounters(), m, c);
        }
        if (sq[0].contains("TotalOppPoisonCounters")) {
            return doXMath(cc.getOpponentsTotalPoisonCounters(), m, c);
        }

        if (sq[0].contains("YourDamageThisTurn")) {
            return doXMath(cc.getAssignedDamage(), m, c);
        }
        if (sq[0].contains("TotalOppDamageThisTurn")) {
            return doXMath(cc.getOpponentsAssignedDamage(), m, c);
        }
        if (sq[0].contains("MaxOppDamageThisTurn")) {
            return doXMath(cc.getMaxOpponentAssignedDamage(), m, c);
        }

        // Count$YourTypeDamageThisTurn Type
        if (sq[0].contains("YourTypeDamageThisTurn")) {
            return doXMath(cc.getAssignedDamage(sq[0].split(" ")[1]), m, c);
        }
        if (sq[0].contains("YourDamageSourcesThisTurn")) {
            Iterable<Card> allSrc = cc.getAssignedDamageSources();
            String restriction = sq[0].split(" ")[1];
            CardCollection filtered = CardLists.getValidCards(allSrc, restriction, cc, c);
            return doXMath(filtered.size(), m, c);
        }
        
        if (sq[0].contains("YourLandsPlayed")) {
            return doXMath(cc.getLandsPlayedThisTurn(), m, c);
        }

        // Count$TopOfLibraryCMC
        if (sq[0].contains("TopOfLibraryCMC")) {
            final Card topCard = cc.getCardsIn(ZoneType.Library).getFirst();
            return doXMath(topCard == null ? 0 : topCard.getCMC(), m, c);
        }
        
        // Count$EnchantedControllerCreatures
        if (sq[0].contains("EnchantedControllerCreatures")) {
            if (c.getEnchantingCard() != null) {
                return CardLists.count(c.getEnchantingCard().getController().getCardsIn(ZoneType.Battlefield), CardPredicates.Presets.CREATURES);
            }
            return 0;
        }

        // Count$MonstrosityMagnitude
        if (sq[0].contains("MonstrosityMagnitude")) {
            return doXMath(c.getMonstrosityNum(), m, c);
        }

        // Count$Chroma.<color name>
        // Count$Devotion.<color name>
        if (sq[0].contains("Chroma") || sq[0].equals("Devotion")) {
            ZoneType sourceZone = sq[0].contains("ChromaInGrave") ?  ZoneType.Graveyard : ZoneType.Battlefield;
            String colorName = sq[1];
            if (colorName.contains("Chosen")) {
                colorName = MagicColor.toShortString(c.getChosenColor());
            }
            final CardCollectionView cards;
            if (sq[0].contains("ChromaSource")) { // Runs Chroma for passed in Source card
                cards = new CardCollection(c);
            }
            else {
                cards = cc.getCardsIn(sourceZone);
            }

            int colorOcurrencices = 0;
            byte colorCode = ManaAtom.fromName(colorName);
            for (Card c0 : cards) {
                for (ManaCostShard sh : c0.getManaCost()){
                    if ((sh.getColorMask() & colorCode) != 0) 
                        colorOcurrencices++;
                }
            }
            return doXMath(colorOcurrencices, m, c);
        }
        // Count$DevotionDual.<color name>.<color name>
        if (sq[0].contains("DevotionDual")) {
            int colorOcurrencices = 0;
            byte color1 = ManaAtom.fromName(sq[1]);
            byte color2 = ManaAtom.fromName(sq[2]);
            for (Card c0 : cc.getCardsIn(ZoneType.Battlefield)) {
                for (ManaCostShard sh : c0.getManaCost()) {
                    if ((sh.getColorMask() & (color1 | color2)) != 0) {
                        colorOcurrencices++;
                    }
                }
            }
            return doXMath(colorOcurrencices, m, c);
        }

        if (sq[0].contains("ColorsCtrl")) {
            final String restriction = l[0].substring(11);
            final String[] rest = restriction.split(",");
            final CardCollection list = CardLists.getValidCards(cc.getGame().getCardsInGame(), rest, cc, c, null);
            int n = 0;
            for (final byte col : MagicColor.WUBRG) {
                if (!CardLists.getColor(list, col).isEmpty()) {
                    n++;
                }
            }
            return doXMath(n, m, c);
        }

        if (sq[0].contains("Hellbent")) {
            return doXMath(Integer.parseInt(sq[cc.hasHellbent() ? 1 : 2]), m, c);
        }
        if (sq[0].contains("Metalcraft")) {
            return doXMath(Integer.parseInt(sq[cc.hasMetalcraft() ? 1 : 2]), m, c);
        }
        if (sq[0].contains("Delirium")) {
            return doXMath(Integer.parseInt(sq[cc.hasDelirium() ? 1 : 2]), m, c);
        }
        if (sq[0].contains("FatefulHour")) {
            return doXMath(Integer.parseInt(sq[cc.getLife() <= 5 ? 1 : 2]), m, c);
        }
        if (sq[0].contains("Revolt")) {
            return doXMath(Integer.parseInt(sq[cc.hasRevolt() ? 1 : 2]), m, c);
        }
        if (sq[0].contains("Landfall")) {
            return doXMath(Integer.parseInt(sq[cc.hasLandfall() ? 1 : 2]), m, c);
        }
        if (sq[0].contains("Threshold")) {
            return doXMath(Integer.parseInt(sq[cc.hasThreshold() ? 1 : 2]), m, c);
        }
        if (sq[0].startsWith("Kicked")) {
            return doXMath(Integer.parseInt(sq[c.getKickerMagnitude() > 0 ? 1 : 2]), m, c);
        }
        if (sq[0].startsWith("AltCost")) {
            return doXMath(Integer.parseInt(sq[c.isOptionalCostPaid(OptionalCost.AltCost) ? 1 : 2]), m, c);
        }

        // Count$wasCastFrom<Zone>.<true>.<false>
        if (sq[0].startsWith("wasCastFrom")) {
            boolean zonesMatch = c.getCastFrom() == ZoneType.smartValueOf(sq[0].substring(11)); 
            return doXMath(Integer.parseInt(sq[zonesMatch ? 1 : 2]), m, c);
        }

        if (sq[0].startsWith("Devoured")) {
            final String validDevoured = l[0].split(" ")[1];
            CardCollection cl = CardLists.getValidCards(c.getDevoured(), validDevoured.split(","), cc, c, null);
            return doXMath(cl.size(), m, c);
        }

        if (sq[0].contains("CardPower")) {
            return doXMath(c.getNetPower(), m, c);
        }
        if (sq[0].contains("CardToughness")) {
            return doXMath(c.getNetToughness(), m, c);
        }
        if (sq[0].contains("CardSumPT")) {
            return doXMath((c.getNetPower() + c.getNetToughness()), m, c);
        }

        // Count$SumPower_valid
        if (sq[0].contains("SumPower")) {
            final String[] restrictions = l[0].split("_");
            final String[] rest = restrictions[1].split(",");
            CardCollection filteredCards = CardLists.getValidCards(cc.getGame().getCardsIn(ZoneType.Battlefield), rest, cc, c, null);
            return doXMath(Aggregates.sum(filteredCards, CardPredicates.Accessors.fnGetNetPower), m, c);
        }
        // Count$CardManaCost
        if (sq[0].contains("CardManaCost")) {
            Card ce;
            if (sq[0].contains("Equipped") && c.isEquipping()) {
                ce = c.getEquipping();
            }
            else if (sq[0].contains("Remembered")) {
                ce = (Card) c.getFirstRemembered();
            }
            else {
                ce = c;
            }

            return doXMath(ce == null ? 0 : ce.getCMC(), m, c);
        }
        // Count$SumCMC_valid
        if (sq[0].contains("SumCMC")) {
            final String[] restrictions = l[0].split("_");
            final String[] rest = restrictions[1].split(",");
            CardCollectionView cardsonbattlefield = game.getCardsIn(ZoneType.Battlefield);
            CardCollection filteredCards = CardLists.getValidCards(cardsonbattlefield, rest, cc, c, null);
            return Aggregates.sum(filteredCards, CardPredicates.Accessors.fnGetCmc);
        }

        if (sq[0].contains("CardNumColors")) {
            return doXMath(CardUtil.getColors(c).countColors(), m, c);
        }
        if (sq[0].contains("ChosenNumber")) {
            return doXMath(c.getChosenNumber(), m, c);
        }
        if (sq[0].contains("CardCounters")) {
            // CardCounters.ALL to be used for Kinsbaile Borderguard and anything that cares about all counters
            int count = 0;
            if (sq[1].equals("ALL")) {
                for (Integer i : c.getCounters().values()) {
                    if (i != null && i > 0) {
                        count += i;
                    }
                }
            }
            else {
                count = c.getCounters(CounterType.getType(sq[1]));
            }
            return doXMath(count, m, c);
        }

        // Count$TotalCounters.<counterType>_<valid>
        if (sq[0].contains("TotalCounters")) {
            final String[] restrictions = l[0].split("_");
            final CounterType cType = CounterType.getType(restrictions[1]);
            final String[] validFilter = restrictions[2].split(",");
            CardCollectionView validCards = game.getCardsIn(ZoneType.Battlefield);
            validCards = CardLists.getValidCards(validCards, validFilter, cc, c, null);
            int cCount = 0;
            for (final Card card : validCards) {
                cCount += card.getCounters(cType);
            }
            return doXMath(cCount, m, c);
        }

        if (sq[0].contains("CardControllerTypes")) {
            return doXMath(getCardTypesFromList(cc.getCardsIn(ZoneType.smartValueOf(sq[1]))), m, c);
        }

        if (sq[0].contains("CardTypes")) {
            return doXMath(getCardTypesFromList(game.getCardsIn(ZoneType.smartValueOf(sq[1]))), m, c);
        }

        if (sq[0].contains("BushidoPoint")) {
            return doXMath(c.getKeywordMagnitude("Bushido"), m, c);
        }
        if (sq[0].contains("TimesKicked")) {
            return doXMath(c.getKickerMagnitude(), m, c);
        }
        if (sq[0].contains("TimesPseudokicked")) {
            return doXMath(c.getPseudoKickerMagnitude(), m, c);
        }

        // Count$IfMainPhase.<numMain>.<numNotMain> // 7/10
        if (sq[0].contains("IfMainPhase")) {
            final PhaseHandler cPhase = cc.getGame().getPhaseHandler();
            final boolean isMyMain = cPhase.getPhase().isMain() && cPhase.getPlayerTurn().equals(cc);
            return doXMath(Integer.parseInt(sq[isMyMain ? 1 : 2]), m, c);
        }

        // Count$ThisTurnEntered <ZoneDestination> [from <ZoneOrigin>] <Valid>
        if (sq[0].contains("ThisTurnEntered")) {
            final String[] workingCopy = l[0].split("_");
            
            ZoneType destination = ZoneType.smartValueOf(workingCopy[1]);
            final boolean hasFrom = workingCopy[2].equals("from");
            ZoneType origin = hasFrom ? ZoneType.smartValueOf(workingCopy[3]) : null;
            String validFilter = workingCopy[hasFrom ? 4 : 2] ;

            final CardCollection res = CardUtil.getThisTurnEntered(destination, origin, validFilter, c);
            if (origin == null) { // Remove cards on the battlefield that changed controller
                CardCollectionView sameDest = CardUtil.getThisTurnEntered(destination, destination, validFilter, c);
                res.removeAll(sameDest);
            }
            return doXMath(res.size(), m, c);
        }

        // Count$LastTurnEntered <ZoneDestination> [from <ZoneOrigin>] <Valid>
        if (sq[0].contains("LastTurnEntered")) {
            final String[] workingCopy = l[0].split("_");
            
            ZoneType destination = ZoneType.smartValueOf(workingCopy[1]);
            final boolean hasFrom = workingCopy[2].equals("from");
            ZoneType origin = hasFrom ? ZoneType.smartValueOf(workingCopy[3]) : null;
            String validFilter = workingCopy[hasFrom ? 4 : 2] ;

            final CardCollection res = CardUtil.getLastTurnEntered(destination, origin, validFilter, c);
            if (origin == null) { // Remove cards on the battlefield that changed controller
                CardCollectionView sameDest = CardUtil.getLastTurnEntered(destination, destination, validFilter, c);
                res.removeAll(sameDest);
            }
            return doXMath(res.size(), m, c);
        }

        // Count$AttackersDeclared
        if (sq[0].contains("AttackersDeclared")) {
            return doXMath(cc.getAttackersDeclaredThisTurn(), m, c);
        }

        // Count$ThisTurnCast <Valid>
        // Count$LastTurnCast <Valid>
        if (sq[0].contains("ThisTurnCast") || sq[0].contains("LastTurnCast")) {

            final String[] workingCopy = l[0].split("_");
            final String validFilter = workingCopy[1];

            List<Card> res = Lists.newArrayList();
            if (workingCopy[0].contains("This")) {
                res = CardUtil.getThisTurnCast(validFilter, c);
            }
            else {
                res = CardUtil.getLastTurnCast(validFilter, c);
            }

            final int ret = doXMath(res.size(), m, c);
            return ret;
        }

        // Count$Morbid.<True>.<False>
        if (sq[0].startsWith("Morbid")) {
            final CardCollection res = CardUtil.getThisTurnEntered(ZoneType.Graveyard, ZoneType.Battlefield, "Creature", c, true);
            if (res.size() > 0) {
                return doXMath(Integer.parseInt(sq[1]), m, c);
            }
            return doXMath(Integer.parseInt(sq[2]), m, c);
        }

        // Count$Madness.<True>.<False>
        if (sq[0].startsWith("Madness")) {
            if (c.isMadness()) {
                return doXMath(StringUtils.isNumeric(sq[1]) ? Integer.parseInt(sq[1]) : xCount(c, c.getSVar(sq[1])), m, c);
            }
            return doXMath(StringUtils.isNumeric(sq[2]) ? Integer.parseInt(sq[2]) : xCount(c, c.getSVar(sq[2])), m, c);
        }

        // Count$Presence_<Type>.<True>.<False>
        if (sq[0].startsWith("Presence")) {
            final String type = sq[0].split("_")[1];

            if (c.getCastFrom() != null && c.getCastSA() != null) {
                int revealed = AbilityUtils.calculateAmount(c, "Revealed$Valid " + type, c.getCastSA());
                int ctrl = AbilityUtils.calculateAmount(c, "Count$Valid " + type + ".inZoneBattlefield+YouCtrl", c.getCastSA());
                if (revealed + ctrl >= 1) {
                    return doXMath(StringUtils.isNumeric(sq[1]) ? Integer.parseInt(sq[1]) : xCount(c, c.getSVar(sq[1])), m, c);
                }
            }
            return doXMath(StringUtils.isNumeric(sq[2]) ? Integer.parseInt(sq[2]) : xCount(c, c.getSVar(sq[2])), m, c);
        }

        if (sq[0].startsWith("LastStateBattlefield")) {
            final String[] k = l[0].split(" ");
            CardCollection list = new CardCollection(game.getLastStateBattlefield());
            list = CardLists.getValidCards(list, k[1].split(","), cc, c, null);
            return CardFactoryUtil.doXMath(list.size(), m, c);
        }

        if (sq[0].startsWith("LastStateGraveyard")) {
            final String[] k = l[0].split(" ");
            CardCollection list = new CardCollection(game.getLastStateGraveyard());
            list = CardLists.getValidCards(list, k[1].split(","), cc, c, null);
            return CardFactoryUtil.doXMath(list.size(), m, c);
        }

        if (sq[0].equals("YourTurns")) {
            return doXMath(cc.getTurn(), m, c);
        }

        if (sq[0].equals("TotalTurns")) {
            // Sorry for the Singleton use, replace this once this function has game passed into it
            return doXMath(game.getPhaseHandler().getTurn(), m, c);
        }
        
        //Count$Random.<Min>.<Max>
        if (sq[0].equals("Random")) {
            int min = StringUtils.isNumeric(sq[1]) ? Integer.parseInt(sq[1]) : xCount(c, c.getSVar(sq[1]));
            int max = StringUtils.isNumeric(sq[2]) ? Integer.parseInt(sq[2]) : xCount(c, c.getSVar(sq[2]));

            return forge.util.MyRandom.getRandom().nextInt(1+max-min) + min;
        }


        // Count$Domain
        if (sq[0].startsWith("Domain")) {
            int n = 0;
            Player neededPlayer = sq[0].equals("DomainActivePlayer") ? activePlayer : cc;
            CardCollection someCards = CardLists.filter(neededPlayer.getCardsIn(ZoneType.Battlefield), Presets.LANDS);
            for (String basic : MagicColor.Constant.BASIC_LANDS) {
                if (!CardLists.getType(someCards, basic).isEmpty()) {
                    n++;
                }
            }
            return doXMath(n, m, c);
        }
        // Count$Converge
        if (sq[0].contains("Converge")) {
            return doXMath(c.getSunburstValue(), m, c);
        }
        // Count$ColoredCreatures *a DOMAIN for creatures*
        if (sq[0].contains("ColoredCreatures")) {
            int mask = 0;
            CardCollection someCards = CardLists.filter(cc.getCardsIn(ZoneType.Battlefield), Presets.CREATURES);
            for (final Card crd : someCards) {
                mask |= CardUtil.getColors(crd).getColor();
            }
            return doXMath(ColorSet.fromMask(mask).countColors(), m, c);
        }
        
        // Count$CardMulticolor.<numMC>.<numNotMC>
        if (sq[0].contains("CardMulticolor")) {
            final boolean isMulti = CardUtil.getColors(c).isMulticolor(); 
            return doXMath(Integer.parseInt(sq[isMulti ? 1 : 2]), m, c);
        }
        
        // Complex counting methods
        CardCollectionView someCards = getCardListForXCount(c, cc, sq);
        
        // 1/10 - Count$MaxCMCYouCtrl
        if (sq[0].contains("MaxCMC")) {
            int mmc = Aggregates.max(someCards, CardPredicates.Accessors.fnGetCmc);
            return doXMath(mmc, m, c);
        }

        return doXMath(someCards.size(), m, c);
    }

    private static CardCollectionView getCardListForXCount(final Card c, final Player cc, final String[] sq) {
        final List<Player> opps = cc.getOpponents();
        CardCollection someCards = new CardCollection();
        final Game game = c.getGame();
        
        // Generic Zone-based counting
        // Count$QualityAndZones.Subquality

        // build a list of cards in each possible specified zone

        if (sq[0].contains("YouCtrl")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Battlefield));
        }

        if (sq[0].contains("InYourYard")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Graveyard));
        }

        if (sq[0].contains("InYourLibrary")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Library));
        }

        if (sq[0].contains("InYourHand")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Hand));
        }

        if (sq[0].contains("InYourSideboard")) {
            someCards.addAll(cc.getCardsIn(ZoneType.Sideboard));
        }

        if (sq[0].contains("OppCtrl")) {
                for (final Player p : opps) {
                        someCards.addAll(p.getZone(ZoneType.Battlefield).getCards());
                }
        }

        if (sq[0].contains("InOppYard")) {
                for (final Player p : opps) {
                        someCards.addAll(p.getCardsIn(ZoneType.Graveyard));
                }
        }

        if (sq[0].contains("InOppHand")) {
                for (final Player p : opps) {
                        someCards.addAll(p.getCardsIn(ZoneType.Hand));
                }
        }

        if (sq[0].contains("InChosenHand")) {
                if (c.getChosenPlayer() != null) {
                        someCards.addAll(c.getChosenPlayer().getCardsIn(ZoneType.Hand));
                }
        }

        if (sq[0].contains("InChosenYard")) {
                if (c.getChosenPlayer() != null) {
                        someCards.addAll(c.getChosenPlayer().getCardsIn(ZoneType.Graveyard));
                }
        }

        if (sq[0].contains("OnBattlefield")) {
                someCards.addAll(game.getCardsIn(ZoneType.Battlefield));
        }

        if (sq[0].contains("InAllYards")) {
                someCards.addAll(game.getCardsIn(ZoneType.Graveyard));
        }

        if (sq[0].contains("SpellsOnStack")) {
            someCards.addAll(game.getCardsIn(ZoneType.Stack));
        }

        if (sq[0].contains("InAllHands")) {
                someCards.addAll(game.getCardsIn(ZoneType.Hand));
        }

        //  Count$InTargetedHand (targeted player's cards in hand)
        if (sq[0].contains("InTargetedHand")) {
            for (final SpellAbility sa : c.getCurrentState().getNonManaAbilities()) {
                final SpellAbility saTargeting = sa.getSATargetingPlayer();
                if (saTargeting != null) {
                    for (final Player tgtP : saTargeting.getTargets().getTargetPlayers()) {
                        someCards.addAll(tgtP.getCardsIn(ZoneType.Hand));
                    }
                }
            }
        }

        //  Count$InTargetedHand (targeted player's cards in hand)
        if (sq[0].contains("InEnchantedHand")) {
            GameEntity o = c.getEnchanting();
            Player controller = null;
            if (o instanceof Card) {
                controller = ((Card) o).getController();
            }
            else {
                controller = (Player) o;
            }
            if (controller != null) {
                someCards.addAll(controller.getCardsIn(ZoneType.Hand));
            }
        }
        if (sq[0].contains("InEnchantedYard")) {
            GameEntity o = c.getEnchanting();
            Player controller = null;
            if (o instanceof Card) {
                controller = ((Card) o).getController();
            }
            else {
                controller = (Player) o;
            }
            if (controller != null) {
                someCards.addAll(controller.getCardsIn(ZoneType.Graveyard));
            }
        }
        
        // filter lists based on the specified quality

        // "Clerics you control" - Count$TypeYouCtrl.Cleric
        if (sq[0].contains("Type")) {
            someCards = CardLists.filter(someCards, CardPredicates.isType(sq[1]));
        }

        // "Named <CARDNAME> in all graveyards" - Count$NamedAllYards.<CARDNAME>

        if (sq[0].contains("Named")) {
            if (sq[1].equals("CARDNAME")) {
                sq[1] = c.getName();
            }
            someCards = CardLists.filter(someCards, CardPredicates.nameEquals(sq[1]));
        }

        // Refined qualities

        // "Untapped Lands" - Count$UntappedTypeYouCtrl.Land
        // if (sq[0].contains("Untapped")) { someCards = CardLists.filter(someCards, Presets.UNTAPPED); }

        // if (sq[0].contains("Tapped")) { someCards = CardLists.filter(someCards, Presets.TAPPED); }

//        String sq0 = sq[0].toLowerCase();
//        for (String color : MagicColor.Constant.ONLY_COLORS) {
//            if (sq0.contains(color))
//                someCards = someCards.filter(CardListFilter.WHITE);
//        }
        // "White Creatures" - Count$WhiteTypeYouCtrl.Creature
        // if (sq[0].contains("White")) someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.WHITE));
        // if (sq[0].contains("Blue"))  someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.BLUE));
        // if (sq[0].contains("Black")) someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.BLACK));
        // if (sq[0].contains("Red"))   someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.RED));
        // if (sq[0].contains("Green")) someCards = CardLists.filter(someCards, CardPredicates.isColor(MagicColor.GREEN));

        if (sq[0].contains("Multicolor")) {
            someCards = CardLists.filter(someCards, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    return CardUtil.getColors(c).isMulticolor();
                }
            });
        }

        if (sq[0].contains("Monocolor")) {
            someCards = CardLists.filter(someCards, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    return CardUtil.getColors(c).isMonoColor();
                }
            });
        }
        return someCards;
    }

    public static int doXMath(final int num, final String operators, final Card c) {
        if (operators == null || operators.equals("none")) {
            return num;
        }

        final String[] s = operators.split("\\.");
        int secondaryNum = 0;

        try {
            if (s.length == 2) {
                secondaryNum = Integer.parseInt(s[1]);
            }
        } catch (final Exception e) {
            secondaryNum = xCount(c, c.getSVar(s[1]));
        }

        if (s[0].contains("Plus")) {
            return num + secondaryNum;
        } else if (s[0].contains("NMinus")) {
            return secondaryNum - num;
        } else if (s[0].contains("Minus")) {
            return num - secondaryNum;
        } else if (s[0].contains("Twice")) {
            return num * 2;
        } else if (s[0].contains("Thrice")) {
            return num * 3;
        } else if (s[0].contains("HalfUp")) {
            return (int) (Math.ceil(num / 2.0));
        } else if (s[0].contains("HalfDown")) {
            return (int) (Math.floor(num / 2.0));
        } else if (s[0].contains("ThirdUp")) {
            return (int) (Math.ceil(num / 3.0));
        } else if (s[0].contains("ThirdDown")) {
            return (int) (Math.floor(num / 3.0));
        } else if (s[0].contains("Negative")) {
            return num * -1;
        } else if (s[0].contains("Times")) {
            return num * secondaryNum;
        } else if (s[0].contains("DivideEvenlyDown")) {
            if (secondaryNum == 0) {
                return 0;
            } else {
                return num / secondaryNum;
            }
        } else if (s[0].contains("Mod")) {
            return num % secondaryNum;
        } else if (s[0].contains("Abs")) {
            return Math.abs(num);
        } else if (s[0].contains("LimitMax")) {
            if (num < secondaryNum) {
                return num;
            } else {
                return secondaryNum;
            }
        } else if (s[0].contains("LimitMin")) {
            if (num > secondaryNum) {
                return num;
            } else {
                return secondaryNum;
            }

        } else {
            return num;
        }
    }

    /**
     * <p>
     * handlePaid.
     * </p>
     * 
     * @param paidList
     *            a {@link forge.game.card.CardCollectionView} object.
     * @param string
     *            a {@link java.lang.String} object.
     * @param source
     *            a {@link forge.game.card.Card} object.
     * @return a int.
     */
    public static int handlePaid(final CardCollectionView paidList, final String string, final Card source) {
        if (paidList == null) {
            if (string.contains(".")) {
                final String[] splitString = string.split("\\.", 2);
                return doXMath(0, splitString[1], source);
            } else {
                return 0;
            }
        }
        if (string.startsWith("Amount")) {
            if (string.contains(".")) {
                final String[] splitString = string.split("\\.", 2);
                return doXMath(paidList.size(), splitString[1], source);
            } else {
                return paidList.size();
            }

        }

        if (string.startsWith("SumCMC")) {
            int sumCMC = 0;
            for(Card c : paidList) {
                sumCMC += c.getCMC();
            }
            return sumCMC;
        }

        if (string.startsWith("Valid")) {
            
            final String[] splitString = string.split("/", 2);
            String valid = splitString[0].substring(6);
            final CardCollection list = CardLists.getValidCards(paidList, valid, source.getController(), source);
            return doXMath(list.size(), splitString.length > 1 ? splitString[1] : null, source);
        }

        String filteredString = string;
        CardCollection filteredList = new CardCollection(paidList);
        final String[] filter = filteredString.split("_");

        if (string.startsWith("FilterControlledBy")) {
            final String pString = filter[0].substring(18);
            FCollectionView<Player> controllers = AbilityUtils.getDefinedPlayers(source, pString, null);
            filteredList = CardLists.filterControlledBy(filteredList, controllers);
            filteredString = filteredString.replace(pString, "");
            filteredString = filteredString.replace("FilterControlledBy_", "");
        }

        int tot = 0;
        for (final Card c : filteredList) {
            tot += xCount(c, filteredString);
        }

        return tot;
    }

    /**
     * <p>
     * isMostProminentColor.
     * </p>
     * 
     * @param list
     *            a {@link Iterable<Card>} object.
     * @return a boolean.
     */
    public static byte getMostProminentColors(final Iterable<Card> list) {
        int cntColors = MagicColor.WUBRG.length;
        final Integer[] map = new Integer[cntColors];
        for (int i = 0; i < cntColors; i++) {
            map[i] = 0;
        }

        for (final Card crd : list) {
            ColorSet color = CardUtil.getColors(crd);
            for (int i = 0; i < cntColors; i++) {
                if (color.hasAnyColor(MagicColor.WUBRG[i]))
                    map[i]++;
            }
        } // for

        byte mask = 0;
        int nMax = -1;
        for (int i = 0; i < cntColors; i++) { 
            if (map[i] > nMax)
                mask = MagicColor.WUBRG[i];
            else if (map[i] == nMax)
                mask |= MagicColor.WUBRG[i];
            else 
                continue;
            nMax = map[i];
        }
        return mask;
    }

    /**
     * <p>
     * SortColorsFromList.
     * </p>
     * 
     * @param list
     *            a {@link forge.game.card.CardCollection} object.
     * @return a List.
     */
    public static int[] SortColorsFromList(final CardCollection list) {
        int cntColors = MagicColor.WUBRG.length;
        final int[] map = new int[cntColors];
        for (int i = 0; i < cntColors; i++) {
            map[i] = 0;
        }

        for (final Card crd : list) {
            ColorSet color = CardUtil.getColors(crd);
            for (int i = 0; i < cntColors; i++) {
                if (color.hasAnyColor(MagicColor.WUBRG[i]))
                    map[i]++;
            }
        } // for
        Arrays.sort(map);
        return map;
    }

    /**
     * <p>
     * getMostProminentColorsFromList.
     * </p>
     * 
     * @param list
     *            a {@link forge.game.card.CardCollectionView} object.
     * @return a boolean.
     */
    public static byte getMostProminentColorsFromList(final CardCollectionView list, final List<String> restrictedToColors) {
        List<Byte> colorRestrictions = Lists.newArrayList();
        for (final String col : restrictedToColors) {
            colorRestrictions.add(MagicColor.fromName(col));
        }
        int cntColors = colorRestrictions.size();
        final Integer[] map = new Integer[cntColors];
        for (int i = 0; i < cntColors; i++) {
            map[i] = 0;
        }

        for (final Card crd : list) {
            ColorSet color = CardUtil.getColors(crd);
            for (int i = 0; i < cntColors; i++) {
                if (color.hasAnyColor(colorRestrictions.get(i))) {
                    map[i]++;
                }
            }
        }

        byte mask = 0;
        int nMax = -1;
        for (int i = 0; i < cntColors; i++) { 
            if (map[i] > nMax)
                mask = colorRestrictions.get(i);
            else if (map[i] == nMax)
                mask |= colorRestrictions.get(i);
            else 
                continue;
            nMax = map[i];
        }
        return mask;
    }

    /**
     * <p>
     * getMostProminentCreatureType.
     * </p>
     * 
     * @param list
     *            a {@link forge.game.card.CardCollection} object.
     * @return an int.
     */
    public static int getMostProminentCreatureTypeSize(final CardCollection list) {
        if (list.isEmpty()) {
            return 0;
        }
        int allCreatureType = 0;

        final Map<String, Integer> map = Maps.newHashMap();
        for (final Card c : list) {
            // Remove Duplicated types
            final Set<String> creatureTypes = c.getType().getCreatureTypes();
            for (String creatureType : creatureTypes) {
                if (creatureType.equals("AllCreatureTypes")) {
                    allCreatureType++;
                }
                else {
                    Integer count = map.get(creatureType);
                    map.put(creatureType, count == null ? 1 : count + 1);
                }
            }
        }

        int max = 0;
        for (final Entry<String, Integer> entry : map.entrySet()) {
            if (max < entry.getValue()) {
                max = entry.getValue();
            }
        }
        return max + allCreatureType;
    }

    /**
     * <p>
     * sharedKeywords.
     * </p>
     * 
     * @param kw
     *            a  String arry.
     * @return a List<String>.
     */
    public static List<String> sharedKeywords(final Iterable<String> kw, final String[] restrictions,
            final Iterable<ZoneType> zones, final Card host) {
        final List<String> filteredkw = Lists.newArrayList();
        final Player p = host.getController();
        CardCollectionView cardlist = p.getGame().getCardsIn(zones);
        final List<String> landkw = Lists.newArrayList();
        final List<String> protectionkw = Lists.newArrayList();
        final List<String> allkw = Lists.newArrayList();

        for (Card c : CardLists.getValidCards(cardlist, restrictions, p, host, null)) {
            for (String k : c.getKeywords()) {
                if (k.endsWith("walk")) {
                    if (!landkw.contains(k)) {
                        landkw.add(k);
                    }
                } else if (k.startsWith("Protection")) {
                    if (!protectionkw.contains(k)) {
                        protectionkw.add(k);
                    }
                }
                if (!allkw.contains(k)) {
                    allkw.add(k);
                }
            }
        }
        for (String keyword : kw) {
            if (keyword.equals("Protection")) {
                filteredkw.addAll(protectionkw);
            } else if (keyword.equals("Landwalk")) {
                filteredkw.addAll(landkw);
            } else if (allkw.contains(keyword)) {
                filteredkw.add(keyword);
            }
        }
        return filteredkw;
    }

    public static int getCardTypesFromList(final CardCollectionView list) {
        EnumSet<CardType.CoreType> types = EnumSet.noneOf(CardType.CoreType.class);
        for (Card c1 : list) {
            Iterables.addAll(types, c1.getType().getCoreTypes());
        }
        return types.size();
    }

    /**
     * <p>
     * getNeededXDamage.
     * </p>
     * 
     * @param ability
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a int.
     */
    public static int getNeededXDamage(final SpellAbility ability) {
        // when targeting a creature, make sure the AI won't overkill on X
        // damage
        final Card target = ability.getTargetCard();
        int neededDamage = -1;

        if ((target != null)) {
            neededDamage = target.getNetToughness() - target.getDamage();
        }

        return neededDamage;
    }

    /**
     * Adds the ability factory abilities.
     * 
     * @param card
     *            the card
     */
    public static final void addAbilityFactoryAbilities(final Card card, final Iterable<String> abilities) {
        // **************************************************
        // AbilityFactory cards
        for (String rawAbility : abilities) {
            final SpellAbility intrinsicAbility = AbilityFactory.getAbility(rawAbility, card);
            card.addSpellAbility(intrinsicAbility);
            intrinsicAbility.setIntrinsic(true);
        }
    }

    /**
     * <p>
     * postFactoryKeywords.
     * </p>
     * 
     * @param card
     *            a {@link forge.game.card.Card} object.
     */
    public static void setupKeywordedAbilities(final Card card) {
        // this function should handle any keywords that need to be added after
        // a spell goes through the factory
        // Cards with Cycling abilities
        // -1 means keyword "Cycling" not found

        for (String keyword : card.getKeywords()) {
            if (keyword.startsWith("Absorb")) {
                addStaticAbility(keyword, card, null);
            } else if (keyword.equals("Cipher")) {
                addStaticAbility(keyword, card, null);
            } else if (keyword.startsWith("Multikicker")) {
                final String[] n = keyword.split(":");
                final SpellAbility sa = card.getFirstSpellAbility();
                sa.setMultiKickerManaCost(new ManaCost(new ManaCostParser(n[1])));
                if (keyword.endsWith("Generic")) {
                    sa.addAnnounceVar("Pseudo-multikicker");
                } else {
                    sa.addAnnounceVar("Multikicker");
                }
            }
            else if (keyword.startsWith("Replicate")) {
                card.getFirstSpellAbility().addAnnounceVar("Replicate");
            }
            else if (keyword.startsWith("Fuse")) {
                card.getState(CardStateName.Original).addNonManaAbility(AbilityFactory.buildFusedAbility(card));
            }
            else if (keyword.startsWith("Evoke")) {
                addSpellAbility(keyword, card, null);
                addTriggerAbility(keyword, card, null);                
            }
            else if (keyword.startsWith("Dash")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Awaken")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Surge")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Graft")) {
                addReplacementEffect(keyword, card, null);
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Melee")) {
                addTriggerAbility(keyword, card, null);                
            }
            else if (keyword.startsWith("Modular")) {
                addReplacementEffect(keyword, card, null);
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Monstrosity")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Ninjutsu")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Scavenge")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Unearth")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Level up")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Cycling")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("TypeCycling")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Transmute")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Soulshift")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Champion")) {
                card.removeIntrinsicKeyword(keyword);

                final String[] k = keyword.split(":");
                final String[] valid = k[1].split(",");
                String desc = k.length > 2 ? k[2] : k[1];
                String article = Lang.startsWithVowel(desc) ? "an" : "a";
                if (desc.equals("Creature")) {
                    desc = "creature"; //use lowercase for "Champion a creature"
                }

                StringBuilder changeType = new StringBuilder();
                for (String v : valid) {
                    if (changeType.length() != 0) {
                        changeType.append(",");
                    }
                    changeType.append(v).append(".YouCtrl+Other");
                }

                StringBuilder trig = new StringBuilder();
                trig.append("Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | ValidCard$ Card.Self | ");
                trig.append("Execute$ ChampionAbility | TriggerDescription$ Champion ").append(article + " ");
                trig.append(desc).append(" (When this enters the battlefield, sacrifice it unless you exile another ");
                trig.append(desc).append(" you control. When this leaves the battlefield, that card returns to the battlefield.)");

                StringBuilder trigReturn = new StringBuilder();
                trigReturn.append("Mode$ ChangesZone | Origin$ Battlefield | Destination$ Any | ValidCard$ Card.Self | ");
                trigReturn.append("Execute$ ChampionReturn | Secondary$ True | TriggerDescription$ When this leaves the battlefield, that card returns to the battlefield.");

                StringBuilder ab = new StringBuilder();
                ab.append("DB$ ChangeZone | Origin$ Battlefield | Destination$ Exile | RememberChanged$ True | Champion$ True | ");
                ab.append("Hidden$ True | Optional$ True | SubAbility$ ChampionSacrifice | ChangeType$ ").append(changeType);

                StringBuilder subAb = new StringBuilder();
                subAb.append("DB$ Sacrifice | Defined$ Card.Self | ConditionDefined$ Remembered | ConditionPresent$ Card | ConditionCompare$ EQ0");

                String returnChampion = "DB$ ChangeZone | Defined$ Remembered | Origin$ Exile | Destination$ Battlefield | SubAbility$ ChampionCleanup";
                final Trigger parsedTrigger = TriggerHandler.parseTrigger(trig.toString(), card, true);
                final Trigger parsedTrigReturn = TriggerHandler.parseTrigger(trigReturn.toString(), card, true);
                card.addTrigger(parsedTrigger);
                card.addTrigger(parsedTrigReturn);
                card.setSVar("ChampionAbility", ab.toString());
                card.setSVar("ChampionReturn", returnChampion);
                card.setSVar("ChampionCleanup", "DB$ Cleanup | ClearRemembered$ True");
                card.setSVar("ChampionSacrifice", subAb.toString());
            }
            else if (keyword.startsWith("If CARDNAME would be put into a graveyard "
                    + "from anywhere, reveal CARDNAME and shuffle it into its owner's library instead.")) {
                String replacement = "Event$ Moved | Destination$ Graveyard | ValidCard$ Card.Self | ReplaceWith$ GraveyardToLibrary";
                String ab =  "DB$ ChangeZone | Hidden$ True | Origin$ All | Destination$ Library | Defined$ ReplacedCard | Reveal$ True | Shuffle$ True";

                card.addReplacementEffect(ReplacementHandler.parseReplacement(replacement, card, true));
                card.setSVar("GraveyardToLibrary", ab);
            }
            else if (keyword.startsWith("Echo")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Cumulative upkeep")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Suspend")) {
                card.setSuspend(true);
                final String[] k = keyword.split(":");

                final String timeCounters = k[1];
                final String cost = k[2];
                card.addSpellAbility(abilitySuspendStatic(card, cost, timeCounters));

                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Fading")) {
                addTriggerAbility(keyword, card, null);
                addReplacementEffect(keyword, card, null);
            }
            else if (keyword.startsWith("Renown")) {
                addTriggerAbility(keyword, card, null); 
            }
            else if (keyword.startsWith("Vanishing")) {
                addTriggerAbility(keyword, card, null);
                addReplacementEffect(keyword, card, null);
            }
            else if (keyword.equals("Delve")) {
                card.getSpellAbilities().getFirst().setDelve(true);
            }
            else if (keyword.startsWith("Haunt")) {
                addSpellAbility(keyword, card, null);
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Annihilator")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Provoke")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Myriad")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Living Weapon")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Aftermath")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.equals("Epic")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.equals("Soulbond")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Conspire")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Exalted")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Exploit")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Extort")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Flanking")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Ingest")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Miracle")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Persist")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Undying")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Unleash")) {
                addReplacementEffect(keyword, card, null);
                addStaticAbility(keyword, card, null);
            }
            else if (keyword.equals("Changeling")) {
                addStaticAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Emerge")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Embalm")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Escalate")) {
                addStaticAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Eternalize")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Fabricate")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Rampage")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Afflict")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Strive")) {
                addStaticAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Bushido")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.endsWith(" offering")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Presence")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Crew")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Retrace")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Undaunted")) {
                addStaticAbility(keyword, card, null);
            }
            else if (keyword.equals("Prowess")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.equals("Evolve")) {
                addTriggerAbility(keyword, card, null);
            } else if (keyword.startsWith("UpkeepCost")) {
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Dredge")) {
                final int dredgeAmount = card.getKeywordMagnitude("Dredge");

                final String actualRep = "Event$ Draw | ActiveZones$ Graveyard | ValidPlayer$ You | "
                        + "ReplaceWith$ DredgeCards | Secondary$ True | Optional$ True | CheckSVar$ "
                        + "DredgeCheckLib | SVarCompare$ GE" + dredgeAmount + " | References$ "
                        + "DredgeCheckLib | AICheckDredge$ True | Description$ " + card.getName()
                        +  " - Dredge " + dredgeAmount;
                final String abString = "DB$ Mill | Defined$ You | NumCards$ " + dredgeAmount + " | "
                        + "SubAbility$ DredgeMoveToPlay";
                final String moveToPlay = "DB$ ChangeZone | Origin$ Graveyard | Destination$ Hand | "
                        + "Defined$ Self";
                final String checkSVar = "Count$ValidLibrary Card.YouOwn";
                card.setSVar("DredgeCards", abString);
                card.setSVar("DredgeMoveToPlay", moveToPlay);
                card.setSVar("DredgeCheckLib", checkSVar);
                card.addReplacementEffect(ReplacementHandler.parseReplacement(actualRep, card, true));
            } else if (keyword.startsWith("Prevent all") || keyword.startsWith("PreventAllDamageBy") ) {
                addReplacementEffect(keyword, card, null);
            }else if (keyword.startsWith("Tribute")) {
                addReplacementEffect(keyword, card, null);
                addTriggerAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Amplify")) {
                // find position of Amplify keyword
                final int ampPos = card.getKeywordPosition("Amplify");
                final String[] ampString = card.getKeywords().get(ampPos).split(":");
                final String amplifyMagnitude = ampString[1];
                final String suffix = !amplifyMagnitude.equals("1") ? "s" : "";
                final String ampTypes = ampString[2];
                String[] refinedTypes = ampTypes.split(",");
                final StringBuilder types = new StringBuilder();
                for (int i = 0; i < refinedTypes.length; i++) {
                    types.append("Card.").append(refinedTypes[i]).append("+YouCtrl");
                    if (i + 1 != refinedTypes.length) {
                        types.append(",");
                    }
                }
                // Setup ETB replacement effects
                final String actualRep = "Event$ Moved | Destination$ Battlefield | ValidCard$ Card.Self |"
                        + " ReplaceWith$ AmplifyReveal | Secondary$ True | Description$ As this creature "
                        + "enters the battlefield, put " + amplifyMagnitude + " +1/+1 counter" + suffix 
                        + " on it for each " + ampTypes.replace(",", " and/or ") 
                        + " card you reveal in your hand.)";
                final String abString = "DB$ Reveal | AnyNumber$ True | RevealValid$ "
                        + types.toString() + " | RememberRevealed$ True | SubAbility$ Amplify";
                final String dbString = "DB$ PutCounter | Defined$ ReplacedCard | CounterType$ P1P1 | "
                        + "CounterNum$ AmpMagnitude | References$ Revealed,AmpMagnitude | SubAbility$"
                        + " AmplifyMoveToPlay | ETB$ True";
                final String moveToPlay = "DB$ ChangeZone | Origin$ All | Destination$ Battlefield | "
                        + "Defined$ ReplacedCard | Hidden$ True | SubAbility$ DBCleanup";
                card.addReplacementEffect(ReplacementHandler.parseReplacement(actualRep, card, true));
                card.setSVar("AmplifyReveal", abString);
                card.setSVar("AmplifyMoveToPlay", moveToPlay);
                card.setSVar("Amplify", dbString);
                card.setSVar("DBCleanup", "DB$ Cleanup | ClearRemembered$ True");
                card.setSVar("AmpMagnitude", "SVar$Revealed/Times." + amplifyMagnitude);
                card.setSVar("Revealed", "Remembered$Amount");
            }
            else if (keyword.startsWith("Equip")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Outlast")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.startsWith("Fortify")) {
                final String equipString = keyword.substring(7);
                final String[] equipExtras = equipString.contains("|") ? equipString.split("\\|", 2) : null;
                // Get cost string
                String equipCost = "";
                if (equipExtras != null) {
                    equipCost = equipExtras[0].trim();
                } else {
                    equipCost = equipString.trim();
                }
                // Create attach ability string
                final StringBuilder abilityStr = new StringBuilder();
                abilityStr.append("AB$ Attach | Cost$ ");
                abilityStr.append(equipCost);
                abilityStr.append(" | ValidTgts$ Land.YouCtrl | TgtPrompt$ Select target land you control ");
                abilityStr.append("| SorcerySpeed$ True | AILogic$ Pump | IsPresent$ Card.Self+nonCreature ");
                if (equipExtras != null) {
                    abilityStr.append("| ").append(equipExtras[1]).append(" ");
                }
                abilityStr.append("| PrecostDesc$ Fortify ");
                Cost cost = new Cost(equipCost, true);
                if (!cost.isOnlyManaCost()) { //Something other than a mana cost
                    abilityStr.append("- ");
                }
                abilityStr.append("| CostDesc$ " + cost.toSimpleString() + " ");
                abilityStr.append("| SpellDescription$ (" + cost.toSimpleString() + ": Attach to target land you control. Fortify only as a sorcery.)");
     
                // instantiate attach ability
                final SpellAbility sa = AbilityFactory.getAbility(abilityStr.toString(), card);
                card.addSpellAbility(sa);
            }
            else if (keyword.startsWith("Bestow")) {
                addSpellAbility(keyword, card, null);
            }
            else if (keyword.equals("Hideaway")) {
                card.getCurrentState().addIntrinsicKeyword("CARDNAME enters the battlefield tapped.");

                final Trigger hideawayTrigger = TriggerHandler.parseTrigger("Mode$ ChangesZone | Destination$ Battlefield | ValidCard$ Card.Self | Execute$ TrigHideawayDig | TriggerDescription$ When CARDNAME enters the battlefield, look at the top four cards of your library, exile one face down, then put the rest on the bottom of your library.", card, true);
                card.addTrigger(hideawayTrigger);
                card.setSVar("TrigHideawayDig", "DB$ Dig | Defined$ You | DigNum$ 4 | DestinationZone$ Exile | ExileFaceDown$ True | RememberChanged$ True | SubAbility$ DBHideawayEffect");
                final Trigger gainControlTrigger = TriggerHandler.parseTrigger("Mode$ ChangesController | ValidCard$ Card.Self | Execute$ DBHideawayEffect | Static$ True", card, true);
                card.addTrigger(gainControlTrigger);
                card.setSVar("DBHideawayEffect", "DB$ Effect | StaticAbilities$ STHideawayEffectLookAtCard | Triggers$ THideawayEffectCleanup | SVars$ DBHideawayEffectExileSelf | ImprintOnHost$ True | Duration$ Permanent | SubAbility$ DBHideawayRemember");
                card.setSVar("STHideawayEffectLookAtCard", "Mode$ Continuous | Affected$ Card.IsRemembered | MayLookAt$ True | EffectZone$ Command | AffectedZone$ Exile | Description$ You may look at the exiled card.");
                card.setSVar("THideawayEffectCleanup", "Mode$ ChangesZone | ValidCard$ Card.IsRemembered | Origin$ Exile | Destination$ Any | TriggerZone$ Command | Execute$ DBHideawayEffectExileSelf | Static$ True");
                card.setSVar("DBHideawayEffectExileSelf", "DB$ ChangeZone | Defined$ Self | Origin$ Command | Destination$ Exile");
                final Trigger changeZoneTrigger = TriggerHandler.parseTrigger("Mode$ ChangesZone | ValidCard$ Card.IsRemembered | Origin$ Exile | Destination$ Any | TriggerZone$ Command | Execute$ DBHideawayCleanup | Static$ True", card, true);
                card.addTrigger(changeZoneTrigger);
                card.setSVar("DBHideawayRemember", "DB$ Animate | Defined$ Imprinted | RememberObjects$ Remembered | Permanent$ True");
                card.setSVar("DBHideawayCleanup", "DB$ Cleanup | ClearRemembered$ True");
            }
        }

        // AddCost
        if (card.hasSVar("FullCost")) {
            String k[] = card.getSVar("FullCost").split(":");
            final SpellAbility sa1 = card.getFirstSpellAbility();
            if (sa1 != null && sa1.isSpell()) {
                sa1.setPayCosts(new Cost(k[0], sa1.isAbility()));
                if (k.length > 1) {
                    sa1.getMapParams().put("AILogic", k[1]);
                }
            }
        }

        // AltCost
        String altCost = card.getSVar("AltCost");
        if (StringUtils.isNotBlank(altCost)) {
            final SpellAbility sa1 = card.getFirstSpellAbility();
            if (sa1 != null && sa1.isSpell()) {
                card.addSpellAbility(makeAltCostAbility(card, altCost, sa1));
            }
        }

        setupEtbKeywords(card);
    }

    private static ReplacementEffect createETBReplacement(final Card card, ReplacementLayer layer,
            final String effect, final boolean optional, final boolean secondary,
            final boolean intrinsic, final String valid, final String zone) {
        SpellAbility repAb = AbilityFactory.getAbility(effect, card);
        String desc = repAb.getDescription();
        setupETBReplacementAbility(repAb);
        if (!intrinsic) {
            repAb.setIntrinsic(false);
        }

        StringBuilder repEffsb = new StringBuilder();
        repEffsb.append("Event$ Moved | ValidCard$ ").append(valid);
        repEffsb.append(" | Destination$ Battlefield | Description$ ").append(desc);
        if (optional) {
            repEffsb.append(" | Optional$ True");
        }
        if (secondary) {
            repEffsb.append(" | Secondary$ True");
        }

        if (!zone.isEmpty()) {
            repEffsb.append(" | ActiveZones$ " + zone);
        }

        ReplacementEffect re = ReplacementHandler.parseReplacement(repEffsb.toString(), card, intrinsic);
        re.setLayer(layer);
        re.setOverridingAbility(repAb);

        return card.addReplacementEffect(re);
    }
    /**
     * TODO: Write javadoc for this method.
     * @param card
     */
    public static void setupEtbKeywords(final Card card) {
        for (String kw : card.getKeywords()) {

            if (kw.startsWith("ETBReplacement")) {
                String[] splitkw = kw.split(":");
                ReplacementLayer layer = ReplacementLayer.smartValueOf(splitkw[1]);
                
                final boolean optional = splitkw.length >= 4 && splitkw[3].contains("Optional");

                final String valid = splitkw.length >= 6 ? splitkw[5] : "Card.Self";
                final String zone = splitkw.length >= 5 ? splitkw[4] : "";
                createETBReplacement(card, layer, card.getSVar(splitkw[2]), optional, false, true, valid, zone);
            } else if (kw.startsWith("etbCounter")) {
                makeEtbCounter(kw, card, true);
            } else if (kw.equals("CARDNAME enters the battlefield tapped.")) {
                card.removeIntrinsicKeyword(kw);

                String effect = "DB$ Tap | Defined$ Self | ETB$ True | SpellDescription$ CARDNAME enters the battlefield tapped.";

                createETBReplacement(card, ReplacementLayer.Other, effect, false, false, true, "Card.Self", "");
            }
        }
    }


    private static ReplacementEffect makeEtbCounter(final String kw, final Card card, final boolean intrinsic)
    {
        String parse = kw;

        String[] splitkw = parse.split(":");

        String desc = "CARDNAME enters the battlefield with ";
        desc += Lang.nounWithNumeral(splitkw[2], CounterType.valueOf(splitkw[1]).getName() + " counter");
        desc += " on it.";

        String extraparams = "";
        String amount = splitkw[2];
        if (splitkw.length > 3) {
            if (!splitkw[3].equals("no Condition")) {
                extraparams = splitkw[3];
            }
        }
        if (splitkw.length > 4) {
            desc = !splitkw[4].equals("no desc") ? splitkw[4] : "";
        }
        String abStr = "DB$ PutCounter | Defined$ Self | CounterType$ " + splitkw[1]
                + " | ETB$ True | CounterNum$ " + amount;

        if (!StringUtils.isNumeric(amount)) {
            abStr += " | References$ " + amount;
        }

        SpellAbility sa = AbilityFactory.getAbility(abStr, card);
        setupETBReplacementAbility(sa);
        if (!intrinsic) {
            sa.setIntrinsic(false);
        }

        String repeffstr = "Event$ Moved | ValidCard$ Card.Self | Destination$ Battlefield "
                + "| Secondary$ True | Description$ " + desc + (!extraparams.equals("") ? " | " + extraparams : "");

        ReplacementEffect re = ReplacementHandler.parseReplacement(repeffstr, card, intrinsic);
        re.setLayer(ReplacementLayer.Other);

        re.setOverridingAbility(sa);

        return card.addReplacementEffect(re);
    }
    
    public static void addTriggerAbility(final String keyword, final Card card, final KeywordsChange kws) {
        final boolean intrinsic = kws == null;

        if (keyword.startsWith("Afflict")) {
            final String[] k = keyword.split(" ");
            final String n = k[1];

            final String trigStr = "Mode$ AttackerBlocked | ValidCard$ Card.Self | TriggerZones$ Battlefield " +
                    " | ValidBlocker$ Creature | Execute$ AfflictLoseLife | Secondary$ True " +
                    " | TriggerDescription$ Afflict " + n + " (" + Keyword.getInstance(keyword).getReminderText() + ")";

            final String abStringAfflict = "DB$ Loselife | Defined$ TriggeredDefendingPlayer" +
                    " | LifeAmount$ " + n;

            final Trigger afflictTrigger = TriggerHandler.parseTrigger(trigStr.toString(), card, intrinsic);
            final Trigger cardTrigger = card.addTrigger(afflictTrigger);

            card.setSVar("AfflictLoseLife", abStringAfflict);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Annihilator")) {
            final String[] k = keyword.split(":");
            final String n = k[1];
            
            final String trig = "Mode$ Attacks | ValidCard$ Card.Self | "
                            + "TriggerZones$ Battlefield | Secondary$ True | TriggerDescription$ "
                            + "Annihilator " + n + " (" + Keyword.getInstance(keyword).getReminderText() + ")";

            final String effect = "AB$ Sacrifice | Cost$ 0 | Defined$ DefendingPlayer | SacValid$ Permanent | Amount$ " + k[1];
            
            final Trigger trigger = TriggerHandler.parseTrigger(trig, card, intrinsic);
            trigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));
            final Trigger cardTrigger = card.addTrigger(trigger);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Bushido")) {
            final String[] k = keyword.split(" ", 2);
            final String n = k[1];

            final String name = "Bushido" + n;

            final String trigBlock = "Mode$ Blocks | ValidCard$ Card.Self | Execute$ Trig" + name + "Pump | Secondary$ True"
                    + " | TriggerDescription$ "+ keyword + " (" + Keyword.getInstance(keyword).getReminderText() + ")";

            final String trigBlocked = "Mode$ AttackerBlocked | ValidCard$ Card.Self | Execute$ Trig" + name + "Pump | Secondary$ True "
                    + " | TriggerDescription$ "+ keyword + " (" + Keyword.getInstance(keyword).getReminderText() + ")";

            String pumpStr = "DB$ Pump | Defined$ Self | NumAtt$ " + n + " | NumDef$ " + n;
            if ("X".equals(n)) {
                pumpStr = "DB$ Pump | Defined$ Self | NumAtt$ " + name + " | NumDef$ " + name + " | References$ "+ name;
                card.setSVar(name, "Count$Valid Creature.attacking");
            }
            card.setSVar("Trig" + name + "Pump", pumpStr);

            final Trigger bushidoTrigger1 = TriggerHandler.parseTrigger(trigBlock, card, intrinsic);
            final Trigger bushidoTrigger2 = TriggerHandler.parseTrigger(trigBlocked, card, intrinsic);

            final Trigger cardTrigger1 = card.addTrigger(bushidoTrigger1);
            final Trigger cardTrigger2 = card.addTrigger(bushidoTrigger2);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger1);
                kws.addTrigger(cardTrigger2);
            }
        } else if (keyword.equals("Cascade")) {
            final StringBuilder trigScript = new StringBuilder(
                    "Mode$ SpellCast | ValidCard$ Card.Self | Execute$ TrigCascade | Secondary$ " +
                    "True | TriggerDescription$ Cascade - CARDNAME");

            final String abString = "DB$ DigUntil | Defined$ You | Amount$ 1 | Valid$ "
                    + "Card.nonLand+cmcLTCascadeX | FoundDestination$ Exile | RevealedDestination$"
                    + " Exile | ImprintRevealed$ True | RememberFound$ True";

            SpellAbility dig = AbilityFactory.getAbility(abString, card);
            dig.setSVar("CascadeX", "Count$CardManaCost");

            final String dbCascadeCast = "DB$ Play | Defined$ Remembered | WithoutManaCost$ True | Optional$ True";
            AbilitySub cascadeCast = (AbilitySub)AbilityFactory.getAbility(dbCascadeCast, card);

            dig.setSubAbility(cascadeCast);

            final String dbMoveToLib = "DB$ ChangeZoneAll | ChangeType$ Card.IsRemembered,Card.IsImprinted"
                    + " | Origin$ Exile | Destination$ Library | RandomOrder$ True | LibraryPosition$ -1";

            AbilitySub moveToLib = (AbilitySub)AbilityFactory.getAbility(dbMoveToLib, card);
            cascadeCast.setSubAbility(moveToLib);

            final String cascadeCleanup = "DB$ Cleanup | ClearRemembered$ True | ClearImprinted$ True";
            AbilitySub cleanup = (AbilitySub)AbilityFactory.getAbility(cascadeCleanup, card);
            moveToLib.setSubAbility(cleanup);

            dig.setIntrinsic(intrinsic);

            final Trigger cascadeTrigger = TriggerHandler.parseTrigger(trigScript.toString(), card, intrinsic);
            cascadeTrigger.setOverridingAbility(dig);

            final Trigger cardTrigger = card.addTrigger(cascadeTrigger);
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.equals("Conspire")) {
            final String trigScript = "Mode$ SpellCast | ValidCard$ Card.Self | Conspire$ True | Execute$ TrigConspire | Secondary$ True | TriggerDescription$ Copy CARDNAME if its conspire cost was paid";
            final String abString = "DB$ CopySpellAbility | Defined$ TriggeredSpellAbility | Amount$ 1";

            card.setSVar("TrigConspire", abString);
            final Trigger conspireTrigger = TriggerHandler.parseTrigger(trigScript, card, intrinsic);

            final Trigger cardTrigger = card.addTrigger(conspireTrigger);
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Cumulative upkeep")) {
            final String[] k = keyword.split(":");
            final Cost cost = new Cost(k[1], false);
            String costDesc = cost.toSimpleString();

            if (!cost.isOnlyManaCost()) {
                costDesc = "- " + costDesc;
            }

            String upkeepTrig = "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Battlefield " +
                    " | IsPresent$ Card.Self | Secondary$ True | TriggerDescription$ " + k[0] + " " +
                    costDesc + " (" + Keyword.getInstance(keyword).getReminderText() + ")";

            String effect = "DB$ Sacrifice | SacValid$ Self | CumulativeUpkeep$ " + k[1];

            final Trigger trigger = TriggerHandler.parseTrigger(upkeepTrig, card, intrinsic);
            trigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));
            final Trigger cardTrigger = card.addTrigger(trigger);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.equals("Dethrone")) {
            final StringBuilder trigScript = new StringBuilder(
                    "Mode$ Attacks | ValidCard$ Card.Self | Attacked$ Player.withMostLife | Secondary$ True | "
                    + "TriggerZones$ Battlefield | Execute$ DethroneCounters | TriggerDescription$"
                    + " Dethrone (" + Keyword.getInstance("Dethrone").getReminderText() + ")");

            final String abString = "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | "
                    + "CounterNum$ 1";
            card.setSVar("DethroneCounters", abString);
            final Trigger dethroneTrigger = TriggerHandler.parseTrigger(trigScript.toString(), card, intrinsic);


            final Trigger cardTrigger = card.addTrigger(dethroneTrigger);
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Echo")) {
            final String[] k = keyword.split(":");
            final String cost = k[1];

            String upkeepTrig = "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Battlefield " +
                    " | IsPresent$ Card.Self+cameUnderControlSinceLastUpkeep | Secondary$ True | " +
                    "TriggerDescription$ " + Keyword.getInstance(keyword).getReminderText();

            String ref = "X".equals(cost) ? " | References$ X" : "";
            String effect = "DB$ Sacrifice | SacValid$ Self | "
                    + "Echo$ " + cost + ref;

            final Trigger trigger = TriggerHandler.parseTrigger(upkeepTrig, card, intrinsic);
            trigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));
            final Trigger cardTrigger = card.addTrigger(trigger);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Evoke")) {
            final StringBuilder trigStr = new StringBuilder(
                    "Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | ValidCard$ Card.Self+evoked | Secondary$ True | TriggerDescription$ "
                            + "Evoke (" + Keyword.getInstance(keyword).getReminderText() + ")");

            final String effect = "DB$ Sacrifice";
            final Trigger trigger = TriggerHandler.parseTrigger(trigStr.toString(), card, intrinsic);
            trigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));
            final Trigger cardTrigger = card.addTrigger(trigger);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.equals("Evolve")) {
            final String trigStr = "Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | "
                    + " ValidCard$ Creature.YouCtrl+Other | EvolveCondition$ True | "
                    + "TriggerZones$ Battlefield | Secondary$ True | "
                    + "TriggerDescription$ Evolve (" + Keyword.getInstance(keyword).getReminderText()+ ")";
            final String effect = "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | "
                    + "CounterNum$ 1 | Evolve$ True";
            
            final Trigger trigger = TriggerHandler.parseTrigger(trigStr.toString(), card, intrinsic);
            trigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));
            final Trigger cardTrigger = card.addTrigger(trigger);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.equals("Exalted")) {
            final String trig = "Mode$ Attacks | ValidCard$ Creature.YouCtrl | Alone$ True | "
                    + "Execute$ ExaltedPump | TriggerZones$ Battlefield | Secondary$ True | TriggerDescription$ "
                    + "Exalted (" + Keyword.getInstance(keyword).getReminderText() + ")";

            final String effect = "DB$ Pump | Defined$ TriggeredAttacker | NumAtt$ +1 | NumDef$ +1";
            final Trigger trigger = TriggerHandler.parseTrigger(trig, card, intrinsic);
            trigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));
            final Trigger cardTrigger = card.addTrigger(trigger);
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.equals("Exploit")) {
            final String trigStr = "Mode$ ChangesZone | ValidCard$ Card.Self | Origin$ Any | Destination$ Battlefield | Secondary$ True"
                    + " | TriggerDescription$ Exploit (" + Keyword.getInstance(keyword).getReminderText() + ")";
            final String effect = "DB$ Sacrifice | SacValid$ Creature | Exploit$ True | Optional$ True";

            final Trigger trigger = TriggerHandler.parseTrigger(trigStr, card, intrinsic);

            trigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));
            final Trigger cardTrigger = card.addTrigger(trigger);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.equals("Extort")) {
            final String extortTrigger = "Mode$ SpellCast | ValidCard$ Card | ValidActivatingPlayer$ You | "
                    + "TriggerZones$ Battlefield | Execute$ ExtortOpps | Secondary$ True"
                    + " | TriggerDescription$ Extort ("+ Keyword.getInstance(keyword).getReminderText() +")";
            final String abString = "AB$ LoseLife | Cost$ WB | Defined$ Player.Opponent | "
                    + "LifeAmount$ 1 | SubAbility$ ExtortGainLife";
            final String dbString = "DB$ GainLife | Defined$ You | LifeAmount$ AFLifeLost | References$ AFLifeLost";
            final Trigger parsedTrigger = TriggerHandler.parseTrigger(extortTrigger, card, intrinsic);
            final Trigger cardTrigger = card.addTrigger(parsedTrigger);
            card.setSVar("ExtortOpps", abString);
            card.setSVar("ExtortGainLife", dbString);
            card.setSVar("AFLifeLost", "Number$0");
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Fabricate")) {
            final String[] k = keyword.split(":");
            final String n = k[1];

            final String name = StringUtils.join(k);

            final String trigStr = "Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield "
                    + " | Execute$ " + name + "Choose | ValidCard$ Card.Self | Secondary$ True"
                    + " | TriggerDescription$ Fabricate " + n + " (" + Keyword.getInstance(keyword).getReminderText() + ")";

            final String choose = "DB$ GenericChoice | Choices$ DB" + name + "Counter,DB" + name + "Token | ConditionPresent$ Card.StrictlySelf | SubAbility$ DB" + name + "Token2 | AILogic$ " + name;
            final String counter = "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ " + n + " | SpellDescription$ Put "
                    + Lang.nounWithNumeral(n, "+1/+1 counter") + " on it.";
            final String token = "DB$ Token | TokenAmount$ " + n + " | TokenName$ Servo | TokenTypes$ Artifact,Creature,Servo"
                    + " | TokenOwner$ You | TokenColors$ Colorless | TokenPower$ 1 | TokenToughness$ 1"
                    + " | TokenImage$ c 1 1 servo | TokenAltImages$ c_1_1_servo2,c_1_1_servo3 | SpellDescription$ Create "
                    + Lang.nounWithNumeral(n, "1/1 colorless Servo artifact creature token") + ".";

            final Trigger trigger = TriggerHandler.parseTrigger(trigStr, card, intrinsic);
            final Trigger cardTrigger = card.addTrigger(trigger);

            card.setSVar(name + "Choose", choose);
            card.setSVar("DB" + name + "Counter", counter);
            card.setSVar("DB" + name + "Token", token);
            card.setSVar("DB" + name + "Token2", token + " | ConditionPresent$ Card.StrictlySelf | ConditionCompare$ EQ0");

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Fading")) {
            String upkeepTrig = "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Battlefield " +
                    " | Execute$ TrigUpkeepFading | Secondary$ True | TriggerDescription$ At the beginning of " +
                    "your upkeep, remove a fade counter from CARDNAME. If you can't, sacrifice CARDNAME.";

            card.setSVar("TrigUpkeepFading", "AB$ RemoveCounter | Cost$ 0 | Defined$ Self | CounterType$ FADE" +
                    " | CounterNum$ 1 | RememberRemoved$ True | SubAbility$ DBUpkeepFadingSac");
            card.setSVar("DBUpkeepFadingSac","DB$ Sacrifice | SacValid$ Self | ConditionCheckSVar$ FadingCheckSVar" +
                    " | ConditionSVarCompare$ EQ0 | References$ FadingCheckSVar | SubAbility$ FadingCleanup");
            card.setSVar("FadingCleanup","DB$ Cleanup | ClearRemembered$ True");
            card.setSVar("FadingCheckSVar","Count$RememberedSize");
            final Trigger trigger = TriggerHandler.parseTrigger(upkeepTrig, card, intrinsic);
            final Trigger cardTrigger = card.addTrigger(trigger);
            
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.equals("Flanking")) {
            final StringBuilder trigFlanking = new StringBuilder(
                    "Mode$ AttackerBlockedByCreature | ValidCard$ Card.Self | ValidBlocker$ Creature.withoutFlanking " +
                    " | TriggerZones$ Battlefield | Execute$ FlankingPump | Secondary$ True " +
                    " | TriggerDescription$ Flanking (" + Keyword.getInstance(keyword).getReminderText() + ")");

            final String abStringFlanking = "DB$ Pump | Defined$ TriggeredBlocker | NumAtt$ -1 | NumDef$ -1";

            final Trigger flankingTrigger = TriggerHandler.parseTrigger(trigFlanking.toString(), card, intrinsic);
            final Trigger cardTrigger = card.addTrigger(flankingTrigger);

            card.setSVar("FlankingPump", abStringFlanking);
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Graft")) {
            final String abStr = "DB$ MoveCounter | Source$ Self | "
                    + "Defined$ TriggeredCardLKICopy | CounterType$ P1P1 | CounterNum$ 1";

            String trigStr = "Mode$ ChangesZone | ValidCard$ Creature.Other"
                + "| Origin$ Any | Destination$ Battlefield "
                + "| TriggerZones$ Battlefield | OptionalDecider$ You "
                + "| IsPresent$ Card.Self+counters_GE1_P1P1"
                + "| Secondary$ True | TriggerDescription$ "
                + "Whenever another creature enters the battlefield, you "
                + "may move a +1/+1 counter from this creature onto it.";
            final Trigger trigger = TriggerHandler.parseTrigger(trigStr, card, intrinsic);

            trigger.setOverridingAbility(AbilityFactory.getAbility(abStr, card));

            final Trigger cardTrigger = card.addTrigger(trigger);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Haunt")) {
            final String[] k = keyword.split(":");
            final String hauntSVarName = k[1];

            List<Trigger> cardTriggers = Lists.newArrayList();

            final StringBuilder sb = new StringBuilder();
            if (card.isCreature()) {
                sb.append("When ").append(card.getName());
                sb.append(" enters the battlefield or the creature it haunts dies, ");
            } else {
                sb.append("When the creature ").append(card.getName());
                sb.append(" haunts dies, ");
            }

            // use new feature to get the Ability
            sb.append("ABILITY");

            final String hauntDescription = sb.toString();

            // Second, create the trigger that runs when the haunted creature dies
            final StringBuilder sbDies = new StringBuilder();
            sbDies.append("Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | TriggerZones$ Exile |");
            sbDies.append("ValidCard$ Creature.HauntedBy | Execute$ ").append(hauntSVarName);
            sbDies.append(" | TriggerDescription$ ").append(hauntDescription);

            final Trigger hauntedDies = TriggerHandler.parseTrigger(sbDies.toString(), card, intrinsic);

            // Fourth, create a trigger that removes the haunting status if the
            // haunter leaves the exile
            final StringBuilder sbUnExiled = new StringBuilder();
            sbUnExiled.append("Mode$ ChangesZone | Origin$ Exile | Destination$ Any | ");
            sbUnExiled.append("ValidCard$ Card.Self | Static$ True | Secondary$ True | ");
            sbUnExiled.append("TriggerDescription$ Blank");

            final Trigger haunterUnExiled = TriggerHandler.parseTrigger(sbUnExiled.toString(), card,
                    intrinsic);

            final SpellAbility unhaunt = AbilityFactory.getAbility("DB$ Haunt", card);

            haunterUnExiled.setOverridingAbility(unhaunt);

            cardTriggers.add(card.addTrigger(haunterUnExiled));

            // Trigger for when the haunted creature leaves the battlefield
            final StringBuilder sbHauntRemoved = new StringBuilder();
            sbHauntRemoved.append("Mode$ ChangesZone | Origin$ Battlefield | Destination$ Any | ");
            sbHauntRemoved.append("ValidCard$ Creature.HauntedBy | Static$ True | Secondary$ True | ");
            sbHauntRemoved.append("TriggerDescription$ Blank");

            final Trigger trigHauntRemoved = TriggerHandler.parseTrigger(sbHauntRemoved.toString(), card,
                    intrinsic);
            trigHauntRemoved.setOverridingAbility(unhaunt);
            
            cardTriggers.add(card.addTrigger(trigHauntRemoved));

            // Fifth, add all triggers and abilities to the card.
            if (card.isCreature()) {
                // Third, create the trigger that runs when the haunting creature
                // enters the battlefield
                final StringBuilder sbETB = new StringBuilder();
                sbETB.append("Mode$ ChangesZone | Destination$ Battlefield | ValidCard$ Card.Self | Execute$ ");
                sbETB.append(hauntSVarName).append(" | Secondary$ True | TriggerDescription$ ");
                sbETB.append(hauntDescription);

                final Trigger haunterETB = TriggerHandler.parseTrigger(sbETB.toString(), card, intrinsic);

                cardTriggers.add(card.addTrigger(haunterETB));
            }

            // First, create trigger that runs when the haunter goes to the
            // graveyard
            final StringBuilder sbHaunter = new StringBuilder();
            sbHaunter.append("Mode$ ChangesZone | Origin$ ");
            sbHaunter.append(card.isCreature() ? "Battlefield" : "Stack");
            sbHaunter.append(" | Destination$ Graveyard | ValidCard$ Card.Self");
            sbHaunter.append(" | Static$ True | Secondary$ True | TriggerDescription$ Blank");

            final Trigger haunterDies = TriggerHandler.parseTrigger(sbHaunter.toString(), card, intrinsic);

            final String hauntDiesEffectStr = "DB$ Haunt | ValidTgts$ Creature | TgtPrompt$ Choose target creature to haunt";
            final SpellAbility hauntDiesAbility = AbilityFactory.getAbility(hauntDiesEffectStr, card);

            haunterDies.setOverridingAbility(hauntDiesAbility);

            cardTriggers.add(card.addTrigger(haunterDies));

            cardTriggers.add(card.addTrigger(hauntedDies));

            if (!intrinsic) {
                for (final Trigger cardTrigger : cardTriggers) {
                    kws.addTrigger(cardTrigger);
                }
            }
        } else if (keyword.equals("Ingest")) {
            final String trigStr = "Mode$ DamageDone | ValidSource$ Card.Self | ValidTarget$ Player | CombatDamage$ True"
                    + "| Secondary$ True | TriggerZones$ Battlefield | TriggerDescription$ Ingest ("
                    + Keyword.getInstance(keyword).getReminderText() + ")";

            final String abStr = "DB$ Mill | NumCards$ 1 | Destination$ Exile | Defined$ TriggeredTarget";

            final Trigger trigger = TriggerHandler.parseTrigger(trigStr, card, intrinsic);

            trigger.setOverridingAbility(AbilityFactory.getAbility(abStr, card));

            final Trigger cardTrigger = card.addTrigger(trigger);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.equals("Living Weapon")) {
            final StringBuilder sbTrig = new StringBuilder();
            sbTrig.append("Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | ");
            sbTrig.append("ValidCard$ Card.Self | Secondary$ True | TriggerDescription$ ");
            sbTrig.append("Living Weapon (" + Keyword.getInstance(keyword).getReminderText() + ")");

            final StringBuilder sbGerm = new StringBuilder();
            sbGerm.append("DB$ Token | TokenAmount$ 1 | TokenName$ Germ | TokenTypes$ Creature,Germ | RememberTokens$ True | ");
            sbGerm.append("TokenOwner$ You | TokenColors$ Black | TokenPower$ 0 | TokenToughness$ 0 | TokenImage$ B 0 0 Germ");

            final SpellAbility saGerm = AbilityFactory.getAbility(sbGerm.toString(), card);

            final String sbAttach = "DB$ Attach | Defined$ Remembered";
            final AbilitySub saAttach = (AbilitySub) AbilityFactory.getAbility(sbAttach, card);
            saGerm.setSubAbility(saAttach);

            final String sbClear = "DB$ Cleanup | ClearRemembered$ True";
            final AbilitySub saClear = (AbilitySub) AbilityFactory.getAbility(sbClear, card);
            saAttach.setSubAbility(saClear);

            final Trigger etbTrigger = TriggerHandler.parseTrigger(sbTrig.toString(), card, intrinsic);

            etbTrigger.setOverridingAbility(saGerm);

            final Trigger cardTrigger = card.addTrigger(etbTrigger);

            if (!intrinsic) {
                saGerm.setIntrinsic(false);
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Madness")) {
            // Set Madness Triggers
            final String[] k = keyword.split(":");
            final String manacost = k[1];
            final String trigPlay = "TrigPlayMadness" + StringUtils.join(manacost.split(" "));

            final String trigStr = "Mode$ Discarded | ValidCard$ Card.Self | IsMadness$ True | " +
                    "Execute$ " + trigPlay + " | Secondary$ True | TriggerDescription$ " +
                    "Play Madness " + ManaCostParser.parse(manacost) + " - " + card.getName();

            final String playMadness = "AB$ Play | Cost$ 0 | Defined$ Self | PlayCost$ " + manacost +
                    " | ConditionDefined$ Self | ConditionPresent$ Card.StrictlySelf+inZoneExile" + 
                    " | Optional$ True | SubAbility$ DBWasNotPlayMadness | RememberPlayed$ True | Madness$ True";
            final String moveToYard = "DB$ ChangeZone | Defined$ Self.StrictlySelf | Origin$ Exile | " +
                    "Destination$ Graveyard | TrackDiscarded$ True | ConditionDefined$ Remembered | ConditionPresent$" +
                    " Card | ConditionCompare$ EQ0 | SubAbility$ DBMadnessCleanup";
            final String cleanUp = "DB$ Cleanup | ClearRemembered$ True";

            final Trigger parsedTrigger = TriggerHandler.parseTrigger(trigStr, card, intrinsic);
            final Trigger cardTrigger = card.addTrigger(parsedTrigger);

            card.setSVar(trigPlay, playMadness);
            card.setSVar("DBWasNotPlayMadness", moveToYard);
            card.setSVar("DBMadnessCleanup", cleanUp);
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.equals("Melee")) {
            final String trigStr = "Mode$ Attacks | ValidCard$ Card.Self | Execute$ MeleePump | Secondary$ True " +
                    " | TriggerDescription$ Melee (" + Keyword.getInstance(keyword).getReminderText() + ")";

            final String effect = "DB$ Pump | Defined$ TriggeredAttacker | NumAtt$ MeleeX | NumDef$ MeleeX | References$ MeleeX";
            card.setSVar("MeleePump", effect);
            card.setSVar("MeleeX", "TriggeredPlayersDefenders$Amount");
            final Trigger trigger = TriggerHandler.parseTrigger(trigStr.toString(), card, intrinsic);
            final Trigger cardTrigger = card.addTrigger(trigger);
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Miracle")) {
            final String[] k = keyword.split(":");
            final String manacost = k[1];
            final String abStrReveal = "DB$ Reveal | Defined$ You | RevealDefined$ Self | MiracleCost$ " + manacost;
            final String abStrPlay = "DB$ Play | Defined$ Self | PlayCost$ " + manacost;

            final String abStrEffect = "DB$ Effect | RememberObjects$ Self | Duration$ Permanent "
                + " | StaticAbilities$ SMayLookAt | Triggers$ TrigCleanup";

            final String abStrLook = "Mode$ Continuous | Affected$ Card.IsRemembered | AffectedZone$ Hand "
                + " | EffectZone$ Command | MayLookAt$ Player";

            //SVar:TrigCleanup:
            final String trigExile = "Mode$ ChangesZone | ValidCard$ Card.IsRemembered | Origin$ Hand " +
                    " | Destination$ Any | TriggerZones$ Command | Static$ True | Execute$ MiracleSelfExile";

            //SVar:DBExileSelf:
            final String abExile = "DB$ ChangeZone | Defined$ Self | Origin$ Command | Destination$ Exile"; 

            final String trigStrDrawn = "Mode$ Drawn | ValidCard$ Card.Self | Miracle$ True | Secondary$ True"
                + " | OptionalDecider$ You | Static$ True | TriggerDescription$ CARDNAME - Miracle";

            final String trigStrRevealed = "Mode$ Revealed | ValidCard$ Card.Self | Miracle$ True"
                + " | IsPresent$ Card.Self | PresentZone$ Hand | Secondary$ True"
                + " | TriggerDescription$ CARDNAME - Miracle";

            final Trigger triggerDrawn = TriggerHandler.parseTrigger(trigStrDrawn, card, intrinsic);

            SpellAbility revealSA = AbilityFactory.getAbility(abStrReveal, card);

            AbilitySub effectSA = (AbilitySub)AbilityFactory.getAbility(abStrEffect, card);
            effectSA.setSVar("SMayLookAt", abStrLook);
            effectSA.setSVar("TrigCleanup", trigExile);
            effectSA.setSVar("MiracleSelfExile", abExile);
            revealSA.setSubAbility(effectSA);

            triggerDrawn.setOverridingAbility(revealSA);

            final Trigger triggerRevealed = TriggerHandler.parseTrigger(trigStrRevealed, card, intrinsic);

            triggerRevealed.setOverridingAbility(AbilityFactory.getAbility(abStrPlay, card));

            final Trigger cardTriggerDrawn = card.addTrigger(triggerDrawn);
            final Trigger cardTriggerRevealed = card.addTrigger(triggerRevealed);
            if (!intrinsic) {
                kws.addTrigger(cardTriggerDrawn);
                kws.addTrigger(cardTriggerRevealed);
            }
        } else if (keyword.startsWith("Modular")) {
            final String abStr = "AB$ PutCounter | Cost$ 0 | ValidTgts$ Artifact.Creature | " +
                    "TgtPrompt$ Select target artifact creature | CounterType$ P1P1 | CounterNum$ ModularX";
            
            String trigStr = "Mode$ ChangesZone | ValidCard$ Card.Self | Origin$ Battlefield | Destination$ Graveyard" +
                    " | OptionalDecider$ TriggeredCardController | TriggerController$ TriggeredCardController" +
                    " | Secondary$ True | TriggerDescription$ When CARDNAME is put into a graveyard from the battlefield, " +
                    "you may put a +1/+1 counter on target artifact creature for each +1/+1 counter on CARDNAME";

            final Trigger trigger = TriggerHandler.parseTrigger(trigStr, card, intrinsic);
            
            SpellAbility ab = AbilityFactory.getAbility(abStr, card);
            
            ab.setSVar("ModularX", "TriggeredCard$CardCounters.P1P1");
            
            trigger.setOverridingAbility(ab);
            
            final Trigger cardTrigger = card.addTrigger(trigger);
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.equals("Myriad")) {
            final String actualTrigger = "Mode$ Attacks | ValidCard$ Card.Self | Execute$ "
                    + "MyriadAbility | Secondary$ True | TriggerDescription$ Myriad ("
                    + Keyword.getInstance(keyword).getReminderText() + ")";
            final String abString = "DB$ RepeatEach | RepeatPlayers$ OpponentsOtherThanDefendingPlayer"
                    + " | RepeatSubAbility$ MyriadCopy | SubAbility$ MyriadDelTrig";
            final String dbString1 = "DB$ CopyPermanent | Defined$ Self | Tapped$ True | "
                    + "Optional$ True | CopyAttacking$ Remembered | ChoosePlayerOrPlaneswalker$"
                    + " True | ImprintCopied$ True";
            final String dbString2 = "DB$ DelayedTrigger | Mode$ Phase | Phase$ EndCombat | "
                    + "Execute$ MyriadExile | RememberObjects$ Imprinted | TriggerDescription$"
                    + " Exile the tokens at end of combat. | SubAbility$ MyriadCleanup";
            final String dbString3 = "DB$ ChangeZone | Defined$ DelayTriggerRemembered | Origin$"
                    + " Battlefield | Destination$ Exile";
            final String dbString4 = "DB$ Cleanup | ClearImprinted$ True";
            final Trigger parsedTrigger = TriggerHandler.parseTrigger(actualTrigger, card, intrinsic);

            card.setSVar("MyriadAbility", abString);
            card.setSVar("MyriadCopy", dbString1);
            card.setSVar("MyriadDelTrig", dbString2);
            card.setSVar("MyriadExile", dbString3);
            card.setSVar("MyriadCleanup", dbString4);
            
            final Trigger cardTrigger = card.addTrigger(parsedTrigger);
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.equals("Persist")) {
            final String trigStr = "Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | OncePerEffect$ True " +
                    " | Execute$ PersistReturn | ValidCard$ Card.Self+counters_EQ0_M1M1 | Secondary$ True" + 
                    " | TriggerDescription$ Persist (" + Keyword.getInstance(keyword).getReminderText() + ")";
            final String effect = "DB$ ChangeZone | Defined$ TriggeredCard | Origin$ Graveyard | Destination$ Battlefield | WithCounters$ M1M1_1";

            final Trigger persistTrigger = TriggerHandler.parseTrigger(trigStr, card, intrinsic);
            final Trigger cardTrigger = card.addTrigger(persistTrigger);

            card.setSVar("PersistReturn", effect);
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Poisonous")) {
            final String[] k = keyword.split(" ");
            final String n = k[1];
            final String trigStr = "Mode$ DamageDone | ValidSource$ Card.Self | ValidTarget$ Player | CombatDamage$ True | Secondary$ True"
                    + " | TriggerZones$ Battlefield | TriggerDescription$ " + keyword + " (" + Keyword.getInstance(keyword).getReminderText() + ")";

            final Trigger parsedTrigger = TriggerHandler.parseTrigger(trigStr.toString(), card, intrinsic);

            final String effect = "AB$ Poison | Cost$ 0 | Defined$ TriggeredTarget | Num$ " + n;
            parsedTrigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));
            final Trigger cardTrigger = card.addTrigger(parsedTrigger);
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Presence")) {
            final String[] k = keyword.split(":");
            card.addIntrinsicKeyword("Kicker:Reveal<1/" + k[1] + ">:Generic");
        } else if (keyword.equals("Provoke")) {
            final String actualTrigger = "Mode$ Attacks | ValidCard$ Card.Self | "
                    + "OptionalDecider$ You | Execute$ ProvokeAbility | Secondary$ True | TriggerDescription$ "
                    + Keyword.getInstance(keyword).getReminderText();
            final String abString = "DB$ MustBlock | ValidTgts$ Creature.DefenderCtrl | "
                    + "TgtPrompt$ Select target creature defending player controls | SubAbility$ ProvokeUntap";
            final String dbString = "DB$ Untap | Defined$ Targeted";

            card.setSVar("ProvokeAbility", abString);
            card.setSVar("ProvokeUntap", dbString);

            final Trigger parsedTrigger = TriggerHandler.parseTrigger(actualTrigger, card, intrinsic);

            final Trigger cardTrigger = card.addTrigger(parsedTrigger);
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.equals("Prowess")) {
            final String trigProwess = "Mode$ SpellCast | ValidCard$ Card.nonCreature" 
                    + " | ValidActivatingPlayer$ You | TriggerZones$ Battlefield | TriggerDescription$ "
                    + "Prowess (" + Keyword.getInstance(keyword).getReminderText() + ")";

            final String effect = "DB$ Pump | Defined$ Self | NumAtt$ +1 | NumDef$ +1";

            final Trigger parsedTrigger = TriggerHandler.parseTrigger(trigProwess, card, intrinsic);
            parsedTrigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));

            final Trigger cardTrigger = card.addTrigger(parsedTrigger);
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }

            if (!card.hasSVar("BuffedBy")) {
                card.setSVar("BuffedBy", "Card.nonCreature+nonLand"); // for the AI
            }
        } else if (keyword.startsWith("Rampage")) {
            final String[] k = keyword.split(" ");
            final String n = k[1];

            final String trigStr = "Mode$ AttackerBlocked | ValidCard$ Card.Self | TriggerZones$ Battlefield " +
                    " | ValidBlocker$ Creature | MinBlockers$ 1 | Execute$ RampagePump" + n + " | Secondary$ True " +
                    " | TriggerDescription$ Rampage " + n + " (" + Keyword.getInstance(keyword).getReminderText() + ")";

            final String abStringRampage = "DB$ Pump | Defined$ TriggeredAttacker" +
                    " | NumAtt$ Rampage" + n + " | NumDef$ Rampage" + n + " | References$ Rampage" + n;

            final Trigger rampageTrigger = TriggerHandler.parseTrigger(trigStr.toString(), card, intrinsic);
            final Trigger cardTrigger = card.addTrigger(rampageTrigger);

            card.setSVar("RampagePump" + n, abStringRampage);
            card.setSVar("Rampage" + n, "SVar$RampageCount/Times." + n);

            card.setSVar("RampageCount", "TriggerCount$NumBlockers/Minus.1");
            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Renown")) {
            final String[] k = keyword.split(":");

            String renownTrig = "Mode$ DamageDone | ValidSource$ Card.Self | ValidTarget$ Player"
                    + " | IsPresent$ Card.Self+IsNotRenowned | CombatDamage$ True | Secondary$ True"
                    + " | TriggerDescription$ Renown " + k[1] +" (" + Keyword.getInstance(keyword).getReminderText() + ")";

            final String effect = "DB$ PutCounter | Defined$ Self | "
                    + "CounterType$ P1P1 | CounterNum$ " + k[1] + " | Renown$ True";

            final Trigger parsedTrigger = TriggerHandler.parseTrigger(renownTrig, card, intrinsic);
            parsedTrigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));
            final Trigger cardTrigger = card.addTrigger(parsedTrigger);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.equals("Soulbond")) {
            // Setup ETB trigger for card with Soulbond keyword
            final String actualTriggerSelf = "Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | "
                    + "ValidCard$ Card.Self | OptionalDecider$ You | "
                    + "IsPresent$ Creature.Other+YouCtrl+NotPaired | Secondary$ True | "
                    + "TriggerDescription$ When CARDNAME enters the battlefield, "
                    + "you may pair CARDNAME with another unpaired creature you control";
            final String abStringSelf = "DB$ Bond | Defined$ Self | ValidCards$ Creature.Other+YouCtrl+NotPaired";
            final Trigger parsedTriggerSelf = TriggerHandler.parseTrigger(actualTriggerSelf, card, intrinsic);
            parsedTriggerSelf.setOverridingAbility(AbilityFactory.getAbility(abStringSelf, card));
            final Trigger cardTriggerSelf = card.addTrigger(parsedTriggerSelf);

            // Setup ETB trigger for other creatures you control
            final String actualTriggerOther = "Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | "
                    + "ValidCard$ Creature.Other+YouCtrl | TriggerZones$ Battlefield | OptionalDecider$ You | "
                    + " IsPresent$ Creature.Self+NotPaired | Secondary$ True | "
                    + " TriggerDescription$ When another unpaired creature you control enters the battlefield, "
                    + "you may pair it with CARDNAME";
            final String abStringOther = "DB$ Bond | Defined$ TriggeredCard | ValidCards$ Creature.Self+NotPaired";
            final Trigger parsedTriggerOther = TriggerHandler.parseTrigger(actualTriggerOther, card, intrinsic);
            parsedTriggerOther.setOverridingAbility(AbilityFactory.getAbility(abStringOther, card));
            final Trigger cardTriggerOther = card.addTrigger(parsedTriggerOther);

            if (!intrinsic) {
                kws.addTrigger(cardTriggerSelf);
                kws.addTrigger(cardTriggerOther);
            }
        } else if (keyword.startsWith("Soulshift")) {
            final String[] k = keyword.split(":");

            final String actualTrigger = "Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard"
                    + "| Secondary$ True | OptionalDecider$ You | ValidCard$ Card.Self"
                    + "| TriggerController$ TriggeredCardController | TriggerDescription$ " + k[0] + " " + k[1]
                    + " (" + Keyword.getInstance(keyword).getReminderText() + ")";
            final String effect = "DB$ ChangeZone | Origin$ Graveyard | Destination$ Hand"
                    + "| ValidTgts$ Spirit.YouOwn+cmcLE" + k[1];
            final Trigger parsedTrigger = TriggerHandler.parseTrigger(actualTrigger, card, intrinsic);
            final SpellAbility sp = AbilityFactory.getAbility(effect, card);
            // Soulshift X
            if (k[1].equals("X")) {
                sp.setSVar("X", "Count$LastStateBattlefield " + k[3]);
            }

            parsedTrigger.setOverridingAbility(sp);
            final Trigger cardTrigger = card.addTrigger(parsedTrigger);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Suspend")) {
            //upkeep trigger
            StringBuilder upkeepTrig = new StringBuilder();;

            upkeepTrig.append("Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Exile ");
            upkeepTrig.append(" | IsPresent$ Card.Self+suspended | PresentZone$ Exile");
            // Mark this trigger as Secondary, so it's not displayed twice
            upkeepTrig.append(" | Secondary$ True | TriggerDescription$ At the beginning of your upkeep, if this card is suspended, remove a time counter from it");

            final String abRemove = "DB$ RemoveCounter | Defined$ Self | CounterType$ TIME | CounterNum$ 1";

            final Trigger parsedUpkeepTrig = TriggerHandler.parseTrigger(upkeepTrig.toString(), card, intrinsic);
            parsedUpkeepTrig.setOverridingAbility(AbilityFactory.getAbility(abRemove, card));
            final Trigger cardTriggerUpkeep = card.addTrigger(parsedUpkeepTrig);

            //play trigger
            StringBuilder playTrig = new StringBuilder();

            playTrig.append("Mode$ CounterRemoved | TriggerZones$ Exile | ValidCard$ Card.Self | CounterType$ TIME | NewCounterAmount$ 0 | Secondary$ True ");
            playTrig.append(" | TriggerDescription$ When the last time counter is removed from this card, if it's exiled, play it without paying its mana cost if able.  ");
            playTrig.append("If you can't, it remains exiled. If you cast a creature spell this way, it gains haste until you lose control of the spell or the permanent it becomes.");

            final String abPlay = "DB$ Play | Defined$ Self | WithoutManaCost$ True | SuspendCast$ True";

            final Trigger parsedPlayTrigger = TriggerHandler.parseTrigger(playTrig.toString(), card, intrinsic);
            parsedPlayTrigger.setOverridingAbility(AbilityFactory.getAbility(abPlay, card));
            final Trigger cardTriggerPlay = card.addTrigger(parsedPlayTrigger);

            if (!intrinsic) {
                kws.addTrigger(cardTriggerUpkeep);
                kws.addTrigger(cardTriggerPlay);
            }
        } else if (keyword.startsWith("Tribute")) {
            // use hardcoded ability name
            final String abStr = "TrigNotTribute";

            // get Description from Ability
            final String desc = AbilityFactory.getMapParams(card.getSVar(abStr)).get("SpellDescription");
            final String trigStr = "Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | ValidCard$ Card.Self+notTributed " +
                     " | Execute$ " + abStr + " | TriggerDescription$ " + desc;

            final Trigger parsedTrigger = TriggerHandler.parseTrigger(trigStr, card, intrinsic);
            final Trigger cardTrigger = card.addTrigger(parsedTrigger);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }

        } else if (keyword.equals("Undying")) {
            final String trigStr = "Mode$ ChangesZone | Origin$ Battlefield | Destination$ Graveyard | OncePerEffect$ True " +
                    " | Execute$ UndyingReturn | ValidCard$ Card.Self+counters_EQ0_P1P1 | TriggerZones$ Battlefield | Secondary$ True" + 
                    " | TriggerDescription$ Undying (" + Keyword.getInstance(keyword).getReminderText() + ")";
            final String effect = "DB$ ChangeZone | Defined$ TriggeredCard | Origin$ Graveyard | Destination$ Battlefield | WithCounters$ P1P1_1";

            final Trigger parsedTrigger = TriggerHandler.parseTrigger(trigStr, card, intrinsic);
            parsedTrigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));
            final Trigger cardTrigger = card.addTrigger(parsedTrigger);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("UpkeepCost")) {
            final String[] k = keyword.split(":");
            final Cost cost = new Cost(k[1], true);

            final StringBuilder sb = new StringBuilder();
            sb.append("At the beginning of your upkeep, sacrifice CARDNAME unless you ");
            if (cost.isOnlyManaCost()) {
                sb.append("pay ");
            }
            final String costStr = k.length == 3 ? k[2] : cost.toSimpleString();

            sb.append(costStr.substring(0,1).toLowerCase() + costStr.substring(1));
            sb.append(".");

            String upkeepTrig = "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | TriggerZones$ Battlefield | " +
                    "TriggerDescription$ " + sb.toString();

            String effect = "DB$ Sacrifice | SacValid$ Self | UnlessPayer$ You | UnlessCost$ " + k[1];

            if (!StringUtils.isNumeric(k[1])) {
                effect += " | References$ " + k[1];
            }

            final Trigger parsedTrigger = TriggerHandler.parseTrigger(upkeepTrig, card, intrinsic);
            parsedTrigger.setOverridingAbility(AbilityFactory.getAbility(effect, card));
            final Trigger cardTrigger = card.addTrigger(parsedTrigger);

            if (!intrinsic) {
                kws.addTrigger(cardTrigger);
            }
        } else if (keyword.startsWith("Vanishing")) {
            // Remove Time counter trigger
            final StringBuilder upkeepTrig = new StringBuilder("Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | " +
                    "TriggerZones$ Battlefield | IsPresent$ Card.Self+counters_GE1_TIME");
            if (keyword.contains(":")) {
                upkeepTrig.append(" | Secondary$ True");
            }
            upkeepTrig.append(" | TriggerDescription$ At the beginning of your upkeep, " +
                    "if CARDNAME has a time counter on it, remove a time counter from it.");

            final String remove = "DB$ RemoveCounter | Defined$ Self" +
                    " | CounterType$ TIME | CounterNum$ 1";
            final Trigger parsedUpkeepTrig = TriggerHandler.parseTrigger(upkeepTrig.toString(), card, intrinsic);
            parsedUpkeepTrig.setOverridingAbility(AbilityFactory.getAbility(remove, card));
            final Trigger cardUpkeepTrig = card.addTrigger(parsedUpkeepTrig);

            // sacrifice trigger
            final StringBuilder sacTrig = new StringBuilder("Mode$ CounterRemoved | TriggerZones$ Battlefield" +
            " | ValidCard$ Card.Self | NewCounterAmount$ 0 | CounterType$ TIME");
            if (keyword.contains(":")) {
                sacTrig.append("| Secondary$ True");
            }
            sacTrig.append("| TriggerDescription$ When the last time counter is removed from CARDNAME, sacrifice it.");

            final String sac = "DB$ Sacrifice | SacValid$ Self";
            final Trigger parsedSacTrigger = TriggerHandler.parseTrigger(sacTrig.toString(), card, intrinsic);
            parsedSacTrigger.setOverridingAbility(AbilityFactory.getAbility(sac, card));
            final Trigger cardSacTrig = card.addTrigger(parsedSacTrigger);

            if (!intrinsic) {
                kws.addTrigger(cardUpkeepTrig);
                kws.addTrigger(cardSacTrig);
            }
        }
    }

    public static void addReplacementEffect(final String keyword, final Card card, final KeywordsChange kws) {

        final boolean intrinsic = kws == null;
        if (keyword.startsWith("Bloodthirst")) {
            final String numCounters = keyword.split(":")[1];

            String desc;
            if (numCounters.equals("X")) {
                desc = "Bloodthirst X (This creature enters the battlefield with X +1/+1 counters on it, "
                        + "where X is the damage dealt to your opponents this turn.)";
                card.setSVar("X", "Count$BloodthirstAmount");
            } else {
                desc = "Bloodthirst " + numCounters + " (" + Keyword.getInstance(keyword).getReminderText() + ")";
            }

            final String etbCounter = "etbCounter:P1P1:" + numCounters + ":Bloodthirst$ True:" + desc;

            final ReplacementEffect re = makeEtbCounter(etbCounter, card, intrinsic);

            if (!intrinsic) {
                kws.addReplacement(re);
            }
        } else if (keyword.startsWith("Devour")) {

            final String[] k = keyword.split(":");
            final String magnitude = k[1];

            String abStr = "DB$ ChangeZone | Hidden$ True | Origin$ All | Destination$ Battlefield"
                    + " | Defined$ ReplacedCard";
            String dbStr = "DB$ Sacrifice | Defined$ You | Amount$ DevourSacX | "
                    + "References$ DevourSacX | SacValid$ Creature.Other | SacMessage$ another creature (Devour "+ magnitude + ") | "
                    + "RememberSacrificed$ True | Optional$ True | "
                    + "Devour$ True | SubAbility$ Devour"+magnitude+"Counters";
            String counterStr = "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ Devour"+magnitude+"X"
                    + " | ETB$ True | References$ Devour"+magnitude+"X,DevourSize | SubAbility$ DevourCleanup";

            card.setSVar("DevourETB", abStr);
            card.setSVar("Devour"+magnitude+"Sac", dbStr);
            card.setSVar("DevourSacX", "Count$Valid Creature.YouCtrl+Other");
            card.setSVar("Devour"+magnitude+"Counters", counterStr);
            card.setSVar("Devour"+magnitude+"X", "SVar$DevourSize/Times." + magnitude);
            card.setSVar("DevourSize", "Count$RememberedSize");
            card.setSVar("DevourCleanup", "DB$ Cleanup | ClearRemembered$ True | SubAbility$ DevourETB");

            String repeffstr = "Event$ Moved | ValidCard$ Card.Self | Destination$ Battlefield | ReplaceWith$ Devour"+magnitude+"Sac "
                    + "| Secondary$ True | Description$ Devour " + magnitude + " ("+ Keyword.getInstance(keyword).getReminderText() + ")";

            ReplacementEffect re = ReplacementHandler.parseReplacement(repeffstr, card, intrinsic);
            re.setLayer(ReplacementLayer.Other);
            ReplacementEffect cardre = card.addReplacementEffect(re);

            if (!intrinsic) {
                kws.addReplacement(cardre);
            }
        } else if (keyword.startsWith("Fading")) {
            final String[] k = keyword.split(":");
            final String m = k[1];

            StringBuilder sb = new StringBuilder("etbCounter:FADE:");
            sb.append(m).append(":no Condition:");
            sb.append("Fading ");
            sb.append(m);
            sb.append(" (").append(Keyword.getInstance(keyword).getReminderText()).append(")");

            final ReplacementEffect re = makeEtbCounter(sb.toString(), card, intrinsic);

            if (!intrinsic) {
                kws.addReplacement(re);
            }
        } else if (keyword.startsWith("Graft")) {
            final String[] k = keyword.split(":");
            final String m = k[1];

            StringBuilder sb = new StringBuilder("etbCounter:P1P1:");
            sb.append(m).append(":no Condition:");
            sb.append("Graft ");
            sb.append(m);
            sb.append(" (").append(Keyword.getInstance(keyword).getReminderText()).append(")");

            final ReplacementEffect re = makeEtbCounter(sb.toString(), card, intrinsic);

            if (!intrinsic) {
                kws.addReplacement(re);
            }
        } else if (keyword.startsWith("Madness")) {
            // Set Madness Replacement effects
            String repeffstr = "Event$ Discard | ActiveZones$ Hand | ValidCard$ Card.Self | " +
                    "ReplaceWith$ DiscardMadness | Secondary$ True | Description$ " +
                    "Madness: If you discard this card, discard it into exile.";
            ReplacementEffect re = ReplacementHandler.parseReplacement(repeffstr, card, intrinsic);
            ReplacementEffect cardre = card.addReplacementEffect(re);
            String sVarMadness = "DB$ Discard | Defined$ ReplacedPlayer" +
                    " | Mode$ Defined | DefinedCards$ ReplacedCard | Madness$ True";
            card.setSVar("DiscardMadness", sVarMadness);

            if (!intrinsic) {
                kws.addReplacement(cardre);
            }
        } else if (keyword.startsWith("Modular")) {
            final String[] k = keyword.split(":");
            final String m = k[1];

            StringBuilder sb = new StringBuilder("etbCounter:P1P1:");
            sb.append(m).append(":no Condition:");
            sb.append("Modular ");
            if (!StringUtils.isNumeric(m)) {
                sb.append("- ");
            }
            sb.append(m);
            sb.append(" (").append(Keyword.getInstance(keyword).getReminderText()).append(")");

            if ("Sunburst".equals(m)) {
                card.setSVar(m, "Count$Converge");
            }

            final ReplacementEffect re = makeEtbCounter(sb.toString(), card, intrinsic);

            if (!intrinsic) {
                kws.addReplacement(re);
            }
        } else if (keyword.equals("Unleash")) {
            String effect = "DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1 | SpellDescription$ Unleash (" + Keyword.getInstance(keyword).getReminderText() + ")";

            ReplacementEffect cardre = createETBReplacement(card, ReplacementLayer.Other, effect, true, true, intrinsic, "Card.Self", "");

            if (!intrinsic) {
                kws.addReplacement(cardre);
            }
        } else if (keyword.startsWith("Tribute")) {
            final String[] k = keyword.split(":");
            final String tributeAmount = k[1];

            final String effect = "DB$ PutCounter | Defined$ ReplacedCard | Tribute$ True | "
                    + "CounterType$ P1P1 | CounterNum$ " + tributeAmount
                    + " | ETB$ True | SpellDescription$ Tribute " + tributeAmount
                    + " ("+ Keyword.getInstance(keyword).getReminderText() + ")";

            ReplacementEffect cardre = createETBReplacement(card, ReplacementLayer.Other, effect, false, true, intrinsic, "Card.Self", "");

            if (!intrinsic) {
                kws.addReplacement(cardre);
            }
        } else if (keyword.startsWith("Vanishing") && keyword.contains(":")) {
            // Vanishing could be added to a card, but this Effect should only be done when it has amount
            final String[] k = keyword.split(":");
            final String m = k[1];

            StringBuilder sb = new StringBuilder("etbCounter:TIME:");
            sb.append(m).append(":no Condition:");
            sb.append("Vanishing ");
            sb.append(m);
            sb.append(" (").append(Keyword.getInstance(keyword).getReminderText()).append(")");

            final ReplacementEffect re = makeEtbCounter(sb.toString(), card, intrinsic);

            if (!intrinsic) {
                kws.addReplacement(re);
            }
        }
        
        // extra part for the Damage Prevention keywords
        if (keyword.startsWith("Prevent all ")) {
            boolean isCombat = false;
            boolean from = false;
            boolean to = false;

            if (keyword.equals("Prevent all combat damage that would be dealt to and dealt by CARDNAME.")) {
                isCombat = from = to = true;
            } else if (keyword.equals("Prevent all combat damage that would be dealt by CARDNAME.")) {
                isCombat = from = true;
            } else if (keyword.equals("Prevent all combat damage that would be dealt to CARDNAME.")) {
                isCombat = to = true;
            } else if (keyword.equals("Prevent all damage that would be dealt to and dealt by CARDNAME.")) {
                from = to = true;
            } else if (keyword.equals("Prevent all damage that would be dealt by CARDNAME.")) {
                from = true;
            } else if (keyword.equals("Prevent all damage that would be dealt to CARDNAME.")) {
                to = true;
            }

            String rep = "Event$ DamageDone | Prevent$ True";
            if (isCombat) {
                rep += "| IsCombat$ True";
            }
            rep += "| Secondary$ True | TiedToKeyword$ " + keyword + " | Description$ " + keyword;

            if (from) {
                String fromRep = rep + " | ValidSource$ Card.Self";
                ReplacementEffect re = ReplacementHandler.parseReplacement(fromRep, card, intrinsic);
                ReplacementEffect cardre = card.addReplacementEffect(re);

                if (!intrinsic) {
                    kws.addReplacement(cardre);
                }
            }
            if (to) {
                String toRep = rep + " | ValidTarget$ Card.Self";
                ReplacementEffect re = ReplacementHandler.parseReplacement(toRep, card, intrinsic);
                ReplacementEffect cardre = card.addReplacementEffect(re);

                if (!intrinsic) {
                    kws.addReplacement(cardre);
                }
            }
        }
        
        // No finish yet, need card updates
        if (keyword.startsWith("PreventAllDamageBy") && keyword.contains(":")) {
            final String[] k = keyword.split(":");
            String rep = "Event$ DamageDone | Prevent$ True | ValidTarget$ Card.Self | ValidSource$ " + k[1];
            rep += "| Description$ ";
            if (k.length > 2) {
                rep += k[2];
            } else {
                rep += "Prevent all damage that would be dealt to CARDNAME by " + k[1];
            }
            
            ReplacementEffect re = ReplacementHandler.parseReplacement(rep, card, intrinsic);
            ReplacementEffect cardre = card.addReplacementEffect(re);

            if (!intrinsic) {
                kws.addReplacement(cardre);
            }
        }
    }

    public static void addSpellAbility(final String keyword, final Card card, final KeywordsChange kws) {
        final boolean intrinsic = kws == null;
        if (keyword.startsWith("Alternative Cost") && !card.isLand()) {
            final String[] kw = keyword.split(":");
            String costStr = kw[1];
            for (SpellAbility sa: card.getBasicSpells()) {
                final SpellAbility newSA = sa.copy();
                newSA.setBasicSpell(false);
                if (costStr.equals("ConvertedManaCost")) {
                    costStr = Integer.toString(card.getCMC());
                }
                final Cost cost = new Cost(costStr, false).add(sa.getPayCosts().copyWithNoMana());
                newSA.getMapParams().put("Secondary", "True");
                newSA.setPayCosts(cost);
                newSA.setDescription(sa.getDescription() + " (by paying " + cost.toSimpleString() + " instead of its mana cost)");
                newSA.setIntrinsic(intrinsic);

                if (!intrinsic) {
                    newSA.setTemporary(true);
                    kws.addSpellAbility(newSA);
                }
                card.addSpellAbility(newSA);
            }
        } else if (keyword.equals("Aftermath")) {
            // Aftermath does modify existing SA, and does not add new one

            // only target RightSplit of it
            final SpellAbility origSA = card.getState(CardStateName.RightSplit).getFirstAbility();
            origSA.setAftermath(true);
            origSA.getRestrictions().setZone(ZoneType.Graveyard);
            // The Exile part is done by the System itself
        } else if (keyword.startsWith("Awaken")) {
            final String[] k = keyword.split(":");
            final String counters = k[1];
            final Cost awakenCost = new Cost(k[2], false);

            final SpellAbility awakenSpell = card.getFirstSpellAbility().copy();

            final String awaken = "DB$ PutCounter | CounterType$ P1P1 | CounterNum$ "+ counters + " | "
                    + "ValidTgts$ Land.YouCtrl | TgtPrompt$ Select target land you control | Awaken$ True";
            final String animate = "DB$ Animate | Defined$ Targeted | Power$ 0 | Toughness$ 0 | Types$"
                    + " Creature,Elemental | Permanent$ True | Keywords$ Haste";

            final AbilitySub awakenSub = (AbilitySub) AbilityFactory.getAbility(awaken, card);
            final AbilitySub animateSub = (AbilitySub) AbilityFactory.getAbility(animate, card);

            awakenSub.setSubAbility(animateSub);
            awakenSpell.appendSubAbility(awakenSub);
            String desc = "Awaken " + counters + " - " + awakenCost.toSimpleString() +
                    " (" + Keyword.getInstance(keyword).getReminderText() + ")";
            awakenSpell.setDescription(desc);
            awakenSpell.setBasicSpell(false);
            awakenSpell.setPayCosts(awakenCost);
            awakenSpell.setIntrinsic(intrinsic);

            if (!intrinsic) {
                awakenSpell.setTemporary(true);
                //sa.setOriginalHost(hostCard);
                kws.addSpellAbility(awakenSpell);
            }
            card.addSpellAbility(awakenSpell);
        } else if (keyword.startsWith("Bestow")) {
            final String[] params = keyword.split(":");
            final String cost = params[1];       

            final StringBuilder sbAttach = new StringBuilder();
            sbAttach.append("SP$ Attach | Cost$ ");
            sbAttach.append(cost);
            sbAttach.append(" | AILogic$ ").append(params.length > 2 ? params[2] : "Pump");
            sbAttach.append(" | Bestow$ True | ValidTgts$ Creature");

            final SpellAbility sa = AbilityFactory.getAbility(sbAttach.toString(), card);
            sa.setDescription("Bestow " + ManaCostParser.parse(cost) +
                    " (" + Keyword.getInstance(keyword).getReminderText() + ")");
            sa.setStackDescription("Bestow - " + card.getName());
            sa.setBasicSpell(false);
            sa.setIntrinsic(intrinsic);

            if (!intrinsic) {
                sa.setTemporary(true);
                //sa.setOriginalHost(hostCard);
                kws.addSpellAbility(sa);
            }
            card.addSpellAbility(sa);
        } else if (keyword.startsWith("Dash")) {
            final String[] k = keyword.split(":");
            final String dashString = "SP$ PermanentCreature | Cost$ " + k[1] + " | SubAbility$"
                    + " DashDelayedTrigger";
            final String dbDelayTrigger = "DB$ DelayedTrigger | Mode$ Phase | Phase$"
                    + " End of Turn | Execute$ DashReturnSelf | RememberObjects$ Self"
                    + " | TriggerDescription$ Return CARDNAME from the battlefield to" + " its owner's hand.";
            final String dbReturn = "DB$ ChangeZone | Origin$ Battlefield | Destination$ Hand"
                    + " | Defined$ DelayTriggerRemembered";
            card.setSVar("DashDelayedTrigger", dbDelayTrigger);
            card.setSVar("DashReturnSelf", dbReturn);

            final SpellAbility newSA = AbilityFactory.getAbility(dashString, card);
            String desc = "Dash " + ManaCostParser.parse(k[1]) + " (" + Keyword.getInstance(keyword).getReminderText()
                    + ")";
            newSA.setStackDescription(card.getName() + " (Dash)");
            newSA.setDescription(desc);
            newSA.setBasicSpell(false);
            newSA.setDash(true);
            newSA.setIntrinsic(intrinsic);

            if (!intrinsic) {
                newSA.setTemporary(true);
                kws.addSpellAbility(newSA);
            }
            card.addSpellAbility(newSA);

        } else if (keyword.startsWith("Emerge")) {
            final String[] kw = keyword.split(":");
            String costStr = kw[1];
            final SpellAbility sa = card.getFirstSpellAbility();

            final SpellAbility newSA = sa.copy();

            newSA.getRestrictions().setIsPresent("Creature.YouCtrl+CanBeSacrificedBy");
            newSA.getMapParams().put("Secondary", "True");
            newSA.setBasicSpell(false);
            newSA.setIsEmerge(true);
            newSA.setPayCosts(new Cost(costStr, false));
            newSA.setDescription(sa.getDescription() + " (Emerge)");
            newSA.setIntrinsic(intrinsic);

            if (!intrinsic) {
                newSA.setTemporary(true);
                kws.addSpellAbility(newSA);
            }
            card.addSpellAbility(newSA);
        } else if (keyword.startsWith("Embalm")) {
            final String[] kw = keyword.split(":");
            String costStr = kw[1];

            String effect = "AB$ CopyPermanent | Cost$ " + costStr + " ExileFromGrave<1/CARDNAME> " +
            "| ActivationZone$ Graveyard | SorcerySpeed$ True | Embalm$ True " + 
            "| PrecostDesc$ Embalm | CostDesc$ " + ManaCostParser.parse(costStr) + " | Defined$ Self " + 
            "| StackDescription$ Embalm - CARDNAME "+
            "| SpellDescription$ (" + Keyword.getInstance(keyword).getReminderText() + ")" ; 
            final SpellAbility sa = AbilityFactory.getAbility(effect, card);
            sa.setIntrinsic(intrinsic);

            if (!intrinsic) {
                sa.setTemporary(true);
                //sa.setOriginalHost(hostCard);
                kws.addSpellAbility(sa);
            }
            card.addSpellAbility(sa);
        } else if (keyword.equals("Epic")) {
            // Epic does modify existing SA, and does not add new one

            // Add the Epic effect as a subAbility
            String dbStr = "DB$ Effect | Triggers$ EpicTrigger | SVars$ EpicCopy | StaticAbilities$ EpicCantBeCast | Duration$ Permanent | Unique$ True";

            final AbilitySub newSA = (AbilitySub) AbilityFactory.getAbility(dbStr.toString(), card);

            card.setSVar("EpicCantBeCast", "Mode$ CantBeCast | ValidCard$ Card | Caster$ You | EffectZone$ Command | Description$ For the rest of the game, you can't cast spells.");
            card.setSVar("EpicTrigger", "Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ EpicCopy | TriggerDescription$ "
                    + "At the beginning of each of your upkeeps, copy " + card.toString() + " except for its epic ability.");
            card.setSVar("EpicCopy", "DB$ CopySpellAbility | Defined$ EffectSource");

            final SpellAbility origSA = card.getFirstSpellAbility();

            // append to original SA
            origSA.appendSubAbility(newSA);            
        } else if (keyword.startsWith("Equip")) {
            // Check for additional params such as preferred AI targets
            final String equipString = keyword.substring(5);
            final String[] equipExtras = equipString.contains("|") ? equipString.split("\\|", 2) : null;
            // Get cost string
            String equipCost = "";
            if (equipExtras != null) {
                equipCost = equipExtras[0].trim();
            } else {
                equipCost = equipString.trim();
            }
            // Create attach ability string
            final StringBuilder abilityStr = new StringBuilder();
            abilityStr.append("AB$ Attach | Cost$ ");
            abilityStr.append(equipCost);
            abilityStr.append(" | ValidTgts$ Creature.YouCtrl | TgtPrompt$ Select target creature you control ");
            abilityStr.append("| SorcerySpeed$ True | Equip$ True | AILogic$ Pump | IsPresent$ Card.Self+nonCreature ");
            if (equipExtras != null) {
                abilityStr.append("| ").append(equipExtras[1]).append(" ");
            }
            abilityStr.append("| PrecostDesc$ Equip ");
            Cost cost = new Cost(equipCost, true);
            if (!cost.isOnlyManaCost()) { //Something other than a mana cost
                abilityStr.append("- ");
            }
            abilityStr.append("| CostDesc$ " + cost.toSimpleString() + " ");
            abilityStr.append("| SpellDescription$ (" + cost.toSimpleString() + ": Attach to target creature you control. Equip only as a sorcery.)");
            // instantiate attach ability
            final SpellAbility newSA = AbilityFactory.getAbility(abilityStr.toString(), card);
            newSA.setIntrinsic(intrinsic);
            if (!intrinsic) {
                newSA.setTemporary(true);
                // sa.setOriginalHost(hostCard);
                kws.addSpellAbility(newSA);
            }
            card.addSpellAbility(newSA);
        } else if (keyword.startsWith("Eternalize")) {
            final String[] kw = keyword.split(":");
            String costStr = kw[1];
            Cost cost = new Cost(costStr, true);
            
            StringBuilder sb = new StringBuilder();

            sb.append("AB$ CopyPermanent | Cost$ ").append(costStr).append(" ExileFromGrave<1/CARDNAME>")
            .append(" | Defined$ Self | ActivationZone$ Graveyard | SorcerySpeed$ True | Eternalize$ True");

            sb.append(" | PrecostDesc$ Eternalize ");
            if (!cost.isOnlyManaCost()) { //Something other than a mana cost
                sb.append("- ");
            }
            // don't use SimpleString there because it does has "and" between cost i dont want that
            costStr = cost.toString();
            // but now it has ": " at the end i want to remove
            sb.append("| CostDesc$ ").append(costStr.substring(0, costStr.length() - 2));
            if (!cost.isOnlyManaCost()) {
                sb.append(".");
            }

            sb.append(" | StackDescription$ Eternalize - CARDNAME ")
            .append("| SpellDescription$ (").append(Keyword.getInstance(keyword).getReminderText()).append(")"); 
            final SpellAbility sa = AbilityFactory.getAbility(sb.toString(), card);
            sa.setIntrinsic(intrinsic);

            if (!intrinsic) {
                sa.setTemporary(true);
                //sa.setOriginalHost(hostCard);
                kws.addSpellAbility(sa);
            }
            card.addSpellAbility(sa);
        } else if (keyword.startsWith("Evoke")) {
            final String[] k = keyword.split(":");
            final SpellAbility sa = card.getFirstSpellAbility();

            final SpellAbility newSA = sa.copy();
            final Cost evokedCost = new Cost(k[1], false);

            final StringBuilder desc = new StringBuilder();
            desc.append("Evoke ").append(evokedCost.toSimpleString()).append(" (");
            desc.append(Keyword.getInstance(keyword).getReminderText());
            desc.append(")");

            newSA.setDescription(desc.toString());

            final StringBuilder sb = new StringBuilder();
            sb.append(card.getName()).append(" (Evoked)");
            newSA.setStackDescription(sb.toString());
            newSA.setBasicSpell(false);
            newSA.setPayCosts(evokedCost);
            newSA.setEvoke(true);
            newSA.setIntrinsic(intrinsic);

            if (!intrinsic) {
                newSA.setTemporary(true);
                kws.addSpellAbility(newSA);
            }

            card.addSpellAbility(newSA);
        } else if (keyword.startsWith("Haunt")) {            
            if (!card.isCreature() && intrinsic) {
                final String[] k = keyword.split(":");
                final String hauntSVarName = k[1];            

                // no nice way to get the cost
                String abString = card.getSVar(hauntSVarName).replace("DB$", "SP$");
                abString += " | Cost$ 0 | StackDescription$ SpellDescription";

                final SpellAbility sa = AbilityFactory.getAbility(abString, card);
                sa.setPayCosts(new Cost(card.getManaCost(), false));
                sa.setIntrinsic(intrinsic);
                card.addSpellAbility(sa);
            }
        } else if (keyword.startsWith("Level up")) {
            final String[] k = keyword.split(":");
            final String manacost = k[1];

            StringBuilder sb = new StringBuilder();
            sb.append("AB$ PutCounter| Cost$ ").append(manacost);
            sb.append(" | PrecostDesc$ Level Up | CostDesc$ ").append(ManaCostParser.parse(manacost));
            sb.append(" | SorcerySpeed$ True | LevelUp$ True | CounterNum$ 1 | CounterType$ LEVEL");
            if (card.hasSVar("maxLevel")) {
                final String strMaxLevel = card.getSVar("maxLevel");
                sb.append("| MaxLevel$ ").append(strMaxLevel);
            }
            sb.append(" | SpellDescription$ (").append(Keyword.getInstance(keyword).getReminderText()).append(")");

            final SpellAbility sa = AbilityFactory.getAbility(sb.toString(), card);
            sa.setIntrinsic(intrinsic);
            
            if (!intrinsic) {
                sa.setTemporary(true);
                kws.addSpellAbility(sa);
            }

            card.addSpellAbility(sa);
        } else if (keyword.startsWith("Monstrosity")) {
            final String[] k = keyword.split(":");
            final String magnitude = k[1];
            final String manacost = k[2];
            
            final String reduceCost = k.length > 3 ? k[3] : null;
            
            Set<String> references = Sets.newHashSet();
            String desc = "Monstrosity " + magnitude;

            String effect = "AB$ PutCounter | Cost$ " + manacost + " | ConditionPresent$ "
                    + "Card.Self+IsNotMonstrous | Monstrosity$ True | CounterNum$ " + magnitude
                    + " | CounterType$ P1P1 | StackDescription$ SpellDescription";
            if ("X".equals(magnitude)) {
                references.add("X");
            }
            if (reduceCost != null) {
                effect += "| ReduceCost$ " + reduceCost;
                references.add(reduceCost);
                desc += ". This ability costs {1} less to activate for each creature card in your graveyard.";
            }
            if (!references.isEmpty()) {
                effect += "| References$ " + TextUtil.join(references, ",");
            }
            
            if (card.hasSVar("MonstrosityAILogic")) {
                effect += "| AILogic$ " + card.getSVar("MonstrosityAILogic");
            }
            
            effect += "| SpellDescription$ " + desc + " (" + Keyword.getInstance(keyword).getReminderText() + ")";

            final SpellAbility sa = AbilityFactory.getAbility(effect, card);
            sa.setIntrinsic(intrinsic);

            if (!intrinsic) {
                sa.setTemporary(true);
                // sa.setOriginalHost(hostCard);
                kws.addSpellAbility(sa);
            }
            card.addSpellAbility(sa);
        } else if (keyword.startsWith("Ninjutsu")) {
            final String[] k = keyword.split(":");
            final String manacost = k[1];

            String effect = "AB$ ChangeZone | Cost$ " + manacost +
                    " Return<1/Creature.attacking+unblocked/unblocked attacker> " + 
                    "| PrecostDesc$ Ninjutsu | CostDesc$ " + ManaCostParser.parse(manacost) +
                    "| ActivationZone$ Hand | Origin$ Hand | Ninjutsu$ True " +
                    "| Destination$ Battlefield | Defined$ Self |" + 
                    " SpellDescription$ (" + Keyword.getInstance(keyword).getReminderText() + ")";

            final SpellAbility sa = AbilityFactory.getAbility(effect, card);
            sa.setIntrinsic(intrinsic);

            if (!intrinsic) {
                sa.setTemporary(true);
                //sa.setOriginalHost(hostCard);
                kws.addSpellAbility(sa);
            }
            card.addSpellAbility(sa);
        } else if (keyword.startsWith("Outlast")) {
            final String[] k = keyword.split(":");
            final String manacost = k[1];

            // Create outlast ability string
            final StringBuilder abilityStr = new StringBuilder();
            abilityStr.append("AB$ PutCounter | Cost$ ");
            abilityStr.append(manacost);
            abilityStr.append(" T | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1 ");
            abilityStr.append("| SorcerySpeed$ True | Outlast$ True ");
            abilityStr.append("| PrecostDesc$ Outlast ");
            Cost cost = new Cost(manacost, true);
            if (!cost.isOnlyManaCost()) { //Something other than a mana cost
                abilityStr.append("- ");
            }
            abilityStr.append("| CostDesc$ " + cost.toSimpleString() + " ");
            abilityStr.append("| SpellDescription$ (" + Keyword.getInstance(keyword).getReminderText() + ")");

            final SpellAbility sa = AbilityFactory.getAbility(abilityStr.toString(), card);
            sa.setIntrinsic(intrinsic);

            if (!intrinsic) {
                sa.setTemporary(true);
                //sa.setOriginalHost(hostCard);
                kws.addSpellAbility(sa);
            }
            card.addSpellAbility(sa);
        } else if (keyword.startsWith("Scavenge")) {
            final String[] k = keyword.split(":");
            final String manacost = k[1];

            String effect = "AB$ PutCounter | Cost$ " + manacost + " ExileFromGrave<1/CARDNAME> " +
                    "| ActivationZone$ Graveyard | ValidTgts$ Creature | CounterType$ P1P1 " +
                    "| CounterNum$ ScavengeX | SorcerySpeed$ True | References$ ScavengeX " + 
                    "| PrecostDesc$ Scavenge | CostDesc$ " + ManaCostParser.parse(manacost) + 
                    "| SpellDescription$ (" + Keyword.getInstance("Scavenge:" + manacost).getReminderText() + ")";

            card.setSVar("ScavengeX", "Count$CardPower");

            final SpellAbility sa = AbilityFactory.getAbility(effect, card);
            sa.setIntrinsic(intrinsic);

            if (!intrinsic) {
                sa.setTemporary(true);
                //sa.setOriginalHost(hostCard);
                kws.addSpellAbility(sa);
            }
            card.addSpellAbility(sa);
        } else if (keyword.startsWith("Surge")) {
            final String[] k = keyword.split(":");
            final Cost surgeCost = new Cost(k[1], false);
            final SpellAbility newSA = card.getFirstSpellAbility().copy();

            newSA.setPayCosts(surgeCost);
            newSA.setBasicSpell(false);
            newSA.addOptionalCost(OptionalCost.Surge);

            String desc = "Surge " + surgeCost.toSimpleString() + " (" + Keyword.getInstance(keyword).getReminderText()
                    + ")";
            newSA.setDescription(desc);
            
            newSA.setIntrinsic(intrinsic);

            if (!intrinsic) {
                newSA.setTemporary(true);
                // sa.setOriginalHost(hostCard);
                kws.addSpellAbility(newSA);
            }
            card.addSpellAbility(newSA);
        } else if (keyword.startsWith("Transmute")) {
            final String[] k = keyword.split(":");
            final String manacost = k[1];

            final String effect = "AB$ ChangeZone | Cost$ " + manacost + " Discard<1/CARDNAME>"
                    + " | PrecostDesc$ Transmute | CostDesc$ " + ManaCostParser.parse(manacost) + " | ActivationZone$ Hand"
                    + " | Origin$ Library | Destination$ Hand | ChangeType$ Card.cmcEQ" + card.getManaCost().getCMC()
                    + " | ChangeNum$ 1 | SorcerySpeed$ True | StackDescription$ SpellDescription | SpellDescription$ ("
                    + Keyword.getInstance(keyword).getReminderText() + ")";

            final SpellAbility sa = AbilityFactory.getAbility(effect, card);
            sa.setIntrinsic(intrinsic);

            if (!intrinsic) {
                sa.setTemporary(true);
                //sa.setOriginalHost(hostCard);
                kws.addSpellAbility(sa);
            }

            card.addSpellAbility(sa);
        } else if (keyword.startsWith("Unearth")) {
            final String[] k = keyword.split(":");
            final String manacost = k[1];

            String effect = "AB$ ChangeZone | Cost$ " + manacost + " | Defined$ Self" +
                    " | Origin$ Graveyard | Destination$ Battlefield | SorcerySpeed$ True" +
                    " | ActivationZone$ Graveyard | Unearth$ True | " +
                    " | PrecostDesc$ Unearth | StackDescription$ " +
                    "Unearth: Return CARDNAME to the battlefield. | SpellDescription$" +
                    " (" + Keyword.getInstance(keyword).getReminderText() + ")";
            
            final SpellAbility sa = AbilityFactory.getAbility(effect, card);
            sa.setIntrinsic(intrinsic);

            if (!intrinsic) {
                sa.setTemporary(true);
                //sa.setOriginalHost(hostCard);
                kws.addSpellAbility(sa);
            }
            card.addSpellAbility(sa);
        } else if (keyword.endsWith(" offering")) {
            final String offeringType = keyword.split(" ")[0];
            final SpellAbility sa = card.getFirstSpellAbility();

            final SpellAbility newSA = sa.copy();

            SpellAbilityRestriction sar = newSA.getRestrictions();
            sar.setIsPresent(offeringType + ".YouCtrl+CanBeSacrificedBy");
            sar.setInstantSpeed(true);

            newSA.getMapParams().put("Secondary", "True");
            newSA.setBasicSpell(false);
            newSA.setIsOffering(true);
            newSA.setPayCosts(sa.getPayCosts());
            newSA.setDescription(sa.getDescription() + " (" + offeringType + " offering)");
            newSA.setIntrinsic(intrinsic);

            if (!intrinsic) {
                newSA.setTemporary(true);
                kws.addSpellAbility(newSA);
            }
            card.addSpellAbility(newSA);
        } else if (keyword.startsWith("Crew")) {
            final String[] k = keyword.split(":");
            final String power = k[1];

            // tapXType has a special check for withTotalPower, and NEEDS it to be "+withTotalPowerGE"
            // So adding redundant YouCtrl to simplify matters even though its unnecessary
            String effect = "AB$ Animate | Cost$ tapXType<Any/Creature.YouCtrl+withTotalPowerGE" + power +
                    "> | CostDesc$ Crew " + power + " (Tap any number of creatures you control with total power " + power +
                    " or more: | Crew$ True | Secondary$ True | Defined$ Self | Types$ Creature,Artifact | OverwriteTypes$ True | " +
                    "KeepSubtypes$ True | KeepSupertypes$ True | SpellDescription$ CARDNAME becomes an artifact creature until end of turn.)";

            final SpellAbility sa = AbilityFactory.getAbility(effect, card);
            sa.setIntrinsic(intrinsic);

            if (!intrinsic) {
                sa.setTemporary(true);
                //sa.setOriginalHost(hostCard);
                kws.addSpellAbility(sa);
            }
            card.addSpellAbility(sa);
        } else if (keyword.startsWith("Cycling")) {
            final String[] k = keyword.split(":");
            final String manacost = k[1];

            StringBuilder sb = new StringBuilder();
            sb.append("AB$ Draw | Cost$ ");
            sb.append(manacost);
            sb.append(" Discard<1/CARDNAME> | ActivationZone$ Hand | PrecostDesc$ Cycling | CostDesc$ ");
            sb.append(ManaCostParser.parse(manacost));
            sb.append("| SpellDescription$ (").append(Keyword.getInstance(keyword).getReminderText()).append(")");

            SpellAbility sa = AbilityFactory.getAbility(sb.toString(), card);
            sa.setIsCycling(true);
            sa.setIntrinsic(intrinsic);

            if (!intrinsic) {
                sa.setTemporary(true);
                //sa.setOriginalHost(hostCard);
                kws.addSpellAbility(sa);
            }
            card.addSpellAbility(sa);
        } else if (keyword.startsWith("TypeCycling")) {
            final String[] k = keyword.split(":");
            final String type = k[1];
            final String manacost = k[2];

            StringBuilder sb = new StringBuilder();
            sb.append("AB$ ChangeZone | Cost$ ").append(manacost);

            String desc = type;
            if (type.equals("Basic")) {
                desc = "Basic land";
            }

            sb.append(" Discard<1/CARDNAME> | ActivationZone$ Hand | PrecostDesc$ ").append(desc).append("cycling ");
            sb.append(" | CostDesc$ ").append(ManaCostParser.parse(manacost));
            sb.append("| Origin$ Library | Destination$ Hand |");
            sb.append("ChangeType$ ").append(type);
            // no Keyword.getReminderText, it does not work yet
            sb.append(" | SpellDescription$ (Search your library for a ").append(desc).append(" card, reveal it,");
            sb.append(" and put it into your hand. Then shuffle your library.)");

            SpellAbility sa = AbilityFactory.getAbility(sb.toString(), card);
            sa.setIsCycling(true);
            sa.setIntrinsic(intrinsic);

            if (!intrinsic) {
                sa.setTemporary(true);
                //sa.setOriginalHost(hostCard);
                kws.addSpellAbility(sa);
            }
            card.addSpellAbility(sa);
        }
    }

    public static void addStaticAbility(final String keyword, final Card card, final KeywordsChange kws) {
        final boolean intrinsic = kws == null;
        String effect = null;

        if (keyword.startsWith("Absorb")) {
            final String[] k = keyword.split(":");
            final String n = k[1];

            StringBuilder sb = new StringBuilder();
            sb.append("Mode$ PreventDamage | Target$ Card.Self | Amount$ ");
            sb.append(n).append("| Secondary$ True | Description$ Absorb ").append(n);
            sb.append(" (").append(Keyword.getInstance(keyword).getReminderText()).append(")");
            effect = sb.toString();
        } else if (keyword.equals("Changeling")) {
            effect = "Mode$ Continuous | EffectZone$ All | Affected$ Card.Self" +
                    " | CharacteristicDefining$ True | AddType$ AllCreatureTypes | Secondary$ True" +
                    " | Description$ Changeling (" + Keyword.getInstance(keyword).getReminderText() + ")";
        } else if (keyword.equals("Cipher")) {
            StringBuilder sb = new StringBuilder();
            sb.append("Mode$ Continuous | EffectZone$ Exile | Affected$ Card.EncodedWithSource");
            sb.append(" | AddTrigger$ CipherTrigger");
            sb.append(" | Description$ Cipher (" + Keyword.getInstance(keyword).getReminderText() + ")");

            effect = sb.toString();

            sb = new StringBuilder();

            sb.append("Mode$ DamageDone | ValidSource$ Card.Self | ValidTarget$ Player | Execute$ PlayEncoded");
            sb.append(" | CombatDamage$ True | OptionalDecider$ You | TriggerDescription$ ");
            sb.append("Whenever CARDNAME deals combat damage to a player, its controller may cast a copy of ");
            sb.append(card.getName()).append(" without paying its mana cost.");

            String trig = sb.toString();

            String ab = "DB$ Play | Defined$ OriginalHost | WithoutManaCost$ True | CopyCard$ True";

            card.setSVar("CipherTrigger", trig);
            card.setSVar("PlayEncoded", ab);
        } else if (keyword.equals("Devoid")) {
            effect = "Mode$ Continuous | EffectZone$ All | Affected$ Card.Self" +
                    " | CharacteristicDefining$ True | SetColor$ Colorless | Secondary$ True" +
                    " | Description$ Devoid (" + Keyword.getInstance(keyword).getReminderText() + ")";
        } else if (keyword.startsWith("Escalate")) {
            final String[] k = keyword.split(":");
            final String manacost = k[1];
            final Cost cost = new Cost(manacost, false);

            StringBuilder sb = new StringBuilder("Escalate ");
            if (!cost.isOnlyManaCost()) {
                sb.append("- ");
            }
            sb.append(cost.toSimpleString());

            effect = "Mode$ RaiseCost | ValidCard$ Card.Self | Type$ Spell | Amount$ Escalate | Cost$ "+ manacost +" | EffectZone$ All" +
                    " | Description$ " + sb.toString() + " (" + Keyword.getInstance(keyword).getReminderText() + ")";
        } else if (keyword.startsWith("Strive")) {
            final String[] k = keyword.split(":");
            final String manacost = k[1];

            effect = "Mode$ RaiseCost | ValidCard$ Card.Self | Type$ Spell | Amount$ Strive | Cost$ "+ manacost +" | EffectZone$ All" +
                    " | Description$ Strive - " + Keyword.getInstance(keyword).getReminderText();
        } else if (keyword.equals("Unleash")) {
            effect = "Mode$ Continuous | Affected$ Card.Self+counters_GE1_P1P1 | AddHiddenKeyword$ CARDNAME can't block.";
        } else if (keyword.equals("Undaunted")) {
            effect = "Mode$ ReduceCost | ValidCard$ Card.Self | Type$ Spell | Amount$ Undaunted | EffectZone$ All" +
                    " | Description$ Undaunted (" + Keyword.getInstance(keyword).getReminderText() + ")";
        }

        if (effect != null) {
            StaticAbility st = card.addStaticAbility(effect);
            st.setIntrinsic(intrinsic);
            if (!intrinsic) {
                kws.addStaticAbility(st);
            }
        }
    }

    /**
     * TODO: Write javadoc for this method.
     * @param card
     * @param altCost
     * @param sa
     * @return 
     */
    private static SpellAbility makeAltCostAbility(final Card card, final String altCost, final SpellAbility sa) {
        final Map<String, String> params = AbilityFactory.getMapParams(altCost);

        final SpellAbility altCostSA = sa.copy();
        final Cost abCost = new Cost(params.get("Cost"), altCostSA.isAbility());
        altCostSA.setPayCosts(abCost);
        altCostSA.setBasicSpell(false);
        altCostSA.addOptionalCost(OptionalCost.AltCost);

        final SpellAbilityRestriction restriction = new SpellAbilityRestriction();
        restriction.setRestrictions(params);
        if (!params.containsKey("ActivationZone")) {
            restriction.setZone(ZoneType.Hand);
        }
        altCostSA.setRestrictions(restriction);

        final String costDescription = params.containsKey("Description") ? params.get("Description") 
                : String.format("You may %s rather than pay %s's mana cost.", abCost.toStringAlt(), card.getName());
        
        altCostSA.setDescription(costDescription);
        if (params.containsKey("References")) {
            for (String svar : params.get("References").split(",")) {
                altCostSA.setSVar(svar, card.getSVar(svar));
            }
        }
        return altCostSA;
    }

    private static final Map<String,String> emptyMap = Maps.newTreeMap();
    public static SpellAbility setupETBReplacementAbility(SpellAbility sa) {
        AbilitySub as = new AbilitySub(ApiType.InternalEtbReplacement, sa.getHostCard(), null, emptyMap);
        sa.appendSubAbility(as);
        return as;
        // ETBReplacementMove(sa.getHostCard(), null));
    }

    /**
     * <p>
     * hasKeyword.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @param k
     *            a {@link java.lang.String} object.
     * @return a int.
     */
    public static final int hasKeyword(final Card c, final String k) {
        return hasKeyword(c, k, 0);
    }

    /**
     * <p>
     * hasKeyword.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @param k
     *            a {@link java.lang.String} object.
     * @param startPos
     *            a int.
     * @return a int.
     */
    private static final int hasKeyword(final Card c, final String k, final int startPos) {
        final List<String> a = c.getKeywords();
        for (int i = startPos; i < a.size(); i++) {
            if (a.get(i).startsWith(k)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * <p>
     * parseKeywords.
     * </p>
     * Pulling out the parsing of keywords so it can be used by the token
     * generator
     * 
     * @param card
     *            a {@link forge.game.card.Card} object.
     * @param cardName
     *            a {@link java.lang.String} object.
     * 
     */
    public static final void parseKeywords(final Card card, final String cardName) {
        if (hasKeyword(card, "Sunburst") != -1) {
            final GameCommand sunburstCIP = new GameCommand() {
                private static final long serialVersionUID = 1489845860231758299L;

                @Override
                public void run() {
                    if (card.isCreature()) {
                        card.addCounter(CounterType.P1P1, card.getSunburstValue(), card, true);
                    } else {
                        card.addCounter(CounterType.CHARGE, card.getSunburstValue(), card, true);
                    }

                }
            };

            final GameCommand sunburstLP = new GameCommand() {
                private static final long serialVersionUID = -7564420917490677427L;

                @Override
                public void run() {
                    card.setSunburstValue(0);
                }
            };

            card.addComesIntoPlayCommand(sunburstCIP);
            card.addLeavesPlayCommand(sunburstLP);
        }

        if (hasKeyword(card, "Devoid") != -1) {
            addStaticAbility("Devoid", card, null);
        }

        if (hasKeyword(card, "Morph") != -1) {
            final int n = hasKeyword(card, "Morph");
            if (n != -1) {

                final String parse = card.getKeywords().get(n).toString();
                Map<String, String> sVars = card.getSVars();

                final String[] k = parse.split(":");

                card.addSpellAbility(abilityMorphDown(card));

                card.setState(CardStateName.FaceDown, false);

                card.addSpellAbility(abilityMorphUp(card, k[1], false));
                card.setSVars(sVars); // for Warbreak Trumpeter.

                card.setState(CardStateName.Original, false);
            }

        } // Morph
        if (hasKeyword(card, "Megamorph") != -1) {
            final int n = hasKeyword(card, "Megamorph");
            if (n != -1) {

                final String parse = card.getKeywords().get(n).toString();
                Map<String, String> sVars = card.getSVars();

                final String[] k = parse.split(":");

                card.addSpellAbility(abilityMorphDown(card));

                card.setState(CardStateName.FaceDown, false);

                card.addSpellAbility(abilityMorphUp(card, k[1], true));
                card.setSVars(sVars);
                card.setState(CardStateName.Original, false);
            }
        } // Megamorph

        final int madness = hasKeyword(card, "Madness");
        if (madness != -1) {
            final String parse = card.getKeywords().get(madness).toString();

            addReplacementEffect(parse, card, null);
            addTriggerAbility(parse, card, null);
        } // madness

        if (hasKeyword(card, "Devour") != -1) {
            final int n = hasKeyword(card, "Devour");
            addReplacementEffect(card.getKeywords().get(n), card, null);
        } // Devour

        /*
         * WARNING: must keep this keyword processing before etbCounter keyword
         * processing.
         */

        final int bloodthirst = hasKeyword(card, "Bloodthirst");
        if (bloodthirst != -1) {
            addReplacementEffect(card.getKeywords().get(bloodthirst), card, null);
        } // bloodthirst

        final int storm = card.getAmountOfKeyword("Storm");
        for (int i = 0; i < storm; i++) {
            final StringBuilder trigScript = new StringBuilder(
                    "Mode$ SpellCast | ValidCard$ Card.Self | Execute$ Storm "
                            + "| TriggerDescription$ Storm (When you cast this spell, "
                            + "copy it for each spell cast before it this turn.)");

            card.setSVar("Storm", "AB$ CopySpellAbility | Cost$ 0 | Defined$ TriggeredSpellAbility | Amount$ StormCount | References$ StormCount");
            card.setSVar("StormCount", "TriggerCount$CurrentStormCount/Minus.1");
            final Trigger stormTrigger = TriggerHandler.parseTrigger(trigScript.toString(), card, true);

            card.addTrigger(stormTrigger);
        } // Storm

        for (int i = 0; i < card.getAmountOfKeyword("Cascade"); i++) {
            addTriggerAbility("Cascade", card, null);
        } // Cascade

        if (hasKeyword(card, "Recover") != -1) {
            final String recoverCost = card.getKeywords().get(card.getKeywordPosition("Recover")).split(":")[1];
            final String abStr = "AB$ ChangeZone | Cost$ 0 | Defined$ Self"
                        + " | Origin$ Graveyard | Destination$ Hand | UnlessCost$ "
                    + recoverCost + " | UnlessPayer$ You | UnlessSwitched$ True"
                    + " | UnlessResolveSubs$ WhenNotPaid | SubAbility$ RecoverExile";
            card.setSVar("RecoverTrig", abStr);
            card.setSVar("RecoverExile", "DB$ ChangeZone | Defined$ Self"
                        + " | Origin$ Graveyard | Destination$ Exile");
            String trigObject = card.isCreature() ? "Creature.Other+YouOwn" : "Creature.YouOwn";
            String trigArticle = card.isCreature() ? "another" : "a";
            String trigStr = "Mode$ ChangesZone | ValidCard$ " + trigObject
                        + " | Origin$ Battlefield | Destination$ Graveyard | "
                        + "TriggerZones$ Graveyard | Execute$ RecoverTrig | "
                        + "TriggerDescription$ When " + trigArticle + " creature is "
                        + "put into your graveyard from the battlefield, you "
                        + "may pay " + recoverCost + ". If you do, return "
                        + "CARDNAME from your graveyard to your hand. Otherwise,"
                        + " exile CARDNAME. | Secondary$ True";
            final Trigger myTrigger = TriggerHandler.parseTrigger(trigStr, card, true);
            card.addTrigger(myTrigger);
        } // Recover

        int ripplePos = hasKeyword(card, "Ripple");
        while (ripplePos != -1) {
            final int n = ripplePos;
            final String parse = card.getKeywords().get(n);
            final String[] k = parse.split(":");
            final int num = Integer.parseInt(k[1]);
            final String triggerSvar = "DBRipple";

            final String actualTrigger = "Mode$ SpellCast | ValidCard$ Card.Self | " +
                    "Execute$ " + triggerSvar + " | Secondary$ True | TriggerDescription$" +
                    " Ripple " + num + " - CARDNAME | OptionalDecider$ You";
            final String abString = "AB$ Dig | Cost$ 0 | NoMove$ True | DigNum$ " + num +
                    " | Reveal$ True | RememberRevealed$ True | SubAbility$ DBCastRipple";
            final String dbCast = "DB$ Play | Valid$ Card.IsRemembered+sameName | " +
                    "ValidZone$ Library | WithoutManaCost$ True | Optional$ True | " +
                    "Amount$ All | SubAbility$ RippleMoveToBottom";

            card.setSVar(triggerSvar.toString(), abString);
            card.setSVar("DBCastRipple", dbCast);
            card.setSVar("RippleMoveToBottom", "DB$ ChangeZoneAll | ChangeType$ " +
                    "Card.IsRemembered | Origin$ Library | Destination$ Library | " +
                    "LibraryPosition$ -1 | SubAbility$ RippleCleanup");
            card.setSVar("RippleCleanup", "DB$ Cleanup | ClearRemembered$ True");

            final Trigger parsedTrigger = TriggerHandler.parseTrigger(actualTrigger, card, true);
            card.addTrigger(parsedTrigger);
            
            ripplePos = hasKeyword(card, "Ripple", n + 1);
        } // Ripple

        for (int i = 0; i < card.getAmountOfKeyword("Dethrone"); i++) {
            addTriggerAbility("Dethrone", card, null);
        } // Dethrone
    }

    public final static void refreshTotemArmor(Card c) {
        boolean hasKw = c.hasKeyword("Totem armor");

        CardState state = c.getCurrentState();
        FCollectionView<ReplacementEffect> res = state.getReplacementEffects();
        for (int ix = 0; ix < res.size(); ix++) {
            ReplacementEffect re = res.get(ix);
            if (re.getMapParams().containsKey("TotemArmor")) {
                if (hasKw) { return; } // has re and kw - nothing to do here
                state.removeReplacementEffect(re);
                ix--;
            }
        }

        if (hasKw) { 
            ReplacementEffect re = ReplacementHandler.parseReplacement("Event$ Destroy | ActiveZones$ Battlefield | ValidCard$ Card.EnchantedBy | ReplaceWith$ RegenTA | Secondary$ True | TotemArmor$ True | Description$ Totem armor - " + c, c, true);
            c.getSVars().put("RegenTA", "AB$ DealDamage | Cost$ 0 | Defined$ ReplacedCard | Remove$ All | SubAbility$ DestroyMe");
            c.getSVars().put("DestroyMe", "DB$ Destroy | Defined$ Self");
            state.addReplacementEffect(re);
        }
    }
}
