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
package forge.game.combat;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import forge.card.CardType;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GlobalRuleChange;
import forge.game.card.*;
import forge.game.cost.Cost;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController.ManaPaymentPurpose;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.Expressions;
import forge.util.TextUtil;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;
import forge.util.maps.MapToAmount;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * Static class containing utility methods related to combat.
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class CombatUtil {

    public static FCollectionView<GameEntity> getAllPossibleDefenders(final Player playerWhoAttacks) {
        final FCollection<GameEntity> defenders = new FCollection<GameEntity>();
        for (final Player defender : playerWhoAttacks.getOpponents()) {
            defenders.add(defender);
            final CardCollection planeswalkers = CardLists.filter(defender.getCardsIn(ZoneType.Battlefield), CardPredicates.Presets.PLANESWALKERS);
            defenders.addAll(planeswalkers);
        }
        return defenders;
    }

    // ////////////////////////////////////
    // ////////// ATTACK METHODS //////////
    // ////////////////////////////////////

    public static boolean validateAttackers(final Combat combat) {
        final AttackConstraints constraints = combat.getAttackConstraints();
        final Pair<Map<Card, GameEntity>, Integer> bestAttack = constraints.getLegalAttackers();
        final int myViolations = constraints.countViolations(combat.getAttackersAndDefenders());
        if (myViolations == -1) {
            return false;
        }
        return myViolations <= bestAttack.getRight().intValue();
    }

    /**
     * <p>
     * Check whether a player should be given the chance to attack this combat.
     * </p>
     * 
     * @param p
     *            a {@link Player}.
     * @return {@code true} if and only if the player controls any creatures and
     *         has any opponents or planeswalkers controlled by opponents to
     *         attack.
     */
    public static boolean canAttack(final Player p) {
        final CardCollection possibleAttackers = getPossibleAttackers(p);
        return !possibleAttackers.isEmpty();
    }

    /**
     * Obtain a {@link CardCollection} of all creatures a {@link Player}
     * controls that could attack any possible defending {@link GameEntity}.
     * Note that this only performs primitive checks (see
     * {@link #canAttack(Card)}).
     * 
     * @param p
     *            the attacking {@link Player}.
     * @return a {@link CardCollection}.
     */
    public static CardCollection getPossibleAttackers(final Player p) {
        return CardLists.filter(p.getCreaturesInPlay(), new Predicate<Card>() {
            @Override
            public boolean apply(final Card attacker) {
                return canAttack(attacker);
            }
        });
    }

    /**
     * Check whether a {@link Card} can attack any {@link GameEntity} that's legal for its controller to attack.
     * @param attacker
     *            the attacking {@link Card}.
     * @return a boolean.
     * @see #canAttack(Card, GameEntity)
     */
    public static boolean canAttack(final Card attacker) {
        return Iterables.any(getAllPossibleDefenders(attacker.getController()), new Predicate<GameEntity>() {
            @Override
            public boolean apply(final GameEntity defender) {
                return canAttack(attacker, defender);
            }
        });
    }

    /**
     * <p>
     * Check whether a {@link Card} is affected by any <i>attacking
     * restrictions</i>. This is <b>not</b> the case if all of the following are
     * true:
     * <ul>
     * <li>It's a creature.</li>
     * <li>It's untapped.</li>
     * <li>It's not phased out.</li>
     * <li>It's not summoning sick.</li>
     * <li>It has no abilities or keywords that prevent it from attacking.</li>
     * <li>It is not affected by any static abilities that prevent it from
     * attacking.</li>
     * </ul>
     * </p>
     * <p>
     * This method doesn't check effects related to other creatures attacking
     * </p>
     * <p>
     * Note that a creature affected by any attacking restrictions may never be
     * declared as an attacker.
     * </p>
     * 
     * @param attacker
     *            the attacking {@link Card}.
     * @param defender
     *            the defending {@link GameEntity}.
     * @return a boolean.
     */
    public static boolean canAttack(final Card attacker, final GameEntity defender) {
        return canAttack(attacker, defender, false);
    }
    public static boolean canAttackNextTurn(final Card attacker, final GameEntity defender) {
        return canAttack(attacker, defender, true);
    }

    private static boolean canAttack(final Card attacker, final GameEntity defender, final boolean forNextTurn) {
        final Game game = attacker.getGame();

        // Basic checks (unless is for next turn)
        if (!forNextTurn && (
                   !attacker.isCreature()
                || attacker.isTapped() || attacker.isPhasedOut()
                || (attacker.hasSickness() && !attacker.hasKeyword("CARDNAME can attack as though it had haste.")
                		&& !attacker.getController().getController().canPlayUnlimited())
                || game.getPhaseHandler().getPhase().isAfter(PhaseType.COMBAT_DECLARE_ATTACKERS))) {
            return false;
        }

        // Goad logic
        // a goaded creature does need to attack a player which does not goaded her
        // or if not possible a planeswalker or a player which does goaded her
        if (attacker.isGoaded()) {
            final boolean goadedByDefender = defender instanceof Player && attacker.isGoadedBy((Player) defender);
            // attacker got goaded by defender or defender is not player
            if (goadedByDefender || !(defender instanceof Player)) {
                for (GameEntity ge : getAllPossibleDefenders(attacker.getController())) {
                    if (!defender.equals(ge) && ge instanceof Player) {
                        // found a player which does not goad that creature
                        // and creature can attack this player or planeswalker
                        if (!attacker.isGoadedBy((Player) ge) && canAttack(attacker, ge)) {
                            return false;
                        }
                    }
                }
            }
        }

        // Keywords
        final boolean canAttackWithDefender = attacker.hasKeyword("CARDNAME can attack as though it didn't have defender.");
        for (final KeywordInterface keyword : attacker.getKeywords()) {
            switch (keyword.getOriginal()) {
            case "CARDNAME can't attack.":
            case "CARDNAME can't attack or block.":
                return false;
            case "CARDNAME can't attack if you cast a spell this turn.":
                if (attacker.getController().getSpellsCastThisTurn() > 0) {
                    return false;
                }
                break;
            case "Defender":
                if (!canAttackWithDefender) {
                    return false;
                }
                break;
            case "CARDNAME can't attack during extra turns.":
                if (game.getPhaseHandler().getPlayerTurn().isPlayingExtraTurn()) {
                    return false;
                }
                break;
            }
        }

        // CantAttack static abilities
        for (final Card ca : game.getCardsIn(ZoneType.listValueOf("Battlefield,Command"))) {
            final FCollectionView<StaticAbility> staticAbilities = ca.getStaticAbilities();
            for (final StaticAbility stAb : staticAbilities) {
                if (stAb.applyAbility("CantAttack", attacker, defender)) {
                    return false;
                }
            }
        }

        return true;
    } // canAttack(Card, GameEntity)

    /**
     * <p>
     * checkPropagandaEffects.
     * </p>
     * 
     * @param attacker
     *            a {@link forge.game.card.Card} object.
     */
    public static boolean checkPropagandaEffects(final Game game, final Card attacker, final Combat combat) {
        final Cost attackCost = getAttackCost(game, attacker,  combat.getDefenderByAttacker(attacker));
        if (attackCost == null) {
            return true;
        }

        // Not a great solution, but prevents a crash by passing a fake SA for Propaganda payments
        // If there's a better way of handling this somewhere deeper in the code, feel free to remove
        final SpellAbility fakeSA = new SpellAbility.EmptySa(attacker, attacker.getController());
        return attacker.getController().getController().payManaOptional(attacker, attackCost, fakeSA,
                "Pay additional cost to declare " + attacker + " an attacker", ManaPaymentPurpose.DeclareAttacker);
    }

    /**
     * Get the cost that has to be paid for a creature to attack a certain
     * defender.
     * 
     * @param game
     *            the {@link Game}.
     * @param attacker
     *            the attacking creature.
     * @param defender
     *            the defending {@link GameEntity}.
     * @return the {@link Cost} of attacking, or {@code null} if there is no
     *         cost.
     */
    static Cost getAttackCost(final Game game, final Card attacker, final GameEntity defender) {
        final Cost attackCost = new Cost(ManaCost.ZERO, true);
        boolean hasCost = false;
        // Sort abilities to apply them in proper order
        for (final Card card : game.getCardsIn(ZoneType.listValueOf("Battlefield,Command"))) {
            final FCollectionView<StaticAbility> staticAbilities = card.getStaticAbilities();
            for (final StaticAbility stAb : staticAbilities) {
                final Cost additionalCost = stAb.getAttackCost(attacker, defender);
                if (null != additionalCost) {
                    attackCost.add(additionalCost);
                    hasCost = true;
                }
            }
        }

        if (!hasCost) {
            return null;
        }
        return attackCost;
    }

    /**
     * <p>
     * This method checks triggered effects of attacking creatures, right before
     * defending player declares blockers.
     * </p>
     * @param game
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     */
    public static void checkDeclaredAttacker(final Game game, final Card c, final Combat combat) {
        // Run triggers
        final Map<String, Object> runParams = Maps.newHashMap();
        runParams.put("Attacker", c);
        final List<Card> otherAttackers = combat.getAttackers();
        otherAttackers.remove(c);
        runParams.put("OtherAttackers", otherAttackers);
        runParams.put("Attacked", combat.getDefenderByAttacker(c));
        runParams.put("DefendingPlayer", combat.getDefenderPlayerByAttacker(c));
        runParams.put("Defenders", combat.getDefenders());
        game.getTriggerHandler().runTrigger(TriggerType.Attacks, runParams, false);

        c.getDamageHistory().setCreatureAttackedThisCombat(true);
        c.getDamageHistory().clearNotAttackedSinceLastUpkeepOf();
        c.getController().setAttackedWithCreatureThisTurn(true);
        c.getController().incrementAttackersDeclaredThisTurn();
    } // checkDeclareAttackers

    /**
     * Create a {@link Map} mapping each possible attacker for the attacking
     * {@link Player} this {@link Combat} (see
     * {@link #getPossibleAttackers(Player)}) to a {@link MapToAmount}. This map
     * then maps each {@link GameEntity}, for which an attack requirement
     * exists, to the number of requirements on attacking that entity. Absent
     * entries, including an empty map, indicate no requirements exist.
     * 
     * @param combat
     *            a {@link Combat}.
     * @return a {@link Map}.
     */
    public static AttackConstraints getAllRequirements(final Combat combat) {
        return new AttackConstraints(combat);
    }

    // ///////////////////////////////////
    // ////////// BLOCK METHODS //////////
    // ///////////////////////////////////

    // can the creature block given the combat state?
    /**
     * <p>
     * canBlock.
     * </p>
     * 
     * @param blocker
     *            a {@link forge.game.card.Card} object.
     * @param combat
     *            a {@link forge.game.combat.Combat} object.
     * @return a boolean.
     */
    public static boolean canBlock(final Card blocker, final Combat combat) {
        if (blocker == null) {
            return false;
        }
        if (combat == null) {
            return CombatUtil.canBlock(blocker);
        }

        if (!CombatUtil.canBlockMoreCreatures(blocker, combat.getAttackersBlockedBy(blocker))) {
            return false;
        }
        final Game game = blocker.getGame();
        final int blockers = combat.getAllBlockers().size();

        if (blockers > 1 && game.getStaticEffects().getGlobalRuleChange(GlobalRuleChange.onlyTwoBlockers)) {
            return false;
        }

        if (blockers > 0 && game.getStaticEffects().getGlobalRuleChange(GlobalRuleChange.onlyOneBlocker)) {
            return false;
        }

        final int blockersFromOnePlayer = CardLists.filter(combat.getAllBlockers(), CardPredicates.isController(blocker.getController())).size();
        if (blockersFromOnePlayer > 0 && game.getStaticEffects().getGlobalRuleChange(GlobalRuleChange.onlyOneBlockerPerOpponent)) {
            return false;
        }

        return CombatUtil.canBlock(blocker);
    }

    // can the creature block at all?
    /**
     * <p>
     * canBlock.
     * </p>
     * 
     * @param blocker
     *            a {@link forge.game.card.Card} object.
     * @return a boolean.
     */
    public static boolean canBlock(final Card blocker) {
        return canBlock(blocker, false);
    }

    // can the creature block at all?
    /**
     * <p>
     * canBlock.
     * </p>
     * 
     * @param blocker
     *            a {@link forge.game.card.Card} object.
     * @return a boolean.
     */
    public static boolean canBlock(final Card blocker, final boolean nextTurn) {
        if (blocker == null) {
            return false;
        }

        if (!nextTurn && blocker.isTapped() && !blocker.hasKeyword("CARDNAME can block as though it were untapped.")) {
            return false;
        }

        if (blocker.hasKeyword("CARDNAME can't block.") || blocker.hasKeyword("CARDNAME can't attack or block.")
                || blocker.isPhasedOut()) {
            return false;
        }

        final List<Card> list = blocker.getController().getCreaturesInPlay();
        if (list.size() < 2 && blocker.hasKeyword("CARDNAME can't attack or block alone.")) {
            return false;
        }

        return true;
    }

    public static boolean canBlockMoreCreatures(final Card blocker, final CardCollectionView blockedBy) {
        if (blockedBy.isEmpty() || blocker.hasKeyword("CARDNAME can block any number of creatures.")) {
            return true;
        }
        int canBlockMore = numberOfAdditionalCreaturesCanBlock(blocker);
        return canBlockMore >= blockedBy.size();
    }

    public static int numberOfAdditionalCreaturesCanBlock(final Card blocker) {
        // If Wizards makes a few more of these, we should really just make a generic version
        return blocker.getAmountOfKeyword("CARDNAME can block an additional creature each combat.") +
                blocker.getAmountOfKeyword("CARDNAME can block an additional ninety-nine creatures.") * 99 +
                blocker.getAmountOfKeyword("CARDNAME can block an additional seven creatures each combat.") * 7;
    }

    // can the attacker be blocked at all?
    /**
     * <p>
     * canBeBlocked.
     * </p>
     * 
     * @param attacker
     *            a {@link forge.game.card.Card} object.
     * @param combat
     *            a {@link forge.game.combat.Combat} object.
     * @return a boolean.
     */
    public static boolean canBeBlocked(final Card attacker, final Combat combat, Player defendingPlayer) {
        if (attacker == null) {
            return true;
        }

        if ( combat != null ) {
            if (attacker.hasStartOfKeyword("CantBeBlockedByAmount GT") && !combat.getBlockers(attacker).isEmpty()) {
                return false;
            }

            // Rule 802.4a: A player can block only creatures attacking him/her or a planeswalker he/she controls
            Player attacked = combat.getDefendingPlayerRelatedTo(attacker);
            if (attacked != null && attacked != defendingPlayer) {
                return false;
            }
        }
        return CombatUtil.canBeBlocked(attacker, defendingPlayer);
    }

    // can the attacker be blocked at all?
    /**
     * <p>
     * canBeBlocked.
     * </p>
     * 
     * @param attacker
     *            a {@link forge.game.card.Card} object.
     * @return a boolean.
     */
    public static boolean canBeBlocked(final Card attacker, final Player defender) {
        if (attacker == null) {
            return true;
        }

        if (attacker.hasKeyword("Unblockable")) {
            return false;
        }

        // Landwalk
        if (isUnblockableFromLandwalk(attacker, defender)) {
            if (CardLists.getAmountOfKeyword(defender.getCreaturesInPlay(), "CARDNAME can block creatures with landwalk abilities as though they didn't have those abilities.") == 0)
            	return false;
        }

        return true;
    }

    // Cache landwalk ability strings instead of generating them each time.
    private static final String[] LANDWALK_KEYWORDS;
    private static final String[] SNOW_LANDWALK_KEYWORDS;
    private static final String[] IGNORE_LANDWALK_KEYWORDS;
    static {
        final int size = MagicColor.Constant.BASIC_LANDS.size();
        LANDWALK_KEYWORDS = new String[size];
        SNOW_LANDWALK_KEYWORDS = new String[size];
        IGNORE_LANDWALK_KEYWORDS = new String[size];
        for (int i = 0; i < size; i++) {
            final String basic = MagicColor.Constant.BASIC_LANDS.get(i);
            final String landwalk = basic + "walk";;
            LANDWALK_KEYWORDS[i] = landwalk;
            SNOW_LANDWALK_KEYWORDS[i] = "Snow " + landwalk.toLowerCase();
            IGNORE_LANDWALK_KEYWORDS[i] = "May be blocked as though it doesn't have " + landwalk + ".";
        }
    }
 
    public static boolean isUnblockableFromLandwalk(final Card attacker, final Player defendingPlayer) {
        //May be blocked as though it doesn't have landwalk. (Staff of the Ages)
        if (attacker.hasKeyword("May be blocked as though it doesn't have landwalk.")) {
            return false;
        }

        List<String> walkTypes = Lists.newArrayList();

        for (int i = 0; i < LANDWALK_KEYWORDS.length; i++) {
            final String basic = MagicColor.Constant.BASIC_LANDS.get(i);
            final String landwalk = LANDWALK_KEYWORDS[i];
            final String snowwalk = SNOW_LANDWALK_KEYWORDS[i];
            final String mayBeBlocked = IGNORE_LANDWALK_KEYWORDS[i];

            if (attacker.hasKeyword(landwalk) && !attacker.hasKeyword(mayBeBlocked)) {
                walkTypes.add(basic);
            }

            if (attacker.hasKeyword(snowwalk)) {
                walkTypes.add(basic + ".Snow");
            }
        }

        for (final KeywordInterface inst : attacker.getKeywords()) {
            String keyword = inst.getOriginal();
            if (keyword.equals("Legendary landwalk")) {
                walkTypes.add("Land.Legendary");
            } else if (keyword.equals("Desertwalk")) {
                walkTypes.add("Desert");
            } else if (keyword.equals("Nonbasic landwalk")) {
                walkTypes.add("Land.nonBasic");
            } else if (keyword.equals("Snow landwalk")) {
                walkTypes.add("Land.Snow");
            } else if (keyword.endsWith("walk")) {
                final String landtype = TextUtil.fastReplace(keyword, "walk", "");
                if (landtype.startsWith("Snow ")) {
                    walkTypes.add(landtype.substring(5) + ".Snow");
                } else if (CardType.isALandType(landtype)) {
                    if (!walkTypes.contains(landtype)) {
                        walkTypes.add(landtype);
                    }
                }
            }
        }

        if (walkTypes.isEmpty()) {
            return false;
        }

        final String valid = StringUtils.join(walkTypes, ",");
        final CardCollectionView defendingLands = defendingPlayer.getCardsIn(ZoneType.Battlefield);
        for (final Card c : defendingLands) {
            if (c.isValid(valid.split(","), defendingPlayer, attacker, null)) {
                return true;
            }
        }

        return false;
    }

    /**
     * canBlockAtLeastOne.
     * 
     * @param blocker
     *            the blocker
     * @param attackers
     *            the attackers
     * @return true, if one can be blocked
     */
    public static boolean canBlockAtLeastOne(final Card blocker, final Iterable<Card> attackers) {
        for (Card attacker : attackers) {
            if (CombatUtil.canBlock(attacker, blocker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Can be blocked.
     * 
     * @param attacker
     *            the attacker
     * @param blockers
     *            the blockers
     * @return true, if successful
     */
    public static boolean canBeBlocked(final Card attacker, final List<Card> blockers, final Combat combat) {
        int blocks = 0;
        for (final Card blocker : blockers) {
            if (CombatUtil.canBeBlocked(attacker, blocker.getController()) && CombatUtil.canBlock(attacker, blocker)) {
                blocks++;
            }
        }

        return canAttackerBeBlockedWithAmount(attacker, blocks, combat);
    }

    public static List<Card> getPotentialBestBlockers(final Card attacker, final List<Card> blockers, final Combat combat) {
        List<Card> potentialBlockers = Lists.newArrayList();
        if (blockers.isEmpty() || attacker == null) {
            return potentialBlockers;
        }

        for (final Card blocker : blockers) {
            if (CombatUtil.canBeBlocked(attacker, blocker.getController()) && CombatUtil.canBlock(attacker, blocker)) {
                potentialBlockers.add(blocker);
            }
        }

        int minBlockers = getMinNumBlockersForAttacker(attacker, blockers.get(0).getController());

        CardLists.sortByPowerDesc(potentialBlockers);

        List<Card> minBlockerList = Lists.newArrayList();
        for (int i = 0; i < minBlockers && i < potentialBlockers.size(); i++) {
            minBlockerList.add(potentialBlockers.get(i));
        }

        return minBlockerList;
    }

    /**
     * <p>
     * needsMoreBlockers.
     * </p>
     * 
     * @param attacker
     *            a {@link forge.game.card.Card} object.
     * @return a boolean.
     */
    public static int needsBlockers(final Card attacker) {

        if (attacker == null) {
            return 0;
        }
        // TODO: remove CantBeBlockedByAmount LT2
        if (attacker.hasKeyword("CantBeBlockedByAmount LT2") || attacker.hasKeyword(Keyword.MENACE)) {
            return 2;
        } else if (attacker.hasKeyword("CantBeBlockedByAmount LT3")) {
            return 3;
        } else
            return 1;
    }

    // Has the human player chosen all mandatory blocks?
    /**
     * <p>
     * finishedMandatotyBlocks.
     * </p>
     * 
     * @param combat
     *            a {@link forge.game.combat.Combat} object.
     * @return a boolean.
     */
    public static String validateBlocks(final Combat combat, final Player defending) {
        final List<Card> defendersArmy = defending.getCreaturesInPlay();
        final List<Card> attackers = combat.getAttackers();
        final List<Card> blockers = CardLists.filterControlledBy(combat.getAllBlockers(), defending);

        // if a creature does not block but should, return false
        for (final Card blocker : defendersArmy) {
            if (blocker.getMustBlockCards() != null) {
               final CardCollectionView blockedSoFar = combat.getAttackersBlockedBy(blocker);
               for (Card cardToBeBlocked : blocker.getMustBlockCards()) {
                 if (!blockedSoFar.contains(cardToBeBlocked) && CombatUtil.canBlockMoreCreatures(blocker, blockedSoFar) 
                         && combat.isAttacking(cardToBeBlocked) && CombatUtil.canBlock(cardToBeBlocked, blocker)) {
                     return TextUtil.concatWithSpace(blocker.toString(),"must still block", TextUtil.addSuffix(cardToBeBlocked.toString(),"."));
                 }
               } 
            }
            // lure effects
            if (!blockers.contains(blocker) && CombatUtil.mustBlockAnAttacker(blocker, combat)) {
                return TextUtil.concatWithSpace(blocker.toString(),"must block an attacker, but has not been assigned to block any.");
            }

            // "CARDNAME blocks each turn/combat if able."
            if (!blockers.contains(blocker) && (blocker.hasKeyword("CARDNAME blocks each turn if able.") || blocker.hasKeyword("CARDNAME blocks each combat if able."))) {
                for (final Card attacker : attackers) {
                    if (CombatUtil.canBlock(attacker, blocker, combat)) {
                        boolean must = true;
                        if (attacker.hasStartOfKeyword("CantBeBlockedByAmount LT") || attacker.hasKeyword(Keyword.MENACE)) {
                            final List<Card> possibleBlockers = Lists.newArrayList(defendersArmy);
                            possibleBlockers.remove(blocker);
                            if (!CombatUtil.canBeBlocked(attacker, possibleBlockers, combat)) {
                                must = false;
                            }
                        }
                        if (must) {
                            String unit = blocker.hasKeyword("CARDNAME blocks each combat if able.") ? "combat," : "turn,";
                            return TextUtil.concatWithSpace(blocker.toString(),"must block each", unit, "but was not assigned to block any attacker now.");
                        }
                    }
                }
            }
        }

        // Creatures that aren't allowed to block unless certain restrictions are met.
        for (final Card blocker : blockers) {
            if (blockers.size() < 2 && blocker.hasKeyword("CARDNAME can't attack or block alone.")) {
                return TextUtil.concatWithSpace(blocker.toString(),"can't block alone.");
            } else if (blockers.size() < 3 && blocker.hasKeyword("CARDNAME can't block unless at least two other creatures block.")) {
                return TextUtil.concatWithSpace(blocker.toString(),"can't block unless at least two other creatures block.");
            } else if (blocker.hasKeyword("CARDNAME can't block unless a creature with greater power also blocks.")) {
                boolean found = false;
                int power = blocker.getNetPower();
                // Note: This is O(n^2), but there shouldn't generally be many creatures with the above keyword.
                for (Card blocker2 : blockers) {
                    if (blocker2.getNetPower() > power) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return TextUtil.concatWithSpace(blocker.toString(),"can't block unless a creature with greater power also blocks.");
                }
            }
        }

        for (final Card attacker : attackers) {
            int cntBlockers = combat.getBlockers(attacker).size();
            // don't accept blocker amount for attackers with keyword defining valid blockers amount
            if (cntBlockers > 0 && !canAttackerBeBlockedWithAmount(attacker, cntBlockers, combat))
                return TextUtil.concatWithSpace(attacker.toString(),"cannot be blocked with", String.valueOf(cntBlockers), "creatures you've assigned");
        }

        return null;
    }

    // can the blocker block an attacker with a lure effect?
    /**
     * <p>
     * mustBlockAnAttacker.
     * </p>
     * 
     * @param blocker
     *            a {@link forge.game.card.Card} object.
     * @param combat
     *            a {@link forge.game.combat.Combat} object.
     * @return a boolean.
     */
    public static boolean mustBlockAnAttacker(final Card blocker, final Combat combat) {
        if (blocker == null || combat == null) {
            return false;
        }

        if (!CombatUtil.canBlock(blocker, combat)) {
            return false;
        }

        final CardCollectionView attackers = combat.getAttackers();
        final CardCollection attackersWithLure = new CardCollection();
        for (final Card attacker : attackers) {
            if (attacker.hasStartOfKeyword("All creatures able to block CARDNAME do so.")
                    || (attacker.hasStartOfKeyword("CARDNAME must be blocked if able.")
                            && combat.getBlockers(attacker).isEmpty())) {
                attackersWithLure.add(attacker);
            } else {
                for (KeywordInterface inst : attacker.getKeywords()) {
                    String keyword = inst.getOriginal();
                    // MustBeBlockedBy <valid>
                    if (keyword.startsWith("MustBeBlockedBy ")) {
                        final String valid = keyword.substring("MustBeBlockedBy ".length());
                        if (blocker.isValid(valid, null, null, null) &&
                                CardLists.getValidCardCount(combat.getBlockers(attacker), valid, null, null) == 0) {
                            attackersWithLure.add(attacker);
                            break;
                        }
                    }
                    // MustBeBlockedByAll:<valid>
                    if (keyword.startsWith("MustBeBlockedByAll")) {
                        final String valid = keyword.split(":")[1];
                        if (blocker.isValid(valid, null, null, null)) {
                            attackersWithLure.add(attacker);
                            break;
                        }
                    }
                }
            }
        }

        final Player defender = blocker.getController();
        for (final Card attacker : attackersWithLure) {
            if (CombatUtil.canBeBlocked(attacker, combat, defender) && CombatUtil.canBlock(attacker, blocker)) {
                boolean canBe = true;
                if (attacker.hasStartOfKeyword("CantBeBlockedByAmount LT") || attacker.hasKeyword(Keyword.MENACE)) {
                    final List<Card> blockers = combat.getDefenderPlayerByAttacker(attacker).getCreaturesInPlay();
                    blockers.remove(blocker);
                    if (!CombatUtil.canBeBlocked(attacker, blockers, combat)) {
                        canBe = false;
                    }
                }
                if (canBe) {
                    return true;
                }
            }
        }

        if (blocker.getMustBlockCards() != null) {
            for (final Card attacker : blocker.getMustBlockCards()) {
                if (CombatUtil.canBeBlocked(attacker, combat, defender) && CombatUtil.canBlock(attacker, blocker)
                        && combat.isAttacking(attacker)) {
                    boolean canBe = true;
                    if (attacker.hasStartOfKeyword("CantBeBlockedByAmount LT") || attacker.hasKeyword(Keyword.MENACE)) {
                        final List<Card> blockers = combat.getDefenderPlayerByAttacker(attacker).getCreaturesInPlay();
                        blockers.remove(blocker);
                        if (!CombatUtil.canBeBlocked(attacker, blockers, combat)) {
                            canBe = false;
                        }
                    }
                    if (canBe) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // can a player block with one or more creatures at the moment?
    /**
     * <p>
     * canAttack.
     * </p>
     * 
     * @param p
     *            a {@link forge.game.player} object.
     * @param combat
     *            a {@link forge.game.combat.Combat} object.
     * @return a boolean.
     */
    public static boolean canBlock(Player p, Combat combat) {
        List<Card> creatures = p.getCreaturesInPlay();
        if (creatures.isEmpty()) { return false; }

        List<Card> attackers = combat.getAttackers();
        if (attackers.isEmpty()) { return false; }

        for (Card c : creatures) {
            for (Card a : attackers) {
                if (CombatUtil.canBlock(a, c, combat)) {
                    return true;
                }
            }
        }
        return false;
    }

    // can the blocker block the attacker given the combat state?
    /**
     * <p>
     * canBlock.
     * </p>
     * 
     * @param attacker
     *            a {@link forge.game.card.Card} object.
     * @param blocker
     *            a {@link forge.game.card.Card} object.
     * @param combat
     *            a {@link forge.game.combat.Combat} object.
     * @return a boolean.
     */
    public static boolean canBlock(final Card attacker, final Card blocker, final Combat combat) {
        if (attacker == null || blocker == null) {
            return false;
        }

        if (!CombatUtil.canBlock(blocker, combat)) {
            return false;
        }
        if (!CombatUtil.canBeBlocked(attacker, combat, blocker.getController())) {
            return false;
        }
        if (combat != null && combat.isBlocking(blocker, attacker)) { // Can't block if already blocking the attacker
            return false;
        }

        boolean mustBeBlockedBy = false;
        for (KeywordInterface inst : attacker.getKeywords()) {
            String keyword = inst.getOriginal();
            // MustBeBlockedBy <valid>
            if (keyword.startsWith("MustBeBlockedBy ")) {
                final String valid = keyword.substring("MustBeBlockedBy ".length());
                if (blocker.isValid(valid, null, null, null) &&
                        CardLists.getValidCardCount(combat.getBlockers(attacker), valid, null, null) == 0) {
                    mustBeBlockedBy = true;
                    break;
                }
            }
            // MustBeBlockedByAll:<valid>
            if (keyword.startsWith("MustBeBlockedByAll")) {
                final String valid = keyword.split(":")[1];
                if (blocker.isValid(valid, null, null, null)) {
                    mustBeBlockedBy = true;
                    break;
                }
            }
        }

        // if the attacker has no lure effect, but the blocker can block another
        // attacker with lure, the blocker can't block the former
        if (!attacker.hasKeyword("All creatures able to block CARDNAME do so.")
                && !(attacker.hasKeyword("CARDNAME must be blocked if able.") && combat.getBlockers(attacker).isEmpty())
                && !(blocker.getMustBlockCards() != null && blocker.getMustBlockCards().contains(attacker))
                && !mustBeBlockedBy
                && CombatUtil.mustBlockAnAttacker(blocker, combat)) {
            return false;
        }

        return CombatUtil.canBlock(attacker, blocker);
    }

    // can the blocker block the attacker?
    /**
     * <p>
     * canBlock.
     * </p>
     * 
     * @param attacker
     *            a {@link forge.game.card.Card} object.
     * @param blocker
     *            a {@link forge.game.card.Card} object.
     * @return a boolean.
     */
    public static boolean canBlock(final Card attacker, final Card blocker) {
        return canBlock(attacker, blocker, false);
    }

    // can the blocker block the attacker?
    /**
     * <p>
     * canBlock.
     * </p>
     * 
     * @param attacker
     *            a {@link forge.game.card.Card} object.
     * @param blocker
     *            a {@link forge.game.card.Card} object.
     * @return a boolean.
     */
    public static boolean canBlock(final Card attacker, final Card blocker, final boolean nextTurn) {
        if ((attacker == null) || (blocker == null)) {
            return false;
        }

        final Game game = attacker.getGame();
        if (!CombatUtil.canBlock(blocker, nextTurn)) {
            return false;
        }
        if (!CombatUtil.canBeBlocked(attacker, blocker.getController())) {
            return false;
        }

        if (CardFactoryUtil.hasProtectionFrom(blocker, attacker)) {
            return false;
        }
        
        if (isUnblockableFromLandwalk(attacker, blocker.getController())
        		&& !blocker.hasKeyword("CARDNAME can block creatures with landwalk abilities as though they didn't have those abilities.")) {
            return false;
        }

        // rare case:
        if (blocker.hasKeyword(Keyword.SHADOW)
                && blocker.hasKeyword("CARDNAME can block creatures with shadow as though they didn't have shadow.")) {
            return false;
        }

        if (attacker.hasKeyword(Keyword.SHADOW) && !blocker.hasKeyword(Keyword.SHADOW)
                && !blocker.hasKeyword("CARDNAME can block creatures with shadow as though they didn't have shadow.")) {
            return false;
        }

        if (!attacker.hasKeyword(Keyword.SHADOW) && blocker.hasKeyword(Keyword.SHADOW)) {
            return false;
        }

        if (attacker.hasKeyword("Creatures with power less than CARDNAME's power can't block it.") && attacker.getNetPower() > blocker.getNetPower()) {
            return false;
        }

        if ((attacker.hasKeyword("Creatures with power greater than CARDNAME's power can't block it.") || attacker.hasKeyword(Keyword.SKULK))
            && attacker.getNetPower() < blocker.getNetPower()) {
            return false;
        }

        // CantBlockBy static abilities
        for (final Card ca : game.getCardsIn(ZoneType.listValueOf("Battlefield,Command"))) {
            for (final StaticAbility stAb : ca.getStaticAbilities()) {
                if (stAb.applyAbility("CantBlockBy", attacker, blocker)) {
                    return false;
                }
            }
        }

        for (KeywordInterface inst : blocker.getKeywords()) {
            String keyword = inst.getOriginal();
            if (keyword.startsWith("CantBlockCardUID")) {
                final String[] k = keyword.split("_", 2);
                if (attacker.getId() == Integer.parseInt(k[1])) {
                    return false;
                }
            } else if (keyword.startsWith("CantBlock")) {
                final String[] parse0 = keyword.split(":");
                final String[] k = parse0[0].split(" ", 2);
                final String[] restrictions = k[1].split(",");
                if (attacker.isValid(restrictions, blocker.getController(), blocker, null)) {
                    return false;
                }
            }
        }

        if (blocker.hasKeyword("CARDNAME can block only creatures with flying.") && !attacker.hasKeyword(Keyword.FLYING)) {
            return false;
        }

        if (attacker.hasKeyword(Keyword.FLYING) && !blocker.hasKeyword(Keyword.FLYING) && !blocker.hasKeyword(Keyword.REACH)) {
            boolean stillblock = false;
            for (KeywordInterface inst : blocker.getKeywords()) {
                String k = inst.getOriginal();
                if (k.startsWith("IfReach")) {
                    String n[] = k.split(":");
                    if (attacker.getType().hasCreatureType(n[1])) {
                        stillblock = true;
                        break;  
                    }
                }
            }
            if (!stillblock) {
                return false;
            }
        }

        if (attacker.hasKeyword(Keyword.HORSEMANSHIP) && !blocker.hasKeyword(Keyword.HORSEMANSHIP)) {
            return false;
        }

        // color is hardcoded there
        if (attacker.hasKeyword(Keyword.FEAR) && !blocker.isArtifact() && !blocker.isBlack()) {
            return false;
        }

        if (attacker.hasKeyword(Keyword.INTIMIDATE) && !blocker.isArtifact() && !blocker.sharesColorWith(attacker)) {
            return false;
        }

        return true;
    } // canBlock()

    public static boolean canAttackerBeBlockedWithAmount(Card attacker, int amount, Combat combat) {
        if( amount == 0 )
            return false; // no block

        List<String> restrictions = Lists.newArrayList();
        for (KeywordInterface inst : attacker.getKeywords()) {
            String kw = inst.getOriginal();
            if (kw.startsWith("CantBeBlockedByAmount")) {
                restrictions.add(TextUtil.split(kw, ' ', 2)[1]);
            }
            if (kw.equals("Menace")) {
            	restrictions.add("LT2");
            }
        }
        for ( String res : restrictions ) {
            int operand = Integer.parseInt(res.substring(2));
            String operator = res.substring(0,2);
            if (Expressions.compare(amount, operator, operand) )
                return false;
        }
        if (combat != null && attacker.hasKeyword("CARDNAME can't be blocked " +
                "unless all creatures defending player controls block it.")) {
            Player defender = combat.getDefenderPlayerByAttacker(attacker);
            if (defender == null) {
                // TODO: a better fix is needed here (to prevent a hard NPE, e.g. when the AI attacks with Tromokratis).
                System.out.println("Warning: defender was 'null' in CombatUtil::canAttackerBeBlockedWithAmount for the card " + attacker + ", attempting to deduce defender.");
                defender = combat.getDefendingPlayers().getFirst();
                if (defender != null) {
                    return amount >= defender.getCreaturesInPlay().size();
                }
                System.out.println("Warning: it was impossible to deduce the defending player in CombatUtil#canAttackerBeBlockedWithAmount, returning 'true' (safest default).");
                return true;
            }
            if (amount < defender.getCreaturesInPlay().size()) {
                return false;
            }
        }

        return true;
    }

    public static int getMinNumBlockersForAttacker(Card attacker, Player defender) {
        List<String> restrictions = Lists.newArrayList();
        for (KeywordInterface inst : attacker.getKeywords()) {
            String kw = inst.getOriginal();
            if (kw.startsWith("CantBeBlockedByAmount")) {
                restrictions.add(TextUtil.split(kw, ' ', 2)[1]);
            }
            if (kw.equals("Menace")) {
                restrictions.add("LT2");
            }
        }
        int minBlockers = 1;
        for ( String res : restrictions ) {
            int operand = Integer.parseInt(res.substring(2));
            String operator = res.substring(0, 2);
            if (operator.equals("LT") || operator.equals("GE")) {
                if (minBlockers < operand) {
                    minBlockers = operand;
                }
            } else if (operator.equals("LE") || operator.equals("GT") || operator.equals("EQ")) {
                if (minBlockers < operand + 1) {
                    minBlockers = operand + 1;
                }
            }
        }
        if (attacker.hasKeyword("CARDNAME can't be blocked unless all creatures defending player controls block it.")) {
            if (defender != null) {
                minBlockers = defender.getCreaturesInPlay().size();
            }
        }

        return minBlockers;
    }
} // end class CombatUtil
