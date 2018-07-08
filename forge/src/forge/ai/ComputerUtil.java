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
package forge.ai;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.*;
import forge.ai.ability.ProtectAi;
import forge.ai.ability.TokenAi;
import forge.card.CardType;
import forge.card.MagicColor;
import forge.card.mana.ManaCostShard;
import forge.game.CardTraitPredicates;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.*;
import forge.game.card.CardPredicates.Presets;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.*;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementLayer;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import forge.util.MyRandom;
import forge.util.TextUtil;
import forge.util.collect.FCollection;
import org.apache.commons.lang3.StringUtils;

import java.util.*;


/**
 * <p>
 * ComputerUtil class.
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class ComputerUtil {
    public static boolean handlePlayingSpellAbility(final Player ai, SpellAbility sa, final Game game) {
        return handlePlayingSpellAbility(ai, sa, game, null);
    }
    public static boolean handlePlayingSpellAbility(final Player ai, SpellAbility sa, final Game game, Runnable chooseTargets) {
        game.getStack().freezeStack();
        final Card source = sa.getHostCard();

        if (sa.isSpell() && !source.isCopiedSpell()) {
            if (source.getType().hasStringType("Arcane")) {
                sa = AbilityUtils.addSpliceEffects(sa);
                if (sa.getSplicedCards() != null && !sa.getSplicedCards().isEmpty() && ai.getController().isAI()) {
                    // we need to reconsider and retarget the SA after additional SAs have been added onto it via splice,
                    // otherwise the AI will fail to add the card to stack and that'll knock it out of the game
                    sa.resetTargets();
                    if (((PlayerControllerAi) ai.getController()).getAi().canPlaySa(sa) != AiPlayDecision.WillPlay) {
                        // for whatever reason the AI doesn't want to play the thing with the spliced subs anymore,
                        // proceeding past this point may result in an illegal play
                        return false;
                    }
                }
            }

            source.setCastSA(sa);
            sa.setLastStateBattlefield(game.getLastStateBattlefield());
            sa.setLastStateGraveyard(game.getLastStateGraveyard());
            sa.setHostCard(game.getAction().moveToStack(source, sa));
        }

        if (!sa.isCopied()) {
            sa.resetPaidHash();
        }

        if (sa.getApi() == ApiType.Charm && !sa.isWrapper()) {
            CharmEffect.makeChoices(sa);
        }
        if (chooseTargets != null) {
            chooseTargets.run();
        }
        if (sa.hasParam("Bestow")) {
            sa.getHostCard().animateBestow();
        }

        final Cost cost = sa.getPayCosts();
        // TODO: update mana color conversion for Daxos of Meletis
        if (cost == null) {
            if (ComputerUtilMana.payManaCost(ai, sa)) {
                game.getStack().addAndUnfreeze(sa);
                return true;
            }
        } else {
            final CostPayment pay = new CostPayment(cost, sa);
            if (pay.payComputerCosts(new AiCostDecision(ai, sa))) {
                game.getStack().addAndUnfreeze(sa);
                if (sa.getSplicedCards() != null && !sa.getSplicedCards().isEmpty()) {
                    game.getAction().reveal(sa.getSplicedCards(), ai, true, "Computer reveals spliced cards from ");
                }
                return true;
            }
        }
        //Should not arrive here
        System.out.println("AI failed to play " + sa.getHostCard());
        return false;
    }

    private static boolean hasDiscardHandCost(final Cost cost) {
        if (cost == null) {
            return false;
        }
        for (final CostPart part : cost.getCostParts()) {
            if (part instanceof CostDiscard) {
                final CostDiscard disc = (CostDiscard) part;
                if (disc.getType().equals("Hand")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static int counterSpellRestriction(final Player ai, final SpellAbility sa) {
        // Move this to AF?
        // Restriction Level is Based off a handful of factors

        int restrict = 0;

        final Card source = sa.getHostCard();
        final TargetRestrictions tgt = sa.getTargetRestrictions();


        // Play higher costing spells first?
        final Cost cost = sa.getPayCosts();
        // Convert cost to CMC
        // String totalMana = source.getSVar("PayX"); // + cost.getCMC()

        // Consider the costs here for relative "scoring"
        if (hasDiscardHandCost(cost)) {
            // Null Brooch aid
            restrict -= (ai.getCardsIn(ZoneType.Hand).size() * 20);
        }

        // Abilities before Spells (card advantage)
        if (sa.isAbility()) {
            restrict += 40;
        }

        // TargetValidTargeting gets biggest bonus
        if (tgt.getSAValidTargeting() != null) {
            restrict += 35;
        }

        // Unless Cost gets significant bonus + 10-Payment Amount
        final String unless = sa.getParam("UnlessCost");
        if (unless != null && !unless.endsWith(">")) {
            final int amount = AbilityUtils.calculateAmount(source, unless, sa);

            final int usableManaSources = ComputerUtilMana.getAvailableManaSources(ComputerUtil.getOpponentFor(ai), true).size();

            // If the Unless isn't enough, this should be less likely to be used
            if (amount > usableManaSources) {
                restrict += 20 - (2 * amount);
            } else {
                restrict -= (10 - (2 * amount));
            }
        }

        // Then base on Targeting Restriction
        final String[] validTgts = tgt.getValidTgts();
        if ((validTgts.length != 1) || !validTgts[0].equals("Card")) {
            restrict += 10;
        }

        // And lastly give some bonus points to least restrictive TargetType
        // (Spell,Ability,Triggered)
        final String tgtType = sa.getParam("TargetType");
        if (tgtType != null) {
            restrict -= (5 * tgtType.split(",").length);
        }
        return restrict;
    }

    // this is used for AI's counterspells
    public static final boolean playStack(final SpellAbility sa, final Player ai, final Game game) {
        sa.setActivatingPlayer(ai);
        if (!ComputerUtilCost.canPayCost(sa, ai)) 
            return false;
            
        final Card source = sa.getHostCard();
        if (sa.isSpell() && !source.isCopiedSpell()) {
            source.setCastSA(sa);
            sa.setLastStateBattlefield(game.getLastStateBattlefield());
            sa.setLastStateGraveyard(game.getLastStateGraveyard());
            sa.setHostCard(game.getAction().moveToStack(source, sa));
        }
        final Cost cost = sa.getPayCosts();
        if (cost == null) {
            ComputerUtilMana.payManaCost(ai, sa);
            game.getStack().add(sa);
        } else {
            final CostPayment pay = new CostPayment(cost, sa);
            if (pay.payComputerCosts(new AiCostDecision(ai, sa))) {
                game.getStack().add(sa);
            }
        }
        return true;
    }

    public static final void playSpellAbilityForFree(final Player ai, final SpellAbility sa) {
        final Game game = ai.getGame();
        sa.setActivatingPlayer(ai);

        final Card source = sa.getHostCard();
        if (sa.isSpell() && !source.isCopiedSpell()) {
            source.setCastSA(sa);
            sa.setLastStateBattlefield(game.getLastStateBattlefield());
            sa.setLastStateGraveyard(game.getLastStateGraveyard());
            sa.setHostCard(game.getAction().moveToStack(source, sa));
        }

        ai.getGame().getStack().add(sa);
    }

    public static final boolean playSpellAbilityWithoutPayingManaCost(final Player ai, final SpellAbility sa, final Game game) {
        final SpellAbility newSA = sa.copyWithNoManaCost();
        newSA.setActivatingPlayer(ai);

        if (!CostPayment.canPayAdditionalCosts(newSA.getPayCosts(), newSA)) {
            return false;
        }

        final Card source = newSA.getHostCard();
        if (newSA.isSpell() && !source.isCopiedSpell()) {
            source.setCastSA(newSA);
            sa.setLastStateBattlefield(game.getLastStateBattlefield());
            sa.setLastStateGraveyard(game.getLastStateGraveyard());
            newSA.setHostCard(game.getAction().moveToStack(source, sa));

            if (newSA.getApi() == ApiType.Charm && !newSA.isWrapper()) {
                CharmEffect.makeChoices(newSA);
            }
        }

        final CostPayment pay = new CostPayment(newSA.getPayCosts(), newSA);
        pay.payComputerCosts(new AiCostDecision(ai, sa));

        game.getStack().add(newSA);
        return true;
    }

    public static final void playNoStack(final Player ai, final SpellAbility sa, final Game game) {
        sa.setActivatingPlayer(ai);
        // TODO: We should really restrict what doesn't use the Stack
        if (ComputerUtilCost.canPayCost(sa, ai)) {
            final Card source = sa.getHostCard();
            if (sa.isSpell() && !source.isCopiedSpell()) {
                source.setCastSA(sa);
                sa.setLastStateBattlefield(game.getLastStateBattlefield());
                sa.setLastStateGraveyard(game.getLastStateGraveyard());
                sa.setHostCard(game.getAction().moveToStack(source, sa));
            }

            final Cost cost = sa.getPayCosts();
            if (cost == null) {
                ComputerUtilMana.payManaCost(ai, sa);
            } else {
                final CostPayment pay = new CostPayment(cost, sa);
                pay.payComputerCosts(new AiCostDecision(ai, sa));
            }

            AbilityUtils.resolve(sa);

            // destroys creatures if they have lethal damage, etc..
            //game.getAction().checkStateEffects();
        }
    }

    public static Card getCardPreference(final Player ai, final Card activate, final String pref, final CardCollection typeList) {
        final Game game = ai.getGame();
        String prefDef = "";
        if (activate != null) {
            prefDef = activate.getSVar("AIPreference");
            final String[] prefGroups = activate.getSVar("AIPreference").split("\\|");
            for (String prefGroup : prefGroups) {
                final String[] prefValid = prefGroup.trim().split("\\$");
                if (prefValid[0].equals(pref) && !prefValid[1].startsWith("Special:")) {
                    CardCollection overrideList = null;
                    if (activate.hasSVar("AIPreferenceOverride")) {
                        overrideList = CardLists.getValidCards(typeList, activate.getSVar("AIPreferenceOverride"), activate.getController(), activate, null);
                    }

                    for (String validItem : prefValid[1].split(",")) {
                        final CardCollection prefList = CardLists.getValidCards(typeList, validItem, activate.getController(), activate, null);
                        int threshold = getAIPreferenceParameter(activate, "CreatureEvalThreshold");
                        int minNeeded = getAIPreferenceParameter(activate, "MinCreaturesBelowThreshold");

                        if (threshold != -1) {
                            List<Card> toRemove = Lists.newArrayList();
                            for (Card c : prefList) {
                                if (c.isCreature()) {
                                    if (ComputerUtilCard.isUselessCreature(ai, c) || ComputerUtilCard.evaluateCreature(c) <= threshold) {
                                        continue;
                                    } else if (ComputerUtilCard.hasActiveUndyingOrPersist(c)) {
                                        continue;
                                    }
                                    toRemove.add(c);
                                }
                            }
                            prefList.removeAll(toRemove);
                        }
                        if (minNeeded != -1) {
                            if (prefList.size() < minNeeded) {
                                return null;
                            }
                        }

                        if (!prefList.isEmpty() || (overrideList != null && !overrideList.isEmpty())) {
                            return ComputerUtilCard.getWorstAI(overrideList == null ? prefList : overrideList);
                        }
                    }
                }
            }
        }
        if (pref.contains("SacCost")) {
            // search for permanents with SacMe. priority 1 is the lowest, priority 5 the highest
            for (int ip = 0; ip < 6; ip++) {
                final int priority = 6 - ip;
                if (priority == 2  && ai.isCardInPlay("Crucible of Worlds")) {
                	CardCollection landsInPlay = CardLists.getType(typeList, "Land");
                	if (!landsInPlay.isEmpty()) {
                        // Don't need more land.
                        return ComputerUtilCard.getWorstLand(landsInPlay);
                    }
                }
                final CardCollection sacMeList = CardLists.filter(typeList, new Predicate<Card>() {
                    @Override
                    public boolean apply(final Card c) {
                        return (c.hasSVar("SacMe") && (Integer.parseInt(c.getSVar("SacMe")) == priority));
                    }
                });
                if (!sacMeList.isEmpty()) {
                    CardLists.shuffle(sacMeList);
                    return sacMeList.get(0);
                }
            }

            // Sac lands
            final CardCollection landsInPlay = CardLists.getType(typeList, "Land");
            if (!landsInPlay.isEmpty()) {
                final int landsInHand = Math.min(2, CardLists.getType(ai.getCardsIn(ZoneType.Hand), "Land").size());
                final CardCollection nonLandsInHand = CardLists.getNotType(ai.getCardsIn(ZoneType.Hand), "Land");
                nonLandsInHand.addAll(ai.getCardsIn(ZoneType.Library));
                final int highestCMC = Math.max(6, Aggregates.max(nonLandsInHand, CardPredicates.Accessors.fnGetCmc));
                if (landsInPlay.size() + landsInHand >= highestCMC) {
                    // Don't need more land.
                    return ComputerUtilCard.getWorstLand(landsInPlay);
                }
            }
            
            // try everything when about to die
            if (game.getPhaseHandler().getPhase().equals(PhaseType.COMBAT_DECLARE_BLOCKERS) 
            		&& ComputerUtilCombat.lifeInSeriousDanger(ai, game.getCombat())) {
            	final CardCollection nonCreatures = CardLists.getNotType(typeList, "Creature");
            	if (!nonCreatures.isEmpty()) {
            		return ComputerUtilCard.getWorstAI(nonCreatures);
            	} else if (!typeList.isEmpty()) {
            		return ComputerUtilCard.getWorstAI(typeList);
            	}
            }
        }
        else if (pref.contains("DiscardCost")) { // search for permanents with DiscardMe
            for (int ip = 0; ip < 6; ip++) { // priority 0 is the lowest, priority 5 the highest
                final int priority = 6 - ip;
                for (Card c : typeList) {
                    if (priority == 3 && c.isLand() && ai.isCardInPlay("Crucible of Worlds")) {
                        return c;
                    }
                    if (c.hasSVar("DiscardMe") && Integer.parseInt(c.getSVar("DiscardMe")) == priority) {
                        return c;
                    }
                }
            }

            // Survival of the Fittest logic
            if (prefDef.contains("DiscardCost$Special:SurvivalOfTheFittest")) {
                return SpecialCardAi.SurvivalOfTheFittest.considerDiscardTarget(ai);
            }

            // Discard lands
            final CardCollection landsInHand = CardLists.getType(typeList, "Land");
            if (!landsInHand.isEmpty()) {
                final CardCollection landsInPlay = CardLists.getType(ai.getCardsIn(ZoneType.Battlefield), "Land");
                final CardCollection nonLandsInHand = CardLists.getNotType(ai.getCardsIn(ZoneType.Hand), "Land");
                final int highestCMC = Math.max(6, Aggregates.max(nonLandsInHand, CardPredicates.Accessors.fnGetCmc));
                if (landsInPlay.size() >= highestCMC
                        || (landsInPlay.size() + landsInHand.size() > 6 && landsInHand.size() > 1)) {
                    // Don't need more land.
                    return ComputerUtilCard.getWorstLand(landsInHand);
                }
            }
            
            // try everything when about to die
            if (activate != null && "Reality Smasher".equals(activate.getName()) ||
                    game.getPhaseHandler().getPhase().equals(PhaseType.COMBAT_DECLARE_BLOCKERS) 
            		&& ComputerUtilCombat.lifeInSeriousDanger(ai, game.getCombat())) {
            	if (!typeList.isEmpty()) {
            		return ComputerUtilCard.getWorstAI(typeList);
            	}
            }
        } else if (pref.contains("DonateMe")) {
            // search for permanents with DonateMe. priority 1 is the lowest, priority 5 the highest
            for (int ip = 0; ip < 6; ip++) {
                final int priority = 6 - ip;
                for (Card c : typeList) {
                    if (c.hasSVar("DonateMe") && Integer.parseInt(c.getSVar("DonateMe")) == priority) {
                        return c;
                    }
                }
            }
        }
        return null;
    }

    public static int getAIPreferenceParameter(final Card c, final String paramName) {
        if (!c.hasSVar("AIPreferenceParams")) {
            return -1;
        }

        String[] params = StringUtils.split(c.getSVar("AIPreferenceParams"), '|');
        for (String param : params) {
            String[] props = StringUtils.split(param, "$");
            String parName = props[0].trim();
            String parValue = props[1].trim();

            switch (parName) {
                case "CreatureEvalThreshold":
                    // Threshold of 150 is just below the level of a 1/1 mana dork or a 2/2 baseline creature with no keywords
                    if (paramName.equals(parName)) {
                        return Integer.parseInt(parValue);
                    }
                    break;
                case "MinCreaturesBelowThreshold":
                    if (paramName.equals(parName)) {
                        return Integer.parseInt(parValue);
                    }
                    break;
                default:
                    System.err.println("Warning: unknown parameter " + parName + " in AIPreferenceParams for card " + c);
                    break;
            }
        }

        return -1;
    }

    public static CardCollection chooseSacrificeType(final Player ai, final String type, final SpellAbility ability, final Card target, final int amount) {
        final Card source = ability.getHostCard();
        CardCollection typeList = CardLists.getValidCards(ai.getCardsIn(ZoneType.Battlefield), type.split(";"), source.getController(), source, null);

        typeList = CardLists.filter(typeList, CardPredicates.canBeSacrificedBy(ability));

        if ((target != null) && target.getController() == ai && typeList.contains(target)) {
            typeList.remove(target); // don't sacrifice the card we're pumping
        }

        if (typeList.size() < amount) {
            return null;
        }

        final CardCollection sacList = new CardCollection();
        int count = 0;

        while (count < amount) {
            Card prefCard = ComputerUtil.getCardPreference(ai, source, "SacCost", typeList);
            if (prefCard == null) {
                prefCard = ComputerUtilCard.getWorstAI(typeList);
            }
            if (prefCard == null) {
                return null;
            }
            sacList.add(prefCard);
            typeList.remove(prefCard);
            count++;
        }
        return sacList;
    }

    public static CardCollection chooseExileFrom(final Player ai, final ZoneType zone, final String type, final Card activate,
            final Card target, final int amount) {
        CardCollection typeList = CardLists.getValidCards(ai.getCardsIn(zone), type.split(";"), activate.getController(), activate, null);
        
        if ((target != null) && target.getController() == ai && typeList.contains(target)) {
            typeList.remove(target); // don't exile the card we're pumping
        }

        if (typeList.size() < amount) {
            return null;
        }

        CardLists.sortByPowerAsc(typeList);
        final CardCollection exileList = new CardCollection();

        for (int i = 0; i < amount; i++) {
            exileList.add(typeList.get(i));
        }
        return exileList;
    }

    public static CardCollection choosePutToLibraryFrom(final Player ai, final ZoneType zone, final String type, final Card activate,
            final Card target, final int amount) {
        CardCollection typeList = CardLists.getValidCards(ai.getCardsIn(zone), type.split(";"), activate.getController(), activate, null);
        
        if ((target != null) && target.getController() == ai && typeList.contains(target)) {
            typeList.remove(target); // don't move the card we're pumping
        }

        if (typeList.size() < amount) {
            return null;
        }

        CardLists.sortByPowerAsc(typeList);
        final CardCollection list = new CardCollection();
        
        if (zone != ZoneType.Hand) {
            Collections.reverse(typeList);
        }
        
        for (int i = 0; i < amount; i++) {
            list.add(typeList.get(i));
        }
        return list;
    }

    public static CardCollection chooseTapType(final Player ai, final String type, final Card activate, final boolean tap, final int amount) {
        return chooseTapType(ai, type, activate, tap, amount, CardCollection.EMPTY);
    }
    public static CardCollection chooseTapType(final Player ai, final String type, final Card activate, final boolean tap, final int amount, final CardCollectionView exclude) {
        CardCollection all = new CardCollection(ai.getCardsIn(ZoneType.Battlefield));
        all.removeAll(exclude);
        CardCollection typeList =
                CardLists.getValidCards(all, type.split(";"), activate.getController(), activate, null);

        // is this needed?
        typeList = CardLists.filter(typeList, Presets.UNTAPPED);

        if (tap) {
            typeList.remove(activate);
        }

        if (typeList.size() < amount) {
            return null;
        }

        CardLists.sortByPowerAsc(typeList);

        final CardCollection tapList = new CardCollection();

        for (int i = 0; i < amount; i++) {
            tapList.add(typeList.get(i));
        }
        return tapList;
    }

    public static CardCollection chooseTapTypeAccumulatePower(final Player ai, final String type, final SpellAbility sa,
            final boolean tap, final int amount, final CardCollectionView exclude) {
        // Used for Crewing vehicles, ideally we sort by useless creatures. Can't Attack/Defender
        int totalPower = 0;
        final Card activate = sa.getHostCard();

        CardCollection all = new CardCollection(ai.getCardsIn(ZoneType.Battlefield));
        all.removeAll(exclude);
        CardCollection typeList =
                CardLists.getValidCards(all, type.split(";"), activate.getController(), activate, sa);

        if (sa.hasParam("Crew")) {
            typeList = CardLists.getNotKeyword(typeList, "CARDNAME can't crew Vehicles.");
        }

        // is this needed?
        typeList = CardLists.filter(typeList, Presets.UNTAPPED);

        if (tap) {
            typeList.remove(activate);
        }
        ComputerUtilCard.sortByEvaluateCreature(typeList);
        Collections.reverse(typeList);
        
        final CardCollection tapList = new CardCollection();

        // Accumulate from "worst" creature
        for (Card next : typeList) {
            int pow = next.getNetPower();
            if (pow <= 0) {
                continue;
            }
            totalPower += pow;
            if (pow >= amount) {
                // If the power of this creature matches the totalPower needed
                // Might as well only use this creature?
                tapList.clear();
            }
            tapList.add(next);
            if (totalPower >= amount) {
                break;
            }
        }

        if (totalPower < amount) {
            return null;
        }
        return tapList;
    }

    public static CardCollection chooseUntapType(final Player ai, final String type, final Card activate, final boolean untap, final int amount) {
        CardCollection typeList =
                CardLists.getValidCards(ai.getCardsIn(ZoneType.Battlefield), type.split(";"), activate.getController(), activate, null);

        // is this needed?
        typeList = CardLists.filter(typeList, Presets.TAPPED);

        if (untap) {
            typeList.remove(activate);
        }

        if (typeList.size() < amount) {
            return null;
        }

        CardLists.sortByPowerDesc(typeList);

        final CardCollection untapList = new CardCollection();

        for (int i = 0; i < amount; i++) {
            untapList.add(typeList.get(i));
        }
        return untapList;
    }

    public static CardCollection chooseReturnType(final Player ai, final String type, final Card activate, final Card target, final int amount) {
        final CardCollection typeList =
                CardLists.getValidCards(ai.getCardsIn(ZoneType.Battlefield), type.split(";"), activate.getController(), activate, null);
        if ((target != null) && target.getController() == ai && typeList.contains(target)) {
            // don't bounce the card we're pumping
            typeList.remove(target);
        }

        if (typeList.size() < amount) {
            return new CardCollection();
        }

        CardLists.sortByPowerAsc(typeList);
        final CardCollection returnList = new CardCollection();

        for (int i = 0; i < amount; i++) {
            returnList.add(typeList.get(i));
        }
        return returnList;
    }

    public static CardCollection choosePermanentsToSacrifice(final Player ai, final CardCollectionView cardlist, final int amount, final SpellAbility source, 
            final boolean destroy, final boolean isOptional) {
        CardCollection remaining = new CardCollection(cardlist);
        final CardCollection sacrificed = new CardCollection();
        final Card host = source.getHostCard();
        final boolean considerSacLogic = "ConsiderSac".equals(source.getParam("AILogic"));
        final int considerSacThreshold = getAIPreferenceParameter(host, "CreatureEvalThreshold");

        if ("OpponentOnly".equals(source.getParam("AILogic"))) {
        	if(!source.getActivatingPlayer().isOpponentOf(ai)) {
        		return sacrificed; // sacrifice none 
        	}
        } else if ("DesecrationDemon".equals(source.getParam("AILogic"))) {
            if (!SpecialCardAi.DesecrationDemon.considerSacrificingCreature(ai, source)) {
                return sacrificed; // don't sacrifice unless in special conditions specified by DesecrationDemon AI
            }
        } else if (isOptional && source.getActivatingPlayer().isOpponentOf(ai)) {
            if ("Pillar Tombs of Aku".equals(host.getName())) {
                if (!ai.canLoseLife() || ai.cantLose()) {
                    return sacrificed; // sacrifice none
                }
            } else if (!considerSacLogic) {
                return sacrificed; // sacrifice none
            }
        }
        boolean exceptSelf = "ExceptSelf".equals(source.getParam("AILogic"));
        boolean removedSelf = false;

        if (isOptional && source.hasParam("Devour") || source.hasParam("Exploit") || considerSacLogic) {
        	if (source.hasParam("Exploit")) {
        		for (Trigger t : host.getTriggers()) {
        			if (t.getMode() == TriggerType.Exploited) {
        	            final String execute = t.getMapParams().get("Execute");
        	            if (execute == null) {
        	                continue;
        	            }
        	            final SpellAbility exSA = AbilityFactory.getAbility(host.getSVar(execute), host);

        	            exSA.setActivatingPlayer(ai);
        	            exSA.setTrigger(true);

        	            // Run non-mandatory trigger.
        	            // These checks only work if the Executing SpellAbility is an Ability_Sub.
        	            if ((exSA instanceof AbilitySub) && !SpellApiToAi.Converter.get(exSA.getApi()).doTriggerAI(ai, exSA, false)) {
        	                // AI would not run this trigger if given the chance
        	                return sacrificed;
        	            }
        			}
        		}
        	}
            remaining = CardLists.filter(remaining, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    int sacThreshold = 190;

                    if ("HeartPiercer".equals(source.getParam("SacrificeParam"))) {
                        if (c.getNetPower() == 0) {
                            return false;
                        } else if (c.getNetPower() >= ai.getOpponentsSmallestLifeTotal()) {
                            return true;
                        }
                    }

                    if ("DesecrationDemon".equals(source.getParam("AILogic"))) {
                        sacThreshold = SpecialCardAi.DesecrationDemon.getSacThreshold();
                    } else if (considerSacLogic && considerSacThreshold != -1) {
                        sacThreshold = considerSacThreshold;
                    }

                    if (c.hasSVar("SacMe") || ComputerUtilCard.evaluateCreature(c) < sacThreshold) {
                        return true;
                    }
                    
                    if (ComputerUtilCard.hasActiveUndyingOrPersist(c)) {
                        return true;
                    }
                    
                    return false;
                }
            });
        }

        final int max = Math.min(remaining.size(), amount);

        if (exceptSelf) {
            removedSelf = remaining.remove(source.getHostCard());
        }

        for (int i = 0; i < max; i++) {
            Card c = chooseCardToSacrifice(remaining, ai, destroy);
            remaining.remove(c);
            if (c != null) {
                sacrificed.add(c);
            }
        }

        if (sacrificed.isEmpty() && removedSelf) {
            sacrificed.add(source.getHostCard());
        }

        return sacrificed;
    }

    // Precondition it wants: remaining are reverse-sorted by CMC
    private static Card chooseCardToSacrifice(final CardCollection remaining, final Player ai, final boolean destroy) {
        // If somehow ("Drop of Honey") they suggest to destroy opponent's card - use the chance!
        for (Card c : remaining) { // first compare is fast, second is precise
            if (ai.isOpponentOf(c.getController()))
                return c;
        }
        
        if (destroy) {
            final CardCollection indestructibles = CardLists.getKeyword(remaining, Keyword.INDESTRUCTIBLE);
            if (!indestructibles.isEmpty()) {
                return indestructibles.get(0);
            }
        }
        for (int ip = 0; ip < 6; ip++) { // priority 0 is the lowest, priority 5 the highest
            final int priority = 6 - ip;
            for (Card card : remaining) {
                if (card.hasSVar("SacMe") && Integer.parseInt(card.getSVar("SacMe")) == priority) {
                    return card;
                }
            }
        }

        Card c = null;
        if (CardLists.getNotType(remaining, "Creature").isEmpty()) {
            c = ComputerUtilCard.getWorstCreatureAI(remaining);
        }
        else if (CardLists.getNotType(remaining, "Land").isEmpty()) {
            c = ComputerUtilCard.getWorstLand(CardLists.filter(remaining, CardPredicates.Presets.LANDS));
        }
        else {
            c = ComputerUtilCard.getWorstPermanentAI(remaining, false, false, false, false);
        }

        if (c != null && c.isEnchanted()) {
            // TODO: choose "worst" controlled enchanting Aura
            for (Card aura : c.getEnchantedBy(false)) {
                if (aura.getController().equals(c.getController()) && remaining.contains(aura)) {
                    return aura;
                }
            }
        }
        return c;
    }

    public static boolean canRegenerate(Player ai, final Card card) {
        if (!card.canBeShielded()) {
            return false;
        }

        boolean canRegen = false;
        ComputerUtilCombat.setCombatRegenTestSuppression(true); // do not check canRegenerate recursively from combat code

        final Player controller = card.getController();
        final Game game = controller.getGame();
        final CardCollectionView l = controller.getCardsIn(ZoneType.Battlefield);
        for (final Card c : l) {
            for (final SpellAbility sa : c.getSpellAbilities()) {
                // This try/catch should fix the "computer is thinking" bug
                try {

                    if (!sa.isAbility() || sa.getApi() != ApiType.Regenerate) {
                        continue; // Not a Regenerate ability
                    }
                    sa.setActivatingPlayer(controller);
                    if (!(sa.canPlay() && ComputerUtilCost.canPayCost(sa, controller))) {
                        continue; // Can't play ability
                    }

                    if (controller == ai) {
                        final Cost abCost = sa.getPayCosts();
                        if (abCost != null) {
                            if (!ComputerUtilCost.checkLifeCost(controller, abCost, c, 4, sa)) {
                                continue; // Won't play ability
                            }

                            if (!ComputerUtilCost.checkSacrificeCost(controller, abCost, c, sa)) {
                                continue; // Won't play ability
                            }

                            if (!ComputerUtilCost.checkCreatureSacrificeCost(controller, abCost, c, sa)) {
                                continue; // Won't play ability
                            }
                        }
                    }

                    final TargetRestrictions tgt = sa.getTargetRestrictions();
                    if (tgt != null) {
                        if (CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), tgt.getValidTgts(), controller, sa.getHostCard(), sa).contains(card)) {
                            canRegen = true;
                        }
                    } else if (AbilityUtils.getDefinedCards(sa.getHostCard(), sa.getParam("Defined"), sa).contains(card)) {
                        canRegen = true;
                    }

                }  catch (final Exception ex) {
                    throw new RuntimeException(TextUtil.concatNoSpace("There is an error in the card code for ", c.getName(), ":", ex.getMessage()), ex);
                } 
            }
        }

        ComputerUtilCombat.setCombatRegenTestSuppression(false);
        return canRegen;
    }

    public static int possibleDamagePrevention(final Card card) {
        int prevented = 0;

        final Player controller = card.getController();
        final Game game = controller.getGame();

        final CardCollectionView l = controller.getCardsIn(ZoneType.Battlefield);
        for (final Card c : l) {
            for (final SpellAbility sa : c.getSpellAbilities()) {
                // if SA is from AF_Counter don't add to getPlayable
                // This try/catch should fix the "computer is thinking" bug
                try {
                    if (sa.getApi() == null || !sa.isAbility()) {
                        continue;
                    }

                    if (sa.getApi() == ApiType.PreventDamage && sa.canPlay()
                            && ComputerUtilCost.canPayCost(sa, controller)) {
                        if (AbilityUtils.getDefinedCards(sa.getHostCard(), sa.getParam("Defined"), sa).contains(card)) {
                            prevented += AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("Amount"), sa);
                        }
                        final TargetRestrictions tgt = sa.getTargetRestrictions();
                        if (tgt != null) {
                            if (CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), tgt.getValidTgts(), controller, sa.getHostCard(), null).contains(card)) {
                                prevented += AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParam("Amount"), sa);
                            }

                        }
                    }
                } catch (final Exception ex) {
                    throw new RuntimeException(TextUtil.concatNoSpace("There is an error in the card code for ", c.getName(), ":", ex.getMessage()), ex);
                }
            }
        }
        return prevented;
    }

    public static boolean castPermanentInMain1(final Player ai, final SpellAbility sa) {
        final Card card = sa.getHostCard();

        if (card.hasSVar("PlayMain1")) {
        	if (card.getSVar("PlayMain1").equals("ALWAYS") || sa.getPayCosts().hasNoManaCost()) {
        		return true;
        	} else if (card.getSVar("PlayMain1").equals("OPPONENTCREATURES")) {
        		//Only play these main1 when the opponent has creatures (stealing and giving them haste)
        		if (!ai.getOpponents().getCreaturesInPlay().isEmpty()) {
        			return true;
        		}
        	} else if (!card.getController().getCreaturesInPlay().isEmpty()) {
        		return true;
        	}
        }

        // try not to cast Raid creatures in main 1 if an attack is likely
        if ("Count$AttackersDeclared".equals(card.getSVar("RaidTest")) && !card.hasKeyword(Keyword.HASTE)) {
            for (Card potentialAtkr: ai.getCreaturesInPlay()) {
                if (ComputerUtilCard.doesCreatureAttackAI(ai, potentialAtkr)) {
                    return false;
                }
            }
        }

        if (card.getManaCost().isZero()) {
        	return true;
        }

        if (card.isCreature() && !card.hasKeyword(Keyword.DEFENDER)
                && (card.hasKeyword(Keyword.HASTE) || ComputerUtil.hasACardGivingHaste(ai, true) || sa.isDash())) {
            return true;
        }
        
        if (card.hasKeyword(Keyword.EXALTED)) {
        	return true;
        }

        //cast equipments in Main1 when there are creatures to equip and no other unequipped equipment
        if (card.isEquipment()) {
            boolean playNow = false;
            for (Card c : card.getController().getCardsIn(ZoneType.Battlefield)) {
                if (c.isEquipment() && !c.isEquipping()) {
                    playNow = false;
                    break;
                }
                if (!playNow && c.isCreature() && ComputerUtilCombat.canAttackNextTurn(c) && c.canBeEquippedBy(card)) {
                    playNow = true;
                }
            }
            if (playNow) {
                return true;
            }
        }

        // get all cards the computer controls with BuffedBy
        final CardCollectionView buffed = ai.getCardsIn(ZoneType.Battlefield);
        for (Card buffedcard : buffed) {
            if (buffedcard.hasSVar("BuffedBy")) {
                final String buffedby = buffedcard.getSVar("BuffedBy");
                final String[] bffdby = buffedby.split(",");
                if (card.isValid(bffdby, buffedcard.getController(), buffedcard, sa)) {
                    return true;
                }
            }
            if (card.isEquipment() && buffedcard.isCreature() && CombatUtil.canAttack(buffedcard, ComputerUtil.getOpponentFor(ai))) {
                return true;
            }
            if (card.isCreature()) {
                if (buffedcard.hasKeyword(Keyword.SOULBOND) && !buffedcard.isPaired()) {
                    return true;
                }
                if (buffedcard.hasKeyword(Keyword.EVOLVE)) {
                    if (buffedcard.getNetPower() < card.getNetPower() || buffedcard.getNetToughness() < card.getNetToughness()) {
                        return true;
                    }
                }
            }
            if (card.hasKeyword(Keyword.SOULBOND) && buffedcard.isCreature() && !buffedcard.isPaired()) {
                return true;
            }

        } // BuffedBy

        // get all cards the human controls with AntiBuffedBy
        final CardCollectionView antibuffed = ComputerUtil.getOpponentFor(ai).getCardsIn(ZoneType.Battlefield);
        for (Card buffedcard : antibuffed) {
            if (buffedcard.hasSVar("AntiBuffedBy")) {
                final String buffedby = buffedcard.getSVar("AntiBuffedBy");
                final String[] bffdby = buffedby.split(",");
                if (card.isValid(bffdby, buffedcard.getController(), buffedcard, sa)) {
                    return true;
                }
            }
        } // AntiBuffedBy

        final CardCollectionView vengevines = ai.getCardsIn(ZoneType.Graveyard, "Vengevine");
        if (!vengevines.isEmpty()) {
            final CardCollectionView creatures = ai.getCardsIn(ZoneType.Hand);
            final CardCollection creatures2 = new CardCollection();
            for (int i = 0; i < creatures.size(); i++) {
                if (creatures.get(i).isCreature() && creatures.get(i).getManaCost().getCMC() <= 3) {
                    creatures2.add(creatures.get(i));
                }
            }
            if (((creatures2.size() + CardUtil.getThisTurnCast("Creature.YouCtrl", vengevines.get(0)).size()) > 1)
                    && card.isCreature() && card.getManaCost().getCMC() <= 3) {
                return true;
            }
        }
        return false;
    }

    /**
     * Is it OK to cast this for less than the Max Targets?
     * @param source the source Card
     * @return true if it's OK to cast this Card for less than the max targets
     */
    public static boolean shouldCastLessThanMax(final Player ai, final Card source) {
        boolean ret = true;
        if (source.getManaCost().countX() > 0) {
            // If TargetMax is MaxTgts (i.e., an "X" cost), this is fine because AI is limited by mana available.
            return ret;
        } else {
            // Otherwise, if life is possibly in danger, then this is fine.
            Combat combat = new Combat(ComputerUtil.getOpponentFor(ai));
            CardCollectionView attackers = ComputerUtil.getOpponentFor(ai).getCreaturesInPlay();
            for (Card att : attackers) {
                if (ComputerUtilCombat.canAttackNextTurn(att, ai)) {
                    combat.addAttacker(att, ComputerUtil.getOpponentFor(att.getController()));
                }
            }
            AiBlockController aiBlock = new AiBlockController(ai);
            aiBlock.assignBlockersForCombat(combat);
            if (!ComputerUtilCombat.lifeInDanger(ai, combat)) {
                // Otherwise, return false. Do not play now.
                ret = false;
            }
        }
        return ret;
    }

    /**
     * Is this discard probably worse than a random draw?
     * @param discard Card to discard
     * @return boolean
     */
    public static boolean isWorseThanDraw(final Player ai, Card discard) {
        if (discard.hasSVar("DiscardMe")) {
            return true;
        }
        
        final Game game = ai.getGame();
        final CardCollection landsInPlay = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.Presets.LANDS);
        final CardCollection landsInHand = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.Presets.LANDS);
        final CardCollection nonLandsInHand = CardLists.getNotType(ai.getCardsIn(ZoneType.Hand), "Land");
        final int highestCMC = Math.max(6, Aggregates.max(nonLandsInHand, CardPredicates.Accessors.fnGetCmc));
        final int discardCMC = discard.getCMC();
        if (discard.isLand()) {
            if (landsInPlay.size() >= highestCMC
                    || (landsInPlay.size() + landsInHand.size() > 6 && landsInHand.size() > 1)
                    || (landsInPlay.size() > 3 && nonLandsInHand.size() == 0)) {
                // Don't need more land.
                return true;
            }
        } else { //non-land
            if (discardCMC > landsInPlay.size() + landsInHand.size() + 2) {
                // not castable for some time.
                return true;
            } else if (!game.getPhaseHandler().isPlayerTurn(ai)
                    && game.getPhaseHandler().getPhase().isAfter(PhaseType.MAIN2)
                    && discardCMC > landsInPlay.size() + landsInHand.size()
                    && discardCMC > landsInPlay.size() + 1
                    && nonLandsInHand.size() > 1) {
                // not castable for at least one other turn.
                return true;
            } else if (landsInPlay.size() > 5 && discard.getCMC() <= 1
                    && !discard.hasProperty("hasXCost", ai, null, null)) {
                // Probably don't need small stuff now.
                return true;
            }
        }
        return false;
    }

    // returns true if it's better to wait until blockers are declared
    public static boolean waitForBlocking(final SpellAbility sa) {
        final Game game = sa.getActivatingPlayer().getGame();
        final PhaseHandler ph = game.getPhaseHandler();

        return (sa.getHostCard().isCreature()
                && sa.getPayCosts().hasTapCost()
                && (ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_BLOCKERS)
                        && !ph.getNextTurn().equals(sa.getActivatingPlayer()))
                && !sa.getHostCard().hasSVar("EndOfTurnLeavePlay")
                && !sa.hasParam("ActivationPhases"));
    }

    //returns true if it's better to wait until blockers are declared).
    public static boolean castSpellInMain1(final Player ai, final SpellAbility sa) {
        final Card source = sa.getHostCard();
        final SpellAbility sub = sa.getSubAbility();

        if (source != null && "ALWAYS".equals(source.getSVar("PlayMain1"))) {
            return true;
        }

        // Cipher spells
        if (sub != null) {
            final ApiType api = sub.getApi();
            if (ApiType.Encode == api && !ai.getCreaturesInPlay().isEmpty()) {
                return true;
            }
            if (ApiType.PumpAll == api && !ai.getCreaturesInPlay().isEmpty()) {
                return true;
            }
            if (ApiType.Pump == api) {
                return true;
            }
        }
        final CardCollectionView buffed = ai.getCardsIn(ZoneType.Battlefield);
        boolean checkThreshold = sa.isSpell() && !ai.hasThreshold() && !sa.getHostCard().isInZone(ZoneType.Graveyard);
        for (Card buffedCard : buffed) {
            if (buffedCard.hasSVar("BuffedBy")) {
                final String buffedby = buffedCard.getSVar("BuffedBy");
                final String[] bffdby = buffedby.split(",");
                if (source.isValid(bffdby, buffedCard.getController(), buffedCard, sa)) {
                    return true;
                }
            }
            //Fill the graveyard for Threshold
            if (checkThreshold) {
                for (StaticAbility stAb : buffedCard.getStaticAbilities()) {
                    if ("Threshold".equals(stAb.getMapParams().get("Condition"))) {
                        return true;
                    }
                }
            }
        }

        // get all cards the human controls with AntiBuffedBy
        final CardCollectionView antibuffed = ComputerUtil.getOpponentFor(ai).getCardsIn(ZoneType.Battlefield);
        for (Card buffedcard : antibuffed) {
            if (buffedcard.hasSVar("AntiBuffedBy")) {
                final String buffedby = buffedcard.getSVar("AntiBuffedBy");
                final String[] bffdby = buffedby.split(",");
                if (source.isValid(bffdby, buffedcard.getController(), buffedcard, sa)) {
                    return true;
                }
            }
        } // AntiBuffedBy
        
        if (sub != null) { 
            return castSpellInMain1(ai, sub);
        }
        
        return false;
    }

    // returns true if the AI should stop using the ability
    public static boolean preventRunAwayActivations(final SpellAbility sa) {
        int activations = sa.getRestrictions().getNumberTurnActivations();

        if (sa.isTemporary()) {
        	return MyRandom.getRandom().nextFloat() >= .95; // Abilities created by static abilities have no memory
        }

        if (activations < 10) { //10 activations per turn should still be acceptable
            return false;
        }

        return MyRandom.getRandom().nextFloat() >= Math.pow(.95, activations);
    }

    public static boolean activateForCost(SpellAbility sa, final Player ai) {
        final Cost abCost = sa.getPayCosts();
        final Card source = sa.getHostCard();
        if (abCost == null) {
            return false;
        }
        if (abCost.hasTapCost() && source.hasSVar("AITapDown")) {
        	return true;
        } else if (sa.hasParam("Planeswalker") && ai.getGame().getPhaseHandler().is(PhaseType.MAIN2)) {
        	for (final CostPart part : abCost.getCostParts()) {
        		if (part instanceof CostPutCounter) {
        			return true;
        		}
        	}
        }
        for (final CostPart part : abCost.getCostParts()) {
            if (part instanceof CostSacrifice) {
                final CostSacrifice sac = (CostSacrifice) part;
    
                final String type = sac.getType();
    
                if (type.equals("CARDNAME")) {
                    if (source.getSVar("SacMe").equals("6")) {
                        return true;
                    }
                    continue;
                }
    
                final CardCollection typeList =
                        CardLists.getValidCards(ai.getCardsIn(ZoneType.Battlefield), type.split(","), source.getController(), source, sa);
                for (Card c : typeList) {
                    if (c.getSVar("SacMe").equals("6")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean hasACardGivingHaste(final Player ai, final boolean checkOpponentCards) {
        final CardCollection all = new CardCollection(ai.getCardsIn(Lists.newArrayList(ZoneType.Battlefield, ZoneType.Command)));

        // Special for Anger
        if (!ai.getGame().isCardInPlay("Yixlid Jailer")
                && !ai.getCardsIn(ZoneType.Graveyard, "Anger").isEmpty()
                && !CardLists.getType(all, "Mountain").isEmpty()) {
            return true;
        }

        // Special for Odric
        if (ai.isCardInPlay("Odric, Lunarch Marshal")
                && !CardLists.getKeyword(all, Keyword.HASTE).isEmpty()) {
            return true;
        }

        // check for Continuous abilities that grant Haste
        for (final Card c : all) {
            for (StaticAbility stAb : c.getStaticAbilities()) {
                Map<String, String> params = stAb.getMapParams();
                if ("Continuous".equals(params.get("Mode")) && params.containsKey("AddKeyword")
                        && params.get("AddKeyword").contains("Haste")) {
                	
                    if (c.isEquipment() && c.getEquipping() == null) {
                        return true;
                    }

                    final String affected = params.get("Affected");
                    if (affected.contains("Creature.YouCtrl")
                    		|| affected.contains("Other+YouCtrl")) {
                        return true;
                    } else if (affected.contains("Creature.PairedWith") && !c.isPaired()) {
                        return true;
                    }
                }
            }

            for (Trigger t : c.getTriggers()) {
                Map<String, String> params = t.getMapParams(); 
                if (!"ChangesZone".equals(params.get("Mode"))
                		|| !"Battlefield".equals(params.get("Destination"))
                		|| !params.containsKey("ValidCard")) {
                    continue;
                }

                final String valid = params.get("ValidCard");
                if (valid.contains("Creature.YouCtrl")
                        || valid.contains("Other+YouCtrl") ) {

                    final SpellAbility sa = t.getTriggeredSA();
                    if (sa != null && sa.getApi() == ApiType.Pump && sa.hasParam("KW")
                            && sa.getParam("KW").contains("Haste")) {
                        return true;
                    }
                }
            }
        }
        
        all.addAll(ai.getCardsActivableInExternalZones(true));
        all.addAll(ai.getCardsIn(ZoneType.Hand));
    
        for (final Card c : all) {
            for (final SpellAbility sa : c.getSpellAbilities()) {
                if (sa.getApi() == ApiType.Pump && sa.hasParam("KW") && sa.getParam("KW").contains("Haste")) {
                    return true;
                }
            }
        }

        if (checkOpponentCards) {
            // Check if the opponents have any cards giving Haste to all creatures on the battlefield
            CardCollection opp = new CardCollection();
            opp.addAll(ai.getOpponents().getCardsIn(ZoneType.Battlefield));
            opp.addAll(ai.getOpponents().getCardsIn(ZoneType.Command));

            for (final Card c : opp) {
                for (StaticAbility stAb : c.getStaticAbilities()) {
                    Map<String, String> params = stAb.getMapParams();
                    if ("Continuous".equals(params.get("Mode")) && params.containsKey("AddKeyword")
                            && params.get("AddKeyword").contains("Haste")) {

                        final ArrayList<String> affected = Lists.newArrayList(params.get("Affected").split(","));
                        if (affected.contains("Creature")) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    public static boolean hasAFogEffect(final Player ai) {
        final CardCollection all = new CardCollection(ai.getCardsIn(ZoneType.Battlefield));
        
        all.addAll(ai.getCardsActivableInExternalZones(true));
        all.addAll(ai.getCardsIn(ZoneType.Hand));
    
        for (final Card c : all) {
            for (final SpellAbility sa : c.getSpellAbilities()) {
                if (sa.getApi() != ApiType.Fog) {
                    continue;
                }
                if (!ComputerUtilCost.canPayCost(sa, ai)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    public static int possibleNonCombatDamage(Player ai) {
        int damage = 0;
        final CardCollection all = new CardCollection(ai.getCardsIn(ZoneType.Battlefield));
        all.addAll(ai.getCardsActivableInExternalZones(true));
        all.addAll(CardLists.filter(ai.getCardsIn(ZoneType.Hand), Predicates.not(Presets.PERMANENTS)));
    
        for (final Card c : all) {
            for (final SpellAbility sa : c.getSpellAbilities()) {
                if (sa.getApi() != ApiType.DealDamage) {
                    continue;
                }
                final String numDam = sa.getParam("NumDmg");
                int dmg = AbilityUtils.calculateAmount(sa.getHostCard(), numDam, sa);
                if (dmg <= damage) {
                    continue;
                }
                final TargetRestrictions tgt = sa.getTargetRestrictions();
                if (tgt == null) {
                    continue;
                }
                final Player enemy = ComputerUtil.getOpponentFor(ai);
                if (!sa.canTarget(enemy)) {
                    continue;
                }
                if (!ComputerUtilCost.canPayCost(sa, ai)) {
                    continue;
                }
                damage = dmg;
            }

            // Triggered abilities
            if (c.isCreature() && c.isInZone(ZoneType.Battlefield) && CombatUtil.canAttack(c)) {
                for (final Trigger t : c.getTriggers()) {
                    if ("Attacks".equals(t.getParam("Mode")) && t.hasParam("Execute")) {
                        String exec = c.getSVar(t.getParam("Execute"));
                        if (!exec.isEmpty()) {
                            SpellAbility trigSa = AbilityFactory.getAbility(exec, c);
                            if (trigSa != null && trigSa.getApi() == ApiType.LoseLife
                                    && trigSa.getParamOrDefault("Defined", "").contains("Opponent")) {
                                trigSa.setHostCard(c);
                                damage += AbilityUtils.calculateAmount(trigSa.getHostCard(), trigSa.getParam("LifeAmount"), trigSa);
                            }
                        }
                    }
                }
            }

        }

        return damage;
    }

    /**
     * Overload of predictThreatenedObjects that evaluates the full stack
     */
    public static List<GameObject> predictThreatenedObjects(final Player aiPlayer, final SpellAbility sa) {
        return predictThreatenedObjects(aiPlayer, sa, false);
    }

    /**
     * Returns list of objects threatened by effects on the stack
     * 
     * @param ai
     *            calling player
     * @param sa
     *            SpellAbility to exclude
     * @param top
     *            only evaluate the top of the stack for threatening effects
     * @return list of threatened objects
     */
    public static List<GameObject> predictThreatenedObjects(final Player ai, final SpellAbility sa, boolean top) {
        final Game game = ai.getGame();
        final List<GameObject> objects = new ArrayList<GameObject>();
        if (game.getStack().isEmpty()) {
            return objects;
        }
    
        // check stack for something that will kill this
        for (SpellAbilityStackInstance si : game.getStack()) {
            // iterate from top of stack to find SpellAbility, including sub-abilities,
            // that does not match "sa"
            SpellAbility spell = si.getSpellAbility(true), sub = spell.getSubAbility();
            while (sub != null && sub != sa) {
                sub = sub.getSubAbility();
            }
            if (sa == null || (sa != spell && sa != sub)) {
                Iterables.addAll(objects, ComputerUtil.predictThreatenedObjects(ai, sa, spell));
            }
            if (top) {
                break;  // only evaluate top-stack
            }
        }        
    
        return objects;
    }

    private static Iterable<? extends GameObject> predictThreatenedObjects(final Player aiPlayer, final SpellAbility saviour,
            final SpellAbility topStack) {
        Iterable<? extends GameObject> objects = new ArrayList<GameObject>();
        final List<GameObject> threatened = new ArrayList<GameObject>();
        ApiType saviourApi = saviour == null ? null : saviour.getApi();
        int toughness = 0;
        boolean grantIndestructible = false;
        boolean grantShroud = false;
    
        if (topStack == null) {
            return objects;
        }
    
        final Card source = topStack.getHostCard();
        final ApiType threatApi = topStack.getApi();
    
        // Can only Predict things from AFs
        if (threatApi == null) {
            return threatened;
        }
        final TargetRestrictions tgt = topStack.getTargetRestrictions();

        if (tgt == null) {
            if (topStack.hasParam("Defined")) {
                objects = AbilityUtils.getDefinedObjects(source, topStack.getParam("Defined"), topStack);
            } else if (topStack.hasParam("ValidCards")) {
                CardCollectionView battleField = aiPlayer.getCardsIn(ZoneType.Battlefield);
                objects = CardLists.getValidCards(battleField, topStack.getParam("ValidCards").split(","), source.getController(), source, topStack);
            } else {
            	return threatened;
            }
        } else {
            objects = topStack.getTargets().getTargets();
            final List<GameObject> canBeTargeted = new ArrayList<GameObject>();
            for (Object o : objects) {
                if (o instanceof Card) {
                    final Card c = (Card) o;
                    if (c.canBeTargetedBy(topStack)) {
                        canBeTargeted.add(c);
                    }
                }
            }
            if (canBeTargeted.isEmpty()) {
            	return threatened;
            }
            objects = canBeTargeted;
        }

        SpellAbility saviorWithSubs = saviour;
        ApiType saviorWithSubsApi = saviorWithSubs == null ? null : saviorWithSubs.getApi();
        while (saviorWithSubs != null) {
            ApiType curApi = saviorWithSubs.getApi();
            if (curApi == ApiType.Pump || curApi == ApiType.PumpAll) {
                toughness = saviorWithSubs.hasParam("NumDef") ?
                        AbilityUtils.calculateAmount(saviorWithSubs.getHostCard(), saviorWithSubs.getParam("NumDef"), saviour) : 0;
                final List<String> keywords = saviorWithSubs.hasParam("KW") ?
                        Arrays.asList(saviorWithSubs.getParam("KW").split(" & ")) : new ArrayList<String>();
                if (keywords.contains("Indestructible")) {
                    grantIndestructible = true;
                }
                if (keywords.contains("Hexproof") || keywords.contains("Shroud")) {
                    grantShroud = true;
                }
                break;
            }
            // Consider pump in subabilities, e.g. Bristling Hydra hexproof subability
            saviorWithSubs = saviorWithSubs.getSubAbility();

        }

        if (saviourApi == ApiType.PutCounter || saviourApi == ApiType.PutCounterAll) {
            if (saviour.getParam("CounterType").equals("P1P1")) {
                toughness = AbilityUtils.calculateAmount(saviour.getHostCard(), saviour.getParam("CounterNum"), saviour);
            } else {
                return threatened;
            }
        }

        // Determine if Defined Objects are "threatened" will be destroyed
        // due to this SA

        // Lethal Damage => prevent damage/regeneration/bounce/shroud
        if (threatApi == ApiType.DealDamage || threatApi == ApiType.DamageAll) {
            // If PredictDamage is >= Lethal Damage
            final int dmg = AbilityUtils.calculateAmount(topStack.getHostCard(),
                    topStack.getParam("NumDmg"), topStack);
            final SpellAbility sub = topStack.getSubAbility();
            boolean noRegen = false;
            if (sub != null && sub.getApi() == ApiType.Pump) {
                final List<String> keywords = sub.hasParam("KW") ? Arrays.asList(sub.getParam("KW").split(" & ")) : new ArrayList<String>();
                for (String kw : keywords) {
                    if (kw.contains("can't be regenerated")) {
                        noRegen = true;
                        break;
                    }
                }
            }
            for (final Object o : objects) {
                if (o instanceof Card) {
                    final Card c = (Card) o;

                    // indestructible
                    if (c.hasKeyword(Keyword.INDESTRUCTIBLE)) {
                        continue;
                    }

                    // already regenerated
                    if (c.getShieldCount() > 0) {
                        continue;
                    }

                    // don't use it on creatures that can't be regenerated
                    if ((saviourApi == ApiType.Regenerate || saviourApi == ApiType.RegenerateAll) && 
                            (!c.canBeShielded() || noRegen)) {
                        continue;
                    }

                    if (saviourApi == ApiType.Pump || saviourApi == ApiType.PumpAll) {
                        boolean canSave = ComputerUtilCombat.predictDamageTo(c, dmg - toughness, source, false) < ComputerUtilCombat.getDamageToKill(c);
                        if ((tgt == null && !grantIndestructible && !canSave)
                                || (!grantIndestructible && !grantShroud && !canSave)) {
                            continue;
                        }
                    }
                    
                    if (saviourApi == ApiType.PutCounter || saviourApi == ApiType.PutCounterAll) {
                        boolean canSave = ComputerUtilCombat.predictDamageTo(c, dmg - toughness, source, false) < ComputerUtilCombat.getDamageToKill(c);
                        if (!canSave && !grantShroud) {
                            continue;
                        }
                    }
                    
                    // cannot protect against source
                    if (saviourApi == ApiType.Protection && (ProtectAi.toProtectFrom(source, saviour) == null)) {
                        continue;
                    }

                    // don't bounce or blink a permanent that the human
                    // player owns or is a token
                    if (saviourApi == ApiType.ChangeZone && (c.getOwner().isOpponentOf(aiPlayer) || c.isToken())) {
                        continue;
                    }
                    
                    if (ComputerUtilCombat.predictDamageTo(c, dmg, source, false) >= ComputerUtilCombat.getDamageToKill(c)) {
                        threatened.add(c);
                    }
                } else if (o instanceof Player) {
                    final Player p = (Player) o;

                    if (source.hasKeyword(Keyword.INFECT)) {
                        if (ComputerUtilCombat.predictDamageTo(p, dmg, source, false) >= p.getPoisonCounters()) {
                            threatened.add(p);
                        }
                    } else if (ComputerUtilCombat.predictDamageTo(p, dmg, source, false) >= p.getLife()) {
                        threatened.add(p);
                    }
                }
            }
        }
        // -Toughness Curse
        else if ((threatApi == ApiType.Pump || threatApi == ApiType.PumpAll && topStack.isCurse())
                && (saviourApi == ApiType.ChangeZone || saviourApi == ApiType.Pump || saviourApi == ApiType.PumpAll 
                || saviourApi == ApiType.Protection || saviourApi == ApiType.PutCounter || saviourApi == ApiType.PutCounterAll
                || saviourApi == null)) {
            final int dmg = -AbilityUtils.calculateAmount(topStack.getHostCard(),
                    topStack.getParam("NumDef"), topStack);
            for (final Object o : objects) {
                if (o instanceof Card) {
                    final Card c = (Card) o;
                    final boolean canRemove = (c.getNetToughness() <= dmg)
                            || (!c.hasKeyword(Keyword.INDESTRUCTIBLE) && c.getShieldCount() == 0 && (dmg >= ComputerUtilCombat.getDamageToKill(c)));
                    if (!canRemove) {
                        continue;
                    }
                    
                    if (saviourApi == ApiType.Pump || saviourApi == ApiType.PumpAll) {
                        final boolean cantSave = c.getNetToughness() + toughness <= dmg
                                || (!c.hasKeyword(Keyword.INDESTRUCTIBLE) && c.getShieldCount() == 0 && !grantIndestructible
                                        && (dmg >= toughness + ComputerUtilCombat.getDamageToKill(c)));
                        if (cantSave && (tgt == null || !grantShroud)) {
                            continue;
                        }
                    }
                    
                    if (saviourApi == ApiType.PutCounter || saviourApi == ApiType.PutCounterAll) {
                        boolean canSave = c.getNetToughness() + toughness > dmg;
                        if (!canSave && !grantShroud) {
                            continue;
                        }
                    }
                    
                    if (saviourApi == ApiType.Protection) {
                        if (tgt == null || (ProtectAi.toProtectFrom(source, saviour) == null)) {
                            continue;
                        }
                    }

                    // don't bounce or blink a permanent that the human
                    // player owns or is a token
                    if (saviourApi == ApiType.ChangeZone && (c.getOwner().isOpponentOf(aiPlayer) || c.isToken())) {
                        continue;
                    }
                    threatened.add(c);
                }
            }
        }
        // Destroy => regeneration/bounce/shroud
        else if ((threatApi == ApiType.Destroy || threatApi == ApiType.DestroyAll)
                && (((saviourApi == ApiType.Regenerate || saviourApi == ApiType.RegenerateAll)
                        && !topStack.hasParam("NoRegen")) || saviourApi == ApiType.ChangeZone
                        || saviourApi == ApiType.Pump || saviourApi == ApiType.PumpAll
                        || saviourApi == ApiType.Protection || saviourApi == null
                        || saviorWithSubsApi == ApiType.Pump || saviorWithSubsApi == ApiType.PumpAll
                        || (grantShroud && saviourApi != ApiType.Pump && saviourApi != ApiType.PumpAll))) {
            for (final Object o : objects) {
                if (o instanceof Card) {
                    final Card c = (Card) o;
                    // indestructible
                    if (c.hasKeyword(Keyword.INDESTRUCTIBLE)) {
                        continue;
                    }

                    // already regenerated
                    if (c.getShieldCount() > 0) {
                        continue;
                    }

                    if (saviourApi == ApiType.Pump || saviourApi == ApiType.PumpAll
                            || saviorWithSubsApi == ApiType.Pump
                            || saviorWithSubsApi == ApiType.PumpAll) {
                        if ((tgt == null && !grantIndestructible)
                                || (!grantShroud && !grantIndestructible)) {
                            continue;
                        }
                    }
                    if (saviourApi == ApiType.Protection) {
                        if (tgt == null || (ProtectAi.toProtectFrom(source, saviour) == null)) {
                            continue;
                        }
                    }

                    // don't bounce or blink a permanent that the human
                    // player owns or is a token
                    if (saviourApi == ApiType.ChangeZone && (c.getOwner().isOpponentOf(aiPlayer) || c.isToken())) {
                        continue;
                    }

                    // don't use it on creatures that can't be regenerated
                    if (saviourApi == ApiType.Regenerate && !c.canBeShielded()) {
                        continue;
                    }

                    if(grantShroud && saviourApi != ApiType.Pump && saviourApi != ApiType.PumpAll && !topStack.isTargeting(c)) {
                        continue;
                    }

                    threatened.add(c);
                }
            }
        }
        // Exiling => bounce/shroud
        else if ((threatApi == ApiType.ChangeZone || threatApi == ApiType.ChangeZoneAll)
                && (saviourApi == ApiType.ChangeZone || saviourApi == ApiType.Pump || saviourApi == ApiType.PumpAll
                || saviourApi == ApiType.Protection || saviourApi == null || (grantShroud && saviourApi != ApiType.Pump && saviourApi != ApiType.PumpAll))
                && topStack.hasParam("Destination")
                && topStack.getParam("Destination").equals("Exile")) {
            for (final Object o : objects) {
                if (o instanceof Card) {
                    final Card c = (Card) o;
                    // give Shroud to targeted creatures
                    if ((saviourApi == ApiType.Pump || saviourApi == ApiType.PumpAll) && (tgt == null || !grantShroud)) {
                        continue;
                    }
                    if (saviourApi == ApiType.Protection) {
                        if (tgt == null || (ProtectAi.toProtectFrom(source, saviour) == null)) {
                            continue;
                        }
                    }

                    // don't bounce or blink a permanent that the human
                    // player owns or is a token
                    if (saviourApi == ApiType.ChangeZone && (c.getOwner().isOpponentOf(aiPlayer) || c.isToken())) {
                        continue;
                    }

                    if(grantShroud && saviourApi != ApiType.Pump && saviourApi != ApiType.PumpAll && !topStack.isTargeting(c)) {
                        continue;
                    }

                    threatened.add(c);
                }
            }
        }
        //GainControl
        else if ((threatApi == ApiType.GainControl 
        			|| (threatApi == ApiType.Attach && topStack.hasParam("AILogic") && topStack.getParam("AILogic").equals("GainControl") ))
                && (saviourApi == ApiType.ChangeZone || saviourApi == ApiType.Pump || saviourApi == ApiType.PumpAll 
                || saviourApi == ApiType.Protection || saviourApi == null || (grantShroud && saviourApi != ApiType.Pump && saviourApi != ApiType.PumpAll))) {
            for (final Object o : objects) {
                if (o instanceof Card) {
                    final Card c = (Card) o;
                    // give Shroud to targeted creatures
                    if ((saviourApi == ApiType.Pump || saviourApi == ApiType.PumpAll && tgt == null) && !grantShroud) {
                        continue;
                    }
                    if (saviourApi == ApiType.Protection) {
                        if (tgt == null || (ProtectAi.toProtectFrom(source, saviour) == null)) {
                            continue;
                        }
                    }

                    if(grantShroud && saviourApi != ApiType.Pump && saviourApi != ApiType.PumpAll && !topStack.isTargeting(c)) {
                        continue;
                    }

                    threatened.add(c);
                }
            }
        }

        Iterables.addAll(threatened, ComputerUtil.predictThreatenedObjects(aiPlayer, saviour, topStack.getSubAbility()));
        return threatened;
    }
    
    public static boolean playImmediately(Player ai, SpellAbility sa) {
        final Card source = sa.getHostCard();
        final Zone zone = source.getZone();
        final Game game = source.getGame();

        if (sa.isTrigger() || zone == null || sa.isCopied()) {
            return true;
        }

        if (zone.getZoneType() == ZoneType.Battlefield) {
            if (predictThreatenedObjects(ai, null).contains(source)) {
                return true;
            }
            if (game.getPhaseHandler().inCombat() && 
            		ComputerUtilCombat.combatantWouldBeDestroyed(ai, source, game.getCombat())) {
            	return true;
            }
        } else if (zone.getZoneType() == ZoneType.Exile && sa.getMayPlay() != null) {
            // play cards in exile that can only be played that turn
            if (game.getPhaseHandler().getPhase() == PhaseType.MAIN2) {
                if (source.mayPlay(sa.getMayPlay()) != null) {
                    return true;
                }
            }
        }
        return false;
    }


    public static int scoreHand(CardCollectionView handList, Player ai) {
        final AiController aic = ((PlayerControllerAi)ai.getController()).getAi();

        // don't mulligan when already too low
        if (handList.size() < aic.getIntProperty(AiProps.MULLIGAN_THRESHOLD)) {
            return handList.size();
        }

        CardCollectionView library = ai.getZone(ZoneType.Library).getCards();
        int landsInDeck = CardLists.filter(library, CardPredicates.isType("Land")).size();

        // no land deck, can't do anything better
        if (landsInDeck == 0) {
            return handList.size();
        }

        final CardCollectionView lands = CardLists.filter(handList, new Predicate<Card>() {
            @Override
            public boolean apply(final Card c) {
                if (c.getManaCost().getCMC() > 0 || c.hasSVar("NeedsToPlay")
                        || (!c.getType().isLand() && !c.getType().isArtifact())) {
                    return false;
                }
                return true;
            }
        });

        final int handSize = handList.size();
        final int landSize = lands.size();
        int score = handList.size();

        if (handSize/2 == landSize || handSize/2 == landSize +1) {
            score += 10;
        }

        final CardCollectionView castables = CardLists.filter(handList, new Predicate<Card>() {
            @Override
            public boolean apply(final Card c) {
                if (c.getManaCost().getCMC() > 0 && c.getManaCost().getCMC() <= landSize) {
                    return false;
                }
                return true;
            }
        });

        score += castables.size() * 2;

        // Improve score for perceived mana efficiency of the hand

        // if at mulligan threshold, and we have any lands accept the hand
        if (handSize == aic.getIntProperty(AiProps.MULLIGAN_THRESHOLD) && landSize > 0) {
            return score;
        }

        // otherwise, reject bad hands or return score
        if ( landSize < 2) {
            // BAD Hands, 0 or 1 lands
            if (landsInDeck == 0 || library.size()/landsInDeck > 6) {
                // Heavy spell deck it's ok
                return handSize;
            }
            return 0;
        } else if (landSize == handSize) {
            if (library.size()/landsInDeck < 2) {
                // Heavy land deck/Momir Basic it's ok
                return handSize;
            }
            return 0;
        } else if (handSize >= 7 && landSize >= handSize-1) {
            // BAD Hands - Mana flooding

            if (library.size()/landsInDeck < 2) {
                // Heavy land deck/Momir Basic it's ok
                return handSize;
            }
            return 0;
        }
        return score;
    }

    // Computer mulligans if there are no cards with converted mana cost of 0 in its hand
    public static boolean wantMulligan(Player ai) {
        final CardCollectionView handList = ai.getCardsIn(ZoneType.Hand);
        return scoreHand(handList, ai) <= 0;
    }
    
    public static CardCollection getPartialParisCandidates(Player ai) {
        // Commander no longer uses partial paris.
        final CardCollection candidates = new CardCollection();
        final CardCollectionView handList = ai.getCardsIn(ZoneType.Hand);
        
        final CardCollection lands = CardLists.getValidCards(handList, "Card.Land", ai, null);
        final CardCollection nonLands = CardLists.getValidCards(handList, "Card.nonLand", ai, null);
        CardLists.sortByCmcDesc(nonLands);
        
        if (lands.size() >= 3 && lands.size() <= 4) {
            return candidates;
        }
        if (lands.size() < 3) {
            //Not enough lands!
            int tgtCandidates = Math.max(Math.abs(lands.size()-nonLands.size()), 3);
            System.out.println("Partial Paris: " + ai.getName() + " lacks lands, aiming to exile " + tgtCandidates + " cards.");
            
            for (int i=0;i<tgtCandidates;i++) {
                candidates.add(nonLands.get(i));
            }
        }
        else {
            //Too many lands!
            //Init
            int cntColors = MagicColor.WUBRG.length;
            List<CardCollection> numProducers = new ArrayList<CardCollection>(cntColors);
            for (byte col : MagicColor.WUBRG) {
                numProducers.add(col, new CardCollection());
            }

            for (Card c : lands) {
                for (SpellAbility sa : c.getManaAbilities()) {
                    AbilityManaPart abmana = sa.getManaPart();
                    for (byte col : MagicColor.WUBRG) {
                        if (abmana.canProduce(MagicColor.toLongString(col))) {
                            numProducers.get(col).add(c);
                        }
                    }
                }                
            }
        }

        System.out.print("Partial Paris: " + ai.getName() + " may exile ");
        for (Card c : candidates) {
            System.out.print(c.toString() + ", ");
        }
        System.out.println();
        
        if (candidates.size() < 2) {
            candidates.clear();
        }
        return candidates;
    }

    public static boolean scryWillMoveCardToBottomOfLibrary(Player player, Card c) {
        boolean bottom = false;

        // AI profile-based toggles
        int maxLandsToScryLandsToTop = 3;
        int minLandsToScryLandsAway = 8;
        int minCreatsToScryCreatsAway = 5;
        int minCreatEvalThreshold = 160; // just a bit higher than a baseline 2/2 creature or a 1/1 mana dork
        int lowCMCThreshold = 3;
        int maxCreatsToScryLowCMCAway = 3;
        boolean uncastablesToBottom = false;
        int uncastableCMCThreshold = 1;
        if (player.getController().isAI()) {
            AiController aic = ((PlayerControllerAi)player.getController()).getAi();
            maxLandsToScryLandsToTop = aic.getIntProperty(AiProps.SCRY_NUM_LANDS_TO_STILL_NEED_MORE);
            minLandsToScryLandsAway = aic.getIntProperty(AiProps.SCRY_NUM_LANDS_TO_NOT_NEED_MORE);
            minCreatsToScryCreatsAway = aic.getIntProperty(AiProps.SCRY_NUM_CREATURES_TO_NOT_NEED_SUBPAR_ONES);
            minCreatEvalThreshold = aic.getIntProperty(AiProps.SCRY_EVALTHR_TO_SCRY_AWAY_LOWCMC_CREATURE);
            lowCMCThreshold = aic.getIntProperty(AiProps.SCRY_EVALTHR_CMC_THRESHOLD);
            maxCreatsToScryLowCMCAway = aic.getIntProperty(AiProps.SCRY_EVALTHR_CREATCOUNT_TO_SCRY_AWAY_LOWCMC);
            uncastablesToBottom = aic.getBooleanProperty(AiProps.SCRY_IMMEDIATELY_UNCASTABLE_TO_BOTTOM);
            uncastableCMCThreshold = aic.getIntProperty(AiProps.SCRY_IMMEDIATELY_UNCASTABLE_CMC_DIFF);
        }

        CardCollectionView allCards = player.getAllCards();
        CardCollectionView cardsInHand = player.getCardsIn(ZoneType.Hand);
        CardCollectionView cardsOTB = player.getCardsIn(ZoneType.Battlefield);

        CardCollection landsOTB = CardLists.filter(cardsOTB, CardPredicates.Presets.LANDS_PRODUCING_MANA);
        CardCollection thisLandOTB = CardLists.filter(cardsOTB, CardPredicates.nameEquals(c.getName()));
        CardCollection landsInHand = CardLists.filter(cardsInHand, CardPredicates.Presets.LANDS_PRODUCING_MANA);
        // valuable mana-producing artifacts that may be equated to a land
        List<String> manaArts = Arrays.asList("Mox Pearl", "Mox Sapphire", "Mox Jet", "Mox Ruby", "Mox Emerald");
        
        // evaluate creatures available in deck
        CardCollectionView allCreatures = CardLists.filter(allCards, Predicates.and(CardPredicates.Presets.CREATURES, CardPredicates.isOwner(player)));
        int numCards = allCreatures.size();

        if (landsOTB.size() < maxLandsToScryLandsToTop && landsInHand.isEmpty()) {
            if ((!c.isLand() && !manaArts.contains(c.getName()))
                    || (c.getManaAbilities().isEmpty() && !c.hasABasicLandType())) {
                // scry away non-lands and non-manaproducing lands in situations when the land count
                // on the battlefield is low, to try to improve the mana base early
                bottom = true;
            }
        }

        if (c.isLand()) {
            if (landsOTB.size() >= minLandsToScryLandsAway) {
                // probably enough lands not to urgently need another one, so look for more gas instead
                bottom = true;
            } else if (landsInHand.size() >= Math.max(cardsInHand.size() / 2, 2)) {
                // scry lands to the bottom if we already have enough lands in hand
                bottom = true;
            }

            if (c.isBasicLand()) {
                if (landsOTB.size() > 5 && thisLandOTB.size() >= 2) {
                    // if we control more than 5 lands, 2 or more of them of the basic type in question,
                    // scry to the bottom if it's a basic land
                    bottom = true;
                }
            }
        } else if (c.isCreature()) {
            CardCollection creaturesOTB = CardLists.filter(cardsOTB, CardPredicates.Presets.CREATURES);
            int avgCreatureValue = numCards != 0 ? ComputerUtilCard.evaluateCreatureList(allCreatures) / numCards : 0;
            int maxControlledCMC = Aggregates.max(creaturesOTB, CardPredicates.Accessors.fnGetCmc);

            if (ComputerUtilCard.evaluateCreature(c) < avgCreatureValue) {
                if (creaturesOTB.size() > minCreatsToScryCreatsAway) {
                    // if there are more than five creatures and the creature is question is below average for
                    // the deck, scry it to the bottom
                    bottom = true;
                } else if (creaturesOTB.size() > maxCreatsToScryLowCMCAway && c.getCMC() <= lowCMCThreshold
                        && maxControlledCMC >= lowCMCThreshold + 1 && ComputerUtilCard.evaluateCreature(c) <= minCreatEvalThreshold) {
                    // if we are already at a stage when we have 4+ CMC creatures on the battlefield,
                    // probably worth it to scry away very low value creatures with low CMC
                    bottom = true;
                }
            }
        }

        if (uncastablesToBottom && !c.isLand()) {
            int cmc = c.isSplitCard() ? Math.min(c.getCMC(Card.SplitCMCMode.LeftSplitCMC), c.getCMC(Card.SplitCMCMode.RightSplitCMC))
                    : c.getCMC();
            int maxCastable = ComputerUtilMana.getAvailableManaEstimate(player, false) + landsInHand.size();
            if (cmc - maxCastable >= uncastableCMCThreshold) {
                bottom = true;
            }
        }

        return bottom;
    }

    public static CardCollection getCardsToDiscardFromOpponent(Player chooser, Player discarder, SpellAbility sa, CardCollection validCards, int min, int max) {
        CardCollection goodChoices = CardLists.filter(validCards, new Predicate<Card>() {
            @Override
            public boolean apply(final Card c) {
                if (c.hasSVar("DiscardMeByOpp") || c.hasSVar("DiscardMe")) {
                    return false;
                }
                return true;
            }
        });
        if (goodChoices.isEmpty()) {
            goodChoices = validCards;
        }
        final CardCollection dChoices = new CardCollection();
        if (sa.hasParam("DiscardValid")) {
            final String validString = sa.getParam("DiscardValid");
            if (validString.contains("Creature") && !validString.contains("nonCreature")) {
                final Card c = ComputerUtilCard.getBestCreatureAI(goodChoices);
                if (c != null) {
                    dChoices.add(ComputerUtilCard.getBestCreatureAI(goodChoices));
                }
            }
        }
    
        Collections.sort(goodChoices, CardLists.TextLenComparator);
    
        CardLists.sortByCmcDesc(goodChoices);
        dChoices.add(goodChoices.get(0));

        return Aggregates.random(goodChoices, min, new CardCollection());
    }

    public static CardCollection getCardsToDiscardFromFriend(Player aiChooser, Player p, SpellAbility sa, CardCollection validCards, int min, int max) {
        if (p == aiChooser) { // ask that ai player what he would like to discard
            final AiController aic = ((PlayerControllerAi)p.getController()).getAi();
            return aic.getCardsToDiscard(min, max, validCards, sa);
        } 
        // no special options for human or remote friends
        return getCardsToDiscardFromOpponent(aiChooser, p, sa, validCards, min, max);
    }

    public static String chooseSomeType(Player ai, String kindOfType, String logic, List<String> invalidTypes) {
        if (invalidTypes == null) {
            invalidTypes = ImmutableList.<String>of();
        }

        final Game game = ai.getGame();
        String chosen = "";
        if (kindOfType.equals("Card")) {
            // TODO
            // computer will need to choose a type
            // based on whether it needs a creature or land,
            // otherwise, lib search for most common type left
            // then, reveal chosenType to Human
            if (game.getPhaseHandler().is(PhaseType.UNTAP) && logic == null) { // Storage Matrix
                double amount = 0;
                for (String type : CardType.getAllCardTypes()) {
                    if (!invalidTypes.contains(type)) {
                        CardCollection list = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.isType(type), Presets.TAPPED);
                        double i = type.equals("Creature") ? list.size() * 1.5 : list.size();
                        if (i > amount) {
                            amount = i;
                            chosen = type;
                        }
                    }
                }
            }
            if (StringUtils.isEmpty(chosen)) {
                chosen = "Creature";
            }
        } else if (kindOfType.equals("Creature")) {
            if (logic != null) {
                List <String> valid = Lists.newArrayList(CardType.getAllCreatureTypes());
                valid.removeAll(invalidTypes);

                if (logic.equals("MostProminentOnBattlefield")) {
                    chosen = ComputerUtilCard.getMostProminentType(game.getCardsIn(ZoneType.Battlefield), valid);
                }
                else if (logic.equals("MostProminentComputerControls")) {
                    chosen = ComputerUtilCard.getMostProminentType(ai.getCardsIn(ZoneType.Battlefield), valid);
                }
                else if (logic.equals("MostProminentOppControls")) {
            	    CardCollection list = CardLists.filterControlledBy(game.getCardsIn(ZoneType.Battlefield), ai.getOpponents());
                    chosen = ComputerUtilCard.getMostProminentType(list, valid);
                    if (!CardType.isACreatureType(chosen) || invalidTypes.contains(chosen)) {
                        list = CardLists.filterControlledBy(game.getCardsInGame(), ai.getOpponents());
                        chosen = ComputerUtilCard.getMostProminentType(list, valid);
                    }
                }
                else if (logic.equals("MostProminentInComputerDeck")) {
                    chosen = ComputerUtilCard.getMostProminentType(ai.getAllCards(), valid);
                }
                else if (logic.equals("MostProminentInComputerGraveyard")) {
                    chosen = ComputerUtilCard.getMostProminentType(ai.getCardsIn(ZoneType.Graveyard), valid);
                }
            }
            if (!CardType.isACreatureType(chosen) || invalidTypes.contains(chosen)) {
                chosen = "Sliver";
            }

        } else if (kindOfType.equals("Basic Land")) {
            if (logic != null) {
                if (logic.equals("MostProminentOppControls")) {
                    CardCollection list = CardLists.filterControlledBy(game.getCardsIn(ZoneType.Battlefield), ai.getOpponents());
                    List<String> valid = Lists.newArrayList(CardType.getBasicTypes());
                    valid.removeAll(invalidTypes);

                    chosen = ComputerUtilCard.getMostProminentType(list, valid);
                } else  if (logic.equals("MostNeededType")) {
                    // Choose a type that is in the deck, but not in hand or on the battlefield 
                    final List<String> basics = new ArrayList<String>();
                    basics.addAll(CardType.Constant.BASIC_TYPES);
                    CardCollectionView presentCards = CardCollection.combine(ai.getCardsIn(ZoneType.Battlefield), ai.getCardsIn(ZoneType.Hand));
                    CardCollectionView possibleCards = ai.getAllCards();
                    
                    for (String b : basics) {
                        if (!Iterables.any(presentCards, CardPredicates.isType(b)) && Iterables.any(possibleCards, CardPredicates.isType(b))) {
                            chosen = b;
                        }
                    }
                    if (chosen.equals("")) {
                        for (String b : basics) {
                            if (Iterables.any(possibleCards, CardPredicates.isType(b))) {
                                chosen = b;
                            }
                        }
                    }
                }
                else if (logic.equals("ChosenLandwalk")) {
                    for (Card c : ComputerUtil.getOpponentFor(ai).getLandsInPlay()) {
                        for (String t : c.getType()) {
                            if (!invalidTypes.contains(t) && CardType.isABasicLandType(t)) {
                                chosen = t;
                                break;
                            }
                        }
                    }
                }
            }

            if (!CardType.isABasicLandType(chosen) || invalidTypes.contains(chosen)) {
                chosen = "Island";
            }
        }
        else if (kindOfType.equals("Land")) {
            if (logic != null) {
                if (logic.equals("ChosenLandwalk")) {
                    for (Card c : ComputerUtil.getOpponentFor(ai).getLandsInPlay()) {
                        for (String t : c.getType().getLandTypes()) {
                            if (!invalidTypes.contains(t)) {
                                chosen = t;
                                break;
                            }
                        }
                    }
                }
            }
            if (StringUtils.isEmpty(chosen)) {
                chosen = "Island";
            }
        }
        return chosen;
    }

    public static Object vote(Player ai, List<Object> options, SpellAbility sa, Multimap<Object, Player> votes) {
        final Card source = sa.getHostCard();
        final Player controller = source.getController();
        final Game game = controller.getGame();

        boolean opponent = controller.isOpponentOf(ai);

        if (!sa.hasParam("AILogic")) {
            return Aggregates.random(options);
        }

        String logic = sa.getParam("AILogic");
        switch (logic) {
        case "Torture":
            return "Torture";
        case "GraceOrCondemnation":
            return ai.getCreaturesInPlay().size() > ComputerUtil.getOpponentFor(ai).getCreaturesInPlay().size() ? "Grace"
                    : "Condemnation";
        case "CarnageOrHomage":
            CardCollection cardsInPlay = CardLists
                    .getNotType(sa.getHostCard().getGame().getCardsIn(ZoneType.Battlefield), "Land");
            CardCollection humanlist = CardLists.filterControlledBy(cardsInPlay, ai.getOpponents());
            CardCollection computerlist = CardLists.filterControlledBy(cardsInPlay, ai);
            return (ComputerUtilCard.evaluatePermanentList(computerlist) + 3) < ComputerUtilCard
                    .evaluatePermanentList(humanlist) ? "Carnage" : "Homage";
        case "Judgment":
            if (votes.isEmpty()) {
                CardCollection list = new CardCollection();
                for (Object o : options) {
                    if (o instanceof Card) {
                        list.add((Card) o);
                        }
                    }
                return ComputerUtilCard.getBestAI(list);
            } else {
                return Iterables.getFirst(votes.keySet(), null);
            }
        case "Protection":
            if (votes.isEmpty()) {
                List<String> restrictedToColors = Lists.newArrayList();
                for (Object o : options) {
                    if (o instanceof String) {
                        restrictedToColors.add((String) o);
                        }
                    }
                CardCollection lists = CardLists.filterControlledBy(ai.getGame().getCardsInGame(), ai.getOpponents());
                return StringUtils.capitalize(ComputerUtilCard.getMostProminentColor(lists, restrictedToColors));
            } else {
                return Iterables.getFirst(votes.keySet(), null);
            }
        case "FeatherOrQuill":

            // try to mill opponent with Quill vote
            if (opponent && !controller.cantLose()) {
                int numQuill = votes.get("Quill").size();
                if (numQuill + 1 >= controller.getCardsIn(ZoneType.Library).size()) {
                    return controller.isCardInPlay("Laboratory Maniac") ? "Feather" : "Quill";
                }
            }
            // is it can't receive counters, choose +1/+1 ones
            if (!source.canReceiveCounters(CounterType.P1P1)) {
                return opponent ? "Feather" : "Quill";
            }
            // if source is not on the battlefield anymore, choose +1/+1
            // ones
            if (!game.getCardState(source).getZone().is(ZoneType.Battlefield)) {
                return opponent ? "Feather" : "Quill";
            }
            // if no hand cards, try to mill opponent
            if (controller.getCardsIn(ZoneType.Hand).isEmpty()) {
                return opponent ? "Quill" : "Feather";
            }

            // AI has something to discard
            if (ai.equals(controller)) {
                CardCollectionView aiCardsInHand = ai.getCardsIn(ZoneType.Hand);
                if (CardLists.count(aiCardsInHand, CardPredicates.hasSVar("DiscardMe")) >= 1) {
                    return "Quill";
                }
            }

            // default card draw and discard are better than +1/+1 counter
            return opponent ? "Feather" : "Quill";
        case "StrengthOrNumbers":
            // similar to fabricate choose +1/+1 or Token
            final SpellAbility saToken = sa.findSubAbilityByType(ApiType.Token);
            int numStrength = votes.get("Strength").size();
            int numNumbers = votes.get("Numbers").size();

            Card token = TokenAi.spawnToken(controller, saToken);

            // is it can't receive counters, choose +1/+1 ones
            if (!source.canReceiveCounters(CounterType.P1P1)) {
                return opponent ? "Strength" : "Numbers";
            }

            // if source is not on the battlefield anymore
            if (!game.getCardState(source).getZone().is(ZoneType.Battlefield)) {
                return opponent ? "Strength" : "Numbers";
            }

            // token would not survive
            if (token == null) {
                return opponent ? "Numbers" : "Strength";
            }

            // TODO check for ETB to +1/+1 counters
            // or over another trigger like lifegain

            int tokenScore = ComputerUtilCard.evaluateCreature(token);

            // score check similar to Fabricate
            Card sourceNumbers = CardUtil.getLKICopy(source);
            Card sourceStrength = CardUtil.getLKICopy(source);

            sourceNumbers.setCounters(CounterType.P1P1, sourceNumbers.getCounters(CounterType.P1P1) + numStrength);
            sourceNumbers.setZone(source.getZone());

            sourceStrength.setCounters(CounterType.P1P1,
                    sourceStrength.getCounters(CounterType.P1P1) + numStrength + 1);
            sourceStrength.setZone(source.getZone());

            int scoreStrength = ComputerUtilCard.evaluateCreature(sourceStrength) + tokenScore * numNumbers;
            int scoreNumbers = ComputerUtilCard.evaluateCreature(sourceNumbers) + tokenScore * (numNumbers + 1);

            return (scoreNumbers >= scoreStrength) != opponent ? "Numbers" : "Strength";

        case "SproutOrHarvest":

            // lifegain would hurt or has no effect
            if (opponent) {
                if (lifegainNegative(controller, source)) {
                    return "Harvest";
                }
            } else {
                if (lifegainNegative(controller, source)) {
                    return "Sprout";
                }
            }

            // is it can't receive counters, choose +1/+1 ones
            if (!source.canReceiveCounters(CounterType.P1P1)) {
                return opponent ? "Sprout" : "Harvest";
            }

            // if source is not on the battlefield anymore
            if (!game.getCardState(source).getZone().is(ZoneType.Battlefield)) {
                return opponent ? "Sprout" : "Harvest";
            }
            // TODO add Lifegain to +1/+1 counters trigger

            // for now +1/+1 counters are better
            return opponent ? "Harvest" : "Sprout";
        case "DeathOrTaxes":
            int numDeath = votes.get("Death").size();
            int numTaxes = votes.get("Taxes").size();

            if (opponent) {
                CardCollection aiCreatures = ai.getCreaturesInPlay();
                CardCollectionView aiCardsInHand = ai.getCardsIn(ZoneType.Hand);
                // would need to sacrifice more creatures than AI has
                // sacrifice even more
                if (aiCreatures.size() <= numDeath) {
                    return "Death";
                }
                // would need to discard more cards than it has
                if (aiCardsInHand.size() <= numTaxes) {
                    return "Taxes";
                }

                // has cards with SacMe or Token
                if (CardLists.count(aiCreatures,
                        Predicates.or(CardPredicates.hasSVar("SacMe"), CardPredicates.Presets.TOKEN)) >= numDeath) {
                    return "Death";
                }

                // has cards with DiscardMe
                if (CardLists.count(aiCardsInHand, CardPredicates.hasSVar("DiscardMe")) >= numTaxes) {
                    return "Taxes";
                }

                // discard is probably less worse than sacrifice
                return "Taxes";
            } else {
                // ai is first voter or ally of controller
                // both are not affected, but if opponents controll creatures,
                // sacrifice is worse
                return controller.getOpponents().getCreaturesInPlay().isEmpty() ? "Taxes" : "Death";
            }
        default:
            return Iterables.getFirst(options, null);
        }
    }

    public static CardCollection getSafeTargets(final Player ai, SpellAbility sa, CardCollectionView validCards) {
        CardCollection safeCards = new CardCollection(validCards);
        safeCards = CardLists.filter(safeCards, new Predicate<Card>() {
            @Override
            public boolean apply(final Card c) {
                if (c.getController() == ai) {
                    if (c.getSVar("Targeting").equals("Dies") || c.getSVar("Targeting").equals("Counter"))
                    return false;
                }
                return true;
            }
        });
        return safeCards;
    }

    public static Card getKilledByTargeting(final SpellAbility sa, CardCollectionView validCards) {
        CardCollection killables = new CardCollection(validCards);
        killables = CardLists.filter(killables, new Predicate<Card>() {
            @Override
            public boolean apply(final Card c) {
                return c.getController() != sa.getActivatingPlayer() && c.getSVar("Targeting").equals("Dies");
            }
        });
        return ComputerUtilCard.getBestCreatureAI(killables);
    }
    
    public static int predictDamageFromSpell(final SpellAbility sa, final Player targetPlayer) {
        int damage = -1; // returns -1 if the spell does not deal damage
        final Card card = sa.getHostCard();
    
        SpellAbility ab = sa;
        while (ab != null) {
            if (ab.getApi() == ApiType.DealDamage) {
                if (damage == -1) { damage = 0; } // found a damage-dealing spell
                if (!ab.hasParam("NumDmg")) {
                    continue;
                }
                damage += ComputerUtilCombat.predictDamageTo(targetPlayer,
                        AbilityUtils.calculateAmount(card, ab.getParam("NumDmg"), ab), card, false);
            } else if (ab.getApi() == ApiType.LoseLife) {
                if (damage == -1) { damage = 0; } // found a damage-dealing spell
                if (!ab.hasParam("LifeAmount")) {
                    continue;
                }
                damage += AbilityUtils.calculateAmount(card, ab.getParam("LifeAmount"), ab);
            }
            ab = ab.getSubAbility();
        }
        
        return damage;
    }
    
    public static int getDamageForPlaying(final Player player, final SpellAbility sa) {
        
        // check for bad spell cast triggers
        int damage = 0;
        final Game game = player.getGame();
        final Card card = sa.getHostCard();
        final FCollection<Trigger> theTriggers = new FCollection<Trigger>();

        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            theTriggers.addAll(c.getTriggers());
        }
        for (Trigger trigger : theTriggers) {
            Map<String, String> trigParams = trigger.getMapParams();
            final Card source = trigger.getHostCard();


            if (!trigger.zonesCheck(game.getZoneOf(source))) {
                continue;
            }
            if (!trigger.requirementsCheck(game)) {
                continue;
            }
            TriggerType mode = trigger.getMode();
            if (mode != TriggerType.SpellCast) {
                continue;
            }
            if (trigParams.containsKey("ValidCard")) {
                if (!card.isValid(trigParams.get("ValidCard"), source.getController(), source, sa)) {
                    continue;
                }
            }
            
            if (trigParams.containsKey("ValidActivatingPlayer")) {
                if (!player.isValid(trigParams.get("ValidActivatingPlayer"), source.getController(), source, sa)) {
                    continue;
                }
            }

            if (!trigParams.containsKey("Execute")) {
                // fall back for OverridingAbility
                SpellAbility trigSa = trigger.getOverridingAbility();
                if (trigSa == null) {
                    continue;
                }
                if (trigSa.getApi() == ApiType.DealDamage) {
                    if (!"TriggeredActivator".equals(trigSa.getParam("Defined"))) {
                        continue;
                    }
                    if (!trigSa.hasParam("NumDmg")) {
                        continue;
                    }
                    damage += ComputerUtilCombat.predictDamageTo(player,
                            AbilityUtils.calculateAmount(source, trigSa.getParam("NumDmg"), trigSa), source, false);
                } else if (trigSa.getApi() == ApiType.LoseLife) {
                    if (!"TriggeredActivator".equals(trigSa.getParam("Defined"))) {
                        continue;
                    }
                    if (!trigSa.hasParam("LifeAmount")) {
                        continue;
                    }
                    damage += AbilityUtils.calculateAmount(source, trigSa.getParam("LifeAmount"), trigSa);
                }
            } else {
                String ability = source.getSVar(trigParams.get("Execute"));
                if (ability.isEmpty()) {
                    continue;
                }

                final Map<String, String> abilityParams = AbilityFactory.getMapParams(ability);
                if ((abilityParams.containsKey("AB") && abilityParams.get("AB").equals("DealDamage"))
                        || (abilityParams.containsKey("DB") && abilityParams.get("DB").equals("DealDamage"))) {
                    if (!"TriggeredActivator".equals(abilityParams.get("Defined"))) {
                        continue;
                    }
                    if (!abilityParams.containsKey("NumDmg")) {
                        continue;
                    }
                    damage += ComputerUtilCombat.predictDamageTo(player,
                            AbilityUtils.calculateAmount(source, abilityParams.get("NumDmg"), null), source, false);
                } else if ((abilityParams.containsKey("AB") && abilityParams.get("AB").equals("LoseLife"))
                        || (abilityParams.containsKey("DB") && abilityParams.get("DB").equals("LoseLife"))) {
                    if (!"TriggeredActivator".equals(abilityParams.get("Defined"))) {
                        continue;
                    }
                    if (!abilityParams.containsKey("LifeAmount")) {
                        continue;
                    }
                    damage += AbilityUtils.calculateAmount(source, abilityParams.get("LifeAmount"), null);
                }
            }
        }
        
        return damage;
    }

    public static int getDamageFromETB(final Player player, final Card permanent) {
        int damage = 0;
        final Game game = player.getGame();
        final FCollection<Trigger> theTriggers = new FCollection<Trigger>();

        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            theTriggers.addAll(card.getTriggers());
        }
        for (Trigger trigger : theTriggers) {
            Map<String, String> trigParams = trigger.getMapParams();
            final Card source = trigger.getHostCard();


            if (!trigger.zonesCheck(game.getZoneOf(source))) {
                continue;
            }
            if (!trigger.requirementsCheck(game)) {
                continue;
            }
            if (trigParams.containsKey("CheckOnTriggeredCard") 
                    && AbilityUtils.getDefinedCards(permanent, source.getSVar(trigParams.get("CheckOnTriggeredCard").split(" ")[0]), null).isEmpty()) {
                continue;
            }
            TriggerType mode = trigger.getMode();
            if (mode != TriggerType.ChangesZone) {
                continue;
            }
            if (!"Battlefield".equals(trigParams.get("Destination"))) {
                continue;
            }
            if (trigParams.containsKey("ValidCard")) {
                if (!permanent.isValid(trigParams.get("ValidCard"), source.getController(), source, null)) {
                    continue;
                }
            }
            if (!trigParams.containsKey("Execute")) {
                // fall back for OverridingAbility
                SpellAbility trigSa = trigger.getOverridingAbility();
                if (trigSa == null) {
                    continue;
                }
                if (trigSa.getApi() == ApiType.DealDamage) {
                    if (!"TriggeredCardController".equals(trigSa.getParam("Defined"))) {
                        continue;
                    }
                    if (!trigSa.hasParam("NumDmg")) {
                        continue;
                    }
                    damage += ComputerUtilCombat.predictDamageTo(player,
                            AbilityUtils.calculateAmount(source, trigSa.getParam("NumDmg"), trigSa), source, false);
                } else if (trigSa.getApi() == ApiType.LoseLife) {
                    if (!"TriggeredCardController".equals(trigSa.getParam("Defined"))) {
                        continue;
                    }
                    if (!trigSa.hasParam("LifeAmount")) {
                        continue;
                    }
                    damage += AbilityUtils.calculateAmount(source, trigSa.getParam("LifeAmount"), trigSa);
                }
            } else {
                String ability = source.getSVar(trigParams.get("Execute"));
                if (ability.isEmpty()) {
                    continue;
                }

                final Map<String, String> abilityParams = AbilityFactory.getMapParams(ability);
                // Destroy triggers
                if ((abilityParams.containsKey("AB") && abilityParams.get("AB").equals("DealDamage"))
                        || (abilityParams.containsKey("DB") && abilityParams.get("DB").equals("DealDamage"))) {
                    if (!"TriggeredCardController".equals(abilityParams.get("Defined"))) {
                        continue;
                    }
                    if (!abilityParams.containsKey("NumDmg")) {
                        continue;
                    }
                    damage += ComputerUtilCombat.predictDamageTo(player,
                            AbilityUtils.calculateAmount(source, abilityParams.get("NumDmg"), null), source, false);
                } else if ((abilityParams.containsKey("AB") && abilityParams.get("AB").equals("LoseLife"))
                        || (abilityParams.containsKey("DB") && abilityParams.get("DB").equals("LoseLife"))) {
                    if (!"TriggeredCardController".equals(abilityParams.get("Defined"))) {
                        continue;
                    }
                    if (!abilityParams.containsKey("LifeAmount")) {
                        continue;
                    }
                    damage += AbilityUtils.calculateAmount(source, abilityParams.get("LifeAmount"), null);
                }
            }
        }
        return damage;
    }

    public static boolean isNegativeCounter(CounterType type, Card c) {
        return type == CounterType.AGE || type == CounterType.BRIBERY || type == CounterType.DOOM
                || type == CounterType.M1M1 || type == CounterType.M0M2 || type == CounterType.M0M1
                || type == CounterType.M1M0 || type == CounterType.M2M1 || type == CounterType.M2M2
                // Blaze only hurts Lands
                || (type == CounterType.BLAZE && c.isLand())
                // Iceberg does use Ice as Storage
                || (type == CounterType.ICE && !"Iceberg".equals(c.getName()))
                // some lands does use Depletion as Storage Counter
                || (type == CounterType.DEPLETION && c.hasKeyword("CARDNAME doesn't untap during your untap step."))
                // treat Time Counters on suspended Cards as Bad,
                // and also on Chronozoa
                || (type == CounterType.TIME && (!c.isInPlay() || "Chronozoa".equals(c.getName())))
                || type == CounterType.GOLD || type == CounterType.MUSIC || type == CounterType.PUPA
                || type == CounterType.PARALYZATION || type == CounterType.SHELL || type == CounterType.SLEEP 
                || type == CounterType.SLEIGHT || type == CounterType.WAGE;
    }

    // this countertypes has no effect
    public static boolean isUselessCounter(CounterType type) {
        return type == CounterType.AWAKENING || type == CounterType.MANIFESTATION || type == CounterType.PETRIFICATION
                || type == CounterType.TRAINING;
    }

    public static Player evaluateBoardPosition(final List<Player> listToEvaluate) {
        Player bestBoardPosition = listToEvaluate.get(0);
        int bestBoardRating = 0;

        for (final Player p : listToEvaluate) {
            int pRating = p.getLife() * 3;
            pRating += p.getLandsInPlay().size() * 2;

            for (final Card c : p.getCardsIn(ZoneType.Battlefield)) {
                pRating += ComputerUtilCard.evaluateCreature(c) / 3;
            }

            if (p.getCardsIn(ZoneType.Library).size() < 3) {
                pRating /= 5;
            }

            System.out.println("Board position evaluation for " + p + ": " + pRating);

            if (pRating > bestBoardRating) {
                bestBoardRating = pRating;
                bestBoardPosition = p;
            }
        }
        return bestBoardPosition;
    }

    public static boolean hasReasonToPlayCardThisTurn(final Player ai, final Card c) {
        if (ai == null || c == null) {
            return false;
        }
        if (!(ai.getController() instanceof PlayerControllerAi)) {
            System.err.println("Unexpected behavior: ComputerUtil::getReasonToPlayCard called with the non-AI player as a parameter.");
            return false;
        }

        for (SpellAbility sa : c.getAllPossibleAbilities(ai, true)) {
            if (sa.getApi() == ApiType.Counter) {
                // return true for counterspells so that the AI can take into account that it may need to cast it later in the opponent's turn
                return true;
            }
            AiPlayDecision decision = ((PlayerControllerAi)ai.getController()).getAi().canPlaySa(sa);
            if (decision == AiPlayDecision.WillPlay || decision == AiPlayDecision.WaitForMain2) {
                return true;
            }
        }

        return false;
    }

    public static boolean lifegainPositive(final Player player, final Card source) {

        if (!player.canGainLife()) {
            return false;
        }

        // Run any applicable replacement effects.
        final Map<String, Object> repParams = Maps.newHashMap();
        repParams.put("Event", "GainLife");
        repParams.put("Affected", player);
        repParams.put("LifeGained", 1);
        repParams.put("Source", source);

        List<ReplacementEffect> list = player.getGame().getReplacementHandler().getReplacementList(repParams,
                ReplacementLayer.None);

        if (Iterables.any(list, CardTraitPredicates.hasParam("AiLogic", "NoLife"))) {
            return false;
        } else if (Iterables.any(list, CardTraitPredicates.hasParam("AiLogic", "LoseLife"))) {
            return false;
        } else if (Iterables.any(list, CardTraitPredicates.hasParam("AiLogic", "LichDraw"))) {
            return false;
        }

        return true;
    }

    public static boolean lifegainNegative(final Player player, final Card source) {
        return lifegainNegative(player, source, 1);
    }

    public static boolean lifegainNegative(final Player player, final Card source, final int n) {

        if (!player.canGainLife()) {
            return false;
        }

        // Run any applicable replacement effects.
        final Map<String, Object> repParams = Maps.newHashMap();
        repParams.put("Event", "GainLife");
        repParams.put("Affected", player);
        repParams.put("LifeGained", n);
        repParams.put("Source", source);

        List<ReplacementEffect> list = player.getGame().getReplacementHandler().getReplacementList(repParams,
                ReplacementLayer.None);

        if (Iterables.any(list, CardTraitPredicates.hasParam("AiLogic", "NoLife"))) {
            // no life gain is not negative
            return false;
        } else if (Iterables.any(list, CardTraitPredicates.hasParam("AiLogic", "LoseLife"))) {
            // lose life is only negagive is the player can lose life
            return player.canLoseLife();
        } else if (Iterables.any(list, CardTraitPredicates.hasParam("AiLogic", "LichDraw"))) {
            // if it would draw more cards than player has, then its negative
            return player.getCardsIn(ZoneType.Library).size() <= n;
        }

        return false;
    }
    
    public static boolean targetPlayableSpellCard(final Player ai, CardCollection options, final SpellAbility sa, final boolean withoutPayingManaCost) {
            // determine and target a card with a SA that the AI can afford and will play
        AiController aic = ((PlayerControllerAi) ai.getController()).getAi();
        Card targetSpellCard = null;
        for (Card c : options) {
            if (withoutPayingManaCost && c.getManaCost() != null && c.getManaCost().getShardCount(ManaCostShard.X) > 0) {
                // The AI will otherwise cheat with the mana payment, announcing X > 0 for spells like Heat Ray when replaying them
                // without paying their mana cost.
                continue;
            }
            for (SpellAbility ab : c.getSpellAbilities()) {
                if (ab.getApi() == null) {
                    // only API-based SAs are supported, other things may lead to a NPE (e.g. Ancestral Vision Suspend SA)
                    continue;
                }
                SpellAbility abTest = withoutPayingManaCost ? ab.copyWithNoManaCost() : ab.copy();
                // at this point, we're assuming that card will be castable from whichever zone it's in by the AI player.
                abTest.setActivatingPlayer(ai);
                abTest.getRestrictions().setZone(c.getZone().getZoneType());
                final boolean play = AiPlayDecision.WillPlay == aic.canPlaySa(abTest);
                final boolean pay = ComputerUtilCost.canPayCost(abTest, ai);
                if (play && pay) {
                    targetSpellCard = c;
                    break;
                }
            }
        }
        if (targetSpellCard == null) {
            return false;
        } else {
            sa.getTargets().add(targetSpellCard);
        }
        return true;
    }


    @Deprecated
    public static final Player getOpponentFor(final Player player) {
        // This method is deprecated and currently functions as a synonym for player.getWeakestOpponent
        // until it can be replaced everywhere in the code.

        // Consider replacing calls to this method either with a multiplayer-friendly determination of
        // opponent that contextually makes the most sense, or with a direct call to player.getWeakestOpponent
        // where that is applicable and makes sense from the point of view of multiplayer AI logic.
        Player opponent = player.getWeakestOpponent();
        if (opponent != null) {
            return opponent;
        }

        throw new IllegalStateException("No opponents left ingame for " + player);
    }

    public static int countUsefulCreatures(Player p) {
        CardCollection creats = p.getCreaturesInPlay();
        int count = 0;

        for (Card c : creats) {
            if (!ComputerUtilCard.isUselessCreature(p, c)) {
                count ++;
            }
        }

        return count;
    }

    public static boolean isPlayingReanimator(final Player ai) {
        // TODO: either add SVars to other reanimator cards, or improve the prediction so that it avoids using a SVar
        // at all but detects this effect from SA parameters (preferred, but difficult)
        CardCollectionView inHand = ai.getCardsIn(ZoneType.Hand);
        CardCollectionView inDeck = ai.getCardsIn(new ZoneType[] {ZoneType.Hand, ZoneType.Library});

        Predicate<Card> markedAsReanimator = new Predicate<Card>() {
            @Override
            public boolean apply(Card card) {
                return "true".equalsIgnoreCase(card.getSVar("IsReanimatorCard"));
            }
        };

        int numInHand = CardLists.filter(inHand, markedAsReanimator).size();
        int numInDeck = CardLists.filter(inDeck, markedAsReanimator).size();

        return numInHand > 0 || numInDeck >= 3;
    }

    public static CardCollection filterAITgts(SpellAbility sa, Player ai, CardCollection srcList, boolean alwaysStrict) {
        final Card source = sa.getHostCard();
        if (source == null) { return srcList; }

        if (sa.hasParam("AITgts")) {
            CardCollection list;
            if (sa.getParam("AITgts").equals("BetterThanSource")) {
                int value = ComputerUtilCard.evaluateCreature(source);
                if (source.isEnchanted()) {
                    for (Card enc : source.getEnchantedBy(false)) {
                        if (enc.getController().equals(ai)) {
                            value += 100; // is 100 per AI's own aura enough?
                        }
                    }
                }
                final int totalValue = value;
                list = CardLists.filter(srcList, new Predicate<Card>() {
                    @Override
                    public boolean apply(final Card c) {
                        return ComputerUtilCard.evaluateCreature(c) > totalValue + 30;
                    }
                });
            } else {
                list = CardLists.getValidCards(srcList, sa.getParam("AITgts"), sa.getActivatingPlayer(), source);
            }

            if (!list.isEmpty() || sa.hasParam("AITgtsStrict") || alwaysStrict) {
                return list;
            } else {
                return srcList;
            }
        }

        return srcList;
    }

    // Check if AI life is in danger/serious danger based on next expected combat
    // assuming a loss of "payment" life
    // call this to determine if it's safe to use a life payment spell
    // or trigger "emergency" strategies such as holding mana for Spike Weaver of Counterspell.
    public static boolean aiLifeInDanger(Player ai, boolean serious, int payment) {
        Player opponent = ComputerUtil.getOpponentFor(ai);
        // test whether the human can kill the ai next turn
        Combat combat = new Combat(opponent);
        boolean containsAttacker = false;
        for (Card att : opponent.getCreaturesInPlay()) {
            if (ComputerUtilCombat.canAttackNextTurn(att, ai)) {
                combat.addAttacker(att, ai);
                containsAttacker = true;
            }
        }
        if (!containsAttacker) {
            return false;
        }
        AiBlockController block = new AiBlockController(ai);
        block.assignBlockersForCombat(combat);

        // TODO predict other, noncombat sources of damage and add them to the "payment" variable.
        // examples : Black Vise, The Rack, known direct damage spells in enemy hand, etc
        // If added, might need a parameter to define whether we want to check all threats or combat threats.

        if ((serious) && (ComputerUtilCombat.lifeInSeriousDanger(ai, combat, payment))) {
            return true;
        }
        if ((!serious) && (ComputerUtilCombat.lifeInDanger(ai, combat, payment))) {
            return true;
        }
        return false;

    }
}
