package forge.game.ability.effects;

import forge.game.Game;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardFactoryUtil;
import forge.game.replacement.ReplacementResult;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.SpellPermanent;
import forge.game.trigger.TriggerType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class CounterEffect extends SpellAbilityEffect {
    @Override
    protected String getStackDescription(SpellAbility sa) {
        final Game game = sa.getActivatingPlayer().getGame();

        final StringBuilder sb = new StringBuilder();
        final List<SpellAbility> sas;

        if (sa.hasParam("AllType")) {
            sas = Lists.newArrayList();
            for (SpellAbilityStackInstance si : game.getStack()) {
                SpellAbility spell = si.getSpellAbility(true);
                if (sa.getParam("AllType").equals("Spell") && !spell.isSpell()) {
                    continue;
                }
                if (sa.hasParam("AllValid")) {
                    if (!spell.getHostCard().isValid(sa.getParam("AllValid"), sa.getActivatingPlayer(), sa.getHostCard(), sa)) {
                        continue;
                    }
                }
                sas.add(spell);
            }
        } else {
            sas = getTargetSpells(sa);
        }

        sb.append("countering");

        boolean isAbility = false;
        for (final SpellAbility tgtSA : sas) {
            sb.append(" ");
            sb.append(tgtSA.getHostCard());
            isAbility = tgtSA.isAbility();
            if (isAbility) {
                sb.append("'s ability");
            }
        }

        if (isAbility && sa.hasParam("DestroyPermanent")) {
            sb.append(" and destroy it");
        }

        sb.append(".");
        return sb.toString();
    } // end counterStackDescription

    @Override
    public void resolve(SpellAbility sa) {
        final Game game = sa.getActivatingPlayer().getGame();
        // TODO Before this resolves we should see if any of our targets are
        // still on the stack
        final List<SpellAbility> sas;

        if (sa.hasParam("AllType")) {
            sas = Lists.newArrayList();
            for (SpellAbilityStackInstance si : game.getStack()) {
                SpellAbility spell = si.getSpellAbility(true);
                if (sa.getParam("AllType").equals("Spell") && !spell.isSpell()) {
                    continue;
                }
                if (sa.hasParam("AllValid")) {
                    if (!spell.getHostCard().isValid(sa.getParam("AllValid"), sa.getActivatingPlayer(), sa.getHostCard(), sa)) {
                        continue;
                    }
                }
                sas.add(spell);
            }
        } else {
            sas = getTargetSpells(sa);
        }

        if (sa.hasParam("ForgetOtherTargets")) {
            if (sa.getParam("ForgetOtherTargets").equals("True")) {
                sa.getHostCard().clearRemembered();
            }
        }

        for (final SpellAbility tgtSA : sas) {
            final Card tgtSACard = tgtSA.getHostCard();
            // should remember even that spell cannot be countered, e.g. Dovescape
            if (sa.hasParam("RememberCounteredCMC")) {
                sa.getHostCard().addRemembered(Integer.valueOf(tgtSACard.getCMC()));
            }

            if (tgtSA.isSpell() && !CardFactoryUtil.isCounterableBy(tgtSACard, sa)) {
                continue;
            }

            final SpellAbilityStackInstance si = game.getStack().getInstanceMatchingSpellAbilityID(tgtSA);
            if (si == null) {
                continue;
            }

            if (sa.hasParam("CounterNoManaSpell") && tgtSA.getTotalManaSpent() != 0) {
                continue;
            }

            removeFromStack(tgtSA, sa, si);

            // Destroy Permanent may be able to be turned into a SubAbility
            if (tgtSA.isAbility() && sa.hasParam("DestroyPermanent")) {
                game.getAction().destroy(tgtSACard, sa);
            }

            if (sa.hasParam("RememberCountered")) {
                if (sa.getParam("RememberCountered").equals("True")) {
                    sa.getHostCard().addRemembered(tgtSACard);
                }
            }

            if (sa.hasParam("RememberSplicedOntoCounteredSpell")) {
                if (tgtSA.getSplicedCards() != null) {
                    sa.getHostCard().addRemembered(tgtSA.getSplicedCards());
                }
            }
        }
    } // end counterResolve

    /**
     * <p>
     * removeFromStack.
     * </p>
     * 
     * @param tgtSA
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param srcSA
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param si
     *            a {@link forge.game.spellability.SpellAbilityStackInstance}
     *            object.
     */
    private static void removeFromStack(final SpellAbility tgtSA,
            final SpellAbility srcSA, final SpellAbilityStackInstance si) {
        final Game game = tgtSA.getActivatingPlayer().getGame();
        // Run any applicable replacement effects. 
        final Map<String, Object> repParams = Maps.newHashMap();
        repParams.put("Event", "Counter");
        repParams.put("TgtSA", tgtSA);
        repParams.put("Affected", tgtSA.getHostCard());
        repParams.put("Cause", srcSA.getHostCard());
        if (game.getReplacementHandler().run(repParams) != ReplacementResult.NotReplaced) {
            return;
        }
        game.getStack().remove(si);

        String destination =  srcSA.hasParam("Destination") ? srcSA.getParam("Destination") : tgtSA.isAftermath() ? "Exile" : "Graveyard";
        if (srcSA.hasParam("DestinationChoice")) {//Hinder
            List<String> pos = Arrays.asList(srcSA.getParam("DestinationChoice").split(","));
            destination = srcSA.getActivatingPlayer().getController().chooseSomeType("a destination to remove", tgtSA, pos, null);
        }
        if (tgtSA.isAbility()) {
            // For Ability-targeted counterspells - do not move it anywhere,
            // even if Destination$ is specified.
        } else if (tgtSA.isFlashBackAbility() || tgtSA.isAftermath())  {
            game.getAction().exile(tgtSA.getHostCard(), srcSA);
        } else if (destination.equals("Graveyard")) {
            game.getAction().moveToGraveyard(tgtSA.getHostCard(), srcSA);
        } else if (destination.equals("Exile")) {
            game.getAction().exile(tgtSA.getHostCard(), srcSA);
        } else if (destination.equals("TopOfLibrary")) {
            game.getAction().moveToLibrary(tgtSA.getHostCard(), srcSA);
        } else if (destination.equals("Hand")) {
            game.getAction().moveToHand(tgtSA.getHostCard(), srcSA);
        } else if (destination.equals("Battlefield")) {
            if (tgtSA instanceof SpellPermanent) {
                Card c = tgtSA.getHostCard();
                System.out.println(c + " is SpellPermanent");
                c.setController(srcSA.getActivatingPlayer(), 0);
                game.getAction().moveToPlay(c, srcSA.getActivatingPlayer(), srcSA);
            } else {
                Card c = game.getAction().moveToPlay(tgtSA.getHostCard(), srcSA.getActivatingPlayer(), srcSA);
                c.setController(srcSA.getActivatingPlayer(), 0);
            }
        } else if (destination.equals("BottomOfLibrary")) {
            game.getAction().moveToBottomOfLibrary(tgtSA.getHostCard(), srcSA);
        } else if (destination.equals("ShuffleIntoLibrary")) {
            game.getAction().moveToBottomOfLibrary(tgtSA.getHostCard(), srcSA);
            tgtSA.getHostCard().getController().shuffle(srcSA);
        } else {
            throw new IllegalArgumentException("AbilityFactory_CounterMagic: Invalid Destination argument for card "
                    + srcSA.getHostCard().getName());
        }
        // Run triggers
        final Map<String, Object> runParams = Maps.newHashMap();
        runParams.put("Player", tgtSA.getActivatingPlayer());
        runParams.put("Card", tgtSA.getHostCard());
        runParams.put("Cause", srcSA.getHostCard());
        runParams.put("CounteredSA", tgtSA);
        srcSA.getActivatingPlayer().getGame().getTriggerHandler().runTrigger(TriggerType.Countered, runParams, false);
        

        if (!tgtSA.isAbility()) {
            System.out.println("Send countered spell to " + destination);
        }
    }

}
