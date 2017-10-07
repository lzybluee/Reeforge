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
package forge.game.ability;

import forge.card.CardStateName;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.spellability.*;
import forge.game.zone.ZoneType;
import forge.util.FileSection;

import java.util.List;
import java.util.Map;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * <p>
 * AbilityFactory class.
 * </p>
 * 
 * @author Forge
 * @version $Id: AbilityFactory.java 35046 2017-08-15 06:49:10Z Agetian $
 */
public final class AbilityFactory {

    static final List<String> additionalAbilityKeys = Lists.newArrayList(
            "WinSubAbility", "OtherwiseSubAbility", // Clash
            "ChooseNumberSubAbility", "Lowest", "Highest", // ChooseNumber
            "HeadsSubAbility", "TailsSubAbility", "LoseSubAbility", // FlipCoin
            "TrueSubAbility", "FalseSubAbility", // Branch
            "ChosenPile", "UnchosenPile", // MultiplePiles & TwoPiles
            "RepeatSubAbility", // Repeat & RepeatEach
            "Execute", // DelayedTrigger
            "FallbackAbility", // Complex Unless costs which can be unpayable
            "ChooseSubAbility", // Can choose a player via ChoosePlayer
            "CantChooseSubAbility" // Can't choose a player via ChoosePlayer
        );

    public enum AbilityRecordType {
        Ability("AB"),
        Spell("SP"),
        StaticAbility("ST"),
        SubAbility("DB");
        
        private final String prefix;
        private AbilityRecordType(String prefix) {
            this.prefix = prefix;
        }
        public String getPrefix() {
            return prefix;
        }
        
        public SpellAbility buildSpellAbility(ApiType api, Card hostCard, Cost abCost, TargetRestrictions abTgt, Map<String, String> mapParams ) {
            switch(this) {
                case Ability: return new AbilityApiBased(api, hostCard, abCost, abTgt, mapParams);
                case Spell: return new SpellApiBased(api, hostCard, abCost, abTgt, mapParams);
                case StaticAbility: return new StaticAbilityApiBased(api, hostCard, abCost, abTgt, mapParams);
                case SubAbility: return new AbilitySub(api, hostCard, abTgt, mapParams);
            }
            return null; // exception here would be fine!
        }
        
        public ApiType getApiTypeOf(Map<String, String> abParams) {
            return ApiType.smartValueOf(abParams.get(this.getPrefix()));
        }
        
        public static AbilityRecordType getRecordType(Map<String, String> abParams) {
            if (abParams.containsKey(AbilityRecordType.Ability.getPrefix())) {
                return AbilityRecordType.Ability;
            } else if (abParams.containsKey(AbilityRecordType.Spell.getPrefix())) {
                return AbilityRecordType.Spell;
            } else if (abParams.containsKey(AbilityRecordType.StaticAbility.getPrefix())) {
                return AbilityRecordType.StaticAbility;
            } else if (abParams.containsKey(AbilityRecordType.SubAbility.getPrefix())) {
                return AbilityRecordType.SubAbility;
            } else {
                return null;
            }
        }
    }
    
    
    /**
     * <p>
     * getAbility.
     * </p>
     * 
     * @param abString
     *            a {@link java.lang.String} object.
     * @param hostCard
     *            a {@link forge.game.card.Card} object.
     * @return a {@link forge.game.spellability.SpellAbility} object.
     */
    public static final SpellAbility getAbility(final String abString, final Card hostCard) {
        return getAbility(abString, hostCard, null);
    }
    
    private static final SpellAbility getAbility(final String abString, final Card hostCard, final SpellAbility parent) {
        Map<String, String> mapParams;
        try {
            mapParams = AbilityFactory.getMapParams(abString);
        }
        catch (RuntimeException ex) {
            throw new RuntimeException(hostCard.getName() + ": " + ex.getMessage());
        }
        // parse universal parameters
        AbilityRecordType type = AbilityRecordType.getRecordType(mapParams);
        if (null == type) {
            String source = hostCard.getName().isEmpty() ? abString : hostCard.getName();
            throw new RuntimeException("AbilityFactory : getAbility -- no API in " + source + ": " + abString);
        }
        return getAbility(mapParams, type, hostCard, parent);
    }
    
    public static final SpellAbility getAbility(final Card hostCard, final String svar) {
        return getAbility(hostCard, svar, null);
    }
    
    private static final SpellAbility getAbility(final Card hostCard, final String svar, final SpellAbility parent) {
        if (!hostCard.hasSVar(svar)) {
            String source = hostCard.getName();
            throw new RuntimeException("AbilityFactory : getAbility -- " + source +  " has no SVar: " + svar);
        } else {
            return getAbility(hostCard.getSVar(svar), hostCard, parent);
        }
    }
    
    public static final SpellAbility getAbility(final Map<String, String> mapParams, AbilityRecordType type, final Card hostCard, final SpellAbility parent) {
        return getAbility(type, type.getApiTypeOf(mapParams), mapParams, parseAbilityCost(hostCard, mapParams, type), hostCard, parent);
    }


    public static Cost parseAbilityCost(final Card hostCard, Map<String, String> mapParams, AbilityRecordType type) {
        Cost abCost = null;
        if (type != AbilityRecordType.SubAbility) {
            String cost = mapParams.get("Cost");
            if (cost == null) {
                throw new RuntimeException("AbilityFactory : getAbility -- no Cost in " + hostCard.getName());
            }
            abCost = new Cost(cost, type == AbilityRecordType.Ability);
        }
        return abCost;
    }

    public static final SpellAbility getAbility(AbilityRecordType type, ApiType api, Map<String, String> mapParams,
            Cost abCost,final Card hostCard, final SpellAbility parent) {
        TargetRestrictions abTgt = mapParams.containsKey("ValidTgts") ? readTarget(mapParams) : null;

        if (api == ApiType.CopySpellAbility || api == ApiType.Counter || api == ApiType.ChangeTargets || api == ApiType.ControlSpell) {
            // Since all "CopySpell" ABs copy things on the Stack no need for it to be everywhere
            // Since all "Counter" or "ChangeTargets" abilities only target the Stack Zone
            // No need to have each of those scripts have that info
            if (abTgt != null) {
                abTgt.setZone(ZoneType.Stack);
            }
        }

        else if (api == ApiType.PermanentCreature || api == ApiType.PermanentNoncreature) {
            // If API is a permanent type, and creating AF Spell
            // Clear out the auto created SpellPemanent spell
            if (type == AbilityRecordType.Spell && !mapParams.containsKey("SubAbility")) {
                hostCard.clearFirstSpell();
            }
        }


        SpellAbility spellAbility = type.buildSpellAbility(api, hostCard, abCost, abTgt, mapParams);


        if (spellAbility == null) {
            final StringBuilder msg = new StringBuilder();
            msg.append("AbilityFactory : SpellAbility was not created for ");
            msg.append(hostCard.getName());
            msg.append(". Looking for API: ").append(api);
            throw new RuntimeException(msg.toString());
        }

        // need to set Parent Early
        if (parent != null && spellAbility instanceof AbilitySub) { 
            ((AbilitySub)spellAbility).setParent(parent);
        }

        // *********************************************
        // set universal properties of the SpellAbility

        if (mapParams.containsKey("References")) {
            for (String svar : mapParams.get("References").split(",")) {
                spellAbility.setSVar(svar, hostCard.getSVar(svar));
            }
        }

        if (api == ApiType.DelayedTrigger && mapParams.containsKey("Execute")) {
            spellAbility.setSVar(mapParams.get("Execute"), hostCard.getSVar(mapParams.get("Execute")));
        }

        if (mapParams.containsKey("PreventionSubAbility")) {
            spellAbility.setSVar(mapParams.get("PreventionSubAbility"), hostCard.getSVar(mapParams.get("PreventionSubAbility")));
        }

        if (mapParams.containsKey("SubAbility")) {
            final String name = mapParams.get("SubAbility");
            SpellAbility p = parent;
            AbilitySub sub = null;
            while (p != null) {
                sub = p.getAdditonalAbility(name);
                if (sub != null) {
                    break;
                }
                p = p.getParent();
            }
            if (sub == null) {
                sub = getSubAbility(hostCard, name, spellAbility);
            }
            spellAbility.setSubAbility(sub);
            spellAbility.setAdditionalAbility(name, sub);            
        }

        for (final String key : additionalAbilityKeys) {
            if (mapParams.containsKey(key) && spellAbility.getAdditonalAbility(key) == null) {
                spellAbility.setAdditionalAbility(key, getSubAbility(hostCard, mapParams.get(key), spellAbility));
            }
        }

        if (api == ApiType.Charm  || api == ApiType.GenericChoice) {
            final String key = "Choices";
            if (mapParams.containsKey(key)) {
                List<String> names = Lists.newArrayList(mapParams.get(key).split(","));
                final SpellAbility sap = spellAbility;
                spellAbility.setAdditionalAbilityList(key, Lists.transform(names, new Function<String, AbilitySub>() {
                    @Override
                    public AbilitySub apply(String input) {
                        return getSubAbility(hostCard, input, sap);
                    } 
                }));
            }
        }

        if (spellAbility instanceof SpellApiBased && hostCard.isPermanent()) {
            String desc = mapParams.containsKey("SpellDescription") ? mapParams.get("SpellDescription")
                    : spellAbility.getHostCard().getName();
            spellAbility.setDescription(desc);
        } else if (mapParams.containsKey("SpellDescription")) {
            final StringBuilder sb = new StringBuilder();

            if (type != AbilityRecordType.SubAbility) { // SubAbilities don't have Costs or Cost
                              // descriptors
                sb.append(spellAbility.getCostDescription());
            }

            sb.append(mapParams.get("SpellDescription"));

            spellAbility.setDescription(sb.toString());
        } else if (api == ApiType.Charm) {
            spellAbility.setDescription(CharmEffect.makeSpellDescription(spellAbility));
        } else {
            spellAbility.setDescription("");
        }

        initializeParams(spellAbility, mapParams);
        makeRestrictions(spellAbility, mapParams);
        makeConditions(spellAbility, mapParams);

        return spellAbility;
    }

    private static final TargetRestrictions readTarget(Map<String, String> mapParams) {
        final String min = mapParams.containsKey("TargetMin") ? mapParams.get("TargetMin") : "1";
        final String max = mapParams.containsKey("TargetMax") ? mapParams.get("TargetMax") : "1";


        // TgtPrompt now optional
        final String prompt = mapParams.containsKey("TgtPrompt") ? mapParams.get("TgtPrompt") : "Select target " + mapParams.get("ValidTgts");

        TargetRestrictions abTgt = new TargetRestrictions(prompt, mapParams.get("ValidTgts").split(","), min, max);

        if (mapParams.containsKey("TgtZone")) { // if Targeting
                                                     // something
            // not in play, this Key
            // should be set
            abTgt.setZone(ZoneType.listValueOf(mapParams.get("TgtZone")));
        }

        if (mapParams.containsKey("MaxTotalTargetCMC")) {
            // only target cards up to a certain total max CMC
            abTgt.setMaxTotalCMC(mapParams.get("MaxTotalTargetCMC"));
        }

        // TargetValidTargeting most for Counter: e.g. target spell that
        // targets X.
        if (mapParams.containsKey("TargetValidTargeting")) {
            abTgt.setSAValidTargeting(mapParams.get("TargetValidTargeting"));
        }

        if (mapParams.containsKey("TargetsSingleTarget")) {
            abTgt.setSingleTarget(true);
        }
        if (mapParams.containsKey("TargetUnique")) {
            abTgt.setUniqueTargets(true);
        }
        if (mapParams.containsKey("TargetsFromSingleZone")) {
            abTgt.setSingleZone(true);
        }
        if (mapParams.containsKey("TargetsWithoutSameCreatureType")) {
            abTgt.setWithoutSameCreatureType(true);
        }
        if (mapParams.containsKey("TargetsWithSameController")) {
            abTgt.setSameController(true);
        }
        if (mapParams.containsKey("TargetsWithDifferentControllers")) {
            abTgt.setDifferentControllers(true);
        }
        if (mapParams.containsKey("DividedAsYouChoose")) {
            abTgt.calculateStillToDivide(mapParams.get("DividedAsYouChoose"), null, null);
            abTgt.setDividedAsYouChoose(true);
        }
        if (mapParams.containsKey("TargetsAtRandom")) {
            abTgt.setRandomTarget(true);
        }
        if (mapParams.containsKey("TargetingPlayer")) {
            abTgt.setMandatory(true);
        }
        return abTgt;
    }

    /**
     * <p>
     * initializeParams.
     * </p>
     * 
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param mapParams
     */
    private static final void initializeParams(final SpellAbility sa, Map<String, String> mapParams) {
        if (mapParams.containsKey("Flashback")) {
            sa.setFlashBackAbility(true);
        }

        if (mapParams.containsKey("NonBasicSpell")) {
            sa.setBasicSpell(false);
        }

        if (mapParams.containsKey("Outlast")) {
            sa.setOutlast(true);
        }
    }

    /**
     * <p>
     * makeRestrictions.
     * </p>
     * 
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param mapParams
     */
    private static final void makeRestrictions(final SpellAbility sa, Map<String, String> mapParams) {
        // SpellAbilityRestrictions should be added in here
        final SpellAbilityRestriction restrict = sa.getRestrictions();
        restrict.setRestrictions(mapParams);
    }

    /**
     * <p>
     * makeConditions.
     * </p>
     * 
     * @param sa
     *            a {@link forge.game.spellability.SpellAbility} object.
     * @param mapParams
     */
    private static final void makeConditions(final SpellAbility sa, Map<String, String> mapParams) {
        // SpellAbilityRestrictions should be added in here
        final SpellAbilityCondition condition = sa.getConditions();
        condition.setConditions(mapParams);
    }

    // Easy creation of SubAbilities
    /**
     * <p>
     * getSubAbility.
     * </p>
     * @param sSub
     * 
     * @return a {@link forge.game.spellability.AbilitySub} object.
     */
    private static final AbilitySub getSubAbility(Card hostCard, String sSub, final SpellAbility parent) {

        if (hostCard.hasSVar(sSub)) {
            return (AbilitySub) AbilityFactory.getAbility(hostCard, sSub, parent);
        }
        System.out.println("SubAbility '"+ sSub +"' not found for: " + hostCard);

        return null;
    }

    public static final Map<String, String> getMapParams(final String abString) {
        return FileSection.parseToMap(abString, "$", "|");
    }

    public static final void adjustChangeZoneTarget(final Map<String, String> params, final SpellAbility sa) {
        if (params.containsKey("Origin")) {
            List<ZoneType> origin = ZoneType.listValueOf(params.get("Origin"));

            final TargetRestrictions tgt = sa.getTargetRestrictions();
        
            // Don't set the zone if it targets a player
            if ((tgt != null) && !tgt.canTgtPlayer()) {
                sa.getTargetRestrictions().setZone(origin);
            }
        }
    
    }

    public static final SpellAbility buildFusedAbility(final Card card) {
        if(!card.isSplitCard()) 
            throw new IllegalStateException("Fuse ability may be built only on split cards");
        
        SpellAbility leftAbility = card.getState(CardStateName.LeftSplit).getFirstAbility();
        Map<String, String> leftMap = Maps.newHashMap(leftAbility.getMapParams());
        AbilityRecordType leftType = AbilityRecordType.getRecordType(leftMap);
        ApiType leftApi = leftType.getApiTypeOf(leftMap);
        leftMap.put("StackDecription", leftMap.get("SpellDescription"));
        leftMap.put("SpellDescription", "Fuse (you may cast both halves of this card from your hand).");
        leftMap.put("ActivationZone", "Hand");

        SpellAbility rightAbility = card.getState(CardStateName.RightSplit).getFirstAbility();
        Map<String, String> rightMap = Maps.newHashMap(rightAbility.getMapParams());

        AbilityRecordType rightType = AbilityRecordType.getRecordType(leftMap);
        ApiType rightApi = leftType.getApiTypeOf(rightMap);
        rightMap.put("StackDecription", rightMap.get("SpellDescription"));
        rightMap.put("SpellDescription", "");

        Cost totalCost = parseAbilityCost(card, leftMap, leftType);
        totalCost.add(parseAbilityCost(card, rightMap, rightType));

        final SpellAbility left = getAbility(leftType, leftApi, leftMap, totalCost, card, null);
        final AbilitySub right = (AbilitySub) getAbility(AbilityRecordType.SubAbility, rightApi, rightMap, null, card, left);
        left.appendSubAbility(right);
        return left;
    }
} // end class AbilityFactory
