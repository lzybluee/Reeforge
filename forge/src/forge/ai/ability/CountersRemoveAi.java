package forge.ai.ability;

import java.util.List;
import java.util.Map;

import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilMana;
import forge.ai.SpellAbilityAi;
import forge.game.Game;
import forge.game.GlobalRuleChange;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CounterType;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

public class CountersRemoveAi extends SpellAbilityAi {

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.ai.SpellAbilityAi#checkPhaseRestrictions(forge.game.player.Player,
     * forge.game.spellability.SpellAbility, forge.game.phase.PhaseHandler)
     */
    @Override
    protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, PhaseHandler ph) {
        final String type = sa.getParam("CounterType");

        if (ph.getPhase().isBefore(PhaseType.MAIN2) && !sa.hasParam("ActivationPhases") && !type.equals("M1M1")) {
            return false;
        }
        return super.checkPhaseRestrictions(ai, sa, ph);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * forge.ai.SpellAbilityAi#checkPhaseRestrictions(forge.game.player.Player,
     * forge.game.spellability.SpellAbility, forge.game.phase.PhaseHandler,
     * java.lang.String)
     */
    @Override
    protected boolean checkPhaseRestrictions(Player ai, SpellAbility sa, PhaseHandler ph, String logic) {
        if ("EndOfOpponentsTurn".equals(logic)) {
            if (!ph.is(PhaseType.END_OF_TURN) || !ph.getNextTurn().equals(ai)) {
                return false;
            }
        }
        return super.checkPhaseRestrictions(ai, sa, ph, logic);
    }

    /*
     * (non-Javadoc)
     * 
     * @see forge.ai.SpellAbilityAi#checkApiLogic(forge.game.player.Player,
     * forge.game.spellability.SpellAbility)
     */
    @Override
    protected boolean checkApiLogic(Player ai, SpellAbility sa) {

        final String type = sa.getParam("CounterType");

        if (sa.usesTargeting()) {
            return doTgt(ai, sa, false);
        }

        if (!type.matches("Any") && !type.matches("All")) {
            final int currCounters = sa.getHostCard().getCounters(CounterType.valueOf(type));
            if (currCounters < 1) {
                return false;
            }
        }

        return super.checkApiLogic(ai, sa);
    }

    private boolean doTgt(Player ai, SpellAbility sa, boolean mandatory) {
        final Card source = sa.getHostCard();
        final Game game = ai.getGame();

        final String type = sa.getParam("CounterType");
        final String amountStr = sa.getParam("CounterNum");

        // remove counter with Time might use Exile Zone too
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        CardCollection list = new CardCollection(game.getCardsIn(tgt.getZone()));
        // need to targetable
        list = CardLists.getTargetableCards(list, sa);

        if (list.isEmpty()) {
            return false;
        }

        if (sa.hasParam("AITgts")) {
            String aiTgts = sa.getParam("AITgts");
            CardCollection prefList = CardLists.getValidCards(list, aiTgts.split(","), ai, source, sa);
            if (!prefList.isEmpty() || sa.hasParam("AITgtsStrict")) {
                list = prefList;
            }
        }

        boolean noLegendary = game.getStaticEffects().getGlobalRuleChange(GlobalRuleChange.noLegendRule);

        if (type.matches("All")) {
            // Logic Part for Vampire Hexmage
            // Break Dark Depths
            if (!ai.isCardInPlay("Marit Lage") || noLegendary) {
                CardCollectionView depthsList = ai.getCardsIn(ZoneType.Battlefield, "Dark Depths");
                depthsList = CardLists.filter(depthsList, CardPredicates.isTargetableBy(sa),
                        CardPredicates.hasCounter(CounterType.ICE, 3));

                if (!depthsList.isEmpty()) {
                    sa.getTargets().add(depthsList.getFirst());
                    return true;
                }
            }

            // Get rid of Planeswalkers:
            list = ai.getOpponents().getCardsIn(ZoneType.Battlefield);
            list = CardLists.filter(list, CardPredicates.isTargetableBy(sa));

            CardCollection planeswalkerList = CardLists.filter(list, CardPredicates.Presets.PLANEWALKERS,
                    CardPredicates.hasCounter(CounterType.LOYALTY, 5));

            if (!planeswalkerList.isEmpty()) {
                sa.getTargets().add(ComputerUtilCard.getBestPlaneswalkerAI(planeswalkerList));
                return true;
            }

        } else if (type.matches("Any")) {
            // variable amount for Hex Parasite
            int amount;
            boolean xPay = false;
            if (amountStr.equals("X") && source.getSVar("X").equals("Count$xPaid")) {
                final int manaLeft = ComputerUtilMana.determineLeftoverMana(sa, ai);

                if (manaLeft == 0) {
                    return false;
                }
                amount = manaLeft;
                xPay = true;
            } else {
                amount = AbilityUtils.calculateAmount(source, amountStr, sa);
            }
            // try to remove them from Dark Depths and Planeswalkers too

            if (!ai.isCardInPlay("Marit Lage") || noLegendary) {
                CardCollectionView depthsList = ai.getCardsIn(ZoneType.Battlefield, "Dark Depths");
                depthsList = CardLists.filter(depthsList, CardPredicates.isTargetableBy(sa),
                        CardPredicates.hasCounter(CounterType.ICE));

                if (!depthsList.isEmpty()) {
                    Card depth = depthsList.getFirst();
                    int ice = depth.getCounters(CounterType.ICE);
                    if (amount >= ice) {
                        sa.getTargets().add(depth);
                        if (xPay) {
                            source.setSVar("PayX", Integer.toString(ice));
                        }
                        return true;
                    }
                }
            }

            // Get rid of Planeswalkers:
            list = ai.getOpponents().getCardsIn(ZoneType.Battlefield);
            list = CardLists.filter(list, CardPredicates.isTargetableBy(sa));

            CardCollection planeswalkerList = CardLists.filter(list, CardPredicates.Presets.PLANEWALKERS,
                    CardPredicates.hasLessCounter(CounterType.LOYALTY, amount));

            if (!planeswalkerList.isEmpty()) {
                Card best = ComputerUtilCard.getBestPlaneswalkerAI(planeswalkerList);
                sa.getTargets().add(best);
                if (xPay) {
                    source.setSVar("PayX", Integer.toString(best.getCurrentLoyalty()));
                }
                return true;
            }

            // some rules only for amount = 1
            if (!xPay) {
                // do as M1M1 part
                CardCollection aiList = CardLists.filterControlledBy(list, ai);

                CardCollection aiM1M1List = CardLists.filter(aiList, CardPredicates.hasCounter(CounterType.M1M1));

                CardCollection aiPersistList = CardLists.getKeyword(aiM1M1List, "Persist");
                if (!aiPersistList.isEmpty()) {
                    aiM1M1List = aiPersistList;
                }

                if (!aiM1M1List.isEmpty()) {
                    sa.getTargets().add(ComputerUtilCard.getBestCreatureAI(aiM1M1List));
                    return true;
                }

                // do as P1P1 part
                CardCollection aiP1P1List = CardLists.filter(aiList, CardPredicates.hasCounter(CounterType.P1P1));
                CardCollection aiUndyingList = CardLists.getKeyword(aiM1M1List, "Undying");

                if (!aiUndyingList.isEmpty()) {
                    aiP1P1List = aiUndyingList;
                }
                if (!aiP1P1List.isEmpty()) {
                    sa.getTargets().add(ComputerUtilCard.getBestCreatureAI(aiP1P1List));
                    return true;
                }

                // fallback to remove any counter from opponent
                CardCollection oppList = CardLists.filterControlledBy(list, ai.getOpponents());
                oppList = CardLists.filter(oppList, CardPredicates.hasCounters());
                if (!oppList.isEmpty()) {
                    final Card best = ComputerUtilCard.getBestAI(oppList);

                    for (final CounterType aType : best.getCounters().keySet()) {
                        if (!ComputerUtil.isNegativeCounter(aType, best)) {
                            sa.getTargets().add(best);
                            return true;
                        }
                    }
                }
            }
        } else if (type.equals("M1M1")) {
            // no special amount for that one yet
            int amount = AbilityUtils.calculateAmount(source, amountStr, sa);
            CardCollection aiList = CardLists.filterControlledBy(list, ai);
            aiList = CardLists.filter(aiList, CardPredicates.hasCounter(CounterType.M1M1, amount));

            CardCollection aiPersist = CardLists.getKeyword(aiList, "Persist");
            if (!aiPersist.isEmpty()) {
                aiList = aiPersist;
            }

            // TODO do not remove -1/-1 counters from cards which does need
            // them for abilities

            if (!aiList.isEmpty()) {
                sa.getTargets().add(ComputerUtilCard.getBestCreatureAI(aiList));
                return true;
            }

        } else if (type.equals("P1P1")) {
            // no special amount for that one yet
            int amount = AbilityUtils.calculateAmount(source, amountStr, sa);

            list = CardLists.filter(list, CardPredicates.hasCounter(CounterType.P1P1, amount));

            // currently only logic for Bloodcrazed Hoplite, but add logic for
            // targeting ai creatures too
            CardCollection aiList = CardLists.filterControlledBy(list, ai);
            if (!aiList.isEmpty()) {
                CardCollection aiListUndying = CardLists.getKeyword(aiList, "Undying");
                if (!aiListUndying.isEmpty()) {
                    aiList = aiListUndying;
                }
                if (!aiList.isEmpty()) {
                    sa.getTargets().add(ComputerUtilCard.getBestCreatureAI(aiList));
                    return true;
                }
            }

            // need to target opponent creatures
            CardCollection oppList = CardLists.filterControlledBy(list, ai.getOpponents());
            if (!oppList.isEmpty()) {
                CardCollection oppListNotUndying = CardLists.getNotKeyword(oppList, "Undying");
                if (!oppListNotUndying.isEmpty()) {
                    oppList = oppListNotUndying;
                }

                if (!oppList.isEmpty()) {
                    sa.getTargets().add(ComputerUtilCard.getWorstCreatureAI(oppList));
                    return true;
                }
            }

        } else if (type.equals("TIME")) {
            int amount;
            boolean xPay = false;
            // Timecrafting has X R
            if (amountStr.equals("X") && source.getSVar("X").equals("Count$xPaid")) {
                final int manaLeft = ComputerUtilMana.determineLeftoverMana(sa, ai);

                if (manaLeft == 0) {
                    return false;
                }
                amount = manaLeft;
                xPay = true;
            } else {
                amount = AbilityUtils.calculateAmount(source, amountStr, sa);
            }

            CardCollection timeList = CardLists.filter(list, CardPredicates.hasLessCounter(CounterType.TIME, amount));

            if (!timeList.isEmpty()) {
                Card best = ComputerUtilCard.getBestAI(timeList);

                int timeCount = best.getCounters(CounterType.TIME);
                sa.getTargets().add(best);
                if (xPay) {
                    source.setSVar("PayX", Integer.toString(timeCount));
                }
                return true;
            }
        }
        if (mandatory) {
            sa.getTargets().add(ComputerUtilCard.getWorstAI(list));
            return true;
        }
        return false;
    }

    @Override
    protected boolean doTriggerAINoCost(Player aiPlayer, SpellAbility sa, boolean mandatory) {
        if (sa.usesTargeting()) {
            return doTgt(aiPlayer, sa, mandatory);
        }
        return mandatory;
    }

    /*
     * (non-Javadoc)
     * 
     * @see forge.ai.SpellAbilityAi#chooseNumber(forge.game.player.Player,
     * forge.game.spellability.SpellAbility, int, int, java.util.Map)
     */
    @Override
    public int chooseNumber(Player player, SpellAbility sa, int min, int max, Map<String, Object> params) {
        // TODO Auto-generated method stub
        return super.chooseNumber(player, sa, min, max, params);
    }

    /*
     * (non-Javadoc)
     * 
     * @see forge.ai.SpellAbilityAi#chooseCounterType(java.util.List,
     * forge.game.spellability.SpellAbility, java.util.Map)
     */
    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, Map<String, Object> params) {
        if (options.size() <= 1) {
            return super.chooseCounterType(options, sa, params);
        }
        Player ai = sa.getActivatingPlayer();
        Card target = (Card) params.get("Target");

        if (target.getController().isOpponentOf(ai)) {
            // if its a Planeswalker try to remove Loyality first
            if (target.isPlaneswalker()) {
                return CounterType.LOYALTY;
            }
            for (CounterType type : options) {
                if (!ComputerUtil.isNegativeCounter(type, target)) {
                    return type;
                }
            }
        } else {
            if (options.contains(CounterType.M1M1) && target.hasKeyword("Persist")) {
                return CounterType.M1M1;
            } else if (options.contains(CounterType.P1P1) && target.hasKeyword("Undying")) {
                return CounterType.M1M1;
            }
            for (CounterType type : options) {
                if (ComputerUtil.isNegativeCounter(type, target)) {
                    return type;
                }
            }
        }
        return super.chooseCounterType(options, sa, params);
    }
}
