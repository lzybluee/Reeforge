package forge.ai.ability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import forge.game.card.*;
import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import forge.ai.*;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.GlobalRuleChange;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.CardPredicates.Presets;
import forge.game.combat.Combat;
import forge.game.cost.Cost;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostPart;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

public class ChangeZoneAi extends SpellAbilityAi {
    /*
     * This class looks horribly convoluted with hidden/known + CanPlay/Drawback/Trigger
     * and static functions like chooseCardToHiddenOriginChangeZone(). It might be a good
     * idea to re-factor ChangeZoneAi into more specific effects since it is really doing
     * too much: blink/bounce/exile/tutor/Raise Dead/Surgical Extraction/......
     */
    
    @Override
    protected boolean checkAiLogic(final Player ai, final SpellAbility sa, final String aiLogic) {
        if (sa.getHostCard() != null && sa.getHostCard().hasSVar("AIPreferenceOverride")) {
            // currently used by SacAndUpgrade logic, might need simplification
            sa.getHostCard().removeSVar("AIPreferenceOverride");
        }

        if (aiLogic.equals("BeforeCombat")) {
            if (ai.getGame().getPhaseHandler().getPhase().isAfter(PhaseType.COMBAT_BEGIN)) {
                return false;
            }
        } else if (aiLogic.equals("SurpriseBlock")) {
            if (ai.getGame().getPhaseHandler().getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
                return false;
            }
        }
        return super.checkAiLogic(ai, sa, aiLogic);
    }

    @Override
    protected boolean checkApiLogic(Player aiPlayer, SpellAbility sa) {
        // Checks for "return true" unlike checkAiLogic()
        String aiLogic = sa.getParam("AILogic");
        if (aiLogic != null) {
            if (aiLogic.equals("Always")) {
                return true;
            } else if (aiLogic.startsWith("SacAndUpgrade")) { // Birthing Pod, Natural Order, etc.
                return this.doSacAndUpgradeLogic(aiPlayer, sa);
            } else if (aiLogic.equals("Necropotence")) {
                return SpecialCardAi.Necropotence.consider(aiPlayer, sa);
            } else if (aiLogic.equals("SameName")) {   // Declaration in Stone
                return this.doSameNameLogic(aiPlayer, sa);
            }
        }
        if (isHidden(sa)) {
            return hiddenOriginCanPlayAI(aiPlayer, sa);
        }
        return knownOriginCanPlayAI(aiPlayer, sa);
    }

    /**
     * <p>
     * changeZonePlayDrawbackAI.
     * </p>
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * 
     * @return a boolean.
     */
    @Override
    public boolean chkAIDrawback(SpellAbility sa, Player aiPlayer) {
        if (isHidden(sa)) {
            return hiddenOriginPlayDrawbackAI(aiPlayer, sa);
        }
        return knownOriginPlayDrawbackAI(aiPlayer, sa);
    }


    private static boolean isHidden(SpellAbility sa) {
        boolean hidden = sa.hasParam("Hidden");
        if (!hidden && sa.hasParam("Origin")) {
            hidden = ZoneType.isHidden(sa.getParam("Origin"));
        }
        return hidden;
    }

    /**
     * <p>
     * changeZoneTriggerAINoCost.
     * </p>
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param mandatory
     *            a boolean.
     * 
     * @return a boolean.
     */
    @Override
    protected boolean doTriggerAINoCost(Player aiPlayer, SpellAbility sa, boolean mandatory) {
        if (sa.isReplacementAbility() && "Command".equals(sa.getParam("Destination")) && "ReplacedCard".equals(sa.getParam("Defined"))) {
            // Process the commander replacement effect ("return to Command zone instead")
            return doReturnCommanderLogic(sa, aiPlayer);
        }

        if (isHidden(sa)) {
            return hiddenTriggerAI(aiPlayer, sa, mandatory);
        }
        return knownOriginTriggerAI(aiPlayer, sa, mandatory);
    }

    // *************************************************************************************
    // ************ Hidden Origin (Library/Hand/Sideboard/Non-targetd other)
    // ***************
    // ******* Hidden origin cards are chosen on the resolution of the spell
    // ***************
    // ******* It is possible for these to have Destination of Battlefield
    // *****************
    // ****** Example: Cavern Harpy where you don't choose the card until
    // resolution *******
    // *************************************************************************************

    /**
     * <p>
     * changeHiddenOriginCanPlayAI.
     * </p>
     *
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a boolean.
     */
    private static boolean hiddenOriginCanPlayAI(final Player ai, final SpellAbility sa) {
        // Fetching should occur fairly often as it helps cast more spells, and
        // have access to more mana
        final Cost abCost = sa.getPayCosts();
        final Card source = sa.getHostCard();
        final String sourceName = ComputerUtilAbility.getAbilitySourceName(sa);
        ZoneType origin = null;
        final Player opponent = ComputerUtil.getOpponentFor(ai);
        boolean activateForCost = ComputerUtil.activateForCost(sa, ai);

        if (sa.hasParam("Origin")) {
            try {
                origin = ZoneType.smartValueOf(sa.getParam("Origin"));
            } catch (IllegalArgumentException ex) {
                // This happens when Origin is something like
                // "Graveyard,Library" (Doomsday)
                return false;
            }
        }
        final String destination = sa.getParam("Destination");

        if (abCost != null) {
            // AI currently disabled for these costs
            if (!ComputerUtilCost.checkSacrificeCost(ai, abCost, source, sa)
                    && !(destination.equals("Battlefield") && !source.isLand())) {
                return false;
            }

            if (!ComputerUtilCost.checkLifeCost(ai, abCost, source, 4, sa)) {
                return false;
            }

            if (!ComputerUtilCost.checkDiscardCost(ai, abCost, source)) {
                for (final CostPart part : abCost.getCostParts()) {
                    if (part instanceof CostDiscard) {
                        CostDiscard cd = (CostDiscard) part;
                        // this is mainly for typecycling
                        if (!cd.payCostFromSource() || !ComputerUtil.isWorseThanDraw(ai, source)) {
                            return false;
                        }
                    }
                }
            }

            //Ninjutsu
            if (sa.hasParam("Ninjutsu")) {
                if (source.getType().isLegendary()
                        && !ai.getGame().getStaticEffects().getGlobalRuleChange(GlobalRuleChange.noLegendRule)) {
                    final CardCollectionView list = ai.getCardsIn(ZoneType.Battlefield);
                    if (Iterables.any(list, CardPredicates.nameEquals(source.getName()))) {
                        return false;
                    }
                }
                if (ai.getGame().getPhaseHandler().getPhase().isAfter(PhaseType.COMBAT_DAMAGE)) {
                    return false;
                }
                List<Card> attackers = ai.getGame().getCombat().getUnblockedAttackers();
                boolean lowerCMC = false;
                for (Card attacker : attackers) {
                    if (attacker.getCMC() < source.getCMC()) {
                        lowerCMC = true;
                        break;
                    }
                }
                if (!lowerCMC) {
                    return false;
                }
            }
        }

        // don't play if the conditions aren't met, unless it would trigger a beneficial sub-condition
        if (!activateForCost && !sa.getConditions().areMet(sa)) {
            final AbilitySub abSub = sa.getSubAbility();
            if (abSub != null && !sa.isWrapper() && "True".equals(source.getSVar("AIPlayForSub"))) {
                if (!abSub.getConditions().areMet(abSub)) {
                    return false;
                }
            } else {
                return false;
            }
        }
        // prevent run-away activations - first time will always return true
        if (ComputerUtil.preventRunAwayActivations(sa)) {
            return false;
        }

        Iterable<Player> pDefined = Lists.newArrayList(source.getController());
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        if (tgt != null && tgt.canTgtPlayer()) {
            boolean isCurse = sa.isCurse();
            if (isCurse && sa.canTarget(opponent)) {
                sa.getTargets().add(opponent);
            } else if (!isCurse && sa.canTarget(ai)) {
                sa.getTargets().add(ai);
            }
            pDefined = sa.getTargets().getTargetPlayers();
        } else {
            if (sa.hasParam("DefinedPlayer")) {
                pDefined = AbilityUtils.getDefinedPlayers(sa.getHostCard(), sa.getParam("DefinedPlayer"), sa);
            } else {
                pDefined = AbilityUtils.getDefinedPlayers(sa.getHostCard(), sa.getParam("Defined"), sa);
            }
        }

        String type = sa.getParam("ChangeType");
        if (type != null) {
            if (type.contains("X") && source.getSVar("X").equals("Count$xPaid")) {
                // Set PayX here to maximum value.
                final int xPay = ComputerUtilMana.determineLeftoverMana(sa, ai);
                source.setSVar("PayX", Integer.toString(xPay));
                type = type.replace("X", Integer.toString(xPay));
            }
        }

        for (final Player p : pDefined) {
            CardCollectionView list = p.getCardsIn(origin);

            if (type != null && p == ai) {
                // AI only "knows" about his information
                list = CardLists.getValidCards(list, type, source.getController(), source);
                list = CardLists.filter(list, new Predicate<Card>() {
                    @Override
                    public boolean apply(final Card c) {
                        if (c.getType().isLegendary()) {
                            if (ai.isCardInPlay(c.getName())) {
                                return false;
                            }
                        }
                        return true;
                    }
                });
            }
            // TODO: prevent ai seaching its own library when Ob Nixilis, Unshackled is in play
            if (origin != null && origin.isKnown()) {
                list = CardLists.getValidCards(list, type, source.getController(), source);
            }

            if (!activateForCost && list.isEmpty()) {
                return false;
            }
            if ("Atarka's Command".equals(sourceName) 
            		&& (list.size() < 2 || ai.getLandsPlayedThisTurn() < 1)) {
            	// be strict on playing lands off charms
            	return false;
            }

            String num = sa.getParam("ChangeNum");
            if (num != null) {
                if (num.contains("X") && source.getSVar("X").equals("Count$xPaid")) {
                    // Set PayX here to maximum value.
                    int xPay = ComputerUtilMana.determineLeftoverMana(sa, ai);
                    xPay = Math.min(xPay, list.size());
                    source.setSVar("PayX", Integer.toString(xPay));
                }
            }
            
            if (sourceName.equals("Temur Sabertooth")) {
                // activated bounce + pump
                if (ComputerUtilCard.shouldPumpCard(ai, sa.getSubAbility(), source, 0, 0, Arrays.asList("Indestructible")) ||
                        ComputerUtilCard.canPumpAgainstRemoval(ai, sa.getSubAbility())) {
                    for (Card c : list) {
                        if (ComputerUtilCard.evaluateCreature(c) < ComputerUtilCard.evaluateCreature(source)) {
                            return true;
                        }
                    }
                }
                return canBouncePermanent(ai, sa, list) != null;
            }

        }
        
        if (ComputerUtil.playImmediately(ai, sa)) {
        	return true;
        }

        // don't use fetching to top of library/graveyard before main2
        if (ai.getGame().getPhaseHandler().getPhase().isBefore(PhaseType.MAIN2)
                && !sa.hasParam("ActivationPhases")) {
            if (!destination.equals("Battlefield") && !destination.equals("Hand")) {
                return false;
            }
            // Only tutor something in main1 if hand is almost empty
            if (ai.getCardsIn(ZoneType.Hand).size() > 1 && destination.equals("Hand")) {
                return false;
            }
        }

        if (ComputerUtil.waitForBlocking(sa)) {
        	return false;
        }
        
        final AbilitySub subAb = sa.getSubAbility();
        return subAb == null || SpellApiToAi.Converter.get(subAb.getApi()).chkDrawbackWithSubs(ai, subAb);
    }

    /**
     * <p>
     * changeHiddenOriginPlayDrawbackAI.
     * </p>
     *
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a boolean.
     */
    private static boolean hiddenOriginPlayDrawbackAI(final Player aiPlayer, final SpellAbility sa) {
        // if putting cards from hand to library and parent is drawing cards
        // make sure this will actually do something:
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        final Player opp = ComputerUtil.getOpponentFor(aiPlayer);
        if (tgt != null && tgt.canTgtPlayer()) {
            boolean isCurse = sa.isCurse();
            if (isCurse && sa.canTarget(opp)) {
                sa.getTargets().add(opp);
            } else if (!isCurse && sa.canTarget(aiPlayer)) {
                sa.getTargets().add(aiPlayer);
            } else {
                return false;
            }
        }

        return true;
    }

    /**
     * <p>
     * changeHiddenTriggerAI.
     * </p>
     *
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param mandatory
     *            a boolean.
     * @return a boolean.
     */
    private static boolean hiddenTriggerAI(final Player ai, final SpellAbility sa, final boolean mandatory) {
        // Fetching should occur fairly often as it helps cast more spells, and
        // have access to more mana

        final Card source = sa.getHostCard();

        if (sa.hasParam("AILogic")) {
            if (sa.getParam("AILogic").equals("Never")) {
                /*
                 * Hack to stop AI from using Aviary Mechanic's "may bounce" trigger.
                 * Ideally it should look for a good bounce target like "Pacifism"-victims
                 * but there is no simple way to check that. It is preferable for the AI
                 * to make sub-optimal choices (waste bounce) than to make obvious mistakes
                 * (bounce useful permanent).
                 */
                return false;
            }
        }

        List<ZoneType> origin = new ArrayList<ZoneType>();
        if (sa.hasParam("Origin")) {
            origin = ZoneType.listValueOf(sa.getParam("Origin"));
        }

        // this works for hidden because the mana is paid first.
        final String type = sa.getParam("ChangeType");
        if (type != null && type.contains("X") && source.getSVar("X").equals("Count$xPaid")) {
            // Set PayX here to maximum value.
            final int xPay = ComputerUtilMana.determineLeftoverMana(sa, ai);
            source.setSVar("PayX", Integer.toString(xPay));
        }

        Iterable<Player> pDefined;
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        if ((tgt != null) && tgt.canTgtPlayer()) {
            final Player opp = ComputerUtil.getOpponentFor(ai);
            if (sa.isCurse()) {
                if (sa.canTarget(opp)) {
                    sa.getTargets().add(opp);
                } else if (mandatory && sa.canTarget(ai)) {
                    sa.getTargets().add(ai);
                }
            } else {
                if (sa.canTarget(ai)) {
                    sa.getTargets().add(ai);
                } else if (mandatory && sa.canTarget(opp)) {
                    sa.getTargets().add(opp);
                }
            }

            pDefined = sa.getTargets().getTargetPlayers();

            if (Iterables.isEmpty(pDefined)) {
                return false;
            }

            if (mandatory) {
                return true;
            }
        } else {
            if (mandatory) {
                return true;
            }
            pDefined = AbilityUtils.getDefinedPlayers(sa.getHostCard(), sa.getParam("Defined"), sa);
        }

        for (final Player p : pDefined) {
            CardCollectionView list = p.getCardsIn(origin);

            // Computer should "know" his deck
            if (p == ai) {
                list = AbilityUtils.filterListByType(list, sa.getParam("ChangeType"), sa);
            }

            if (list.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // *********** Utility functions for Hidden ********************
    /**
     * <p>
     * basicManaFixing.
     * </p>
     * @param ai
     * 
     * @param list
     *            a List<Card> object.
     * @return a {@link forge.game.card.Card} object.
     */
    private static Card basicManaFixing(final Player ai, final List<Card> list) { // Search for a Basic Land
        final CardCollectionView combined = CardCollection.combine(ai.getCardsIn(ZoneType.Battlefield), ai.getCardsIn(ZoneType.Hand));
        final List<String> basics = new ArrayList<String>();

        // what types can I go get?
        for (final String name : MagicColor.Constant.BASIC_LANDS) {
            if (!CardLists.getType(list, name).isEmpty()) {
                basics.add(name);
            }
        }

        // Which basic land is least available from hand and play, that I still
        // have in my deck
        int minSize = Integer.MAX_VALUE;
        String minType = null;

        for (String b : basics) {
            final int num = CardLists.getType(combined, b).size();
            if (num < minSize) {
                minType = b;
                minSize = num;
            }
        }

        List<Card> result = list;
        if (minType != null) {
            result = CardLists.getType(list, minType);
        }
        
        // pick dual lands if available
        if (Iterables.any(result, Predicates.not(CardPredicates.Presets.BASIC_LANDS))) {
            result = CardLists.filter(result, Predicates.not(CardPredicates.Presets.BASIC_LANDS));
        }

        return result.get(0);
    }

    /**
     * <p>
     * areAllBasics.
     * </p>
     * 
     * @param types
     *            a {@link java.lang.String} object.
     * @return a boolean.
     */
    private static boolean areAllBasics(final String types) {
        for (String ct : types.split(",")) {
            if (!MagicColor.Constant.BASIC_LANDS.contains(ct)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Some logic for picking a creature card from a list.
     * @param list
     * @return Card
     */
    private static Card chooseCreature(final Player ai, CardCollection list) {
        // Creating a new combat for testing purposes. 
    	final Player opponent = ComputerUtil.getOpponentFor(ai);
        Combat combat = new Combat(opponent);
        for (Card att : opponent.getCreaturesInPlay()) {
            combat.addAttacker(att, ai);
        }
        AiBlockController block = new AiBlockController(ai);
        block.assignBlockersForCombat(combat);

        if (ComputerUtilCombat.lifeInDanger(ai, combat)) {
            // need something AI can cast now
            ComputerUtilCard.sortByEvaluateCreature(list);
            for (Card c : list) {
                if (ComputerUtilMana.hasEnoughManaSourcesToCast(c.getFirstSpellAbility(), ai))
                   return c;
            }
            return null;
        }

        // not urgent, get the largest creature possible
        return ComputerUtilCard.getBestCreatureAI(list);
    }

    // *************************************************************************************
    // **************** Known Origin (Battlefield/Graveyard/Exile) *************************
    // ******* Known origin cards are chosen during casting of the spell (target) **********
    // *************************************************************************************

    /**
     * <p>
     * changeKnownOriginCanPlayAI.
     * </p>
     *
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a boolean.
     */
    private static boolean knownOriginCanPlayAI(final Player ai, final SpellAbility sa) {
        // Retrieve either this card, or target Cards in Graveyard

        final List<ZoneType> origin = Lists.newArrayList();
        if (sa.hasParam("Origin")) {
            origin.addAll(ZoneType.listValueOf(sa.getParam("Origin")));
        } else if (sa.hasParam("TgtZone")) {
            origin.addAll(ZoneType.listValueOf(sa.getParam("TgtZone")));
        }

        final ZoneType destination = ZoneType.smartValueOf(sa.getParam("Destination"));

        if (ComputerUtil.preventRunAwayActivations(sa)) {
            return false;
        }

        if (sa.usesTargeting()) {
            if (!isPreferredTarget(ai, sa, false, false)) {
                return false;
            }
        } else {
            // non-targeted retrieval
            final List<Card> retrieval = sa.knownDetermineDefined(sa.getParam("Defined"));

            if (retrieval == null || retrieval.isEmpty()) {
                return false;
            }

            // if (origin.equals("Graveyard")) {
            // return this card from graveyard: cards like Hammer of Bogardan
            // in general this is cool, but we should add some type of
            // restrictions

            // return this card from battlefield: cards like Blinking Spirit
            // in general this should only be used to protect from Imminent Harm
            // (dying or losing control of)
            if (origin.contains(ZoneType.Battlefield)) {
                if (ai.getGame().getStack().isEmpty()) {
                    return false;
                }

                final AbilitySub abSub = sa.getSubAbility();
                ApiType subApi = null;
                if (abSub != null) {
                    subApi = abSub.getApi();
                }

                // only use blink or bounce effects
                if (!(destination.equals(ZoneType.Exile) && (subApi == ApiType.DelayedTrigger || subApi == ApiType.ChangeZone))
                        && !destination.equals(ZoneType.Hand)) {
                    return false;
                }

                final List<GameObject> objects = ComputerUtil.predictThreatenedObjects(ai, sa);
                boolean contains = false;
                for (final Card c : retrieval) {
                    if (objects.contains(c)) {
                        contains = true;
                        break;
                    }
                }
                if (!contains) {
                    return false;
                }
            }

            if (destination == ZoneType.Battlefield) {
                // predict whether something may put a ETBing creature below zero toughness
                // (e.g. Reassembing Skeleton + Elesh Norn, Grand Cenobite)
                for (final Card c : retrieval) {
                    if (c.isCreature()) {
                        final Card copy = CardUtil.getLKICopy(c);
                        ComputerUtilCard.applyStaticContPT(c.getGame(), copy, null);
                        if (copy.getNetToughness() <= 0) {
                            return false;
                        }
                    }
                }
            }
        }

        final AbilitySub subAb = sa.getSubAbility();
        if (subAb != null && !SpellApiToAi.Converter.get(subAb.getApi()).chkDrawbackWithSubs(ai, subAb)) {
            return false;
        }

        return true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.ai.SpellAbilityAi#checkPhaseRestrictions(forge.game.player.Player,
     * forge.game.spellability.SpellAbility, forge.game.phase.PhaseHandler)
     */
    @Override
    protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, PhaseHandler ph) {
        if (isHidden(sa)) {
            return true;
        }

        final List<ZoneType> origin = Lists.newArrayList();
        if (sa.hasParam("Origin")) {
            origin.addAll(ZoneType.listValueOf(sa.getParam("Origin")));
        } else if (sa.hasParam("TgtZone")) {
            origin.addAll(ZoneType.listValueOf(sa.getParam("TgtZone")));
        }

        final ZoneType destination = ZoneType.smartValueOf(sa.getParam("Destination"));

        // don't return something to your hand if your hand is full of good stuff
        if (destination.equals(ZoneType.Hand) && origin.contains(ZoneType.Graveyard)) {
            final int handSize = ai.getCardsIn(ZoneType.Hand).size();
            if (ph.getPhase().isBefore(PhaseType.MAIN1)) {
                return false;
            }
            if (ph.getPhase().isBefore(PhaseType.MAIN2) && handSize > 1) {
                return false;
            }
            if (ph.isPlayerTurn(ai) && handSize >= ai.getMaxHandSize()) {
                return false;
            }
        }
        
        //don't unearth after attacking is possible
        if (sa.hasParam("Unearth") && ph.getPhase().isAfter(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
            return false;
        }

        if (destination.equals(ZoneType.Library) && origin.contains(ZoneType.Graveyard)) {
            if (ph.getPhase().isBefore(PhaseType.MAIN2)) {
                return false;
            }
            if (ComputerUtil.waitForBlocking(sa)) {
                return false;
            }
        }

        return super.checkPhaseRestrictions(ai, sa, ph);
    }

    /**
     * <p>
     * changeKnownOriginPlayDrawbackAI.
     * </p>
     *
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @return a boolean.
     */
    private static boolean knownOriginPlayDrawbackAI(final Player aiPlayer, final SpellAbility sa) {
        if (!sa.usesTargeting()) {
            return true;
        }

        return isPreferredTarget(aiPlayer, sa, false, true);
    }

    /**
     * <p>
     * changeKnownPreferredTarget.
     * </p>
     *
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param mandatory
     *            a boolean.
     * @return a boolean.
     */
    private static boolean isPreferredTarget(final Player ai, final SpellAbility sa, final boolean mandatory, boolean immediately) {
        final Card source = sa.getHostCard();
        final List<ZoneType> origin = Lists.newArrayList();
        if (sa.hasParam("Origin")) {
            origin.addAll(ZoneType.listValueOf(sa.getParam("Origin")));
        } else if (sa.hasParam("TgtZone")) {
            origin.addAll(ZoneType.listValueOf(sa.getParam("TgtZone")));
        }

        final ZoneType destination = ZoneType.smartValueOf(sa.getParam("Destination"));
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        final Game game = ai.getGame();

        final AbilitySub abSub = sa.getSubAbility();
        ApiType subApi = null;
        String subAffected = "";
        if (abSub != null) {
            subApi = abSub.getApi();
            if (abSub.hasParam("Defined")) {
                subAffected = abSub.getParam("Defined");
            }
        }

        sa.resetTargets();
        CardCollection list = CardLists.getValidCards(game.getCardsIn(origin), tgt.getValidTgts(), ai, source, sa);
        list = CardLists.getTargetableCards(list, sa);
        if (sa.hasParam("AITgts")) {
            list = CardLists.getValidCards(list, sa.getParam("AITgts"), ai, source);
        }
        if (source.isInZone(ZoneType.Hand)) {
            list = CardLists.filter(list, Predicates.not(CardPredicates.nameEquals(source.getName()))); // Don't get the same card back.
        }
        if (sa.isSpell()) {
            list.remove(source); // spells can't target their own source, because it's actually in the stack zone
        }
        //System.out.println("isPreferredTarget " + list);
        if (sa.hasParam("AttachedTo")) {
            //System.out.println("isPreferredTarget att " + list);
            list = CardLists.filter(list, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
                        if (card.isValid(sa.getParam("AttachedTo"), ai, c, sa)) {
                            return true;
                        }
                    }
                    return false;
                }
            });
            //System.out.println("isPreferredTarget ok " + list);
        }

        if (list.size() < tgt.getMinTargets(sa.getHostCard(), sa)) {
            return false;
        }
        
        immediately |= ComputerUtil.playImmediately(ai, sa);

        // Narrow down the list:
        if (origin.contains(ZoneType.Battlefield)) {
            if ("Polymorph".equals(sa.getParam("AILogic"))) {
                list = CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Battlefield), sa);
                if (list.isEmpty()) {
                    return false;
                }
                Card worst = ComputerUtilCard.getWorstAI(list);
                if (worst.isCreature() && ComputerUtilCard.evaluateCreature(worst) >= 200) {
                    return false;
                }
                if (!worst.isCreature() && worst.getCMC() > 1) {
                    return false;
                }
                sa.getTargets().add(worst);
                return true;
            }

            // Combat bouncing
            if (tgt.getMinTargets(sa.getHostCard(), sa) <= 1) {
                if (game.getPhaseHandler().is(PhaseType.COMBAT_DECLARE_BLOCKERS)) {
                    Combat currCombat = game.getCombat();
                    CardCollection attackers = currCombat.getAttackers();
                    ComputerUtilCard.sortByEvaluateCreature(attackers);
                    for (Card attacker : attackers) {
                        CardCollection blockers = currCombat.getBlockers(attacker);
                        // Save my attacker by bouncing a blocker
                        if (attacker.getController().equals(ai) && attacker.getShieldCount() == 0
                        		&& ComputerUtilCombat.attackerWouldBeDestroyed(ai, attacker, currCombat) 
                                && !currCombat.getBlockers(attacker).isEmpty()) {
                            ComputerUtilCard.sortByEvaluateCreature(blockers);
                            Combat combat = new Combat(ai);
                            combat.addAttacker(attacker, ComputerUtil.getOpponentFor(ai));
                            for (Card blocker : blockers) {
                                combat.addBlocker(attacker, blocker);
                            }
                            for (Card blocker : blockers) {
                                combat.removeFromCombat(blocker);
                                if (!ComputerUtilCombat.attackerWouldBeDestroyed(ai, attacker, combat) && sa.canTarget(blocker)) {
                                    sa.getTargets().add(blocker);
                                    return true;
                                } else {
                                    combat.addBlocker(attacker, blocker);
                                }
                            }
                        }
                        // Save my blocker by bouncing the attacker (cannot handle blocking multiple attackers)
                        if (attacker.getController().isOpponentOf(ai) && !blockers.isEmpty()) {
                            for (Card blocker : blockers) {
                                if (ComputerUtilCombat.blockerWouldBeDestroyed(ai, blocker, currCombat) && sa.canTarget(attacker)) {
                                    sa.getTargets().add(attacker);
                                    return true;
                                }
                            }
                        }
                    }
                }
            }

            // if it's blink or bounce, try to save my about to die stuff
            final boolean blink = (destination.equals(ZoneType.Exile) && (subApi == ApiType.DelayedTrigger
                    || (subApi == ApiType.ChangeZone && subAffected.equals("Remembered"))));
            if ((destination.equals(ZoneType.Hand) || blink) && (tgt.getMinTargets(sa.getHostCard(), sa) <= 1)) {
                // save my about to die stuff
                Card tobounce = canBouncePermanent(ai, sa, list);
                if (tobounce != null) {
                    if ("BounceOnce".equals(sa.getParam("AILogic")) && isBouncedThisTurn(ai, tobounce)) {
                        return false;
                    }

                    sa.getTargets().add(tobounce);

                    boolean saheeliFelidarCombo = sa.getHostCard().getName().equals("Felidar Guardian") 
                            && tobounce.getName().equals("Saheeli Rai")
                            && CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.nameEquals("Felidar Guardian")).size() < 
                            CardLists.filter(ai.getOpponents().getCardsIn(ZoneType.Battlefield), CardPredicates.isType("Creature")).size() + ai.getOpponentsGreatestLifeTotal() + 10;

                    // remember that the card was bounced already unless it's a special combo case
                    if (!saheeliFelidarCombo) {
                        rememberBouncedThisTurn(ai, tobounce);
                    }

                    return true;
                }
                // bounce opponent's stuff
                list = CardLists.filterControlledBy(list, ai.getOpponents());
                if (!CardLists.getNotType(list, "Land").isEmpty()) {
	                // When bouncing opponents stuff other than lands, don't bounce cards with CMC 0
	                list = CardLists.filter(list, new Predicate<Card>() {
	                    @Override
	                    public boolean apply(final Card c) {
	                        for (Card aura : c.getEnchantedBy(false)) {
	                            if (aura.getController().isOpponentOf(ai)) {
	                                return true;
	                            } else {
	                                return false;
	                            }
	                        }
	                        if (blink) {
	                            return c.isToken();
	                        } else {
	                            return c.isToken() || c.getCMC() > 0;
	                        }
	                    }
	                });
                }
                // TODO: Blink permanents with ETB triggers
                /*else if (!sa.isTrigger() && SpellAbilityAi.playReusable(ai, sa)) {
                    aiPermanents = CardLists.filter(aiPermanents, new Predicate<Card>() {
                        @Override
                        public boolean apply(final Card c) {
                            if (c.hasCounters()) {
                                return false; // don't blink something with
                            }
                            // counters TODO check good and
                            // bad counters
                            // checks only if there is a dangerous ETB effect
                            return !c.equals(sa.getHostCard()) && SpellPermanent.checkETBEffects(c, ai);
                        }
                    });
                    if (!aiPermanents.isEmpty()) {
                        // Choose "best" of the remaining to save
                        sa.getTargets().add(ComputerUtilCard.getBestAI(aiPermanents));
                        return true;
                    }
                }*/
            }

        } else if (origin.contains(ZoneType.Graveyard)) {
        	if (destination.equals(ZoneType.Exile) || destination.equals(ZoneType.Library)) {
                // Don't use these abilities before main 2 if possible
                if (!immediately && game.getPhaseHandler().getPhase().isBefore(PhaseType.MAIN2)
                        && !sa.hasParam("ActivationPhases") && !ComputerUtil.castSpellInMain1(ai, sa)) {
                    return false;
                }
                if (!immediately && (!game.getPhaseHandler().getNextTurn().equals(ai)
                            || game.getPhaseHandler().getPhase().isBefore(PhaseType.END_OF_TURN))
                        && !sa.hasParam("PlayerTurn") && !SpellAbilityAi.isSorcerySpeed(sa)
                        && !ComputerUtil.activateForCost(sa, ai)) {
                    return false;
                }
        	} else if (destination.equals(ZoneType.Hand)) {
                // only retrieve cards from computer graveyard
                list = CardLists.filterControlledBy(list, ai);
            } else if (sa.hasParam("AttachedTo")) {
                list = CardLists.filter(list, new Predicate<Card>() {
                    @Override
                    public boolean apply(final Card c) {
                        for (SpellAbility attach : c.getSpellAbilities()) {
                            if ("Pump".equals(attach.getParam("AILogic"))) {
                                return true; //only use good auras
                            }
                        }
                        return false;
                    }
                });
            }
        }

        // blink human targets only during combat
        if (origin.contains(ZoneType.Battlefield)
                && destination.equals(ZoneType.Exile)
                && (subApi == ApiType.DelayedTrigger || (subApi == ApiType.ChangeZone  && subAffected.equals("Remembered")))
                && !(game.getPhaseHandler().is(PhaseType.COMBAT_DECLARE_ATTACKERS) || sa.isAbility())) {
            return false;
        }

        // Exile and bounce opponents stuff
        if (destination.equals(ZoneType.Exile) || origin.contains(ZoneType.Battlefield)) {

            // don't rush bouncing stuff when not going to attack
            if (!immediately && sa.getPayCosts() != null
                    && game.getPhaseHandler().getPhase().isBefore(PhaseType.MAIN2)
                    && game.getPhaseHandler().isPlayerTurn(ai)
                    && ai.getCreaturesInPlay().isEmpty()) {
                return false;
            }

            list = CardLists.filterControlledBy(list, ai.getOpponents());
            list = CardLists.filter(list, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    for (Card aura : c.getEnchantedBy(false)) {
                        if (c.getOwner().isOpponentOf(ai) && aura.getController().equals(ai)) {
                            return false;
                        }
                    }
                    return true;
                }
            });
        }

        // Only care about combatants during combat
        if (game.getPhaseHandler().inCombat() && origin.contains(ZoneType.Battlefield)) {
        	CardCollection newList = CardLists.getValidCards(list, "Card.attacking,Card.blocking", null, null);
        	if (!newList.isEmpty() || !sa.isTrigger()) {
        		list = newList;
        	}
        }

        if (list.isEmpty()) {
            return false;
        }

        // Check if the opponent can save a creature from bounce/blink/whatever by paying
        // the Unless cost (for example, Erratic Portal)
        list.removeAll(getSafeTargetsIfUnlessCostPaid(ai, sa, list));

        if (!mandatory && list.size() < tgt.getMinTargets(sa.getHostCard(), sa)) {
            return false;
        }

        // target loop
        while (sa.getTargets().getNumTargeted() < tgt.getMaxTargets(sa.getHostCard(), sa)) {
            // AI Targeting
            Card choice = null;

            if (!list.isEmpty()) {
                if (destination.equals(ZoneType.Battlefield) || origin.contains(ZoneType.Battlefield)) {
                    final Card mostExpensive = ComputerUtilCard.getMostExpensivePermanentAI(list, sa, false);
                    if (mostExpensive.isCreature()) {
                        // if a creature is most expensive take the best one
                        if (destination.equals(ZoneType.Exile)) {
                            // If Exiling things, don't give bonus to Tokens
                            choice = ComputerUtilCard.getBestCreatureAI(list);
                        } else if (origin.contains(ZoneType.Graveyard)) {
                            choice = mostExpensive;
                            // Karmic Guide can chain another creature
                            for (Card c : list) {
                                if ("Karmic Guide".equals(c.getName())) {
                                    choice = c;
                                    break;
                                }
                            }
                        } else {
                            choice = ComputerUtilCard.getBestCreatureToBounceAI(list);
                        }
                    } else {
                        choice = mostExpensive;
                    }

                    //option to hold removal instead only applies for single targeted removal
                    if (!immediately && tgt.getMaxTargets(source, sa) == 1) {
                        if (!ComputerUtilCard.useRemovalNow(sa, choice, 0, destination)) {
                            return false;
                        }
                    }
                } else if (destination.equals(ZoneType.Hand) || destination.equals(ZoneType.Library)) {
                    List<Card> nonLands = CardLists.getNotType(list, "Land");
                    // Prefer to pull a creature, generally more useful for AI.
                    choice = chooseCreature(ai, CardLists.filter(nonLands, CardPredicates.Presets.CREATURES));
                    if (choice == null) { // Could not find a creature.
                        if (ai.getLife() <= 5) { // Desperate?
                            // Get something AI can cast soon.
                            System.out.println("5 Life or less, trying to find something castable.");
                            CardLists.sortByCmcDesc(nonLands);
                            for (Card potentialCard : nonLands) {
                               if (ComputerUtilMana.hasEnoughManaSourcesToCast(potentialCard.getFirstSpellAbility(), ai)) {
                                   choice = potentialCard;
                                   break;
                               }
                            }
                        } else {
                            // Get the best card in there.
                            System.out.println("No creature and lots of life, finding something good.");
                            choice = ComputerUtilCard.getBestAI(nonLands);
                        }
                    }
                    if (choice == null) {
                        // No creatures or spells?
                        CardLists.shuffle(list);
                        choice = list.get(0);
                    }
                } else {
                    choice = ComputerUtilCard.getBestAI(list);
                }
            }
            if (choice == null) { // can't find anything left
                if (sa.getTargets().getNumTargeted() == 0 || sa.getTargets().getNumTargeted() < tgt.getMinTargets(sa.getHostCard(), sa)) {
                    if (!mandatory) {
                        sa.resetTargets();
                    }
                    return false;
                } else {
                    if (!sa.isTrigger() && !ComputerUtil.shouldCastLessThanMax(ai, source)) {
                        return false;
                    }
                    break;
                }
            }

            // if max CMC exceeded, do not choose this card (but keep looking for other options)
            if (sa.hasParam("MaxTotalTargetCMC")) {
                if (choice.getCMC() > sa.getTargetRestrictions().getMaxTotalCMC(choice, sa) - sa.getTargets().getTotalTargetedCMC()) {
                    list.remove(choice);
                    continue;
                }
            }

            list.remove(choice);
            sa.getTargets().add(choice);
        }

        // Honor the Single Zone restriction. For now, simply remove targets that do not belong to the same zone as the first targeted card.
        // TODO: ideally the AI should consider at this point which targets exactly to pick (e.g. one card in the first player's graveyard
        // vs. two cards in the second player's graveyard, which cards are more relevant to be targeted, etc.). Consider improving.
        if (sa.getTargetRestrictions().isSingleZone()) {
            Card firstTgt = sa.getTargets().getFirstTargetedCard();
            CardCollection toRemove = new CardCollection();
            if (firstTgt != null) {
                for (Card t : sa.getTargets().getTargetCards()) {
                   if (!t.getController().equals(firstTgt.getController())) {
                       toRemove.add(t);
                   }
                }
                for (Card dontTarget : toRemove) {
                   sa.getTargets().remove(dontTarget);
                }
            }
        }

        return true;
    }
    
    /**
     * Checks if a permanent threatened by a stack ability or in combat can
     * be saved by bouncing.
     * @param ai controlling player
     * @param sa ChangeZone ability
     * @param list possible targets
     * @return target to bounce, null if no good targets
     */
    private static Card canBouncePermanent(final Player ai, SpellAbility sa, CardCollectionView list) {
        Game game = ai.getGame();
        // filter out untargetables
        CardCollectionView aiPermanents = CardLists
                .filterControlledBy(list, ai);

        // Felidar Guardian + Saheeli Rai combo support
        if (sa.getHostCard().getName().equals("Felidar Guardian")) {
            CardCollection saheeli = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.nameEquals("Saheeli Rai"));
            if (!saheeli.isEmpty()) {
                return saheeli.get(0);
            }
        }

        // Don't blink cards that will die.
        aiPermanents = ComputerUtil.getSafeTargets(ai, sa, aiPermanents);
        if (!game.getStack().isEmpty()) {
            final List<GameObject> objects = ComputerUtil
                    .predictThreatenedObjects(ai, sa);

            final List<Card> threatenedTargets = new ArrayList<Card>();

            for (final Card c : aiPermanents) {
                if (objects.contains(c)) {
                    threatenedTargets.add(c);
                }
            }

            if (!threatenedTargets.isEmpty()) {
                // Choose "best" of the remaining to save
                return ComputerUtilCard.getBestAI(threatenedTargets);
            }
        }
        // Save combatants
        else if (game.getPhaseHandler().is(PhaseType.COMBAT_DECLARE_BLOCKERS)) {
            Combat combat = game.getCombat();
            final CardCollection combatants = CardLists.filter(aiPermanents,
                    CardPredicates.Presets.CREATURES);
            ComputerUtilCard.sortByEvaluateCreature(combatants);

            for (final Card c : combatants) {
                if (c.getShieldCount() == 0
                        && ComputerUtilCombat.combatantWouldBeDestroyed(ai, c,
                                combat) && c.getOwner() == ai && !c.isToken()) {
                    return c;
                }
            }
        }
        return null;
    }

    private static boolean isUnpreferredTarget(final Player ai, final SpellAbility sa, final boolean mandatory) {
        if (!mandatory) {
            if (!"Always".equals(sa.getParam("AILogic"))) {
                return false;
            }
        }

        final Card source = sa.getHostCard();
        final ZoneType origin = ZoneType.listValueOf(sa.getParam("Origin")).get(0);
        final ZoneType destination = ZoneType.smartValueOf(sa.getParam("Destination"));
        final TargetRestrictions tgt = sa.getTargetRestrictions();

        CardCollection list = CardLists.getValidCards(ai.getGame().getCardsIn(origin), tgt.getValidTgts(), ai, source, sa);

        // Narrow down the list:
        if (origin.equals(ZoneType.Battlefield)) {
            // filter out untargetables
            list = CardLists.getTargetableCards(list, sa);

            // if Destination is hand, either bounce opponents dangerous stuff
            // or save my about to die stuff

            // if Destination is exile, filter out my cards
        }
        else if (origin.equals(ZoneType.Graveyard)) {
            // Retrieve from Graveyard to:
        }

        for (final Card c : sa.getTargets().getTargetCards()) {
            list.remove(c);
        }

        if (list.isEmpty()) {
            return false;
        }

        // target loop
        while (sa.getTargets().getNumTargeted() < tgt.getMinTargets(sa.getHostCard(), sa)) {
            // AI Targeting
            Card choice = null;

            if (!list.isEmpty()) {
                if (ComputerUtilCard.getMostExpensivePermanentAI(list, sa, false).isCreature()
                        && (destination.equals(ZoneType.Battlefield) || origin.equals(ZoneType.Battlefield))) {
                    // if a creature is most expensive take the best
                    choice = ComputerUtilCard.getBestCreatureToBounceAI(list);
                } else if (destination.equals(ZoneType.Battlefield) || origin.equals(ZoneType.Battlefield)) {
                    choice = ComputerUtilCard.getMostExpensivePermanentAI(list, sa, false);
                } else if (destination.equals(ZoneType.Hand) || destination.equals(ZoneType.Library)) {
                    List<Card> nonLands = CardLists.getNotType(list, "Land");
                    // Prefer to pull a creature, generally more useful for AI.
                    choice = chooseCreature(ai, CardLists.filter(nonLands, CardPredicates.Presets.CREATURES));
                    if (choice == null) { // Could not find a creature.
                        if (ai.getLife() <= 5) { // Desperate?
                            // Get something AI can cast soon.
                            System.out.println("5 Life or less, trying to find something castable.");
                            CardLists.sortByCmcDesc(nonLands);
                            for (Card potentialCard : nonLands) {
                               if (ComputerUtilMana.hasEnoughManaSourcesToCast(potentialCard.getFirstSpellAbility(), ai)) {
                                   choice = potentialCard;
                                   break;
                               }
                            }
                        } else {
                            // Get the best card in there.
                            System.out.println("No creature and lots of life, finding something good.");
                            choice = ComputerUtilCard.getBestAI(nonLands);
                        }
                    }
                    if (choice == null) {
                        // No creatures or spells?
                        CardLists.shuffle(list);
                        choice = list.get(0);
                    }
                } else {
                    choice = ComputerUtilCard.getBestAI(list);
                }
            }
            if (choice == null) { // can't find anything left
                if (sa.getTargets().getNumTargeted() == 0 || sa.getTargets().getNumTargeted() < tgt.getMinTargets(sa.getHostCard(), sa)) {
                    sa.resetTargets();
                    return false;
                } else {
                    if (!ComputerUtil.shouldCastLessThanMax(ai, source)) {
                        return false;
                    }
                    break;
                }
            }

            list.remove(choice);
            sa.getTargets().add(choice);
        }

        return true;
    }

    /**
     * <p>
     * changeKnownOriginTriggerAI.
     * </p>
     *
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param mandatory
     *            a boolean.
     * @return a boolean.
     */
    private static boolean knownOriginTriggerAI(final Player ai, final SpellAbility sa, final boolean mandatory) {
        if (sa.getTargetRestrictions() == null) {
            // Just in case of Defined cases
            if (!mandatory && sa.hasParam("AttachedTo")) {
                final List<Card> list = AbilityUtils.getDefinedCards(sa.getHostCard(), sa.getParam("AttachedTo"), sa);
                if (!list.isEmpty()) {
                    final Card attachedTo = list.get(0);
                    // This code is for the Dragon auras
                    if (attachedTo.getController().isOpponentOf(ai)) {
                        return false;
                    }
                }
            }
        } else if (isPreferredTarget(ai, sa, mandatory, true)) {
            // do nothing
        } else if (!isUnpreferredTarget(ai, sa, mandatory)) {
            return false;
        }

        return true;
    }

    public static Card chooseCardToHiddenOriginChangeZone(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, Player player, final Player decider) {
        if (fetchList.isEmpty()) {
            return null;
        }
        if (sa.hasParam("AILogic")) {
            String logic = sa.getParam("AILogic");
            if ("NeverBounceItself".equals(logic)) {
                Card source = sa.getHostCard();
                if (fetchList.contains(source)) {
                    // For cards that should never be bounced back to hand with their own [e.g. triggered] abilities, such as guild lands.
                    fetchList.remove(source);
                }
            } else if ("WorstCard".equals(logic)) {
                return ComputerUtilCard.getWorstAI(fetchList);
            } else if ("Mairsil".equals(logic)) {
                return SpecialCardAi.MairsilThePretender.considerCardFromList(fetchList);
            }
        }
        if (fetchList.isEmpty()) {
            return null;
        }
        String type = sa.getParam("ChangeType");
        if (type == null) {
            type = "Card";
        }
        
        Card c = null;
        final Player activator = sa.getActivatingPlayer();
        
        CardLists.shuffle(fetchList);
        // Save a card as a default, in case we can't find anything suitable.
        Card first = fetchList.get(0);
        if (ZoneType.Battlefield.equals(destination)) {
            fetchList = CardLists.filter(fetchList, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    if (c.getType().isLegendary()) {
                        if (decider.isCardInPlay(c.getName())) {
                            return false;
                        }
                    }
                    return true;
                }
            });
            if (player.isOpponentOf(decider) && sa.hasParam("GainControl") && activator.equals(decider)) {
                fetchList = CardLists.filter(fetchList, new Predicate<Card>() {
                    @Override
                    public boolean apply(final Card c) {
                        if (c.hasSVar("RemAIDeck") || c.hasSVar("RemRandomDeck")) {
                            return false;
                        }
                        return true;
                    }
                });
            }
        }
        if (ZoneType.Exile.equals(destination) || origin.contains(ZoneType.Battlefield)
                || (ZoneType.Library.equals(destination) && origin.contains(ZoneType.Hand))) {
            // Exiling or bouncing stuff
            if (player.isOpponentOf(decider)) {
                c = ComputerUtilCard.getBestAI(fetchList);
            } else {
                c = ComputerUtilCard.getWorstAI(fetchList);
                if (ComputerUtilAbility.getAbilitySourceName(sa).equals("Temur Sabertooth")) {
                    Card tobounce = canBouncePermanent(player, sa, fetchList);
                    if (tobounce != null) {
                        c = tobounce;
                        rememberBouncedThisTurn(player, c);
                    }
                }
            }
        } else if (origin.contains(ZoneType.Library) && (type.contains("Basic") || areAllBasics(type))) {
            c = basicManaFixing(decider, fetchList);
        } else if (ZoneType.Hand.equals(destination) && CardLists.getNotType(fetchList, "Creature").isEmpty()) {
            c = chooseCreature(decider, fetchList);
        } else if (ZoneType.Battlefield.equals(destination) || ZoneType.Graveyard.equals(destination)) {
            if (!activator.equals(decider) && sa.hasParam("GainControl")) {
                c = ComputerUtilCard.getWorstAI(fetchList);
            } else {
                c = ComputerUtilCard.getBestAI(fetchList);
            }
        } else {
            // Don't fetch another tutor with the same name
            CardCollection sameNamed = CardLists.filter(fetchList, Predicates.not(CardPredicates.nameEquals(ComputerUtilAbility.getAbilitySourceName(sa))));
            if (origin.contains(ZoneType.Library) && !sameNamed.isEmpty()) {
                fetchList = sameNamed;
            }

            // Does AI need a land?
            CardCollectionView hand = decider.getCardsIn(ZoneType.Hand);
            if (CardLists.filter(hand, Presets.LANDS).isEmpty() && CardLists.filter(decider.getCardsIn(ZoneType.Battlefield), Presets.LANDS).size() < 4) {
                boolean canCastSomething = false;
                for (Card cardInHand : hand) {
                    canCastSomething |= ComputerUtilMana.hasEnoughManaSourcesToCast(cardInHand.getFirstSpellAbility(), decider);
                }
                if (!canCastSomething) {
                    System.out.println("Pulling a land as there are none in hand, less than 4 on the board, and nothing in hand is castable.");
                    c = basicManaFixing(decider, fetchList);
                }
            }
            if (c == null) {
                System.out.println("Don't need a land or none available; trying for a creature.");
                fetchList = CardLists.getNotType(fetchList, "Land");
                // Prefer to pull a creature, generally more useful for AI.
                c = chooseCreature(decider, CardLists.filter(fetchList, CardPredicates.Presets.CREATURES));
            }
            if (c == null) { // Could not find a creature.
                if (decider.getLife() <= 5) { // Desperate?
                    // Get something AI can cast soon.
                    System.out.println("5 Life or less, trying to find something castable.");
                    CardLists.sortByCmcDesc(fetchList);
                    for (Card potentialCard : fetchList) {
                       if (ComputerUtilMana.hasEnoughManaSourcesToCast(potentialCard.getFirstSpellAbility(), decider)) {
                           c = potentialCard;
                           break;
                       }
                    }
                } else {
                    // Get the best card in there.
                    System.out.println("No creature and lots of life, finding something good.");
                    c = ComputerUtilCard.getBestAI(fetchList);
                }
            }
        }
        if (c == null) {
            c = first;
        }
        return c;
    }

    /* (non-Javadoc)
     * @see forge.card.ability.SpellAbilityAi#confirmAction(forge.game.player.Player, forge.card.spellability.SpellAbility, forge.game.player.PlayerActionConfirmMode, java.lang.String)
     */
    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message) {
        // AI was never asked
        return true;
    }
    

    @Override
    public Card chooseSingleCard(Player ai, SpellAbility sa, Iterable<Card> options, boolean isOptional, Player targetedPlayer) {
        // Called when looking for creature to attach aura or equipment
        return ComputerUtilCard.getBestAI(options);
    }
    
    /* (non-Javadoc)
     * @see forge.card.ability.SpellAbilityAi#chooseSinglePlayer(forge.game.player.Player, forge.card.spellability.SpellAbility, java.util.List)
     */
    @Override
    public Player chooseSinglePlayer(Player ai, SpellAbility sa, Iterable<Player> options) {
        // Currently only used by Curse of Misfortunes, so this branch should never get hit
        // But just in case it does, just select the first option
        return Iterables.getFirst(options, null);
    }
    
    private boolean doSacAndUpgradeLogic(final Player ai, SpellAbility sa) {
        Card source = sa.getHostCard();
        PhaseHandler ph = ai.getGame().getPhaseHandler();
        String logic = sa.getParam("AILogic");
        boolean sacWorst = logic.contains("SacWorst");

        if (!ph.is(PhaseType.MAIN2)) {
            // Should be given a chance to cast other spells as well as to use a previously upgraded creature
            return false;
        }

        String definedSac = StringUtils.split(source.getSVar("AIPreference"), "$")[1];
        String definedGoal = sa.getParam("ChangeType");
        boolean anyCMC = !definedGoal.contains(".cmc");

        CardCollection listToSac = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.restriction(definedSac.split(","), ai, source, sa));
        listToSac.sort(!sacWorst ? CardLists.CmcComparatorInv : Collections.reverseOrder(CardLists.CmcComparatorInv));

        for (Card sacCandidate : listToSac) {
            int sacCMC = sacCandidate.getCMC();

            int goalCMC = source.hasSVar("X") ? AbilityUtils.calculateAmount(source, source.getSVar("X").replace("Sacrificed$CardManaCost", "Number$" + sacCMC), sa) : sacCMC + 1;
            String curGoal = definedGoal;

            if (!anyCMC) {
                // TODO: improve the detection of X in the "cmc**X" part to avoid clashing with other letters in the definition
                curGoal = definedGoal.replace("X", String.format("%d", goalCMC));
            }

            CardCollection listGoal = CardLists.filter(ai.getCardsIn(ZoneType.Library), CardPredicates.restriction(curGoal.split(","), ai, source, sa));

            if (!anyCMC) {
                listGoal = CardLists.getValidCards(listGoal, curGoal, source.getController(), source);
            } else {
                listGoal = CardLists.getValidCards(listGoal, curGoal + (curGoal.contains(".") ? "+" : ".") + "cmcGE" + goalCMC, source.getController(), source);
            }

            listGoal = CardLists.filter(listGoal, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    if (c.getType().isLegendary()) {
                        if (ai.isCardInPlay(c.getName())) {
                            return false;
                        }
                    }
                    return true;
                }
            });

            if (!listGoal.isEmpty()) {
                // make sure we're upgrading sacCMC->goalCMC
                source.setSVar("AIPreferenceOverride", "Creature.cmcEQ" + sacCMC);
                return true;
            }
        }

        // no candidates to upgrade
        return false;
    }

    private boolean doSameNameLogic(Player aiPlayer, SpellAbility sa) {
        final Game game = aiPlayer.getGame();
        final Card source = sa.getHostCard();
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        final ZoneType origin = ZoneType.listValueOf(sa.getParam("Origin")).get(0);
        CardCollection list = CardLists.getValidCards(game.getCardsIn(origin), tgt.getValidTgts(), aiPlayer,
                source, sa);
        list = CardLists.filterControlledBy(list, aiPlayer.getOpponents());
        if (list.isEmpty()) {
            return false;   // no valid targets
        }

        Map<Player, Map.Entry<String, Integer>> data = Maps.newHashMap();

        // need to filter for the opponents first
        for (final Player opp : aiPlayer.getOpponents()) {
            CardCollection oppList = CardLists.filterControlledBy(list, opp);

            // no cards
            if (oppList.isEmpty()) {
                continue;
            }

            // Compute value for each possible target
            Map<String, Integer> values = ComputerUtilCard.evaluateCreatureListByName(oppList);

            // reject if none of them can be targeted
            oppList = CardLists.filter(oppList, CardPredicates.isTargetableBy(sa));
            // none can be targeted
            if (oppList.isEmpty()) {
                continue;
            }

            List<String> toRemove = Lists.newArrayList();
            for (final String name : values.keySet()) {
                if (CardLists.filter(oppList, CardPredicates.nameEquals(name)).isEmpty()) {
                    toRemove.add(name);
                }
            }
            values.keySet().removeAll(toRemove);

            // JAVA 1.8 use Map.Entry.comparingByValue()
            data.put(opp, Collections.max(values.entrySet(), new Comparator<Map.Entry<String, Integer>>() {
                @Override
                public int compare(Map.Entry<String, Integer> o1, Map.Entry<String, Integer> o2) {
                    return o1.getValue() - o2.getValue();
                }
            }));
        }

        // JAVA 1.8 use Map.Entry.comparingByValue() somehow
        Map.Entry<Player, Map.Entry<String, Integer>> max = Collections.max(data.entrySet(), new Comparator<Map.Entry<Player, Map.Entry<String, Integer>>>() {
            @Override
            public int compare(Map.Entry<Player, Map.Entry<String, Integer>> o1, Map.Entry<Player, Map.Entry<String, Integer>> o2) {
                return o1.getValue().getValue() - o2.getValue().getValue();
            }
        });

        // filter list again by the opponent and a creature of the wanted name that can be targeted
        list = CardLists.filter(CardLists.filterControlledBy(list, max.getKey()),
                CardPredicates.nameEquals(max.getValue().getKey()), CardPredicates.isTargetableBy(sa));

        // list should contain only one element or zero
        sa.resetTargets();
        for (Card c : list) {
            sa.getTargets().add(c);
            return true;
        }

        return false;
    }

    public boolean doReturnCommanderLogic(SpellAbility sa, Player aiPlayer) {
        @SuppressWarnings("unchecked")
        Map<String, Object> originalParams = (Map<String, Object>)sa.getReplacingObject("OriginalParams");
        SpellAbility causeSa = (SpellAbility)originalParams.get("Cause");
        SpellAbility causeSub = null;
        
        if (causeSa != null && (causeSub = causeSa.getSubAbility()) != null) {
            ApiType subApi = causeSub.getApi();
            
            if (subApi == ApiType.ChangeZone && "Exile".equals(causeSub.getParam("Origin"))
                    && "Battlefield".equals(causeSub.getParam("Destination"))) {
                // A blink effect implemented using ChangeZone API
                return false;
            } else if (subApi == ApiType.DelayedTrigger) {
                SpellAbility exec = causeSub.getAdditonalAbility("Execute");
                if (exec != null && exec.getApi() == ApiType.ChangeZone) {
                    if ("Exile".equals(exec.getParam("Origin")) && "Battlefield".equals(exec.getParam("Destination"))) {
                        // A blink effect implemented using a delayed trigger
                        return false;
                    }
                }
            } else if (causeSa.getHostCard() != null && causeSa.getHostCard().equals((Card)sa.getReplacingObject("Card"))
                    && causeSa.getActivatingPlayer().equals(aiPlayer)) {
                // This is an intrinsic effect that blinks the card (e.g. Obzedat, Ghost Council), no need to
                // return the commander to the Command zone.
                return false;
            }
        }
        
        // Normally we want the commander back in Command zone to recast him later
        return true;
    }

    private static CardCollection getSafeTargetsIfUnlessCostPaid(Player ai, SpellAbility sa, Iterable<Card> potentialTgts) {
        // Determines if the controller of each potential target can negate the ChangeZone effect
        // by paying the Unless cost. Returns the list of targets that can be saved that way.
        final Card source = sa.getHostCard();
        final CardCollection canBeSaved = new CardCollection();

        for (Card potentialTgt : potentialTgts) {
            String unlessCost = sa.hasParam("UnlessCost") ? sa.getParam("UnlessCost").trim() : null;

            if (unlessCost != null && !unlessCost.endsWith(">")) {
                Player opp = potentialTgt.getController();
                int usableManaSources = ComputerUtilMana.getAvailableManaEstimate(opp);

                int toPay = 0;
                boolean setPayX = false;
                if (unlessCost.equals("X") && source.getSVar(unlessCost).equals("Count$xPaid")) {
                    setPayX = true;
                    toPay = ComputerUtilMana.determineLeftoverMana(sa, ai);
                } else {
                    toPay = AbilityUtils.calculateAmount(source, unlessCost, sa);
                }

                if (toPay == 0) {
                    canBeSaved.add(potentialTgt);
                }

                if (toPay <= usableManaSources) {
                    canBeSaved.add(potentialTgt);
                }

                if (setPayX) {
                    source.setSVar("PayX", Integer.toString(toPay));
                }
            }
        }

        return canBeSaved;
    }

    private static void rememberBouncedThisTurn(Player ai, Card c) {
        AiCardMemory.rememberCard(ai, c, AiCardMemory.MemorySet.BOUNCED_THIS_TURN);
    }

    private static boolean isBouncedThisTurn(Player ai, Card c) {
        return AiCardMemory.isRememberedCard(ai, c, AiCardMemory.MemorySet.BOUNCED_THIS_TURN);
    }
}
