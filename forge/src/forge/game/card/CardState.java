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
package forge.game.card;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import forge.card.*;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostParser;
import forge.game.ForgeScript;
import forge.game.GameObject;
import forge.game.card.CardView.CardStateView;
import forge.game.keyword.KeywordCollection;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityPredicates;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class CardState extends GameObject {
    private String name = "";
    private CardType type = new CardType();
    private ManaCost manaCost = ManaCost.NO_COST;
    private byte color = MagicColor.COLORLESS;
    private int basePower = 0;
    private int baseToughness = 0;
    private KeywordCollection intrinsicKeywords = new KeywordCollection();

    private final FCollection<SpellAbility> nonManaAbilities = new FCollection<SpellAbility>();
    private final FCollection<SpellAbility> manaAbilities = new FCollection<SpellAbility>();
    private FCollection<Trigger> triggers = new FCollection<Trigger>();
    private FCollection<ReplacementEffect> replacementEffects = new FCollection<ReplacementEffect>();
    private FCollection<StaticAbility> staticAbilities = new FCollection<StaticAbility>();
    private String imageKey = "";
    private Map<String, String> sVars = Maps.newTreeMap();

    private List<KeywordInterface> cachedKeywords = Lists.newArrayList();
    
    private CardRarity rarity = CardRarity.Unknown;
    private String setCode = CardEdition.UNKNOWN.getCode();

    private final CardStateView view;
    private final Card card;

    public CardState(CardStateView view0, Card card0) {
        view = view0;
        card = card0;
        view.updateRarity(this);
        view.updateSetCode(this);
    }

    public CardStateView getView() {
        return view;
    }

    public Card getCard() {
        return card;
    }

    public final String getName() {
        return name;
    }
    public final void setName(final String name0) {
        name = name0;
        view.updateName(this);
    }

    @Override
    public String toString() {
        return name + " (" + view.getState() + ")";
    }

    public final CardTypeView getType() {
        return type;
    }
    public final void addType(String type0) {
        if (type.add(type0)) {
            view.updateType(this);
        }
    }
    public final void setType(final CardType type0) {
        if (type0 == type) {
            // Logic below would incorrectly clear the type if it's the same object.
            return;
        }
        if (type0.isEmpty() && type.isEmpty()) { return; }
        type.clear();
        type.addAll(type0);
        view.updateType(this);
    }

    public final ManaCost getManaCost() {
        return manaCost;
    }
    public final void setManaCost(final ManaCost manaCost0) {
        manaCost = manaCost0;
        view.updateManaCost(this);
    }

    public final byte getColor() {
        return color;
    }
    public final void setColor(final String color) {
        final ManaCostParser parser = new ManaCostParser(color);
        final ManaCost cost = new ManaCost(parser);
        setColor(cost.getColorProfile());
    }
    public final void setColor(final byte color) {
        this.color = color;
        view.updateColors(card);
    }

    public final int getBasePower() {
        return basePower;
    }
    public final void setBasePower(final int basePower0) {
        if (basePower == basePower0) { return; }
        basePower = basePower0;
        view.updatePower(this);
    }

    public final int getBaseToughness() {
        return baseToughness;
    }
    public final void setBaseToughness(final int baseToughness0) {
        if (baseToughness == baseToughness0) { return; }
        baseToughness = baseToughness0;
        view.updateToughness(this);
    }

    public final Collection<KeywordInterface> getCachedKeywords() {
        return cachedKeywords;
    }

    public final void setCachedKeywords(final Collection<KeywordInterface> col) {
        cachedKeywords.clear();
        cachedKeywords.addAll(col);
    }

    public final Collection<KeywordInterface> getIntrinsicKeywords() {
        return intrinsicKeywords.getValues();
    }
    public final boolean hasIntrinsicKeyword(String k) {
        return intrinsicKeywords.contains(k);
    }
    public final void setIntrinsicKeywords(final Iterable<KeywordInterface> intrinsicKeyword0, final boolean lki) {
        intrinsicKeywords.clear();
        for (KeywordInterface k : intrinsicKeyword0) {
            intrinsicKeywords.insert(k.copy(card, lki));
        }
        card.updateKeywordsCache(this);
    }

    public final KeywordInterface addIntrinsicKeyword(final String s, boolean initTraits) {
        if (s.trim().length() == 0) {
            return null;
        }
        KeywordInterface inst = intrinsicKeywords.add(s); 
        if (inst != null && initTraits) {
            inst.createTraits(card, true);
        }
        return inst;
    }
    public final boolean addIntrinsicKeywords(final Iterable<String> keywords) {
        return addIntrinsicKeywords(keywords, true);
    }
    public final boolean addIntrinsicKeywords(final Iterable<String> keywords, boolean initTraits) {
        boolean changed = false;
        for (String k : keywords) {
            if (addIntrinsicKeyword(k, initTraits) != null) {
                changed = true;
            }
        }
        return changed;
    }

    public final boolean removeIntrinsicKeyword(final String s) {
        return intrinsicKeywords.remove(s);
    }

    public final FCollectionView<SpellAbility> getSpellAbilities() {
        FCollection<SpellAbility> newCol = new FCollection<SpellAbility>(manaAbilities);
        newCol.addAll(nonManaAbilities);
        card.updateSpellAbilities(newCol, this, null);
        return newCol;
    }
    public final FCollectionView<SpellAbility> getManaAbilities() {
        FCollection<SpellAbility> newCol = new FCollection<SpellAbility>(manaAbilities);
        card.updateSpellAbilities(newCol, this, true);
        return newCol;
    }
    public final FCollectionView<SpellAbility> getNonManaAbilities() {
        FCollection<SpellAbility> newCol = new FCollection<SpellAbility>(nonManaAbilities);
        card.updateSpellAbilities(newCol, this, false);
        return newCol;
    }

    public final FCollectionView<SpellAbility> getIntrinsicSpellAbilities() {
        return new FCollection<SpellAbility>(Iterables.filter(getSpellAbilities(), SpellAbilityPredicates.isIntrinsic()));
    }

    public final boolean hasSpellAbility(final SpellAbility sa) {
        return getSpellAbilities().contains(sa);
    }

    public final boolean hasSpellAbility(final int id) {
        for (SpellAbility sa : getSpellAbilities()) {
            if (id == sa.getId()) {
                return true;
            }
        }
        return false;
    }

    public final void setNonManaAbilities(SpellAbility sa) {
    	nonManaAbilities.clear();
    	if (sa != null) {
    	    nonManaAbilities.add(sa);
    	}
    }

    public final boolean addSpellAbility(final SpellAbility a) {
        if (a.isManaAbility()) {
            return manaAbilities.add(a);
        }
        return nonManaAbilities.add(a);
    }
    public final boolean removeSpellAbility(final SpellAbility a) {
        if (a.isManaAbility()) {
            // if (!a.isExtrinsic()) { return false; } //never remove intrinsic mana abilities, is this the way to go??
            return manaAbilities.remove(a);
        }
        return nonManaAbilities.remove(a);
    }
    public final boolean addManaAbility(final SpellAbility a) {
        return manaAbilities.add(a);
    }
    public final boolean addManaAbilities(final Iterable<SpellAbility> a) {
        return manaAbilities.addAll(a);
    }
    public final boolean removeManaAbility(final SpellAbility a) {
        return manaAbilities.remove(a);
    }
    public final boolean addNonManaAbility(final SpellAbility a) {
        return nonManaAbilities.add(a);
    }
    public final boolean addNonManaAbilities(final Iterable<SpellAbility> a) {
        return nonManaAbilities.addAll(a);
    }
    public final boolean removeNonManaAbility(final SpellAbility a) {
        return nonManaAbilities.remove(a);
    }

    public final void clearFirstSpell() {
        for (int i = 0; i < nonManaAbilities.size(); i++) {
            if (nonManaAbilities.get(i).isSpell()) {
                nonManaAbilities.remove(i);
                return;
            }
        }
    }

    public final SpellAbility getFirstAbility() {
        return Iterables.getFirst(getIntrinsicSpellAbilities(), null);
    }

    public final FCollectionView<Trigger> getTriggers() {
        FCollection<Trigger> result = new FCollection<>(triggers);
        card.updateTriggers(result, this);
        return result;
    }

    public final boolean hasTrigger(final Trigger t) {
        return getTriggers().contains(t);
    }

    public final boolean hasTrigger(final int id) {
        for (final Trigger t : getTriggers()) {
            if (id == t.getId()) {
                return true;
            }
        }
        return false;
    }

    public final void setTriggers(final FCollection<Trigger> triggers0) {
        triggers = triggers0;
    }
    public final boolean addTrigger(final Trigger t) {
        return triggers.add(t);
    }
    public final boolean removeTrigger(final Trigger t) {
        return triggers.remove(t);
    }
    public final void clearTriggers() {
        triggers.clear();
    }

    public final FCollectionView<StaticAbility> getStaticAbilities() {
        FCollection<StaticAbility> result = new FCollection<>(staticAbilities);
        card.updateStaticAbilities(result, this);
        return result;
    }
    public final boolean addStaticAbility(StaticAbility stab) {
        return staticAbilities.add(stab);
    }
    public final boolean removeStaticAbility(StaticAbility stab) {
        return staticAbilities.remove(stab);
    }
    public final void setStaticAbilities(final Iterable<StaticAbility> staticAbilities0) {
        staticAbilities = new FCollection<StaticAbility>(staticAbilities0);
    }
    public final void clearStaticAbilities() {
        staticAbilities.clear();
    }

    public final String getImageKey() {
        return imageKey;
    }
    public final void setImageKey(final String imageFilename0) {
        imageKey = imageFilename0;
        view.updateImageKey(this);
    }

    public FCollectionView<ReplacementEffect> getReplacementEffects() {
        FCollection<ReplacementEffect> result = new FCollection<>(replacementEffects);
        card.updateReplacementEffects(result, this);
        return result;
    }
    public boolean addReplacementEffect(final ReplacementEffect replacementEffect) {
        return replacementEffects.add(replacementEffect);
    }
    public boolean removeReplacementEffect(final ReplacementEffect replacementEffect) {
        return replacementEffects.remove(replacementEffect);
    }
    public void clearReplacementEffects() {
        replacementEffects.clear();
    }

    public final boolean hasReplacementEffect(final ReplacementEffect re) {
        return getReplacementEffects().contains(re);
    }

    public final boolean hasReplacementEffect(final int id) {
        for (final ReplacementEffect r : getReplacementEffects()) {
            if (id == r.getId()) {
                return true;
            }
        }
        return false;
    }

    public final ReplacementEffect getReplacementEffect(final int id) {
        for (final ReplacementEffect r : getReplacementEffects()) {
            if (id == r.getId()) {
                return r;
            }
        }
        return null;
    }

    public final Map<String, String> getSVars() {
        return sVars;
    }
    public final String getSVar(final String var) {
        if (sVars.containsKey(var)) {
            return sVars.get(var);
        } else {
            return "";
        }
    }
    public final boolean hasSVar(final String var) {
        if (var == null) {
            return false;
        }
        return sVars.containsKey(var);
    }
    public final void setSVar(final String var, final String str) {
        sVars.put(var, str);
        view.updateFoilIndex(card.getState(CardStateName.Original));
    }
    public final void setSVars(final Map<String, String> newSVars) {
        sVars = Maps.newTreeMap();
        sVars.putAll(newSVars);
        view.updateFoilIndex(card.getState(CardStateName.Original));
    }
    public final void removeSVar(final String var) {
        if (sVars.containsKey(var)) {
            sVars.remove(var);
        }
    }

    public final int getFoil() {
        final String foil = getSVar("Foil");
        if (!foil.isEmpty()) {
            return Integer.parseInt(foil);
        }
        return 0;
    }

    public final void copyFrom(final CardState source, final boolean lki) {
        // Makes a "deeper" copy of a CardState object
        setName(source.getName());
        setType(source.type);
        setManaCost(source.getManaCost());
        setColor(source.getColor());
        setBasePower(source.getBasePower());
        setBaseToughness(source.getBaseToughness());
        setSVars(source.getSVars());

        manaAbilities.clear();
        for (SpellAbility sa : source.manaAbilities) {
            if (sa.isIntrinsic()) {
                manaAbilities.add(sa.copy(card, lki));
            }
        }

        nonManaAbilities.clear();
        for (SpellAbility sa : source.nonManaAbilities) {
            if (sa.isIntrinsic()) {
                nonManaAbilities.add(sa.copy(card, lki));
            }
        }

        setIntrinsicKeywords(source.intrinsicKeywords.getValues(), lki);
        setImageKey(source.getImageKey());
        setRarity(source.rarity);
        setSetCode(source.setCode);

        triggers.clear();
        for (Trigger tr : source.triggers) {
            if (tr.isIntrinsic()) {
                triggers.add(tr.copy(card, lki));
            }
        }

        replacementEffects.clear();
        for (ReplacementEffect re : source.replacementEffects) {
            if (re.isIntrinsic()) {
                replacementEffects.add(re.copy(card, lki));
            }
        }

        staticAbilities.clear();
        for (StaticAbility sa : source.staticAbilities) {
            if (sa.isIntrinsic()) {
                staticAbilities.add(new StaticAbility(sa, this.card));
            }
        }
    }

    
    public CardRarity getRarity() {
        return rarity;
    }
    public void setRarity(CardRarity rarity0) {
        rarity = rarity0;
        view.updateRarity(this);
    }

    public String getSetCode() {
        return setCode;
    }
    
    public CardTypeView getTypeWithChanges() {
        return getType().getTypeWithChanges(card.getChangedCardTypes());
    }
    
    public void setSetCode(String setCode0) {
        setCode = setCode0;
        view.updateSetCode(this);
    }

    /* (non-Javadoc)
     * @see forge.game.GameObject#hasProperty(java.lang.String, forge.game.player.Player, forge.game.card.Card, forge.game.spellability.SpellAbility)
     */
    @Override
    public boolean hasProperty(String property, Player sourceController, Card source, SpellAbility spellAbility) {
        return ForgeScript.cardStateHasProperty(this, property, sourceController, source, spellAbility);
    }

    public void addIntrinsicKeywords(Collection<KeywordInterface> intrinsicKeywords2) {
        for (KeywordInterface inst : intrinsicKeywords2) {
            intrinsicKeywords.insert(inst);
        }
    }
    
    
}
