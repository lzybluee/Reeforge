package forge.ai.ability;

import java.util.Collections;
import java.util.List;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;

import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCombat;
import forge.ai.SpellAbilityAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates.Presets;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;

public class ChooseCardAi extends SpellAbilityAi {

    /**
     * The rest of the logic not covered by the canPlayAI template is defined here
     */
    @Override
    protected boolean checkApiLogic(final Player ai, final SpellAbility sa) {
        if (sa.usesTargeting()) {
            sa.resetTargets();
            // search targetable Opponents
            final List<Player> oppList = Lists.newArrayList(Iterables.filter(
                    ai.getOpponents(), PlayerPredicates.isTargetableBy(sa)));

            if (oppList.isEmpty()) {
                return false;
            }

            sa.getTargets().add(Iterables.getFirst(oppList, null));
        }
        return true;
    }

    /**
     * Checks if the AI will play a SpellAbility with the specified AiLogic
     */
    @Override
    protected boolean checkAiLogic(final Player ai, final SpellAbility sa, final String aiLogic) {
        final Card host = sa.getHostCard();
        final Game game = ai.getGame();
        ZoneType choiceZone = ZoneType.Battlefield;
        if (sa.hasParam("ChoiceZone")) {
            choiceZone = ZoneType.smartValueOf(sa.getParam("ChoiceZone"));
        }
        CardCollectionView choices = ai.getGame().getCardsIn(choiceZone);
        if (sa.hasParam("Choices")) {
            choices = CardLists.getValidCards(choices, sa.getParam("Choices"), host.getController(), host);
        }
        if (sa.hasParam("TargetControls")) {
            choices = CardLists.filterControlledBy(choices, ai.getOpponents());
        }
        if (aiLogic.equals("AtLeast1") || aiLogic.equals("OppPreferred")) {
            if (choices.isEmpty()) {
                return false;
            }
        } else if (aiLogic.equals("AtLeast2") || aiLogic.equals("BestBlocker")) {
            if (choices.size() < 2) {
                return false;
            }
        } else if (aiLogic.equals("Clone") || aiLogic.equals("Vesuva")) {
            final String filter = aiLogic.equals("Clone") ? "Permanent.YouDontCtrl,Permanent.nonLegendary"
                    : "Permanent.YouDontCtrl+notnamedVesuva,Permanent.nonLegendary+notnamedVesuva";

            choices = CardLists.getValidCards(choices, filter, host.getController(), host);
            if (choices.isEmpty()) {
                return false;
            }
        } else if (aiLogic.equals("Never")) {
            return false;
        } else if (aiLogic.equals("NeedsPrevention")) {
            if (!game.getPhaseHandler().is(PhaseType.COMBAT_DECLARE_BLOCKERS)) {
                return false;
            }
            final Combat combat = game.getCombat();
            choices = CardLists.filter(choices, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    if (!combat.isAttacking(c, ai) || !combat.isUnblocked(c)) {
                        return false;
                    }
                    int ref = ComputerUtilAbility.getAbilitySourceName(sa).equals("Forcefield") ? 1 : 0;
                    return ComputerUtilCombat.damageIfUnblocked(c, ai, combat, true) > ref;
                }
            });
            if (choices.isEmpty()) {
                return false;
            }
        } else if (aiLogic.equals("Ashiok")) {
            final int loyalty = host.getCounters(CounterType.LOYALTY) - 1;
            for (int i = loyalty; i >= 0; i--) {
                host.setSVar("ChosenX", "Number$" + i);
                choices = ai.getGame().getCardsIn(choiceZone);
                choices = CardLists.getValidCards(choices, sa.getParam("Choices"), host.getController(), host);
                if (!choices.isEmpty()) {
                    return true;
                }
            }

            if (choices.isEmpty()) {
                return false;
            }
        } else if (aiLogic.equals("RandomNonLand")) {
            if (CardLists.getValidCards(choices, "Card.nonLand", host.getController(), host).isEmpty()) {
                return false;
            }
        } else if (aiLogic.equals("Duneblast")) {
            CardCollection aiCreatures = ai.getCreaturesInPlay();
            CardCollection oppCreatures = ComputerUtil.getOpponentFor(ai).getCreaturesInPlay();
            aiCreatures = CardLists.getNotKeyword(aiCreatures, "Indestructible");
            oppCreatures = CardLists.getNotKeyword(oppCreatures, "Indestructible");

            // Use it as a wrath, when the human creatures threat the ai's life
            if (aiCreatures.isEmpty() && ComputerUtilCombat.sumDamageIfUnblocked(oppCreatures, ai) >= ai.getLife()) {
                return true;
            }

            Card chosen = ComputerUtilCard.getBestCreatureAI(aiCreatures);
            aiCreatures.remove(chosen);
            int minGain = 200;

            if ((ComputerUtilCard.evaluateCreatureList(aiCreatures) + minGain) >= ComputerUtilCard
                    .evaluateCreatureList(oppCreatures)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean chkAIDrawback(SpellAbility sa, Player ai) {
        if (sa.hasParam("AILogic") && !checkAiLogic(ai, sa, sa.getParam("AILogic"))) {
            return false;
        }
        return checkApiLogic(ai, sa);
    }
    
    /* (non-Javadoc)
     * @see forge.card.ability.SpellAbilityAi#chooseSingleCard(forge.card.spellability.SpellAbility, java.util.List, boolean)
     */
    @Override
    public Card chooseSingleCard(final Player ai, final SpellAbility sa, Iterable<Card> options, boolean isOptional, Player targetedPlayer) {
        final Card host = sa.getHostCard();
        final Player ctrl = host.getController();
        final String logic = sa.getParam("AILogic");
        Card choice = null;
        if (logic == null) {
            // Base Logic is choose "best"
            choice = ComputerUtilCard.getBestAI(options);
        } else if ("WorstCard".equals(logic)) {
            choice = ComputerUtilCard.getWorstAI(options);
        } else if (logic.equals("BestBlocker")) {
            if (!CardLists.filter(options, Presets.UNTAPPED).isEmpty()) {
                options = CardLists.filter(options, Presets.UNTAPPED);
            }
            choice = ComputerUtilCard.getBestCreatureAI(options);
        } else if (logic.equals("Clone") || logic.equals("Vesuva")) {
            final String filter = logic.equals("Clone") ? "Permanent.YouDontCtrl,Permanent.nonLegendary" :
                    "Permanent.YouDontCtrl+notnamedVesuva,Permanent.nonLegendary+notnamedVesuva";

            CardCollection newOptions = CardLists.getValidCards(options, filter.split(","), ctrl, host, sa);
            if (!newOptions.isEmpty()) {
                options = newOptions;
            }
            choice = ComputerUtilCard.getBestAI(options);
            if (logic.equals("Vesuva") && "Vesuva".equals(choice.getName())) {
                choice = null;
            }
        } else if ("RandomNonLand".equals(logic)) {
            options = CardLists.getValidCards(options, "Card.nonLand", host.getController(), host);
            choice = Aggregates.random(options);
        } else if (logic.equals("Untap")) {
            final String filter = "Permanent.YouCtrl,Permanent.tapped";
            CardCollection newOptions = CardLists.getValidCards(options, filter.split(","), ctrl, host, sa);
            if (!newOptions.isEmpty()) {
                options = newOptions;
            }
            choice = ComputerUtilCard.getBestAI(options);
        } else if (logic.equals("NeedsPrevention")) {
            final Game game = ai.getGame();
            final Combat combat = game.getCombat();
            CardCollectionView better =  CardLists.filter(options, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    if (combat == null || !combat.isAttacking(c, ai) || !combat.isUnblocked(c)) {
                        return false;
                    }
                    int ref = ComputerUtilAbility.getAbilitySourceName(sa).equals("Forcefield") ? 1 : 0; 
                    return ComputerUtilCombat.damageIfUnblocked(c, ai, combat, true) > ref;
                }
            });
            if (!better.isEmpty()) {
                choice = ComputerUtilCard.getBestAI(better);
            } else {
                choice = ComputerUtilCard.getBestAI(options);
            }
        } else if ("OppPreferred".equals(logic)) {
            CardCollectionView oppControlled = CardLists.filterControlledBy(options, ai.getOpponents());
            if (!oppControlled.isEmpty()) {
                choice = ComputerUtilCard.getBestAI(oppControlled);
            } else {
                CardCollectionView aiControlled = CardLists.filterControlledBy(options, ai);
                choice = ComputerUtilCard.getWorstAI(aiControlled);
            }
        } else if ("LowestCMCCreature".equals(logic)) {
            CardCollection creats = CardLists.filter(options, Presets.CREATURES);
            creats = CardLists.filterToughness(creats, 1);
            if (creats.isEmpty()) {
                choice = ComputerUtilCard.getWorstAI(options);
            } else {
                CardLists.sortByCmcDesc(creats);
                Collections.reverse(creats);
                choice = creats.get(0);
            }
        } else if ("TangleWire".equals(logic)) {
            CardCollectionView betterList = CardLists.filter(options, new Predicate<Card>() {
                @Override
                public boolean apply(final Card c) {
                    if (c.isCreature()) {
                        return false;
                    }
                    for (SpellAbility sa : c.getAllSpellAbilities()) {
                        if (sa.getPayCosts() != null && sa.getPayCosts().hasTapCost()) {
                            return false;
                        }
                    }
                    return true;
                }
            });
            System.out.println("Tangle Wire" + options + " - " + betterList);
            if (!betterList.isEmpty()) {
                choice = betterList.get(0);
            } else {
                choice = ComputerUtilCard.getWorstPermanentAI(options, false, false, false, false);
            }
        } else if (logic.equals("Duneblast")) {
            CardCollectionView aiCreatures = ai.getCreaturesInPlay();
            aiCreatures = CardLists.getNotKeyword(aiCreatures, "Indestructible");

            if (aiCreatures.isEmpty()) {
                return null;
            }

            Card chosen = ComputerUtilCard.getBestCreatureAI(aiCreatures);
            return chosen;
        } else {
            choice = ComputerUtilCard.getBestAI(options);
        }
        return choice;
    }
}
