package forge.game.combat;

import com.google.common.primitives.Ints;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GlobalRuleChange;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import forge.util.maps.LinkedHashMapToAmount;
import forge.util.maps.MapToAmount;
import forge.util.maps.MapToAmountUtil;

import java.util.Map;
import java.util.Map.Entry;

public class GlobalAttackRestrictions {

    private final int max;
    private final MapToAmount<GameEntity> defenderMax;
    private final PlayerCollection mustBeAttackedByEachOpp;
    private GlobalAttackRestrictions(final int max, final MapToAmount<GameEntity> defenderMax, PlayerCollection mustBeAttackedByEachOpp) {
        this.max = max;
        this.defenderMax = defenderMax;
        this.mustBeAttackedByEachOpp = mustBeAttackedByEachOpp;
    }

    public int getMax() {
        return max;
    }
    public MapToAmount<GameEntity> getDefenderMax() {
        return defenderMax;
    }

    public boolean isLegal(final Map<Card, GameEntity> attackers, final CardCollection possibleAttackers) {
        return !getViolations(attackers, possibleAttackers,true).isViolated();
    }

    public GlobalAttackRestrictionViolations getViolations(final Map<Card, GameEntity> attackers, final CardCollection possibleAttackers) {
        return getViolations(attackers, possibleAttackers,false);
    }
    private GlobalAttackRestrictionViolations getViolations(final Map<Card, GameEntity> attackers, final CardCollection possibleAttackers, final boolean returnQuickly) {
        final int nTooMany = max < 0 ? 0 : attackers.size() - max;
        if (returnQuickly && nTooMany > 0) {
            return new GlobalAttackRestrictionViolations(nTooMany, MapToAmountUtil.<GameEntity>emptyMap(), MapToAmountUtil.<GameEntity>emptyMap());
        }

        final MapToAmount<GameEntity> defenderTooMany = new LinkedHashMapToAmount<GameEntity>(defenderMax.size());
        outer: for (final GameEntity defender : attackers.values()) {
            final Integer max = defenderMax.get(defender);
            if (max == null) {
                continue;
            }
            if (returnQuickly && max.intValue() == 0) {
                // there's at least one creature attacking this defender
                defenderTooMany.put(defender, 1);
                break;
            }
            int count = 0;
            for (final Entry<Card, GameEntity> attDef : attackers.entrySet()) {
                if (attDef.getValue() == defender) {
                    count++;
                    if (returnQuickly && count > max.intValue()) {
                        defenderTooMany.put(defender, count - max.intValue());
                        break outer;
                    }
                }
            }
            final int nDefTooMany = count - max.intValue();
            if (nDefTooMany > 0) {
                // Too many attackers to one defender!
                defenderTooMany.put(defender, nDefTooMany);
            }
        }

        final MapToAmount<GameEntity> defenderTooFew = new LinkedHashMapToAmount<GameEntity>(defenderMax.size());
        for (final GameEntity mandatoryDef : mustBeAttackedByEachOpp) {
            // check to ensure that this defender can even legally be attacked in the first place
            boolean canAttackThisDef = false;
            for (Card c : possibleAttackers) {
                if (CombatUtil.canAttack(c, mandatoryDef) && null == CombatUtil.getAttackCost(c.getGame(), c, mandatoryDef)) {
                    canAttackThisDef = true;
                    break;
                }
            }
            if (!canAttackThisDef) {
                continue;
            }

            boolean isAttacked = false;
            for (final GameEntity defender : attackers.values()) {
                if (defender.equals(mandatoryDef)) {
                    isAttacked = true;
                    break;
                } else if (defender instanceof Card && ((Card)defender).getController().equals(mandatoryDef)) {
                    isAttacked = true;
                    break;
                }
            }
            if (!isAttacked) {
                defenderTooFew.add(mandatoryDef);
            }
        }

        return new GlobalAttackRestrictionViolations(nTooMany, defenderTooMany, defenderTooFew);
    }

    final class GlobalAttackRestrictionViolations {
        private final boolean isViolated;
        private final int globalTooMany;
        private final MapToAmount<GameEntity> defenderTooMany;
        private final MapToAmount<GameEntity> defenderTooFew;

        public GlobalAttackRestrictionViolations(final int globalTooMany, final MapToAmount<GameEntity> defenderTooMany, final MapToAmount<GameEntity> defenderTooFew) {
            this.isViolated = globalTooMany > 0 || !defenderTooMany.isEmpty() || !defenderTooFew.isEmpty();
            this.globalTooMany = globalTooMany;
            this.defenderTooMany = defenderTooMany;
            this.defenderTooFew = defenderTooFew;
        }
        public boolean isViolated() {
            return isViolated;
        }
        public int getGlobalTooMany() {
            return globalTooMany;
        }
        public MapToAmount<GameEntity> getDefenderTooFew() {
            return defenderTooFew;
        }
        public MapToAmount<GameEntity> getDefenderTooMany() {
            return defenderTooMany;
        }
    }

    /**
     * <p>
     * Get all global restrictions (applying to all creatures).
     * </p>
     * 
     * @param attackingPlayer
     *            the {@link Player} declaring attack.
     * @return a {@link GlobalAttackRestrictions} object.
     */
    public static GlobalAttackRestrictions getGlobalRestrictions(final Player attackingPlayer, final FCollectionView<GameEntity> possibleDefenders) {
        int max = -1;
        final MapToAmount<GameEntity> defenderMax = new LinkedHashMapToAmount<GameEntity>(possibleDefenders.size());
        final PlayerCollection mustBeAttacked = new PlayerCollection();
        final Game game = attackingPlayer.getGame();

        /* if (game.getStaticEffects().getGlobalRuleChange(GlobalRuleChange.onlyOneAttackerATurn)) {
            if (attackingPlayer.getAttackedWithCreatureThisTurn()) {
                max = 0;
            } else {
                max = 1;
            }
        } */

        if (max == -1 && game.getStaticEffects().getGlobalRuleChange(GlobalRuleChange.onlyOneAttackerACombat)) {
            max = 1;
        }

        if (max == -1) {
            for (final Card card : game.getCardsIn(ZoneType.Battlefield)) {
                if (card.hasKeyword("No more than two creatures can attack each combat.")) {
                    max = 2;
                    break;
                }
            }
        }

        for (final Card card : game.getCardsIn(ZoneType.Battlefield)) {
            if (card.hasKeyword("Each opponent must attack you or a planeswalker you control with at least one creature each combat if able.")) {
                if (attackingPlayer.isOpponentOf(card.getController())) {
                    mustBeAttacked.add(card.getController());
                }
            }
        }

        for (final GameEntity defender : possibleDefenders) {
            final int defMax = getMaxAttackTo(defender);
            if (defMax != -1) {
                defenderMax.add(defender, defMax);
            }
        }
        if (defenderMax.size() == possibleDefenders.size()) {
            // maximum on each defender, global maximum is sum of these
            max = Ints.min(max, defenderMax.countAll());
        }

        return new GlobalAttackRestrictions(max, defenderMax, mustBeAttacked);
    }

    /**
     * <p>
     * Get the maximum number of creatures allowed to attack a certain defender.
     * </p>
     * 
     * @param defender
     *            the defending {@link GameEntity}.
     * @return the maximum number of creatures, or -1 if it is unlimited.
     */
    private static int getMaxAttackTo(final GameEntity defender) {
        if (defender instanceof Player) {
            for (final Card card : ((Player) defender).getCardsIn(ZoneType.Battlefield)) {
                if (card.hasKeyword("No more than one creature can attack you each combat.")) {
                    return 1;
                } else if (card.hasKeyword("No more than two creatures can attack you each combat.")) {
                    return 2;
                }
            }
        }

        return -1;
    }
}
