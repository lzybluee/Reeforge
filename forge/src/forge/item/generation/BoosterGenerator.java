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
package forge.item.generation;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import forge.StaticData;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRulesPredicates;
import forge.card.CardSplitType;
import forge.card.PrintSheet;
import forge.card.CardEdition.FoilType;
import forge.item.IPaperCard;
import forge.item.IPaperCard.Predicates.Presets;
import forge.item.PaperCard;
import forge.item.SealedProduct;
import forge.util.Aggregates;
import forge.util.MyRandom;
import forge.util.TextUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;

/**
 * <p>
 * BoosterGenerator class.
 * </p>
 *
 * @author Forge
 * @version $Id: BoosterGenerator.java 35014 2017-08-13 00:40:48Z Max mtg $
 */
public class BoosterGenerator {


    private final static Map<String, PrintSheet> cachedSheets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static synchronized PrintSheet getPrintSheet(String key) {
        if( !cachedSheets.containsKey(key) )
            cachedSheets.put(key, makeSheet(key, StaticData.instance().getCommonCards().getAllCards()));
        return cachedSheets.get(key);
    }

    private static PaperCard generateFoilCard(PrintSheet sheet) {
        return StaticData.instance().getCommonCards().getFoiled(sheet.random(1, true).get(0));
    }

    private static PaperCard generateFoilCard(List<PaperCard> cardList) {
        Collections.shuffle(cardList);
        return StaticData.instance().getCommonCards().getFoiled(cardList.get(0));
    }

    public static List<PaperCard> getBoosterPack(SealedProduct.Template template) {
        // TODO: tweak the chances of generating Masterpieces to be more authentic
        // (currently merely added to the Rare/Mythic Rare print sheet via ExtraFoilSheetKey)

        List<PaperCard> result = new ArrayList<>();
        List<PrintSheet> sheetsUsed = new ArrayList<>();

        CardEdition edition = StaticData.instance().getEditions().get(template.getEdition());

        boolean hasFoil = edition != null
                && !template.getSlots().isEmpty()
                && MyRandom.getRandom().nextDouble() < edition.getFoilChanceInBooster()
                && edition.getFoilType() != FoilType.NOT_SUPPORTED;
        boolean foilAtEndOfPack = hasFoil && edition.getFoilAlwaysInCommonSlot();

        Random rand = new Random();
        // Foil chances
        // 1 Rare or Mythic rare (distribution ratio same as nonfoils)
        // 2-3 Uncommons
        // 4-6 Commons or Basic Lands
        // 7 Time Shifted
        // 8 VMA Special
        // 9 DFC
        // if result not valid for pack, reroll
        // Other special types of foil slots, add here
        CardRarity foilCard = CardRarity.Unknown;
        while (foilCard == CardRarity.Unknown) {
            int randomNum = rand.nextInt(9) + 1;
            switch (randomNum) {
                case 1:
                    // Rare or Mythic
                    foilCard = CardRarity.Rare;
                    break;
                case 2:
                case 3:
                    // Uncommon
                    foilCard = CardRarity.Uncommon;
                    break;
                case 4:
                case 5:
                case 6:
                    // Common or Basic Land
                    foilCard = CardRarity.Common;
                    break;
                case 7:
                    // Time Spiral
                    if (edition != null) {
                        if (edition.getName().equals("Time Spiral")) {
                            foilCard = CardRarity.Special;
                        }
                    }
                    break;
                case 8:
                    if (edition != null) {
                        if (edition.getName().equals("Vintage Masters")) {
                            // 1 in 53 packs, with 7 possibilities for the slot itself in VMA
                            // (1 RareMythic, 2 Uncommon, 3 Common, 1 Special)
                            if (rand.nextInt(53) <= 7) {
                                foilCard = CardRarity.Special;
                            }
                        }
                    }
                    break;
                case 9:
                    if (edition != null) {
                        // every sixth foil - same as rares
                        if (template.hasSlot("dfc")) {
                            foilCard = CardRarity.Special;
                        }
                    }
                    break;
                // Insert any additional special rarities/slot types for special
                // sets here, for 11 and up
                default:
                    foilCard = CardRarity.Common;
                    // otherwise, default is Common or Basic Land
                    break;
            }
        }

        String extraFoilSheetKey = edition != null ? edition.getAdditionalSheetForFoils() : "";
        boolean replaceCommon = edition != null && !template.getSlots().isEmpty()
                && MyRandom.getRandom().nextDouble() < edition.getChanceReplaceCommonWith();

        String foilSlot = "";

        if (hasFoil) {
            // Default, if no matching slot type is found : equal chance for each slot
            // should not have effect unless new sets that do not match existing
            // rarities are added
            foilSlot = Aggregates.random(template.getSlots()).getLeft().split("[ :!]")[0];

            switch (foilCard) {
                case Rare:
                    // Take whichever rare slot the pack has.
                    // Not the "MYTHIC" slot, no pack has that AFAIK
                    // and chance was for rare/mythic.
                    if (template.hasSlot(BoosterSlots.RARE_MYTHIC)) {
                        foilSlot = BoosterSlots.RARE_MYTHIC;
                    } else if (template.hasSlot(BoosterSlots.RARE)) {
                        foilSlot = BoosterSlots.RARE;
                    } else if (template.hasSlot(BoosterSlots.UNCOMMON_RARE)) {
                        foilSlot = BoosterSlots.UNCOMMON_RARE;
                    }
                    break;
                case Uncommon:
                    if (template.hasSlot(BoosterSlots.UNCOMMON)) {
                        foilSlot = BoosterSlots.UNCOMMON;
                    } else if (template.hasSlot(BoosterSlots.UNCOMMON_RARE)) {
                        foilSlot = BoosterSlots.UNCOMMON_RARE;
                    }
                    break;
                case Common:
                    foilSlot = BoosterSlots.COMMON;
                    if (template.hasSlot(BoosterSlots.BASIC_LAND)) {
                        // According to information I found, Basic Lands
                        // are on the common foil sheet, each type appearing once.
                        // Large Sets usually have 110 commons and 20 lands.
                        if (rand.nextInt(130) <= 20) {
                            foilSlot = BoosterSlots.BASIC_LAND;
                        }
                    }
                    break;
                case Special:
                    if (template.hasSlot(BoosterSlots.TIME_SHIFTED)) {
                        foilSlot = BoosterSlots.TIME_SHIFTED;
                    } else if (template.hasSlot(BoosterSlots.DUAL_FACED_CARD)) {
                        foilSlot = BoosterSlots.DUAL_FACED_CARD;
                    } else if (template.hasSlot(BoosterSlots.SPECIAL)) {
                        foilSlot = BoosterSlots.SPECIAL;
                    }
                    break;
                default:
                    break;
            }
        }

        List<PaperCard> foilCardGeneratedAndHeld = new ArrayList<>();

        for (Pair<String, Integer> slot : template.getSlots()) {
            String slotType = slot.getLeft(); // add expansion symbol here?
            int numCards = slot.getRight();

            String[] sType = TextUtil.splitWithParenthesis(slotType, ' ');
            String setCode = sType.length == 1 && template.getEdition() != null ? template.getEdition() : null;
            String sheetKey = StaticData.instance().getEditions().contains(setCode) ? slotType.trim() + " " + setCode
                    : slotType.trim();

            slotType = slotType.split("[ :!]")[0]; // add expansion symbol here?

            boolean foilInThisSlot = hasFoil && (slotType.equals(foilSlot));

            if ((!foilAtEndOfPack && foilInThisSlot)
                    || (foilAtEndOfPack && hasFoil && slotType.startsWith(BoosterSlots.COMMON))) {
                numCards--;
            }

            if (replaceCommon && slotType.startsWith(BoosterSlots.COMMON)) {
                numCards--;
                String replaceKey = StaticData.instance().getEditions().contains(setCode)
                        ? edition.getSlotReplaceCommonWith().trim() + " " + setCode
                        : edition.getSlotReplaceCommonWith().trim();
                PrintSheet replaceSheet = getPrintSheet(replaceKey);
                result.addAll(replaceSheet.random(1, true));
                sheetsUsed.add(replaceSheet);
                replaceCommon = false;
            }

            PrintSheet ps = getPrintSheet(sheetKey);
            result.addAll(ps.random(numCards, true));
            sheetsUsed.add(ps);

            if (foilInThisSlot) {
                if (!foilAtEndOfPack) {
                    hasFoil = false;
                    if (!extraFoilSheetKey.isEmpty()) {
                        // TODO: extra foil sheets are currently reliably supported
                        // only for boosters with FoilAlwaysInCommonSlot=True.
                        // If FoilAlwaysInCommonSlot is false, a card from the extra
                        // sheet may still replace a card in any slot.
                        List<PaperCard> foilCards = new ArrayList<>();
                        for (PaperCard card : ps.toFlatList()) {
                            if (!foilCards.contains(card)) {
                                foilCards.add(card);
                            }
                        }
                        addCardsFromExtraSheet(foilCards, extraFoilSheetKey);
                        result.add(generateFoilCard(foilCards));
                    } else {
                        result.add(generateFoilCard(ps));
                    }
                } else { // foilAtEndOfPack
                    if (!extraFoilSheetKey.isEmpty()) {
                        // TODO: extra foil sheets are currently reliably supported
                        // only for boosters with FoilAlwaysInCommonSlot=True.
                        // If FoilAlwaysInCommonSlot is false, a card from the extra
                        // sheet may still replace a card in any slot.
                        List<PaperCard> foilCards = new ArrayList<>();
                        for (PaperCard card : ps.toFlatList()) {
                            if (!foilCards.contains(card)) {
                                foilCards.add(card);
                            }
                        }
                        addCardsFromExtraSheet(foilCards, extraFoilSheetKey);
                        foilCardGeneratedAndHeld.add(generateFoilCard(foilCards));
                    } else {
                        if (edition != null) {
                            if (edition.getName().equals("Vintage Masters")) {
                                // Vintage Masters foil slot
                                // If "Special" was picked here, either foil or
                                // nonfoil P9 needs to be generated
                                // 1 out of ~30 normal and mythic rares are foil,
                                // match that.
                                // If not special card, make it always foil.
                                if ((rand.nextInt(30) == 1) || (foilSlot != BoosterSlots.SPECIAL)) {
                                    foilCardGeneratedAndHeld.add(generateFoilCard(ps));
                                } else {
                                    // Otherwise it's not foil (even though this is the
                                    // foil slot!)
                                    result.addAll(ps.random(1, true));
                                }
                            } else {
                                foilCardGeneratedAndHeld.add(generateFoilCard(ps));
                            }
                        }
                    }
                }
            }
        }

        if (hasFoil && foilAtEndOfPack) {
            result.addAll(foilCardGeneratedAndHeld);
        }

        return result;
    }

    public static void addCardsFromExtraSheet(List<PaperCard> dest, String printSheetKey) {
        PrintSheet extraSheet = getPrintSheet(printSheetKey);

        // try to determine the allowed rarity of the cards in dest
        Set<CardRarity> allowedRarity = Sets.newHashSet();
        if (!dest.isEmpty()) {
            for (PaperCard inDest : dest) {
                allowedRarity.add(inDest.getRarity());
            }
        }

        for (PaperCard card : extraSheet.toFlatList()) {
            if (!dest.contains(card) && allowedRarity.contains(card.getRarity())) {
                dest.add(card);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static PrintSheet makeSheet(String sheetKey, Iterable<PaperCard> src) {

        PrintSheet ps = new PrintSheet(sheetKey);
        String[] sKey = TextUtil.splitWithParenthesis(sheetKey, ' ', 2);
        Predicate<PaperCard> setPred = (Predicate<PaperCard>) (sKey.length > 1 ? IPaperCard.Predicates.printedInSets(sKey[1].split(" ")) : Predicates.alwaysTrue());

        List<String> operators = new LinkedList<>(Arrays.asList(TextUtil.splitWithParenthesis(sKey[0], ':')));
        Predicate<PaperCard> extraPred = buildExtraPredicate(operators);

        // source replacement operators - if one is applied setPredicate will be ignored
        Iterator<String> itMod = operators.iterator();
        while(itMod.hasNext()) {

            String mainCode = itMod.next();

            if (mainCode.regionMatches(true, 0, "fromSheet", 0, 9)) { // custom print sheet

                String sheetName = StringUtils.strip(mainCode.substring(9), "()\" ");
                src = StaticData.instance().getPrintSheets().get(sheetName).toFlatList();
                setPred = Predicates.alwaysTrue();

            } else if (mainCode.startsWith("promo") || mainCode.startsWith("name")) { // get exactly the named cards, that's a tiny inlined print sheet

                String list = StringUtils.strip(mainCode.substring(5), "() ");
                String[] cardNames = TextUtil.splitWithParenthesis(list, ',', '"', '"');
                List<PaperCard> srcList = new ArrayList<>();

                for(String cardName: cardNames) {
                    srcList.add(StaticData.instance().getCommonCards().getCard(cardName));
                }

                src = srcList;
                setPred = Predicates.alwaysTrue();

            } else {
                continue;
            }

            itMod.remove();

        }

        // only special operators should remain by now - the ones that could not be turned into one predicate
        String mainCode = operators.isEmpty() ? null : operators.get(0).trim();

        if( null == mainCode || mainCode.equalsIgnoreCase(BoosterSlots.ANY) ) { // no restriction on rarity

            Predicate<PaperCard> predicate = Predicates.and(setPred, extraPred);
            ps.addAll(Iterables.filter(src, predicate));

        } else if ( mainCode.equalsIgnoreCase(BoosterSlots.UNCOMMON_RARE) ) { // for sets like ARN, where U1 cards are considered rare and U3 are uncommon

            Predicate<PaperCard> predicateRares = Predicates.and(setPred, IPaperCard.Predicates.Presets.IS_RARE, extraPred);
            ps.addAll(Iterables.filter(src, predicateRares));

            Predicate<PaperCard> predicateUncommon = Predicates.and( setPred, IPaperCard.Predicates.Presets.IS_UNCOMMON, extraPred);
            ps.addAll(Iterables.filter(src, predicateUncommon), 3);

        } else if ( mainCode.equalsIgnoreCase(BoosterSlots.RARE_MYTHIC) ) {
            // Typical ratio of rares to mythics is 53:15, changing to 35:10 in smaller sets.
            // To achieve the desired 1:8 are all mythics are added once, and all rares added twice per print sheet.

            Predicate<PaperCard> predicateMythic = Predicates.and( setPred, IPaperCard.Predicates.Presets.IS_MYTHIC_RARE, extraPred);
            ps.addAll(Iterables.filter(src, predicateMythic));

            Predicate<PaperCard> predicateRare = Predicates.and( setPred, IPaperCard.Predicates.Presets.IS_RARE, extraPred);
            ps.addAll(Iterables.filter(src, predicateRare), 2);

        } else {
            throw new IllegalArgumentException("Booster generator: operator could not be parsed - " + mainCode);
        }

        return ps;

    }

    /**
     * This method also modifies passed parameter
     */
    private static Predicate<PaperCard> buildExtraPredicate(List<String> operators) {

        List<Predicate<PaperCard>> conditions = new ArrayList<>();

        Iterator<String> itOp = operators.iterator();
        while(itOp.hasNext()) {

            String operator = itOp.next();
            if(StringUtils.isEmpty(operator)) {
                itOp.remove();
                continue;
            }

            if(operator.endsWith("s")) {
                operator = operator.substring(0, operator.length() - 1);
            }

            boolean invert = operator.charAt(0) == '!';
            if (invert) { operator = operator.substring(1); }

            Predicate<PaperCard> toAdd = null;
            if (operator.equalsIgnoreCase(BoosterSlots.DUAL_FACED_CARD)) {
                toAdd = Predicates.compose(Predicates.or(CardRulesPredicates.splitType(CardSplitType.Transform), CardRulesPredicates.splitType(CardSplitType.Meld)),
                        PaperCard.FN_GET_RULES);
            } else if (operator.equalsIgnoreCase(BoosterSlots.LAND)) {          toAdd = Predicates.compose(CardRulesPredicates.Presets.IS_LAND, PaperCard.FN_GET_RULES);
            } else if (operator.equalsIgnoreCase(BoosterSlots.BASIC_LAND)) {    toAdd = IPaperCard.Predicates.Presets.IS_BASIC_LAND;
            } else if (operator.equalsIgnoreCase(BoosterSlots.TIME_SHIFTED)) {  toAdd = IPaperCard.Predicates.Presets.IS_SPECIAL;
            } else if (operator.equalsIgnoreCase(BoosterSlots.SPECIAL)) {       toAdd = IPaperCard.Predicates.Presets.IS_SPECIAL;
            } else if (operator.equalsIgnoreCase(BoosterSlots.MYTHIC)) {        toAdd = IPaperCard.Predicates.Presets.IS_MYTHIC_RARE;
            } else if (operator.equalsIgnoreCase(BoosterSlots.RARE)) {          toAdd = IPaperCard.Predicates.Presets.IS_RARE;
            } else if (operator.equalsIgnoreCase(BoosterSlots.UNCOMMON)) {      toAdd = IPaperCard.Predicates.Presets.IS_UNCOMMON;
            } else if (operator.equalsIgnoreCase(BoosterSlots.COMMON)) {        toAdd = IPaperCard.Predicates.Presets.IS_COMMON;
            } else if (operator.startsWith("name(")) {
                operator = StringUtils.strip(operator.substring(4), "() ");
                String[] cardNames = TextUtil.splitWithParenthesis(operator, ',', '"', '"');
                toAdd = IPaperCard.Predicates.names(Lists.newArrayList(cardNames));
            } else if (operator.startsWith("color(")) {
                operator = StringUtils.strip(operator.substring("color(".length() + 1), "()\" ");
                switch (operator.toLowerCase()) {
                    case "black":
                        toAdd = Presets.IS_BLACK;
                        break;
                    case "blue":
                        toAdd = Presets.IS_BLUE;
                        break;
                    case "green":
                        toAdd = Presets.IS_GREEN;
                        break;
                    case "red":
                        toAdd = Presets.IS_RED;
                        break;
                    case "white":
                        toAdd = Presets.IS_WHITE;
                        break;
                    case "colorless":
                        toAdd = Presets.IS_COLORLESS;
                        break;
                }
            } else if (operator.startsWith("fromSets(")) {
                operator = StringUtils.strip(operator.substring("fromSets(".length() + 1), "()\" ");
                String[] sets = operator.split(",");
                toAdd = IPaperCard.Predicates.printedInSets(sets);
            } else if (operator.startsWith("fromSheet(") && invert) {
                String sheetName = StringUtils.strip(operator.substring(9), "()\" ");
                Iterable<PaperCard> src = StaticData.instance().getPrintSheets().get(sheetName).toFlatList();
                List<String> cardNames = Lists.newArrayList();
                for (PaperCard card : src) {
                    cardNames.add(card.getName());
                }
                toAdd = IPaperCard.Predicates.names(Lists.newArrayList(cardNames));
            }

            if (toAdd == null) {
                continue;
            } else {
                itOp.remove();
            }

            if (invert) {
                toAdd = Predicates.not(toAdd);
            }
            conditions.add(toAdd);

        }

        if (conditions.isEmpty()) {
            return Predicates.alwaysTrue();
        }

        return Predicates.and(conditions);

    }


}
