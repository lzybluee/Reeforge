package forge.gauntlet;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import forge.deck.Deck;
import forge.deck.DeckType;
import forge.deck.DeckgenUtil;
import forge.model.FModel;

public class GauntletUtil {
    public static GauntletData createQuickGauntlet(final Deck userDeck, final int numOpponents, final List<DeckType> allowedDeckTypes) {
        GauntletData gauntlet = new GauntletData();
        setDefaultGauntletName(gauntlet, GauntletIO.PREFIX_QUICK);
        FModel.setGauntletData(gauntlet);

        // Generate gauntlet decks
        Deck deck;
        final List<String> eventNames = new ArrayList<String>();
        final List<Deck> decks = new ArrayList<Deck>();

        for (int i = 0; i < numOpponents; i++) {
            int randType = (int)Math.floor(Math.random() * allowedDeckTypes.size());
            switch (allowedDeckTypes.get(randType)) {
            case COLOR_DECK:
                deck = DeckgenUtil.getRandomColorDeck(true);
                eventNames.add("Random colors deck");
                break;
            case STANDARD_COLOR_DECK:
                deck = DeckgenUtil.getRandomColorDeck(FModel.getFormats().getStandard().getFilterPrinted(),true);
                eventNames.add(deck.getName());
                break;
            case STANDARD_CARDGEN_DECK:
                deck = DeckgenUtil.buildCardGenDeck(FModel.getFormats().getStandard(),true);
                eventNames.add(deck.getName());
                break;
            case MODERN_CARDGEN_DECK:
                deck = DeckgenUtil.buildCardGenDeck(FModel.getFormats().getModern(),true);
                eventNames.add(deck.getName());
                break;
            case MODERN_COLOR_DECK:
                deck = DeckgenUtil.getRandomColorDeck(FModel.getFormats().getModern().getFilterPrinted(),true);
                eventNames.add(deck.getName());
                break;
            case CUSTOM_DECK:
                deck = DeckgenUtil.getRandomCustomDeck();
                if(deck != null) {
                    eventNames.add(deck.getName());
                }
                break;
            case PRECONSTRUCTED_DECK:
                deck = DeckgenUtil.getRandomPreconDeck();
                eventNames.add(deck.getName());
                break;
            case QUEST_OPPONENT_DECK:
                deck = DeckgenUtil.getRandomQuestDeck();
                eventNames.add(deck.getName());
                break;
            case THEME_DECK:
                deck = DeckgenUtil.getRandomThemeDeck();
                eventNames.add(deck.getName());
                break;
            default:
                continue;
            }
            if(deck != null)
                decks.add(deck);
        }

        gauntlet.setDecks(decks);
        gauntlet.setEventNames(eventNames);
        gauntlet.setUserDeck(userDeck);

        // Reset all variable fields to 0, stamps and saves automatically.
        gauntlet.reset();
        return gauntlet;
    }

    public static void setDefaultGauntletName(GauntletData gauntlet, String prefix) {
        final File[] arrFiles = GauntletIO.getGauntletFilesUnlocked(prefix);
        final Set<String> setNames = new HashSet<String>();
        for (File f : arrFiles) {
            setNames.add(f.getName());
        }

        int num = 1;
        while (setNames.contains(prefix + num + GauntletIO.SUFFIX_DATA)) {
            num++;
        }
        gauntlet.setName(prefix + num);
    }
}
