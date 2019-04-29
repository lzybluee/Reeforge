package forge.ai;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import forge.card.CardType;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.MagicColor.Constant;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.CostPayEnergy;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordCollection;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementLayer;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.MagicStack;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.Aggregates;
import forge.util.Expressions;
import forge.util.MyRandom;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.Map.Entry;

public class ComputerUtilCard {
    public static Card getMostExpensivePermanentAI(final CardCollectionView list, final SpellAbility spell, final boolean targeted) {
        CardCollectionView all = list;
        if (targeted) {
            all = CardLists.filter(all, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    return c.canBeTargetedBy(spell);
                }
            });
        }
        return ComputerUtilCard.getMostExpensivePermanentAI(all);
    }

    /**
     * <p>
     * Sorts a List<Card> by "best" using the EvaluateCreature function.
     * the best creatures will be first in the list.
     * </p>
     * 
     * @param list
     */
    public static void sortByEvaluateCreature(final CardCollection list) {
        Collections.sort(list, ComputerUtilCard.EvaluateCreatureComparator);
    } // sortByEvaluateCreature()
    
    // The AI doesn't really pick the best artifact, just the most expensive.
    /**
     * <p>
     * getBestArtifactAI.
     * </p>
     * 
     * @param list
     * @return a {@link forge.game.card.Card} object.
     */
    public static Card getBestArtifactAI(final List<Card> list) {
        List<Card> all = CardLists.filter(list, CardPredicates.Presets.ARTIFACTS);
        if (all.size() == 0) {
            return null;
        }
        // get biggest Artifact
        return Aggregates.itemWithMax(all, CardPredicates.Accessors.fnGetCmc);
    }
    
    /**
     * Returns the best Planeswalker from a given list
     * @param list list of cards to evaluate
     * @return best Planeswalker
     */
    public static Card getBestPlaneswalkerAI(final List<Card> list) {
        List<Card> all = CardLists.filter(list, CardPredicates.Presets.PLANESWALKERS);
        if (all.isEmpty()) {
            return null;
        }
        // no AI logic, just return most expensive
        return Aggregates.itemWithMax(all, CardPredicates.Accessors.fnGetCmc);
    }

    /**
     * Returns the worst Planeswalker from a given list
     * @param list list of cards to evaluate
     * @return best Planeswalker
     */
    public static Card getWorstPlaneswalkerAI(final List<Card> list) {
        List<Card> all = CardLists.filter(list, CardPredicates.Presets.PLANESWALKERS);
        if (all.isEmpty()) {
            return null;
        }
        // no AI logic, just return least expensive
        return Aggregates.itemWithMin(all, CardPredicates.Accessors.fnGetCmc);
    }

    // The AI doesn't really pick the best enchantment, just the most expensive.
    /**
     * <p>
     * getBestEnchantmentAI.
     * </p>
     * 
     * @param list
     * @param spell
     *            a {@link forge.game.card.Card} object.
     * @param targeted
     *            a boolean.
     * @return a {@link forge.game.card.Card} object.
     */
    public static Card getBestEnchantmentAI(final List<Card> list, final SpellAbility spell, final boolean targeted) {
        List<Card> all = CardLists.filter(list, CardPredicates.Presets.ENCHANTMENTS);
        if (targeted) {
            all = CardLists.filter(all, new Predicate<Card>() {
    
                @Override
                public boolean apply(final Card c) {
                    return c.canBeTargetedBy(spell);
                }
            });
        }
    
        // get biggest Enchantment
        return Aggregates.itemWithMax(all, CardPredicates.Accessors.fnGetCmc);
    }

    /**
     * <p>
     * getBestLandAI.
     * </p>
     * 
     * @param list
     * @return a {@link forge.game.card.Card} object.
     */
    public static Card getBestLandAI(final Iterable<Card> list) {
        final List<Card> land = CardLists.filter(list, CardPredicates.Presets.LANDS);
        if (land.isEmpty()) {
            return null;
        }
    
        // prefer to target non basic lands
        final List<Card> nbLand = CardLists.filter(land, Predicates.not(CardPredicates.Presets.BASIC_LANDS));
    
        if (!nbLand.isEmpty()) {
            // TODO - Rank non basics?
            return Aggregates.random(nbLand);
        }
    
        // if no non-basic lands, target the least represented basic land type
        String sminBL = "";
        int iminBL = 20000; // hopefully no one will ever have more than 20000
                            // lands of one type....
        int n = 0;
        for (String name : MagicColor.Constant.BASIC_LANDS) {
            n = CardLists.getType(land, name).size();
            if ((n < iminBL) && (n > 0)) {
                // if two or more are tied, only the
                // first
                // one checked will be used
                iminBL = n;
                sminBL = name;
            }
        }
        if (iminBL == 20000) {
            return null; // no basic land was a minimum
        }
    
        final List<Card> bLand = CardLists.getType(land, sminBL);
    
        for (Card ut : Iterables.filter(bLand, CardPredicates.Presets.UNTAPPED)) {
            return ut;
        }
    
    
        return Aggregates.random(bLand); // random tapped land of least represented type
    }

    /**
     * <p>
     * getCheapestPermanentAI.
     * </p>
     * 
     * @param all
     * @param spell
     *            a {@link forge.game.card.Card} object.
     * @param targeted
     *            a boolean.
     * @return a {@link forge.game.card.Card} object.
     */
    public static Card getCheapestPermanentAI(Iterable<Card> all, final SpellAbility spell, final boolean targeted) {
        if (targeted) {
            all = CardLists.filter(all, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    return c.canBeTargetedBy(spell);
                }
            });
        }
        if (Iterables.isEmpty(all)) {
            return null;
        }
    
        // get cheapest card:
        Card cheapest = null;
    
        for (Card c : all) {
            if (cheapest == null || cheapest.getManaCost().getCMC() <= cheapest.getManaCost().getCMC()) {
                cheapest = c;
            }
        }
    
        return cheapest;
    
    }

    // returns null if list.size() == 0
    /**
     * <p>
     * getBestAI.
     * </p>
     * 
     * @param list
     * @return a {@link forge.game.card.Card} object.
     */
    public static Card getBestAI(final Iterable<Card> list) {
        // Get Best will filter by appropriate getBest list if ALL of the list
        // is of that type
        if (Iterables.all(list, CardPredicates.Presets.CREATURES)) {
            return ComputerUtilCard.getBestCreatureAI(list);
        }
        if (Iterables.all(list, CardPredicates.Presets.LANDS)) {
            return getBestLandAI(list);
        }
        // TODO - Once we get an EvaluatePermanent this should call
        // getBestPermanent()
        return ComputerUtilCard.getMostExpensivePermanentAI(list);
    }

    /**
     * getBestCreatureAI.
     * 
     * @param list
     *            the list
     * @return the card
     */
    public static Card getBestCreatureAI(final Iterable<Card> list) {
        return Aggregates.itemWithMax(Iterables.filter(list, CardPredicates.Presets.CREATURES), ComputerUtilCard.creatureEvaluator);
    }

    /**
     * <p>
     * getWorstCreatureAI.
     * </p>
     * 
     * @param list
     * @return a {@link forge.game.card.Card} object.
     */
    public static Card getWorstCreatureAI(final Iterable<Card> list) {
        return Aggregates.itemWithMin(Iterables.filter(list, CardPredicates.Presets.CREATURES), ComputerUtilCard.creatureEvaluator);
    }

    // This selection rates tokens higher
    /**
     * <p>
     * getBestCreatureToBounceAI.
     * </p>
     * 
     * @param list
     * @return a {@link forge.game.card.Card} object.
     */
    public static Card getBestCreatureToBounceAI(final CardCollectionView list) {
        final int tokenBonus = 60;
        Card biggest = null;
        int biggestvalue = -1;

        for (Card card : CardLists.filter(list, CardPredicates.Presets.CREATURES)) {
            int newvalue = ComputerUtilCard.evaluateCreature(card);
            newvalue += card.isToken() ? tokenBonus : 0; // raise the value of tokens

            if (biggestvalue < newvalue) {
                biggest = card;
                biggestvalue = newvalue;
            }
        }
        return biggest;
    }

    /**
     * <p>
     * getWorstAI.
     * </p>
     * 
     * @param list
     * @return a {@link forge.game.card.Card} object.
     */
    public static Card getWorstAI(final Iterable<Card> list) {
        return ComputerUtilCard.getWorstPermanentAI(list, false, false, false, false);
    }

    /**
     * <p>
     * getWorstPermanentAI.
     * </p>
     * 
     * @param list
     * @param biasEnch
     *            a boolean.
     * @param biasLand
     *            a boolean.
     * @param biasArt
     *            a boolean.
     * @param biasCreature
     *            a boolean.
     * @return a {@link forge.game.card.Card} object.
     */
    public static Card getWorstPermanentAI(final Iterable<Card> list, final boolean biasEnch, final boolean biasLand,
            final boolean biasArt, final boolean biasCreature) {
        if (Iterables.isEmpty(list)) {
            return null;
        }
        
        final boolean hasEnchantmants = Iterables.any(list, CardPredicates.Presets.ENCHANTMENTS);
        if (biasEnch && hasEnchantmants) {
            return getCheapestPermanentAI(CardLists.filter(list, CardPredicates.Presets.ENCHANTMENTS), null, false);
        }
    
        final boolean hasArtifacts = Iterables.any(list, CardPredicates.Presets.ARTIFACTS); 
        if (biasArt && hasArtifacts) {
            return getCheapestPermanentAI(CardLists.filter(list, CardPredicates.Presets.ARTIFACTS), null, false);
        }

        if (biasLand && Iterables.any(list, CardPredicates.Presets.LANDS)) {
            return ComputerUtilCard.getWorstLand(CardLists.filter(list, CardPredicates.Presets.LANDS));
        }
    
        final boolean hasCreatures = Iterables.any(list, CardPredicates.Presets.CREATURES);
        if (biasCreature && hasCreatures) {
            return getWorstCreatureAI(CardLists.filter(list, CardPredicates.Presets.CREATURES));
        }
    
        List<Card> lands = CardLists.filter(list, CardPredicates.Presets.LANDS);
        if (!lands.isEmpty()) {
        	if (lands.size() > 3) {
                return ComputerUtilCard.getWorstLand(lands);
        	}
        }

        if (hasEnchantmants || hasArtifacts) {
            final List<Card> ae = CardLists.filter(list, Predicates.and(Predicates.<Card>or(CardPredicates.Presets.ARTIFACTS, CardPredicates.Presets.ENCHANTMENTS), new Predicate<Card>() {
                @Override
                public boolean apply(Card card) {
                    return !card.hasSVar("DoNotDiscardIfAble");
                }
            }));
            return getCheapestPermanentAI(ae, null, false);
        }
    
        if (hasCreatures) {
        	CardCollection creatures = CardLists.filter(list, CardPredicates.Presets.CREATURES);
        	Card creature = getWorstCreatureAI(creatures);
        	if(creatures.size() > 3 || creature.getManaCost().getCMC() < 3) {
        		return creature;
        	}
        }
    
        // Planeswalkers fall through to here, lands will fall through if there
        // aren't very many
        return getCheapestPermanentAI(list, null, false);
    }

    public static final Card getCheapestSpellAI(final Iterable<Card> list) {
        if (!Iterables.isEmpty(list)) {
            CardCollection cc = CardLists.filter(new CardCollection(list),
                    Predicates.or(CardPredicates.isType("Instant"), CardPredicates.isType("Sorcery")));
            Collections.sort(cc, CardLists.CmcComparatorInv);

            if (cc.isEmpty()) {
                return null;
            }

            Card cheapest = cc.getLast();
            if (cheapest.hasSVar("DoNotDiscardIfAble")) {
                for (int i = cc.size() - 1; i >= 0; i--) {
                    if (!cc.get(i).hasSVar("DoNotDiscardIfAble")) {
                        cheapest = cc.get(i);
                        break;
                    }
                }
            }

            return cheapest;
        }

        return null;
    }

    public static final Comparator<Card> EvaluateCreatureComparator = new Comparator<Card>() {
        @Override
        public int compare(final Card a, final Card b) {
            return ComputerUtilCard.evaluateCreature(b) - ComputerUtilCard.evaluateCreature(a);
        }
    };

    private static final CreatureEvaluator creatureEvaluator = new CreatureEvaluator();

    /**
     * <p>
     * evaluateCreature.
     * </p>
     * 
     * @param c
     *            a {@link forge.game.card.Card} object.
     * @return a int.
     */
    public static int evaluateCreature(final Card c) {
        return creatureEvaluator.evaluateCreature(c);
    }

    public static int evaluateCreature(final Card c, final boolean considerPT, final boolean considerCMC) {
        return creatureEvaluator.evaluateCreature(c, considerPT, considerCMC);
    }

    public static int evaluatePermanentList(final CardCollectionView list) {
        int value = 0;
        for (int i = 0; i < list.size(); i++) {
            value += list.get(i).getCMC() + 1;
        }
        return value;
    }

    public static int evaluateCreatureList(final CardCollectionView list) {
        return Aggregates.sum(list, creatureEvaluator);
    }
    
    public static Map<String, Integer> evaluateCreatureListByName(final CardCollectionView list) {
        // Compute value for each possible target
        Map<String, Integer> values = Maps.newHashMap();
        for (Card c : list) {
            String name = c.getName();
            int val = evaluateCreature(c);
            if (values.containsKey(name)) {
                values.put(name, values.get(name) + val);
            } else {
                values.put(name, val);
            }
        }
        return values;
    }

    public static boolean doesCreatureAttackAI(final Player aiPlayer, final Card card) {
        AiController aic = ((PlayerControllerAi)aiPlayer.getController()).getAi();
        return aic.getPredictedCombat().isAttacking(card);
    }

    /**
     * Extension of doesCreatureAttackAI() for "virtual" creatures that do not actually exist on the battlefield yet
     * such as unanimated manlands.
     * @param ai controller of creature 
     * @param card creature to be evaluated
     * @return creature will be attack
     */
    public static boolean doesSpecifiedCreatureAttackAI(final Player ai, final Card card) {
        AiAttackController aiAtk = new AiAttackController(ai, card);
        Combat combat = new Combat(ai);
        aiAtk.declareAttackers(combat);
        return combat.isAttacking(card);
    }

    public static boolean canBeKilledByRoyalAssassin(final Player ai, final Card card) {
    	boolean wasTapped = card.isTapped();
    	for (Player opp : ai.getOpponents()) {
    		for (Card c : opp.getCardsIn(ZoneType.Battlefield)) {
    			for (SpellAbility sa : c.getSpellAbilities()) {
                    if (sa.getApi() != ApiType.Destroy) {
                        continue;
                    }
                    if (!ComputerUtilCost.canPayCost(sa, opp)) {
                        continue;
                    }
                    sa.setActivatingPlayer(opp);
                    if (sa.canTarget(card)) {
                    	continue;
                    }
                    // check whether the ability can only target tapped creatures
                	card.setTapped(true);
                    if (!sa.canTarget(card)) {
                    	card.setTapped(wasTapped);
                    	continue;
                    }
                	card.setTapped(wasTapped);
                    return true;
    			}
    		}
    	}
    	return false;
    }
    
    /**
     * Create a mock combat where ai is being attacked and returns the list of likely blockers. 
     * @param ai blocking player
     * @param blockers list of additional blockers to be considered
     * @return list of creatures assigned to block in the simulation
     */
    public static CardCollectionView getLikelyBlockers(final Player ai, final CardCollectionView blockers) {
        AiBlockController aiBlk = new AiBlockController(ai);
        final Player opp = ComputerUtil.getOpponentFor(ai);
        Combat combat = new Combat(opp);
        //Use actual attackers if available, else consider all possible attackers
        Combat currentCombat = ai.getGame().getCombat();
        if (currentCombat != null && currentCombat.getAttackingPlayer() != ai) {
            for (Card c : currentCombat.getAttackers()) {
                combat.addAttacker(c, ai);
            }
        } else {
            for (Card c : opp.getCreaturesInPlay()) {
                if (ComputerUtilCombat.canAttackNextTurn(c, ai)) {
                    combat.addAttacker(c, ai);
                }
            }
        }
        if (blockers == null || blockers.isEmpty()) {
            aiBlk.assignBlockersForCombat(combat);
        } else {
            aiBlk.assignAdditionalBlockers(combat, blockers);
        }
        return combat.getAllBlockers();
    }
    
    /**
     * Decide if a creature is going to be used as a blocker.
     * @param ai controller of creature 
     * @param blocker creature to be evaluated
     * @return creature will be a blocker
     */
    public static boolean doesSpecifiedCreatureBlock(final Player ai, Card blocker) {
        return getLikelyBlockers(ai, new CardCollection(blocker)).contains(blocker);
    }

    /**
     * Check if an attacker can be blocked profitably (ie. kill attacker)
     * @param ai controller of attacking creature
     * @param attacker attacking creature to evaluate
     * @return attacker will die
     */
    public static boolean canBeBlockedProfitably(final Player ai, Card attacker) {
        AiBlockController aiBlk = new AiBlockController(ai);
        Combat combat = new Combat(ai);
        combat.addAttacker(attacker, ai);
        final List<Card> attackers = new ArrayList<Card>();
        attackers.add(attacker);
        aiBlk.assignBlockersGivenAttackers(combat, attackers);
        return ComputerUtilCombat.attackerWouldBeDestroyed(ai, attacker, combat);
    }
    
    /**
     * getMostExpensivePermanentAI.
     * 
     * @param all
     *            the all
     * @return the card
     */
    public static Card getMostExpensivePermanentAI(final Iterable<Card> all) {
        Card biggest = null;
    
        int bigCMC = -1;
        for (final Card card : all) {
            int curCMC = card.getCMC();
    
            // Add all cost of all auras with the same controller
            if (card.isEnchanted()) {
                final List<Card> auras = CardLists.filterControlledBy(card.getEnchantedBy(false), card.getController());
                curCMC += Aggregates.sum(auras, CardPredicates.Accessors.fnGetCmc) + auras.size();
            }
    
            if (curCMC >= bigCMC) {
                bigCMC = curCMC;
                biggest = card;
            }
        }
    
        return biggest;
    }

    public static String getMostProminentCardName(final CardCollectionView list) {
        if (list.size() == 0) {
            return "";
        }
    
        final Map<String, Integer> map = Maps.newHashMap();
    
        for (final Card c : list) {
            final String name = c.getName();
            Integer currentCnt = map.get(name);
            map.put(name, currentCnt == null ? Integer.valueOf(1) : Integer.valueOf(1 + currentCnt));
        } // for
    
        int max = 0;
        String maxName = "";
    
        for (final Entry<String, Integer> entry : map.entrySet()) {
            final String type = entry.getKey();
            // Log.debug(type + " - " + entry.getValue());
    
            if (max < entry.getValue()) {
                max = entry.getValue();
                maxName = type;
            }
        }
        return maxName;
    }

    public static String getMostProminentBasicLandType(final CardCollectionView list) {
        return getMostProminentType(list, CardType.getBasicTypes());
    }

    /**
     * <p>
     * getMostProminentCreatureType.
     * </p>
     * 
     * @param list
     * @return a {@link java.lang.String} object.
     */
    public static String getMostProminentCreatureType(final CardCollectionView list) {
        return getMostProminentType(list, CardType.getAllCreatureTypes());
    }

    public static String getMostProminentType(final CardCollectionView list, final List<String> valid) {
        if (list.size() == 0) {
            return "";
        }

        final Map<String, Integer> typesInDeck = Maps.newHashMap();

        // TODO JAVA 8 use getOrDefault
        for (final Card c : list) {

            // Changeling are all creature types, they are not interesting for
            // counting creature types
            if (c.hasStartOfKeyword(Keyword.CHANGELING.toString())) {
                continue;
            }
            // ignore cards that does enter the battlefield as clones
            boolean isClone = false;
            for (ReplacementEffect re : c.getReplacementEffects()) {
                if (re.getLayer() == ReplacementLayer.Copy) {
                    isClone = true;
                    break;
                }
            }
            if (isClone) {
                continue;
            }

            Set<String> cardCreatureTypes = c.getType().getCreatureTypes();
            for (String type : cardCreatureTypes) {
                Integer count = typesInDeck.get(type);
                if (count == null) {
                    count = 0;
                }
                typesInDeck.put(type, count + 1);
            }
            //also take into account abilities that generate tokens
            for (SpellAbility sa : c.getAllSpellAbilities()) {
                if (sa.getApi() != ApiType.Token) {
                    continue;
                }
                if (sa.hasParam("TokenTypes")) {
                    for (String var : sa.getParam("TokenTypes").split(",")) {
                        if (!CardType.isACreatureType(var)) {
                            continue;
                        }
                        Integer count = typesInDeck.get(var);
                        if (count == null) {
                            count = 0;
                        }
                        typesInDeck.put(var, count + 1);
                    }
                }
            }
            // same for Trigger that does make Tokens
            for(Trigger t:c.getTriggers()){
                SpellAbility sa = t.getOverridingAbility();
                String sTokenTypes = null;
                if (sa != null) {
                    if (sa.getApi() != ApiType.Token || !sa.hasParam("TokenTypes")) {
                        continue;
                    }
                    sTokenTypes = sa.getParam("TokenTypes");
                } else if (t.hasParam("Execute")) {
                    String name = t.getParam("Execute");
                    if (!c.hasSVar(name)) {
                        continue;
                    }

                    Map<String, String> params = AbilityFactory.getMapParams(c.getSVar(name));
                    if (!params.containsKey("TokenTypes")) {
                        continue;
                    }
                    sTokenTypes = params.get("TokenTypes");
                }

                if (sTokenTypes == null) {
                    continue;
                }

                for (String var : sTokenTypes.split(",")) {
                    if (!CardType.isACreatureType(var)) {
                        continue;
                    }
                    Integer count = typesInDeck.get(var);
                    if (count == null) {
                        count = 0;
                    }
                    typesInDeck.put(var, count + 1);
                }
            }
            // special rule for Fabricate and Servo
            if(c.hasStartOfKeyword(Keyword.FABRICATE.toString())){
                Integer count = typesInDeck.get("Servo");
                if (count == null) {
                    count = 0;
                }
                typesInDeck.put("Servo", count + 1);
            }
        } // for

        int max = 0;
        String maxType = "";
    
        for (final Entry<String, Integer> entry : typesInDeck.entrySet()) {
            final String type = entry.getKey();
            // Log.debug(type + " - " + entry.getValue());

            if (max < entry.getValue()) {
                max = entry.getValue();
                maxType = type;
            }
        }
    
        return maxType;
    }

    /**
     * <p>
     * getMostProminentColor.
     * </p>
     * 
     * @param list
     * @return a {@link java.lang.String} object.
     */
    public static String getMostProminentColor(final Iterable<Card> list) {
        byte colors = CardFactoryUtil.getMostProminentColors(list);
        for(byte c : MagicColor.WUBRG) {
            if ( (colors & c) != 0 )
                return MagicColor.toLongString(c);
        }
        return MagicColor.Constant.WHITE; // no difference, there was no prominent color
    }

    public static String getMostProminentColor(final CardCollectionView list, final List<String> restrictedToColors) {
        byte colors = CardFactoryUtil.getMostProminentColorsFromList(list, restrictedToColors);
        for (byte c : MagicColor.WUBRG) {
            if ((colors & c) != 0) {
                return MagicColor.toLongString(c);
            }
        }
        return restrictedToColors.get(0); // no difference, there was no prominent color
    }

    public static List<String> getColorByProminence(final List<Card> list) {
        int cntColors = MagicColor.WUBRG.length;
        final List<Pair<Byte,Integer>> map = new ArrayList<Pair<Byte,Integer>>();
        for(int i = 0; i < cntColors; i++) {
            map.add(MutablePair.of(MagicColor.WUBRG[i], 0));
        }

        for (final Card crd : list) {
            ColorSet color = CardUtil.getColors(crd);
            if (color.hasWhite()) map.get(0).setValue(Integer.valueOf(map.get(0).getValue()+1));
            if (color.hasBlue()) map.get(1).setValue(Integer.valueOf(map.get(1).getValue()+1));
            if (color.hasBlack()) map.get(2).setValue(Integer.valueOf(map.get(2).getValue()+1));
            if (color.hasRed()) map.get(3).setValue(Integer.valueOf(map.get(3).getValue()+1));
            if (color.hasGreen()) map.get(4).setValue(Integer.valueOf(map.get(4).getValue()+1));
        } // for

        Collections.sort(map, new Comparator<Pair<Byte,Integer>>() {
            @Override public int compare(Pair<Byte, Integer> o1, Pair<Byte, Integer> o2) {
                return o2.getValue() - o1.getValue();
            }
        });
    
        // will this part be once dropped?
        List<String> result = new ArrayList<String>(cntColors);
        for(Pair<Byte, Integer> idx : map) { // fetch color names in the same order
            result.add(MagicColor.toLongString(idx.getKey()));
        }
        // reverse to get indices for most prominent colors first.
        return result;
    }


    /**
     * <p>
     * getWorstLand.
     * </p>
     * 
     * @param lands
     * @return a {@link forge.game.card.Card} object.
     */
    public static Card getWorstLand(final List<Card> lands) {
        Card worstLand = null;
        int maxScore = Integer.MIN_VALUE;
        // first, check for tapped, basic lands
        for (Card tmp : lands) {
            int score = tmp.isTapped() ? 2 : 0;
            score += tmp.isBasicLand() ? 1 : 0;
            score -= tmp.isCreature() ? 4 : 0;
            for (Card aura : tmp.getEnchantedBy(false)) {
            	if (aura.getController().isOpponentOf(tmp.getController())) {
            		score += 5;
            	} else {
            		score -= 5;
            	}
            }
            if (score >= maxScore) {
                worstLand = tmp;
                maxScore = score;
            }
        }
        return worstLand;
    } // end getWorstLand

    public static Card getBestLandToAnimate(final Iterable<Card> lands) {
        Card land = null;
        int maxScore = Integer.MIN_VALUE;
        // first, check for tapped, basic lands
        for (Card tmp : lands) {
            // TODO Improve this by choosing basic lands that I have plenty of mana in
            int score = tmp.isTapped() ? 0 : 2;
            score += tmp.isBasicLand() ? 2 : 0;
            score -= tmp.isCreature() ? 4 : 0;
            score -= 5 * tmp.getEnchantedBy(false).size();

            if (score >= maxScore) {
                land = tmp;
                maxScore = score;
            }
        }
        return land;
    } // end getBestLandToAnimate

    public static final Predicate<Deck> AI_KNOWS_HOW_TO_PLAY_ALL_CARDS = new Predicate<Deck>() {
        @Override
        public boolean apply(Deck d) {
            for(Entry<DeckSection, CardPool> cp: d) {
                for(Entry<PaperCard, Integer> e : cp.getValue()) {
                    if ( e.getKey().getRules().getAiHints().getRemAIDecks() )
                        return false;
                }
            }
            return true;
        }
    };
    public static List<String> chooseColor(SpellAbility sa, int min, int max, List<String> colorChoices) {
        List<String> chosen = new ArrayList<String>();
        Player ai = sa.getActivatingPlayer();
        final Game game = ai.getGame();
        Player opp = ComputerUtil.getOpponentFor(ai);
        if (sa.hasParam("AILogic")) {
            final String logic = sa.getParam("AILogic");
             
            if (logic.equals("MostProminentInHumanDeck")) {
                chosen.add(ComputerUtilCard.getMostProminentColor(CardLists.filterControlledBy(game.getCardsInGame(), opp), colorChoices));
            }
            else if (logic.equals("MostProminentInComputerDeck")) {
                chosen.add(ComputerUtilCard.getMostProminentColor(CardLists.filterControlledBy(game.getCardsInGame(), ai), colorChoices));
            }
            else if (logic.equals("MostProminentDualInComputerDeck")) {
                List<String> prominence = ComputerUtilCard.getColorByProminence(CardLists.filterControlledBy(game.getCardsInGame(), ai));
                chosen.add(prominence.get(0));
                chosen.add(prominence.get(1));
            }
            else if (logic.equals("MostProminentInGame")) {
                chosen.add(ComputerUtilCard.getMostProminentColor(game.getCardsInGame(), colorChoices));
            }
            else if (logic.equals("MostProminentHumanCreatures")) {
                CardCollectionView list = opp.getCreaturesInPlay();
                if (list.isEmpty()) {
                    list = CardLists.filter(CardLists.filterControlledBy(game.getCardsInGame(), opp), CardPredicates.Presets.CREATURES);
                }
                chosen.add(ComputerUtilCard.getMostProminentColor(list, colorChoices));
            }
            else if (logic.equals("MostProminentComputerControls")) {
                chosen.add(ComputerUtilCard.getMostProminentColor(ai.getCardsIn(ZoneType.Battlefield), colorChoices));
            }
            else if (logic.equals("MostProminentHumanControls")) {
                chosen.add(ComputerUtilCard.getMostProminentColor(opp.getCardsIn(ZoneType.Battlefield), colorChoices));
            }
            else if (logic.equals("MostProminentPermanent")) {
                chosen.add(ComputerUtilCard.getMostProminentColor(game.getCardsIn(ZoneType.Battlefield), colorChoices));
            }
            else if (logic.equals("MostProminentAttackers") && game.getPhaseHandler().inCombat()) {
                chosen.add(ComputerUtilCard.getMostProminentColor(game.getCombat().getAttackers(), colorChoices));
            }
            else if (logic.equals("MostProminentInActivePlayerHand")) {
                chosen.add(ComputerUtilCard.getMostProminentColor(game.getPhaseHandler().getPlayerTurn().getCardsIn(ZoneType.Hand), colorChoices));
            }
            else if (logic.equals("MostProminentInComputerDeckButGreen")) {
            	List<String> prominence = ComputerUtilCard.getColorByProminence(CardLists.filterControlledBy(game.getCardsInGame(), ai));
            	if (prominence.get(0) == MagicColor.Constant.GREEN) {
                    chosen.add(prominence.get(1));
            	} else {
                    chosen.add(prominence.get(0));
            	}
            }
            else if (logic.equals("MostExcessOpponentControls")) {
            	int maxExcess = 0;
            	String bestColor = Constant.GREEN;
            	for (byte color : MagicColor.WUBRG) {
            		CardCollectionView ailist = ai.getCardsIn(ZoneType.Battlefield);
            		CardCollectionView opplist = opp.getCardsIn(ZoneType.Battlefield);
            		
            		ailist = CardLists.filter(ailist, CardPredicates.isColor(color));
            		opplist = CardLists.filter(opplist, CardPredicates.isColor(color));

                    int excess = evaluatePermanentList(opplist) - evaluatePermanentList(ailist);
                    if (excess > maxExcess) {
                    	maxExcess = excess;
                    	bestColor = MagicColor.toLongString(color);
                    }
                }
                chosen.add(bestColor);
            }
            else if (logic.equals("MostProminentKeywordInComputerDeck")) {
                CardCollectionView list = ai.getAllCards();
                int m1 = 0;
                String chosenColor = MagicColor.Constant.WHITE;

                for (final String c : MagicColor.Constant.ONLY_COLORS) {
                    final int cmp = CardLists.filter(list, CardPredicates.containsKeyword(c)).size();
                    if (cmp > m1) {
                        m1 = cmp;
                        chosenColor = c;
                    }
                }
                chosen.add(chosenColor);
            }
        }
        if (chosen.isEmpty()) {
            chosen.add(MagicColor.Constant.GREEN);
        }
        return chosen;
    }
    
    public static boolean useRemovalNow(final SpellAbility sa, final Card c, final int dmg, ZoneType destination) {
        final Player ai = sa.getActivatingPlayer();
        final AiController aic = ((PlayerControllerAi)ai.getController()).getAi();
        final Player opp = ComputerUtil.getOpponentFor(ai);
        final Game game = ai.getGame();
        final PhaseHandler ph = game.getPhaseHandler();
        final PhaseType phaseType = ph.getPhase();

        final int costRemoval = sa.getHostCard().getCMC();
        final int costTarget = c.getCMC();
        
        if (!sa.isSpell()) {
        	return true;
        }
        
        //Check for cards that profit from spells - for example Prowess or Threshold
        if (phaseType == PhaseType.MAIN1 && ComputerUtil.castSpellInMain1(ai, sa)) {
            return true;
        }
        
        //interrupt 1: Check whether a possible blocker will be killed for the AI to make a bigger attack
        if (ph.is(PhaseType.MAIN1) && ph.isPlayerTurn(ai) && c.isCreature()) {
            AiAttackController aiAtk = new AiAttackController(ai);
            final Combat combat = new Combat(ai);
            aiAtk.removeBlocker(c);
            aiAtk.declareAttackers(combat);
        	if (!combat.getAttackers().isEmpty()) {
                AiAttackController aiAtk2 = new AiAttackController(ai);
                final Combat combat2 = new Combat(ai);
                aiAtk2.declareAttackers(combat2);
                if (combat.getAttackers().size() > combat2.getAttackers().size()) {
                	return true;
                }
        	}
        }

        // interrupt 2: remove blocker to save my attacker
        if (ph.is(PhaseType.COMBAT_DECLARE_BLOCKERS) && !ph.isPlayerTurn(ai)) {
            Combat currCombat = game.getCombat();
            if (currCombat != null && !currCombat.getAllBlockers().isEmpty() && currCombat.getAllBlockers().contains(c)) {
                for (Card attacker : currCombat.getAttackersBlockedBy(c)) {
                    if (attacker.getShieldCount() == 0 && ComputerUtilCombat.attackerWouldBeDestroyed(ai, attacker, currCombat)) {
                        CardCollection blockers = currCombat.getBlockers(attacker);
                        ComputerUtilCard.sortByEvaluateCreature(blockers);
                        Combat combat = new Combat(ai);
                        combat.addAttacker(attacker, opp);
                        for (Card blocker : blockers) {
                            if (blocker == c) {
                                continue;
                            }
                            combat.addBlocker(attacker, blocker);
                        }
                        if (!ComputerUtilCombat.attackerWouldBeDestroyed(ai, attacker, combat)) {
                            return true;
                        }
                    }
                }
            }
        }
        
        // interrupt 3:  two for one = good
        if (c.isEnchanted()) {
            boolean myEnchants = false;
            for (Card enc : c.getEnchantedBy(false)) {
                if (enc.getOwner().equals(ai)) {
                    myEnchants = true;
                    break;
                }
            }
            if (!myEnchants) {
                return true;    //card advantage > tempo
            }
        }
        
        //interrupt 4: opponent pumping target (only works if the pump target is the chosen best target to begin with)
        final MagicStack stack = game.getStack();
        if (!stack.isEmpty()) {
            final SpellAbility topStack = stack.peekAbility();
            if (topStack.getActivatingPlayer().equals(opp) && c.equals(topStack.getTargetCard()) && topStack.isSpell()) {
            	return true;
            }
        }
        
        //burn and curse spells
        float valueBurn = 0;
        if (dmg > 0) {
            if (sa.getDescription().contains("would die, exile it instead")) {
                destination = ZoneType.Exile;
            }
            valueBurn = 1.0f * c.getNetToughness() / dmg;
            valueBurn *= valueBurn;
            if (sa.getTargetRestrictions().canTgtPlayer()) {
                valueBurn /= 2;     //preserve option to burn to the face
            }
            if (valueBurn >= 0.8 && phaseType.isBefore(PhaseType.COMBAT_END)) {
            	return true;
            }
        }
        
        //evaluate tempo gain
        float valueTempo = Math.max(0.1f * costTarget / costRemoval, valueBurn);
        if (c.isEquipped()) {
            valueTempo *= 2;
        }
        if (SpellAbilityAi.isSorcerySpeed(sa)) {
            valueTempo *= 2;    //sorceries have less usage opportunities
        }
        if (!c.canBeDestroyed()) {
            valueTempo *= 2;    //deal with annoying things
        }
        if (!destination.equals(ZoneType.Graveyard) &&  //TODO:boat-load of "when blah dies" triggers
                c.hasKeyword(Keyword.PERSIST) || c.hasKeyword(Keyword.UNDYING) || c.hasKeyword(Keyword.MODULAR)) {
            valueTempo *= 2;
        }
        if (destination.equals(ZoneType.Hand) && !c.isToken()) {
            valueTempo /= 2;    //bouncing non-tokens for tempo is less valuable
        }
        if (c.isLand()) {
            valueTempo += 0.5f / opp.getLandsInPlay().size();   //set back opponent's mana
            if ("Land".equals(sa.getParam("ValidTgts")) && ph.getPhase().isAfter(PhaseType.COMBAT_END)) {
            	valueTempo += 0.5; // especially when nothing else can be targeted
            }
        }
        if (!ph.isPlayerTurn(ai) && ph.getPhase().equals(PhaseType.END_OF_TURN)) {
            valueTempo *= 2;    //prefer to cast at opponent EOT
        }
        if (valueTempo >= 0.8 && ph.getPhase().isBefore(PhaseType.COMBAT_END)) {
        	return true;
        }
        
        //evaluate threat of targeted card
        float threat = 0;
        if (c.isCreature()) {
        	// the base value for evaluate creature is 100
        	threat += (-1 + 1.0f * ComputerUtilCard.evaluateCreature(c) / 100) / costRemoval;
        	if (ai.getLife() > 0 && ComputerUtilCombat.canAttackNextTurn(c)) {
        		Combat combat = game.getCombat();
        		threat += 1.0f * ComputerUtilCombat.damageIfUnblocked(c, opp, combat, true) / ai.getLife();
        		//TODO:add threat from triggers and other abilities (ie. Master of Cruelties)
        	}
        	if (ph.isPlayerTurn(ai) && phaseType.isAfter(PhaseType.COMBAT_DECLARE_BLOCKERS)) {
        		threat *= 0.1f;
        	}
        	if (!ph.isPlayerTurn(ai) && 
        			(phaseType.isBefore(PhaseType.COMBAT_BEGIN) || phaseType.isAfter(PhaseType.COMBAT_DECLARE_BLOCKERS))) {
        		threat *= 0.1f;
        	}
        } else if (c.isPlaneswalker()) {
            threat = 1;
        } else if (aic.getBooleanProperty(AiProps.ACTIVELY_DESTROY_ARTS_AND_NONAURA_ENCHS) && ((c.isArtifact() && !c.isCreature()) || (c.isEnchantment() && !c.isAura()))) {
            // non-creature artifacts and global enchantments with suspicious intrinsic abilities
            boolean priority = false;
            if (c.getOwner().isOpponentOf(ai) && c.getController().isOpponentOf(ai)) {
                // if this thing is both owned and controlled by an opponent and it has a continuous ability,
                // assume it either benefits the player or disrupts the opponent
                for (final StaticAbility stAb : c.getStaticAbilities()) {
                    final Map<String, String> params = stAb.getMapParams();
                    if (params.get("Mode").equals("Continuous") && stAb.isIntrinsic() && !stAb.isTemporary()) {
                        priority = true;
                        break;
                    }
                }
                if (!priority) {
                    for (final Trigger t : c.getTriggers()) {
                        if (t.isIntrinsic() && !t.isTemporary()) {
                            // has a triggered ability, could be benefitting the opponent or disrupting the AI
                            priority = true;
                            break;
                        }
                    }
                }
                // if this thing has AILogic set to "Curse", it's probably meant as some form of disruption
                if (!priority) {
                    for (final String sVar : c.getSVars().keySet()) {
                        if (c.getSVars().get(sVar).contains("AILogic$ Curse")) {
                            // this is a curse ability, so prioritize its removal
                            priority = true;
                            break;
                        }
                    }
                }
                // if it's a priority object, set its threat level to high
                if (priority) {
                    threat = 1.0f;
                }
            }
        } else {
            for (final StaticAbility stAb : c.getStaticAbilities()) {
                final Map<String, String> params = stAb.getMapParams();
                //continuous buffs
                if (params.get("Mode").equals("Continuous") && "Creature.YouCtrl".equals(params.get("Affected"))) {
                    int bonusPT = 0;
                    if (params.containsKey("AddPower")) {
                        bonusPT += AbilityUtils.calculateAmount(c, params.get("AddPower"), stAb);
                    }
                    if (params.containsKey("AddToughness")) {
                        bonusPT += AbilityUtils.calculateAmount(c, params.get("AddPower"), stAb);
                    }
                    String kws = params.get("AddKeyword");
                    if (kws != null) {
                        bonusPT += 4 * (1 + StringUtils.countMatches(kws, "&"));    //treat each added keyword as a +2/+2 for now
                    }
                    if (bonusPT > 0) {
                        threat = bonusPT * (1 + opp.getCreaturesInPlay().size()) / 10.0f;
                    }
                }
            }
            //TODO:add threat from triggers and other abilities (ie. Bident of Thassa)
        }
        if (!c.getManaAbilities().isEmpty()) {
            threat += 0.5f * costTarget / opp.getLandsInPlay().size();   //set back opponent's mana
        }
        
        final float valueNow = Math.max(valueTempo, threat);
        if (valueNow < 0.2) {   //hard floor to reduce ridiculous odds for instants over time
            return false;
        } else {
            final float chance = MyRandom.getRandom().nextFloat();
            return chance < valueNow;
        }
    }

    /**
     * Decides if the "pump" is worthwhile
     * @param ai casting player
     * @param sa Pump* or CounterPut*
     * @param c target of sa
     * @param toughness +T
     * @param power +P
     * @param keywords additional keywords from sa (only for Pump)
     * @return
     */
    public static boolean shouldPumpCard(final Player ai, final SpellAbility sa, final Card c, final int toughness,
                                         final int power, final List<String> keywords) {
        return shouldPumpCard(ai, sa, c, toughness, power, keywords, false);
    }

    public static boolean shouldPumpCard(final Player ai, final SpellAbility sa, final Card c, final int toughness,
            final int power, final List<String> keywords, boolean immediately) {
        final Game game = ai.getGame();
        final PhaseHandler phase = game.getPhaseHandler();
        final Combat combat = phase.getCombat();
        final boolean isBerserk = "Berserk".equals(sa.getParam("AILogic"));
        final boolean loseCardAtEOT = "Sacrifice".equals(sa.getParam("AtEOT")) || "Exile".equals(sa.getParam("AtEOT"))
                || "Destroy".equals(sa.getParam("AtEOT")) || "ExileCombat".equals(sa.getParam("AtEOT"));

        boolean combatTrick = false;
        boolean holdCombatTricks = false;
        int chanceToHoldCombatTricks = -1;

        if (ai.getController().isAI()) {
            AiController aic = ((PlayerControllerAi)ai.getController()).getAi();
            holdCombatTricks = aic.getBooleanProperty(AiProps.TRY_TO_HOLD_COMBAT_TRICKS_UNTIL_BLOCK);
            chanceToHoldCombatTricks = aic.getIntProperty(AiProps.CHANCE_TO_HOLD_COMBAT_TRICKS_UNTIL_BLOCK);
        }
        
        if (!c.canBeTargetedBy(sa)) {
            return false;
        }

        if (c.getNetToughness() + toughness <= 0) {
            return false;
        }

        /* -- currently disabled until better conditions are devised and the spell prediction is made smarter --
        // Determine if some mana sources need to be held for the future spell to cast in Main 2 before determining whether to pump.
        AiController aic = ((PlayerControllerAi)ai.getController()).getAi();
        if (aic.getCardMemory().isMemorySetEmpty(AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2)) {
            // only hold mana sources once
            SpellAbility futureSpell = aic.predictSpellToCastInMain2(ApiType.Pump);
            if (futureSpell != null && futureSpell.getHostCard() != null) {
                aic.reserveManaSources(futureSpell);
            }
        }
        */

        // will the creature attack (only relevant for sorcery speed)?
        if (phase.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)
                && phase.isPlayerTurn(ai)
                && SpellAbilityAi.isSorcerySpeed(sa)
                && power > 0
                && ComputerUtilCard.doesCreatureAttackAI(ai, c)) {
            return true;
        }

        // buff attacker/blocker using triggered pump (unless it's lethal and we don't want to be reckless)
        if (immediately && phase.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS) && !loseCardAtEOT) {
            if (phase.isPlayerTurn(ai)) {
                if (CombatUtil.canAttack(c)) {
                    return true;
                }
            } else {
                if (CombatUtil.canBlock(c)) {
                    return true;
                }
            }
        }

        final Player opp = ComputerUtil.getOpponentFor(ai);
        Card pumped = getPumpedCreature(ai, sa, c, toughness, power, keywords);
        List<Card> oppCreatures = opp.getCreaturesInPlay();
        float chance = 0;
        
        //create and buff attackers
        if (phase.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS) && phase.isPlayerTurn(ai) && opp.getLife() > 0) {
            //1. become attacker for whatever reason
            if (!ComputerUtilCard.doesCreatureAttackAI(ai, c) && ComputerUtilCard.doesSpecifiedCreatureAttackAI(ai, pumped)) {
                float threat = 1.0f * ComputerUtilCombat.damageIfUnblocked(pumped, opp, combat, true) / opp.getLife();
                if (CardLists.filter(oppCreatures, CardPredicates.possibleBlockers(pumped)).isEmpty()) {
                    threat *= 2;
                }
                if (c.getNetPower() == 0 && c == sa.getHostCard() && power > 0 ) {
                    threat *= 4;    //over-value self +attack for 0 power creatures which may be pumped further after attacking 
                }
                chance += threat;

                // -- Hold combat trick (the AI will try to delay the pump until Declare Blockers) --
                // Enable combat trick mode only in case it's a pure buff spell in hand with no keywords or with Trample,
                // First Strike, or Double Strike, otherwise the AI is unlikely to cast it or it's too late to
                // cast it during Declare Blockers, thus ruining its attacker
                if (holdCombatTricks && sa.getApi() == ApiType.Pump
                        && sa.hasParam("NumAtt") && sa.getHostCard() != null
                        && sa.getHostCard().getZone() != null && sa.getHostCard().getZone().is(ZoneType.Hand)
                        && c.getNetPower() > 0 // too obvious if attacking with a 0-power creature
                        && sa.getHostCard().isInstant() // only do it for instant speed spells in hand
                        && ComputerUtilMana.hasEnoughManaSourcesToCast(sa, ai)) {
                    combatTrick = true;

                    final List<String> kws = sa.hasParam("KW") ? Arrays.asList(sa.getParam("KW").split(" & "))
                            : Lists.<String>newArrayList();
                    for (String kw : kws) {
                        if (!kw.equals("Trample") && !kw.equals("First Strike") && !kw.equals("Double Strike")) {
                            combatTrick = false;
                            break;
                        }
                    }
                }
            }
            
            //2. grant haste
            if (keywords.contains("Haste") && c.hasSickness() && !c.isTapped()) {
                chance += 0.5f;
                if (ComputerUtilCard.doesSpecifiedCreatureAttackAI(ai, pumped)) {
                    chance += 0.5f * ComputerUtilCombat.damageIfUnblocked(pumped, opp, combat, true) / opp.getLife();
                }
            }
            
            //3. grant evasive
            if (!CardLists.filter(oppCreatures, CardPredicates.possibleBlockers(c)).isEmpty()) {
                if (CardLists.filter(oppCreatures, CardPredicates.possibleBlockers(pumped)).isEmpty() 
                        && ComputerUtilCard.doesSpecifiedCreatureAttackAI(ai, pumped)) {
                    chance += 0.5f * ComputerUtilCombat.damageIfUnblocked(pumped, opp, combat, true) / opp.getLife();
                }
            }
        }
        
        //combat trickery
        if (phase.is(PhaseType.COMBAT_DECLARE_BLOCKERS)) {
            //clunky code because ComputerUtilCombat.combatantWouldBeDestroyed() does not work for this sort of artificial combat
            Combat pumpedCombat = new Combat(phase.isPlayerTurn(ai) ? ai : opp);
            List<Card> opposing = null;
            boolean pumpedWillDie = false;
            final boolean isAttacking = combat.isAttacking(c);

            if ((isBerserk && isAttacking) || loseCardAtEOT) { pumpedWillDie = true; }

            if (isAttacking) {
                pumpedCombat.addAttacker(pumped, opp);
                opposing = combat.getBlockers(c);
                for (Card b : opposing) {
                    pumpedCombat.addBlocker(pumped, b);
                }
                if (ComputerUtilCombat.attackerWouldBeDestroyed(ai, pumped, pumpedCombat)) {
                    pumpedWillDie = true;
                }
            } else {
                opposing = combat.getAttackersBlockedBy(c);
                for (Card a : opposing) {
                    pumpedCombat.addAttacker(a, ai);
                    pumpedCombat.addBlocker(a, pumped);
                }
                if (ComputerUtilCombat.blockerWouldBeDestroyed(ai, pumped, pumpedCombat)) {
                    pumpedWillDie = true;
                }
            }
            
            //1. save combatant
            if (ComputerUtilCombat.combatantWouldBeDestroyed(ai, c, combat) && !pumpedWillDie 
                    && !c.hasKeyword(Keyword.INDESTRUCTIBLE)) {
                // hack because attackerWouldBeDestroyed()
                // does not check for Indestructible when computing lethal damage
                return true;
            }
            
            //2. kill combatant
            boolean survivor = false;
            for (Card o : opposing) {
                if (!ComputerUtilCombat.combatantWouldBeDestroyed(opp, o, combat)) {
                    survivor = true;
                    break;
                }
            }
            if (survivor) {
                for (Card o : opposing) {
                    if (!ComputerUtilCombat.combatantWouldBeDestroyed(opp, o, combat) 
                    		&& !(o.hasSVar("SacMe") && Integer.parseInt(o.getSVar("SacMe")) > 2)) {
                        if (isAttacking) {
                            if (ComputerUtilCombat.blockerWouldBeDestroyed(opp, o, pumpedCombat)) {
                                return true;
                            }
                        } else {
                            if (ComputerUtilCombat.attackerWouldBeDestroyed(opp, o, pumpedCombat)) {
                                return true;
                            }
                        }
                    }
                }
            }
            
            //3. buff attacker
            if (combat.isAttacking(c) && opp.getLife() > 0) {
                int dmg = ComputerUtilCombat.damageIfUnblocked(c, opp, combat, true);
                int pumpedDmg = ComputerUtilCombat.damageIfUnblocked(pumped, opp, pumpedCombat, true);
                int poisonOrig = opp.canReceiveCounters(CounterType.POISON) ? ComputerUtilCombat.poisonIfUnblocked(c, ai) : 0;
                int poisonPumped = opp.canReceiveCounters(CounterType.POISON) ? ComputerUtilCombat.poisonIfUnblocked(pumped, ai) : 0;

                // predict Infect
                if (pumpedDmg == 0 && c.hasKeyword(Keyword.INFECT)) {
                    if (poisonPumped > poisonOrig) {
                        pumpedDmg = poisonPumped;
                    }
                }

                if (combat.isBlocked(c)) {
                    if (!c.hasKeyword(Keyword.TRAMPLE)) {
                        dmg = 0;
                    }
                    if (c.hasKeyword(Keyword.TRAMPLE) || keywords.contains("Trample")) {
                       for (Card b : combat.getBlockers(c)) {
                           pumpedDmg -= ComputerUtilCombat.getDamageToKill(b);
                       }
                    } else {
                        pumpedDmg = 0;
                    }
                }
                if (pumpedDmg > dmg) {
                    if ((!c.hasKeyword(Keyword.INFECT) && pumpedDmg >= opp.getLife())
                            || (c.hasKeyword(Keyword.INFECT) && opp.canReceiveCounters(CounterType.POISON) && pumpedDmg >= opp.getPoisonCounters())) {
                        return true;
                    }
                }
                // try to determine if pumping a creature for more power will give lethal on board
                // considering all unblocked creatures after the blockers are already declared
                if (phase.is(PhaseType.COMBAT_DECLARE_BLOCKERS) && pumpedDmg > dmg) {
                    int totalPowerUnblocked = 0;
                    for (Card atk : combat.getAttackers()) {
                        if (combat.isBlocked(atk) && !atk.hasKeyword(Keyword.TRAMPLE)) {
                            continue;
                        }
                        if (atk == c) {
                            totalPowerUnblocked += pumpedDmg; // this accounts for Trample by now
                        } else {
                            totalPowerUnblocked += ComputerUtilCombat.damageIfUnblocked(atk, opp, combat, true);
                            if (combat.isBlocked(atk)) {
                                // consider Trample damage properly for a blocked creature
                                for (Card blk : combat.getBlockers(atk)) {
                                    totalPowerUnblocked -= ComputerUtilCombat.getDamageToKill(blk);
                                }
                            }
                        }
                    }
                    if (totalPowerUnblocked >= opp.getLife()) {
                        return true;
                    }
                }
                float value = 1.0f * (pumpedDmg - dmg);
                if (c == sa.getHostCard() && power > 0) {
                    int divisor = sa.getPayCosts().getTotalMana().getCMC();
                    if (divisor <= 0) {
                        divisor = 1;
                    }
                    value *= power / divisor;
                } else {
                    value /= opp.getLife();
                }
                chance += value;
            }
            
            //4. lifelink
            if (ai.canGainLife() && ai.getLife() > 0 && !c.hasKeyword(Keyword.LIFELINK) && keywords.contains("Lifelink")
                    && (combat.isAttacking(c) || combat.isBlocking(c))) {
                int dmg = pumped.getNetCombatDamage();
                //The actual dmg inflicted should be the sum of ComputerUtilCombat.predictDamageTo() for opposing creature
                //and trample damage (if any)
                chance += 1.0f * dmg / ai.getLife();
            }
            
            //5. if the life of the computer is in danger, try to pump blockers blocking Tramplers
            if (combat.isBlocking(c) && toughness > 0 ) {
                List<Card> blockedBy = combat.getAttackersBlockedBy(c);
                boolean attackerHasTrample = false;
                for (Card b : blockedBy) {
                    attackerHasTrample |= b.hasKeyword(Keyword.TRAMPLE);
                }
                if (attackerHasTrample && (sa.isAbility() || ComputerUtilCombat.lifeInDanger(ai, combat))) {
                    return true;
                }
            }
        }
        
        if (isBerserk) {
            // if we got here, Berserk will result in the pumped creature dying at EOT and the opponent will not lose
            // (other similar cards with AILogic$ Berserk that do not die only when attacking are excluded from consideration)
            if (ai.getController() instanceof PlayerControllerAi) {
                boolean aggr = ((PlayerControllerAi)ai.getController()).getAi().getBooleanProperty(AiProps.USE_BERSERK_AGGRESSIVELY)
                        || sa.hasParam("AtEOT");
                if (!aggr) {
                    return false;
                }
            }
        }

        boolean wantToHoldTrick = holdCombatTricks && !ai.getCardsIn(ZoneType.Hand).isEmpty();
        if (chanceToHoldCombatTricks >= 0) {
            // Obey the chance specified in the AI profile for holding combat tricks
            wantToHoldTrick &= MyRandom.percentTrue(chanceToHoldCombatTricks);
        } else {
            // Use standard considerations dependent solely on the buff chance determined above
            wantToHoldTrick &= MyRandom.getRandom().nextFloat() < chance;
        }

        boolean isHeldCombatTrick = combatTrick && wantToHoldTrick;

        if (isHeldCombatTrick) {
           if (AiCardMemory.isMemorySetEmpty(ai, AiCardMemory.MemorySet.TRICK_ATTACKERS)) {
               // Attempt to hold combat tricks until blockers are declared, and try to lure the opponent into blocking
               // (The AI will only do it for one attacker at the moment, otherwise it risks running his attackers into
               // an army of opposing blockers with only one combat trick in hand)
               // Reserve the mana until Declare Blockers such that the AI doesn't tap out before having a chance to use
               // the combat trick
        	   boolean reserved = false;
               if (ai.getController().isAI()) {
                   reserved = ((PlayerControllerAi) ai.getController()).getAi().reserveManaSources(sa, PhaseType.COMBAT_DECLARE_BLOCKERS, false);
                   // Only proceed with this if we could actually reserve mana
                   if (reserved) {
                       AiCardMemory.rememberCard(ai, c, AiCardMemory.MemorySet.MANDATORY_ATTACKERS);
                       AiCardMemory.rememberCard(ai, c, AiCardMemory.MemorySet.TRICK_ATTACKERS);
                       return false;
                   }
               }
           } else {
               // Don't try to mix "lure" and "precast" paradigms for combat tricks, since that creates issues with
               // the AI overextending the attack
               return false;
           }
        }

        return MyRandom.getRandom().nextFloat() < chance;
    }
    
    /**
     * Apply "pump" ability and return modified creature
     * @param ai casting player
     * @param sa Pump* or CounterPut*
     * @param c target of sa
     * @param toughness +T
     * @param power +P
     * @param keywords additional keywords from sa (only for Pump)
     * @return
     */
    public static Card getPumpedCreature(final Player ai, final SpellAbility sa,
            final Card c, int toughness, int power, final List<String> keywords) {
        Card pumped = CardFactory.copyCard(c, true);
        pumped.setSickness(c.hasSickness());
        final long timestamp = c.getGame().getNextTimestamp();
        final List<String> kws = new ArrayList<String>();
        for (String kw : keywords) {
            if (kw.startsWith("HIDDEN")) {
                pumped.addHiddenExtrinsicKeyword(kw);
            } else {
                kws.add(kw);
            }
        }

        // Berserk (and other similar cards)
        final boolean isBerserk = "Berserk".equals(sa.getParam("AILogic"));
        int berserkPower = 0;
        if (isBerserk && sa.hasSVar("X")) {
            if ("Targeted$CardPower".equals(sa.getSVar("X"))) {
                berserkPower = c.getCurrentPower();
            } else {
                berserkPower = AbilityUtils.calculateAmount(sa.getHostCard(), "X", sa);
            }
        }

        // Electrostatic Pummeler
        for (SpellAbility ab : c.getSpellAbilities()) {
            if ("Pummeler".equals(ab.getParam("AILogic"))) {
                Pair<Integer, Integer> newPT = SpecialCardAi.ElectrostaticPummeler.getPumpedPT(ai, power, toughness);
                power = newPT.getLeft();
                toughness = newPT.getRight();
            }
        }

        pumped.addNewPT(c.getCurrentPower(), c.getCurrentToughness(), timestamp);
        pumped.addTempPowerBoost(c.getTempPowerBoost() + power + berserkPower);
        pumped.addTempToughnessBoost(c.getTempToughnessBoost() + toughness);
        pumped.addChangedCardKeywords(kws, null, false, false, timestamp);
        Set<CounterType> types = c.getCounters().keySet();
        for(CounterType ct : types) {
            pumped.addCounterFireNoEvents(ct, c.getCounters(ct), c, true);
        }
        //Copies tap-state and extra keywords (auras, equipment, etc.) 
        if (c.isTapped()) {
            pumped.setTapped(true);
        }

        KeywordCollection copiedKeywords = new KeywordCollection();
        copiedKeywords.insertAll(pumped.getKeywords());
        List<KeywordInterface> toCopy = Lists.newArrayList();
        for (KeywordInterface k : c.getKeywords()) {
            if (!copiedKeywords.contains(k.getOriginal())) {
                if (k.getHidden()) {
                    pumped.addHiddenExtrinsicKeyword(k);
                } else {
                    toCopy.add(k.copy(c, false));
                }
            }
        }
        final long timestamp2 = c.getGame().getNextTimestamp(); //is this necessary or can the timestamp be re-used?
        pumped.addChangedCardKeywordsInternal(toCopy, null, false, false, timestamp2, true);
        ComputerUtilCard.applyStaticContPT(ai.getGame(), pumped, new CardCollection(c));
        return pumped;
    }
    
    /**
     * Applies static continuous Power/Toughness effects to a (virtual) creature.
     * @param game game instance to work with 
     * @param vCard creature to work with
     * @param exclude list of cards to exclude when considering ability sources, accepts null
     */
    public static void applyStaticContPT(final Game game, Card vCard, final CardCollectionView exclude) {
        if (!vCard.isCreature()) {
            return;
        }
        final CardCollection list = new CardCollection(game.getCardsIn(ZoneType.Battlefield));
        list.addAll(game.getCardsIn(ZoneType.Command));
        if (exclude != null) {
            list.removeAll(exclude);
        }
        list.add(vCard); // account for the static abilities that may be present on the card itself
        for (final Card c : list) {
            for (final StaticAbility stAb : c.getStaticAbilities()) {
                final Map<String, String> params = stAb.getMapParams();
                if (!params.get("Mode").equals("Continuous")) {
                    continue;
                }
                if (!params.containsKey("Affected")) {
                    continue;
                }
                if (!params.containsKey("AddPower") && !params.containsKey("AddToughness")) {
                    continue;
                }
                final String valid = params.get("Affected");
                if (!vCard.isValid(valid, c.getController(), c, null)) {
                    continue;
                }
                if (params.containsKey("AddPower")) {
                    String addP = params.get("AddPower");
                    int att = 0;
                    if (addP.equals("AffectedX")) {
                        att = CardFactoryUtil.xCount(vCard, AbilityUtils.getSVar(stAb, addP));
                    } else {
                        att = AbilityUtils.calculateAmount(c, addP, stAb);
                    }
                    vCard.addTempPowerBoost(att);
                }
                if (params.containsKey("AddToughness")) {
                    String addT = params.get("AddToughness");
                    int def = 0;
                    if (addT.equals("AffectedY")) {
                        def = CardFactoryUtil.xCount(vCard, AbilityUtils.getSVar(stAb, addT));
                    } else {
                        def = AbilityUtils.calculateAmount(c, addT, stAb);
                    }
                    vCard.addTempToughnessBoost(def);
                }
            }
        }
    }
    
    /**
     * Evaluate if the ability can save a target against removal
     * @param ai casting player
     * @param sa Pump* or CounterPut*
     * @return
     */
    public static boolean canPumpAgainstRemoval(Player ai, SpellAbility sa) {
        final List<GameObject> objects = ComputerUtil.predictThreatenedObjects(sa.getActivatingPlayer(), sa, true);
        final CardCollection threatenedTargets = new CardCollection();

        if (!sa.usesTargeting()) {
            final List<Card> cards = AbilityUtils.getDefinedCards(sa.getHostCard(), sa.getParam("Defined"), sa);
            for (final Card card : cards) {
                if (objects.contains(card)) {
                    return true;
                }
            }
            // For pumps without targeting restrictions, just return immediately until this is fleshed out.
            return false;
        }
        CardCollection targetables = CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Battlefield), sa);

        targetables = ComputerUtil.getSafeTargets(ai, sa, targetables);
        for (final Card c : targetables) {
            if (objects.contains(c)) {
                threatenedTargets.add(c);
            }
        }
        if (!threatenedTargets.isEmpty()) {
            ComputerUtilCard.sortByEvaluateCreature(threatenedTargets);
            for (Card c : threatenedTargets) {
                if (sa.canAddMoreTarget()) {
                    sa.getTargets().add(c);
                    if (!sa.canAddMoreTarget()) {
                        break;
                    }
                }
            }
            if (!sa.isTargetNumberValid()) {
                sa.resetTargets();
                return false;
            }
            return true;
        }
        return false;
    }

    public static boolean isUselessCreature(Player ai, Card c) {
        if (c == null) {
            return true;
        }
        if (!c.isCreature()) {
            return false;
        }
        if (c.hasKeyword("CARDNAME can't attack or block.") || (c.hasKeyword("CARDNAME doesn't untap during your untap step.") && c.isTapped()) || (c.getOwner() == ai && ai.getOpponents().contains(c.getController()))) {
            return true;
        }
        return false;
    }

    public static boolean hasActiveUndyingOrPersist(final Card c) {
        if (c.hasKeyword(Keyword.UNDYING) && c.getCounters(CounterType.P1P1) == 0) {
            return true;
        }
        if (c.hasKeyword(Keyword.PERSIST) && c.getCounters(CounterType.M1M1) == 0) {
            return true;
        }
        return false;
    }

    public static boolean isPresentOnBattlefield(final Game game, final String cardName) {
        return !CardLists.filter(game.getCardsIn(ZoneType.Battlefield), CardPredicates.nameEquals(cardName)).isEmpty();
    }

    public static int getMaxSAEnergyCostOnBattlefield(final Player ai) {
        // returns the maximum energy cost of an ability that permanents on the battlefield under AI's control have
        CardCollectionView otb = ai.getCardsIn(ZoneType.Battlefield);
        int maxEnergyCost = 0;

        for (Card c : otb) {
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (sa.getPayCosts() == null) {
                    continue;
                }

                CostPayEnergy energyCost = sa.getPayCosts().getCostEnergy();
                if (energyCost != null) {
                    int amount = energyCost.convertAmount();
                    if (amount > maxEnergyCost) {
                        maxEnergyCost = amount;
                    }
                }
            }
        }

        return maxEnergyCost;
    }

    public static CardCollection prioritizeCreaturesWorthRemovingNow(final Player ai, CardCollection oppCards, final boolean temporary) {
        if (!CardLists.getNotType(oppCards, "Creature").isEmpty()) {
            // non-creatures were passed, nothing to do here
            return oppCards;
        }

        boolean enablePriorityRemoval = false;
        boolean priorityRemovalOnlyInDanger = false;
        int priorityRemovalThreshold = 0;
        int lifeInDanger = 5;
        if (ai.getController().isAI()) {
            AiController aic = ((PlayerControllerAi)ai.getController()).getAi();
            enablePriorityRemoval = aic.getBooleanProperty(AiProps.ACTIVELY_DESTROY_IMMEDIATELY_UNBLOCKABLE);
            priorityRemovalThreshold = aic.getIntProperty(AiProps.DESTROY_IMMEDIATELY_UNBLOCKABLE_THRESHOLD);
            priorityRemovalOnlyInDanger = aic.getBooleanProperty(AiProps.DESTROY_IMMEDIATELY_UNBLOCKABLE_ONLY_IN_DNGR);
            lifeInDanger = aic.getIntProperty(AiProps.DESTROY_IMMEDIATELY_UNBLOCKABLE_LIFE_IN_DNGR);
        }

        if (!enablePriorityRemoval) {
            // Nothing to do here, the profile does not allow prioritizing
            return oppCards;
        }

        CardCollection aiCreats = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.Presets.CREATURES);
        if (temporary) {
            // Pump effects that add "CARDNAME can't attack" and similar things. Only do it if something is untapped.
            oppCards = CardLists.filter(oppCards, CardPredicates.Presets.UNTAPPED);
        }

        CardCollection priorityCards = new CardCollection();
        for (Card atk : oppCards) {
            boolean canBeBlocked = false;
            if (isUselessCreature(atk.getController(), atk)) {
                continue;
            }
            for (Card blk : aiCreats) {
                if (CombatUtil.canBlock(atk, blk, true)) {
                    canBeBlocked = true;
                    break;
                }
            }
            if (!canBeBlocked) {
                boolean threat = atk.getNetCombatDamage() >= ai.getLife() - lifeInDanger;
                if (!priorityRemovalOnlyInDanger || threat) {
                    priorityCards.add(atk);
                }
            }
        }

        if (!priorityCards.isEmpty() && priorityCards.size() <= priorityRemovalThreshold) {
            return priorityCards;
        }

        return oppCards;
    }

    public static AiPlayDecision checkNeedsToPlayReqs(final Card card, final SpellAbility sa) {
        Game game = card.getGame();
        boolean isRightSplit = sa != null && sa.isRightSplit();
        String needsToPlayName = isRightSplit ? "SplitNeedsToPlay" : "NeedsToPlay";
        String needsToPlayVarName = isRightSplit ? "SplitNeedsToPlayVar" : "NeedsToPlayVar";

        if (card.hasSVar(needsToPlayName)) {
            final String needsToPlay = card.getSVar(needsToPlayName);
            CardCollectionView list = game.getCardsIn(ZoneType.Battlefield);

            list = CardLists.getValidCards(list, needsToPlay.split(","), card.getController(), card, null);
            if (list.isEmpty()) {
                return AiPlayDecision.MissingNeededCards;
            }
        }
        if (card.getSVar(needsToPlayVarName).length() > 0) {
            final String needsToPlay = card.getSVar(needsToPlayVarName);
            int x = 0;
            int y = 0;
            String sVar = needsToPlay.split(" ")[0];
            String comparator = needsToPlay.split(" ")[1];
            String compareTo = comparator.substring(2);
            try {
                x = Integer.parseInt(sVar);
            } catch (final NumberFormatException e) {
                x = CardFactoryUtil.xCount(card, card.getSVar(sVar));
            }
            try {
                y = Integer.parseInt(compareTo);
            } catch (final NumberFormatException e) {
                y = CardFactoryUtil.xCount(card, card.getSVar(compareTo));
            }
            if (!Expressions.compare(x, comparator, y)) {
                return AiPlayDecision.NeedsToPlayCriteriaNotMet;
            }
        }

        return AiPlayDecision.WillPlay;
    }
}
