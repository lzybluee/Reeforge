package forge.ai.ability;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import forge.ai.*;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.*;
import forge.game.card.CardPredicates.Presets;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerCollection;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.Collection;
import java.util.List;

public class CopyPermanentAi extends SpellAbilityAi {
    @Override
    protected boolean canPlayAI(Player aiPlayer, SpellAbility sa) {
        // Card source = sa.getHostCard();
        // TODO - I'm sure someone can do this AI better

        PhaseHandler ph = aiPlayer.getGame().getPhaseHandler();
        String aiLogic = sa.getParamOrDefault("AILogic", "");

        if (ComputerUtil.preventRunAwayActivations(sa)) {
            return false;
        }

        if ("MimicVat".equals(aiLogic)) {
            return SpecialCardAi.MimicVat.considerCopy(aiPlayer, sa);
        } else if ("AtEOT".equals(aiLogic)) {
            return ph.is(PhaseType.END_OF_TURN);
        } else if ("AtOppEOT".equals(aiLogic)) {
            return ph.is(PhaseType.END_OF_TURN) && ph.getPlayerTurn() != aiPlayer;
        }

        if (sa.hasParam("AtEOT") && !aiPlayer.getGame().getPhaseHandler().is(PhaseType.MAIN1)) {
            return false;
        }

        if (sa.hasParam("Defined")) {
            // If there needs to be an imprinted card, don't activate the ability if nothing was imprinted yet (e.g. Mimic Vat)
            if (sa.getParam("Defined").equals("Imprinted.ExiledWithSource") && sa.getHostCard().getImprintedCards().isEmpty()) {
                return false;
            }
        }

        if (sa.hasParam("Embalm") || sa.hasParam("Eternalize")) {
            // E.g. Vizier of Many Faces: check to make sure it makes sense to make the token now
            if (ComputerUtilCard.checkNeedsToPlayReqs(sa.getHostCard(), sa) != AiPlayDecision.WillPlay) {
                return false;
            }
        }

        if (sa.usesTargeting() && sa.hasParam("TargetingPlayer")) {
            sa.resetTargets();
            Player targetingPlayer = AbilityUtils.getDefinedPlayers(sa.getHostCard(), sa.getParam("TargetingPlayer"), sa).get(0);
            sa.setTargetingPlayer(targetingPlayer);
            return targetingPlayer.getController().chooseTargetsFor(sa);
        } else {
            return this.doTriggerAINoCost(aiPlayer, sa, false);
        }
    }

    @Override
    protected boolean doTriggerAINoCost(final Player aiPlayer, SpellAbility sa, boolean mandatory) {
        final Card host = sa.getHostCard();
        final Player activator = sa.getActivatingPlayer();
        final Game game = host.getGame();
        final String sourceName = ComputerUtilAbility.getAbilitySourceName(sa);

        
        // ////
        // Targeting
        if (sa.usesTargeting()) {
            sa.resetTargets();

            CardCollection list = new CardCollection(CardUtil.getValidCardsToTarget(sa.getTargetRestrictions(), sa));

            list = CardLists.filter(list, Predicates.not(CardPredicates.hasSVar("RemAIDeck")));
            //Nothing to target
            if (list.isEmpty()) {
            	return false;
            }
            
            // Saheeli Rai + Felidar Guardian combo support
            if ("Saheeli Rai".equals(sourceName)) {
                CardCollection felidarGuardian = CardLists.filter(list, CardPredicates.nameEquals("Felidar Guardian"));
                if (felidarGuardian.size() > 0) {
                    // can copy a Felidar Guardian and combo off, so let's do it
                    sa.getTargets().add(felidarGuardian.get(0));
                    return true;
                }
            }

            // target loop
            while (sa.canAddMoreTarget()) {
                if (list.isEmpty()) {
                    if (!sa.isTargetNumberValid() || (sa.getTargets().getNumTargeted() == 0)) {
                        sa.resetTargets();
                        return false;
                    } else {
                        // TODO is this good enough? for up to amounts?
                        break;
                    }
                }

                list = CardLists.filter(list, new Predicate<Card>() {
                    @Override
                    public boolean apply(final Card c) {
                        return !c.getType().isLegendary() || !c.getController().equals(aiPlayer);
                    }
                });
                Card choice;
                if (!CardLists.filter(list, Presets.CREATURES).isEmpty()) {
                    if (sa.hasParam("TargetingPlayer")) {
                        choice = ComputerUtilCard.getWorstCreatureAI(list);
                    } else {
                        choice = ComputerUtilCard.getBestCreatureAI(list);
                    }
                } else {
                    choice = ComputerUtilCard.getMostExpensivePermanentAI(list, sa, true);
                }

                if (choice == null) { // can't find anything left
                    if (!sa.isTargetNumberValid() || (sa.getTargets().getNumTargeted() == 0)) {
                        sa.resetTargets();
                        return false;
                    } else {
                        // TODO is this good enough? for up to amounts?
                        break;
                    }
                }
                list.remove(choice);
                sa.getTargets().add(choice);
            }
        } else if (sa.hasParam("Choices")) {
            // only check for options, does not select there
            CardCollectionView choices = game.getCardsIn(ZoneType.Battlefield);
            choices = CardLists.getValidCards(choices, sa.getParam("Choices"), activator, host);
            Collection<Card> betterChoices = getBetterOptions(aiPlayer, sa, choices, !mandatory);
            if (betterChoices.isEmpty()) {
                return mandatory;
            }
        } else {
            // if no targeting, it should always be ok
        }

        return true;
    }
    
    /* (non-Javadoc)
     * @see forge.card.ability.SpellAbilityAi#confirmAction(forge.game.player.Player, forge.card.spellability.SpellAbility, forge.game.player.PlayerActionConfirmMode, java.lang.String)
     */
    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message) {
        //TODO: add logic here
        return true;
    }
    
    /* (non-Javadoc)
     * @see forge.card.ability.SpellAbilityAi#chooseSingleCard(forge.game.player.Player, forge.card.spellability.SpellAbility, java.util.List, boolean)
     */
    @Override
    public Card chooseSingleCard(Player ai, SpellAbility sa, Iterable<Card> options, boolean isOptional, Player targetedPlayer) {
        // Select a card to attach to
        CardCollection betterOptions = getBetterOptions(ai, sa, options, isOptional);
        if (!betterOptions.isEmpty()) {
            options = betterOptions;
        }
        return ComputerUtilCard.getBestAI(options);
    }
    
    private CardCollection getBetterOptions(Player ai, SpellAbility sa, Iterable<Card> options, boolean isOptional) {
        final Card host = sa.getHostCard();
        final Player ctrl = host.getController();
        final String filter = "Permanent.YouDontCtrl,Permanent.nonLegendary";
        // TODO add filter to not select Legendary from Other Player when ai already have a Legendary with that name
        return CardLists.getValidCards(options, filter.split(","), ctrl, host, sa);
    }

    @Override
    protected Player chooseSinglePlayer(Player ai, SpellAbility sa, Iterable<Player> options) {
        final List<Card> cards = new PlayerCollection(options).getCreaturesInPlay();
        Card chosen = ComputerUtilCard.getBestCreatureAI(cards);
        return chosen != null ? chosen.getController() : Iterables.getFirst(options, null);
    }

}
