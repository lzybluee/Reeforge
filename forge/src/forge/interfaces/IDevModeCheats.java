package forge.interfaces;

public interface IDevModeCheats {

    void setCanPlayUnlimited(boolean canPlayUnlimited0);

    void setViewAllCards(boolean canViewAll);

    void generateMana(boolean empty);

    void dumpGameState();

    void setupGameState();

    void tutorForCard(boolean sideboard);

    void addCountersToPermanent(boolean player);

    void removeCountersFromPermanent(boolean player);

    void tapPermanents(boolean all);

    void untapPermanents(boolean all);

    void setPlayerLife(boolean maxlife);

    void winGame(boolean lose);

    void addCardToHand();

    void addCardToBattlefield();

    void addCardToLibrary();

    void addCardToGraveyard();

    void addCardToExile();

    void castASpell();

    void repeatLastAddition();

    /*
     * Exiles cards from specified player's hand. Will prompt user for player and cards.
     */
    void exileCardsFromHand();

    /*
     * Exiles cards from play. Will prompt user for player and cards.
     */
    void exileCardsFromBattlefield();

    void removeCardsFromGame();


    void riggedPlanarRoll();

    void planeswalkTo();

    /**
     * Implementation of {@link IDevModeCheats} that disallows cheating by
     * performing no action whatsoever when any of its methods is called.
     */
    public static final IDevModeCheats NO_CHEAT = new IDevModeCheats() {
        @Override
        public void winGame(boolean lose) {
        }
        @Override
        public void untapPermanents(boolean all) {
        }
        @Override
        public void tutorForCard(boolean sideboard) {
        }
        @Override
        public void tapPermanents(boolean all) {
        }
        @Override
        public void setupGameState() {
        }
        @Override
        public void setViewAllCards(final boolean canViewAll) {
        }
        @Override
        public void setPlayerLife(boolean maxlife) {
        }
        @Override
        public void setCanPlayUnlimited(final boolean canPlayUnlimited0) {
        }
        @Override
        public void riggedPlanarRoll() {
        }
        @Override
        public void planeswalkTo() {
        }
        @Override
        public void generateMana(boolean empty) {
        }
        @Override
        public void dumpGameState() {
        }
        @Override
        public void addCountersToPermanent(boolean player) {
        }
        @Override
        public void removeCountersFromPermanent(boolean player) {
        }
        @Override
        public void addCardToHand() {
        }
        @Override
        public void exileCardsFromHand() {
        }
        @Override
        public void exileCardsFromBattlefield() {
        }
        @Override
        public void addCardToBattlefield() {
        }
        @Override
        public void addCardToLibrary() {
        }
        @Override
        public void addCardToGraveyard() {
        }
        @Override
        public void addCardToExile() {
        }
        @Override
        public void castASpell() {
        }
        @Override
        public void repeatLastAddition() {
        }
        @Override
        public void removeCardsFromGame() {
        }
    };

}