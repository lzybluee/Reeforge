package forge.game.replacement;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

import java.util.Map;

/** 
 * TODO: Write javadoc for this type.
 *
 */
public class ReplaceToken extends ReplacementEffect {

    /**
     * 
     * ReplaceProduceMana.
     * @param mapParams &emsp; HashMap<String, String>
     * @param host &emsp; Card
     */
    public ReplaceToken(final Map<String, String> mapParams, final Card host, final boolean intrinsic) {
        super(mapParams, host, intrinsic);
    }

    /* (non-Javadoc)
     * @see forge.card.replacement.ReplacementEffect#canReplace(java.util.Map)
     */
    @Override
    public boolean canReplace(Map<String, Object> runParams) {
        if (!runParams.get("Event").equals("CreateToken") || ((int) runParams.get("TokenNum")) <= 0) {
            return false;
        }

        if (mapParams.containsKey("EffectOnly")) {
            final Boolean effectOnly = (Boolean) runParams.get("EffectOnly");
            if (!effectOnly) {
                return false;
            }
        }

        if (this.getMapParams().containsKey("ValidPlayer")) {
            if (!matchesValid(runParams.get("Affected"), this.getMapParams().get("ValidPlayer").split(","), this.getHostCard())) {
                return false;
            }
        }

        return true;
    }

    /* (non-Javadoc)
     * @see forge.card.replacement.ReplacementEffect#setReplacingObjects(java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    public void setReplacingObjects(Map<String, Object> runParams, SpellAbility sa) {
        sa.setReplacingObject("TokenNum", runParams.get("TokenNum"));
        sa.setReplacingObject("Player", runParams.get("Affected"));
    }

}
