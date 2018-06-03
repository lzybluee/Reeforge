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
package forge.game.trigger;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.TriggerReplacementBase;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.Ability;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.*;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import forge.util.TextUtil;

/**
 * <p>
 * Abstract Trigger class. Constructed by reflection only
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public abstract class Trigger extends TriggerReplacementBase {
    private static int maxId = 0;
    private static int nextId() { return ++maxId; }

    /**
     * <p>
     * resetIDs.
     * </p>
     */
    public static void resetIDs() {
        Trigger.maxId = 50000;
    }

    /** The ID. */
    private int id;


    /** The run params. */
    private Map<String, Object> runParams;

    private TriggerType mode;

    private Map<String, Object> storedTriggeredObjects = null;
    
    private List<Object> triggerRemembered = Lists.newArrayList();

    // number of times this trigger was activated this this turn
    // used to handle once-per-turn triggers like Crawling Sensation
    private int numberTurnActivations = 0;

    /**
     * <p>
     * Setter for the field <code>storedTriggeredObjects</code>.
     * </p>
     * 
     * @param storedTriggeredObjects
     *            a {@link java.util.HashMap} object.
     * @since 1.0.15
     */
    public final void setStoredTriggeredObjects(final Map<String, Object> storedTriggeredObjects) {
        this.storedTriggeredObjects = Maps.newHashMap(storedTriggeredObjects);
    }

    /**
     * <p>
     * Getter for the field <code>storedTriggeredObjects</code>.
     * </p>
     * 
     * @return a {@link java.util.HashMap} object.
     * @since 1.0.15
     */
    public final Map<String, Object> getStoredTriggeredObjects() {
        return this.storedTriggeredObjects;
    }


    private List<PhaseType> validPhases;

    /**
     * <p>
     * Constructor for Trigger.
     * </p>
     * 
     * @param params
     *            a {@link java.util.HashMap} object.
     * @param host
     *            a {@link forge.game.card.Card} object.
     * @param intrinsic
     *            the intrinsic
     */
    public Trigger(final Map<String, String> params, final Card host, final boolean intrinsic) {
        this.id = nextId();
        this.intrinsic = intrinsic;

        this.setRunParams(new HashMap<String, Object>()); // TODO: Consider whether this can be null instead, for performance reasons.
        this.originalMapParams.putAll(params);
        this.mapParams.putAll(params);
        this.setHostCard(host);
    }

    /**
     * <p>
     * toString.
     * </p>
     * 
     * @return a {@link java.lang.String} object.
     */
    @Override
    public final String toString() {
    	return toString(false);
    }
    
    public String toString(boolean active) {
        if (this.mapParams.containsKey("TriggerDescription") && !this.isSuppressed()) {

            StringBuilder sb = new StringBuilder();
            String desc = this.mapParams.get("TriggerDescription");
            if(active)
                desc = TextUtil.fastReplace(desc, "CARDNAME", getHostCard().toString());
            else
                desc = TextUtil.fastReplace(desc, "CARDNAME", getHostCard().getName());
            if (getHostCard().getEffectSource() != null) {
                if(active)
                    desc = TextUtil.fastReplace(desc, "EFFECTSOURCE", getHostCard().getEffectSource().toString());
                else
                    desc = TextUtil.fastReplace(desc, "EFFECTSOURCE", getHostCard().getEffectSource().getName());
            }
            sb.append(desc);
            if (!this.triggerRemembered.isEmpty()) {
                sb.append(" (").append(this.triggerRemembered).append(")");
            }
            return sb.toString();
        } else {
            return "";
        }
    }

    public final String replaceAbilityText(final String desc, final CardState state) {
        // this function is for ABILITY
        if (!desc.contains("ABILITY")) {
            return desc;
        }
        SpellAbility sa = getOverridingAbility();
        if (sa == null && this.mapParams.containsKey("Execute")) {
            sa = AbilityFactory.getAbility(state, this.mapParams.get("Execute"));
            setOverridingAbility(sa);
        }

        return replaceAbilityText(desc, sa);
        
    }
    
    public final String replaceAbilityText(final String desc, SpellAbility sa) {
        String result = desc;

        // this function is for ABILITY
        if (!result.contains("ABILITY")) {
            return result;
        }
        if (sa == null) {
            sa = getOverridingAbility();
        }
        if (sa != null) {
            String saDesc;
            // if sa is a wrapper, get the Wrapped Ability
            if (sa.isWrapper()) {
                final WrappedAbility wa = (WrappedAbility) sa;
                sa = wa.getWrappedAbility();

                // wrapped Charm spells are special,
                // only get the selected abilities
                if (ApiType.Charm.equals(sa.getApi())) {
                    saDesc = sa.getStackDescription();
                } else {
                    saDesc = sa.getDescription();
                }
            } else if (ApiType.Charm.equals(sa.getApi())) {
                // use special formating, can be used in Card Description
                saDesc = CharmEffect.makeFormatedDescription(sa);
            } else {
                saDesc = sa.getDescription();
            }
            if (!saDesc.isEmpty()) {
                // string might have leading whitespace
                saDesc = saDesc.trim();
                saDesc = saDesc.substring(0, 1).toLowerCase() + saDesc.substring(1);
                result = TextUtil.fastReplace(result, "ABILITY", saDesc);
            }
        }

        return result;
    }

    /**
     * <p>
     * phasesCheck.
     * </p>
     * 
     * @return a boolean.
     */
    public final boolean phasesCheck(final Game game) {
        PhaseHandler phaseHandler = game.getPhaseHandler();
        if (null != validPhases) {
            if (!validPhases.contains(phaseHandler.getPhase())) {
                return false;
            }
        }

        if (this.mapParams.containsKey("PreCombatMain")) {
            if (!phaseHandler.isPreCombatMain()) {
                return false;
            }
        }

        if (this.mapParams.containsKey("PlayerTurn")) {
            if (!phaseHandler.isPlayerTurn(this.getHostCard().getController())) {
                return false;
            }
        }

        if (this.mapParams.containsKey("NotPlayerTurn")) {
            if (phaseHandler.isPlayerTurn(this.getHostCard().getController())) {
                return false;
            }
        }

        if (this.mapParams.containsKey("OpponentTurn")) {
            if (!this.getHostCard().getController().isOpponentOf(phaseHandler.getPlayerTurn())) {
                return false;
            }
        }

        if (this.mapParams.containsKey("FirstUpkeep")) {
            if (!phaseHandler.isFirstUpkeep()) {
                return false;
            }
        }

        if (this.mapParams.containsKey("FirstUpkeepThisGame")) {
            if (!phaseHandler.isFirstUpkeepThisGame()) {
                return false;
            }
        }

        if (this.mapParams.containsKey("FirstCombat")) {
            if (!phaseHandler.isFirstCombat()) {
                return false;
            }
        }

        if (this.mapParams.containsKey("TurnCount")) {
            int turn = Integer.parseInt(this.mapParams.get("TurnCount"));
            if (phaseHandler.getTurn() != turn) {
                return false;
            }
        }

        return true;
    }
    /**
     * <p>
     * requirementsCheck.
     * </p>
     * @param game 
     *
     * @return a boolean.
     */
    public final boolean requirementsCheck(Game game) {

        if (this.mapParams.containsKey("APlayerHasMoreLifeThanEachOther")) {
            int highestLife = Integer.MIN_VALUE; // Negative base just in case a few Lich's or Platinum Angels are running around
            final List<Player> healthiest = new ArrayList<Player>();
            for (final Player p : game.getPlayers()) {
                if (p.getLife() > highestLife) {
                    healthiest.clear();
                    highestLife = p.getLife();
                    healthiest.add(p);
                } else if (p.getLife() == highestLife) {
                    highestLife = p.getLife();
                    healthiest.add(p);
                }
            }

            if (healthiest.size() != 1) {
                // More than one player tied for most life
                return false;
            }
        }

        if (this.mapParams.containsKey("APlayerHasMostCardsInHand")) {
            int largestHand = 0;
            final List<Player> withLargestHand = new ArrayList<Player>();
            for (final Player p : game.getPlayers()) {
                if (p.getCardsIn(ZoneType.Hand).size() > largestHand) {
                    withLargestHand.clear();
                    largestHand = p.getCardsIn(ZoneType.Hand).size();
                    withLargestHand.add(p);
                } else if (p.getCardsIn(ZoneType.Hand).size() == largestHand) {
                    largestHand = p.getCardsIn(ZoneType.Hand).size();
                    withLargestHand.add(p);
                }
            }

            if (withLargestHand.size() != 1) {
                // More than one player tied for most life
                return false;
            }
        }
        
        if ( !meetsCommonRequirements(this.mapParams))
            return false;

        return true;
    }


    public boolean meetsRequirementsOnTriggeredObjects(Game game,  Map<String, Object> runParams) {
        if ("True".equals(this.mapParams.get("EvolveCondition"))) {
            final Card moved = (Card) runParams.get("Card");
            if (moved == null) {
                return false;
                // final StringBuilder sb = new StringBuilder();
                // sb.append("Trigger::requirementsCheck() - EvolveCondition condition being checked without a moved card. ");
                // sb.append(this.getHostCard().getName());
                // throw new RuntimeException(sb.toString());
            }
            if (moved.getNetPower() <= this.getHostCard().getNetPower()
                    && moved.getNetToughness() <= this.getHostCard().getNetToughness()) {
                return false;
            }
        }

        String condition = this.mapParams.get("Condition");
        if ("AltCost".equals(condition)) {
            final Card moved = (Card) runParams.get("Card");
            if( null != moved && !moved.isOptionalCostPaid(OptionalCost.AltCost))
                return false;
        } else if ("AttackedPlayerWithMostLife".equals(condition)) {
            GameEntity attacked = (GameEntity) runParams.get("Attacked");
            if (attacked == null) {
                // Check "Defender" too because once triggering objects are set on TriggerAttacks, the value of Attacked
                // ends up being in Defender at that point.
                attacked = (GameEntity) runParams.get("Defender");
            }
            if (attacked == null || !attacked.isValid("Player.withMostLife",
                    this.getHostCard().getController(), this.getHostCard(), null)) {
                return false;
            }
        } else if ("AttackedPlayerWhoAttackedYouLastTurn".equals(condition)) {
            GameEntity attacked = (GameEntity) runParams.get("Attacked");
            if (attacked == null) {
                // Check "Defender" too because once triggering objects are set on TriggerAttacks, the value of Attacked
                // ends up being in Defender at that point.
                attacked = (GameEntity) runParams.get("DefendingPlayer");
            }
            Player attacker = this.getHostCard().getController();

            boolean valid = false;
            if (game.getPlayersAttackedLastTurn().containsKey(attacked)) {
                if (game.getPlayersAttackedLastTurn().get(attacked).contains(attacker)) {
                    valid = true;
                }
            }

            if (attacked == null || !valid) {
                return false;
            }
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public final boolean equals(final Object o) {
        if (!(o instanceof Trigger)) {
            return false;
        }

        return this.getId() == ((Trigger) o).getId();
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return 41 * (41 + this.getId());
    }

    /**
     * <p>
     * performTest.
     * </p>
     * 
     * @param runParams2
     *            a {@link java.util.HashMap} object.
     * @return a boolean.
     */
    public abstract boolean performTest(java.util.Map<String, Object> runParams2);

    /**
     * <p>
     * setTriggeringObjects.
     * </p>
     * 
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     */
    public abstract void setTriggeringObjects(SpellAbility sa);

    /**
     * Gets the run params.
     * 
     * @return the runParams
     */
    public Map<String, Object> getRunParams() {
        return this.runParams;
    }

    /**
     * Sets the run params.
     * 
     * @param runParams0
     *            the runParams to set
     */
    public void setRunParams(final Map<String, Object> runParams0) {
        this.runParams = runParams0;
    }

    /**
     * Gets the id.
     *
     * @return the id
     */
    public int getId() {
        return this.id;
    }

    /**
     * <p>
     * setID.
     * </p>
     *
     * @param id
     *            a int.
     */
    public final void setId(final int id) {
        this.id = id;
    }

    private Ability triggeredSA;

    /**
     * Gets the triggered sa.
     * 
     * @return the triggered sa
     */
    public final Ability getTriggeredSA() {
        return this.triggeredSA;
    }

    /**
     * Sets the triggered sa.
     * 
     * @param sa
     *            the triggered sa to set
     */
    public void setTriggeredSA(final Ability sa) {
        this.triggeredSA = sa;
    }

    public void addRemembered(Object o) {
        this.triggerRemembered.add(o);
    }
    
    public List<Object> getTriggerRemembered() {
        return this.triggerRemembered;
    }

    /**
     * TODO: Write javadoc for this method.
     * @return the mode
     */
    public TriggerType getMode() {
        return mode;
    }

    /**
     * 
     * @param triggerType
     *            the triggerType to set
     * @param triggerType
     */
    void setMode(TriggerType triggerType) {
        mode = triggerType;
    }

    public final Trigger copy(Card newHost, boolean lki) {
        final Trigger copy = (Trigger) clone();

        copy.originalMapParams.putAll(originalMapParams);
        copy.mapParams.putAll(originalMapParams);
        copy.setHostCard(newHost);

        if (getOverridingAbility() != null) {
            copy.setOverridingAbility(getOverridingAbility().copy(newHost, lki));
        }

        if (!lki) {
            copy.setId(nextId());
        }

        if (validPhases != null) {
            copy.setTriggerPhases(Lists.newArrayList(validPhases));
        }
        copy.setActiveZone(validHostZones);
        copy.setTemporary(isTemporary());
        return copy;
    }

    public boolean isStatic() {
        return this.mapParams.containsKey("Static"); // && params.get("Static").equals("True") [always true if present]
    }

    public void setTriggerPhases(List<PhaseType> phases) {
        validPhases = phases;
    }

    public List<PhaseType> getTriggerPhases() {
        return validPhases;
    }

    //public String getImportantStackObjects(SpellAbility sa) { return ""; };
    abstract public String getImportantStackObjects(SpellAbility sa);

    public int getActivationsThisTurn() {
        return this.numberTurnActivations;
    }

    public void triggerRun()
    {
        this.numberTurnActivations++;
    }

    // Resets the state stored each turn for per-turn and per-instance restriction
    public void resetTurnState()
    {
        this.numberTurnActivations = 0;
    }

    /** {@inheritDoc} */
    @Override
    public final Object clone() {
        try {
            return super.clone();
        } catch (final Exception ex) {
            throw new RuntimeException("Trigger : clone() error, " + ex);
        }
    }
}
