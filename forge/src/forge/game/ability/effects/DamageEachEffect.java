package forge.game.ability.effects;

import forge.game.GameObject;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardDamageMap;
import forge.game.card.CardFactoryUtil;
import forge.game.card.CardLists;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

import java.util.List;

public class DamageEachEffect extends DamageBaseEffect {

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellEffect#getStackDescription(java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    protected String getStackDescription(SpellAbility sa) {
        final StringBuilder sb = new StringBuilder();
        final String damage = sa.getParam("NumDmg");
        final int iDmg = AbilityUtils.calculateAmount(sa.getHostCard(), damage, sa);

        String desc = sa.getParam("ValidCards");
        if (sa.hasParam("ValidDescription")) {
            desc = sa.getParam("ValidDescription");
        }

        String dmg = "";
        if (sa.hasParam("DamageDesc")) {
            dmg = sa.getParam("DamageDesc");
        } else {
            dmg += iDmg + " damage";
        }

        if (sa.hasParam("StackDescription")) {
            sb.append(sa.getParam("StackDescription"));
        } else {
            sb.append("Each ").append(desc).append(" deals ").append(dmg).append(" to ");
            for (final Player p : getTargetPlayers(sa)) {
                sb.append(p);
            }
            if (sa.hasParam("DefinedCards")) {
                if (sa.getParam("DefinedCards").equals("Self")) {
                    sb.append(" itself");
                }
            }
        }
        sb.append(".");
        return sb.toString();
    }


    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellEffect#resolve(java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    public void resolve(SpellAbility sa) {
        final Card card = sa.getHostCard();

        FCollectionView<Card> sources = card.getGame().getCardsIn(ZoneType.Battlefield);
        if (sa.hasParam("ValidCards")) {
            sources = CardLists.getValidCards(sources, sa.getParam("ValidCards"), card.getController(), card);
        }

        final List<GameObject> tgts = getTargets(sa, "DefinedPlayers");

        final boolean targeted = sa.usesTargeting();
        CardDamageMap damageMap = new CardDamageMap();
        CardDamageMap preventMap = new CardDamageMap();

        for (final Object o : tgts) {
            for (final Card source : sources) {
                final Card sourceLKI = source.getGame().getChangeZoneLKIInfo(source);

                final int dmg = CardFactoryUtil.xCount(source, sa.getSVar("X"));
                
                // System.out.println(source+" deals "+dmg+" damage to "+o.toString());
                if (o instanceof Card) {
                    final Card c = (Card) o;
                    if (c.isInPlay() && (!targeted || c.canBeTargetedBy(sa))) {
                        c.addDamage(dmg, sourceLKI, damageMap, preventMap);
                    }

                } else if (o instanceof Player) {
                    final Player p = (Player) o;
                    if (!targeted || p.canBeTargetedBy(sa)) {
                        p.addDamage(dmg, sourceLKI, damageMap, preventMap);
                    }
                }
            }
        }

        if (sa.hasParam("DefinedCards")) {
            if (sa.getParam("DefinedCards").equals("Self")) {
                for (final Card source : sources) {
                    final Card sourceLKI = source.getGame().getChangeZoneLKIInfo(source);

                    final int dmg = CardFactoryUtil.xCount(source, card.getSVar("X"));
                    // System.out.println(source+" deals "+dmg+" damage to "+source);
                    source.addDamage(dmg, sourceLKI, damageMap, preventMap);
                }
            }
            if (sa.getParam("DefinedCards").equals("Remembered")) {
                for (final Card source : sources) {
                    final int dmg = CardFactoryUtil.xCount(source, card.getSVar("X"));
                    final Card sourceLKI = source.getGame().getChangeZoneLKIInfo(source);

                    for (final Object o : sa.getHostCard().getRemembered()) {
                        if (o instanceof Card) {
                            Card rememberedcard = (Card) o;
                            // System.out.println(source + " deals " + dmg + " damage to " + rememberedcard);
                            rememberedcard.addDamage(dmg, sourceLKI, damageMap, preventMap);
                        }
                    }
                }
            }
        }
        preventMap.triggerPreventDamage(false);

        damageMap.dealLifelinkDamage();

        replaceDying(sa);
    }
}