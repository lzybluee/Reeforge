package forge.ai.ability;

import forge.ai.AiController;
import forge.ai.AiProps;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import java.util.Iterator;

import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardFactoryUtil;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetRestrictions;
import forge.util.MyRandom;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

public class CounterAi extends SpellAbilityAi {

    @Override
    protected boolean canPlayAI(Player ai, SpellAbility sa) {
        boolean toReturn = true;
        final Cost abCost = sa.getPayCosts();
        final Card source = sa.getHostCard();
        final String sourceName = ComputerUtilAbility.getAbilitySourceName(sa);
        final Game game = ai.getGame();
        int tgtCMC = 0;
        SpellAbility tgtSA = null;

        if (game.getStack().isEmpty()) {
            return false;
        }

        if (abCost != null) {
            // AI currently disabled for these costs
            if (!ComputerUtilCost.checkSacrificeCost(ai, abCost, source, sa)) {
                return false;
            }
            if (!ComputerUtilCost.checkLifeCost(ai, abCost, source, 4, sa)) {
                return false;
            }
        }

        if ("Force of Will".equals(sourceName)) {
            if (!SpecialCardAi.ForceOfWill.consider(ai, sa)) {
                return false;
            }
        }

        final TargetRestrictions tgt = sa.getTargetRestrictions();
        if (tgt != null) {

            final SpellAbility topSA = ComputerUtilAbility.getTopSpellAbilityOnStack(game, sa);
            if (!CardFactoryUtil.isCounterableBy(topSA.getHostCard(), sa) || topSA.getActivatingPlayer() == ai
                    || ai.getAllies().contains(topSA.getActivatingPlayer())) {
                // might as well check for player's friendliness
                return false;
            }
            if (sa.hasParam("AITgts") && (topSA.getHostCard() == null
                    || !topSA.getHostCard().isValid(sa.getParam("AITgts"), sa.getActivatingPlayer(), source, sa))) {
                return false;
            }

            if (sa.hasParam("CounterNoManaSpell") && topSA.getTotalManaSpent() > 0) {
                return false;
            }

            sa.resetTargets();
            if (sa.canTargetSpellAbility(topSA)) {
                sa.getTargets().add(topSA);
                if (topSA.getPayCosts().getTotalMana() != null) {
                    tgtSA = topSA;
                    tgtCMC = topSA.getPayCosts().getTotalMana().getCMC();
                    tgtCMC += topSA.getPayCosts().getTotalMana().countX() > 0 ? 3 : 0; // TODO: somehow determine the value of X paid and account for it?
                }
            } else {
                return false;
            }
        } else {
            return false;
        }

        String unlessCost = sa.hasParam("UnlessCost") ? sa.getParam("UnlessCost").trim() : null;

        if (unlessCost != null && !unlessCost.endsWith(">")) {
            Player opp = tgtSA.getActivatingPlayer();
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
                return false;
            }

            if (toPay <= usableManaSources) {
                // If this is a reusable Resource, feel free to play it most of
                // the time
                if (!SpellAbilityAi.playReusable(ai,sa)) {
                    return false;
                }
            }

            if (setPayX) {
                source.setSVar("PayX", Integer.toString(toPay));
            }
        }

        // TODO Improve AI

        // Will return true if this spell can counter (or is Reusable and can
        // force the Human into making decisions)

        // But really it should be more picky about how it counters things

        if (sa.hasParam("AILogic")) {
            String logic = sa.getParam("AILogic");
            if ("Never".equals(logic)) {
                return false;
            } else if (logic.startsWith("MinCMC.")) {
                int minCMC = Integer.parseInt(logic.substring(7));
                if (tgtCMC < minCMC) {
                    return false;
                }
            }
        }

        // Specific constraints for the AI to use/not use counterspells against specific groups of spells
        // (specified in the AI profile)
        AiController aic = ((PlayerControllerAi)ai.getController()).getAi();
        boolean ctrCmc0ManaPerms = aic.getBooleanProperty(AiProps.ALWAYS_COUNTER_CMC_0_MANA_MAKING_PERMS);
        boolean ctrDamageSpells = aic.getBooleanProperty(AiProps.ALWAYS_COUNTER_DAMAGE_SPELLS);
        boolean ctrRemovalSpells = aic.getBooleanProperty(AiProps.ALWAYS_COUNTER_REMOVAL_SPELLS);
        boolean ctrOtherCounters = aic.getBooleanProperty(AiProps.ALWAYS_COUNTER_OTHER_COUNTERSPELLS);
        String ctrNamed = aic.getProperty(AiProps.ALWAYS_COUNTER_SPELLS_FROM_NAMED_CARDS);
        if (tgtSA != null && tgtCMC < aic.getIntProperty(AiProps.MIN_SPELL_CMC_TO_COUNTER)) {
            boolean dontCounter = true;
            Card tgtSource = tgtSA.getHostCard();
            if ((tgtSource != null && tgtCMC == 0 && tgtSource.isPermanent() && !tgtSource.getManaAbilities().isEmpty() && ctrCmc0ManaPerms)
                    || (tgtSA.getApi() == ApiType.DealDamage || tgtSA.getApi() == ApiType.LoseLife || tgtSA.getApi() == ApiType.DamageAll && ctrDamageSpells)
                    || (tgtSA.getApi() == ApiType.Counter && ctrOtherCounters)
                    || (tgtSA.getApi() == ApiType.Destroy || tgtSA.getApi() == ApiType.DestroyAll || tgtSA.getApi() == ApiType.Sacrifice
                       || tgtSA.getApi() == ApiType.SacrificeAll && ctrRemovalSpells)) {
                dontCounter = false;
            }

            if (tgtSource != null && !ctrNamed.isEmpty() && !"none".equalsIgnoreCase(ctrNamed)) {
                for (String name : StringUtils.split(ctrNamed, ";")) {
                    if (name.equals(tgtSource.getName())) {
                        dontCounter = false;
                    }
                }
            }
                
            // should not refrain from countering a  CMC X spell if that's the only CMC
            // counterable with that particular counterspell type (e.g. Mental Misstep vs. CMC 1 spells)
            if (sa.getParamOrDefault("ValidTgts", "").startsWith("Card.cmcEQ")) {
                int validTgtCMC = AbilityUtils.calculateAmount(source, sa.getParam("ValidTgts").substring(10), sa);
                if (tgtCMC == validTgtCMC) {
                    dontCounter = false;
                }
            }

            if (dontCounter) {
                return false;
            }
        }
        
        return toReturn;
    }

    @Override
    public boolean chkAIDrawback(SpellAbility sa, Player aiPlayer) {
        return doTriggerAINoCost(aiPlayer, sa, true);
    }

    @Override
    protected boolean doTriggerAINoCost(Player ai, SpellAbility sa, boolean mandatory) {
        final TargetRestrictions tgt = sa.getTargetRestrictions();
        final Game game = ai.getGame();

        if (tgt != null) {
            if (game.getStack().isEmpty()) {
                return false;
            }

            sa.resetTargets();
            Pair<SpellAbility, Boolean> pair = chooseTargetSpellAbility(game, sa, ai, mandatory);
            SpellAbility tgtSA = pair.getLeft();

            if (tgtSA == null) {
                return false;
            }
            sa.getTargets().add(tgtSA);
            if (!mandatory && !pair.getRight()) {
                // If not mandatory and not preferred, bail out after setting target
                return false;
            }

            String unlessCost = sa.hasParam("UnlessCost") ? sa.getParam("UnlessCost").trim() : null;

            final Card source = sa.getHostCard();
            if (unlessCost != null) {
                Player opp = tgtSA.getActivatingPlayer();
                int usableManaSources = ComputerUtilMana.getAvailableManaEstimate(opp);

                int toPay = 0;
                boolean setPayX = false;
                if (unlessCost.equals("X") && source.getSVar(unlessCost).equals("Count$xPaid")) {
                    setPayX = true;
                    toPay = ComputerUtilMana.determineLeftoverMana(sa, ai);
                } else {
                    toPay = AbilityUtils.calculateAmount(source, unlessCost, sa);
                }

                if (!mandatory) {
                    if (toPay == 0) {
                        return false;
                    }

                    if (toPay <= usableManaSources) {
                        // If this is a reusable Resource, feel free to play it most
                        // of the time
                        if (!SpellAbilityAi.playReusable(ai,sa) || (MyRandom.getRandom().nextFloat() < .4)) {
                            return false;
                        }
                    }
                }

                if (setPayX) {
                    source.setSVar("PayX", Integer.toString(toPay));
                }
            }
        }

        // TODO Improve AI

        // Will return true if this spell can counter (or is Reusable and can
        // force the Human into making decisions)

        // But really it should be more picky about how it counters things
        return true;
    }

    public Pair<SpellAbility, Boolean> chooseTargetSpellAbility(Game game, SpellAbility sa, Player ai, boolean mandatory) {
        SpellAbility tgtSA;
        SpellAbility leastBadOption = null;
        SpellAbility bestOption = null;

        Iterator<SpellAbilityStackInstance> it = game.getStack().iterator();
        SpellAbilityStackInstance si = null;
        while(it.hasNext()) {
            si = it.next();
            tgtSA = si.getSpellAbility(true);
            if (!sa.canTargetSpellAbility(tgtSA)) {
                continue;
            }
            if (leastBadOption == null) {
                leastBadOption = tgtSA;
            }

            if (!CardFactoryUtil.isCounterableBy(tgtSA.getHostCard(), sa) ||
                tgtSA.getActivatingPlayer() == ai ||
                !tgtSA.getActivatingPlayer().isOpponentOf(ai)) {
                // Is this a "better" least bad option
                if (leastBadOption.getActivatingPlayer().isOpponentOf(ai)) {
                    // NOOP
                } else if (sa.getActivatingPlayer().isOpponentOf(ai)) {
                    // Target opponents uncounterable stuff, before our own stuff
                    leastBadOption = tgtSA;
                }
                continue;
            }

            if (bestOption == null) {
                bestOption = tgtSA;
            } else {
                // TODO Determine if this option is better than the current best option
                boolean betterThanBest = false;
                if (betterThanBest) {
                    bestOption = tgtSA;
                }
                // Don't really need to keep updating leastBadOption once we have a bestOption
            }
        }

        return new ImmutablePair<>(bestOption != null ? bestOption : leastBadOption, bestOption != null);
    }
}
