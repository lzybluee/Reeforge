package forge.game.ability.effects;

import com.google.common.collect.Iterables;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardDamageMap;
import forge.game.card.CardUtil;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.Lang;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class DamageDealEffect extends DamageBaseEffect {

    /* (non-Javadoc)
     * @see forge.game.ability.SpellAbilityEffect#getStackDescription(forge.game.spellability.SpellAbility)
     */
    @Override
    protected String getStackDescription(SpellAbility sa) {
        // when damageStackDescription is called, just build exactly what is happening
        final StringBuilder sb = new StringBuilder();
        final String damage = sa.getParam("NumDmg");
        final int dmg = AbilityUtils.calculateAmount(sa.getHostCard(), damage, sa);

        List<GameObject> tgts = getTargets(sa);
        if (tgts.isEmpty()) 
            return "";

        final List<Card> definedSources = AbilityUtils.getDefinedCards(sa.getHostCard(), sa.getParam("DamageSource"), sa);

        if (!definedSources.isEmpty() && definedSources.get(0) != sa.getHostCard()) {
            sb.append(definedSources.get(0).toString()).append(" deals");
        } else {
            sb.append("Deals");
        }

        sb.append(" ").append(dmg).append(" damage ");

        if (sa.hasParam("DivideEvenly")) {
            sb.append("divided evenly (rounded down) ");
        } else if (sa.hasParam("DividedAsYouChoose")) {
            sb.append("divided as you choose ");
        }
        sb.append("to ").append(Lang.joinHomogenous(tgts));

        if (sa.hasParam("Radiance")) {
            sb.append(" and each other ").append(sa.getParam("ValidTgts"))
                    .append(" that shares a color with ");
            if (tgts.size() > 1) {
                sb.append("them");
            } else {
                sb.append("it");
            }
        }

        sb.append(". ");
        return sb.toString();
    }

    /* (non-Javadoc)
     * @see forge.game.ability.SpellAbilityEffect#resolve(forge.game.spellability.SpellAbility)
     */
    @Override
    public void resolve(SpellAbility sa) {
        final Card hostCard = sa.getHostCard();
        final Game game = hostCard.getGame();

        final String damage = sa.getParam("NumDmg");
        int dmg = AbilityUtils.calculateAmount(sa.getHostCard(), damage, sa);

        final boolean noPrevention = sa.hasParam("NoPrevention");
        final boolean combatDmg = "true".equalsIgnoreCase(sa.getParamOrDefault("CombatDamage", "false"));
        final boolean removeDamage = sa.hasParam("Remove");
        final boolean divideOnResolution = sa.hasParam("DividerOnResolution");

        List<GameObject> tgts = getTargets(sa);
        if (sa.hasParam("OptionalDecider")) {
            Player decider = Iterables.getFirst(AbilityUtils.getDefinedPlayers(sa.getHostCard(), sa.getParam("OptionalDecider"), sa), null);
            if (decider != null && !decider.getController().confirmAction(sa, null, "Do you want to deal " + dmg + " damage to " + tgts + " ?")) {
                return;
            }
        }

        // Right now for Fireball, maybe later for other stuff
        if (sa.hasParam("DivideEvenly")) {
            String evenly = sa.getParam("DivideEvenly");
            if (evenly.equals("RoundedDown")) {
                dmg = tgts.isEmpty() ? 0 : dmg / tgts.size();
            }
        }

        final boolean targeted = (sa.usesTargeting());

        if (sa.hasParam("Radiance") && targeted) {
            Card origin = null;
            for (int i = 0; i < tgts.size(); i++) {
                if (tgts.get(i) instanceof Card) {
                    origin = (Card) tgts.get(i);
                    break;
                }
            }
            // Can't radiate from a player
            if (origin != null) {
                for (final Card c : CardUtil.getRadiance(sa.getHostCard(), origin,
                        sa.getParam("ValidTgts").split(","))) {
                    tgts.add(c);
                }
            }
        }

        final boolean remember = sa.hasParam("RememberDamaged");


        // make a new damage map, combat damage will be applied later into combat map
        CardDamageMap damageMap = new CardDamageMap();
        CardDamageMap preventMap = new CardDamageMap();

        
        final List<Card> definedSources = AbilityUtils.getDefinedCards(sa.getHostCard(), sa.getParam("DamageSource"), sa);
        if (definedSources == null || definedSources.isEmpty()) {
            return;
        }
        
        for (Card source : definedSources) {
            final Card sourceLKI = sa.getHostCard().getGame().getChangeZoneLKIInfo(source);
        
            if (divideOnResolution) {
                // Dividing Damage up to multiple targets using combat damage box
                // Currently only used for Master of the Wild Hunt
                List<Player> players = AbilityUtils.getDefinedPlayers(sa.getHostCard(), sa.getParam("DividerOnResolution"), sa);
                if (players.isEmpty()) {
                    return;
                }
    
                CardCollection assigneeCards = new CardCollection();
                // Do we have a way of doing this in a better fashion?
                for (GameObject obj : tgts) {
                    if (obj instanceof Card) {
                        assigneeCards.add((Card)obj);
                    }
                }
    
                Player assigningPlayer = players.get(0);
                Map<Card, Integer> map = assigningPlayer.getController().assignCombatDamage(sourceLKI, assigneeCards, dmg, null, true);
                for (Entry<Card, Integer> dt : map.entrySet()) {
                    dt.getKey().addDamage(dt.getValue(), sourceLKI, damageMap, preventMap);
                }
    
                // transport combat damage back into combat damage map
                if (combatDmg) {
                    game.getCombat().getDamageMap().putAll(damageMap);
                } else {
                    preventMap.triggerPreventDamage(false);
                    // non combat damage cause lifegain there
                    damageMap.triggerDamageDoneOnce(false);
                    replaceDying(sa);
                }
                return;
            }

            if (sa.hasParam("RelativeTarget")) {
                tgts = AbilityUtils.getDefinedObjects(source, sa.getParam("Defined"), sa);
            }

            for (final Object o : tgts) {
                dmg = (sa.usesTargeting() && sa.hasParam("DividedAsYouChoose")) ? sa.getTargetRestrictions().getDividedValue(o) : dmg;
                if (o instanceof Card) {
                    final Card c = (Card) o;
                    if (c.isInPlay() && (!targeted || c.canBeTargetedBy(sa))) {
                        if (removeDamage) {
                            c.setDamage(0);
                            c.setHasBeenDealtDeathtouchDamage(false);
                            c.clearAssignedDamage();
                        }
                        else {
                            c.addDamage(dmg, sourceLKI, combatDmg, noPrevention, damageMap, preventMap);
                        }
                    }
                } else if (o instanceof Player) {
                    final Player p = (Player) o;
                    if (!targeted || p.canBeTargetedBy(sa)) {
                        p.addDamage(dmg, sourceLKI, combatDmg, noPrevention, damageMap, preventMap);
                    }
                }
            }
    
            if (remember) {
                source.addRemembered(damageMap.row(sourceLKI).keySet());
            }
        }
        // transport combat damage back into combat damage map
        if (combatDmg) {
            game.getCombat().getDamageMap().putAll(damageMap);
        } else {
            preventMap.triggerPreventDamage(false);
            // non combat damage cause lifegain there
            damageMap.triggerDamageDoneOnce(false);
        }

        replaceDying(sa);
    }
}
