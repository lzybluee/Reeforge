package forge.achievement;

import forge.game.Game;
import forge.game.GameType;
import forge.game.player.Player;

public class ArcaneMaster extends Achievement {
    public ArcaneMaster() {
        super("ArcaneMaster", "Arcane Master", "Win a game without casting", Integer.MAX_VALUE,
            "more than 3 spells", 3,
            "more than 2 spells", 2,
            "more than 1 spell", 1,
            "any spells", 0);
    }

    @Override
    protected int evaluate(Player player, Game game) {
        if (game.getRules().hasAppliedVariant(GameType.MomirBasic) || game.getRules().hasAppliedVariant(GameType.MoJhoSto)) {
            return defaultValue; // Momir Basic is exempt from this achievement (custom rules do not require any spellcasting by default)
        }
        if (player.getOutcome().hasWon()) {
            return player.getAchievementTracker().spellsCast;
        }
        return defaultValue; //indicate that player didn't win
    }

    @Override
    protected String getNoun() {
        return "Spell";
    }
}
