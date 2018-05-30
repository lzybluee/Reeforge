package forge.game;

import com.google.common.base.Function;
import forge.StaticData;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.game.player.RegisteredPlayer;

public enum GameType {
    Sealed          (DeckFormat.Limited, true, true, true, "Sealed", ""),
    Draft           (DeckFormat.Limited, true, true, true, "Draft", ""),
    Winston         (DeckFormat.Limited, true, true, true, "Winston", ""),
    Gauntlet        (DeckFormat.Constructed, false, true, true, "Gauntlet", ""),
    Tournament      (DeckFormat.Constructed, false, true, true, "Tournament", ""),
    Quest           (DeckFormat.QuestDeck, true, true, false, "Quest", ""),
    QuestDraft      (DeckFormat.Limited, true, true, true, "Quest Draft", ""),
    PlanarConquest  (DeckFormat.PlanarConquest, true, false, false, "Planar Conquest", ""),
    Puzzle          (DeckFormat.Puzzle, false, false, false, "Puzzle", "Solve a puzzle from the given game state"),
    Constructed     (DeckFormat.Constructed, false, true, true, "Constructed", ""),
    DeckManager     (DeckFormat.Constructed, false, true, true, "Deck Manager", ""),
    Vanguard        (DeckFormat.Vanguard, true, true, true, "Vanguard", "Each player has a special \"Avatar\" card that affects the game."),
    Commander       (DeckFormat.Commander, false, false, false, "Commander", "Each player has a legendary \"General\" card which can be cast at any time and determines deck colors."),
    TinyLeaders     (DeckFormat.TinyLeaders, false, false, false, "Tiny Leaders", "Each player has a legendary \"General\" card which can be cast at any time and determines deck colors. Each card must have CMC less than 4."),
    Brawl           (DeckFormat.Brawl, false, false, false, "Brawl", "Each player has a legendary \"General\" card which can be cast at any time and determines deck colors. Only cards legal in Standard may be used."),
    Planeswalker    (DeckFormat.PlanarConquest, false, false, true, "Planeswalker", "Each player has a Planeswalker card which can be cast at any time."),
    Planechase      (DeckFormat.Planechase, false, false, true, "Planechase", "Plane cards apply global effects. The Plane card changes when a player rolls \"Planeswalk\" on the planar die."),
    Archenemy       (DeckFormat.Archenemy, false, false, true, "Archenemy", "One player is the Archenemy and fights the other players by playing Scheme cards."),
    ArchenemyRumble (DeckFormat.Archenemy, false, false, true, "Archenemy Rumble", "All players are Archenemies and can play Scheme cards."),
    MomirBasic      (DeckFormat.Constructed, false, false, false, "Momir Basic", "Each player has a deck containing 60 basic lands and the Momir Vig avatar.", new Function<RegisteredPlayer, Deck>() {
        @Override
        public Deck apply(RegisteredPlayer player) {
            Deck deck = new Deck();
            CardPool mainDeck = deck.getMain();
            mainDeck.add("Plains", 12);
            mainDeck.add("Island", 12);
            mainDeck.add("Swamp", 12);
            mainDeck.add("Mountain", 12);
            mainDeck.add("Forest", 12);
            deck.getOrCreate(DeckSection.Avatar).add(StaticData.instance().getVariantCards()
                    .getCard("Momir Vig, Simic Visionary Avatar"), 1);
            return deck;
        }
    }),
    MoJhoSto      (DeckFormat.Constructed, false, false, false, "MoJhoSto", "Each player has a deck containing 60 basic lands and the Momir Vig, Jhoira of the Ghitu, and Stonehewer Giant avatars.", new Function<RegisteredPlayer, Deck>() {
        @Override
        public Deck apply(RegisteredPlayer player) {
            Deck deck = new Deck();
            CardPool mainDeck = deck.getMain();
            mainDeck.add("Plains", 12);
            mainDeck.add("Island", 12);
            mainDeck.add("Swamp", 12);
            mainDeck.add("Mountain", 12);
            mainDeck.add("Forest", 12);
            deck.getOrCreate(DeckSection.Avatar).add(StaticData.instance().getVariantCards()
                    .getCard("Momir Vig, Simic Visionary Avatar"), 1);
            deck.getOrCreate(DeckSection.Avatar).add(StaticData.instance().getVariantCards()
                    .getCard("Jhoira of the Ghitu Avatar"), 1);
            deck.getOrCreate(DeckSection.Avatar).add(StaticData.instance().getVariantCards()
                    .getCard("Stonehewer Giant Avatar"), 1);
            return deck;
        }
    });

    private final DeckFormat deckFormat;
    private final boolean isCardPoolLimited, canSideboard, addWonCardsMidGame;
    private final String name, description;
    private final Function<RegisteredPlayer, Deck> deckAutoGenerator;

    private GameType(DeckFormat deckFormat0, boolean isCardPoolLimited0, boolean canSideboard0, boolean addWonCardsMidgame0, String name0, String description0) {
        this(deckFormat0, isCardPoolLimited0, canSideboard0, addWonCardsMidgame0, name0, description0, null);
    }
    private GameType(DeckFormat deckFormat0, boolean isCardPoolLimited0, boolean canSideboard0, boolean addWonCardsMidgame0, String name0, String description0, Function<RegisteredPlayer, Deck> deckAutoGenerator0) {
        deckFormat = deckFormat0;
        isCardPoolLimited = isCardPoolLimited0;
        canSideboard = canSideboard0;
        addWonCardsMidGame = addWonCardsMidgame0;
        name = name0;
        description = description0;
        deckAutoGenerator = deckAutoGenerator0;
    }

    /**
     * @return the decksFormat
     */
    public DeckFormat getDeckFormat() {
        return deckFormat;
    }

    public boolean isAutoGenerated() {
        return deckAutoGenerator != null;
    }

    public Deck autoGenerateDeck(RegisteredPlayer player) {
        return deckAutoGenerator.apply(player);
    }

    /**
     * @return the isCardpoolLimited
     */
    public boolean isCardPoolLimited() {
        return isCardPoolLimited;
    }

    /**
     * @return the canSideboard
     */
    public boolean isSideboardingAllowed() {
        return canSideboard;
    }

    public boolean canAddWonCardsMidGame() {
        return addWonCardsMidGame;
    }

    public boolean isCommandZoneNeeded() {
    	return true; //TODO: Figure out way to move command zone into field so it can be hidden when empty
        /*switch (this) {
        case Archenemy:
        case Commander:
        case TinyLeaders:
        case Planechase:
        case Vanguard:
            return true;
        default:
            return false;
        }*/
    }

    public String toString() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}
