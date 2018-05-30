package forge.ai.ability;

import com.google.common.base.Predicate;
import forge.ai.*;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostSacrifice;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

public class DestroyAi extends SpellAbilityAi {
    @Override
    public boolean chkAIDrawback(SpellAbility sa, Player ai) {
        return canPlayAI(ai, sa);
    }

    @Override
    protected boolean canPlayAI(final Player ai, SpellAbility sa) {
        // AI needs to be expanded, since this function can be pretty complex
        // based on what the expected targets could be
        final Cost abCost = sa.getPayCosts();
        final TargetRestrictions abTgt = sa.getTargetRestrictions();
        final Card source = sa.getHostCard();
        final boolean noRegen = sa.hasParam("NoRegen");
        final String logic = sa.getParam("AILogic");
        boolean hasXCost = false;

        CardCollection list;

        if (abCost != null) {
            if (!ComputerUtilCost.checkSacrificeCost(ai, abCost, source, sa)) {
                return false;
            }

            if (!ComputerUtilCost.checkLifeCost(ai, abCost, source, 4, sa)) {
                return false;
            }

            if (!ComputerUtilCost.checkDiscardCost(ai, abCost, source)) {
                return false;
            }

            hasXCost = abCost.getCostMana() != null ? abCost.getCostMana().getAmountOfX() > 0 : false;
        }

        if ("AtOpponentsCombatOrAfter".equals(sa.getParam("AILogic"))) {
            PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (ph.getPlayerTurn() == ai || ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
                return false;
            }
        } else if ("AtEOT".equals(sa.getParam("AILogic"))) {
            PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (!ph.is(PhaseType.END_OF_TURN)) {
                return false;
            }
        } else if ("AtEOTIfNotAttacking".equals(sa.getParam("AILogic"))) {
            PhaseHandler ph = ai.getGame().getPhaseHandler();
            if (!ph.is(PhaseType.END_OF_TURN) || ai.getAttackedWithCreatureThisTurn()) {
                return false;
            }
        }

        if (ComputerUtil.preventRunAwayActivations(sa)) {
            return false;
        }

        // Ability that's intended to destroy own useless token to trigger Grave Pacts
        // should be fired at end of turn or when under attack after blocking to make opponent sac something
        boolean havepact = false;

        // TODO replace it with look for a dies -> sacrifice trigger check
        havepact |= ai.isCardInPlay("Grave Pact");
        havepact |= ai.isCardInPlay("Butcher of Malakir");
        havepact |= ai.isCardInPlay("Dictate of Erebos");
        if ("Pactivator".equals(logic) && havepact) {
            if ((!ai.getGame().getPhaseHandler().isPlayerTurn(ai))
                    && ((ai.getGame().getPhaseHandler().is(PhaseType.END_OF_TURN)) || (ai.getGame().getPhaseHandler().is(PhaseType.COMBAT_DECLARE_BLOCKERS)))
                    && (ai.getOpponents().getCreaturesInPlay().size() > 0)) {
                ai.getController().chooseTargetsFor(sa);
                return true;
            }
        }

        // Targeting
        if (abTgt != null) {
            sa.resetTargets();
            if (sa.hasParam("TargetingPlayer")) {
                Player targetingPlayer = AbilityUtils.getDefinedPlayers(source, sa.getParam("TargetingPlayer"), sa).get(0);
                sa.setTargetingPlayer(targetingPlayer);
                return targetingPlayer.getController().chooseTargetsFor(sa);
            }
            if ("MadSarkhanDragon".equals(logic)) {
                return SpecialCardAi.SarkhanTheMad.considerMakeDragon(ai, sa);
            } else if (logic != null && logic.startsWith("MinLoyalty.")) {
                int minLoyalty = Integer.parseInt(logic.substring(logic.indexOf(".") + 1));
                if (source.getCounters(CounterType.LOYALTY) < minLoyalty) {
                    return false;
                }
            } else if ("Polymorph".equals(logic)) {
                list = CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Battlefield), sa);
                if (list.isEmpty()) {
                    return false;
                }
                for (Card c : list) {
                    if (c.hasKeyword(Keyword.INDESTRUCTIBLE)) {
                        sa.getTargets().add(c);
                        return true;
                    }
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
            list = CardLists.getTargetableCards(ai.getOpponents().getCardsIn(ZoneType.Battlefield), sa);
            if ("FatalPush".equals(logic)) {
                final int cmcMax = ai.hasRevolt() ? 4 : 2;
                list = CardLists.filter(list, CardPredicates.lessCMC(cmcMax));
            }

            // Filter AI-specific targets if provided
            list = ComputerUtil.filterAITgts(sa, ai, (CardCollection)list, true);

            list = CardLists.getNotKeyword(list, Keyword.INDESTRUCTIBLE);
            if (CardLists.getNotType(list, "Creature").isEmpty()) {
                list = ComputerUtilCard.prioritizeCreaturesWorthRemovingNow(ai, list, false);
            }
            if (!SpellAbilityAi.playReusable(ai, sa)) {
                list = CardLists.filter(list, new Predicate<Card>() {
                    @Override
                    public boolean apply(final Card c) {
                        //Check for cards that can be sacrificed in response
                        for (final SpellAbility ability : c.getAllSpellAbilities()) {
                            if (ability.isAbility()) {
                                final Cost cost = ability.getPayCosts();
                                for (final CostPart part : cost.getCostParts()) {
                                    if (!(part instanceof CostSacrifice)) {
                                        continue;
                                    }
                                    CostSacrifice sacCost = (CostSacrifice) part;
                                    if (sacCost.payCostFromSource() && ComputerUtilCost.canPayCost(ability, c.getController())) {
                                        return false;
                                    }
                                }
                            }
                        }
                        if (c.hasSVar("SacMe")) {
                            return false;
                        }
                        //Check for undying
                        return (!c.hasKeyword(Keyword.UNDYING) || c.getCounters(CounterType.P1P1) > 0);
                    }
                });
            }

            // If NoRegen is not set, filter out creatures that have a
            // regeneration shield
            if (!noRegen) {
                // TODO filter out things that might be tougher?
                list = CardLists.filter(list, new Predicate<Card>() {
                    @Override
                    public boolean apply(final Card c) {
                        return (c.getShieldCount() == 0 && !ComputerUtil.canRegenerate(ai, c));
                    }
                });
            }

            if (list.isEmpty()) {
                return false;
            }

            int maxTargets = abTgt.getMaxTargets(sa.getHostCard(), sa);

            if (hasXCost) {
                // TODO: currently the AI will maximize mana spent on X, trying to maximize damage. This may need improvement.
                maxTargets = Math.min(ComputerUtilMana.determineMaxAffordableX(ai, sa), abTgt.getMaxTargets(sa.getHostCard(), sa));
                // X can't be more than the lands we have in our hand for "discard X lands"!
                if ("ScorchedEarth".equals(logic)) {
                    int lands = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.Presets.LANDS).size();
                    maxTargets = Math.min(maxTargets, lands);
                }
            }
            if (sa.hasParam("AIMaxTgtsCount")) {
                // Cards that have confusing costs for the AI (e.g. Eliminate the Competition) can have forced max target constraints specified
                // TODO: is there a better way to predict things like "sac X" costs without needing a special AI variable?
                maxTargets = Math.min(CardFactoryUtil.xCount(sa.getHostCard(), "Count$" + sa.getParam("AIMaxTgtsCount")), maxTargets);
            }

            if (maxTargets == 0) {
                // can't afford X or otherwise target anything
                return false;
            }

            // target loop
            while (sa.getTargets().getNumTargeted() < maxTargets) {
                if (list.isEmpty()) {
                    if ((sa.getTargets().getNumTargeted() < abTgt.getMinTargets(sa.getHostCard(), sa))
                        || (sa.getTargets().getNumTargeted() == 0)) {
                        sa.resetTargets();
                        return false;
                    } else {
                        // TODO is this good enough? for up to amounts?
                        break;
                    }
                }

                Card choice = null;
                // If the targets are only of one type, take the best
                if (CardLists.getNotType(list, "Creature").isEmpty()) {
                    choice = ComputerUtilCard.getBestCreatureAI(list);
                    if ("OppDestroyYours".equals(logic)) {
                        Card aiBest = ComputerUtilCard.getBestCreatureAI(ai.getCreaturesInPlay());
                        if (ComputerUtilCard.evaluateCreature(aiBest) > ComputerUtilCard.evaluateCreature(choice) - 40) {
                            return false;
                        }
                    }
                    if ("Pongify".equals(logic)) {
                        final Card token = TokenAi.spawnToken(choice.getController(), sa.getSubAbility());
                        if (token == null) {
                            return true;    // becomes Terminate
                        } else {
                            if (source.getGame().getPhaseHandler().getPhase()
                                .isBefore(PhaseType.COMBAT_DECLARE_BLOCKERS) || // prevent surprise combatant
                                ComputerUtilCard.evaluateCreature(choice) < 1.5
                                * ComputerUtilCard.evaluateCreature(token)) {
                                return false;
                            }
                        }
                    }
                } else if (CardLists.getNotType(list, "Land").isEmpty()) {
                    choice = ComputerUtilCard.getBestLandAI(list);

                    if ("LandForLand".equals(logic) || "GhostQuarter".equals(logic)) {
                        // Strip Mine, Wasteland - cut short if the relevant logic fails
                        if (!doLandForLandRemovalLogic(sa, ai, choice, logic)) {
                            return false;
                        }
                    }
                } else {
                    choice = ComputerUtilCard.getMostExpensivePermanentAI(list, sa, true);
                }
                //option to hold removal instead only applies for single targeted removal
                if (!sa.isTrigger() && abTgt.getMaxTargets(sa.getHostCard(), sa) == 1) {
                    if (!ComputerUtilCard.useRemovalNow(sa, choice, 0, ZoneType.Graveyard)) {
                        return false;
                    }
                }

                if (choice == null) { // can't find anything left
                    if ((sa.getTargets().getNumTargeted() < abTgt.getMinTargets(sa.getHostCard(), sa))
                        || (sa.getTargets().getNumTargeted() == 0)) {
                        sa.resetTargets();
                        return false;
                    } else {
                        // TODO is this good enough? for up to amounts?
                        break;
                    }
                } else {
                    // Don't destroy stolen permanents when the stealing aura can be destroyed
                    if (choice.getOwner() == ai) {
                        for (Card aura : choice.getEnchantedBy(false)) {
                            SpellAbility sp = aura.getFirstSpellAbility();
                            if (sp != null && "GainControl".equals(sp.getParam("AILogic"))
                                && aura.getController() != ai && sa.canTarget(aura)) {
                                choice = aura;
                            }
                        }
                    }
                }
                list.remove(choice);
                sa.getTargets().add(choice);
            }
        } else if (sa.hasParam("Defined")) {
            list = AbilityUtils.getDefinedCards(source, sa.getParam("Defined"), sa);
            if ("WillSkipTurn".equals(logic) && (sa.getHostCard().getController().equals(ai)
                || ai.getCreaturesInPlay().size() < ComputerUtil.getOpponentFor(ai).getCreaturesInPlay().size()
                || !source.getGame().getPhaseHandler().isPlayerTurn(ai)
                || ai.getLife() <= 5)) {
                // Basic ai logic for Lethal Vapors
                return false;
            }

            if (list.isEmpty()
                || !CardLists.filterControlledBy(list, ai).isEmpty()
                || CardLists.getNotKeyword(list, Keyword.INDESTRUCTIBLE).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected boolean doTriggerAINoCost(Player ai, SpellAbility sa, boolean mandatory) {
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        final Card source = sa.getHostCard();
        final boolean noRegen = sa.hasParam("NoRegen");
        if (tgt != null) {
            sa.resetTargets();

            CardCollection list = CardLists.getTargetableCards(ai.getGame().getCardsIn(ZoneType.Battlefield), sa);
            list = CardLists.getValidCards(list, tgt.getValidTgts(), source.getController(), source, sa);

            if (list.isEmpty() || list.size() < tgt.getMinTargets(sa.getHostCard(), sa)) {
                return false;
            }

            CardCollection preferred = CardLists.getNotKeyword(list, Keyword.INDESTRUCTIBLE);
            preferred = CardLists.filterControlledBy(preferred, ai.getOpponents());
            if (CardLists.getNotType(preferred, "Creature").isEmpty()) {
                preferred = ComputerUtilCard.prioritizeCreaturesWorthRemovingNow(ai, preferred, false);
            }

            // If NoRegen is not set, filter out creatures that have a
            // regeneration shield
            if (!noRegen) {
                // TODO filter out things that could regenerate in response?
                // might be tougher?
                preferred = CardLists.filter(preferred, new Predicate<Card>() {
                    @Override
                    public boolean apply(final Card c) {
                        return c.getShieldCount() == 0;
                    }
                });
            }

            // Filter AI-specific targets if provided
            preferred = ComputerUtil.filterAITgts(sa, ai, (CardCollection)preferred, true);

            for (final Card c : preferred) {
                list.remove(c);
            }

            if (preferred.isEmpty() && !mandatory) {
            	return false;
            }

            while (sa.getTargets().getNumTargeted() < tgt.getMaxTargets(sa.getHostCard(), sa)) {
                if (preferred.isEmpty()) {
                    if (sa.getTargets().getNumTargeted() == 0
                            || sa.getTargets().getNumTargeted() < tgt.getMinTargets(sa.getHostCard(), sa)) {
                        if (!mandatory) {
                            sa.resetTargets();
                            return false;
                        } else {
                            break;
                        }
                    } else {
                        break;
                    }
                } else {
                    Card c;
                    if (CardLists.getNotType(preferred, "Creature").isEmpty()) {
                        c = ComputerUtilCard.getBestCreatureAI(preferred);
                    } else if (CardLists.getNotType(preferred, "Land").isEmpty()) {
                        c = ComputerUtilCard.getBestLandAI(preferred);
                    } else {
                        c = ComputerUtilCard.getMostExpensivePermanentAI(preferred, sa, false);
                    }
                    sa.getTargets().add(c);
                    preferred.remove(c);
                }
            }

            while (sa.getTargets().getNumTargeted() < tgt.getMinTargets(sa.getHostCard(), sa)) {
                if (list.isEmpty()) {
                    break;
                } else {
                    Card c;
                    if (CardLists.getNotType(list, "Creature").isEmpty()) {
                        if (!sa.getUniqueTargets().isEmpty() && sa.getParent().getApi() == ApiType.Destroy
                                && sa.getUniqueTargets().get(0) instanceof Card) {
                            // basic ai for Diaochan 
                            c = (Card) sa.getUniqueTargets().get(0);
                        } else {
                            c = ComputerUtilCard.getWorstCreatureAI(list);
                        }
                    } else {
                        c = ComputerUtilCard.getCheapestPermanentAI(list, sa, false);
                    }
                    sa.getTargets().add(c);
                    list.remove(c);
                }
            }

            if (sa.getTargets().getNumTargeted() < tgt.getMinTargets(sa.getHostCard(), sa)) {
                return false;
            }
        } else {
            if (!mandatory) {
                return false;
            }
        }

        return true;
    }

    public boolean doLandForLandRemovalLogic(SpellAbility sa, Player ai, Card tgtLand, String logic) {
        if (tgtLand == null) { return false; }

        Player tgtPlayer = tgtLand.getController();
        int oppLandsOTB = tgtPlayer.getLandsInPlay().size();
        
        // AI profile-dependent properties
        AiController aic = ((PlayerControllerAi)ai.getController()).getAi();
        int amountNoTempoCheck = aic.getIntProperty(AiProps.STRIPMINE_MIN_LANDS_OTB_FOR_NO_TEMPO_CHECK);
        int amountNoTimingCheck = aic.getIntProperty(AiProps.STRIPMINE_MIN_LANDS_FOR_NO_TIMING_CHECK);
        int amountLandsInHand = aic.getIntProperty(AiProps.STRIPMINE_MIN_LANDS_IN_HAND_TO_ACTIVATE);
        int amountLandsToManalock = aic.getIntProperty(AiProps.STRIPMINE_MAX_LANDS_TO_ATTEMPT_MANALOCKING);
        boolean highPriorityIfNoLandDrop = aic.getBooleanProperty(AiProps.STRIPMINE_HIGH_PRIORITY_ON_SKIPPED_LANDDROP);

        // if the opponent didn't play a land and has few lands OTB, might be worth mana-locking him
        PhaseHandler ph = ai.getGame().getPhaseHandler();
        boolean oppSkippedLandDrop = (tgtPlayer.getLandsPlayedLastTurn() == 0 && ph.isPlayerTurn(ai))
                || (tgtPlayer.getLandsPlayedThisTurn() == 0 && ph.isPlayerTurn(tgtPlayer) && ph.getPhase().isAfter(PhaseType.MAIN2));
        boolean canManaLock = oppLandsOTB <= amountLandsToManalock && oppSkippedLandDrop;

        // Best target is a basic land, and there's only one of it, so destroying it may potentially color-lock the opponent
        // (triggers either if the opponent skipped a land drop or if there are quite a few lands already in play but only one of the given type)
        CardCollection oppLands = tgtPlayer.getLandsInPlay();
        boolean canColorLock = (oppSkippedLandDrop || oppLands.size() > 3)
                && tgtLand.isBasicLand() && CardLists.filter(oppLands, CardPredicates.nameEquals(tgtLand.getName())).size() == 1;

        // Non-basic lands are currently not ranked in any way in ComputerUtilCard#getBestLandAI, so if a non-basic land is best target,
        // consider killing it off unless there's too much potential tempo loss.
        // TODO: actually rank non-basics in that method and then kill off the potentially dangerous (manlands, Valakut) or lucrative 
        // (dual/triple mana that opens access to a certain color) lands
        boolean nonBasicTgt = !tgtLand.isBasicLand();

        // Try not to lose tempo too much and not to mana-screw yourself when considering this logic
        int numLandsInHand = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.Presets.LANDS_PRODUCING_MANA).size();
        int numLandsOTB = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.Presets.LANDS_PRODUCING_MANA).size();

        // If the opponent skipped a land drop, consider not looking at having the extra land in hand if the profile allows it
        boolean isHighPriority = highPriorityIfNoLandDrop && oppSkippedLandDrop;

        boolean timingCheck = canManaLock || canColorLock || nonBasicTgt;
        boolean tempoCheck = numLandsOTB >= amountNoTempoCheck 
                || ((numLandsInHand >= amountLandsInHand || isHighPriority) && ((numLandsInHand + numLandsOTB >= amountNoTimingCheck) || timingCheck));

        // For Ghost Quarter, only use it if you have either more lands in play than your opponent
        // or the same number of lands but an extra land in hand (otherwise the AI plays too suboptimally)
        if ("GhostQuarter".equals(logic)) {
            return tempoCheck && (numLandsOTB > oppLands.size() || (numLandsOTB == oppLands.size() && numLandsInHand > 0));
        } else {
            return tempoCheck;
        }
    }

}
