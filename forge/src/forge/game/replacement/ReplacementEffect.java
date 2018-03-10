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
package forge.game.replacement;

import forge.game.Game;
import forge.game.TriggerReplacementBase;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.util.TextUtil;

import java.util.List;
import java.util.Map;

/**
 * TODO: Write javadoc for this type.
 * 
 */
public abstract class ReplacementEffect extends TriggerReplacementBase {
    private static int maxId = 0;
    private static int nextId() { return ++maxId; }

    /** The ID. */
    private int id;

    private ReplacementLayer layer = ReplacementLayer.None;

    /** The has run. */
    private boolean hasRun = false;

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
    /**
     * Checks for run.
     * 
     * @return the hasRun
     */
    public final boolean hasRun() {
        return this.hasRun;
    }

    /**
     * Instantiates a new replacement effect.
     * 
     * @param map
     *            the map
     * @param host
     *            the host
     */
    public ReplacementEffect(final Map<String, String> map, final Card host, final boolean intrinsic) {
        this.id = nextId();
        this.intrinsic = intrinsic;
        originalMapParams.putAll(map);
        mapParams.putAll(map);
        this.setHostCard(host);
        if (map.containsKey("Layer")) {
            this.setLayer(ReplacementLayer.smartValueOf(map.get("Layer")));
        }
    }

    /**
     * Sets the checks for run.
     * 
     * @param hasRun
     *            the hasRun to set
     */
    public final void setHasRun(final boolean hasRun) {
        this.hasRun = hasRun;
    }

    /**
     * Can replace.
     * 
     * @param runParams
     *            the run params
     * @return true, if successful
     */
    public abstract boolean canReplace(final Map<String, Object> runParams);

    /**
     * <p>
     * requirementsCheck.
     * </p>
     * @param game 
     * 
     * @return a boolean.
     */
    public boolean requirementsCheck(Game game) {
        return this.requirementsCheck(game, this.getMapParams());
    }
    
    public boolean requirementsCheck(Game game, Map<String,String> params) {

        if (this.isSuppressed()) {
            return false; // Effect removed by effect
        }

        if (params.containsKey("PlayerTurn")) {
            if (params.get("PlayerTurn").equals("True") && !game.getPhaseHandler().isPlayerTurn(this.getHostCard().getController())) {
                return false;
            }
        }

        if (params.containsKey("ActivePhases")) {
            boolean isPhase = false;
            List<PhaseType> aPhases = PhaseType.parseRange(params.get("ActivePhases"));
            final PhaseType currPhase = game.getPhaseHandler().getPhase();
            for (final PhaseType s : aPhases) {
                if (s == currPhase) {
                    isPhase = true;
                    break;
                }
            }

            return isPhase;
        }

        return meetsCommonRequirements(params);
    }

    /**
     * Gets the copy.
     * 
     * @return the copy
     */
    public final ReplacementEffect copy(final Card host, final boolean lki) {
        final ReplacementEffect res = (ReplacementEffect) clone();
        for (String key : getSVars()) {
            res.setSVar(key, getSVar(key));
        }

        final SpellAbility sa = this.getOverridingAbility();
        if (sa != null) {
            final SpellAbility overridingAbilityCopy = sa.copy(host, lki);
            if (overridingAbilityCopy != null) {
                res.setOverridingAbility(overridingAbilityCopy);
            }
        }

        if (!lki) {
            res.setId(nextId());
            res.setHasRun(false);
        }
        
        res.setHostCard(host);

        res.setActiveZone(validHostZones);
        res.setLayer(getLayer());
        res.setTemporary(isTemporary());
        return res;
    }

    /**
     * Sets the replacing objects.
     * 
     * @param runParams
     *            the run params
     * @param spellAbility
     *            the SpellAbility
     */
    public void setReplacingObjects(final Map<String, Object> runParams, final SpellAbility spellAbility) {
        // Should be overridden by replacers that need it.
    }

    /**
     * @return the layer
     */
    public ReplacementLayer getLayer() {
        return layer;
    }

    /**
     * @param layer0 the layer to set
     */
    public void setLayer(ReplacementLayer layer0) {
        this.layer = layer0;
    }

    /**
     * To string.
     *
     * @return a String
     */
    @Override
    public String toString() {
        if (hasParam("Description") && !this.isSuppressed()) {
            String desc = AbilityUtils.applyDescriptionTextChangeEffects(getParam("Description"), this);
            if (desc.contains("CARDNAME")) {
                desc = TextUtil.fastReplace(desc, "CARDNAME", getHostCard().toString());
            }
            return desc;
        } else {
            return "";
        }
    }

    /** {@inheritDoc} */
    @Override
    public final Object clone() {
        try {
            return super.clone();
        } catch (final Exception ex) {
            throw new RuntimeException("ReplacementEffect : clone() error, " + ex);
        }
    }

    /** {@inheritDoc} */
    @Override
    public final boolean equals(final Object o) {
        if (!(o instanceof ReplacementEffect)) {
            return false;
        }

        return this.getId() == ((ReplacementEffect) o).getId();
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return 42 * (42 + this.getId());
    }
}
