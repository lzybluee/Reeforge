package forge.game.ability.effects;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/** 
 * TODO: Write javadoc for this type.
 *
 */
public class PermanentNoncreatureEffect extends PermanentEffect {

    @Override
    public String getStackDescription(final SpellAbility sa) {
        final Card sourceCard = sa.getHostCard();
        final StringBuilder sb = new StringBuilder();
        sb.append(sourceCard.getName());
        return sb.toString();
    }
}
