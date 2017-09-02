/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.game.combat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import forge.game.Game;
import forge.game.spellability.SpellAbilityStackInstance;
import org.apache.commons.lang3.tuple.Pair;

import com.google.common.base.Function;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Table;

import forge.game.GameEntity;
import forge.game.GameLogEntryType;
import forge.game.GameObjectMap;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardDamageMap;
import forge.game.player.Player;
import forge.game.trigger.TriggerType;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;

/**
 * <p>
 * Combat class.
 * </p>
 * 
 * @author Forge
 * @version $Id: Combat.java 35257 2017-08-27 18:17:21Z Agetian $
 */
public class Combat {
    private final Player playerWhoAttacks;
    private final AttackConstraints attackConstraints;
    // Defenders, as they are attacked by hostile forces
    private final FCollection<GameEntity> attackableEntries = new FCollection<GameEntity>();

    // Keyed by attackable defender (player or planeswalker)
    private final Multimap<GameEntity, AttackingBand> attackedByBands = Multimaps.synchronizedMultimap(ArrayListMultimap.<GameEntity, AttackingBand>create());
    private final Multimap<AttackingBand, Card> blockedBands = Multimaps.synchronizedMultimap(ArrayListMultimap.<AttackingBand, Card>create());

    private final Map<Card, Integer> defendingDamageMap = Maps.newHashMap();

    private Map<Card, CardCollection> attackersOrderedForDamageAssignment = Maps.newHashMap();
    private Map<Card, CardCollection> blockersOrderedForDamageAssignment = Maps.newHashMap();
    private Map<GameEntity, CombatLki> lkiCache = Maps.newHashMap();
    private CardDamageMap dealtDamageTo = new CardDamageMap();

    // List holds creatures who have dealt 1st strike damage to disallow them deal damage on regular basis (unless they have double-strike KW) 
    private CardCollection combatantsThatDealtFirstStrikeDamage = new CardCollection();

    public Combat(final Player attacker) {
        playerWhoAttacks = attacker;

        // Create keys for all possible attack targets
        for (final GameEntity defender : CombatUtil.getAllPossibleDefenders(playerWhoAttacks)) {
            attackableEntries.add(defender);
        }

        attackConstraints = new AttackConstraints(this);
    }

    public Combat(Combat combat, GameObjectMap map) {
        playerWhoAttacks = map.map(combat.playerWhoAttacks);
        for (GameEntity entry : combat.attackableEntries) {
            attackableEntries.add(map.map(entry));
        }

        HashMap<AttackingBand, AttackingBand> bandsMap = new HashMap<>();
        for (Entry<GameEntity, AttackingBand> entry : combat.attackedByBands.entries()) {
            AttackingBand origBand = entry.getValue();
            ArrayList<Card> attackers = new ArrayList<Card>();
            for (Card c : origBand.getAttackers()) {
                attackers.add(map.map(c));
            }
            AttackingBand newBand = new AttackingBand(attackers);
            Boolean blocked = entry.getValue().isBlocked();
            if (blocked != null) {
                newBand.setBlocked(blocked);
            }
            bandsMap.put(origBand, newBand);
            attackedByBands.put(map.map(entry.getKey()), newBand);
        }
        for (Entry<AttackingBand, Card> entry : combat.blockedBands.entries()) {
            blockedBands.put(bandsMap.get(entry.getKey()), map.map(entry.getValue()));
        }
        for (Entry<Card, Integer> entry : combat.defendingDamageMap.entrySet()) {
            defendingDamageMap.put(map.map(entry.getKey()), entry.getValue());
        }
        
        for (Entry<Card, CardCollection> entry : combat.attackersOrderedForDamageAssignment.entrySet()) {
            attackersOrderedForDamageAssignment.put(map.map(entry.getKey()), map.mapCollection(entry.getValue()));
        }
        for (Entry<Card, CardCollection> entry : combat.blockersOrderedForDamageAssignment.entrySet()) {
            blockersOrderedForDamageAssignment.put(map.map(entry.getKey()), map.mapCollection(entry.getValue()));
        }
        // Note: Doesn't currently set up lkiCache, since it's just a cache and not strictly needed...
        for (Table.Cell<Card, GameEntity, Integer> entry : combat.dealtDamageTo.cellSet()) {
            dealtDamageTo.put(map.map(entry.getRowKey()), map.map(entry.getColumnKey()), entry.getValue());
        }

        attackConstraints = new AttackConstraints(this);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (GameEntity defender : attackableEntries) {
            CardCollection attackers = getAttackersOf(defender);
            if (attackers.isEmpty()) {
                continue;
            }
            sb.append(defender);
            sb.append(" is being attacked by:\n");
            for (Card attacker : attackers) {
                sb.append("  ").append(attacker).append("\n");
                for (Card blocker : getBlockers(attacker)) {
                    sb.append("  ... blocked by: ").append(blocker).append("\n");
                }
            }
        }
        if (sb.length() == 0) {
            return "<no attacks>";
        }
        return sb.toString();
    }

    public void endCombat() {
        //backup attackers and blockers
        CardCollection attackers = getAttackers();
        CardCollection blockers = getAllBlockers();

        //clear all combat-related collections
        attackableEntries.clear();
        attackedByBands.clear();
        blockedBands.clear();
        defendingDamageMap.clear();
        attackersOrderedForDamageAssignment.clear();
        blockersOrderedForDamageAssignment.clear();
        lkiCache.clear();
        combatantsThatDealtFirstStrikeDamage.clear();

        //update view for all attackers and blockers
        for (Card c : attackers) {
            c.getDamageHistory().endCombat();
            c.updateAttackingForView();
        }
        for (Card c : blockers) {
            c.getDamageHistory().endCombat();
            c.updateBlockingForView();
        }
    }

    public final void clearAttackers() {
        for (final Card attacker : getAttackers()) {
            removeFromCombat(attacker);
        }
    }

    public final Player getAttackingPlayer() {
        return playerWhoAttacks;
    }

    public final AttackConstraints getAttackConstraints() {
        return attackConstraints;
    }
    public final FCollectionView<GameEntity> getDefenders() {
        return attackableEntries;
    }

    public final FCollection<GameEntity> getDefendersControlledBy(Player who) {
        FCollection<GameEntity> res = new FCollection<GameEntity>();
        for (GameEntity ge : attackableEntries) {
            // if defender is the player himself or his cards
            if (ge == who || ge instanceof Card && ((Card) ge).getController() == who) {
                res.add(ge);
            }
        }
        return res;
    }

    public final FCollectionView<Player> getDefendingPlayers() {
        return new FCollection<Player>(Iterables.filter(attackableEntries, Player.class));
    }

    public final CardCollection getDefendingPlaneswalkers() {
        return new CardCollection(Iterables.filter(attackableEntries, Card.class));
    }

    public final Map<Card, GameEntity> getAttackersAndDefenders() {
        return Maps.asMap(getAttackers().asSet(), new Function<Card, GameEntity>() {
            @Override
            public GameEntity apply(final Card attacker) {
                return getDefenderByAttacker(attacker);
            }
        });
    }

    public final List<AttackingBand> getAttackingBandsOf(GameEntity defender) {
        return Lists.newArrayList(attackedByBands.get(defender));
    }

    public final CardCollection getAttackersOf(GameEntity defender) {
        CardCollection result = new CardCollection();
        if (!attackedByBands.containsKey(defender))
            return result;
        for (AttackingBand v : attackedByBands.get(defender)) {
            result.addAll(v.getAttackers());
        }
        return result;
    }

    public final void addAttacker(final Card c, GameEntity defender) {
        addAttacker(c, defender, null);
    }

    public final void addAttacker(final Card c, GameEntity defender, AttackingBand band) {
        Collection<AttackingBand> attackersOfDefender = attackedByBands.get(defender);
        if (attackersOfDefender == null) {
            System.out.println("Trying to add Attacker " + c + " to missing defender " + defender);
            return;
        }

        // This is trying to fix the issue of an attacker existing in two bands at once
        AttackingBand existingBand = getBandOfAttacker(c);
        if (existingBand != null) {
            existingBand.removeAttacker(c);
        }

        if (band == null || !attackersOfDefender.contains(band)) {
            band = new AttackingBand(c);
            attackersOfDefender.add(band);
        }
        else {
            band.addAttacker(c);
        }
        c.updateAttackingForView();
    }

    public final GameEntity getDefenderByAttacker(final Card c) {
        return getDefenderByAttacker(getBandOfAttacker(c));
    }

    public final GameEntity getDefenderByAttacker(final AttackingBand c) {
        for (Entry<GameEntity, AttackingBand> e : attackedByBands.entries()) {
            if (e.getValue() == c) {
                return e.getKey();
            }
        }
        return null;
    }    

    public final Player getDefenderPlayerByAttacker(final Card c) {
        GameEntity defender = getDefenderByAttacker(c);

        // System.out.println(c.toString() + " attacks " + defender.toString());
        if (defender instanceof Player) {
            return (Player) defender;
        }

        // maybe attack on a controlled planeswalker?
        if (defender instanceof Card) {
            return ((Card) defender).getController();
        }
        return null;
    }

    // takes LKI into consideration, should use it at all times (though a single iteration over multimap seems faster)
    public final AttackingBand getBandOfAttacker(final Card c) {
        for (AttackingBand ab : attackedByBands.values()) {
            if (ab.contains(c)) {
                return ab;
            }
        }
        CombatLki lki = lkiCache.get(c); 
        return lki == null || !lki.isAttacker ? null : lki.getFirstBand();
    }
    
    public final AttackingBand getBandOfAttackerNotNull(final Card c) {
        AttackingBand band = getBandOfAttacker(c);
        if (band == null) {
            throw new NullPointerException("No band for attacker " + c);
        }
        return band;
    }

    public final List<AttackingBand> getAttackingBands() {
        return Lists.newArrayList(attackedByBands.values());
    } 
    
    /**
     * Checks if a card is attacking, returns true if the card was attacking when it left the battlefield
     */
    public final boolean isLKIAttacking(final Card c) {
        AttackingBand ab = getBandOfAttacker(c);
        return ab != null;
    }

    public boolean isAttacking(Card card, GameEntity defender) {
        AttackingBand ab = getBandOfAttacker(card);
        for (Entry<GameEntity, AttackingBand> ee : attackedByBands.entries()) {
            if (ee.getValue() == ab) {
                return ee.getKey() == defender;
            }
        }
        return false;
    }
  
    /**
     * Checks if a card is currently attacking, returns false if the card is not currently attacking, even if its LKI was.
     */
    public final boolean isAttacking(Card card) {
        for (AttackingBand ab : attackedByBands.values()) {
            if (ab.contains(card)) {
                return true;
            }
        }
        return false;
    }

    public final CardCollection getAttackers() {
        CardCollection result = new CardCollection();
        for (AttackingBand ab : attackedByBands.values()) {
            result.addAll(ab.getAttackers());
        }
        return result;
    }

    public final CardCollection getBlockers(final Card card) {
        // If requesting the ordered blocking list pass true, directly. 
        AttackingBand band = getBandOfAttacker(card);
        Collection<Card> blockers = blockedBands.get(band);
        return blockers == null ? new CardCollection() : new CardCollection(blockers);
    }

    public final boolean isBlocked(final Card attacker) {
        AttackingBand band = getBandOfAttacker(attacker);
        return band == null ? false : Boolean.TRUE.equals(band.isBlocked());
    }

    // Some cards in Alpha may UNBLOCK an attacker, so second parameter is not always-true
    public final void setBlocked(final Card attacker, boolean value) {
        getBandOfAttackerNotNull(attacker).setBlocked(value); // called by Curtain of Light, Dazzling Beauty, Trap Runner
    }

    public final void addBlocker(final Card attacker, final Card blocker) {
        final AttackingBand band = getBandOfAttackerNotNull(attacker);
        blockedBands.put(band, blocker);
        // If damage is already assigned, add this blocker as a "late entry"
        if (blockersOrderedForDamageAssignment.containsKey(attacker)) {
            addBlockerToDamageAssignmentOrder(attacker, blocker);
        }
        blocker.updateBlockingForView();
    }

    // remove blocked from specific attacker
    public final void removeBlockAssignment(final Card attacker, final Card blocker) {
        AttackingBand band = getBandOfAttackerNotNull(attacker);
        Collection<Card> cc = blockedBands.get(band);
        if (cc != null) {
            cc.remove(blocker);
        }
        blocker.updateBlockingForView();
    }

    // remove blocker from everywhere
    public final void undoBlockingAssignment(final Card blocker) {
        CardCollection toRemove = new CardCollection(blocker);
        blockedBands.values().removeAll(toRemove);
        blocker.updateBlockingForView();
    }

    public final CardCollection getAllBlockers() {
        CardCollection result = new CardCollection();
        for (Card blocker : blockedBands.values()) {
            if (!result.contains(blocker)) {
                result.add(blocker);
            }
        }
        return result;
    }

    public final CardCollection getBlockers(final AttackingBand band) {
        Collection<Card> blockers = null;
        if(blockersOrderedForDamageAssignment.isEmpty()) {
            blockers = blockedBands.get(band);
        } else {
            blockers = new CardCollection();
            for(Card attacker : band.getAttackers()) {
                Collection<Card> bandBlockers = blockersOrderedForDamageAssignment.get(attacker);
                if(bandBlockers == null)
                    continue;
                for(Card c : bandBlockers) {
                    if(!blockers.contains(c)) {
                        blockers.add(c);
                    }
                }
            }
        }
        return blockers == null ? new CardCollection() : new CardCollection(blockers);
    }

    public final CardCollection getAttackersBlockedBy(final Card blocker) {
        CardCollection blocked =  new CardCollection();
        for (Entry<AttackingBand, Card> s : blockedBands.entries()) {
            if (s.getValue().equals(blocker)) {
                blocked.addAll(s.getKey().getAttackers());
            }
        }
        return blocked;
    }

    public final FCollectionView<AttackingBand> getAttackingBandsBlockedBy(Card blocker) {
        FCollection<AttackingBand> bands = new FCollection<AttackingBand>();
        for (Entry<AttackingBand, Card> kv : blockedBands.entries()) {
            if (kv.getValue().equals(blocker)) {
                bands.add(kv.getKey());
            }
        }
        return bands;
    }

    public Player getDefendingPlayerRelatedTo(final Card source) {
        Card attacker = source;
        if (source.isAura()) {
            attacker = source.getEnchantingCard();
        }
        else if (source.isEquipment()) {
            attacker = source.getEquipping();
        }
        else if (source.isFortification()) {
            attacker = source.getFortifying();
        }

        // return the corresponding defender
        return getDefenderPlayerByAttacker(attacker);
    }

    /** If there are multiple blockers, the Attacker declares the Assignment Order */
    public void orderBlockersForDamageAssignment() { // this method performs controller's role 
        List<Pair<Card, CardCollection>> blockersNeedManualOrdering = new ArrayList<>();
        for (AttackingBand band : attackedByBands.values()) {
            if (band.isEmpty()) continue;

            Collection<Card> blockers = blockedBands.get(band);
            if (blockers == null || blockers.isEmpty()) {
                continue;
            }

            for (Card attacker : band.getAttackers()) {
                if (blockers.size() <= 1) {
                    blockersOrderedForDamageAssignment.put(attacker, new CardCollection(blockers));
                }
                else { // process it a bit later
                    blockersNeedManualOrdering.add(Pair.of(attacker, new CardCollection(blockers))); // we know there's a list
                }
            }
        }
        
        // brought this out of iteration on bands to avoid concurrency problems 
        for (Pair<Card, CardCollection> pair : blockersNeedManualOrdering) {
            // Damage Ordering needs to take cards like Melee into account, is that happening?
            CardCollection orderedBlockers = playerWhoAttacks.getController().orderBlockers(pair.getLeft(), pair.getRight()); // we know there's a list
            blockersOrderedForDamageAssignment.put(pair.getLeft(), orderedBlockers);

            // Display the chosen order of blockers in the log
            // TODO: this is best done via a combat panel update
            StringBuilder sb = new StringBuilder();
            sb.append(playerWhoAttacks.getName());
            sb.append(" has ordered blockers for ");
            sb.append(pair.getLeft());
            sb.append(": ");
            for (int i = 0; i < orderedBlockers.size(); i++) {
                sb.append(orderedBlockers.get(i));
                if (i != orderedBlockers.size() - 1) {
                    sb.append(", ");
                }
            }
            playerWhoAttacks.getGame().getGameLog().add(GameLogEntryType.COMBAT, sb.toString());
        }
    }
    
    /**
     * Add a blocker to the damage assignment order of an attacker. The
     * relative order of creatures already blocking the attacker may not be
     * changed. Performs controller's role.
     * 
     * @param attacker the attacking creature.
     * @param blocker the blocking creature.
     */
    public void addBlockerToDamageAssignmentOrder(Card attacker, Card blocker) {
    	final CardCollection oldBlockers = blockersOrderedForDamageAssignment.get(attacker);
    	if (oldBlockers == null || oldBlockers.isEmpty()) {
   			blockersOrderedForDamageAssignment.put(attacker, new CardCollection(blocker));
    	}
    	else {
    		CardCollection orderedBlockers = playerWhoAttacks.getController().orderBlocker(attacker, blocker, oldBlockers);
            blockersOrderedForDamageAssignment.put(attacker, orderedBlockers);
    	}
    }
    
    public void orderAttackersForDamageAssignment() { // this method performs controller's role
        // If there are multiple blockers, the Attacker declares the Assignment Order
        for (final Card blocker : getAllBlockers()) {
            orderAttackersForDamageAssignment(blocker);
        }
    }

    public void orderAttackersForDamageAssignment(Card blocker) { // this method performs controller's role
        CardCollection attackers = getAttackersBlockedBy(blocker);
        // They need a reverse map here: Blocker => List<Attacker>
        
        Player blockerCtrl = blocker.getController();
        CardCollection orderedAttacker = attackers.size() <= 1 ? attackers : blockerCtrl.getController().orderAttackers(blocker, attackers);

        // Damage Ordering needs to take cards like Melee into account, is that happening?
        attackersOrderedForDamageAssignment.put(blocker, orderedAttacker);
    }

    // removes references to this attacker from all indices and orders
    public void unregisterAttacker(final Card c, AttackingBand ab) {
        blockersOrderedForDamageAssignment.remove(c);
        
        Collection<Card> blockers = blockedBands.get(ab);
        if (blockers != null) {
            for (Card b : blockers) {
                // Clear removed attacker from assignment order 
                if (attackersOrderedForDamageAssignment.containsKey(b)) {
                    attackersOrderedForDamageAssignment.get(b).remove(c);
                }
            }
        }

        // restore the original defender in case it was changed before the creature was
        // removed from combat but before the trigger resolved (e.g. Ulamog, the Ceaseless
        // Hunger + Portal Mage + Unsummon)
        Game game = c.getGame();
        for (SpellAbilityStackInstance si : game.getStack()) {
            if (si.isTrigger() && c.equals(si.getSourceCard())) {
                GameEntity origDefender = (GameEntity)si.getTriggeringObject("OriginalDefender");
                if (origDefender != null) {
                    si.updateTriggeringObject("Defender", origDefender);
                    if (origDefender instanceof Player) {
                        si.updateTriggeringObject("DefendingPlayer", origDefender);
                    } else if (origDefender instanceof Card) {
                        si.updateTriggeringObject("DefendingPlayer", ((Card)origDefender).getController());
                    }
                }
            }
        }
    }

    // removes references to this defender from all indices and orders
    public void unregisterDefender(final Card c, AttackingBand bandBeingBlocked) {
        attackersOrderedForDamageAssignment.remove(c);
        for (Card atk : bandBeingBlocked.getAttackers()) {
            if (blockersOrderedForDamageAssignment.containsKey(atk)) {
                blockersOrderedForDamageAssignment.get(atk).remove(c);
            }
        }
    }

    // remove a combatant whose side is unknown
    public final void removeFromCombat(final Card c) {
        AttackingBand ab = getBandOfAttacker(c);
        if (ab != null) {
            unregisterAttacker(c, ab);
            ab.removeAttacker(c);
            c.updateAttackingForView();
            return;
        }

        // if not found in attackers, look for this card in blockers
        for (Entry<AttackingBand, Card> be : blockedBands.entries()) {
            if (be.getValue().equals(c)) {
                unregisterDefender(c, be.getKey());
            }
        }
        
        // remove card from map
        while (blockedBands.values().remove(c));
        c.updateBlockingForView();
    }

    public final boolean removeAbsentCombatants() {
        // iterate all attackers and remove illegal declarations
        CardCollection missingCombatants = new CardCollection();
        for (Entry<GameEntity, AttackingBand> ee : attackedByBands.entries()) {
            CardCollectionView atk = ee.getValue().getAttackers();
            for (int i = atk.size() - 1; i >= 0; i--) { // might remove items from collection, so no iterators
                Card c = atk.get(i);
                if (!c.isInPlay() || !c.isCreature()) {
                    missingCombatants.add(c);
                }
            }
        }

        for (Entry<AttackingBand, Card> be : blockedBands.entries()) {
            Card blocker = be.getValue();
            if (!blocker.isInPlay() || !blocker.isCreature()) {
                missingCombatants.add(blocker);
            }
        }

        if (missingCombatants.isEmpty()) { return false; }

        for (Card c : missingCombatants) {
            removeFromCombat(c);
        }
        return true;
    }
    
    // Call this method right after turn-based action of declare blockers has been performed
    public final void fireTriggersForUnblockedAttackers() {
        for (AttackingBand ab : attackedByBands.values()) {
            Collection<Card> blockers = blockedBands.get(ab);
            boolean isBlocked = blockers != null && !blockers.isEmpty();
            ab.setBlocked(isBlocked);

            if (!isBlocked) {
                for (Card attacker : ab.getAttackers()) {
                    // Run Unblocked Trigger
                    final Map<String, Object> runParams = Maps.newHashMap();
                    runParams.put("Attacker", attacker);
                    runParams.put("Defender",getDefenderByAttacker(attacker));
                    runParams.put("DefendingPlayer", getDefenderPlayerByAttacker(attacker));
                    attacker.getGame().getTriggerHandler().runTrigger(TriggerType.AttackerUnblocked, runParams, false);
                }
            }
        }
    }

    private final boolean assignBlockersDamage(boolean firstStrikeDamage) {
        // Assign damage by Blockers
        final CardCollection blockers = getAllBlockers();
        boolean assignedDamage = false;

        for (final Card blocker : blockers) {
            if (!dealDamageThisPhase(blocker, firstStrikeDamage)) {
                continue;
            }
            
            if (firstStrikeDamage) {
                combatantsThatDealtFirstStrikeDamage.add(blocker);
            }
            
            CardCollection attackers = attackersOrderedForDamageAssignment.get(blocker);

            final int damage = blocker.getNetCombatDamage();

            if (!attackers.isEmpty()) {
                Player attackingPlayer = getAttackingPlayer();
                Player assigningPlayer = blocker.getController();

                if (AttackingBand.isValidBand(attackers, true))
                    assigningPlayer = attackingPlayer;

                assignedDamage = true;
                Map<Card, Integer> map = assigningPlayer.getController().assignCombatDamage(blocker, attackers, damage, null, assigningPlayer != blocker.getController());
                for (Entry<Card, Integer> dt : map.entrySet()) {
                    dt.getKey().addAssignedDamage(dt.getValue(), blocker);
                }
            }
        }
        return assignedDamage;
    }

    private final boolean assignAttackersDamage(boolean firstStrikeDamage) {
        // Assign damage by Attackers
        defendingDamageMap.clear(); // this should really happen in deal damage
        CardCollection orderedBlockers = null;
        final CardCollection attackers = getAttackers();
        boolean assignedDamage = false;
        for (final Card attacker : attackers) {
            if (!dealDamageThisPhase(attacker, firstStrikeDamage)) {
                continue;
            }
            
            if (firstStrikeDamage) {
                combatantsThatDealtFirstStrikeDamage.add(attacker);
            }
            
            // If potential damage is 0, continue along
            final int damageDealt = attacker.getNetCombatDamage();
            if (damageDealt <= 0) {
                continue;
            }
            
            AttackingBand band = getBandOfAttacker(attacker);
            if (band == null) {
                continue;
            }

            boolean trampler = attacker.hasKeyword("Trample");
            orderedBlockers = blockersOrderedForDamageAssignment.get(attacker);
            assignedDamage = true;
            // If the Attacker is unblocked, or it's a trampler and has 0 blockers, deal damage to defender
            if (orderedBlockers == null || orderedBlockers.isEmpty()) {
                if (trampler || !band.isBlocked()) { // this is called after declare blockers, no worries 'bout nulls in isBlocked
                    addDefendingDamage(damageDealt, attacker);
                } // No damage happens if blocked but no blockers left
            }
            else {
                GameEntity defender = getDefenderByAttacker(band);
                Player assigningPlayer = getAttackingPlayer();
                // Defensive Formation is very similar to Banding with Blockers
                // It allows the defending player to assign damage instead of the attacking player
                if (defender instanceof Player && defender.hasKeyword("You assign combat damage of each creature attacking you.")) {
                    assigningPlayer = (Player)defender;
                }
                else if (AttackingBand.isValidBand(orderedBlockers, true)){
                    assigningPlayer = orderedBlockers.get(0).getController();
                }

                Map<Card, Integer> map = assigningPlayer.getController().assignCombatDamage(attacker, orderedBlockers, damageDealt, defender, getAttackingPlayer() != assigningPlayer);
                for (Entry<Card, Integer> dt : map.entrySet()) {
                    if (dt.getKey() == null) {
                        if (dt.getValue() > 0) 
                            addDefendingDamage(dt.getValue(), attacker);
                    } else {
                        dt.getKey().addAssignedDamage(dt.getValue(), attacker);
                    }
                }
            } // if !hasFirstStrike ...
        } // for
        return assignedDamage;
    }
    
    private final boolean dealDamageThisPhase(Card combatant, boolean firstStrikeDamage) {
        // During first strike damage, double strike and first strike deal damage
        // During regular strike damage, double strike and anyone who hasn't dealt damage deal damage
        if (combatant.hasDoubleStrike()) {
            return true;
        }
        if (firstStrikeDamage && combatant.hasFirstStrike()) {
            return true;
        }
        return !firstStrikeDamage && !combatantsThatDealtFirstStrikeDamage.contains(combatant);
    }

    // Damage to whatever was protected there. 
    private final void addDefendingDamage(final int n, final Card source) {
        final GameEntity ge = getDefenderByAttacker(source);

        if (ge instanceof Card) {
            final Card planeswalker = (Card) ge;
            planeswalker.addAssignedDamage(n, source);
            return;
        }

        if (!defendingDamageMap.containsKey(source)) {
            defendingDamageMap.put(source, n);
        }
        else {
            defendingDamageMap.put(source, defendingDamageMap.get(source) + n);
        }
    }

    public final boolean assignCombatDamage(boolean firstStrikeDamage) {
        boolean assignedDamage = assignAttackersDamage(firstStrikeDamage);
        assignedDamage |= assignBlockersDamage(firstStrikeDamage);
        if (!firstStrikeDamage) {
            // Clear first strike damage list since it doesn't matter anymore
            combatantsThatDealtFirstStrikeDamage.clear();
        }
        return assignedDamage;
    }

    public final CardDamageMap getDamageMap() {
        return dealtDamageTo;
    }

    public void dealAssignedDamage() {
    	playerWhoAttacks.getGame().copyLastState();

    	CardDamageMap preventMap = new CardDamageMap();

        // This function handles both Regular and First Strike combat assignment
        for (final Entry<Card, Integer> entry : defendingDamageMap.entrySet()) {
            GameEntity defender = getDefenderByAttacker(entry.getKey());
            if (defender instanceof Player) { // player
                ((Player) defender).addCombatDamage(entry.getValue(), entry.getKey(), dealtDamageTo, preventMap);
            }
            else if (defender instanceof Card) { // planeswalker
                ((Card) defender).getController().addCombatDamage(entry.getValue(), entry.getKey(), dealtDamageTo, preventMap);
            }
        }

        // this can be much better below here...

        final CardCollection combatants = new CardCollection();
        combatants.addAll(getAttackers());
        combatants.addAll(getAllBlockers());
        combatants.addAll(getDefendingPlaneswalkers());

        for (final Card c : combatants) {
            // if no assigned damage to resolve, move to next
            if (c.getTotalAssignedDamage() == 0) {
                continue;
            }

            c.addCombatDamage(c.getAssignedDamageMap(), dealtDamageTo, preventMap);
            c.clearAssignedDamage();
        }
        
        // Run triggers
        for (final GameEntity ge : dealtDamageTo.columnKeySet()) {
            final Map<String, Object> runParams = Maps.newHashMap();
            runParams.put("DamageSources", dealtDamageTo.column(ge).keySet());
            runParams.put("DamageTarget", ge);
            ge.getGame().getTriggerHandler().runTrigger(TriggerType.CombatDamageDoneOnce, runParams, false);
        }

        preventMap.triggerPreventDamage(true);
        // This was deeper before, but that resulted in the stack entry acting like before.

        // LifeLink for Combat Damage at this place
        dealtDamageTo.dealLifelinkDamage();

        // when ... deals combat damage to one or more
        for (final Card damageSource : dealtDamageTo.rowKeySet()) {
            final Map<String, Object> runParams = Maps.newHashMap();
            Map<GameEntity, Integer> row = dealtDamageTo.row(damageSource);

            // TODO find better way to get the sum
            int dealtDamage = 0;
            for (Integer i : row.values()) {
                dealtDamage += i;
            }

            runParams.put("DamageSource", damageSource);
            runParams.put("DamageTargets", row.keySet());
            runParams.put("DamageAmount", dealtDamage);
            damageSource.getGame().getTriggerHandler().runTrigger(TriggerType.DealtCombatDamageOnce, runParams, false);
        }
        dealtDamageTo.clear();
    }

    public final boolean isUnblocked(final Card att) {
        AttackingBand band = getBandOfAttacker(att);
        return band == null ? false : Boolean.FALSE.equals(band.isBlocked());
    }

    public final CardCollection getUnblockedAttackers() {
        CardCollection unblocked = new CardCollection();
        for (AttackingBand ab : attackedByBands.values()) {
            if (Boolean.FALSE.equals(ab.isBlocked())) {
                unblocked.addAll(ab.getAttackers());
            }
        }
        return unblocked;
    }

    public boolean isPlayerAttacked(Player who) {
        for (GameEntity defender : attackedByBands.keySet()) {
            Card defenderAsCard = defender instanceof Card ? (Card)defender : null;
            if ((null != defenderAsCard && defenderAsCard.getController() != who) || 
                (null == defenderAsCard && defender != who)) {
                continue; // defender is not related to player 'who'
            }
            for (AttackingBand ab : attackedByBands.get(defender)) {
                if (!ab.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isBlocking(Card blocker) {
        if (blockedBands.containsValue(blocker)) {
            return true; // is blocking something at the moment
        };

        CombatLki lki = lkiCache.get(blocker);
        return null != lki && !lki.isAttacker; // was blocking something anyway
    }

    public boolean isBlocking(Card blocker, Card attacker) {
        AttackingBand ab = getBandOfAttacker(attacker);
        Collection<Card> blockers = blockedBands.get(ab);
        if (blockers != null && blockers.contains(blocker)) {
            return true; // is blocking the attacker's band at the moment
        }
        
        CombatLki lki = lkiCache.get(blocker);
        return null != lki && !lki.isAttacker && lki.relatedBands.contains(ab); // was blocking that very band
    }

    public void saveLKI(Card lastKnownInfo) {
        FCollectionView<AttackingBand> attackersBlocked = null;
        final AttackingBand attackingBand = getBandOfAttacker(lastKnownInfo);
        final boolean isAttacker = attackingBand != null;
        if (!isAttacker) {
            attackersBlocked = getAttackingBandsBlockedBy(lastKnownInfo);
            if (attackersBlocked.isEmpty()) {
                return; // card was not even in combat
            }
        }
        final FCollectionView<AttackingBand> relatedBands = isAttacker ? new FCollection<AttackingBand>(attackingBand) : attackersBlocked;
        lkiCache.put(lastKnownInfo, new CombatLki(isAttacker, relatedBands));
    }
}
