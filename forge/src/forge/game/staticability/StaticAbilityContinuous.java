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
package forge.game.staticability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.card.CardStateName;
import forge.game.card.*;
import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import forge.GameCommand;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GlobalRuleChange;
import forge.game.StaticEffect;
import forge.game.StaticEffects;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.spellability.AbilityActivated;
import forge.game.spellability.AbilityStatic;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerHandler;
import forge.game.zone.ZoneType;

/**
 * The Class StaticAbility_Continuous.
 */
public final class StaticAbilityContinuous {

    // Private constructor to prevent instantiation
    private StaticAbilityContinuous() {
    }

    /**
     * Apply the effects of a static ability that apply in a particular layer.
     * The cards to which the effects are applied are dynamically determined.
     *
     * @param stAb
     *            a {@link StaticAbility}.
     * @param layer
     *            the {@link StaticAbilityLayer} of effects to apply.
     * @return a {@link CardCollectionView} of cards that have been affected.
     * @see #getAffectedCards(StaticAbility)
     * @see #applyContinuousAbility(StaticAbility, CardCollectionView,
     *      StaticAbilityLayer)
     */
	public static CardCollectionView applyContinuousAbility(final StaticAbility stAb, final StaticAbilityLayer layer) {
        final CardCollectionView affectedCards = getAffectedCards(stAb);
        return applyContinuousAbility(stAb, affectedCards, layer);
    }

    /**
     * Apply the effects of a static ability that apply in a particular layer to
     * a predefined set of cards.
     *
     * @param stAb
     *            a {@link StaticAbility}.
     * @param affectedCards
     *            a {@link CardCollectionView} of cards that are to be affected.
     * @param layer
     *            the {@link StaticAbilityLayer} of effects to apply.
     * @return a {@link CardCollectionView} of cards that have been affected,
     *         identical to {@code affectedCards}.
     */
    public static CardCollectionView applyContinuousAbility(final StaticAbility stAb, final CardCollectionView affectedCards, final StaticAbilityLayer layer) {
        final Map<String, String> params = stAb.getMapParams();
        final Card hostCard = stAb.getHostCard();
        final Player controller = hostCard.getController();

        final List<Player> affectedPlayers = StaticAbilityContinuous.getAffectedPlayers(stAb);
        final Game game = hostCard.getGame();

        final StaticEffect se = game.getStaticEffects().getStaticEffect(stAb);
        se.setAffectedCards(affectedCards);
        se.setAffectedPlayers(affectedPlayers);
        se.setParams(params);
        se.setTimestamp(hostCard.getTimestamp());

        String changeColorWordsTo = null;
        Card gainTextSource = null;

        String addP = "";
        int powerBonus = 0;
        String addT = "";
        int toughnessBonus = 0;
        String setP = "";
        int setPower = Integer.MAX_VALUE;
        String setT = "";
        int setToughness = Integer.MAX_VALUE;
        int keywordMultiplier = 1;

        String[] addKeywords = null;
        List<String> addHiddenKeywords = Lists.newArrayList();
        String[] removeKeywords = null;
        String[] addAbilities = null;
        String[] addReplacements = null;
        String[] addSVars = null;
        String[] addTypes = null;
        String[] removeTypes = null;
        String addColors = null;
        String[] addTriggers = null;
        String[] addStatics = null;
        List<SpellAbility> addFullAbs = null;
        boolean removeAllAbilities = false;
        boolean removeSuperTypes = false;
        boolean removeCardTypes = false;
        boolean removeSubTypes = false;
        boolean removeCreatureTypes = false;

        List<Player> mayLookAt = null;

        boolean controllerMayPlay = false, mayPlayWithoutManaCost = false, mayPlayWithFlash = false;
        boolean mayPlayGrantZonePermissions = true;
        Integer mayPlayLimit = null;

        //Global rules changes
        if (layer == StaticAbilityLayer.RULES && params.containsKey("GlobalRule")) {
            final StaticEffects effects = game.getStaticEffects();
            effects.setGlobalRuleChange(GlobalRuleChange.fromString(params.get("GlobalRule")));
        }

        if (layer == StaticAbilityLayer.TEXT && params.containsKey("GainTextOf")) {
            final String valid = params.get("GainTextOf");
            CardCollection allValid = CardLists.getValidCards(game.getCardsInGame(), valid, hostCard.getController(), hostCard, null);
            if (allValid.size() > 1) {
                // TODO: if ever necessary, support gaining text of multiple cards at the same time
                System.err.println("Error: GainTextOf parameter was not defined as a unique card for " + hostCard);
            } else if (allValid.size() == 1) {
                gainTextSource = allValid.get(0);
            } else {
                gainTextSource = null;
            }
        }

        if (layer == StaticAbilityLayer.TEXT && params.containsKey("ChangeColorWordsTo")) {
            changeColorWordsTo = params.get("ChangeColorWordsTo");
        }

        if (layer == StaticAbilityLayer.SETPT &&params.containsKey("SetPower")) {
            setP = params.get("SetPower");
            setPower = AbilityUtils.calculateAmount(hostCard, setP, stAb);
        }

        if (layer == StaticAbilityLayer.SETPT && params.containsKey("SetToughness")) {
            setT = params.get("SetToughness");
            setToughness = AbilityUtils.calculateAmount(hostCard, setT, stAb);
        }

        if (layer == StaticAbilityLayer.MODIFYPT && params.containsKey("AddPower")) {
            addP = params.get("AddPower");
            powerBonus = AbilityUtils.calculateAmount(hostCard, addP, stAb, true);
            if (!StringUtils.isNumeric(addP) && !addP.equals("AffectedX")) {
                se.setXValue(powerBonus);
            }
        }

        if (layer == StaticAbilityLayer.MODIFYPT && params.containsKey("AddToughness")) {
            addT = params.get("AddToughness");
            toughnessBonus = AbilityUtils.calculateAmount(hostCard, addT, stAb, true);
            if (!StringUtils.isNumeric(addT) && !addT.equals("AffectedX")) {
                se.setYValue(toughnessBonus);
            }
        }

        if (params.containsKey("KeywordMultiplier")) {
            final String multiplier = params.get("KeywordMultiplier");
            if (multiplier.equals("X")) {
                keywordMultiplier = AbilityUtils.calculateAmount(hostCard, "X", stAb);
                se.setXValue(keywordMultiplier);
            } else {
                keywordMultiplier = Integer.valueOf(multiplier);
            }
        }

        if (layer == StaticAbilityLayer.ABILITIES2 && params.containsKey("AddKeyword")) {
            addKeywords = params.get("AddKeyword").split(" & ");
            final Iterable<String> chosencolors = hostCard.getChosenColors();
            for (final String color : chosencolors) {
                for (int w = 0; w < addKeywords.length; w++) {
                    addKeywords[w] = addKeywords[w].replaceAll("ChosenColor", StringUtils.capitalize(color));
                }
            }
            final String chosenType = hostCard.getChosenType();
            for (int w = 0; w < addKeywords.length; w++) {
                addKeywords[w] = addKeywords[w].replaceAll("ChosenType", chosenType);
            }
            final String chosenName = hostCard.getNamedCard().replace(",", ";");
            final String hostCardUID = Integer.toString(hostCard.getId()); // Protection with "doesn't remove" effect
            final String hostCardPower = Integer.toString(hostCard.getNetPower());
            for (int w = 0; w < addKeywords.length; w++) {
                if (addKeywords[w].startsWith("Protection:")) {
                    String k = addKeywords[w];
                    k = k.replaceAll("ChosenName", "Card.named" + chosenName);
                    k = k.replace("HostCardUID", hostCardUID);
                    addKeywords[w] = k;
                } else if (addKeywords[w].startsWith("CantBeBlockedBy")) {
                    String k = addKeywords[w];
                    k = k.replaceAll("HostCardPower", hostCardPower);
                    addKeywords[w] = k;
                }
            }
            if (params.containsKey("SharedKeywordsZone")) {
                List<ZoneType> zones = ZoneType.listValueOf(params.get("SharedKeywordsZone"));
                String[] restrictions = params.containsKey("SharedRestrictions") ? params.get("SharedRestrictions").split(",") : new String[] {"Card"};
                List<String> kw = CardFactoryUtil.sharedKeywords(Arrays.asList(addKeywords), restrictions, zones, hostCard);
                addKeywords = kw.toArray(new String[kw.size()]);
            }
        }

        if ((layer == StaticAbilityLayer.RULES || layer == StaticAbilityLayer.ABILITIES1) && params.containsKey("AddHiddenKeyword")) {
            // can't have or gain, need to be applyed in ABILITIES1
            for (String k : params.get("AddHiddenKeyword").split(" & ")) {
                if ( (k.contains("can't have or gain")) == (layer == StaticAbilityLayer.ABILITIES1))
                    addHiddenKeywords.add(k);
            }
        }

        if (layer == StaticAbilityLayer.ABILITIES2 && params.containsKey("RemoveKeyword")) {
            removeKeywords = params.get("RemoveKeyword").split(" & ");
        }

        if (layer == StaticAbilityLayer.ABILITIES1 && params.containsKey("RemoveAllAbilities")) {
            removeAllAbilities = true;
        }

        if (layer == StaticAbilityLayer.ABILITIES2 && params.containsKey("AddAbility")) {
            final String[] sVars = params.get("AddAbility").split(" & ");
            for (int i = 0; i < sVars.length; i++) {
                sVars[i] = hostCard.getSVar(sVars[i]);
            }
            addAbilities = sVars;
        }

        if (layer == StaticAbilityLayer.ABILITIES2 && params.containsKey("AddReplacementEffects")) {
            final String[] sVars = params.get("AddReplacementEffects").split(" & ");
            for (int i = 0; i < sVars.length; i++) {
                sVars[i] = hostCard.getSVar(sVars[i]);
            }
            addReplacements = sVars;
        }

        if (layer == StaticAbilityLayer.ABILITIES2 && params.containsKey("AddSVar")) {
            addSVars = params.get("AddSVar").split(" & ");
        }

        if (layer == StaticAbilityLayer.TYPE && params.containsKey("AddType")) {
            addTypes = params.get("AddType").split(" & ");
            if (addTypes[0].equals("ChosenType")) {
                final String chosenType = hostCard.getChosenType();
                addTypes[0] = chosenType;
                se.setChosenType(chosenType);
            } else if (addTypes[0].equals("ImprintedCreatureType")) {
                if (hostCard.hasImprintedCard()) {
                    final Set<String> imprinted = hostCard.getImprintedCards().getFirst().getType().getCreatureTypes();
                    addTypes = imprinted.toArray(new String[imprinted.size()]);
                }
            }
        }

        if (layer == StaticAbilityLayer.TYPE && params.containsKey("RemoveType")) {
            removeTypes = params.get("RemoveType").split(" & ");
            if (removeTypes[0].equals("ChosenType")) {
                final String chosenType = hostCard.getChosenType();
                removeTypes[0] = chosenType;
                se.setChosenType(chosenType);
            }
        }

        if (layer == StaticAbilityLayer.TYPE) {
            if (params.containsKey("RemoveSuperTypes")) {
                removeSuperTypes = true;
            }

            if (params.containsKey("RemoveCardTypes")) {
                removeCardTypes = true;
            }

            if (params.containsKey("RemoveSubTypes")) {
                removeSubTypes = true;
            }

            if (params.containsKey("RemoveCreatureTypes")) {
                removeCreatureTypes = true;
            }
        }

        if (layer == StaticAbilityLayer.COLOR) {
            if (params.containsKey("AddColor")) {
                final String colors = params.get("AddColor");
                if (colors.equals("ChosenColor")) {
                    addColors = CardUtil.getShortColorsString(hostCard.getChosenColors());
                } else {
                    addColors = CardUtil.getShortColorsString(new ArrayList<String>(Arrays.asList(colors.split(
                            " & "))));
                }
            }

            if (params.containsKey("SetColor")) {
                final String colors = params.get("SetColor");
                if (colors.equals("ChosenColor")) {
                    addColors = CardUtil.getShortColorsString(hostCard.getChosenColors());
                } else {
                    addColors = CardUtil.getShortColorsString(new ArrayList<String>(Arrays.asList(
                            colors.split(" & "))));
                }
                se.setOverwriteColors(true);
            }
        }

        if (layer == StaticAbilityLayer.ABILITIES2) {
            if (params.containsKey("AddTrigger")) {
                final String[] sVars = params.get("AddTrigger").split(" & ");
                for (int i = 0; i < sVars.length; i++) {
                    sVars[i] = hostCard.getSVar(sVars[i]);
                }
                addTriggers = sVars;
            }

            if (params.containsKey("AddStaticAbility")) {
                final String[] sVars = params.get("AddStaticAbility").split(" & ");
                for (int i = 0; i < sVars.length; i++) {
                    sVars[i] = hostCard.getSVar(sVars[i]);
                }
                addStatics = sVars;
            }
        }

        if (layer == StaticAbilityLayer.ABILITIES1 && params.containsKey("GainsAbilitiesOf")) {
            final String[] valids = params.get("GainsAbilitiesOf").split(",");
            List<ZoneType> validZones = new ArrayList<ZoneType>();
            validZones.add(ZoneType.Battlefield);
            if (params.containsKey("GainsAbilitiesOfZones")) {
                validZones.clear();
                for (String s : params.get("GainsAbilitiesOfZones").split(",")) {
                    validZones.add(ZoneType.smartValueOf(s));
                }
            }

            CardCollectionView cardsIGainedAbilitiesFrom = game.getCardsIn(validZones);
            cardsIGainedAbilitiesFrom = CardLists.getValidCards(cardsIGainedAbilitiesFrom, valids, hostCard.getController(), hostCard, null);

            if (cardsIGainedAbilitiesFrom.size() > 0) {
                addFullAbs = new ArrayList<SpellAbility>();

                for (Card c : cardsIGainedAbilitiesFrom) {
                    for (SpellAbility sa : c.getSpellAbilities()) {
                        if (sa instanceof AbilityActivated) {
                            SpellAbility newSA = ((AbilityActivated) sa).getCopy();
                            if (params.containsKey("GainsAbilitiesLimitPerTurn")) {
                                newSA.setRestrictions(sa.getRestrictions());
                                newSA.getRestrictions().setLimitToCheck(params.get("GainsAbilitiesLimitPerTurn"));
                            }
                            newSA.setOriginalHost(c);
                            newSA.setIntrinsic(false);
                            newSA.setTemporary(true);
                            newSA.setHostCard(hostCard);
                            addFullAbs.add(newSA);
                        }
                    }
                }
            }
        }

        if (layer == StaticAbilityLayer.RULES) {
            // These fall under Rule changes, as they don't fit any other category
            if (params.containsKey("MayLookAt")) {
                String look = params.get("MayLookAt");
                if ("True".equals(look)) {
                    look = "You";
                }
                mayLookAt = AbilityUtils.getDefinedPlayers(hostCard, look, null);
            }
            if (params.containsKey("MayPlay")) {
                controllerMayPlay = true;
                if (params.containsKey("MayPlayWithoutManaCost")) {
                    mayPlayWithoutManaCost = true;
                }
                if (params.containsKey("MayPlayWithFlash")) {
                	mayPlayWithFlash = true;
                }
                if (params.containsKey("MayPlayLimit")) {
                    mayPlayLimit = Integer.parseInt(params.get("MayPlayLimit"));
                }
                if (params.containsKey("MayPlayDontGrantZonePermissions")) {
                    mayPlayGrantZonePermissions = false;
                }
            }

            if (params.containsKey("IgnoreEffectCost")) {
                String cost = params.get("IgnoreEffectCost");
                buildIgnorEffectAbility(stAb, cost, affectedPlayers, affectedCards);
            }
        }

        // modify players
        for (final Player p : affectedPlayers) {

            // add keywords
            if (addKeywords != null) {
                for (int i = 0; i < keywordMultiplier; i++) {
                    p.addChangedKeywords(addKeywords, removeKeywords == null ? new String[0] : removeKeywords, se.getTimestamp());
                }
            }

            // add static abilities
            if (addStatics != null) {
                for (String s : addStatics) {
                    StaticAbility stat = p.addStaticAbility(hostCard, s);
                    stat.setTemporary(true);
                    stat.setIntrinsic(false);
                }
            }

            if (layer == StaticAbilityLayer.RULES) {
                if (params.containsKey("SetMaxHandSize")) {
                    String mhs = params.get("SetMaxHandSize");
                    if (mhs.equals("Unlimited")) {
                        p.setUnlimitedHandSize(true);
                    } else {
                        int max = AbilityUtils.calculateAmount(hostCard, mhs, stAb);
                        p.setMaxHandSize(max);
                    }
                }

                if (params.containsKey("RaiseMaxHandSize")) {
                    String rmhs = params.get("RaiseMaxHandSize");
                    int rmax = AbilityUtils.calculateAmount(hostCard, rmhs, stAb);
                    p.setMaxHandSize(p.getMaxHandSize() + rmax);
                }

                if (params.containsKey("ManaColorConversion")) {
                    AbilityUtils.applyManaColorConversion(p, params);
                }
            }
        }

        // start modifying the cards
        for (int i = 0; i < affectedCards.size(); i++) {
            final Card affectedCard = affectedCards.get(i);

            // Gain control
            if (layer == StaticAbilityLayer.CONTROL && params.containsKey("GainControl")) {
                affectedCard.addTempController(hostCard.getController(), hostCard.getTimestamp());
            }

            // Gain text from another card
            if (layer == StaticAbilityLayer.TEXT) {
                // Restore the original text in case it was remembered before
                if (affectedCard.getStates().contains(CardStateName.OriginalText)) {
                    affectedCard.clearTriggersNew();
                    List<SpellAbility> saToRemove = Lists.newArrayList();
                    for (SpellAbility saTemp : affectedCard.getSpellAbilities()) {
                        if (saTemp.isTemporary()) {
                            saToRemove.add(saTemp);
                        }
                    }
                    for (SpellAbility saRem : saToRemove) {
                        affectedCard.removeSpellAbility(saRem);
                    }
                    CardFactory.copyState(affectedCard, CardStateName.OriginalText, affectedCard, CardStateName.Original, false);
                }

                if (gainTextSource != null) {
                    if (!affectedCard.getStates().contains(CardStateName.OriginalText)) {
                        // Remember the original text first in case it hasn't been done yet
                        CardFactory.copyState(affectedCard, CardStateName.Original, affectedCard, CardStateName.OriginalText, false);
                    }

                    CardFactory.copyState(gainTextSource, CardStateName.Original, affectedCard, CardStateName.Original, false);

                    // Do not clone the set code and rarity from the target card
                    affectedCard.getState(CardStateName.Original).setSetCode(affectedCard.getState(CardStateName.OriginalText).getSetCode());
                    affectedCard.getState(CardStateName.Original).setRarity(affectedCard.getState(CardStateName.OriginalText).getRarity());

                    // Enable this in case Volrath's original image is to be used
                    affectedCard.getState(CardStateName.Original).setImageKey(affectedCard.getState(CardStateName.OriginalText).getImageKey());

                    // Activated abilities (statics and repleffects are apparently copied vis copyState?)
                    for (SpellAbility sa : gainTextSource.getSpellAbilities()) {
                        if (sa instanceof AbilityActivated) {
                            SpellAbility newSA = ((AbilityActivated) sa).getCopy();
                            newSA.setOriginalHost(gainTextSource);
                            newSA.setIntrinsic(false);
                            newSA.setTemporary(true);
                            newSA.setHostCard(affectedCard);
                            affectedCard.addSpellAbility(newSA);
                        }
                    }
                    // Triggered abilities
                    for (Trigger t: gainTextSource.getTriggers()) {
                        affectedCard.addTrigger(t.getCopyForHostCard(affectedCard));
                    }

                    // Volrath's Shapeshifter shapeshifting ability needs to be added onto the new text
                    if (params.containsKey("GainedTextHasThisStaticAbility")) {
                        affectedCard.getCurrentState().addStaticAbility(stAb);
                    }
                }
            }

            // Change color words
            if (changeColorWordsTo != null) {
                final byte color;
                if (changeColorWordsTo.equals("ChosenColor")) {
                    if (hostCard.hasChosenColor()) {
                        color = MagicColor.fromName(Iterables.getFirst(hostCard.getChosenColors(), null));
                    } else {
                        color = 0;
                    }
                } else {
                    color = MagicColor.fromName(changeColorWordsTo);
                }

                if (color != 0) {
                    final String colorName = MagicColor.toLongString(color);
                    affectedCard.addChangedTextColorWord("Any", colorName, se.getTimestamp());
                }
            }

            // set P/T
            if (layer == StaticAbilityLayer.SETPT) {
                if (params.containsKey("CharacteristicDefining")) {
                    if (setPower != Integer.MAX_VALUE) {
                        affectedCard.setBasePower(setPower);
                    }
                    if (setToughness != Integer.MAX_VALUE) {
                        affectedCard.setBaseToughness(setToughness);
                    }
                } else if ((setPower != Integer.MAX_VALUE) || (setToughness != Integer.MAX_VALUE)) {
                    // non CharacteristicDefining
                    if (setP.startsWith("AffectedX")) {
                        setPower = CardFactoryUtil.xCount(affectedCard, AbilityUtils.getSVar(stAb, setP));
                    }
                    if (setT.startsWith("AffectedX")) {
                        setToughness = CardFactoryUtil.xCount(affectedCard, AbilityUtils.getSVar(stAb, setT));
                    }
                    affectedCard.addNewPT(setPower, setToughness, hostCard.getTimestamp());
                }
            }

            // add P/T bonus
            if (layer == StaticAbilityLayer.MODIFYPT) {
                if (addP.startsWith("AffectedX")) {
                    powerBonus = CardFactoryUtil.xCount(affectedCard, AbilityUtils.getSVar(stAb, addP));
                    se.addXMapValue(affectedCard, powerBonus);
                }
                if (addT.startsWith("AffectedX")) {
                    toughnessBonus = CardFactoryUtil.xCount(affectedCard, AbilityUtils.getSVar(stAb, addT));
                    se.addXMapValue(affectedCard, toughnessBonus);
                }
                affectedCard.addSemiPermanentPowerBoost(powerBonus, false);
                affectedCard.addSemiPermanentToughnessBoost(toughnessBonus, false);
            }

            // add keywords
            // TODO regular keywords currently don't try to use keyword multiplier
            // (Although nothing uses it at this time)
            if ((addKeywords != null) || (removeKeywords != null) || removeAllAbilities) {
                String[] newKeywords = null;
                if (addKeywords != null) {
                    newKeywords = Arrays.copyOf(addKeywords, addKeywords.length);
                    for (int j = 0; j < newKeywords.length; ++j) {
                    	newKeywords[j] = newKeywords[j].replace("CardManaCost", affectedCard.getManaCost().getShortString());
                    }
                }

                affectedCard.addChangedCardKeywords(newKeywords, removeKeywords, removeAllAbilities,
                        hostCard.getTimestamp());
            }

            // add HIDDEN keywords
            if (!addHiddenKeywords.isEmpty()) {
                for (final String k : addHiddenKeywords) {
                    for (int j = 0; j < keywordMultiplier; j++) {
                        affectedCard.addHiddenExtrinsicKeyword(k);
                    }
                }
            }

            // add SVars
            if (addSVars != null) {
                for (final String sVar : addSVars) {
                    String actualSVar = hostCard.getSVar(sVar);
                    String name = sVar;
                    if (actualSVar.startsWith("SVar:")) {
                        actualSVar = actualSVar.split("SVar:")[1];
                        name = actualSVar.split(":")[0];
                        actualSVar = actualSVar.split(":")[1];
                    }
                    affectedCard.setSVar(name, actualSVar);
                }
            }

            if (addFullAbs != null) {
                for (final SpellAbility ab : addFullAbs) {
                    affectedCard.addSpellAbility(ab);
                }
            }

            // add abilities
            if (addAbilities != null) {
                for (String abilty : addAbilities) {
                    if (abilty.contains("CardManaCost")) {
                        abilty = abilty.replace("CardManaCost", affectedCard.getManaCost().getShortString());
                    } else if (abilty.contains("ConvertedManaCost")) {
                        final String costcmc = Integer.toString(affectedCard.getCMC());
                        abilty = abilty.replace("ConvertedManaCost", costcmc);
                    }
                    if (abilty.startsWith("AB") || abilty.startsWith("ST")) { // grant the ability
                        final SpellAbility sa = AbilityFactory.getAbility(abilty, affectedCard);
                        sa.setTemporary(true);
                        sa.setIntrinsic(false);
                        sa.setOriginalHost(hostCard);
                        affectedCard.addSpellAbility(sa);
                    }
                }
            }

            // add Replacement effects
            if (addReplacements != null) {
                for (String rep : addReplacements) {
                    final ReplacementEffect actualRep = ReplacementHandler.parseReplacement(rep, affectedCard, false);
                    actualRep.setIntrinsic(false);
                    affectedCard.addReplacementEffect(actualRep).setTemporary(true);;
                }
            }
            
            // add Types
            if ((addTypes != null) || (removeTypes != null)) {
                affectedCard.addChangedCardTypes(addTypes, removeTypes, removeSuperTypes, removeCardTypes,
                        removeSubTypes, removeCreatureTypes, hostCard.getTimestamp());
            }

            // add colors
            if (addColors != null) {
                affectedCard.addColor(addColors, !se.isOverwriteColors(), hostCard.getTimestamp());
            }

            // add triggers
            if (addTriggers != null) {
                for (final String trigger : addTriggers) {
                    final Trigger actualTrigger = TriggerHandler.parseTrigger(trigger, affectedCard, false);
                    // if the trigger has Execute param, which most trigger gained by Static Abilties should have
                    // turn them into SpellAbility object before adding to card
                    // with that the TargetedCard does not need the Svars added to them anymore
                    // but only do it if the trigger doesn't already have a overriding ability
                    if (actualTrigger.hasParam("Execute") && actualTrigger.getOverridingAbility() == null) {
                        SpellAbility sa = AbilityFactory.getAbility(hostCard, actualTrigger.getParam("Execute"));
                        // set hostcard there so when the card is added to trigger, it doesn't make a copy of it
                        sa.setHostCard(affectedCard);
                        // set OriginalHost to get the owner of this static ability
                        sa.setOriginalHost(hostCard);
                        // set overriding ability to the trigger
                        actualTrigger.setOverridingAbility(sa);    
                    }
                    actualTrigger.setIntrinsic(false);
                    affectedCard.addTrigger(actualTrigger).setTemporary(true);
                }
            }

            // add static abilities
            if (addStatics != null) {
                for (String s : addStatics) {
                    if (s.contains("ConvertedManaCost")) {
                        final String costcmc = Integer.toString(affectedCard.getCMC());
                        s = s.replace("ConvertedManaCost", costcmc);
                    }

                    StaticAbility stat = affectedCard.addStaticAbility(s);
                    stat.setTemporary(true);
                    stat.setIntrinsic(false);
                }
            }

            // remove triggers
            if ((layer == StaticAbilityLayer.ABILITIES2 && (params.containsKey("RemoveTriggers")) || removeAllAbilities)) {
                for (final Trigger trigger : affectedCard.getTriggers()) {
                    trigger.setTemporarilySuppressed(true);
                }
            }

            // remove activated and static abilities
            if (removeAllAbilities) {
                for (final SpellAbility ab : affectedCard.getSpellAbilities()) {
                    ab.setTemporarilySuppressed(true);
                }
                for (final StaticAbility stA : affectedCard.getStaticAbilities()) {
                    stA.setTemporarilySuppressed(true);
                }
                for (final ReplacementEffect rE : affectedCard.getReplacementEffects()) {
                    rE.setTemporarilySuppressed(true);
                }
            }

            if (mayLookAt != null) {
                for (Player p : mayLookAt) {
                    affectedCard.setMayLookAt(p, true);
                }
            }
            if (controllerMayPlay && (mayPlayLimit == null || hostCard.getMayPlayTurn() < mayPlayLimit)) {
                Player mayPlayController = params.containsKey("MayPlayCardOwner") ? affectedCard.getOwner() : controller;
                affectedCard.setMayPlay(mayPlayController, mayPlayWithoutManaCost, mayPlayWithFlash, mayPlayGrantZonePermissions, stAb);
            }

            affectedCard.updateAbilityTextForView(); // only update keywords and text for view to avoid flickering
        }

        return affectedCards;
    }

    private static void buildIgnorEffectAbility(final StaticAbility stAb, final String costString, final List<Player> players, final CardCollectionView cards) {
        final List<Player> validActivator = new ArrayList<Player>(players);
        for (final Card c : cards) {
            validActivator.add(c.getController());
        }
        final Card sourceCard = stAb.getHostCard();
        Cost cost = new Cost(costString, true);
        final AbilityStatic addIgnore = new AbilityStatic(sourceCard, cost, null) {

            @Override
            public void resolve() {
                stAb.addIgnoreEffectPlayers(this.getActivatingPlayer());
                stAb.setIgnoreEffectCards(cards);
            }

            @Override
            public boolean canPlay() {
                return validActivator.contains(this.getActivatingPlayer())
                        && sourceCard.isInPlay();
            }

        };
        addIgnore.setTemporary(true);
        addIgnore.setIntrinsic(false);
        addIgnore.setApi(ApiType.InternalIgnoreEffect);
        addIgnore.setDescription(cost + " Ignore the effect until end of turn.");
        sourceCard.addSpellAbility(addIgnore);

        final GameCommand removeIgnore = new GameCommand() {
            private static final long serialVersionUID = -5415775215053216360L;
            @Override
            public void run() {
                stAb.clearIgnoreEffects();
            }
        };
        sourceCard.getGame().getEndOfTurn().addUntil(removeIgnore);
        sourceCard.addLeavesPlayCommand(removeIgnore);
    }

    private static List<Player> getAffectedPlayers(final StaticAbility stAb) {
        final Map<String, String> params = stAb.getMapParams();
        final Card hostCard = stAb.getHostCard();
        final Player controller = hostCard.getController();

        final List<Player> players = new ArrayList<Player>();

        if (!params.containsKey("Affected")) {
            return players;
        }

        final String[] strngs = params.get("Affected").split(",");

        for (Player p : controller.getGame().getPlayersInTurnOrder()) {
            if (p.isValid(strngs, controller, hostCard, null)) {
                players.add(p);
            }
        }
        players.removeAll(stAb.getIgnoreEffectPlayers());

        return players;
    }

    private static CardCollectionView getAffectedCards(final StaticAbility stAb) {
        final Map<String, String> params = stAb.getMapParams();
        final Card hostCard = stAb.getHostCard();
        final Game game = hostCard.getGame();
        final Player controller = hostCard.getController();

        if (params.containsKey("CharacteristicDefining")) {
            return new CardCollection(hostCard); // will always be the card itself
        }

        // non - CharacteristicDefining
        CardCollection affectedCards;
        if (params.containsKey("AffectedZone")) {
            affectedCards = new CardCollection(game.getCardsIn(ZoneType.listValueOf(params.get("AffectedZone"))));
        } else {
            affectedCards = new CardCollection(game.getCardsIn(ZoneType.Battlefield));
        }

        if (params.containsKey("Affected") && !params.get("Affected").contains(",")) {
            if (params.get("Affected").contains("Self")) {
                affectedCards = new CardCollection(hostCard);
            } else if (params.get("Affected").contains("EnchantedBy")) {
                affectedCards = new CardCollection(hostCard.getEnchantingCard());
            } else if (params.get("Affected").contains("EquippedBy")) {
                affectedCards = new CardCollection(hostCard.getEquipping());
            } else if (params.get("Affected").equals("EffectSource")) {
                affectedCards = new CardCollection(AbilityUtils.getDefinedCards(hostCard, params.get("Affected"), null));
                return affectedCards;
            }
        }

        if (params.containsKey("Affected")) {
            affectedCards = CardLists.getValidCards(affectedCards, params.get("Affected").split(","), controller, hostCard, null);
        }
        affectedCards.removeAll((List<?>) stAb.getIgnoreEffectCards());
        return affectedCards;
    }
}
