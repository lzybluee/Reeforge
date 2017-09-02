package forge.ai.ability;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Lists;
import forge.ai.*;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.card.CardPredicates.Presets;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostRemoveCounter;
import forge.game.cost.CostTapType;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityRestriction;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

public class PumpAi extends PumpAiBase {

    private static boolean hasTapCost(final Cost cost, final Card source) {
        if (cost == null) {
            return true;
        }
        for (final CostPart part : cost.getCostParts()) {
            if (part instanceof CostTapType) {
                return true;
            }
        }
        return false;
    }
    
    @Override
    protected boolean checkAiLogic(final Player ai, final SpellAbility sa, final String aiLogic) {
        if ("FellTheMighty".equals(aiLogic)) {
            CardCollection aiList = ai.getCreaturesInPlay();
            if (aiList.isEmpty()) {
                return false;
            }
            CardLists.sortByPowerAsc(aiList);
            if (!sa.canTarget(aiList.get(0))) {
                return false;
            }
        } else if ("MoveCounter".equals(aiLogic)) {
            final Game game = ai.getGame();
            List<Card> tgtCards = CardLists.filter(game.getCardsIn(ZoneType.Battlefield),
                    CardPredicates.isTargetableBy(sa));
            if (tgtCards.isEmpty()) {
                return false;
            }
            SpellAbility moveSA = null;
            SpellAbility sub = sa.getSubAbility();
            while (sub != null) {
                if (ApiType.MoveCounter.equals(sub.getApi())) {
                    moveSA = sub;
                    break;
                }
                sub = sub.getSubAbility();
            }

            if (moveSA == null) {
                System.err.println("MoveCounter AiLogic without MoveCounter SubAbility!");
                return false;
            }


        }
        return super.checkAiLogic(ai, sa, aiLogic);
    }

    @Override
    protected boolean checkPhaseRestrictions(final Player ai, final SpellAbility sa, final PhaseHandler ph,
            final String logic) {
        // special Phase check for MoveCounter
        if (logic.equals("MoveCounter")) {
            if (ph.inCombat() && ph.getPlayerTurn().isOpponentOf(ai)) {
                return true;
            }

            if (!ph.getNextTurn().equals(ai) || ph.getPhase().isBefore(PhaseType.END_OF_TURN)) {
                return false;
            }
            return true;
        }
        return super.checkPhaseRestrictions(ai, sa, ph);
    }
    
    @Override
    protected boolean checkPhaseRestrictions(final Player ai, final SpellAbility sa, final PhaseHandler ph) {
        final Game game = ai.getGame();
        if (game.getStack().isEmpty() && hasTapCost(sa.getPayCosts(), sa.getHostCard())) {
            if (ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS) && ph.isPlayerTurn(ai)) {
                return false;
            }
            if (ph.getPhase().isBefore(PhaseType.COMBAT_BEGIN) && ph.getPlayerTurn().isOpponentOf(ai)) {
                return false;
            }
        }
        if (game.getStack().isEmpty() && ph.getPhase().isBefore(PhaseType.COMBAT_BEGIN)) {
            // Instant-speed pumps should not be cast outside of combat when the
            // stack is empty
            if (!sa.isCurse() && !SpellAbilityAi.isSorcerySpeed(sa)) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    protected boolean checkApiLogic(Player ai, SpellAbility sa) {
        final Game game = ai.getGame();
        final Card source = sa.getHostCard();
        final String sourceName = ComputerUtilAbility.getAbilitySourceName(sa);
        final List<String> keywords = sa.hasParam("KW") ? Arrays.asList(sa.getParam("KW").split(" & "))
                : Lists.<String>newArrayList();
        final String numDefense = sa.hasParam("NumDef") ? sa.getParam("NumDef") : "";
        final String numAttack = sa.hasParam("NumAtt") ? sa.getParam("NumAtt") : "";

        final String aiLogic = sa.getParam("AILogic");

        final boolean isFight = "Fight".equals(aiLogic) || "PowerDmg".equals(aiLogic);
        final boolean isBerserk = "Berserk".equals(aiLogic);

        if ("Pummeler".equals(sa.getParam("AILogic"))) {
            return SpecialCardAi.ElectrostaticPummeler.consider(ai, sa);
        } else if ("MoveCounter".equals(aiLogic)) {
            final SpellAbility moveSA = sa.findSubAbilityByType(ApiType.MoveCounter);

            if (moveSA == null) {
                return false;
            }

            final String counterType = moveSA.getParam("CounterType");
            final CounterType cType = "Any".equals(counterType) ? null : CounterType.valueOf(counterType);
            
            final PhaseHandler ph = game.getPhaseHandler();
            if (ph.inCombat() && ph.getPlayerTurn().isOpponentOf(ai)) {
                CardCollection attr = ph.getCombat().getAttackers();
                attr = CardLists.getTargetableCards(attr, sa);

                if (cType != null) {
                    attr = CardLists.filter(attr, CardPredicates.hasCounter(cType));
                    if (attr.isEmpty()) {
                        return false;
                    }
                    final String amountStr = moveSA.getParam("CounterNum");
                    CardCollection best = CardLists.filter(attr, new Predicate<Card>() {
                        @Override
                        public boolean apply(Card card) {

                            int amount = 0;
                            if (StringUtils.isNumeric(amountStr)) {
                                amount = AbilityUtils.calculateAmount(source, amountStr, moveSA);
                            } else if (source.hasSVar(amountStr)) {
                                if ("Count$ChosenNumber".equals(source.getSVar(amountStr))) {
                                    amount = card.getCounters(cType);
                                }
                            }

                            int i = card.getCounters(cType);
                            if (i < amount) {
                                return false;
                            }

                            final Card srcCardCpy = CardUtil.getLKICopy(card);
                            // cant use substract on Copy
                            srcCardCpy.setCounters(cType, srcCardCpy.getCounters(cType) - amount);

                            if (CounterType.P1P1.equals(cType) && srcCardCpy.getNetToughness() <= 0) {
                                if (srcCardCpy.getCounters(cType) > 0 || !card.hasKeyword("Undying")
                                        || card.isToken()) {
                                    return true;
                                }
                                return false;
                            }
                            return false;
                        }

                    });

                    if (best.isEmpty()) {
                        best = attr;
                    }

                    final Card card = ComputerUtilCard.getBestCreatureAI(best);
                    sa.getTargets().add(card);
                    return true;
                }
            } else {
                final String amountStr = moveSA.getParam("CounterNum");
                final boolean sameCtrl = moveSA.getTargetRestrictions().isSameController();

                List<Card> list = CardLists.getTargetableCards(game.getCardsIn(ZoneType.Battlefield), sa);
                if (cType != null) {
                    list = CardLists.filter(list, CardPredicates.hasCounter(cType));
                    if (list.isEmpty()) {
                        return false;
                    }
                    List<Card> oppList = CardLists.filterControlledBy(list, ai.getOpponents());
                    if (!oppList.isEmpty() && !sameCtrl) {
                        List<Card> best = CardLists.filter(oppList, new Predicate<Card>() {
                            @Override
                            public boolean apply(Card card) {
                                int amount = 0;
                                if (StringUtils.isNumeric(amountStr)) {
                                    amount = AbilityUtils.calculateAmount(source, amountStr, moveSA);
                                } else if (source.hasSVar(amountStr)) {
                                    if ("Count$ChosenNumber".equals(source.getSVar(amountStr))) {
                                        amount = card.getCounters(cType);
                                    }
                                }

                                int i = card.getCounters(cType);
                                if (i < amount) {
                                    return false;
                                }

                                final Card srcCardCpy = CardUtil.getLKICopy(card);
                                // cant use substract on Copy
                                srcCardCpy.setCounters(cType, srcCardCpy.getCounters(cType) - amount);

                                if (CounterType.P1P1.equals(cType) && srcCardCpy.getNetToughness() <= 0) {
                                    if (srcCardCpy.getCounters(cType) > 0 || !card.hasKeyword("Undying")
                                            || card.isToken()) {
                                        return true;
                                    }
                                    return false;
                                }
                                return true;
                            }
                        });

                        if (best.isEmpty()) {
                            best = oppList;
                        }

                        final Card card = ComputerUtilCard.getBestCreatureAI(best);
                        sa.getTargets().add(card);
                        return true;
                    }
                }

            }
        } else if ("FellTheMighty".equals(aiLogic)) {
            CardCollection aiList = ai.getCreaturesInPlay();
            CardLists.sortByPowerAsc(aiList);
            Card lowest = aiList.get(0);

            CardCollection oppList = CardLists.filter(game.getCardsIn(ZoneType.Battlefield),
                    CardPredicates.Presets.CREATURES, CardPredicates.isControlledByAnyOf(ai.getOpponents()));

            oppList = CardLists.filterPower(oppList, lowest.getNetPower() + 1);
            if (ComputerUtilCard.evaluateCreatureList(oppList) > 200) {
                sa.resetTargets();
                sa.getTargets().add(lowest);
                return true;
            }
        } else if (sa.hasParam("AILogic") && sa.getParam("AILogic").startsWith("Donate")) {
            // Donate step 1 - try to target an opponent, preferably one who does not have a donate target yet
            return SpecialCardAi.Donate.considerTargetingOpponent(ai, sa);
        }

        if (ComputerUtil.preventRunAwayActivations(sa)) {
            return false;
        }

        if (!game.getStack().isEmpty() && !sa.isCurse() && !isFight) {
            return ComputerUtilCard.canPumpAgainstRemoval(ai, sa);
        }

        if (sa.hasParam("ActivationNumberSacrifice")) {
            final SpellAbilityRestriction restrict = sa.getRestrictions();
            final int sacActivations = Integer.parseInt(sa.getParam("ActivationNumberSacrifice").substring(2));
            final int activations = restrict.getNumberTurnActivations();
            // don't risk sacrificing a creature just to pump it
            if (activations >= sacActivations - 1) {
                return false;
            }
        }

        if (source.getSVar("X").equals("Count$xPaid")) {
            source.setSVar("PayX", "");
        }

        int defense;
        if (numDefense.contains("X") && source.getSVar("X").equals("Count$xPaid")) {
            // Set PayX here to maximum value.
            int xPay = ComputerUtilMana.determineLeftoverMana(sa, ai);
            if (sourceName.equals("Necropolis Fiend")) {
            	xPay = Math.min(xPay, sa.getActivatingPlayer().getCardsIn(ZoneType.Graveyard).size());
                sa.setSVar("X", Integer.toString(xPay));
            }
            source.setSVar("PayX", Integer.toString(xPay));
            defense = xPay;
            if (numDefense.equals("-X")) {
                defense = -xPay;
            }
        } else {
            defense = AbilityUtils.calculateAmount(sa.getHostCard(), numDefense, sa);
        }

        int attack;
        if (numAttack.contains("X") && source.getSVar("X").equals("Count$xPaid")) {
            // Set PayX here to maximum value.
            final String toPay = source.getSVar("PayX");

            if (toPay.equals("")) {
                final int xPay = ComputerUtilMana.determineLeftoverMana(sa, ai);
                source.setSVar("PayX", Integer.toString(xPay));
                attack = xPay;
            } else {
                attack = Integer.parseInt(toPay);
            }
        } else {
            attack = AbilityUtils.calculateAmount(sa.getHostCard(), numAttack, sa);
        }

        if ((numDefense.contains("X") && defense == 0) || (numAttack.contains("X") && attack == 0 && !isBerserk)) {
            return false;
        }

        //Untargeted
        if ((sa.getTargetRestrictions() == null) || !sa.getTargetRestrictions().doesTarget()) {
            final List<Card> cards = AbilityUtils.getDefinedCards(sa.getHostCard(), sa.getParam("Defined"), sa);

            if (cards.isEmpty()) {
                return false;
            }

            // when this happens we need to expand AI to consider if its ok for
            // everything?
            for (final Card card : cards) {
                if (sa.isCurse()) {
                    if (!card.getController().isOpponentOf(ai)) {
                        return false;
                    }

                    if (!containsUsefulKeyword(ai, keywords, card, sa, attack)) {
                        continue;
                    }

                    return true;
                }
                if (!card.getController().isOpponentOf(ai)) {
                    if (ComputerUtilCard.shouldPumpCard(ai, sa, card, defense, attack, keywords, false)) {
                        return true;
                    } else if (containsUsefulKeyword(ai, keywords, card, sa, attack)) {
                        return true;
                    }
                }
            }
            return false;
        }
        //Targeted
        if (!pumpTgtAI(ai, sa, defense, attack, false, false)) {
            return false;
        }

        if ("DebuffForXCounters".equals(sa.getParam("AILogic")) && sa.getTargetCard() != null) {
            // e.g. Skullmane Baku
            CounterType ctrType = CounterType.KI;
            for (CostPart part : sa.getPayCosts().getCostParts()) {
                if (part instanceof CostRemoveCounter) {
                    ctrType = ((CostRemoveCounter)part).counter;
                    break;
                }
            }

            // Do not pay more counters than necessary to kill the targeted creature
            int chosenX = Math.min(source.getCounters(ctrType), sa.getTargetCard().getNetToughness());
            sa.setSVar("ChosenX", String.valueOf(chosenX));
        }

        return true;
    } // pumpPlayAI()

    private boolean pumpTgtAI(final Player ai, final SpellAbility sa, final int defense, final int attack, final boolean mandatory, 
    		boolean immediately) {
        final List<String> keywords = sa.hasParam("KW") ? Arrays.asList(sa.getParam("KW").split(" & "))
                : Lists.<String>newArrayList();
        final Game game = ai.getGame();
        final Card source = sa.getHostCard();
        final boolean isFight = "Fight".equals(sa.getParam("AILogic")) || "PowerDmg".equals(sa.getParam("AILogic"));
        
        immediately |= ComputerUtil.playImmediately(ai, sa);

        if (!mandatory
                && !immediately
                && game.getPhaseHandler().getPhase().isAfter(PhaseType.COMBAT_DECLARE_BLOCKERS)
                && !(sa.isCurse() && defense < 0)
                && !containsNonCombatKeyword(keywords)
                && !sa.hasParam("UntilYourNextTurn")
                && !"Snapcaster".equals(sa.getParam("AILogic"))
                && !isFight) {
            return false;
        }

        final TargetRestrictions tgt = sa.getTargetRestrictions();
        sa.resetTargets();
        if (sa.hasParam("TargetingPlayer") && sa.getActivatingPlayer().equals(ai) && !sa.isTrigger()) {
            Player targetingPlayer = AbilityUtils.getDefinedPlayers(source, sa.getParam("TargetingPlayer"), sa).get(0);
            sa.setTargetingPlayer(targetingPlayer);
            return targetingPlayer.getController().chooseTargetsFor(sa);
        }

        CardCollection list;
        if (sa.hasParam("AILogic")) {
            if (sa.getParam("AILogic").equals("HighestPower")) {
                list = CardLists.getValidCards(CardLists.filter(game.getCardsIn(ZoneType.Battlefield), Presets.CREATURES), tgt.getValidTgts(), ai, source, sa);
                list = CardLists.getTargetableCards(list, sa);
                CardLists.sortByPowerDesc(list);
                if (!list.isEmpty()) {
                    sa.getTargets().add(list.get(0));
                    return true;
                } else {
                    return false;
                }
            } else if (sa.getParam("AILogic").equals("DonateTargetPerm")) {
                // Donate step 2 - target a donatable permanent.
                return SpecialCardAi.Donate.considerDonatingPermanent(ai, sa);
            }
            if (isFight) {
                return FightAi.canFightAi(ai, sa, attack, defense);
            }
        }

        if (sa.isCurse()) {
            for (final Player opp : ai.getOpponents()) {
                if (sa.canTarget(opp)) {
                    sa.getTargets().add(opp);
                    return true;
                }
            }
            list = getCurseCreatures(ai, sa, defense, attack, keywords);
        } else {
            if (!tgt.canTgtCreature()) {
                ZoneType zone = tgt.getZone().get(0);
                list = new CardCollection(game.getCardsIn(zone));
            } else {
                list = getPumpCreatures(ai, sa, defense, attack, keywords, immediately);
            }
            if (sa.canTarget(ai)) {
                sa.getTargets().add(ai);
                return true;
            }
        }

        list = CardLists.getValidCards(list, tgt.getValidTgts(), ai, source, sa);
        if (game.getStack().isEmpty()) {
            // If the cost is tapping, don't activate before declare
            // attack/block
            if (sa.getPayCosts() != null && sa.getPayCosts().hasTapCost()) {
                if (game.getPhaseHandler().getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)
                        && game.getPhaseHandler().isPlayerTurn(ai)) {
                    list.remove(sa.getHostCard());
                }
                if (game.getPhaseHandler().getPhase().isBefore(PhaseType.COMBAT_DECLARE_BLOCKERS)
                        && game.getPhaseHandler().getPlayerTurn().isOpponentOf(ai)) {
                    list.remove(sa.getHostCard());
                }
            }
        }

        // Detain target nonland permanent: don't target noncreature permanents that don't have
        // any activated abilities.
        if ("DetainNonLand".equals(sa.getParam("AILogic"))) {
            list = CardLists.filter(list, Predicates.or(CardPredicates.Presets.CREATURES, new Predicate<Card>() {
                @Override
                public boolean apply(Card card) {
                    for (SpellAbility sa: card.getSpellAbilities()) {
                        if (sa.isAbility()) {
                            return true;
                        }
                    }
                    return false;
                }
            }));
        }

        if (list.isEmpty()) {
            if (ComputerUtil.activateForCost(sa, ai)) {
                return pumpMandatoryTarget(ai, sa);
            }
            return mandatory && pumpMandatoryTarget(ai, sa);
        }

        if (!sa.isCurse()) {
            // Don't target cards that will die.
            list = ComputerUtil.getSafeTargets(ai, sa, list);
        }
        
        if ("Snapcaster".equals(sa.getParam("AILogic"))) {
            if (!ComputerUtil.targetPlayableSpellCard(ai, list, sa, false)) {
                return false;
            }
        }
        
        while (sa.getTargets().getNumTargeted() < tgt.getMaxTargets(source, sa)) {
            Card t = null;
            // boolean goodt = false;

            if (list.isEmpty()) {
                if (sa.getTargets().getNumTargeted() < tgt.getMinTargets(source, sa) || sa.getTargets().getNumTargeted() == 0) {
                    if (mandatory || ComputerUtil.activateForCost(sa, ai)) {
                        return pumpMandatoryTarget(ai, sa);
                    }

                    sa.resetTargets();
                    return false;
                } else {
                    // TODO is this good enough? for up to amounts?
                    break;
                }
            }

            t = ComputerUtilCard.getBestAI(list);
            //option to hold removal instead only applies for single targeted removal
            if (!immediately && tgt.getMaxTargets(source, sa) == 1 && sa.isCurse() && defense < 0) {
                if (!ComputerUtilCard.useRemovalNow(sa, t, -defense, ZoneType.Graveyard) 
                        && !ComputerUtil.activateForCost(sa, ai)) {
                    return false;
                }
            }
            sa.getTargets().add(t);
            list.remove(t);
        }

        return true;
    } // pumpTgtAI()

    private boolean pumpMandatoryTarget(final Player ai, final SpellAbility sa) {
        final Game game = ai.getGame();
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        CardCollection list = CardLists.getValidCards(game.getCardsIn(ZoneType.Battlefield), tgt.getValidTgts(), sa.getActivatingPlayer(), sa.getHostCard(), sa);
        list = CardLists.getTargetableCards(list, sa);

        if (list.size() < tgt.getMinTargets(sa.getHostCard(), sa)) {
            sa.resetTargets();
            return false;
        }

        // Remove anything that's already been targeted
        for (final Card c : sa.getTargets().getTargetCards()) {
            list.remove(c);
        }

        CardCollection pref;
        CardCollection forced;
        final Card source = sa.getHostCard();

        if (sa.isCurse()) {
            pref = CardLists.filterControlledBy(list, ai.getOpponents());
            forced = CardLists.filterControlledBy(list, ai);
        } else {
            pref = CardLists.filterControlledBy(list, ai);
            forced = CardLists.filterControlledBy(list, ai.getOpponents());
        }

        while (sa.getTargets().getNumTargeted() < tgt.getMaxTargets(source, sa)) {
            if (pref.isEmpty()) {
                break;
            }

            Card c;
            if (CardLists.getNotType(pref, "Creature").isEmpty()) {
                c = ComputerUtilCard.getBestCreatureAI(pref);
            } else {
                c = ComputerUtilCard.getMostExpensivePermanentAI(pref, sa, true);
            }

            pref.remove(c);

            sa.getTargets().add(c);
        }

        while (sa.getTargets().getNumTargeted() < tgt.getMinTargets(source, sa)) {
            if (forced.isEmpty()) {
                break;
            }

            Card c;
            if (CardLists.getNotType(forced, "Creature").isEmpty()) {
                c = ComputerUtilCard.getWorstCreatureAI(forced);
            } else {
                c = ComputerUtilCard.getCheapestPermanentAI(forced, sa, true);
            }

            forced.remove(c);

            sa.getTargets().add(c);
        }

        if (sa.getTargets().getNumTargeted() < tgt.getMinTargets(source, sa)) {
            sa.resetTargets();
            return false;
        }

        return true;
    } // pumpMandatoryTarget()

    @Override
    protected boolean doTriggerAINoCost(Player ai, SpellAbility sa, boolean mandatory) {
        final Card source = sa.getHostCard();
        final String numDefense = sa.hasParam("NumDef") ? sa.getParam("NumDef") : "";
        final String numAttack = sa.hasParam("NumAtt") ? sa.getParam("NumAtt") : "";

        int defense;
        if (numDefense.contains("X") && source.getSVar("X").equals("Count$xPaid")) {
            // Set PayX here to maximum value.
            final int xPay = ComputerUtilMana.determineLeftoverMana(sa, ai);
            source.setSVar("PayX", Integer.toString(xPay));
            defense = xPay;
        } else {
            defense = AbilityUtils.calculateAmount(sa.getHostCard(), numDefense, sa);
        }

        int attack;
        if (numAttack.contains("X") && source.getSVar("X").equals("Count$xPaid")) {
            // Set PayX here to maximum value.
            final String toPay = source.getSVar("PayX");

            if (toPay.equals("")) {
                final int xPay = ComputerUtilMana.determineLeftoverMana(sa, ai);
                source.setSVar("PayX", Integer.toString(xPay));
                attack = xPay;
            } else {
                attack = Integer.parseInt(toPay);
            }
        } else {
            attack = AbilityUtils.calculateAmount(sa.getHostCard(), numAttack, sa);
        }

        if (sa.getTargetRestrictions() == null) {
            if (mandatory) {
                return true;
            }
        } else {
            return pumpTgtAI(ai, sa, defense, attack, mandatory, true);
        }

        return true;
    } // pumpTriggerAI

    @Override
    public boolean chkAIDrawback(SpellAbility sa, Player ai) {

        final Card source = sa.getHostCard();

        final String numDefense = sa.hasParam("NumDef") ? sa.getParam("NumDef") : "";
        final String numAttack = sa.hasParam("NumAtt") ? sa.getParam("NumAtt") : "";

        if (numDefense.equals("-X") && sa.getSVar("X").equals("Count$ChosenNumber")) {
            int energy = ai.getCounters(CounterType.ENERGY);
            for (SpellAbility s : source.getSpellAbilities()) {
                if ("PayEnergy".equals(s.getParam("AILogic"))) {
                    energy += AbilityUtils.calculateAmount(source, s.getParam("CounterNum"), sa);
                    break;
                }
            }
            int minus = 0;
            for (; energy > 0; energy--) {
                if (pumpTgtAI(ai, sa, -energy, -energy, false, true)) {
                    minus = sa.getTargetCard().getNetToughness();
                    if (minus > energy || minus < 1) {
                        continue; // in case the calculation gets messed up somewhere
                    }
                    source.setSVar("EnergyToPay", "Number$" + minus);
                    return true;
                }
            }
            return false;
        }
        
        int defense;
        if (numDefense.contains("X") && source.getSVar("X").equals("Count$xPaid")) {
            defense = Integer.parseInt(source.getSVar("PayX"));
        } else {
            defense = AbilityUtils.calculateAmount(sa.getHostCard(), numDefense, sa);
        }

        int attack;
        if (numAttack.contains("X") && source.getSVar("X").equals("Count$xPaid")) {
            if (source.getSVar("PayX").equals("")) {
                // X is not set yet
                final int xPay = ComputerUtilMana.determineLeftoverMana(sa.getRootAbility(), ai);
                source.setSVar("PayX", Integer.toString(xPay));
                attack = xPay;
            } else {
                attack = Integer.parseInt(source.getSVar("PayX"));
            }
        } else {
            attack = AbilityUtils.calculateAmount(sa.getHostCard(), numAttack, sa);
        }

        if ((sa.getTargetRestrictions() == null) || !sa.getTargetRestrictions().doesTarget()) {
            if (source.isCreature()) {
                if (!source.hasKeyword("Indestructible") && source.getNetToughness() + defense <= source.getDamage()) {
                    return false;
                }
                if (source.getNetToughness() + defense <= 0) {
                    return false;
                }
            }
        } else {
            //Targeted
            if (!pumpTgtAI(ai, sa, defense, attack, false, true)) {
                return false;
            }
        }

        return true;
    } // pumpDrawbackAI()
    


    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message) {
        //TODO Add logic here if necessary but I think the AI won't cast
        //the spell in the first place if it would curse its own creature
        //and the pump isn't mandatory
        return true;
    }
}
