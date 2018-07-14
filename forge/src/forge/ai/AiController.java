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

import com.esotericsoftware.minlog.Log;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import forge.ai.ability.ChangeZoneAi;
import forge.ai.ability.ExploreAi;
import forge.ai.simulation.SpellAbilityPicker;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.SpellApiBased;
import forge.game.card.*;
import forge.game.card.CardPredicates.Presets;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.*;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.replacement.ReplaceMoved;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.*;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.Aggregates;
import forge.util.Expressions;
import forge.util.MyRandom;
import forge.util.collect.FCollectionView;

import java.security.InvalidParameterException;
import java.util.*;
import java.util.Map.Entry;

/**
 * <p>
 * AiController class.
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class AiController {
    private final Player player;
    private final Game game;
    private final AiCardMemory memory;
    private Combat predictedCombat;
    private boolean useSimulation;
    private SpellAbilityPicker simPicker;

    public void setUseSimulation(boolean value) {
        this.useSimulation = value;
    }
    
    public SpellAbilityPicker getSimulationPicker() {
        return simPicker;
    }
    
    public Game getGame() {
        return game;
    }

    public Player getPlayer() {
        return player;
    }

    public AiCardMemory getCardMemory() {
        return memory;
    }

    public Combat getPredictedCombat() {
        if (predictedCombat == null) {
            AiAttackController aiAtk = new AiAttackController(player);
            predictedCombat = new Combat(player);
            aiAtk.declareAttackers(predictedCombat);
        }
        return predictedCombat;
    }

    public AiController(final Player computerPlayer, final Game game0) {
        player = computerPlayer;
        game = game0;
        memory = new AiCardMemory();
        simPicker = new SpellAbilityPicker(game, player);
    }

    private List<SpellAbility> getPossibleETBCounters() {
        CardCollection all = new CardCollection(player.getCardsIn(ZoneType.Hand));
        CardCollectionView ccvPlayerLibrary = player.getCardsIn(ZoneType.Library);
        
        all.addAll(player.getCardsIn(ZoneType.Exile));
        all.addAll(player.getCardsIn(ZoneType.Graveyard));
        if (!ccvPlayerLibrary.isEmpty()) {
            all.add(ccvPlayerLibrary.get(0));
        }

        for (final Player opp : player.getOpponents()) {
            all.addAll(opp.getCardsIn(ZoneType.Exile));
        }

        final List<SpellAbility> spellAbilities = Lists.newArrayList();
        for (final Card c : all) {
            for (final SpellAbility sa : c.getNonManaAbilities()) {
                if (sa instanceof SpellPermanent) {
                    sa.setActivatingPlayer(player);
                    if (checkETBEffects(c, sa, ApiType.Counter)) {
                        spellAbilities.add(sa);
                    }
                }
            }
        }
        return spellAbilities;
    }
    
    // look for cards on the battlefield that should prevent the AI from using that spellability
    private boolean checkCurseEffects(final SpellAbility sa) {
        CardCollectionView ccvGameBattlefield = game.getCardsIn(ZoneType.Battlefield);
        for (final Card c : ccvGameBattlefield) {
            if (c.hasSVar("AICurseEffect")) {
                final String curse = c.getSVar("AICurseEffect");
                if ("NonActive".equals(curse) && !player.equals(game.getPhaseHandler().getPlayerTurn())) {
                    return true;
                } else {
                    final Card host = sa.getHostCard();
                    if ("DestroyCreature".equals(curse) && sa.isSpell() && host.isCreature()
                            && !host.hasKeyword(Keyword.INDESTRUCTIBLE)) {
                        return true;
                    } else if ("CounterEnchantment".equals(curse) && sa.isSpell() && host.isEnchantment()
                            && CardFactoryUtil.isCounterable(host)) {
                        return true;
                    } else if ("ChaliceOfTheVoid".equals(curse) && sa.isSpell() && CardFactoryUtil.isCounterable(host)
                            && host.getCMC() == c.getCounters(CounterType.CHARGE)) {
                        return true;
                    }  else if ("BazaarOfWonders".equals(curse) && sa.isSpell() && CardFactoryUtil.isCounterable(host)) {
                        String hostName = host.getName();
                        for (Card card : ccvGameBattlefield) {
                            if (!card.isToken() && card.getName().equals(hostName)) {
                                return true;
                            }
                        }
                        for (Card card : game.getCardsIn(ZoneType.Graveyard)) {
                            if (card.getName().equals(hostName)) {
                                return true;
                            }
                        }
                    }
                } 
            }
        }
        return false;
    }

    public boolean checkETBEffects(final Card card, final SpellAbility sa, final ApiType api) {
        if (card.isCreature()
                && game.getStaticEffects().getGlobalRuleChange(GlobalRuleChange.noCreatureETBTriggers)) {
            return api == null;
        }
        boolean rightapi = false;
        String battlefield = ZoneType.Battlefield.toString();
        Player activatingPlayer = sa.getActivatingPlayer();
        
        // Trigger play improvements
        for (final Trigger tr : card.getTriggers()) {
            // These triggers all care for ETB effects

            final Map<String, String> params = tr.getMapParams();
            if (tr.getMode() != TriggerType.ChangesZone) {
                continue;
            }

            if (!params.get("Destination").equals(battlefield)) {
                continue;
            }

            if (params.containsKey("ValidCard")) {
                String validCard = params.get("ValidCard");
                if (!validCard.contains("Self")) {
                    continue;
                }
                if (validCard.contains("notkicked")) {
                    if (sa.isKicked()) {
                        continue;
                    }
                } else if (validCard.contains("kicked")) {
                    if (validCard.contains("kicked ")) { // want a specific kicker
                        String s = validCard.split("kicked ")[1];
                        if ("1".equals(s) && !sa.isOptionalCostPaid(OptionalCost.Kicker1)) continue;
                        if ("2".equals(s) && !sa.isOptionalCostPaid(OptionalCost.Kicker2)) continue;
                    } else if (!sa.isKicked()) { 
                        continue;
                    }
                }
            }

            if (!tr.requirementsCheck(game)) {
                continue;
            }

            // if trigger is not mandatory - no problem
            if (params.get("OptionalDecider") != null && api == null) {
                continue;
            }

            SpellAbility exSA = tr.getOverridingAbility();

            if (exSA == null) {
                // Maybe better considerations
                if (!params.containsKey("Execute")) {
                    continue;
                }
                exSA = AbilityFactory.getAbility(card, params.get("Execute"));
            } else {
                exSA = exSA.copy();
            }

            if (api != null) {
                if (exSA.getApi() != api) {
                    continue;
                }
                rightapi = true;
                if (!(exSA instanceof AbilitySub)) {
                    if (!ComputerUtilCost.canPayCost(exSA, player)) {
                        return false;
                    }
                }
            }

            if (sa != null) {
                exSA.setActivatingPlayer(activatingPlayer);
            }
            else {
                exSA.setActivatingPlayer(player);
            }
            exSA.setTrigger(true);

            // for trigger test, need to ignore the conditions
            SpellAbilityCondition cons = exSA.getConditions();
            if (cons != null) {
                String pres = cons.getIsPresent();
                if (pres != null && pres.matches("Card\\.(Strictly)?Self")) {
                        cons.setIsPresent(null);
                }
            }

            // Run non-mandatory trigger.
            // These checks only work if the Executing SpellAbility is an Ability_Sub.
            if (exSA instanceof AbilitySub && !doTrigger(exSA, false)) {
                // AI would not run this trigger if given the chance
                return false;
            }
        }
        if (api != null && !rightapi) {
            return false;
        }

        // Replacement effects
        for (final ReplacementEffect re : card.getReplacementEffects()) {
            // These Replacements all care for ETB effects

            final Map<String, String> params = re.getMapParams();
            if (!(re instanceof ReplaceMoved)) {
                continue;
            }

            if (!params.get("Destination").equals(battlefield)) {
                continue;
            }

            if (params.containsKey("ValidCard")) {
                String validCard = params.get("ValidCard");
                if (!validCard.contains("Self")) {
                    continue;
                }
                if (validCard.contains("notkicked")) {
                    if (sa.isKicked()) {
                        continue;
                    }
                } else if (validCard.contains("kicked")) {
                    if (validCard.contains("kicked ")) { // want a specific kicker
                        String s = validCard.split("kicked ")[1];
                        if ("1".equals(s) && !sa.isOptionalCostPaid(OptionalCost.Kicker1)) continue;
                        if ("2".equals(s) && !sa.isOptionalCostPaid(OptionalCost.Kicker2)) continue;
                    } else if (!sa.isKicked()) { // otherwise just any must be present
                        continue;
                    }
                }
            }

            if (!re.requirementsCheck(game)) {
                continue;
            }
            final SpellAbility exSA = re.getOverridingAbility();

            if (exSA != null) {
                if (sa != null) {
                    exSA.setActivatingPlayer(activatingPlayer);
                }
                else {
                    exSA.setActivatingPlayer(player);
                }

                if (exSA.getActivatingPlayer() == null) {
                    throw new InvalidParameterException("Executing SpellAbility for Replacement Effect has no activating player");
                }
            }

            // ETBReplacement uses overriding abilities.
            // These checks only work if the Executing SpellAbility is an Ability_Sub.
            if (exSA != null && (exSA instanceof AbilitySub) && !doTrigger(exSA, false)) {
                return false;
            }
        }
        return true;
    }

    private static List<SpellAbility> getPlayableCounters(final CardCollection l) {
        final List<SpellAbility> spellAbility = Lists.newArrayList();
        for (final Card c : l) {
            for (final SpellAbility sa : c.getNonManaAbilities()) {
                // Check if this AF is a Counterpsell
                if (sa.getApi() == ApiType.Counter) {
                    spellAbility.add(sa);
                }
            }
        }
        return spellAbility;
    }

    private CardCollection filterLandsToPlay(CardCollection landList) {
        final CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
        CardCollection nonLandList = CardLists.filter(hand, Predicates.not(CardPredicates.Presets.LANDS));
        if (landList.size() == 1 && nonLandList.size() < 3) {
            CardCollectionView cardsInPlay = player.getCardsIn(ZoneType.Battlefield);
            CardCollection landsInPlay = CardLists.filter(cardsInPlay, Presets.LANDS);
            CardCollection allCards = new CardCollection(player.getCardsIn(ZoneType.Graveyard));
            allCards.addAll(player.getCardsIn(ZoneType.Command));
            allCards.addAll(cardsInPlay);
            int maxCmcInHand = Aggregates.max(hand, CardPredicates.Accessors.fnGetCmc);
            int max = Math.max(maxCmcInHand, 6);
            // consider not playing lands if there are enough already and an ability with a discard cost is present
            if (landsInPlay.size() + landList.size() > max) {
                for (Card c : allCards) {
                    for (SpellAbility sa : c.getSpellAbilities()) {
                        Cost payCosts = sa.getPayCosts();
                        if (payCosts != null) {
                            for (CostPart part : payCosts.getCostParts()) {
                                if (part instanceof CostDiscard) {
                                    return null;
                                }
                            }
                        }
                    }
                }
            }
        }
    
        landList = CardLists.filter(landList, new Predicate<Card>() {
            @Override
            public boolean apply(final Card c) {
                CardCollectionView battlefield = player.getCardsIn(ZoneType.Battlefield);
                canPlaySpellBasic(c, null);
                String name = c.getName();
                if (c.getType().isLegendary() && !name.equals("Flagstones of Trokair")) {
                    if (Iterables.any(battlefield, CardPredicates.nameEquals(name))) {
                        return false;
                    }
                }

                // don't play the land if it has cycling and enough lands are available
                final FCollectionView<SpellAbility> spellAbilities = c.getSpellAbilities();

                final CardCollectionView hand = player.getCardsIn(ZoneType.Hand);
                CardCollection lands = new CardCollection(battlefield);
                lands.addAll(hand);
                lands = CardLists.filter(lands, CardPredicates.Presets.LANDS);
                int maxCmcInHand = Aggregates.max(hand, CardPredicates.Accessors.fnGetCmc);
                for (final SpellAbility sa : spellAbilities) {
                    if (sa.isCycling()) {
                        if (lands.size() >= Math.max(maxCmcInHand, 6)) {
                            return false;
                        }
                    }
                }
                return true;
            }
        });
        return landList;
    }

    private Card chooseBestLandToPlay(CardCollection landList) {
        if (landList.isEmpty()) {
            return null;
        }
        //Skip reflected lands.
        CardCollection unreflectedLands = new CardCollection(landList);
        for (Card l : landList) {
            if (l.isReflectedLand()) {
                unreflectedLands.remove(l);
            }
        }
        if (!unreflectedLands.isEmpty()) {
            landList = unreflectedLands;
        }

        CardCollection nonLandsInHand = CardLists.filter(player.getCardsIn(ZoneType.Hand), Predicates.not(CardPredicates.Presets.LANDS));

        //try to skip lands that enter the battlefield tapped
        if (!nonLandsInHand.isEmpty()) {
            CardCollection nonTappeddLands = new CardCollection();
            for (Card land : landList) {
                // Is this the best way to check if a land ETB Tapped?
                if (land.hasSVar("ETBTappedSVar")) {
                    continue;
                }
                // Glacial Fortress and friends
                if (land.hasSVar("ETBCheckSVar") && CardFactoryUtil.xCount(land, land.getSVar("ETBCheckSVar")) == 0) {
                    continue;
                }
                nonTappeddLands.add(land);
            }
            if (!nonTappeddLands.isEmpty()) {
                landList = nonTappeddLands;
            }
        }

        // Choose first land to be able to play a one drop
        if (player.getLandsInPlay().isEmpty()) {
            CardCollection oneDrops = CardLists.filter(nonLandsInHand, CardPredicates.hasCMC(1));
            for (int i = 0; i < MagicColor.WUBRG.length; i++) {
                byte color = MagicColor.WUBRG[i];
                if (!CardLists.filter(oneDrops, CardPredicates.isColor(color)).isEmpty()) {
                    for (Card land : landList) {
                        if (land.getType().hasSubtype(MagicColor.Constant.BASIC_LANDS.get(i))) {
                            return land;
                        }
                        for (final SpellAbility m : ComputerUtilMana.getAIPlayableMana(land)) {
                            AbilityManaPart mp = m.getManaPart();
                            if (mp.canProduce(MagicColor.toShortString(color), m)) {
                                return land;
                            }
                        }
                    }
                }
            }
        }

        //play lands with a basic type that is needed the most
        final CardCollectionView landsInBattlefield = player.getCardsIn(ZoneType.Battlefield);
        final List<String> basics = Lists.newArrayList();

        // what types can I go get?
        for (final String name : MagicColor.Constant.BASIC_LANDS) {
            if (!CardLists.getType(landList, name).isEmpty()) {
                basics.add(name);
            }
        }
        if (!basics.isEmpty()) {
            // Which basic land is least available
            int minSize = Integer.MAX_VALUE;
            String minType = null;

            for (String b : basics) {
                final int num = CardLists.getType(landsInBattlefield, b).size();
                if (num < minSize) {
                    minType = b;
                    minSize = num;
                }
            }

            if (minType != null) {
                landList = CardLists.getType(landList, minType);
            }

            // pick dual lands if available
            if (Iterables.any(landList, Predicates.not(CardPredicates.Presets.BASIC_LANDS))) {
                landList = CardLists.filter(landList, Predicates.not(CardPredicates.Presets.BASIC_LANDS));
            }
        }
        return landList.get(0);
    }

    // if return true, go to next phase
    private SpellAbility chooseCounterSpell(final List<SpellAbility> possibleCounters) {
        if (possibleCounters == null || possibleCounters.isEmpty()) {
            return null;
        }
        SpellAbility bestSA = null;
        int bestRestriction = Integer.MIN_VALUE;

        for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(possibleCounters, player)) {
            SpellAbility currentSA = sa;
            sa.setActivatingPlayer(player);
            // check everything necessary
            
            
            AiPlayDecision opinion = canPlayAndPayFor(currentSA);
            //PhaseHandler ph = game.getPhaseHandler();
            // System.out.printf("Ai thinks '%s' of %s @ %s %s >>> \n", opinion, sa, Lang.getPossesive(ph.getPlayerTurn().getName()), ph.getPhase());
            if (opinion == AiPlayDecision.WillPlay) {
                if (bestSA == null) {
                    bestSA = currentSA;
                    bestRestriction = ComputerUtil.counterSpellRestriction(player, currentSA);
                } else {
                    // Compare bestSA with this SA
                    final int restrictionLevel = ComputerUtil.counterSpellRestriction(player, currentSA);
    
                    if (restrictionLevel > bestRestriction) {
                        bestRestriction = restrictionLevel;
                        bestSA = currentSA;
                    }
                }
            }
        }

        // TODO - "Look" at Targeted SA and "calculate" the threshold
        // if (bestRestriction < targetedThreshold) return false;
        return bestSA;
    }

    public SpellAbility predictSpellToCastInMain2(ApiType exceptSA) {
        return predictSpellToCastInMain2(exceptSA, true);
    }

    private SpellAbility predictSpellToCastInMain2(ApiType exceptSA, boolean handOnly) {
        if (!getBooleanProperty(AiProps.PREDICT_SPELLS_FOR_MAIN2)) {
            return null;
        }

        final CardCollectionView cards = handOnly ? player.getCardsIn(ZoneType.Hand) :
            ComputerUtilAbility.getAvailableCards(game, player);

        List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(cards, player);
        Collections.sort(all, saComparator); // put best spells first

        for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player)) {
            ApiType saApi = sa.getApi();
            
            if (saApi == ApiType.Counter || saApi == exceptSA) {
                continue;
            }
            sa.setActivatingPlayer(player);
            // TODO: this currently only works as a limited prediction of permanent spells.
            // Ideally this should cast canPlaySa to determine that the AI is truly able/willing to cast a spell,
            // but that is currently difficult to implement due to various side effects leading to stack overflow.
            Card host = sa.getHostCard();
            if (!ComputerUtil.castPermanentInMain1(player, sa) && host != null && !host.isLand() && ComputerUtilCost.canPayCost(sa, player)) {
                if (sa instanceof SpellPermanent) {
                    return sa;
                }
            }
        }
        return null;
    }

    public void reserveManaSources(SpellAbility sa) {
        reserveManaSources(sa, PhaseType.MAIN2, false);
    }

    public void reserveManaSources(SpellAbility sa, PhaseType phaseType, boolean enemy) {
        ManaCostBeingPaid cost = ComputerUtilMana.calculateManaCost(sa, true, 0);
        CardCollection manaSources = ComputerUtilMana.getManaSourcesToPayCost(cost, sa, player);

        AiCardMemory.MemorySet memSet;

        switch (phaseType) {
            case MAIN2:
                memSet = AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2;
                break;
            case COMBAT_DECLARE_BLOCKERS:
                memSet = enemy ? AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK
                    : AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_DECLBLK;
                break;
            default:
                System.out.println("Warning: unsupported mana reservation phase specified for reserveManaSources: "
                        + phaseType.name() + ", reserving until Main 2 instead. Consider adding support for the phase if needed.");
                memSet = AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2;
                break;
        }

        for (Card c : manaSources) {
            AiCardMemory.rememberCard(player, c, memSet);
        }
    }

    // This is for playing spells regularly (no Cascade/Ripple etc.)
    private AiPlayDecision canPlayAndPayFor(final SpellAbility sa) {
        if (!sa.canPlay()) {
            return AiPlayDecision.CantPlaySa;
        }

        AiPlayDecision decision = ComputerUtilCost.canPayCost(sa, player) ? AiPlayDecision.WillPlay : AiPlayDecision.CantAfford;
        if(decision == AiPlayDecision.CantAfford) {
        	return decision;
        }
        return canPlaySa(sa);
    }

    public AiPlayDecision canPlaySa(SpellAbility sa) {
        final Card card = sa.getHostCard();
        if (!checkAiSpecificRestrictions(sa)) {
            return AiPlayDecision.CantPlayAi;
        }
        if (sa instanceof WrappedAbility) {
            return canPlaySa(((WrappedAbility) sa).getWrappedAbility());
        }
        if (sa.getApi() != null) {
            boolean canPlay = SpellApiToAi.Converter.get(sa.getApi()).canPlayAIWithSubs(player, sa);
            if (!canPlay) {
                return AiPlayDecision.CantPlayAi;
            }
        }
        else if (sa.getPayCosts() != null){
            Cost payCosts = sa.getPayCosts();
            ManaCost mana = payCosts.getTotalMana();
            if (mana != null) {
                if(mana.countX() > 0) {
                    // Set PayX here to maximum value.
                    final int xPay = ComputerUtilMana.determineLeftoverMana(sa, player);
                    if (xPay <= 0) {
                        return AiPlayDecision.CantAffordX;
                    }
                    card.setSVar("PayX", Integer.toString(xPay));
                } else if (mana.isZero()) {
                    // if mana is zero, but card mana cost does have X, then something is wrong
                    ManaCost cardCost = card.getManaCost(); 
                    if (cardCost != null && cardCost.countX() > 0) {
                        return AiPlayDecision.CantPlayAi;
                    }
                }
            }
        }
        if (checkCurseEffects(sa)) {
            return AiPlayDecision.CurseEffects;
        }
        if (sa instanceof SpellPermanent) {
            return canPlayFromEffectAI((SpellPermanent)sa, false, true);
        }
        if (sa.usesTargeting() && !sa.isTargetNumberValid()) {
            if (!sa.getTargetRestrictions().hasCandidates(sa, true)) {
                return AiPlayDecision.TargetingFailed;
            }
        }
        if (sa instanceof Spell) {
            if (ComputerUtil.getDamageForPlaying(player, sa) >= player.getLife() 
                    && !player.cantLoseForZeroOrLessLife() && player.canLoseLife()) {
                return AiPlayDecision.CurseEffects;
            }
            return canPlaySpellBasic(card, sa);
        }
        return AiPlayDecision.WillPlay;
    }

    public boolean isNonDisabledCardInPlay(final String cardName) {
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.getName().equals(cardName)) {
                // TODO - Better logic to detemine if a permanent is disabled by local effects
                // currently assuming any permanent enchanted by another player
                // is disabled and a second copy is necessary
                // will need actual logic that determines if the enchantment is able
                // to disable the permanent or it's still functional and a duplicate is unneeded.
                boolean disabledByEnemy = false;
                for (Card card2 : card.getEnchantedBy(false)) {
                    if (card2.getOwner() != player) {
                        disabledByEnemy = true;
                    }
                }
                if (!disabledByEnemy) {
                    return true;
                }
            }
        }
        return false;
    }

    private AiPlayDecision canPlaySpellBasic(final Card card, final SpellAbility sa) {
        if ("True".equals(card.getSVar("NonStackingEffect")) && isNonDisabledCardInPlay(card.getName())) {
            return AiPlayDecision.NeedsToPlayCriteriaNotMet;
        }
        // add any other necessary logic to play a basic spell here
        return ComputerUtilCard.checkNeedsToPlayReqs(card, sa);
    }

    // not sure "playing biggest spell" matters?
    private final static Comparator<SpellAbility> saComparator = new Comparator<SpellAbility>() {
        @Override
        public int compare(final SpellAbility a, final SpellAbility b) {
            // sort from highest cost to lowest
            // we want the highest costs first
            int a1 = a.getPayCosts() == null ? 0 : a.getPayCosts().getTotalMana().getCMC();
            int b1 = b.getPayCosts() == null ? 0 : b.getPayCosts().getTotalMana().getCMC();

            // deprioritize planar die roll marked with AIRollPlanarDieParams:LowPriority$ True
            if (ApiType.RollPlanarDice == a.getApi() && a.getHostCard() != null && a.getHostCard().hasSVar("AIRollPlanarDieParams") && a.getHostCard().getSVar("AIRollPlanarDieParams").toLowerCase().matches(".*lowpriority\\$\\s*true.*")) {
                return 1;
            } else if (ApiType.RollPlanarDice == b.getApi() && b.getHostCard() != null && b.getHostCard().hasSVar("AIRollPlanarDieParams") && b.getHostCard().getSVar("AIRollPlanarDieParams").toLowerCase().matches(".*lowpriority\\$\\s*true.*")) {
                return -1;
            }

            // deprioritize pump spells with pure energy cost (can be activated last,
            // since energy is generally scarce, plus can benefit e.g. Electrostatic Pummeler)
            int a2 = 0, b2 = 0;
            if (a.getApi() == ApiType.Pump && a.getPayCosts() != null && a.getPayCosts().getCostEnergy() != null) {
                if (a.getPayCosts().hasOnlySpecificCostType(CostPayEnergy.class)) {
                    a2 = a.getPayCosts().getCostEnergy().convertAmount();
                }
            }
            if (b.getApi() == ApiType.Pump && b.getPayCosts() != null && b.getPayCosts().getCostEnergy() != null) {
                if (b.getPayCosts().hasOnlySpecificCostType(CostPayEnergy.class)) {
                    b2 = b.getPayCosts().getCostEnergy().convertAmount();
                }
            }
            if (a2 == 0 && b2 > 0) {
                return -1;
            } else if (b2 == 0 && a2 > 0) {
                return 1;
            }

            // cast 0 mana cost spells first (might be a Mox)
            if (a1 == 0 && b1 > 0 && ApiType.Mana != a.getApi()) {
                return -1;
            } else if (a1 > 0 && b1 == 0 && ApiType.Mana != b.getApi()) {
                return 1;
            }
            
            if (a.getHostCard() != null && a.getHostCard().hasSVar("FreeSpellAI")) {
                return -1;
            } else if (b.getHostCard() != null && b.getHostCard().hasSVar("FreeSpellAI")) {
                return 1;
            }

            a1 += getSpellAbilityPriority(a);
            b1 += getSpellAbilityPriority(b);

            return b1 - a1;
        }
        
        private int getSpellAbilityPriority(SpellAbility sa) {
            int p = 0;
            Card source = sa.getHostCard();
            final Player ai = source == null ? sa.getActivatingPlayer() : source.getController();
            if (ai == null) {
                System.err.println("Error: couldn't figure out the activating player and host card for SA: " + sa);
                return 0;
            }
            final boolean noCreatures = ai.getCreaturesInPlay().isEmpty();

            if (source != null) {
                // puts creatures in front of spells
                if (source.isCreature()) {
                    p += 1;
                }
                // don't play equipments before having any creatures
                if (source.isEquipment() && noCreatures) {
                    p -= 9;
                }
                // 1. increase chance of using Surge effects
                // 2. non-surged versions are usually inefficient
                if (source.getOracleText().contains("surge cost") && !sa.isSurged()) {
                    p -= 9;
                }
                // move snap-casted spells to front
                if (source.isInZone(ZoneType.Graveyard)) {
                    if (sa.getMayPlay() != null && source.mayPlay(sa.getMayPlay()) != null) {
                        p += 50;
                    }
                }
                // if the profile specifies it, deprioritize Storm spells in an attempt to build up storm count
                if (source.hasKeyword(Keyword.STORM) && ai.getController() instanceof PlayerControllerAi) {
                    p -= (((PlayerControllerAi) ai.getController()).getAi().getIntProperty(AiProps.PRIORITY_REDUCTION_FOR_STORM_SPELLS));
                }
            }

            // use Surge and Prowl costs when able to
            if (sa.isSurged() || 
                    (sa.getRestrictions().getProwlTypes() != null && !sa.getRestrictions().getProwlTypes().isEmpty())) {
                p += 9;
            }
            // sort planeswalker abilities with most costly first
            if (sa.getRestrictions().isPwAbility()) {
                final CostPart cost = sa.getPayCosts().getCostParts().get(0);
                if (cost instanceof CostRemoveCounter) {
                    p += cost.convertAmount() == null ? 1 : cost.convertAmount();
                } else if (cost instanceof CostPutCounter) {
                    p -= cost.convertAmount();
                }
                if (sa.hasParam("Ultimate")) {
                    p += 9;
                }
            }

            if (ApiType.DestroyAll == sa.getApi()) {
                p += 4;
            } else if (ApiType.Mana == sa.getApi()) {
                p -= 9;
            }

            // try to cast mana ritual spells before casting spells to maximize potential mana
            if ("ManaRitual".equals(sa.getParam("AILogic"))) {
                p += 9;
            }

            return p;
        }
    };

    public CardCollection getCardsToDiscard(final int numDiscard, final String[] uTypes, final SpellAbility sa) {
        return getCardsToDiscard(numDiscard, uTypes, sa, CardCollection.EMPTY);
    }

    public CardCollection getCardsToDiscard(final int numDiscard, final String[] uTypes, final SpellAbility sa, final CardCollectionView exclude) {
        CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
        hand.removeAll(exclude);
        if ((uTypes != null) && (sa != null)) {
            hand = CardLists.getValidCards(hand, uTypes, sa.getActivatingPlayer(), sa.getHostCard(), sa);
        }
        return getCardsToDiscard(numDiscard, numDiscard, hand, sa);
    }

    public CardCollection getCardsToDiscard(int min, final int max, final CardCollection validCards, final SpellAbility sa) {
        if (validCards.size() < min) {
            return null;
        }

        Card sourceCard = null;
        final CardCollection discardList = new CardCollection();
        int count = 0;
        if (sa != null) {
            sourceCard = sa.getHostCard();
            if ("Always".equals(sa.getParam("AILogic")) && !validCards.isEmpty()) {
                min = 1;
            } else if ("VolrathsShapeshifter".equals(sa.getParam("AILogic"))) {
                return SpecialCardAi.VolrathsShapeshifter.targetBestCreature(player, sa);
            }

            if (sa.hasParam("AnyNumber")) {
                if ("DiscardUncastableAndExcess".equals(sa.getParam("AILogic"))) {
                    CardCollection discards = new CardCollection();
                    final CardCollectionView inHand = player.getCardsIn(ZoneType.Hand);
                    final int numLandsOTB = CardLists.filter(player.getCardsIn(ZoneType.Hand), CardPredicates.Presets.LANDS).size();
                    int numOppInHand = 0;
                    for (Player p : player.getGame().getPlayers()) {
                        if (p.getCardsIn(ZoneType.Hand).size() > numOppInHand) {
                            numOppInHand = p.getCardsIn(ZoneType.Hand).size();
                        }
                    }
                    for (Card c : inHand) {
                        if (c.hasSVar("DoNotDiscardIfAble") || c.hasSVar("IsReanimatorCard")) { continue; }
                        if (c.isCreature() && !ComputerUtilMana.hasEnoughManaSourcesToCast(c.getSpellPermanent(), player)) {
                            discards.add(c);
                        }
                        if ((c.isLand() && numLandsOTB >= 5) || (c.getFirstSpellAbility() != null && !ComputerUtilMana.hasEnoughManaSourcesToCast(c.getFirstSpellAbility(), player))) {
                            if (discards.size() + 1 <= numOppInHand) {
                                discards.add(c);
                            }
                        }
                    }
                    return discards;
                }
            }

        }

        // look for good discards
        while (count < min) {
            Card prefCard = null;
            if (sa != null && sa.getActivatingPlayer() != null && sa.getActivatingPlayer().isOpponentOf(player)) {
                for (Card c : validCards) {
                    if (c.hasKeyword("If a spell or ability an opponent controls causes you to discard CARDNAME,"
                            + " put it onto the battlefield instead of putting it into your graveyard.")
                            || !c.getSVar("DiscardMeByOpp").isEmpty()) {
                        prefCard = c;
                        break;
                    }
                }
            }
            if (prefCard == null) {
                prefCard = ComputerUtil.getCardPreference(player, sourceCard, "DiscardCost", validCards);
                if (prefCard != null && prefCard.hasSVar("DoNotDiscardIfAble")) {
                    prefCard = null;
                }
            }
            if (prefCard != null) {
                discardList.add(prefCard);
                validCards.remove(prefCard);
                count++;
            }
            else {
                break;
            }
        }

        final int discardsLeft = min - count;

        // choose rest
        for (int i = 0; i < discardsLeft; i++) {
            if (validCards.isEmpty()) {
                continue;
            }
            final int numLandsInPlay = Iterables.size(Iterables.filter(player.getCardsIn(ZoneType.Battlefield), CardPredicates.Presets.LANDS));
            final CardCollection landsInHand = CardLists.filter(validCards, CardPredicates.Presets.LANDS);
            final int numLandsInHand = landsInHand.size();
    
            // Discard a land
            boolean canDiscardLands = numLandsInHand > 3  || (numLandsInHand > 2 && numLandsInPlay > 0)
            || (numLandsInHand > 1 && numLandsInPlay > 2) || (numLandsInHand > 0 && numLandsInPlay > 5);
    
            if (canDiscardLands) {
                discardList.add(landsInHand.get(0));
                validCards.remove(landsInHand.get(0));
            }
            else { // Discard other stuff
                CardLists.sortByCmcDesc(validCards);
                int numLandsAvailable = numLandsInPlay;
                if (numLandsInHand > 0) {
                    numLandsAvailable++;
                }

                //Discard unplayable card
                boolean discardedUnplayable = false;
                for (int j = 0; j < validCards.size(); j++) {
                    if (validCards.get(j).getCMC() > numLandsAvailable && !validCards.get(j).hasSVar("DoNotDiscardIfAble")) {
                        discardList.add(validCards.get(j));
                        validCards.remove(validCards.get(j));
                        discardedUnplayable = true;
                        break;
                    } else if (validCards.get(j).getCMC() <= numLandsAvailable) {
                        // cut short to avoid looping over cards which are guaranteed not to fit the criteria
                        break;
                    }
                }

                if (!discardedUnplayable) {
                    // discard worst card
                    Card worst = ComputerUtilCard.getWorstAI(validCards);
                    if (worst == null) {
                        // there were only instants and sorceries, and maybe cards that are not good to discard, so look
                        // for more discard options
                        worst = ComputerUtilCard.getCheapestSpellAI(validCards);
                    }
                    if (worst == null && !validCards.isEmpty()) {
                        // still nothing chosen, so choose the first thing that works, trying not to make DoNotDiscardIfAble
                        // discards
                        for (Card c : validCards) {
                            if (!c.hasSVar("DoNotDiscardIfAble")) {
                                worst = c;
                                break;
                            }
                        }
                        // Only DoNotDiscardIfAble cards? If we have a duplicate for something, discard it
                        if (worst == null) {
                            for (Card c : validCards) {
                                if (CardLists.filter(player.getCardsIn(ZoneType.Hand), CardPredicates.nameEquals(c.getName())).size() > 1) {
                                    worst = c;
                                    break;
                                }
                            }
                            if (worst == null) {
                                // Otherwise just grab a random card and discard it
                                worst = Aggregates.random(validCards);
                            }
                        }
                    }
                    discardList.add(worst);
                    validCards.remove(worst);
                }
            }
        }
        return discardList;
    }

    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message) {
        ApiType api = sa.getApi();

        // Abilities without api may also use this routine, However they should provide a unique mode value
        if (api == null) {
            String exMsg = String.format("AI confirmAction does not know what to decide about %s mode (api is null).",
                    mode);
            throw new IllegalArgumentException(exMsg);
        }
        return SpellApiToAi.Converter.get(api).confirmAction(player, sa, mode, message);
    }

    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, int bid, Player winner) {
        if (mode != null) switch (mode) {
            case BidLife:
                if (sa.hasParam("AIBidMax")) {
                    return !player.equals(winner) && bid < Integer.parseInt(sa.getParam("AIBidMax")) && player.getLife() > bid + 5;
                }
                return false;
            default:
                return false;
        } 
        return false;
    }

    public boolean confirmStaticApplication(Card hostCard, GameEntity affected, String logic, String message) {
        if (logic.equalsIgnoreCase("ProtectFriendly")) {
            final Player controller = hostCard.getController();
            if (affected instanceof Player) {
                return !((Player) affected).isOpponentOf(controller);
            }
            if (affected instanceof Card) {
                return !((Card) affected).getController().isOpponentOf(controller);
            }
        }
        return true;
    }

    public String getProperty(AiProps propName) {
        return AiProfileUtil.getAIProp(getPlayer().getLobbyPlayer(), propName);
    }

    public int getIntProperty(AiProps propName) {
        String prop = AiProfileUtil.getAIProp(getPlayer().getLobbyPlayer(), propName);

        if (prop == null || prop.isEmpty()) {
            return Integer.parseInt(propName.getDefault());
        }

        return Integer.parseInt(prop);
    }

    public boolean getBooleanProperty(AiProps propName) {
        String prop = AiProfileUtil.getAIProp(getPlayer().getLobbyPlayer(), propName);

        if (prop == null || prop.isEmpty()) {
            return Boolean.parseBoolean(propName.getDefault());
        }

        return Boolean.parseBoolean(prop);
    }

    public AiPlayDecision canPlayFromEffectAI(Spell spell, boolean mandatory, boolean withoutPayingManaCost) {
        int damage = ComputerUtil.getDamageForPlaying(player, spell);
        if (damage >= player.getLife() && !player.cantLoseForZeroOrLessLife() && player.canLoseLife()) {
            return AiPlayDecision.CurseEffects;
        }

        final Card card = spell.getHostCard();
        if (spell instanceof SpellApiBased) {
            boolean chance = false;
            if (withoutPayingManaCost) {
                chance = SpellApiToAi.Converter.get(spell.getApi()).doTriggerNoCostWithSubs(player, spell, mandatory);
            } else {
                chance = SpellApiToAi.Converter.get(spell.getApi()).doTriggerAI(player, spell, mandatory);
            }
            if (!chance)
                return AiPlayDecision.TargetingFailed;

            if (spell instanceof SpellPermanent) {
                if (mandatory) {
                    return AiPlayDecision.WillPlay;
                }

                if (!checkETBEffects(card, spell, null)) {
                    return AiPlayDecision.BadEtbEffects;
                }
                if (damage + ComputerUtil.getDamageFromETB(player, card) >= player.getLife()
                        && !player.cantLoseForZeroOrLessLife() && player.canLoseLife()) {
                    return AiPlayDecision.BadEtbEffects;
                }
            }
        }

        return canPlaySpellBasic(card, spell);
    }

    // declares blockers for given defender in a given combat
    public void declareBlockersFor(Player defender, Combat combat) {
        AiBlockController block = new AiBlockController(defender);
        // When player != defender, AI should declare blockers for its benefit.
        block.assignBlockersForCombat(combat);
    }

    public void declareAttackers(Player attacker, Combat combat) {
        // 12/2/10(sol) the decision making here has moved to getAttackers()
        AiAttackController aiAtk = new AiAttackController(attacker); 
        aiAtk.declareAttackers(combat);

        // if invalid: just try an attack declaration that we know to be legal
        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            final Map<Card, GameEntity> legal = combat.getAttackConstraints().getLegalAttackers().getLeft();
            System.err.println("AI Attack declaration invalid, defaulting to: " + legal);
            for (final Map.Entry<Card, GameEntity> mandatoryAttacker : legal.entrySet()) {
                combat.addAttacker(mandatoryAttacker.getKey(), mandatoryAttacker.getValue());
            }
            if (!CombatUtil.validateAttackers(combat)) {
                aiAtk.declareAttackers(combat);
            }
        }

        for (final Card element : combat.getAttackers()) {
            // tapping of attackers happens after Propaganda is paid for
            final StringBuilder sb = new StringBuilder();
            sb.append("Computer just assigned ").append(element.getName()).append(" as an attacker.");
            Log.debug(sb.toString());
        }
    }

    private List<SpellAbility> singleSpellAbilityList(SpellAbility sa) {
        if (sa == null) { return null; }

        // System.out.println("Chosen to play: " + sa);

        final List<SpellAbility> abilities = Lists.newArrayList();
        abilities.add(sa);
        return abilities;
    }

    public List<SpellAbility> chooseSpellAbilityToPlay() {
        // Reset cached predicted combat, as it may be stale. It will be
        // re-created if needed and used for any AI logic that needs it.
        predictedCombat = null;

        if (useSimulation) {
            return singleSpellAbilityList(simPicker.chooseSpellAbilityToPlay(null));
        }

        CardCollection landsWannaPlay = ComputerUtilAbility.getAvailableLandsToPlay(game, player);
        CardCollection playBeforeLand = CardLists.filter(
            player.getCardsIn(ZoneType.Hand), CardPredicates.hasSVar("PlayBeforeLandDrop")
        );

        if (!playBeforeLand.isEmpty()) {
            SpellAbility wantToPlayBeforeLand = chooseSpellAbilityToPlayFromList(
                ComputerUtilAbility.getSpellAbilities(playBeforeLand, player), false
            );
            if (wantToPlayBeforeLand != null) {
                return singleSpellAbilityList(wantToPlayBeforeLand);
            }
        }

        if (landsWannaPlay != null) {
            landsWannaPlay = filterLandsToPlay(landsWannaPlay);
            Log.debug("Computer " + game.getPhaseHandler().getPhase().nameForUi);
            if (landsWannaPlay != null && !landsWannaPlay.isEmpty() && player.canPlayLand(null)) {
                // TODO search for other land it might want to play?
                Card land = chooseBestLandToPlay(landsWannaPlay);
                if (ComputerUtil.getDamageFromETB(player, land) < player.getLife() || !player.canLoseLife() 
                        || player.cantLoseForZeroOrLessLife() ) {
                    if (!game.getPhaseHandler().is(PhaseType.MAIN1) || !isSafeToHoldLandDropForMain2(land)) {
                        final List<SpellAbility> abilities = Lists.newArrayList();

                        LandAbility la = new LandAbility(land, player, null);
                        if (la.canPlay()) {
                            abilities.add(la);
                        }

                        // add mayPlay option
                        for (CardPlayOption o : land.mayPlay(player)) {
                            la = new LandAbility(land, player, o.getAbility());
                            if (la.canPlay()) {
                                abilities.add(la);
                            }
                        }
                        if (!abilities.isEmpty()) {
                            return abilities;
                        }
                    }
                }
            }
        }

        return singleSpellAbilityList(getSpellAbilityToPlay());
    }

    private boolean isSafeToHoldLandDropForMain2(Card landToPlay) {
        boolean hasMomir = !CardLists.filter(player.getCardsIn(ZoneType.Command),
                CardPredicates.nameEquals("Momir Vig, Simic Visionary Avatar")).isEmpty();
        if (hasMomir) {
            // Don't do this in Momir variants since it messes with the AI decision making for the avatar.
            return false;
        }

        if (!MyRandom.percentTrue(getIntProperty(AiProps.HOLD_LAND_DROP_FOR_MAIN2_IF_UNUSED))) {
            // check against the chance specified in the profile
            return false;
        }
        if (game.getPhaseHandler().getTurn() <= 2) {
            // too obvious when doing it on the very first turn of the game
            return false;
        }

        CardCollection inHand = CardLists.filter(player.getCardsIn(ZoneType.Hand),
                Predicates.not(CardPredicates.Presets.LANDS));
        CardCollectionView otb = player.getCardsIn(ZoneType.Battlefield);

        if (getBooleanProperty(AiProps.HOLD_LAND_DROP_ONLY_IF_HAVE_OTHER_PERMS)) {
            if (CardLists.filter(otb, Predicates.not(CardPredicates.Presets.LANDS)).isEmpty()) {
                return false;
            }
        }

        // TODO: improve the detection of taplands
        boolean isTapLand = false;
        for (ReplacementEffect repl : landToPlay.getReplacementEffects()) {
            if (repl.getParamOrDefault("Description", "").equals("CARDNAME enters the battlefield tapped.")) {
                isTapLand = true;
            }
        }

        int totalCMCInHand = Aggregates.sum(inHand, CardPredicates.Accessors.fnGetCmc);
        int minCMCInHand = Aggregates.min(inHand, CardPredicates.Accessors.fnGetCmc);
        int predictedMana = ComputerUtilMana.getAvailableManaEstimate(player, true);

        boolean canCastWithLandDrop = (predictedMana + 1 >= minCMCInHand) && !isTapLand;
        boolean cantCastAnythingNow = predictedMana < minCMCInHand;

        boolean hasRelevantAbsOTB = !CardLists.filter(otb, new Predicate<Card>() {
            @Override
            public boolean apply(Card card) {
                boolean isTapLand = false;
                for (ReplacementEffect repl : card.getReplacementEffects()) {
                    // TODO: improve the detection of taplands
                    if (repl.getParamOrDefault("Description", "").equals("CARDNAME enters the battlefield tapped.")) {
                        isTapLand = true;
                    }
                }

                for (SpellAbility sa : card.getSpellAbilities()) {
                    if (sa.getPayCosts() != null && sa.isAbility()
                            && sa.getPayCosts().getCostMana() != null
                            && sa.getPayCosts().getCostMana().getMana().getCMC() > 0
                            && (!sa.getPayCosts().hasTapCost() || !isTapLand)
                            && (!sa.hasParam("ActivationZone") || sa.getParam("ActivationZone").contains("Battlefield"))) {
                        return true;
                    }
                }
                return false;
            }
        }).isEmpty();

        boolean hasLandBasedEffect = !CardLists.filter(otb, new Predicate<Card>() {
            @Override
            public boolean apply(Card card) {
                for (Trigger t : card.getTriggers()) {
                    Map<String, String> params = t.getMapParams();
                    if ("ChangesZone".equals(params.get("Mode"))
                            && params.containsKey("ValidCard")
                            && !params.get("ValidCard").contains("nonLand")
                            && ((params.get("ValidCard").contains("Land")) || (params.get("ValidCard").contains("Permanent")))
                            && "Battlefield".equals(params.get("Destination"))) {
                        // Landfall and other similar triggers
                        return true;
                    }
                }
                for (String sv : card.getSVars().keySet()) {
                    String varValue = card.getSVar(sv);
                    if (varValue.startsWith("Count$Valid") || sv.equals("BuffedBy")) {
                        if (varValue.contains("Land") || varValue.contains("Plains") || varValue.contains("Forest")
                                || varValue.contains("Mountain") || varValue.contains("Island") || varValue.contains("Swamp")
                                || varValue.contains("Wastes")) {
                            // In presence of various cards that get buffs like "equal to the number of lands you control",
                            // safer for our AI model to just play the land earlier rather than make a blunder
                            return true;
                        }
                    }
                }
                return false;
            }
        }).isEmpty();

        // TODO: add prediction for effects that will untap a tapland as it enters the battlefield
        if (!canCastWithLandDrop && cantCastAnythingNow && !hasLandBasedEffect && (!hasRelevantAbsOTB || isTapLand)) {
            // Hopefully there's not much to do with the extra mana immediately, can wait for Main 2
            return true;
        }
        if ((predictedMana <= totalCMCInHand && canCastWithLandDrop) || (hasRelevantAbsOTB && !isTapLand) || hasLandBasedEffect) {
            // Might need an extra land to cast something, or for some kind of an ETB ability with a cost or an
            // alternative cost (if we cast it in Main 1), or to use an activated ability on the battlefield
            return false;
        }

        return true;
    }

    private final SpellAbility getSpellAbilityToPlay() {
        // if top of stack is owned by me
        if (!game.getStack().isEmpty() && game.getStack().peekAbility().getActivatingPlayer().equals(player)) {
            // probably should let my stuff resolve
            return null;
        }
        final CardCollection cards = ComputerUtilAbility.getAvailableCards(game, player);

        if (!game.getStack().isEmpty()) {
            SpellAbility counter = chooseCounterSpell(getPlayableCounters(cards));
            if (counter != null) return counter;
    
            SpellAbility counterETB = chooseSpellAbilityToPlayFromList(getPossibleETBCounters(), false);
            if (counterETB != null)
                return counterETB;
        }

        return chooseSpellAbilityToPlayFromList(ComputerUtilAbility.getSpellAbilities(cards, player), true);
    }

    private SpellAbility chooseSpellAbilityToPlayFromList(final List<SpellAbility> all, boolean skipCounter) {
        if (all == null || all.isEmpty())
            return null;

        Collections.sort(all, saComparator); // put best spells first
        
        for (final SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player)) {
            // Don't add Counterspells to the "normal" playcard lookups
            if (skipCounter && sa.getApi() == ApiType.Counter) {
                continue;
            }

            if (sa.getHostCard().hasKeyword(Keyword.STORM)
                    && sa.getApi() != ApiType.Counter // AI would suck at trying to deliberately proc a Storm counterspell
                    && CardLists.filter(player.getCardsIn(ZoneType.Hand), Predicates.not(Predicates.or(CardPredicates.Presets.LANDS, CardPredicates.hasKeyword("Storm")))).size() > 0) {
                if (game.getView().getStormCount() < this.getIntProperty(AiProps.MIN_COUNT_FOR_STORM_SPELLS)) {
                    // skip evaluating Storm unless we reached the minimum Storm count
                    continue;
                }
            }

            sa.setActivatingPlayer(player);
            sa.setLastStateBattlefield(game.getLastStateBattlefield());
            sa.setLastStateGraveyard(game.getLastStateGraveyard());
            
            AiPlayDecision opinion = canPlayAndPayFor(sa);
            // PhaseHandler ph = game.getPhaseHandler();
            // System.out.printf("Ai thinks '%s' of %s -> %s @ %s %s >>> \n", opinion, sa.getHostCard(), sa, Lang.getPossesive(ph.getPlayerTurn().getName()), ph.getPhase());
            
            if (opinion != AiPlayDecision.WillPlay)
                continue;
    
            return sa;
        }
        
        return null;
    }

    public CardCollection chooseCardsToDelve(int genericCost, CardCollection grave) {
        CardCollection toExile = new CardCollection();
        int numToExile = Math.min(grave.size(), genericCost);
        
        for (int i = 0; i < numToExile; i++) {
            Card chosen = null;
            for (final Card c : grave) { // Exile noncreatures first in
                // case we can revive. Might
                // wanna do some additional
                // checking here for Flashback
                // and the like.
                if (!c.isCreature()) {
                    chosen = c;
                    break;
                }
            }
            if (chosen == null) {
                chosen = ComputerUtilCard.getWorstCreatureAI(grave);
            }

            if (chosen == null) {
                // Should never get here but... You know how it is.
                chosen = grave.get(0);
            }

            toExile.add(chosen);
            grave.remove(chosen);
        }
        return toExile;
    }
    
    public boolean doTrigger(SpellAbility spell, boolean mandatory) {
        if (spell.getApi() != null)
            return SpellApiToAi.Converter.get(spell.getApi()).doTriggerAI(player, spell, mandatory);
        if (spell instanceof WrappedAbility)
            return doTrigger(((WrappedAbility)spell).getWrappedAbility(), mandatory);
        if (spell.getPayCosts() == Cost.Zero && spell.getTargetRestrictions() == null) {
            // For non-converted triggers (such as Cumulative Upkeep) that don't have costs or targets to worry about
            return true;
        }
        
        return false;
    }
    
    /**
     * Ai should run.
     *
     * @param sa the sa
     * @return true, if successful
     */
    public final boolean aiShouldRun(final ReplacementEffect effect, final SpellAbility sa) {
        Card hostCard = effect.getHostCard();
        if (hostCard.hasAlternateState()) {
            hostCard = game.getCardState(hostCard);
        }
        if(effect.toString().startsWith("Unleash (") && player.getLife() <= 5) {
            return false;
        }

        if (effect.getMapParams().containsKey("AICheckSVar")) {
            System.out.println("aiShouldRun?" + sa);
            final String svarToCheck = effect.getMapParams().get("AICheckSVar");
            String comparator = "GE";
            int compareTo = 1;

            if (effect.getMapParams().containsKey("AISVarCompare")) {
                final String fullCmp = effect.getMapParams().get("AISVarCompare");
                comparator = fullCmp.substring(0, 2);
                final String strCmpTo = fullCmp.substring(2);
                try {
                    compareTo = Integer.parseInt(strCmpTo);
                } catch (final Exception ignored) {
                    if (sa == null) {
                        compareTo = CardFactoryUtil.xCount(hostCard, hostCard.getSVar(strCmpTo));
                    } else {
                        compareTo = AbilityUtils.calculateAmount(hostCard, hostCard.getSVar(strCmpTo), sa);
                    }
                }
            }

            int left = 0;

            if (sa == null) {
                left = CardFactoryUtil.xCount(hostCard, hostCard.getSVar(svarToCheck));
            } else {
                left = AbilityUtils.calculateAmount(hostCard, svarToCheck, sa);
            }
            System.out.println("aiShouldRun?" + left + comparator + compareTo);
            if (Expressions.compare(left, comparator, compareTo)) {
                return true;
            }
        } else if (effect.getMapParams().containsKey("AICheckDredge")) {
            return player.getCardsIn(ZoneType.Library).size() > 8 || player.isCardInPlay("Laboratory Maniac");
        } else if (sa != null && doTrigger(sa, false)) {
            return true;
        }

        return false;
    }

    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        // AI would play everything. But limits to one copy of (Leyline of Singularity) and (Gemstone Caverns)
        
        List<SpellAbility> result = Lists.newArrayList();
        for(SpellAbility sa : usableFromOpeningHand) {
            // Is there a better way for the AI to decide this?
            if (doTrigger(sa, false)) {
                result.add(sa);
            }
        }
        
        boolean hasLeyline1 = false;
        SpellAbility saGemstones = null;
        
        for(int i = 0; i < result.size(); i++) {
            SpellAbility sa = result.get(i);
            
            String srcName = sa.getHostCard().getName();
            if ("Gemstone Caverns".equals(srcName)) {
                if (saGemstones == null)
                    saGemstones = sa;
                else
                    result.remove(i--);
            } else if ("Leyline of Singularity".equals(srcName)) {
                if (!hasLeyline1)
                    hasLeyline1 = true;
                else
                    result.remove(i--);
            }
        }
        
        // Play them last
        if (saGemstones != null) {
            result.remove(saGemstones);
            result.add(saGemstones);
        }
        
        return result;
    }

    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        final Card source = sa.getHostCard();
        final String logic = sa.getParamOrDefault("AILogic", "Max");
        if ("GainLife".equals(logic)) {
            if (player.getLife() < 5 || player.getCardsIn(ZoneType.Hand).size() >= player.getMaxHandSize()) {
                return min;
            }
        } else if ("LoseLife".equals(logic)) {
            if (player.getLife() > 5) {
                return min;
            }
        } else if ("Min".equals(logic)) {
            return min;
        } else if ("DigACard".equals(logic)) {
            int random = MyRandom.getRandom().nextInt(Math.min(4, max)) + 1;
            if (player.getLife() < random + 5) {
                return min;
            } else {
                return random;
            }
        } else if ("Damnation".equals(logic)) {
            int chosenMax = player.getLife() - 1;
            int cardsInPlay = player.getCardsIn(ZoneType.Battlefield).size();
            return Math.min(chosenMax, cardsInPlay);
        } else if ("OptionalDraw".equals(logic)) {
            int cardsInHand = player.getCardsIn(ZoneType.Hand).size();
            int maxDraw = Math.min(player.getMaxHandSize() + 2 - cardsInHand, max);
            int maxCheckLib = Math.min(maxDraw, player.getCardsIn(ZoneType.Library).size());
            return Math.max(min, maxCheckLib);
        } else if ("RepeatDraw".equals(logic)) {
            int remaining = player.getMaxHandSize() - player.getCardsIn(ZoneType.Hand).size()
                    + MyRandom.getRandom().nextInt(3);
            return Math.max(remaining, min) / 2;
        } else if ("LowestLoseLife".equals(logic)) {
            return MyRandom.getRandom().nextInt(Math.min(player.getLife() / 3, ComputerUtil.getOpponentFor(player).getLife())) + 1;
        } else if ("HighestGetCounter".equals(logic)) {
            return MyRandom.getRandom().nextInt(3);
        } else if (source.hasSVar("EnergyToPay")) {
            return AbilityUtils.calculateAmount(source, source.getSVar("EnergyToPay"), sa);
        }
        return max;
    }

    public boolean confirmPayment(CostPart costPart) {
        throw new UnsupportedOperationException("AI is not supposed to reach this code at the moment");
    }

    public Map<GameEntity, CounterType> chooseProliferation(final SpellAbility sa) {
        final Map<GameEntity, CounterType> result = Maps.newHashMap();  
        
        final List<Player> allies = player.getAllies();
        allies.add(player);
        final List<Player> enemies = player.getOpponents();
        final Function<Card, CounterType> predProliferate = new Function<Card, CounterType>() {
            @Override
            public CounterType apply(Card crd) {
                //fast way out, no need to check other stuff
                if (!crd.hasCounters()) {
                    return null;
                }

                // cards controlled by ai or ally with Vanishing or Fading
                // and exaclty one counter of the specifice type gets high priority to keep the card
                if (allies.contains(crd.getController())) {
                    // except if its a Chronozoa, because it WANTS to be removed to make more
                    if (crd.hasKeyword(Keyword.VANISHING) && !"Chronozoa".equals(crd.getName())) {
                        if (crd.getCounters(CounterType.TIME) == 1) {
                            return CounterType.TIME;
                        }
                    } else if (crd.hasKeyword(Keyword.FADING)) {
                        if (crd.getCounters(CounterType.FADE) == 1) {
                            return CounterType.FADE;
                        }
                    }
                }

                for (final Entry<CounterType, Integer> c1 : crd.getCounters().entrySet()) {
                    // if card can not recive the given counter, try another one
                    if (!crd.canReceiveCounters(c1.getKey())) {
                        continue;
                    }
                    if (ComputerUtil.isNegativeCounter(c1.getKey(), crd) && enemies.contains(crd.getController())) {
                        return c1.getKey();
                    }
                    if (!ComputerUtil.isNegativeCounter(c1.getKey(), crd) && allies.contains(crd.getController())) {
                        return c1.getKey();
                    }
                }
                return null;
            }
        };

        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            CounterType ct = predProliferate.apply(c);
            if (ct != null)
                result.put(c, ct);
        }
        
        for (Player e : enemies) {
            // TODO In the future check of enemies can get poison counters and give them some other bad counter type
            if (e.getCounters(CounterType.POISON) > 0) {
                result.put(e, CounterType.POISON);
            }
        }

        for (Player pl : allies) {
            if (pl.getCounters(CounterType.EXPERIENCE) > 0) {
                result.put(pl, CounterType.EXPERIENCE);
            } else if (pl.getCounters(CounterType.ENERGY) > 0) {
                result.put(pl, CounterType.ENERGY);
            }
        }

        return result;
    }

    public CardCollection chooseCardsForEffect(CardCollectionView pool, SpellAbility sa, int min, int max, boolean isOptional) {
        if (sa == null || sa.getApi() == null) {
            throw new UnsupportedOperationException();
        }
        CardCollection result = new CardCollection();
        switch(sa.getApi()) {
            case TwoPiles:
                // TODO: improve AI
                Card biggest = null;
                Card smallest = null;
                biggest = pool.get(0);
                smallest = pool.get(0);

                for (Card c : pool) {
                    if (c.getCMC() >= biggest.getCMC()) {
                        biggest = c;
                    } else if (c.getCMC() <= smallest.getCMC()) {
                        smallest = c;
                    }
                }
                result.add(biggest);

                if (max > 3 && !result.contains(smallest)) {
                    result.add(smallest);
                }
                break;
            case MultiplePiles:
                // Whims of the Fates {all, 0, 0}
                result.addAll(pool);
                break;
            default:
                CardCollection editablePool = new CardCollection(pool);
                for (int i = 0; i < max; i++) {
                    Card c = player.getController().chooseSingleEntityForEffect(editablePool, sa, null, isOptional);
                    if (c != null) {
                        result.add(c);
                        editablePool.remove(c);
                    } else {
                        break;
                    }

                    // Special case for Bow to My Command which simulates a complex tap cost via ChooseCard
                    // TODO: consider enhancing support for tapXType<Any/...> in UnlessCost to get rid of this hack
                    if ("BowToMyCommand".equals(sa.getParam("AILogic"))) {
                        if (!sa.getHostCard().getZone().is(ZoneType.Command)) {
                            // Make sure that other opponents do not tap for an already abandoned scheme
                            result.clear();
                            break;
                        }

                        int totPower = 0;
                        for (Card p : result) {
                            totPower += p.getNetPower();
                        }
                        if (totPower >= 8) {
                            break;
                        }
                    }
                }
        }

        // TODO: Hack for Phyrexian Dreadnought. Might need generalization (possibly its own AI logic)
        if ("Phyrexian Dreadnought".equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            result = SpecialCardAi.PhyrexianDreadnought.reviseCreatureSacList(player, sa, result);
        }

        return result;
    }

    public Collection<? extends PaperCard> complainCardsCantPlayWell(Deck myDeck) {
        List<PaperCard> result = Lists.newArrayList();
        // When using simulation, AI should be able to figure out most cards.
        if (!useSimulation) {
            for (Entry<DeckSection, CardPool> ds : myDeck) {
                for (Entry<PaperCard, Integer> cp : ds.getValue()) {
                    if (cp.getKey().getRules().getAiHints().getRemAIDecks()) 
                        result.add(cp.getKey());
                }
            }
        }
        return result;
    }


    // this is where the computer cheats
    // changes AllZone.getComputerPlayer().getZone(Zone.Library)
    
    public int chooseNumber(SpellAbility sa, String title,List<Integer> options, Player relatedPlayer) {
        switch(sa.getApi())
        {
            case SetLife:
                if (relatedPlayer.equals(sa.getHostCard().getController())) {
                    return Collections.max(options);
                } else if (relatedPlayer.isOpponentOf(sa.getHostCard().getController())) {
                    return Collections.min(options);
                } else {
                    return options.get(0);
                }
            default:
                return 0;
        }
    }

    public boolean chooseDirection(SpellAbility sa) {
        if (sa == null || sa.getApi() == null) {
            throw new UnsupportedOperationException();
        }
        // Left:True; Right:False
        if ("GainControl".equals(sa.getParam("AILogic")) && game.getPlayers().size() > 2) {
            CardCollection creats = CardLists.getType(game.getCardsIn(ZoneType.Battlefield), "Creature");
            CardCollection left = CardLists.filterControlledBy(creats, game.getNextPlayerAfter(player, Direction.Left));
            CardCollection right = CardLists.filterControlledBy(creats, game.getNextPlayerAfter(player, Direction.Right));
            if (!left.isEmpty() || !right.isEmpty()) {
                CardCollection all = new CardCollection(left);
                all.addAll(right);
                return left.contains(ComputerUtilCard.getBestCreatureAI(all));
            }
        }
        return MyRandom.getRandom().nextBoolean();
    }

    public Card chooseCardToHiddenOriginChangeZone(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, Player player2, Player decider) {
        if (useSimulation) {
            return simPicker.chooseCardToHiddenOriginChangeZone(destination, origin, sa, fetchList, player2, decider);
        }
        if (sa.getApi() == ApiType.Explore) {
            return ExploreAi.shouldPutInGraveyard(fetchList, decider);
        } else {
            return ChangeZoneAi.chooseCardToHiddenOriginChangeZone(destination, origin, sa, fetchList, player2, decider);
        }
    }

    public List<SpellAbility> orderPlaySa(List<SpellAbility> activePlayerSAs) {
        // list is only one or empty, no need to filter
        if (activePlayerSAs.size() < 2) {
            return activePlayerSAs;
        }

        List<SpellAbility> result = Lists.newArrayList();

        // filter list by ApiTypes
        List<SpellAbility> discard = filterListByApi(activePlayerSAs, ApiType.Discard);
        List<SpellAbility> mandatoryDiscard = filterList(discard, SpellAbilityPredicates.isMandatory());

        List<SpellAbility> draw = filterListByApi(activePlayerSAs, ApiType.Draw);

        List<SpellAbility> putCounter = filterListByApi(activePlayerSAs, ApiType.PutCounter);
        List<SpellAbility> putCounterAll = filterListByApi(activePlayerSAs, ApiType.PutCounterAll);

        List<SpellAbility> evolve = filterList(putCounter, SpellAbilityPredicates.hasParam("Evolve"));

        List<SpellAbility> token = filterListByApi(activePlayerSAs, ApiType.Token);
        List<SpellAbility> pump = filterListByApi(activePlayerSAs, ApiType.Pump);
        List<SpellAbility> pumpAll = filterListByApi(activePlayerSAs, ApiType.PumpAll);

        // do mandatory discard early if hand is empty or has DiscardMe card
        boolean discardEarly = false;
        CardCollectionView playerHand = player.getCardsIn(ZoneType.Hand);
        if (playerHand.isEmpty() || CardLists.count(playerHand, CardPredicates.hasSVar("DiscardMe")) > 0) {
            discardEarly = true;
            result.addAll(mandatoryDiscard);
        }

        // token should be added first so they might get the pump bonus
        result.addAll(token);
        result.addAll(pump);
        result.addAll(pumpAll);

        // do Evolve Trigger before other PutCounter SpellAbilities
        // do putCounter before Draw/Discard because it can cause a Draw Trigger
        result.addAll(evolve);
        result.addAll(putCounter);
        result.addAll(putCounterAll);

        // do Draw before Discard
        result.addAll(draw);
        result.addAll(discard); // optional Discard, probably combined with Draw

        if (!discardEarly) {
            result.addAll(mandatoryDiscard);
        }

        result.addAll(activePlayerSAs);

        //need to reverse because of magic stack
        Collections.reverse(result);
        return result;
    }

    // TODO move to more common place
    private <T> List<T> filterList(List<T> input, Predicate<? super T> pred) {
        List<T> filtered = Lists.newArrayList(Iterables.filter(input, pred));
        input.removeAll(filtered);
        return filtered;
    }

    // TODO move to more common place
    private List<SpellAbility> filterListByApi(List<SpellAbility> input, ApiType type) {
        return filterList(input, SpellAbilityPredicates.isApi(type));
    }

    private <T extends CardTraitBase> List<T> filterListByAiLogic(List<T> list, final String logic) {
        return filterList(list, CardTraitPredicates.hasParam("AiLogic", logic));
    }

    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, int min, int num, boolean allowRepeat) {
        if (simPicker != null) {
            return simPicker.chooseModeForAbility(sa, min, num, allowRepeat);
        }
        return null;
    }

    public CardCollectionView chooseSacrificeType(String type, SpellAbility ability, int amount) {
        if (simPicker != null) {
            return simPicker.chooseSacrificeType(type, ability, amount);
        }
        return ComputerUtil.chooseSacrificeType(player, type, ability, ability.getTargetCard(), amount);
    }
    
    private boolean checkAiSpecificRestrictions(final SpellAbility sa) {
        // AI-specific restrictions specified as activation parameters in spell abilities
        
        if (sa.hasParam("AILifeThreshold")) {
            if (player.getLife() <= Integer.parseInt(sa.getParam("AILifeThreshold"))) {
                return false;
            }
        }
        
        return true;
    }

    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> list,
            Map<String, Object> runParams) {
        // no need to choose anything
        if (list.size() <= 1) {
            return Iterables.getFirst(list, null);
        }

        if (runParams.containsKey("Event")) {
            // replace lifegain effects
            if ("GainLife".equals(runParams.get("Event"))) {
                List<ReplacementEffect> noGain = filterListByAiLogic(list, "NoLife");
                List<ReplacementEffect> loseLife = filterListByAiLogic(list, "LoseLife");
                List<ReplacementEffect> doubleLife = filterListByAiLogic(list, "DoubleLife");
                List<ReplacementEffect> lichDraw = filterListByAiLogic(list, "LichDraw");

                if (!noGain.isEmpty()) {
                    // no lifegain is better than lose life
                    return Iterables.getFirst(noGain, null);
                } else if (!loseLife.isEmpty()) {
                    // lose life before double life to prevent lose double
                    return Iterables.getFirst(loseLife, null);
                } else if (!lichDraw.isEmpty()) {
                    // lich draw before double life to prevent to draw to much
                    return Iterables.getFirst(lichDraw, null);
                } else if (!doubleLife.isEmpty()) {
                    // other than that, do double life
                    return Iterables.getFirst(doubleLife, null);
                }
            } else if ("DamageDone".equals(runParams.get("Event"))) {
                List<ReplacementEffect> prevention = filterList(list, CardTraitPredicates.hasParam("Prevention"));

                // TODO when Protection is done as ReplacementEffect do them
                // before normal prevention
                if (!prevention.isEmpty()) {
                    return Iterables.getFirst(prevention, null);
                }
            }
        }

        // AI logic for choosing which replacement effect to apply
        // happens here.
        return Iterables.getFirst(list, null);
    }
    
}

