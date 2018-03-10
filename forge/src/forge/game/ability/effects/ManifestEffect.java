package forge.game.ability.effects;

import com.google.common.collect.Sets;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GlobalRuleChange;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.card.CardUtil;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

public class ManifestEffect extends SpellAbilityEffect {
    @Override
    public void resolve(SpellAbility sa) {
        final Card source = sa.getHostCard();
        final Game game = source.getGame();
        // Usually a number leaving possibility for X, Sacrifice X land: Manifest X creatures.
        final int amount = sa.hasParam("Amount") ? AbilityUtils.calculateAmount(source,
                sa.getParam("Amount"), sa) : 1;
        // Most commonly "defined" is Top of Library
        final String defined = sa.hasParam("Defined") ? sa.getParam("Defined") : "TopOfLibrary";

        for (final Player p : getTargetPlayers(sa, "DefinedPlayer")) {
            if (sa.usesTargeting() || p.canBeTargetedBy(sa)) {
                CardCollection tgtCards;
                if ("TopOfLibrary".equals(defined)) {
                    tgtCards = p.getTopXCardsFromLibrary(amount);
                } else {
                    tgtCards = getTargetCards(sa);
                }

                if (sa.hasParam("Shuffle")) {
                    CardLists.shuffle(tgtCards);
                }

                for(Card c : tgtCards) {
                    //check if lki would be a land entering the battlefield
                    if (game.getStaticEffects().getGlobalRuleChange(GlobalRuleChange.noLandBattlefield)) {
                        Card lki = CardUtil.getLKICopy(c);
                        lki.setState(CardStateName.FaceDown, false);
                        lki.setManifested(true);
                        lki.setLastKnownZone(p.getZone(ZoneType.Battlefield));
                        CardCollection preList = new CardCollection(lki);
                        game.getAction().checkStaticAbilities(false, Sets.newHashSet(lki), preList);
                        if (lki.isLand()) {
                            continue;
                        }
                    }
                    Card rem = c.manifest(p, sa);
                    if (sa.hasParam("RememberManifested") && rem != null) {
                        source.addRemembered(rem);
                    }
                }
            }
        }
    }
}
