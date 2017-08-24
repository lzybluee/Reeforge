package forge.achievement;

import forge.GuiBase;
import forge.assets.FSkinProp;
import forge.assets.ISkinImage;
import forge.game.Game;
import forge.game.player.Player;
import forge.item.IPaperCard;
import forge.model.FModel;
import forge.properties.ForgeConstants;

public class PlaneswalkerAchievements extends AchievementCollection {
    public static final PlaneswalkerAchievements instance = new PlaneswalkerAchievements();

    public static ISkinImage getTrophyImage(String planeswalkerName) {
        return GuiBase.getInterface().createLayeredImage(FSkinProp.IMG_SPECIAL_TROPHY, ForgeConstants.CACHE_ACHIEVEMENTS_DIR + "/" + planeswalkerName + ".png", 1);
    }

    private PlaneswalkerAchievements() {
        super("Planeswalker Ultimates", ForgeConstants.ACHIEVEMENTS_DIR + "planeswalkers.xml", false, ForgeConstants.PLANESWALKER_ACHIEVEMENT_LIST_FILE);
    }

    @Override
    protected void addSharedAchivements() {
        //prevent including shared achievements
    }

    protected void add(String cardName0, String displayName0, String flavorText0) {
        add(new PlaneswalkerUltimate(cardName0, displayName0, flavorText0));
    }

    @Override
    public void updateAll(Player player) {
        //only call update achievements for any ultimates activated during the game
        if (player.getOutcome().hasWon()) {
            boolean needSave = false;
            for (String ultimate : player.getAchievementTracker().activatedUltimates) {
                Achievement achievement = achievements.get(ultimate);
                if (achievement != null) {
                    achievement.update(player);
                    needSave = true;
                }
            }
            if (needSave) {
                save();
            }
        }
    }

    private class PlaneswalkerUltimate extends ProgressiveAchievement {
        private PlaneswalkerUltimate(String cardName0, String displayName0, String flavorText0) {
            super(cardName0, displayName0, "Win a game after activating " + cardName0 + "'s ultimate", flavorText0);
        }

        @Override
        protected boolean eval(Player player, Game game) {
            return true; //if this reaches this point, it can be presumed that alternate win condition achieved
        }

        @Override
        public IPaperCard getPaperCard() {
            return FModel.getMagicDb().getCommonCards().getCard(getKey());
        }

        @Override
        protected String getNoun() {
            return "Win";
        }
    }
}
